package eu.smartsteer.navigator.core.net

import eu.smartsteer.navigator.core.aog.AogFrame
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

actual fun createUdpLink(): UdpLink = JvmUdpLink()

actual fun localAddresses(): List<LocalAddress> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nic ->
            nic.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .map { LocalAddress(it.hostAddress ?: "", nic.displayName ?: nic.name) }
        }
} catch (e: Exception) {
    emptyList()
}

private class JvmUdpLink : UdpLink {
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var target: InetAddress = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 5, 255.toByte()))

    @Volatile private var unicast: List<InetAddress> = emptyList()

    override fun setUnicast(ips: List<String>) {
        unicast = ips.mapNotNull { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
    }

    override fun setSubnet(a: Int, b: Int, c: Int) {
        target = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), 255.toByte()))
    }

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

    override fun send(bytes: ByteArray, port: Int): String? {
        val s = socket ?: return "Nasłuch nie działa"
        return try {
            val u = unicast
            if (u.isEmpty()) s.send(DatagramPacket(bytes, bytes.size, target, port))
            else for (ip in u) s.send(DatagramPacket(bytes, bytes.size, ip, port))
            null
        } catch (e: Exception) {
            "Brak sieci ${target.hostAddress?.removeSuffix(".255")}.x: ${e.message}"
        }
    }

    override fun broadcastAll(bytes: ByteArray, port: Int): String? {
        val all = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        var sent = 0
        var lastError: String? = null
        for (a in localAddresses()) {
            try {
                DatagramSocket(null).use { t ->
                    t.reuseAddress = true
                    t.broadcast = true
                    // jak AgIO: wysyłka z portu 9999 danej karty — moduły odpowiadają na 9999
                    try { t.bind(InetSocketAddress(InetAddress.getByName(a.address), AogFrame.PORT_FROM_MODULES)) } catch (e: Exception) { t.bind(InetSocketAddress(InetAddress.getByName(a.address), 0)) }
                    t.send(DatagramPacket(bytes, bytes.size, all, port))
                    sent++
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
        return if (sent > 0) null else (lastError ?: "Brak włączonej karty sieciowej")
    }

    override fun stop() {
        socket?.close()
        socket = null
    }
}
