package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Auto-zawrót 1:1 z AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki SourceCode/GPS/Classes/CYouTurn.cs,
 * CTurn.cs (BuildTurnLines, IsPointInsideTurnArea), CTurnLines.cs (FixTurnLine, CalculateTurnHeadings):
 * linia zawrotu z granicy, budowa zawrotu w fazach (0 → 1 → 2 → 10) — omega (Dubins) albo szeroki (dwa półłuki i odcinek
 * po linii zawrotu), przesuwanie do linii zawrotu (MoveTurnInsideTurnLine), proste wjazdu/wyjazdu (AddABSequenceLines /
 * AddCurveSequenceLines), wygładzanie (SmoothYouTurn) i uruchomienie 1 m przed trzecim punktem (YouTurnTrigger).
 * Kurs: 0 = północ, zgodnie z zegarem; Vec2.x = wschód, Vec2.y = północ.
 */
class YouTurnAog {

    /** Linie zawrotu: [0] = zewnętrzna (granica odsunięta do środka), dalej przeszkody (odsunięte na zewnątrz). */
    class TurnLines(val lines: List<List<Pose>>, private val polys: List<List<Vec2>>) {
        /** IsPointInsideTurnArea: −1 poza zewnętrzną, 0 w polu do zawracania, i = w przeszkodzie i. */
        fun inside(p: Vec2): Int {
            if (polys.isEmpty() || !Polygon.contains(polys[0], p)) return -1
            for (i in 1 until polys.size) if (Polygon.contains(polys[i], p)) return i
            return 0
        }

        companion object {
            /** BuildTurnLines + FixTurnLine(odstęp, 2) + CalculateTurnHeadings + przerzedzenie po kursie + zamknięcie. */
            fun build(fence: List<Vec2>, obstacles: List<List<Vec2>>, distance: Double): TurnLines? {
                if (fence.size < 3) return null
                val lines = ArrayList<List<Pose>>()
                val polys = ArrayList<List<Vec2>>()
                // AgOpenGPS odsuwa punkt w lewo od kierunku granicy: zewnętrzna przeciwnie do zegara (lewo = do środka pola),
                // przeszkody zgodnie z zegarem (lewo = na zewnątrz przeszkody)
                val all = listOf(counterClockwise(fence)) + obstacles.filter { it.size >= 3 }.map { counterClockwise(it).reversed() }
                for ((j, f) in all.withIndex()) {
                    val head = loopHeadings(f)
                    val raw = ArrayList<Pose>()
                    for (i in f.indices.reversed()) {
                        val h = head[i]
                        val pt = Vec2(f[i].x + (-sin(PI / 2 + h) * distance), f[i].y + (-cos(PI / 2 + h) * distance))
                        if ((j == 0) == Polygon.contains(f, pt)) raw.add(Pose(pt, h))
                    }
                    val fixed = fix(raw, f, distance, 2.0)
                    if (fixed.size < 3) { if (j == 0) return null else continue }
                    // przerzedzenie: punkt tylko przy zmianie kursu > 0,005 rad
                    val out = ArrayList<Pose>()
                    var delta = 0.0
                    for (i in fixed.indices) {
                        if (i == 0) { out.add(fixed[0]); continue }
                        delta += fixed[i - 1].heading - fixed[i].heading
                        if (abs(delta) > 0.005) { out.add(fixed[i]); delta = 0.0 }
                    }
                    if (out.isNotEmpty()) out.add(out[0])
                    lines.add(out)
                    polys.add(out.map { it.p })
                }
                return TurnLines(lines, polys)
            }

            private fun counterClockwise(r: List<Vec2>): List<Vec2> {
                var a = 0.0
                for (i in r.indices) { val p = r[i]; val q = r[(i + 1) % r.size]; a += p.x * q.y - q.x * p.y }
                return if (a < 0) r.reversed() else r
            }

            private fun loopHeadings(f: List<Vec2>): DoubleArray {
                val n = f.size
                return DoubleArray(n) { i ->
                    val prev = f[(i - 1 + n) % n]; val next = f[(i + 1) % n]
                    var x = atan2(next.x - prev.x, next.y - prev.y)
                    if (x < 0) x += 2 * PI
                    x
                }
            }

            private fun fix(turn0: List<Pose>, fence: List<Vec2>, totalHeadWidth: Double, spacing0: Double): List<Pose> {
                val t = ArrayList(turn0)
                val thw2 = totalHeadWidth * totalHeadWidth
                val sp2 = spacing0 * spacing0
                // za blisko granicy
                for (fp in fence) {
                    var j = 0
                    while (j < t.size) { if (d2(fp, t[j].p) < thw2 * 0.99) t.removeAt(j) else j++ }
                }
                // za rzadko — punkt w połowie
                var i = 0
                while (i < t.size) {
                    val j = if (i + 1 == t.size) 0 else i + 1
                    if (t.size > 1 && d2(t[i].p, t[j].p) > sp2 * 1.8) {
                        t.add(j, Pose(Vec2((t[i].p.x + t[j].p.x) / 2.0, (t[i].p.y + t[j].p.y) / 2.0), t[i].heading))
                        continue
                    }
                    i++
                }
                // za gęsto
                i = 0
                while (i < t.size - 1) { if (d2(t[i].p, t[i + 1].p) < sp2) t.removeAt(i + 1) else i++ }
                // CalculateTurnHeadings
                val n = t.size
                if (n < 2) return t
                return List(n) { k ->
                    val prev = t[(k - 1 + n) % n]; val next = t[(k + 1) % n]
                    var x = atan2(next.p.x - prev.p.x, next.p.y - prev.p.y)
                    if (x < 0) x += 2 * PI
                    Pose(t[k].p, x)
                }
            }
        }
    }

