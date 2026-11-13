package eu.smartsteer.navigator.core.aog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AogFrameTest {

    @Test
    fun buildAndParseRoundTrip() {
        val frame = AogFrame.build(Pgn.STEER_DATA, byteArrayOf(1, 2, 3))
        assertEquals(9, frame.size)
        assertEquals(0x80, frame[0].u8())
        assertEquals(0x81, frame[1].u8())
        // CRC = (0x7F + 0xFE + 3 + 1 + 2 + 3) & 0xFF
        assertEquals((0x7F + 0xFE + 3 + 1 + 2 + 3) and 0xFF, frame.last().u8())

        val parsed = assertNotNull(AogFrame.parse(frame))
        assertEquals(Pgn.STEER_DATA, parsed.pgn)
        assertEquals(listOf<Byte>(1, 2, 3), parsed.data.toList())
    }

    @Test
    fun rejectsBadCrc() {
        val frame = AogFrame.build(Pgn.STEER_DATA, byteArrayOf(1, 2, 3))
        frame[frame.lastIndex] = (frame.last() + 1).toByte()
        assertNull(AogFrame.parse(frame))
    }

    @Test
    fun steerDataEncoding() {
        val frame = SteerData(speedKmh = 8.4, autosteerOn = true, steerAngleDeg = -1.1, lineDistanceCm = 3, sections = 0b0011_1111).toFrame()
        assertEquals(14, frame.size)
        assertEquals(84, frame[5].u8() or (frame[6].u8() shl 8))
        assertEquals(1, frame[7].u8())
        assertEquals(-110, ((frame[8].u8()) or (frame[9].u8() shl 8)).toShort().toInt())
        assertEquals(0x3F, frame[11].u8())
    }

    @Test
    fun autosteerStatusDecoding() {
        val data = byteArrayOf(
            0xA6.toByte(), 0xFF.toByte(), // -90 -> -0,9°
            0x0F, 0x27,                   // 9999 -> brak kursu
            0xB8.toByte(), 0x22,          // 8888 -> brak przechyłu
            0x03,                         // bit 0 roboczy, bit 1 = włącznik autosteru rozwarty (wyłączony)
            48,
        )
        val frame = assertNotNull(AogFrame.parse(AogFrame.build(Pgn.FROM_AUTOSTEER, data, source = 0x7E)))
        val status = assertNotNull(AutosteerStatus.from(frame))
        assertEquals(-0.9, status.actualAngleDeg, 1e-9)
        assertNull(status.headingDeg)
        assertNull(status.rollDeg)
        assertEquals(true, status.workSwitch)
        assertEquals(false, status.steerSwitch)   // jak AgOpenGPS: steerSwitchHigh = wyłączony
        assertEquals(48, status.pwm)
    }
}
