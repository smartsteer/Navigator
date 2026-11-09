package eu.smartsteer.navigator

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.ui.theme.Ss
import eu.smartsteer.navigator.ui.theme.SmartSteerTheme

enum class Screen(val label: String) {
    Map("Mapa"), Fields("Pola"), Steer("Sterowanie"), Implement("Maszyna"), Connections("Połączenia")
}

@Composable
fun App() {
    SmartSteerTheme {
        var screen by remember { mutableStateOf(Screen.Map) }
        Column(Modifier.fillMaxSize().background(Ss.Ground)) {
            TopBar()
            Row(Modifier.fillMaxSize()) {
                NavRail(screen) { screen = it }
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Map -> GuidanceScreen()
                        else -> Placeholder(screen.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Ss.Surface).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SmartSteer", color = Ss.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f))
        Chip("Brak GPS", Ss.Warning)
        Text("0,0 km/h", color = Ss.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Ss.SurfaceHigh).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    Column(
        Modifier.width(96.dp).fillMaxHeight().background(Ss.Surface).padding(vertical = 10.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Screen.entries.forEach { s ->
            val active = s == current
            Box(
                Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (active) Ss.SurfaceHigh else Ss.Surface)
                    .clickable { onSelect(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(s.label, color = if (active) Ss.Accent else Ss.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GuidanceScreen() {
    var engaged by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize()) {
        Canvas(Modifier.weight(1f).fillMaxHeight().background(Ss.MapGround)) {
            val step = 46.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(Ss.Border, Offset(x, 0f), Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(Ss.Border, Offset(0f, y), Offset(size.width, y)); y += step }
            drawLine(Ss.Accent, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 3.dp.toPx())
        }
        Column(
            Modifier.width(240.dp).fillMaxHeight().background(Ss.Surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(20.dp))
                    .border(3.dp, Ss.Accent, RoundedCornerShape(20.dp))
                    .background(if (engaged) Ss.Accent else Ss.Surface)
                    .clickable { engaged = !engaged },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (engaged) "AUTOSTER" else "RĘCZNIE",
                    color = if (engaged) Ss.OnAccent else Ss.Text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$title — w przygotowaniu", color = Ss.TextMuted, fontSize = 18.sp)
    }
}
