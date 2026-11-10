package eu.smartsteer.navigator.core.ntrip

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.gps.Nmea
import eu.smartsteer.navigator.core.hw.HardwareSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NtripTest {
    @Test
    fun requestWithBasicAuth() {
        val r = Ntrip.request(NtripConfig(host = "caster", mount = "MP1", user = "Aladdin", password = "open sesame")).decodeToString()
        assertTrue(r.startsWith("GET /MP1 HTTP/1.0\r\n"))
        assertTrue(r.contains("Authorization: Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==\r\n"), r)
        assertTrue(r.endsWith("\r\n\r\n"))
        assertEquals("YQ==", Ntrip.base64("a".encodeToByteArray()))
        assertEquals("YWI=", Ntrip.base64("ab".encodeToByteArray()))
    }

    @Test
    fun replies() {
        assertEquals(Ntrip.Reply.OK, Ntrip.reply("ICY 200 OK"))
        assertEquals(Ntrip.Reply.OK, Ntrip.reply("HTTP/1.1 200 OK\r\nContent-Type: gnss/data"))
        assertEquals(Ntrip.Reply.SOURCETABLE, Ntrip.reply("SOURCETABLE 200 OK\r\nServer: x"))
        assertEquals(Ntrip.Reply.UNAUTHORIZED, Ntrip.reply("HTTP/1.0 401 Unauthorized"))
        assertEquals(Ntrip.Reply.ERROR, Ntrip.reply("HTTP/1.0 404 Not Found"))
    }

    @Test
    fun ggaIsValidNmea() {
        val s = Ntrip.gga(52.123456789, -21.5, 45319.5, 4, 14, 0.7, 101.25)
        assertTrue(s.startsWith("\$GPGGA,123519.50,5207.407407,N,02130.000000,W,4,14,0.7,101.2,M"), s)
        val g = assertNotNull(Nmea.parseGga(s.trim()))
        assertEquals(52.123456789, g.latitude, 1e-7)
        assertEquals(-21.5, g.longitude, 1e-9)
        assertEquals(FixQuality.RTK_FIX, g.fix)
        assertEquals(45319.5, g.utcSeconds!!, 1e-9)
    }

    @Test
    fun configJson() {
        val c = NtripConfig(true, "h\"x", 2102, "M", "u", "p\\q", 5, 2233)
        assertEquals(c, NtripConfig.fromJson(c.toJson()))
    }

    @Test
    fun freeDriveFrame() {
        val h = HardwareSession()
        h.setFreeDrive(true)
        h.nudgeFreeDrive(5.0)
        h.nudgeFreeDrive(100.0) // ograniczone do maks. skrętu (30°)
        val f = assertNotNull(AogFrame.parse(h.steerFrame()))
        assertEquals(80, (f.data[0].toInt() and 0xFF) or ((f.data[1].toInt() and 0xFF) shl 8))
        assertEquals(1, f.data[2].toInt())
        assertEquals(3000, ((f.data[3].toInt() and 0xFF) or ((f.data[4].toInt() and 0xFF) shl 8)).toShort().toInt())
    }

    @Test
    fun sourcetableIsParsedAndSortedByDistance() {
        val text = "SOURCETABLE 200 OK\r\nContent-Type: text/plain\r\n\r\n" +
            "CAS;system.asgeupos.pl;8080;ASG;GUGiK;0;POL;52.0;21.0\r\n" +
            "NET;ASG-EUPOS;GUGiK;B;N;;;;\r\n" +
            "STR;KRAK_RTCM_3_1;Krakow;RTCM 3.1;1004(1),1012(1);2;GPS+GLO;ASG-EUPOS;POL;50.07;19.92;0;0;sNTRIP;none;B;N;9600;\r\n" +
            "STR;WROC_RTCM_3_1;Wroclaw;RTCM 3.1;1004(1);2;GPS;ASG-EUPOS;POL;51.11;17.06;0;0;sNTRIP;none;B;N;9600;\r\n" +
            "STR;NAWGEO_POJ_3_1;VRS;RTCM 3.1;;2;GPS+GLO;ASG-EUPOS;POL;0.00;0.00;1;1;sNTRIP;none;B;N;9600;\r\n" +
            "ENDSOURCETABLE\r\n"
        val list = Ntrip.parseSourcetable(text)
        assertEquals(listOf("KRAK_RTCM_3_1", "WROC_RTCM_3_1", "NAWGEO_POJ_3_1"), list.map { it.name })
        val vrs = list[2]
        assertTrue(vrs.nmea && vrs.networkSolution)
        assertEquals(null, vrs.lat)
        // ciągnik pod Wrocławiem: najpierw Wrocław, potem Kraków, punkt bez współrzędnych na końcu
        val sorted = Ntrip.sortByDistance(list, 51.0, 17.0)
        assertEquals(listOf("WROC_RTCM_3_1", "KRAK_RTCM_3_1", "NAWGEO_POJ_3_1"), sorted.map { it.first.name })
        val dKrak = sorted[1].second!!
        assertTrue(dKrak in 210.0..240.0, "Wrocław–Kraków ok. 220 km, wyszło $dKrak")
        // zapytanie o listę: „GET /” bez punktu montowania
        val r = Ntrip.sourcetableRequest(NtripConfig(host = "c", mount = "X")).decodeToString()
        assertTrue(r.startsWith("GET / HTTP/1.0\r\n"))
    }
}
