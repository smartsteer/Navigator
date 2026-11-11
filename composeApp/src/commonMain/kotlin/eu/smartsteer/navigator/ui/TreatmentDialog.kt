package eu.smartsteer.navigator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.war.WarProduct
import eu.smartsteer.navigator.core.war.WarTreatment
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.Keypad
import eu.smartsteer.navigator.ui.components.KeypadMode
import eu.smartsteer.navigator.ui.components.KeypadRequest
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.TextInput
import eu.smartsteer.navigator.ui.theme.Ss

/**
 * „Co robiłeś na polu?” — po zamknięciu pola (tylko z WAR). Rodzaj, środek / nawóz / odmiana z listy gospodarstwa
 * w WAR (najpierw ostatnio używane) i dawka. Odpowiedź idzie z pracą do WAR i wypełnia wpis do ewidencji zabiegów
 * albo karty pola. „Pomiń” — praca idzie bez szczegółów (WAR zapyta na telefonie).
 */
@Composable
fun TreatmentDialog(war: WarController) {
    val ask = war.pendingAsk ?: return
    val sug = ask.suggestion
    var kind by remember(ask.id) { mutableStateOf(sug?.kind ?: "") }
    var product by remember(ask.id) { mutableStateOf(sug?.product ?: "") }
    var permit by remember(ask.id) { mutableStateOf(sug?.permit ?: "") }
    var dose by remember(ask.id) { mutableStateOf(sug?.dose) }
    var unit by remember(ask.id) { mutableStateOf(sug?.unit ?: "") }
    var crop by remember(ask.id) { mutableStateOf(sug?.crop ?: "") }
    val variable = sug?.variableRate == true && sug.product == product

    fun pick(p: WarProduct) {
        product = p.name; permit = p.permit
        if (p.dose != null) dose = p.dose
        if (p.unit.isNotEmpty() && p.unit in WarTreatment.units(kind)) unit = p.unit
        if (p.crop.isNotEmpty()) crop = p.crop
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC050807))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(16.dp)
                .clip(RoundedCornerShape(18.dp)).background(Ss.Surface).border(1.dp, Ss.BorderStrong, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Co robiłeś na polu „${ask.fieldName}”?", color = Ss.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Label("${ask.ha.pl(2)} ha${if (ask.tool.isNotEmpty()) " · ${ask.tool}" else ""} — trafi do WAR (ewidencja zabiegów / karta pola), nie trzeba przepisywać.", Ss.TextMuted, 13.sp)

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WarTreatment.KINDS.forEach { (k, n) ->
                    Chip(n, k == kind) {
                        if (k != kind) {
                            kind = k
                            if (unit !in WarTreatment.units(k)) unit = WarTreatment.units(k).firstOrNull() ?: ""
                            if (sug?.kind != k) { product = ""; permit = ""; dose = null; crop = "" }
                        }
                    }
                }
            }

            if (kind.isNotEmpty() && kind != "uprawa" && kind != "inne") {
                val what = when (kind) { "oprysk" -> "Środek ochrony"; "dolistny" -> "Nawóz dolistny / odżywka"; "siew" -> "Odmiana"; else -> "Nawóz" }
                Label(what, Ss.TextSoft, 13.sp)
                TextInput(
                    product, { product = it; permit = war.products.forKind(kind).firstOrNull { p -> p.name.equals(it, true) }?.permit ?: "" },
                    title = what, modifier = Modifier.fillMaxWidth(), hint = "dotknij, żeby wpisać — albo wybierz poniżej", maxLength = 60, fontSize = 18.sp,
                )
                val all = war.products.forKind(kind)
                val q = product.trim().lowercase()
                val list = (if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) && !it.name.equals(product, true) }).take(8)
                if (list.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        list.forEach { p ->
                            val extra = listOfNotNull(p.permit.takeIf { it.isNotEmpty() }, p.crop.takeIf { it.isNotEmpty() },
                                p.dose?.let { d -> "ostatnio ${d.pl(if (d < 10) 2 else 0)} ${p.unit}" }).joinToString(" · ")
                            ActionButton(Modifier.fillMaxWidth(), minHeight = 46.dp, onClick = { pick(p) }) {
                                Text(p.name, color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (extra.isNotEmpty()) Text(extra, color = Ss.TextMuted, fontSize = 12.sp)
                            }
                        }
                    }
                } else if (all.isEmpty()) {
                    Label("Lista z WAR jeszcze nie przyszła — wpisz nazwę ręcznie (WAR na telefonie wyśle listę przy najbliższym sprawdzeniu).", Ss.TextMuted, 12.sp)
                }
                if (kind == "siew") {
                    Label("Uprawa", Ss.TextSoft, 13.sp)
                    TextInput(crop, { crop = it }, title = "Uprawa", modifier = Modifier.fillMaxWidth(), hint = "np. pszenica ozima", maxLength = 40)
                }

                Label(if (variable) "Dawka (zmienna z mapy WAR — typowa strefa)" else "Dawka", Ss.TextSoft, 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier.width(160.dp).clip(RoundedCornerShape(10.dp)).background(Ss.Ground).border(1.dp, Ss.BorderStrong, RoundedCornerShape(10.dp))
                            .clickable {
                                Keypad.open(KeypadRequest("Dawka ($unit)", dose?.let { it.pl(if (it < 10) 2 else 1).replace(" ", "") } ?: "", KeypadMode.Number,
                                    maxLength = 10, filter = { v -> v.filter { c -> c.isDigit() || c in ".," } }) { t -> dose = t.replace(',', '.').toDoubleOrNull() })
                            }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(dose?.pl(if (dose!! < 10) 2 else 1) ?: "—", color = Ss.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                    WarTreatment.units(kind).forEach { u -> Chip(u, u == unit) { unit = u } }
                }
            } else if (kind == "uprawa" || kind == "inne") {
                Label(if (kind == "uprawa") "Co (np. orka, talerzowanie, uprawa przedsiewna)" else "Co zrobiono", Ss.TextSoft, 13.sp)
                TextInput(product, { product = it }, title = "Co zrobiono", modifier = Modifier.fillMaxWidth(), hint = "opcjonalnie", maxLength = 60)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(Modifier.weight(2f), background = if (kind.isEmpty()) Ss.SurfaceHigh else Ss.Accent, border = if (kind.isEmpty()) Ss.BorderStrong else Ss.Accent, onClick = {
                    if (kind.isNotEmpty()) war.answer(ask.id, WarTreatment(kind, product, permit, dose, if (dose != null) unit else "", crop, variable))
                }) { ButtonText("Zapisz do WAR", color = if (kind.isEmpty()) Ss.TextMuted else Ss.OnAccent, weight = FontWeight.Bold) }
                ActionButton(Modifier.weight(1f), onClick = { war.answer(ask.id, null) }) { ButtonText("Pomiń", color = Ss.TextSoft) }
            }
            Label("Pomiń — praca i tak trafi do WAR, a szczegóły uzupełnisz na telefonie. Pytanie wyłączysz w Ustawienia → WAR.", Ss.TextMuted, 11.sp)
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Ss.Accent else Ss.SurfaceHigh)
            .border(1.dp, if (on) Ss.Accent else Ss.BorderStrong, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) Ss.OnAccent else Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
