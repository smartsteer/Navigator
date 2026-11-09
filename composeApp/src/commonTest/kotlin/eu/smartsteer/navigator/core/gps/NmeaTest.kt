package eu.smartsteer.navigator.core.gps

import eu.smartsteer.navigator.core.geo.LocalPlane
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NmeaTest {

    @Test
    fun parsesRtkFixGga() {
        val g = assertNotNull(Nmea.parseGga("\$GNGGA,123519.00,5150.12340,N,01920.56780,E,4,24,0.6,182.3,M,34.5,M,0.4,0000*59"))
        assertEquals(FixQuality.RTK_FIX, g.fix)
        assertEquals(24, g.satellites)
        assertTrue(abs(g.latitude - (51 + 50.1234 / 60)) < 1e-9)
        assertTrue(abs(g.longitude - (19 + 20.5678 / 60)) < 1e-9)
        assertEquals(0.4, g.correctionAge)
    }

    @Test
    fun rejectsBadChecksum() {
        assertNull(Nmea.parseGga("\$GNGGA,123519.00,5150.12340,N,01920.56780,E,4,24,0.6,182.3,M,34.5,M,0.4,0000*00"))
    }

    @Test
    fun parsesVtg() {
        val v = assertNotNull(Nmea.parseVtg("\$GNVTG,357.2,T,,M,4.54,N,8.40,K,D*1C"))
        assertEquals(8.4, v.speedKmh)
        assertEquals(357.2, v.courseDeg)
    }

    @Test
    fun localPlaneRoundTrip() {
        val plane = LocalPlane(51.8354, 19.3428)
        val p = plane.toLocal(51.8354 + 0.001, 19.3428 + 0.001)
        // ~111 m na północ, ~69 m na wschód na tej szerokości
        assertTrue(abs(p.y - 111.3) < 0.5, "y=${p.y}")
        assertTrue(abs(p.x - 69.0) < 0.5, "x=${p.x}")
        val (lat, lon) = plane.toGeo(p)
        assertTrue(abs(lat - 51.8364) < 1e-9 && abs(lon - 19.3438) < 1e-9)
    }
}
