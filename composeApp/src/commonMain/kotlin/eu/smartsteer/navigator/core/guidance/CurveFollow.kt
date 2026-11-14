package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure pursuit po krzywej 1:1 z AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CABCurve.cs
 * (GetCurrentCurveLine, findNearestGlobalCurvePoint, findNearestLocalCurvePoint): linia [curList] w kierunku krzywej
 * odniesienia, jazda zgodnie albo pod prąd ([sameWay]), własna całka krzywej (−0,04 / −0,02), koniec krzywej wyłącza autoster.
 */
class CurveFollow {
    private var a = 0
    private var b = 0
    private var current = 0
    private var global = true
    private var inty = 0.0
    private var pde = 0.0
    private var pdeLast = 0.0
    private var deriv = 0.0
    private var counter2 = 0

    /** Nowa linia (findGlobalNearestCurvePoint = true). */
    fun reset() { global = true }

    class Result(val steerAngleDeg: Double, val distanceFromLine: Double, val goal: Vec2, val pastEnd: Boolean)

    fun step(
        curList: List<Pose>, sameWay: Boolean, pivot: Vec2, fixHeading: Double, avgSpeed: Double, reverse: Boolean,
        autosteerOn: Boolean, rollDeg: Double?, p: VehicleProfile, steering: AbSteering,
    ): Result? {
        val n = curList.size
        if (n == 0) return null
        val goalDist = steering.goalPointDistance(avgSpeed, p)
        val revHeading = if (reverse) !sameWay else sameWay
        var cc = if (global) { global = false; nearestGlobal(curList, pivot, 10) } else nearestLocal(curList, pivot, current.coerceIn(0, n - 1), goalDist, revHeading)
        var minA = Double.MAX_VALUE
        var minB = Double.MAX_VALUE
        var dd = cc + 8; if (dd > n - 1) dd = n
        cc -= 8; if (cc < 0) cc = 0
        for (j in cc until dd) {
            val d = d2(pivot, curList[j].p)
            if (d < minA) { minB = minA; b = a; minA = d; a = j } else if (d < minB) { minB = d; b = j }
        }
        if (a > b) { val c = a; a = b; b = c }
        current = a
        if (a > n - 1 || b > n - 1) return null
        val dx = curList[b].p.x - curList[a].p.x
        val dz = curList[b].p.y - curList[a].p.y
        if (abs(dx) < 1e-300 && abs(dz) < 1e-300) return null
        var dist = ((dz * pivot.x) - (dx * pivot.y) + (curList[b].p.x * curList[a].p.y) - (curList[b].p.y * curList[a].p.x)) / sqrt(dz * dz + dx * dx)
        if (p.purePursuitIntegral != 0.0 && !reverse) {
            pde = dist * 0.2 + pde * 0.8
            if (counter2++ > 4) { deriv = pde - pdeLast; pdeLast = pde; counter2 = 0; deriv *= 2 }
            if (autosteerOn && avgSpeed > 2.5 && abs(deriv) < 0.1) {
                if ((inty < 0 && dist < 0) || (inty > 0 && dist > 0)) inty += pde * p.purePursuitIntegral * -0.04
                else if (abs(dist) > 0.02) { inty += pde * p.purePursuitIntegral * -0.02; inty = inty.coerceIn(-0.2, 0.2) }
            } else inty *= 0.95
        } else inty = 0.0
        val u = (((pivot.x - curList[a].p.x) * dx) + ((pivot.y - curList[a].p.y) * dz)) / ((dx * dx) + (dz * dz))
        var start = Vec2(curList[a].p.x + u * dx, curList[a].p.y + u * dz)
        val count = if (revHeading) 1 else -1
        var soFar = 0.0
        var goal = start
        var i = if (revHeading) b else a
        var guard = 0
        while (i in 0 until n && guard++ <= n) {
            val temp = sqrt(d2(start, curList[i].p))
            if (temp + soFar > goalDist) {
                val j = (goalDist - soFar) / temp
                goal = Vec2((1 - j) * start.x + j * curList[i].p.x, (1 - j) * start.y + j * curList[i].p.y)
                break
            } else soFar += temp
            start = curList[i].p
            goal = start
            i += count
            if (i < 0) i = n - 1
            if (i > n - 1) i = 0
        }
        // za końcem krzywej (przy włączonym autosterze, jazda naprzód)
        val pastEnd = autosteerOn && !reverse && (if (sameWay) d2(goal, curList[n - 1].p) < 0.25 else d2(goal, curList[0].p) < 0.25)
        val g2 = d2(goal, pivot)
        val localHeading = if (revHeading) 2 * PI - fixHeading + inty else 2 * PI - fixHeading - inty
        var steer = atan(2 * (((goal.x - pivot.x) * cos(localHeading)) + ((goal.y - pivot.y) * sin(localHeading))) * p.wheelbase / g2) * 180.0 / PI
        if (rollDeg != null) steer += rollDeg * -p.sideHillComp
        steer = steer.coerceIn(-p.maxSteerAngle, p.maxSteerAngle)
        if (!sameWay) dist *= -1.0
        steering.modeActualXte = dist
        return Result(steer, dist, goal, pastEnd)
    }

    private fun nearestGlobal(c: List<Pose>, q: Vec2, inc: Int): Int {
        var best = 0; var bd = Double.MAX_VALUE
        var i = 0
        while (i < c.size) { val d = d2(q, c[i].p); if (d < bd) { bd = d; best = i }; i += inc }
        return best
    }

    private fun nearestLocal(c: List<Pose>, q: Vec2, startIndex: Int, minSearch: Double, reverseDir: Boolean): Int {
        val n = c.size
        var minDist = d2(q, c[(startIndex + n) % n].p)
        var minIdx = startIndex
        val dir = if (reverseDir) 1 else -1
        var soFar = 0.0
        var start = c[startIndex].p
        var off = 1
        while (off < n) {
            val pi = (startIndex + off * dir + n * 2) % n
            val d = d2(q, c[pi].p)
            if (d < minDist) { minDist = d; minIdx = pi }
            soFar += sqrt(d2(start, c[pi].p))
            start = c[pi].p
            off++
            if (soFar > minSearch) break
        }
        while (off < n) {
            val pi = (startIndex + off * dir + n * 2) % n
            val d = d2(q, c[pi].p)
            if (d < minDist) { minDist = d; minIdx = pi } else break
            off++
        }
        soFar = 0.0
        start = c[startIndex].p
        off = 1
        while (off < n && soFar < 3.0) {
            val pi = (startIndex - off * dir + n * 2) % n
            soFar += sqrt(d2(start, c[pi].p))
            start = c[pi].p
            if (soFar >= 3.0) break
            val d = d2(q, c[pi].p)
            if (d < minDist * 0.8) { minDist = d; minIdx = pi } else break
            off++
        }
        return minIdx
    }

    private fun d2(u: Vec2, v: Vec2): Double { val x = u.x - v.x; val y = u.y - v.y; return x * x + y * y }
}
