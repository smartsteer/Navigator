package eu.smartsteer.navigator.ui.camera

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

actual class CameraReader actual constructor() {
    @Volatile private var stopped = false
    @Volatile private var conn: HttpURLConnection? = null

    actual fun start(url: String, onJpeg: (ByteArray) -> Unit, onError: (String) -> Unit) {
        stopped = false
        Thread({
            while (!stopped) {
                try {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 10000 }
                    conn = c
                    val type = c.contentType.orEmpty().lowercase()
                    if (c.responseCode != 200) { onError("Kamera odpowiada ${c.responseCode}"); Thread.sleep(3000); continue }
                    if (type.startsWith("image/")) {
                        // pojedyncze zdjęcie: odpytujemy co 0,3 s
                        onJpeg(c.inputStream.use { it.readBytes() })
                        c.disconnect()
                        Thread.sleep(300)
                    } else {
                        readMjpeg(c.inputStream, onJpeg)
                    }
                } catch (e: Exception) {
                    if (!stopped) { onError("Brak obrazu: ${e.message ?: e}"); Thread.sleep(3000) }
                }
            }
        }, "camera").apply { isDaemon = true }.start()
    }

    /** Strumień MJPEG: klatki od znacznika FFD8 do FFD9. */
    private fun readMjpeg(input: InputStream, onJpeg: (ByteArray) -> Unit) {
        input.buffered(64 * 1024).use { inp ->
            val out = ByteArrayOutputStream(64 * 1024)
            var inFrame = false
            var prev = -1
            while (!stopped) {
                val b = inp.read()
                if (b < 0) break
                if (!inFrame) {
                    if (prev == 0xFF && b == 0xD8) { inFrame = true; out.reset(); out.write(0xFF); out.write(0xD8) }
                } else {
                    out.write(b)
                    if (prev == 0xFF && b == 0xD9) { inFrame = false; onJpeg(out.toByteArray()) }
                    if (out.size() > 4_000_000) { inFrame = false; out.reset() }
                }
                prev = b
            }
        }
    }

    actual fun stop() {
        stopped = true
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}
