package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.sprayer.FlowMode
import eu.smartsteer.navigator.core.sprayer.Liquid
import eu.smartsteer.navigator.core.sprayer.Nozzle
import eu.smartsteer.navigator.core.sprayer.NozzleKind
import eu.smartsteer.navigator.core.sprayer.SprayerConfig
import eu.smartsteer.navigator.core.sprayer.ValveType
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.SectionOverride
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.Keypad
import eu.smartsteer.navigator.ui.components.KeypadMode
import eu.smartsteer.navigator.ui.components.KeypadRequest
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.NavIcon
import eu.smartsteer.navigator.ui.components.Panel
import eu.smartsteer.navigator.ui.components.Pill
import eu.smartsteer.navigator.ui.components.SectionTitle
import eu.smartsteer.navigator.ui.components.Segmented
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss
import kotlin.math.abs

/** Kolory dysz ISO 10625 (kolor korpusu dyszy). */
private fun isoColor(n: Nozzle): Color = when (n.name) {
    "01" -> Color(0xFFFF8A1F)
    "015" -> Color(0xFF2FAE4A)
    "02" -> Color(0xFFF2D21B)
    "025" -> Color(0xFFB07CE0)
    "03" -> Color(0xFF2F7BE0)
    "04" -> Color(0xFFE5392F)
    "05" -> Color(0xFF8B5A2B)
    "06" -> Color(0xFF8E9696)
    else -> Color(0xFFF4F4F4)
}

/**
 * Komputer opryskiwacza — ekran pracy (jak terminale Horsch / Krukowiak / Napa, w kolorach WAR)
 * i ustawienia (sekcje i dysze na sekcję, rozstaw, dysze ISO i RSM, ciecz, pomiar, zawory Arag).
 */
@Composable
fun SprayerScreen(hw: HardwareController, startInSettings: Boolean = false) {
    val sp = hw.sprayer
    sp.tick
    var settings by remember { mutableStateOf(startInSettings) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Opryskiwacz", color = Ss.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (sp.simulated) Pill("SYMULATOR — płytka Maszyna jeszcze niepodłączona", Ss.Warning)
            Box(Modifier.weight(1f))
            Segmented(listOf("PRACA", "USTAWIENIA"), if (settings) 1 else 0, { settings = it == 1 })
            ActionButton(Modifier.width(150.dp), background = Ss.AccentSoft, contentColor = Ss.Accent, border = Ss.Accent,
                onClick = { sp.navRequest = "Map" }) {
                NavIcon(IconKind.Map, Ss.Accent)
                ButtonText("MAPA", Ss.Accent, 16.sp, FontWeight.Bold)
            }
        }
        if (settings) SprayerSettings(hw) else SprayerWork(hw)
    }
}

