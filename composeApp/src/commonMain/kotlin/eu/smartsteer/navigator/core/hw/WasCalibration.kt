package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kalibracja czujnika kąta kół z jazdy po okręgu: ciągnik jedzie w kółko ze stałym skrętem, z pozycji GPS
 * liczymy promień okręgu, z niego prawdziwy kąt kół (atan(rozstaw osi / promień)) i porównujemy z odczytem
 * czujnika → nowa liczba impulsów na stopień. Pomysł jak „Smart WAS” w AgOpenGPS, rachunek własny
 * (okrąg dopasowany metodą najmniejszych kwadratów).
 */
class WasCalibration {
    private val pts = ArrayList<Vec2>()
    private val angles = ArrayList<Double>()
    private var lastHeading = Double.NaN
    /** Ile stopni kursu już przejechano. */
    var turnedDeg = 0.0; private set
    var running = false; private set

    data class Result(val radiusM: Double, val trueDeg: Double, val reportedDeg: Double, val newCountsPerDeg: Int, val fitErrorM: Double)

    fun start() { pts.clear(); angles.clear(); lastHeading = Double.NaN; turnedDeg = 0.0; running = true }
    fun stop() { running = false }

    /** Próbka co krok prowadzenia (pozycja tylnej osi, kurs, kąt z czujnika). */
    fun sample(pivot: Vec2, headingRad: Double, reportedDeg: Double, speedKmh: Double) {
        if (!running || speedKmh < 1.0) return
        if (pts.isEmpty() || pts.last().distanceTo(pivot) >= 0.3) {
            pts.add(pivot)
            angles.add(reportedDeg)
        }
        if (!lastHeading.isNaN()) {
            var d = headingRad - lastHeading
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            turnedDeg += abs(d) * 180 / PI
        }
        lastHeading = headingRad
    }

    /** Wynik po co najmniej 3/4 okręgu; null, gdy za mało danych albo jazda nie była po okręgu. */
    fun result(wheelbase: Double, countsPerDeg: Int): Result? {
        if (turnedDeg < 270 || pts.size < 20) return null
        val (c, r) = fitCircle(pts) ?: return null
        if (r < 2.0 || r > 200.0) return null
        val err = sqrt(pts.sumOf { val d = it.distanceTo(c) - r; d * d } / pts.size)
        val trueDeg = atan(wheelbase / r) * 180 / PI
        val rep = abs(angles.average())
        if (rep < 2.0) return null
        val cpd = (countsPerDeg * rep / trueDeg).roundToInt().coerceIn(1, 255)
        return Result(r, trueDeg, rep, cpd, err)
    }

    companion object {
        /** Okrąg najmniejszych kwadratów (metoda Kåsy): środek i promień. */
        fun fitCircle(p: List<Vec2>): Pair<Vec2, Double>? {
            if (p.size < 3) return null
            val mx = p.sumOf { it.x } / p.size
            val my = p.sumOf { it.y } / p.size
            var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
            for (q in p) {
                val u = q.x - mx; val v = q.y - my
                suu += u * u; svv += v * v; suv += u * v
                suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
            }
            val a = (suuu + suvv) / 2; val b = (svvv + svuu) / 2
            val det = suu * svv - suv * suv
            if (abs(det) < 1e-9) return null
            val uc = (a * svv - b * suv) / det
            val vc = (b * suu - a * suv) / det
            val r = sqrt(uc * uc + vc * vc + (suu + svv) / p.size)
            return Vec2(mx + uc, my + vc) to r
        }
    }
}
