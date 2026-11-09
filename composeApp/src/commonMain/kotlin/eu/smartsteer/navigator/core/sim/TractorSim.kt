package eu.smartsteer.navigator.core.sim

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.tan

/**
 * Prosty model ciągnika (model rowerowy) z opóźnieniem układu kierowniczego —
 * zastępuje prawdziwy GPS i płytkę, dopóki nie podłączymy sprzętu.
 */
class TractorSim(
    var position: Vec2,
    var heading: Double,
    var wheelbase: Double = 2.65,
    /** Maksymalna prędkość zmiany kąta kół, °/s (silnik na kierownicy / zawór). */
    var steerRateDegPerS: Double = 35.0,
    /** Stała czasowa opóźnienia układu kierowniczego, s. */
    var steerLagS: Double = 0.18,
) {
    var speedMs: Double = 0.0
    var actualSteerDeg: Double = 0.0
        private set

    private var lagged = 0.0

    fun step(dt: Double, commandedSteerDeg: Double) {
        // ograniczenie prędkości skrętu + opóźnienie pierwszego rzędu
        val diff = commandedSteerDeg - lagged
        val maxStep = steerRateDegPerS * dt
        lagged += if (abs(diff) > maxStep) maxStep * sign(diff) else diff
        val k = 1.0 - exp(-dt / steerLagS)
        actualSteerDeg += (lagged - actualSteerDeg) * k

        val delta = actualSteerDeg * PI / 180.0
        heading += speedMs / wheelbase * tan(delta) * dt
        heading = normalize(heading)
        position += Vec2.forward(heading) * (speedMs * dt)
    }

    companion object {
        fun normalize(a: Double): Double {
            var r = a
            while (r > PI) r -= 2 * PI
            while (r < -PI) r += 2 * PI
            return r
        }
    }
}