    private class CClose(
        var closePt: Pose = Pose(Vec2(0.0, 0.0), 0.0),
        var turnLineNum: Int = -1,
        var turnLineIndex: Int = -1,
        var turnLineHeading: Double = -1.0,
        var curveIndex: Int = -1,
    ) {
        fun copy() = CClose(closePt, turnLineNum, turnLineIndex, turnLineHeading, curveIndex)
    }

    /** Wszystko o bieżącej linii, czego potrzebuje budowa zawrotu (mf.ABLine / mf.curve w AgOpenGPS). */
    class Line(
        val isAB: Boolean,
        /** AB: kurs linii AB (abHeading). */
        val abHeading: Double,
        /** Jazda zgodnie z kierunkiem linii (isHeadingSameWay). */
        val sameWay: Boolean,
        /** AB: rzut osi tylnej na bieżący przejazd (rEastAB, rNorthAB). */
        val rPoint: Vec2,
        /** AB: końce bieżącego przejazdu przedłużone o 2000 m (currentLinePtA / B). */
        val curA: Vec2, val curB: Vec2,
        /** Krzywa: bieżący przejazd (curList) i indeks przy ciągniku (currentLocationIndex). */
        val curList: List<Pose>, val currentLocationIndex: Int,
        /** Następny przejazd (BuildNewOffsetList) o [skip] przejazdów w stronę zawrotu. */
        val nextLine: (skip: Int) -> List<Pose>,
    )

    /** Ustawienia zawrotu. */
    class Settings(
        val radius: Double, val skip: Int, val toolSpacing: Double, val toolOffset: Double,
        val extensionLength: Double, val style: Int,
    )

    var ytList: List<Pose> = emptyList(); private set
    var phase = 0; private set
    var isTurnLeft = false
    var triggered = false; private set
    var tooClose = false; private set
    var isGoingStraightThrough = false; private set
    private var ytList2: List<Pose> = emptyList()
    private var nextCurve: List<Pose> = emptyList()
    private var isOutSameCurve = false
    private var isOutOfBounds = false
    private var semiCircleIndex = -1
    private var closest = CClose()
    private var inClosest = CClose()
    private var outClosest = CClose()
    private var startOfTurn = CClose()
    private var pointSpacing = 0.81
    private var radius = 8.1
    private var resetAtS = Double.NEGATIVE_INFINITY

    /** ResetCreatedYouTurn (+ makeUTurnCounter = 0). */
    fun resetCreated(nowS: Double) {
        phase = 0; ytList = emptyList(); isOutSameCurve = false; isGoingStraightThrough = false; resetAtS = nowS
    }

    /** ResetYouTurn. */
    fun reset(nowS: Double) { triggered = false; resetCreated(nowS); tooClose = false }

    /** CompleteYouTurn. */
    fun complete(nowS: Double) { triggered = false; resetCreated(nowS) }

    private fun failCreate() { isOutOfBounds = true; tooClose = true; phase = 11 }

    /** Ręczny zawrót (BuildManualYouTurn): gotowa ścieżka Dubinsa. */
    fun setManual(path: List<Pose>) { ytList = path; phase = 10; isGoingStraightThrough = false }

    /** YouTurnTrigger — zmiana przejazdu i kierunku robi sesja; tu stan zawrotu i kierunek następnego. */
    fun trigger() { triggered = true; if (!isGoingStraightThrough) isTurnLeft = !isTurnLeft }

