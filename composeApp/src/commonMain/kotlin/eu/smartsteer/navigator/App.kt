package eu.smartsteer.navigator

import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.UpdateBanner
import eu.smartsteer.navigator.ui.UpdateController
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.NavIcon
import eu.smartsteer.navigator.ui.components.Pill
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.screens.ConnectionsScreen
import eu.smartsteer.navigator.ui.screens.FieldsScreen
import eu.smartsteer.navigator.ui.screens.HardwareMapScreen
import eu.smartsteer.navigator.ui.screens.ImplementScreen
import eu.smartsteer.navigator.ui.screens.MapScreen
import eu.smartsteer.navigator.ui.screens.SettingsScreen
import eu.smartsteer.navigator.ui.screens.SteerScreen
import eu.smartsteer.navigator.ui.theme.SmartSteerTheme
import eu.smartsteer.navigator.ui.theme.Ss
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil

enum class Screen(val label: String, val icon: IconKind) {
    Map("Mapa", IconKind.Map),
    Fields("Pola", IconKind.Field),
    Steer("Sterowanie", IconKind.Steer),
    Implement("Maszyna", IconKind.Implement),
    Connections("Połączenia", IconKind.Network),
    Settings("Ustawienia", IconKind.Settings),
}

@Composable
fun App() {
    SmartSteerTheme {
        val ctrl = remember { DemoController() }
        var screen by remember { mutableStateOf(Screen.Map) }
        val upd = remember { UpdateController() }
        val hw = remember { HardwareController() }

        // sieć modułów (tryb Sprzęt): odbiór i wysyłka PGN 254 co 100 ms
        LaunchedEffect(hw) { hw.run() }

        // aktualizacje: pierwsze sprawdzenie chwilę po starcie, potem co kilka godzin
        LaunchedEffect(upd) {
            delay(UpdateController.FIRST_CHECK_MS)
            while (true) {
                upd.check()
                delay(UpdateController.INTERVAL_MS)
            }
        }

        // pętla symulacji: krok co klatkę ekranu
        LaunchedEffect(ctrl) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    ctrl.advance((now - last) / 1e9)
                    last = now
                }
            }
        }

        Column(Modifier.fillMaxSize().background(Ss.Ground)) {
            TopBar(ctrl, hw)
            UpdateBar(upd, ctrl, hw)
            Row(Modifier.fillMaxSize()) {
                NavRail(screen) { screen = it }
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Map -> if (hw.hardware) HardwareMapScreen(hw) else MapScreen(ctrl)
                        Screen.Fields -> FieldsScreen(ctrl)
                        Screen.Steer -> SteerScreen(ctrl)
                        Screen.Implement -> ImplementScreen(ctrl)
                        Screen.Connections -> ConnectionsScreen(ctrl, hw)
                        Screen.Settings -> SettingsScreen(hw)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBar(upd: UpdateController, ctrl: DemoController, hw: HardwareController) {
    ctrl.frame // stan autosteru odświeżany co klatkę
    hw.tick
    UpdateBanner(upd, autosteerOn = ctrl.session.engaged || hw.session.engaged)
}

@Composable
private fun TopBar(ctrl: DemoController, hw: HardwareController) {
    ctrl.frame
    hw.tick
    val s = ctrl.session
    val h = hw.session
    val hwOn = hw.hardware
    val gpsOk = hwOn && h.gpsAlive(hw.now())
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Ss.Surface).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.width(224.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NavIcon(IconKind.Logo, Ss.Accent, 30.dp)
            Column {
                Text("SmartSteer", color = Ss.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(if (hwOn) "Sprzęt · płytka AIO" else "Pole demo · symulator", color = Ss.TextMuted, fontSize = 12.sp)
            }
        }
        LightBar(if (hwOn) (if (h.line != null && gpsOk) h.xteCm else 0.0) else s.xteCm, Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hwOn) {
                val fix = if (gpsOk) h.fix.label else "BRAK GPS"
                if (gpsOk && h.fix == eu.smartsteer.navigator.core.gps.FixQuality.RTK_FIX) Pill(fix, Ss.Accent, Ss.AccentSoft) else Pill(fix, Ss.Warning)
                Pill("SPRZĘT", Ss.Info)
            } else {
                Pill("RTK FIX", Ss.Accent, Ss.AccentSoft)
                Pill("SYMULATOR", Ss.Warning)
            }
            Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (hwOn) h.speedKmh else s.speedKmh).pl(1), color = Ss.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("km/h", color = Ss.TextMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** Lightbar: każda kratka = 2 cm odchyłki, środek świeci na zielono, gdy jesteśmy na linii. */
@Composable
private fun LightBar(xteCm: Double, modifier: Modifier) {
    val a = abs(xteCm)
    val lit = if (a < 1.5) 0 else minOf(7, ceil(a / 2.0).toInt())
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until 15) {
                val d = i - 7
                val on = d != 0 && abs(d) <= lit && (d > 0) == (xteCm > 0)
                val color = when {
                    d == 0 -> if (lit == 0) Ss.Accent else Ss.Dim
                    on -> if (abs(d) >= 5) androidx.compose.ui.graphics.Color(0xFFFF7A59) else Ss.Warning
                    else -> Ss.Border
                }
                Box(Modifier.size(width = 22.dp, height = if (d == 0) 22.dp else 14.dp).clip(RoundedCornerShape(4.dp)).background(color))
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Odchyłka", color = Ss.TextMuted, fontSize = 12.sp)
            Text(
                "${xteCm.pl(1, plusSign = true)} cm",
                color = if (lit == 0) Ss.Accent else Ss.Warning, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                when { lit == 0 -> "na linii"; xteCm > 0 -> "w prawo"; else -> "w lewo" },
                color = Ss.TextMuted, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun NavRail(current: Screen, onSelect: (Screen) -> Unit) {
    Column(
        Modifier.width(88.dp).fillMaxHeight().background(Ss.Surface).padding(vertical = 10.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Screen.entries.forEach { s ->
            val active = s == current
            val color = if (active) Ss.Accent else Ss.TextMuted
            Column(
                Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (active) Ss.AccentSoft else Ss.Surface)
                    .clickable(role = Role.Tab) { onSelect(s) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            ) {
                NavIcon(s.icon, color)
                Text(s.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
