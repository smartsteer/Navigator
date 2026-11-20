package eu.smartsteer.navigator.core.sprayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SprayerComputerTest {

    @Test
    fun nozzleTableAndRateMath() {
        val n03 = Nozzle.ISO.first { it.name == "03" }
        assertTrue(abs(n03.flowAt(3.0) - 1.18) < 1e-9)
        assertTrue(abs(n03.flowAt(12.0) - 2.36) < 1e-9) // 4× ciśnienie = 2× przepływ
        assertTrue(abs(n03.pressureFor(2.36) - 12.0) < 1e-9)

        val c = SprayerConfig(nozzlesPerSection = List(7) { 6 }) // 42 dysze × 0,5 m = 21 m
        assertEquals(21.0, c.totalWidthM)
        // 200 l/ha × 8 km/h × 21 m / 600 = 56 l/min
        assertTrue(abs(c.targetFlowLpm(8.0, 0x7F) - 56.0) < 1e-9)
        assertTrue(abs(c.rateLha(56.0, 8.0, 0x7F) - 200.0) < 1e-9)
        // dwie sekcje wyłączone → 5/7 szerokości
        assertTrue(abs(c.targetFlowLpm(8.0, 0b0011111) - 40.0) < 1e-9)
        // na jedną dyszę 200 × 8 × 0,5 / 600 = 1,333 l/min → ok. 3,83 bar dla 03
        assertTrue(abs(c.pressureFor(8.0) - 3.83) < 0.01)
    }

    @Test
    fun tank() {
        val t = VirtualTank(1000.0)
        t.fill(800.0); t.consume(150.0)
        assertEquals(650.0, t.liters)
        assertTrue(abs(t.hectaresLeft(200.0) - 3.25) < 1e-9)
        t.fill(5000.0)
        assertEquals(1000.0, t.liters)
    }

    /** Pętla: komputer ↔ symulator, zwraca historię (czas ms, przepływ, cel). */
    private fun run(
        computer: SprayerComputer, sim: SprayerSim, ms: Int,
        speed: (Int) -> Double, mask: (Int) -> Int, t0: Int = 0,
        out: MutableList<Triple<Int, Double, Double>>,
    ) {
        var t = t0
        while (t < t0 + ms) {
            val cmd = computer.step(100, speed(t), mask(t), sim.status())
            sim.receive(cmd)
            repeat(10) { sim.step(10) }
            t += 100
            out += Triple(t, computer.flowLpm, computer.targetLpm)
        }
    }

    private fun settleTimeMs(h: List<Triple<Int, Double, Double>>, from: Int, tol: Double): Int? {
        val after = h.filter { it.first > from }
        val idx = after.indexOfFirst { _ -> true }
        if (idx < 0) return null
        // pierwszy moment, od którego przepływ zostaje w tolerancji do końca
        for (i in after.indices) {
            if (after.drop(i).all { abs(it.second - it.third) <= tol * it.third }) return after[i].first - from
        }
        return null
    }

    private fun scenario(mode: FlowMode, hasSensor: Boolean = true) {
        val cfg = SprayerConfig(flowMode = mode, nozzlesPerSection = List(9) { 6 }, targetLha = 200.0)
        val computer = SprayerComputer(cfg).apply { running = true }
        val sim = SprayerSim(nozzlesPerSection = 6, pulsesPerLiter = 255.0, hasPressureSensor = hasSensor)
        val h = mutableListOf<Triple<Int, Double, Double>>()
        val all = 0x1FF
        // 0–15 s: 8 km/h, 15–30 s: 12 km/h, 30–45 s: 12 km/h i 3 sekcje zgaszone
        run(computer, sim, 45_000, speed = { if (it < 15_000) 8.0 else 12.0 }, mask = { if (it < 30_000) all else 0b000111111 }, out = h)

        val start = settleTimeMs(h.filter { it.first <= 15_000 }, 0, 0.05)
        val speedUp = settleTimeMs(h.filter { it.first in 15_001..30_000 }, 15_000, 0.05)
        val sections = settleTimeMs(h.filter { it.first > 30_000 }, 30_000, 0.05)
        assertTrue(start != null && start <= 8_000, "$mode start: $start ms")
        assertTrue(speedUp != null && speedUp <= 5_000, "$mode zmiana prędkości: $speedUp ms")
        assertTrue(sections != null && sections <= 3_000, "$mode zmiana sekcji: $sections ms")
        // przeregulowanie po zmianie prędkości < 10 %
        val over = h.filter { it.first in 15_001..30_000 }.maxOf { (it.second - it.third) / it.third }
        assertTrue(over < 0.10, "$mode przeregulowanie ${over * 100} %")
        // dawka na końcu
        assertTrue(abs(computer.actualLha - 200.0) < 200.0 * 0.05, "$mode dawka ${computer.actualLha}")
    }

    /**
     * Prawdziwy świat: luz zaworu 300 ms, opóźnienie ciśnienia 0,7 s (długie węże), szum przepływomierza ±3 %.
     * Bez ustawiania czegokolwiek: dochodzi do dawki, nie buja się i uczy się luzu oraz czasu ustalania.
     */
    @Test
    fun regulationRealWorldNoTuning() {
        val cfg = SprayerConfig(flowMode = FlowMode.FLOWMETER, nozzlesPerSection = List(9) { 6 }, targetLha = 200.0)
        val computer = SprayerComputer(cfg).apply { running = true }
        val sim = SprayerSim(nozzlesPerSection = 6, pulsesPerLiter = 255.0, hasPressureSensor = false,
            backlashMs = 300, delayMs = 700, flowNoise = 0.03)
        val h = mutableListOf<Triple<Int, Double, Double>>()
        val pulses = mutableListOf<Pair<Int, Int>>()
        var t = 0
        val all = 0x1FF
        while (t < 90_000) {
            val speed = if (t < 30_000) 8.0 else if (t < 60_000) 10.5 else 7.0
            val mask = if (t < 45_000) all else 0b000111111
            val cmd = computer.step(100, speed, mask, sim.status())
            if (cmd.regPulseMs != 0) pulses += t to cmd.regPulseMs
            sim.receive(cmd)
            repeat(10) { sim.step(10) }
            t += 100
            h += Triple(t, computer.flowLpm, computer.targetLpm)
        }
        // tolerancja 8 %: szum przepływomierza ±3 % + strefa martwa ±2,5 %
        val start = settleTimeMs(h.filter { it.first <= 30_000 }, 0, 0.08)
        val speedUp = settleTimeMs(h.filter { it.first in 30_001..45_000 }, 30_000, 0.08)
        val sections = settleTimeMs(h.filter { it.first in 45_001..60_000 }, 45_000, 0.08)
        val slowDown = settleTimeMs(h.filter { it.first > 60_000 }, 60_000, 0.08)
        assertTrue(start != null && start <= 15_000, "start: $start ms")
        assertTrue(speedUp != null && speedUp <= 12_000, "zmiana prędkości: $speedUp ms")
        assertTrue(sections != null && sections <= 8_000, "zmiana sekcji: $sections ms")
        assertTrue(slowDown != null && slowDown <= 12_000, "zwolnienie: $slowDown ms")
        // bez bujania: w ostatnich 15 s najwyżej 2 zmiany kierunku zaworu
        val tail = pulses.filter { it.first > 75_000 }.map { if (it.second > 0) 1 else -1 }
        val reversals = tail.zipWithNext().count { (a, b) -> a != b }
        assertTrue(reversals <= 2, "bujanie: $reversals zmian kierunku w 15 s")
        val l = computer.learned()
        // dokładny luz mierzy kreator (CalibrationWizardTest); w pracy regulator tylko go poprawia
        assertTrue(l.backlashMs in 0..800, "luz wyuczony: ${l.backlashMs} ms (prawdziwy 300)")
        assertTrue(l.settleMs >= 400, "czas ustalania wyuczony: ${l.settleMs} ms")
        // średnia z ostatnich 10 s w ±4 %
        val lastErr = h.filter { it.first > 80_000 }.map { it.second / it.third - 1 }.average()
        assertTrue(abs(lastErr) < 0.04, "średni błąd dawki ${lastErr * 100} %")
    }

    @Test
    fun pressureSensorRescale() {
        // płytka liczy jak 0–10 bar 0,5–4,5 V; czujnik 0–20 bar: 2,5 V → płytka 5 bar → naprawdę 10 bar
        val c = SprayerConfig(pressureRangeBar = 20.0)
        assertTrue(abs(c.sensorBar(5.0)!! - 10.0) < 1e-9)
        // 4–20 mA, 0–16 bar: 12 mA × 150 Ω = 1,8 V → płytka (1,8−0,5)/4×10 = 3,25 bar → naprawdę 8 bar
        val m = SprayerConfig(pressureInput = PressureInput.MA, pressureRangeBar = 16.0)
        assertTrue(abs(m.sensorBar(3.25)!! - 8.0) < 1e-6)
        assertEquals(null, c.sensorBar(null))
        // zero
        assertTrue(abs(SprayerConfig(pressureZeroBar = 0.2).sensorBar(3.0)!! - 2.8) < 1e-9)
    }

    @Test fun regulationFlowmeter() = scenario(FlowMode.FLOWMETER, hasSensor = false)
    @Test fun regulationPressureOnly() = scenario(FlowMode.PRESSURE)
    @Test fun regulationPressureAndFlowmeter() = scenario(FlowMode.PRESSURE_AND_FLOWMETER)

    @Test
    fun stopClosesAndHoldsValve() {
        val computer = SprayerComputer(SprayerConfig()).apply { running = true }
        val sim = SprayerSim()
        val h = mutableListOf<Triple<Int, Double, Double>>()
        run(computer, sim, 5_000, { 8.0 }, { 0x7F }, out = h)
        val pos = sim.valvePosition
        computer.running = false
        val cmd = computer.step(100, 8.0, 0x7F, sim.status())
        assertTrue(!cmd.mainOpen && cmd.sections == 0 && cmd.regPulseMs == 0)
        // postój: zawór nie rusza się
        computer.running = true
        run(computer, sim, 3_000, { 0.0 }, { 0x7F }, t0 = 5_000, out = h)
        assertEquals(pos, sim.valvePosition)
    }
}

