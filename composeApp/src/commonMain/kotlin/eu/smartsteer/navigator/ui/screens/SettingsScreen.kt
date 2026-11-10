package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.SteerEnable
import eu.smartsteer.navigator.core.settings.VehicleProfile
import eu.smartsteer.navigator.core.war.WarProtocol
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
import kotlin.math.abs
import kotlin.math.round

private enum class Tab(val label: String) {
    Vehicle("Ciągnik"), Tool("Maszyna"), Guidance("Prowadzenie"), Imu("IMU"), Module("Moduł skrętu"), Board("Płytka"), Ntrip("NTRIP"), War("WAR"), Profiles("Profile"),
}

/** Ustawienia sprzętu i prowadzenia, zapisywane w profilu pojazdu (jak w AgOpenGPS). */
@Composable
fun SettingsScreen(hw: HardwareController) {
    var tab by remember { mutableStateOf(Tab.Vehicle) }
    hw.tick
    val p = hw.profile
    val set: (VehicleProfile) -> Unit = { hw.updateProfile(it) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(Tab.entries.map { it.label }, tab.ordinal, { tab = Tab.entries[it] })
            Box(Modifier.weight(1f))
            Pill("Profil: ${hw.profileName}", Ss.Accent, Ss.AccentSoft)
        }
        hw.profileError?.let { Label(it, Ss.Warning, 14.sp) }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    Tab.Vehicle -> {
                        Choice("Dźwięki (autoster, alarmy, zawrót, sekcje)", listOf("WŁ.", "WYŁ."), if (hw.soundOn) 0 else 1) { hw.setSound(it == 0) }
                        VehicleTab(p, set)
                    }
                    Tab.Tool -> ToolTab(p, set)
                    Tab.Guidance -> GuidanceTab(p, set)
                    Tab.Imu -> ImuTab(hw, p, set)
                    Tab.Module -> ModuleTab(hw, p, set)
                    Tab.Board -> BoardTab(hw, p, set)
                    Tab.Ntrip -> NtripTab(hw)
                    Tab.War -> WarTab(hw)
                    Tab.Profiles -> ProfilesTab(hw)
                }
            }
            LivePanel(hw)
        }
    }
}

// ---------------- zakładki ----------------

@Composable
private fun ColumnScope.VehicleTab(p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    SectionTitle("Wymiary ciągnika")
    Num("Rozstaw osi", "Od środka tylnej osi do środka przedniej", p.wheelbase, "m", 0.05, 1.0, 8.0, 2) { set(p.copy(wheelbase = it)) }
    Num("Rozstaw kół", "Szerokość śladu kół", p.trackWidth, "m", 0.05, 0.5, 4.0, 2) { set(p.copy(trackWidth = it)) }
    SectionTitle("Antena GPS")
    Num("Antena przed tylną osią", "Ujemna wartość = antena za osią", p.antennaPivot, "m", 0.05, -5.0, 5.0, 2) { set(p.copy(antennaPivot = it)) }
    Num(
        "Antena w bok", "Od środka ciągnika", p.antennaOffset, "m", 0.01, -2.0, 2.0, 2,
        fmt = { v -> if (abs(v) < 0.005) "na środku" else "${abs(v).pl(2)} m ${if (v > 0) "w lewo" else "w prawo"}" },
        minusLabel = "◀", plusLabel = "▶", invert = true,
    ) { set(p.copy(antennaOffset = it)) }
    Num("Wysokość anteny", "Nad ziemią (do kompensacji przechyłu)", p.antennaHeight, "m", 0.05, 0.0, 6.0, 2) { set(p.copy(antennaHeight = it)) }
    SectionTitle("Ograniczenia autosteru")
    Num("Maksymalny skręt kół", null, p.maxSteerAngle, "°", 1.0, 10.0, 90.0, 0) { set(p.copy(maxSteerAngle = it)) }
    Num("Wyłącz powyżej", "Autoster wyłączy się przy większej prędkości", p.maxSteerSpeed, "km/h", 1.0, 1.0, 40.0, 0) { set(p.copy(maxSteerSpeed = it)) }
    Num("Wyłącz poniżej", "Po 8 s jazdy wolniej; 0 = nie wyłączaj", p.minSteerSpeed, "km/h", 0.5, 0.0, 10.0, 1) { set(p.copy(minSteerSpeed = it)) }
}