    /**
     * BuildABLineDubinsYouTurn / BuildCurveDubinsYouTurn — jeden krok budowy (wywoływać przy każdej pozycji, dopóki faza ≠ 10/11).
     * [nowS] — makeUTurnCounter: budowa zaczyna się 1 s po wyzerowaniu.
     */
    fun build(line: Line, tl: TurnLines, pivot: Vec2, s: Settings, nowS: Double): Boolean {
        pointSpacing = s.radius * 0.1
        radius = s.radius
        if (nowS - resetAtS < 1.0) { phase = 0; return true }
        val turnOffset = s.toolSpacing * s.skip + (if (isTurnLeft) -s.toolOffset * 2.0 else s.toolOffset * 2.0)
        val ok = if (line.isAB) {
            if (turnOffset > s.radius * 2.0) abWide(line, tl, pivot, s) else abOmega(line, tl, pivot, s, turnOffset)
        } else {
            if (turnOffset > s.radius * 2.0) curveWide(line, tl, pivot, s) else curveOmega(line, tl, pivot, s, turnOffset)
        }
        if (s.style == 0 && phase == 10 && ytList.size > 12) ytList = smooth(ytList, 6)
        return ok
    }

    // ---------------- AB ----------------

    private fun abHead(line: Line): Double {
        var head = line.abHeading
        if (!line.sameWay) head += PI
        if (head >= 2 * PI) head -= 2 * PI
        return head
    }

    private fun abOmega(line: Line, tl: TurnLines, pivot: Vec2, s: Settings, turnOffset: Double): Boolean {
        val head = abHead(line)
        when (phase) {
            0 -> {
                findABTurnPoint(line, tl, Pose(line.rPoint, head))
                if (closest.turnLineIndex == -1) { failCreate(); return false }
                inClosest = closest.copy()
                val start = Pose(inClosest.closePt.p, head)
                var inv = head - PI
                if (inv < 0) inv += 2 * PI
                if (inv > 2 * PI) inv -= 2 * PI
                val gp = if (isTurnLeft) Vec2(start.p.x + cos(-inv) * turnOffset, start.p.y + sin(-inv) * turnOffset)
                    else Vec2(start.p.x - cos(-inv) * turnOffset, start.p.y - sin(-inv) * turnOffset)
                val yt = ArrayList(Dubins(s.radius).generate(start, Pose(gp, inv)))
                isOutOfBounds = true
                if (yt.isEmpty()) { failCreate(); return false }
                phase = 1
                thin(yt)
                ytList = yt
                return true
            }
            1 -> {
                ytList = moveInside(ytList, head, false, false, tl, pivot)
                if (ytList.isEmpty()) { failCreate(); return false }
                isOutOfBounds = false
                phase = 10
                tooClose = false
                return addABSequenceLines(line, pivot, s)
            }
        }
        return true
    }

    private fun abWide(line: Line, tl: TurnLines, pivot: Vec2, s: Settings): Boolean {
        val head = abHead(line)
        when (phase) {
            0 -> {
                findABTurnPoint(line, tl, Pose(line.rPoint, 0.0))
                inClosest = closest.copy()
                startOfTurn = closest.copy()
                if (inClosest.turnLineIndex == -1) { failCreate(); return false }
                ytList = halfCircle(Pose(inClosest.closePt.p, head), head, if (isTurnLeft) -1.0 else 1.0)
                isOutOfBounds = true
                ytList = moveInside(ytList, head, true, false, tl, pivot)
                if (ytList.isEmpty()) { failCreate(); return false }
                phase = 1
                return true
            }
            1 -> {
                nextCurve = line.nextLine(s.skip)
                val hd = abs(startOfTurn.closePt.heading - ytList.last().heading)
                val sameWayTL = !(hd > PI / 2 && hd < 3 * PI / 2)
                if (!findABOutTurnPoint(line, tl, inClosest, sameWayTL)) { failCreate(); return false }
                outClosest = closest.copy()
                var headie = if (!isOutSameCurve) head else head + PI
                if (headie >= 2 * PI) headie -= 2 * PI
                ytList2 = halfCircle(Pose(outClosest.closePt.p, headie), headie, if (isTurnLeft) 1.0 else -1.0)
                isOutOfBounds = true
                ytList2 = moveInside(ytList2, headie, true, true, tl, pivot)
                if (ytList2.isEmpty()) { failCreate(); return false }
                phase = 2
                return true
            }
            2 -> {
                if (!joinAlongTurnLine(tl)) return false
                val yt = densifyAndHeadings(ArrayList(ytList), keepEnds = true)
                if (yt.isEmpty() || yt[0].p.distanceTo(pivot) < 3) { failCreate(); return false }
                ytList = yt
                isGoingStraightThrough = PI - abs(abs(yt[yt.size - 2].heading - yt[1].heading) - PI) < PI / 2
                isOutOfBounds = false
                phase = 10
                tooClose = false
                ytList2 = emptyList()
                return addABSequenceLines(line, pivot, s)
            }
        }
        return false
    }

    // ---------------- krzywa ----------------

