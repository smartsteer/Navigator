package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure pursuit: wybieramy punkt na ścieżce w odległości "look-ahead" przed ciągnikiem
 * i liczymy taki kąt kół, żeby łuk o promieniu z modelu rowerowego przez niego przeszedł.
 *
 *   δ = atan( 2·L·sin(α) / ld )
 *
 * L – rozstaw osi, α – kąt między kierunkiem jazdy a kierunkiem na punkt, ld – odległość do punktu.
 * Kąt dodatni = skręt w prawo.
 */
class PurePursuit(
    var wheelbase: Double = 2.65,
    var maxSteerDeg: Double = 35.0,
    /** Czas wyprzedzenia: look-ahead = prędkość × czas, ale nie mniej niż [minLookahead]. */
    var lookaheadSeconds: Double = 1.8,
    var minLookahead: Double = 3.0,
) {
    data class Result(
        val steerDeg: Double,
        val goal: Vec2,
        val projection: GuidancePath.Projection,
    )

    /**
     * @param position punkt sterowania (środek tylnej osi), m
     * @param heading kurs ciągnika, rad
     * @param speed prędkość, m/s
     * @param lateralShift przesunięcie linii w prawo, m (przycisk "przesuń linię")
     */
    fun compute(
        path: GuidancePath,
        position: Vec2,
        heading: Double,
        speed: Double,
        stationHint: Double? = null,
        lateralShift: Double = 0.0,
    ): Result {
        val proj = path.project(position, stationHint)
        val ld = max(minLookahead, speed * lookaheadSeconds)
        val s = proj.station + ld
        val goal = path.pointAt(s) + Vec2.right(path.headingAt(s)) * lateralShift

        val d = goal - position
        val fwd = d dot Vec2.forward(heading)
        val right = d dot Vec2.right(heading)
        val alpha = atan2(right, fwd)
        val dist = max(0.1, d.length)
        var delta = atan(2.0 * wheelbase * sin(alpha) / dist)
        val maxRad = maxSteerDeg * PI / 180.0
        delta = delta.coerceIn(-maxRad, maxRad)

        return Result(
            steerDeg = delta * 180.0 / PI,
            goal = goal,
            projection = proj.copy(lateral = proj.lateral - lateralShift),
        )
    }
}
