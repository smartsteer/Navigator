package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.settings.VehicleProfile
import eu.smartsteer.navigator.core.settings.vehicleFiles
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.PI
import kotlin.math.max

/** Zamiana pliku PNG/JPG na obraz (Android: BitmapFactory, Windows: Skia). Null = to nie jest obraz. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** Okno wyboru obrazu (Android: galeria/pliki, Windows: okno „Otwórz”). Wynik: zawartość pliku albo null (anulowano). */
expect fun pickImageFile(onResult: (ByteArray?) -> Unit)

/**
 * Ikona ciągnika na mapie i na rysunku zestawu: strzałka, ciągnik z góry (koła skręcają razem z prawdziwymi)
 * albo własny obraz rolnika (PNG z przezroczystym tłem, widok z góry, przód u góry) — zapisany w profilu jako tekst base64.
 */
@OptIn(ExperimentalEncodingApi::class)
object TractorIcons {
    private const val FILE = "ikona_ciagnika.b64"
    const val MAX_BYTES = 3_000_000

    var custom by mutableStateOf<ImageBitmap?>(runCatching { vehicleFiles().read(FILE)?.let { decodeImage(Base64.decode(it.trim())) } }.getOrNull())
        private set

    /** Zapisuje własny obraz; zwraca błąd albo null. */
    fun setCustom(bytes: ByteArray): String? {
        if (bytes.size > MAX_BYTES) return "Obraz jest za duży (najwyżej 3 MB) — zmniejsz go, np. do 512 × 512 pikseli"
        val img = decodeImage(bytes) ?: return "To nie jest obraz PNG ani JPG"
        runCatching { vehicleFiles().write(FILE, Base64.encode(bytes)) }.onFailure { return "Nie udało się zapisać: ${it.message}" }
        custom = img
        DisplayPrefs.updateTractorIcon(2)
        return null
    }

    fun clearCustom() {
        runCatching { vehicleFiles().delete(FILE) }
        custom = null
        if (DisplayPrefs.tractorIcon == 2) DisplayPrefs.updateTractorIcon(1)
    }

    /** Kolory ciągnika do wyboru (pierwszy = czerwony z motywu). */
    val palette = listOf("", "#2E8B3E", "#1F5FBF", "#FF8C1A", "#F2C200", "#7A7F85", "#E8ECE9")

    fun bodyColor(): Color = TrailColors.argb(DisplayPrefs.tractorColor)?.let { Color(it) } ?: eu.smartsteer.navigator.ui.theme.Ss.Tractor
}

/**
 * Rysuje ciągnik w układzie świata (metry): [o] zamienia punkt świata na ekran, [pivot] = środek tylnej osi,
 * [heading] w radianach (0 = północ, zgodnie z zegarem), [steerDeg] = kąt kół (+ w prawo).
 * [icon]: 0 strzałka, 1 ciągnik, 2 własny obraz (gdy brak — ciągnik).
 */
