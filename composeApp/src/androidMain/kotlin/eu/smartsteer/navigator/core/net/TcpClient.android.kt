package eu.smartsteer.navigator.core.net

import java.net.InetSocketAddress
import java.net.Socket

actual fun createTcpClient(): TcpClient = JvmTcpClient()

private class JvmTcpClient : TcpClient {
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    override fun start(
        host: String,
        port: Int,
        onConnected: () -> Unit,
        onData: (ByteArray, Int) -> Unit,
        onClosed: (String?) -> Unit,
    ) {
        Thread({
            var error: String? = null
            try {
                val s = Socket()
                socket = s
                s.connect(InetSocketAddress(host, port), 10_000)
                s.soTimeout = 30_000 // brak danych przez 30 s = zerwane połączenie
                onConnected()
                val inp = s.getInputStream()
                val buf = ByteArray(4096)
                while (!closed) {
                    val n = inp.read(buf)
                    if (n < 0) { error = "Caster zamknął połączenie"; break }
                    if (n > 0) onData(buf, n)
                }
            } catch (e: Exception) {
                if (!closed) error = e.message ?: e.toString()
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                onClosed(if (closed) null else error)
            }
        }, "ntrip").apply { isDaemon = true }.start()
    }

    override fun send(bytes: ByteArray): String? {
        val s = socket ?: return "Brak połączenia"
        return try {
            s.getOutputStream().apply { write(bytes); flush() }
            null
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) {}
    }
}
