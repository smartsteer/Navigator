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
     * [o] — punkt świata na ekran, [pos] — położenie maszyny, [heading] w radianach, [up] — kierunek „w głąb” ekranu
     * w układzie świata, [k] — jak mocno wysokość przesuwa w głąb (0 = widok z góry).
     */
    fun DrawScope.drawMachine(
        o: (Vec2) -> Offset, pos: Vec2, heading: Double, kind: Kind, body: Color, width: Double,
        up: Vec2, k: Double, outline: Color, px: Float,
    ) {
        val f = Vec2.forward(heading)
        val r = Vec2.right(heading)
        fun w(x: Double, y: Double, z: Double) = pos + r * x + f * y + up * (z * k)
        val list = boxes(kind, body, width).sortedWith(compareByDescending<Box> { ((pos + r * it.cx + f * it.cy) dot up) }.thenBy { it.z0 })
        for (b in list) {
            val xs = doubleArrayOf(b.cx - b.w / 2, b.cx + b.w / 2, b.cx + b.w / 2, b.cx - b.w / 2)
            val ys = doubleArrayOf(b.cy - b.l / 2, b.cy - b.l / 2, b.cy + b.l / 2, b.cy + b.l / 2)
            val z1 = b.z0 + b.h
            if (k > 0) {
                for (i in 0 until 4) {
                    val j = (i + 1) % 4
                    val a = pos + r * xs[i] + f * ys[i]
                    val c = pos + r * xs[j] + f * ys[j]
                    val e = c - a
                    // normalna na zewnątrz (wierzchołki idą przeciwnie do zegara patrząc z góry na układ maszyny)
                    val n = Vec2(e.y, -e.x)
                    val towardCam = -(n dot up)
                    if (towardCam <= 0) continue
                    val side = Path().apply {
                        val p0 = o(w(xs[i], ys[i], b.z0)); moveTo(p0.x, p0.y)
                        val p1 = o(w(xs[j], ys[j], b.z0)); lineTo(p1.x, p1.y)
                        val p2 = o(w(xs[j], ys[j], z1)); lineTo(p2.x, p2.y)
                        val p3 = o(w(xs[i], ys[i], z1)); lineTo(p3.x, p3.y)
                        close()
                    }
                    drawPath(side, shade(b.color, 0.62f + 0.18f * (i % 2)))
                }
            }
            val top = Path().apply {
                for (i in 0 until 4) { val p = o(w(xs[i], ys[i], z1)); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                close()
            }
            drawPath(top, b.color)
            drawPath(top, outline.copy(alpha = 0.35f), style = Stroke(width = 1f * px))
        }
    }
}
