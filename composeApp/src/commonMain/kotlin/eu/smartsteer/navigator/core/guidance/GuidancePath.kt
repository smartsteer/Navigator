package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.atan2

/**
 * Ścieżka prowadzenia jako łamana (linia AB, krzywa, zawrót — wszystko sprowadza się do punktów).
 * "Stacja" (s) to odległość w metrach wzdłuż ścieżki od jej początku.
 */
class GuidancePath(val points: List<Vec2>) {
    init {
        require(points.size >= 2) { "Ścieżka potrzebuje co najmniej 2 punktów" }
    }

    private val stations: DoubleArray = DoubleArray(points.size).also { st ->
        for (i in 1 until points.size) st[i] = st[i - 1] + points[i].distanceTo(points[i - 1])
    }

    val length: Double get() = stations.last()

    data class Projection(
        val station: Double,
        val point: Vec2,
        /** Odchyłka boczna w metrach: + = punkt jest na prawo od ścieżki (patrząc w kierunku jazdy). */
        val lateral: Double,
        /** Kierunek ścieżki w tym miejscu (kurs, radiany). */
        val heading: Double,
    )

    /**
     * Rzut punktu na ścieżkę. [hint] to ostatnio znana stacja — szukamy w jej okolicy,
     * żeby na ciasnych zawrotach nie "przeskoczyć" na sąsiedni odcinek.
     */
    fun project(p: Vec2, hint: Double? = null, window: Double = 30.0): Projection {
        var from = 0
        var to = points.size - 2
        if (hint != null) {
            from = segmentAt(hint - window)
            to = segmentAt(hint + window)
        }
        var best: Projection? = null
        var bestDist = Double.MAX_VALUE
        for (i in from..to) {
            val a = points[i]
            val b = points[i + 1]
            val ab = b - a
            val len2 = ab dot ab
            if (len2 < 1e-12) continue
            val t = (((p - a) dot ab) / len2).coerceIn(0.0, 1.0)
            val q = a + ab * t
            val d = p.distanceTo(q)
            if (d < bestDist) {
                bestDist = d
                val heading = atan2(ab.x, ab.y)
                val right = Vec2.right(heading)
                best = Projection(
                    station = stations[i] + (stations[i + 1] - stations[i]) * t,
                    point = q,
                    lateral = (p - q) dot right,
                    heading = heading,
                )
            }
        }
        return best!!
    }

    fun pointAt(s: Double): Vec2 {
        val i = segmentAt(s)
        val segLen = stations[i + 1] - stations[i]
        val t = if (segLen < 1e-12) 0.0 else ((s - stations[i]) / segLen).coerceIn(0.0, 1.0)
        return points[i] + (points[i + 1] - points[i]) * t
    }

    fun headingAt(s: Double): Double {
        val i = segmentAt(s)
        val ab = points[i + 1] - points[i]
        return atan2(ab.x, ab.y)
    }

    /** Indeks odcinka zawierającego stację s (przycięty do zakresu). */
    fun segmentAt(s: Double): Int {
        if (s <= 0.0) return 0
        if (s >= length) return points.size - 2
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (stations[mid] <= s) lo = mid else hi = mid
        }
        return lo
    }
}
