package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.aog.AogStream
import eu.smartsteer.navigator.core.aog.Frame
import eu.smartsteer.navigator.core.net.ByteLink
import eu.smartsteer.navigator.core.net.SprayerPorts
import eu.smartsteer.navigator.core.settings.TextFiles
import eu.smartsteer.navigator.core.settings.VehicleProfile
import eu.smartsteer.navigator.core.sprayer.SprayerAlarm
import eu.smartsteer.navigator.core.sprayer.SprayerAlarms
import eu.smartsteer.navigator.core.sprayer.SprayerComputer
import eu.smartsteer.navigator.core.sprayer.SprayerConfig
import eu.smartsteer.navigator.core.sprayer.SprayerConfigText
import eu.smartsteer.navigator.core.sprayer.SprayerPgn
import eu.smartsteer.navigator.core.sprayer.SprayerSim
import eu.smartsteer.navigator.core.sprayer.SprayerStatus
import eu.smartsteer.navigator.core.sprayer.sprayReportJson
import kotlin.time.TimeSource

/** Ręczne ustawienie sekcji opryskiwacza: AUTO (wg automatu sekcji Navigatora), zawsze włączona, zawsze wyłączona. */
enum class SectionOverride(val label: String) { AUTO("AUTO"), ON("WŁ."), OFF("WYŁ.") }

/** Skąd przychodzi opryskiwacz: symulator, płytka Maszyna na USB, płytka Ster w sieci (CAN). */
enum class SprayerSource(val label: String) { SIM("Symulator"), USB("Płytka Maszyna — USB / Bluetooth"), NET("Płytka Ster — sieć (CAN)") }

/**
 * Komputer opryskiwacza w aplikacji: ustawienia (opryskiwacz.cfg), połączenie (opryskiwacz_polaczenie.cfg),
 * krok co 100 ms z prędkością i sekcjami z Navigatora, alarmy, zużycie cieczy na polu.
 */
class SprayerController(private val files: TextFiles) {
    private val file = "opryskiwacz.cfg"
    private val tankFile = "zbiornik.cfg"
    private val linkFile = "opryskiwacz_polaczenie.cfg"
    private val clock = TimeSource.Monotonic.markNow()
    private fun nowMs() = clock.elapsedNow().inWholeMilliseconds

    var config by mutableStateOf(runCatching { SprayerConfigText.read(files.read(file)) }.getOrDefault(SprayerConfig()))
        private set
    val computer = SprayerComputer(config)
    private var sim = newSim(config)
    private val alarms = SprayerAlarms()

    // ---------------- połączenie ----------------
    var source by mutableStateOf(SprayerSource.SIM); private set
    var usbPort by mutableStateOf(""); private set
    var linkStatus by mutableStateOf(""); private set
    private var link: ByteLink? = null
    @Volatile private var rxStatus: SprayerStatus? = null
    @Volatile private var rxAtMs = -1_000_000L
    /** Ramka do wysłania przez sieć (płytka Ster) — zabiera ją pętla sieci. */
    var pendingNetFrame: ByteArray? = null

    val simulated get() = source == SprayerSource.SIM
    /** Łączność z płytką: odpowiedź w ostatniej sekundzie (symulator — zawsze). */
    val linkOk get() = simulated || nowMs() - rxAtMs < 1000

    var status: SprayerStatus? by mutableStateOf(null); private set
    /** Prośba o przejście do ekranu (nazwa z enum Screen) — miniatura na Mapie ↔ ekran Opryskiwacz. */
    var navRequest: String? by mutableStateOf(null)
    var overrides by mutableStateOf(List(SprayerPgn.MAX_SECTIONS) { SectionOverride.AUTO }); private set
    /** Maska sekcji faktycznie wysłana (bit0 = sekcja 1). */
    var mask by mutableStateOf(0); private set
    var tick by mutableStateOf(0L); private set
    var activeAlarms by mutableStateOf<List<SprayerAlarm>>(emptyList()); private set
    private var manualPulse = 0
    private var tankSaveCountdown = 0
    /** Litry zużyte od wyzerowania licznika. */
    var usedL by mutableStateOf(0.0); private set
    /** Litry zużyte na bieżącym polu (do raportu i WAR). */
    var fieldUsedL by mutableStateOf(0.0); private set
    private var fieldName: String? = null

