package eu.smartsteer.navigator.core.net

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import eu.smartsteer.navigator.ui.power.PowerPlatform

/**
 * Płytka opryskiwacza na USB (tablet jako USB host): urządzenie klasy CDC ACM (wirtualny port szeregowy).
 * Nazwa na liście: „USB: produkt — VID:PID”. Przy pierwszym połączeniu Android pyta o zgodę na urządzenie.
 */
actual object SprayerPorts {
    private const val ACTION = "eu.smartsteer.navigator.USB_PERMISSION"
    actual val supported: Boolean = true

    private fun ctx(): Context? = PowerPlatform.activity?.get()?.applicationContext
    private fun usb(): UsbManager? = ctx()?.getSystemService(Context.USB_SERVICE) as? UsbManager

    private fun key(d: UsbDevice) = "%04X:%04X".format(d.vendorId, d.productId)

    actual fun list(): List<String> {
        val m = usb() ?: return emptyList()
        return m.deviceList.values.map { d -> "USB: ${d.productName ?: d.deviceName} — ${key(d)}" }
    }

    actual fun create(): ByteLink = CdcLink()

    private class CdcLink : ByteLink {
        @Volatile private var conn: UsbDeviceConnection? = null
        @Volatile private var out: UsbEndpoint? = null
        @Volatile private var closed = false
        private var claimed = ArrayList<UsbInterface>()

        override fun open(port: String, onBytes: (ByteArray, Int) -> Unit, onClosed: (String?) -> Unit): String? {
            val c = ctx() ?: return "Brak aplikacji"
            val m = usb() ?: return "USB niedostępne"
            val k = port.substringAfterLast(" — ").trim()
            val dev = m.deviceList.values.firstOrNull { key(it) == k } ?: return "Nie widzę urządzenia USB $k — podłącz płytkę"
            if (!m.hasPermission(dev)) {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(c, 0, Intent(ACTION).setPackage(c.packageName), flags)
                m.requestPermission(dev, pi)
                return "Zezwól na dostęp do USB w okienku systemu i połącz ponownie"
            }
            var dataIf: UsbInterface? = null
            var ctrlIf: UsbInterface? = null
            for (i in 0 until dev.interfaceCount) {
                val f = dev.getInterface(i)
                if (f.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA && dataIf == null) dataIf = f
                if (f.interfaceClass == UsbConstants.USB_CLASS_COMM && ctrlIf == null) ctrlIf = f
            }
            // urządzenia bez opisu CDC (np. własny układ USB‑UART): pierwszy interfejs z parą bulk IN/OUT
            if (dataIf == null) dataIf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { f ->
                (0 until f.endpointCount).any { f.getEndpoint(it).type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            }
            val d = dataIf ?: return "To urządzenie USB nie jest portem szeregowym"
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until d.endpointCount) {
                val e = d.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN) epIn = e else epOut = e
            }
            if (epIn == null || epOut == null) return "Brak kanałów danych USB"
            val cn = m.openDevice(dev) ?: return "Nie można otworzyć urządzenia USB"
            ctrlIf?.let { if (cn.claimInterface(it, true)) claimed.add(it) }
            if (!cn.claimInterface(d, true)) { cn.close(); return "Urządzenie USB zajęte" }
            claimed.add(d)
            val ctrlIndex = ctrlIf?.id ?: 0
            // SET_LINE_CODING 115200 8N1, SET_CONTROL_LINE_STATE DTR+RTS (CDC ACM)
            val lc = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0, 0, 8)
            cn.controlTransfer(0x21, 0x20, 0, ctrlIndex, lc, lc.size, 500)
            cn.controlTransfer(0x21, 0x22, 0x03, ctrlIndex, null, 0, 500)
            conn = cn; out = epOut; closed = false
            val inEp = epIn
            Thread({
                var err: String? = null
                val buf = ByteArray(maxOf(64, inEp.maxPacketSize) * 8)
                try {
                    while (!closed) {
                        val n = cn.bulkTransfer(inEp, buf, buf.size, 300)
                        if (n > 0) onBytes(buf, n)
                        else if (n < 0 && m.deviceList.values.none { key(it) == k }) { err = "Płytka odłączona od USB"; break }
                    }
                } catch (e: Throwable) {
                    if (!closed) err = e.message ?: e.toString()
                } finally {
                    runCatching { claimed.forEach { cn.releaseInterface(it) } }
                    runCatching { cn.close() }
                    onClosed(if (closed) null else err)
                }
            }, "oprysk-usb").apply { isDaemon = true }.start()
            return null
        }

        override fun write(bytes: ByteArray) {
            val c = conn ?: return
            val e = out ?: return
            runCatching { c.bulkTransfer(e, bytes, bytes.size, 200) }
        }

        override fun close() { closed = true }
    }
}
