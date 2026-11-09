package eu.smartsteer.navigator.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.ui.DemoController
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
import kotlin.math.roundToInt

@Composable
fun MapScreen(ctrl: DemoController) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            MapCanvas(ctrl, Modifier.fillMaxSize())
            InfoCards(ctrl, Modifier.align(Alignment.TopStart).padding(16.dp))
            MapButtons(ctrl, Modifier.align(Alignment.TopEnd).padding(16.dp))
            SectionsBar(ctrl, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            FinishedBanner(ctrl, Modifier.align(Alignment.Center))
        }
        RightPanel(ctrl)
    }
}

private val overlayShape = RoundedCornerShape(14.dp)
private fun Modifier.overlay() = this
    .clip(overlayShape)
    .background(Ss.Surface.copy(alpha = 0.92f))
    .border(1.dp, Ss.Border, overlayShape)

@Composable
private fun InfoCards(ctrl: DemoController, modifier: Modifier) {
    ctrl.frame
    val s = ctrl.session
    val pass = s.plan.passAt(s.station)
    val course = (((s.sim.heading * 180 / PI) + 360) % 360).roundToInt() % 360
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.overlay().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Label("Linia prowadzenia")
            BigValue("AB „Pole demo”", size = 18.sp)
            Text(
                if (pass >= 0) "Przejazd ${pass + 1} z ${s.plan.passCount} · kurs $course°" else "Zawrót na uwrociu · kurs $course°",
                color = Ss.TextSoft, fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun SquareButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(52.dp).overlay().clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MapButtons(ctrl: DemoController, modifier: Modifier) {
    val s = ctrl.session
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SquareButton({ ctrl.zoom = (ctrl.zoom * 1.3f).coerceAtMost(60f) }) { NavIcon(IconKind.Plus, Ss.Text, 22.dp) }
        SquareButton({ ctrl.zoom = (ctrl.zoom / 1.3f).coerceAtLeast(2.5f) }) { NavIcon(IconKind.Minus, Ss.Text, 22.dp) }
        SquareButton({ ctrl.headingUp = !ctrl.headingUp }) {
            ButtonText(if (ctrl.headingUp) "KURS" else "N", size = 13.sp, weight = FontWeight.Bold)
        }
        SquareButton({
            s.timeScale = when (s.timeScale) { 1.0 -> 4.0; 4.0 -> 10.0; else -> 1.0 }
            ctrl.touch()
        }) {
            ctrl.frame
            ButtonText("×${s.timeScale.roundToInt()}", size = 14.sp, weight = FontWeight.Bold)
        }
        SquareButton({ ctrl.paused = !ctrl.paused }) {
            ButtonText(if (ctrl.paused) "▶" else "II", size = 15.sp, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SectionsBar(ctrl: DemoController, modifier: Modifier) {
    ctrl.frame
    val s = ctrl.session
    Row(
        modifier.overlay().padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Segmented(
            options = listOf("AUTO", "RĘCZNIE"),
            selected = if (s.autoSections) 0 else 1,
            onSelect = {
                if (it == 1 && s.autoSections) for (i in s.manualSections.indices) s.manualSections[i] = true
                s.autoSections = it == 0
                ctrl.touch()
            },
            selectedColor = { if (it == 0) Ss.Accent else Ss.Warning },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until s.config.sections) {
                val on = s.sectionOn[i]
                val armed = !s.autoSections && s.manualSections[i]
                val bg = when { on -> Ss.Accent; armed -> Ss.AccentSoft; else -> Ss.SurfaceHigh }
                val fg = if (on) Ss.OnAccent else if (armed) Ss.Accent else Ss.TextSoft
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                        .border(1.dp, if (armed && !on) Ss.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) {
                            if (s.autoSections) {
                                for (k in s.manualSections.indices) s.manualSections[k] = true
                                s.autoSections = false
                            }
                            s.manualSections[i] = !s.manualSections[i]
                            ctrl.touch()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FinishedBanner(ctrl: DemoController, modifier: Modifier) {
    ctrl.frame
    if (!ctrl.session.finished) return
    Column(
        modifier.overlay().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigValue("Pole skończone", size = 24.sp)
        Text("Obrobiono ${(ctrl.session.workedM2 / 10_000).pl(2)} ha", color = Ss.TextSoft, fontSize = 15.sp)
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = { ctrl.restart() }) {
            ButtonText("Jedź jeszcze raz", color = Ss.OnAccent, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RightPanel(ctrl: DemoController) {
    ctrl.frame
    val s = ctrl.session
    Column(
        Modifier.width(248.dp).fillMaxHeight().background(Ss.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // duży przycisk autosteru
        val shape = RoundedCornerShape(20.dp)
        Column(
            Modifier.fillMaxWidth().height(140.dp).clip(shape)
                .background(if (s.engaged) Ss.Accent else Color.Transparent)
                .border(3.dp, Ss.Accent, shape)
                .clickable(role = Role.Button, enabled = !s.finished) { s.engaged = !s.engaged; ctrl.touch() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            val fg = if (s.engaged) Ss.OnAccent else Ss.Text
            NavIcon(IconKind.Steer, fg, 38.dp)
            Text(if (s.engaged) "AUTOSTER" else "RĘCZNIE", color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                if (s.engaged) "Włączony · dotknij, aby wyłączyć" else "Dotknij, aby włączyć",
                color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ss.Panel2).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val left = (s.interiorAreaM2 - s.workedM2).coerceAtLeast(0.0)
            Row {
                Box(Modifier.weight(1f)) { Stat("Prędkość", "${s.speedKmh.pl(1)} km/h", valueSize = 18.sp) }
                Box(Modifier.weight(1f)) { Stat("Obrobione", "${(s.workedM2 / 10_000).pl(2)} ha", valueSize = 18.sp) }
            }
            Row {
                Box(Modifier.weight(1f)) { Stat("Wydajność", "${s.workRateHaH.pl(1)} ha/h", valueSize = 18.sp) }
                Box(Modifier.weight(1f)) { Stat("Zostało", "${(left / 10_000).pl(2)} ha", valueSize = 18.sp) }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Przesuń linię · ${if (s.lineShiftCm == 0) "0" else (s.lineShiftCm.toDouble()).pl(0, plusSign = true)} cm")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(Modifier.weight(1f), onClick = { s.lineShiftCm -= 2; ctrl.touch() }) { NavIcon(IconKind.ChevronLeft, Ss.Text, 22.dp, 2.4f) }
                ActionButton(Modifier.weight(1f), onClick = { s.lineShiftCm += 2; ctrl.touch() }) { NavIcon(IconKind.ChevronRight, Ss.Text, 22.dp, 2.4f) }
            }
        }

        ActionButton(
            Modifier.fillMaxWidth(),
            background = if (s.autoUturn) Ss.AccentSoft else Ss.SurfaceHigh,
            onClick = { s.autoUturn = !s.autoUturn; ctrl.touch() },
        ) {
            Text("Auto‑zawrót", Modifier.weight(1f), color = Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            ButtonText(if (s.autoUturn) "WŁ." else "WYŁ.", color = if (s.autoUturn) Ss.Accent else Ss.TextMuted, weight = FontWeight.Bold)
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Prędkość symulacji")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Modifier.width(52.dp), onClick = { s.targetSpeedKmh = (s.targetSpeedKmh - 0.5).coerceAtLeast(0.0); ctrl.touch() }) {
                    NavIcon(IconKind.Minus, Ss.Text, 20.dp)
                }
                Text(
                    "${s.targetSpeedKmh.pl(1)} km/h",
                    color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                ActionButton(Modifier.width(52.dp), onClick = { s.targetSpeedKmh = (s.targetSpeedKmh + 0.5).coerceAtMost(20.0); ctrl.touch() }) {
                    NavIcon(IconKind.Plus, Ss.Text, 20.dp)
                }
            }
        }

        Box(Modifier.weight(1f))

        ActionButton(Modifier.fillMaxWidth(), onClick = { ctrl.restart() }) {
            NavIcon(IconKind.Restart, Ss.TextSoft, 20.dp)
            ButtonText("Zacznij od nowa", color = Ss.TextSoft)
        }
    }
}