    private fun curveOmega(line: Line, tl: TurnLines, pivot: Vec2, s: Settings, turnOffset: Double): Boolean {
        val cur = line.curList
        when (phase) {
            0 -> {
                if (!findCurveTurnPoint(line, tl)) { failCreate(); return false }
                inClosest = closest.copy()
                val count = if (line.sameWay) -1 else 1
                var curveIndex = inClosest.curveIndex
                isOutOfBounds = true
                var stop = 0
                var head = 0.0
                var yt: List<Pose> = emptyList()
                while (isOutOfBounds) {
                    stop++
                    isOutOfBounds = false
                    curveIndex += count
                    if (curveIndex < 1 || curveIndex > cur.size - 2) { failCreate(); return false }
                    var h = cur[curveIndex].heading
                    if (!line.sameWay) h += PI
                    if (h >= 2 * PI) h -= 2 * PI
                    head = h
                    var inv = h - PI
                    if (inv <= -PI) inv += 2 * PI
                    if (inv >= PI) inv -= 2 * PI
                    val base = cur[curveIndex - count].p
                    val gp = if (isTurnLeft) Vec2(base.x + cos(-inv) * turnOffset, base.y + sin(-inv) * turnOffset)
                        else Vec2(base.x - cos(-inv) * turnOffset, base.y - sin(-inv) * turnOffset)
                    yt = Dubins(s.radius).generate(Pose(cur[curveIndex].p, h), Pose(gp, inv))
                    if (yt.isEmpty()) { failCreate(); return false }
                    if (stop == 300) { failCreate(); return false }
                    for (q in yt) if (tl.inside(q.p) != 0) { isOutOfBounds = true; break }
                }
                inClosest.curveIndex = curveIndex
                val t = ArrayList(yt)
                thin(t)
                ytList = moveInside(t, head, false, false, tl, pivot)
                if (ytList.isEmpty()) { failCreate(); return false }
                phase = 1
                return true
            }
            1 -> {
                nextCurve = line.nextLine(s.skip)
                var dis = Double.MAX_VALUE
                if (nextCurve.size > 1) {
                    for (i in 1 until nextCurve.size) {
                        val nd = nextCurve[i].p.distanceTo(ytList.last().p)
                        if (nd < dis) { dis = nd; outClosest.curveIndex = if (line.sameWay) i - 1 else i }
                    }
                    if (outClosest.curveIndex >= 0) {
                        outClosest.closePt = nextCurve[outClosest.curveIndex]
                        inClosest.closePt = cur[inClosest.curveIndex]
                        if (!addCurveSequenceLines(line, s)) return false
                    }
                }
                val yt = densifyAndHeadings(ArrayList(ytList), keepEnds = false)
                if (yt.isEmpty() || yt[0].p.distanceTo(pivot) < 3) { failCreate(); return false }
                ytList = yt
                isOutOfBounds = false
                phase = 10
                tooClose = false
                return true
            }
        }
        return true
    }

    private fun curveWide(line: Line, tl: TurnLines, pivot: Vec2, s: Settings): Boolean {
        val cur = line.curList
        val count = if (line.sameWay) -1 else 1
        when (phase) {
            0 -> {
                if (!findCurveTurnPoint(line, tl)) { failCreate(); return false }
                inClosest = closest.copy()
                startOfTurn = inClosest.copy()
                var stop = 0
                isOutOfBounds = true
                while (isOutOfBounds) {
                    isOutOfBounds = false
                    stop++
                    if (inClosest.curveIndex !in cur.indices) { failCreate(); return false }
                    var head = cur[inClosest.curveIndex].heading
                    if (!line.sameWay) head += PI
                    if (head > 2 * PI) head -= 2 * PI
                    val yt = halfCircle(Pose(cur[inClosest.curveIndex].p, head), head, if (isTurnLeft) -1.0 else 1.0)
                    if (yt.isEmpty()) { failCreate(); return false }
                    var j = 0
                    while (j < yt.size) { if (tl.inside(yt[j].p) != 0) { isOutOfBounds = true; break }; j += 2 }
                    if (!isOutOfBounds) {
                        ytList = moveInside(yt, head, true, false, tl, pivot)
                        if (ytList.isEmpty()) { failCreate(); return false }
                        phase = 1
                        return true
                    }
                    if (stop == 300 || inClosest.curveIndex < 1 || inClosest.curveIndex > cur.size - 2) { failCreate(); return false }
                    inClosest.curveIndex += count
                    inClosest.closePt = cur[inClosest.curveIndex]
                    if (yt[0].p.distanceTo(pivot) < 3) { failCreate(); return false }
                }
                return false
            }
            1 -> {
                nextCurve = line.nextLine(s.skip)
                val hd = abs(inClosest.turnLineHeading - ytList.last().heading)
                val sameWayTL = !(hd > PI / 2 && hd < 3 * PI / 2)
                if (!findCurveOutTurnPoint(line, tl, startOfTurn, sameWayTL)) { failCreate(); return false }
                outClosest = closest.copy()
                isOutOfBounds = true
                while (isOutOfBounds) {
                    isOutOfBounds = false
                    if (outClosest.curveIndex !in nextCurve.indices) { failCreate(); return false }
                    var head = nextCurve[outClosest.curveIndex].heading
                    if ((!line.sameWay && !isOutSameCurve) || (line.sameWay && isOutSameCurve)) head += PI
                    if (head > 2 * PI) head -= 2 * PI
                    val yt2 = halfCircle(Pose(nextCurve[outClosest.curveIndex].p, head), head, if (isTurnLeft) 1.0 else -1.0)
                    if (yt2.isEmpty()) { failCreate(); return false }
                    var j = 0
                    while (j < yt2.size) { if (tl.inside(yt2[j].p) != 0) { isOutOfBounds = true; break }; j += 2 }
                    if (!isOutOfBounds) {
                        ytList2 = moveInside(yt2, head, true, true, tl, pivot)
                        if (ytList2.isEmpty()) { failCreate(); return false }
                        phase = 2
                        return true
                    }
                    if (outClosest.curveIndex < 1 || outClosest.curveIndex > nextCurve.size - 2) { failCreate(); return false }
                    outClosest.curveIndex += if (!isOutSameCurve) count else -count
                    outClosest.closePt = nextCurve[outClosest.curveIndex]
                }
                return false
            }
            2 -> {
                if (!joinAlongTurnLine(tl)) return false
                if (!addCurveSequenceLines(line, s)) return false
                val yt = densifyAndHeadings(ArrayList(ytList), keepEnds = false)
                if (yt.isEmpty() || yt[0].p.distanceTo(pivot) < 3) { failCreate(); return false }
                ytList = yt
                isGoingStraightThrough = PI - abs(abs(yt[yt.size - 2].heading - yt[1].heading) - PI) < PI / 2
                ytList2 = emptyList()
                isOutOfBounds = false
                phase = 10
                tooClose = false
                return true
            }
        }
        return false
    }

