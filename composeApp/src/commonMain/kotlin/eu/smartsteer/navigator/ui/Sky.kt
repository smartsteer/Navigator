package eu.smartsteer.navigator.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import eu.smartsteer.navigator.core.weather.Weather
import kotlin.math.abs
import kotlin.math.sin

/**
 * Niebo w widoku 3D: kolor z wysokości słońca (noc, świt/zmierzch, złota godzina, dzień), szarość z zachmurzenia,
 * mgła, deszcz, śnieg i burza z aktualnej pogody (Open-Meteo, ta sama co do ostrzeżeń o wietrze). Słońce i gwiazdy
 * liczone lokalnie (Sun.kt).
 */
class SkyLook(
    val top: Color, val horizon: Color,
    /** Mgła nad dalszą częścią pola, 0–1. */
    val fog: Float,
    /** Przyciemnienie pola nocą, 0–1. */
    val night: Float,
    val cloud: Float,
    val rain: Boolean, val snow: Boolean, val storm: Boolean,
    val sunElev: Double, val sunVisible: Boolean,
)

object Sky {
    private val NIGHT_TOP = Color(0xFF04060D); private val NIGHT_HOR = Color(0xFF101826)
    private val DUSK_TOP = Color(0xFF14254A); private val DUSK_HOR = Color(0xFFE9804A)
    private val GOLD_TOP = Color(0xFF3A74C4); private val GOLD_HOR = Color(0xFFF3C27E)
    private val DAY_TOP = Color(0xFF2C78D6); private val DAY_HOR = Color(0xFFAFD3F3)
    private val GREY_TOP = Color(0xFF6E7884); private val GREY_HOR = Color(0xFFB4BAC1)

