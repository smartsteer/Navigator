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
 * Prowadzenie po prostej linii (odcinek A→B skierowany zgodnie z kierunkiem jazdy):
 * pure pursuit z członem całkującym oraz Stanley — wierne przeniesienie z AgOpenGPS.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki
 * SourceCode/GPS/Classes/CABLine.cs (pure pursuit z całkowaniem, GetCurrentABLine),
 * SourceCode/GPS/Classes/CGuidance.cs (Stanley: StanleyGuidanceABLine, DoSteerAngleCalc),
 * SourceCode/GPS/Classes/CVehicle.cs (UpdateGoalPointDistance), SourceCode/GPS/Classes/CABCurve.cs (GetCurrentCurveLine).
 *
 * Konwencje jak w AgOpenGPS: kurs 0 = północ, zgodnie z zegarem; odchyłka + = na prawo od linii;
 * kąt skrętu + = w prawo. Wywoływać przy każdej nowej pozycji (ok. 10 Hz).
 */
class AbSteering {
    data class Output(
        /** Kąt zadany kół, °. */
        val steerAngleDeg: Double,
        /** Odchyłka osi tylnej od linii, m (+ = na prawo). */
        val distanceFromLine: Double,
        /** Punkt docelowy (pure pursuit) albo rzut osi przedniej (Stanley) — do rysowania. */
        val goal: Vec2,
    )

    // --- stan pure pursuit ---
    private var inty = 0.0
    private var pivotDistanceError = 0.0
    private var pivotDistanceErrorLast = 0.0
    private var pivotDerivative = 0.0
    private var counter2 = 0
    /** Ostatnia odchyłka (modeActualXTE) — wpływa na wyprzedzenie. */
    private var modeActualXte = 0.0

    // --- stan Stanleya ---
    private var stInty = 0.0
    private var xTrackSteerCorrection = 0.0
    private var distSteerError = 0.0
    private var lastDistSteerError = 0.0
    private var derivativeDistError = 0.0
    private var stPivotDistanceError = 0.0
    private var counter = 0

    /** Wartość członu całkującego (do podglądu), m. */
    val integral: Double get() = inty + stInty

    fun reset() {
        inty = 0.0; pivotDistanceError = 0.0; pivotDistanceErrorLast = 0.0; pivotDerivative = 0.0; counter2 = 0
        stInty = 0.0; xTrackSteerCorrection = 0.0; distSteerError = 0.0; lastDistSteerError = 0.0
        derivativeDistError = 0.0; stPivotDistanceError = 0.0; counter = 0
        modeActualXte = 0.0
    }

    /**
     * @param a,b odcinek linii w kierunku jazdy
     * @param pivot środek tylnej osi
     * @param heading kurs ciągnika, rad
     * @param speedKmh prędkość, km/h
     * @param engaged czy autoster jest włączony (całkowanie tylko wtedy)
     * @param rollDeg przechył z IMU (null = brak)
     */
    fun compute(
        a: Vec2, b: Vec2, pivot: Vec2, heading: Double, speedKmh: Double,
        engaged: Boolean, p: VehicleProfile, rollDeg: Double? = null,
    ): Output = when (p.algorithm) {
        SteerAlgorithm.PURE_PURSUIT -> purePursuit(a, b, pivot, heading, speedKmh, engaged, p, rollDeg)
        SteerAlgorithm.STANLEY -> stanley(a, b, pivot, heading, speedKmh, engaged, p, rollDeg)
    }

    /** Wynik prowadzenia po krzywej: także indeks odcinka (podpowiedź na następny krok) i koniec ścieżki. */
    data class PathOutput(val out: Output, val index: Int, val pastEnd: Boolean)

    /**
     * Prowadzenie po łamanej skierowanej zgodnie z jazdą (krzywa AB, kontur).
     * Pure pursuit: punkt docelowy szukany wzdłuż łamanej (CABCurve.GetCurrentCurveLine).
     * Stanley: odcinek najbliższy przedniej osi traktowany jak lokalna linia AB.
     */
    fun computePath(
        pts: List<Pose>, hint: Int, pivot: Vec2, heading: Double, speedKmh: Double,
        engaged: Boolean, p: VehicleProfile, rollDeg: Double? = null,
    ): PathOutput? {
        if (pts.size < 2) return null
        val steerPt = pivot + Vec2(sin(heading), cos(heading)) * p.wheelbase
        val ref = if (p.algorithm == SteerAlgorithm.STANLEY) steerPt else pivot
        val i = nearestSegment(pts, ref, hint)
        val a = pts[i].p
        val b = pts[i + 1].p
        if (a.distanceTo(b) < 1e-9) return null
        var goalAtEnd = false
        val out = when (p.algorithm) {
            SteerAlgorithm.STANLEY -> stanley(a, b, pivot, heading, speedKmh, engaged, p, rollDeg)
            SteerAlgorithm.PURE_PURSUIT -> purePursuit(a, b, pivot, heading, speedKmh, engaged, p, rollDeg) { start, dist ->
                var from = start
                var soFar = 0.0
                var k = i + 1
                var goal: Vec2? = null
                while (k < pts.size) {
                    val d = from.distanceTo(pts[k].p)
                    if (d + soFar > dist) {
                        val j = (dist - soFar) / d
                        goal = from * (1 - j) + pts[k].p * j
                        break
                    }
                    soFar += d
                    from = pts[k].p
                    k++
                }
                goal ?: pts.last().p.also { goalAtEnd = true }
            }
        }
        val pastEnd = goalAtEnd || out.goal.distanceTo(pts.last().p) < 0.5
        return PathOutput(out, i, pastEnd)
    }

