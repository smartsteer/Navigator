package eu.smartsteer.navigator.core.net

import com.fazecast.jSerialComm.SerialPort

actual object SerialPorts {
    actual val supported: Boolean = true
    actual fun list(): List<String> = try {
        SerialPort.getCommPorts().map { it.systemPortName + (it.descriptivePortName?.let { d -> " — $d" } ?: "") }
    } catch (_: Throwable) { emptyList() }
    actual fun create(): SerialLink = JvmSerialLink()
}

private class JvmSerialLink : SerialLink {
    @Volatile private var port: SerialPort? = null
    @Volatile private var closed = false

    override fun open(port: String, baud: Int, onLine: (String) -> Unit, onClosed: (String?) -> Unit): String? {
        val name = port.substringBefore(" — ").trim()
        val p = try { SerialPort.getCommPort(name) } catch (e: Throwable) { return "Brak portu $name" }
        p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1000, 0)
        if (!p.openPort()) return "Nie można otworzyć $name (zajęty przez inny program?)"
        this.port = p
        closed = false
        Thread({
            var err: String? = null
            val sb = StringBuilder()
            val buf = ByteArray(1024)
            try {
                while (!closed) {
                    val n = p.readBytes(buf, buf.size)
                    if (n < 0) { err = "Port $name rozłączony"; break }
                    for (i in 0 until n) {
                        val c = (buf[i].toInt() and 0xFF).toChar()
                        if (c == '\n' || c == '\r') {
                            if (sb.isNotEmpty()) { onLine(sb.toString()); sb.setLength(0) }
                        } else if (sb.length < 512) sb.append(c) else sb.setLength(0)
                    }
                }
            } catch (e: Throwable) {
                if (!closed) err = e.message ?: e.toString()
            } finally {
                try { p.closePort() } catch (_: Throwable) {}
                onClosed(if (closed) null else err)
            }
        }, "gps-serial").apply { isDaemon = true }.start()
        return null
    }

    override fun write(bytes: ByteArray) {
        try { port?.writeBytes(bytes, bytes.size) } catch (_: Throwable) {}
    }

    override fun close() {
        closed = true
        try { port?.closePort() } catch (_: Throwable) {}
    }
}
