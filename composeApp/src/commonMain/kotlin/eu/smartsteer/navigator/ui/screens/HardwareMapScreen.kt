package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.guidance.Pose
import eu.smartsteer.navigator.core.hw.GuidanceMode
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.BigValue
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.NavIcon
import eu.smartsteer.navigator.ui.components.Segmented
import eu.smartsteer.navigator.ui.components.Stat
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Ekran Mapa w trybie Sprzęt: prawdziwa pozycja, linia AB z przycisków A/B, autoster. */
@Composable
fun HardwareMapScreen(hw: HardwareController) {
    var zoom by remember { mutableStateOf(12f) }
    var headingUp by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            HwCanvas(hw, zoom, headingUp, Modifier.fillMaxSize())
            HwInfo(hw, Modifier.align(Alignment.TopStart).padding(16.dp))
            Column(Modifier.align(Alignment.TopEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HwSquare({ zoom = (zoom * 1.3f).coerceAtMost(60f) }) { NavIcon(IconKind.Plus, Ss.Text, 22.dp) }
                HwSquare({ zoom = (zoom / 1.3f).coerceAtLeast(1.5f) }) { NavIcon(IconKind.Minus, Ss.Text, 22.dp) }
                HwSquare({ headingUp = !headingUp }) { ButtonText(if (headingUp) "KURS" else "N", size = 13.sp, weight = FontWeight.Bold) }
            }
            HwMessage(hw, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
        HwRightPanel(hw)
    }
}

private val shape14 = RoundedCornerShape(14.dp)
private fun Modifier.hwOverlay() = this.clip(shape14).background(Ss.Surface.copy(alpha = 0.92f)).border(1.dp, Ss.Border, shape14)

@Composable
private fun HwSquare(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(52.dp).hwOverlay().clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HwInfo(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val s = hw.session
    val now = hw.now()
    val course = (((s.headingRad * 180 / PI) + 360) % 360).roundToInt() % 360
    Column(modifier.hwOverlay().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Label("Linia prowadzenia")
        val l = s.line
        val (title, sub) = when (s.mode) {
            GuidanceMode.AB -> when {
                l != null -> "AB · kurs ${(((l.heading * 180 / PI) + 360) % 360).roundToInt() % 360}°" to
                    "Przejazd ${if (s.passIndex == 0) "AB" else s.passIndex.toString()}${if (s.reversed) " · powrót" else ""}"
                s.pointA != null -> "Punkt A zapisany" to "Przejedź min. 20 m i naciśnij B"
                else -> "Brak linii" to "Naciśnij A na początku przejazdu"
            }
            GuidanceMode.CURVE -> when {
                s.recordingCurve != null -> "Nagrywam krzywą" to "${s.recordingLength.roundToInt()} m · na końcu „Zakończ”"
                s.curveRef != null -> "Krzywa" to
                    "Przejazd ${if (s.curvePassIndex == 0) "główny" else s.curvePassIndex.toString()}${if (s.curveReversed) " · powrót" else ""}"
                else -> "Brak krzywej" to "Naciśnij „Nagraj krzywą” i jedź"
            }
            GuidanceMode.CONTOUR -> "Kontur" to
                if (s.contour.guide.isNotEmpty()) "Trzymam się poprzedniego przejazdu" else "Brak przejazdu obok (do 3 szerokości)"
        }
        BigValue(title, size = 18.sp)
        Text(sub, color = Ss.TextSoft, fontSize = 13.sp)
        Text(
            if (s.gpsAlive(now)) "GPS ${s.fix.label} · kurs $course° (${s.headingSource.label})" else "GPS: brak pozycji",
            color = if (s.gpsAlive(now)) Ss.TextSoft else Ss.Warning, fontSize = 13.sp,
        )
    }
}

@Composable
private fun HwMessage(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val m = hw.session.message ?: return
    Text(m, modifier.hwOverlay().padding(horizontal = 16.dp, vertical = 12.dp), color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun HwRightPanel(hw: HardwareController) {
    hw.tick
    val s = hw.session
    val now = hw.now()
    Column(
        Modifier.width(248.dp).fillMaxHeight().background(Ss.Surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val hasLine = when (s.mode) {
            GuidanceMode.AB -> s.line != null
            GuidanceMode.CURVE -> s.curveRef != null
            GuidanceMode.CONTOUR -> s.contour.guide.isNotEmpty()
        }
        val ready = hasLine && s.gpsAlive(now) && s.autosteerAlive(now)
        val shape = RoundedCornerShape(20.dp)
        val border = if (ready || s.engaged) Ss.Accent else Ss.Dim
        Column(
            Modifier.fillMaxWidth().height(120.dp).clip(shape)
                .background(if (s.engaged) Ss.Accent else Color.Transparent)
                .border(3.dp, border, shape)
                .clickable(role = Role.Button) { s.setEngaged(!s.engaged, now) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            val fg = if (s.engaged) Ss.OnAccent else if (ready) Ss.Text else Ss.TextMuted
            NavIcon(IconKind.Steer, fg, 38.dp)
            Text(if (s.engaged) "AUTOSTER" else "RĘCZNIE", color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    s.engaged -> "Włączony · dotknij, aby wyłączyć"
                    ready -> "Dotknij, aby włączyć"
                    !hasLine -> when (s.mode) {
                        GuidanceMode.AB -> "Najpierw linia AB"
                        GuidanceMode.CURVE -> "Najpierw nagraj krzywą"
                        GuidanceMode.CONTOUR -> "Brak przejazdu obok"
                    }
                    !s.gpsAlive(now) -> "Czekam na GPS"
                    else -> "Czekam na moduł skrętu"
                },
                color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }

        Segmented(
            GuidanceMode.entries.map { it.label }, s.mode.ordinal,
            { s.setMode(GuidanceMode.entries[it]); hw.touch() },
            Modifier.fillMaxWidth(),
        )
        when (s.mode) {
            GuidanceMode.AB -> AbControls(hw)
            GuidanceMode.CURVE -> CurveControls(hw)
            GuidanceMode.CONTOUR -> {
                Label("Prowadzi wzdłuż poprzedniego przejazdu, o szerokość maszyny obok. Przejazdy nagrywają się same podczas jazdy.", Ss.TextSoft, 13.sp)
                ActionButton(Modifier.fillMaxWidth(), onClick = { s.clearContour(); hw.touch() }) { ButtonText("Wyczyść ślady", color = Ss.TextSoft) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Przesuń linię · ${if (s.lineShiftCm == 0) "0" else s.lineShiftCm.toDouble().pl(0, plusSign = true)} cm")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Modifier.weight(1f), onClick = { s.lineShiftCm -= 2; hw.touch() }) { NavIcon(IconKind.ChevronLeft, Ss.Text, 22.dp, 2.4f) }
                ActionButton(Modifier.weight(1f), onClick = { s.lineShiftCm += 2; hw.touch() }) { NavIcon(IconKind.ChevronRight, Ss.Text, 22.dp, 2.4f) }
            }
        }
        HwStats(hw)
    }
}

@Composable
private fun CurveControls(hw: HardwareController) {
    val s = hw.session
    if (s.recordingCurve != null) {
        ActionButton(
            Modifier.fillMaxWidth(), minHeight = 64.dp, background = Ss.Warning, border = Ss.Warning,
            onClick = { s.finishCurve(); hw.touch() },
        ) { ButtonText("Zakończ · ${s.recordingLength.roundToInt()} m", color = Ss.OnWarning, size = 18.sp, weight = FontWeight.Bold) }
        ActionButton(Modifier.fillMaxWidth(), onClick = { s.cancelCurve(); hw.touch() }) { ButtonText("Przerwij", color = Ss.TextSoft) }
    } else {
        ActionButton(
            Modifier.fillMaxWidth(), minHeight = 64.dp, background = Ss.AccentSoft, border = Ss.Accent,
            onClick = { s.startCurve(); hw.touch() },
        ) { ButtonText(if (s.curveRef == null) "Nagraj krzywą" else "Nagraj nową krzywą", color = Ss.Accent, size = 17.sp, weight = FontWeight.Bold) }
        if (s.curveRef != null) {
            ActionButton(Modifier.fillMaxWidth(), onClick = { s.clearCurve(); hw.touch() }) { ButtonText("Usuń krzywą", color = Ss.TextSoft) }
        }
    }
}

@Composable
private fun AbControls(hw: HardwareController) {
    val s = hw.session
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val aSet = s.pointA != null
            ActionButton(
                Modifier.weight(1f), minHeight = 64.dp,
                background = if (aSet) Ss.AccentSoft else Ss.SurfaceHigh,
                border = if (aSet) Ss.Accent else Ss.BorderStrong,
                onClick = { s.markA(); hw.touch() },
            ) { ButtonText("A", color = if (aSet) Ss.Accent else Ss.Text, size = 24.sp, weight = FontWeight.Bold) }
            val bOk = aSet && s.line == null
            ActionButton(
                Modifier.weight(1f), minHeight = 64.dp,
                background = if (s.line != null) Ss.AccentSoft else Ss.SurfaceHigh,
                border = if (s.line != null) Ss.Accent else Ss.BorderStrong,
                onClick = { s.markB(); hw.touch() },
            ) { ButtonText("B", color = if (s.line != null) Ss.Accent else if (bOk) Ss.Text else Ss.TextMuted, size = 24.sp, weight = FontWeight.Bold) }
        }
        if (s.line != null || s.pointA != null) {
            ActionButton(Modifier.fillMaxWidth(), onClick = { s.clearLine(); hw.touch() }) { ButtonText("Usuń linię", color = Ss.TextSoft) }
        }
}

@Composable
private fun HwStats(hw: HardwareController) {
    val s = hw.session
    val now = hw.now()
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ss.Panel2).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val st = s.autosteer
            Row {
                Box(Modifier.weight(1f)) { Stat("Kąt zadany", "${s.steerSetDeg.pl(1, plusSign = true)}°", valueSize = 18.sp) }
                Box(Modifier.weight(1f)) {
                    Stat("Kąt kół", if (st != null && s.autosteerAlive(now)) "${st.actualAngleDeg.pl(1, plusSign = true)}°" else "—", valueSize = 18.sp)
                }
            }
            val g = s.lastGga
            Row {
                Box(Modifier.weight(1f)) { Stat("Satelity", if (g != null) "${g.satellites}" else "—", valueSize = 18.sp) }
                Box(Modifier.weight(1f)) {
                    Stat("Wiek poprawek", g?.correctionAge?.let { "${it.pl(1)} s" } ?: "—", valueSize = 18.sp,
                        valueColor = if (s.fix == FixQuality.RTK_FIX) Ss.Text else Ss.Warning)
                }
            }
        }
}

@Composable
private fun HwCanvas(hw: HardwareController, zoom: Float, headingUp: Boolean, modifier: Modifier) {
    Canvas(modifier.background(Ss.MapGround)) {
        hw.tick
        val s = hw.session
        val pos = s.pivot ?: Vec2.ZERO
        val sc = zoom * density
        val px = 1f / sc
        val fwd = Vec2.forward(s.headingRad)
        val lead = size.height * 0.18f / sc
        val cam = if (headingUp) pos + fwd * lead.toDouble() else pos
        val camX = cam.x.toFloat()
        val camY = cam.y.toFloat()
        fun o(p: Vec2) = Offset(p.x.toFloat(), p.y.toFloat())
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            if (headingUp) rotate(-(s.headingRad * 180 / PI).toFloat(), Offset.Zero)
            scale(sc, -sc, Offset.Zero)
            translate(-camX, -camY)
        }) {
            val r = hypot(size.width, size.height) / sc
            // siatka co 10 m
            val gc = Color(0xFF1C2822)
            var x = floor((camX - r) / 10f) * 10f
            while (x <= camX + r) { drawLine(gc, Offset(x, camY - r), Offset(x, camY + r), px); x += 10f }
            var y = floor((camY - r) / 10f) * 10f
            while (y <= camY + r) { drawLine(gc, Offset(camX - r, y), Offset(camX + r, y), px); y += 10f }

            // ślad jazdy
            if (s.trail.size >= 2) {
                val path = Path()
                s.trail.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat()) }
                drawPath(path, Ss.Info.copy(alpha = 0.45f), style = Stroke(width = 2f * px, cap = StrokeCap.Round))
            }

            fun poly(pts: List<Pose>, color: Color, width: Float, dashed: Boolean = false) {
                if (pts.size < 2) return
                val path = Path()
                pts.forEachIndexed { i, q -> if (i == 0) path.moveTo(q.p.x.toFloat(), q.p.y.toFloat()) else path.lineTo(q.p.x.toFloat(), q.p.y.toFloat()) }
                drawPath(
                    path, color,
                    style = Stroke(
                        width = width * px, cap = StrokeCap.Round,
                        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f * px, 8f * px)) else null,
                    ),
                )
            }
            when (s.mode) {
                GuidanceMode.CURVE -> {
                    s.curveRef?.let { poly(it, Ss.MapLine.copy(alpha = 0.6f), 1.5f) }
                    poly(s.curvePath, Ss.Accent, 3f)
                    s.recordingCurve?.let { rec -> poly(rec.map { Pose(it, 0.0) }, Ss.Warning, 3f) }
                }
                GuidanceMode.CONTOUR -> {
                    s.contour.strips.forEach { poly(it, Ss.Dim, 1.5f, dashed = true) }
                    poly(s.contour.guide, Ss.Accent, 3f)
                }
                GuidanceMode.AB -> {}
            }

            // linia AB i sąsiednie przejazdy
            val l = s.line.takeIf { s.mode == GuidanceMode.AB }
            if (l != null) {
                val dir = Vec2.forward(l.heading)
                val right = Vec2.right(l.heading)
                val w = s.profile.passSpacing
                val along = ((pos - l.a) dot dir)
                for (k in s.passIndex - 6..s.passIndex + 6) {
                    val base = l.a + right * (k * w) + dir * along
                    val a = base - dir * r.toDouble()
                    val b = base + dir * r.toDouble()
                    val active = k == s.passIndex
                    drawLine(
                        if (active) Ss.Accent else if (k == 0) Ss.MapLine.copy(alpha = 0.6f) else Ss.Dim,
                        o(a), o(b),
                        (if (active) 3f else 1.5f) * px,
                        pathEffect = if (active || k == 0) null else PathEffect.dashPathEffect(floatArrayOf(8f * px, 8f * px)),
                    )
                }
                drawCircle(Ss.MapLine, 6f * px, o(l.a))
                drawCircle(Ss.MapLine, 6f * px, o(l.b))
            }
            s.pointA?.let { if (l == null && s.mode == GuidanceMode.AB) drawCircle(Ss.Warning, 8f * px, o(it)) }
            s.goal?.let { drawCircle(Ss.Accent, 5f * px, o(it)) }

            // ciągnik: prosty trójkąt w kolorze z projektu, czubek = przód
            val p = s.pivot
            if (p != null) {
                val rt = Vec2.right(s.headingRad)
                val tri = Path().apply {
                    moveTo(o(p + fwd * 3.2).x, o(p + fwd * 3.2).y)
                    lineTo(o(p + rt * 1.1 - fwd * 0.6).x, o(p + rt * 1.1 - fwd * 0.6).y)
                    lineTo(o(p - rt * 1.1 - fwd * 0.6).x, o(p - rt * 1.1 - fwd * 0.6).y)
                    close()
                }
                drawPath(tri, Ss.TractorBody)
                drawPath(tri, Ss.Ground, style = Stroke(width = 1.5f * px))
                s.antenna?.let { drawCircle(Ss.Text, 3f * px, o(it)) }
            }
        }
    }
}
