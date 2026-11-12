package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.settings.TextFiles
import eu.smartsteer.navigator.core.settings.VehicleProfile
import eu.smartsteer.navigator.core.sprayer.SprayerComputer
import eu.smartsteer.navigator.core.sprayer.SprayerConfig
import eu.smartsteer.navigator.core.sprayer.SprayerConfigText
import eu.smartsteer.navigator.core.sprayer.SprayerSim
import eu.smartsteer.navigator.core.sprayer.SprayerStatus

/** Ręczne ustawienie sekcji opryskiwacza: AUTO (wg automatu sekcji Navigatora), zawsze włączona, zawsze wyłączona. */
enum class SectionOverride(val label: String) { AUTO("AUTO"), ON("WŁ."), OFF("WYŁ.") }

/**
 * Komputer opryskiwacza w aplikacji: ustawienia (plik opryskiwacz.cfg), krok co 100 ms z prędkością
 * i sekcjami z Navigatora. Dopóki nie ma połączenia z płytką Maszyna (USB — etap 13b), pracuje na symulatorze.
 */
class SprayerController(private val files: TextFiles) {
    private val file = "opryskiwacz.cfg"
    private val tankFile = "zbiornik.cfg"

    var config by mutableStateOf(runCatching { SprayerConfigText.read(files.read(file)) }.getOrDefault(SprayerConfig()))
        private set
    val computer = SprayerComputer(config)
    private var sim = SprayerSim(nozzlesPerSection = config.nozzlesPerSection.first(), nozzleLpmAt3Bar = config.nozzle.lpmAt3Bar, pulsesPerLiter = config.pulsesPerLiter)
    val simulated get() = true

    var status: SprayerStatus? by mutableStateOf(null); private set
    /** Prośba o przejście do ekranu (nazwa z enum Screen) — miniatura na Mapie ↔ ekran Opryskiwacz. */
    var navRequest: String? by mutableStateOf(null)
    var overrides by mutableStateOf(List(9) { SectionOverride.AUTO }); private set
    /** Maska sekcji faktycznie wysłana (bit0 = sekcja 1). */
    var mask by mutableStateOf(0); private set
    var tick by mutableStateOf(0L); private set
    /** Impuls zaworu w trybie RĘCZNIE (przyciski ±), wysyłany raz. */
    private var manualPulse = 0
    private var tankSaveCountdown = 0
    /** Litry zużyte od wyzerowania licznika. */
    var usedL by mutableStateOf(0.0); private set

    init {
        runCatching { files.read(tankFile)?.trim()?.toDoubleOrNull() }.getOrNull()?.let { computer.tank.setLevel(it) }
    }

    fun update(c: SprayerConfig) {
        if (c == config) return
        config = c
        computer.reconfigure(c)
        sim = SprayerSim(nozzlesPerSection = c.nozzlesPerSection.first(), nozzleLpmAt3Bar = c.nozzle.lpmAt3Bar * c.liquidFactor, pulsesPerLiter = c.pulsesPerLiter)
        runCatching { files.write(file, SprayerConfigText.write(c)) }
        tick++
    }

    fun cycleOverride(i: Int) {
        overrides = overrides.mapIndexed { k, o -> if (k == i) SectionOverride.entries[(o.ordinal + 1) % 3] else o }
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

    /** Krok co [dtMs]: [autoOn] — stan sekcji z automatu Navigatora. */
    fun step(dtMs: Int, speedKmh: Double, autoOn: (Int) -> Boolean) {
        val n = config.sectionCount
        var m = 0
        for (i in 0 until n) {
            val on = when (overrides[i]) { SectionOverride.AUTO -> autoOn(i); SectionOverride.ON -> true; SectionOverride.OFF -> false }
            if (on) m = m or (1 shl i)
        }
        val before = computer.tank.liters
        val pulse = manualPulse; manualPulse = 0
        val cmd = computer.step(dtMs, speedKmh, m, status, pulse)
        sim.receive(cmd)
        repeat(dtMs / 10) { sim.step(10) }
        status = sim.status()
        mask = cmd.sections
        val used = before - computer.tank.liters
        if (used > 0) usedL += used
        if (++tankSaveCountdown >= 50) { tankSaveCountdown = 0; if (computer.running) saveTank() }
        tick++
    }
}
