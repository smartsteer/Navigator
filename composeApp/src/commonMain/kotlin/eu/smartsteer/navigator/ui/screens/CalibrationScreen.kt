package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.sprayer.CalibrationWizard
import eu.smartsteer.navigator.core.sprayer.CalibrationWizard.NozzleState
import eu.smartsteer.navigator.core.sprayer.CalibrationWizard.Step
import eu.smartsteer.navigator.core.sprayer.FlowMode
import eu.smartsteer.navigator.core.sprayer.PressureInput
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.Keypad
import eu.smartsteer.navigator.ui.components.KeypadMode
import eu.smartsteer.navigator.ui.components.KeypadRequest
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.Panel
import eu.smartsteer.navigator.ui.components.Segmented
import eu.smartsteer.navigator.ui.components.touchVerticalScroll
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss

/**
 * Kreator kalibracji opryskiwacza — duże kroki, jedna rzecz naraz, wszystko na samej wodzie na postoju.
 * Logika: core/sprayer/CalibrationWizard.kt (testy na symulatorze z luzem zaworu, opóźnieniem i zużytymi dyszami).
 */
@Composable
fun CalibrationWizardView(hw: HardwareController) {
    val sp = hw.sprayer
    sp.tick
    val w = sp.calibration ?: return
    val steps = w.steps
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // pasek kroków
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { i, s ->
                val cur = s == w.step
                val done = steps.indexOf(w.step) > i
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (cur) Ss.Accent else if (done) Ss.AccentSoft else Ss.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}. ${s.title}", color = if (cur) Ss.OnAccent else if (done) Ss.Accent else Ss.TextMuted,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.weight(1f).fillMaxHeight().touchVerticalScroll()) {
                Text(w.step.title, color = Ss.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                instructions(w).forEach { line -> Text(line, color = Ss.TextSoft, fontSize = 17.sp, lineHeight = 23.sp) }
                Box(Modifier.height(6.dp))
                StepControls(hw, w)
            }
            // na żywo
            Panel(Modifier.width(320.dp).fillMaxHeight()) {
                Label("NA ŻYWO", Ss.TextMuted, 12.sp)
                val pc = sp.computer
                Big(pc.pressureBar?.pl(1) ?: "—", "bar")
                Big(pc.flowLpm.pl(1), "l/min")
                if (w.status.isNotEmpty()) Text(w.status, color = Ss.Text, fontSize = 15.sp, lineHeight = 20.sp)
                if (w.busy || w.step == Step.NOZZLE && w.nozzleState == NozzleState.MEASURING) ProgressBar(w.progress)
                w.error?.let { e ->
                    Text(e, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ss.Danger).padding(12.dp))
                    ActionButton(Modifier.fillMaxWidth(), onClick = { w.retry() }) { ButtonText("PONÓW KROK", size = 16.sp) }
                }
            }
        }
        // nawigacja
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Modifier.width(170.dp).height(60.dp), onClick = { sp.finishCalibration(false) }) { ButtonText("ANULUJ", size = 16.sp) }
            if (w.step != steps.first()) ActionButton(Modifier.width(170.dp).height(60.dp), onClick = { w.back() }) { ButtonText("◀ WSTECZ", size = 16.sp) }
            Box(Modifier.weight(1f))
            if (w.step == Step.NOZZLE && !w.stepDone) ActionButton(Modifier.width(170.dp).height(60.dp), onClick = { w.next() }) { ButtonText("POMIŃ", size = 16.sp) }
            if (w.step == Step.DONE) {
                ActionButton(Modifier.width(260.dp).height(60.dp), background = Ss.Accent, contentColor = Ss.OnAccent, border = Color.Transparent,
                    onClick = { sp.finishCalibration(true) }) { ButtonText("ZAPISZ I ZAKOŃCZ", Ss.OnAccent, 17.sp, FontWeight.Bold) }
            } else {
                val ok = w.stepDone && !w.busy
                ActionButton(Modifier.width(220.dp).height(60.dp), background = if (ok) Ss.Accent else Ss.SurfaceHigh,
                    contentColor = if (ok) Ss.OnAccent else Ss.TextMuted, border = Color.Transparent,
                    onClick = { if (ok) w.next() }) { ButtonText("DALEJ ▶", if (ok) Ss.OnAccent else Ss.TextMuted, 17.sp, FontWeight.Bold) }
            }
        }
    }
}