fun DrawScope.drawTractorIcon(
    o: (Vec2) -> Offset, pivot: Vec2, heading: Double, p: VehicleProfile, steerDeg: Double,
    icon: Int = DisplayPrefs.tractorIcon, body: Color = TractorIcons.bodyColor(), outline: Color = Color.White, px: Float = 1f,
) {
    val fwd = Vec2.forward(heading)
    val rt = Vec2.right(heading)
    val wb = p.wheelbase.coerceIn(1.5, 5.0)
    val tr = p.trackWidth.coerceIn(1.0, 3.2)
    fun w(x: Double, y: Double) = pivot + rt * x + fwd * y
    val img = TractorIcons.custom
    val unit = (o(pivot + rt) - o(pivot)).getDistance()           // jednostek rysunku na metr
    fun lw(m: Double, minPx: Float) = max((m * unit).toFloat(), minPx * px)
    when {
        icon == 0 -> {
            val tri = Path().apply {
                moveTo(o(pivot + fwd * 3.2).x, o(pivot + fwd * 3.2).y)
                lineTo(o(pivot + rt * 1.1 - fwd * 0.6).x, o(pivot + rt * 1.1 - fwd * 0.6).y)
                lineTo(o(pivot - rt * 1.1 - fwd * 0.6).x, o(pivot - rt * 1.1 - fwd * 0.6).y)
                close()
            }
            drawPath(tri, body)
            drawPath(tri, outline, style = Stroke(width = 2f * px))
        }
        icon == 2 && img != null -> {
            // obraz: przód u góry, od 0,9 m za tylną osią do 1,1 m przed przednią osią. Macierz z wektorów osi —
            // działa i na mapie (oś Y odwrócona), i na rysunku zestawu
            val len = wb + 2.0
            val m = (len / img.height.coerceAtLeast(1)).toFloat()
            val o0 = o(pivot)
            val ex = o(pivot + rt) - o0
            val ey = o(pivot - fwd) - o0
            val tl = o(w(-len * img.width / img.height.coerceAtLeast(1) / 2, wb + 1.1))
            val mx = Matrix().apply {
                values[Matrix.ScaleX] = ex.x * m; values[Matrix.SkewY] = ex.y * m
                values[Matrix.SkewX] = ey.x * m; values[Matrix.ScaleY] = ey.y * m
                values[Matrix.TranslateX] = tl.x; values[Matrix.TranslateY] = tl.y
            }
            withTransform({ transform(mx) }) { drawImage(img) }
        }
        else -> {
            val tyre = Color(0xFF1A1D1B)
            val tread = Color(0xFF3A403C)
            val glass = Color(0xFF243038)
            fun poly(vararg pts: Vec2) = Path().apply {
                val a = o(pts[0]); moveTo(a.x, a.y)
                for (i in 1 until pts.size) { val b = o(pts[i]); lineTo(b.x, b.y) }
                close()
            }
            fun rect(cx: Double, cy: Double, hw: Double, hl: Double) = poly(w(cx - hw, cy - hl), w(cx + hw, cy - hl), w(cx + hw, cy + hl), w(cx - hw, cy + hl))
            // koło: opona z bieżnikiem (kreski w poprzek), obrócone o kąt skrętu
            fun wheel(cx: Double, cy: Double, hw: Double, hl: Double, angDeg: Double) {
                val h = heading + angDeg * PI / 180
                val f2 = Vec2.forward(h); val r2 = Vec2.right(h)
                val c = w(cx, cy)
                fun q(x: Double, y: Double) = c + r2 * x + f2 * y
                drawPath(poly(q(-hw, -hl), q(hw, -hl), q(hw, hl), q(-hw, hl)), tyre)
                val n = 7
                for (k in 1 until n) {
                    val y = -hl + 2 * hl * k / n
                    drawLine(tread, o(q(-hw * 0.85, y)), o(q(hw * 0.85, y)), lw(0.05, 1.2f))
                }
            }
            // tylny zaczep / TUZ
            drawLine(tyre, o(w(0.0, -0.2)), o(w(0.0, -0.95)), lw(0.18, 2f))
            // oś przednia i koła
            drawLine(tyre, o(w(-tr / 2 + 0.1, wb)), o(w(tr / 2 - 0.1, wb)), lw(0.1, 1.5f))
            wheel(-tr / 2 + 0.1, wb, 0.2, 0.55, steerDeg)
            wheel(tr / 2 - 0.1, wb, 0.2, 0.55, steerDeg)
            // koła tylne
            wheel(-tr / 2, 0.0, 0.29, 0.85, 0.0)
            wheel(tr / 2, 0.0, 0.29, 0.85, 0.0)
            // błotniki nad tylnymi kołami
            val fender = body.copy(red = body.red * 0.8f, green = body.green * 0.8f, blue = body.blue * 0.8f)
            drawPath(rect(-tr / 2 + 0.05, 0.05, 0.3, 0.55), fender)
            drawPath(rect(tr / 2 - 0.05, 0.05, 0.3, 0.55), fender)
            // maska z obciążnikiem z przodu
            val hood = poly(w(-0.42, 0.6), w(0.42, 0.6), w(0.42, wb + 0.75), w(0.3, wb + 0.95), w(-0.3, wb + 0.95), w(-0.42, wb + 0.75))
            drawPath(hood, body)
            drawPath(hood, outline.copy(alpha = 0.55f), style = Stroke(width = lw(0.05, 1.2f)))
            drawPath(rect(0.0, wb + 1.05, 0.48, 0.12), Color(0xFF4A504C))
            // wydech
            val ex = o(w(0.28, wb - 0.1))
            drawCircle(tyre, lw(0.08, 1.5f), ex)
            // kabina: szyby dookoła, dach w kolorze ciągnika
            val cab = rect(0.0, 0.25, 0.78, 0.82)
            drawPath(cab, glass)
            drawPath(cab, outline.copy(alpha = 0.8f), style = Stroke(width = lw(0.06, 1.5f)))
            drawPath(rect(0.0, 0.22, 0.62, 0.66), body)
        }
    }
}
