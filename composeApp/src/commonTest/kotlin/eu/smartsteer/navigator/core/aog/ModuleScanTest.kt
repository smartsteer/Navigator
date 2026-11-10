package eu.smartsteer.navigator.core.aog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModuleScanTest {
    @Test
    fun framesLikeAgIo() {
        val scan = ModuleScan.scanRequest()
        assertEquals(listOf(0x80, 0x81, 0x7F, 202, 3, 202, 202, 5), scan.take(8).map { it.toInt() and 0xFF })
        val sub = ModuleScan.subnetChange(192, 168, 1)
        assertEquals(listOf(0x80, 0x81, 0x7F, 201, 5, 201, 201, 192, 168, 1), sub.take(10).map { it.toInt() and 0xFF })
    }

    @Test
    fun replyFromSteerModule() {
        val reply = AogFrame.build(203, byteArrayOf(192.toByte(), 168.toByte(), 1, 126, 192.toByte(), 168.toByte(), 1), source = 126)
        val m = assertNotNull(ModuleScan.parseReply(assertNotNull(AogFrame.parse(reply))))
        assertEquals(ModuleScan.Kind.STEER, m.kind)
        assertEquals("192.168.1.126", m.ip)
        assertEquals("192.168.1", m.subnetText)
    }

    @Test
    fun subnetText() {
        assertEquals(listOf(192, 168, 5), ModuleScan.parseSubnet(" 192.168.5 "))
        assertEquals(listOf(10, 0, 0), ModuleScan.parseSubnet("10.0.0."))
        assertNull(ModuleScan.parseSubnet("192.168.5.10"))
        assertNull(ModuleScan.parseSubnet("192.300.5"))
    }
}
