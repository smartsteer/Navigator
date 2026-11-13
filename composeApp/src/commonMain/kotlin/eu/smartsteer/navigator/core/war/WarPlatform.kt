package eu.smartsteer.navigator.core.war

/** Kryptografia WAR (docs/WAR.md §1) — implementacja na JVM (javax.crypto) w androidMain/desktopMain. */
expect object WarCrypto {
    /** PBKDF2-HMAC-SHA256(kod, „war-sync-v1”, 150 000) → 32 bajty. Wolne (0,1–0,5 s) — liczyć raz, poza wątkiem UI. */
    fun deriveKey(code: String): ByteArray
    /** base64( iv[12] + szyfrogram + tag[16] ), AES-256-GCM bez AAD. */
    fun encrypt(key: ByteArray, plain: ByteArray): String
    /** null = nie da się odszyfrować (inny kod, uszkodzona paczka). */
    fun decrypt(key: ByteArray, packet: String): ByteArray?
    /** SHA-256 tekstu UTF-8 jako hex małymi literami. */
    fun sha256Hex(text: String): String
    /** Losowe bajty (id maszyny). */
    fun random(n: Int): ByteArray
}

/** Odpowiedź HTTP: kod i treść (także przy błędzie). code = 0 — brak połączenia. */
data class HttpResult(val code: Int, val body: String)

/** Wywołanie HTTP(S) z JSON — blokujące, wywoływać poza wątkiem UI. */
expect fun httpJson(method: String, url: String, body: String?, userAgent: String): HttpResult

/** Bieżący czas ISO 8601 ze strefą, np. 2026-11-10T08:12:00+01:00. */
expect fun nowIso(): String

/** Milisekundy od 1970 (UTC). */
expect fun nowMillis(): Long

/** Otwiera adres w przeglądarce systemu (Windows) — np. WAR Cam. */
expect fun openUrl(url: String): Boolean
