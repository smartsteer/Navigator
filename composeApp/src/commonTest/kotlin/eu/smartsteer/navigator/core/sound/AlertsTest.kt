package eu.smartsteer.navigator.core.sound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertsTest {
    private fun st(t: Double, engaged: Boolean = false, toggle: Double = -10.0, gps: Boolean = true, rtk: Boolean = true,
                   turn: Boolean = false, sec: Boolean = false, orders: Int = 0) =
        AlertState(true, engaged, toggle, gps, rtk, turn, sec, orders, t)

    @Test
    fun autosteerOnOffAndAutomaticAlarm() {
        val a = AlertTracker()
        assertEquals(emptyList<SoundKind>(), a.events(st(0.0)))
        assertEquals(listOf(SoundKind.AUTOSTEER_ON), a.events(st(0.1, engaged = true, toggle = 0.05)))
        assertEquals(listOf(SoundKind.AUTOSTEER_OFF), a.events(st(5.0, engaged = false, toggle = 4.9)))
        a.events(st(6.0, engaged = true, toggle = 5.9))
        // GPS zniknął i autoster wyłączył się sam — jeden alarm
        assertEquals(listOf(SoundKind.ALARM), a.events(st(9.0, engaged = false, toggle = 5.9, gps = false)))
    }

    @Test
    fun rtkTurnSectionsOrders() {
        val a = AlertTracker()
        a.events(st(0.0))
        assertEquals(listOf(SoundKind.WARNING), a.events(st(0.1, rtk = false)))
        assertEquals(listOf(SoundKind.UTURN), a.events(st(0.2, rtk = false, turn = true)))
        assertEquals(listOf(SoundKind.SECTION_ON), a.events(st(2.0, rtk = false, turn = true, sec = true)))
        assertTrue(a.events(st(2.3, rtk = false, turn = true, sec = false)).isEmpty(), "bez trajkotania sekcji")
        assertEquals(listOf(SoundKind.ORDER), a.events(st(4.0, rtk = false, turn = true, orders = 1)))
    }

    @Test
    fun tonesHaveRightLength() {
        val pcm = Tones.pcm(SoundKind.AUTOSTEER_ON)
        assertEquals((110 + 30 + 160) * Tones.RATE / 1000, pcm.size)
        assertTrue(pcm.any { it > 10000 })
    }
}
