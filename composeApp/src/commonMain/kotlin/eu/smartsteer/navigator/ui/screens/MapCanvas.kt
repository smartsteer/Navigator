package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.sim.CoverageStrip
import eu.smartsteer.navigator.core.sim.DemoSession
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.theme.Ss
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Mapa na żywo. Wszystko rysujemy we współrzędnych pola (metry, y w górę = północ),
 * a jedną transformacją przeliczamy na piksele — dzięki temu zoom i obrót "za ciągnikiem" są tanie.
 */
@Composable
fun MapCanvas(ctrl: DemoController, modifier: Modifier = Modifier) {
    val session = ctrl.session
    // pasy pokrycia zamieniamy na Path tylko raz (otwarty pas — gdy przybędzie punktów)
    val stripCache = remember(session) { HashMap<CoverageStrip, Pair<Int, Path>>() }

    Canvas(modifier.clipToBounds().background(Ss.MapGround)) {
        @Suppress("UNUSED_VARIABLE") val frame = ctrl.frame // odświeżanie co klatkę
        val s = ctrl.session
        val sc = ctrl.zoom * density               // piksele na metr
        val px = 1f / sc                            // 1 piksel w metrach
        val headingDeg = (s.sim.heading * 180.0 / PI).toFloat()
        val fwd = Vec2.forward(s.sim.heading)
        // ciągnik trochę poniżej środka, żeby widzieć więcej przed sobą
        val lead = size.height * 0.18f / sc
        val cam = if (ctrl.headingUp) s.sim.position + fwd * lead.toDouble() else s.sim.position
        val camX = cam.x.toFloat()
        val camY = cam.y.toFloat()

        withTransform({
            translate(size.width / 2f, size.height / 2f)
            if (ctrl.headingUp) rotate(-headingDeg, Offset.Zero)
            scale(sc, -sc, Offset.Zero)
            translate(-camX, -camY)
        }) {
            val radius = hypot(size.width, size.height) / sc // zapas na obrót
            drawGrid(camX, camY, radius, px)
            drawField(s, px)
            drawCoverage(s, stripCache)
            drawPasses(s, px)
            drawGuidance(s, px)
            drawImplement(s, px)
            drawTractor(s, px)
        }
    }
}

private fun v(p: Vec2) = Offset(p.x.toFloat(), p.y.toFloat())

private fun DrawScope.drawGrid(cx: Float, cy: Float, r: Float, px: Float) {
    val step = 10f
    val c = Color(0xFF1C2822)
    var x = floor((cx - r) / step) * step
    while (x <= cx + r) { drawLine(c, Offset(x, cy - r), Offset(x, cy + r), px); x += step }
    var y = floor((cy - r) / step) * step
    while (y <= cy + r) { drawLine(c, Offset(cx - r, y), Offset(cx + r, y), px); y += step }
}

private fun polygon(pts: List<Vec2>): Path = Path().apply {
    moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
    for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
    close()
}

private fun DrawScope.drawField(s: DemoSession, px: Float) {
    val boundary = polygon(s.plan.boundary)
    drawPath(boundary, Ss.FieldFill)
    drawPath(boundary, Ss.MapLine, style = Stroke(width = 2.5f * px, join = StrokeJoin.Round))
    drawPath(
        polygon(s.plan.interior),
        Ss.MapLine.copy(alpha = 0.45f),
        style = Stroke(width = 1.5f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f * px, 6f * px))),
    )
}

private fun DrawScope.drawCoverage(s: DemoSession, cache: HashMap<CoverageStrip, Pair<Int, Path>>) {
    val color = Ss.Accent.copy(alpha = 0.26f)
    for (strip in s.coverage) {
        val n = strip.left.size
        if (n < 2) continue
        val cached = cache[strip]
        val path = if (cached != null && cached.first == n) cached.second else {
            Path().apply {
                moveTo(strip.left[0].x.toFloat(), strip.left[0].y.toFloat())
                for (i in 1 until n) lineTo(strip.left[i].x.toFloat(), strip.left[i].y.toFloat())
                for (i in n - 1 downTo 0) lineTo(strip.right[i].x.toFloat(), strip.right[i].y.toFloat())
                close()
            }.also { cache[strip] = Pair(n, it) }
        }
        drawPath(path, color)
    }
}

