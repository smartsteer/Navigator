package eu.smartsteer.navigator.core.weather

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Położenie słońca z daty, godziny i pozycji — uproszczone wzory NOAA (dokładność ok. 1°, wystarczy do koloru nieba).
 * Bez internetu.
 */
object Sun {
    /** Wysokość nad horyzontem i azymut (od północy, zgodnie z zegarem), w stopniach. */
    fun position(utcMillis: Long, latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val days = utcMillis / 86_400_000.0 + 2440587.5 - 2451545.0       // dni od J2000
        val g = rad(357.529 + 0.98560028 * days)                              // anomalia średnia
        val q = 280.459 + 0.98564736 * days                                   // długość średnia
        val l = rad(q + 1.915 * sin(g) + 0.020 * sin(2 * g))                  // długość ekliptyczna
        val e = rad(23.439 - 0.00000036 * days)
        val decl = asin(sin(e) * sin(l))
        val ra = atan2(cos(e) * sin(l), cos(l))
        val gmst = (18.697374558 + 24.06570982441908 * days) % 24.0           // czas gwiazdowy, h
        val ha = rad(((gmst * 15 + lonDeg) - deg(ra)))                        // kąt godzinny
        val lat = rad(latDeg)
        val sinAlt = sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(ha)
        val alt = asin(sinAlt.coerceIn(-1.0, 1.0))
        val cosAz = ((sin(decl) - sin(alt) * sin(lat)) / (cos(alt) * cos(lat))).coerceIn(-1.0, 1.0)
        var az = deg(acos(cosAz))
        if (sin(ha) > 0) az = 360 - az
        return deg(alt) to az
    }

    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
}
