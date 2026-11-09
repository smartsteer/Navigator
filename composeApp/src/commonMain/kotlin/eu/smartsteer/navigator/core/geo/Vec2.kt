package eu.smartsteer.navigator.core.geo

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Punkt / wektor na płaszczyźnie lokalnej w metrach.
 * x = wschód, y = północ.
 *
 * Kurs (heading) w radianach: 0 = północ, rośnie zgodnie z ruchem wskazówek zegara.
 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    val length: Double get() = hypot(x, y)
    fun distanceTo(o: Vec2) = hypot(x - o.x, y - o.y)

    companion object {
        val ZERO = Vec2(0.0, 0.0)

        /** Wektor jednostkowy w kierunku jazdy dla danego kursu. */
        fun forward(heading: Double) = Vec2(sin(heading), cos(heading))

        /** Wektor jednostkowy w prawo od kierunku jazdy. */
        fun right(heading: Double) = Vec2(cos(heading), -sin(heading))
    }
}
