package eu.smartsteer.navigator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import androidx.compose.foundation.Image
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.DisplayPrefs
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.settings.nowText
import eu.smartsteer.navigator.ui.brandImage
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.UpdateBanner
import eu.smartsteer.navigator.ui.components.KeypadHost
import eu.smartsteer.navigator.ui.TreatmentDialog
import eu.smartsteer.navigator.ui.UpdateController
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.NavIcon
import eu.smartsteer.navigator.ui.components.Pill
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.screens.ConnectionsScreen
import eu.smartsteer.navigator.ui.screens.FieldsScreen
import eu.smartsteer.navigator.ui.screens.HardwareFieldsScreen
import eu.smartsteer.navigator.ui.screens.HardwareMapScreen
import eu.smartsteer.navigator.ui.screens.ImplementScreen
import eu.smartsteer.navigator.ui.screens.MapScreen
import eu.smartsteer.navigator.ui.screens.SettingsScreen
import eu.smartsteer.navigator.ui.screens.HardwareSteerScreen
import eu.smartsteer.navigator.ui.screens.HardwareImplementScreen
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
    Sprayer("Oprysk", IconKind.Sprayer),
    Connections("Połączenia", IconKind.Network),
    Settings("Ustawienia", IconKind.Settings),
}

/** [hwIn] i [startScreen] — tylko do zrzutów ekranu w testach. */
@Composable
fun App(hwIn: HardwareController? = null, startScreen: Screen = Screen.Map) {
    SmartSteerTheme {
        val ctrl = remember { DemoController() }
        var screen by remember { mutableStateOf(startScreen) }
        val upd = remember { UpdateController() }
        val hw = remember { hwIn ?: HardwareController() }
        // miniatura opryskiwacza na Mapie ↔ ekran Opryskiwacz
        LaunchedEffect(hw.sprayer.navRequest) {
            hw.sprayer.navRequest?.let { n -> Screen.entries.firstOrNull { it.name == n }?.let { screen = it }; hw.sprayer.navRequest = null }
        }
        // X w oknie / przycisk Zamknij: najpierw zapis pola i wyłączenie autosteru
        LaunchedEffect(hw) { eu.smartsteer.navigator.ui.power.AppExit.onRequest = { hw.closeApp() } }

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

        // dźwięki: zdarzenia (autoster, GPS, zawrót, sekcje, zlecenia) sprawdzane co 100 ms
        LaunchedEffect(hw) {
            while (true) {
                delay(100)
                hw.soundStep()
            }
        }

        // Interfejs jest zaprojektowany na 1280 × 780 dp. Na mniejszych ekranach (telefon, mały tablet)
        // skalujemy całość proporcjonalnie, żeby nic nie było ucięte.
        BoxWithConstraints(Modifier.fillMaxSize().background(Ss.Ground).windowInsetsPadding(WindowInsets.safeDrawing)) {
            val d = LocalDensity.current
            val fit = minOf(maxWidth.value / DESIGN_W, maxHeight.value / DESIGN_H)
            // tryb tablet: całość powiększona do ekranu (większe przyciski); zwykły: tylko zmniejszanie, gdy ekran mały
            val scale = (if (DisplayPrefs.tablet) fit.coerceAtMost(2.5f) else minOf(1f, fit)).coerceAtLeast(0.35f)
            CompositionLocalProvider(LocalDensity provides Density(d.density * scale, d.fontScale)) {
        Column(Modifier.fillMaxSize().background(Ss.Ground)) {
            TopBar(ctrl, hw)
            UpdateBar(upd, ctrl, hw)
            Row(Modifier.fillMaxSize()) {
                NavRail(screen, onClose = { hw.closeApp() }) { screen = it }
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.Map -> if (hw.hardware) HardwareMapScreen(hw) else MapScreen(ctrl)
                        Screen.Fields -> if (hw.hardware) HardwareFieldsScreen(hw) else FieldsScreen(ctrl)
                        Screen.Steer -> if (hw.hardware) HardwareSteerScreen(hw) else SteerScreen(ctrl)
                        Screen.Implement -> if (hw.hardware) HardwareImplementScreen(hw) else ImplementScreen(ctrl)
                        Screen.Sprayer -> eu.smartsteer.navigator.ui.screens.SprayerScreen(hw)
                        Screen.Connections -> ConnectionsScreen(ctrl, hw)
                        Screen.Settings -> SettingsScreen(hw)
                    }
                }
            }
        }
        PowerBanner(hw)
        // po zamknięciu pola z WAR: „co robiłeś?” (pod klawiaturą, która otwiera się nad oknem)
        if (hw.hardware) TreatmentDialog(hw.war)
        // klawiatura na ekranie (liczby; tekst na Windows) — nad wszystkim
        KeypadHost()
            }
        }
    }
}

