package eu.smartsteer.navigator.core.settings

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.Pgn
import eu.smartsteer.navigator.core.aog.SteerConfig
import eu.smartsteer.navigator.core.aog.SteerData
import eu.smartsteer.navigator.core.aog.SteerSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesTest {

    private class MemFiles : TextFiles {
        val m = LinkedHashMap<String, String>()
        override fun list() = m.keys.toList()
        override fun read(name: String) = m[name]
        override fun write(name: String, text: String) { m[name] = text }
        override fun delete(name: String) { m.remove(name) }
        override val location = "pamięć"
    }

    @Test
    fun jsonRoundTripKeepsEverything() {
        val p = VehicleProfile(
            wheelbase = 2.85, antennaOffset = -0.25, toolWidth = 6.0, toolOverlap = 0.1,
            algorithm = SteerAlgorithm.STANLEY, stanleyIntegral = 0.4, gainP = 70, wasOffset = -350,
            steerEnable = SteerEnable.BUTTON, cytron = false, danfoss = true,
        )
        assertEquals(p, VehicleProfile.fromJson(p.toJson()))
        // brakujące pola = domyślne
        assertEquals(VehicleProfile(wheelbase = 2.5), VehicleProfile.fromJson("{\"wheelbase\": 2.5}"))
        assertNull(VehicleProfile.fromJson("{zepsuty"))
    }

    @Test
    fun saveKeepsBackupAndLoadFallsBack() {
        val f = MemFiles()
        val pr = Profiles(f)
        pr.save("Fendt 724", VehicleProfile(wheelbase = 2.9))
        assertNull(f.read("Fendt 724.last"))
        pr.save("Fendt 724", VehicleProfile(wheelbase = 3.0))
        assertEquals(2.9, VehicleProfile.fromJson(f.read("Fendt 724.last")!!)!!.wheelbase)
        f.write("Fendt 724.json", "uszkodzony")
        assertEquals(2.9, pr.load("Fendt 724")!!.wheelbase)
        assertEquals(listOf("Fendt 724"), pr.names())
    }

    @Test
    fun startupCreatesDefaultThenRemembersLast() {
        val f = MemFiles()
        val pr = Profiles(f)
        val (name, p) = pr.openStartup()
        assertEquals(Profiles.DEFAULT_NAME, name)
        assertEquals(VehicleProfile(), p)
        pr.save("Ursus", VehicleProfile(toolWidth = 3.0))
        pr.setLastUsed("Ursus")
        assertEquals("Ursus" to VehicleProfile(toolWidth = 3.0), pr.openStartup())
        pr.delete("Ursus")
        assertFalse(pr.exists("Ursus"))
        assertEquals(Profiles.DEFAULT_NAME, pr.openStartup().first)
    }

    @Test
    fun sanitizeNames() {
        assertEquals("John Deere6R", Profiles.sanitize("  John Deere/6R?  "))
        assertEquals("ab", Profiles.sanitize("a:b"))
        assertTrue(Profiles.sanitize("   ").isEmpty())
    }

    @Test
    fun pgnFramesLikeAgOpenGps() {
        val p = VehicleProfile()
        // domyślne ustawienia płytki AgOpenGPS: setting0 = 56
        assertEquals(56, p.setting0)
        assertEquals(0, p.setting1)
        val c = assertNotNull(AogFrame.parse(SteerConfig(p.setting0, 3, 2.5, p.setting1).toFrame()))
        assertEquals(Pgn.STEER_CONFIG, c.pgn)
        assertEquals(listOf(56, 3, 25, 0, 0, 0, 0, 0), c.data.map { it.toInt() and 0xFF })

        val s = assertNotNull(AogFrame.parse(SteerSettings(50, 180, 30, 25, 110, -2, 100).toFrame()))
        assertEquals(Pgn.STEER_SETTINGS, s.pgn)
        assertEquals(listOf(50, 180, 30, 25, 110, 0xFE, 0xFF, 100), s.data.map { it.toInt() and 0xFF })

        // odległość w PGN 254: 2 cm na jednostkę, +127; 255 = wyłączony
        assertEquals(255, SteerData.lineDistanceByte(false, 10))
        assertEquals(127, SteerData.lineDistanceByte(true, 0))
        assertEquals(132, SteerData.lineDistanceByte(true, 10))
        assertEquals(122, SteerData.lineDistanceByte(true, -10))
        assertEquals(254, SteerData.lineDistanceByte(true, 900))
    }
}
