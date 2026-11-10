package eu.smartsteer.navigator.core.ntrip

import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.floor

/** Ustawienia klienta NTRIP (poprawki RTK przez internet tabletu). */
data class NtripConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 2101,
    val mount: String = "",
    val user: String = "",
    val password: String = "",
    /** Co ile sekund wysyłać pozycję (GGA) do castera — potrzebne dla VRS/sieci; 0 = nie wysyłać. */
    val ggaIntervalS: Int = 10,
    /** Port UDP, na który płytka AIO odbiera poprawki (jak w AgIO). */
    val udpPort: Int = 2233,
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"enabled\": ").append(enabled).append(",\n")
        append("  \"host\": \"").append(esc(host)).append("\",\n")
        append("  \"port\": ").append(port).append(",\n")
        append("  \"mount\": \"").append(esc(mount)).append("\",\n")
        append("  \"user\": \"").append(esc(user)).append("\",\n")
        append("  \"password\": \"").append(esc(password)).append("\",\n")
        append("  \"ggaIntervalS\": ").append(ggaIntervalS).append(",\n")
        append("  \"udpPort\": ").append(udpPort).append('\n')
        append("}\n")
    }

    companion object {
        fun fromJson(text: String): NtripConfig? {
            val m = MiniJson.parse(text) as? Map<*, *> ?: return null
            val d = NtripConfig()
            return NtripConfig(
                enabled = m["enabled"] as? Boolean ?: d.enabled,
                host = m["host"] as? String ?: d.host,
                port = (m["port"] as? Double)?.toInt() ?: d.port,
                mount = m["mount"] as? String ?: d.mount,
                user = m["user"] as? String ?: d.user,
                password = m["password"] as? String ?: d.password,
                ggaIntervalS = (m["ggaIntervalS"] as? Double)?.toInt() ?: d.ggaIntervalS,
                udpPort = (m["udpPort"] as? Double)?.toInt() ?: d.udpPort,
            )
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

/**
 * Protokół NTRIP 1.0 (HTTP/1.0, odpowiedź „ICY 200 OK”) — bez kodowania „chunked”.
 * Na podstawie AgOpenGPS/AgIO (© Brian Tischler i współtwórcy, GPLv3), plik
 * SourceCode/AgIO/Source/Forms/NTRIPComm.Designer.cs (zapytanie, autoryzacja, wysyłanie GGA, przekazanie RTCM na UDP 2233).
 */
object Ntrip {

    fun request(c: NtripConfig): ByteArray {
        val sb = StringBuilder()
        sb.append("GET /").append(c.mount.trim()).append(" HTTP/1.0\r\n")
        sb.append("User-Agent: NTRIP SmartSteerNavigator/1.0\r\n")
        if (c.user.isNotEmpty()) sb.append("Authorization: Basic ").append(base64("${c.user}:${c.password}".encodeToByteArray())).append("\r\n")
        sb.append("Accept: */*\r\nConnection: close\r\n\r\n")
        return sb.toString().encodeToByteArray()
    }

    /** Zapytanie o listę punktów montowania (sourcetable): „GET /” bez nazwy punktu. */
    fun sourcetableRequest(c: NtripConfig): ByteArray = request(c.copy(mount = ""))

    /**
     * Punkt montowania z listy castera (wiersz STR):
     * STR;nazwa;opis;format;szczegóły;faza;systemy;sieć;kraj;szer;dług;nmea;rozwiązanie;generator;kompresja;autoryzacja;opłata;bitrate;…
     */
    data class Mount(
        val name: String,
        val identifier: String,
        val format: String,
        val navSystem: String,
        val network: String,
        val country: String,
        val lat: Double?,
        val lon: Double?,
        /** Caster chce naszej pozycji (GGA) — zwykle sieć VRS. */
        val nmea: Boolean,
        /** 1 = rozwiązanie sieciowe (VRS / MAC), 0 = jedna stacja. */
        val networkSolution: Boolean,
        val auth: String,
    )

    /** Wiersze STR z odpowiedzi castera. */
    fun parseSourcetable(text: String): List<Mount> = text.lineSequence().mapNotNull { line ->
        val l = line.trim()
        if (!l.startsWith("STR;")) return@mapNotNull null
        val f = l.split(';')
        fun at(i: Int) = f.getOrNull(i)?.trim().orEmpty()
        val name = at(1)
        if (name.isEmpty()) return@mapNotNull null
        val lat = at(9).toDoubleOrNull()
        val lon = at(10).toDoubleOrNull()
        val zero = lat == 0.0 && lon == 0.0
        Mount(
            name = name, identifier = at(2), format = at(3), navSystem = at(6), network = at(7), country = at(8),
            lat = if (zero) null else lat, lon = if (zero) null else lon,
            nmea = at(11) == "1", networkSolution = at(12) == "1", auth = at(15),
        )
    }.toList()

    /** Odległość po kuli ziemskiej, km. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = PI / 180
        val dLat = (lat2 - lat1) * r
        val dLon = (lon2 - lon1) * r
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(lat1 * r) * kotlin.math.cos(lat2 * r) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * 6371.0 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Lista posortowana: najbliższe najpierw (bez współrzędnych na końcu, alfabetycznie). */
    fun sortByDistance(list: List<Mount>, lat: Double?, lon: Double?): List<Pair<Mount, Double?>> {
        val withD = list.map { m ->
            m to if (lat != null && lon != null && m.lat != null && m.lon != null) distanceKm(lat, lon, m.lat, m.lon) else null
        }
        return withD.sortedWith(compareBy<Pair<Mount, Double?>> { it.second == null }.thenBy { it.second ?: 0.0 }.thenBy { it.first.name })
    }

    enum class Reply { OK, UNAUTHORIZED, SOURCETABLE, ERROR }

    /** Ocena nagłówka odpowiedzi castera (pierwsza linia). */
    fun reply(header: String): Reply {
        val first = header.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            first.startsWith("ICY 200") || (first.startsWith("HTTP/") && first.contains(" 200")) ->
                if (header.contains("gnss/sourcetable", ignoreCase = true)) Reply.SOURCETABLE else Reply.OK
            first.startsWith("SOURCETABLE") -> Reply.SOURCETABLE
            first.contains(" 401") -> Reply.UNAUTHORIZED
            else -> Reply.ERROR
        }
    }

    /**
     * Zdanie GGA z bieżącej pozycji — caster sieciowy (VRS) liczy z niego poprawki dla naszego miejsca.
     * [utcSeconds] = sekundy od północy UTC.
     */
    fun gga(lat: Double, lon: Double, utcSeconds: Double, fix: Int, sats: Int, hdop: Double, altitude: Double): String {
        val t = ((utcSeconds % 86400) + 86400) % 86400
        val hh = floor(t / 3600).toInt()
        val mm = floor((t - hh * 3600) / 60).toInt()
        val ss = t - hh * 3600 - mm * 60
        val body = buildString {
            append("GPGGA,")
            append(two(hh)).append(two(mm)).append(fixed(ss, 2, 2)).append(',')
            append(coord(abs(lat), 2)).append(',').append(if (lat >= 0) 'N' else 'S').append(',')
            append(coord(abs(lon), 3)).append(',').append(if (lon >= 0) 'E' else 'W').append(',')
            append(fix.coerceIn(0, 9)).append(',').append(sats.coerceIn(0, 99).toString().padStart(2, '0')).append(',')
            append(fixed(hdop, 1, 1)).append(',').append(fixed(altitude, 1, 1)).append(",M,0.0,M,,")
        }
        var x = 0
        for (ch in body) x = x xor ch.code
        return "$" + body + "*" + x.toString(16).uppercase().padStart(2, '0') + "\r\n"
    }

    private fun coord(v: Double, degDigits: Int): String {
        var d = floor(v).toInt()
        var m = (v - d) * 60
        if (m >= 59.9999995) { d += 1; m = 0.0 }
        return d.toString().padStart(degDigits, '0') + fixed(m, 2, 6)
    }

    private fun two(v: Int) = v.toString().padStart(2, '0')

    /** Liczba z [dec] miejscami po kropce i co najmniej [intDigits] cyframi przed nią. */
    private fun fixed(v: Double, intDigits: Int, dec: Int): String {
        var scale = 1L
        repeat(dec) { scale *= 10 }
        val neg = v < 0
        val r = kotlin.math.round(abs(v) * scale).toLong()
        val ip = (r / scale).toString().padStart(intDigits, '0')
        val fp = (r % scale).toString().padStart(dec, '0')
        return (if (neg) "-" else "") + ip + (if (dec > 0) ".$fp" else "")
    }

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun base64(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val n = (b[i].toInt() and 0xFF shl 16) or
                ((if (i + 1 < b.size) b[i + 1].toInt() and 0xFF else 0) shl 8) or
                (if (i + 2 < b.size) b[i + 2].toInt() and 0xFF else 0)
            sb.append(B64[n shr 18 and 63]).append(B64[n shr 12 and 63])
            sb.append(if (i + 1 < b.size) B64[n shr 6 and 63] else '=')
            sb.append(if (i + 2 < b.size) B64[n and 63] else '=')
            i += 3
        }
        return sb.toString()
    }
}
