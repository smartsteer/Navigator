package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kontur 1:1 z AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CContour.cs:
 * nagrywanie pasów (StartContourLine, AddPoint z przesunięciem maszyny, StopContourLine), budowa linii prowadzenia
 * (BuildContourGuidanceLine) i prowadzenie po niej (DistanceFromContourLine: Stanley albo pure pursuit z własną całką).
 * Linia prowadzenia [guide] ma kierunek pasa odniesienia (jak ctList); [sameWay] mówi, czy jedziemy zgodnie z nim.
 */
class Contour {
    val strips = ArrayList<MutableList<Pose>>()
    private var current: MutableList<Pose>? = null

    private var stripNum = -1
    private var lastLockPt = Int.MAX_VALUE
    private var pt = 0
    /** Blokada pasa (przycisk „zablokuj kontur”, SetLockToLine). */
    var locked = false; private set
    var guide: List<Pose> = emptyList(); private set
    var sameWay = true; private set

    val isRecording: Boolean get() = current != null

    /** StartContourLine / AddPoint / StopContourLine — [working] = choć jedna sekcja maluje (patchCounter != 0). */
    fun record(pose: Pose, working: Boolean, toolOffset: Double = 0.0) {
        if (!working) { stop(); return }
        val c = current ?: ArrayList<Pose>(16).also { current = it; strips.add(it) }
        c.add(Pose(Vec2(pose.p.x + cos(pose.heading) * toolOffset, pose.p.y - sin(pose.heading) * toolOffset), pose.heading))
    }

    fun stop() {
        val c = current ?: return
        if (c.size <= 5) strips.remove(c)
        current = null
    }

    fun clear() {
        strips.clear(); current = null; guide = emptyList(); locked = false; stripNum = -1
    }

    /** SetLockToLine: przełącza blokadę, gdy jest linia (więcej niż 5 punktów). */
    fun toggleLock(): Boolean { if (guide.size > 5) locked = !locked; return locked }

    private fun drop(): List<Pose> { guide = emptyList(); locked = false; return guide }

    /** BuildContourGuidanceLine. [autosteerOn] = przycisk autostera (wtedy, jak przy blokadzie, trzymamy się pasa). */
    fun build(pivot: Vec2, heading: Double, toolWidth: Double, overlap: Double, autosteerOn: Boolean, toolOffset: Double = 0.0): List<Pose> {
        val toolContourDistance = toolWidth * 3 + abs(toolOffset)
        val halfWidth = toolWidth / 2
        if (strips.isEmpty()) return guide
        val sinH = sin(heading) * 0.2
        val cosH = cos(heading) * 0.2
        val sin2HL = sin(heading + PI / 2)
        val cos2HL = cos(heading + PI / 2)
        val boxA = Vec2(pivot.x - sin2HL + sinH, pivot.y - cos2HL + cosH)
        val boxB = Vec2(pivot.x + sin2HL + sinH, pivot.y + cos2HL + cosH)
        var minDistance = Double.MAX_VALUE
        if (!locked && !autosteerOn) {
            stripNum = -1
            for (s in strips.indices) {
                val strip = strips[s]
                if (strip.isEmpty()) continue
                var p = 0
                while (p < strip.size) {
                    val q = strip[p].p
                    if (((boxA.x - boxB.x) * (q.y - boxB.y)) - ((boxA.y - boxB.y) * (q.x - boxB.x)) > 0) { p += 3; continue }
                    val d = d2(pivot, q)
                    if (d < minDistance) { minDistance = d; stripNum = s; pt = p; lastLockPt = p }
                    p += 3
                }
            }
            minDistance = sqrt(minDistance)
            if (stripNum < 0 || minDistance > toolContourDistance || strips[stripNum].size < 4) return drop()
        } else {
            if (stripNum !in strips.indices) return drop()
            val strip = strips[stripNum]
            if (strip.size < 2) return drop()
            val start = (if (lastLockPt == Int.MAX_VALUE) 0 else lastLockPt - 20).coerceAtLeast(0)
            val stop = (if (lastLockPt == Int.MAX_VALUE) strip.size else lastLockPt + 20).coerceAtMost(strip.size)
            var i = start
            while (i < stop) {
                val d = d2(pivot, strip[i].p)
                if (minDistance >= d) { minDistance = d; pt = i; lastLockPt = i }
                i += 3
            }
            if (sqrt(minDistance) > toolContourDistance) return drop()
        }
        val strip = strips[stripNum]
        val refX = strip[pt].p.x
        val refZ = strip[pt].p.y
        val distanceFromRefLine = when {
            pt < strip.size - 1 -> {
                val dx = strip[pt + 1].p.x - refX; val dz = strip[pt + 1].p.y - refZ
                ((dz * pivot.x) - (dx * pivot.y) + (strip[pt + 1].p.x * refZ) - (strip[pt + 1].p.y * refX)) / sqrt(dz * dz + dx * dx)
            }
            pt > 0 -> {
                val dx = refX - strip[pt - 1].p.x; val dz = refZ - strip[pt - 1].p.y
                ((dz * pivot.x) - (dx * pivot.y) + (refX * strip[pt - 1].p.y) - (refZ * strip[pt - 1].p.x)) / sqrt(dz * dz + dx * dx)
            }
            else -> return guide
        }
        val isSameWay = PI - abs(abs(heading - strip[pt].heading) - PI) < 1.57
        val refDist = (distanceFromRefLine + (if (isSameWay) toolOffset else -toolOffset)) / (toolWidth - overlap)
        val howManyPathsAway = if (abs(distanceFromRefLine) > halfWidth || abs(toolOffset) > halfWidth) (if (refDist < 0) -1.0 else 1.0) else 0.0
        val start: Int
        val stop: Int
        if (isSameWay) { start = (pt - 20).coerceAtLeast(0); stop = (pt + 70).coerceAtMost(strip.size) }
        else { start = (pt - 70).coerceAtLeast(0); stop = (pt + 20).coerceAtMost(strip.size) }
        val distAway = (toolWidth - overlap) * howManyPathsAway + (if (isSameWay) -toolOffset else toolOffset)
        val distSqAway = distAway * distAway * 0.97
        val out = ArrayList<Pose>()
        for (i in start until stop) {
            val s = strip[i]
            val point = Pose(Vec2(s.p.x + cos(s.heading) * distAway, s.p.y - sin(s.heading) * distAway), s.heading)
            var ok = true
            for (j in start until stop) if (d2(point.p, strip[j].p) < distSqAway) { ok = false; break }
            if (!ok) continue
            if (out.isEmpty() || d2(point.p, out.last().p) > 0.2) out.add(point)
        }
        if (out.size < 5) return drop()
        sameWay = isSameWay
        guide = out
        return guide
    }

