package eu.smartsteer.navigator.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Proste bryłowe modele maszyn gospodarstwa (kombajn, ciągnik z opryskiwaczem, siewnikiem albo inną maszyną) i własnego
 * ciągnika w widoku 3D. Każda część to graniastosłup: profil z boku (y do przodu, z w górę) wyciągnięty na szerokość
 * (oś x w prawo) — koła są walcami, maska ma pochylony przód, kabina słupki i dach z okapem. Ściany cieniowane od światła
 * z góry. Własne kształty — żadnych cudzych grafik.
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

    /**
     * Graniastosłup: [profile] — punkty (y, z) przeciwnie do zegara patrząc od prawej strony (+x), wypukły; wyciągnięty
     * od cx − w/2 do cx + w/2. [yaw] (stopnie) obraca część wokół (cx, cy) — skręcone koła; y profilu liczone od cy.
     */
    private class Part(val cx: Double, val cy: Double, val w: Double, val profile: List<Pair<Double, Double>>, val color: Color,
                       val yaw: Double = 0.0, val edges: Boolean = true)

    private val TYRE = Color(0xFF26292B)
    private val RIM = Color(0xFFB9BEC2)
    private val GLASS = Color(0xFF4E6A7C)
    private val STEEL = Color(0xFF8A9096)
    private val DARK = Color(0xFF3A3F3C)

    private fun shade(c: Color, f: Float) = c.copy(red = (c.red * f).coerceIn(0f, 1f), green = (c.green * f).coerceIn(0f, 1f), blue = (c.blue * f).coerceIn(0f, 1f))

    private fun box(cx: Double, cy: Double, w: Double, l: Double, z0: Double, h: Double, c: Color) =
        Part(cx, 0.0, w, listOf(cy - l / 2 to z0, cy + l / 2 to z0, cy + l / 2 to z0 + h, cy - l / 2 to z0 + h), c)

    /** Wielokąt „zaokrąglony” wokół (y0, z0) — zbiornik, rura. */
    private fun round(cx: Double, y0: Double, z0: Double, ry: Double, rz: Double, w: Double, c: Color, n: Int = 12) =
        Part(cx, 0.0, w, (0 until n).map { val a = 2 * PI * it / n; y0 + ry * cos(a) to z0 + rz * sin(a) }, c)

    /** Łuk (błotnik) nad kołem o promieniu [r] i środku (0, [zc]) od kąta [a0] do [a1] (stopnie, 0 = do przodu), grubość [t]. */
    private fun arc(r: Double, zc: Double, t: Double, a0: Double, a1: Double, n: Int = 8): List<Pair<Double, Double>> {
        val outer = (0..n).map { val a = (a0 + (a1 - a0) * it / n) * PI / 180; (r + t) * cos(a) to zc + (r + t) * sin(a) }
        val inner = (0..n).map { val a = (a1 - (a1 - a0) * it / n) * PI / 180; r * cos(a) to zc + r * sin(a) }
        return outer + inner
    }

    /**
     * Koło: opona z bieżnikiem (co drugi występ odrobinę niżej), felga w [rim] i ciemna piasta po obu stronach;
     * [steer] — skręt w stopniach.
     */
    private fun wheel(cx: Double, cy: Double, r: Double, w: Double, steer: Double = 0.0, rim: Color = RIM): List<Part> {
        val n = 28
        val tyre = (0 until n).map { val a = 2 * PI * (it + 0.5) / n; val rr = if (it % 2 == 0) r else r * 0.955; rr * cos(a) to r + rr * sin(a) }
        fun disc(k: Double, m: Int) = (0 until m).map { val a = 2 * PI * it / m; r * k * cos(a) to r + r * k * sin(a) }
        return listOf(
            Part(cx - w / 2 - 0.03, cy, 0.03, disc(0.2, 8), DARK, steer, false),
            Part(cx - w / 2 - 0.01, cy, 0.02, disc(0.62, 14), rim, steer, false),
            Part(cx, cy, w, tyre, TYRE, steer, false),
            Part(cx + w / 2 + 0.01, cy, 0.02, disc(0.62, 14), rim, steer, false),
            Part(cx + w / 2 + 0.03, cy, 0.03, disc(0.2, 8), DARK, steer, false),
        )
    }

    private val LAMP = Color(0xFFF2F4F0)
    private val BEACON = Color(0xFFFF9A1F)
    private val STRIPE = Color(0xFFD8B04A)

    /**
     * Ciągnik: duże tylne koła i niewiele mniejsze przednie z felgami w kolorze maszyny, maska z czarną atrapą, światłami
     * i paskiem, obciążnik z przodu, kabina z czarnymi słupkami, dach w kolorze maszyny z kogutami i lampami, lusterka,
     * wydech przy prawym słupku, błotniki nad kołami.
     */
    private fun tractor(body: Color, wb: Double = 2.5, tr: Double = 1.9, steer: Double = 0.0): List<Part> {
        val rr = 0.95                       // promień tylnego koła
        val fr = 0.7                        // przedniego
        val fx = tr / 2 - 0.05              // rozstaw przednich kół
        val dark = shade(body, 0.7f)
        val roofY0 = -0.85; val roofY1 = 1.15
        val list = ArrayList<Part>()
        list += wheel(-tr / 2, 0.0, rr, 0.62, 0.0, body) + wheel(tr / 2, 0.0, rr, 0.62, 0.0, body)
        list += wheel(-fx, wb, fr, 0.5, steer, body) + wheel(fx, wb, fr, 0.5, steer, body)
        // błotniki: tylne stałe, przednie skręcają z kołem
        for (sx in listOf(-1.0, 1.0)) {
            list += Part(sx * tr / 2, 0.0, 0.7, arc(rr + 0.06, rr, 0.07, 15.0, 160.0), body, 0.0, true)
            list += Part(sx * fx, wb, 0.56, arc(fr + 0.06, fr, 0.06, 20.0, 150.0, 6), body, steer, true)
        }
        list += listOf(
            box(0.0, 0.2, tr - 0.8, 1.6, 0.5, 0.75, DARK),                                                     // skrzynia i most
            box(0.0, wb * 0.55, 0.7, wb * 0.9, 0.55, 0.45, DARK),                                               // rama pod maską
            // maska: od kabiny do przodu, przód zaokrąglony w dół
            Part(0.0, 0.0, 1.02, listOf(0.7 to 0.95, wb + 0.75 to 0.95, wb + 0.98 to 1.15, wb + 0.95 to 1.5, wb + 0.7 to 1.68, 0.7 to 1.82), body),
            Part(0.0, 0.0, 1.05, listOf(0.75 to 1.5, wb + 0.85 to 1.42, wb + 0.85 to 1.47, 0.75 to 1.55), STRIPE, edges = false), // pasek
            // czarna atrapa z przodu i światła po bokach atrapy
            Part(0.0, 0.0, 0.86, listOf(wb + 0.72 to 0.75, wb + 1.0 to 0.75, wb + 1.03 to 1.12, wb + 1.0 to 1.4, wb + 0.72 to 1.4), Color(0xFF15181A)),
            box(-0.4, wb + 1.0, 0.16, 0.06, 1.28, 0.1, LAMP), box(0.4, wb + 1.0, 0.16, 0.06, 1.28, 0.1, LAMP),
            // obciążnik
            Part(0.0, 0.0, 1.05, listOf(wb + 0.95 to 0.45, wb + 1.55 to 0.45, wb + 1.6 to 0.6, wb + 1.55 to 1.0, wb + 0.95 to 1.0), Color(0xFF2B2F31)),
            // kabina: szyby i czarne słupki w narożnikach
            Part(0.0, 0.0, 1.55, listOf(-0.75 to 1.15, 0.95 to 1.15, 1.08 to 2.7, -0.72 to 2.72), GLASS.copy(alpha = 0.82f), edges = false),
        )
        for (sx in listOf(-1.0, 1.0)) for ((y0, y1) in listOf(-0.78 to -0.66, 0.94 to 1.1)) {
            list += Part(sx * 0.76, 0.0, 0.09, listOf(y0 to 1.1, y1 to 1.1, y1 + (if (y0 > 0) 0.13 else 0.0) to 2.72, y0 + (if (y0 > 0) 0.13 else 0.0) to 2.72), Color(0xFF111416))
        }
        list += listOf(
            box(0.0, 0.1, 1.62, 1.8, 1.1, 0.12, Color(0xFF111416)),                                            // listwa pod szybami
            // dach w kolorze maszyny z okapem, czarny pas lamp z przodu
            Part(0.0, 0.0, 1.78, listOf(roofY0 to 2.7, roofY1 to 2.68, roofY1 + 0.08 to 2.82, roofY1 to 2.95, roofY0 + 0.05 to 2.98), body),
            box(0.0, roofY1 + 0.02, 1.5, 0.1, 2.72, 0.09, Color(0xFF111416)),
            box(-0.55, roofY1 + 0.06, 0.12, 0.04, 2.74, 0.06, LAMP), box(0.55, roofY1 + 0.06, 0.12, 0.04, 2.74, 0.06, LAMP),
            round(-0.62, 0.9, 3.05, 0.07, 0.08, 0.12, BEACON, 8), round(0.62, 0.9, 3.05, 0.07, 0.08, 0.12, BEACON, 8),        // koguty
            // lusterka na wysięgnikach
            box(-0.98, 1.0, 0.42, 0.05, 2.35, 0.04, Color(0xFF111416)), box(0.98, 1.0, 0.42, 0.05, 2.35, 0.04, Color(0xFF111416)),
            box(-1.22, 1.0, 0.06, 0.16, 2.1, 0.32, Color(0xFF111416)), box(1.22, 1.0, 0.06, 0.16, 2.1, 0.32, Color(0xFF111416)),
            // wydech przy prawym przednim słupku
            round(0.62, 1.32, 2.25, 0.07, 0.75, 0.13, Color(0xFF1C1F21), 8),
            // stopnie do kabiny
            box(-0.82, 0.35, 0.18, 0.5, 0.45, 0.6, DARK),
            box(0.0, -0.95, 0.5, 0.25, 0.55, 0.4, dark),                                                         // tylny TUZ
        )
        return list
    }

    private fun parts(kind: Kind, body: Color, width: Double): List<Part> {
        val w = if (width > 0.5) width else 3.0
        return when (kind) {
            Kind.COMBINE -> {
                val hw = if (width > 0.5) width else 6.0
                val dark = shade(body, 0.82f)
                wheel(-1.6, 2.0, 0.95, 0.75) + wheel(1.6, 2.0, 0.95, 0.75) + wheel(-1.35, -2.3, 0.6, 0.45) + wheel(1.35, -2.3, 0.6, 0.45) + listOf(
                    Part(0.0, 0.0, hw, listOf(4.0 to 0.3, 5.4 to 0.3, 5.45 to 0.5, 4.35 to 1.25, 4.0 to 1.25), Color(0xFFD9A930)), // heder
                    Part(0.0, 0.0, 1.3, listOf(2.9 to 0.75, 4.15 to 0.75, 4.15 to 1.45, 2.9 to 2.1), dark),           // przenośnik pochyły
                    Part(0.0, 0.0, 2.9, listOf(-3.0 to 0.95, 2.8 to 0.95, 2.8 to 3.4, -2.3 to 3.4, -3.0 to 2.6), body), // korpus
                    Part(0.0, 0.0, 2.6, listOf(-1.5 to 3.4, 1.2 to 3.4, 1.45 to 4.3, -1.75 to 4.3), dark),           // zbiornik ziarna
                    Part(0.0, 0.0, 1.8, listOf(1.6 to 3.4, 3.25 to 3.4, 3.55 to 4.8, 1.6 to 4.9), GLASS),            // kabina
                    Part(0.0, 0.0, 2.0, listOf(1.45 to 4.85, 3.7 to 4.78, 3.65 to 5.0, 1.45 to 5.06), shade(body, 0.92f)),
                    round(-2.9, 0.6, 3.9, 0.17, 0.17, 3.4, STEEL, 8),                                                   // rura wyładowcza
                )
            }
            Kind.SPRAYER -> tractor(body) + listOf(
                round(0.0, -3.4, 1.45, 1.15, 0.8, 1.7, Color(0xFFE6E8E6)),                                       // zbiornik
                box(0.0, -3.4, 1.9, 2.5, 0.45, 0.25, DARK),                                                       // rama
                box(0.0, -4.85, w, 0.18, 1.1, 0.16, STEEL),                                                       // belka
                box(0.0, -4.85, 0.5, 0.5, 0.55, 0.8, DARK),
            )
            Kind.SEEDER -> tractor(body) + listOf(
                box(0.0, -3.0, w, 1.6, 0.25, 0.75, Color(0xFF3B6FA8)),
                Part(0.0, 0.0, minOf(w, 2.6), listOf(-3.6 to 1.0, -2.4 to 1.0, -2.25 to 1.95, -3.75 to 1.95), Color(0xFF2E5A8C)), // skrzynia nasienna
            )
            Kind.TRACTOR -> tractor(body) + if (width > 0.5) listOf(box(0.0, -2.6, width, 0.9, 0.2, 0.8, Color(0xFFB0662A))) else emptyList()
        }
    }

    /**
     * [proj] — punkt świata na wysokości z (m) na ekran razem z głębokością (dalej = więcej), null za kamerą.
     * [tilt] — widok 3D; z góry widać tylko wierzch brył.
     */
    fun DrawScope.drawMachine(
        proj: (Vec2, Double) -> Pair<Float, Offset>?, pos: Vec2, heading: Double, kind: Kind, body: Color, width: Double,
        tilt: Boolean, outline: Color,
    ) = drawParts(proj, pos, heading, parts(kind, body, width), outline)

    /** Własny ciągnik jako bryła: rozstaw osi i kół z profilu, przednie koła skręcają o [steerDeg]. */
    fun DrawScope.drawOwnTractor(
        proj: (Vec2, Double) -> Pair<Float, Offset>?, pivot: Vec2, heading: Double, body: Color, wheelbase: Double, track: Double,
        steerDeg: Double, outline: Color,
    ) = drawParts(proj, pivot, heading, tractor(body, wheelbase.coerceIn(1.5, 5.0), track.coerceIn(1.0, 3.2), steerDeg), outline)

    /** Ikona „strzałka” w 3D: klin grubości 0,5 m nad ziemią. */
    fun DrawScope.drawOwnArrow(proj: (Vec2, Double) -> Pair<Float, Offset>?, pivot: Vec2, heading: Double, body: Color, outline: Color) {
        val f = Vec2.forward(heading); val r = Vec2.right(heading)
        val pts = listOf(Vec2(0.0, 3.2), Vec2(-1.1, -0.6), Vec2(0.0, 0.1), Vec2(1.1, -0.6)).map { pivot + r * it.x + f * it.y }
        val lo = pts.map { proj(it, 0.15)?.second ?: return }
        val hi = pts.map { proj(it, 0.65)?.second ?: return }
        val sides = pts.indices.map { i -> val j = (i + 1) % pts.size; Triple(i, j, proj((pts[i] + pts[j]) * 0.5, 0.4)?.first ?: 0f) }.sortedByDescending { it.third }
        for ((i, j, _) in sides) face(listOf(lo[i], lo[j], hi[j], hi[i]), shade(body, 0.6f + 0.15f * (i % 2)), outline, false)
        face(hi, body, outline, true)
    }

    /** Rysuje ścianę, gdy jest zwrócona do kamery (na ekranie ten sam obieg co dach widziany z góry). */
    private fun DrawScope.face(pts: List<Offset>, c: Color, outline: Color, edge: Boolean): Boolean {
        var area = 0f
        for (i in pts.indices) { val p = pts[i]; val q = pts[(i + 1) % pts.size]; area += p.x * q.y - q.x * p.y }
        if (area >= -0.5f) return false
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y); close() }
        drawPath(path, c)
        if (edge) drawPath(path, outline.copy(alpha = 0.22f), style = Stroke(width = 1f))
        return true
    }

    // światło z góry, trochę z lewej i z przodu ekranu — w układzie świata (x, y, z)
    private val LIGHT = doubleArrayOf(-0.35, -0.3, 0.89)

    private fun DrawScope.drawParts(proj: (Vec2, Double) -> Pair<Float, Offset>?, pos: Vec2, heading: Double, parts: List<Part>, outline: Color) {
        val sorted = parts.map { p ->
            val my = p.profile.sumOf { it.first } / p.profile.size
            val mz = p.profile.sumOf { it.second } / p.profile.size
            val h = heading + p.yaw * PI / 180
            val c = pos + Vec2.right(heading) * p.cx + Vec2.forward(heading) * p.cy
            p to (proj(c + Vec2.forward(h) * my, mz)?.first ?: Float.MAX_VALUE)
        }.sortedByDescending { it.second }.map { it.first }
        for (p in sorted) {
            val h = heading + p.yaw * PI / 180
            val f = Vec2.forward(h); val r = Vec2.right(h)
            val c = pos + Vec2.right(heading) * p.cx + Vec2.forward(heading) * p.cy
            val n = p.profile.size
            fun world(side: Double, i: Int) = c + r * side + f * p.profile[i].first
            val left = (0 until n).map { world(-p.w / 2, it) }
            val right = (0 until n).map { world(p.w / 2, it) }
            val lp = (0 until n).map { proj(left[it], p.profile[it].second)?.second }
            val rp = (0 until n).map { proj(right[it], p.profile[it].second)?.second }
            if (lp.any { it == null } || rp.any { it == null }) continue
            val l = lp.map { it!! }; val rr = rp.map { it!! }
            fun lit(nx: Double, ny: Double, nz: Double): Color {
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9)
                val d = (nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / len
                return shade(p.color, (0.58 + 0.42 * d.coerceAtLeast(0.0)).toFloat())
            }
            // boki pasami (normalna na zewnątrz profilu), potem ściany boczne (±x)
            for (i in 0 until n) {
                val j = (i + 1) % n
                val dy = p.profile[j].first - p.profile[i].first
                val dz = p.profile[j].second - p.profile[i].second
                // normalna w układzie części: (0, dz, −dy) → świat
                val nw = f * dz
                face(listOf(l[i], l[j], rr[j], rr[i]), lit(nw.x, nw.y, -dy), outline, false)
            }
            face(rr, lit(r.x, r.y, 0.0), outline, p.edges)
            face(l.reversed(), lit(-r.x, -r.y, 0.0), outline, p.edges)
        }
    }
}
