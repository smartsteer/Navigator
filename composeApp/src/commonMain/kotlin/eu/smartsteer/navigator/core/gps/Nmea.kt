package eu.smartsteer.navigator.core.gps

/** Jakość pozycji z GGA (pole 6). */
enum class FixQuality(val label: String) {
    NONE("Brak"), GPS("GPS"), DGPS("DGPS"), RTK_FIX("RTK FIX"), RTK_FLOAT("RTK FLOAT"), OTHER("Inny");

    companion object {
        fun of(code: Int) = when (code) {
            0 -> NONE; 1 -> GPS; 2 -> DGPS; 4 -> RTK_FIX; 5 -> RTK_FLOAT; else -> OTHER
        }
    }
}

data class Gga(
    val latitude: Double,
    val longitude: Double,
    val fix: FixQuality,
    val satellites: Int,
    val hdop: Double,
    val altitude: Double,
    /** Wiek poprawek RTK w sekundach (null = brak). */
    val correctionAge: Double?,
)

data class Vtg(val courseDeg: Double?, val speedKmh: Double)

/**
 * Parser zdań NMEA 0183 (GGA i VTG od dowolnego talkera: GP, GN, GL…).
 * Zdania z błędną sumą kontrolną są odrzucane.
 */
object Nmea {

    fun parseGga(sentence: String): Gga? {
        val f = fields(sentence, "GGA") ?: return null
        if (f.size < 14) return null
        val lat = coord(f[2], f[3], 2) ?: return null
        val lon = coord(f[4], f[5], 3) ?: return null
        return Gga(
            latitude = lat,
            longitude = lon,
            fix = FixQuality.of(f[6].toIntOrNull() ?: 0),
            satellites = f[7].toIntOrNull() ?: 0,
            hdop = f[8].toDoubleOrNull() ?: 99.0,
            altitude = f[9].toDoubleOrNull() ?: 0.0,
            correctionAge = f[13].toDoubleOrNull(),
        )
    }

    fun parseVtg(sentence: String): Vtg? {
        val f = fields(sentence, "VTG") ?: return null
        if (f.size < 8) return null
        val speed = f[7].toDoubleOrNull() ?: return null
        return Vtg(courseDeg = f[1].toDoubleOrNull(), speedKmh = speed)
    }

    /** Sprawdza sumę kontrolną i typ zdania; zwraca pola (pole 0 = adres, np. "GNGGA"). */
    internal fun fields(sentence: String, type: String): List<String>? {
        val s = sentence.trim()
        if (!s.startsWith("$")) return null
        val star = s.lastIndexOf('*')
        val body = if (star > 0) s.substring(1, star) else s.substring(1)
        if (star > 0) {
            val expected = s.substring(star + 1).take(2).toIntOrNull(16) ?: return null
            var sum = 0
            for (c in body) sum = sum xor c.code
            if (sum != expected) return null
        }
        val f = body.split(',')
        if (f[0].length < 5 || !f[0].endsWith(type)) return null
        return f
    }

    /** "5150.1234","N" -> 51.835390 (ddmm.mmmm / dddmm.mmmm). */
    internal fun coord(value: String, hemisphere: String, degDigits: Int): Double? {
        if (value.length <= degDigits) return null
        val deg = value.substring(0, degDigits).toIntOrNull() ?: return null
        val min = value.substring(degDigits).toDoubleOrNull() ?: return null
        val d = deg + min / 60.0
        return when (hemisphere) { "S", "W" -> -d; "N", "E" -> d; else -> null }
    }
}
