package eu.smartsteer.navigator.ui

import eu.smartsteer.navigator.core.sim.SimReceiver
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.core.field.RateMapImport
import eu.smartsteer.navigator.core.war.WarRateMap
import eu.smartsteer.navigator.core.hw.AutoTune
import eu.smartsteer.navigator.core.hw.GuidanceMode
import eu.smartsteer.navigator.ui.power.PowerPlatform
import eu.smartsteer.navigator.ui.power.PowerState
import eu.smartsteer.navigator.core.settings.ToolPresets
import eu.smartsteer.navigator.core.net.SerialLink
import eu.smartsteer.navigator.core.net.SerialPorts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.Pgn
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
import eu.smartsteer.navigator.core.field.FieldData
import eu.smartsteer.navigator.core.sound.AlertState
import eu.smartsteer.navigator.core.sound.AlertTracker
import eu.smartsteer.navigator.core.sound.SoundKind
import eu.smartsteer.navigator.core.sound.SoundPlayer
import eu.smartsteer.navigator.core.field.FieldStore
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.settings.warFiles
import eu.smartsteer.navigator.core.update.platformUpdater
import eu.smartsteer.navigator.core.war.WarField
import eu.smartsteer.navigator.core.war.nowIso
import eu.smartsteer.navigator.core.io.AgoImport
import eu.smartsteer.navigator.core.io.FieldExchange
import eu.smartsteer.navigator.core.settings.agoFiles
import eu.smartsteer.navigator.core.settings.agoLocation
import eu.smartsteer.navigator.core.io.FieldImport
import eu.smartsteer.navigator.core.settings.exportFiles
import eu.smartsteer.navigator.core.settings.importFiles
import eu.smartsteer.navigator.core.settings.nowText
import eu.smartsteer.navigator.core.settings.openFolder
import eu.smartsteer.navigator.core.settings.Profiles
import eu.smartsteer.navigator.core.settings.fieldFiles
import eu.smartsteer.navigator.core.settings.VehicleProfile
import eu.smartsteer.navigator.core.settings.vehicleFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
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
private const val APPLIED = ".wykonanie.csv"

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
    /** Komputer opryskiwacza (ekran Opryskiwacz). */
    val sprayer = SprayerController(files)
    /** Tryb „tylko opryskiwacz”: bez nawigacji i autosteru — sam komputer opryskiwacza (sekcje z przycisków). */
    var sprayerOnly by mutableStateOf(runCatching { files.read("tryb_pracy.cfg")?.trim() == "oprysk" }.getOrDefault(false))
        private set
    /** Tryb „tylko nawigacja”: bez komputera opryskiwacza (ekran Oprysk ukryty, płytka nie jest szukana). */
    var navOnly by mutableStateOf(runCatching { files.read("tryb_pracy.cfg")?.trim() == "tylko_nawigacja" }.getOrDefault(false))
        private set

    /** 0 = nawigacja + opryskiwacz, 1 = tylko nawigacja, 2 = tylko opryskiwacz. */
    fun updateWorkMode(mode: Int) {
        if (mode == 2) { navOnly = false; updateSprayerOnly(true); return }
        sprayerOnly = false
        navOnly = mode == 1
        if (navOnly) sprayer.disconnect()
        runCatching { files.write("tryb_pracy.cfg", if (navOnly) "tylko_nawigacja" else "nawigacja") }
        if (!navOnly) sprayer.connectUsb()
        tick++
    }

    fun updateSprayerOnly(on: Boolean) {
        sprayerOnly = on
        if (on) navOnly = false
        // wersja bez autosteru: prędkość z GPS płytki opryskiwacza (przez USB), chyba że wybrano stałą
        if (on && sprayer.config.speedSource == eu.smartsteer.navigator.core.sprayer.SpeedSource.GPS)
            sprayer.update(sprayer.config.copy(speedSource = eu.smartsteer.navigator.core.sprayer.SpeedSource.BOARD_GPS))
        runCatching { files.write("tryb_pracy.cfg", if (on) "oprysk" else "nawigacja") }
        tick++
    }
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
            fieldStore.saveExtra(f.name, APPLIED, session.applied.toText())
            lastFieldSaveS = now()
            fieldError = null
            refreshFields()
            null
        } catch (e: Exception) {
            ("Nie udało się zapisać pola: ${e.message}").also { fieldError = it }
        }
    }

    // ---------------- import (KML / ISOXML) i raporty dla WAR ----------------
    private val importDir by lazy { importFiles() }
    private val exportDir by lazy { exportFiles() }
    val importLocation: String get() = importDir.location
    val exportLocation: String get() = exportDir.location
    private var fieldStartedText = ""

    /** Pliki gotowe do importu: *.kml, *.xml oraz foldery z TASKDATA.XML. */
    fun importCandidates(): List<String> = try {
        importDir.list().filter { n ->
            val l = n.lowercase()
            l.endsWith(".kml") || l.endsWith(".xml") || importDir.read("$n/TASKDATA.XML") != null || importDir.read("$n/taskdata.xml") != null
        }.sortedBy { it.lowercase() }
    } catch (e: Exception) { emptyList() }

    /** Pliki map dawek w folderze importu: *.geojson, *.json, *.kml. */
    fun rateMapCandidates(): List<String> = try {
        importDir.list().filter { n -> val l = n.lowercase(); l.endsWith(".geojson") || l.endsWith(".json") || l.endsWith(".kml") }.sortedBy { it.lowercase() }
    } catch (e: Exception) { emptyList() }

    /** Wczytuje mapę dawek do otwartego pola. */
    fun importRateMap(name: String): String {
        val pl = session.plane ?: return "Najpierw otwórz pole"
        if (session.fieldName == null) return "Najpierw otwórz pole"
        val text = importDir.read(name) ?: return "Nie można odczytać „$name”"
        val m = RateMapImport.parse(text, name.substringBeforeLast('.'), pl) ?: return "W „$name” nie ma stref z dawką (wielokąty z liczbą we właściwościach)"
        session.rateMap = m
        saveField()
        tick++
        return "Mapa dawek „${m.name}”: ${m.zones.size} stref, ${m.minRate.pl(0)}–${m.maxRate.pl(0)} ${m.unit}"
    }

    fun clearRateMap() { session.rateMap = null; saveField(); tick++ }

    /** Mapa dawek z WAR do otwartego pola. Zwraca komunikat. */
    fun applyWarRateMap(rm: WarRateMap): String {
        val pl = session.plane ?: return "Najpierw otwórz pole"
        if (session.fieldName == null) return "Najpierw otwórz pole"
        val m = rm.toRateMap(pl)
        if (m.zones.isEmpty()) return "Mapa z WAR nie ma stref w polu"
        session.rateMap = m
        saveField()
        tick++
        return "Mapa dawek z WAR: ${m.minRate.pl(0)}–${m.maxRate.pl(0)} ${m.unit}"
    }

    /** Przyszła mapa z WAR: od razu do otwartego pola, jeśli to jego mapa; inne pola wczytają ją przy otwarciu. */
    fun onWarRateMap(rm: WarRateMap) {
        val what = rm.product.ifEmpty { rm.kindName }.let { if (it.isEmpty()) "" else " ($it)" }
        if (session.fieldName != null && session.fieldWarId == rm.warId) mapMessage(applyWarRateMap(rm) + what)
        else mapMessage("Przyszła mapa dawek z WAR dla pola „${rm.fieldName}”$what — wczyta się przy otwarciu pola")
        tick++
    }

    /** Przy otwarciu pola z WAR: wczytaj mapę z WAR, gdy pole nie ma mapy albo ma starszą z WAR (własnej z pliku nie ruszamy). */
    private fun loadWarRateMapOnOpen() {
        val rm = war.rateMapFor(session.fieldWarId) ?: return
        val cur = session.rateMap
        if (cur == null || (cur.name.startsWith(WarRateMap.PREFIX) && cur.name != rm.mapName)) applyWarRateMap(rm)
    }

    fun setDefaultRate(v: Double) { session.rateMap = session.rateMap?.copy(defaultRate = v); saveField(); tick++ }

    /** Wczytuje pola z pliku do listy pól. Zwraca komunikat dla rolnika. */
    fun importFile(name: String): String {
        val text = importDir.read(name) ?: importDir.read("$name/TASKDATA.XML") ?: importDir.read("$name/taskdata.xml")
            ?: return "Nie można odczytać „$name”"
        val base = name.substringBeforeLast('.')
        val fields = if (text.contains("<kml", ignoreCase = true)) FieldImport.parseKml(text, base) else FieldImport.parseIsoxml(text)
        if (fields.isEmpty()) return "W pliku „$name” nie ma granicy ani linii"
        val added = ArrayList<String>()
        for (f in fields) {
            val data = FieldExchange.toFieldData(f) ?: continue
            var n = Profiles.sanitize(data.name).ifEmpty { "Pole" }
            var k = 2
            while (fieldStore.exists(n)) n = "${Profiles.sanitize(data.name)} ($k)".also { k++ }
            try { fieldStore.save(data.copy(name = n), "") ; added.add(n) } catch (_: Exception) {}
        }
        refreshFields()
        tick++
        return if (added.isEmpty()) "Nic nie zaimportowano" else "Zaimportowano: ${added.joinToString(", ")}"
    }

    /** Początek pracy na polu (ISO 8601) — do paczki pracy dla WAR. */
    var fieldStartedIso = ""
        private set

    /** Połączenie z WAR przez API. */
    val war: WarController by lazy { WarController(this, warFiles(), platformUpdater().currentVersion) }

    /** Otwiera pole z WAR (po `war_id`), a gdy go jeszcze nie ma — zakłada je z granicą z WAR. */
    fun openWarField(f: WarField): String? {
        val existing = fieldStore.names().firstOrNull { n -> fieldStore.load(n)?.first?.warId == f.id }
        if (existing != null) return openField(existing)
        val pl = LocalPlane(f.centerLat, f.centerLon)
        val boundary = f.ring.map { pl.toLocal(it.second, it.first) }
        var n = Profiles.sanitize(f.name).ifEmpty { "Pole WAR" }
        var k = 2
        while (fieldStore.exists(n)) n = "${Profiles.sanitize(f.name)} ($k)".also { k++ }
        val data = FieldData(n, f.centerLat, f.centerLon, boundary.takeIf { it.size >= 3 }, 0.0, null, null, f.id)
        return try {
            fieldStore.save(data, "")
            refreshFields()
            openField(n)
        } catch (e: Exception) {
            "Nie udało się założyć pola: ${e.message}"
        }
    }

    // ---------------- „Kopiuj z AgOpenGPS” ----------------

    /** Profile pojazdów AgOpenGPS: nowe (VehicleProfiles) i stare (Vehicles, wszystko w jednym pliku). */
    fun agoVehicles(): List<String> =
        listOf("VehicleProfiles", "Vehicles").flatMap { d ->
            agoFiles(d)?.list().orEmpty().filter { it.endsWith(".xml", true) }.map { "$d/$it" }
        }

    fun agoTools(): List<String> = agoFiles("ToolProfiles")?.list().orEmpty().filter { it.endsWith(".xml", true) }.map { "ToolProfiles/$it" }

    val agoFolder: String get() = agoLocation()

    private fun readAgo(path: String): String? {
        val dir = path.substringBefore('/')
        return agoFiles(dir)?.read(path.substringAfter('/'))
    }

    /** Nowy profil z ustawień AgOpenGPS (pojazd + opcjonalnie narzędzie). Zwraca komunikat. */
    fun copyFromAgo(vehicle: String?, tool: String?): String {
        if (vehicle == null && tool == null) return "Wybierz profil pojazdu albo narzędzia"
        var p = VehicleProfile()
        var count = 0
        for (path in listOfNotNull(vehicle, tool)) {
            val text = readAgo(path) ?: return "Nie można odczytać $path"
            val (np, n) = AgoImport.applySettings(p, AgoImport.parseSettings(text))
            p = np
            count += n
        }
        if (count == 0) return "W wybranych plikach nie ma znanych ustawień AgOpenGPS"
        val base = listOfNotNull(vehicle, tool).joinToString(" + ") { it.substringAfter('/').removeSuffix(".xml").removeSuffix(".XML") }
        var name = Profiles.sanitize("AOG $base").ifEmpty { "AOG" }
        var k = 2
        while (profileNames.contains(name)) name = Profiles.sanitize("AOG $base ($k)").also { k++ }
        profile = p
        session.profile = p
        val err = saveProfileAs(name)
        configDirty = true
        configChangedAt = now()
        return err ?: "Skopiowano $count ustawień do profilu „$name” — sprawdź wymiary i wyślij do płytki"
    }

    /** Pola AgOpenGPS (foldery z Field.txt). */
    fun agoFields(): List<String> {
        val f = agoFiles("Fields") ?: return emptyList()
        return f.list().filter { f.read("$it/Field.txt") != null }.sortedBy { it.lowercase() }
    }

    fun importAgoField(name: String): String {
        val f = agoFiles("Fields") ?: return "Brak folderu pól AgOpenGPS"
        val field = f.read("$name/Field.txt")?.let { AgoImport.toField(name, it, f.read("$name/Boundary.txt"), f.read("$name/TrackLines.txt")) }
            ?: return "Nie można odczytać pola „$name”"
        var n = Profiles.sanitize(name).ifEmpty { "Pole AOG" }
        var k = 2
        while (fieldStore.exists(n)) n = "${Profiles.sanitize(name)} ($k)".also { k++ }
        return try {
            fieldStore.save(field.copy(name = n), "")
            refreshFields()
            tick++
            "Zaimportowano pole „$n”" + if (field.boundary == null) " (bez granicy)" else ""
        } catch (e: Exception) {
            "Nie udało się zapisać pola: ${e.message}"
        }
    }

    /** Raport zabiegu (GeoJSON) do folderu raportów — do wczytania w WAR. */
    /**
     * Paczka diagnostyczna do wysłania (e-mail, komunikator): wersja, stan łączy, profil, dziennik nadzoru,
     * historia samouczenia i ostatnie pakiety. Hasła (NTRIP, kod WAR) są pomijane.
     */
    fun saveDiagnostics(): String {
        val n = now()
        val s = session
        val text = buildString {
            appendLine("WAR Navigator — diagnostyka ${nowText()}")
            appendLine("Wersja: ${runCatching { platformUpdater().currentVersion }.getOrDefault("?")}")
            appendLine("Tryb: ${if (hardware) "SPRZĘT" else "SYMULATOR"}${if (simulating) " (symulacja jazdy)" else ""}${if (wifiMode) " · Wi-Fi" else ""}")
            appendLine("Sieć modułów: $subnetText.x · odebrano $rxCount · wysłano $txCount · błąd wysyłki: ${sendError ?: "—"}")
            appendLine("Moduły: ${foundModules.joinToString { "${it.source}@${it.ip}" }.ifEmpty { "—" }} · adresy: ${moduleSeen.keys.joinToString()}")
            appendLine("Łącze z modułem skrętu: straty ${linkLossPct ?: "—"}% · najdłuższa przerwa ${linkMaxGapMs ?: "—"} ms")
            appendLine("GPS: ${s.fix.label} · żywy ${s.gpsAlive(n)} · sat ${s.lastGga?.satellites ?: "—"} · HDOP ${s.lastGga?.hdop ?: "—"} · wiek poprawek ${s.lastGga?.correctionAge ?: "—"} · kurs z ${s.headingSource.label}")
            appendLine("Moduł skrętu: ${s.autosteer?.let { "kąt ${it.actualAngleDeg}° przełącznik ${it.steerSwitch} PWM ${it.pwm}" } ?: "brak"} · żywy ${s.autosteerAlive(n)}")
            appendLine("NTRIP: $ntripStatus · ${ntripConfig.host}:${ntripConfig.port}/${ntripConfig.mount} · ${ntripRate.toInt()} B/s")
            appendLine("COM: $serialStatus")
            appendLine("Autoster: ${if (s.engaged) "WŁ." else "wył."} · tryb ${s.mode.label} · odchyłka ${s.xteCm} cm · zadany ${s.steerSetDeg}° · prędkość ${s.speedKmh} km/h")
            appendLine("Pole: ${s.fieldName ?: "—"} · linii ${s.tracks.size} · obrobiono ${s.coverage.workedM2.toInt()} m²")
            appendLine("Samouczenie: ${tuneMode.label} · ${tuneStats?.let { "RMS ${it.rmsCm} cm, maks ${it.maxCm}, przejścia ${it.crossingsPerMin}/min, koła ${it.wheelLagS}s ${it.wheelGain}" } ?: "—"}")
            appendLine()
            appendLine("--- Profil „$profileName” ---")
            appendLine(profile.toJson())
            appendLine("--- Ostatnie wyłączenia / zmiany (dziennik nadzoru) ---")
            runCatching { files.read("dziennik-nadzoru.txt").orEmpty().lines().takeLast(100) }.getOrDefault(emptyList()).forEach { appendLine(it) }
            appendLine("--- Historia samouczenia ---")
            tuneHistory.forEach { appendLine(it) }
            appendLine("--- Ostatnie pakiety ---")
            log.forEach { appendLine(it) }
        }
        val name = "diagnostyka ${nowText().replace(":", "")}.txt"
        return try {
            exportDir.write(name, text)
            runCatching { openFolder(exportDir.location) }
            "Zapisano „$name” w ${exportDir.location} — wyślij ten plik (e-mail, komunikator)"
        } catch (e: Exception) { "Nie udało się: ${e.message}" }
    }

    /** Zapisuje wszystko i zamyka program (przycisk „Zamknij”): autoster wyłączony, pole zapisane z raportem. */
    var exiting by mutableStateOf(false)
        private set
    private var exitCountdown = 0

    fun closeApp() {
        if (exiting) return
        exiting = true
        if (session.engaged) session.setEngaged(false, now())
        if (session.fieldName != null) saveField()
        // pętla wyśle „autoster wyłączony” do płytki; zapamiętany tryb zostaje (po restarcie znów SPRZĘT, nie symulator)
        if (hardware) setHardwareMode(false, remember = false)
        tick++
    }

    /** Eksport otwartego pola: KML i ISOXML (folder TASKDATA) w folderze raportów. */
    fun exportField(): String {
        val f = session.fieldData() ?: return "Żadne pole nie jest otwarte"
        val base = Profiles.sanitize(f.name).ifEmpty { "Pole" }
        return try {
            exportDir.write("$base.kml", eu.smartsteer.navigator.core.io.FieldExport.kml(f))
            exportDir.write("$base ISOXML/TASKDATA/TASKDATA.XML", eu.smartsteer.navigator.core.io.FieldExport.isoxml(f))
            val pl = session.plane
            if (pl != null && session.applied.points.isNotEmpty()) exportDir.write("$base wykonanie.geojson", session.applied.toGeoJson(pl, f.name))
            "Zapisano „$base.kml”, „$base ISOXML/TASKDATA”${if (session.applied.points.isNotEmpty()) " i mapę wykonania" else ""} w ${exportDir.location}"
        } catch (e: Exception) { "Nie udało się: ${e.message}" }
    }

    fun writeReport(): String? {
        val f = session.fieldData() ?: return "Żadne pole nie jest otwarte"
        val end = nowText()
        val json = FieldExchange.reportGeoJson(
            f, session.coverage, profileName, profile.toolWidth, profile.numSections,
            fieldStartedText.ifEmpty { end }, end,
            spray = if (navOnly) null else sprayer.sprayJson(session.coverage.workedM2 / 10_000),
        )
        val file = "${Profiles.sanitize(f.name)} ${end.replace(":", "")}.geojson"
        return try {
            exportDir.write(file, json)
            null
        } catch (e: Exception) {
            "Nie udało się zapisać raportu: ${e.message}"
        }
    }

    fun newField(raw: String): String? {
        val name = Profiles.sanitize(raw)
        if (name.isEmpty()) return "Wpisz nazwę pola"
        if (fieldStore.exists(name)) return "Pole „$name” już istnieje"
        session.fieldName?.let { saveField(); if (session.coverage.workedM2 > 0) writeReport(); war.onFieldClosed() }
        if (!session.newField(name)) return session.message
        fieldStartedText = nowText()
        fieldStartedIso = nowIso()
        war.onFieldOpened(null)
        val err = saveField()
        tick++
        return err
    }

    fun openField(name: String): String? {
        session.fieldName?.let { saveField(); if (session.coverage.workedM2 > 0) writeReport(); war.onFieldClosed() }
        val (f, cov) = fieldStore.load(name) ?: return "Pole „$name” jest uszkodzone"
        session.openField(f, cov)
        runCatching { fieldStore.loadExtra(name, APPLIED) }.getOrNull()?.let { session.applied.loadText(it) }
        fieldStartedText = nowText()
        fieldStartedIso = nowIso()
        war.onFieldOpened(f.warId)
        loadWarRateMapOnOpen()
        tick++
        return null
    }

    fun closeField(): String? {
        val err = saveField()
        if (err != null && session.fieldName != null) return err
        if (session.coverage.workedM2 > 0) writeReport()
        war.onFieldClosed()
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
    // ---------------- czujnik przeszkód (core/safety, docs/PRZESZKODY.md) ----------------
    private val obstacleFile = "przeszkody.cfg"
    var obstacleConfig by mutableStateOf(
        runCatching { files.read(obstacleFile)?.let { eu.smartsteer.navigator.core.safety.ObstacleConfig.fromJson(it) } }.getOrNull()
            ?: eu.smartsteer.navigator.core.safety.ObstacleConfig(),
    )
        private set
    var obstacleError by mutableStateOf<String?>(null)
        private set
    /** Ile paczek przyszło z czujnika (do ekranu Połączenia / Ustawień). */
    var obstacleRx by mutableStateOf(0)
        private set
    private var obstacleReceiver: eu.smartsteer.navigator.core.safety.ObstacleReceiver? = null
    private var obstaclePort = 0
    private val obstacleInbox = Channel<String>(Channel.CONFLATED)
    private var obstacleLevelPrev = 0

    fun updateObstacleConfig(c: eu.smartsteer.navigator.core.safety.ObstacleConfig) {
        obstacleConfig = c
        session.obstacleGuard.config = c
        try { files.write(obstacleFile, c.toJson()) } catch (_: Exception) {}
        applyObstacleReceiver()
        tick++
    }

    private fun applyObstacleReceiver() {
        val c = obstacleConfig
        if (!c.enabled) { obstacleReceiver?.stop(); obstacleReceiver = null; obstaclePort = 0; obstacleError = null; session.obstacleFrame = null; return }
        if (obstacleReceiver != null && obstaclePort == c.port) return
        obstacleReceiver?.stop()
        val r = eu.smartsteer.navigator.core.safety.createObstacleReceiver()
        obstacleError = r.start(c.port) { txt -> obstacleInbox.trySend(txt) }
        obstacleReceiver = if (obstacleError == null) r else null
        obstaclePort = if (obstacleError == null) c.port else 0
    }

    /** Próba bez kamery: przeszkoda [distM] przed maszyną przez chwilę (do sprawdzenia alarmu i zatrzymania). */
    fun testObstacle(distM: Double) {
        session.obstacleFrame = eu.smartsteer.navigator.core.safety.ObstacleFrame(now(), listOf(eu.smartsteer.navigator.core.safety.Obstacle(distM, 0.0, "test", 1.0)), true, "test")
        tick++
    }

    /** Po kroku prowadzenia: nowy STOP przez przeszkodę — komunikat na mapie i zdarzenie do WAR. */
    private fun obstacleEventStep() {
        val v = session.obstacleVerdict
        val lvl = if (hardware) v?.level ?: 0 else 0
        if (lvl == 2 && obstacleLevelPrev < 2 && v != null) {
            mapMessage(v.message ?: "Czujnik przeszkód")
            if (v.nearest != null && v.nearest.kind != "test") war.reportEvent("przeszkoda", v.message ?: "przeszkoda")
        }
        obstacleLevelPrev = lvl
    }

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

    // lista punktów montowania z castera (sourcetable)
    var mounts by mutableStateOf<List<Ntrip.Mount>>(emptyList())
        private set
    var mountsStatus by mutableStateOf<String?>(null)
        private set
    var mountsLoading by mutableStateOf(false)
        private set

    /** Pobiera listę aktywnych punktów montowania („GET /”). Działa także bez trybu SPRZĘT — potrzebny tylko internet. */
    suspend fun fetchMounts(host: String, port: Int, user: String, password: String) {
        if (host.isBlank()) { mountsStatus = "Wpisz adres castera"; return }
        if (mountsLoading) return
        mountsLoading = true
        mountsStatus = "Pobieram listę z ${host.trim()}…"
        val client = createTcpClient()
        val done = CompletableDeferred<String?>()
        val buf = ArrayList<ByteArray>()
        var total = 0
        val req = Ntrip.sourcetableRequest(NtripConfig(host = host.trim(), port = port, user = user.trim(), password = password))
        client.start(
            host.trim(), port,
            onConnected = { client.send(req)?.let { done.complete(it) } },
            onData = { b, n ->
                if (total < 2_000_000) { buf.add(b.copyOf(n)); total += n }
                // koniec listy — nie czekamy, aż caster zamknie połączenie
                if (b.copyOf(n).decodeToString().contains("ENDSOURCETABLE")) done.complete(null)
            },
            onClosed = { err -> done.complete(err) },
        )
        val err = withTimeoutOrNull(12_000) { done.await() } ?: if (done.isCompleted) null else "Brak odpowiedzi (12 s)"
        withContext(Dispatchers.Default) { client.close() }
        val parts = runCatching { buf.toList() }.getOrDefault(emptyList())
        val all = ByteArray(parts.sumOf { it.size }).also { var o = 0; for (c in parts) { c.copyInto(it, o); o += c.size } }
        val text = all.decodeToString()
        val list = Ntrip.parseSourcetable(text)
        mounts = list
        mountsStatus = when {
            list.isNotEmpty() -> "Aktywnych punktów: ${list.size}"
            Ntrip.reply(text) == Ntrip.Reply.UNAUTHORIZED -> "Caster wymaga logowania — sprawdź login i hasło"
            err != null && total == 0 -> "Nie udało się połączyć: $err"
            else -> "Caster nie zwrócił listy punktów"
        }
        mountsLoading = false
        tick++
    }

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
        // odbiornik na USB/COM dostaje poprawki bezpośrednio (jak w AgIO)
        serial?.let { sl -> withContext(Dispatchers.Default) { sl.write(data) } }
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

    // ---------------- wspólna praca kilku maszyn (przez WAR) ----------------
    var shareCoverage by mutableStateOf(runCatching { files.read("wspolna.cfg")?.trim() == "1" }.getOrDefault(false))
        private set
    private var shareSentS = -1e9
    private var shareSentCells = -1L

    fun changeShareCoverage(on: Boolean) {
        shareCoverage = on
        try { files.write("wspolna.cfg", if (on) "1" else "0") } catch (_: Exception) {}
        tick++
    }

    /** Rodzaj pracy tej maszyny (oprysk, siew…) — ze zlecenia WAR albo ustawiony ręcznie; pusty = nie wiadomo. */
    var workKind by mutableStateOf(runCatching { files.read("praca.cfg")?.trim().orEmpty() }.getOrDefault(""))
        private set

    fun changeWorkKind(k: String) {
        if (k == workKind) return
        workKind = k
        try { files.write("praca.cfg", k) } catch (_: Exception) {}
        session.setOthersForSections { it.isNotEmpty() && it == k }
        tick++
    }

    /**
     * Pokrycie innej maszyny z WAR — tylko gdy otwarte jest to samo pole. Na mapie zawsze; sekcje omijają je tylko,
     * gdy obie maszyny robią ten sam rodzaj pracy (siew i oprysk doglebowy na tym samym polu się nie wykluczają).
     */
    fun onOthersCoverage(warId: String, machine: String, name: String, g: eu.smartsteer.navigator.core.war.CoverageGrid, color: String = "", work: String = "") {
        if (!shareCoverage || session.fieldWarId != warId) return
        val first = machine !in session.othersMachines
        session.addOthersCoverage(machine, name, g, color, work, sameWork = work.isNotEmpty() && work == workKind)
        if (first) { mapMessage("Na tym polu pracuje też: $name"); say("Na polu pracuje też $name") }
        tick++
    }

    private fun shareStep() {
        if (!shareCoverage) return
        val t = now()
        if (t - shareSentS < 60.0) return
        val warId = session.fieldWarId ?: return
        val pl = session.plane ?: return
        if (session.coverage.cellCount == shareSentCells) return
        val g = eu.smartsteer.navigator.core.war.CoverageGrid.from(session.coverage, pl) ?: return
        shareSentS = t
        shareSentCells = session.coverage.cellCount
        war.sendLiveCoverage(warId, session.fieldName.orEmpty(), g)
    }

    // ---------------- kamera IP ----------------
    var cameraUrl by mutableStateOf(runCatching { files.read("kamera.cfg")?.trim().orEmpty() }.getOrDefault(""))
        private set

    fun setCamera(url: String) {
        cameraUrl = url.trim()
        try { files.write("kamera.cfg", cameraUrl) } catch (_: Exception) {}
        tick++
    }

    // ---------------- sekwencja na uwrociu ----------------
    private val seqFired = HashSet<Int>()
    private var seqLastDir: Boolean? = null

    private fun headlandSeqStep() {
        val steps = eu.smartsteer.navigator.core.field.HeadlandSequence.parse(profile.headlandSeq)
        val s = session
        if (steps.isEmpty()) { s.hydOverride = 0; return }
        if (s.speedKmh < 1.0) return
        val hh = s.toolToHeadland()
        if (hh == null || hh.first > 60.0) { seqFired.clear(); seqLastDir = null; return }
        val (d, toHeadland) = hh
        if (seqLastDir != toHeadland) { seqFired.clear(); seqLastDir = toHeadland }
        steps.forEachIndexed { i, st ->
            if (st.entering != toHeadland || i in seqFired || d > st.distM + 0.3) return@forEachIndexed
            seqFired.add(i)
            when (st.kind) {
                eu.smartsteer.navigator.core.field.HeadlandSequence.Kind.RAISE -> { s.hydOverride = 2; mapMessage("Uwrocie: podnoszę"); say("Podnoszę") }
                eu.smartsteer.navigator.core.field.HeadlandSequence.Kind.LOWER -> { s.hydOverride = 1; mapMessage("Pole: opuszczam"); say("Opuszczam") }
                eu.smartsteer.navigator.core.field.HeadlandSequence.Kind.MESSAGE -> if (st.text.isNotBlank()) { mapMessage(st.text); say(st.text) }
            }
        }
    }

    // ---------------- pogoda (wiatr do oprysku) ----------------
    var weather by mutableStateOf<eu.smartsteer.navigator.core.weather.Weather?>(null)
        private set
    var weatherAgeText by mutableStateOf("")
        private set
    private var weatherAtS = -1e9
    private var weatherTryS = -1e9
    private var windWarnS = -1e9

    private suspend fun weatherStep() {
        val t = now()
        val g = session.lastGga ?: return
        if (!session.gpsAlive(t)) return
        if (t - weatherAtS < 900 && weather != null) return
        if (t - weatherTryS < 120) return
        weatherTryS = t
        val res = withContext(Dispatchers.Default) {
            runCatching { eu.smartsteer.navigator.core.war.httpJson("GET", eu.smartsteer.navigator.core.weather.Weather.url(g.latitude, g.longitude), null, "WAR-Navigator") }.getOrNull()
        } ?: return
        if (res.code == 200) eu.smartsteer.navigator.core.weather.Weather.parse(res.body)?.let { weather = it; weatherAtS = t; weatherAgeText = nowText().takeLast(5) }
    }

    private fun windWarnStep() {
        val w = weather ?: return
        if (!profile.isSprayer || !session.sections.anyOn()) return
        val t = now()
        if (w.windMs > eu.smartsteer.navigator.core.weather.Weather.SPRAY_MAX_WIND && t - windWarnS > 300) {
            windWarnS = t
            mapMessage("Wiatr ${w.windMs.pl(1)} m/s — za silny do oprysku (do ${eu.smartsteer.navigator.core.weather.Weather.SPRAY_MAX_WIND.pl(0)} m/s)")
            say("Za silny wiatr do oprysku")
        }
    }

    // ---------------- komunikaty głosowe ----------------
    private val voiceFile = "glos.cfg"
    var voiceOn by mutableStateOf(runCatching { files.read(voiceFile)?.trim() == "1" }.getOrDefault(false))
        private set
    private val lastSaid = HashMap<String, Double>()
    private var vHeadlandStage = 0
    private var vTurn = false
    private var vKPhase = 0
    private var vRtk: Boolean? = null
    private var vRateKey = ""

    fun setVoice(on: Boolean) {
        voiceOn = on
        try { files.write(voiceFile, if (on) "1" else "0") } catch (_: Exception) {}
        if (on) eu.smartsteer.navigator.ui.voice.Speech.say("Komunikaty głosowe włączone")
        tick++
    }

    /** Mówi zdanie (to samo nie częściej niż co 8 s). */
    fun say(text: String) {
        if (!voiceOn) return
        val t = now()
        if ((lastSaid[text] ?: -100.0) > t - 8.0) return
        lastSaid[text] = t
        eu.smartsteer.navigator.ui.voice.Speech.say(text)
    }

    private fun num(v: Double): String = v.pl(if (v < 10 && v % 1.0 != 0.0) 1 else 0).replace(".", ",")

    /** Co 100 ms: zdarzenia → zdania (uwrocie, zawrót, RTK, dawka). Wyłączenia autosteru mówi logTrip. */
    private fun voiceStep() {
        if (!voiceOn || !hardware) return
        val s = session
        // uwrocie przed maszyną
        val hh = if (s.speedKmh > 2.0) s.toolToHeadland() else null
        if (hh == null || hh.first > 40.0) vHeadlandStage = 0
        else {
            val (d, toHeadland) = hh
            if (toHeadland) {
                if (d < 30 && vHeadlandStage < 1) { vHeadlandStage = 1; say("Uwrocie za ${(d / 5).toInt() * 5} metrów") }
                if (d < 1.5 && vHeadlandStage < 2) { vHeadlandStage = 2; say(if (s.profile.hydEnabled && s.profile.hydAuto && s.hydLiftOn) "Uwrocie" else "Uwrocie. Podnieś") }
            } else if (d < 1.5 && vHeadlandStage < 3) { vHeadlandStage = 3; say(if (s.profile.hydEnabled && s.profile.hydAuto && s.hydLiftOn) "Pole" else "Pole. Opuść") }
        }
        // zawroty
        val turning = s.youTurnPath != null
        if (turning && !vTurn) say(if (s.kPhase != 0) "Zawrót z cofaniem ${if (s.uTurnRight) "w prawo" else "w lewo"}" else "Zawracam ${if (s.uTurnRight) "w prawo" else "w lewo"}")
        vTurn = turning
        if (s.kPhase != vKPhase) {
            when (s.kPhase) { 2 -> say("Stop. Wsteczny"); 3 -> say("Stop. Naprzód") }
            vKPhase = s.kPhase
        }
        // RTK (gdy jest pozycja)
        if (s.gpsAlive(now()) && !rtkNotExpected) {
            val rtk = s.fix == eu.smartsteer.navigator.core.gps.FixQuality.RTK_FIX
            if (vRtk == true && !rtk) say("Utracono RTK")
            if (vRtk == false && rtk) say("RTK fix")
            vRtk = rtk
        }
        // dawka z mapy
        val rn = s.rateNow()
        if (rn != null && s.sections.anyOn()) {
            val (rate, _) = rn
            val p = s.profile
            val text = when {
                p.rateBase > 0 && p.rateHintMode == 1 -> {
                    val n = kotlin.math.round(((rate / p.rateBase - 1) * 100) / p.rateStepPct).toInt()
                    if (n == 0) "Sterownik sto procent" else "Sterownik ${if (n > 0) "plus" else "minus"} ${num(kotlin.math.abs(n * p.rateStepPct))} procent"
                }
                p.rateBase > 0 && rate > 0 -> "Jedź ${num(p.rateBase * p.rateBaseSpeed / rate)} kilometrów na godzinę"
                else -> "Dawka ${num(rate)}"
            }
            if (text != vRateKey) { vRateKey = text; say(text) }
        }
    }

    // ---------------- pole samo się podpowiada po wjeździe ----------------
    private class KnownField(val name: String, val plane: LocalPlane, val boundary: List<Vec2>)
    private var knownFields: List<KnownField>? = null
    private var knownFieldsFor: List<String> = emptyList()
    private var lastFieldCheckS = 0.0
    private val dismissedFields = HashSet<String>()
    /** Pole, na którym stoi ciągnik (gdy żadne nie jest otwarte) — propozycja otwarcia. */
    var fieldSuggestion by mutableStateOf<String?>(null)
        private set

    private fun fieldLocatorStep() {
        val t = now()
        if (t - lastFieldCheckS < 5.0) return
        lastFieldCheckS = t
        val g = session.lastGga
        if (session.fieldName != null || g == null || !session.gpsAlive(t)) { fieldSuggestion = null; return }
        if (knownFields == null || knownFieldsFor != fieldNames) {
            knownFieldsFor = fieldNames
            knownFields = fieldNames.mapNotNull { n ->
                val f = runCatching { fieldStore.loadData(n) }.getOrNull() ?: return@mapNotNull null
                val b = f.boundary?.takeIf { it.size >= 3 } ?: return@mapNotNull null
                KnownField(n, LocalPlane(f.originLat, f.originLon), b)
            }
        }
        val hit = knownFields.orEmpty().firstOrNull { k -> Polygon.contains(k.boundary, k.plane.toLocal(g.latitude, g.longitude)) }?.name
        val sug = hit?.takeIf { it !in dismissedFields }
        if (sug != null && sug != fieldSuggestion) say("Jesteś na polu $sug")
        fieldSuggestion = sug
    }

    fun acceptFieldSuggestion() {
        val n = fieldSuggestion ?: return
        fieldSuggestion = null
        mapMessage(openField(n) ?: "Otwarto pole „$n”")
        say("Otwarto pole $n")
    }

    fun dismissFieldSuggestion() { fieldSuggestion?.let { dismissedFields.add(it) }; fieldSuggestion = null; tick++ }

    // ---------------- samouczenie prowadzenia ----------------
    private val tuneFile = "samouczenie.cfg"
    private val autoTune = AutoTune()
    var tuneMode by mutableStateOf(
        runCatching { AutoTune.Mode.valueOf(files.read(tuneFile)?.trim().orEmpty()) }.getOrDefault(AutoTune.Mode.OFF),
    )
        private set
    /** Propozycja czekająca na decyzję (tryb PODPOWIEDZI). */
    var tunePending by mutableStateOf<AutoTune.Proposal?>(null)
        private set
    /** Ustawienia sprzed pierwszej automatycznej zmiany — do „Cofnij”. */
    var tuneBaseline by mutableStateOf<VehicleProfile?>(null)
        private set
    var tuneHistory by mutableStateOf<List<String>>(emptyList())
        private set
    val tuneStats: AutoTune.Stats? get() = autoTune.lastStats

    fun changeTuneMode(m: AutoTune.Mode) {
        tuneMode = m
        try { files.write(tuneFile, m.name) } catch (_: Exception) {}
        if (m == AutoTune.Mode.OFF) tunePending = null
        autoTune.reset()
        tick++
    }

    private fun applyTune(prop: AutoTune.Proposal) {
        if (tuneBaseline == null) tuneBaseline = profile
        updateProfile(prop.profile)
        val line = "${nowText()}  " + prop.reasons.joinToString("; ")
        tuneHistory = (listOf(line) + tuneHistory).take(20)
        logTrip("Samouczenie: " + prop.reasons.joinToString("; "))
        mapMessage("Samouczenie: " + prop.reasons.first())
    }

    fun acceptTune() { tunePending?.let { applyTune(it) }; tunePending = null; tick++ }
    fun dismissTune() { tunePending = null; tick++ }

    /** Przywraca ustawienia sprzed samouczenia. */
    fun revertTune() {
        tuneBaseline?.let { updateProfile(it); logTrip("Samouczenie: cofnięto zmiany") }
        tuneBaseline = null
        tuneHistory = emptyList()
        tick++
    }

    private fun tuneStep() {
        if (tuneMode == AutoTune.Mode.OFF || !hardware) return
        val s = session
        val t = now()
        val hasLine = (s.mode == GuidanceMode.AB && s.line != null) || (s.mode == GuidanceMode.CURVE && s.curveRef != null)
        val steady = s.engaged && hasLine && s.youTurnPath == null
        val act = s.autosteer?.takeIf { s.autosteerAlive(t) }?.actualAngleDeg
        val prop = autoTune.sample(t, steady, s.xteCm, s.steerSetDeg, act, s.speedKmh, profile) ?: return
        // propozycja liczona dla profilu z chwili oceny — jeśli w międzyczasie coś zmieniono, pomijamy
        when (tuneMode) {
            AutoTune.Mode.AUTO -> applyTune(prop)
            AutoTune.Mode.SUGGEST -> tunePending = prop
            AutoTune.Mode.OFF -> {}
        }
    }

    // ---------------- łączność przez Wi-Fi ----------------
    private val wifiFile = "wifi.cfg"
    /** Tryb Wi-Fi: wysyłka na adresy modułów (z potwierdzeniami Wi-Fi) zamiast rozgłoszeń. */
    var wifiMode by mutableStateOf(runCatching { files.read(wifiFile)?.trim() == "1" }.getOrDefault(false))
        private set
    private val moduleSeen = HashMap<String, Double>()
    private val linkRx = ArrayDeque<Double>()
    /** Adresy modułów, na które idzie wysyłka (tryb Wi-Fi). */
    var wifiTargets by mutableStateOf<List<String>>(emptyList())
        private set
    /** Jakość łącza z modułem skrętu (ostatnie 5 s): odsetek zgubionych odpowiedzi i najdłuższa przerwa. */
    var linkLossPct by mutableStateOf<Int?>(null)
        private set
    var linkMaxGapMs by mutableStateOf<Int?>(null)
        private set
    private var lastWifiBroadcastS = 0.0

    fun setWifi(on: Boolean) {
        wifiMode = on
        try { files.write(wifiFile, if (on) "1" else "0") } catch (_: Exception) {}
        if (!on) link?.setUnicast(emptyList())
        tick++
    }

    private suspend fun wifiStep(l: UdpLink?) {
        val t = now()
        // jakość łącza: moduł odpowiada na każdą ramkę PGN 254 (10 na sekundę)
        while (linkRx.isNotEmpty() && t - linkRx.first() > 5.0) linkRx.removeFirst()
        if (hardware && txCount > 50) {
            linkLossPct = ((1.0 - linkRx.size / 50.0) * 100).toInt().coerceIn(0, 100)
            var gap = if (linkRx.isEmpty()) 5.0 else t - linkRx.last()
            for (i in 1 until linkRx.size) gap = maxOf(gap, linkRx[i] - linkRx[i - 1])
            linkMaxGapMs = (gap * 1000).toInt()
        } else { linkLossPct = null; linkMaxGapMs = null }
        val ips = moduleSeen.filter { t - it.value < 10.0 }.keys.sorted()
        if (!wifiMode || l == null) { wifiTargets = emptyList(); return }
        wifiTargets = ips
        l.setUnicast(ips)
        // co 3 s jedno rozgłoszenie, żeby zgłosił się moduł, którego jeszcze nie znamy (albo zmienił adres)
        if (ips.isEmpty() || t - lastWifiBroadcastS > 3.0) {
            lastWifiBroadcastS = t
            if (ips.isNotEmpty()) {
                val f = session.steerFrame()
                l.setUnicast(emptyList())
                withContext(Dispatchers.Default) { l.send(f) }
                l.setUnicast(ips)
            }
        }
    }

    // ---------------- zasilanie: zamknięcie po zaniku ----------------
    private val powerFile = "zasilanie.cfg"
    private val powerCfg = runCatching { files.read(powerFile).orEmpty().lines() }.getOrDefault(emptyList())
    var powerAutoClose by mutableStateOf(powerCfg.getOrNull(0)?.trim() == "1")
        private set
    var powerDelayS by mutableStateOf(powerCfg.getOrNull(1)?.trim()?.toIntOrNull() ?: 10)
        private set
    var powerShutdownPc by mutableStateOf(powerCfg.getOrNull(2)?.trim() == "1")
        private set
    /** Sekundy do zamknięcia po zaniku zasilania (null = nic się nie dzieje). */
    var powerCountdown by mutableStateOf<Int?>(null)
        private set
    private var powerLostAt = Double.NaN
    private var powerCancelled = false
    private var exitAt = Double.NaN

    fun setPowerOptions(autoClose: Boolean = powerAutoClose, delayS: Int = powerDelayS, shutdownPc: Boolean = powerShutdownPc) {
        powerAutoClose = autoClose; powerDelayS = delayS.coerceIn(3, 600); powerShutdownPc = shutdownPc
        try { files.write(powerFile, "${if (autoClose) 1 else 0}\n$powerDelayS\n${if (shutdownPc) 1 else 0}\n") } catch (_: Exception) {}
        tick++
    }

    /** „Anuluj” na pasku odliczania — do ponownego podłączenia zasilania. */
    fun cancelPowerExit() { powerCancelled = true; powerCountdown = null; tick++ }

    private fun powerStep() {
        val t = now()
        if (!exitAt.isNaN()) {
            // ramka „autoster wyłączony” już poszła — zamykamy
            if (t >= exitAt) PowerPlatform.exit(powerShutdownPc && PowerPlatform.canShutdownSystem)
            return
        }
        val ext = PowerState.external
        if (ext != false) { powerLostAt = Double.NaN; powerCancelled = false; powerCountdown = null; return }
        if (!powerAutoClose || powerCancelled) { powerCountdown = null; return }
        if (powerLostAt.isNaN()) { powerLostAt = t; mapMessage("Brak zasilania z ciągnika") }
        val left = powerDelayS - (t - powerLostAt)
        powerCountdown = kotlin.math.ceil(left).toInt().coerceAtLeast(0)
        if (left <= 0) {
            // zapis pola i bezpieczne wyłączenie autosteru, potem zamknięcie
            if (session.engaged) session.setEngaged(false, t)
            saveField()
            if (hardware) setHardwareMode(false)
            exitAt = t + 1.0
        }
    }

    // ---------------- zapisane maszyny ----------------
    private val toolPresets = ToolPresets(files)
    var toolNames by mutableStateOf(runCatching { toolPresets.names() }.getOrDefault(emptyList()))
        private set
    var currentTool by mutableStateOf(runCatching { files.read("maszyna.txt")?.trim().orEmpty() }.getOrDefault(""))
        private set

    /** Zapisuje ustawienia maszyny z bieżącego profilu pod nazwą (np. „Talerzówka 3 m”). */
    fun saveToolAs(raw: String): String? {
        val name = Profiles.sanitize(raw)
        if (name.isEmpty()) return "Wpisz nazwę maszyny"
        return try {
            toolPresets.save(name, profile)
            toolNames = toolPresets.names()
            rememberTool(name)
            null
        } catch (e: Exception) { "Nie udało się zapisać: ${e.message}" }
    }

    /** Podpina zapisaną maszynę do bieżącego ciągnika (wymiary ciągnika zostają). */
    fun loadTool(name: String): String? {
        val t = toolPresets.load(name) ?: return "Maszyna „$name” jest uszkodzona"
        if (session.engaged) session.setEngaged(false, now())
        updateProfile(ToolPresets.apply(profile, t))
        rememberTool(name)
        return null
    }

    fun deleteTool(name: String) {
        toolPresets.delete(name)
        toolNames = toolPresets.names()
        if (currentTool == name) rememberTool("")
    }

    private fun rememberTool(n: String) {
        currentTool = n
        try { files.write("maszyna.txt", n) } catch (_: Exception) {}
        tick++
    }

    // ---------------- odbiornik GPS na USB/COM ----------------
    private val serialFile = "gps-com.cfg"
    val serialSupported: Boolean get() = SerialPorts.supported
    var serialPort by mutableStateOf("")
        private set
    var serialBaud by mutableStateOf(115200)
        private set
    var serialStatus by mutableStateOf("Wyłączony")
        private set
    private var serial: SerialLink? = null

    init {
        runCatching {
            val t = files.read(serialFile).orEmpty().lines()
            serialPort = t.getOrNull(0).orEmpty().trim()
            serialBaud = t.getOrNull(1)?.trim()?.toIntOrNull() ?: 115200
        }
    }

    fun serialPorts(): List<String> = SerialPorts.list()

    /** GPS z telefonu (2–5 m): tylko prowadzenie ręczne — autoster zablokowany i bez przycisku na mapie. */
    val phoneGps: Boolean get() = serialPort.startsWith("Telefon")
    /** RTK nie jest tu oczekiwany (GPS telefonu, tryb „tylko opryskiwacz”) — brak RTK FIX to normalny stan, nie ostrzeżenie. */
    val rtkNotExpected: Boolean get() = phoneGps || sprayerOnly

    private fun applySteerBlock() {
        session.gpsTimeoutS = if (phoneGps) 3.0 else eu.smartsteer.navigator.core.hw.HardwareSession.GPS_TIMEOUT_S
        session.steerBlockedReason = if (phoneGps) "GPS z telefonu (2–5 m) — tylko prowadzenie ręczne; do autosteru potrzebny RTK (płytka AIO albo odbiornik na Bluetooth/USB)" else null
    }

    /** Otwiera port (pusty = wyłącza). Zdania NMEA idą jak z sieci, poprawki NTRIP także do portu. */
    fun setSerial(port: String, baud: Int) {
        serial?.close(); serial = null
        serialPort = port; serialBaud = baud
        try { files.write(serialFile, "$port\n$baud\n") } catch (_: Exception) {}
        if (port.isBlank()) { serialStatus = "Wyłączony"; applySteerBlock(); tick++; return }
        val link = SerialPorts.create()
        val err = link.open(port, baud,
            onLine = { line -> inbox.trySend(Packet((line + "\n").encodeToByteArray(), "COM")) },
            onClosed = { e -> serialStatus = e ?: "Zamknięty"; serial = null },
        )
        if (err == null) { serial = link; serialStatus = if (phoneGps) "Odbieram z GPS telefonu" else "Odbieram z ${port.substringBefore(" — ")}${if (port.startsWith("BT: ")) "" else " ($baud)"}" } else serialStatus = err
        applySteerBlock()
        if (err == null && !hardware) setHardwareMode(true)
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
        machineSetting0, hydRaiseTime, hydLowerTime, machineUser1, machineUser2, machineUser3, machineUser4,
    )

    private class Packet(val bytes: ByteArray, val from: String)
    private val inbox = Channel<Packet>(512, BufferOverflow.DROP_OLDEST)
    private var link: UdpLink? = null
    private val clock = TimeSource.Monotonic.markNow()

    /** Tylko dla zrzutów ekranu w testach: zamrożony czas, żeby wolne renderowanie nie wyglądało jak zawieszenie programu. */
    var timeForScreenshots: Double? = null
    fun now(): Double = timeForScreenshots ?: (clock.elapsedNow().inWholeMilliseconds / 1000.0)

    private var stopRequested = false

    private val modeFile = "tryb.cfg"
    private val lastFieldFile = "ostatnie-pole.txt"
    private var savedFieldName: String? = null

    // ---------------- dziennik nadzoru ----------------
    private val tripFile = "dziennik-nadzoru.txt"
    /** Ostatnie samoczynne wyłączenia autosteru (najnowsze na początku), z godziną. */
    var recentTrips by mutableStateOf(
        runCatching { files.read(tripFile).orEmpty().lines().filter { it.isNotBlank() }.takeLast(20).reversed() }.getOrDefault(emptyList()),
    )
        private set
    val tripLogLocation: String get() = profilesLocation + " → " + tripFile

    private fun logTrip(msg: String) {
        if (!msg.startsWith("Samouczenie")) say(msg.substringBefore(" — ").let { if (msg.contains("autoster wyłączony")) "Autoster wyłączony. $it" else it })
        val line = "${nowText()}  $msg"
        recentTrips = (listOf(line) + recentTrips).take(20)
        try {
            val old = files.read(tripFile).orEmpty().lines().filter { it.isNotBlank() }.takeLast(999)
            files.write(tripFile, (old + line).joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    /** [remember] = false przy zamykaniu programu: płytka dostaje „autoster wyłączony”, ale następny start dalej jest w trybie SPRZĘT. */
    fun setHardwareMode(on: Boolean, remember: Boolean = true) {
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
        // zapamiętujemy tryb — po ponownym uruchomieniu aplikacja startuje tak, jak ją zostawiono
        if (remember) try { files.write(modeFile, if (on) "sprzet" else "symulator") } catch (_: Exception) {}
        tick++
    }

    /** Pętla: odbiór pakietów i wysyłka PGN 254 co 100 ms (tylko w trybie Sprzęt). */
    suspend fun run() = coroutineScope {
        // tryb z poprzedniego uruchomienia (SPRZĘT albo symulacja jazdy)
        val saved = runCatching { files.read(modeFile)?.trim() }.getOrNull()
        if (!hardware && (saved == "sprzet" || simulating)) setHardwareMode(true)
        if (serialPort.isNotBlank() && serial == null) setSerial(serialPort, serialBaud)
        if (!navOnly) sprayer.connectUsb()
        PowerPlatform.startMonitor()
        // pole otwarte przy poprzednim uruchomieniu (z pokryciem i liniami)
        val lastField = runCatching { files.read(lastFieldFile)?.trim() }.getOrNull()
        if (!lastField.isNullOrEmpty() && session.fieldName == null && runCatching { fieldStore.exists(lastField) }.getOrDefault(false)) {
            openField(lastField)?.let { mapMessage(it) } ?: mapMessage("Otwarto ostatnie pole „$lastField”")
        }
        savedFieldName = session.fieldName
        launch { war.loop() }
        session.obstacleGuard.config = obstacleConfig
        applyObstacleReceiver()
        launch {
            for (txt in obstacleInbox) {
                eu.smartsteer.navigator.core.safety.ObstacleProtocol.parse(txt, now())?.let { session.obstacleFrame = it; obstacleRx++ }
            }
        }
        launch {
            for (p in inbox) {
                if (!hardware) continue
                rxCount++
                lastRxS = now()
                AogFrame.parse(p.bytes)?.let { f ->
                    if (sprayer.source == SprayerSource.NET) sprayer.onFrame(f)
                    ModuleScan.parseReply(f)?.let { m -> foundModules = foundModules.filter { it.source != m.source } + m }
                    // adresy modułów (do wysyłki bezpośredniej przez Wi-Fi) i jakość łącza z PGN 253
                    if (p.from != "COM") moduleSeen[p.from] = now()
                    if (f.pgn == Pgn.FROM_AUTOSTEER) linkRx.addLast(now())
                }
                session.onDatagram(p.bytes, p.bytes.size, now())
                addLog(describe(p))
            }
        }
        while (true) {
            delay(100)
            val sprayEvents = if (navOnly) emptyList() else sprayer.step(100, if (hardware) session.speedKmh else 0.0, session.fieldName) { i -> sprayerOnly || session.sections.on.getOrElse(i) { false } }
            if (soundOn && sprayEvents.isNotEmpty()) SoundPlayer.play(if (sprayEvents.any { it.loud }) SoundKind.ALARM else SoundKind.WARNING)
            if (mapNote != null && now() > mapNoteUntilS) mapNote = null
            if (exiting) { exitCountdown++; if (exitCountdown >= 8) eu.smartsteer.navigator.ui.power.PowerPlatform.exit(false) }
            powerStep()
            if (hardware) fieldLocatorStep()
            voiceStep()
            if (hardware) { weatherStep(); windWarnStep(); headlandSeqStep(); shareStep() }
            wifiStep(link)
            if (session.fieldName != savedFieldName) {
                savedFieldName = session.fieldName
                try { files.write(lastFieldFile, savedFieldName ?: "") } catch (_: Exception) {}
            }
            while (session.tripLog.isNotEmpty()) logTrip(session.tripLog.removeFirst())
            // obrót mapy za ciągnikiem: wygładzony kurs, żeby pole obracało się płynnie
            var dh = session.headingRad - viewHeading
            while (dh > kotlin.math.PI) dh -= 2 * kotlin.math.PI
            while (dh < -kotlin.math.PI) dh += 2 * kotlin.math.PI
            viewHeading += dh * 0.35
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
            if (simulating) simStep()
            session.update(now())
            obstacleEventStep()
            tuneStep()
            recordHistory()
            ntripStep(l)
            // autozapis pola co 30 s
            if (session.fieldName != null && now() - lastFieldSaveS > 30) saveField()
            val frame = session.steerFrame()
            val machine = session.machineFrame()
            if (l != null) {
                val s64 = session.sections64Frame()
                val rate = session.rateFrame()
                val safety = session.safetyFrame()
                sendError = withContext(Dispatchers.Default) { l.send(frame) ?: l.send(machine) ?: s64?.let { l.send(it) } ?: rate?.let { l.send(it) } ?: safety?.let { l.send(it) } }
                if (sendError == null) txCount++
                sprayer.pendingNetFrame?.let { pf -> sprayer.pendingNetFrame = null; withContext(Dispatchers.Default) { l.send(pf) } }
                if (configDirty && now() - configChangedAt > 1.5) {
                    val f252 = session.steerSettingsFrame()
                    val f251 = session.steerConfigFrame()
                    val f238 = session.machineConfigFrame()
                    val err = withContext(Dispatchers.Default) { l.send(f252) ?: l.send(f251) ?: l.send(f238) }
                    if (err == null) { configDirty = false; configSentAt = now() }
                }
            }
            tick++
        }
    }

    // ---------------- symulacja jazdy (bez płytki) ----------------
    /** Udawany odbiornik RTK i moduł skrętu — do nauki obsługi i sprawdzenia ustawień bez ciągnika. */
    val sim = SimReceiver()
    /** Zawsze startuje wyłączona: po restarcie (np. już w ciągniku) program nie może jechać na udawanym GPS i udawanym module skrętu. */
    var simulating by mutableStateOf(false)
        private set

    fun setSimulation(on: Boolean) {
        simulating = on
        if (on && !hardware) setHardwareMode(true)
        tick++
    }

    private fun simStep() {
        val s = session
        val steer = when {
            s.engaged -> s.steerSetDeg
            s.freeDrive -> s.freeDriveAngle
            else -> null
        }
        sim.step(0.1, steer)
        val t = now()
        s.onNmea(sim.vtg(), t)
        s.onNmea(sim.gga(), t)
        val f = sim.pgn253()
        s.onDatagram(f, f.size, t)
    }

    /** Kurs, za którym obraca się mapa (wygładzony). */
    var viewHeading = 0.0; private set

    /** Krótki komunikat na mapie (np. po przyjęciu zlecenia), znika po 8 s. */
    var mapNote by mutableStateOf<String?>(null)
        private set
    private var mapNoteUntilS = 0.0

    fun mapMessage(m: String) { mapNote = m; mapNoteUntilS = now() + 8; tick++ }

    // ---------------- dźwięki ----------------
    private val soundFile = "dzwieki.cfg"
    var soundOn by mutableStateOf(runCatching { files.read(soundFile)?.trim() != "0" }.getOrDefault(true))
        private set
    private val alerts = AlertTracker()

    fun setSound(on: Boolean) {
        soundOn = on
        try { files.write(soundFile, if (on) "1" else "0") } catch (_: Exception) {}
        if (on) SoundPlayer.play(SoundKind.SECTION_ON)
        tick++
    }

    /** Co 100 ms: zmiany stanu → dźwięki (autoster, GPS, RTK, zawrót, sekcje, zlecenia z WAR). */
    fun soundStep() {
        val n = now()
        val s = session
        val ev = alerts.events(
            AlertState(
                hardware = hardware, engaged = s.engaged, userToggleS = s.lastUserToggleS,
                gpsOk = hardware && s.gpsAlive(n), rtkFix = rtkNotExpected || s.fix == FixQuality.RTK_FIX, turning = s.youTurnPath != null,
                anySection = s.sections.anyOn(), newOrders = war.newOrders.size, nowS = n,
                obstacle = if (hardware) s.obstacleVerdict?.level ?: 0 else 0,
            ),
        )
        if (soundOn) ev.firstOrNull()?.let { SoundPlayer.play(it) }
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