private fun DrawScope.drawPasses(s: DemoSession, px: Float) {
    val plan = s.plan
    val c = Ss.MapLine.copy(alpha = 0.12f)
    for (k in 0 until plan.passCount) {
        val x = plan.passX(k).toFloat()
        drawLine(c, Offset(x, plan.headland.toFloat()), Offset(x, (plan.lengthM - plan.headland).toFloat()), 1.5f * px)
    }
}

private fun DrawScope.drawGuidance(s: DemoSession, px: Float) {
    val path = s.plan.path
    val shift = s.lineShiftCm / 100.0
    val from = (s.station - 15.0).coerceAtLeast(0.0)
    val to = (s.station + 90.0).coerceAtMost(path.length)
    val line = Path()
    var st = from
    var first = true
    while (st <= to) {
        val p = path.pointAt(st) + Vec2.right(path.headingAt(st)) * shift
        if (first) { line.moveTo(p.x.toFloat(), p.y.toFloat()); first = false } else line.lineTo(p.x.toFloat(), p.y.toFloat())
        st += 1.0
    }
    drawPath(line, Ss.Accent, style = Stroke(width = 3f * px, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // look-ahead: od maski do punktu docelowego
    val nose = s.sim.position + Vec2.forward(s.sim.heading) * 3.3
    drawLine(
        Ss.Accent, v(nose), v(s.goal), 2f * px,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * px, 5f * px)),
    )
    drawCircle(Ss.Accent, 8f * px, v(s.goal), style = Stroke(width = 3f * px))
}

/** Prostokąt w układzie ciągnika: środek w poprzek [lat], od [f0] do przodu na długość [len]. */
private fun box(s: DemoSession, origin: Vec2, lat: Double, f0: Double, width: Double, len: Double): Path {
    val fwd = Vec2.forward(s.sim.heading)
    val right = Vec2.right(s.sim.heading)
    fun p(r: Double, f: Double) = origin + right * r + fwd * f
    return polygon(
        listOf(
            p(lat - width / 2, f0), p(lat + width / 2, f0),
            p(lat + width / 2, f0 + len), p(lat - width / 2, f0 + len),
        ),
    )
}

private fun DrawScope.drawImplement(s: DemoSession, px: Float) {
    val fwd = Vec2.forward(s.sim.heading)
    val c = s.implementCenter()
    // dyszel / zaczep
    drawLine(Ss.MapLine, v(s.sim.position - fwd * 0.6), v(c + fwd * 0.35), 3f * px)
    for (i in 0 until s.config.sections) {
        val (a, b) = s.sectionEdges(i)
        val quad = polygon(listOf(a + fwd * 0.35, b + fwd * 0.35, b - fwd * 0.35, a - fwd * 0.35))
        drawPath(quad, if (s.sectionOn[i]) Ss.Accent else Ss.Dim)
        drawPath(quad, Ss.Ground, style = Stroke(width = 1.5f * px))
    }
}

private fun DrawScope.drawTractor(s: DemoSession, px: Float) {
    val o = s.sim.position // środek tylnej osi
    val wb = s.config.wheelbase
    val outline = Stroke(width = 1.5f * px, join = StrokeJoin.Round)
    // koła tylne
    for (side in listOf(-0.95, 0.95)) {
        val w = box(s, o, side, -0.85, 0.55, 1.7)
        drawPath(w, Ss.Ground); drawPath(w, Ss.MapLine, style = outline)
    }
    // oś przednia i koła przednie
    val fwd = Vec2.forward(s.sim.heading)
    val right = Vec2.right(s.sim.heading)
    drawLine(Ss.MapLine, v(o + fwd * wb + right * -0.8), v(o + fwd * wb + right * 0.8), 2f * px)
    for (side in listOf(-0.8, 0.8)) {
        val w = box(s, o, side, wb - 0.5, 0.35, 1.0)
        drawPath(w, Ss.Ground); drawPath(w, Ss.MapLine, style = outline)
    }
    // maska, kabina, szyby, antena
    drawPath(box(s, o, 0.0, 0.9, 0.85, 2.6), Ss.TractorBody)
    drawPath(box(s, o, 0.0, -0.7, 1.45, 1.9), Ss.TractorBody)
    drawPath(box(s, o, 0.0, -0.45, 1.05, 1.4), Ss.OnWarning.copy(alpha = 0.55f))
    drawCircle(Ss.Text, 0.16f, v(o + fwd * 0.2))
}
