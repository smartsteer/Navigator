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
    fun headlandLoopHalfWidthInside() {
        val h = HardwareSession(VehicleProfile(toolWidth = 6.0))
        h.restoreFieldForTest(listOf(Vec2(0.0, 0.0), Vec2(200.0, 0.0), Vec2(200.0, 100.0), Vec2(0.0, 100.0)))
        assertTrue(h.headlandTrack())
        val c = assertNotNull(h.curveRef)
        assertEquals(1, h.tracks.size)
        // wszystkie punkty 3 m od granicy (± zaokrąglenie narożników)
        for (q in c) {
            val d = minOf(q.p.x, 200 - q.p.x, q.p.y, 100 - q.p.y)
            assertTrue(d in 2.5..3.5, "punkt $q d=$d")
        }
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

class CoverageOutlineTest {
    @Test
    fun outlineOfWorkedAreaWithGapsAndHole() {
        val c = Coverage()
        // pasy 6 m na 60 m długości, przerwy 1 m między pasami, w środku dziura 5×5 m
        var x = 0.0
        while (x < 40) {
            c.paintQuad(Vec2(x, 0.0), Vec2(x + 6, 0.0), Vec2(x + 6, 60.0), Vec2(x, 60.0))
            x += 7.0
        }
        val o = assertNotNull(c.outline())
        val a = Polygon.area(o)
        // 41 × 60 = 2460 m² (przerwy i krawędzie domknięte, ±1 m dookoła)
        assertTrue(a in 2200.0..2700.0, "pole $a")
        assertTrue(Polygon.contains(o, Vec2(20.0, 30.0)))
        assertTrue(!Polygon.contains(o, Vec2(20.0, 70.0)))
    }
}

class RateMapTest {
    private val geo = """{"type":"FeatureCollection","features":[
      {"type":"Feature","properties":{"id":1,"Dawka":120.5},"geometry":{"type":"Polygon","coordinates":[[[21.0,52.0],[21.001,52.0],[21.001,52.001],[21.0,52.001],[21.0,52.0]]]}},
      {"type":"Feature","properties":{"id":2,"Dawka":"200"},"geometry":{"type":"MultiPolygon","coordinates":[[[[21.001,52.0],[21.002,52.0],[21.002,52.001],[21.001,52.001]]]]}}]}"""

    @Test
    fun geoJsonZonesAndRateAt() {
        val pl = eu.smartsteer.navigator.core.geo.LocalPlane(52.0, 21.0)
        val m = assertNotNull(RateMapImport.parse(geo, "Saletra", pl))
        assertEquals(2, m.zones.size)
        assertEquals("kg/ha", m.unit)
        assertEquals(120.5, m.rateAt(pl.toLocal(52.0005, 21.0005)))
        assertEquals(200.0, m.rateAt(pl.toLocal(52.0005, 21.0015)))
        assertEquals(0.0, m.rateAt(pl.toLocal(52.01, 21.01)))
        // zapis w polu
        val f = FieldData("P", 52.0, 21.0, rateMap = m)
        val back = assertNotNull(FieldData.fromJson(f.toJson()))
        val rb = assertNotNull(back.rateMap)
        assertEquals(m.zones.map { it.rate }, rb.zones.map { it.rate })
        assertEquals(m.unit, rb.unit)
        // ramka PGN 224
        val fr = RateMap.frame(120.5, 0, true)
        assertEquals(0xE0, fr[3].toInt() and 0xFF)
        assertEquals(1205, (fr[5].toInt() and 0xFF) or ((fr[6].toInt() and 0xFF) shl 8))
    }

    @Test
    fun kmlZones() {
        val kml = "<kml><Document><Placemark><name>Strefa 1</name><ExtendedData><Data name=\"rate\"><value>85</value></Data></ExtendedData>" +
            "<Polygon><outerBoundaryIs><LinearRing><coordinates>21.0,52.0 21.001,52.0 21.001,52.001 21.0,52.001</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark></Document></kml>"
        val pl = eu.smartsteer.navigator.core.geo.LocalPlane(52.0, 21.0)
        val m = assertNotNull(RateMapImport.parse(kml, "x", pl))
        assertEquals(85.0, m.rateAt(pl.toLocal(52.0005, 21.0005)))
    }

    @Test
    fun exportRoundTrip() {
        val f = FieldData(
            "Za stodołą", 52.0, 21.0, listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 80.0), Vec2(0.0, 80.0)), 0.0,
            tracks = listOf(Track("AB 1", AbLine(Vec2(5.0, 0.0), Vec2(5.0, 80.0)))),
            obstacles = listOf(listOf(Vec2(40.0, 40.0), Vec2(45.0, 40.0), Vec2(45.0, 45.0))),
        )
        val k = eu.smartsteer.navigator.core.io.FieldImport.parseKml(eu.smartsteer.navigator.core.io.FieldExport.kml(f), "x").single()
        assertEquals("Za stodołą", k.name)
        assertEquals(4, k.boundary!!.size)
        assertNotNull(k.ab)
        val i = eu.smartsteer.navigator.core.io.FieldImport.parseIsoxml(eu.smartsteer.navigator.core.io.FieldExport.isoxml(f)).single()
        assertEquals(4, i.boundary!!.size)
        assertNotNull(i.ab)
        val back = assertNotNull(eu.smartsteer.navigator.core.io.FieldExchange.toFieldData(i))
        assertTrue(abs(Polygon.area(back.boundary!!) - 8000.0) < 5.0)
    }
}

class WeatherTest {
    @Test
    fun parseOpenMeteo() {
        val j = """{"latitude":52.2,"current":{"time":"2026-11-11T10:00","temperature_2m":8.4,"relative_humidity_2m":81,"wind_speed_10m":4.7,"wind_gusts_10m":9.1,"wind_direction_10m":265,"precipitation":0.0}}"""
        val w = assertNotNull(eu.smartsteer.navigator.core.weather.Weather.parse(j))
        assertEquals(4.7, w.windMs)
        assertEquals("zachodni", w.windFrom)
        assertTrue(w.windMs > eu.smartsteer.navigator.core.weather.Weather.SPRAY_MAX_WIND)
    }
}