    /** Licznik hektarów opryskanych: dzienny (zerowany przez rolnika) i całkowity (nigdy nie zerowany). */
    private val haFile = "oprysk_ha.txt"
    var dailyHa by mutableStateOf(0.0); private set
    var totalHa by mutableStateOf(0.0); private set
    /** Kreator kalibracji (null = zwykła praca). */
    var calibration by mutableStateOf<eu.smartsteer.navigator.core.sprayer.CalibrationWizard?>(null); private set

    fun startCalibration() {
        calibration = eu.smartsteer.navigator.core.sprayer.CalibrationWizard(config)
        tick++
    }

    /** Koniec kreatora: [save] = zapisz wyniki, inaczej wróć do ustawień sprzed kreatora. Zawory zamknięte. */
    fun finishCalibration(save: Boolean) {
        val c = calibration ?: return
        calibration = null
        computer.running = false
        computer.autoRate = true
        update(if (save) c.result else c.original)
        tick++
    }

    private var learnCountdown = 0

    /** Co regulator nauczył się w pracy → ustawienia (bez przebudowy regulatora). */
    private fun storeLearned() {
        val l = computer.learned()
        val c = config
        val changed = kotlin.math.abs(l.gain - c.regGain) > 0.05 * maxOf(c.regGain, 1e-9) ||
            kotlin.math.abs(l.settleMs - c.regSettleMs) > 100 || kotlin.math.abs(l.backlashMs - c.regBacklashMs) > 50
        if (!changed) return
        config = c.copy(regGain = l.gain, regSettleMs = l.settleMs, regBacklashMs = l.backlashMs)
        computer.config = config
        runCatching { files.write(file, SprayerConfigText.write(config)) }
    }

    fun resetDailyHa() { dailyHa = 0.0; saveHa(); tick++ }
    private fun saveHa() { runCatching { files.write(haFile, "$dailyHa\n$totalHa\n") } }

    init {
        runCatching { files.read(haFile).orEmpty().lines().mapNotNull { it.trim().toDoubleOrNull() } }.getOrNull()?.let {
            dailyHa = it.getOrElse(0) { 0.0 }; totalHa = it.getOrElse(1) { 0.0 }
        }
        runCatching { files.read(tankFile)?.trim()?.toDoubleOrNull() }.getOrNull()?.let { computer.tank.setLevel(it) }
        runCatching { files.read(linkFile)?.trim() }.getOrNull()?.let { s ->
            when {
                s == "NET" -> source = SprayerSource.NET
                s.startsWith("USB|") -> { source = SprayerSource.USB; usbPort = s.substringAfter("USB|") }
            }
        }
    }

    private fun newSim(c: SprayerConfig) = SprayerSim(
        nozzlesPerSection = c.nozzlesPerSection.first(), nozzleLpmAt3Bar = c.nozzle.lpmAt3Bar * c.liquidFactor, pulsesPerLiter = c.pulsesPerLiter,
    )

    /** Prędkość użyta do dawki (wg źródła z ustawień). */
    var speedKmh by mutableStateOf(0.0); private set

    fun update(c: SprayerConfig) {
        if (c == config) return
        config = c
        computer.reconfigure(c)
        sim = newSim(c)
        runCatching { files.write(file, SprayerConfigText.write(c)) }
        tick++
    }

    /** Tryb „tylko nawigacja”: zamyka port płytki i czyści alarmy opryskiwacza. */
    fun disconnect() {
        link?.close(); link = null
        linkStatus = ""
        activeAlarms = emptyList()
        tick++
    }

    fun ports(): List<String> = runCatching { SprayerPorts.list() }.getOrDefault(emptyList())

    /** Wybór źródła; dla USB [port] z listy. */
    fun setSource(s: SprayerSource, port: String = usbPort) {
        link?.close(); link = null
        source = s
        usbPort = port
        rxStatus = null
        linkStatus = ""
        runCatching { files.write(linkFile, when (s) { SprayerSource.SIM -> "SIM"; SprayerSource.NET -> "NET"; SprayerSource.USB -> "USB|$port" }) }
        if (s == SprayerSource.USB) connectUsb()
        tick++
    }

