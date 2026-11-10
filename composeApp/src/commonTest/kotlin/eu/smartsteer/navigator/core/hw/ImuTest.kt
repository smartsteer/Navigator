package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.Pgn
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.gps.Nmea
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImuTest {
    private val plane = LocalPlane(52.0, 21.0)

    private fun cs(body: String): String {
        var x = 0
        for (c in body) x = x xor c.code
        return "$" + body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }

    private fun dm(v: Double, degDigits: Int): String {
        val a = abs(v)
        val d = floor(a).toInt()
        val mm = (((a - d) * 60 * 1e7).toLong()).toString().padStart(9, '0')
        return d.toString().padStart(degDigits, '0') + mm.dropLast(7) + "." + mm.takeLast(7)
    }

    private fun gga(p: Vec2): String {
        val (lat, lon) = plane.toGeo(p)
        return cs("GNGGA,120000.00,${dm(lat, 2)},N,${dm(lon, 3)},E,4,12,0.8,100.0,M,40.0,M,1.0,0000")
    }

    private fun reply253(headingX10: Int, rollX10: Int): ByteArray {
        val d = byteArrayOf(0, 0, headingX10.toByte(), (headingX10 shr 8).toByte(), rollX10.toByte(), (rollX10 shr 8).toByte(), 0, 0)
        return AogFrame.build(Pgn.FROM_AUTOSTEER, d, source = 0x7E)
    }

    @Test
    fun pandaUsesTenthsLikeAgIo() {
        val (lat, lon) = plane.toGeo(Vec2.ZERO)
        val p = assertNotNull(Nmea.parsePanda(cs("PANDA,120000.00,${dm(lat, 2)},N,${dm(lon, 3)},E,4,14,0.7,101.2,0.8,3.24,2715,-35,12,0")))
        assertEquals(271.5, p.headingDeg!!, 1e-9)
        assertEquals(-3.5, p.rollDeg!!, 1e-9)
        val none = assertNotNull(Nmea.parsePanda(cs("PANDA,120000.00,${dm(lat, 2)},N,${dm(lon, 3)},E,4,14,0.7,101.2,0.8,3.24,65535,32767,0,0")))
        assertNull(none.headingDeg)
        assertNull(none.rollDeg)
    }

    @Test
    fun rollZeroInvertAndFilter() {
        val imu = Imu()
        val p = VehicleProfile(rollZero = 1.0, invertRoll = true, rollFilter = 0.5)
        imu.onRoll(-5.0, filtered = true, p, 0.0)
        assertEquals(4.0, imu.rollDeg!!, 1e-9)        // −(−5) − 1
        assertEquals(5.0, imu.rawRollDeg!!, 1e-9)
        imu.onRoll(-7.0, filtered = true, p, 0.1)
        assertEquals(5.0, imu.rollDeg!!, 1e-9)        // 4·0,5 + 6·0,5
        assertTrue(imu.rollAlive(0.5))
        assertTrue(!imu.rollAlive(2.0))
    }

    @Test
    fun rollMovesAntennaToCenterOfTractor() {
        // przechył 5° w prawo, antena 3 m: antena jest 26 cm na prawo od kół → pozycję przesuwamy w lewo
        val h = HardwareSession(VehicleProfile(antennaHeight = 3.0, antennaPivot = 0.0))
        val r = reply253(9999, 50)
        h.onDatagram(r, r.size, 0.0)
        h.onNmea(gga(Vec2.ZERO), 0.0)
        val expected = sin(5 * PI / 180) * -3.0
        assertEquals(expected, h.rollCorrectionM, 1e-9)
        assertEquals(expected, h.pivot!!.x, 1e-6) // kurs 0 (północ): „w prawo” = wschód
        assertNull(h.imu.headingDeg)               // 9999 = brak kursu IMU
    }

    @Test
    fun fusionPullsImuHeadingToGps() {
        val imu = Imu()
        imu.onHeading(350.0, 0.0) // żyroskop pokazuje 350°, GPS mówi 10°
        var out = 0.0
        repeat(300) { out = imu.fuse(10 * PI / 180, 0.06) }
        assertTrue(abs(out - 10 * PI / 180) < 1e-3, "kurs ${out * 180 / PI}")
        // na postoju IMU obraca się o 5°, kurs idzie za nim
        imu.onHeading(355.0, 1.0)
        assertTrue(abs(imu.corrected() - 15 * PI / 180) < 1e-3)
    }

    @Test
    fun imuHeadingUsedWhenStanding() {
        val h = HardwareSession(VehicleProfile(antennaPivot = 0.0))
        val r = reply253(900, 0) // 90,0°
        h.onDatagram(r, r.size, 0.0)
        h.onNmea(gga(Vec2.ZERO), 0.0)
        assertEquals(HeadingSource.IMU, h.headingSource)
        assertTrue(abs(h.headingRad - PI / 2) < 1e-9)
    }
}
