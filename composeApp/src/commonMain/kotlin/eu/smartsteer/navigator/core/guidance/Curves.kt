package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Punkt ścieżki z kierunkiem (kurs w radianach, 0 = północ). */
data class Pose(val p: Vec2, val heading: Double)

/**
 * Narzędzia krzywych prowadzenia: przygotowanie nagranej krzywej i równoległe przejazdy.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki
 * SourceCode/GPS/Classes/CABCurve.cs (MakePointMinimumSpacing, CalculateHeadings, AddFirstLastPoints,
 * GetExtendedReferenceCurve, BuildNewOffsetList), SourceCode/GPS/Forms/Guidance/FormBuildTracks.cs (SmoothAB, btnBCurve_Click)
 * i SourceCode/GPS/Classes/CGLM.cs (Catmull).
 */
object Curves {

    /** Wstawia punkty pośrednie, aż żaden odstęp nie przekracza [maxDistance]. */
    fun makePointMinimumSpacing(pts: List<Vec2>, maxDistance: Double): List<Vec2> {
        if (pts.size <= 3) return pts
        val out = ArrayList<Vec2>(pts.size * 2)
        out.add(pts[0])
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val d = a.distanceTo(b)
            if (d > maxDistance) {
                // tyle samo co wielokrotne dzielenie na pół w AgOpenGPS: 2^k odcinków
                var parts = 1
                while (d / parts > maxDistance) parts *= 2
                for (k in 1 until parts) out.add(a + (b - a) * (k.toDouble() / parts))
            }
            out.add(b)
        }
        return out
    }

    /** Gęsta łamana: punkty co najwyżej [step] m (także dla 2 punktów, w przeciwieństwie do makePointMinimumSpacing). */
    fun densify(pts: List<Vec2>, step: Double): List<Vec2> {
        if (pts.size < 2) return pts
        val out = ArrayList<Vec2>()
        out.add(pts[0])
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val n = kotlin.math.ceil(a.distanceTo(b) / step).toInt().coerceAtLeast(1)
            for (k in 1..n) out.add(a + (b - a) * (k.toDouble() / n))
        }
        return out
    }

    /** Kursy punktów: środkowe z sąsiadów, skrajne z najbliższego odcinka. */
    fun headings(pts: List<Vec2>): List<Pose> {
        if (pts.size < 2) return pts.map { Pose(it, 0.0) }
        val n = pts.size
        return List(n) { i ->
            val a = pts[if (i == 0) 0 else i - 1]
            val b = pts[if (i == n - 1) n - 1 else i + 1]
            Pose(pts[i], norm(atan2(b.x - a.x, b.y - a.y)))
        }
    }

    /** Przedłużenie krzywej prosto na obu końcach (co 1 m), żeby prowadzenie działało też za końcem przejazdu. */
    fun extendEnds(pts: List<Pose>, meters: Int): List<Pose> {
        if (pts.size < 2) return pts
        val out = ArrayList<Pose>(pts.size + 2 * meters)
        val first = pts.first()
        for (i in meters - 1 downTo 1) out.add(Pose(first.p - fwd(first.heading) * i.toDouble(), first.heading))
        out.addAll(pts)
        val last = pts.last()
        for (i in 1 until meters) out.add(Pose(last.p + fwd(last.heading) * i.toDouble(), last.heading))
        return out
    }

    /** Wygładzenie średnią ruchomą z [smPts] punktów (końce bez zmian). */
    fun smooth(pts: List<Pose>, smPts: Int): List<Pose> {
        val cnt = pts.size
        val half = smPts / 2
        if (cnt <= smPts) return pts
        return List(cnt) { i ->
            if (i < half || i >= cnt - half) {
                pts[i]
            } else {
                var x = 0.0
                var y = 0.0
                for (j in -half until half) { x += pts[i + j].p.x; y += pts[i + j].p.y }
                Pose(Vec2(x / smPts, y / smPts), pts[i].heading)
            }
        }
    }

    /** Nagrana jazda → krzywa odniesienia (jak przycisk B przy krzywej w AgOpenGPS). */
    fun buildReference(recorded: List<Vec2>): List<Pose> {
        val spaced = makePointMinimumSpacing(recorded, 1.6)
        val withHeadings = headings(spaced)
        val extended = extendEnds(withHeadings, EXTEND_M)
        return headings(smooth(extended, 4).map { it.p })
    }

    /**
     * Równoległy przejazd: każdy punkt odsunięty o [distAway] w prawo (ujemne = w lewo).
     * Punkty, które po odsunięciu wypadają bliżej krzywej niż [distAway] (wnętrze łuku), są pomijane,
     * potem gęstnienie splajnem Catmull-Rom i nowe kursy — jak BuildNewOffsetList.
     */
    fun offset(ref: List<Pose>, distAway: Double, toolSpacing: Double): List<Pose> {
        if (ref.size < 2) return ref
        if (distAway == 0.0) return ref
        val step = (toolSpacing * 0.48).coerceIn(1.0, 4.0)
        val distSqAway = distAway * distAway - 0.01
        val list = ArrayList<Vec2>()
        // do sprawdzania odległości od krzywej wystarczą punkty co ~1 m; siatka przyspiesza szukanie
        val grid = Grid(ref.map { it.p }, kotlin.math.abs(distAway).coerceAtLeast(1.0))
        for (r in ref) {
            val point = r.p + Vec2(sin(PI / 2 + r.heading), cos(PI / 2 + r.heading)) * distAway
            if (grid.anyCloserThan(point, distSqAway)) continue
            if (list.isEmpty()) {
                list.add(point)
            } else {
                val l = list.last()
                val d2 = (point.x - l.x) * (point.x - l.x) + (point.y - l.y) * (point.y - l.y)
                if (d2 > step) list.add(point)
            }
        }
        if (list.size <= 6) return headings(list)
        // zagęszczenie Catmull-Rom tam, gdzie punkty są rzadsze niż krok
        val dense = ArrayList<Vec2>(list.size * 2)
        dense.add(list[0])
        for (i in 0 until list.size - 3) {
            dense.add(list[i + 1])
            val d = list[i + 1].distanceTo(list[i + 2])
            if (d > step) {
                val loops = (d / step + 1).toInt()
                for (j in 1 until loops) dense.add(catmull(j / loops.toDouble(), list[i], list[i + 1], list[i + 2], list[i + 3]))
            }
        }
        dense.add(list[list.size - 2])
        dense.add(list[list.size - 1])
        return headings(dense)
    }

    fun catmull(t: Double, p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2): Vec2 {
        val tt = t * t
        val ttt = tt * t
        val q1 = -ttt + 2.0 * tt - t
        val q2 = 3.0 * ttt - 5.0 * tt + 2.0
        val q3 = -3.0 * ttt + 4.0 * tt + t
        val q4 = ttt - tt
        return Vec2(
            0.5 * (p0.x * q1 + p1.x * q2 + p2.x * q3 + p3.x * q4),
            0.5 * (p0.y * q1 + p1.y * q2 + p2.y * q3 + p3.y * q4),
        )
    }

    /** Odwrócenie kierunku ścieżki (jazda „pod prąd” krzywej). */
    fun reversed(pts: List<Pose>): List<Pose> = pts.asReversed().map { Pose(it.p, norm(it.heading + PI)) }

    fun length(pts: List<Vec2>): Double {
        var s = 0.0
        for (i in 1 until pts.size) s += pts[i].distanceTo(pts[i - 1])
        return s
    }

    /** Indeks najbliższego punktu: najpierw co 10, potem dokładnie w okolicy (jak w AgOpenGPS). */
    fun nearestIndex(pts: List<Pose>, p: Vec2): Int {
        if (pts.isEmpty()) return -1
        var best = 0
        var bestD = Double.MAX_VALUE
        var j = 0
        while (j < pts.size) {
            val d = d2(pts[j].p, p)
            if (d < bestD) { bestD = d; best = j }
            j += 10
        }
        val from = (best - 10).coerceAtLeast(0)
        val to = (best + 10).coerceAtMost(pts.size - 1)
        for (k in from..to) {
            val d = d2(pts[k].p, p)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    const val EXTEND_M = 200

    internal fun d2(a: Vec2, b: Vec2) = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
    private fun fwd(h: Double) = Vec2(sin(h), cos(h))
    internal fun norm(h: Double): Double {
        var r = h % (2 * PI)
        if (r < 0) r += 2 * PI
        return r
    }

    /** Prosta siatka kubełkowa do pytania „czy jakiś punkt krzywej jest bliżej niż …”. */
    private class Grid(pts: List<Vec2>, private val cell: Double) {
        private val map = HashMap<Long, MutableList<Vec2>>()
        init { for (p in pts) map.getOrPut(key(cx(p.x), cx(p.y))) { ArrayList() }.add(p) }
        private fun cx(v: Double) = kotlin.math.floor(v / cell).toLong()
        private fun key(i: Long, j: Long) = i * 1_000_003L + j
        fun anyCloserThan(p: Vec2, dist2: Double): Boolean {
            val i0 = cx(p.x)
            val j0 = cx(p.y)
            for (i in i0 - 1..i0 + 1) for (j in j0 - 1..j0 + 1) {
                val l = map[key(i, j)] ?: continue
                for (q in l) if (d2(p, q) < dist2) return true
            }
            return false
        }
    }
}
