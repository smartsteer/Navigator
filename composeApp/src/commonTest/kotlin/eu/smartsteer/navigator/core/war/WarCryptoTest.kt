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

    /** docs/WAR.md §3.10 — wektor testowy skrzynki floty (z WAR, sprawdzony w Pythonie). */
    @Test
    fun fleetVector() {
        assertEquals("5c9804e7df32aa3c7bc85063c111ba510b553a6618f3f064537a10758e7fa19e", WarCrypto.sha256Hex(WarProtocol.fleetMailboxText("ABCD-EFGH-JKLM-NPQR")))
        val key = WarCrypto.deriveKey(code)
        val plain = WarCrypto.decrypt(key, "AAECAwQFBgcICQoL8EqYwnX6Gwx1p4X0JyfGTHtWu1+ODHhYZ0xivEQOkHSxVDuhOlFoHkZ9L14XeFDXFQ1C7xTyTTZJfT1/Hg/TvutWNxqU4ISRyFlAlLZ1fYwgOJ4a1DEiTe4=")!!.decodeToString()
        assertEquals("{\"typ\":\"pozycja_floty\",\"v\":1,\"komputer\":\"tab-test\",\"lat\":52.1,\"lon\":19.4}", plain)
        val m = eu.smartsteer.navigator.core.update.MiniJson.parse(plain) as Map<*, *>
        val fm = WarProtocol.parseFleetPosition(m, "tab-test", 1000)!!
        assertEquals(52.1, fm.lat)
        assertEquals("tab-test", fm.tractor)
        // własna paczka pozycji czyta się z powrotem
        val c = WarConfig(code = code, machineId = "tab-ja")
        val own = WarProtocol.fleetPosition(52.2, 19.5, 90.0, 8.4, 4, "Opryskiwacz", 21.0, "p1", "Za stodołą", true, "Renault", c, "2026-11-13T08:00:00Z")
        val back = WarProtocol.parseFleetPosition(eu.smartsteer.navigator.core.update.MiniJson.parse(own) as Map<*, *>, "tab-ja", 5)!!
        assertEquals("p1", back.warId)
        assertEquals(true, back.working)
        assertEquals(21.0, back.width)
    }
}
