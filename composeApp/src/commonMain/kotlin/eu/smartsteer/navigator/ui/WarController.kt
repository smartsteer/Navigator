package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.settings.TextFiles
import eu.smartsteer.navigator.core.update.MiniJson
import eu.smartsteer.navigator.core.war.CoverageGrid
import eu.smartsteer.navigator.core.war.FieldPartsAssembler
import eu.smartsteer.navigator.core.war.HttpResult
import eu.smartsteer.navigator.core.war.Json
import eu.smartsteer.navigator.core.war.WarConfig
import eu.smartsteer.navigator.core.war.WarCrypto
import eu.smartsteer.navigator.core.war.WarField
import eu.smartsteer.navigator.core.war.WarOrder
import eu.smartsteer.navigator.core.war.WarProtocol
import eu.smartsteer.navigator.core.war.WarProducts
import eu.smartsteer.navigator.core.war.WarRateMap
import eu.smartsteer.navigator.core.war.WarTreatment
import eu.smartsteer.navigator.core.war.httpJson
import eu.smartsteer.navigator.core.war.nowIso
import eu.smartsteer.navigator.core.war.nowMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI

/**
 * Połączenie z WAR przez API (docs/WAR.md): kod nawigacji, szyfrowane skrzynki, kolejka wysyłki na dysku,
 * pola i zlecenia z WAR, praca w polu i pozycja na żywo. Protokół zaimplementowany od nowa według opisu.
 */
class WarController(private val hw: HardwareController, private val files: TextFiles, private val version: String) {
    var config by mutableStateOf(
        runCatching { files.read(CONFIG)?.let { WarConfig.fromJson(it) } }.getOrNull() ?: WarConfig(),
    )
        private set
    /** Stan dla rolnika, np. „Połączono”, „Czekam na WAR — otwórz WAR na telefonie”. */
    var status by mutableStateOf("Nie połączono — wpisz kod nawigacji")
        private set
    /** Wynik testu kodu: true = konto z WAR Sync, false = kod nieaktywny, null = nie sprawdzono. */
    var codeActive by mutableStateOf<Boolean?>(null)
        private set
    var fields by mutableStateOf(loadFields())
        private set
    var orders by mutableStateOf(loadOrders())
        private set
    var queueSize by mutableStateOf(0)
        private set
    var lastSentText by mutableStateOf<String?>(null)
        private set
    /** Środki, nawozy i odmiany gospodarstwa z WAR (podpowiedzi przy pytaniu o zabieg). */
    var products by mutableStateOf(loadProducts())
        private set
    /** Praca czeka na odpowiedź „co robiłeś?” (okno po zamknięciu pola). */
    var pendingAsk by mutableStateOf<PendingAsk?>(null)
        private set

    /** Praca wstrzymana do odpowiedzi: plik h_<id>.json, podpowiedź ze zlecenia / mapy dawek / ostatniego zabiegu. */
    data class PendingAsk(val id: String, val fieldName: String, val ha: Double, val tool: String, val suggestion: WarTreatment?)
    /** Zlecenie przyjęte do bieżącego pola (wraca w paczce pracy, po zamknięciu pola = zrobione). */
    var activeOrderId: String? = null
        private set

    private var key: ByteArray? = null
    private var keyCode = ""
    private var worksBox = ""
    private var navBox = ""
    private var ownNavBox = ""
    private var fleetBox = ""
    private var fleetOd: String? = null
    private var nextFleetReadMs = 0L
    private var nextFleetPosMs = 0L
    private var pendingFleetCoverage: String? = null
    /** Inne maszyny gospodarstwa (§3.10): id maszyny → ostatnia pozycja. */
    var fleet by mutableStateOf<Map<String, WarProtocol.FleetMachine>>(emptyMap())
        private set
    /** Tylko do zrzutów ekranu (instrukcja): inne maszyny i wiadomość głosowa bez serwera. */
    fun setFleetForScreenshots(m: List<WarProtocol.FleetMachine>) { fleet = m.associateBy { it.id } }
    fun addVoiceForScreenshots(from: String) { voice = voice + eu.smartsteer.navigator.core.war.VoiceMsg("glos-zrzut", from, "", 5.0, false, ByteArray(0)) }
    private val assembler = FieldPartsAssembler()
    private var deviceSentThisRun = false
    private var nextPollMs = 0L
    private var nextFlushMs = 0L
    private var nextTestMs = 0L
    private var nextPositionMs = 0L
    private var backoffUntilMs = 0L
    private var inactiveNav = false

    init {
        if (config.machineId.isEmpty()) {
            config = config.copy(machineId = WarProtocol.machineId(WarCrypto.random(8)))
            save()
        }
        queueSize = queueNames().size
        pendingAsk = loadPending()
    }

