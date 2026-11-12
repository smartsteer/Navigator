package eu.smartsteer.navigator.core.net

import com.fazecast.jSerialComm.SerialPort

actual object SprayerPorts {
    actual val supported: Boolean = true
    actual fun list(): List<String> = SerialPorts.list()
    actual fun create(): ByteLink = JvmByteLink()
}

private class JvmByteLink : ByteLink {
    @Volatile private var port: SerialPort? = null
    @Volatile private var closed = false

    override fun open(port: String, onBytes: (ByteArray, Int) -> Unit, onClosed: (String?) -> Unit): String? {
        val name = port.substringBefore(" — ").trim()
        val p = try { SerialPort.getCommPort(name) } catch (e: Throwable) { return "Brak portu $name" }
        p.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 0)
        if (!p.openPort()) return "Nie można otworzyć $name (zajęty przez inny program?)"
        this.port = p
        closed = false
        Thread({
            var err: String? = null
            val buf = ByteArray(1024)
            try {
                while (!closed) {
                    val n = p.readBytes(buf, buf.size)
                    if (n < 0) { err = "Port $name rozłączony"; break }
                    if (n > 0) onBytes(buf, n)
                }
            } catch (e: Throwable) {
                if (!closed) err = e.message ?: e.toString()
            } finally {
                try { p.closePort() } catch (_: Throwable) {}
                onClosed(if (closed) null else err)
            }
        }, "oprysk-serial").apply { isDaemon = true }.start()
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
