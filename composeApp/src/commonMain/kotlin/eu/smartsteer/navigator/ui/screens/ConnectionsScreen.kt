package eu.smartsteer.navigator.ui.screens

import eu.smartsteer.navigator.ui.components.touchVerticalScroll
import eu.smartsteer.navigator.ui.components.touchHorizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.net.localAddresses
import eu.smartsteer.navigator.ui.DemoController
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.ui.components.KeypadMode
import eu.smartsteer.navigator.ui.components.TextInput
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.Label
import eu.smartsteer.navigator.ui.components.Panel
import eu.smartsteer.navigator.ui.components.SectionTitle
import eu.smartsteer.navigator.ui.components.Segmented
import eu.smartsteer.navigator.ui.pl
import eu.smartsteer.navigator.ui.theme.Ss

/** Ekran Połączenia: tryb, sieć modułów (szukanie, zmiana sieci), stan modułów, samouczek i podgląd pakietów. */
@Composable
fun ConnectionsScreen(@Suppress("UNUSED_PARAMETER") ctrl: DemoController, hw: HardwareController) {
    hw.tick
    var rightTab by remember { mutableStateOf(0) }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().touchVerticalScroll(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModePanel(hw)
            NetworkPanel(hw)
            if (hw.serialSupported) SerialPanel(hw)
            WifiPanel(hw)
            CameraPanel(hw)
            SectionTitle("Moduły")
            ModulesStatus(hw)
        }
        Panel(Modifier.width(460.dp).fillMaxHeight().touchVerticalScroll()) {
            Segmented(listOf("Samouczek łączenia", "Co przychodzi"), rightTab, { rightTab = it })
            if (rightTab == 0) Tutorial(hw) else LiveLog(hw)
        }
    }
}

@Composable
private fun ModePanel(hw: HardwareController) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(if (hw.hardware) "Tryb Sprzęt" else "Tryb Symulator", color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Label(
                    "Sieć modułów ${hw.subnetText}.x · nasłuch UDP ${AogFrame.PORT_FROM_MODULES}, wysyłka ${AogFrame.PORT_TO_MODULES}",
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Symulacja jazdy", color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Label("Udaje odbiornik RTK i moduł skrętu — można przećwiczyć linie, autoster, sekcje i zawroty bez ciągnika. Po ponownym uruchomieniu programu jest zawsze wyłączona.", Ss.TextMuted, 13.sp)
            }
            Segmented(
                options = listOf("WYŁ.", "WŁ."),
                selected = if (hw.simulating) 1 else 0,
                onSelect = { hw.setSimulation(it == 1) },
                selectedColor = { if (it == 1) Ss.Warning else Ss.TextMuted },
            )
        }
        hw.linkError?.let { Label(it, Ss.Warning, 14.sp) }
        if (hw.hardware) hw.sendError?.let { Label("Wysyłka: $it", Ss.Warning, 13.sp) }
    }
}

/** Kamera IP (MJPEG albo zdjęcie JPEG) — podgląd na mapie. */
@Composable
private fun CameraPanel(hw: HardwareController) {
    var url by remember { mutableStateOf(hw.cameraUrl) }
    val cam = eu.smartsteer.navigator.ui.camera.Camera
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Kamera zewnętrzna (IP) — np. z tyłu maszyny")
        Label("Adres obrazu MJPEG albo zdjęcia JPEG z kamery w sieci (np. http://192.168.5.50/video.mjpg). Podgląd włączasz przyciskiem KAM na mapie.", Ss.TextMuted, 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            eu.smartsteer.navigator.ui.components.TextInput(url, { url = it }, title = "Adres kamery", modifier = Modifier.weight(1f), hint = "http://…", maxLength = 200)
            ActionButton(onClick = { hw.setCamera(url); cam.start(url) }) { ButtonText("Zapisz i sprawdź") }
            if (cam.running) ActionButton(onClick = { cam.stop() }) { ButtonText("Stop", color = Ss.TextSoft) }
        }
        Label("Stan: ${cam.status}", Ss.TextSoft, 13.sp)
    }
}