    val configured: Boolean get() = WarProtocol.isValidCode(config.code)

    // ---------------- WAR Cam: obraz z kamery tabletu do telefonu gospodarza (strona kamery WAR, jak w WAR Sync) ----------------
    /** Kamera nadaje (Android: okienko na Mapie; Windows: okno przeglądarki). */
    var camOn by mutableStateOf(false)
        private set

    fun cameraUrl(): String? {
        if (!configured || worksBox.isEmpty()) return null
        val name = config.tractorName.ifEmpty { hw.profileName }.ifEmpty { "Navigator" }
        val enc = name.encodeToByteArray().joinToString("") { b ->
            val c = (b.toInt() and 0xFF)
            if (c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A || c == 0x2D || c == 0x5F || c == 0x2E) c.toChar().toString()
            else "%" + c.toString(16).uppercase().padStart(2, '0')
        }
        return config.server.trimEnd('/') + "/kamera#s=" + worksBox + "&auto=1&n=" + enc
    }

    /** Włącza/wyłącza WAR Cam; zwraca komunikat dla rolnika albo null. */
    fun toggleCamera(): String? {
        val url = cameraUrl() ?: return "Najpierw połącz z WAR (kod nawigacji)"
        if (eu.smartsteer.navigator.ui.war.warCamInApp) { camOn = !camOn; return null }
        return if (eu.smartsteer.navigator.core.war.openUrl(url)) "Kamera nadaje z okna przeglądarki — zamknij je, żeby przestać" else "Nie udało się otworzyć przeglądarki"
    }
    fun stopCamera() { camOn = false }

    // ---------------- wiadomości głosowe (docs/WAR.md 3.11) — z telefonu gospodarza i do niego ----------------
    /** Ostatnie wiadomości (najnowsza na końcu). */
    var voice by mutableStateOf<List<eu.smartsteer.navigator.core.war.VoiceMsg>>(emptyList())
        private set
    var voiceRecording by mutableStateOf(false)
        private set
    var voiceStartedMs = 0L
        private set
    private var voiceBox = ""
    private var nextVoiceMs = 0L

    /** Mikrofon: pierwsze naciśnięcie — nagrywa, drugie — wysyła do WAR (telefon gospodarza). Zwraca komunikat albo null. */
    fun voiceButton(): String? {
        val a = eu.smartsteer.navigator.ui.voice.VoiceAudio
        if (!voiceRecording) {
            if (!configured) return "Najpierw połącz z WAR (kod nawigacji)"
            a.start()?.let { return it }
            voiceRecording = true
            voiceStartedMs = nowMillis()
            return null
        }
        voiceRecording = false
        val pcm = a.stop() ?: return "Nic nie nagrano"
        val s = eu.smartsteer.navigator.core.war.WarVoice.resample16to12(pcm)
        val sec = s.size.toDouble() / eu.smartsteer.navigator.core.war.WarVoice.RATE
        if (sec < 0.5) return "Za krótko — przytrzymaj dłużej"
        if (eu.smartsteer.navigator.core.war.WarVoice.level(pcm) < 0.005) return "Cisza — mikrofon nic nie słyszy (wyciszony?)"
        val wav = eu.smartsteer.navigator.core.war.WarVoice.wav(s)
        val id = "glos-" + WarCrypto.random(6).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val from = config.operator.ifEmpty { config.tractorName.ifEmpty { hw.profileName } }
        val t = nowIso()
        enqueue(id, eu.smartsteer.navigator.core.war.WarVoice.packet(id, from, config.machineId, t, sec, wav))
        voice = (voice + eu.smartsteer.navigator.core.war.VoiceMsg(id, "Ja", t, sec, true, wav)).takeLast(10)
        return "Wysłano (${sec.toInt()} s) — gospodarz usłyszy w WAR na telefonie"
    }

    fun playVoice(m: eu.smartsteer.navigator.core.war.VoiceMsg) = eu.smartsteer.navigator.ui.voice.VoiceAudio.play(m.wav)

