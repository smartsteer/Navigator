package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.test.Test
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
