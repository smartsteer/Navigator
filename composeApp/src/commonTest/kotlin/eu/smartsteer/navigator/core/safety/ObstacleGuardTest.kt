package eu.smartsteer.navigator.core.safety

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.sound.AlertState
import eu.smartsteer.navigator.core.sound.AlertTracker
import eu.smartsteer.navigator.core.sound.SoundKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObstacleGuardTest {
    private val g = ObstacleGuard(ObstacleConfig(enabled = true))

    @Test
    fun parsesSensorPackets() {
        val f = assertNotNull(ObstacleProtocol.parse("""{"v":1,"zrodlo":"oak-przod","ok":true,"przeszkody":[{"z":6.2,"x":-0.4,"klasa":"osoba","p":0.83},{"z":-1},{"x":2}]}""", 10.0))
        assertEquals(1, f.obstacles.size, "złe wpisy (ujemna odległość, brak z) pominięte")
        assertEquals(Obstacle(6.2, -0.4, "osoba", 0.83), f.obstacles[0])
        assertEquals("oak-przod", f.source)
        assertTrue(f.sensorOk)
        assertNull(ObstacleProtocol.parse("nie json", 0.0))
        assertEquals(false, ObstacleProtocol.parse("""{"ok":false,"przeszkody":[]}""", 0.0)!!.sensorOk)
    }

    @Test
    fun stopZoneGrowsWithSpeed() {
        assertEquals(4.0, g.stopLength(0.0), "postój — minimum")
        // 10 km/h: 2,78 m/s · 0,8 s + 2,78²/(2·2) + 1 = 2,22 + 1,93 + 1 = 5,15 m
        assertEquals(5.15, g.stopLength(10.0), 0.02)
        assertTrue(g.stopLength(20.0) > 10.0)
    }

    @Test
    fun verdicts() {
        fun at(vararg o: Obstacle) = ObstacleFrame(100.0, o.toList(), true)
        // maszyna 3 m → pół szerokości 1,5 + zapas 0,7 = 2,2 m
        assertEquals(0, g.check(at(), 100.2, 8.0, 3.0).level)
        assertEquals(2, g.check(at(Obstacle(3.0, 0.5, "osoba", 0.9)), 100.2, 8.0, 3.0).level)
        assertEquals(0, g.check(at(Obstacle(3.0, 3.0, "osoba", 0.9)), 100.2, 8.0, 3.0).level, "obok pasa maszyny — nie zatrzymuje")
        assertEquals(0, g.check(at(Obstacle(3.0, 0.0, "osoba", 0.3)), 100.2, 8.0, 3.0).level, "za mała pewność")
        val warn = g.check(at(Obstacle(7.0, 0.0, "pies", 0.9)), 100.2, 8.0, 3.0)
        assertEquals(1, warn.level); assertTrue(warn.message!!.contains("pies"))
        // najbliższa w pasie wygrywa
        val v = g.check(at(Obstacle(20.0, 0.0), Obstacle(3.5, -1.0, "słup", 0.9)), 100.2, 8.0, 3.0)
        assertEquals(2, v.level); assertEquals("słup", v.nearest!!.kind)
    }

    @Test
    fun deadSensorMeansStop() {
        assertEquals(2, g.check(null, 5.0, 0.0, 3.0).level)
        val f = ObstacleFrame(100.0, emptyList(), true)
        assertEquals(0, g.check(f, 100.9, 0.0, 3.0).level)
        val dead = g.check(f, 101.5, 0.0, 3.0)
        assertEquals(2, dead.level); assertEquals(false, dead.sensorAlive)
        assertEquals(2, g.check(ObstacleFrame(100.0, emptyList(), false), 100.1, 0.0, 3.0).level, "czujnik zgłasza błąd")
    }

    @Test
    fun safetyFrame() {
        val v = g.check(ObstacleFrame(1.0, listOf(Obstacle(3.4, 0.0)), true), 1.1, 5.0, 3.0)
        val f = assertNotNull(AogFrame.parse(ObstacleGuard.frame(v)))
        assertEquals(ObstacleGuard.PGN_SAFETY, f.pgn)
        assertEquals(2, f.data[0].toInt()); assertEquals(34, (f.data[1].toInt() and 0xFF) or ((f.data[2].toInt() and 0xFF) shl 8)); assertEquals(1, f.data[3].toInt())
    }

    @Test
    fun alarmOnStop() {
        val t = AlertTracker()
        fun st(o: Int) = AlertState(true, false, 0.0, true, true, false, false, 0, 10.0, o)
        t.events(st(0))
        assertEquals(SoundKind.WARNING, t.events(st(1)).first())
        assertEquals(SoundKind.ALARM, t.events(st(2)).first())
        assertTrue(t.events(st(2)).isEmpty(), "bez powtarzania co 100 ms")
    }
}