/** Łączność z płytką przez Wi-Fi (router/most Wi-Fi w kabinie). */
@Composable
private fun WifiPanel(hw: HardwareController) {
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Łączność przez Wi-Fi", color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Label("Tablet bez kabla: płytka podpięta kablem do małego routera Wi-Fi w kabinie, tablet łączy się z jego siecią.", Ss.TextMuted, 13.sp)
            }
            Segmented(listOf("KABEL", "WI-FI"), if (hw.wifiMode) 1 else 0, { hw.setWifi(it == 1) }, selectedColor = { if (it == 1) Ss.Info else Ss.Accent })
        }
        val loss = hw.linkLossPct
        val gap = hw.linkMaxGapMs
        if (loss != null && gap != null) {
            val bad = loss > 10 || gap > 400
            Label(
                "Łącze z modułem skrętu (5 s): zgubione $loss% · najdłuższa przerwa $gap ms" + if (bad) " — za słabe do autosteru" else " — dobre",
                if (bad) Ss.Warning else Ss.Accent, 14.sp,
            )
        }
        if (hw.wifiMode) {
            Label(
                if (hw.wifiTargets.isEmpty()) "Szukam modułów… (rozgłoszenie)" else "Wysyłka bezpośrednio do: ${hw.wifiTargets.joinToString(", ")}",
                Ss.TextSoft, 13.sp,
            )
            Label(
                "Jak ustawić: router (np. mały „travel router”) z adresem w sieci płytki ${hw.subnetText}.x, płytka kablem do portu LAN, " +
                    "Wi-Fi 5 GHz z osobną nazwą, router blisko tabletu. Windows: Opcje zasilania → Ustawienia karty sieci bezprzewodowej → Maksymalna wydajność. " +
                    "Program sam wyłącza oszczędzanie energii Wi-Fi na Androidzie i wysyła ramki bezpośrednio do modułów (pewniej niż rozgłoszenia). " +
                    "Nadzór wyłączy autoster, gdy moduł nie odpowiada dłużej niż 1 s.",
                Ss.TextMuted, 12.sp,
            )
        }
    }
}

/** Odbiornik GPS podłączony kablem USB/COM (bez płytki AIO albo obok niej). */
@Composable
private fun SerialPanel(hw: HardwareController) {
    var ports by remember { mutableStateOf(hw.serialPorts()) }
    var pick by remember { mutableStateOf(hw.serialPort) }
    var baud by remember { mutableStateOf(hw.serialBaud) }
    val bauds = listOf(9600, 38400, 115200, 460800)
    val phoneGps = ports.firstOrNull()?.startsWith("Telefon") == true          // Android: telefon i Bluetooth zamiast COM
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle(if (phoneGps) "Odbiornik GPS: telefon, Bluetooth" else "Odbiornik GPS na USB / COM")
        Label(
            if (phoneGps) "Bez płytki AIO: „Telefon — GPS wbudowany” (2–5 m — oprysk, rozsiewanie, granice, hektary; nie do autosterowania) " +
                "albo sparowany odbiornik GNSS na Bluetooth (dokładność odbiornika, z NTRIP — RTK 2 cm). Poprawki NTRIP idą do odbiornika."
            else "Gdy odbiornik (np. F9P) jest podłączony kablem do komputera, a nie do płytki — także odbiornik na Bluetooth (port COM po sparowaniu). Poprawki NTRIP idą wtedy do tego portu.",
            Ss.TextMuted, 13.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(onClick = { ports = hw.serialPorts() }) { ButtonText("Odśwież porty", color = Ss.TextSoft) }
            if (ports.isEmpty()) Label("Brak portów COM", Ss.Warning, 13.sp)
            if (phoneGps) Label("Odbiornik Bluetooth najpierw sparuj w ustawieniach Bluetooth telefonu.", Ss.TextMuted, 12.sp)
        }
        ports.forEach { name ->
            val cur = name == pick
            Text(
                name,
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (cur) Ss.AccentSoft else Ss.SurfaceHigh)
                    .clickable { pick = name }.padding(horizontal = 12.dp, vertical = 10.dp),
                color = if (cur) Ss.Accent else Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (!phoneGps) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Prędkość", color = Ss.Text, fontSize = 15.sp)
            Segmented(bauds.map { it.toString() }, bauds.indexOf(baud).coerceAtLeast(0), { baud = bauds[it] })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = { if (pick.isNotBlank()) hw.setSerial(pick, baud) }) {
                ButtonText("Połącz", color = Ss.OnAccent, weight = FontWeight.Bold)
            }
            if (hw.serialPort.isNotBlank()) ActionButton(onClick = { hw.setSerial("", baud) }) { ButtonText("Rozłącz", color = Ss.TextSoft) }
            Label(hw.serialStatus, if (hw.serialStatus.startsWith("Odbieram")) Ss.Accent else Ss.TextSoft, 13.sp)
        }
    }
}

