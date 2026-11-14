package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.guidance.Pose
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ścieżki technologiczne 1:1 z AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3):
 *  - SourceCode/GPS/Classes/CTram.cs — ślady wokół granicy (CreateBoundaryTrack), IsTramOuterOrInner,
 *  - SourceCode/GPS/Forms/Guidance/FormTramLine.cs — ślady wzdłuż linii AB (BuildABTram) i krzywej
 *    (BuildCurveTram, BuildExtendedCurveRef, AddCurveTramPass),
 *  - SourceCode/GPS/Forms/OpenGL.Designer.cs — bajt sterujący (tram.controlByte) z odczytu śladów pod maszyną,
 *  - SourceCode/GPS/IO/TramFiles.cs — plik Tram.txt.
 * Bit 1 = ślad pod prawą stroną maszyny, bit 2 = pod lewą (PGN 239, bajt „tram”).
 */
object Tramlines {
    class Set(val outer: List<Vec2>, val inner: List<Vec2>, val lines: List<List<Vec2>>) {
        val isEmpty get() = outer.isEmpty() && inner.isEmpty() && lines.all { it.isEmpty() }
    }

    val EMPTY = Set(emptyList(), emptyList(), emptyList())

    /** Tryb wyświetlania/sterowania (tram.displayMode): 0 wył., 1 wszystko, 2 tylko linie, 3 tylko wokół granicy. */
    fun usesLines(mode: Int) = mode == 1 || mode == 2
    fun usesBoundary(mode: Int) = mode == 1 || mode == 3

    /** IsTramOuterOrInner: przy parzystej liczbie przejazdów maszyny na opryskiwacz ślad jest przy krawędzi maszyny. */
    fun isOuter(tramWidth: Double, toolWidth: Double, inverted: Boolean): Boolean {
        val o = ((tramWidth / toolWidth + 0.5).toInt()) % 2 == 0
        return if (inverted) !o else o
    }

    // ---------------- ślady wokół granicy (CTram.CreateBoundaryTrack) ----------------

    fun boundaryTracks(fence: List<Vec2>, tramWidth: Double, halfWheelTrack: Double): Pair<List<Vec2>, List<Vec2>> {
        if (fence.size < 3) return emptyList<Vec2>() to emptyList()
        val f = clockwise(fence)
        return boundaryTrack(f, 0.5 * tramWidth - halfWheelTrack) to boundaryTrack(f, 0.5 * tramWidth + halfWheelTrack)
    }

    /** AgOpenGPS przesuwa granicę w lewo od jej kierunku — dla granicy zgodnej z zegarem to jest do środka pola. */
    private fun clockwise(r: List<Vec2>): List<Vec2> {
        var a = 0.0
        for (i in r.indices) { val p = r[i]; val q = r[(i + 1) % r.size]; a += p.x * q.y - q.x * p.y }
        return if (a > 0) r.reversed() else r
    }

    /** CFenceLine.CalculateFenceLineHeadings — kurs punktu z sąsiadów (zamknięta pętla). */
    private fun fenceHeadings(f: List<Vec2>): DoubleArray {
        val n = f.size
        val h = DoubleArray(n)
        for (i in 0 until n) {
            val prev = f[(i - 1 + n) % n]; val next = f[(i + 1) % n]
            var x = atan2(next.x - prev.x, next.y - prev.y)
            if (x < 0) x += 2 * PI
            h[i] = x
        }
        return h
    }

    private fun boundaryTrack(fence: List<Vec2>, distance: Double): List<Vec2> {
        val ptCount = fence.size
        if (ptCount < 2) return emptyList()
        val head = fenceHeadings(fence)
        val distSq = distance * distance * 0.999
        val raw = ArrayList<Pose>()
        for (i in 0 until ptCount) {
            val h = head[i]
            val pt = Vec2(fence[i].x - sin(PI / 2 + h) * distance, fence[i].y - cos(PI / 2 + h) * distance)
            var add = true
            for (j in 0 until ptCount) if (d2(pt, fence[j]) < distSq) { add = false; break }
            if (!add) continue
            if (raw.isNotEmpty()) { if (d2(pt, raw.last().p) > 1.0) raw.add(Pose(pt, h)) } else raw.add(Pose(pt, h))
        }
        if (raw.size < 4) return raw.map { it.p }
        raw.add(raw[0]); raw.add(raw[0])
        val spaced = minimumSpacing(raw, 1.2)
        return spaced.map { it.p }
    }