    private suspend fun voiceStep(now: Long) {
        val k = key ?: return
        if (now < nextVoiceMs || voiceBox.isEmpty()) return
        nextVoiceMs = now + 15_000
        val r = call("GET", "/api/sync/zlecenia?skrzynka=$voiceBox", null, backoffOn429 = false)
        if (r.code != 200) return                     // 403: nikt jeszcze nic nie nagrał do tego tabletu
        val list = (MiniJson.parse(r.body) as? Map<*, *>)?.get("zlecenia") as? List<*> ?: return
        val done = ArrayList<String>()
        for (e in list) {
            val m = e as? Map<*, *> ?: continue
            val id = m["id"] as? String ?: continue
            val plain = withContext(Dispatchers.Default) { WarCrypto.decrypt(k, m["dane"] as? String ?: "") } ?: continue
            done.add(id)
            val p = MiniJson.parse(plain.decodeToString()) as? Map<*, *> ?: continue
            if (p["typ"] != "glos" || voice.any { it.id == p["id"] }) continue
            val wav = runCatching { eu.smartsteer.navigator.core.ntrip.Ntrip.unbase64(p["wav"] as? String ?: "") }.getOrNull() ?: continue
            if (!eu.smartsteer.navigator.core.war.WarVoice.isWav(wav)) continue
            val from = (p["od"] as? String).orEmpty().ifEmpty { "WAR" }.take(40)
            val msg = eu.smartsteer.navigator.core.war.VoiceMsg((p["id"] as? String).orEmpty(), from, (p["czas"] as? String).orEmpty(),
                (p["s"] as? Number)?.toDouble() ?: 0.0, false, wav)
            voice = (voice + msg).takeLast(10)
            hw.mapMessage("📞 Wiadomość głosowa od $from")
            if (!voiceRecording) eu.smartsteer.navigator.ui.voice.VoiceAudio.play(wav)
        }
        if (done.isNotEmpty()) call("POST", "/api/sync/zlecenia/odebrane", Json.obj("skrzynka" to voiceBox, "ids" to done), backoffOn429 = false)
    }
    val newOrders: List<WarOrder> get() = orders.filter {
        it.status == "nowe" && it.local.isEmpty() && (it.takenByMachine.isEmpty() || it.takenByMachine == config.machineId)
    }

    // ---------------- ustawienia ----------------

    /** Zapis kodu nawigacji; zwraca błąd albo null. */
    fun setCode(raw: String): String? {
        val c = WarProtocol.normalizeCode(raw)
        if (c.isEmpty()) {
            update(config.copy(code = ""))
            status = "Nie połączono — wpisz kod nawigacji"
            return null
        }
        if (!WarProtocol.isValidCode(c)) return "Kod ma 16 znaków (litery i cyfry, bez I, O, 0, 1) — sprawdź w WAR → Gospodarstwo → Maszyny → WAR Sync"
        if (c != config.code) {
            update(config.copy(code = c, lastDeviceMs = 0))
            key = null
            codeActive = null
            deviceSentThisRun = false
            inactiveNav = false
        }
        nextTestMs = 0; nextPollMs = 0; nextFlushMs = 0
        status = "Łączę z WAR…"
        return null
    }

    fun update(c: WarConfig) {
        config = c
        save()
    }

    private fun save() { try { files.write(CONFIG, config.toJson()) } catch (_: Exception) {} }

    // ---------------- pętla ----------------

    suspend fun loop() {
        while (true) {
            delay(1000)
            // wiadomość głosowa: najwyżej 30 s — po czasie wysyłamy sami
            if (voiceRecording && nowMillis() - voiceStartedMs >= eu.smartsteer.navigator.core.war.WarVoice.MAX_S * 1000L) voiceButton()?.let { hw.mapMessage(it) }
            try { step() } catch (e: Exception) { status = "Błąd WAR: ${e.message}" }
        }
    }

    private suspend fun step() {
        if (!configured) return
        if (!ensureKey()) return
        val now = nowMillis()
        releaseOldHeld(now)
        if (now < backoffUntilMs) return
        // test kodu: po wpisaniu i co 10 min
        if (now >= nextTestMs) {
            nextTestMs = now + 10 * 60_000
            val r = call("GET", "/api/sync/prace?skrzynka=$worksBox&licz=1", null)
            when {
                r.code == 200 -> {
                    val m = MiniJson.parse(r.body) as? Map<*, *>
                    codeActive = m?.get("aktywny") as? Boolean ?: true
                    if (codeActive == false) status = "Kod nie należy do konta WAR z modułem WAR Sync"
                }
                r.code == 402 || r.code == 403 -> { codeActive = false; status = detail(r) }
                r.code == 0 -> { status = "Brak internetu — dane czekają w kolejce"; nextTestMs = now + 60_000 }
                else -> nextTestMs = now + 60_000
            }
        }
        if (codeActive == false) return
        // zgłoszenie urządzenia: po starcie (najwyżej raz na dobę) i co 7 dni
        val age = now - config.lastDeviceMs
        if ((!deviceSentThisRun && age > DAY) || age > 7 * DAY) {
            deviceSentThisRun = true
            enqueue("dev-${config.machineId}-$now", WarProtocol.device(config, version, nowIso()))
            update(config.copy(lastDeviceMs = now))
        }
        if (now >= nextFlushMs) { nextFlushMs = now + 20_000; flush() }
        if (now >= nextPollMs) { nextPollMs = now + if (inactiveNav) 180_000 else 60_000; poll() }
        position(now)
        fleetStep(now)
        voiceStep(now)
    }

