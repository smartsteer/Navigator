package eu.smartsteer.navigator.core.war

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

actual object WarCrypto {
    private val rnd = SecureRandom()

    actual fun deriveKey(code: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), "war-sync-v1".toByteArray(Charsets.UTF_8), 150_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    actual fun encrypt(key: ByteArray, plain: ByteArray): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain) // szyfrogram + tag (16 B) na końcu
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    actual fun decrypt(key: ByteArray, packet: String): ByteArray? = try {
        val all = Base64.getDecoder().decode(packet.trim())
        if (all.size < 12 + 16) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
            c.doFinal(all, 12, all.size - 12)
        }
    } catch (e: Exception) {
        null
    }

    actual fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    actual fun random(n: Int): ByteArray = ByteArray(n).also { rnd.nextBytes(it) }
}

actual fun httpJson(method: String, url: String, body: String?, userAgent: String): HttpResult = try {
    val c = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept", "application/json")
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }
    try {
        if (body != null) c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        HttpResult(code, stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
    } finally {
        c.disconnect()
    }
} catch (e: Exception) {
    HttpResult(0, e.message ?: e.toString())
}

actual fun nowIso(): String =
    java.time.OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun openUrl(url: String): Boolean = try {
    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    true
} catch (_: Exception) {
    false
}