    /** CABCurve.MakePointMinimumSpacing: wstawia środek, aż żaden odcinek nie jest dłuższy niż [minDistance]. */
    fun minimumSpacing(list: List<Pose>, minDistance: Double): List<Pose> {
        val x = ArrayList(list)
        if (x.size <= 3) return x
        var i = 0
        while (i < x.size - 1) {
            val j = i + 1
            if (sqrt(d2(x[i].p, x[j].p)) > minDistance) {
                x.add(j, Pose(Vec2((x[i].p.x + x[j].p.x) / 2.0, (x[i].p.y + x[j].p.y) / 2.0), x[i].heading))
                continue
            }
            i++
        }
        return x
    }

    // ---------------- ślady wzdłuż linii (FormTramLine) ----------------

    /** BuildABTram: ślady wewnętrzne i zewnętrzne [passes] przejazdów opryskiwacza od przejazdu [startPass], po prawej linii (albo lewej gdy [flip]). */
    fun abLines(
        a: Vec2, heading: Double, tramWidth: Double, halfWheelTrack: Double, passes: Int, startPass: Int, flip: Boolean,
        fence: List<Vec2>?, maxFieldDistance: Double,
    ): List<List<Vec2>> {
        val hs = sin(heading); val hc = cos(heading)
        val endA = Vec2(a.x - hs * maxFieldDistance, a.y - hc * maxFieldDistance)
        val len = 2 * maxFieldDistance
        val ref = ArrayList<Vec2>()
        var i = 0
        while (i < len.toInt()) { ref.add(Vec2(hs * i + endA.x, hc * i + endA.y)); i += 2 }
        var hCalc = heading + PI / 2
        if (hCalc < 0) hCalc += 2 * PI
        if (hCalc > 2 * PI) hCalc -= 2 * PI
        if (flip) hCalc += PI
        if (hCalc > 2 * PI) hCalc -= 2 * PI
        val s = sin(hCalc); val c = cos(hCalc)
        val out = ArrayList<List<Vec2>>()
        for (sign in listOf(-1.0, 1.0)) {
            for (k in startPass until passes + startPass) {
                val widd = tramWidth * 0.5 + sign * halfWheelTrack + tramWidth * k
                val arr = ArrayList<Vec2>()
                for (r in ref) {
                    val p = Vec2(s * widd + r.x, c * widd + r.y)
                    if (fence == null || Polygon.contains(fence, p)) arr.add(p)
                }
                out.add(arr)
            }
        }
        return out
    }

    /** BuildCurveTram (z BuildExtendedCurveRef i AddCurveTramPass). */
    fun curveLines(
        curve: List<Pose>, tramWidth: Double, halfWheelTrack: Double, passes: Int, startPass: Int, flip: Boolean, fence: List<Vec2>?,
    ): List<List<Vec2>> {
        if (curve.size < 2) return emptyList()
        val refPts = extendedRef(curve, fence)
        val side = if (flip) PI else 0.0
        val out = ArrayList<List<Vec2>>()
        for (sign in listOf(-1.0, 1.0)) {
            for (k in startPass..(passes + startPass) - 1) {
                val widd = tramWidth * 0.5 + sign * halfWheelTrack + tramWidth * k
                out.add(curvePass(refPts, widd, side, fence))
            }
        }
        return out
    }

    private fun extendedRef(curve: List<Pose>, fence: List<Vec2>?): List<Pose> {
        val first = curve.first(); val last = curve.last()
        fun leave(p: Vec2): Double = if (fence == null) 500.0 else minOf(sqrt(fence.maxOf { d2(it, p) }) + 4, 20000.0)
        val out = ArrayList<Pose>()
        val se = leave(first.p); val ee = leave(last.p)
        val fs = sin(first.heading); val fc = cos(first.heading)
        var d = se
        while (d >= 2) { out.add(Pose(Vec2(first.p.x - fs * d, first.p.y - fc * d), first.heading)); d -= 2 }
        out.addAll(curve)
        val ls = sin(last.heading); val lc = cos(last.heading)
        d = 2.0
        while (d <= ee) { out.add(Pose(Vec2(last.p.x + ls * d, last.p.y + lc * d), last.heading)); d += 2 }
        return out
    }