@Composable
private fun SprayerWork(hw: HardwareController) {
    val sp = hw.sprayer
    val c = sp.config
    val pc = sp.computer
    val st = sp.status
    val speed = if (hw.hardware) hw.session.speedKmh else 0.0
    val running = pc.running
    val rateOk = pc.rateOk()
    val pressure = st?.pressureBar
    val kind = c.nozzleKind
    val pressureOk = pressure == null || !running || pressure in kind.minBar..kind.maxBar

    Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Dawka — najważniejsza liczba
        SprayTile(Modifier.weight(1.4f), "DAWKA", if (running) (if (rateOk) Ss.Accent else Ss.Warning) else Ss.Border) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (running) pc.actualLha.pl(0) else "—", color = if (!running) Ss.TextMuted else if (rateOk) Ss.Accent else Ss.Warning, fontSize = 54.sp, fontWeight = FontWeight.Bold)
                Text(" l/ha", color = Ss.TextSoft, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(
                "zadana ${pc.targetLha.pl(0)} l/ha" + (if (pc.rateAdjustPercent != 0) " (${pc.rateAdjustPercent.toDouble().pl(0, plusSign = true)} %)" else "") +
                    (if (c.liquid.density > 1.0) " · ${(pc.targetLha * c.liquid.density).pl(0)} kg/ha" else ""),
                color = Ss.TextSoft, fontSize = 15.sp,
            )
            Text(
                when {
                    !running -> "STOP — opryskiwanie wyłączone"
                    speed < c.minSpeedKmh -> "Postój — zawór trzyma położenie"
                    !pc.autoRate -> "RĘCZNIE — dawka nie jest regulowana"
                    rateOk -> "Dawka w normie"
                    else -> "Dawka poza tolerancją ±10 %"
                },
                color = if (running && !rateOk) Ss.Warning else Ss.TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        SprayTile(Modifier.weight(1f), "CIŚNIENIE", if (pressureOk) Ss.Border else Ss.Warning) {
            BigNumber(pressure?.pl(1) ?: "brak", "bar", if (pressureOk) Ss.Text else Ss.Warning)
            Gauge(pressure ?: 0.0, 0.0, 10.0, kind.minBar, kind.maxBar)
            Label("dysza: ${kind.minBar.pl(1)}–${kind.maxBar.pl(1)} bar", if (pressureOk) Ss.TextMuted else Ss.Warning, 13.sp)
        }
        SprayTile(Modifier.weight(1f), "PRZEPŁYW", Ss.Border) {
            BigNumber(pc.flowLpm.pl(1), "l/min", Ss.Text)
            Label("cel ${pc.targetLpm.pl(1)} l/min", Ss.TextSoft, 14.sp)
            Label(c.flowMode.label, Ss.TextMuted, 12.sp)
        }
        SprayTile(Modifier.weight(1f), "PRĘDKOŚĆ", Ss.Border) {
            val r = c.speedRangeKmh(pc.targetLha)
            val inRange = speed < 0.5 || speed in r
            BigNumber(speed.pl(1), "km/h", if (inRange) Ss.Text else Ss.Warning)
            Label("dla dawki: ${r.start.pl(1)}–${r.endInclusive.pl(1)} km/h", if (inRange) Ss.TextSoft else Ss.Warning, 13.sp)
            Label("szer. ${c.openWidthM(sp.mask).pl(1)} z ${c.totalWidthM.pl(1)} m", Ss.TextMuted, 12.sp)
        }
        TankTile(hw, Modifier.width(170.dp))
    }

    // Belka z sekcjami — dotknięcie zmienia AUTO / WŁ. / WYŁ.
    Panel(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Sekcje — dotknij, aby przełączyć AUTO / WŁ. / WYŁ.")
            Box(Modifier.weight(1f))
            Label("Automat sekcji: ${hw.session.sections.mode.label}", Ss.TextMuted, 13.sp)
        }
        Boom(hw)
    }

    // Przyciski pracy
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(
            Modifier.width(230.dp).height(84.dp),
            background = if (running) Ss.Danger else Ss.Accent,
            contentColor = if (running) Color.White else Ss.OnAccent,
            border = Color.Transparent,
            onClick = { sp.toggleRun() },
        ) { ButtonText(if (running) "STOP" else "START", if (running) Color.White else Ss.OnAccent, 30.sp, FontWeight.Black) }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label("Regulacja dawki")
            Segmented(listOf("AUTO", "RĘCZNIE"), if (pc.autoRate) 0 else 1, { sp.setAuto(it == 0) },
                selectedColor = { if (it == 0) Ss.Accent else Ss.Warning })
        }
        if (pc.autoRate) {
            Stepper("Dawka", "${pc.targetLha.pl(0)} l/ha", onMinus = { sp.adjustRate(-5) }, onPlus = { sp.adjustRate(5) }, onCenter = { sp.resetRateAdjust() })
        } else {
            Stepper("Zawór regulacyjny", "mniej / więcej", onMinus = { sp.manualValve(-250) }, onPlus = { sp.manualValve(250) }, onCenter = {})
        }
        Box(Modifier.weight(1f))
        ActionButton(
            Modifier.height(84.dp).width(150.dp),
            background = if (pc.workLights) Ss.Warning else Ss.SurfaceHigh,
            contentColor = if (pc.workLights) Ss.OnWarning else Ss.Text,
            onClick = { sp.toggleLights() },
        ) {
            Text("OŚWIETLENIE\n${if (pc.workLights) "WŁĄCZONE" else "wyłączone"}", color = if (pc.workLights) Ss.OnWarning else Ss.Text,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
    val faults = st?.let { s ->
        buildList {
            if (s.failsafe) add("brak łączności z płytką — zawory zamknięte")
            if (s.sectionFaults != 0) add("błąd zaworu sekcji ${(0 until 9).filter { s.sectionFaults and (1 shl it) != 0 }.joinToString { "${it + 1}" }}")
        }
    }.orEmpty()
    if (faults.isNotEmpty()) Text("⚠ " + faults.joinToString(" · "), color = Ss.Danger, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SprayTile(modifier: Modifier, title: String, border: Color, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxHeight().clip(shape).background(Ss.Surface).border(if (border == Ss.Border) 1.dp else 2.dp, border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, color = Ss.TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun BigNumber(value: String, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(" $unit", color = Ss.TextSoft, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** Pasek z zaznaczonym zakresem pracy dyszy. */
@Composable
private fun Gauge(v: Double, min: Double, max: Double, okFrom: Double, okTo: Double) {
    val track = Ss.SurfaceHigh
    val ok = Ss.AccentSoft
    val mark = if (v in okFrom..okTo) Ss.Accent else Ss.Warning
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        fun x(a: Double) = (((a - min) / (max - min)).coerceIn(0.0, 1.0) * size.width).toFloat()
        drawRoundRect(track, size = size, cornerRadius = CornerRadius(7f, 7f))
        drawRoundRect(ok, Offset(x(okFrom), 0f), Size(x(okTo) - x(okFrom), size.height), CornerRadius(7f, 7f))
        drawRoundRect(mark, Offset(0f, size.height * 0.25f), Size(x(v), size.height * 0.5f), CornerRadius(4f, 4f))
    }
}

@Composable
private fun TankTile(hw: HardwareController, modifier: Modifier) {
    val sp = hw.sprayer
    val t = sp.computer.tank
    val c = sp.config
    val frac = (t.liters / c.tankCapacityL).coerceIn(0.0, 1.0)
    val low = t.liters <= c.tankReserveL
    val color = if (low) Ss.Warning else Ss.Info
    SprayTile(modifier.clickable(role = Role.Button) { askFill(hw) }, "ZBIORNIK", if (low) Ss.Warning else Ss.Border) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            val track = Ss.SurfaceHigh
            Canvas(Modifier.width(26.dp).height(110.dp)) {
                drawRoundRect(track, size = size, cornerRadius = CornerRadius(8f, 8f))
                val h = (size.height * frac).toFloat()
                drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(8f, 8f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(t.liters.pl(0), color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Label("z ${c.tankCapacityL.pl(0)} l")
                Label("≈ ${t.hectaresLeft(sp.computer.targetLha).pl(1)} ha", Ss.TextSoft, 13.sp)
                Label(if (low) "REZERWA" else "dotknij: napełnij", if (low) Ss.Warning else Ss.Accent, 12.sp)
            }
        }
    }
}

private fun askFill(hw: HardwareController) {
    Keypad.open(KeypadRequest(
        title = "Ile litrów wlano do zbiornika?",
        initial = "",
        mode = KeypadMode.Number,
        hint = "Dodaje do stanu. Wpisz 0, aby wyzerować zbiornik; pojemność ${hw.sprayer.config.tankCapacityL.pl(0)} l.",
        maxLength = 6,
        filter = { v -> v.filter { it.isDigit() || it in ".," } },
    ) { s ->
        s.replace(',', '.').toDoubleOrNull()?.let { l -> if (l == 0.0) hw.sprayer.setTank(0.0) else hw.sprayer.fill(l) }
    })
}

@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, onCenter: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(title)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Modifier.size(60.dp), onClick = onMinus) { NavIcon(IconKind.Minus, Ss.Text) }
            Box(
                Modifier.width(130.dp).height(60.dp).clip(RoundedCornerShape(12.dp)).background(Ss.Ground)
                    .clickable(role = Role.Button, onClick = onCenter),
                contentAlignment = Alignment.Center,
            ) { Text(value, color = Ss.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
            ActionButton(Modifier.size(60.dp), onClick = onPlus) { NavIcon(IconKind.Plus, Ss.Text) }
        }
    }
}

/** Belka: sekcje w proporcji do szerokości, dysze jako kropki, kolor = stan. */
@Composable
private fun Boom(hw: HardwareController) {
    val sp = hw.sprayer
    val c = sp.config
    Row(Modifier.fillMaxWidth().height(118.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until c.sectionCount) {
            val on = sp.computer.running && sp.mask and (1 shl i) != 0
            val ov = sp.overrides[i]
            val bg = when {
                on -> Ss.Accent
                ov == SectionOverride.OFF -> Ss.Ground
                else -> Ss.SurfaceHigh
            }
            val fg = if (on) Ss.OnAccent else Ss.Text
            val border = when (ov) { SectionOverride.AUTO -> Color.Transparent; SectionOverride.ON -> Ss.Warning; SectionOverride.OFF -> Ss.Danger }
            val shape = RoundedCornerShape(10.dp)
            Column(
                Modifier.weight(c.nozzlesPerSection[i].toFloat()).fillMaxHeight().clip(shape).background(bg)
                    .border(if (border == Color.Transparent) 0.dp else 3.dp, border, shape)
                    .clickable(role = Role.Button) { sp.cycleOverride(i) }.padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("${i + 1}", color = fg, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text(ov.label, color = if (ov == SectionOverride.AUTO) fg else if (on) Ss.OnAccent else border, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("${c.sectionWidthM(i).pl(1)} m", color = fg, fontSize = 12.sp, maxLines = 1)
                Box(Modifier.weight(1f))
                val dot = if (on) Ss.OnAccent else Ss.TextMuted
                val n = c.nozzlesPerSection[i]
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val step = size.width / n
                    for (k in 0 until n) drawCircle(dot, 3.5f, Offset(step * (k + 0.5f), size.height / 2))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ustawienia

@Composable
private fun SprayerSettings(hw: HardwareController) {
    val sp = hw.sprayer
    val c = sp.config
    val set: (SprayerConfig) -> Unit = { sp.update(it) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Belka i sekcje")
                SettingRow("Liczba sekcji") {
                    SmallStepper("${c.sectionCount}", onMinus = {
                        if (c.sectionCount > 1) set(c.copy(nozzlesPerSection = c.nozzlesPerSection.dropLast(1)))
                    }, onPlus = {
                        if (c.sectionCount < 9) set(c.copy(nozzlesPerSection = c.nozzlesPerSection + c.nozzlesPerSection.last()))
                    })
                }
                SettingRow("Rozstaw dysz") {
                    val opts = listOf(0.25, 0.375, 0.5, 0.75, 1.0)
                    val idx = opts.indexOfFirst { abs(it - c.nozzleSpacingM) < 1e-6 }
                    Segmented(opts.map { "${(it * 100).pl(if (it == 0.375) 1 else 0)} cm" }, idx, { set(c.copy(nozzleSpacingM = opts[it])) })
                }
                Label("${c.nozzlesPerMeter.pl(2)} dyszy na 1 mb · szerokość belki ${c.totalWidthM.pl(2)} m · ${c.nozzlesPerSection.sum()} dysz", Ss.TextSoft, 14.sp)
                for (i in 0 until c.sectionCount) {
                    SettingRow("Sekcja ${i + 1}: ${c.sectionWidthM(i).pl(2)} m") {
                        SmallStepper("${c.nozzlesPerSection[i]} dysz", onMinus = {
                            if (c.nozzlesPerSection[i] > 1) set(c.copy(nozzlesPerSection = c.nozzlesPerSection.mapIndexed { k, v -> if (k == i) v - 1 else v }))
                        }, onPlus = {
                            if (c.nozzlesPerSection[i] < 60) set(c.copy(nozzlesPerSection = c.nozzlesPerSection.mapIndexed { k, v -> if (k == i) v + 1 else v }))
                        })
                    }
                }
                val p = hw.profile
                val same = p.numSections == c.sectionCount && p.sectionWidthsEffective.zip(c.nozzlesPerSection.indices.map { c.sectionWidthM(it) }).all { abs(it.first - it.second) < 0.005 }
                ActionButton(Modifier.fillMaxWidth(), background = if (same) Ss.SurfaceHigh else Ss.Accent, contentColor = if (same) Ss.Text else Ss.OnAccent,
                    onClick = { hw.updateProfile(sp.applyTo(hw.profile)) }) {
                    ButtonText(if (same) "Sekcje maszyny zgodne z opryskiwaczem ✓" else "Zastosuj szerokości do sekcji maszyny (automat sekcji, pokrycie)",
                        if (same) Ss.Text else Ss.OnAccent)
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Zawory i pomiar")
                SettingRow("Zawory Arag") {
                    Segmented(listOf("2‑pin", "3‑pin"), if (c.valveType == ValveType.ARAG_2PIN) 0 else 1,
                        { set(c.copy(valveType = if (it == 0) ValveType.ARAG_2PIN else ValveType.ARAG_3PIN)) })
                }
                Label("Pomiar przepływu")
                FlowMode.entries.forEach { m -> OptionRow(m.label, c.flowMode == m) { set(c.copy(flowMode = m)) } }
                SettingRow("Impulsy przepływomierza na litr") {
                    NumberBox("Impulsy na litr", c.pulsesPerLiter, 1, 1.0, 10_000.0) { set(c.copy(pulsesPerLiter = it)) }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Dysze")
                Label("Rodzaj")
                NozzleKind.entries.forEach { k ->
                    OptionRow("${k.label} · ${k.minBar.pl(1)}–${k.maxBar.pl(1)} bar", c.nozzleKind == k) {
                        val liquid = if (k.name.startsWith("RSM") && c.liquid == Liquid.WATER) Liquid.RSM32 else c.liquid
                        set(c.copy(nozzleKind = k, liquid = liquid))
                    }
                }
                Label("Rozmiar (kolor ISO) — wydatek wody przy 3 bar")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Nozzle.ISO.forEach { n ->
                        val sel = n == c.nozzle
                        val shape = RoundedCornerShape(10.dp)
                        Column(
                            Modifier.weight(1f).height(76.dp).clip(shape).background(if (sel) Ss.AccentSoft else Ss.Ground)
                                .border(if (sel) 2.dp else 1.dp, if (sel) Ss.Accent else Ss.Border, shape)
                                .clickable(role = Role.Button) { set(c.copy(nozzle = n)) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        ) {
                            Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(isoColor(n)).border(1.dp, Ss.BorderStrong, RoundedCornerShape(50)))
                            Text(n.name, color = Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(n.lpmAt3Bar.pl(2), color = Ss.TextMuted, fontSize = 11.sp)
                        }
                    }
                }
                Label("Ciecz")
                Segmented(Liquid.entries.map { it.label.substringBefore(" /") }, c.liquid.ordinal, { set(c.copy(liquid = Liquid.entries[it])) })
                val r = c.speedRangeKmh()
                Text(
                    "Dysza ${c.nozzle.name} ${c.nozzle.color}: ${c.nozzleFlowAt(3.0).pl(2)} l/min przy 3 bar" +
                        (if (c.liquid.density > 1.0) " (${c.liquid.label}, ×${c.liquidFactor.pl(2)} względem wody)" else "") +
                        ". Przy ${c.targetLha.pl(0)} l/ha jedź ${r.start.pl(1)}–${r.endInclusive.pl(1)} km/h; przy 8 km/h ciśnienie ${c.pressureFor(8.0).pl(1)} bar.",
                    color = Ss.TextSoft, fontSize = 14.sp,
                )
            }
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Dawka i zbiornik")
                SettingRow("Dawka zadana, l/ha") { NumberBox("Dawka, l/ha", c.targetLha, 0, 10.0, 2000.0) { set(c.copy(targetLha = it)) } }
                if (c.liquid.density > 1.0) Label("= ${(c.targetLha * c.liquid.density).pl(0)} kg/ha ${c.liquid.label}", Ss.TextSoft, 13.sp)
                SettingRow("Pojemność zbiornika, l") { NumberBox("Pojemność zbiornika, l", c.tankCapacityL, 0, 50.0, 20_000.0) { set(c.copy(tankCapacityL = it)) } }
                SettingRow("Rezerwa (alarm), l") { NumberBox("Rezerwa, l", c.tankReserveL, 0, 0.0, 5000.0) { set(c.copy(tankReserveL = it)) } }
                Label("Zużyto od wyzerowania: ${sp.usedL.pl(1)} l", Ss.TextSoft, 14.sp)
                ActionButton(Modifier.fillMaxWidth(), onClick = { sp.resetUsed() }) { ButtonText("Wyzeruj licznik zużycia") }
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(if (selected) Ss.AccentSoft else Ss.Ground)
            .border(if (selected) 2.dp else 1.dp, if (selected) Ss.Accent else Ss.Border, shape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).border(2.dp, if (selected) Ss.Accent else Ss.TextMuted, RoundedCornerShape(50)).padding(4.dp)) {
            if (selected) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(Ss.Accent))
        }
        Text(label, color = Ss.Text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun SmallStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(Modifier.size(52.dp), onClick = onMinus) { NavIcon(IconKind.Minus, Ss.Text) }
        Text(value, Modifier.width(84.dp), color = Ss.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        ActionButton(Modifier.size(52.dp), onClick = onPlus) { NavIcon(IconKind.Plus, Ss.Text) }
    }
}

@Composable
private fun NumberBox(title: String, value: Double, decimals: Int, min: Double, max: Double, onCommit: (Double) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.width(140.dp).height(52.dp).clip(shape).background(Ss.Ground).border(1.dp, Ss.Border, shape)
            .clickable(role = Role.Button) {
                Keypad.open(KeypadRequest(
                    title = title,
                    initial = value.pl(decimals).replace(" ", ""),
                    mode = KeypadMode.Number,
                    hint = "Zakres: ${min.pl(decimals)} … ${max.pl(decimals)}",
                    maxLength = 10,
                    filter = { v -> v.filter { it.isDigit() || it in ".," } },
                ) { t -> t.replace(',', '.').toDoubleOrNull()?.let { onCommit(it.coerceIn(min, max)) } })
            },
        contentAlignment = Alignment.Center,
    ) { Text(value.pl(decimals), color = Ss.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

/**
 * Miniatura opryskiwacza na Mapie: dawka, ciśnienie, zbiornik, sekcje, START/STOP.
 * Dotknięcie → ekran Opryskiwacz (tam przycisk MAPA wraca). Widoczna, gdy maszyna to opryskiwacz albo trwa oprysk.
 */
@Composable
fun SprayerMini(hw: HardwareController, modifier: Modifier = Modifier) {
    val sp = hw.sprayer
    sp.tick
    val pc = sp.computer
    if (!hw.profile.isSprayer && !pc.running) return
    val c = sp.config
    val running = pc.running
    val ok = pc.rateOk()
    val low = pc.tank.liters <= c.tankReserveL
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.width(200.dp).clip(shape).background(Ss.Surface.copy(alpha = 0.94f))
            .border(if (running && !ok) 2.dp else 1.dp, if (running && !ok) Ss.Warning else Ss.Border, shape),
    ) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { sp.navRequest = "Sprayer" }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavIcon(IconKind.Sprayer, Ss.TextMuted, 16.dp)
                Text("  OPRYSK", color = Ss.TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("ustawienia ›", color = Ss.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (running) pc.actualLha.pl(0) else "—", color = if (!running) Ss.TextMuted else if (ok) Ss.Accent else Ss.Warning,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(" / ${pc.targetLha.pl(0)} l/ha", color = Ss.TextSoft, fontSize = 13.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            Text(
                "${sp.status?.pressureBar?.pl(1) ?: "—"} bar · ${pc.flowLpm.pl(1)} l/min",
                color = Ss.TextSoft, fontSize = 13.sp,
            )
            Text("zbiornik ${pc.tank.liters.pl(0)} l" + (if (low) " · REZERWA" else ""), color = if (low) Ss.Warning else Ss.Info, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().height(10.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until c.sectionCount) {
                    val on = running && sp.mask and (1 shl i) != 0
                    Box(Modifier.weight(c.nozzlesPerSection[i].toFloat()).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(if (on) Ss.Accent else Ss.SurfaceHigh))
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(52.dp).background(if (running) Ss.Danger else Ss.Accent)
                .clickable(role = Role.Button) { sp.toggleRun() },
            contentAlignment = Alignment.Center,
        ) { Text(if (running) "STOP" else "START OPRYSKU", color = if (running) Color.White else Ss.OnAccent, fontSize = 18.sp, fontWeight = FontWeight.Black) }
    }
}
