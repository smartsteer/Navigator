package eu.smartsteer.navigator.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.ui.theme.Ss

/** Karta panelu (ciemne tło, zaokrąglone rogi, cienka ramka). */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Ss.Surface)
            .border(1.dp, Ss.Border, RoundedCornerShape(16.dp))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Label(text: String, color: Color = Ss.TextMuted, size: TextUnit = 12.sp) {
    Text(text, color = color, fontSize = size)
}

@Composable
fun BigValue(text: String, color: Color = Ss.Text, size: TextUnit = 22.sp) {
    Text(text, color = color, fontSize = size, fontWeight = FontWeight.Bold)
}

@Composable
fun Stat(label: String, value: String, valueColor: Color = Ss.Text, valueSize: TextUnit = 16.sp) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(label)
        Text(value, color = valueColor, fontSize = valueSize, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Pill(text: String, color: Color, background: Color = Ss.SurfaceHigh) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Przycisk panelu, min. 52 dp wysokości (pod palec w rękawicy). */
@Composable
fun ActionButton(
    modifier: Modifier = Modifier,
    background: Color = Ss.SurfaceHigh,
    contentColor: Color = Ss.Text,
    border: Color = Ss.BorderStrong,
    minHeight: Dp = 52.dp,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) { content() }
    }
}

@Composable
fun ButtonText(text: String, color: Color = Ss.Text, size: TextUnit = 14.sp, weight: FontWeight = FontWeight.SemiBold) {
    Text(text, color = color, fontSize = size, fontWeight = weight)
}

/** Przełącznik segmentowy (np. AUTO / RĘCZNIE). */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: (Int) -> Color = { Ss.Accent },
) {
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(Ss.Ground).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) selectedColor(i) else Color.Transparent)
                    .clickable(role = Role.Button) { onSelect(i) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Ss.OnAccent else Ss.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

enum class IconKind { Map, Field, Steer, Implement, Network, ChevronLeft, ChevronRight, Plus, Minus, Target, Restart, Logo, Settings }

/** Ikony rysowane kreską w siatce 24×24 (jak w projekcie), bez zewnętrznych bibliotek. */
@Composable
fun NavIcon(kind: IconKind, color: Color, size: Dp = 24.dp, strokeWidth: Float = 2f) {
    Canvas(Modifier.size(size)) { drawIcon(kind, color, strokeWidth) }
}

fun DrawScope.drawIcon(kind: IconKind, color: Color, strokeWidth: Float = 2f) {
    val u = size.minDimension / 24f
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    val st = Stroke(width = strokeWidth * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun poly(vararg pts: Float, close: Boolean = false) {
        val p = Path()
        p.moveTo(pts[0] * u, pts[1] * u)
        var i = 2
        while (i < pts.size) { p.lineTo(pts[i] * u, pts[i + 1] * u); i += 2 }
        if (close) p.close()
        drawPath(p, color, style = st)
    }
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, o(x1, y1), o(x2, y2), st.width, StrokeCap.Round)
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1f) =
        drawRoundRect(color, o(x, y), Size(w * u, h * u), CornerRadius(r * u), style = st)

    when (kind) {
        IconKind.Map -> { poly(3f, 6f, 9f, 3f, 15f, 6f, 21f, 3f, 21f, 18f, 15f, 21f, 9f, 18f, 3f, 21f, close = true); line(9f, 3f, 9f, 18f); line(15f, 6f, 15f, 21f) }
        IconKind.Field -> { poly(4f, 5f, 11f, 3f, 20f, 6f, 18f, 20f, 6f, 21f, close = true); line(8f, 4f, 7f, 20f); line(13f, 4f, 14f, 20f) }
        IconKind.Steer -> {
            drawCircle(color, 9f * u, o(12f, 12f), style = st)
            drawCircle(color, 2.5f * u, o(12f, 12f), style = st)
            line(3.5f, 10.5f, 9.5f, 11.5f); line(20.5f, 10.5f, 14.5f, 11.5f); line(12f, 14.5f, 12f, 21f)
        }
        IconKind.Implement -> { line(2f, 18f, 22f, 18f); line(5f, 18f, 5f, 21f); line(9.5f, 18f, 9.5f, 21f); line(14.5f, 18f, 14.5f, 21f); line(19f, 18f, 19f, 21f); rect(8f, 3f, 8f, 10f, 2f); line(12f, 13f, 12f, 18f) }
        IconKind.Network -> { rect(9f, 2f, 6f, 6f); rect(2f, 16f, 6f, 6f); rect(16f, 16f, 6f, 6f); line(12f, 8f, 12f, 12f); poly(5f, 16f, 5f, 12f, 19f, 12f, 19f, 16f) }
        IconKind.ChevronLeft -> poly(15f, 5f, 8f, 12f, 15f, 19f)
        IconKind.ChevronRight -> poly(9f, 5f, 16f, 12f, 9f, 19f)
        IconKind.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        IconKind.Minus -> line(5f, 12f, 19f, 12f)
        IconKind.Target -> { drawCircle(color, 4f * u, o(12f, 12f), style = st); line(12f, 2f, 12f, 6f); line(12f, 18f, 12f, 22f); line(2f, 12f, 6f, 12f); line(18f, 12f, 22f, 12f) }
        IconKind.Restart -> { poly(4f, 4f, 4f, 9f, 9f, 9f); drawArc(color, 200f, 270f, false, o(4f, 4f), Size(16f * u, 16f * u), style = st) }
        IconKind.Settings -> {
            line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
            drawCircle(Ss.Surface, 2.6f * u, o(15f, 6f)); drawCircle(color, 2.6f * u, o(15f, 6f), style = st)
            drawCircle(Ss.Surface, 2.6f * u, o(8f, 12f)); drawCircle(color, 2.6f * u, o(8f, 12f), style = st)
            drawCircle(Ss.Surface, 2.6f * u, o(13f, 18f)); drawCircle(color, 2.6f * u, o(13f, 18f), style = st)
        }
        IconKind.Logo -> { drawCircle(color, 9f * u, o(12f, 12f), style = st); line(12f, 3f, 12f, 21f); poly(7f, 21f, 12f, 12f, 17f, 21f) }
    }
}