    private fun curvePass(refPts: List<Pose>, widd: Double, side: Double, fence: List<Vec2>?): List<Vec2> {
        val arr = ArrayList<Vec2>()
        val away = widd * widd * 0.999999
        for (r in refPts) {
            val p = Vec2(sin(PI / 2 + r.heading + side) * widd + r.p.x, cos(PI / 2 + r.heading + side) * widd + r.p.y)
            if (arr.isNotEmpty() && d2(p, arr.last()) <= 1.2) continue
            if (fence != null && !Polygon.contains(fence, p)) continue
            var tooClose = false
            for (t in refPts) if (d2(p, t.p) < away) { tooClose = true; break }
            if (!tooClose) arr.add(p)
        }
        return arr
    }

    // ---------------- bajt sterujący (OpenGL.Designer.cs) ----------------

    /**
     * Odczyt śladu pod maszyną na linii maszyny: przy „zewnętrznym” układzie [halfWheelTrack] od krawędzi, inaczej od środka.
     * Ślad w buforze AgOpenGPS ma 4 piksele (0,4 m), więc trafienie = ślad bliżej niż 0,2 m od punktu.
     */
    fun controlByte(
        set: Set, mode: Int, left: Vec2, right: Vec2, toolWidth: Double, trackWidth: Double, halfWheelTrack: Double,
        outer: Boolean, manualLeft: Boolean = false, manualRight: Boolean = false,
    ): Int {
        if (mode <= 0 || toolWidth <= trackWidth) return 0
        val dir = (right - left) * (1.0 / toolWidth.coerceAtLeast(0.01))
        fun at(x: Double) = left + dir * x
        val pr = if (outer) at(toolWidth - halfWheelTrack) else at(toolWidth / 2 + halfWheelTrack)
        val pl = if (outer) at(halfWheelTrack) else at(toolWidth / 2 - halfWheelTrack)
        var b = 0
        if (hit(set, mode, pr) || manualRight) b += 1
        if (hit(set, mode, pl) || manualLeft) b += 2
        return b
    }

    private fun hit(set: Set, mode: Int, p: Vec2): Boolean {
        val r2 = 0.2 * 0.2
        if (usesLines(mode)) for (l in set.lines) if (near(l, p, r2, closed = false)) return true
        if (usesBoundary(mode)) { if (near(set.outer, p, r2, true) || near(set.inner, p, r2, true)) return true }
        return false
    }

    private fun near(l: List<Vec2>, p: Vec2, r2: Double, closed: Boolean): Boolean {
        if (l.isEmpty()) return false
        val n = if (closed) l.size else l.size - 1
        if (l.size == 1) return d2(l[0], p) <= r2
        for (i in 0 until n) {
            val a = l[i]; val b = l[(i + 1) % l.size]
            val e = b - a
            val len2 = e.x * e.x + e.y * e.y
            val t = if (len2 < 1e-12) 0.0 else (((p.x - a.x) * e.x + (p.y - a.y) * e.y) / len2).coerceIn(0.0, 1.0)
            if (d2(Vec2(a.x + e.x * t, a.y + e.y * t), p) <= r2) return true
        }
        return false
    }

    // ---------------- Tram.txt ----------------

    fun parseTramTxt(text: String): Set {
        val lines = text.lines().map { it.trim() }
        var k = 0
        if (lines.getOrNull(0)?.startsWith("$") == true) k++
        fun readPts(): List<Vec2> {
            val n = lines.getOrNull(k++)?.toIntOrNull() ?: return emptyList()
            val out = ArrayList<Vec2>()
            repeat(n) {
                val p = lines.getOrNull(k++)?.split(',')
                val e = p?.getOrNull(0)?.toDoubleOrNull(); val nn = p?.getOrNull(1)?.toDoubleOrNull()
                if (e != null && nn != null) out.add(Vec2(e, nn))
            }
            return out
        }
        val outer = readPts()
        val inner = readPts()
        val count = lines.getOrNull(k++)?.toIntOrNull() ?: 0
        val ls = ArrayList<List<Vec2>>()
        repeat(count) { ls.add(readPts()) }
        return Set(outer, inner, ls)
    }

    private fun d2(u: Vec2, v: Vec2): Double { val x = u.x - v.x; val y = u.y - v.y; return x * x + y * y }
}
