package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.AutosteerStatus
import eu.smartsteer.navigator.core.hw.HardwareSession
import eu.smartsteer.navigator.core.net.UdpLink
import eu.smartsteer.navigator.core.net.createUdpLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * Tryb Sprzęt: sieć UDP + [HardwareSession]. Pakiety z wątku sieci trafiają do kolejki,
 * a całą logikę wykonujemy w jednym miejscu (wątek UI), więc nie ma wyścigów.
 */
class HardwareController {
    var hardware by mutableStateOf(false)
        private set
    val session = HardwareSession()
    /** Rośnie co 100 ms w trybie Sprzęt — ekrany go czytają, żeby się odświeżać. */
    var tick by mutableStateOf(0L)
        private set
    var linkError by mutableStateOf<String?>(null)
        private set
    var sendError by mutableStateOf<String?>(null)
        private set
    var rxCount = 0L; private set
    var txCount = 0L; private set
    /** Ostatnie odebrane wiadomości (najnowsza na górze) do podglądu na ekranie Połączenia. */
    val log = ArrayDeque<String>()

    private class Packet(val bytes: ByteArray, val from: String)
    private val inbox = Channel<Packet>(512, BufferOverflow.DROP_OLDEST)
    private var link: UdpLink? = null
    private val clock = TimeSource.Monotonic.markNow()

    fun now(): Double = clock.elapsedNow().inWholeMilliseconds / 1000.0

    private var stopRequested = false

    fun setHardwareMode(on: Boolean) {
        if (on == hardware) return
        if (on) {
            if (link == null) {
                val l = createUdpLink()
                linkError = l.start { b, n, from -> inbox.trySend(Packet(b.copyOf(n), from)) }
                link = if (linkError == null) l else null
            }
            stopRequested = false
        } else {
            session.setEngaged(false, now())
            stopRequested = true // pętla wyśle ostatnią ramkę „autoster wyłączony” i zamknie gniazdo
        }
        hardware = on
        tick++
    }

    /** Pętla: odbiór pakietów i wysyłka PGN 254 co 100 ms (tylko w trybie Sprzęt). */
    suspend fun run() = coroutineScope {
        launch {
            for (p in inbox) {
                if (!hardware) continue
                rxCount++
                session.onDatagram(p.bytes, p.bytes.size, now())
                addLog(describe(p))
            }
        }
        while (true) {
            delay(100)
            val l = link
            if (stopRequested && l != null) {
                val off = session.steerFrame()
                withContext(Dispatchers.Default) { l.send(off); l.stop() }
                link = null
                linkError = null
                stopRequested = false
            }
            if (!hardware) continue
            session.update(now())
            val frame = session.steerFrame()
            if (l != null) {
                sendError = withContext(Dispatchers.Default) { l.send(frame) }
                if (sendError == null) txCount++
            }
            tick++
        }
    }

    /** Odświeżenie ekranu po akcji z UI. */
    fun touch() { tick++ }

    private fun describe(p: Packet): String {
        val b = p.bytes
        if (b.isNotEmpty() && b[0] == '$'.code.toByte()) return b.decodeToString().trim().lineSequence().joinToString("  ") { it.take(60) }
        val f = AogFrame.parse(b) ?: return "Nieznany pakiet (${b.size} B) z ${p.from}"
        val st = AutosteerStatus.from(f)
        return if (st != null) {
            "PGN 253 z ${p.from} · kąt kół ${st.actualAngleDeg.pl(1, plusSign = true)}° · " +
                "przełącznik ${if (st.steerSwitch) "WŁ." else "WYŁ."} · PWM ${st.pwm}"
        } else {
            "PGN ${f.pgn} z ${p.from} · ${f.data.size} B"
        }
    }

    private fun addLog(s: String) {
        log.addFirst(s)
        while (log.size > 14) log.removeLast()
    }
}
