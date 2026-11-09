package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.background
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
import eu.smartsteer.navigator.core.sim.DemoConfig
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.NavIcon
import eu.smartsteer.navigator.ui.components.Panel
import eu.smartsteer.navigator.ui.components.SectionTitle
import eu.smartsteer.navigator.ui.components.Segmented
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss

private val widths = listOf(3.0, 4.0, 6.0, 12.0)
private val fields = listOf(Pair(72.0, 160.0), Pair(120.0, 250.0), Pair(48.0, 100.0))

@Composable
fun ImplementScreen(ctrl: DemoController) {
    val cfg = ctrl.session.config
    var width by remember(cfg) { mutableStateOf(cfg.implementWidth) }
    var sections by remember(cfg) { mutableStateOf(cfg.sections) }
    var field by remember(cfg) { mutableStateOf(fields.indexOfFirst { it.first == cfg.fieldWidth && it.second == cfg.fieldLength }.coerceAtLeast(0)) }
    val changed = width != cfg.implementWidth || sections != cfg.sections ||
        fields[field].first != cfg.fieldWidth || fields[field].second != cfg.fieldLength

    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("Wymiary zestawu")
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Antena nad tylną osią", color = Ss.TextMuted, fontSize = 13.sp, modifier = Modifier.width(130.dp), textAlign = TextAlign.End)
                    Box(Modifier.size(width = 120.dp, height = 150.dp).clip(RoundedCornerShape(22.dp)).background(Ss.TractorBody), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(76.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(Ss.Ground))
                    }
                    Text("Rozstaw osi ${cfg.wheelbase.pl(2)} m", color = Ss.TextMuted, fontSize = 13.sp, modifier = Modifier.width(130.dp))
                }
                Box(Modifier.width(4.dp).height(40.dp).background(Ss.Text))
                Text(
                    "Oś → maszyna ${cfg.hitchToImplement.pl(1)} m",
                    color = Ss.TextSoft, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Ss.SurfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Box(Modifier.width(4.dp).height(20.dp).background(Ss.Text))
                Row(Modifier.width(520.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0 until sections) {
                        Box(
                            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(8.dp)).background(Ss.Accent),
                            contentAlignment = Alignment.Center,
                        ) { if (sections <= 12) Text("${i + 1}", color = Ss.OnAccent, fontWeight = FontWeight.Bold) }
                    }
                }
                Text(
                    "Szerokość robocza ${width.pl(1)} m · sekcja ${(width / sections).pl(2)} m",
                    color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Szerokość robocza")
                Segmented(
                    options = widths.map { "${it.pl(0)} m" },
                    selected = widths.indexOf(width).coerceAtLeast(0),
                    onSelect = { width = widths[it] },
                )
            }
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Sekcje")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Liczba sekcji", color = Ss.TextSoft, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    ActionButton(Modifier.width(52.dp), onClick = { sections = (sections - 1).coerceAtLeast(1) }) { NavIcon(IconKind.Minus, Ss.Text, 20.dp) }
                    Text("$sections", color = Ss.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), textAlign = TextAlign.Center)
                    ActionButton(Modifier.width(52.dp), onClick = { sections = (sections + 1).coerceAtMost(16) }) { NavIcon(IconKind.Plus, Ss.Text, 20.dp) }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                SectionTitle("Pole demo")
                Segmented(
                    options = fields.map { "${it.first.pl(0)}×${it.second.pl(0)} m" },
                    selected = field,
                    onSelect = { field = it },
                )
            }
            Box(Modifier.weight(1f))
            if (changed) Label("Zmiany zaczną działać po ponownym starcie przejazdu.", Ss.Warning, 13.sp)
            ActionButton(
                Modifier.fillMaxWidth(),
                background = if (changed) Ss.Accent else Ss.SurfaceHigh,
                border = if (changed) Ss.Accent else Ss.BorderStrong,
                onClick = {
                    ctrl.rebuild(
                        cfg.copy(
                            implementWidth = width,
                            sections = sections,
                            fieldWidth = fields[field].first,
                            fieldLength = fields[field].second,
                            headland = maxOf(9.0, width * 2),
                        ),
                    )
                },
            ) {
                ButtonText("Zastosuj i zacznij od nowa", color = if (changed) Ss.OnAccent else Ss.TextSoft, weight = FontWeight.Bold)
            }
        }
    }
}
