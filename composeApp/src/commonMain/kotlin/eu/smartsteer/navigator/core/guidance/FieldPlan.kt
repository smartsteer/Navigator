package eu.smartsteer.navigator.core.guidance

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Plan jazdy po prostokątnym polu: przejazdy równoległe do linii AB (z południa na północ)
 * połączone zawrotami na uwrociach w jedną ciągłą ścieżkę.
 */
class FieldPlan(
    val widthM: Double,
    val lengthM: Double,
    val implementWidth: Double,
    requestedHeadland: Double,
    minTurnRadius: Double,
) {
    private val turnRadius = maxOf(minTurnRadius, implementWidth / 2)

    /** Jak daleko do przodu sięga zawrót od końca przejazdu, m. */
    val turnDepth: Double = if (implementWidth >= 2 * turnRadius) {
        turnRadius
    } else {
        val half = implementWidth / 2 + turnRadius
        sqrt(4 * turnRadius * turnRadius - half * half) + turnRadius
    }

    /** Uwrocie musi pomieścić zawrót (i maszynę za ciągnikiem), więc w razie potrzeby jest poszerzane. */
    val headland: Double = maxOf(requestedHeadland, turnDepth + 3.5)

    /** Granica pola (x = wschód, y = północ). */
    val boundary = listOf(Vec2(0.0, 0.0), Vec2(widthM, 0.0), Vec2(widthM, lengthM), Vec2(0.0, lengthM))

    /** Wnętrze pola bez uwroci — tu pracują sekcje. */
    val interior = listOf(
        Vec2(0.0, headland), Vec2(widthM, headland), Vec2(widthM, lengthM - headland), Vec2(0.0, lengthM - headland),
    )

    val passCount: Int = ceil(widthM / implementWidth).toInt()

    fun passX(k: Int) = implementWidth / 2 + k * implementWidth

    /** Zakres stacji ścieżki, w którym trwa przejazd k (od wjazdu w pole do zjazdu na uwrocie). */
    val passRanges: List<ClosedFloatingPointRange<Double>>
    val path: GuidancePath

    init {
        // zawroty kończą się 0,75 m przed granicą pola
        val yStart = 0.75 + turnDepth            // koniec przejazdu na uwrociu południowym
        val yEnd = lengthM - 0.75 - turnDepth    // koniec przejazdu na uwrociu północnym
        val pts = ArrayList<Vec2>()
        val ranges = ArrayList<ClosedFloatingPointRange<Double>>()
        var station = 0.0

        fun add(p: Vec2) {
            if (pts.isNotEmpty()) station += p.distanceTo(pts.last())
            pts.add(p)
        }

        for (k in 0 until passCount) {
            val north = k % 2 == 0
            val x = passX(k)
            val from = if (north) Vec2(x, yStart) else Vec2(x, yEnd)
            val to = if (north) Vec2(x, yEnd) else Vec2(x, yStart)
            add(from)
            // punkty co 5 m na prostej, żeby rzut i rysowanie były gładkie
            val n = maxOf(1, (from.distanceTo(to) / 5.0).toInt())
            var enter = 0.0
            for (i in 1..n) {
                add(from + (to - from) * (i.toDouble() / n))
                if (enter == 0.0 && (if (north) pts.last().y >= headland else pts.last().y <= lengthM - headland)) enter = station
            }
            val leaveY = if (north) lengthM - headland else headland
            ranges.add(enter..(station - kotlin.math.abs(to.y - leaveY)))
            if (k < passCount - 1) {
                val fwd = if (north) Vec2(0.0, 1.0) else Vec2(0.0, -1.0)
                val side = Vec2(1.0, 0.0) // kolejny przejazd jest zawsze na wschód
                turnPoints(implementWidth, turnRadius).forEach { (u, v) -> add(to + side * u + fwd * v) }
            }
        }
        path = GuidancePath(dedupe(pts))
        passRanges = ranges
    }

    /** Numer przejazdu dla stacji, albo -1 gdy ciągnik jest na zawrocie. */
    fun passAt(station: Double): Int = passRanges.indexOfFirst { station in it }

    fun isInInterior(p: Vec2) = p.x in 0.0..widthM && p.y in headland..(lengthM - headland)

    companion object {
        /**
         * Punkty zawrotu w układzie lokalnym (u = w bok do następnego przejazdu, v = do przodu),
         * od końca przejazdu (0,0) jadąc +v do początku następnego (w,0) jadąc −v.
         * Gdy w ≥ 2r: zwykłe "U" z prostym odcinkiem; gdy w < 2r: zawrót "gruszka" (bulb).
         */
        fun turnPoints(w: Double, r: Double, step: Double = 0.8): List<Pair<Double, Double>> {
            val out = ArrayList<Pair<Double, Double>>()
            fun arc(cu: Double, cv: Double, a0: Double, sweep: Double) {
                val n = maxOf(2, ceil(kotlin.math.abs(sweep) * r / step).toInt())
                for (i in 1..n) {
                    val a = a0 + sweep * i / n
                    out.add(Pair(cu + r * cos(a), cv + r * sin(a)))
                }
            }
            if (w >= 2 * r) {
                // ćwierćłuk w prawo, prosta, ćwierćłuk w prawo
                arc(r, 0.0, PI, -PI / 2)
                val straight = w - 2 * r
                val n = maxOf(1, (straight / step).toInt())
                for (i in 1..n) out.add(Pair(r + straight * i / n, r))
                arc(w - r, 0.0, PI / 2, -PI / 2)
            } else {
                // gruszka: łuk w lewo, duży łuk w prawo, łuk w lewo
                val half = w / 2 + r
                val y2 = sqrt(4 * r * r - half * half)
                val c2u = w / 2
                val aT1 = atan2(y2, half)
                arc(-r, 0.0, 0.0, aT1)
                val s2 = atan2(-y2, -half)
                val e2 = atan2(-y2, half)
                var sweep = s2 - e2
                while (sweep < 0) sweep += 2 * PI
                arc(c2u, y2, s2, -sweep)
                val s3 = atan2(y2, -half)
                arc(w + r, 0.0, s3, PI - s3)
            }
            return out
        }

        private fun dedupe(pts: List<Vec2>): List<Vec2> {
            val out = ArrayList<Vec2>(pts.size)
            for (p in pts) if (out.isEmpty() || out.last().distanceTo(p) > 1e-6) out.add(p)
            return out
        }
    }
}
