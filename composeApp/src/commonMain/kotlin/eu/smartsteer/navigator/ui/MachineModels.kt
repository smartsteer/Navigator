package eu.smartsteer.navigator.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import eu.smartsteer.navigator.core.geo.Vec2

/**
 * Proste bryłowe modele innych maszyn gospodarstwa (kombajn, ciągnik z opryskiwaczem, siewnikiem albo inną maszyną).
 * Rysowane na pochylonej mapie: wysokość bryły przesuwa jej górę „w głąb” ekranu ([up] × wysokość × [k]), więc
 * w widoku 3D maszyna stoi, a z góry (k = 0) widać jej obrys. Własne kształty — żadnych cudzych grafik.
 */
object MachineModels {
    enum class Kind { COMBINE, SPRAYER, SEEDER, TRACTOR }

    /** Rodzaj z nazwy pojazdu i maszyny (WAR: „pojazd”, „narzedzie”). */
    fun kindOf(tractor: String, tool: String): Kind {
        val t = (tractor + " " + tool).lowercase()
        return when {
            listOf("kombajn", "combine", "harvester", "lexion", "tucano").any { it in t } -> Kind.COMBINE
            listOf("oprysk", "sprayer", "spray").any { it in t } -> Kind.SPRAYER
            listOf("siewnik", "siew", "seeder", "drill", "sadzark").any { it in t } -> Kind.SEEDER
            else -> Kind.TRACTOR
        }
    }

    /** Rodzaj z typu maszyny w Parku WAR; null, gdy typu nie ma (wtedy zgadujemy z nazwy). */
    fun kindOfType(type: String): Kind? {
        if (type.isBlank()) return null
        val parts = type.split('/')
        return when {
            "kombajn" in parts -> Kind.COMBINE
            "opryskiwacz" in parts -> Kind.SPRAYER
            "siewnik" in parts -> Kind.SEEDER
            else -> Kind.TRACTOR
        }
    }

    private class Box(val cx: Double, val cy: Double, val w: Double, val l: Double, val z0: Double, val h: Double, val color: Color)

    private val TYRE = Color(0xFF24282A)
    private val GLASS = Color(0xFF2A3843)
    private val STEEL = Color(0xFF8A9096)

    private fun shade(c: Color, f: Float) = c.copy(red = c.red * f, green = c.green * f, blue = c.blue * f)

    private fun boxes(kind: Kind, body: Color, width: Double): List<Box> {
        val roof = shade(body, 0.9f)
        val tractor = listOf(
            Box(-0.95, 0.0, 0.55, 1.7, 0.0, 1.7, TYRE), Box(0.95, 0.0, 0.55, 1.7, 0.0, 1.7, TYRE),
            Box(-0.85, 2.5, 0.4, 1.1, 0.0, 1.1, TYRE), Box(0.85, 2.5, 0.4, 1.1, 0.0, 1.1, TYRE),
            Box(0.0, 1.9, 0.9, 2.6, 0.6, 1.1, body),
            Box(0.0, 0.1, 1.5, 1.6, 0.8, 1.9, GLASS), Box(0.0, 0.1, 1.6, 1.7, 2.7, 0.14, roof),
        )
        val w = if (width > 0.5) width else 3.0
        return when (kind) {
            Kind.COMBINE -> {
                val hw = if (width > 0.5) width else 6.0
                listOf(
                    Box(-1.75, 2.0, 0.75, 1.8, 0.0, 1.8, TYRE), Box(1.75, 2.0, 0.75, 1.8, 0.0, 1.8, TYRE),
                    Box(-1.4, -2.3, 0.5, 1.1, 0.0, 1.1, TYRE), Box(1.4, -2.3, 0.5, 1.1, 0.0, 1.1, TYRE),
                    Box(0.0, 4.7, hw, 1.4, 0.3, 0.9, Color(0xFFD9A930)),          // heder
                    Box(0.0, 3.5, 1.4, 1.6, 0.6, 1.2, shade(body, 0.85f)),         // przenośnik pochyły
                    Box(0.0, 0.1, 3.0, 5.6, 0.9, 2.6, body),                        // korpus
                    Box(0.0, 0.0, 2.6, 2.6, 3.5, 0.9, shade(body, 0.8f)),           // zbiornik ziarna
                    Box(0.0, 2.5, 1.8, 1.6, 3.5, 1.4, GLASS), Box(0.0, 2.5, 1.9, 1.7, 4.9, 0.15, roof), // kabina
                    Box(-2.9, 0.6, 3.4, 0.35, 3.7, 0.3, STEEL),                    // rura wyładowcza
                )
            }
            Kind.SPRAYER -> tractor + listOf(
                Box(0.0, -3.4, 1.7, 2.4, 0.6, 1.6, Color(0xFFE6E8E6)),              // zbiornik
                Box(0.0, -4.9, w, 0.25, 1.1, 0.15, STEEL),                          // belka
            )
            Kind.SEEDER -> tractor + listOf(
                Box(0.0, -3.0, w, 1.6, 0.3, 1.0, Color(0xFF3B6FA8)),
                Box(0.0, -3.0, minOf(w, 2.6), 1.2, 1.3, 0.8, Color(0xFF2E5A8C)),   // skrzynia nasienna
            )
            Kind.TRACTOR -> tractor + if (width > 0.5) listOf(Box(0.0, -2.6, width, 0.9, 0.2, 0.8, Color(0xFFB0662A))) else emptyList()
        }
    }

