package eu.smartsteer.navigator.core.war

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Zgodność z docs/WAR.md §1 — wartości policzone niezależnie (Python: hashlib + cryptography AESGCM). */
class WarCryptoTest {
    private val code = "ABCDEFGHJKLMNPQR"

    @Test
    fun mailboxAndKey() {
        assertEquals(
            "169278ba1fb0a069d8f90b883a1c48173e427bffceed2965a41321b8b9c5a458",
            WarCrypto.sha256Hex(WarProtocol.worksMailboxText(code)),
        )
        val key = WarCrypto.deriveKey(code)
        assertEquals("d9a035cb3547b1622285f59606e467b0f85678f5a71803058705f7ad05f8b0a7", key.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') })
    }

    @Test
    fun decryptsPacketFromOtherImplementation() {
        val key = WarCrypto.deriveKey(code)
        // iv = 12 × 0x01, treść {"z":"py"} — zaszyfrowane w Pythonie
        assertEquals("{\"z\":\"py\"}", WarCrypto.decrypt(key, "AQEBAQEBAQEBAQEBbm/BhhmZnGC/k82c07QtFe0/N6ug2K5cRkQ=")!!.decodeToString())
        val p = WarCrypto.encrypt(key, "zażółć".encodeToByteArray())
        assertEquals("zażółć", WarCrypto.decrypt(key, p)!!.decodeToString())
        assertNull(WarCrypto.decrypt(WarCrypto.deriveKey("ZZZZZZZZZZZZZZZZ"), p))
    }
}
