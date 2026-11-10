package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Auto-zawrót na uwrociu.
 *
 * Wzorowane na AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CYouTurn.cs
 * (CreateABOmegaTurn / CreateABWideTurn, MoveTurnInsideTurnLine): zawrót zaczyna się w miejscu, gdzie linia
 * prowadzenia dochodzi do „linii zawrotu” (granica odsunięta do środka), kończy się na przejeździe oddalonym
 * o [skip] szerokości, a cały kształt cofamy, aż zmieści się w granicy. Kształt: „U” (szeroki zawrót),
 * gdy odstęp ≥ 2R, albo „omega” (z wyjściem w przeciwną stronę), gdy odstęp < 2R — tak jak wybiera AgOpenGPS.
 * Geometria łuków liczona tu wprost (AgOpenGPS używa ścieżek Dubinsa, CDubins.cs), wynik jest ten sam: łuki o promieniu R.
 */
object YouTurn {

    /**
     * Kształt zawrotu w układzie lokalnym: start (0,0) z kursem +y, koniec (±w, 0) z kursem −y.
     * x > 0 = w prawo względem jazdy. Punkty co ok. [step] m.
     */
    fun shape(r: Double, w: Double, right: Boolean, step: Double = 0.5): List<Vec2> {
        val out = ArrayList<Vec2>()
        if (w >= 2 * r) {
            // szeroki zawrót: ćwierć łuku, prosta w bok, ćwierć łuku
            arc(out, Vec2(r, 0.0), r, PI, PI / 2, clockwise = true, step = step)
            line(out, Vec2(r, r), Vec2(w - r, r), step)
            arc(out, Vec2(w - r, 0.0), r, PI / 2, 0.0, clockwise = true, step = step)
        } else {
            // omega: łuk w przeciwną stronę, duży łuk przez górę, łuk z powrotem
            val c1 = Vec2(-r, 0.0)
            val c3 = Vec2(w + r, 0.0)
            val yc = sqrt((4 * r * r - (w / 2 + r) * (w / 2 + r)).coerceAtLeast(0.0))
            val c2 = Vec2(w / 2, yc)
            val t1 = (c1 + c2) * 0.5
            val t2 = (c2 + c3) * 0.5
            arc(out, c1, r, 0.0, ang(c1, t1), clockwise = false, step = step)
            arc(out, c2, r, ang(c2, t1), ang(c2, t2), clockwise = true, step = step)
            arc(out, c3, r, ang(c3, t2), PI, clockwise = false, step = step)
        }
        return if (right) out else out.map { Vec2(-it.x, it.y) }
    }

    /** Indeks pierwszego punktu linii (od [fromIdx]) poza linią zawrotu; −1 = brak. */
    fun exitIndex(guide: List<Pose>, fromIdx: Int, turnLine: List<Vec2>): Int {
        for (i in fromIdx.coerceAtLeast(0) until guide.size) if (!Polygon.contains(turnLine, guide[i].p)) return i
        return -1
    }

    /** Jak daleko do przodu sięga kształt (od punktu startu). */
    fun depth(pts: List<Vec2>) = pts.maxOf { it.y }

    /**
     * Ścieżka zawrotu po linii [guide] (w kierunku jazdy, punkty co ~1 m).
     * @param fromIdx indeks punktu przy ciągniku
     * @param turnLine granica, której zawrót nie może przekroczyć
     * @return punkty: dojazd po linii, zawrót, 20 m prosto po nowym przejeździe; null = nie da się
     */
    fun build(
        guide: List<Pose>, fromIdx: Int, turnLine: List<Vec2>,
        radius: Double, width: Double, right: Boolean,
    ): List<Pose>? {
        if (guide.size < 2 || turnLine.size < 3) return null
        // punkt, w którym linia wychodzi poza linię zawrotu
        var exit = -1
        for (i in fromIdx.coerceAtLeast(0) until guide.size) {
            if (!Polygon.contains(turnLine, guide[i].p)) { exit = i; break }
        }
        if (exit <= fromIdx) return null
        val e = guide[exit]
        val fwd = Vec2(sin(e.heading), cos(e.heading))
        val rgt = Vec2(cos(e.heading), -sin(e.heading))
        val local = shape(radius, width, right)
        val d = depth(local)
        // cofamy kształt, aż cały zmieści się w granicy (MoveTurnInsideTurnLine)
        var shift = d
        var placed: List<Vec2>? = null
        while (shift <= d + 40.0) {
            val o = e.p - fwd * shift
            val world = local.map { o + rgt * it.x + fwd * it.y }
            if (world.all { Polygon.contains(turnLine, it) }) { placed = world; break }
            shift += 0.5
        }
        val turn = placed ?: return null
        val origin = e.p - fwd * shift
        // dojazd: punkty linii przed początkiem zawrotu
        val path = ArrayList<Vec2>()
        for (i in fromIdx.coerceAtLeast(0)..exit) {
            if (((guide[i].p - origin) dot fwd) >= -0.3) break
            path.add(guide[i].p)
        }
        if (path.isEmpty()) return null // za późno — ciągnik już minął początek zawrotu
        path.addAll(turn)
        // 20 m po nowym przejeździe, w przeciwną stronę
        val end = turn.last()
        for (k in 1..20) path.add(end - fwd * k.toDouble())
        return Curves.headings(path)
    }

    private fun ang(c: Vec2, p: Vec2) = atan2(p.y - c.y, p.x - c.x)

    /** Łuk wokół [c] od kąta [a0] do [a1] (kąty matematyczne: 0 = +x, przeciwnie do zegara). */
    private fun arc(out: MutableList<Vec2>, c: Vec2, r: Double, a0: Double, a1: Double, clockwise: Boolean, step: Double) {
        var sweep = a1 - a0
        if (clockwise) { while (sweep > 0) sweep -= 2 * PI } else { while (sweep < 0) sweep += 2 * PI }
        val n = ((kotlin.math.abs(sweep) * r) / step).toInt().coerceAtLeast(2)
        for (k in 0..n) {
            val a = a0 + sweep * k / n
            val p = Vec2(c.x + r * cos(a), c.y + r * sin(a))
            if (out.isEmpty() || out.last().distanceTo(p) > 1e-6) out.add(p)
        }
    }

    private fun line(out: MutableList<Vec2>, a: Vec2, b: Vec2, step: Double) {
        val n = (a.distanceTo(b) / step).toInt()
        for (k in 1..n) out.add(a + (b - a) * (k.toDouble() / n))
    }
}
