package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Plan przejazdów: kierunek linii AB, przy którym pole (wnętrze bez uwrocia) przejedzie się
 * najmniejszą liczbą przejazdów danej szerokości. Sprawdza kierunki wzdłuż każdego boku pola i co 1°;
 * przy równej liczbie przejazdów wybiera dłuższe przejazdy (mniej zawrotów na hektar) i kierunek wzdłuż boku.
 * Pierwszy przejazd leży pół szerokości od skraju, kolejne na prawo od linii AB.
 */
object FieldPlanner {
    data class Plan(
        /** Kurs przejazdów (0 = północ), radiany. */
        val heading: Double,
        val passes: Int,
        /** Ostatni przejazd zachodzi na granicę o tyle metrów (niewykorzystana szerokość). */
        val wasteM: Double,
        /** Łączna długość przejazdów w polu, m. */
        val lengthM: Double,
        val line: AbLine,
        /** Kierunek pokrywa się z bokiem pola. */
        val alongEdge: Boolean,
    ) {
        val headingDeg: Int get() = (((heading * 180 / PI) % 180 + 180) % 180).roundToInt() % 180
        val turns: Int get() = (passes - 1).coerceAtLeast(0)
    }

    /** Najlepszy plan i kilka innych kierunków do porównania (posortowane od najlepszego). */
    fun plan(area: List<Vec2>, spacing: Double, maxResults: Int = 4): List<Plan> {
        if (area.size < 3 || spacing <= 0.05) return emptyList()
        val candidates = ArrayList<Pair<Double, Boolean>>()
        for (i in area.indices) {
            val a = area[i]
            val b = area[(i + 1) % area.size]
            if (a.distanceTo(b) < 5.0) continue
            candidates.add(norm(atan2(b.x - a.x, b.y - a.y)) to true)
        }
        for (d in 0 until 180) candidates.add(d * PI / 180 to false)
        val plans = candidates.map { (h, edge) -> evaluate(area, spacing, h, edge) }
        val sorted = plans.sortedWith(
            compareBy<Plan> { it.passes }
                .thenByDescending { it.lengthM / it.passes.coerceAtLeast(1) }
                .thenBy { if (it.alongEdge) 0 else 1 },
        )
        // różne kierunki (co najmniej 5° od siebie)
        val out = ArrayList<Plan>()
        for (p in sorted) {
            if (out.none { angDiff(it.heading, p.heading) < 5 * PI / 180 }) out.add(p)
            if (out.size >= maxResults) break
        }
        return out
    }

    fun evaluate(area: List<Vec2>, spacing: Double, heading: Double, alongEdge: Boolean = false): Plan {
        val dir = Vec2.forward(heading)
        val right = Vec2.right(heading)
        var minN = Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        var minD = Double.MAX_VALUE; var maxD = -Double.MAX_VALUE
        for (p in area) {
            val n = p dot right
            val d = p dot dir
            if (n < minN) minN = n
            if (n > maxN) maxN = n
            if (d < minD) minD = d
            if (d > maxD) maxD = d
        }
        val width = maxN - minN
        val passes = ceil(width / spacing - 1e-6).toInt().coerceAtLeast(1)
        // długość przejazdów: przybliżenie z pola powierzchni (powierzchnia / szerokość przejazdu)
        val length = Polygon.area(area) / spacing
        val start = right * (minN + spacing / 2) + dir * minD
        return Plan(
            heading = heading, passes = passes, wasteM = passes * spacing - width, lengthM = length,
            line = AbLine(start, start + dir * (maxD - minD).coerceAtLeast(20.0)), alongEdge = alongEdge,
        )
    }

    private fun norm(h: Double): Double {
        var r = h % PI
        if (r < 0) r += PI
        return r
    }

    private fun angDiff(a: Double, b: Double): Double {
        val d = abs(norm(a) - norm(b))
        return minOf(d, PI - d)
    }
}
