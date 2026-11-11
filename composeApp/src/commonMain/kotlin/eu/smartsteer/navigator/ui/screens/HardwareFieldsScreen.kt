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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.settings.openFolder
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.components.TextInput
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
                TextInput(
                    newName, { newName = it }, title = "Nazwa nowego pola", modifier = Modifier.weight(1f),
                    hint = "np. Za stodołą", maxLength = 40, fontSize = 18.sp,
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

            SectionTitle("Pola z WAR")
            val war = hw.war
            if (!war.configured) {
                Label("Połącz z WAR: Ustawienia → WAR (kod nawigacji z telefonu).", Ss.TextMuted, 13.sp)
            } else if (war.fields.isEmpty()) {
                Label(war.status.takeIf { it.startsWith("Czekam") } ?: "Pola z WAR jeszcze nie przyszły — otwórz WAR na telefonie.", Ss.TextSoft, 13.sp)
            }
            war.fields.forEach { wf ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(wf.name, color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Label(
                            listOfNotNull("${wf.ha.pl(2)} ha", wf.crop.takeIf { it.isNotEmpty() }, if (wf.parts > 1) "pole ma ${wf.parts} części — przyszła największa" else null).joinToString(" · "),
                            Ss.TextMuted, 12.sp,
                        )
                    }
                    ActionButton(onClick = { msg = hw.openWarField(wf) ?: "Otwarto „${wf.name}” z WAR" }) { ButtonText("Otwórz") }
                }
            }

            SectionTitle("Pola z AgOpenGPS")
            val agoFields = remember(hw.tick / 50) { hw.agoFields() }
            if (agoFields.isEmpty()) Label("Nie znalazłem pól AgOpenGPS (szukam w: ${hw.agoFolder}\\Fields).", Ss.TextMuted, 13.sp)
            agoFields.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(f, Modifier.weight(1f), color = Ss.Text, fontSize = 15.sp)
                    ActionButton(onClick = { msg = hw.importAgoField(f) }) { ButtonText("Kopiuj") }
                }
            }

            SectionTitle("Import z WAR i innych programów")
            Label("Wrzuć plik KML albo ISOXML (TASKDATA.XML lub cały folder TASKDATA) do folderu importu — pojawi się poniżej.", Ss.TextSoft, 13.sp)
            val files = remember(hw.tick / 30) { hw.importCandidates() }
            if (files.isEmpty()) Label("Folder importu jest pusty.", Ss.TextMuted, 13.sp)
            files.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(f, Modifier.weight(1f), color = Ss.Text, fontSize = 15.sp)
                    ActionButton(onClick = { msg = hw.importFile(f) }) { ButtonText("Wczytaj") }
                }
            }
            ActionButton(onClick = { if (!openFolder(hw.importLocation)) msg = "Folder importu: ${hw.importLocation}" }) { ButtonText("Otwórz folder importu", color = Ss.TextSoft) }
            Label(hw.importLocation, Ss.TextMuted, 12.sp)
        }

        // --- bieżące pole ---
        Panel(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
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
                    if (s.coverage.workedM2 > 100) ActionButton(onClick = { s.boundaryFromCoverage(); msg = s.message; hw.saveField() }) { ButtonText("Granica z obrobionej powierzchni") }
                }
            }

            SectionTitle("Eksport pola")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(onClick = { msg = hw.exportField() }) { ButtonText("Eksportuj pole (KML + ISOXML)") }
            }
            Label("Granica, przeszkody, linie i znaczniki — do Google Earth, WAR, terminali ISOBUS i innych programów.", Ss.TextMuted, 12.sp)

            SectionTitle("Mapa dawek (zmienne dawkowanie)")
            run {
                val rm = s.rateMap
                if (rm != null) {
                    Label("„${rm.name}”: ${rm.zones.size} stref, ${rm.minRate.pl(0)}–${rm.maxRate.pl(0)} ${rm.unit} · poza strefami ${rm.defaultRate.pl(0)}", Ss.Accent, 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(onClick = { hw.setDefaultRate(rm.minRate) }) { ButtonText("Poza strefami: najmniejsza", size = 13.sp) }
                        ActionButton(onClick = { hw.setDefaultRate(0.0) }) { ButtonText("Poza strefami: 0", size = 13.sp) }
                        ActionButton(onClick = { hw.clearRateMap(); msg = "Mapa dawek usunięta" }) { ButtonText("Usuń mapę", color = Ss.TextSoft, size = 13.sp) }
                    }
                }
                val cands = remember(hw.tick / 50) { hw.rateMapCandidates() }
                if (cands.isEmpty()) {
                    Label("Wrzuć plik mapy (GeoJSON albo KML ze strefami i dawką) do folderu: ${hw.importLocation}", Ss.TextMuted, 13.sp)
                } else {
                    Label("Pliki w folderze importu — dotknij, żeby wczytać do otwartego pola:", Ss.TextMuted, 13.sp)
                    cands.forEach { n -> ActionButton(onClick = { msg = hw.importRateMap(n) }) { ButtonText(n, size = 13.sp) } }
                }
                Label(
                    "Dawka w miejscu maszyny (z wyprzedzeniem) widać na mapie i idzie do sterownika dawki jako PGN 224. " +
                        "Bez sterownika: w Ustawienia → Maszyna wpisz dawkę ustawioną na rozsiewaczu i prędkość — mapa podpowie, jak jechać.",
                    Ss.TextMuted, 12.sp,
                )
            }

            SectionTitle("Mapa pola bez internetu")
            run {
                val src = eu.smartsteer.navigator.core.map.TileSource.entries.getOrElse(eu.smartsteer.navigator.ui.DisplayPrefs.background) { eu.smartsteer.navigator.core.map.TileSource.NONE }
                val tm = eu.smartsteer.navigator.ui.map.TileManager
                val pl = s.plane
                if (src == eu.smartsteer.navigator.core.map.TileSource.NONE) {
                    Label("Wybierz podkład (ORTO albo SAT) przyciskiem na mapie albo w Ustawienia → Ciągnik.", Ss.TextMuted, 13.sp)
                } else if (b == null || pl == null) {
                    Label("Najpierw granica pola — pobierze się mapa z zapasem 100 m.", Ss.TextMuted, 13.sp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton(onClick = {
                            val g = b.map { pl.toGeo(it) }
                            val m = 100.0
                            val dLat = m / 111_320.0
                            val dLon = m / (111_320.0 * kotlin.math.cos(g.first().first * kotlin.math.PI / 180))
                            tm.prefetch(src, g.minOf { it.first } - dLat, g.maxOf { it.first } + dLat, g.minOf { it.second } - dLon, g.maxOf { it.second } + dLon)
                        }) { ButtonText(if (tm.prefetching) "Pobieram…" else "Pobierz mapę pola (${src.short})") }
                        tm.prefetchText?.let { Label(it, Ss.TextSoft, 13.sp) }
                    }
                }
            }

            SectionTitle("Przeszkody na polu${if (s.obstacles.isNotEmpty()) " (${s.obstacles.size})" else ""}")
            Label("Oczko wodne, słup, kępa drzew: objedź dookoła środkiem ciągnika. Sekcje wyłączają się nad przeszkodą.", Ss.TextMuted, 13.sp)
            if (s.recordingObstacle != null) {
                Label("Nagrywam przeszkodę… ${s.recordingObstacle!!.size} punktów.", Ss.Warning, 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(background = Ss.Warning, border = Ss.Warning, minHeight = 60.dp, onClick = { s.finishObstacle(); msg = s.message; hw.saveField() }) {
                        ButtonText("Zakończ przeszkodę", color = Ss.OnWarning, weight = FontWeight.Bold)
                    }
                    ActionButton(onClick = { s.cancelObstacle(); msg = s.message }) { ButtonText("Przerwij", color = Ss.TextSoft) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(onClick = { s.startObstacle(); msg = s.message }) { ButtonText("Nagraj przeszkodę") }
                    if (s.obstacles.isNotEmpty()) ActionButton(onClick = { s.removeNearestObstacle(); msg = s.message; hw.saveField() }) { ButtonText("Usuń najbliższą", color = Ss.TextSoft) }
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
            SectionTitle("Raport zabiegu (dla WAR)")
            Label("Zapisuje się sam przy zamykaniu pola: GeoJSON z granicą, obrobionymi pasami, hektarami, datą i maszyną.", Ss.TextSoft, 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(onClick = { msg = hw.writeReport() ?: "Raport zapisany w folderze raportów" }) { ButtonText("Zapisz raport teraz") }
                ActionButton(onClick = { if (!openFolder(hw.exportLocation)) msg = "Folder raportów: ${hw.exportLocation}" }) { ButtonText("Otwórz folder raportów", color = Ss.TextSoft) }
            }
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
