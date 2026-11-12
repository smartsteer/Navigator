package eu.smartsteer.navigator.core.sprayer

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Symulator opryskiwacza z płytką Maszyna — do testów terminala bez sprzętu.
 *
 * Model: pompa + zawór regulacyjny (silnik, pełny przesuw [valveTravelMs]) ustawia ciśnienie
 * p = pMax · położenie, z bezwładnością [pressureTauS]. Przepływ = suma otwartych sekcji
 * × dysze × przepływ dyszy przy 3 bar × √(p / 3). Przepływomierz daje [pulsesPerLiter] imp./l.
 * Zachowuje się jak płytka: polecenie wykonywane raz na licznik, zabezpieczenie po 0,5 s ciszy.
 */
class SprayerSim(
    val nozzlesPerSection: Int = 6,
    val nozzleLpmAt3Bar: Double = 1.18,   // dysza 03 (niebieska)
    val maxPressureBar: Double = 8.0,
    val valveTravelMs: Int = 4000,
    val pressureTauS: Double = 0.3,
    val pulsesPerLiter: Double = 255.0,
    val hasPressureSensor: Boolean = true,
    val supplyVolts: Double = 13.8,
) {
    /** Położenie zaworu regulacyjnego 0..1. */
    var valvePosition = 0.4
        private set
    var pressureBar = 0.0
        private set
    var flowLpm = 0.0
        private set

    private var lastCmd: SprayerCommand? = null
    private var lastCounter = -1
    private var sinceCmdMs = Int.MAX_VALUE / 2
    private var pendingPulseMs = 0
    private var pulseFraction = 0.0
    private var pulseCount = 0L
    private var lastPeriodUs = 0L
    private var sinceLastPulseUs = 0L

    val failsafe get() = sinceCmdMs > SprayerPgn.FAILSAFE_MS

    fun receive(cmd: SprayerCommand) {
        sinceCmdMs = 0
        if (cmd.counter != lastCounter) {
            lastCounter = cmd.counter
            pendingPulseMs += cmd.regPulseMs
        }
        lastCmd = cmd
    }

    fun step(dtMs: Int) {
        sinceCmdMs += dtMs
        val cmd = lastCmd
        val active = cmd != null && !failsafe

        // Silnik zaworu regulacyjnego: wykonuje zaległy impuls z jego prędkością.
        if (pendingPulseMs != 0) {
            val run = if (pendingPulseMs > 0) minOf(pendingPulseMs, dtMs) else maxOf(pendingPulseMs, -dtMs)
            pendingPulseMs -= run
            valvePosition = (valvePosition + run.toDouble() / valveTravelMs).coerceIn(0.0, 1.0)
        }

        val mainOpen = active && cmd!!.mainOpen
        val target = if (mainOpen) maxPressureBar * valvePosition else 0.0
        val a = 1 - exp(-dtMs / 1000.0 / pressureTauS)
        pressureBar += (target - pressureBar) * a

        val open = if (mainOpen) (0 until cmd!!.sectionCount).count { cmd.isSectionOpen(it) } else 0
        flowLpm = open * nozzlesPerSection * nozzleLpmAt3Bar * sqrt(max(pressureBar, 0.0) / 3.0)

        // Przepływomierz.
        val dtUs = dtMs * 1000L
        val pulses = flowLpm / 60.0 * pulsesPerLiter * dtMs / 1000.0 + pulseFraction
        val whole = pulses.toLong()
        pulseFraction = pulses - whole
        sinceLastPulseUs += dtUs
        if (whole > 0) {
            pulseCount = (pulseCount + whole) and 0xFFFF_FFFFL
            lastPeriodUs = (60_000_000.0 / (flowLpm * pulsesPerLiter)).toLong()
            sinceLastPulseUs = 0
        } else if (sinceLastPulseUs > 2_000_000) {
            lastPeriodUs = 0
        }
    }

    fun status(): SprayerStatus {
        val cmd = lastCmd
        val active = cmd != null && !failsafe
        return SprayerStatus(
            counter = lastCounter.coerceAtLeast(0),
            linkOk = true,
            mainOpen = active && cmd!!.mainOpen,
            workLights = active && cmd!!.workLights,
            failsafe = failsafe,
            pulseCount = pulseCount,
            pulsePeriodUs = lastPeriodUs,
            pressureBar = if (hasPressureSensor) pressureBar else null,
            supplyVolts = supplyVolts,
        )
    }
}
