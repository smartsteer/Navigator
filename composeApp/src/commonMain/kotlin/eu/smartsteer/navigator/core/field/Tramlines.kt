package eu.smartsteer.navigator.core.field

import kotlin.math.floor

/**
 * Ścieżki technologiczne: przy siewie co [every] przejazdów siewnika (szerokość opryskiwacza) zostawiamy
 * niezasiane ślady kół opryskiwacza. Moduł maszyny dostaje bity: 1 = ślad po prawej stronie siewnika,
 * 2 = po lewej (jak w AgOpenGPS, PGN 239 bajt „tram”).
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki SourceCode/GPS/Classes/CTram.cs
 * i SourceCode/GPS/Forms/Position.designer.cs (bity tram w PGN 239) — geometria liczona tu po numerach przejazdów.
 */
object Tramlines {
    /**
     * Środki śladów kół (odległość w prawo od linii AB, m) w okolicy przejazdu [pass].
     * Przejazdy siewnika: środek k-tego = k·w. Przejazd opryskiwacza obejmuje [every] przejazdów siewnika.
     */
    fun tracksNear(pass: Int, w: Double, every: Int, track: Double, shift: Int): List<Double> {
        if (every <= 0) return emptyList()
        val out = ArrayList<Double>()
        val j0 = floor((pass - shift).toDouble() / every).toInt()
        for (j in j0 - 1..j0 + 1) {
            val center = ((j * every + shift) + (every - 1) / 2.0) * w
            out.add(center - track / 2)
            out.add(center + track / 2)
        }
        return out
    }

    /** Bity dla przejazdu [pass] (jazda zgodnie z AB, albo odwrotnie gdy [reversed]). */
    fun bits(pass: Int, w: Double, every: Int, track: Double, wheel: Double, shift: Int, reversed: Boolean): Int {
        if (every <= 0) return 0
        val c = pass * w
        var b = 0
        for (t in tracksNear(pass, w, every, track, shift)) {
            // ślad koła zachodzi na ten przejazd siewnika?
            if (t + wheel / 2 <= c - w / 2 || t - wheel / 2 >= c + w / 2) continue
            val rightOfCenter = t > c
            val right = if (reversed) !rightOfCenter else rightOfCenter
            b = b or (if (right) 1 else 2)
        }
        return b
    }
}