    /** Odcinek łamanej najbliższy punktowi; przy znanej podpowiedzi szukamy tylko w jej okolicy. */
    private fun nearestSegment(pts: List<Pose>, q: Vec2, hint: Int): Int {
        val from: Int
        val to: Int
        if (hint in 0 until pts.size - 1) {
            from = (hint - 15).coerceAtLeast(0)
            to = (hint + 15).coerceAtMost(pts.size - 2)
        } else {
            val c = Curves.nearestIndex(pts, q)
            from = (c - 10).coerceAtLeast(0)
            to = (c + 10).coerceAtMost(pts.size - 2)
        }
        var best = from
        var bestD = Double.MAX_VALUE
        for (k in from..to) {
            val a = pts[k].p
            val ab = pts[k + 1].p - a
            val len2 = ab dot ab
            val t = if (len2 < 1e-12) 0.0 else (((q - a) dot ab) / len2).coerceIn(0.0, 1.0)
            val d = Curves.d2(a + ab * t, q)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    // ---------------- pure pursuit (CABLine.cs) ----------------

    private fun purePursuit(
        a: Vec2, b: Vec2, pivot: Vec2, heading: Double, avgSpeed: Double,
        engaged: Boolean, p: VehicleProfile, rollDeg: Double?,
        /** Dla krzywej: punkt docelowy z przejścia wzdłuż ścieżki (rzut, odległość) → punkt. */
        walk: ((Vec2, Double) -> Vec2)? = null,
    ): Output {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val abHeading = atan2(dx, dy)

        val distanceFromCurrentLinePivot =
            ((dy * pivot.x) - (dx * pivot.y) + (b.x * a.y) - (b.y * a.x)) / sqrt((dy * dy) + (dx * dx))

        if (p.purePursuitIntegral != 0.0) {
            pivotDistanceError = distanceFromCurrentLinePivot * 0.2 + pivotDistanceError * 0.8
            if (counter2++ > 4) {
                pivotDerivative = pivotDistanceError - pivotDistanceErrorLast
                pivotDistanceErrorLast = pivotDistanceError
                counter2 = 0
                pivotDerivative *= 2
            }
            if (engaged && abs(pivotDerivative) < 0.1 && avgSpeed > 2.5) {
                // jedziemy przez linię w złą stronę — szybko zmniejszamy całkę
                if ((inty < 0 && distanceFromCurrentLinePivot < 0) || (inty > 0 && distanceFromCurrentLinePivot > 0)) {
                    inty += pivotDistanceError * p.purePursuitIntegral * -0.04
                } else if (abs(distanceFromCurrentLinePivot) > 0.02) {
                    inty += pivotDistanceError * p.purePursuitIntegral * -0.02
                    inty = inty.coerceIn(-0.2, 0.2)
                }
            } else {
                inty *= 0.95
            }
        } else {
            inty = 0.0
        }

        // punkt na linii najbliżej osi tylnej
        val u = (((pivot.x - a.x) * dx) + ((pivot.y - a.y) * dy)) / ((dx * dx) + (dy * dy))
        val rEast = a.x + u * dx
        val rNorth = a.y + u * dy

        val goalPointDistance = goalPointDistance(avgSpeed, p)
        val goal = walk?.invoke(Vec2(rEast, rNorth), goalPointDistance)
            ?: Vec2(rEast + sin(abHeading) * goalPointDistance, rNorth + cos(abHeading) * goalPointDistance)

        val dSquared = (goal.x - pivot.x) * (goal.x - pivot.x) + (goal.y - pivot.y) * (goal.y - pivot.y)
        val localHeading = 2 * PI - heading + inty
        var steerAngle = toDeg(
            atan(
                2 * (((goal.x - pivot.x) * cos(localHeading)) + ((goal.y - pivot.y) * sin(localHeading))) *
                    p.wheelbase / dSquared,
            ),
        )
        if (rollDeg != null) steerAngle += rollDeg * -p.sideHillComp
        steerAngle = steerAngle.coerceIn(-p.maxSteerAngle, p.maxSteerAngle)

        modeActualXte = distanceFromCurrentLinePivot
        return Output(steerAngle, distanceFromCurrentLinePivot, goal)
    }

    /** CVehicle.UpdateGoalPointDistance — wyprzedzenie zależne od prędkości i odchyłki. */
    internal fun goalPointDistance(avgSpeed: Double, p: VehicleProfile): Double {
        var xte = abs(modeActualXte)
        var goalPointDistance = avgSpeed * 0.05 * p.lookAheadMult
        var hold = p.lookAheadHold
        val acquire = p.lookAheadHold * p.acquireFactor
        if (xte <= 0.1) {
            goalPointDistance *= hold
            goalPointDistance += hold
        } else if (xte > 0.1 && xte < 0.4) {
            xte -= 0.1
            hold = (1 - (xte / 0.3)) * (hold - acquire)
            hold += acquire
            goalPointDistance *= hold
            goalPointDistance += hold
        } else {
            goalPointDistance *= acquire
            goalPointDistance += acquire
        }
        if (goalPointDistance < 2) goalPointDistance = 2.0
        return goalPointDistance
    }

    // ---------------- Stanley (CGuidance.cs) ----------------

    private fun stanley(
        a: Vec2, b: Vec2, pivot: Vec2, heading: Double, avgSpeed: Double,
        engaged: Boolean, p: VehicleProfile, rollDeg: Double?,
    ): Output {
        val steer = pivot + Vec2(sin(heading), cos(heading)) * p.wheelbase
        var dx = b.x - a.x
        var dy = b.y - a.y
        val lineHeading = atan2(dx, dy)

        val distanceFromCurrentLinePivot =
            ((dy * pivot.x) - (dx * pivot.y) + (b.x * a.y) - (b.y * a.x)) / sqrt((dy * dy) + (dx * dx))
        var u = (((pivot.x - a.x) * dx) + ((pivot.y - a.y) * dy)) / ((dx * dx) + (dy * dy))
        val rEastPivot = a.x + u * dx
        val rNorthPivot = a.y + u * dy

        // linia przesunięta o człon całkujący (w prawo o inty)
        val sideE = sin(lineHeading + PI / 2) * stInty
        val sideN = cos(lineHeading + PI / 2) * stInty
        val sa = Vec2(a.x + sideE, a.y + sideN)
        val sb = Vec2(b.x + sideE, b.y + sideN)
        dx = sb.x - sa.x
        dy = sb.y - sa.y
        val distanceFromCurrentLineSteer =
            ((dy * steer.x) - (dx * steer.y) + (sb.x * sa.y) - (sb.y * sa.x)) / sqrt((dy * dy) + (dx * dx))
        u = (((steer.x - sa.x) * dx) + ((steer.y - sa.y) * dy)) / ((dx * dx) + (dy * dy))
        val rEastSteer = sa.x + u * dx
        val rNorthSteer = sa.y + u * dy

        val steerErr = atan2(rEastSteer - rEastPivot, rNorthSteer - rNorthPivot)
        var steerHeadingError = heading - steerErr
        if (steerHeadingError > PI) steerHeadingError -= PI
        else if (steerHeadingError < -PI) steerHeadingError += PI
        if (steerHeadingError > PI / 2) steerHeadingError -= PI
        else if (steerHeadingError < -PI / 2) steerHeadingError += PI

        // --- DoSteerAngleCalc ---
        steerHeadingError *= p.stanleyHeadingGain
        var sped = abs(avgSpeed)
        sped = if (sped > 1) 1 + 0.277 * (sped - 1) else 1.0
        val xtec = atan((distanceFromCurrentLineSteer * p.stanleyDistanceGain) / sped)
        xTrackSteerCorrection = (xTrackSteerCorrection * 0.5) + xtec * 0.5

        distSteerError = (distSteerError * 0.95) + ((xTrackSteerCorrection * 60) * 0.05)
        if (counter++ > 5) {
            derivativeDistError = distSteerError - lastDistSteerError
            lastDistSteerError = distSteerError
            counter = 0
        }

        var steerAngle = toDeg((xTrackSteerCorrection + steerHeadingError) * -1.0)
        if (abs(distanceFromCurrentLineSteer) > 0.5) steerAngle *= 0.5
        else steerAngle *= (1 - abs(distanceFromCurrentLineSteer))

        stPivotDistanceError = (stPivotDistanceError * 0.6) + (distanceFromCurrentLinePivot * 0.4)
        if (avgSpeed > 1 && engaged && abs(derivativeDistError) < 1 && abs(stPivotDistanceError) < 0.25) {
            if ((stInty < 0 && distanceFromCurrentLinePivot < 0) || (stInty > 0 && distanceFromCurrentLinePivot > 0)) {
                stInty += stPivotDistanceError * p.stanleyIntegral * -0.03
            } else {
                stInty += stPivotDistanceError * p.stanleyIntegral * -0.01
            }
            if (p.stanleyIntegral == 0.0) stInty = 0.0
        } else {
            stInty *= 0.7
        }

        if (rollDeg != null) steerAngle += rollDeg * -p.sideHillComp
        steerAngle = steerAngle.coerceIn(-p.maxSteerAngle, p.maxSteerAngle)
        modeActualXte = distanceFromCurrentLinePivot
        return Output(steerAngle, distanceFromCurrentLinePivot, Vec2(rEastSteer, rNorthSteer))
    }

    private fun toDeg(r: Double) = r * 180.0 / PI
}