@Composable
private fun ColumnScope.ToolTab(p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    SectionTitle("Maszyna")
    Num("Szerokość robocza", null, p.toolWidth, "m", 0.1, 0.5, 60.0, 2) { set(p.copy(toolWidth = it)) }
    Num("Zakładka", "Ile sąsiednie przejazdy na siebie zachodzą; ujemna = przerwa", p.toolOverlap, "m", 0.05, -2.0, 2.0, 2) { set(p.copy(toolOverlap = it)) }
    Label("Odstęp między przejazdami: ${p.passSpacing.pl(2)} m", Ss.TextSoft, 15.sp)
    SectionTitle("Zaczep")
    Choice("Rodzaj maszyny", listOf("Zawieszana", "Ciągniona"), if (p.toolTrailing) 1 else 0) { set(p.copy(toolTrailing = it == 1)) }
    Num(
        "Zaczep od tylnej osi", "Ile za osią (albo przed, dla maszyny z przodu)", p.hitchLength, "m", 0.1, -15.0, 10.0, 1,
        fmt = { v -> if (abs(v) < 0.05) "na osi" else "${abs(v).pl(1)} m ${if (v < 0) "za" else "przed"}" },
    ) { set(p.copy(hitchLength = it)) }
    if (p.toolTrailing) {
        Num(
            "Od zaczepu do osi maszyny", "Długość dyszla", -p.trailingHitchLength, "m", 0.1, 0.0, 20.0, 1,
        ) { set(p.copy(trailingHitchLength = -it)) }
    }
    SectionTitle("Sekcje")
    Num("Liczba sekcji", "Równe szerokości: ${(p.toolWidth / p.numSections).pl(2)} m każda", p.numSections.toDouble(), "", 1.0, 1.0, 16.0, 0) {
        set(p.copy(numSections = it.toInt()))
    }
    Num("Włączanie z wyprzedzeniem", "Czas otwarcia zaworu / opadania", p.lookAheadOn, "s", 0.1, 0.0, 5.0, 1) { set(p.copy(lookAheadOn = it)) }
    Num("Wyłączanie z wyprzedzeniem", "Czas zamknięcia zaworu", p.lookAheadOff, "s", 0.1, 0.0, 5.0, 1) { set(p.copy(lookAheadOff = it)) }
    Num("Sekcje stoją poniżej", null, p.slowSpeedCutoff, "km/h", 0.1, 0.0, 5.0, 1) { set(p.copy(slowSpeedCutoff = it)) }
    Toggle("Wyłączaj poza granicą pola", p.sectionOffWhenOut) { set(p.copy(sectionOffWhenOut = it)) }
    Toggle("Wyłączaj na uwrociu", p.sectionOffInHeadland) { set(p.copy(sectionOffInHeadland = it)) }
    SectionTitle("Przełącznik roboczy")
    Toggle("Sekcje tylko przy włączonym przełączniku", p.useWorkSwitch) { set(p.copy(useWorkSwitch = it)) }
    if (p.useWorkSwitch) {
        Choice("Włączony, gdy styk", listOf("Zwarty (niski)", "Rozwarty (wysoki)"), if (p.workSwitchActiveLow) 0 else 1) { set(p.copy(workSwitchActiveLow = it == 0)) }
    }
}

