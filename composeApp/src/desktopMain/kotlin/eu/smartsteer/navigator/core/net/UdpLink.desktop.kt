package eu.smartsteer.navigator.core.net

import eu.smartsteer.navigator.core.aog.AogFrame
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException

actual fun createUdpLink(): UdpLink = JvmUdpLink()

private class JvmUdpLink : UdpLink {
    @Volatile private var socket: DatagramSocket? = null
    private val target by lazy { InetAddress.getByName(MODULES_BROADCAST) }

    override fun start(onPacket: (ByteArray, Int, String) -> Unit): String? {
        if (socket != null) return null
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(AogFrame.PORT_FROM_MODULES))
            }
        } catch (e: Exception) {
            return "Port ${AogFrame.PORT_FROM_MODULES} zajęty (czy działa AgIO?): ${e.message}"
        }
        socket = s
        Thread({
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            while (!s.isClosed) {
                try {
                    pkt.length = buf.size
                    s.receive(pkt)
                    onPacket(buf, pkt.length, pkt.address?.hostAddress ?: "?")
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    // pojedynczy zły pakiet nie zatrzymuje nasłuchu
                }
            }
        }, "udp-9999").apply { isDaemon = true }.start()
        return null
    }

    override fun send(bytes: ByteArray): String? {
        val s = socket ?: return "Nasłuch nie działa"
        return try {
            s.send(DatagramPacket(bytes, bytes.size, target, AogFrame.PORT_TO_MODULES))
            null
        } catch (e: Exception) {
            "Brak sieci 192.168.5.x: ${e.message}"
        }
    }

    override fun stop() {
        socket?.close()
        socket = null
    }
}
