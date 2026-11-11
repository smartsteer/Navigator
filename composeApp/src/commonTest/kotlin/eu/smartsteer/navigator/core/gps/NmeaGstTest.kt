package eu.smartsteer.navigator.core.gps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NmeaGstTest {
    private fun cs(body: String) = "$" + body + "*" + body.fold(0) { a, c -> a xor c.code }.toString(16).uppercase().padStart(2, '0')

    @Test
    fun horizontalErrorFromGst() {
        // u-blox: błąd szer. 0,012 m, dł. 0,009 m → 0,015 m
        assertEquals(0.015, Nmea.parseGstHorizontal(cs("GNGST,082356.00,0.5,,,,0.012,0.009,0.02"))!!, 1e-6)
        // GPS telefonu: dokładność 4 m → 2,83 / 2,83 → 4 m
        assertEquals(4.0, Nmea.parseGstHorizontal(cs("GPGST,,4.00,,,,2.83,2.83,"))!!, 0.01)
        assertNull(Nmea.parseGstHorizontal(cs("GPGST,,,,,,,,")))
        assertNull(Nmea.parseGstHorizontal("\$GPGST,,4.00,,,,2.83,2.83,*00"), "zła suma kontrolna")
    }
}