    /**
     * [proj] — punkt świata na wysokości z (m) na ekran razem z głębokością (dalej = więcej), null za kamerą.
     * [tilt] — widok 3D: rysujemy też ściany brył (te zwrócone do kamery), od najdalszej bryły do najbliższej.
     */
    fun DrawScope.drawMachine(
        proj: (Vec2, Double) -> Pair<Float, Offset>?, pos: Vec2, heading: Double, kind: Kind, body: Color, width: Double,
        tilt: Boolean, outline: Color,
    ) {
        val f = Vec2.forward(heading)
        val r = Vec2.right(heading)
        fun at(x: Double, y: Double) = pos + r * x + f * y
        val list = boxes(kind, body, width)
            .map { it to (proj(at(it.cx, it.cy), it.z0 + it.h / 2)?.first ?: Float.MAX_VALUE) }
            .sortedByDescending { it.second }
            .map { it.first }
        for (b in list) {
            val xs = doubleArrayOf(b.cx - b.w / 2, b.cx + b.w / 2, b.cx + b.w / 2, b.cx - b.w / 2)
            val ys = doubleArrayOf(b.cy - b.l / 2, b.cy - b.l / 2, b.cy + b.l / 2, b.cy + b.l / 2)
            val z1 = b.z0 + b.h
            val lo = (0 until 4).map { proj(at(xs[it], ys[it]), b.z0)?.second }
            val hi = (0 until 4).map { proj(at(xs[it], ys[it]), z1)?.second }
            if (lo.any { it == null } || hi.any { it == null }) continue
            val l = lo.map { it!! }; val u = hi.map { it!! }
            fun face(pts: List<Offset>, c: Color, edge: Boolean) {
                // ściana widoczna, gdy na ekranie ma ten sam obieg co dach widziany z góry
                var area = 0f
                for (i in pts.indices) { val p = pts[i]; val q = pts[(i + 1) % pts.size]; area += p.x * q.y - q.x * p.y }
                if (area >= 0f) return
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close() }
                drawPath(path, c)
                if (edge) drawPath(path, outline.copy(alpha = 0.3f), style = Stroke(width = 1f))
            }
            if (tilt) for (i in 0 until 4) {
                val j = (i + 1) % 4
                face(listOf(l[i], l[j], u[j], u[i]), shade(b.color, 0.62f + 0.18f * (i % 2)), false)
            }
            face(u, b.color, true)
        }
    }
}