    /** Otwiera port USB (przy starcie, po wyborze i co kilka sekund po zerwaniu); bezpieczne do wielokrotnego wywołania. */
    fun connectUsb() {
        if (source != SprayerSource.USB || link != null) return
        if (usbPort.isBlank()) { linkStatus = "Wybierz port płytki"; return }
        val l = SprayerPorts.create()
        val parser = AogStream()
        val err = runCatching {
            l.open(usbPort, onBytes = { b, n -> parser.feed(b, n) { f -> onFrame(f) } }, onClosed = { e ->
                link = null
                linkStatus = e ?: "Rozłączono"
            })
        }.getOrElse { it.message ?: "Błąd portu" }
        if (err == null) { link = l; linkStatus = "Połączono z ${usbPort.substringBefore(" — ")}" } else linkStatus = err
        tick++
    }

    /** Ramka od płytki (USB albo sieć). */
    fun onFrame(f: Frame) {
        if (f.pgn != SprayerPgn.STATUS) return
        SprayerStatus.from(f)?.let { rxStatus = it; rxAtMs = nowMs() }
    }

    /** Przełącznik sekcji: AUTO (sekcje z automatu, dotknięcie wyłącza/przywraca) albo RĘCZNIE (dotknięcie wł./wył.). */
    var sectionsAuto by mutableStateOf(true); private set

    fun switchSectionsAuto(auto: Boolean) {
        if (auto == sectionsAuto) return
        sectionsAuto = auto
        // przy zmianie trybu wyłączone zostają wyłączone, reszta: AUTO ↔ WŁ.
        overrides = overrides.map { o -> if (o == SectionOverride.OFF) o else if (auto) SectionOverride.AUTO else SectionOverride.ON }
        tick++
    }

    /** Jedno dotknięcie sekcji: AUTO ↔ WYŁ. w trybie AUTO, WŁ. ↔ WYŁ. w trybie RĘCZNIE. */
    fun cycleOverride(i: Int) {
        val on = if (sectionsAuto) SectionOverride.AUTO else SectionOverride.ON
        overrides = overrides.mapIndexed { k, o -> if (k == i) (if (o == SectionOverride.OFF) on else SectionOverride.OFF) else o }
        tick++
    }

    /** Wszystkie sekcje naraz: włączone (AUTO albo WŁ. wg trybu) albo wyłączone. */
    fun setAllSections(on: Boolean) {
        val v = if (!on) SectionOverride.OFF else if (sectionsAuto) SectionOverride.AUTO else SectionOverride.ON
        overrides = overrides.map { v }
        tick++
    }

    fun toggleRun() { computer.running = !computer.running; tick++ }
    fun setAuto(on: Boolean) { computer.autoRate = on; tick++ }
    fun toggleLights() { computer.workLights = !computer.workLights; tick++ }
    fun adjustRate(deltaPercent: Int) { computer.rateAdjustPercent = (computer.rateAdjustPercent + deltaPercent).coerceIn(-50, 50); tick++ }
    fun resetRateAdjust() { computer.rateAdjustPercent = 0; tick++ }
    fun manualValve(ms: Int) { manualPulse += ms }
    fun fill(liters: Double) { computer.tank.fill(liters); saveTank(); tick++ }
    fun setTank(liters: Double) { computer.tank.setLevel(liters); saveTank(); tick++ }
    fun resetUsed() { usedL = 0.0; tick++ }

    private fun saveTank() { runCatching { files.write(tankFile, computer.tank.liters.toString()) } }

    /** Szerokości sekcji opryskiwacza → profil maszyny (sekcje, szerokość robocza) dla automatu sekcji i pokrycia. */
    fun applyTo(p: VehicleProfile): VehicleProfile {
        val widths = config.nozzlesPerSection.indices.map { config.sectionWidthM(it) }
        return p.copy(numSections = widths.size, sectionWidths = widths, toolWidth = widths.sum(), isSprayer = true)
    }

    private var reconnectCountdown = 0

