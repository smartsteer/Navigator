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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
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
import eu.smartsteer.navigator.core.field.Coverage
import eu.smartsteer.navigator.core.field.SectionMode
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.guidance.Pose
import eu.smartsteer.navigator.core.hw.GuidanceMode
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.DisplayPrefs
import eu.smartsteer.navigator.core.map.TileMath
import eu.smartsteer.navigator.core.map.TileSource
import eu.smartsteer.navigator.ui.map.TileManager
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.BigValue
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.Keypad
import eu.smartsteer.navigator.ui.components.KeypadMode
import eu.smartsteer.navigator.ui.components.KeypadRequest
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
    var zoom by remember { mutableStateOf(20f) }
    var headingUp by remember { mutableStateOf(true) }
    var tilt by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        HwCanvas(hw, zoom, headingUp, tilt, locked, Modifier.fillMaxSize())

        // lewa kolumna narzędzi mapy
        Column(
            Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MapTool(IconKind.Follow, headingUp, enabled = !locked, label = if (headingUp) "KURS" else "N") { headingUp = !headingUp }
            MapTool(null, tilt, enabled = !locked, label = "3D") { tilt = !tilt }
            MapTool(if (locked) IconKind.Lock else IconKind.Unlock, locked, enabled = true, label = null) { locked = !locked }
            // kamera zewnętrzna (np. z tyłu maszyny): zawsze pod ręką; bez adresu — dotknięcie pyta o adres
            run {
                val cam = eu.smartsteer.navigator.ui.camera.Camera
                MapTool(null, cam.running, enabled = true, label = "KAM") {
                    if (cam.running) cam.stop()
                    else if (hw.cameraUrl.isNotBlank()) cam.start(hw.cameraUrl)
                    else askCameraUrl(hw)
                }
            }
            val bg = TileSource.entries.getOrElse(DisplayPrefs.background) { TileSource.NONE }
            MapTool(null, bg != TileSource.NONE, enabled = !locked, label = bg.short) {
                DisplayPrefs.updateBackground((DisplayPrefs.background + 1) % TileSource.entries.size)
            }
            Column(Modifier.width(52.dp).hwOverlay()) {
                Box(Modifier.size(52.dp).clickable(enabled = !locked, role = Role.Button) { zoom = (zoom * 1.3f).coerceAtMost(80f) }, contentAlignment = Alignment.Center) {
                    NavIcon(IconKind.Plus, Ss.Text, 22.dp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ss.Border))
                Box(Modifier.size(52.dp).clickable(enabled = !locked, role = Role.Button) { zoom = (zoom / 1.3f).coerceAtLeast(1.5f) }, contentAlignment = Alignment.Center) {
                    NavIcon(IconKind.Minus, Ss.Text, 22.dp)
                }
            }
        }

        HwInfo(hw, Modifier.align(Alignment.BottomStart).padding(start = 76.dp, bottom = 16.dp))
        OrderBanner(hw, Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
        ObstacleBanner(hw, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        FieldSuggestion(hw, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
        HeadlandHint(hw, Modifier.align(Alignment.TopCenter).padding(top = 170.dp))
        KTurnBanner(hw, Modifier.align(Alignment.Center).padding(bottom = 160.dp))
        RatePill(hw, Modifier.align(Alignment.TopCenter).padding(top = 215.dp))
        WeatherChip(hw, Modifier.align(Alignment.TopStart).padding(start = 76.dp, top = 12.dp))
        FleetChip(hw, Modifier.align(Alignment.TopStart).padding(start = 76.dp, top = 112.dp))
        VoiceButton(hw, Modifier.align(Alignment.TopStart).padding(start = 344.dp, top = 112.dp))
        // WAR Cam (Android): okienko ze stroną kamery WAR — działa, dopóki jest na ekranie
        if (hw.war.camOn) hw.war.cameraUrl()?.let { url ->
            Box(Modifier.align(Alignment.BottomStart).padding(start = 76.dp, bottom = 96.dp).size(220.dp, 165.dp).hwOverlay()) {
                eu.smartsteer.navigator.ui.war.WarCamPane(url, Modifier.fillMaxSize())
                Text("✕ WAR Cam", Modifier.align(Alignment.TopEnd).background(Ss.Bar.copy(alpha = 0.85f)).clickable { hw.war.stopCamera() }
                    .padding(horizontal = 8.dp, vertical = 4.dp), color = Ss.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        TunePendingCard(hw, Modifier.align(Alignment.TopStart).padding(start = 76.dp, top = 60.dp).width(380.dp))

        // prawa kolumna: linia, zawrót, sekcje
        RightTools(hw, sheet, locked, { sheet = !sheet }, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp))
        if (sheet) {
            GuideSheet(hw, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 84.dp)) { sheet = false }
        }

        if (hw.simulating) SimControls(hw, locked, Modifier.align(Alignment.BottomStart).padding(start = 76.dp, bottom = 190.dp))
        SprayerMini(hw, Modifier.align(Alignment.TopEnd).padding(top = 354.dp, end = 12.dp))
        HwSectionsBar(hw, locked, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
        HwMessage(hw, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
        if (hw.session.gpsTooPoorForSections) Text(
            "Słaby GPS — sekcje nie pracują, aż pozycja będzie dokładniejsza niż 10 m", color = Ss.Ground, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 156.dp).clip(RoundedCornerShape(10.dp)).background(Ss.Warning).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        if (hw.session.steerBlockedReason == null) SteerButton(hw, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
        else Text(
            "GPS telefonu · prowadzenie ręczne", color = Ss.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp).clip(RoundedCornerShape(10.dp)).background(Ss.Bar.copy(alpha = 0.9f)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        CameraView(hw, Modifier.align(Alignment.BottomEnd).padding(end = 130.dp, bottom = 100.dp))
        TileSource.entries.getOrElse(DisplayPrefs.background) { TileSource.NONE }.takeIf { it != TileSource.NONE }?.let {
            Text(it.attribution, Modifier.align(Alignment.BottomEnd).padding(end = 130.dp, bottom = 4.dp), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
        }
    }
}

/** Okrągły przycisk na mapie (lewa kolumna). */
@Composable
private fun MapTool(icon: IconKind?, active: Boolean, enabled: Boolean, label: String?, onClick: () -> Unit) {
    Column(
        Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (active) Ss.AccentSoft else Ss.Bar.copy(alpha = 0.92f))
            .border(1.dp, if (active) Ss.Accent else Ss.Border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val c = if (active) Ss.Accent else Ss.Text
        if (icon != null) NavIcon(icon, c, if (label != null) 20.dp else 24.dp)
        if (label != null) Text(label, color = c, fontSize = if (icon == null) 16.sp else 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Prawa kolumna narzędzi: linia prowadzenia, auto-zawrót, sekcje AUTO / WYŁ. */
@Composable
private fun RightTools(hw: HardwareController, sheetOpen: Boolean, locked: Boolean, onSheet: () -> Unit, modifier: Modifier) {
    hw.tick
    val s = hw.session
    val sec = s.sections
    Column(
        modifier.width(64.dp).clip(RoundedCornerShape(18.dp)).background(Ss.Bar.copy(alpha = 0.94f))
            .border(1.dp, Ss.Border, RoundedCornerShape(18.dp)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundTool(IconKind.LineAB, sheetOpen, !locked, s.mode.label, onClick = onSheet)
        RoundTool(IconKind.UTurn, s.autoUturn, !locked && s.mode != GuidanceMode.CONTOUR, if (s.autoUturn) (if (s.uTurnRight) "▶" else "◀") else "zawrót") {
            s.autoUturn = !s.autoUturn; if (!s.autoUturn) s.cancelTurn(); hw.touch()
        }
        RoundTool(IconKind.SectionsAuto, sec.mode == SectionMode.AUTO, !locked, "AUTO") { sec.mode = SectionMode.AUTO; hw.touch() }
        RoundTool(IconKind.SectionsOff, sec.mode == SectionMode.OFF, !locked, "WYŁ.", warn = true) { sec.mode = SectionMode.OFF; hw.touch() }
        if (s.profile.hydEnabled && s.profile.hydAuto) {
            val st = s.hydLiftState()
            RoundTool(IconKind.Implement, s.hydLiftOn, !locked, if (!s.hydLiftOn) "podnośnik" else if (st == 2) "↑ GÓRA" else if (st == 1) "↓ DÓŁ" else "brak uwr.") {
                s.hydLiftOn = !s.hydLiftOn; hw.touch()
            }
        }
    }
}

@Composable
private fun RoundTool(icon: IconKind, active: Boolean, enabled: Boolean, label: String, warn: Boolean = false, onClick: () -> Unit) {
    val on = if (warn) Ss.Warning else Ss.Accent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(50.dp).clip(CircleShape).background(if (active) on else Ss.SurfaceHigh)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { NavIcon(icon, if (active) Ss.OnAccent else Ss.Text, 26.dp) }
        Text(label, color = if (active) on else Ss.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Duży okrągły przycisk autosteru (prawy dolny róg). */
@Composable
private fun SteerButton(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val s = hw.session
    val now = hw.now()
    val hasLine = when (s.mode) {
        GuidanceMode.AB -> s.line != null
        GuidanceMode.CURVE -> s.curveRef != null
        GuidanceMode.CONTOUR -> s.contour.guide.isNotEmpty()
    }
    val ready = hasLine && s.gpsAlive(now) && s.autosteerAlive(now)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(96.dp).clip(CircleShape)
                .background(if (s.engaged) Ss.Accent else Ss.Bar.copy(alpha = 0.94f))
                .border(4.dp, if (s.engaged || ready) Ss.Accent else Ss.Dim, CircleShape)
                .clickable(role = Role.Button) { s.setEngaged(!s.engaged, now) },
            contentAlignment = Alignment.Center,
        ) { NavIcon(IconKind.Wheel, if (s.engaged) Ss.OnAccent else if (ready) Ss.Accent else Ss.TextMuted, 54.dp, 2.4f) }
        Text(
            when {
                s.engaged -> "AUTOSTER"
                ready -> "gotowy"
                !hasLine -> "brak linii"
                !s.gpsAlive(now) -> "brak GPS"
                else -> "brak modułu"
            },
            color = if (s.engaged) Ss.Accent else Ss.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        )
    }
}

/** Wysuwany panel linii: tryb AB / krzywa / kontur, przyciski A-B, przesunięcie, zawrót, odczyty. */
@Composable
private fun GuideSheet(hw: HardwareController, modifier: Modifier, onClose: () -> Unit) {
    hw.tick
    val s = hw.session
    Column(
        modifier.width(280.dp).clip(RoundedCornerShape(18.dp)).background(Ss.Bar.copy(alpha = 0.97f))
            .border(1.dp, Ss.Border, RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Linia prowadzenia", Modifier.weight(1f), color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ActionButton(Modifier.width(52.dp), onClick = onClose) { ButtonText("✕", size = 16.sp) }
        }
        Segmented(GuidanceMode.entries.map { it.label }, s.mode.ordinal, { s.setMode(GuidanceMode.entries[it]); hw.touch() }, Modifier.fillMaxWidth())
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
        if (s.mode != GuidanceMode.CONTOUR) TurnControls(hw)
        TrackList(hw)
        PlanControls(hw)
        FlagControls(hw)
        HwStats(hw)
    }
}

/** Symulacja jazdy: prędkość i ręczny skręt (gdy autoster nie prowadzi). */
@Composable
private fun SimControls(hw: HardwareController, locked: Boolean, modifier: Modifier) {
    hw.tick
    val sim = hw.sim
    Row(
        modifier.hwOverlay().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.padding(horizontal = 6.dp)) {
            Text("SYMULACJA", color = Ss.Warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("${sim.targetSpeedKmh.pl(1)} km/h", color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        ActionButton(Modifier.width(52.dp), onClick = { if (!locked) { sim.targetSpeedKmh = (sim.targetSpeedKmh - 1).coerceAtLeast(-6.0); hw.touch() } }) { NavIcon(IconKind.Minus, Ss.Text, 20.dp) }
        ActionButton(Modifier.width(52.dp), onClick = { if (!locked) { sim.targetSpeedKmh = (sim.targetSpeedKmh + 1).coerceAtMost(25.0); hw.touch() } }) { NavIcon(IconKind.Plus, Ss.Text, 20.dp) }
        ActionButton(Modifier.width(52.dp), onClick = { if (!locked) { sim.manualSteerDeg = (sim.manualSteerDeg - 5).coerceAtLeast(-30.0); hw.touch() } }) { NavIcon(IconKind.ChevronLeft, Ss.Text, 20.dp) }
        ActionButton(Modifier.width(52.dp), onClick = { if (!locked) { sim.manualSteerDeg = 0.0; hw.touch() } }) { ButtonText("${sim.manualSteerDeg.pl(0)}°", size = 13.sp) }
        ActionButton(Modifier.width(52.dp), onClick = { if (!locked) { sim.manualSteerDeg = (sim.manualSteerDeg + 5).coerceAtMost(30.0); hw.touch() } }) { NavIcon(IconKind.ChevronRight, Ss.Text, 20.dp) }
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
            (s.fieldName?.let { "Pole „$it”" } ?: "Bez pola (nie zapisuje się)") + " · obrobiono ${(s.coverage.workedM2 / 10_000).pl(2)} ha",
            color = if (s.fieldName != null) Ss.TextSoft else Ss.Warning, fontSize = 13.sp,
        )
        Text(
            if (s.gpsAlive(now)) "${if (s.fix.label.startsWith("GPS")) s.fix.label else "GPS " + s.fix.label} · kurs $course° (${s.headingSource.label})" else "GPS: brak pozycji",
            color = if (s.gpsAlive(now)) Ss.TextSoft else Ss.Warning, fontSize = 13.sp,
        )
    }
}

/** Pogoda w miejscu ciągnika: wiatr (na czerwono przy oprysku, gdy za silny), temperatura. */
@Composable
private fun WeatherChip(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val w = hw.weather ?: return
    val bad = hw.profile.isSprayer && w.windMs > eu.smartsteer.navigator.core.weather.Weather.SPRAY_MAX_WIND
    Text(
        "Wiatr ${w.windMs.pl(1)} m/s${w.gustMs?.let { " (porywy ${it.pl(0)})" } ?: ""} ${w.windFrom}" +
            (w.tempC?.let { " · ${it.pl(0)}°C" } ?: "") + (w.humidity?.let { " · ${it.pl(0)}%" } ?: ""),
        modifier.clip(RoundedCornerShape(10.dp)).background(if (bad) Ss.Danger else Ss.Bar.copy(alpha = 0.9f)).padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (bad) androidx.compose.ui.graphics.Color.White else Ss.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** Pytanie o adres kamery IP (MJPEG albo zdjęcie JPEG) — zapis i od razu podgląd. */
private fun askCameraUrl(hw: HardwareController) {
    Keypad.open(KeypadRequest(
        title = "Adres kamery (z tyłu maszyny, siewnik…)",
        initial = hw.cameraUrl.ifBlank { "http://" },
        mode = KeypadMode.Text,
        hint = "Obraz MJPEG albo zdjęcie JPEG z kamery w sieci tabletu, np. http://192.168.5.50/video.mjpg",
        maxLength = 200,
    ) { t ->
        val url = t.trim()
        if (url.length > 8) { hw.setCamera(url); eu.smartsteer.navigator.ui.camera.Camera.start(url) }
    })
}

/** Podgląd z kamery: dotknięcie powiększa / zmniejsza. */
@Composable
private fun CameraView(hw: HardwareController, modifier: Modifier) {
    val cam = eu.smartsteer.navigator.ui.camera.Camera
    if (!cam.running) return
    var big by remember { mutableStateOf(false) }
    val w = if (big) 640.dp else 320.dp
    Box(
        modifier.width(w).height(w * 9 / 16).clip(RoundedCornerShape(12.dp)).background(Color.Black)
            .border(2.dp, Ss.Border, RoundedCornerShape(12.dp)).clickable { big = !big },
        contentAlignment = Alignment.Center,
    ) {
        val f = cam.frame
        if (f != null) androidx.compose.foundation.Image(f, contentDescription = "Kamera", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        else Text(cam.status, color = Color.White, fontSize = 13.sp)
        // zmiana adresu bez wchodzenia w Połączenia
        Text("adres", Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.6f)).clickable { askCameraUrl(hw) }
            .padding(horizontal = 10.dp, vertical = 8.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** Ciągnik wjechał na znane pole, a żadne nie jest otwarte. */
@Composable
private fun FieldSuggestion(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val n = hw.fieldSuggestion ?: return
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Ss.Bar.copy(alpha = 0.96f)).border(2.dp, Ss.Accent, RoundedCornerShape(14.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Jesteś na polu „$n”", color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp))
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = { hw.acceptFieldSuggestion() }) { ButtonText("Otwórz", color = Ss.OnAccent, weight = FontWeight.Bold) }
        ActionButton(onClick = { hw.dismissFieldSuggestion() }) { ButtonText("Nie", color = Ss.TextSoft) }
    }
}

/** Zawrót K i jazda do tyłu: duży napis, co teraz zrobić. */
@Composable
private fun KTurnBanner(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val s = hw.session
    val (text, warn) = when {
        s.kPhase == 2 && !s.reversing -> "STOP — WSTECZNY" to true
        s.kPhase == 2 -> "COFAJ ↓" to false
        s.kPhase == 3 && s.reversing -> "STOP — NAPRZÓD" to true
        s.reversing && !s.engaged -> "WSTECZ" to false
        else -> return
    }
    Text(
        text,
        modifier.clip(RoundedCornerShape(16.dp)).background(if (warn) Ss.Warning else Ss.Info).padding(horizontal = 28.dp, vertical = 14.dp),
        color = if (warn) Ss.OnWarning else Ss.Ground, fontSize = 28.sp, fontWeight = FontWeight.Bold,
    )
}

/** Dawka z mapy dawek w miejscu maszyny. */
@Composable
private fun RatePill(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val s = hw.session
    val rm = s.rateMap ?: return
    val (rate, zone) = s.rateNow() ?: return
    val p = s.profile
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Ss.Bar.copy(alpha = 0.92f)).border(1.dp, Ss.Accent, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Dawka ${rate.pl(0)} ${rm.unit}${if (zone < 0) " (poza strefami)" else ""}", color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (p.rateBase > 0 && rate > 0 && p.rateHintMode == 1) {
            // sterownik rozsiewacza z przyciskami ±krok%: ile razy nacisnąć względem 100%
            val pct = (rate / p.rateBase - 1) * 100
            val n = kotlin.math.round(pct / p.rateStepPct).toInt()
            val setPct = n * p.rateStepPct
            Text(
                when {
                    n == 0 -> "Sterownik: 100% (bez zmian)"
                    n > 0 -> "Sterownik: +${setPct.pl(0)}% (${n}× „+”)"
                    else -> "Sterownik: −${(-setPct).pl(0)}% (${-n}× „−”)"
                },
                color = if (n == 0) Ss.Accent else Ss.Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Text("= ${(p.rateBase * (1 + setPct / 100)).pl(0)} ${rm.unit} (mapa: ${rate.pl(0)})", color = Ss.TextSoft, fontSize = 13.sp)
        }
        // bez sterownika: dawka ∝ 1 / prędkość przy stałym otwarciu rozsiewacza
        if (p.rateBase > 0 && rate > 0 && p.rateHintMode == 0) {
            val vNeed = p.rateBase * p.rateBaseSpeed / rate
            val v = s.speedKmh
            val dv = vNeed - v
            val ok = v > 0.5 && kotlin.math.abs(dv) / vNeed < 0.05
            Text(
                when {
                    v < 0.5 -> "Jedź ${vNeed.pl(1)} km/h"
                    ok -> "Prędkość dobra (${vNeed.pl(1)} km/h)"
                    dv < 0 -> "ZWOLNIJ o ${(-dv).pl(1)} km/h → ${vNeed.pl(1)} km/h"
                    else -> "PRZYŚPIESZ o ${dv.pl(1)} km/h → ${vNeed.pl(1)} km/h"
                },
                color = if (ok) Ss.Accent else Ss.Warning, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            if (v > 0.5) {
                // albo przy tej prędkości: ustawienie dawki na rozsiewaczu
                val setting = rate * v / p.rateBaseSpeed
                val d = setting - p.rateBase
                Text(
                    "albo ustaw na rozsiewaczu ${setting.pl(0)} (${if (d >= 0) "+" else "−"}${kotlin.math.abs(d).pl(0)})",
                    color = Ss.TextSoft, fontSize = 13.sp,
                )
            }
        }
    }
}

/** Podpowiedź przy ręcznym podnoszeniu: ile metrów zostało maszynie do uwrocia / do pola. */
@Composable
private fun HeadlandHint(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val s = hw.session
    if (s.speedKmh < 1.0) return
    val (d, toHeadland) = s.toolToHeadland() ?: return
    val now = d < 1.5
    val text = when {
        toHeadland && now -> "UWROCIE — PODNIEŚ"
        toHeadland -> "Uwrocie za ${d.pl(0)} m"
        now -> "POLE — OPUŚĆ"
        else -> "Pole za ${d.pl(0)} m"
    }
    val bg = if (now) (if (toHeadland) Ss.Warning else Ss.Accent) else Ss.Bar.copy(alpha = 0.9f)
    val fg = if (now) (if (toHeadland) Ss.OnWarning else Ss.OnAccent) else Ss.Text
    Text(text, modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 16.dp, vertical = 8.dp), color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun HwMessage(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val m = hw.mapNote ?: hw.session.message ?: return
    Text(m, modifier.hwOverlay().padding(horizontal = 16.dp, vertical = 12.dp), color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

/** Auto-zawrót: włącznik, kierunek najbliższego zawrotu, przerwanie trwającego. */
@Composable
private fun TurnControls(hw: HardwareController) {
    val s = hw.session
    val hasBoundary = s.boundary != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(
            Modifier.weight(1f),
            background = if (s.autoUturn) Ss.AccentSoft else Ss.SurfaceHigh,
            border = if (s.autoUturn) Ss.Accent else Ss.BorderStrong,
            onClick = { s.autoUturn = !s.autoUturn; if (!s.autoUturn) s.cancelTurn(); hw.touch() },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ButtonText("Zawrót", size = 13.sp)
                ButtonText(if (s.autoUturn) "AUTO" else "WYŁ.", color = if (s.autoUturn) Ss.Accent else Ss.TextMuted, size = 15.sp, weight = FontWeight.Bold)
            }
        }
        ActionButton(Modifier.weight(1f), onClick = { s.uTurnRight = !s.uTurnRight; hw.touch() }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ButtonText("Następny", size = 13.sp)
                ButtonText(if (s.uTurnRight) "w prawo ▶" else "◀ w lewo", size = 15.sp, weight = FontWeight.Bold)
            }
        }
    }
    if (s.youTurnPath != null) {
        ActionButton(Modifier.fillMaxWidth(), background = Ss.Warning, border = Ss.Warning, onClick = { s.cancelTurn(); hw.touch() }) {
            ButtonText("Przerwij zawrót", color = Ss.OnWarning, weight = FontWeight.Bold)
        }
    } else if (s.autoUturn && !hasBoundary) {
        Label("Auto-zawrót potrzebuje granicy pola (ekran Pola).", Ss.Warning, 12.sp)
    }
}

/** Czujnik przeszkód: czerwony pas przy STOP (przeszkoda albo martwy czujnik), pomarańczowy przy „Uwaga”. */
@Composable
private fun ObstacleBanner(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val v = hw.session.obstacleVerdict ?: return
    if (v.level == 0) return
    val c = if (v.level == 2) Ss.Danger else Ss.Warning
    Box(
        modifier.widthIn(min = 360.dp).clip(RoundedCornerShape(14.dp)).background(c).padding(horizontal = 22.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(v.message ?: if (v.level == 2) "STOP" else "Uwaga", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** Nowe zlecenie z WAR: Przyjmij (otwiera pole) / Odrzuć. */
@Composable
private fun OrderBanner(hw: HardwareController, modifier: Modifier) {
    hw.tick
    val o = hw.war.newOrders.firstOrNull() ?: return
    val more = hw.war.newOrders.size - 1
    Column(
        modifier.width(460.dp).hwOverlay().border(2.dp, Ss.Info, RoundedCornerShape(14.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label("Nowe zlecenie z WAR${if (more > 0) " (i jeszcze $more)" else ""}${if (o.from.isNotEmpty()) " · od: ${o.from}" else ""}", Ss.Info, 12.sp)
        BigValue(o.title, size = 18.sp)
        if (o.details.isNotEmpty()) Text(o.details, color = Ss.TextSoft, fontSize = 14.sp)
        if (o.notes.isNotEmpty()) Text("Uwagi: ${o.notes}", color = Ss.TextSoft, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(Modifier.weight(1f), background = Ss.Accent, border = Ss.Accent, onClick = {
                val m = hw.war.accept(o)
                hw.mapMessage(m)
            }) { ButtonText("Przyjmij", color = Ss.OnAccent, weight = FontWeight.Bold) }
            ActionButton(Modifier.weight(1f), onClick = { hw.war.reject(o); hw.touch() }) { ButtonText("Odrzuć", color = Ss.TextSoft) }
        }
    }
}

private fun polygonPath(pts: List<Vec2>): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
    for (i in 1 until pts.size) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
    close()
}

/** Pasek sekcji na dole mapy: numer sekcji i jej tryb (AUTO / RĘCZ. / WYŁ.); dotknięcie = sterowanie ręczne. */
@Composable
private fun HwSectionsBar(hw: HardwareController, locked: Boolean, modifier: Modifier) {
    hw.tick
    val s = hw.session
    val sec = s.sections
    val n = s.profile.numSections.coerceIn(1, 64)
    val w = if (n > 16) maxOf(10f, 880f / n - 4f).dp else if (n > 10) 46.dp else if (n > 6) 56.dp else 68.dp
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Ss.Bar.copy(alpha = 0.94f))
            .border(1.dp, Ss.Border, RoundedCornerShape(16.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 0 until n) {
            val on = sec.on[i]
            val manual = sec.mode == SectionMode.MANUAL
            val armed = manual && sec.manual[i]
            val bg = when { on -> Ss.Accent; armed -> Ss.AccentSoft; else -> Ss.SurfaceHigh }
            val fg = if (on) Ss.OnAccent else if (armed) Ss.Accent else Ss.TextSoft
            val tag = when {
                sec.mode == SectionMode.OFF -> "WYŁ."
                manual -> if (sec.manual[i]) "RĘCZ." else "WYŁ."
                else -> "AUTO"
            }
            Column(
                Modifier.width(w).height(52.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                    .border(1.dp, if (armed && !on) Ss.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable(enabled = !locked, role = Role.Button) {
                        // dotknięcie sekcji przełącza na tryb ręczny (jak w AgOpenGPS)
                        if (sec.mode != SectionMode.MANUAL) { sec.manual.fill(true); sec.mode = SectionMode.MANUAL }
                        sec.manual[i] = !sec.manual[i]
                        hw.touch()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (w >= 26.dp) Text("${i + 1}", color = fg, fontSize = if (w >= 40.dp) 17.sp else 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (w >= 40.dp) Text(tag, color = fg.copy(alpha = 0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Modifier.weight(1f), onClick = { s.markAPlus(); hw.touch() }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ButtonText("A+", size = 17.sp, weight = FontWeight.Bold)
                    ButtonText("z kursu", size = 11.sp, color = Ss.TextMuted)
                }
            }
            ActionButton(Modifier.weight(1f), onClick = {
                Keypad.open(
                    KeypadRequest("Kurs linii A+ (0–359°, 0 = północ)", "", KeypadMode.Number, hint = "np. 87") { t ->
                        t.replace(',', '.').toDoubleOrNull()?.let { s.markAPlus(((it % 360) + 360) % 360); hw.touch() }
                    },
                )
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ButtonText("A+ °", size = 17.sp, weight = FontWeight.Bold)
                    ButtonText("wpisz kurs", size = 11.sp, color = Ss.TextMuted)
                }
            }
            ActionButton(Modifier.weight(1f), onClick = { s.snapToTractor(); hw.touch() }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ButtonText("Do ciągnika", size = 14.sp, weight = FontWeight.Bold)
                    ButtonText("przesuń linię", size = 11.sp, color = Ss.TextMuted)
                }
            }
        }
        if (s.line != null || s.pointA != null) {
            ActionButton(Modifier.fillMaxWidth(), onClick = { s.clearLine(); hw.touch() }) { ButtonText("Usuń linię", color = Ss.TextSoft) }
        }
}

/** Lista linii pola (jak w AgOpenGPS): dotknięcie wybiera, ✕ usuwa. */
@Composable
private fun TrackList(hw: HardwareController) {
    val s = hw.session
    if (s.tracks.isEmpty()) return
    Label("Linie pola (${s.tracks.size})")
    s.tracks.forEachIndexed { i, t ->
        val cur = i == s.activeTrack
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (cur) Ss.AccentSoft else Ss.SurfaceHigh)
                .border(1.dp, if (cur) Ss.Accent else Ss.Border, RoundedCornerShape(10.dp))
                .clickable(role = Role.Button) { s.selectTrack(i); hw.touch() }.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (t.isCurve) "∿" else "╱", color = if (cur) Ss.Accent else Ss.TextMuted, fontSize = 16.sp, modifier = Modifier.width(22.dp))
            Text(t.name, Modifier.weight(1f), color = if (cur) Ss.Accent else Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Box(Modifier.size(44.dp).clickable(role = Role.Button) { s.deleteTrack(i); hw.touch() }, contentAlignment = Alignment.Center) {
                Text("✕", color = Ss.TextMuted, fontSize = 15.sp)
            }
        }
    }
}

/** Plan przejazdów: kierunki z najmniejszą liczbą przejazdów dla tej maszyny. */
@Composable
private fun PlanControls(hw: HardwareController) {
    val s = hw.session
    var plans by remember { mutableStateOf<List<eu.smartsteer.navigator.core.field.FieldPlanner.Plan>>(emptyList()) }
    if (s.boundary == null) {
        Label("Plan przejazdów, linie z granicy i uwrocie: najpierw granica pola (ekran Pola).", Ss.TextMuted, 12.sp)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(Modifier.weight(1f), onClick = { s.abFromBoundary(); hw.touch() }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ButtonText("Wzdłuż boku", size = 14.sp, weight = FontWeight.Bold)
                ButtonText("najbliższy bok pola", size = 11.sp, color = Ss.TextMuted)
            }
        }
        ActionButton(Modifier.weight(1f), onClick = { s.headlandTrack(); hw.touch() }) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ButtonText("Uwrocie", size = 14.sp, weight = FontWeight.Bold)
                ButtonText("dookoła pola", size = 11.sp, color = Ss.TextMuted)
            }
        }
    }
    ActionButton(Modifier.fillMaxWidth(), background = Ss.AccentSoft, border = Ss.Accent, onClick = { plans = s.planPasses(); hw.touch() }) {
        ButtonText("Zaplanuj przejazdy (najmniej)", color = Ss.Accent, weight = FontWeight.Bold)
    }
    if (plans.isNotEmpty()) {
        Label("Szerokość przejazdu ${s.profile.passSpacing.pl(2)} m${if (s.headland != null) " · wnętrze bez uwrocia" else ""}. Dotknij, aby użyć:", Ss.TextMuted, 12.sp)
        plans.forEachIndexed { i, p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (i == 0) Ss.AccentSoft else Ss.SurfaceHigh)
                    .clickable(role = Role.Button) { s.applyPlan(p); plans = emptyList(); hw.touch() }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${p.passes} przejazdów · kurs ${p.headingDeg}°", color = if (i == 0) Ss.Accent else Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${p.turns} zawrotów · nadmiar ${p.wasteM.pl(1)} m${if (p.alongEdge) " · wzdłuż boku" else ""}",
                        color = Ss.TextMuted, fontSize = 12.sp,
                    )
                }
                if (i == 0) Text("NAJLEPSZY", color = Ss.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Znaczniki na polu (kamień, mokre miejsce…). */
@Composable
private fun FlagControls(hw: HardwareController) {
    val s = hw.session
    Label("Znaczniki${if (s.flags.isNotEmpty()) " (${s.flags.size})" else ""}")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((c, col) in listOf(0 to Ss.Danger, 1 to Ss.GuideLine, 2 to Ss.Info)) {
            ActionButton(Modifier.weight(1f), onClick = { s.addFlag(c); hw.touch() }) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(col))
            }
        }
        ActionButton(Modifier.weight(1.4f), onClick = { s.removeNearestFlag(); hw.touch() }) { ButtonText("Usuń najbliższy", size = 12.sp, color = Ss.TextSoft) }
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
                        valueColor = if (s.fix == FixQuality.RTK_FIX || hw.rtkNotExpected) Ss.Text else Ss.Warning)
                }
            }
        }
}

@Composable
private fun HwCanvas(hw: HardwareController, zoom: Float, headingUp: Boolean, tilt: Boolean, locked: Boolean, modifier: Modifier) {
    // pasy pokrycia zamieniamy na Path raz (otwarty pas — gdy przybędzie punktów)
    val stripCache = remember { HashMap<Coverage.Strip, Pair<Int, Path>>() }
    val othersCache = remember { mutableStateOf<Pair<Int, Map<String, Path>>?>(null) }
    val density = LocalDensity.current.density
    val tm = rememberTextMeasurer()
    Canvas(
        modifier.clipToBounds().background(Ss.MapGround)
            // „3D”: mapa pochylona jak widok z kabiny
            .graphicsLayer {
                if (tilt) { rotationX = 48f; cameraDistance = 9f * density; transformOrigin = TransformOrigin(0.5f, 0.72f) }
            }
            .pointerInput(headingUp, locked) {
            if (locked) return@pointerInput
            // dotknięcie ciągnika zamienia przód z tyłem (jak w AgOpenGPS)
            detectTapGestures { tap ->
                val tx = size.width / 2f
                val ty = size.height / 2f + if (headingUp) size.height * 0.18f else 0f
                val r = maxOf(48f * density, 3.5f * zoom * density)
                if (kotlin.math.hypot(tap.x - tx, tap.y - ty) <= r) { hw.session.flipHeading(); hw.touch() }
            }
        },
    ) {
        hw.tick
        val s = hw.session
        val pos = s.pivot ?: Vec2.ZERO
        val sc = zoom * density
        val px = 1f / sc
        val fwd = Vec2.forward(s.headingRad)
        val viewFwd = Vec2.forward(hw.viewHeading)
        val lead = size.height * 0.18f / sc
        val cam = if (headingUp) pos + viewFwd * lead.toDouble() else pos
        val camX = cam.x.toFloat()
        val camY = cam.y.toFloat()
        fun o(p: Vec2) = Offset(p.x.toFloat(), p.y.toFloat())
        withTransform({
            translate(size.width / 2f, size.height / 2f)
            if (headingUp) rotate(-(hw.viewHeading * 180 / PI).toFloat(), Offset.Zero)
            scale(sc, -sc, Offset.Zero)
            translate(-camX, -camY)
        }) {
            val r = hypot(size.width, size.height) / sc
            // podkład: ortofotomapa / zdjęcia satelitarne
            val src = TileSource.entries.getOrElse(DisplayPrefs.background) { TileSource.NONE }
            val pl = s.plane
            val sat = src != TileSource.NONE && pl != null
            if (sat && pl != null) {
                TileManager.version
                val (lat0, _) = pl.toGeo(cam)
                val mppScreen = 1.0 / sc
                var z = kotlin.math.round(kotlin.math.ln(TileMath.metersPerPixel(lat0, 0) / mppScreen) / kotlin.math.ln(2.0)).toInt().coerceIn(3, src.maxZoom)
                val (la, lo) = pl.toGeo(cam + Vec2(-r.toDouble(), -r.toDouble()))
                val (lb, lb2) = pl.toGeo(cam + Vec2(r.toDouble(), r.toDouble()))
                var tiles = TileMath.range(minOf(la, lb), maxOf(la, lb), minOf(lo, lb2), maxOf(lo, lb2), z)
                while (tiles.size > 120 && z > 3) { z--; tiles = TileMath.range(minOf(la, lb), maxOf(la, lb), minOf(lo, lb2), maxOf(lo, lb2), z) }
                for ((tx, ty) in tiles) {
                    val img = TileManager.get(src, z, tx, ty) ?: continue
                    val nw = pl.toLocal(TileMath.lat(ty.toDouble(), z), TileMath.lon(tx.toDouble(), z))
                    val se = pl.toLocal(TileMath.lat(ty + 1.0, z), TileMath.lon(tx + 1.0, z))
                    withTransform({
                        translate(nw.x.toFloat(), nw.y.toFloat())
                        scale(((se.x - nw.x) / img.width).toFloat(), ((se.y - nw.y) / img.height).toFloat(), Offset.Zero)
                    }) { drawImage(img) }
                }
            }
            // siatka co 10 m
            val gc = if (sat) Color(0x22FFFFFF) else Color(0xFF1C2822)
            var x = floor((camX - r) / 10f) * 10f
            while (x <= camX + r) { drawLine(gc, Offset(x, camY - r), Offset(x, camY + r), px); x += 10f }
            var y = floor((camY - r) / 10f) * 10f
            while (y <= camY + r) { drawLine(gc, Offset(camX - r, y), Offset(camX + r, y), px); y += 10f }

            // granica i uwrocie
            s.boundary?.let { b ->
                if (!sat) drawPath(polygonPath(b), Ss.FieldFill)
                drawPath(polygonPath(b), Ss.MapLine, style = Stroke(width = 2.5f * px))
            }
            s.headland?.let { h ->
                drawPath(polygonPath(h), Ss.Warning.copy(alpha = 0.8f), style = Stroke(width = 1.5f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * px, 6f * px))))
            }
            // mapa dawek: im większa dawka, tym ciemniejsza zieleń
            s.rateMap?.let { rm ->
                val lo = rm.minRate; val span = (rm.maxRate - lo).coerceAtLeast(1e-6)
                for (z in rm.zones) {
                    val f = ((z.rate - lo) / span).toFloat().coerceIn(0f, 1f)
                    val c = androidx.compose.ui.graphics.lerp(Color(0xFFF2E86D), Color(0xFF1B7F3B), f)
                    drawPath(polygonPath(z.poly), c.copy(alpha = 0.35f))
                    drawPath(polygonPath(z.poly), c.copy(alpha = 0.8f), style = Stroke(width = 1.5f * px))
                }
            }
            // przeszkody (sekcje się na nich wyłączają)
            for (ob in s.obstacles) {
                drawPath(polygonPath(ob), Ss.Danger.copy(alpha = 0.25f))
                drawPath(polygonPath(ob), Ss.Danger, style = Stroke(width = 2f * px))
            }
            s.recordingObstacle?.let { r ->
                if (r.size >= 2) {
                    val path = Path()
                    r.forEachIndexed { i, q -> if (i == 0) path.moveTo(q.x.toFloat(), q.y.toFloat()) else path.lineTo(q.x.toFloat(), q.y.toFloat()) }
                    drawPath(path, Ss.Danger, style = Stroke(width = 3f * px, cap = StrokeCap.Round))
                }
            }
            s.recordingBoundary?.let { r ->
                if (r.size >= 2) {
                    val path = Path()
                    r.forEachIndexed { i, q -> if (i == 0) path.moveTo(q.x.toFloat(), q.y.toFloat()) else path.lineTo(q.x.toFloat(), q.y.toFloat()) }
                    drawPath(path, Ss.Warning, style = Stroke(width = 3f * px, cap = StrokeCap.Round))
                }
            }

            // mapa pokrycia
            if (s.coverage.strips.isEmpty()) stripCache.clear()
            val coverColor = (eu.smartsteer.navigator.ui.TrailColors.argb(DisplayPrefs.trailColor)?.let { Color(it) } ?: Ss.Accent).copy(alpha = 0.35f)
            for (st in s.coverage.strips) {
                val n = st.left.size
                if (n < 2) continue
                val cached = stripCache[st]
                val path = if (cached != null && cached.first == n) cached.second else Path().apply {
                    moveTo(st.left[0].x.toFloat(), st.left[0].y.toFloat())
                    for (i in 1 until n) lineTo(st.left[i].x.toFloat(), st.left[i].y.toFloat())
                    for (i in n - 1 downTo 0) lineTo(st.right[i].x.toFloat(), st.right[i].y.toFloat())
                    close()
                }.also { stripCache[st] = n to it }
                drawPath(path, coverColor)
            }

            // praca innych maszyn na tym polu (wspólna praca przez WAR)
            if (s.othersCells.isNotEmpty()) {                          // każda maszyna w swoim kolorze śladu
                val cached = othersCache.value
                val paths = if (cached != null && cached.first == s.othersVersion) cached.second else s.othersCells.mapValues { (_, quads) ->
                    Path().apply { for (q in quads) { moveTo(q[0].x.toFloat(), q[0].y.toFloat()); for (i in 1 until q.size) lineTo(q[i].x.toFloat(), q[i].y.toFloat()); close() } }
                }.also { othersCache.value = s.othersVersion to it }
                for ((m, path) in paths) {
                    val c = eu.smartsteer.navigator.ui.TrailColors.argb(s.othersColors[m])?.let { Color(it) } ?: Ss.Info
                    drawPath(path, c.copy(alpha = 0.3f))
                }
            }

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

            // auto-zawrót: linia zawrotu i trwający zawrót
            if (s.autoUturn) s.turnLine?.let {
                drawPath(polygonPath(it), Ss.TextMuted.copy(alpha = 0.6f), style = Stroke(width = 1f * px, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * px, 6f * px))))
            }
            s.youTurnPath?.let { poly(it, Ss.Accent, 3f) }
            // zawrót K: odcinek do przejechania wstecz
            if (s.kPhase == 1) s.kReverse?.let { poly(it, Ss.Warning, 2.5f, dashed = true) }

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
                        if (active) Ss.GuideLine else Ss.MapLine.copy(alpha = if (k == 0) 0.75f else 0.45f),
                        o(a), o(b),
                        (if (active) 4f else 1.5f) * px,
                    )
                }
                // ścieżki technologiczne (ślady kół opryskiwacza)
                if (s.profile.tramEvery > 0) {
                    val tw = maxOf(s.profile.tramWheel.toFloat(), 2f * px)
                    for (t in eu.smartsteer.navigator.core.field.Tramlines.tracksNear(s.passIndex, w, s.profile.tramEvery, s.profile.tramTrack, s.profile.tramShift)) {
                        val base = l.a + right * t + dir * along
                        drawLine(Ss.Warning.copy(alpha = 0.55f), o(base - dir * r.toDouble()), o(base + dir * r.toDouble()), tw)
                    }
                }
                drawCircle(Ss.MapLine, 6f * px, o(l.a))
                drawCircle(Ss.MapLine, 6f * px, o(l.b))
            }
            s.pointA?.let { if (l == null && s.mode == GuidanceMode.AB) drawCircle(Ss.Warning, 8f * px, o(it)) }
            s.goal?.let { drawCircle(Ss.Accent, 5f * px, o(it)) }
            // znaczniki
            for (f in s.flags) {
                val c = when (f.color) { 1 -> Ss.GuideLine; 2 -> Ss.Info; else -> Ss.Danger }
                drawCircle(c, 9f * px, o(f.p))
                drawCircle(Ss.Ground, 9f * px, o(f.p), style = Stroke(width = 2f * px))
            }

            // maszyna i sekcje
            s.sections.hitchPos?.let { hitch ->
                s.sections.toolPos?.let { tp -> drawLine(Ss.MapLine, o(hitch), o(tp), 2f * px) }
                s.pivot?.let { pv -> drawLine(Ss.MapLine, o(pv), o(hitch), 2f * px) }
            }
            val tf = Vec2.forward(s.sections.toolHeading)
            for (i in 0 until s.profile.numSections.coerceIn(1, 64)) {
                val (a, b) = s.sections.edges(i, s.profile) ?: break
                val quad = Path().apply {
                    moveTo(o(a + tf * 0.35).x, o(a + tf * 0.35).y); lineTo(o(b + tf * 0.35).x, o(b + tf * 0.35).y)
                    lineTo(o(b - tf * 0.35).x, o(b - tf * 0.35).y); lineTo(o(a - tf * 0.35).x, o(a - tf * 0.35).y); close()
                }
                drawPath(quad, if (s.sections.on[i]) Ss.Accent else Ss.Dim)
                drawPath(quad, Ss.OnAccent, style = Stroke(width = 1.5f * px))
            }

            // inne maszyny gospodarstwa (flota WAR §3.10): niebieski trójkąt w kierunku jazdy
            val plane = s.plane
            if (plane != null) for (m in hw.war.fleet.values) {
                val mp = plane.toLocal(m.lat, m.lon)
                val h = m.courseDeg * kotlin.math.PI / 180
                val f = Vec2.forward(h)
                val r = Vec2.right(h)
                val tri = Path().apply {
                    moveTo(o(mp + f * 3.2).x, o(mp + f * 3.2).y)
                    lineTo(o(mp + r * 1.1 - f * 0.6).x, o(mp + r * 1.1 - f * 0.6).y)
                    lineTo(o(mp - r * 1.1 - f * 0.6).x, o(mp - r * 1.1 - f * 0.6).y)
                    close()
                }
                val mc = eu.smartsteer.navigator.ui.TrailColors.argb(m.color)?.let { Color(it) } ?: Ss.Info
                if (m.tracker) {                                          // lokalizator WAR Track: kółko z kreską kierunku
                    drawCircle(Ss.Warning, 1.6f, o(mp))
                    drawCircle(Ss.Text, 1.6f, o(mp), style = Stroke(width = 2f * px))
                    if (m.kmh >= 1) drawLine(Ss.Text, o(mp), o(mp + f * 3.0), 2f * px)
                    continue
                }
                drawPath(tri, mc)
                drawPath(tri, Ss.Text, style = Stroke(width = 2f * px))
                if (m.width > 0.5) {
                    val half = m.width / 2
                    drawLine(mc.copy(alpha = 0.8f), o(mp - r * half - f * 1.5), o(mp + r * half - f * 1.5), 3f * px)
                }
            }

            // ciągnik: strzałka, ciągnik z góry (przednie koła skręcają jak prawdziwe) albo własny obraz — Ustawienia → Ciągnik
            val p = s.pivot
            if (p != null) {
                eu.smartsteer.navigator.ui.drawTractorIcon({ o(it) }, p, s.headingRad, s.profile, s.autosteer?.actualAngleDeg ?: 0.0, outline = Ss.Text, px = px)
                if (DisplayPrefs.tractorIcon == 0) drawCircle(Ss.Text, 4f * px, o(p))
            }
        }

        // numery przejazdów u góry ekranu (jak w AgOpenGPS): aktywny na żółto
        val l = s.line.takeIf { s.mode == GuidanceMode.AB }
        if (l != null) {
            val th = if (headingUp) -hw.viewHeading else 0.0
            val cosT = kotlin.math.cos(th).toFloat()
            val sinT = kotlin.math.sin(th).toFloat()
            fun screen(p: Vec2): Offset {
                val vx = ((p.x - cam.x) * sc).toFloat()
                val vy = (-(p.y - cam.y) * sc).toFloat()
                return Offset(size.width / 2 + vx * cosT - vy * sinT, size.height / 2 + vx * sinT + vy * cosT)
            }
            val up = if (headingUp) viewFwd else Vec2(0.0, 1.0)
            val top = cam + up * ((size.height / 2 - 64f * density) / sc).toDouble()
            val dir = Vec2.forward(l.heading)
            val right = Vec2.right(l.heading)
            val w = s.profile.passSpacing
            val rad = 17f * density
            for (k in s.passIndex - 8..s.passIndex + 8) {
                val base = l.a + right * (k * w)
                val pt = base + dir * ((top - base) dot dir)
                val c = screen(pt)
                if (c.x < rad || c.x > size.width - rad || c.y < 0 || c.y > size.height) continue
                val active = k == s.passIndex
                drawCircle(if (active) Ss.GuideLine else Ss.Bar.copy(alpha = 0.9f), rad, c)
                drawCircle(if (active) Ss.GuideLine else Ss.MapLine.copy(alpha = 0.7f), rad, c, style = Stroke(width = 2f * density))
                val layout = tm.measure(k.toString(), TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (active) Ss.OnWarning else Ss.Text))
                drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
            }
        }
    }
}


/** Wiadomość głosowa do gospodarza (WAR na telefonie): stuknij — nagrywa, stuknij drugi raz — wysyła; ▶ ostatnia odebrana. */
@Composable
private fun VoiceButton(hw: HardwareController, modifier: Modifier) {
    val w = hw.war
    if (!w.configured) return
    val rec = w.voiceRecording
    var sec by remember { mutableStateOf(0) }
    LaunchedEffect(rec) {
        sec = 0
        while (rec) { kotlinx.coroutines.delay(500); sec = ((eu.smartsteer.navigator.core.war.nowMillis() - w.voiceStartedMs) / 1000).toInt() }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(if (rec) Ss.Warning else Ss.Bar.copy(alpha = 0.9f))
                .border(2.dp, if (rec) Ss.Warning else Ss.Border, RoundedCornerShape(26.dp))
                .clickable { w.voiceButton()?.let { hw.mapMessage(it) } },
            contentAlignment = Alignment.Center,
        ) { Text(if (rec) "■" else "🎙", color = Ss.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        // WAR Cam: obraz z kamery tabletu do telefonu gospodarza — włącz / wyłącz jednym dotknięciem
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(if (w.camOn) Ss.Accent else Ss.Bar.copy(alpha = 0.9f))
                .border(2.dp, if (w.camOn) Ss.Accent else Ss.Border, RoundedCornerShape(26.dp))
                .clickable { w.toggleCamera()?.let { hw.mapMessage(it) } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📷", fontSize = 16.sp)
                Text("CAM", color = if (w.camOn) Ss.OnAccent else Ss.TextSoft, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (rec) Text("Nagrywam ${sec} s — stuknij, żeby wysłać", Modifier.hwOverlay().padding(horizontal = 10.dp, vertical = 6.dp), color = Ss.Text, fontSize = 13.sp)
        else w.voice.lastOrNull { !it.mine }?.let { m ->
            Text("▶ ${m.from}", Modifier.hwOverlay().clickable { w.playVoice(m) }.padding(horizontal = 10.dp, vertical = 6.dp),
                color = Ss.Info, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Inne maszyny gospodarstwa (flota WAR): nazwa, narzędzie, prędkość, odległość — niebieskie trójkąty na mapie. */
@Composable
private fun FleetChip(hw: HardwareController, modifier: Modifier) {
    val w = hw.war
    if (!w.configured || !w.config.fleet) return
    val fleet = w.fleet.values.sortedBy { it.tractor }
    var open by remember { mutableStateOf(true) }
    val s = hw.session
    val me = s.pivot
    val plane = s.plane
    Column(
        modifier.width(if (open && fleet.isNotEmpty()) 260.dp else 170.dp).hwOverlay().clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (fleet.isEmpty()) "FLOTA · nikogo nie widać" else "FLOTA · ${fleet.size} ${if (open) "▾" else "▸"}",
            color = if (fleet.isEmpty()) Ss.TextMuted else Ss.Info, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        )
        if (open) for (m in fleet.take(4)) {
            val dist = if (me != null && plane != null) plane.toLocal(m.lat, m.lon).distanceTo(me) else null
            Text(
                "${if (m.tracker) "📡 " else ""}${m.tractor}${if (m.tool.isNotEmpty()) " · ${m.tool}" else ""}",
                color = Ss.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            Text(
                "${if (m.working) "pracuje" else "stoi"} · ${m.kmh.pl(1)} km/h" +
                    (dist?.let { " · " + if (it < 1000) "${it.toInt()} m" else "${(it / 1000).pl(1)} km" } ?: "") +
                    (if (m.field.isNotEmpty()) " · ${m.field}" else ""),
                color = Ss.TextMuted, fontSize = 11.sp, maxLines = 1,
            )
        }
    }
}
