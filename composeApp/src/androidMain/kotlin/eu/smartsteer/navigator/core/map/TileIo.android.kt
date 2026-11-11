package eu.smartsteer.navigator.core.map

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

actual object TileIo {
    private val dir: File get() = File(eu.smartsteer.navigator.core.settings.AndroidStorage.filesDir ?: File("/data/local/tmp"), "Mapa")

    actual fun read(key: String): ByteArray? = try {
        File(dir, key).takeIf { it.isFile && it.length() > 0 }?.readBytes()
    } catch (_: Exception) { null }

    actual fun write(key: String, bytes: ByteArray) {
        try {
            val f = File(dir, key)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    actual fun download(url: String): ByteArray? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "WAR-Navigator/1.0 (rolnicza nawigacja)")
        }
        try {
            if (c.responseCode != 200) null
            else {
                val type = c.contentType.orEmpty()
                val b = c.inputStream.use { it.readBytes() }
                if (type.startsWith("image/") && b.size > 100) b else null
            }
        } finally { c.disconnect() }
    } catch (_: Exception) { null }

    actual fun cacheBytes(): Long = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
}
