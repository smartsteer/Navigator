package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupervisorTest {
    private val p = VehicleProfile()
    private fun inp(
        t: Double, engaged: Boolean = true, fix: FixQuality = FixQuality.RTK_FIX, age: Double? = 1.0,
        xte: Double? = 0.0, set: Double = 0.0, actual: Double? = 0.0, speed: Double = 8.0, turn: Boolean = false,
    ) = Supervisor.Input(t, engaged, turn, fix, age, xte, set, actual, speed)

    @Test
    fun allGoodNoTrip() {
        val s = Supervisor()
        var t = 0.0
        repeat(200) { assertNull(s.check(inp(t), p)); t += 0.1 }
    }

    @Test
    fun loopGapTrips() {
        val s = Supervisor()
        assertNull(s.check(inp(0.0), p))
        assertNull(s.check(inp(0.1), p))
        val why = assertNotNull(s.check(inp(1.3), p))
        assertTrue(why.contains("Przerwa"), why)
        // bez autosteru przerwa nic nie robi
        val s2 = Supervisor()
        assertNull(s2.check(inp(0.0, engaged = false), p))
        assertNull(s2.check(inp(5.0, engaged = false), p))
    }

    @Test
    fun rtkLossTripsAfterDelay() {
        val s = Supervisor()
        var t = 0.0
        var why: String? = null
        while (t < 10 && why == null) { why = s.check(inp(t, fix = FixQuality.RTK_FLOAT), p); t += 0.1 }
        assertNotNull(why)
        assertTrue(t in 4.9..5.3, "po $t s")
        // krótka utrata FIX (2 s) nie wyłącza
        val s2 = Supervisor()
        t = 0.0
        repeat(20) { assertNull(s2.check(inp(t, fix = FixQuality.RTK_FLOAT), p)); t += 0.1 }
        repeat(100) { assertNull(s2.check(inp(t), p)); t += 0.1 }
        // 0 = wyłączone
        val s3 = Supervisor()
        t = 0.0
        repeat(100) { assertNull(s3.check(inp(t, fix = FixQuality.GPS), p.copy(supRtkLossS = 0.0))); t += 0.1 }
    }

    @Test
    fun oldCorrectionsTrip() {
        assertNotNull(Supervisor().check(inp(0.0, age = 15.0), p))
        assertNull(Supervisor().check(inp(0.0, age = null), p))
    }

    @Test
    fun driftFromLineTripsOnlyAfterBeingOnLine() {
        val s = Supervisor()
        var t = 0.0
        // dojazd z 2 m — nie wyłącza
        for (x in listOf(2.0, 1.6, 1.2, 0.8, 0.4, 0.2)) { assertNull(s.check(inp(t, xte = x), p)); t += 0.1 }
        assertNull(s.check(inp(t, xte = 0.8), p)); t += 0.1
        assertNotNull(s.check(inp(t, xte = 1.2), p))
        // w trakcie zawrotu nie
        val s2 = Supervisor()
        assertNull(s2.check(inp(0.0, xte = 0.1), p))
        assertNull(s2.check(inp(0.1, xte = 3.0, turn = true), p))
    }

    @Test
    fun wheelsNotFollowingTrip() {
        val s = Supervisor()
        var t = 0.0
        var why: String? = null
        while (t < 5 && why == null) { why = s.check(inp(t, set = 10.0, actual = 0.0), p); t += 0.1 }
        assertNotNull(why)
        assertTrue(t in 1.9..2.3, "po $t s")
        // na postoju nie sprawdzamy
        val s2 = Supervisor()
        t = 0.0
        repeat(50) { assertNull(s2.check(inp(t, set = 10.0, actual = 0.0, speed = 0.0), p)); t += 0.1 }
    }

    @Test
    fun sessionDisengagesAndLogs() {
        val sim = eu.smartsteer.navigator.core.sim.SimReceiver()
        val h = HardwareSession(VehicleProfile(antennaPivot = 0.0))
        var t = 0.0
        fun feed() { h.onNmea(sim.vtg(), t); h.onNmea(sim.gga(), t); val f = sim.pgn253(); h.onDatagram(f, f.size, t); h.update(t) }
        feed()
        h.restoreLine(AbLine(eu.smartsteer.navigator.core.geo.Vec2(0.0, 0.0), eu.smartsteer.navigator.core.geo.Vec2(0.0, 50.0)))
        repeat(20) { sim.step(0.1, null); t += 0.1; feed() }
        assertNull(h.setEngaged(true, t))
        repeat(10) { sim.step(0.1, h.steerSetDeg); t += 0.1; feed() }
        // zawieszenie programu na 2 s
        sim.step(2.0, h.steerSetDeg); t += 2.0; feed()
        assertTrue(!h.engaged)
        assertTrue(h.tripLog.last().contains("Przerwa"), h.tripLog.toString())
    }
}

