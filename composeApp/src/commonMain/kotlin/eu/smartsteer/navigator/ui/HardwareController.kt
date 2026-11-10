package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.ModuleScan
import eu.smartsteer.navigator.core.aog.AutosteerStatus
import eu.smartsteer.navigator.core.hw.HardwareSession
import eu.smartsteer.navigator.core.net.UdpLink
import eu.smartsteer.navigator.core.net.createUdpLink
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.net.TcpClient
import eu.smartsteer.navigator.core.net.createTcpClient
import eu.smartsteer.navigator.core.ntrip.Ntrip
import eu.smartsteer.navigator.core.ntrip.NtripConfig
import eu.smartsteer.navigator.core.field.FieldStore
import eu.smartsteer.navigator.core.settings.Profiles
import eu.smartsteer.navigator.core.settings.fieldFiles
import eu.smartsteer.navigator.core.settings.VehicleProfile
import eu.smartsteer.navigator.core.settings.vehicleFiles
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

    // ---------------- profile pojazdów ----------------
    private val files = vehicleFiles()
    private val profiles = Profiles(files)
    val profilesLocation: String get() = files.location
    var profileName by mutableStateOf("")
        private set
    var profile by mutableStateOf(VehicleProfile())
        private set
    var profileNames by mutableStateOf(listOf<String>())
        private set
    var profileError by mutableStateOf<String?>(null)
        private set
    /** Ustawienia płytki (PGN 252/251) czekają na wysłanie — wysyłamy 1,5 s po ostatniej zmianie (oszczędza EEPROM). */
    private var configDirty = true
    private var configChangedAt = 0.0
    var configSentAt by mutableStateOf<Double?>(null)
        private set

    init {
        try {
            val (n, p) = profiles.openStartup()
            profileName = n
            profile = p
            profileNames = profiles.names()
        } catch (e: Exception) {
            profileName = Profiles.DEFAULT_NAME
            profileError = "Nie można odczytać profili: ${e.message}"
        }
        session.profile = profile
    }

    /** Zmiana ustawień: od razu działa na prowadzenie i zapisuje się w bieżącym profilu. */
    fun updateProfile(p: VehicleProfile) {
        if (p == profile) return
        val boardChanged = p.boardBytes() != profile.boardBytes()
        profile = p
        session.profile = p
        persist()
        if (boardChanged) { configDirty = true; configChangedAt = now() }
        tick++
    }

    fun loadProfile(name: String) {
        val p = profiles.load(name) ?: run { profileError = "Profil „$name” jest uszkodzony"; return }
        profileName = name
        profile = p
        session.profile = p
        profiles.setLastUsed(name)
        profileError = null
        configDirty = true
        configChangedAt = now()
        tick++
    }

    /** Zapis bieżących ustawień pod nową nazwą i przełączenie na ten profil. Zwraca błąd albo null. */
    fun saveProfileAs(raw: String): String? {
        val name = Profiles.sanitize(raw)
        if (name.isEmpty()) return "Wpisz nazwę"
        if (profiles.exists(name) && name != profileName) return "Profil „$name” już istnieje"
        return try {
            profiles.save(name, profile)
            profiles.setLastUsed(name)
            profileName = name
            profileNames = profiles.names()
            tick++
            null
        } catch (e: Exception) {
            "Nie udało się zapisać: ${e.message}"
        }
    }

    fun deleteProfile(name: String) {
        if (name == profileName) return
        profiles.delete(name)
        profileNames = profiles.names()
        tick++
    }

    /**
     * Zerowanie czujnika kąta kół: koła ustawione prosto, bieżący odczyt staje się zerem.
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Settings/FormSteer.cs (btnZeroWAS_Click).
     */
    fun zeroWas(): String? {
        val st = session.autosteer?.takeIf { session.autosteerAlive(now()) } ?: return "Brak danych z modułu skrętu"
        val offset = (profile.countsPerDegree * -st.actualAngleDeg + profile.wasOffset).toInt()
        if (kotlin.math.abs(offset) > 3900) return "Za duży kąt kół — nie można wyzerować"
        updateProfile(profile.copy(wasOffset = offset))
        return null
    }

    /**
     * Zerowanie przechyłu: ciągnik stoi na płaskim, bieżący odczyt IMU staje się zerem.
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Settings/FormSteerWiz.cs (zerowanie rollZero).
     */
    fun zeroRoll(): String? {
        val raw = session.imu.rawRollDeg?.takeIf { session.imu.rollAlive(now()) } ?: return "Brak odczytu przechyłu z IMU"
        if (kotlin.math.abs(raw) > 10) return "Przechył ponad 10° — ustaw ciągnik na płaskim"
        updateProfile(profile.copy(rollZero = raw))
        return null
    }

    // ---------------- pola ----------------
    private val fieldStore = FieldStore(fieldFiles())
    val fieldsLocation: String get() = fieldStore.location
    var fieldNames by mutableStateOf(runCatching { fieldStore.names() }.getOrDefault(emptyList()))
        private set
    var fieldError by mutableStateOf<String?>(null)
        private set
    private var lastFieldSaveS = 0.0

    fun refreshFields() {
        fieldNames = try { fieldStore.names() } catch (e: Exception) { emptyList() }
    }

    /** Zapis otwartego pola (granica, linie, pokrycie). */
    fun saveField(): String? {
        val f = session.fieldData() ?: return "Żadne pole nie jest otwarte"
        return try {
            fieldStore.save(f, session.coverage.toText())
            lastFieldSaveS = now()
            fieldError = null
            refreshFields()
            null
        } catch (e: Exception) {
            ("Nie udało się zapisać pola: ${e.message}").also { fieldError = it }
        }
    }

    fun newField(raw: String): String? {
        val name = Profiles.sanitize(raw)
        if (name.isEmpty()) return "Wpisz nazwę pola"
        if (fieldStore.exists(name)) return "Pole „$name” już istnieje"
        session.fieldName?.let { saveField() }
        if (!session.newField(name)) return session.message
        val err = saveField()
        tick++
        return err
    }

    fun openField(name: String): String? {
        session.fieldName?.let { saveField() }
        val (f, cov) = fieldStore.load(name) ?: return "Pole „$name” jest uszkodzone"
        session.openField(f, cov)
        tick++
        return null
    }

    fun closeField(): String? {
        val err = saveField()
        if (err != null && session.fieldName != null) return err
        session.closeField()
        tick++
        return null
    }

    fun deleteField(name: String) {
        if (name == session.fieldName) return
        fieldStore.delete(name)
        refreshFields()
        tick++
    }

    // ---------------- sieć modułów ----------------
    private val netFile = "siec.cfg"
    /** Sieć modułów: trzy pierwsze liczby adresu (domyślnie 192.168.5, jak w AgOpenGPS). */
    var subnet by mutableStateOf(
        runCatching { files.read(netFile)?.let { ModuleScan.parseSubnet(it) } }.getOrNull() ?: listOf(192, 168, 5),
    )
        private set
    val subnetText: String get() = subnet.joinToString(".")
    /** Moduły, które odpowiedziały na szukanie (PGN 203). */
    var foundModules by mutableStateOf(listOf<ModuleScan.Module>())
        private set
    var netMessage by mutableStateOf<String?>(null)
        private set
    var lastRxS = Double.NEGATIVE_INFINITY; private set
    private var pendingBroadcast: ByteArray? = null

    /** Czy coś przyszło z sieci w ostatnich 3 s. */
    fun receiving(): Boolean = hardware && now() - lastRxS < 3.0

    fun setSubnet(text: String): String? {
        val v = ModuleScan.parseSubnet(text) ?: return "Wpisz trzy liczby, np. 192.168.5"
        subnet = v
        try { files.write(netFile, subnetText) } catch (_: Exception) {}
        link?.setSubnet(v[0], v[1], v[2])
        netMessage = "Sieć modułów: $subnetText.x"
        tick++
        return null
    }

    /** Szukanie modułów — odpowiedzi pojawią się w [foundModules]. Działa w trybie SPRZĘT. */
    fun scanModules(): String? {
        if (!hardware || link == null) return "Najpierw włącz tryb SPRZĘT"
        foundModules = emptyList()
        pendingBroadcast = ModuleScan.scanRequest()
        netMessage = "Szukam modułów…"
        tick++
        return null
    }

    /** Przestawia wszystkie moduły na sieć [subnet] (PGN 201), jak przycisk w AgIO. */
    fun moveModulesToSubnet(): String? {
        if (!hardware || link == null) return "Najpierw włącz tryb SPRZĘT"
        pendingBroadcast = ModuleScan.subnetChange(subnet[0], subnet[1], subnet[2])
        netMessage = "Wysłano nową sieć $subnetText.x do modułów — wyłącz i włącz płytkę, potem „Szukaj modułów”"
        tick++
        return null
    }

    // ---------------- wykresy ----------------
    /** Historia ostatnich 30 s (co 100 ms): kąt zadany, kąt kół, odchyłka w cm. */
    val histSet = ArrayDeque<Float>()
    val histActual = ArrayDeque<Float>()
    val histXte = ArrayDeque<Float>()

    private fun recordHistory() {
        val st = session.autosteer?.takeIf { session.autosteerAlive(now()) }
        histSet.addLast((if (session.freeDrive) session.freeDriveAngle else session.steerSetDeg).toFloat())
        histActual.addLast(st?.actualAngleDeg?.toFloat() ?: Float.NaN)
        histXte.addLast(if (session.line != null || session.curveRef != null || session.contour.guide.isNotEmpty()) session.xteCm.toFloat() else Float.NaN)
        for (h in listOf(histSet, histActual, histXte)) while (h.size > HISTORY) h.removeFirst()
    }

    // ---------------- NTRIP ----------------
    private val ntripFile = "ntrip.cfg"
    var ntripConfig by mutableStateOf(
        runCatching { files.read(ntripFile)?.let { NtripConfig.fromJson(it) } }.getOrNull() ?: NtripConfig(),
    )
        private set
    var ntripStatus by mutableStateOf("Wyłączony")
        private set
    var ntripReceiving by mutableStateOf(false)
        private set
    var ntripBytes = 0L; private set
    /** Bajty poprawek na sekundę (średnia). */
    var ntripRate by mutableStateOf(0.0)
        private set
    private var ntrip: TcpClient? = null
    private sealed interface NtripEvent {
        data object Connected : NtripEvent
        class Data(val bytes: ByteArray) : NtripEvent
        class Closed(val error: String?) : NtripEvent
    }
    private val ntripEvents = Channel<NtripEvent>(512, BufferOverflow.DROP_OLDEST)
    private var ntripHdr = ByteArray(0)
    private var ntripHeaderDone = false
    private var ntripRetryAtS = 0.0
    private var ntripLastGgaS = 0.0
    private var ntripRateBytes = 0L
    private var ntripRateAtS = 0.0

    fun updateNtrip(c: NtripConfig) {
        val old = ntripConfig
        ntripConfig = c
        try { files.write(ntripFile, c.toJson()) } catch (_: Exception) {}
        // zmiana adresu / punktu / loginu — łączymy od nowa
        if (ntrip != null && (old.host != c.host || old.port != c.port || old.mount != c.mount || old.user != c.user || old.password != c.password || !c.enabled)) {
            stopNtrip(if (c.enabled) "Łączę od nowa…" else "Wyłączony")
            ntripRetryAtS = 0.0
        }
        tick++
    }

    private fun stopNtrip(status: String?) {
        ntrip?.close()
        ntrip = null
        ntripReceiving = false
        if (status != null) ntripStatus = status
    }

    private suspend fun ntripStep(l: UdpLink?) {
        val c = ntripConfig
        val want = c.enabled && c.host.isNotBlank() && c.mount.isNotBlank()
        if (!want) {
            if (ntrip != null) stopNtrip(null)
            ntripStatus = if (c.enabled) "Uzupełnij adres i punkt montowania" else "Wyłączony"
            return
        }
        if (ntrip == null && now() >= ntripRetryAtS) {
            val t = createTcpClient()
            ntrip = t
            ntripHdr = ByteArray(0); ntripHeaderDone = false
            ntripStatus = "Łączę z ${c.host}:${c.port}…"
            t.start(
                c.host.trim(), c.port,
                onConnected = { ntripEvents.trySend(NtripEvent.Connected) },
                onData = { b, n -> ntripEvents.trySend(NtripEvent.Data(b.copyOf(n))) },
                onClosed = { e -> ntripEvents.trySend(NtripEvent.Closed(e)) },
            )
        }
        while (true) {
            val ev = ntripEvents.tryReceive().getOrNull() ?: break
            val t = ntrip
            when (ev) {
                is NtripEvent.Connected -> if (t != null) {
                    val req = Ntrip.request(c)
                    withContext(Dispatchers.Default) { t.send(req) }
                    ntripStatus = "Połączono, czekam na odpowiedź…"
                }
                is NtripEvent.Closed -> {
                    if (ntrip != null) {
                        ntrip = null
                        ntripReceiving = false
                        ntripStatus = "Rozłączono: ${ev.error ?: "koniec połączenia"} — ponowię za 10 s"
                        ntripRetryAtS = now() + 10
                    }
                }
                is NtripEvent.Data -> if (t != null) onNtripData(ev.bytes, c, l)
            }
        }
        // pozycja dla castera (VRS) co ustawiony czas
        val g = session.lastGga
        if (ntripReceiving && c.ggaIntervalS > 0 && g != null && session.gpsAlive(now()) && now() - ntripLastGgaS >= c.ggaIntervalS) {
            ntripLastGgaS = now()
            val code = when (g.fix) {
                FixQuality.GPS -> 1; FixQuality.DGPS -> 2; FixQuality.RTK_FIX -> 4; FixQuality.RTK_FLOAT -> 5; else -> 1
            }
            val gga = Ntrip.gga(g.latitude, g.longitude, g.utcSeconds ?: 0.0, code, g.satellites, g.hdop, g.altitude).encodeToByteArray()
            val t = ntrip
            if (t != null) withContext(Dispatchers.Default) { t.send(gga) }
        }
        if (now() - ntripRateAtS >= 2.0) {
            ntripRate = ntripRateBytes / (now() - ntripRateAtS).coerceAtLeast(0.1)
            ntripRateBytes = 0
            ntripRateAtS = now()
        }
    }

    private suspend fun onNtripData(bytes: ByteArray, c: NtripConfig, l: UdpLink?) {
        var data = bytes
        if (!ntripHeaderDone) {
            // nagłówek zbieramy jako bajty — po nim od razu idą binarne dane RTCM
            ntripHdr += bytes
            val crlf = indexOf(ntripHdr, CRLF)
            if (crlf < 0) {
                if (ntripHdr.size > 4000) { stopNtrip("Nieznana odpowiedź castera"); ntripRetryAtS = now() + 30 }
                return
            }
            val first = ntripHdr.decodeToString(0, crlf)
            val end: Int
            val headerText: String
            if (first.startsWith("ICY")) {
                // NTRIP 1.0: „ICY 200 OK”, czasem z pustą linią
                val blank = ntripHdr.size >= crlf + 4 && ntripHdr[crlf + 2] == '\r'.code.toByte() && ntripHdr[crlf + 3] == '\n'.code.toByte()
                end = if (blank) crlf + 4 else crlf + 2
                headerText = first
            } else {
                val e = indexOf(ntripHdr, CRLFCRLF)
                if (e < 0) {
                    if (ntripHdr.size > 4000) { stopNtrip("Nieznana odpowiedź castera"); ntripRetryAtS = now() + 30 }
                    return
                }
                end = e + 4
                headerText = ntripHdr.decodeToString(0, e)
            }
            when (Ntrip.reply(headerText)) {
                Ntrip.Reply.OK -> {
                    ntripHeaderDone = true
                    ntripReceiving = true
                    ntripStatus = "Odbieram poprawki z „${c.mount}”"
                    ntripLastGgaS = 0.0 // od razu wyślij pozycję
                    data = ntripHdr.copyOfRange(end.coerceAtMost(ntripHdr.size), ntripHdr.size)
                    ntripHdr = ByteArray(0)
                }
                Ntrip.Reply.UNAUTHORIZED -> { stopNtrip("Zły login lub hasło"); ntripRetryAtS = now() + 60; return }
                Ntrip.Reply.SOURCETABLE -> { stopNtrip("Caster nie zna punktu „${c.mount}” (odesłał listę punktów)"); ntripRetryAtS = now() + 60; return }
                Ntrip.Reply.ERROR -> { stopNtrip("Caster odmówił: ${first.take(60)}"); ntripRetryAtS = now() + 30; return }
            }
        }
        if (data.isEmpty()) return
        ntripBytes += data.size
        ntripRateBytes += data.size
        val link = l ?: return
        // RTCM do płytki AIO jak w AgIO: rozgłoszenie UDP na port 2233, w kawałkach mieszczących się w pakiecie
        withContext(Dispatchers.Default) {
            var i = 0
            while (i < data.size) {
                val e = (i + NTRIP_CHUNK).coerceAtMost(data.size)
                link.send(data.copyOfRange(i, e), c.udpPort)
                i = e
            }
        }
    }

    /** Ręczne „Wyślij do płytki”. */
    fun sendBoardConfig() { configDirty = true; configChangedAt = now() - 10; tick++ }

    private fun persist() {
        try {
            profiles.save(profileName, profile)
            profileError = null
        } catch (e: Exception) {
            profileError = "Nie udało się zapisać profilu: ${e.message}"
        }
    }

    private fun VehicleProfile.boardBytes() = listOf(
        gainP, highPwm, lowPwm, minPwm, countsPerDegree, wasOffset, ackerman,
        setting0, setting1, maxPulseCounts, (minSteerSpeed * 10).toInt(),
    )

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
                l.setSubnet(subnet[0], subnet[1], subnet[2])
                linkError = l.start { b, n, from -> inbox.trySend(Packet(b.copyOf(n), from)) }
                link = if (linkError == null) l else null
            }
            stopRequested = false
            configDirty = true
            configChangedAt = now() - 10
        } else {
            session.setEngaged(false, now())
            if (session.freeDrive) session.setFreeDrive(false)
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
                lastRxS = now()
                AogFrame.parse(p.bytes)?.let { f -> ModuleScan.parseReply(f)?.let { m -> foundModules = foundModules.filter { it.source != m.source } + m } }
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
            if (!hardware) { if (ntrip != null) stopNtrip(null); continue }
            if (l != null) {
                pendingBroadcast?.let { b -> pendingBroadcast = null; netMessage = withContext(Dispatchers.Default) { l.broadcastAll(b) } ?: netMessage }
            }
            session.update(now())
            recordHistory()
            ntripStep(l)
            // autozapis pola co 30 s
            if (session.fieldName != null && now() - lastFieldSaveS > 30) saveField()
            val frame = session.steerFrame()
            val machine = session.machineFrame()
            if (l != null) {
                sendError = withContext(Dispatchers.Default) { l.send(frame) ?: l.send(machine) }
                if (sendError == null) txCount++
                if (configDirty && now() - configChangedAt > 1.5) {
                    val f252 = session.steerSettingsFrame()
                    val f251 = session.steerConfigFrame()
                    val err = withContext(Dispatchers.Default) { l.send(f252) ?: l.send(f251) }
                    if (err == null) { configDirty = false; configSentAt = now() }
                }
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

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        outer@ for (i in 0..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    companion object {
        const val HISTORY = 300
        private val CRLF = byteArrayOf(13, 10)
        private val CRLFCRLF = byteArrayOf(13, 10, 13, 10)
        private const val NTRIP_CHUNK = 256
    }
}
