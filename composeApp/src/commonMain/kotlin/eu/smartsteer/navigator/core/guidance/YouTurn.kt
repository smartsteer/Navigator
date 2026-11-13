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
 * Zawrót „U” budowany jak w AgOpenGPS: omega ze ścieżki Dubinsa (CDubins.cs), szeroki z ćwiartek łuku i odcinka linii zawrotu.
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

    // ================= zawrót jak w AgOpenGPS (CYouTurn.cs + CDubins.cs) =================

    private class Cross(val pt: Vec2, val seg: Int)

    /** GetLineIntersection (CYouTurn.cs): przecięcie odcinków p0–p1 i p2–p3; null = brak. */
    fun segIntersect(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2): Vec2? {
        val s1x = p1.x - p0.x; val s1y = p1.y - p0.y
        val s2x = p3.x - p2.x; val s2y = p3.y - p2.y
        val den = -s2x * s1y + s1x * s2y
        if (kotlin.math.abs(den) < 1e-12) return null
        val sp = (-s1y * (p0.x - p2.x) + s1x * (p0.y - p2.y)) / den
        val tp = (s2x * (p0.y - p2.y) - s2y * (p0.x - p2.x)) / den
        if (sp in 0.0..1.0 && tp in 0.0..1.0) return Vec2(p0.x + tp * s1x, p0.y + tp * s1y)
        return null
    }

    /** Wszystkie przecięcia odcinka a–b z zamkniętą linią zawrotu. */
    private fun crossings(a: Vec2, b: Vec2, tl: List<Vec2>): List<Cross> {
        val out = ArrayList<Cross>()
        for (i in tl.indices) {
            val c = segIntersect(tl[i], tl[(i + 1) % tl.size], a, b) ?: continue
            out.add(Cross(c, i))
        }
        return out
    }

    private fun segHeading(tl: List<Vec2>, i: Int): Double {
        val a = tl[i]; val b = tl[(i + 1) % tl.size]
        return Curves.norm(atan2(b.x - a.x, b.y - a.y))
    }

    /**
     * MoveTurnInsideTurnLine: cofa kształt co 1 m, aż cały jest w linii zawrotu, potem przesuwa naprzód co 0,1 m,
     * aż dotknie linii; [deleteSecondHalf] — zostają punkty przed pierwszym, który wyszedł (ćwiartka łuku).
     */
    private fun moveInside(list: List<Pose>, head: Double, deleteSecondHalf: Boolean, invertHeading: Boolean, tl: List<Vec2>, pivot: Vec2): List<Pose> {
        val sinH = sin(head); val cosH = cos(head)
        val arr = list.map { it.p }.toMutableList()
        var out = true
        var j = 0
        var guard = 0
        while (out) {
            guard++
            out = false
            val dist = arr[0].distanceTo(pivot)
            for (i in arr.indices) arr[i] = Vec2(arr[i].x - sinH, arr[i].y - cosH)
            while (j < arr.size) {
                if (!Polygon.contains(tl, arr[j])) { out = true; if (j > 0) j--; break }
                j++
            }
            if (guard == 1000 || dist < 3) return emptyList()
        }
        var semi = -1
        while (!out) {
            for (i in arr.indices) arr[i] = Vec2(arr[i].x + sinH * 0.1, arr[i].y + cosH * 0.1)
            for (a in arr.indices) if (!Polygon.contains(tl, arr[a])) { semi = a; out = true; break }
        }
        val res = ArrayList<Pose>()
        for (i in arr.indices) {
            if (i == semi && deleteSecondHalf) break
            res.add(Pose(arr[i], Curves.norm(list[i].heading + if (invertHeading) PI else 0.0)))
        }
        return res
    }

    /** Półokrąg „z Dubinsa” (CreateABWideTurn): od [start] z kursem [head], skręt w [right], krok [step]. */
    private fun halfCircle(start: Vec2, head: Double, right: Boolean, radius: Double, step: Double): List<Pose> {
        val out = ArrayList<Pose>()
        var pos = start
        var h = head
        out.add(Pose(pos, h))
        val turn = if (right) 1.0 else -1.0
        while (kotlin.math.abs(head - h) < PI) {
            pos = Vec2(pos.x + step * sin(h), pos.y + step * cos(h))
            h += (step / radius) * turn
            out.add(Pose(pos, h))
        }
        return out
    }

    /** FindInnerTurnPoints: przecięcie odcinka do 5 m od [from] w kierunku [dir] z linią zawrotu (najbliższe). */
    private fun innerPoint(from: Vec2, dir: Double, tl: List<Vec2>): Cross? =
        crossings(from - Vec2(sin(dir), cos(dir)) * 0.5, from + Vec2(sin(dir), cos(dir)) * 5.0, tl).minByOrNull { it.pt.distanceTo(from) }

    /**
     * Zawrót jak w AgOpenGPS: omega (odstęp ≤ 2R) — ścieżka Dubinsa od punktu wejścia na linię zawrotu do punktu
     * na następnym przejeździe, cofnięta w granicę; szeroki — ćwierć łuku, odcinek wzdłuż linii zawrotu, ćwierć łuku
     * (CreateABWideTurn / CreateCurveWideTurn, MoveTurnInsideTurnLine, FindInnerTurnPoints).
     * @param guide przejazd w kierunku jazdy, @param fromIdx punkt przy ciągniku, @param width odstęp do następnego przejazdu
     * @return dojazd, zawrót i 20 m prosto po nowym przejeździe; null = nie da się (FailCreate)
     */
    fun build(
        guide: List<Pose>, fromIdx: Int, turnLine: List<Vec2>,
        radius: Double, width: Double, right: Boolean,
    ): List<Pose>? {
        if (guide.size < 2 || turnLine.size < 3) return null
        val from = fromIdx.coerceIn(0, guide.size - 1)
        val pivot = guide[from].p
        // punkt wejścia: pierwsze przecięcie przejazdu z linią zawrotu przed ciągnikiem (FindABTurnPoint)
        var inC: Cross? = null
        var head = 0.0
        for (i in from until guide.size - 1) {
            val c = crossings(guide[i].p, guide[i + 1].p, turnLine).minByOrNull { it.pt.distanceTo(guide[i].p) }
            if (c != null) { inC = c; head = Curves.norm(atan2(guide[i + 1].p.x - guide[i].p.x, guide[i + 1].p.y - guide[i].p.y)); break }
        }
        val inPt = inC ?: return null
        val step = radius * 0.1
        val turn: List<Pose>
        if (width <= radius * 2.0) {
            // omega: Dubins od wejścia do punktu na następnym przejeździe, z kursem odwrotnym
            var inv = head - PI
            if (inv < 0) inv += 2 * PI
            if (inv > 2 * PI) inv -= 2 * PI
            val side = Vec2(cos(-inv), sin(-inv)) * width   // w lewo od kursu jazdy
            val goal = if (!right) inPt.pt + side else inPt.pt - side
            var yt = Dubins(radius).generate(Pose(inPt.pt, head), Pose(goal, inv)).toMutableList()
            if (yt.isEmpty()) return null
            var i = 1
            while (i < yt.size - 2) {
                if (Curves.d2(yt[i].p, yt[i + 1].p) < step) { yt.removeAt(i + 1); i-- }
                i++
            }
            turn = moveInside(yt, head, false, false, turnLine, pivot)
            if (turn.isEmpty()) return null
        } else {
            // szeroki: ćwierć łuku przy wejściu
            val yt1 = moveInside(halfCircle(inPt.pt, head, right, radius, step), head, true, false, turnLine, pivot)
            if (yt1.isEmpty()) return null
            // następny przejazd i jego przecięcie z linią zawrotu
            val sideSign = if (right) 1.0 else -1.0
            val next = guide.map { g -> Pose(g.p + Vec2(cos(g.heading), -sin(g.heading)) * (width * sideSign), g.heading) }
            var outC: Cross? = null
            for (k in 0 until next.size - 1) {
                val c = crossings(next[k].p, next[k + 1].p, turnLine).minByOrNull { it.pt.distanceTo(inPt.pt) } ?: continue
                if (outC == null || c.pt.distanceTo(inPt.pt) < outC.pt.distanceTo(inPt.pt)) outC = c
            }
            val outPt = outC ?: return null
            val yt2 = moveInside(halfCircle(outPt.pt, head, !right, radius, step), head, true, true, turnLine, pivot)
            if (yt2.isEmpty()) return null
            // łączymy po linii zawrotu
            val hd = kotlin.math.abs(segHeading(turnLine, inPt.seg) - yt1.last().heading)
            val firstSameWay = !(hd > PI / 2 && hd < 3 * PI / 2)
            val sC = innerPoint(yt1.last().p, yt1[0].heading, turnLine) ?: return null
            val gC = innerPoint(yt2.last().p, yt2[0].heading + PI, turnLine) ?: return null
            val pts = ArrayList<Vec2>(yt1.map { it.p })
            if (sC.seg != gC.seg) {
                val n = turnLine.size
                var loops = kotlin.math.abs(sC.seg - gC.seg)
                if (loops > n / 2) loops = if (sC.seg < gC.seg) (n - gC.seg) + sC.seg else (n - sC.seg) + gC.seg
                var idx = sC.seg
                if (firstSameWay) {
                    repeat(loops) { idx = (idx + 1) % n; pts.add(turnLine[idx]) }
                } else {
                    repeat(loops) { pts.add(turnLine[idx]); idx = (idx - 1 + n) % n }
                }
            }
            for (a in yt2.indices.reversed()) pts.add(yt2[a].p)
            // uzupełnienie przerw > 1 m
            var i = 1
            while (i < pts.size - 2) {
                val j = i + 1
                if (j != pts.size - 1 && Curves.d2(pts[i], pts[j]) > 1) { pts.add(j, (pts[i] + pts[j]) * 0.5); i-- }
                i++
            }
            turn = Curves.headings(pts)
        }
        if (turn.first().p.distanceTo(pivot) < 3) return null
        // dojazd po przejeździe do początku zawrotu (AddSequenceLines), potem 20 m po nowym przejeździe
        val fwd = Vec2(sin(head), cos(head))
        val path = ArrayList<Vec2>()
        for (i in from until guide.size) {
            if (((guide[i].p - turn.first().p) dot fwd) >= -0.3) break
            path.add(guide[i].p)
        }
        if (path.isEmpty()) return null
        path.addAll(turn.map { it.p })
        val end = turn.last()
        val back = Vec2(sin(end.heading), cos(end.heading))
        for (k in 1..20) path.add(end.p + back * k.toDouble())
        return Curves.headings(path)
    }

    /** Zawrót z cofaniem (K): dojazd + łuk naprzód, łuk wstecz, wyjazd naprzód na nowy przejazd. */
    data class KTurn(val forward: List<Pose>, val reverse: List<Pose>, val exit: List<Pose>)

    /**
     * Kształt zawrotu K w układzie przejazdu (y = naprzód, x = w prawo), dla [w] < 2·[r]:
     * łuk naprzód o kąt α (cos α = −w / 2r), łuk wstecz o π − α z przeciwnym skrętem, potem prosto na nowy przejazd.
     * Na podstawie pomysłu K-turn z AgOpenGPS (CYouTurn.cs), geometria liczona tu wprost z okręgów.
     */
    fun kShape(r: Double, w: Double, right: Boolean, step: Double = 0.5): Triple<List<Vec2>, List<Vec2>, List<Vec2>>? {
        if (w >= 2 * r || w <= 0) return null
        val alpha = kotlin.math.acos(-w / (2 * r))
        val s = if (right) 1.0 else -1.0
        val fwd = ArrayList<Vec2>()
        val nF = ((alpha * r) / step).toInt().coerceAtLeast(4)
        for (k in 0..nF) { val t = alpha * k / nF; fwd.add(Vec2(s * (r - r * cos(t)), r * sin(t))) }
        val p1 = fwd.last()
        val rev = ArrayList<Vec2>()
        val nR = (((PI - alpha) * r) / step).toInt().coerceAtLeast(4)
        for (k in 0..nR) {
            val t = alpha + (PI - alpha) * k / nR
            rev.add(Vec2(p1.x + s * r * (cos(t) - cos(alpha)), p1.y - r * (sin(t) - sin(alpha))))
        }
        val end = rev.last()
        val out = ArrayList<Vec2>()
        var y = end.y
        while (y > -20.0) { out.add(Vec2(end.x, y)); y -= step }
        return Triple(fwd, rev, out)
    }

    /** Zawrót K ułożony na końcu przejazdu, w granicy [turnLine]. Null = nie mieści się albo za późno. */
    fun buildK(guide: List<Pose>, fromIdx: Int, turnLine: List<Vec2>, radius: Double, width: Double, right: Boolean): KTurn? {
        if (guide.size < 2 || turnLine.size < 3) return null
        val (f, r, x) = kShape(radius, width, right) ?: return null
        var exit = -1
        for (i in fromIdx.coerceAtLeast(0) until guide.size) {
            if (!Polygon.contains(turnLine, guide[i].p)) { exit = i; break }
        }
        if (exit <= fromIdx) return null
        val e = guide[exit]
        val fwd = Vec2(sin(e.heading), cos(e.heading))
        val rgt = Vec2(cos(e.heading), -sin(e.heading))
        val d = (f + r).maxOf { it.y }
        var shift = d
        var origin: Vec2? = null
        while (shift <= d + 40.0) {
            val o = e.p - fwd * shift
            if ((f + r).all { Polygon.contains(turnLine, o + rgt * it.x + fwd * it.y) }) { origin = o; break }
            shift += 0.5
        }
        val o = origin ?: return null
        fun w(v: Vec2) = o + rgt * v.x + fwd * v.y
        val approach = ArrayList<Vec2>()
        for (i in fromIdx.coerceAtLeast(0)..exit) {
            if (((guide[i].p - o) dot fwd) >= -0.3) break
            approach.add(guide[i].p)
        }
        if (approach.isEmpty()) return null
        return KTurn(
            Curves.headings(approach + f.map { w(it) }),
            Curves.headings(r.map { w(it) }),
            Curves.headings(x.map { w(it) }),
        )
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