class WasCalibrationTest {
    @Test
    fun circleGivesTrueAngleAndNewCounts() {
        // rozstaw osi 3 m, prawdziwy skręt 15° → promień 3 / tan 15° = 11,2 m; czujnik pokazuje 20° (za dużo impulsów)
        val wb = 3.0
        val r = wb / kotlin.math.tan(15 * kotlin.math.PI / 180)
        val cal = WasCalibration()
        cal.start()
        var a = 0.0
        while (a < 2 * kotlin.math.PI) {
            val p = eu.smartsteer.navigator.core.geo.Vec2(r * kotlin.math.cos(a) + 5, r * kotlin.math.sin(a) - 2)
            cal.sample(p, -a, 20.0, 5.0)
            a += 0.02
        }
        val res = kotlin.test.assertNotNull(cal.result(wb, 100))
        assertTrue(kotlin.math.abs(res.radiusM - r) < 0.01, "promień ${res.radiusM}")
        assertTrue(kotlin.math.abs(res.trueDeg - 15.0) < 0.05)
        assertTrue(res.newCountsPerDeg == 133, "impulsy ${res.newCountsPerDeg}")
    }
}

class AutoTuneTest {
    private fun run(p: eu.smartsteer.navigator.core.settings.VehicleProfile, xte: (Double) -> Double, act: (Double) -> Double = { 0.0 }, set: (Double) -> Double = { 0.0 }): AutoTune.Proposal? {
        val a = AutoTune()
        var t = 0.0
        var out: AutoTune.Proposal? = null
        while (t < 31.0 && out == null) { out = a.sample(t, true, xte(t), set(t), act(t), 8.0, p); t += 0.1 }
        return out
    }

    @Test
    fun oscillationLengthensLookAhead() {
        val p = eu.smartsteer.navigator.core.settings.VehicleProfile()
        // kołysanie ±6 cm co 4 s
        val prop = kotlin.test.assertNotNull(run(p, { 6 * kotlin.math.sin(2 * kotlin.math.PI * it / 4) }))
        assertTrue(prop.profile.lookAheadHold > p.lookAheadHold, prop.reasons.toString())
    }

    @Test
    fun steadyOffsetAddsIntegral() {
        val p = eu.smartsteer.navigator.core.settings.VehicleProfile(purePursuitIntegral = 0.0)
        val prop = kotlin.test.assertNotNull(run(p, { 4.0 + 0.5 * kotlin.math.sin(it) }))
        assertTrue(prop.profile.purePursuitIntegral > 0.0, prop.reasons.toString())
    }

    @Test
    fun goodDrivingChangesNothing() {
        val p = eu.smartsteer.navigator.core.settings.VehicleProfile()
        assertNull(run(p, { 1.0 * kotlin.math.sin(it / 3) }, set = { 2 * kotlin.math.sin(it) }, act = { 2 * kotlin.math.sin(it - 0.1) }))
    }

    @Test
    fun slowWheelsRaiseGainOnSecondWindow() {
        val p = eu.smartsteer.navigator.core.settings.VehicleProfile()
        val a = AutoTune()
        var t = 0.0
        var out: AutoTune.Proposal? = null
        // koła za zadanym o 0,8 s i o połowę słabiej; odchyłka mała
        while (t < 62.0 && out == null) {
            out = a.sample(t, true, 1.0 * kotlin.math.sin(t / 3), 3 * kotlin.math.sin(t), 1.5 * kotlin.math.sin(t - 0.8), 8.0, p)
            t += 0.1
        }
        val prop = kotlin.test.assertNotNull(out)
        assertTrue(prop.profile.gainP > p.gainP, prop.reasons.toString())
    }
}