@Composable
private fun NetworkPanel(hw: HardwareController) {
    var text by remember { mutableStateOf(hw.subnetText) }
    var msg by remember { mutableStateOf<String?>(null) }
    val addrs = remember(hw.tick / 20) { localAddresses() }
    val s = hw.subnet
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Sieć płytki")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sieć modułów", color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            TextInput(
                text, { text = it }, title = "Sieć modułów (trzy pierwsze liczby adresu)", modifier = Modifier.width(150.dp),
                hint = "192.168.5", mode = KeypadMode.Ip, maxLength = 11, fontSize = 18.sp,
                filter = { v -> v.filter { it.isDigit() || it == '.' } },
            )
            Text(".x", color = Ss.TextMuted, fontSize = 18.sp)
            ActionButton(onClick = { msg = hw.setSubnet(text) ?: "Zapisano" }) { ButtonText("Zapisz") }
        }
        Label("Domyślnie 192.168.5 — tak jak w AgOpenGPS. Zmieniaj tylko, jeśli płytka była przestawiona w AgIO.", Ss.TextMuted, 13.sp)

        // karty sieciowe komputera
        val match = addrs.any { it.inSubnet(s[0], s[1], s[2]) }
        Text(
            if (match) "Komputer ma adres w sieci ${hw.subnetText}.x ✓" else "Komputer NIE ma adresu ${hw.subnetText}.x — ustaw go (samouczek, krok 2)",
            color = if (match) Ss.Accent else Ss.Warning, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        )
        addrs.forEach { a ->
            Label("• ${a.address}  (${a.name})${if (a.inSubnet(s[0], s[1], s[2])) "  ← sieć płytki" else ""}", if (a.inSubnet(s[0], s[1], s[2])) Ss.Accent else Ss.TextSoft, 13.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(background = Ss.AccentSoft, border = Ss.Accent, onClick = { msg = hw.scanModules() }) {
                ButtonText("Szukaj modułów", color = Ss.Accent, weight = FontWeight.Bold)
            }
            var confirm by remember { mutableStateOf(false) }
            ActionButton(
                background = if (confirm) Ss.Warning else Ss.SurfaceHigh,
                onClick = { if (confirm) { msg = hw.moveModulesToSubnet(); confirm = false } else confirm = true },
            ) {
                ButtonText(
                    if (confirm) "Na pewno? Dotknij jeszcze raz" else "Przestaw moduły na ${hw.subnetText}.x",
                    color = if (confirm) Ss.OnWarning else Ss.TextSoft,
                )
            }
        }
        (msg ?: hw.netMessage)?.let { Label(it, Ss.TextSoft, 13.sp) }
        if (hw.foundModules.isNotEmpty()) {
            hw.foundModules.forEach { m ->
                val same = m.subnet == s
                Label(
                    "${m.label}: ${m.ip}${if (same) "" else " — w innej sieci (${m.subnetText}.x)!"}",
                    if (same) Ss.Accent else Ss.Warning, 14.sp,
                )
            }
            val other = hw.foundModules.firstOrNull { it.subnet != s }
            if (other != null) {
                ActionButton(onClick = { text = other.subnetText; msg = hw.setSubnet(other.subnetText) ?: "Aplikacja ustawiona na ${other.subnetText}.x" }) {
                    ButtonText("Ustaw aplikację na sieć modułów (${other.subnetText}.x)")
                }
            }
        }
    }
}

