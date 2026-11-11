package eu.smartsteer.navigator.core.safety

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

private class JvmObstacleReceiver : ObstacleReceiver {
    @Volatile private var socket: DatagramSocket? = null

    override fun start(port: Int, onText: (String) -> Unit): String? {
        stop()
        val s = try {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        } catch (e: Exception) {
            return "Port $port zajęty albo niedostępny (${e.message})"
        }
        socket = s
        Thread({
            val buf = ByteArray(16 * 1024)
            while (!s.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    onText(String(p.data, 0, p.length, Charsets.UTF_8))
                } catch (_: Exception) {
                    if (s.isClosed) break
                }
            }
        }, "czujnik-przeszkod").apply { isDaemon = true }.start()
        return null
    }

    override fun stop() {
        socket?.close()
        socket = null
    }
}

actual fun createObstacleReceiver(): ObstacleReceiver = JvmObstacleReceiver()
