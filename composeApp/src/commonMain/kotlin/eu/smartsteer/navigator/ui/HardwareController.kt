package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.AutosteerStatus
import eu.smartsteer.navigator.core.hw.HardwareSession
import eu.smartsteer.navigator.core.net.UdpLink
import eu.smartsteer.navigator.core.net.createUdpLink
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
                linkError = l.start { b, n, from -> inbox.trySend(Packet(b.copyOf(n), from)) }
                link = if (linkError == null) l else null
            }
            stopRequested = false
            configDirty = true
            configChangedAt = now() - 10
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
}