    // ---------------- flota: inne maszyny gospodarstwa (§3.10) ----------------

    private suspend fun fleetPost(kind: String, plain: String) {
        val k = key ?: return
        val dane = withContext(Dispatchers.Default) { WarCrypto.encrypt(k, plain.encodeToByteArray()) }
        // 429 przy flocie to nie błąd — następna paczka przejdzie (bez wstrzymywania reszty WAR)
        call("POST", "/api/sync/flota", Json.obj("skrzynka" to fleetBox, "skrzynka_prac" to worksBox, "maszyna" to config.machineId,
            "rodzaj" to kind, "dane" to dane), backoffOn429 = false)
    }

    private suspend fun fleetStep(now: Long) {
        val s = hw.session
        val k = key ?: return
        if (!config.fleet) { if (fleet.isNotEmpty()) fleet = emptyMap(); return }
        val share = hw.shareCoverage
        val working = s.engaged || s.sections.anyOn()
        // własna pozycja dla innych (ten sam wyłącznik co „Pokazuj na mapie WAR”)
        if (config.live && hw.hardware && now >= nextFleetPosMs) {
            val g = s.lastGga
            if (g != null && s.gpsAlive(hw.now())) {
                nextFleetPosMs = now + if (working) 5_000 else 30_000
                val fix = when (g.fix) { FixQuality.GPS -> 1; FixQuality.DGPS -> 2; FixQuality.RTK_FIX -> 4; FixQuality.RTK_FLOAT -> 5; else -> 0 }
                val course = ((s.headingRad * 180 / PI) % 360 + 360) % 360
                fleetPost("pozycja", WarProtocol.fleetPosition(
                    g.latitude, g.longitude, course, s.speedKmh, fix, config.toolName.ifEmpty { hw.profileName }, hw.profile.toolWidth,
                    s.fieldWarId, s.fieldName.orEmpty(), working, config.tractorName.ifEmpty { hw.profileName }, config, nowIso(),
                    eu.smartsteer.navigator.ui.DisplayPrefs.trailColor,
                ))
            }
        }
        pendingFleetCoverage?.let { pendingFleetCoverage = null; fleetPost("pokrycie", it) }
        // odczyt innych: co 5 s w pracy, co 30 s na postoju
        if ((config.live || share) && now >= nextFleetReadMs) {
            nextFleetReadMs = now + if (working) 5_000 else 30_000
            val od = fleetOd?.let { "&od=" + it.replace("+", "%2B").replace(":", "%3A") }.orEmpty()
            val r = call("GET", "/api/sync/flota?skrzynka=$fleetBox&maszyna=${config.machineId}$od", null, backoffOn429 = false)
            if (r.code == 200) {
                val m = MiniJson.parse(r.body) as? Map<*, *>
                (m?.get("ts") as? String)?.let { fleetOd = it }
                val list = m?.get("maszyny") as? List<*> ?: emptyList<Any>()
                var f = fleet
                for (e in list) {
                    val it = e as? Map<*, *> ?: continue
                    val who = it["maszyna"] as? String ?: continue
                    if (who == config.machineId) continue           // własne paczki wracają — pomijamy
                    val dane = it["dane"] as? String ?: continue
                    val plain = withContext(Dispatchers.Default) { WarCrypto.decrypt(k, dane) } ?: continue
                    val p = MiniJson.parse(plain.decodeToString()) as? Map<*, *> ?: continue
                    when (p["typ"]) {
                        "pozycja_floty" -> WarProtocol.parseFleetPosition(p, who, now)?.let { fm -> f = f + (who to fm) }
                        "pokrycie_floty" -> {
                            val warId = (p["pole"] as? Map<*, *>)?.get("war_id") as? String ?: continue
                            val g = WarProtocol.parseGrid(p["pokrycie"] as? Map<*, *>) ?: continue
                            hw.onOthersCoverage(warId, who, (p["pojazd"] as? String).orEmpty().ifEmpty { "inna maszyna" }, g, WarProtocol.fleetColor(p))
                        }
                    }
                }
                // lokalizatory WAR Track (maszyny bez Navigatora): serwer podaje za każdym razem wszystkie świeże
                f = f.filterValues { !it.tracker } + WarProtocol.parseTrackers(m?.get("lokalizatory"), now).associateBy { it.id }
                fleet = f.filterValues { now - it.receivedMs < 5 * 60_000 }   // znika po 5 min ciszy
            } else if (fleet.isNotEmpty()) {
                fleet = fleet.filterValues { now - it.receivedMs < 5 * 60_000 }
            }
        }
    }

