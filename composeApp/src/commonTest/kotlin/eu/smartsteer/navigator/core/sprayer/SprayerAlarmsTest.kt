package eu.smartsteer.navigator.core.sprayer

import eu.smartsteer.navigator.core.aog.AogStream
import eu.smartsteer.navigator.core.aog.Frame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SprayerAlarmsTest {
    private fun input(running: Boolean = true, link: Boolean = true, rateOk: Boolean = true, tank: Double = 500.0) =
        SprayerAlarms.Input(running, link, SprayerStatus(0, true, true, false, false, 0, 0, 3.0, supplyVolts = 13.5), true, rateOk, true, tank, 100.0)

    @Test
    fun rateAlarmIsDelayed() {
        val a = SprayerAlarms(4000)
        assertTrue(a.step(100, input()).isEmpty())
        repeat(30) { assertTrue(a.step(100, input(rateOk = false)).isEmpty()) } // 3 s — jeszcze cicho
        var fired = emptyList<SprayerAlarm>()
        repeat(15) { fired = fired + a.step(100, input(rateOk = false)) }
        assertEquals(listOf(SprayerAlarm.RATE), fired)
        a.step(100, input())
        assertTrue(a.active.isEmpty())
    }

    @Test
    fun linkAndReserveImmediate() {
        val a = SprayerAlarms()
        assertEquals(listOf(SprayerAlarm.LINK, SprayerAlarm.RESERVE), a.step(100, input(link = false, tank = 80.0)))
        assertTrue(a.step(100, input(link = false, tank = 80.0)).isEmpty()) // raz, nie co krok
        assertEquals(listOf(SprayerAlarm.EMPTY), a.step(100, input(tank = 0.0)))
        // STOP: brak alarmów pracy, rezerwa zostaje
        a.step(100, input(running = false, tank = 80.0))
        assertEquals(setOf(SprayerAlarm.RESERVE), a.active)
    }

    @Test
    fun streamParserResyncs() {
        val f1 = SprayerStatus(1, true, true, false, false, 100, 5000, 2.5).toFrame()
        val f2 = SprayerCommand(2, true, 3, 2).toFrame()
        val bad = f1.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        val junk = byteArrayOf(1, 0x80.toByte(), 7) + bad + f1 + byteArrayOf(0x80.toByte()) + f2
        val got = ArrayList<Frame>()
        val s = AogStream()
        // podawane po kawałku, jak z portu
        var i = 0
        while (i < junk.size) { val n = minOf(5, junk.size - i); s.feed(junk.copyOfRange(i, i + n)) { got += it }; i += n }
        assertEquals(listOf(SprayerPgn.STATUS, SprayerPgn.COMMAND), got.map { it.pgn })
        assertEquals(2.5, SprayerStatus.from(got[0])!!.pressureBar)
    }
}

class SprayReportTest {
    @Test
    fun json() {
        assertEquals(null, sprayReportJson(SprayerConfig(), 0.1, 2.0))
        val j = sprayReportJson(SprayerConfig(liquid = Liquid.RSM28, targetLha = 150.0), 300.0, 2.0)!!
        assertEquals(
            "{\"ciecz\":\"RSM 28 %\",\"ciecz_l\":300.0,\"dawka_lha\":150.0,\"dawka_zadana_lha\":150,\"dawka_kgha\":192.0," +
                "\"dysza\":\"03 niebieska\",\"rodzaj_dyszy\":\"Płaskostrumieniowa\"}", j,
        )
    }
}
