package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.Panel
import eu.smartsteer.navigator.ui.components.Pill
import eu.smartsteer.navigator.ui.components.SectionTitle
import eu.smartsteer.navigator.ui.components.Stat
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss

@Composable
fun FieldsScreen(ctrl: DemoController) {
    ctrl.frame
    val s = ctrl.session
    val plan = s.plan
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(400.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Pole demo", color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Label("${plan.widthM.pl(0)} × ${plan.lengthM.pl(0)} m · 1 linia AB", Ss.TextMuted, 13.sp)
                    }
                    Text("${(plan.widthM * plan.lengthM / 10_000).pl(2)} ha", color = Ss.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Label(
                "Tu pojawi się lista Twoich pól: rysowanie granicy jazdą po obwodzie, import KML / ISOXML oraz pola i zalecenia z WAR.",
                Ss.TextSoft, 13.sp,
            )
        }
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            SectionTitle("Pole demo")
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Stat("Przejazdy", "${plan.passCount}")
                Stat("Uwrocie", "${plan.headland.pl(1)} m")
                Stat("Do obrobienia", "${(s.interiorAreaM2 / 10_000).pl(2)} ha")
                Stat("Linia AB", "kurs 0°")
            }
            Label("Zmiana szerokości maszyny i wielkości pola: ekran Maszyna.", Ss.TextSoft, 13.sp)
        }
    }
}

@Composable
fun ConnectionsScreen(ctrl: DemoController) {
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Tryb symulatora", color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Label(
                            "Sieć modułów 192.168.5.x · nasłuch UDP ${AogFrame.PORT_FROM_MODULES}, wysyłka ${AogFrame.PORT_TO_MODULES}",
                            Ss.TextMuted, 13.sp,
                        )
                    }
                    Pill("DEMO", Ss.Warning)
                }
            }
            SectionTitle("Moduły")
            Module("Odbiornik GPS RTK", "NMEA GGA + VTG", "Symulowany", Ss.Warning)
            Module("Moduł autosterowania", "PGN 254 → / ← PGN 253", "Nie podłączono", Ss.TextMuted)
            Module("Moduł maszyny (sekcje)", "PGN 239", "Nie podłączono", Ss.TextMuted)
        }
        Panel(Modifier.width(400.dp).fillMaxHeight()) {
            SectionTitle("Co dalej")
            Label(
                "Następny krok: prawdziwe połączenie UDP z płytką AIO. Aplikacja zacznie wysyłać kąt zadany (PGN 254) i odczytywać kąt kół oraz przełączniki (PGN 253) — symulator zostanie jako tryb ćwiczeń.",
                Ss.TextSoft, 14.sp,
            )
            Box(Modifier.weight(1f))
            Label("Na Androidzie: przejściówka USB‑C → Ethernet albo Wi‑Fi przez router w kabinie.", Ss.TextMuted, 13.sp)
        }
    }
}

@Composable
private fun Module(name: String, detail: String, status: String, color: Color) {
    Panel(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Label(detail, Ss.TextMuted, 13.sp)
            }
            Text(status, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