    private suspend fun ensureKey(): Boolean {
        val c = config.code
        if (key != null && keyCode == c) return true
        val k = withContext(Dispatchers.Default) { WarCrypto.deriveKey(c) }
        key = k
        keyCode = c
        worksBox = WarCrypto.sha256Hex(WarProtocol.worksMailboxText(c))
        navBox = WarCrypto.sha256Hex(WarProtocol.navMailboxText(c))
        voiceBox = WarCrypto.sha256Hex(eu.smartsteer.navigator.core.war.WarVoice.mailboxText(c, config.machineId))
        ownNavBox = WarCrypto.sha256Hex(WarProtocol.ownNavMailboxText(c, config.machineId))
        fleetBox = WarCrypto.sha256Hex(WarProtocol.fleetMailboxText(c))
        fleetOd = null
        return true
    }

    private suspend fun call(method: String, path: String, body: String?, backoffOn429: Boolean = true): HttpResult {
        val url = config.server.trimEnd('/') + path
        val r = withContext(Dispatchers.Default) { httpJson(method, url, body, "SmartSteer-Navigator/$version") }
        if (r.code == 429 && backoffOn429) backoffUntilMs = nowMillis() + 60_000
        return r
    }

    private fun detail(r: HttpResult): String =
        ((MiniJson.parse(r.body) as? Map<*, *>)?.get("detail") as? String)?.removePrefix("NIEAKTYWNY:")?.trim()
            ?.ifEmpty { null } ?: "Kod nieaktywny w WAR (HTTP ${r.code})"

    // ---------------- kolejka wysyłki ----------------

    private fun queueNames() = try { files.list().filter { it.startsWith("q_") && it.endsWith(".json") }.sorted() } catch (_: Exception) { emptyList() }

    /** Paczka do wysłania (zapis na dysku, wysyłka z ponawianiem). */
    fun enqueue(id: String, plainJson: String) {
        try { files.write("q_$id.json", Json.obj("id" to id, "plain" to plainJson)) } catch (_: Exception) {}
        queueSize = queueNames().size
        nextFlushMs = 0
    }

    private suspend fun flush() {
        val k = key ?: return
        for (name in queueNames()) {
            val m = files.read(name)?.let { MiniJson.parse(it) as? Map<*, *> }
            val id = m?.get("id") as? String
            val plain = m?.get("plain") as? String
            if (id == null || plain == null) { files.delete(name); continue }
            val dane = withContext(Dispatchers.Default) { WarCrypto.encrypt(k, plain.encodeToByteArray()) }
            // wiadomość głosowa: większa paczka (do 1,7 MB), WAR powiadamia telefon gospodarza
            val r = call("POST", "/api/sync/praca", Json.obj("skrzynka" to worksBox, "id" to id, "dane" to dane, "zrodlo" to "Navigator",
                "glos" to if (id.startsWith("glos-")) true else null))
            when (r.code) {
                200 -> { files.delete(name); lastSentText = nowIso().take(16).replace('T', ' ') }
                400 -> files.delete(name) // zła paczka — ponawianie nic nie da
                402, 403 -> { status = detail(r); codeActive = false; break }
                else -> { if (r.code == 0) status = "Brak internetu — dane czekają w kolejce"; break }
            }
        }
        queueSize = queueNames().size
        if (codeActive == true && queueSize == 0 && !inactiveNav) status = "Połączono z WAR"
    }

    // ---------------- odbiór pól i zleceń ----------------

    /** Własna skrzynka (nowy WAR) i wspólna (starszy WAR / starsze Navigatory w gospodarstwie). */
    private suspend fun poll() {
        val own = pollBox(ownNavBox)
        val shared = pollBox(navBox)
        val codes = listOf(own.code, shared.code)
        when {
            200 in codes -> { inactiveNav = false; if (codeActive == true) status = "Połączono z WAR" }
            codes.all { it == 402 || it == 403 } -> {
                inactiveNav = true
                status = if (codeActive == true) "Czekam na WAR — otwórz WAR na telefonie" else detail(shared)
            }
            0 in codes -> status = "Brak internetu — dane czekają w kolejce"
        }
    }

    private suspend fun pollBox(box: String): HttpResult {
        val k = key ?: return HttpResult(0, "")
        val r = call("GET", "/api/sync/zlecenia?skrzynka=$box", null)
        if (r.code != 200) return r
        val list = (MiniJson.parse(r.body) as? Map<*, *>)?.get("zlecenia") as? List<*> ?: return r
        val done = ArrayList<String>()
        for (e in list) {
            val m = e as? Map<*, *> ?: continue
            val id = m["id"] as? String ?: continue
            val dane = m["dane"] as? String ?: continue
            val plain = withContext(Dispatchers.Default) { WarCrypto.decrypt(k, dane) } ?: continue // inny kod — nie ruszamy
            if (handle(plain.decodeToString())) done.add(id)
        }
        if (done.isNotEmpty()) {
            call("POST", "/api/sync/zlecenia/odebrane", Json.obj("skrzynka" to box, "ids" to done))
            if (list.size >= 100) nextPollMs = 0
        }
        return r
    }

