package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.NavIcon
import eu.smartsteer.navigator.ui.components.Panel
import eu.smartsteer.navigator.ui.components.Pill
import eu.smartsteer.navigator.ui.components.SectionTitle
import eu.smartsteer.navigator.ui.components.Segmented
import eu.smartsteer.navigator.ui.components.Stat
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss

/** Ekran Pola w trybie Sprzęt: zakładanie, otwieranie i zapis pól, granica objazdem, uwrocie. */
@Composable
fun HardwareFieldsScreen(hw: HardwareController) {
    hw.tick
    val s = hw.session
    var msg by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // --- lista pól ---
        Column(
            Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Nowe pole")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicTextField(
                    value = newName,
                    onValueChange = { newName = it.take(40) },
                    singleLine = true,
                    textStyle = TextStyle(color = Ss.Text, fontSize = 18.sp),
                    cursorBrush = SolidColor(Ss.Accent),
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Ss.Ground)
                        .border(1.dp, Ss.BorderStrong, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 14.dp),
                    decorationBox = { inner ->
                        if (newName.isEmpty()) Text("np. Za stodołą", color = Ss.TextMuted, fontSize = 18.sp)
                        inner()
                    },
                )
                ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = {
                    val err = hw.newField(newName)
                    msg = err ?: "Założono pole „${s.fieldName}”"
                    if (err == null) newName = ""
                }) { ButtonText("Załóż", color = Ss.OnAccent, weight = FontWeight.Bold) }
            }
            Label("Pole zakłada się w miejscu, gdzie stoi ciągnik (potrzebny GPS i tryb SPRZĘT).", Ss.TextMuted, 13.sp)

            SectionTitle("Zapisane pola")
            if (hw.fieldNames.isEmpty()) Label("Brak zapisanych pól.", Ss.TextSoft, 14.sp)
            hw.fieldNames.forEach { name ->
                val current = name == s.fieldName
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (current) Ss.AccentSoft else Ss.Surface)
                        .border(1.dp, if (current) Ss.Accent else Ss.Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NavIcon(IconKind.Field, if (current) Ss.Accent else Ss.TextMuted)
                    Text(name, Modifier.weight(1f), color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (current) {
                        Pill("OTWARTE", Ss.Accent, Ss.AccentSoft)
                    } else {
                        ActionButton(onClick = { msg = hw.openField(name) ?: "Otwarto „$name”" }) { ButtonText("Otwórz") }
                        ActionButton(onClick = { hw.deleteField(name); msg = "Usunięto „$name”" }) { ButtonText("Usuń", color = Ss.Warning) }
                    }
                }
            }
            msg?.let { Label(it, Ss.TextSoft, 14.sp) }
            hw.fieldError?.let { Label(it, Ss.Warning, 14.sp) }
            Label("Pliki pól: ${hw.fieldsLocation}", Ss.TextMuted, 12.sp)
        }

        // --- bieżące pole ---
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            val name = s.fieldName
            if (name == null) {
                SectionTitle("Żadne pole nie jest otwarte")
                Label(
                    "Bez pola linie i mapa pokrycia działają, ale nie zapisują się. Załóż nowe pole albo otwórz zapisane.",
                    Ss.TextSoft, 14.sp,
                )
                return@Panel
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), color = Ss.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                ActionButton(onClick = { msg = hw.saveField() ?: "Zapisano" }) { ButtonText("Zapisz") }
                ActionButton(Modifier.padding(start = 10.dp), onClick = { msg = hw.closeField() ?: "Pole zamknięte i zapisane" }) { ButtonText("Zamknij pole", color = Ss.TextSoft) }
            }
            val b = s.boundary
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Powierzchnia", b?.let { "${(Polygon.area(it) / 10_000).pl(2)} ha" } ?: "brak granicy")
                Stat("Obrobiono", "${(s.coverage.workedM2 / 10_000).pl(2)} ha")
                Stat("Uwrocie", if (s.headlandWidth > 0) "${s.headlandWidth.pl(1)} m" else "brak")
            }

            SectionTitle("Granica pola")
            if (s.recordingBoundary != null) {
                Label("Nagrywam… ${s.recordingBoundary!!.size} punktów. Objedź całe pole i wróć do miejsca startu.", Ss.Warning, 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(background = Ss.Warning, border = Ss.Warning, minHeight = 60.dp, onClick = { s.finishBoundary(); msg = s.message; hw.saveField() }) {
                        ButtonText("Zakończ granicę", color = Ss.OnWarning, weight = FontWeight.Bold)
                    }
                    ActionButton(onClick = { s.cancelBoundary(); msg = s.message }) { ButtonText("Przerwij", color = Ss.TextSoft) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Krawędź do nagrania", color = Ss.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Segmented(listOf("Lewa", "Środek", "Prawa"), s.boundarySide + 1, { s.boundarySide = it - 1; hw.touch() })
                }
                Label("Lewa/prawa = krawędź maszyny po tej stronie (pół szerokości od środka ciągnika).", Ss.TextMuted, 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(background = Ss.AccentSoft, border = Ss.Accent, minHeight = 60.dp, onClick = { s.startBoundary(); msg = s.message }) {
                        ButtonText(if (b == null) "Nagraj granicę" else "Nagraj od nowa", color = Ss.Accent, weight = FontWeight.Bold)
                    }
                    if (b != null) ActionButton(onClick = { s.clearBoundary(); msg = s.message; hw.saveField() }) { ButtonText("Usuń granicę", color = Ss.TextSoft) }
                }
            }

            SectionTitle("Uwrocie")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Szerokość uwrocia", color = Ss.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                ActionButton(Modifier.width(56.dp), onClick = { s.setHeadlandWidth((s.headlandWidth - s.profile.passSpacing).coerceAtLeast(0.0)); hw.saveField(); hw.touch() }) {
                    NavIcon(IconKind.Minus, Ss.Text, 20.dp)
                }
                Text(
                    if (s.headlandWidth > 0) "${s.headlandWidth.pl(1)} m" else "brak",
                    Modifier.width(110.dp), color = Ss.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                ActionButton(Modifier.width(56.dp), onClick = { s.setHeadlandWidth(s.headlandWidth + s.profile.passSpacing); hw.saveField(); hw.touch() }) {
                    NavIcon(IconKind.Plus, Ss.Text, 20.dp)
                }
            }
            Label(
                if (b == null) "Uwrocie liczy się od granicy — najpierw nagraj granicę."
                else "Krok = jeden przejazd (${s.profile.passSpacing.pl(2)} m). Na uwrociu sekcje się wyłączają (ustawienie w Ustawienia → Maszyna).",
                Ss.TextMuted, 13.sp,
            )
            SectionTitle("Mapa pokrycia")
            // dwa dotknięcia — żeby nie skasować pracy przypadkiem
            ActionButton(
                background = if (confirmClear) Ss.Warning else Ss.SurfaceHigh,
                onClick = {
                    if (confirmClear) { s.coverage.clear(); msg = "Mapa pokrycia wyczyszczona"; hw.saveField(); confirmClear = false } else confirmClear = true
                },
            ) {
                ButtonText(if (confirmClear) "Na pewno? Dotknij jeszcze raz" else "Wyczyść pokrycie", color = if (confirmClear) Ss.OnWarning else Ss.Warning)
            }
        }
    }
}