    fun look(sunElevDeg: Double, w: Weather?): SkyLook {
        val e = sunElevDeg
        fun t(a: Double, b: Double) = ((e - a) / (b - a)).coerceIn(0.0, 1.0).toFloat()
        // kolejne pory: noc (< −12°), świt/zmierzch (−12…0°), złota godzina (0…10°), dzień (> 10°)
        var top: Color
        var hor: Color
        when {
            e < -12 -> { top = NIGHT_TOP; hor = NIGHT_HOR }
            e < -2 -> { val f = t(-12.0, -2.0); top = lerp(NIGHT_TOP, DUSK_TOP, f); hor = lerp(NIGHT_HOR, DUSK_HOR, f) }
            e < 6 -> { val f = t(-2.0, 6.0); top = lerp(DUSK_TOP, GOLD_TOP, f); hor = lerp(DUSK_HOR, GOLD_HOR, f) }
            else -> { val f = t(6.0, 20.0); top = lerp(GOLD_TOP, DAY_TOP, f); hor = lerp(GOLD_HOR, DAY_HOR, f) }
        }
        val cloud = ((w?.cloudPct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        val wet = w?.rain == true || w?.snow == true
        // zachmurzenie szarzy niebo (w dzień mocniej, nocą tylko trochę rozjaśnia)
        val dayF = t(-6.0, 4.0)
        val grey = (cloud * 0.85f + if (wet) 0.15f else 0f).coerceIn(0f, 1f) * (0.35f + 0.65f * dayF)
        val dim = 0.35f + 0.65f * dayF
        top = lerp(top, GREY_TOP.copy(red = GREY_TOP.red * dim, green = GREY_TOP.green * dim, blue = GREY_TOP.blue * dim), grey)
        hor = lerp(hor, GREY_HOR.copy(red = GREY_HOR.red * dim, green = GREY_HOR.green * dim, blue = GREY_HOR.blue * dim), grey)
        if (w?.storm == true) { top = lerp(top, Color(0xFF2B2F38), 0.5f); hor = lerp(hor, Color(0xFF555B66), 0.4f) }
        val fog = when {
            w?.fog == true -> 0.9f
            wet -> 0.45f
            else -> 0.18f + cloud * 0.2f
        }
        val night = (1f - t(-10.0, 0.0)) * 0.55f
        return SkyLook(top, hor, fog, night, cloud, w?.rain == true, w?.snow == true, w?.storm == true, e, cloud < 0.85f && w?.fog != true && e > -1)
    }

    /**
     * Rysuje niebo na całym obszarze. [sunRelDeg] — azymut słońca względem kierunku patrzenia (0 = na wprost),
     * [horizonY] — szacowana linia horyzontu, [timeS] — do ruchu deszczu i śniegu.
     */
    fun DrawScope.drawSky(l: SkyLook, sunRelDeg: Double, horizonY: Float, timeS: Double) {
        drawRect(Brush.verticalGradient(0f to l.top, 1f to l.horizon, startY = 0f, endY = horizonY.coerceAtLeast(1f)), size = Size(size.width, horizonY.coerceAtLeast(1f)))
        drawRect(l.horizon, topLeft = Offset(0f, horizonY), size = Size(size.width, (size.height - horizonY).coerceAtLeast(0f)))
        // gwiazdy (stałe położenia), gdy ciemno i niezbyt pochmurno
        if (l.sunElev < -8 && l.cloud < 0.7f) {
            val a = ((-8 - l.sunElev) / 6).coerceIn(0.0, 1.0).toFloat() * (1f - l.cloud)
            var seed = 12345L
            repeat(90) {
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val x = (seed % 10000) / 10000f * size.width
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val y = (seed % 10000) / 10000f * horizonY * 0.95f
                drawCircle(Color.White.copy(alpha = 0.35f * a + 0.4f * a * ((seed % 7) / 7f)), 1.1f + (seed % 3) * 0.4f, Offset(x, y))
            }
        }
        // słońce (albo księżycowa poświata nie — tylko słońce), gdy w polu widzenia ±70°
        if (l.sunVisible && abs(sunRelDeg) < 70) {
            val x = size.width / 2 + (sunRelDeg / 70.0).toFloat() * size.width / 2
            val y = horizonY - (l.sunElev.coerceAtMost(40.0) / 40.0).toFloat() * horizonY * 0.9f
            val warm = l.sunElev < 8
            val core = if (warm) Color(0xFFFFC36B) else Color(0xFFFFF6D8)
            val glow = (1f - l.cloud) * 0.55f
            drawCircle(Brush.radialGradient(listOf(core.copy(alpha = glow), core.copy(alpha = 0f)), Offset(x, y), size.minDimension * 0.22f), size.minDimension * 0.22f, Offset(x, y))
            drawCircle(core.copy(alpha = (1f - l.cloud * 0.8f).coerceIn(0.15f, 1f)), size.minDimension * 0.028f, Offset(x, y))
        }
        // chmury: miękkie, przesuwające się powoli smugi — tyle, ile zachmurzenia
        val n = (l.cloud * 9).toInt()
        for (i in 0 until n) {
            val sp = 4.0 + i * 1.7
            val x = (((i * 0.37 + timeS / (60.0 * sp)) % 1.0) * (size.width * 1.4) - size.width * 0.2).toFloat()
            val y = horizonY * (0.12f + 0.11f * (i % 6))
            val wpx = size.width * (0.22f + 0.05f * (i % 4))
            val c = lerp(Color.White, l.horizon, 0.45f).copy(alpha = 0.18f + 0.1f * l.cloud)
            drawOval(c, topLeft = Offset(x, y), size = Size(wpx, horizonY * 0.07f))
            drawOval(c, topLeft = Offset(x + wpx * 0.2f, y - horizonY * 0.03f), size = Size(wpx * 0.55f, horizonY * 0.07f))
        }
        // opad
        if (l.rain || l.snow) {
            var seed = 777L
            val count = if (l.storm) 140 else 90
            repeat(count) {
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val bx = (seed % 10000) / 10000f * size.width
                seed = (seed * 1103515245 + 12345) and 0x7fffffff
                val by = (seed % 10000) / 10000f
                if (l.snow) {
                    val y = ((by + timeS * 0.08) % 1.0).toFloat() * horizonY
                    val x = bx + sin(timeS * 1.3 + bx).toFloat() * 6f
                    drawCircle(Color.White.copy(alpha = 0.7f), 1.8f, Offset(x, y))
                } else {
                    val y = ((by + timeS * 0.9) % 1.0).toFloat() * horizonY
                    drawLine(Color(0xFFCFD8E3).copy(alpha = 0.45f), Offset(bx, y), Offset(bx - 4f, y + 14f), 1.2f)
                }
            }
        }
        // błysk burzy co kilkanaście sekund
        if (l.storm && (timeS % 13.0) < 0.12) drawRect(Color.White.copy(alpha = 0.35f), size = Size(size.width, horizonY))
    }
}