class SprayerLiquidTest {
    @Test
    fun rsmCorrection() {
        val water = SprayerConfig()
        val rsm = SprayerConfig(liquid = Liquid.RSM28, nozzleKind = NozzleKind.RSM_3)
        // RSM 1,28 kg/l płynie √1,28 = 1,13× wolniej
        assertTrue(abs(rsm.nozzleFlowAt(3.0) - 1.18 / 1.1314) < 0.001)
        // ta sama dawka wymaga wyższego ciśnienia: ×1,28
        assertTrue(abs(rsm.pressureFor(8.0) / water.pressureFor(8.0) - 1.28) < 1e-9)
        val r = rsm.speedRangeKmh(150.0)
        assertTrue(r.start < r.endInclusive && r.start > 0)
    }

    @Test
    fun configTextRoundTrip() {
        val c = SprayerConfig(
            nozzlesPerSection = listOf(5, 6, 6, 8, 6, 6, 5), nozzleSpacingM = 0.5, nozzle = Nozzle.ISO[5],
            nozzleKind = NozzleKind.RSM_7, liquid = Liquid.RSM32, valveType = ValveType.ARAG_2PIN,
            flowMode = FlowMode.FLOWMETER, pulsesPerLiter = 255.0, targetLha = 180.0, tankCapacityL = 2500.0,
        )
        assertEquals(c, SprayerConfigText.read(SprayerConfigText.write(c)))
        assertEquals(SprayerConfig(), SprayerConfigText.read("śmieci"))
    }
}