@Composable
private fun ColumnScope.GuidanceTab(p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    SectionTitle("Auto-zawrót")
    Num("Promień zawrotu", "Najciaśniejszy łuk, jaki ciągnik z maszyną spokojnie przejedzie", p.youTurnRadius, "m", 0.5, 3.0, 30.0, 1) { set(p.copy(youTurnRadius = it)) }
    Num("Zawracaj na przejazd", "1 = sąsiedni, 2 = co drugi…", p.youTurnSkip.toDouble(), "", 1.0, 1.0, 10.0, 0) { set(p.copy(youTurnSkip = it.toInt())) }
    Num("Odstęp od granicy", "Zawrót nie podejdzie bliżej granicy pola", p.youTurnFromBoundary, "m", 0.5, 0.0, 20.0, 1) { set(p.copy(youTurnFromBoundary = it)) }
    Label(
        if (p.youTurnSkip * p.passSpacing >= 2 * p.youTurnRadius) "Kształt: szeroki zawrót „U”." else "Kształt: „omega” (odstęp mniejszy niż 2 × promień) — potrzebuje więcej miejsca na uwrociu.",
        Ss.TextMuted, 13.sp,
    )
    SectionTitle("Algorytm prowadzenia")
    Segmented(SteerAlgorithm.entries.map { it.label }, p.algorithm.ordinal, { set(p.copy(algorithm = SteerAlgorithm.entries[it])) })
    if (p.algorithm == SteerAlgorithm.PURE_PURSUIT) {
        Label("Pure pursuit celuje w punkt przed ciągnikiem. Krótsze wyprzedzenie = ostrzej, dłuższe = łagodniej.", Ss.TextSoft, 13.sp)
        Num("Wyprzedzenie przy trzymaniu linii", null, p.lookAheadHold, "m", 0.1, 1.0, 10.0, 1) { set(p.copy(lookAheadHold = it)) }
        Num("Wpływ prędkości na wyprzedzenie", null, p.lookAheadMult, "×", 0.1, 0.5, 5.0, 1) { set(p.copy(lookAheadMult = it)) }
        Num("Wyprzedzenie przy dojeżdżaniu", "Mnożnik, gdy ciągnik jest dalej niż 40 cm od linii", p.acquireFactor, "×", 0.05, 0.5, 1.5, 2) { set(p.copy(acquireFactor = it)) }
        Num("Człon całkujący", "Usuwa stałą odchyłkę na zboczu; 0 = wyłączony", p.purePursuitIntegral, "", 0.05, 0.0, 1.0, 2) { set(p.copy(purePursuitIntegral = it)) }
    } else {
        Label("Stanley prowadzi przednią oś po linii; dobry przy wolnej jeździe i precyzyjnych pracach.", Ss.TextSoft, 13.sp)
        Num("Wzmocnienie odchyłki", null, p.stanleyDistanceGain, "×", 0.1, 0.1, 4.0, 1) { set(p.copy(stanleyDistanceGain = it)) }
        Num("Wzmocnienie kursu", null, p.stanleyHeadingGain, "×", 0.1, 0.1, 1.5, 1) { set(p.copy(stanleyHeadingGain = it)) }
        Num("Człon całkujący", "0 = wyłączony", p.stanleyIntegral, "", 0.05, 0.0, 1.0, 2) { set(p.copy(stanleyIntegral = it)) }
    }
}

@Composable
private fun ColumnScope.ImuTab(hw: HardwareController, p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    var zeroMsg by remember { mutableStateOf<String?>(null) }
    val imu = hw.session.imu
    val now = hw.now()
    SectionTitle("Żyroskop (IMU)")
    Label(
        if (imu.rollAlive(now) || imu.headingAlive(now)) {
            "IMU działa: " + listOfNotNull(
                imu.rollDeg?.takeIf { imu.rollAlive(now) }?.let { "przechył ${it.pl(1, plusSign = true)}°" },
                imu.headingDeg?.takeIf { imu.headingAlive(now) }?.let { "kurs ${it.pl(1)}°" },
            ).joinToString(" · ")
        } else {
            "Brak danych z IMU. Odczyt przychodzi z modułu skrętu (PGN 253), zdań PANDA/PAOGI albo osobnego IMU."
        },
        if (imu.rollAlive(now)) Ss.Accent else Ss.Warning, 14.sp,
    )
    SectionTitle("Przechył")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(background = Ss.AccentSoft, border = Ss.Accent, onClick = { zeroMsg = hw.zeroRoll() ?: "Zero przechyłu ustawione" }) {
            ButtonText("Wyzeruj przechył — na płaskim", color = Ss.Accent, weight = FontWeight.Bold)
        }
        zeroMsg?.let { Label(it, Ss.TextSoft, 14.sp) }
    }
    Num("Zero przechyłu", "Odczyt na płaskim terenie", p.rollZero, "°", 0.1, -10.0, 10.0, 1) { set(p.copy(rollZero = it)) }
    Toggle("Odwróć przechył", p.invertRoll) { set(p.copy(invertRoll = it)) }
    Label("Sprawdź: prawe koła niżej (np. w bruździe) = przechył dodatni. Jeśli jest ujemny, włącz odwrócenie.", Ss.TextMuted, 13.sp)
    Num("Filtr przechyłu", "Większy = spokojniej, ale z opóźnieniem", p.rollFilter, "", 0.05, 0.0, 0.95, 2) { set(p.copy(rollFilter = it)) }
    Label("Wysokość anteny (zakładka Ciągnik): ${p.antennaHeight.pl(2)} m — od niej zależy poprawka pozycji na zboczu.", Ss.TextSoft, 13.sp)
    SectionTitle("Kurs")
    Num("Korekta kursu IMU przez GPS", "Fuzja; 0 = kurs tylko z GPS", p.fusionWeight, "", 0.01, 0.0, 0.5, 2) { set(p.copy(fusionWeight = it)) }
    Num("Kompensacja zbocza", "Stopnie skrętu na stopień przechyłu", p.sideHillComp, "", 0.01, 0.0, 1.0, 2) { set(p.copy(sideHillComp = it)) }
}