    /**
     * Krok co [dtMs]: [autoOn] — stan sekcji z automatu Navigatora, [field] — otwarte pole (zużycie liczone na pole).
     * Zwraca nowo włączone alarmy (do dźwięku).
     */
    fun step(dtMs: Int, gpsSpeedKmh: Double, field: String? = null, autoOn: (Int) -> Boolean): List<SprayerAlarm> {
        val speedKmh = when (config.speedSource) {
            eu.smartsteer.navigator.core.sprayer.SpeedSource.GPS -> gpsSpeedKmh
            eu.smartsteer.navigator.core.sprayer.SpeedSource.MANUAL -> config.manualSpeedKmh
            eu.smartsteer.navigator.core.sprayer.SpeedSource.BOARD_GPS -> status?.boardSpeedKmh ?: 0.0
        }
        this.speedKmh = speedKmh
        // symulator: GPS płytki jedzie z prędkością GPS tabletu, a bez niego — z prędkością „stałą” (do nauki obsługi)
        sim.groundSpeedKmh = if (gpsSpeedKmh > 0.3) gpsSpeedKmh else config.manualSpeedKmh
        if (field != fieldName) { fieldName = field; fieldUsedL = 0.0 }
        if (source == SprayerSource.USB && link == null && ++reconnectCountdown >= 30) { reconnectCountdown = 0; connectUsb() }
        val n = config.sectionCount
        var m = 0
        for (i in 0 until n) {
            val on = when (overrides[i]) { SectionOverride.AUTO -> autoOn(i); SectionOverride.ON -> true; SectionOverride.OFF -> false }
            if (on) m = m or (1 shl i)
        }
        val before = computer.tank.liters
        val pulse = manualPulse; manualPulse = 0
        val st = if (simulated) status else rxStatus.takeIf { linkOk }
        // kreator kalibracji przejmuje sterowanie: sekcje, START, regulację, impulsy zaworu i wirtualną prędkość
        val cal = calibration
        val calOut = cal?.step(eu.smartsteer.navigator.core.sprayer.CalibrationWizard.In(
            dtMs, simulated || linkOk, st?.pulseCount, computer.flowLpm, computer.pressureBar, st?.pressureBar, computer.targetLpm,
        ))
        if (cal != null && cal.applyRequest) { cal.applyRequest = false; update(cal.result) }
        if (calOut != null) { computer.running = calOut.running; computer.autoRate = calOut.autoRate; m = calOut.mask }
        val cmd = computer.step(dtMs, calOut?.speedKmh ?: speedKmh, m, st, if (calOut != null) calOut.pulseMs else pulse)
        when (source) {
            SprayerSource.SIM -> {
                sim.receive(cmd)
                repeat(dtMs / 10) { sim.step(10) }
                status = sim.status()
            }
            SprayerSource.USB -> {
                link?.write(cmd.toFrame())
                status = st
            }
            SprayerSource.NET -> {
                pendingNetFrame = cmd.toFrame()
                status = st
            }
        }
        mask = cmd.sections
        val used = before - computer.tank.liters
        if (used > 0) { usedL += used; fieldUsedL += used }
        // hektary: szerokość otwartych sekcji × przejechana droga (tylko przy pracy i jeździe)
        if (cal == null && cmd.mainOpen && cmd.sections != 0 && speedKmh > 0.3) {
            val ha = config.openWidthM(cmd.sections) * speedKmh / 3.6 * dtMs / 1000.0 / 10_000.0
            dailyHa += ha; totalHa += ha
        }
        if (cal == null && computer.running && ++learnCountdown >= 600) { learnCountdown = 0; storeLearned() }
        if (++tankSaveCountdown >= 50) { tankSaveCountdown = 0; if (computer.running) { saveTank(); saveHa() } }

        val p = computer.pressureBar
        val kind = config.nozzleKind
        val fresh = alarms.step(dtMs, SprayerAlarms.Input(
            running = computer.running,
            linkOk = linkOk,
            status = status,
            regulating = computer.autoRate && speedKmh >= config.minSpeedKmh && m != 0,
            rateOk = computer.rateOk(),
            pressureOk = p == null || p in kind.minBar..kind.maxBar,
            tankL = computer.tank.liters,
            reserveL = config.tankReserveL,
        ))
        val act = alarms.active.sortedBy { it.ordinal }
        if (act != activeAlarms) activeAlarms = act
        tick++
        return fresh
    }

    /** Fragment JSON o oprysku do raportu pola i paczki pracy WAR (null — opryskiwacz nie pracował na tym polu). */
    fun sprayJson(workedHa: Double): String? = sprayReportJson(config, fieldUsedL, workedHa)
}