    /** Obsługa odszyfrowanej paczki; true = zapisano, można potwierdzić odbiór. */
    private fun handle(json: String): Boolean {
        val (typ, m) = WarProtocol.parse(json) ?: return true // nie JSON — potwierdzamy, żeby nie wisiało
        when (typ) {
            "pola_war" -> {
                val all = assembler.add(m)
                if (all != null) {
                    fields = all
                    saveFields()
                }
            }
            "zlecenie" -> {
                val o = WarProtocol.parseOrder(m) ?: return true
                val old = orders.firstOrNull { it.id == o.id }
                val merged = o.copy(local = if (o.status == "anulowane") "anulowane" else old?.local ?: "")
                orders = (orders.filter { it.id != o.id } + merged).takeLast(200)
                saveOrders()
                hw.touch()
            }
            "pokrycie_innych" -> {
                // wspólna praca: pokrycie innej maszyny gospodarstwa na tym samym polu
                val who = (m["komputer"] as? String).orEmpty()
                if (who == config.machineId) return true
                val warId = (m["pole"] as? Map<*, *>)?.get("war_id") as? String ?: return true
                val g = WarProtocol.parseGrid(m["pokrycie"] as? Map<*, *>) ?: return true
                hw.onOthersCoverage(warId, who, (m["pojazd"] as? String).orEmpty().ifEmpty { "inna maszyna" }, g)
            }
            "produkty_war" -> {
                try { files.write(PRODUCTS, json) } catch (_: Exception) { return false }
                products = WarProducts.parse(m)
            }
            "mapa_dawek" -> {
                val rm = WarRateMap.parse(m) ?: return true
                val old = rateMapFor(rm.warId)
                if (old != null && old.sentAt > rm.sentAt) return true          // starsza wysyłka przyszła później
                try { files.write(WarRateMap.fileName(rm.warId), json) } catch (_: Exception) { return false }
                hw.onWarRateMap(rm)
            }
        }
        return true
    }

    /** Zdarzenie dla gospodarza w WAR (np. zatrzymanie przez przeszkodę) — paczka „zdarzenie” do skrzynki prac. */
    fun reportEvent(kind: String, text: String) {
        if (!configured) return
        val s = hw.session
        val g = s.lastGga
        val id = "ev-${config.machineId}-${nowMillis()}"
        enqueue(id, Json.obj(
            "typ" to "zdarzenie", "v" to 1, "id" to id, "rodzaj" to kind, "tekst" to text.take(200), "komputer" to config.machineId,
            "pojazd" to config.tractorName.ifEmpty { hw.profileName }, "pole" to s.fieldName, "t" to nowIso(),
            "lat" to g?.latitude, "lon" to g?.longitude, "kmh" to Json.Raw(((s.speedKmh * 10).toInt() / 10.0).toString()),
        ))
    }

    /** Wysyła bieżące pokrycie pola innym maszynom gospodarstwa (skrzynka floty, §3.10). */
    fun sendLiveCoverage(warId: String, fieldName: String, g: CoverageGrid) {
        pendingFleetCoverage = WarProtocol.fleetCoverage(config, warId, config.tractorName.ifEmpty { hw.profileName }, g, nowIso(), eu.smartsteer.navigator.ui.DisplayPrefs.trailColor)
    }

    private fun loadProducts(): WarProducts = try {
        files.read(PRODUCTS)?.let { WarProtocol.parse(it)?.second }?.let { WarProducts.parse(it) } ?: WarProducts()
    } catch (_: Exception) { WarProducts() }

    // ---------------- „co robiłeś?” po zamknięciu pola ----------------

    private fun heldNames() = try { files.list().filter { it.startsWith("h_") && it.endsWith(".json") }.sorted() } catch (_: Exception) { emptyList() }

    private fun loadPending(): PendingAsk? {
        for (name in heldNames()) {
            val m = files.read(name)?.let { MiniJson.parse(it) as? Map<*, *> } ?: run { files.delete(name); null } ?: continue
            val id = m["id"] as? String ?: run { files.delete(name); continue }
            val sug = (m["podpowiedz"] as? Map<*, *>)?.let { parseTreatment(it) }
            return PendingAsk(id, m["pole"] as? String ?: "", (m["ha"] as? Number)?.toDouble() ?: 0.0, m["narzedzie"] as? String ?: "", sug)
        }
        return null
    }

