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
    /** Czas pozycji: sekundy od północy UTC (null = brak). */
    val utcSeconds: Double? = null,
)

data class Vtg(val courseDeg: Double?, val speedKmh: Double)

/** HDT — kurs rzeczywisty (np. z dwóch anten). */
data class Hdt(val headingDeg: Double)

/**
 * PANDA / PAOGI — zdania płytek AgOpenGPS: pozycja + prędkość + kurs + przechył w jednym.
 * PAOGI: kurs i przechył z dwóch anten; PANDA: kurs i przechył z IMU na płytce.
 */
data class Panda(
    val gga: Gga,
    val speedKmh: Double,
    val headingDeg: Double?,
    val rollDeg: Double?,
    val dualAntenna: Boolean,
)

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
            utcSeconds = utc(f[1]),
        )
    }

    fun parseVtg(sentence: String): Vtg? {
        val f = fields(sentence, "VTG") ?: return null
        if (f.size < 8) return null
        val speed = f[7].toDoubleOrNull() ?: return null
        return Vtg(courseDeg = f[1].toDoubleOrNull(), speedKmh = speed)
    }

    /**
     * GST — błąd pozycji (1σ): zwraca błąd poziomy w metrach, √(błąd szer.² + błąd dł.²).
     * Podają go odbiorniki u-blox/Unicore (trzeba włączyć zdanie GST) i GPS telefonu w Navigatorze.
     */
    fun parseGstHorizontal(sentence: String): Double? {
        val f = fields(sentence, "GST") ?: return null
        val la = f.getOrNull(6)?.toDoubleOrNull() ?: return null
        val lo = f.getOrNull(7)?.toDoubleOrNull() ?: return null
        val h = kotlin.math.sqrt(la * la + lo * lo)
        return if (h.isFinite() && h > 0 && h < 1000) h else null
    }

    fun parseHdt(sentence: String): Hdt? {
        val f = fields(sentence, "HDT") ?: return null
        if (f.size < 2) return null
        return Hdt(f[1].toDoubleOrNull() ?: return null)
    }

    /** Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/AgIO/Source/Forms/NMEA.Designer.cs (układ i skala pól PANDA/PAOGI). */
    fun parsePanda(sentence: String): Panda? {
        val dual = sentence.trim().startsWith("\$PAOGI")
        val f = fields(sentence, if (dual) "PAOGI" else "PANDA") ?: return null
        if (f.size < 14) return null
        val lat = coord(f[2], f[3], 2) ?: return null
        val lon = coord(f[4], f[5], 3) ?: return null
        val gga = Gga(
            latitude = lat,
            longitude = lon,
            fix = FixQuality.of(f[6].toIntOrNull() ?: 0),
            satellites = f[7].toIntOrNull() ?: 0,
            hdop = f[8].toDoubleOrNull() ?: 99.0,
            altitude = f[9].toDoubleOrNull() ?: 0.0,
            correctionAge = f[10].toDoubleOrNull(),
            utcSeconds = utc(f[1]),
        )
        // PAOGI: kurs i przechył w stopniach (liczby z kropką). PANDA: z IMU, liczby całkowite ×10
        // (65535 / 32767 = brak IMU) — tak czyta je AgIO (NMEA.Designer.cs, ParsePANDA/ParsePAOGI).
        val h = f[12].toDoubleOrNull()
        val r = f[13].toDoubleOrNull()
        val heading = if (dual) h else h?.takeIf { it < 65535.0 }?.div(10.0)
        val roll = if (dual) r else r?.takeIf { it < 32767.0 }?.div(10.0)
        return Panda(
            gga = gga,
            speedKmh = (f[11].toDoubleOrNull() ?: 0.0) * 1.852,
            headingDeg = heading?.takeIf { it in 0.0..360.0 },
            rollDeg = roll?.takeIf { kotlin.math.abs(it) <= 90.0 },
            dualAntenna = dual,
        )
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

    /** "123519.50" -> 45319,5 s od północy. */
    internal fun utc(v: String): Double? {
        if (v.length < 6) return null
        val h = v.substring(0, 2).toIntOrNull() ?: return null
        val m = v.substring(2, 4).toIntOrNull() ?: return null
        val sec = v.substring(4).toDoubleOrNull() ?: return null
        return h * 3600 + m * 60 + sec
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