@Composable
private fun ColumnScope.ModuleTab(hw: HardwareController, p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    var zeroMsg by remember { mutableStateOf<String?>(null) }
    val s = hw.session
    // test silnika wyłącza się, gdy wychodzimy z tej zakładki
    DisposableEffect(Unit) { onDispose { if (s.freeDrive) s.setFreeDrive(false) } }
    SectionTitle("Test silnika (bez jazdy)")
    Label("Koła kręcą się przyciskami — sprawdzisz kierunek silnika, czujnik kąta i wzmocnienie. Wyłącza się samo, gdy ciągnik ruszy.", Ss.TextSoft, 13.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            background = if (s.freeDrive) Ss.Warning else Ss.SurfaceHigh, border = if (s.freeDrive) Ss.Warning else Ss.BorderStrong,
            onClick = { s.setFreeDrive(!s.freeDrive); hw.touch() },
        ) { ButtonText(if (s.freeDrive) "Test WŁĄCZONY" else "Włącz test", color = if (s.freeDrive) Ss.OnWarning else Ss.Text, weight = FontWeight.Bold) }
        if (s.freeDrive) {
            ActionButton(Modifier.width(64.dp), onClick = { s.nudgeFreeDrive(-5.0); hw.touch() }) { ButtonText("◀◀", size = 16.sp) }
            ActionButton(Modifier.width(56.dp), onClick = { s.nudgeFreeDrive(-1.0); hw.touch() }) { ButtonText("◀", size = 16.sp) }
            ActionButton(onClick = { s.centerFreeDrive(); hw.touch() }) { ButtonText("${s.freeDriveAngle.pl(0, plusSign = true)}° · prosto") }
            ActionButton(Modifier.width(56.dp), onClick = { s.nudgeFreeDrive(1.0); hw.touch() }) { ButtonText("▶", size = 16.sp) }
            ActionButton(Modifier.width(64.dp), onClick = { s.nudgeFreeDrive(5.0); hw.touch() }) { ButtonText("▶▶", size = 16.sp) }
        }
    }
    SectionTitle("Czujnik kąta kół")
    Num("Impulsy na stopień", "Ustaw tak, żeby kąt na ekranie zgadzał się z kołami", p.countsPerDegree.toDouble(), "", 1.0, 1.0, 255.0, 0) { set(p.copy(countsPerDegree = it.toInt())) }
    Num("Przesunięcie zera", null, p.wasOffset.toDouble(), "", 5.0, -4000.0, 4000.0, 0) { set(p.copy(wasOffset = it.toInt())) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(background = Ss.AccentSoft, border = Ss.Accent, onClick = { zeroMsg = hw.zeroWas() ?: "Zero ustawione" }) {
            ButtonText("Wyzeruj — koła prosto", color = Ss.Accent, weight = FontWeight.Bold)
        }
        zeroMsg?.let { Label(it, Ss.TextSoft, 14.sp) }
    }
    Num("Ackermann", "Korekta różnicy skrętu w lewo i w prawo", p.ackerman.toDouble(), "%", 1.0, 1.0, 200.0, 0) { set(p.copy(ackerman = it.toInt())) }
    SectionTitle("Silnik / zawór")
    Num("Wzmocnienie P", "Jak mocno silnik reaguje na różnicę kąta", p.gainP.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(gainP = it.toInt())) }
    Num("Maksymalny PWM", null, p.highPwm.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(highPwm = it.toInt())) }
    Num("Niski PWM", "Moc przy małych korektach", p.lowPwm.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(lowPwm = it.toInt())) }
    Num("Minimalny PWM", "Najmniejsza moc, przy której koła ruszają", p.minPwm.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(minPwm = it.toInt())) }
}