    // ---------------- części wspólne ----------------

    /** Półokrąg: od [start] skręcamy o 180° krokiem pointSpacing ([turn] = −1 w lewo, +1 w prawo). */
    private fun halfCircle(start: Pose, head: Double, turn: Double): List<Pose> {
        val out = ArrayList<Pose>()
        var x = start.p.x; var y = start.p.y; var h = start.heading
        out.add(Pose(Vec2(x, y), h))
        var guard = 0
        while (abs(head - h) < PI && guard++ < 10000) {
            x += pointSpacing * sin(h); y += pointSpacing * cos(h)
            h += (pointSpacing / radius) * turn
            out.add(Pose(Vec2(x, y), h))
        }
        return out
    }

    /** Usuwa punkty bliżej niż pointSpacing (porównanie z kwadratem odległości — jak w AgOpenGPS). */
    private fun thin(yt: MutableList<Pose>) {
        var i = 1
        while (i < yt.size - 2) {
            if (d2(yt[i].p, yt[i + 1].p) < pointSpacing) yt.removeAt(i + 1) else i++
        }
    }

    /** Wstawia środki tam, gdzie odstęp > 1 m, i liczy kursy z sąsiadów (omega/szeroki zawrót krzywej obcinają po 2 punkty z końców). */
    private fun densifyAndHeadings(yt: MutableList<Pose>, keepEnds: Boolean): List<Pose> {
        var i = 1
        while (i < yt.size - 2) {
            val j = i + 1
            if (j == yt.size - 1) { i++; continue }
            if (d2(yt[i].p, yt[j].p) > 1) {
                yt.add(j, Pose(Vec2((yt[i].p.x + yt[j].p.x) / 2.0, (yt[i].p.y + yt[j].p.y) / 2.0), yt[i].heading))
                continue
            }
            i++
        }
        val n = yt.size
        if (n < 3) return yt
        val out = ArrayList<Pose>()
        if (keepEnds) {
            for (k in 0 until n) {
                val prev = maxOf(k - 1, 0); val next = minOf(k + 1, n - 1)
                out.add(Pose(yt[k].p, norm(atan2(yt[next].p.x - yt[prev].p.x, yt[next].p.y - yt[prev].p.y))))
            }
        } else {
            val cnt = n - 2
            for (k in 2 until cnt) out.add(Pose(yt[k].p, norm(atan2(yt[k + 1].p.x - yt[k - 1].p.x, yt[k + 1].p.y - yt[k - 1].p.y))))
        }
        return out
    }

