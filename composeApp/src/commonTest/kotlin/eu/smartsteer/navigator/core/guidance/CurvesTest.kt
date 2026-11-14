package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CurvesTest {

    /** Łuk w prawo (zgodnie z zegarem) o promieniu r wokół (r, 0), start w (0,0) na północ. */
    private fun arc(r: Double, degrees: Int, step: Double = 0.5): List<Vec2> {
        val out = ArrayList<Vec2>()
        val n = (r * degrees * PI / 180 / step).toInt()
        for (i in 0..n) {
            val a = i * step / r
            out.add(Vec2(r - r * cos(a), r * sin(a)))
        }
        return out
    }

    @Test
    fun straightRecordingOffsetsSideways() {
        val rec = (0..60).map { Vec2(0.0, it.toDouble()) }
        val ref = Curves.buildReference(rec)
        assertTrue(ref.size > 300, "przedłużenie o 200 m na końcach")
        val off = Curves.offset(ref, 3.0, 3.0)
        for (p in off) assertEquals(3.0, p.p.x, 1e-6)
        val left = Curves.offset(ref, -6.0, 3.0)
        for (p in left) assertEquals(-6.0, p.p.x, 1e-6)
    }

    @Test
    fun arcOffsetKeepsDistanceFromCenter() {
        val ref = Curves.headings(arc(50.0, 90))
        val inner = Curves.offset(ref, 10.0, 3.0) // w prawo = do środka łuku
        val outer = Curves.offset(ref, -10.0, 3.0)
        val c = Vec2(50.0, 0.0)
        // jak w AgOpenGPS krzywa jest przedłużana prosto o 200 m na końcach — sprawdzamy sam łuk (y ≥ 0, x ≤ 50)
        fun onArc(v: Vec2) = v.y >= 0.5 && v.x <= 49.5
        assertTrue(inner.count { onArc(it.p) } > 20 && outer.count { onArc(it.p) } > 20)
        for (p in inner.filter { onArc(it.p) }) assertTrue(abs(p.p.distanceTo(c) - 40.0) < 0.15, "wewnątrz ${p.p.distanceTo(c)}")
        for (p in outer.filter { onArc(it.p) }) assertTrue(abs(p.p.distanceTo(c) - 60.0) < 0.15, "na zewnątrz ${p.p.distanceTo(c)}")
    }

    @Test
    fun purePursuitOnArcSteersWithTheCurve() {
        val ref = Curves.headings(arc(50.0, 120))
        val p = VehicleProfile()
        val s = AbSteering()
        // ciągnik na łuku, kurs styczny
        val a = 40.0 / 50.0
        val pos = Vec2(50 - 50 * cos(a), 50 * sin(a))
        var out: AbSteering.PathOutput? = null
        repeat(5) { out = s.computePath(ref, out?.index ?: -1, pos, a, 8.0, true, p) }
        val o = out!!
        assertTrue(abs(o.out.distanceFromLine) < 0.01, "xte ${o.out.distanceFromLine}")
        val expected = atan(p.wheelbase / 50.0) * 180 / PI
        assertTrue(abs(o.out.steerAngleDeg - expected) < 0.6, "kąt ${o.out.steerAngleDeg}, oczekiwany ~$expected")
        assertTrue(!o.pastEnd)
    }

    @Test
    fun contourFollowsPreviousPassOneWidthAway() {
        val c = Contour()
        for (y in 0..120) c.record(Pose(Vec2(0.0, y * 0.5), 0.0), working = true)
        c.record(Pose(Vec2(0.0, 60.0), 0.0), working = false)
        assertEquals(1, c.strips.size)
        // wracamy na południe, 3,2 m na wschód od pasa
        val g = c.build(Vec2(3.2, 30.0), PI, toolWidth = 3.0, overlap = 0.0, autosteerOn = false)
        assertTrue(g.size >= 5)
        assertTrue(!c.sameWay)
        for (p in g) assertEquals(3.0, p.p.x, 1e-6)
        assertTrue(abs(g.first().heading) < 1e-6, "jak ctList w AgOpenGPS: kierunek pasa odniesienia")
        // prowadzenie: ciągnik 0,2 m na wschód od linii jadąc na południe → skręt w prawo (na zachód)
        val prof = eu.smartsteer.navigator.core.settings.VehicleProfile()
        val r = assertNotNull(c.follow(Vec2(3.2, 30.0), Vec2(3.2, 30.0), PI, 8.0, false, true, false, null, prof, AbSteering()))
        assertTrue(r.steerAngleDeg > 0, "kąt ${r.steerAngleDeg}")
        // za daleko od pasa (ponad 3 szerokości) — brak konturu
        assertTrue(c.build(Vec2(12.0, 30.0), PI, 3.0, 0.0, false).isEmpty())
    }
}