    /** Odpowiedź (albo null = pomiń): praca idzie do kolejki z polem „zabieg”. */
    fun answer(id: String, t: WarTreatment?) {
        val name = "h_$id.json"
        val m = files.read(name)?.let { MiniJson.parse(it) as? Map<*, *> }
        val plain = m?.get("plain") as? String
        if (plain != null) {
            val full = if (t != null && plain.endsWith("}")) plain.dropLast(1) + ",\"zabieg\":" + t.toJson() + "}" else plain
            enqueue(id, full)
            if (t != null) try { files.write(LAST_TREATMENT, Json.obj("narzedzie" to (m["narzedzie"] as? String ?: ""), "zabieg" to Json.Raw(t.toJson()))) } catch (_: Exception) {}
        }
        files.delete(name)
        pendingAsk = loadPending()
    }

    /** Wstrzymane prace bez odpowiedzi przez 12 h idą bez szczegółów — WAR i tak zapyta rolnika na telefonie. */
    private fun releaseOldHeld(now: Long) {
        for (name in heldNames()) {
            val m = files.read(name)?.let { MiniJson.parse(it) as? Map<*, *> } ?: continue
            val t = (m["t"] as? Number)?.toLong() ?: 0L
            if (now - t > 12 * 60 * 60 * 1000L) (m["id"] as? String)?.let { answer(it, null) }
        }
    }

    private fun suggestion(order: WarOrder?, warId: String?, tool: String): WarTreatment? {
        if (order != null && order.kind in WarTreatment.KINDS.map { it.first }) {
            return WarTreatment(order.kind, order.product, products.sprays.firstOrNull { it.name.equals(order.product, true) }?.permit.orEmpty(), order.dose, order.unit)
        }
        rateMapFor(warId)?.let { rm ->
            val k = rm.kindName.lowercase()
            val kind = when { k.startsWith("siew") -> "siew"; k.startsWith("oprysk") -> "oprysk"; else -> "nawoz" }
            return WarTreatment(kind, rm.product, dose = rm.rates[2], unit = rm.unit, variableRate = true)
        }
        val last = files.read(LAST_TREATMENT)?.let { MiniJson.parse(it) as? Map<*, *> } ?: return null
        if ((last["narzedzie"] as? String) != tool) return null
        return (last["zabieg"] as? Map<*, *>)?.let { parseTreatment(it) }
    }

    private fun parseTreatment(m: Map<*, *>): WarTreatment? {
        val kind = m["rodzaj"] as? String ?: return null
        return WarTreatment(
            kind, m["produkt"] as? String ?: "", m["nr"] as? String ?: "", (m["dawka"] as? Number)?.toDouble(),
            m["jedn"] as? String ?: "", m["uprawa"] as? String ?: "", m["zmienna_dawka"] == true,
        )
    }

    /** Ostatnia mapa dawek przysłana z WAR dla pola o tym id (albo null). */
    fun rateMapFor(warId: String?): WarRateMap? = try {
        warId?.let { files.read(WarRateMap.fileName(it)) }?.let { WarProtocol.parse(it)?.second }?.let { WarRateMap.parse(it) }
    } catch (_: Exception) { null }

    private fun saveFields() {
        try { files.write(FIELDS, fields.joinToString(",\n", "[", "]") { WarProtocol.fieldJson(it) }) } catch (_: Exception) {}
    }

    private fun loadFields(): List<WarField> = try {
        (files.read(FIELDS)?.let { MiniJson.parse(it) } as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { m -> WarProtocol.parseField(m) } }
    } catch (_: Exception) { emptyList() }

    private fun saveOrders() {
        try { files.write(ORDERS, orders.joinToString(",\n", "[", "]") { WarProtocol.orderJson(it) }) } catch (_: Exception) {}
    }

    private fun loadOrders(): List<WarOrder> = try {
        (files.read(ORDERS)?.let { MiniJson.parse(it) } as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { m -> WarProtocol.parseOrderWithLocal(m) } }
    } catch (_: Exception) { emptyList() }

    // ---------------- zlecenia ----------------

    /** Przyjęcie zlecenia: status do WAR i otwarcie pola zlecenia. Zwraca komunikat. */
    fun accept(o: WarOrder): String {
        setLocal(o.id, "przyjete")
        enqueue("st-${o.id}-${nowMillis()}", WarProtocol.orderStatus(o.id, "przyjete", config, nowIso()))
        activeOrderId = o.id
        val f = o.warField ?: return "Przyjęto: ${o.title}"
        val err = hw.openWarField(f)
        activeOrderId = o.id // otwarcie pola czyści przypisanie — przywracamy
        return err ?: "Przyjęto: ${o.title} — pole otwarte"
    }

