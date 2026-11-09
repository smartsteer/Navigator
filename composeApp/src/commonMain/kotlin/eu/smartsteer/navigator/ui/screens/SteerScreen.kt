package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.Panel
import eu.smartsteer.navigator.ui.components.SectionTitle
import eu.smartsteer.navigator.ui.components.Stat
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss
import kotlin.math.abs

@Composable
fun SteerScreen(ctrl: DemoController) {
    ctrl.frame
    val s = ctrl.session
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(Modifier.weight(1f), "Kąt zadany", "${s.steerSetDeg.pl(1)}°", Ss.Accent)
                Tile(Modifier.weight(1f), "Kąt rzeczywisty", "${s.sim.actualSteerDeg.pl(1)}°")
                Tile(Modifier.weight(1f), "Błąd kąta", "${abs(s.steerSetDeg - s.sim.actualSteerDeg).pl(1)}°")
                Tile(Modifier.weight(1f), "Odchyłka od linii", "${s.xteCm.pl(1, plusSign = true)} cm")
            }
            Panel(Modifier.weight(1f).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionTitle("Kąt skrętu — ostatnie 20 s") }
                    Legend(Ss.Accent, "zadany")
                    Box(Modifier.width(16.dp))
                    Legend(Ss.Text, "rzeczywisty")
                }
                SteerChart(s.historySet, s.historyActual, Modifier.weight(1f).fillMaxWidth())
                Row {
                    Label("−20 s"); Box(Modifier.weight(1f)); Label("skala ±10°"); Box(Modifier.weight(1f)); Label("teraz")
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Czujnik kąta kół (WAS)")
                Label("Teraz pracuje symulacja. Po podłączeniu płytki tu będzie kalibracja zera i liczniki na stopień.", Ss.TextSoft, 13.sp)
            }
        }

        Panel(Modifier.width(360.dp).fillMaxHeight()) {
            SectionTitle("Strojenie")
            Tuning(
                label = "Punkt wyprzedzenia (look‑ahead)",
                value = s.lookaheadSeconds.toFloat(), range = 0.8f..4f,
                display = "${s.lookaheadSeconds.pl(1)} s",
            ) { s.lookaheadSeconds = it.toDouble(); ctrl.touch() }
            Tuning(
                label = "Szybkość układu kierowniczego",
                value = s.sim.steerRateDegPerS.toFloat(), range = 10f..80f,
                display = "${s.sim.steerRateDegPerS.pl(0)} °/s",
            ) { s.sim.steerRateDegPerS = it.toDouble(); ctrl.touch() }
            Tuning(
                label = "Opóźnienie hydrauliki",
                value = s.sim.steerLagS.toFloat(), range = 0.05f..0.8f,
                display = "${s.sim.steerLagS.pl(2)} s",
            ) { s.sim.steerLagS = it.toDouble(); ctrl.touch() }
            Box(Modifier.weight(1f))
            Label(
                "Dwa dolne suwaki zmieniają tylko symulowany ciągnik — pobaw się nimi i zobacz, jak autoster sobie radzi z wolną lub opóźnioną hydrauliką.",
                Ss.TextSoft, 13.sp,
            )
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, value: String, color: Color = Ss.Text) {
    Panel(modifier, padding = 14.dp) { Stat(label, value, color, 26.sp) }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(18.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(text, color = Ss.TextSoft, fontSize = 13.sp)
    }
}

@Composable
private fun Tuning(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(label, color = Ss.TextSoft, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(display, color = Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Ss.Accent,
                activeTrackColor = Ss.Accent,
                inactiveTrackColor = Ss.Dim,
            ),
        )
    }
}

@Composable
private fun SteerChart(set: List<Double>, actual: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        val mid = size.height / 2
        val scaleY = size.height / 2 / 10f // ±10°
        drawLine(Ss.Dim, Offset(0f, mid), Offset(size.width, mid), 1f)
        drawLine(Ss.SurfaceHigh, Offset(0f, mid - 5 * scaleY), Offset(size.width, mid - 5 * scaleY), 1f)
        drawLine(Ss.SurfaceHigh, Offset(0f, mid + 5 * scaleY), Offset(size.width, mid + 5 * scaleY), 1f)
        fun series(data: List<Double>): Path {
            val p = Path()
            val n = 200
            val start = n - data.size
            data.forEachIndexed { i, d ->
                val x = size.width * (start + i) / (n - 1)
                val y = mid - d.toFloat().coerceIn(-10f, 10f) * scaleY
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        if (set.size > 1) drawPath(series(set), Ss.Accent, style = Stroke(width = 3.5f, join = StrokeJoin.Round))
        if (actual.size > 1) drawPath(series(actual), Ss.Text, style = Stroke(width = 1.8f, join = StrokeJoin.Round))
    }
}