private fun instructions(w: CalibrationWizard): List<String> = when (w.step) {
    Step.PREPARE -> listOf(
        "1. Wlej do zbiornika czystą wodę — co najmniej 200 l. Bez środka!",
        "2. Opryskiwacz stoi na placu, belka rozłożona, nikt nie stoi pod belką.",
        "3. Włącz pompę (WOM) na zwykłe obroty pracy.",
        "4. Płytka podłączona (USB / Bluetooth / sieć). Dalej program prowadzi sam — zajmie to ok. 10 minut.",
    )
    Step.SENSOR -> listOf(
        "Wybierz czujnik ciśnienia jak na jego tabliczce: rodzaj sygnału i zakres.",
        "Zawór główny jest teraz zamknięty — gdy ciśnienie pokazuje prawie zero, naciśnij ZERUJ.",
    )
    Step.FLOW_CHECK -> listOf(
        "Otwieram zawór główny i wszystkie sekcje — z dysz poleci woda.",
        "Sprawdzam, czy jest ciśnienie i czy przepływomierz daje impulsy. Gdy coś nie gra, napiszę, co sprawdzić.",
    )
    Step.VALVE -> listOf(
        "Program sam pozna zawór regulacyjny: zamknie go do końca, otworzy krokami, zmierzy, jak szybko rośnie przepływ, jak długo się ustala i ile ma luzu.",
        "Ciśnienie będzie się zmieniać od zera do pełnego — to normalne. Trwa 2–4 minuty. Nic nie dotykaj.",
    )
    Step.NOZZLE -> listOf(
        "Próba z miarką (dzbanek z podziałką). Program ustawi ciśnienie ok. ${w.testBar.pl(0)} bar.",
        "Gdy będzie gotowe: podstaw miarki pod 1–3 dysze (najlepiej lewa, środek, prawa), naciśnij START POMIARU i po minucie odstaw.",
        "Wpisz, ile litrów nalało się z każdej dyszy. Z tego program poprawi przepływomierz i sprawdzi zużycie dysz.",
    )
    Step.RATE -> listOf(
        "Ostatnia próba: program „jedzie” wirtualnie ${w.testSpeedKmh.pl(1)} km/h na postoju i sam ustawia dawkę ${w.result.targetLha.pl(0)} l/ha.",
        "Potem przyspiesza i sprawdza, jak szybko poprawia dawkę. Trwa minutę.",
    )
    Step.DONE -> listOf("Kalibracja zakończona. Co się zmieniło:")
}