@Composable
private fun ColumnScope.BoardTab(hw: HardwareController, p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    SectionTitle("Kierunki")
    Toggle("Odwróć czujnik kąta kół", p.invertWas) { set(p.copy(invertWas = it)) }
    Toggle("Odwróć kierunek silnika", p.invertMotor) { set(p.copy(invertMotor = it)) }
    Toggle("Odwróć przekaźniki", p.invertRelays) { set(p.copy(invertRelays = it)) }
    SectionTitle("Elektronika")
    Choice("Czujnik kąta kół", listOf("Pojedynczy", "Różnicowy"), if (p.singleInput) 0 else 1) { set(p.copy(singleInput = it == 0)) }
    Choice("Sterownik silnika", listOf("Cytron", "IBT2"), if (p.cytron) 0 else 1) { set(p.copy(cytron = it == 0)) }
    Toggle("Zawór Danfoss", p.danfoss) { set(p.copy(danfoss = it)) }
    Choice("Oś czujnika IMU na płytce", listOf("X", "Y"), if (p.axisY) 1 else 0) { set(p.copy(axisY = it == 1)) }
    SectionTitle("Włączanie i wyłączanie autosteru")
    Choice("Włącznik na płytce", SteerEnable.entries.map { it.label }, p.steerEnable.ordinal) { set(p.copy(steerEnable = SteerEnable.entries[it])) }
    val sensor = when { p.encoder -> 1; p.pressureSensor -> 2; p.currentSensor -> 3; else -> 0 }
    Choice("Wyłączanie ruchem kierownicy", listOf("Brak", "Enkoder", "Ciśnienie", "Prąd"), sensor) {
        set(p.copy(encoder = it == 1, pressureSensor = it == 2, currentSensor = it == 3))
    }
    if (sensor != 0) {
        Num(
            if (sensor == 1) "Próg enkodera" else "Próg czujnika", if (sensor == 1) "Liczba impulsów" else "Wartość 0–255",
            p.maxPulseCounts.toDouble(), "", 1.0, 0.0, 255.0, 0,
        ) { set(p.copy(maxPulseCounts = it.toInt())) }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = { hw.sendBoardConfig() }) {
            ButtonText("Wyślij do płytki", color = Ss.OnAccent, weight = FontWeight.Bold)
        }
        Label(
            when {
                !hw.hardware -> "Wysyłka działa w trybie SPRZĘT (ekran Połączenia)"
                hw.configSentAt != null -> "Wysłano ${(hw.now() - hw.configSentAt!!).pl(0)} s temu"
                else -> "Jeszcze nie wysłano"
            },
            Ss.TextSoft, 14.sp,
        )
    }
    Label("Zmiany wysyłają się też same, 1,5 s po ostatnim dotknięciu.", Ss.TextMuted, 13.sp)
}

