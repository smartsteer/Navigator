package eu.smartsteer.navigator.core.io

import eu.smartsteer.navigator.core.field.Coverage
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImportTest {
    private val kml = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>Za stodołą</name>
<Placemark><name>granica</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
21.0000,52.0000,0 21.0030,52.0000,0 21.0030,52.0020,0 21.0000,52.0020,0 21.0000,52.0000,0
</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
<Placemark><name>AB</name><LineString><coordinates>21.0010,52.0001 21.0010,52.0019</coordinates></LineString></Placemark>
</Document></kml>"""

    private val iso = """<?xml version="1.0" encoding="UTF-8"?>
<ISO11783_TaskData VersionMajor="4">
<PFD A="PFD1" C="Pole &amp; łąka" D="60000">
 <PLN A="1"><LSG A="1">
  <PNT A="2" C="52.0000" D="21.0000"/><PNT A="2" C="52.0000" D="21.0030"/>
  <PNT A="2" C="52.0020" D="21.0030"/><PNT A="2" C="52.0020" D="21.0000"/>
 </LSG></PLN>
 <GGP A="GGP1"><GPN A="GPN1" C="AB1" D="1"><LSG A="5">
  <PNT A="6" C="52.0001" D="21.0010"/><PNT A="7" C="52.0019" D="21.0010"/>
 </LSG></GPN></GGP>
</PFD>
</ISO11783_TaskData>"""

    @Test
    fun kmlFieldWithBoundaryAndAb() {
        val f = FieldImport.parseKml(kml, "plik").single()
        assertEquals("Za stodołą", f.name)
        assertEquals(4, f.boundary!!.size)
        assertEquals(52.0001 to 21.0010, f.ab!!.first)
        val d = assertNotNull(FieldExchange.toFieldData(f))
        // ok. 205 m × 222 m ≈ 4,56 ha
        val ha = Polygon.area(d.boundary!!) / 10_000
        assertTrue(ha in 4.4..4.7, "ha = $ha")
        val ab = d.ab!!
        assertTrue(kotlin.math.abs(ab.a.x - ab.b.x) < 0.01, "linia na północ")
    }

    @Test
    fun isoxmlField() {
        val f = FieldImport.parseIsoxml(iso).single()
        assertEquals("Pole & łąka", f.name)
        assertEquals(4, f.boundary!!.size)
        assertEquals(52.0019 to 21.0010, f.ab!!.second)
    }

    @Test
    fun reportIsValidGeoJson() {
        val d = FieldExchange.toFieldData(FieldImport.parseKml(kml, "x").single())!!
        val c = Coverage()
        val s = c.newStrip()
        c.extend(s, Vec2(0.0, 0.0), Vec2(3.0, 0.0))
        c.extend(s, Vec2(0.0, 10.0), Vec2(3.0, 10.0))
        val json = FieldExchange.reportGeoJson(d, c, "Fendt", 3.0, 3, "2026-11-10 10:00", "2026-11-10 11:00")
        val m = assertNotNull(MiniJson.parse(json) as? Map<*, *>, json.take(300))
        assertEquals("FeatureCollection", m["type"])
        assertEquals(2, (m["features"] as List<*>).size)
        val props = m["properties"] as Map<*, *>
        assertEquals("Za stodołą", props["pole"])
        assertEquals(0.003, props["obrobiono_ha"] as Double, 0.0002)
    }
}
