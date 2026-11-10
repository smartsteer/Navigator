package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import eu.smartsteer.navigator.core.settings.TextFiles
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldTest {
    private val square = listOf(Vec2(0.0, 0.0), Vec2(100.0, 0.0), Vec2(100.0, 100.0), Vec2(0.0, 100.0))

    @Test
    fun polygonAreaContainsAndHeadland() {
        assertEquals(10_000.0, Polygon.area(square), 1e-9)
        assertTrue(Polygon.contains(square, Vec2(50.0, 50.0)))
        assertFalse(Polygon.contains(square, Vec2(150.0, 50.0)))
        for (poly in listOf(square, square.reversed())) {
            val inner = Polygon.inset(poly, 10.0)
            assertEquals(6_400.0, Polygon.area(inner), 1.0)
            assertTrue(Polygon.contains(inner, Vec2(50.0, 50.0)))
            assertFalse(Polygon.contains(inner, Vec2(5.0, 50.0)))
        }
    }

    @Test
    fun simplifyDropsDenseAndStraightPoints() {
        val raw = ArrayList<Vec2>()
        for (i in 0..100) raw.add(Vec2(i.toDouble(), 0.0))
        for (i in 0..100) raw.add(Vec2(100.0, i.toDouble()))
        for (i in 100 downTo 0) raw.add(Vec2(i.toDouble(), 100.0))
        for (i in 100 downTo 1) raw.add(Vec2(0.0, i.toDouble()))
        val s = Polygon.simplify(raw)
        assertEquals(4, s.size)
        assertEquals(10_000.0, Polygon.area(s), 1e-6)
    }

    @Test
    fun coverageCountsAreaOnce() {
        val c = Coverage()
        val s = c.newStrip()
        c.extend(s, Vec2(0.0, 0.0), Vec2(3.0, 0.0))
        c.extend(s, Vec2(0.0, 10.0), Vec2(3.0, 10.0))
        assertEquals(30.0, c.workedM2, 1.0)
        assertTrue(c.isCovered(Vec2(1.5, 5.0)))
        assertFalse(c.isCovered(Vec2(4.0, 5.0)))
        // ten sam pas drugi raz nie dokłada hektarów
        val s2 = c.newStrip()
        c.extend(s2, Vec2(0.0, 0.0), Vec2(3.0, 0.0))
        c.extend(s2, Vec2(0.0, 10.0), Vec2(3.0, 10.0))
        assertEquals(30.0, c.workedM2, 1.0)
        // ujemne współrzędne też działają
        val s3 = c.newStrip()
        c.extend(s3, Vec2(-10.0, -10.0), Vec2(-7.0, -10.0))
        c.extend(s3, Vec2(-10.0, -5.0), Vec2(-7.0, -5.0))
        assertTrue(c.isCovered(Vec2(-8.5, -7.0)))
        // zapis i odczyt
        val back = Coverage().also { it.loadText(c.toText()) }
        assertEquals(c.workedM2, back.workedM2, 1e-9)
        assertEquals(3, back.strips.size)
    }

    @Test
    fun sectionsTurnOffBeforeCoveredAreaAndOutsideBoundary() {
        val p = VehicleProfile(toolWidth = 3.0, numSections = 3, hitchLength = 0.0, toolTrailing = false, lookAheadOff = 0.5, lookAheadOn = 1.0)
        val c = Coverage()
        // pas już obrobiony od y = 20 do 40
        val pre = c.newStrip()
        c.extend(pre, Vec2(-1.5, 20.0), Vec2(1.5, 20.0))
        c.extend(pre, Vec2(-1.5, 40.0), Vec2(1.5, 40.0))
        val sec = Sections()
        var y = 0.0
        var offAt = Double.NaN
        while (y < 30) {
            sec.updateTool(Vec2(0.0, y), 0.0, p)
            sec.update(p, 7.2, c, null, null, workOn = true)
            if (y > 5 && !sec.anyOn() && offAt.isNaN()) offAt = y
            y += 0.2
        }
        // 7,2 km/h = 2 m/s, wyłączenie 0,5 s = 1 m przed obrobionym pasem
        assertTrue(abs(offAt - 19.0) < 0.5, "wyłączenie przy y = $offAt")
        assertEquals(0b111, run {
            val s2 = Sections()
            s2.updateTool(Vec2(0.0, 0.0), 0.0, p)
            s2.update(p, 7.2, Coverage(), null, null, true)
            s2.bits()
        })
        // poza granicą sekcje stoją
        val s3 = Sections()
        s3.updateTool(Vec2(500.0, 500.0), 0.0, p)
        s3.update(p, 7.2, Coverage(), square, null, true)
        assertFalse(s3.anyOn())
        // za wolno — stoją
        val s4 = Sections()
        s4.updateTool(Vec2(50.0, 50.0), 0.0, p)
        s4.update(p, 0.2, Coverage(), square, null, true)
        assertFalse(s4.anyOn())
    }

    @Test
    fun trailingToolFollowsHitch() {
        val p = VehicleProfile(hitchLength = -1.0, toolTrailing = true, trailingHitchLength = -3.0)
        val s = Sections()
        s.updateTool(Vec2(0.0, 0.0), 0.0, p)
        assertEquals(Vec2(0.0, -4.0), s.toolPos)
        // jedziemy prosto — maszyna dalej 4 m za osią
        for (i in 1..20) s.updateTool(Vec2(0.0, i * 0.5), 0.0, p)
        assertEquals(0.0, s.toolPos!!.x, 1e-6)
        assertEquals(10.0 - 4.0, s.toolPos!!.y, 1e-6)
    }

    @Test
    fun fieldJsonAndStore() {
        val f = FieldData("Za stodołą", 52.1, 21.2, square, 12.0, AbLine(Vec2(1.0, 2.0), Vec2(3.0, 40.0)), listOf(Vec2(0.0, 0.0), Vec2(1.0, 1.0)))
        assertEquals(f, FieldData.fromJson(f.toJson()))
        val mem = object : TextFiles {
            val m = HashMap<String, String>()
            override fun list() = m.keys.toList()
            override fun read(name: String) = m[name]
            override fun write(name: String, text: String) { m[name] = text }
            override fun delete(name: String) { m.remove(name) }
            override val location = "pamięć"
        }
        val st = FieldStore(mem)
        st.save(f, "0 0 3 0 0 10 3 10\n")
        assertEquals(listOf("Za stodołą"), st.names())
        val (back, cov) = assertNotNull(st.load("Za stodołą"))
        assertEquals(f, back)
        assertTrue(cov.startsWith("0 0 3 0"))
    }

    @Test
    fun customSectionWidthsAndToolOffset() {
        val p = VehicleProfile(numSections = 3, sectionWidths = listOf(1.0, 2.0, 3.0), toolOffset = 0.5, hitchLength = 0.0, toolTrailing = false)
        val s = Sections()
        s.updateTool(Vec2(0.0, 0.0), 0.0, p)
        val (a0, b0) = s.edges(0, p)!!
        val (a2, b2) = s.edges(2, p)!!
        assertEquals(-2.5, a0.x, 1e-9); assertEquals(-1.5, b0.x, 1e-9)
        assertEquals(0.5, a2.x, 1e-9); assertEquals(3.5, b2.x, 1e-9)
        // zła liczba szerokości — równe
        assertEquals(listOf(2.0, 2.0), VehicleProfile(toolWidth = 4.0, numSections = 2, sectionWidths = listOf(1.0)).sectionWidthsEffective)
        val f = Sections.machineConfigFrame(VehicleProfile(hydEnabled = true, machineInvertRelays = true, hydRaiseTime = 5))
        assertEquals(listOf(0x80, 0x81, 0x7F, 0xEE, 8, 5, 4, 1, 3, 1, 2, 3, 4), f.take(13).map { it.toInt() and 0xFF })
    }
}