@Composable
private fun ColumnScope.StepControls(hw: HardwareController, w: CalibrationWizard) {
    val sp = hw.sprayer
    var msg by remember(w.step) { mutableStateOf<String?>(null) }
    when (w.step) {
        Step.PREPARE -> Label(if (w.stepDone) "✔ Płytka połączona — naciśnij DALEJ." else "Czekam na łączność z płytką…",
            if (w.stepDone) Ss.Accent else Ss.Warning, 16.sp)
        Step.SENSOR -> {
            val r = w.result
            Label("Sygnał", Ss.TextMuted, 13.sp)
            Segmented(PressureInput.entries.map { it.label }, r.pressureInput.ordinal, { w.setSensor(PressureInput.entries[it], r.pressureRangeBar) })
            Label("Zakres czujnika", Ss.TextMuted, 13.sp)
            val ranges = listOf(6.0, 10.0, 16.0, 20.0, 25.0)
            Segmented(ranges.map { "0–${it.pl(0)} bar" }, ranges.indexOf(r.pressureRangeBar).coerceAtLeast(0), { w.setSensor(r.pressureInput, ranges[it]) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Modifier.width(200.dp).height(56.dp), onClick = { msg = w.zeroPressure(sp.status?.pressureBar) }) { ButtonText("ZERUJ", size = 17.sp) }
                msg?.let { Label(it, Ss.TextSoft, 14.sp) }
            }
        }
        Step.FLOW_CHECK -> if (w.stepDone) Label("✔ Przepływ jest — naciśnij DALEJ.", Ss.Accent, 16.sp)
        Step.VALVE -> {
            if (!w.busy && !w.stepDone) BigButton("START NAUKI ZAWORU") { w.startValveLearn() }
            if (w.stepDone) Label("✔ " + w.status, Ss.Accent, 16.sp)
        }
        Step.NOZZLE -> NozzleControls(w)
        Step.RATE -> {
            if (!w.busy && !w.stepDone) BigButton("START PRÓBY DAWKI") { w.startRateTest() }
            if (w.stepDone) Label("✔ " + w.status, Ss.Accent, 16.sp)
        }
        Step.DONE -> {
            w.summary().forEach { Text("• $it", color = Ss.Text, fontSize = 17.sp, lineHeight = 23.sp) }
            if (sp.config.flowMode == FlowMode.PRESSURE) Label("Tryb „przepływ z ciśnienia”: przepływomierz nie był potrzebny.", Ss.TextMuted, 13.sp)
            Label("ZAPISZ I ZAKOŃCZ — wyniki zostają w ustawieniach. ANULUJ — wszystko wraca jak przed kreatorem.", Ss.TextMuted, 14.sp)
        }
    }
}

@Composable
private fun NozzleControls(w: CalibrationWizard) {
    var liters by remember { mutableStateOf(listOf<Double?>(null, null, null)) }
    var err by remember { mutableStateOf<String?>(null) }
    when (w.nozzleState) {
        NozzleState.IDLE -> BigButton("USTAW CIŚNIENIE DO PRÓBY") { w.startNozzle() }
        NozzleState.STABILIZING -> Label("Ustawiam ciśnienie… przygotuj miarki.", Ss.Warning, 16.sp)
        NozzleState.READY -> BigButton("START POMIARU (60 s)") { w.startMeasure() }
        NozzleState.MEASURING -> Text("${w.measureLeftS} s", color = Ss.Accent, fontSize = 72.sp, fontWeight = FontWeight.Black)
        NozzleState.ENTER -> {
            Label("Odstaw miarki. Ile litrów z każdej dyszy? (puste = nie łapałem)", Ss.Text, 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (k in 0..2) {
                    ActionButton(Modifier.width(170.dp).height(64.dp), onClick = {
                        Keypad.open(KeypadRequest(
                            title = "Dysza ${k + 1}: ile litrów w minutę?",
                            initial = liters[k]?.toString()?.replace('.', ',') ?: "",
                            mode = KeypadMode.Number,
                            hint = "Np. 1,25 — z podziałki miarki (1 l = 1000 ml).",
                            maxLength = 6,
                            filter = { v -> v.filter { it.isDigit() || it in ".," } },
                        ) { s -> liters = liters.toMutableList().also { l -> l[k] = s.replace(',', '.').toDoubleOrNull() } })
                    }) { ButtonText("Dysza ${k + 1}: ${liters[k]?.pl(2) ?: "—"} l", size = 16.sp) }
                }
            }
            BigButton("ZATWIERDŹ WYNIKI") { err = w.enterNozzleLiters(liters.filterNotNull()) }
            err?.let { Label(it, Ss.Warning, 15.sp) }
        }
        NozzleState.DONE -> Label("✔ " + w.nozzleReport, Ss.Accent, 16.sp)
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    ActionButton(Modifier.fillMaxWidth().height(72.dp), background = Ss.Accent, contentColor = Ss.OnAccent, border = Color.Transparent, onClick = onClick) {
        ButtonText(text, Ss.OnAccent, 20.sp, FontWeight.Black)
    }
}

@Composable
private fun Big(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = Ss.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(" $unit", color = Ss.TextSoft, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
}

@Composable
private fun ProgressBar(f: Float) {
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Ss.SurfaceHigh)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Ss.Accent))
    }
}
