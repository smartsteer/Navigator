package eu.smartsteer.navigator.core.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import eu.smartsteer.navigator.ui.power.PowerPlatform
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/**
 * Android bez płytki AIO: źródła GPS na liście „portów” (ekran Połączenia → Odbiornik GPS):
 *  - „Telefon — GPS wbudowany”: zdania NMEA prosto z odbiornika telefonu (gdy ich nie podaje — budujemy GGA/VTG
 *    z pozycji); dokładność 2–5 m (L1/L5: 1–2 m) — oprysk, rozsiewanie, granice, hektary; nie do autosterowania,
 *  - „BT: nazwa — adres”: sparowany odbiornik GNSS na Bluetooth (profil szeregowy SPP), NMEA w obie strony —
 *    poprawki NTRIP idą do odbiornika (RTK przez tablet).
 */
actual object SerialPorts {
    const val PHONE = "Telefon — GPS wbudowany"
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    actual val supported: Boolean = true

    private fun ctx(): Context? = PowerPlatform.activity?.get()?.applicationContext

    private fun has(p: String) = ctx()?.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Pyta o uprawnienia (okno systemu); zwraca true, gdy już są. */
    private fun ask(vararg perms: String): Boolean {
        val missing = perms.filter { !has(it) }
        if (missing.isEmpty()) return true
        PowerPlatform.activity?.get()?.requestPermissions(missing.toTypedArray(), 4711)
        return false
    }

    private val btPerms: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()

    @SuppressLint("MissingPermission")
    actual fun list(): List<String> {
        val out = mutableListOf(PHONE)
        val c = ctx() ?: return out
        if (!ask(*btPerms)) return out + "BT: zezwól na Bluetooth i naciśnij „Odśwież porty”"
        val bt = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return out
        runCatching { bt.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .sortedBy { it.name ?: "" }
            .forEach { out.add("BT: ${it.name ?: "?"} — ${it.address}") }
        return out
    }

    actual fun create(): SerialLink = AndroidGpsLink()

    private class AndroidGpsLink : SerialLink {
        @Volatile private var closed = false
        private var lm: LocationManager? = null
        private var nmea: OnNmeaMessageListener? = null
        private var loc: LocationListener? = null
        private var socket: BluetoothSocket? = null
        @Volatile private var out: OutputStream? = null
        @Volatile private var lastGgaMs = 0L

        @SuppressLint("MissingPermission")
        override fun open(port: String, baud: Int, onLine: (String) -> Unit, onClosed: (String?) -> Unit): String? {
            val c = ctx() ?: return "Brak dostępu do systemu"
            if (port == PHONE) {
                if (!ask(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    return "Zezwól na lokalizację (okno systemu) i naciśnij „Połącz” jeszcze raz"
                val m = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return "Telefon nie ma GPS"
                if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) return "Włącz lokalizację w telefonie (GPS)"
                val main = Handler(Looper.getMainLooper())
                val n = OnNmeaMessageListener { msg, _ ->
                    if (closed) return@OnNmeaMessageListener
                    val s = msg.trim()
                    if (s.length > 6 && s.substring(3, 6) == "GGA") lastGgaMs = System.currentTimeMillis()
                    onLine(s)
                }
                // pozycja co 0,1–1 s (to też trzyma odbiornik włączony); gdy telefon nie podaje NMEA — GGA/VTG z pozycji
                val l = object : LocationListener {
                    override fun onLocationChanged(p: Location) {
                        if (closed || System.currentTimeMillis() - lastGgaMs < 2500) return
                        gga(p)?.let(onLine); vtg(p)?.let(onLine)
                    }
                    @Deprecated("Android < 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) { onClosed("Lokalizacja w telefonie wyłączona") }
                }
                runCatching { m.addNmeaListener(n, main) }
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, l, Looper.getMainLooper())
                lm = m; nmea = n; loc = l
                return null
            }
            if (port.startsWith("BT: ")) {
                if (!ask(*btPerms)) return "Zezwól na Bluetooth (okno systemu) i naciśnij „Połącz” jeszcze raz"
                val addr = port.substringAfterLast(" — ").trim()
                val bt = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return "Telefon nie ma Bluetooth"
                if (!bt.isEnabled) return "Włącz Bluetooth"
                val dev = runCatching { bt.getRemoteDevice(addr) }.getOrNull() ?: return "Nie znam urządzenia $addr — sparuj je w ustawieniach Bluetooth"
                Thread({
                    try {
                        runCatching { bt.cancelDiscovery() }
                        val s = dev.createRfcommSocketToServiceRecord(SPP)
                        socket = s
                        s.connect()
                        out = s.outputStream
                        val r = s.inputStream.bufferedReader(Charsets.US_ASCII)
                        while (!closed) {
                            val line = r.readLine() ?: break
                            if (line.startsWith("$")) onLine(line.trim())
                        }
                        if (!closed) onClosed("Odbiornik Bluetooth rozłączony")
                    } catch (e: Exception) {
                        if (!closed) onClosed("Bluetooth: ${e.message ?: "nie udało się połączyć"} (odbiornik włączony i w zasięgu?)")
                    }
                }, "gps-bt").apply { isDaemon = true }.start()
                return null
            }
            return "Nieznane źródło: $port"
        }

        override fun write(bytes: ByteArray) {
            runCatching { out?.write(bytes); out?.flush() }               // poprawki RTCM z NTRIP do odbiornika BT
        }

        @SuppressLint("MissingPermission")
        override fun close() {
            closed = true
            runCatching { nmea?.let { lm?.removeNmeaListener(it) } }
            runCatching { loc?.let { lm?.removeUpdates(it) } }
            runCatching { socket?.close() }
            lm = null; nmea = null; loc = null; socket = null; out = null
        }
    }

    // ---------------- GGA / VTG z pozycji (telefony, które nie podają NMEA) ----------------

    private fun cs(body: String): String = "$" + body + "*" + String.format(Locale.US, "%02X", body.fold(0) { a, ch -> a xor ch.code })

    private fun dm(v: Double, lat: Boolean): String {
        val a = abs(v); val d = floor(a).toInt(); val m = (a - d) * 60
        return if (lat) String.format(Locale.US, "%02d%010.7f,%s", d, m, if (v >= 0) "N" else "S")
        else String.format(Locale.US, "%03d%010.7f,%s", d, m, if (v >= 0) "E" else "W")
    }

    private fun gga(p: Location): String? {
        val t = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = p.time }
        val hhmmss = String.format(Locale.US, "%02d%02d%05.2f", t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE),
            t.get(java.util.Calendar.SECOND) + t.get(java.util.Calendar.MILLISECOND) / 1000.0)
        val sats = p.extras?.getInt("satellites", 0)?.takeIf { it > 0 } ?: 8
        val hdop = if (p.hasAccuracy()) (p.accuracy / 5.0).coerceIn(0.5, 20.0) else 1.5     // przybliżenie z dokładności (m)
        val alt = if (p.hasAltitude()) p.altitude else 0.0
        return cs(String.format(Locale.US, "GPGGA,%s,%s,%s,1,%02d,%.1f,%.1f,M,0.0,M,,", hhmmss, dm(p.latitude, true), dm(p.longitude, false), sats, hdop, alt))
    }

    private fun vtg(p: Location): String? {
        if (!p.hasSpeed()) return null
        val kmh = p.speed * 3.6
        val course = if (p.hasBearing()) String.format(Locale.US, "%.1f", p.bearing) else ""
        return cs(String.format(Locale.US, "GPVTG,%s,T,,M,%.2f,N,%.2f,K,A", course, kmh / 1.852, kmh))
    }
}