    /** Faza 2 szerokiego zawrotu: łączy dwa półłuki odcinkiem po linii zawrotu. */
    private fun joinAlongTurnLine(tl: TurnLines): Boolean {
        val cnt1 = ytList.size; val cnt2 = ytList2.size
        if (cnt1 == 0 || cnt2 == 0) { failCreate(); return false }
        val fhd = abs(inClosest.turnLineHeading - ytList.last().heading)
        val firstSame = !(fhd > PI / 2 && fhd < 3 * PI / 2)
        findInnerTurnPoints(tl, ytList[cnt1 - 1], ytList[0].heading, inClosest, firstSame)
        val start = closest.copy()
        findInnerTurnPoints(tl, ytList2[cnt2 - 1], ytList2[0].heading + PI, outClosest, !firstSame)
        val goal = closest.copy()
        if (start.turnLineNum != goal.turnLineNum || start.turnLineNum < 0) { failCreate(); return false }
        val out = ArrayList(ytList)
        if (start.turnLineIndex != goal.turnLineIndex) {
            val line = tl.lines[start.turnLineNum]
            val turnCount = line.size
            var loops = abs(start.turnLineIndex - goal.turnLineIndex)
            if (loops > turnCount / 2) {
                loops = if (start.turnLineIndex < goal.turnLineIndex) (turnCount - goal.turnLineIndex) + start.turnLineIndex
                    else (turnCount - start.turnLineIndex) + goal.turnLineIndex
            }
            var idx = start.turnLineIndex
            if (firstSame) {
                repeat(loops) {
                    if (idx + 1 >= turnCount) idx = -1
                    out.add(line[idx + 1])
                    idx++
                    if (idx >= turnCount) idx = 0
                }
            } else {
                repeat(loops) {
                    out.add(line[idx])
                    idx--
                    if (idx == -1) idx = turnCount - 1
                }
            }
        }
        for (a in 0 until cnt2) out.add(ytList2[cnt2 - 1 - a])
        ytList = out
        return true
    }

    /** MoveTurnInsideTurnLine: cofamy kształt o 1 m, aż cały jest w polu, potem przesuwamy naprzód o 0,1 m do linii zawrotu. */
    private fun moveInside(list: List<Pose>, head: Double, deleteSecondHalf: Boolean, invertHeading: Boolean, tl: TurnLines, pivot: Vec2): List<Pose> {
        val cosH = cos(head); val sinH = sin(head)
        val arr = list.map { it }.toMutableList()
        val cnt = arr.size
        semiCircleIndex = -1
        var out = isOutOfBounds
        var j = 0
        var stop = 0
        fun shift(dx: Double, dy: Double) { for (i in 0 until cnt) arr[i] = Pose(Vec2(arr[i].p.x + dx, arr[i].p.y + dy), arr[i].heading) }
        while (out) {
            stop++
            out = false
            val dPivot = arr[0].p.distanceTo(pivot)
            shift(-sinH, -cosH)
            while (j < cnt) {
                if (tl.inside(arr[j].p) != 0) { out = true; if (j > 0) j--; break }
                j++
            }
            if (stop == 1000 || dPivot < 3) return emptyList()
        }
        var guard = 0
        while (!out && guard++ < 100000) {
            shift(sinH * 0.1, cosH * 0.1)
            for (a in 0 until cnt) if (tl.inside(arr[a].p) != 0) { semiCircleIndex = a; out = true; break }
        }
        val res = ArrayList<Pose>()
        for (i in 0 until cnt) {
            if (i == semiCircleIndex && deleteSecondHalf) break
            var h = arr[i].heading
            if (invertHeading) h += PI
            if (h >= 2 * PI) h -= 2 * PI else if (h < 0) h += 2 * PI
            res.add(Pose(arr[i].p, h))
        }
        isOutOfBounds = false
        return res
    }

    private fun addABSequenceLines(line: Line, pivot: Vec2, s: Settings): Boolean {
        var inhead = line.abHeading
        if (!line.sameWay) inhead += PI
        if (inhead > 2 * PI) inhead -= 2 * PI
        var outhead = inhead
        if (isOutSameCurve) outhead += PI
        if (outhead > 2 * PI) outhead -= 2 * PI
        val lenny = s.extensionLength * 2.0
        val yt = ArrayList(ytList)
        var a = 0
        while (a < lenny) {
            yt.add(0, Pose(Vec2(yt[0].p.x - sin(inhead) * pointSpacing, yt[0].p.y - cos(inhead) * pointSpacing), inhead))
            a++
        }
        val count = yt.size
        val last = yt[count - 1].p
        var i = 0
        while (i <= lenny) {
            yt.add(Pose(Vec2(last.x - sin(outhead) * i * pointSpacing, last.y - cos(outhead) * i * pointSpacing), outhead))
            i++
        }
        ytList = yt
        var k = 0
        while (k < yt.size) {
            if (d2(yt[k].p, pivot) <= 3) { failCreate(); return false }
            k += 2
        }
        tooClose = false
        return true
    }