class ReverseAndKTurnTest {
    private fun feeder(h: HardwareSession, sim: eu.smartsteer.navigator.core.sim.SimReceiver): (Double) -> Unit = { t ->
        h.onNmea(sim.vtg(), t); h.onNmea(sim.gga(), t)
        val f = sim.pgn253(); h.onDatagram(f, f.size, t)
        h.update(t)
    }

    @Test
    fun reversingKeepsTractorHeading() {
        val sim = eu.smartsteer.navigator.core.sim.SimReceiver()
        val h = HardwareSession(eu.smartsteer.navigator.core.settings.VehicleProfile(antennaPivot = 0.0))
        val feed = feeder(h, sim)
        var t = 0.0
        sim.targetSpeedKmh = 5.0
        repeat(40) { sim.step(0.1, null); t += 0.1; feed(t) }
        assertTrue(!h.reversing)
        sim.targetSpeedKmh = -3.0
        repeat(60) { sim.step(0.1, null); t += 0.1; feed(t) }
        assertTrue(h.reversing, "powinien wykryć cofanie")
        val diff = kotlin.math.abs(eu.smartsteer.navigator.core.sim.TractorSim.normalize(h.headingRad - sim.tractor.heading))
        assertTrue(diff < 0.1, "kurs ciągnika przy cofaniu: różnica $diff")
        sim.targetSpeedKmh = 5.0
        repeat(60) { sim.step(0.1, null); t += 0.1; feed(t) }
        assertTrue(!h.reversing)
    }

    @Test
    fun kTurnToNextPass() {
        val sim = eu.smartsteer.navigator.core.sim.SimReceiver()
        val h = HardwareSession(eu.smartsteer.navigator.core.settings.VehicleProfile(antennaPivot = 0.0, toolWidth = 4.0, youTurnStyle = 1, youTurnRadius = 6.0, wheelbase = 3.3, supRtkLossS = 0.0))
        val feed = feeder(h, sim)
        var t = 0.0
        sim.targetSpeedKmh = 6.0
        feed(t)
        h.restoreFieldForTest(listOf(Vec2(-60.0, -30.0), Vec2(60.0, -30.0), Vec2(60.0, 120.0), Vec2(-60.0, 120.0)))
        h.setHeadlandWidth(20.0)
        h.restoreLine(AbLine(Vec2(0.0, 0.0), Vec2(0.0, 50.0)))
        repeat(20) { sim.step(0.1, null); t += 0.1; feed(t) }
        kotlin.test.assertNull(h.setEngaged(true, t))
        h.autoUturn = true
        var sawK = false; var sawRev = false
        var steps = 0
        while (steps < 3000) {
            when (h.kPhase) {
                1 -> sawK = true
                2 -> { sim.targetSpeedKmh = -3.0; if (h.reversing) sawRev = true }
                3 -> sim.targetSpeedKmh = 5.0
            }
            sim.step(0.1, if (h.engaged) h.steerSetDeg else 0.0); t += 0.1; feed(t); steps++
            if (sawRev && h.kPhase == 0) break
            if (!h.engaged) break
        }
        assertTrue(sawK, "zawrót K się nie zaczął: ${h.message}")
        assertTrue(sawRev, "nie było cofania: ${h.message}")
        assertTrue(h.engaged, "autoster wyłączony: ${h.message}")
        assertEquals(0, h.kPhase)
        // jedziemy na sąsiednim przejeździe, z powrotem na południe
        repeat(150) { sim.step(0.1, h.steerSetDeg); t += 0.1; feed(t) }
        assertTrue(h.engaged, "po zawrocie: ${h.message}")
        assertTrue(kotlin.math.abs(kotlin.math.abs(h.passIndex) - 1) == 0, "przejazd ${h.passIndex}")
        assertTrue(kotlin.math.abs(h.xteCm) < 15.0, "odchyłka ${h.xteCm}")
    }
}