@Composable
private fun ColumnScope.NtripTab(hw: HardwareController) {
    val c = hw.ntripConfig
    var host by remember { mutableStateOf(c.host) }
    var port by remember { mutableStateOf(c.port.toString()) }
    var mount by remember { mutableStateOf(c.mount) }
    var user by remember { mutableStateOf(c.user) }
    var pass by remember { mutableStateOf(c.password) }
    SectionTitle("Poprawki RTK przez internet (NTRIP)")
    Label(
        "Tablet/komputer pobiera poprawki z castera (np. ASG-EUPOS, własna stacja) i wysyła je do płytki AIO (UDP ${c.udpPort}), " +
            "tak jak robi to AgIO. Potrzebny internet w tablecie i tryb SPRZĘT.",
        Ss.TextSoft, 13.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill(
            hw.ntripStatus,
            if (hw.ntripReceiving) Ss.Accent else if (c.enabled) Ss.Warning else Ss.TextMuted,
            if (hw.ntripReceiving) Ss.AccentSoft else Ss.SurfaceHigh,
        )
        if (hw.ntripReceiving) Label("${hw.ntripRate.pl(0)} B/s · razem ${(hw.ntripBytes / 1024.0).pl(0)} kB", Ss.TextSoft, 14.sp)
    }
    Field("Adres castera", host, "np. system.asgeupos.pl") { host = it }
    Field("Port", port, "2101", number = true) { port = it.filter { ch -> ch.isDigit() }.take(5) }
    Field("Punkt montowania (mountpoint)", mount, "np. NAWGEO_POJ_3_1") { mount = it }
    Field("Login", user, "") { user = it }
    Field("Hasło", pass, "", password = true) { pass = it }
    Num("Wysyłaj pozycję do castera co", "Potrzebne dla sieci VRS; 0 = nie wysyłaj", c.ggaIntervalS.toDouble(), "s", 1.0, 0.0, 60.0, 0) {
        hw.updateNtrip(hw.ntripConfig.copy(ggaIntervalS = it.toInt()))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = {
            hw.updateNtrip(
                hw.ntripConfig.copy(
                    enabled = true, host = host.trim(), port = port.toIntOrNull() ?: 2101,
                    mount = mount.trim(), user = user.trim(), password = pass,
                ),
            )
        }) { ButtonText("Zapisz i połącz", color = Ss.OnAccent, weight = FontWeight.Bold) }
        if (c.enabled) {
            ActionButton(onClick = { hw.updateNtrip(hw.ntripConfig.copy(enabled = false)) }) { ButtonText("Rozłącz", color = Ss.TextSoft) }
        }
    }
    Label("Hasło zapisuje się w pliku ustawień na tym urządzeniu (tak jak w AgIO).", Ss.TextMuted, 12.sp)
}

@Composable
private fun ColumnScope.WarTab(hw: HardwareController) {
    val w = hw.war
    val c = w.config
    var code by remember { mutableStateOf(if (c.code.isEmpty()) "" else WarProtocol.pretty(c.code)) }
    var tractor by remember { mutableStateOf(c.tractorName) }
    var tool by remember { mutableStateOf(c.toolName) }
    var who by remember { mutableStateOf(c.operator) }
    var server by remember { mutableStateOf(c.server) }
    var msg by remember { mutableStateOf<String?>(null) }
    SectionTitle("Połączenie z WAR (Wirtualny Asystent Rolnika)")
    Label(
        "Pola i zlecenia przychodzą z telefonu, a praca w polu i pozycja na żywo wracają do WAR. " +
            "Kod nawigacji: w telefonie WAR → Gospodarstwo → Maszyny → WAR Sync. Wszystko jest szyfrowane kodem.",
        Ss.TextSoft, 13.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val ok = w.codeActive == true && !w.status.startsWith("Brak") && !w.status.startsWith("Czekam")
        Pill(w.status, if (ok) Ss.Accent else if (w.configured) Ss.Warning else Ss.TextMuted, if (ok) Ss.AccentSoft else Ss.SurfaceHigh)
        if (w.queueSize > 0) Label("Czeka na wysłanie: ${w.queueSize}", Ss.Warning, 13.sp)
        w.lastSentText?.let { Label("Ostatnio wysłano: $it", Ss.TextMuted, 13.sp) }
    }
    Field("Kod nawigacji", code, "ABCD-EFGH-JKLM-NPQR") { code = it.uppercase().take(19) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = {
            msg = w.setCode(code) ?: "Kod zapisany — sprawdzam w WAR…"
            if (WarProtocol.isValidCode(WarProtocol.normalizeCode(code))) code = WarProtocol.pretty(WarProtocol.normalizeCode(code))
        }) { ButtonText("Zapisz i sprawdź kod", color = Ss.OnAccent, weight = FontWeight.Bold) }
    }
    msg?.let { Label(it, Ss.TextSoft, 14.sp) }
    SectionTitle("Jak ta maszyna ma się nazywać w WAR")
    Field("Nazwa ciągnika", tractor, "np. Renault 133.14") { tractor = it.take(40) }
    Field("Nazwa narzędzia (stała)", tool, hw.profileName) { tool = it.take(40) }
    Label("WAR przypisuje rodzaj zabiegu do nazwy narzędzia (np. „Siewnik 3 m” = siew) — nie zmieniaj jej co dzień.", Ss.TextMuted, 12.sp)
    Field("Kto jedzie", who, "np. Marek") { who = it.take(40) }
    Toggle("Pokazuj na mapie WAR (pozycja na żywo)", c.live) { w.update(w.config.copy(live = it)) }
    Field("Serwer", server, "https://www.agro.smartsteer.eu") { server = it.trim() }
    ActionButton(onClick = {
        w.update(w.config.copy(tractorName = tractor.trim(), toolName = tool.trim(), operator = who.trim(), server = server.ifEmpty { "https://www.agro.smartsteer.eu" }))
        msg = "Zapisano"
    }) { ButtonText("Zapisz nazwy") }
    Label("Id tej maszyny: ${c.machineId}", Ss.TextMuted, 12.sp)
}

