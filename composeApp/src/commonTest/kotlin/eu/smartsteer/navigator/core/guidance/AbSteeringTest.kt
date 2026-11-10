package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AbSteeringTest {
    private val a = Vec2(0.0, -100.0)
    private val b = Vec2(0.0, 100.0)
    private val pp = VehicleProfile()
    private val st = VehicleProfile(algorithm = SteerAlgorithm.STANLEY)

    @Test
    fun onLineSteersStraight() {
        for (p in listOf(pp, st)) {
            val r = AbSteering().compute(a, b, Vec2(0.0, 0.0), 0.0, 8.0, true, p)
            assertTrue(abs(r.steerAngleDeg) < 1e-6, "${p.algorithm}: ${r.steerAngleDeg}")
            assertEquals(0.0, r.distanceFromLine, 1e-9)
        }
    }

    @Test
    fun rightOfLineSteersLeft() {
        for (p in listOf(pp, st)) {
            val r = AbSteering().compute(a, b, Vec2(0.5, 0.0), 0.0, 8.0, true, p)
            assertEquals(0.5, r.distanceFromLine, 1e-9)
            assertTrue(r.steerAngleDeg < -1.0, "${p.algorithm}: ${r.steerAngleDeg}")
        }
        // ciągnik krzywo w lewo, ale na linii → kontra w prawo
        for (p in listOf(pp, st)) {
            val r = AbSteering().compute(a, b, Vec2(0.0, 0.0), -10 * PI / 180, 8.0, true, p)
            assertTrue(r.steerAngleDeg > 0.5, "${p.algorithm}: ${r.steerAngleDeg}")
        }
    }

    @Test
    fun steerAngleIsLimited() {
        val r = AbSteering().compute(a, b, Vec2(3.0, 0.0), 0.0, 8.0, true, pp.copy(maxSteerAngle = 25.0))
        assertEquals(-25.0, r.steerAngleDeg, 1e-9)
    }

    @Test
    fun goalPointDistanceLikeAgOpenGps() {
        val s = AbSteering()
        // 10 km/h, na linii: 10·0,05·1,5 = 0,75; ×3 + 3 = 5,25 m
        assertEquals(5.25, s.goalPointDistance(10.0, pp), 1e-9)
        // postój: minimum 3 m (wyprzedzenie trzymania)
        assertEquals(3.0, s.goalPointDistance(0.0, pp), 1e-9)
        // daleko od linii: współczynnik dojazdu 0,9 → 2,7·0,75 + 2,7 = 4,725
        s.compute(a, b, Vec2(1.0, 0.0), 0.0, 10.0, false, pp)
        assertEquals(4.725, s.goalPointDistance(10.0, pp), 1e-9)
    }

    @Test
    fun integralGrowsOnlyWhenEngaged() {
        val p = pp.copy(purePursuitIntegral = 1.0)
        val off = AbSteering()
        val on = AbSteering()
        repeat(100) {
            off.compute(a, b, Vec2(0.1, 0.0), 0.0, 8.0, false, p)
            on.compute(a, b, Vec2(0.1, 0.0), 0.0, 8.0, true, p)
        }
        assertEquals(0.0, off.integral, 1e-9)
        assertTrue(on.integral < -0.01, "całka ${on.integral}")
        assertTrue(on.integral >= -0.2)
    }
}