    fun reject(o: WarOrder) {
        setLocal(o.id, "odrzucone")
        enqueue("st-${o.id}-${nowMillis()}", WarProtocol.orderStatus(o.id, "odrzucone", config, nowIso()))
    }

    private fun setLocal(id: String, state: String) {
        orders = orders.map { if (it.id == id) it.copy(local = state) else it }
        saveOrders()
        hw.touch()
    }

    /** Wywoływane przy otwarciu innego pola — zlecenie nie przechodzi na inne pole. */
    fun onFieldOpened(warId: String?) {
        val o = orders.firstOrNull { it.id == activeOrderId }
        if (o == null || o.warField?.id != warId) activeOrderId = null
    }

    // ---------------- praca i pozycja ----------------

    /** Przy zamknięciu pola: paczka pracy (≥ 0,05 ha) i status „zrobione” przyjętego zlecenia. */
    fun onFieldClosed() {
        if (!configured) return
        val s = hw.session
        val f = s.fieldData() ?: return
        if (s.coverage.workedM2 < 500.0) return
        val plane = LocalPlane(f.originLat, f.originLon)
        val boundary = f.boundary?.map { plane.toGeo(it) }
        val center = f.boundary?.let { b -> plane.toGeo(eu.smartsteer.navigator.core.geo.Vec2(b.sumOf { it.x } / b.size, b.sumOf { it.y } / b.size)) }
            ?: s.pivot?.let { plane.toGeo(it) } ?: (f.originLat to f.originLon)
        val id = "nav-${config.machineId}-${nowMillis()}"
        val tool = config.toolName.ifEmpty { hw.profileName }
        val work = WarProtocol.Work(
            id = id, fieldName = f.name, warId = f.warId, tool = tool, width = hw.profile.toolWidth,
            tractor = config.tractorName.ifEmpty { hw.profileName }, start = hw.fieldStartedIso.ifEmpty { nowIso() }, end = nowIso(),
            ha = s.coverage.workedM2 / 10_000, haPasses = s.coverage.passesM2 / 10_000,
            haField = f.boundary?.let { Polygon.area(it) / 10_000 },
            pointLat = center.first, pointLon = center.second, boundary = boundary,
            orderId = activeOrderId, version = version, grid = CoverageGrid.from(s.coverage, plane),
            spray = hw.sprayer.sprayJson(s.coverage.workedM2 / 10_000),
        )
        val plain = WarProtocol.work(work, config)
        if (config.askTreatment) {
            val sug = suggestion(orders.firstOrNull { it.id == activeOrderId }, f.warId, tool)
            try {
                files.write("h_$id.json", Json.obj(
                    "id" to id, "plain" to plain, "t" to nowMillis(), "pole" to f.name, "ha" to s.coverage.workedM2 / 10_000,
                    "narzedzie" to tool, "podpowiedz" to sug?.let { Json.Raw(it.toJson()) },
                ))
                pendingAsk = loadPending()
            } catch (_: Exception) { enqueue(id, plain) }
        } else enqueue(id, plain)
        activeOrderId?.let { oid ->
            enqueue("st-$oid-${nowMillis()}", WarProtocol.orderStatus(oid, "zrobione", config, nowIso()))
            setLocal(oid, "zrobione")
        }
        activeOrderId = null
    }

    private suspend fun position(now: Long) {
        val s = hw.session
        if (!config.live || !hw.hardware || now < nextPositionMs) return
        val g = s.lastGga ?: return
        if (!s.gpsAlive(hw.now())) return
        val working = s.engaged || s.sections.anyOn()
        nextPositionMs = now + if (working) 5_000 else 30_000
        val k = key ?: return
        val fix = when (g.fix) { FixQuality.GPS -> 1; FixQuality.DGPS -> 2; FixQuality.RTK_FIX -> 4; FixQuality.RTK_FLOAT -> 5; else -> 0 }
        val course = ((s.headingRad * 180 / PI) % 360 + 360) % 360
        val plain = WarProtocol.position(
            g.latitude, g.longitude, course, s.speedKmh, fix, g.satellites,
            config.toolName.ifEmpty { hw.profileName }, hw.profile.toolWidth, s.fieldName ?: "", working, config, nowIso(),
        )
        val dane = withContext(Dispatchers.Default) { WarCrypto.encrypt(k, plain.encodeToByteArray()) }
        call("POST", "/api/sync/pozycja", Json.obj("skrzynka" to worksBox, "maszyna" to config.machineId, "dane" to dane))
    }

    companion object {
        private const val CONFIG = "war.cfg"
        private const val FIELDS = "pola_war.json"
        private const val PRODUCTS = "produkty_war.json"
        private const val LAST_TREATMENT = "zabieg_ostatni.json"
        private const val ORDERS = "zlecenia.json"
        private const val DAY = 24L * 60 * 60 * 1000
    }
}