/** Pasek odliczania po zaniku zasilania z ciągnika. */
@Composable
private fun PowerBanner(hw: HardwareController) {
    if (hw.exiting) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Text("Zapisuję pole i zamykam…", color = androidx.compose.ui.graphics.Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    val left = hw.powerCountdown ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.padding(top = 80.dp).clip(RoundedCornerShape(16.dp)).background(Ss.Danger).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Brak zasilania z ciągnika — ${if (hw.powerShutdownPc) "wyłączenie komputera" else "zamknięcie programu"} za $left s",
                color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(androidx.compose.ui.graphics.Color.White)
                    .clickable(role = Role.Button) { hw.cancelPowerExit() }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("Anuluj", color = Ss.Danger, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

private const val DESIGN_W = 1280f
private const val DESIGN_H = 780f

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
    if (hwOn) { HwTopBar(hw); return }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(Ss.Surface).padding(start = 20.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.width(224.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val brand = remember { brandImage() }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (brand != null) {
                    // „WAR Navigator” ze znakiem WAR (branding/war-navigator-naglowek.svg)
                    Image(brand, contentDescription = "WAR Navigator", modifier = Modifier.height(30.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NavIcon(IconKind.Logo, Ss.Accent, 26.dp)
                        Text("WAR Navigator", color = Ss.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
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

/**
 * Górny pasek w trybie Sprzęt (układ jak w nawigacjach rolniczych, kolory WAR):
 * godzina · RTK i satelity · wiek poprawek · dokładność · lightbar z odchyłką · autoster · wydajność i koniec · prędkość · powierzchnia.
 */
@Composable
private fun HwTopBar(hw: HardwareController) {
    hw.tick
    val h = hw.session
    val now = hw.now()
    val gpsOk = h.gpsAlive(now)
    val g = h.lastGga
    val rtk = gpsOk && h.fix == FixQuality.RTK_FIX
    val stamp = remember(hw.tick / 50) { runCatching { nowText() }.getOrDefault("") }
    val clock = stamp.takeLast(5)
    val stateColor = when {
        !gpsOk -> Ss.Danger
        h.engaged -> Ss.Accent
        !rtk -> Ss.Warning
        else -> Ss.Leaf
    }
    Column(Modifier.fillMaxWidth().background(Ss.Bar)) {
        Row(
            Modifier.fillMaxWidth().height(62.dp).padding(start = 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val brand = remember { brandImage() }
            if (brand != null) Image(brand, contentDescription = "WAR Navigator", modifier = Modifier.height(26.dp))
            else NavIcon(IconKind.Logo, Ss.Accent, 26.dp)
            Text(clock, color = Ss.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            // RTK + satelity
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val label = if (gpsOk) h.fix.label else "BRAK GPS"
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(if (rtk) Ss.Accent else if (gpsOk) Ss.Warning else Ss.Danger)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) { Text(label, color = if (rtk) Ss.OnAccent else Ss.Ground, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                Text(
                    "${if (gpsOk && g != null) g.satellites else "—"} SAT${if (hw.ntripReceiving) " · NTRIP" else ""}",
                    color = Ss.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            TopStat(g?.correctionAge?.takeIf { gpsOk }?.let { "${it.pl(0)} s" } ?: "—", "POPRAWKI",
                if (gpsOk && (g?.correctionAge ?: 99.0) <= 5) Ss.Text else Ss.Warning)
            // zmierzona (GST z odbiornika / telefonu): „±1,4 cm”, „±3,2 m”; bez GST — przybliżenie dla rodzaju poprawki
            val acc = h.horizontalAccuracy(now)
            TopStat(if (gpsOk) acc?.takeIf { it.second }?.let { "±" + metersText(it.first) } ?: accuracyText(h.fix) else "—", "DOKŁADNOŚĆ",
                if (rtk && (acc == null || acc.first < 0.05)) Ss.Text else Ss.Warning)
            val hasLine = h.line != null || h.curveRef != null || h.contour.guide.isNotEmpty()
            HwLightBar(if (hasLine && gpsOk) h.xteCm else null, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NavIcon(IconKind.Wheel, if (h.engaged) Ss.Accent else Ss.TextMuted, 26.dp, 2.2f)
                Text(if (h.engaged) "AUTO" else "RĘCZNIE", color = if (h.engaged) Ss.Accent else Ss.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            // wydajność i przewidywany koniec (gdy znamy granicę pola)
            val haH = h.speedKmh * h.profile.passSpacing / 10.0
            val left = h.boundary?.let { (Polygon.area(it) - h.coverage.workedM2).coerceAtLeast(0.0) / 10_000 }
            val end = if (left != null && haH > 0.3 && h.sections.anyOn()) endClock(clock, left / haH) else null
            TopStat("${haH.pl(1)} ha/h", if (end != null) "KONIEC $end" else "WYDAJNOŚĆ")
            TopStat("${h.speedKmh.pl(1)} km/h", "PRĘDKOŚĆ")
            TopStat("${(h.coverage.workedM2 / 10_000).pl(2)} ha", "POWIERZCHNIA")
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(stateColor))
    }
}

@Composable
private fun TopStat(value: String, label: String, color: androidx.compose.ui.graphics.Color = Ss.Text) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Ss.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** 1,4 cm / 35 cm / 3,2 m. */
private fun metersText(m: Double): String = when {
    m < 0.1 -> "${((m * 1000).toInt() / 10.0).toString().replace('.', ',')} cm"
    m < 1.0 -> "${(m * 100).toInt()} cm"
    else -> "${((m * 10).toInt() / 10.0).toString().replace('.', ',')} m"
}

/** Przybliżona dokładność pozycji dla danego rodzaju poprawki. */
private fun accuracyText(f: FixQuality) = when (f) {
    FixQuality.RTK_FIX -> "2 cm"
    FixQuality.RTK_FLOAT -> "20 cm"
    FixQuality.DGPS -> "50 cm"
    FixQuality.GPS -> "1.5 m"
    else -> "—"
}

/** Godzina „hh:mm” za [hours] godzin. */
private fun endClock(clock: String, hours: Double): String? {
    val parts = clock.split(":")
    val hh = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val mm = parts.getOrNull(1)?.toIntOrNull() ?: return null
    if (hours > 48) return null
    val t = (hh * 60 + mm + (hours * 60).toInt()) % (24 * 60)
    return "${(t / 60).toString().padStart(2, '0')}:${(t % 60).toString().padStart(2, '0')}"
}

/** Lightbar z odchyłką w środku: kratki po 2 cm na lewo i prawo, [xteCm] = null — brak linii. */
@Composable
private fun HwLightBar(xteCm: Double?, modifier: Modifier) {
    val x = xteCm ?: 0.0
    val a = abs(x)
    val lit = if (xteCm == null || a < 1.5) 0 else minOf(8, ceil(a / 2.0).toInt())
    val centerColor = when {
        xteCm == null -> Ss.SurfaceHigh
        lit == 0 -> Ss.Accent
        lit < 5 -> Ss.Warning
        else -> Ss.Danger
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
        for (d in -8..8) {
            if (d == 0) {
                Box(
                    Modifier.width(92.dp).height(42.dp).clip(RoundedCornerShape(10.dp)).background(centerColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (xteCm == null) "—" else "${a.pl(if (a < 10) 1 else 0)}",
                        color = if (xteCm == null) Ss.TextMuted else Ss.Ground, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    )
                }
                continue
            }
            val on = lit > 0 && abs(d) <= lit && (d > 0) == (x > 0)
            val color = when {
                on -> if (abs(d) >= 5) Ss.Danger else Ss.Warning
                else -> Ss.Border
            }
            Box(Modifier.size(width = 9.dp, height = if (abs(d) % 4 == 0) 22.dp else 14.dp).clip(RoundedCornerShape(3.dp)).background(color))
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
private fun NavRail(current: Screen, onClose: () -> Unit, onSelect: (Screen) -> Unit) {
    Column(
        Modifier.width(88.dp).fillMaxHeight().background(Ss.Surface).padding(vertical = 10.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Screen.entries.forEach { s ->
            val active = s == current
            val color = if (active) Ss.Accent else Ss.TextMuted
            Column(
                Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (active) Ss.AccentSoft else Ss.Surface)
                    .clickable(role = Role.Tab) { onSelect(s) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            ) {
                NavIcon(s.icon, color)
                Text(s.label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        }
        Box(Modifier.weight(1f))
        RailToggle(if (DisplayPrefs.day) "DZIEŃ" else "NOC", DisplayPrefs.day) { DisplayPrefs.updateDay(!DisplayPrefs.day) }
        RailToggle("TABLET", DisplayPrefs.tablet) { DisplayPrefs.updateTablet(!DisplayPrefs.tablet) }
        if (DisplayPrefs.fullScreenSupported) RailToggle("PEŁNY\nEKRAN", DisplayPrefs.fullScreen) { DisplayPrefs.toggleFullScreen() }
        CloseButton(onClose)
    }
}

/** Zamknięcie programu: pierwsze dotknięcie pyta, drugie (w ciągu 4 s) zapisuje wszystko i zamyka. */
@Composable
private fun CloseButton(onClose: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp))
            .background(if (armed) Ss.Danger else Ss.SurfaceHigh)
            .clickable(role = Role.Button) { if (armed) onClose() else armed = true },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (armed) "NA PEWNO?\nDOTKNIJ" else "ZAMKNIJ",
            color = if (armed) androidx.compose.ui.graphics.Color.White else Ss.Danger, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 12.sp,
        )
    }
}

/** Mały przełącznik na dole paska nawigacji (tryb tablet / pełny ekran). */
@Composable
private fun RailToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(10.dp))
            .background(if (on) Ss.AccentSoft else Ss.SurfaceHigh)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (on) Ss.Accent else Ss.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 12.sp,
        )
    }
}

