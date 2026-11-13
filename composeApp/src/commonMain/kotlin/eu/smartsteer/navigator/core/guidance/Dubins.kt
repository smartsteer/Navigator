package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Najkrótsza ścieżka Dubinsa (łuk–prosta–łuk albo trzy łuki) między dwoma położeniami z kursem.
 *
 * Wierne przeniesienie z AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CDubins.cs
 * (na podstawie kodu Erika Nordeusa, habrador.com) — łącznie z warunkami i kolejnością sprawdzania jak w oryginale.
 * Kurs: 0 = północ, zgodnie z zegarem; Vec2.x = wschód, Vec2.y = północ.
 */
class Dubins(private val turningRadius: Double) {

    private enum class PathType { RSR, LSL, RSL, LSR, RLR, LRL }

    private class OnePath(
        val length1: Double, val length2: Double, val length3: Double,
        val type: PathType, val segment2Turning: Boolean,
        val s1Right: Boolean, val s2Right: Boolean, val s3Right: Boolean,
    ) {
        val totalLength = length1 + length2 + length3
        var coords: List<Vec2> = emptyList()
    }

    private lateinit var startPos: Vec2
    private lateinit var goalPos: Vec2
    private var startHeading = 0.0
    private var goalHeading = 0.0
    private lateinit var startLeft: Vec2
    private lateinit var startRight: Vec2
    private lateinit var goalLeft: Vec2
    private lateinit var goalRight: Vec2

    /** GenerateDubins: punkty ścieżki (co piąty krok 5 cm, czyli co ~25 cm) z kursem; pusta lista = brak ścieżki. */
    fun generate(start: Pose, goal: Pose): List<Pose> {
        startPos = start.p; startHeading = start.heading
        goalPos = goal.p; goalHeading = goal.heading
        val paths = allPaths()
        val out = ArrayList<Pose>()
        val best = paths.firstOrNull() ?: return out
        val c = best.coords
        if (c.size > 1) {
            var i = 0
            while (i < c.size - 1) {
                out.add(Pose(c[i], atan2(c[i + 1].x - c[i].x, c[i + 1].y - c[i].y)))
                i += 5
            }
        }
        return out
    }

    private fun allPaths(): List<OnePath> {
        goalRight = rightCircle(goalPos, goalHeading)
        goalLeft = leftCircle(goalPos, goalHeading)
        startRight = rightCircle(startPos, startHeading)
        startLeft = leftCircle(startPos, startHeading)
        val list = ArrayList<OnePath>()
        // RSR i LSL tylko gdy okręgi nie są w tym samym miejscu (warunek dokładnie jak w AgOpenGPS)
        if (startRight.x != goalRight.x && startRight.y != goalRight.y) list += rsr()
        if (startLeft.x != goalLeft.x && startLeft.y != goalLeft.y) list += lsl()
        var cmp = turningRadius * 2 * turningRadius * 2
        if (len2(startRight - goalLeft) > cmp) list += rsl()
        if (len2(startLeft - goalRight) > cmp) list += lsr()
        cmp = 4 * turningRadius * 4 * turningRadius
        if (len2(startRight - goalRight) < cmp) list += rlr()
        if (len2(startLeft - goalLeft) < cmp) list += lrl()
        list.sortBy { it.totalLength }
        for (p in list) p.coords = totalPath(p)
        return list
    }

    private fun rsr(): OnePath {
        val (t1, t2) = lslOrRsr(startRight, goalRight, false)
        return OnePath(arcLength(startRight, startPos, t1, false), (t1 - t2).length(), arcLength(goalRight, t2, goalPos, false),
            PathType.RSR, false, true, false, true)
    }

    private fun lsl(): OnePath {
        val (t1, t2) = lslOrRsr(startLeft, goalLeft, true)
        return OnePath(arcLength(startLeft, startPos, t1, true), (t1 - t2).length(), arcLength(goalLeft, t2, goalPos, true),
            PathType.LSL, false, false, false, false)
    }

    private fun rsl(): OnePath {
        val (t1, t2) = rslOrLsr(startRight, goalLeft, false)
        return OnePath(arcLength(startRight, startPos, t1, false), (t1 - t2).length(), arcLength(goalLeft, t2, goalPos, true),
            PathType.RSL, false, true, false, false)
    }

    private fun lsr(): OnePath {
        val (t1, t2) = rslOrLsr(startLeft, goalRight, true)
        return OnePath(arcLength(startLeft, startPos, t1, true), (t1 - t2).length(), arcLength(goalRight, t2, goalPos, false),
            PathType.LSR, false, false, false, true)
    }

