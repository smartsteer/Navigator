package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import eu.smartsteer.navigator.core.hw.HardwareSession
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlannerTest {
    @Test
    fun longRectanglePassesAlongLongSide() {
        // 400 m na wschód × 100 m na północ, maszyna 6 m → 17 przejazdów wzdłuż (kurs 90°)
        val r = listOf(Vec2(0.0, 0.0), Vec2(400.0, 0.0), Vec2(400.0, 100.0), Vec2(0.0, 100.0))
        val best = FieldPlanner.plan(r, 6.0).first()
        assertEquals(17, best.passes)
        assertEquals(90, best.headingDeg)
        // pierwszy przejazd 3 m od skraju
        val right = Vec2.right(best.heading)
        val firstN = best.line.a dot right
        val minN = r.minOf { it dot right }
        assertTrue(abs(firstN - minN - 3.0) < 1e-6)
    }

    @Test
    fun rotatedFieldFindsItsEdge() {
        // prostokąt 300 × 80 obrócony o 30°
        val a = 30 * PI / 180
        fun rot(x: Double, y: Double) = Vec2(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a))
        val r = listOf(rot(0.0, 0.0), rot(300.0, 0.0), rot(300.0, 80.0), rot(0.0, 80.0))
        val best = FieldPlanner.plan(r, 4.0).first()
        assertEquals(20, best.passes)
        assertTrue(best.alongEdge)
        // inny kierunek daje więcej przejazdów
        assertTrue(FieldPlanner.evaluate(r, 4.0, best.heading + PI / 2).passes > 60)
    }

    @Test
    fun sessionAppliesPlanAndKeepsTrackList() {
        val h = HardwareSession(VehicleProfile(toolWidth = 6.0))
        h.restoreFieldForTest(listOf(Vec2(0.0, 0.0), Vec2(400.0, 0.0), Vec2(400.0, 100.0), Vec2(0.0, 100.0)))
        val plan = h.planPasses().first()
        h.applyPlan(plan)
        assertEquals(1, h.tracks.size)
        assertNotNull(h.line)
        h.addTrack(Track("Druga", AbLine(Vec2(0.0, 0.0), Vec2(0.0, 50.0))))
        assertEquals(1, h.activeTrack)
        h.selectTrack(0)
        assertEquals(plan.line, h.line)
        h.deleteTrack(1)
        assertEquals(1, h.tracks.size)
        assertEquals(0, h.activeTrack)
    }

    @Test
    fun fieldJsonKeepsTracksFlagsObstacles() {
        val f = FieldData(
            "P", 52.0, 21.0, null, 0.0, null, null, null,
            tracks = listOf(Track("AB 1", AbLine(Vec2(1.0, 2.0), Vec2(3.0, 40.0))), Track("Krzywa 1", null, listOf(Vec2(0.0, 0.0), Vec2(1.0, 5.0)))),
            activeTrack = 1,
            flags = listOf(Flag(Vec2(5.0, 6.0), 2, "kamień")),
            obstacles = listOf(listOf(Vec2(0.0, 0.0), Vec2(5.0, 0.0), Vec2(5.0, 5.0))),
        )
        val back = assertNotNull(FieldData.fromJson(f.toJson()))
        assertEquals(f.tracks, back.tracks)
        assertEquals(1, back.activeTrack)
        assertEquals(f.flags, back.flags)
        assertEquals(f.obstacles, back.obstacles)
    }
}

class TramlinesTest {
    @Test
    fun threeSeederPassesPerSprayer() {
        // siewnik 6 m, opryskiwacz 18 m (co 3 przejazdy), rozstaw 1,8 m: ślady w przejeździe środkowym (1, 4, 7…)
        val b = (0..5).map { Tramlines.bits(it, 6.0, 3, 1.8, 0.5, 0, reversed = false) }
        assertEquals(listOf(0, 3, 0, 0, 3, 0), b)
        // przesunięcie o 1 przejazd
        assertEquals(3, Tramlines.bits(2, 6.0, 3, 1.8, 0.5, 1, reversed = false))
        // 4 przejazdy na opryskiwacz: ślady wypadają na przejazdach 1 (prawy) i 2 (lewy)
        assertEquals(1, Tramlines.bits(1, 6.0, 4, 1.8, 0.5, 0, reversed = false) and 1)
        assertEquals(2, Tramlines.bits(2, 6.0, 4, 1.8, 0.5, 0, reversed = false))
        // jazda w drugą stronę zamienia strony
        assertEquals(1, Tramlines.bits(2, 6.0, 4, 1.8, 0.5, 0, reversed = true))
    }

    @Test
    fun pgn229CarriesSectionsAbove16() {
        val bits = (1L shl 0) or (1L shl 17) or (1L shl 63)
        val f = Sections.sections64Frame(bits)
        assertEquals(0xE5, f[3].toInt() and 0xFF)
        assertEquals(8, f[4].toInt())
        assertEquals(1, f[5].toInt() and 0xFF)
        assertEquals(2, f[7].toInt() and 0xFF)   // sekcja 18 = bajt 2, bit 1
        assertEquals(0x80, f[12].toInt() and 0xFF)
    }
}