    private fun addCurveSequenceLines(line: Line, s: Settings): Boolean {
        val lenny = s.extensionLength * 2.0
        var sameWay = line.sameWay
        var a = if (sameWay) -1 else 1
        val yt = ArrayList(ytList)
        var i = 0
        while (i < lenny && i > -lenny) {
            if (inClosest.curveIndex !in line.curList.indices) { failCreate(); return false }
            yt.add(0, line.curList[inClosest.curveIndex])
            inClosest.curveIndex += a
            if (inClosest.curveIndex < 2 || inClosest.curveIndex > line.curList.size - 3) { failCreate(); return false }
            i += a
        }
        if (isOutSameCurve) sameWay = !sameWay
        a = if (sameWay) -1 else 1
        i = 0
        while (i < lenny && i > -lenny) {
            if (outClosest.curveIndex !in nextCurve.indices) { failCreate(); return false }
            yt.add(nextCurve[outClosest.curveIndex])
            outClosest.curveIndex += a
            if (outClosest.curveIndex < 2 || outClosest.curveIndex > line.curList.size - 3) { failCreate(); return false }
            i += a
        }
        ytList = yt
        return true
    }

    /** SmoothYouTurn: średnia ruchoma z [smPts] punktów, bez pierwszego i ostatniego, kurs do następnego. */
    fun smooth(yt: List<Pose>, smPts: Int): List<Pose> {
        val cnt = yt.size
        if (cnt <= smPts) return yt
        val x = DoubleArray(cnt); val y = DoubleArray(cnt)
        for (s in 0 until smPts / 2) { x[s] = yt[s].p.x; y[s] = yt[s].p.y }
        for (s in cnt - smPts / 2 until cnt) { x[s] = yt[s].p.x; y[s] = yt[s].p.y }
        for (i in smPts / 2 until cnt - smPts / 2) {
            var sx = 0.0; var sy = 0.0
            for (j in -smPts / 2 until smPts / 2) { sx += yt[j + i].p.x; sy += yt[j + i].p.y }
            x[i] = sx / smPts; y[i] = sy / smPts
        }
        val out = ArrayList<Pose>()
        for (i in 1 until cnt - 1) out.add(Pose(Vec2(x[i], y[i]), norm(atan2(x[i + 1] - x[i], y[i + 1] - y[i]))))
        return out
    }

    // ---------------- punkty na linii zawrotu ----------------

    /** FindABTurnPoint: najbliższe przecięcie odcinka od [from] do końca przejazdu z liniami zawrotu. */
    private fun findABTurnPoint(line: Line, tl: TurnLines, from: Pose) {
        val end = if (line.sameWay) line.curB else line.curA
        var best: CClose? = null
        var minD = Double.MAX_VALUE
        for ((j, l) in tl.lines.withIndex()) {
            for (i in 0 until l.size - 1) {
                val ip = intersect(l[i].p, l[i + 1].p, from.p, end) ?: continue
                var hed = atan2(l[i + 1].p.x - l[i].p.x, l[i + 1].p.y - l[i].p.y)
                if (hed < 0) hed += 2 * PI
                val d = d2(from.p, ip)
                if (minD >= d) { minD = d; best = CClose(Pose(ip, hed), j, i, l[i].heading, -1) }
            }
        }
        if (best != null) closest = best
        else closest = CClose()
    }

    private fun findABOutTurnPoint(line: Line, tl: TurnLines, inPt: CClose, sameWayTL: Boolean): Boolean {
        val l = tl.lines.getOrNull(inPt.turnLineNum) ?: return false
        val a = if (sameWayTL) 1 else -1
        var idx = inPt.turnLineIndex
        var stopIdx = inPt.turnLineIndex - a
        if (stopIdx < 0) stopIdx = l.size - 3
        if (stopIdx > l.size - 1) idx = 3
        var guard = 0
        while (idx != stopIdx && guard++ < l.size * 2 + 10) {
            if (idx < 0) idx = l.size - 2
            if (idx > l.size - 2) idx = 0
            if (nextCurve.size > 1) {
                intersect(l[idx].p, l[idx + 1].p, nextCurve[0].p, nextCurve[1].p)?.let { ip ->
                    closest = CClose(Pose(ip, line.abHeading), inPt.turnLineNum, idx, l[idx].heading, -1)
                    return true
                }
            }
            intersect(l[idx].p, l[idx + 1].p, line.curA, line.curB)?.let { ip ->
                var hed = if (line.sameWay) atan2(line.rPoint.x - ip.x, line.rPoint.y - ip.y) else atan2(ip.x - line.rPoint.x, ip.y - line.rPoint.y)
                if (hed < 0) hed += 2 * PI
                if (round(hed * 1000) / 1000 == round(line.abHeading * 1000) / 1000) return false
                if (idx != inPt.turnLineIndex) {
                    closest = CClose(Pose(ip, line.abHeading), inPt.turnLineNum, idx, l[idx].heading, -1)
                    isOutSameCurve = true
                    return true
                }
            }
            idx += a
        }
        return false
    }