    private fun rlr(): OnePath {
        val (t1, t2, mid) = rlrOrLrl(startRight, goalRight, false)
        return OnePath(arcLength(startRight, startPos, t1, false), arcLength(mid, t1, t2, true), arcLength(goalRight, t2, goalPos, false),
            PathType.RLR, true, true, false, true)
    }

    private fun lrl(): OnePath {
        val (t1, t2, mid) = rlrOrLrl(startLeft, goalLeft, true)
        return OnePath(arcLength(startLeft, startPos, t1, true), arcLength(mid, t1, t2, false), arcLength(goalLeft, t2, goalPos, true),
            PathType.LRL, true, false, true, false)
    }

    private fun totalPath(p: OnePath): List<Vec2> {
        val out = ArrayList<Vec2>()
        var pos = startPos
        var theta = startHeading
        out.add(pos)
        fun add(segments: Int, turning: Boolean, right: Boolean) {
            for (i in 0..segments) {
                pos = Vec2(pos.x + DRIVE * sin(theta), pos.y + DRIVE * cos(theta))
                if (turning) theta += (DRIVE / turningRadius) * (if (right) 1.0 else -1.0)
                out.add(pos)
            }
        }
        add(floor(p.length1 / DRIVE).toInt(), true, p.s1Right)
        add(floor(p.length2 / DRIVE).toInt(), p.segment2Turning, p.s2Right)
        add(floor(p.length3 / DRIVE).toInt(), true, p.s3Right)
        out.add(goalPos)
        return out
    }

    // ---------------- DubinsMath ----------------

    private fun rightCircle(p: Vec2, h: Double) = Vec2(p.x + turningRadius * sin(h + PI / 2), p.y + turningRadius * cos(h + PI / 2))
    private fun leftCircle(p: Vec2, h: Double) = Vec2(p.x + turningRadius * sin(h - PI / 2), p.y + turningRadius * cos(h - PI / 2))

    private fun lslOrRsr(sc: Vec2, gc: Vec2, isBottom: Boolean): Pair<Vec2, Vec2> {
        var theta = PI / 2
        theta += atan2(gc.y - sc.y, gc.x - sc.x)
        if (isBottom) theta += PI
        val t1 = Vec2(sc.x + turningRadius * cos(theta), sc.y + turningRadius * sin(theta))
        val dir = gc - sc
        return t1 to (t1 + dir)
    }

    private fun rslOrLsr(sc: Vec2, gc: Vec2, isBottom: Boolean): Pair<Vec2, Vec2> {
        val d = (sc - gc).length()
        var theta = acos((2 * turningRadius) / d)
        if (isBottom) theta *= -1.0
        theta += atan2(gc.y - sc.y, gc.x - sc.x)
        val t1 = Vec2(sc.x + turningRadius * cos(theta), sc.y + turningRadius * sin(theta))
        val tmp = Vec2(sc.x + 2.0 * turningRadius * cos(theta), sc.y + 2.0 * turningRadius * sin(theta))
        val dir = gc - tmp
        return t1 to (t1 + dir)
    }

    private fun rlrOrLrl(sc: Vec2, gc: Vec2, isLrl: Boolean): Triple<Vec2, Vec2, Vec2> {
        val d = (sc - gc).length()
        var theta = acos(d / (4 * turningRadius))
        val v1 = gc - sc
        theta = if (isLrl) atan2(v1.y, v1.x) + theta else atan2(v1.y, v1.x) - theta
        val mid = Vec2(sc.x + 2 * turningRadius * cos(theta), sc.y + 2 * turningRadius * sin(theta))
        val v2 = norm(sc - mid) * turningRadius
        val v3 = norm(gc - mid) * turningRadius
        return Triple(mid + v2, mid + v3, mid)
    }

    private fun arcLength(center: Vec2, start: Vec2, goal: Vec2, isLeft: Boolean): Double {
        val v1 = start - center
        val v2 = goal - center
        var theta = atan2(v2.y, v2.x) - atan2(v1.y, v1.x)
        if (theta < 0 && isLeft) theta += 2.0 * PI
        else if (theta > 0 && !isLeft) theta -= 2.0 * PI
        return abs(theta * turningRadius)
    }

    private fun len2(v: Vec2) = v.x * v.x + v.y * v.y
    private fun Vec2.length() = sqrt(x * x + y * y)
    private fun norm(v: Vec2): Vec2 { val l = v.length(); return if (l < 1e-12) v else Vec2(v.x / l, v.y / l) }

    companion object {
        /** Krok budowania ścieżki, m (driveDistance). */
        const val DRIVE = 0.05
    }
}