    // ---------------- DistanceFromContourLine ----------------

    private var a = 0
    private var b = 0
    private var inty = 0.0
    private var pivotDistanceError = 0.0
    private var pivotDistanceErrorLast = 0.0
    private var pivotDerivative = 0.0
    private var counter2 = 0
    var goal: Vec2? = null; private set

    class Result(val steerAngleDeg: Double, val distanceFromLine: Double)

    /**
     * Prowadzenie po [guide] — null, gdy linia ma ≤ 8 punktów (odległość 0). [antenna] = pn.fix (AgOpenGPS liczy
     * odległość pure pursuit od anteny), [rollDeg] = przechył z IMU, [steering] = wyprzedzenie celu i modeActualXTE.
     */
    fun follow(
        pivot: Vec2, antenna: Vec2, heading: Double, avgSpeed: Double, reverse: Boolean, autosteerOn: Boolean, inTurn: Boolean,
        rollDeg: Double?, p: VehicleProfile, steering: AbSteering,
    ): Result? {
        val ct = guide
        val n = ct.size
        if (n <= 8) { steering.modeActualXte = 0.0; return null }
        var minA = 1_000_000.0
        var minB = 1_000_000.0
        val distance: Double
        var steer: Double
        if (p.algorithm == SteerAlgorithm.STANLEY) {
            val st = pivot + Vec2.forward(heading) * p.wheelbase
            for (t in 0 until n) {
                val d = d2(st, ct[t].p)
                if (d < minA) { minB = minA; b = a; minA = d; a = t } else if (d < minB) { minB = d; b = t }
            }
            if (a > b) { val c = a; a = b; b = c }
            val dx = ct[b].p.x - ct[a].p.x
            val dy = ct[b].p.y - ct[a].p.y
            if (abs(dx) < 1e-300 && abs(dy) < 1e-300) return null
            var dist = ((dy * st.x) - (dx * st.y) + (ct[b].p.x * ct[a].p.y) - (ct[b].p.y * ct[a].p.x)) / sqrt(dy * dy + dx * dx)
            var abHeading = atan2(dx, dy)
            if (abHeading < 0) abHeading += 2 * PI
            val same = PI - abs(abs(heading - abHeading) - PI) < PI / 2
            val u = (((st.x - ct[a].p.x) * dx) + ((st.y - ct[a].p.y) * dy)) / ((dx * dx) + (dy * dy))
            goal = Vec2(ct[a].p.x + u * dx, ct[a].p.y + u * dy)
            var delta: Double
            if (same) delta = heading - abHeading else { dist *= -1.0; delta = heading - abHeading + PI }
            if (delta > PI) delta -= PI else if (delta < PI) delta += PI
            if (delta > PI / 2) delta -= PI else if (delta < -PI / 2) delta += PI
            if (reverse) delta *= -1
            delta *= p.stanleyHeadingGain
            delta = delta.coerceIn(-0.74, 0.74)
            steer = atan((dist * p.stanleyDistanceGain) / ((abs(avgSpeed) * 0.277777) + 1)).coerceIn(-0.74, 0.74)
            steer = ((steer + delta) * -1.0) * 180.0 / PI
            steer = steer.coerceIn(-p.maxSteerAngle, p.maxSteerAngle)
            distance = dist
        } else {
            for (t in 0 until n) {
                val d = d2(pivot, ct[t].p)
                if (d < minA) { minB = minA; b = a; minA = d; a = t } else if (d < minB) { minB = d; b = t }
            }
            if (a > b) { val c = a; a = b; b = c }
            if (locked && (a < 2 || b > n - 3)) { locked = false; lastLockPt = Int.MAX_VALUE; return null }
            val dx = ct[b].p.x - ct[a].p.x
            val dy = ct[b].p.y - ct[a].p.y
            if (abs(dx) < 1e-300 && abs(dy) < 1e-300) return null
            var dist = ((dy * antenna.x) - (dx * antenna.y) + (ct[b].p.x * ct[a].p.y) - (ct[b].p.y * ct[a].p.x)) / sqrt(dy * dy + dx * dx)
            if (p.purePursuitIntegral != 0.0) {
                pivotDistanceError = dist * 0.2 + pivotDistanceError * 0.8
                if (counter2++ > 4) {
                    pivotDerivative = pivotDistanceError - pivotDistanceErrorLast
                    pivotDistanceErrorLast = pivotDistanceError
                    counter2 = 0
                    pivotDerivative *= 2
                }
                if (autosteerOn && abs(pivotDerivative) < 0.1 && avgSpeed > 2.5 && !inTurn) {
                    if ((inty < 0 && dist < 0) || (inty > 0 && dist > 0)) {
                        inty += pivotDistanceError * p.purePursuitIntegral * -0.06
                    } else if (abs(dist) > 0.02) {
                        inty += pivotDistanceError * p.purePursuitIntegral * -0.02
                        inty = inty.coerceIn(-0.2, 0.2)
                    }
                } else inty *= 0.95
            } else inty = 0.0
            if (reverse) inty = 0.0
            val same = PI - abs(abs(heading - ct[a].heading) - PI) < PI / 2
            if (!same) dist *= -1.0
            val u = (((pivot.x - ct[a].p.x) * dx) + ((pivot.y - ct[a].p.y) * dy)) / ((dx * dx) + (dy * dy))
            val r = Vec2(ct[a].p.x + u * dx, ct[a].p.y + u * dy)
            steering.modeActualXte = dist
            val goalDist = steering.goalPointDistance(avgSpeed, p)
            val fwd = if (reverse) !same else same
            val count = if (fwd) 1 else -1
            var start = r
            var soFar = 0.0
            var g = goal ?: r
            var i = if (fwd) b else a
            while (i in 0 until n) {
                val temp = sqrt(d2(start, ct[i].p))
                if (temp + soFar > goalDist) {
                    val j = (goalDist - soFar) / temp
                    g = Vec2((1 - j) * start.x + j * ct[i].p.x, (1 - j) * start.y + j * ct[i].p.y)
                    break
                } else soFar += temp
                start = ct[i].p
                i += count
            }
            goal = g
            val gd2 = d2(g, pivot)
            val localHeading = if (same) 2 * PI - heading + inty else 2 * PI - heading - inty
            steer = atan(2 * (((g.x - pivot.x) * cos(localHeading)) + ((g.y - pivot.y) * sin(localHeading))) * p.wheelbase / gd2) * 180.0 / PI
            if (rollDeg != null) steer += rollDeg * -p.sideHillComp
            steer = steer.coerceIn(-p.maxSteerAngle, p.maxSteerAngle)
            distance = dist
        }
        steering.modeActualXte = distance
        return Result(steer, distance)
    }

    private fun d2(u: Vec2, v: Vec2): Double { val x = u.x - v.x; val y = u.y - v.y; return x * x + y * y }
}