@Composable
private fun ModulesStatus(hw: HardwareController) {
    val s = hw.session
    val now = hw.now()
    if (!hw.hardware) {
        Module("Odbiornik GPS RTK", "NMEA GGA + VTG", "Symulowany", Ss.Warning)
        Module("Moduł autosterowania", "PGN 254 → / ← PGN 253", "Nie podłączono", Ss.TextMuted)
    } else {
        val g = s.lastGga
        val gpsOk = s.gpsAlive(now)
        Module(
            "Odbiornik GPS RTK",
            if (g != null) "${g.satellites} sat. · HDOP ${g.hdop.pl(1)} · kurs ${s.headingSource.label} · ${s.speedKmh.pl(1)} km/h" else "Czekam na NMEA (GGA, VTG, PAOGI)",
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
    val nc = hw.ntripConfig
    Module(
        "Poprawki NTRIP",
        if (nc.enabled) "${nc.host}:${nc.port}/${nc.mount} → UDP ${nc.udpPort}" else "Ustawienia → NTRIP",
        if (hw.ntripReceiving) "${hw.ntripRate.pl(0)} B/s" else if (nc.enabled) "Brak" else "Wyłączone",
        if (hw.ntripReceiving) Ss.Accent else if (nc.enabled) Ss.Warning else Ss.TextMuted,
    )
    val w = hw.war
    Module(
        "WAR (Wirtualny Asystent Rolnika)",
        if (w.configured) "Pola z WAR: ${w.fields.size} · zlecenia nowe: ${w.newOrders.size} · w kolejce: ${w.queueSize}" else "Ustawienia → WAR (kod nawigacji z telefonu)",
        if (!w.configured) "Nie połączono" else w.status.take(28),
        if (!w.configured) Ss.TextMuted else if (w.codeActive == true && w.status == "Połączono z WAR") Ss.Accent else Ss.Warning,
    )
    if (hw.hardware) {
        Module("Moduł maszyny (sekcje)", "PGN 239 co 100 ms · włączone sekcje: ${s.sections.on.count { it }}", "Wysyłam", Ss.Accent)
    } else {
        Module("Moduł maszyny (sekcje)", "PGN 239", "Nie podłączono", Ss.TextMuted)
    }
}

@Composable
private fun LiveLog(hw: HardwareController) {
    if (!hw.hardware) {
        Label("Włącz tryb SPRZĘT, aby zobaczyć na żywo dane z płytki AIO.", Ss.TextSoft, 14.sp)
        return
    }
    Label("Odebrano ${hw.rxCount} · wysłano PGN 254: ${hw.txCount}", Ss.TextMuted, 13.sp)
    if (hw.log.isEmpty()) Label("Nic jeszcze nie przyszło. Przejdź samouczek po lewej zakładce.", Ss.Warning, 14.sp)
    hw.log.forEach { Text(it, color = Ss.TextSoft, fontSize = 12.sp, maxLines = 1) }
}

/** Samouczek łączenia: kroki z automatycznym sprawdzaniem (✓ / ✗). */
@Composable
private fun Tutorial(hw: HardwareController) {
    val s = hw.session
    val now = hw.now()
    val sub = hw.subnetText
    val addrs = remember(hw.tick / 20) { localAddresses() }
    val pcInSubnet = addrs.any { it.inSubnet(hw.subnet[0], hw.subnet[1], hw.subnet[2]) }
    Label("Przejdź kroki po kolei. Zielony znaczek pojawia się sam, gdy krok działa.", Ss.TextSoft, 13.sp)
    Step(
        1, "Płytka zasilona, kabel wpięty", if (hw.hardware) hw.receiving() else null,
        "Płytka AIO dostaje 12 V z ciągnika, kabel sieciowy idzie z płytki do komputera (albo do routera w kabinie, do którego jest podłączony komputer). Na płytce powinny migać diody portu sieciowego.",
    )
    Step(
        2, "Adres komputera: $sub.10", pcInSubnet,
        "Windows: Ustawienia → Sieć i Internet → Ethernet → „Przypisanie adresu IP” → Edytuj → Ręczne → IPv4 włączone. " +
            "Adres IP: $sub.10, długość prefiksu podsieci: 24 (maska 255.255.255.0). Bramę i DNS zostaw puste — internet dalej idzie przez Wi‑Fi/LTE. " +
            "Moduły mają adresy powyżej 100 (np. $sub.126), więc .10 się z nimi nie gryzie. Drugi komputer w tej samej kabinie: $sub.11.",
    )
    Step(
        3, "AgIO zamknięte, tryb SPRZĘT", if (hw.hardware) hw.linkError == null else false,
        "AgIO i AgOpenGPS muszą być wyłączone (zajmują ten sam port 9999). Potem u góry tego ekranu przełącz na SPRZĘT.",
    )
    Step(
        4, "Zapora Windows przepuszcza dane", if (hw.hardware && pcInSubnet) hw.receiving() else null,
        "Przy pierwszym uruchomieniu Windows pyta o dostęp do sieci — zaznacz OBIE: prywatne i publiczne (sieć z płytką jest zwykle „publiczna, niezidentyfikowana”). " +
            "Jeśli okna nie było: Zabezpieczenia Windows → Zapora i ochrona sieci → Zezwalaj aplikacji przez zaporę → Zmień ustawienia → zaznacz SmartSteer Navigator w obu kolumnach. " +
            "Gdy nadal nic nie przychodzi: „Szukaj modułów” — pokaże, w jakiej sieci jest płytka.",
    )
    Step(
        5, "GPS nadaje pozycję", if (hw.hardware) s.gpsAlive(now) else null,
        "W zakładce „Co przychodzi” powinny lecieć zdania \$GNGGA / \$GNVTG albo \$PAOGI. Antena musi widzieć niebo. " +
            "Uwaga: odbiornik podłączony kablem USB prosto do komputera (bez płytki) nie jest jeszcze obsługiwany.",
    )
    Step(
        6, "RTK FIX (dokładność 2 cm)", if (hw.hardware && s.gpsAlive(now)) s.fix == FixQuality.RTK_FIX else null,
        "Poprawki z radia/bazy idą prosto do odbiornika. Przez internet: Ustawienia → NTRIP (adres castera, punkt, login, hasło). FLOAT zwykle przechodzi w FIX po 1–3 minutach.",
    )
    Step(
        7, "Moduł skrętu odpowiada", if (hw.hardware) s.autosteerAlive(now) else null,
        "Na ekranie widać kąt kół z czujnika. Brak odpowiedzi przy działającym GPS: sprawdź zasilanie silnika/zaworu i ustawienia płytki (Ustawienia → Płytka, „Wyślij do płytki”).",
    )
    Step(
        8, "Test silnika na postoju", null,
        "Ustawienia → Moduł skrętu → „Włącz test”. Strzałka ▶ ma skręcić koła w prawo. Jeśli w lewo — Ustawienia → Płytka → „Odwróć kierunek silnika”. " +
            "Jeśli kąt na ekranie rośnie odwrotnie niż skręt — „Odwróć czujnik kąta kół”. Koła prosto → „Wyzeruj”.",
    )
    Step(
        9, "Wymiary ciągnika i maszyny", null,
        "Ustawienia → Ciągnik (rozstaw osi, antena, wysokość anteny) i Maszyna (szerokość, sekcje). Potem: ekran Pola → nowe pole, Mapa → A i B, autoster na wolnej prędkości.",
    )
}

@Composable
private fun Step(n: Int, title: String, ok: Boolean?, body: String) {
    val color = when (ok) { true -> Ss.Accent; false -> Ss.Warning; null -> Ss.TextMuted }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ss.Panel2).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(if (ok == true) Ss.Accent else Ss.SurfaceHigh), contentAlignment = Alignment.Center) {
            Text(if (ok == true) "✓" else n.toString(), color = if (ok == true) Ss.OnAccent else color, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (ok != true) Label(body, Ss.TextSoft, 13.sp)
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
