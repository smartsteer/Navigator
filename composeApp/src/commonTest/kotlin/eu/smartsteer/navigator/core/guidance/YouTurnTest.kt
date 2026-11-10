package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YouTurnTest {

    private fun continuous(p: List<Vec2>) {
        for (i in 1 until p.size) assertTrue(p[i].distanceTo(p[i - 1]) < 0.7, "skok przy $i: ${p[i - 1]} → ${p[i]}")
    }

    @Test
    fun wideTurnShape() {
        val s = YouTurn.shape(8.0, 24.0, right = true)
        assertTrue(s.first().distanceTo(Vec2(0.0, 0.0)) < 1e-9)
        assertEquals(24.0, s.last().x, 1e-6)
        assertEquals(0.0, s.last().y, 1e-6)
        assertEquals(8.0, YouTurn.depth(s), 1e-6)
        continuous(s)
    }

    @Test
    fun omegaTurnShapeAndLeft() {
        val s = YouTurn.shape(8.1, 6.0, right = false)
        assertEquals(-6.0, s.last().x, 1e-6)
        assertEquals(0.0, s.last().y, 1e-6)
        assertTrue(s.any { it.x > 1.0 }, "omega wychodzi najpierw w przeciwną stronę")
        assertTrue(YouTurn.depth(s) > 8.1)
        continuous(s)
    }

    @Test
    fun turnFitsInsideTurnLine() {
        val field = listOf(Vec2(0.0, 0.0), Vec2(200.0, 0.0), Vec2(200.0, 200.0), Vec2(0.0, 200.0))
        val turnLine = Polygon.inset(field, 2.0)
        val guide = Curves.headings(Curves.densify(listOf(Vec2(100.0, 0.0), Vec2(100.0, 300.0)), 1.0))
        val from = Curves.nearestIndex(guide, Vec2(100.0, 150.0))
        val path = assertNotNull(YouTurn.build(guide, from, turnLine, 8.1, 6.0, right = true))
        for (p in path) assertTrue(Polygon.contains(field, p.p), "poza polem: ${p.p}")
        val last = path.last()
        assertEquals(106.0, last.p.x, 1e-6)
        assertTrue(abs(last.heading - kotlin.math.PI) < 0.01, "kurs na południe")
        assertTrue(path.maxOf { it.p.y } <= 198.0 + 1e-6)
        // za ciasno: zawrót o promieniu 150 m się nie zmieści
        assertNull(YouTurn.build(guide, from, turnLine, 150.0, 6.0, right = true))
    }
}
