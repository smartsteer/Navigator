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
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.components.Segmented
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
fun ConnectionsScreen(ctrl: DemoController, hw: HardwareController) {
    hw.tick
    val s = hw.session
    val now = hw.now()
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (hw.hardware) "Tryb Sprzęt" else "Tryb Symulator", color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Label(
                            "Sieć modułów 192.168.5.x · nasłuch UDP ${AogFrame.PORT_FROM_MODULES}, wysyłka ${AogFrame.PORT_TO_MODULES}",
                            Ss.TextMuted, 13.sp,
                        )
                    }
                    Segmented(
                        options = listOf("SYMULATOR", "SPRZĘT"),
                        selected = if (hw.hardware) 1 else 0,
                        onSelect = { hw.setHardwareMode(it == 1) },
                        selectedColor = { if (it == 1) Ss.Accent else Ss.Warning },
                    )
                }
                hw.linkError?.let { Label(it, Ss.Warning, 14.sp) }
                if (hw.hardware) hw.sendError?.let { Label("Wysyłka: $it", Ss.Warning, 13.sp) }
            }
            SectionTitle("Moduły")
            if (!hw.hardware) {
                Module("Odbiornik GPS RTK", "NMEA GGA + VTG", "Symulowany", Ss.Warning)
                Module("Moduł autosterowania", "PGN 254 → / ← PGN 253", "Nie podłączono", Ss.TextMuted)
            } else {
                val g = s.lastGga
                val gpsOk = s.gpsAlive(now)
                Module(
                    "Odbiornik GPS RTK",
                    if (g != null) "${g.satellites} sat. · HDOP ${g.hdop.pl(1)} · kurs ${s.headingSource.label} · ${s.speedKmh.pl(1)} km/h" else "Czekam na NMEA (GGA, VTG, PANDA/PAOGI)",
                    if (gpsOk) s.fix.label else "Brak danych",
                    when { !gpsOk -> Ss.Warning; s.fix == FixQuality.RTK_FIX -> Ss.Accent; else -> Ss.Warning },
                )
                val st = s.autosteer
                val asOk = s.autosteerAlive(now)
                Module(
                    "Moduł autosterowania",
                    if (st != null) "Kąt kół ${st.actualAngleDeg.pl(1, plusSign = true)}° · przełącznik ${if (st.steerSwitch) "WŁ." else "WYŁ."} · PWM ${st.pwm}" else "Czekam na PGN 253",
                    if (asOk) "Połączony" else "Brak odpowiedzi",
                    if (asOk) Ss.Accent else Ss.Warning,
                )
            }
            Module("Moduł maszyny (sekcje)", "PGN 239", "Nie podłączono", Ss.TextMuted)
        }
        Panel(Modifier.width(420.dp).fillMaxHeight()) {
            SectionTitle("Co przychodzi z sieci")
            if (!hw.hardware) {
                Label("Włącz tryb SPRZĘT, aby zobaczyć na żywo dane z płytki AIO.", Ss.TextSoft, 14.sp)
            } else {
                Label("Odebrano ${hw.rxCount} · wysłano PGN 254: ${hw.txCount}", Ss.TextMuted, 13.sp)
                if (hw.log.isEmpty()) Label("Nic jeszcze nie przyszło. Sprawdź kabel i adres 192.168.5.x komputera.", Ss.Warning, 14.sp)
                hw.log.forEach { Text(it, color = Ss.TextSoft, fontSize = 12.sp, maxLines = 1) }
            }
            Box(Modifier.weight(1f))
            Label("Na Androidzie: przejściówka USB‑C → Ethernet albo Wi‑Fi przez router w kabinie. Komputer musi mieć adres 192.168.5.x (np. 192.168.5.10).", Ss.TextMuted, 13.sp)
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