@Composable
private fun Field(label: String, value: String, hint: String, password: Boolean = false, number: Boolean = false, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ss.Surface)
            .border(1.dp, Ss.Border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, Modifier.width(240.dp), color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Ss.Text, fontSize = 17.sp),
            cursorBrush = SolidColor(Ss.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else if (password) KeyboardType.Password else KeyboardType.Text),
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Ss.Ground)
                .border(1.dp, Ss.BorderStrong, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = Ss.TextMuted, fontSize = 17.sp)
                inner()
            },
        )
    }
}

@Composable
private fun ColumnScope.ProfilesTab(hw: HardwareController) {
    var newName by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    SectionTitle("Profile pojazdów")
    Label("Każdy ciągnik może mieć własny profil. Zmiany zapisują się od razu w bieżącym profilu.", Ss.TextSoft, 14.sp)
    hw.profileNames.forEach { name ->
        val current = name == hw.profileName
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (current) Ss.AccentSoft else Ss.Surface)
                .border(1.dp, if (current) Ss.Accent else Ss.Border, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(name, Modifier.weight(1f), color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (current) {
                Pill("W UŻYCIU", Ss.Accent, Ss.AccentSoft)
            } else {
                ActionButton(onClick = { hw.loadProfile(name); msg = "Wczytano „$name”" }) { ButtonText("Wczytaj") }
                ActionButton(onClick = { hw.deleteProfile(name); msg = "Usunięto „$name”" }) { ButtonText("Usuń", color = Ss.Warning) }
            }
        }
    }
    SectionTitle("Zapisz jako nowy profil")
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
                if (newName.isEmpty()) Text("np. Fendt 724 + siewnik 6 m", color = Ss.TextMuted, fontSize = 18.sp)
                inner()
            },
        )
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = {
            val err = hw.saveProfileAs(newName)
            msg = err ?: "Zapisano „${hw.profileName}”"
            if (err == null) newName = ""
        }) { ButtonText("Zapisz", color = Ss.OnAccent, weight = FontWeight.Bold) }
    }
    msg?.let { Label(it, Ss.TextSoft, 14.sp) }
    Label("Pliki profili: ${hw.profilesLocation}", Ss.TextMuted, 12.sp)
}

// ---------------- podgląd na żywo ----------------

@Composable
private fun LivePanel(hw: HardwareController) {
    val s = hw.session
    val now = hw.now()
    val st = s.autosteer?.takeIf { s.autosteerAlive(now) }
    val imu = s.imu
    Panel(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
        SectionTitle("Na żywo")
        if (!hw.hardware) {
            Label("Włącz tryb SPRZĘT na ekranie Połączenia, żeby widzieć odczyty z płytki.", Ss.TextSoft, 14.sp)
        }
        Row {
            Box(Modifier.weight(1f)) { Stat("Kąt zadany", "${(if (s.freeDrive) s.freeDriveAngle else s.steerSetDeg).pl(1, plusSign = true)}°", valueSize = 20.sp) }
            Box(Modifier.weight(1f)) { Stat("Kąt kół", st?.let { "${it.actualAngleDeg.pl(1, plusSign = true)}°" } ?: "—", valueSize = 20.sp) }
        }
        Chart(hw.histSet, hw.histActual, minRange = 5f, unit = "°")
        Label("Zielona — zadany, biała — kąt kół (30 s)", Ss.TextMuted, 12.sp)
        Row {
            Box(Modifier.weight(1f)) { Stat("Odchyłka", if (hw.histXte.lastOrNull()?.isNaN() == false) "${s.xteCm.pl(1, plusSign = true)} cm" else "—", valueSize = 20.sp) }
            Box(Modifier.weight(1f)) { Stat("PWM", st?.pwm?.toString() ?: "—", valueSize = 20.sp) }
        }
        Chart(hw.histXte, null, minRange = 10f, unit = "cm")
        Stat("Przechył", imu.rollDeg?.takeIf { imu.rollAlive(now) }?.let { "${it.pl(1, plusSign = true)}° · poprawka ${(s.rollCorrectionM * 100).pl(0, plusSign = true)} cm" } ?: "brak IMU", valueSize = 16.sp)
        Row {
            Box(Modifier.weight(1f)) { Stat("Całka", "${(s.integral * 100).pl(1, plusSign = true)} cm", valueSize = 16.sp) }
            Box(Modifier.weight(1f)) { Stat("Przełącznik", st?.let { if (it.steerSwitch) "WŁ." else "WYŁ." } ?: "—", valueSize = 16.sp) }
        }
    }
}

