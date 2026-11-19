package eu.smartsteer.navigator.core.sprayer

import eu.smartsteer.navigator.core.aog.AogFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Wspólne wektory z firmware płytki Maszyna (firmware/maszyna/test/test_main.cpp):
 * ta sama ramka PGN 160 i PGN 161 po obu stronach — zmiana protokołu musi zmienić oba testy.
 */
class SprayerFirmwareVectorTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun commandFrameMatchesFirmware() {
        val f = SprayerCommand(
            counter = 5, mainOpen = true, sections = 0b101, sectionCount = 3,
            valveType = ValveType.ARAG_3PIN, workLights = true, twoPinDriveMs = 2500, regPulseMs = -120,
        ).toFrame()
        assertContentEquals(
            bytes(0x80, 0x81, 0x7F, 0xA0, 0x08, 0x05, 0x07, 0x05, 0x00, 0x03, 0x19, 0x88, 0xFF, 0xDB), f,
        )
    }

    @Test
    fun statusFrameFromFirmwareParses() {
        val f = bytes(
            0x80, 0x81, 0x7E, 0xA1, 0x14, 0x09, 0x03, 0x04, 0x03, 0x02, 0x01, 0xE9, 0x5B, 0x00, 0x00,
            0x38, 0x01, 0x02, 0x01, 0x02, 0x8A, 0x2F, 0x03, 0x0B, 0x02, 0x94,
        )
        val s = assertNotNull(SprayerStatus.from(assertNotNull(AogFrame.parse(f))))
        assertEquals(9, s.counter)
        assertEquals(true, s.linkOk)
        assertEquals(true, s.mainOpen)
        assertEquals(0x01020304L, s.pulseCount)
        assertEquals(23529L, s.pulsePeriodUs)
        assertEquals(3.12, s.pressureBar!!, 1e-9)
        assertEquals(0x0102, s.sectionFaults)
        assertEquals(SprayerStatus.FAULT_REG, s.faults)
        assertEquals(13.8, s.supplyVolts, 1e-9)
        assertEquals(8.15, s.boardSpeedKmh!!, 1e-9)
        assertEquals(11, s.boardSats)
        assertEquals(2, s.boardFix)
    }
}