    private fun findCurveTurnPoint(line: Line, tl: TurnLines): Boolean {
        val cur = line.curList
        val count = if (line.sameWay) 1 else -1
        var turnNum = 99
        closest = CClose()
        var j = line.currentLocationIndex
        while (j > 0 && j < cur.size) {
            val ti = tl.inside(cur[j].p)
            if (ti != 0) { closest.curveIndex = j - count; closest.turnLineNum = ti; turnNum = ti; break }
            j += count
        }
        if (turnNum < 0) { closest.turnLineNum = 0; turnNum = 0 } else if (turnNum == 99) return false
        if (closest.curveIndex == -1) return false
        val l = tl.lines.getOrNull(turnNum) ?: return false
        val ci = closest.curveIndex
        if (ci + count !in cur.indices || ci !in cur.indices) return false
        for (i in 0 until l.size - 1) {
            val ip = intersect(l[i].p, l[i + 1].p, cur[ci].p, cur[ci + count].p) ?: continue
            closest.closePt = Pose(ip, cur[ci].heading)
            closest.turnLineIndex = i
            closest.turnLineNum = turnNum
            closest.turnLineHeading = l[i].heading
            if (!line.sameWay && closest.curveIndex > 0) closest.curveIndex--
            break
        }
        return closest.turnLineIndex != -1 && closest.curveIndex != -1
    }

    private fun findCurveOutTurnPoint(line: Line, tl: TurnLines, inPt: CClose, sameWayTL: Boolean): Boolean {
        val l = tl.lines.getOrNull(inPt.turnLineNum) ?: return false
        val cur = line.curList
        val a = if (sameWayTL) 1 else -1
        var idx = inPt.turnLineIndex
        var stopIdx = inPt.turnLineIndex - a
        if (stopIdx < 0) stopIdx = l.size - 3
        if (stopIdx > l.size - 1) idx = 3
        var guard = 0
        while (idx != stopIdx && guard++ < l.size * 2 + 10) {
            if (idx < 0) idx = l.size - 2
            if (idx > l.size - 2) idx = 0
            for (i in 0 until nextCurve.size - 2) {
                val ip = intersect(l[idx].p, l[idx + 1].p, nextCurve[i].p, nextCurve[i + 1].p) ?: continue
                closest = CClose(Pose(ip, nextCurve[i].heading), inPt.turnLineNum, idx, l[idx].heading, i)
                return true
            }
            for (i in 0 until cur.size - 2) {
                val ip = intersect(l[idx].p, l[idx + 1].p, cur[i].p, cur[i + 1].p) ?: continue
                if ((i < inPt.curveIndex && line.sameWay) || (i > inPt.curveIndex && !line.sameWay)) return false
                if (i != inPt.curveIndex) {
                    closest = CClose(Pose(ip, cur[i].heading), inPt.turnLineNum, idx, l[idx].heading, i)
                    isOutSameCurve = true
                    nextCurve = cur
                    return true
                }
            }
            idx += a
        }
        return false
    }

    private fun findInnerTurnPoints(tl: TurnLines, from: Pose, inDirection: Double, ref: CClose, sameWayTL: Boolean): Boolean {
        val l = tl.lines.getOrNull(ref.turnLineNum) ?: return false
        val e = Vec2(from.p.x + sin(inDirection), from.p.y + cos(inDirection))
        val a = if (sameWayTL) 1 else -1
        var idx = ref.turnLineIndex
        var stopIdx = ref.turnLineIndex - a
        if (stopIdx < 0) stopIdx = l.size - 3
        if (stopIdx > l.size - 1) idx = 3
        var guard = 0
        while (idx != stopIdx && guard++ < l.size * 2 + 10) {
            if (idx < 0) idx = l.size - 2
            if (idx > l.size - 2) idx = 0
            intersect(l[idx].p, l[idx + 1].p, from.p, e)?.let { ip ->
                closest = CClose(Pose(ip, -1.0), ref.turnLineNum, idx, l[idx].heading, -1)
                return true
            }
            idx += a
        }
        return false
    }

    /** GetLineIntersection: przecięcie odcinków p0–p1 i p2–p3 (null = brak). */
    private fun intersect(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2): Vec2? {
        val s1x = p1.x - p0.x; val s1y = p1.y - p0.y
        val s2x = p3.x - p2.x; val s2y = p3.y - p2.y
        val den = -s2x * s1y + s1x * s2y
        val s = (-s1y * (p0.x - p2.x) + s1x * (p0.y - p2.y)) / den
        if (s >= 0 && s <= 1) {
            val t = (s2x * (p0.y - p2.y) - s2y * (p0.x - p2.x)) / den
            if (t >= 0 && t <= 1) return Vec2(p0.x + t * s1x, p0.y + t * s1y)
        }
        return null
    }

    companion object {
        private fun d2(u: Vec2, v: Vec2): Double { val x = u.x - v.x; val y = u.y - v.y; return x * x + y * y }
        private fun norm(h: Double): Double { var x = h % (2 * PI); if (x < 0) x += 2 * PI; return x }
    }
}
