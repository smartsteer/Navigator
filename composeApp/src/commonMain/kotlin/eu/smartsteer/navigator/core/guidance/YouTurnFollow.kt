package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prowadzenie po ścieżce zawrotu — wierne przeniesienie CYouTurn.DistanceFromYouTurnLine z AgOpenGPS
 * (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CYouTurn.cs: dwa najbliższe punkty od
 * najdalszego osiągniętego minus 5, Stanley (oś przednia) albo pure pursuit (oś tylna, bez całki), mnożnik
 * setAS_uTurnCompensation, koniec zawrotu dopiero po przejechaniu połowy ścieżki.
 * Kurs: 0 = północ, zgodnie z zegarem; Vec2.x = wschód, Vec2.y = północ.
 */
class YouTurnFollow {
    private var a = 0
    private var b = 0
    private var maxProgress = 0

    fun reset() { a = 0; b = 0; maxProgress = 0 }

    class Result(val steerAngleDeg: Double, val distanceFromLine: Double, val goal: Vec2)

    /** null = zawrót zakończony (CompleteYouTurn). [steering] — wyprzedzenie celu (UpdateGoalPointDistance) i modeActualXTE. */
    fun step(
        yt: List<Pose>, pivotAxle: Vec2, fixHeading: Double, avgSpeed: Double, reverse: Boolean,
        p: VehicleProfile, steering: AbSteering,
    ): Result? {
        val n = yt.size
        if (n == 0) return null
        var minA = 1_000_000.0
        var minB = 1_000_000.0
        val start0 = maxOf(0, maxProgress - 5)
        if (p.algorithm == SteerAlgorithm.STANLEY) {
            val pivot = pivotAxle + Vec2.forward(fixHeading) * p.wheelbase      // steerAxlePos
            for (t in start0 until n) {
                val d = d2(pivot, yt[t].p)
                if (d < minA) { minB = minA; b = a; minA = d; a = t } else if (d < minB) { minB = d; b = t }
            }
            if (minA > 16) return null
            if (a > b) { val x = a; a = b; b = x }
            if (a < 0) a = 0
            b = a + 1
            if (a > maxProgress) maxProgress = a
            val progressed = maxProgress > n / 2
            if (b >= n - 1 && progressed) return null
            if (b >= n) return null
            val dx = yt[b].p.x - yt[a].p.x
            val dz = yt[b].p.y - yt[a].p.y
            if (kotlin.math.abs(dx) < 1e-300 && kotlin.math.abs(dz) < 1e-300) return Result(0.0, 0.0, yt[a].p)
            val abHeading = yt[a].heading
            val dist = ((dz * pivot.x) - (dx * pivot.y) + (yt[b].p.x * yt[a].p.y) - (yt[b].p.y * yt[a].p.x)) / sqrt(dz * dz + dx * dx)
            val u = (((pivot.x - yt[a].p.x) * dx) + ((pivot.y - yt[a].p.y) * dz)) / ((dx * dx) + (dz * dz))
            val r = Vec2(yt[a].p.x + u * dx, yt[a].p.y + u * dz)
            var delta = fixHeading - abHeading
            // dokładnie jak w AgOpenGPS (łącznie z „else if (< PI)”)
            if (delta > PI) delta -= PI else if (delta < PI) delta += PI
            if (delta > PI / 2) delta -= PI else if (delta < -PI / 2) delta += PI
            if (reverse) delta *= -1
            delta *= p.stanleyHeadingGain
            delta = delta.coerceIn(-0.74, 0.74)
            var steer = atan((dist * p.stanleyDistanceGain) / ((avgSpeed * 0.277777) + 1))
            steer = steer.coerceIn(-0.74, 0.74)
            steer = ((steer + delta * p.uTurnCompensation) * -1.0) * 180.0 / PI
            steer = steer.coerceIn(-p.maxSteerAngle, p.maxSteerAngle)
            steering.modeActualXte = dist
            return Result(steer, dist, r)
        }

        val pivot = pivotAxle
        for (t in start0 until n) {
            val d = d2(pivot, yt[t].p)
            if (d < minA) { minB = minA; b = a; minA = d; a = t } else if (d < minB) { minB = d; b = t }
        }
        if (a > b) { val x = a; a = b; b = x }
        if (a > maxProgress) maxProgress = a
        val distancePiv = sqrt(d2(yt[a].p, pivot))
        val progressed = maxProgress > n / 2
        if (((a > 0 && distancePiv > 2) || (b >= n - 1)) && progressed) return null
        val dx = yt[b].p.x - yt[a].p.x
        val dz = yt[b].p.y - yt[a].p.y
        if (kotlin.math.abs(dx) < 1e-300 && kotlin.math.abs(dz) < 1e-300) return Result(0.0, 0.0, yt[a].p)
        val dist = ((dz * pivot.x) - (dx * pivot.y) + (yt[b].p.x * yt[a].p.y) - (yt[b].p.y * yt[a].p.x)) / sqrt(dz * dz + dx * dx)
        val u = (((pivot.x - yt[a].p.x) * dx) + ((pivot.y - yt[a].p.y) * dz)) / ((dx * dx) + (dz * dz))
        val rE = yt[a].p.x + u * dx
        val rN = yt[a].p.y + u * dz
        val goalDist = steering.goalPointDistance(avgSpeed, p)
        val forward = !reverse
        val count = if (forward) 1 else -1
        var start = Vec2(rE, rN)
        var soFar = 0.0
        var goal = start
        var i = if (forward) b else a
        var found = false
        while (i in 0 until n) {
            val temp = sqrt(d2(start, yt[i].p))
            if (temp + soFar > goalDist) {
                val j = (goalDist - soFar) / temp
                goal = Vec2((1 - j) * start.x + j * yt[i].p.x, (1 - j) * start.y + j * yt[i].p.y)
                found = true
                break
            } else soFar += temp
            start = yt[i].p
            if (i == n - 1) return null           // cel dalej niż koniec zawrotu
            i += count
        }
        if (!found) return null
        val d2g = d2(goal, pivot)
        val localHeading = 2 * PI - fixHeading
        var steer = (atan(2 * (((goal.x - pivot.x) * cos(localHeading)) + ((goal.y - pivot.y) * sin(localHeading))) * p.wheelbase / d2g)) * 180.0 / PI
        steer *= p.uTurnCompensation
        steer = steer.coerceIn(-p.maxSteerAngle, p.maxSteerAngle)
        // isHeadingSameWay = true w AgOpenGPS — znak odległości bez zmian
        steering.modeActualXte = dist
        return Result(steer, dist, goal)
    }

    private fun d2(u: Vec2, v: Vec2): Double { val x = u.x - v.x; val y = u.y - v.y; return x * x + y * y }
}
