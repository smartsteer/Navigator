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
            // pasek tylko na bokach maski (cienkie listwy tuż obok niej — nie zasłaniają wierzchu)
            Part(-0.52, 0.0, 0.02, listOf(0.75 to 1.5, wb + 0.85 to 1.42, wb + 0.85 to 1.47, 0.75 to 1.55), STRIPE, edges = false),
            Part(0.52, 0.0, 0.02, listOf(0.75 to 1.5, wb + 0.85 to 1.42, wb + 0.85 to 1.47, 0.75 to 1.55), STRIPE, edges = false),
            // czarna atrapa z przodu i światła po bokach atrapy
            Part(0.0, 0.0, 0.86, listOf(wb + 0.72 to 0.75, wb + 1.0 to 0.75, wb + 1.03 to 1.12, wb + 1.0 to 1.4, wb + 0.72 to 1.4), Color(0xFF15181A)),
            box(-0.4, wb + 1.0, 0.16, 0.06, 1.28, 0.1, LAMP), box(0.4, wb + 1.0, 0.16, 0.06, 1.28, 0.1, LAMP),
            // obciążnik
            Part(0.0, 0.0, 1.05, listOf(wb + 0.95 to 0.55, wb + 1.4 to 0.55, wb + 1.45 to 0.68, wb + 1.4 to 0.98, wb + 0.95 to 0.98), Color(0xFF2B2F31)),
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


    private val ACCENT = Color(0xFFE2BE2C)
    private val BLACK = Color(0xFF15181A)

    /** Rura wzdłuż maszyny (oś y): środek (cx, cy), długość [len], na wysokości [z]. */
    private fun tubeY(cx: Double, cy: Double, len: Double, z: Double, r: Double, c: Color, n: Int = 8) =
        Part(cx, cy, len, (0 until n).map { val a = 2 * PI * it / n; r * cos(a) to z + r * sin(a) }, c, 90.0, false)

    /** Rura w poprzek maszyny (oś x). */
    private fun tubeX(cx: Double, y: Double, len: Double, z: Double, r: Double, c: Color, n: Int = 8) =
        Part(cx, 0.0, len, (0 until n).map { val a = 2 * PI * it / n; y + r * cos(a) to z + r * sin(a) }, c, 0.0, false)

    /**
     * Kombajn zbożowy: heder z nagarniaczem (listwy na kole), ślimakiem i rozdzielaczami na końcach, przenośnik pochyły,
     * wysoki korpus z panelami bocznymi i paskiem, zbiornik ziarna z obrzeżem, rura wyładowcza złożona wzdłuż lewego boku,
     * przeszklona kabina z daszkiem, kogutem i barierką, drabinka, duże koła napędowe z przodu i małe skrętne z tyłu,
     * kratka chłodnicy i rozrzutnik słomy z tyłu.
     */
    private fun combine(body: Color, hw: Double): List<Part> {
        val dark = shade(body, 0.78f)
        val l = ArrayList<Part>()
        l += wheel(-1.5, 1.6, 0.92, 0.78, 0.0, ACCENT) + wheel(1.5, 1.6, 0.92, 0.78, 0.0, ACCENT)
        l += wheel(-1.3, -3.3, 0.6, 0.45, 0.0, ACCENT) + wheel(1.3, -3.3, 0.6, 0.45, 0.0, ACCENT)
        for (sx in listOf(-1.0, 1.0)) l += Part(sx * 1.5, 1.6, 0.86, arc(0.98, 0.92, 0.07, 10.0, 120.0, 7), body)   // błotniki
        l += listOf(
            // korpus: ścięte rogi z przodu i z tyłu
            Part(0.0, 0.0, 2.3, listOf(-4.0 to 0.95, 2.3 to 0.95, 2.6 to 1.3, 2.6 to 2.95, -3.5 to 3.15, -4.0 to 2.6), body),
            // panele boczne (szersze, odrobinę ciemniejsze) i pasek wzdłuż boku
            Part(0.0, 0.0, 2.75, listOf(-3.3 to 1.15, 0.6 to 1.15, 0.6 to 2.85, -3.3 to 2.95), shade(body, 0.92f)),
            Part(-1.39, 0.0, 0.02, listOf(-3.3 to 2.45, 0.6 to 2.45, 0.6 to 2.52, -3.3 to 2.52), ACCENT, edges = false),
            Part(1.39, 0.0, 0.02, listOf(-3.3 to 2.45, 0.6 to 2.45, 0.6 to 2.52, -3.3 to 2.52), ACCENT, edges = false),
            box(0.0, -2.0, 1.9, 2.6, 0.55, 0.45, BLACK),                                                          // podwozie
            // zbiornik ziarna z pochylonymi ścianami i ciemnym obrzeżem
            Part(0.0, 0.0, 2.85, listOf(-2.3 to 3.1, 0.6 to 3.1, 1.25 to 3.85, 1.15 to 4.1, -2.65 to 4.1, -2.75 to 3.85), body),
            Part(0.0, 0.0, 2.95, listOf(-2.7 to 4.05, 1.2 to 4.05, 1.2 to 4.18, -2.7 to 4.18), dark),
            // tył: kratka chłodnicy, wydech, rozrzutnik słomy
            Part(0.0, 0.0, 2.0, listOf(-3.85 to 3.05, -2.75 to 3.05, -2.75 to 3.45, -3.6 to 3.55), body),            // osłona silnika
            Part(0.0, 0.0, 2.04, listOf(-3.7 to 3.1, -2.95 to 3.1, -2.95 to 3.38, -3.55 to 3.45), BLACK),           // kratka (widać po bokach)
            Part(0.65, 0.0, 0.16, listOf(-2.98 to 3.3, -2.82 to 3.3, -2.82 to 4.05, -2.98 to 4.05), BLACK),         // wydech
            Part(0.0, 0.0, 1.9, listOf(-4.45 to 0.85, -3.95 to 0.85, -3.95 to 1.65, -4.3 to 1.55), BLACK),
            // rura wyładowcza złożona wzdłuż lewego boku, z wylotem z tyłu
            tubeY(-1.55, -0.9, 6.4, 3.75, 0.17, body, 10),
            Part(-1.55, -4.1, 0.34, listOf(-0.12 to 3.35, 0.12 to 3.35, 0.15 to 3.75, -0.15 to 3.75), dark, 90.0),
            // przenośnik pochyły do hedera
            Part(0.0, 0.0, 1.45, listOf(2.3 to 0.95, 3.85 to 0.55, 4.1 to 0.72, 4.1 to 1.6, 2.6 to 2.35), dark),
            // kabina: podest, przeszklenie (przód załamany), czarne słupki, daszek z lampami i kogutem
            box(0.0, 2.0, 2.5, 2.0, 2.85, 0.2, BLACK),
            Part(0.0, 0.0, 1.95, listOf(1.15 to 3.05, 2.65 to 3.05, 3.05 to 3.65, 3.05 to 4.35, 1.15 to 4.45), GLASS.copy(alpha = 0.82f), edges = false),
        )
        for (sx in listOf(-1.0, 1.0)) {
            l += Part(sx * 0.98, 0.0, 0.08, listOf(2.95 to 3.05, 3.1 to 3.05, 3.12 to 4.38, 2.98 to 4.38), BLACK)       // słupki przednie
            l += Part(sx * 0.98, 0.0, 0.08, listOf(1.12 to 3.05, 1.25 to 3.05, 1.25 to 4.45, 1.12 to 4.45), BLACK)       // tylne
            l += tubeY(sx * 1.24, 2.0, 1.9, 3.45, 0.025, STEEL, 6)                                                     // barierka
            l += Part(sx * 1.24, 0.0, 0.04, listOf(1.15 to 2.95, 1.2 to 2.95, 1.2 to 3.45, 1.15 to 3.45), STEEL)
            l += Part(sx * 1.24, 0.0, 0.04, listOf(2.85 to 2.95, 2.9 to 2.95, 2.9 to 3.45, 2.85 to 3.45), STEEL)
        }
        l += listOf(
            Part(0.0, 0.0, 2.15, listOf(1.0 to 4.4, 3.2 to 4.33, 3.28 to 4.5, 1.0 to 4.62), body),                        // daszek
            box(0.0, 3.22, 1.7, 0.08, 4.36, 0.1, BLACK),
            box(-0.55, 3.27, 0.14, 0.04, 4.38, 0.06, LAMP), box(0.55, 3.27, 0.14, 0.04, 4.38, 0.06, LAMP),
            round(-0.7, 1.4, 4.7, 0.07, 0.08, 0.12, BEACON, 8),
            // drabinka po lewej przed kołem
            Part(-1.3, 0.0, 0.06, listOf(2.45 to 0.5, 2.6 to 0.5, 2.15 to 2.9, 2.0 to 2.9), STEEL),
            Part(-1.3, 0.0, 0.5, listOf(2.2 to 1.3, 2.4 to 1.3, 2.38 to 1.36, 2.18 to 1.36), BLACK),
            Part(-1.3, 0.0, 0.5, listOf(2.08 to 2.0, 2.28 to 2.0, 2.26 to 2.06, 2.06 to 2.06), BLACK),
        )
        // heder: podłoga z listwą tnącą, tylna ściana, ślimak, ściany boczne z rozdzielaczami, nagarniacz
        l += listOf(
            Part(0.0, 0.0, hw, listOf(3.95 to 0.25, 5.15 to 0.22, 5.25 to 0.32, 3.95 to 0.45), dark),
            Part(0.0, 0.0, hw, listOf(3.8 to 0.3, 4.1 to 0.3, 4.15 to 1.6, 3.85 to 1.6), body),
            tubeX(0.0, 4.4, hw - 0.3, 0.68, 0.28, ACCENT, 10),
            box(0.0, 5.2, hw - 0.1, 0.08, 0.23, 0.05, STEEL),
        )
        for (sx in listOf(-1.0, 1.0)) {
            val ex = sx * (hw / 2 - 0.03)
            l += Part(ex, 0.0, 0.06, listOf(3.8 to 0.25, 5.15 to 0.25, 4.7 to 1.05, 3.85 to 1.65), body)              // ściana boczna
            l += Part(sx * (hw / 2 + 0.08), 0.0, 0.2, listOf(4.6 to 0.2, 6.0 to 0.25, 4.7 to 0.95), ACCENT)          // rozdzielacz
            l += Part(sx * (hw / 2 - 0.15), 0.0, 0.08, listOf(3.9 to 1.5, 4.05 to 1.45, 4.95 to 1.5, 4.9 to 1.62), BLACK) // ramię nagarniacza
        }
        // nagarniacz: listwy na kole Ø 1,1 m i tarcze na końcach
        val ry = 4.95; val rz = 1.5; val rr = 0.55
        for (k in 0 until 6) {
            val a = 2 * PI * k / 6 + 0.3
            l += tubeX(0.0, ry + rr * cos(a), hw - 0.45, rz + rr * sin(a), 0.035, ACCENT, 6)
        }
        // ramiona nagarniacza od wału do listew (szprychy) na obu końcach
        for (sx in listOf(-1.0, 1.0)) for (k in 0 until 6) {
            val a = 2 * PI * k / 6 + 0.3
            val ca = cos(a); val sa = sin(a); val t = 0.025
            l += Part(sx * (hw / 2 - 0.22), 0.0, 0.04, listOf(ry + sa * t to rz - ca * t, ry + rr * ca + sa * t to rz + rr * sa - ca * t, ry + rr * ca - sa * t to rz + rr * sa + ca * t, ry - sa * t to rz + ca * t), BLACK, 0.0, false)
        }
        l += tubeX(0.0, ry, hw - 0.4, rz, 0.06, BLACK, 6)                                                              // wał
        return l
    }

    /** Opryskiwacz zawieszany: zbiornik z pokrywą, rama, belka kratownicowa z rurą cieczy i rozpylaczami. */
    private fun sprayer(w: Double): List<Part> {
        val white = Color(0xFFE6E8E6)
        val l = ArrayList<Part>()
        l += listOf(
            box(0.0, -1.35, 0.7, 0.5, 0.55, 0.6, DARK),                                                                // zawieszenie
            box(0.0, -3.0, 1.9, 2.6, 0.45, 0.22, DARK),                                                                // rama
            Part(0.0, 0.0, 1.75, (0 until 14).map { val a = 2 * PI * it / 14; -3.0 + 1.2 * cos(a) to 1.5 + 0.82 * sin(a) }, white), // zbiornik
            Part(0.0, 0.0, 0.5, (0 until 10).map { k -> val a = 2 * PI * k / 10; -2.6 + 0.24 * cos(a) to 2.36 + 0.08 * sin(a) }, Color(0xFFF0B020)), // pokrywa wlewu
            box(0.0, -4.35, 1.2, 0.3, 0.6, 1.2, DARK),                                                                 // wieża belki
        )
        // belka: górna i dolna rura, ukośne pręty, rura cieczy i rozpylacze
        l += tubeX(0.0, -4.65, w, 1.55, 0.05, STEEL, 6)
        l += tubeX(0.0, -4.65, w, 0.95, 0.06, STEEL, 6)
        l += tubeX(0.0, -4.75, w, 0.85, 0.025, BLACK, 6)
        val n = (w / 1.5).toInt().coerceIn(2, 30)
        for (i in 0..n) {
            val x = -w / 2 + w * i / n
            l += Part(x, 0.0, 0.05, listOf(-4.68 to 0.95, -4.62 to 0.95, -4.62 to 1.55, -4.68 to 1.55), STEEL, 0.0, false)
        }
        return l
    }

    /** Inna maszyna za ciągnikiem (np. agregat uprawowy): rama, zęby i wał doprawiający. */
    private fun cultivator(w: Double): List<Part> {
        val c = Color(0xFFB0662A)
        val l = ArrayList<Part>()
        l += box(0.0, -1.35, 0.7, 0.5, 0.45, 0.6, DARK)
        l += box(0.0, -2.4, w, 0.14, 0.75, 0.14, c)
        l += box(0.0, -3.0, w, 0.14, 0.75, 0.14, c)
        l += box(0.0, -2.7, 0.3, 1.1, 0.8, 0.1, c)
        l += tubeX(0.0, -3.75, w, 0.28, 0.27, shade(c, 0.8f), 10)                                                    // wał
        val n = (w / 0.45).toInt().coerceIn(4, 30)
        for (i in 0 until n) {
            val x = -w / 2 + w * (i + 0.5) / n
            val y = if (i % 2 == 0) -2.4 else -3.0
            l += Part(x, 0.0, 0.04, listOf(y - 0.03 to 0.05, y + 0.1 to 0.05, y + 0.04 to 0.8, y - 0.03 to 0.8), STEEL, 0.0, false)
        }
        return l
    }

    /** Siewnik: skrzynia nasienna z pokrywą, belka z redlicami (talerze), zagarniacz. */
    private fun seeder(w: Double): List<Part> {
        val blue = Color(0xFF3B6FA8)
        val l = ArrayList<Part>()
        val hb = minOf(w, 3.0)
        l += listOf(
            box(0.0, -1.35, 0.7, 0.5, 0.45, 0.6, DARK),
            box(0.0, -2.9, w, 1.7, 0.45, 0.25, DARK),                                                                  // rama
            Part(0.0, 0.0, hb, listOf(-3.35 to 0.75, -2.45 to 0.75, -2.15 to 1.75, -3.65 to 1.75), blue),            // skrzynia
            Part(0.0, 0.0, hb + 0.06, listOf(-3.7 to 1.72, -2.1 to 1.72, -2.2 to 1.86, -3.6 to 1.86), shade(blue, 0.8f)), // pokrywa
            tubeX(0.0, -3.9, w, 0.4, 0.05, STEEL, 6),                                                                  // zagarniacz
        )
        // redlice talerzowe co ~0,25 m (najwyżej 24 widoczne)
        val n = (w / 0.25).toInt().coerceIn(4, 24)
        for (i in 0 until n) {
            val x = -w / 2 + w * (i + 0.5) / n
            l += Part(x, -3.3, 0.04, (0 until 10).map { k -> val a = 2 * PI * k / 10; 0.2 * cos(a) to 0.22 + 0.2 * sin(a) }, STEEL, 0.0, false)
        }
        return l
    }

    private fun parts(kind: Kind, body: Color, width: Double): List<Part> {
        val w = if (width > 0.5) width else 3.0
        return when (kind) {
            Kind.COMBINE -> combine(body, if (width > 0.5) width else 6.0)
            Kind.SPRAYER -> tractor(body) + sprayer(w)
            Kind.SEEDER -> tractor(body) + seeder(w)
            Kind.TRACTOR -> tractor(body) + if (width > 0.5) cultivator(width) else emptyList()
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