/** Mały wykres przebiegu (środek = 0, skala dopasowuje się sama). */
@Composable
private fun Chart(a: ArrayDeque<Float>, b: ArrayDeque<Float>?, minRange: Float, unit: String) {
    val va = a.toList()
    val vb = b?.toList()
    var range = minRange
    for (v in va) if (!v.isNaN()) range = maxOf(range, kotlin.math.abs(v))
    vb?.forEach { if (!it.isNaN()) range = maxOf(range, kotlin.math.abs(it)) }
    Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(10.dp)).background(Ss.Ground)) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height
            val w = size.width
            val n = HardwareController.HISTORY
            fun y(v: Float) = h / 2 - v / range * (h / 2 - 6f)
            drawLine(Ss.Border, Offset(0f, h / 2), Offset(w, h / 2), 1.5f)
            fun series(list: List<Float>, color: Color, width: Float) {
                val path = Path()
                var started = false
                val start = n - list.size
                list.forEachIndexed { i, v ->
                    if (v.isNaN()) { started = false; return@forEachIndexed }
                    val x = (start + i) * w / (n - 1)
                    if (!started) { path.moveTo(x, y(v)); started = true } else path.lineTo(x, y(v))
                }
                drawPath(path, color, style = Stroke(width = width))
            }
            vb?.let { series(it, Ss.Text, 2f) }
            series(va, if (vb == null) Ss.Warning else Ss.Accent, 2.5f)
        }
        Text("±${range.toDouble().pl(0)} $unit", Modifier.align(Alignment.TopEnd).padding(4.dp), color = Ss.TextMuted, fontSize = 11.sp)
    }
}

// ---------------- elementy ----------------

/** Wiersz z liczbą i dużymi przyciskami − / + (bez klawiatury — wygodne w rękawicy). */
@Composable
private fun Num(
    label: String,
    hint: String?,
    value: Double,
    unit: String,
    step: Double,
    min: Double,
    max: Double,
    decimals: Int,
    fmt: ((Double) -> String)? = null,
    minusLabel: String? = null,
    plusLabel: String? = null,
    invert: Boolean = false,
    onChange: (Double) -> Unit,
) {
    fun change(dir: Int) {
        val k = if (invert) -dir else dir
        val v = round((value + k * step) / step) * step
        onChange(v.coerceIn(min, max))
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ss.Surface)
            .border(1.dp, Ss.Border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (hint != null) Label(hint, Ss.TextMuted, 12.sp)
        }
        ActionButton(Modifier.width(56.dp), onClick = { change(-1) }) {
            if (minusLabel != null) ButtonText(minusLabel, size = 18.sp) else NavIcon(IconKind.Minus, Ss.Text, 20.dp)
        }
        Text(
            fmt?.invoke(value) ?: (value.pl(decimals) + if (unit.isNotEmpty()) " $unit" else ""),
            Modifier.width(130.dp),
            color = Ss.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        )
        ActionButton(Modifier.width(56.dp), onClick = { change(1) }) {
            if (plusLabel != null) ButtonText(plusLabel, size = 18.sp) else NavIcon(IconKind.Plus, Ss.Text, 20.dp)
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) =
    Choice(label, listOf("NIE", "TAK"), if (value) 1 else 0) { onChange(it == 1) }

@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ss.Surface)
            .border(1.dp, Ss.Border, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Segmented(options, selected, onSelect)
    }
}
