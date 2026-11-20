package eu.smartsteer.navigator.core.sprayer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Kreator kalibracji na „brudnym” symulatorze: przepływomierz ma naprawdę 300 imp./l (w ustawieniach 255),
 * dysze zużyte +8 %, luz zaworu 250 ms, opóźnienie ciśnienia 0,5 s, szum przepływomierza ±2 %.
 * Po kreatorze wszystko ma być wyuczone, a próba dawki udana.
 */
class CalibrationWizardTest {
    private class Rig(val cfg: SprayerConfig, val sim: SprayerSim) {
        val computer = SprayerComputer(cfg)
        val wiz = CalibrationWizard(cfg)
        var t = 0
        /** Prawdziwy wydatek jednej dyszy (do „miarki”), średnia z ostatniej minuty. */
        var jugSum = 0.0
        var jugN = 0

        fun run(ms: Int, until: () -> Boolean = { false }) {
            val end = t + ms
            while (t < end && !until()) {
                val st = sim.status()
                val out = wiz.step(CalibrationWizard.In(100, true, st.pulseCount, computer.flowLpm, computer.pressureBar, st.pressureBar, computer.targetLpm))
                if (wiz.applyRequest) { computer.reconfigure(wiz.result); wiz.applyRequest = false }
                computer.running = out.running
                computer.autoRate = out.autoRate
                val cmd = computer.step(100, out.speedKmh ?: 0.0, out.mask, st, out.pulseMs)
                sim.receive(cmd)
                repeat(10) { sim.step(10) }
                if (wiz.nozzleState == CalibrationWizard.NozzleState.MEASURING) { jugSum += sim.flowLpm / cfg.nozzlesPerSection.sum(); jugN++ }
                t += 100
            }
        }
    }

    @Test
    fun wholeWizardLearnsEverything() {
        val cfg = SprayerConfig(nozzlesPerSection = List(7) { 6 }, pulsesPerLiter = 255.0, flowMode = FlowMode.PRESSURE_AND_FLOWMETER)
        val sim = SprayerSim(nozzlesPerSection = 6, pulsesPerLiter = 300.0, nozzleWear = 1.08,
            backlashMs = 250, delayMs = 500, flowNoise = 0.02, valveTravelMs = 5000)
        val r = Rig(cfg, sim)
        val w = r.wiz
        assertEquals(CalibrationWizard.Step.PREPARE, w.step)
        r.run(500)
        assertTrue(w.stepDone)
        w.next()
        assertEquals(CalibrationWizard.Step.SENSOR, w.step)
        r.run(500)
        assertTrue(w.zeroPressure(sim.status().pressureBar).startsWith("Wyzerowano"))
        w.next()
        assertEquals(CalibrationWizard.Step.FLOW_CHECK, w.step)
        r.run(12_000) { w.stepDone }
        assertTrue(w.stepDone, "sprawdzenie przepływu: ${w.error}")
        w.next()
        assertEquals(CalibrationWizard.Step.VALVE, w.step)
        w.startValveLearn()
        r.run(240_000) { !w.busy }
        assertNull(w.error)
        assertTrue(w.stepDone, w.status)
        val travel = w.result.valveTravelMs
        assertTrue(travel in 3000..7000, "przesuw $travel ms (prawdziwy 5000)")
        assertTrue(w.result.regBacklashMs in 100..450, "luz ${w.result.regBacklashMs} ms (prawdziwy 250)")
        assertTrue(w.result.regSettleMs >= 400, "ustalanie ${w.result.regSettleMs} ms")
        w.next()
        assertEquals(CalibrationWizard.Step.NOZZLE, w.step)
        w.startNozzle()
        r.run(60_000) { w.nozzleState == CalibrationWizard.NozzleState.READY }
        assertEquals(CalibrationWizard.NozzleState.READY, w.nozzleState, w.status)
        w.startMeasure()
        r.run(61_000) { w.nozzleState == CalibrationWizard.NozzleState.ENTER }
        val jug = r.jugSum / r.jugN
        // rolnik łapie z trzech dysz (różnice ±2 %)
        assertNull(w.enterNozzleLiters(listOf(jug * 0.98, jug, jug * 1.02)))
        assertTrue(abs(w.result.pulsesPerLiter - 300.0) < 300.0 * 0.03, "imp./l ${w.result.pulsesPerLiter} (prawdziwe 300)")
        assertTrue(abs(w.result.nozzleFactor - 1.08) < 0.04, "dysze ${w.result.nozzleFactor} (prawdziwe 1,08)")
        w.next()
        assertEquals(CalibrationWizard.Step.RATE, w.step)
        r.run(200)
        w.startRateTest()
        r.run(70_000) { !w.busy }
        assertTrue(w.stepDone, "próba dawki: ${w.error} ${w.rateReport}")
        w.next()
        assertEquals(CalibrationWizard.Step.DONE, w.step)
        assertTrue(w.summary().isNotEmpty())
    }

    @Test
    fun flowCheckExplainsMissingFlowmeter() {
        val cfg = SprayerConfig(nozzlesPerSection = List(5) { 6 }, flowMode = FlowMode.PRESSURE_AND_FLOWMETER)
        val sim = SprayerSim(nozzlesPerSection = 6, pulsesPerLiter = 0.0)   // przepływomierz odłączony
        val r = Rig(cfg, sim)
        r.wiz.next(); r.wiz.next()
        r.run(12_000)
        val e = assertNotNull(r.wiz.error)
        assertTrue(e.contains("przepływomierz"), e)
    }

    @Test
    fun sensorZeroAndRange() {
        val w = CalibrationWizard(SprayerConfig())
        w.setSensor(PressureInput.VOLT, 20.0)
        assertTrue(w.zeroPressure(0.05).startsWith("Wyzerowano"))
        assertTrue(abs(w.result.pressureZeroBar - 0.1) < 1e-9)
        assertTrue(w.zeroPressure(3.0).startsWith("Czujnik pokazuje"))
    }
}
