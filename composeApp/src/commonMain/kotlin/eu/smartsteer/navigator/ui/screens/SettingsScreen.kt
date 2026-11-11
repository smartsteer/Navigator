package eu.smartsteer.navigator.ui.screens

import androidx.compose.foundation.Canvas
import eu.smartsteer.navigator.core.field.SectionMode
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import eu.smartsteer.navigator.core.ntrip.Ntrip
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
import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.SteerEnable
import eu.smartsteer.navigator.core.settings.VehicleProfile
import eu.smartsteer.navigator.core.war.WarProtocol
import eu.smartsteer.navigator.ui.HardwareController
import eu.smartsteer.navigator.core.hw.AutoTune
import eu.smartsteer.navigator.ui.DisplayPrefs
import eu.smartsteer.navigator.ui.components.ActionButton
import eu.smartsteer.navigator.ui.components.ButtonText
import eu.smartsteer.navigator.ui.components.IconKind
import eu.smartsteer.navigator.ui.components.Keypad
import eu.smartsteer.navigator.ui.components.KeypadMode
import eu.smartsteer.navigator.ui.components.KeypadRequest
import eu.smartsteer.navigator.ui.components.TextInput
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
    Vehicle("Ciągnik"), Tool("Maszyna"), Guidance("Prowadzenie"), Imu("IMU"), Module("Moduł skrętu"), Supervisor("Nadzór"), Board("Płytka"), Ntrip("NTRIP"), War("WAR"), Profiles("Profile"),
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
                        Choice("Ekran", listOf("Zwykły", "Tablet (powiększony)"), if (DisplayPrefs.tablet) 1 else 0) { DisplayPrefs.updateTablet(it == 1) }
                        Choice("Kolory", listOf("Noc (ciemne)", "Dzień (jasne)"), if (DisplayPrefs.day) 1 else 0) { DisplayPrefs.updateDay(it == 1) }
                        if (DisplayPrefs.fullScreenSupported) Toggle("Pełny ekran (F11)", DisplayPrefs.fullScreen) { DisplayPrefs.updateFullScreen(it) }
                        Choice("Podkład mapy", eu.smartsteer.navigator.core.map.TileSource.entries.map { it.short }, DisplayPrefs.background) { DisplayPrefs.updateBackground(it) }
                        Label("ORTO = ortofotomapa Polski z Geoportalu, SAT = zdjęcia satelitarne Esri. Raz obejrzane kafelki zostają na dysku; całe pole pobierzesz na ekranie Pola.", Ss.TextMuted, 12.sp)
                        PowerSettings(hw)
                        VehicleTab(p, set)
                    }
                    Tab.Tool -> ToolTab(p, set)
                    Tab.Guidance -> { AutoTunePanel(hw); GuidanceTab(p, set) }
                    Tab.Imu -> ImuTab(hw, p, set)
                    Tab.Module -> ModuleTab(hw, p, set)
                    Tab.Supervisor -> SupervisorTab(hw, p, set)
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
    Num("Szerokość robocza", "Dotknij liczby, żeby wpisać dokładnie (np. 2,95)", p.toolWidth, "m", 0.05, 0.3, 60.0, 2) {
        // przy własnych szerokościach sekcji zmiana szerokości wraca do równych sekcji
        set(p.copy(toolWidth = it, sectionWidths = emptyList()))
    }
    Num(
        "Przesunięcie maszyny w bok", "Względem środka ciągnika", p.toolOffset, "m", 0.01, -10.0, 10.0, 2,
        fmt = { v -> if (abs(v) < 0.005) "na środku" else "${abs(v).pl(2)} m ${if (v > 0) "w prawo" else "w lewo"}" },
        minusLabel = "◀", plusLabel = "▶",
    ) { set(p.copy(toolOffset = it)) }
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
    Num("Liczba sekcji", "Powyżej 16 moduł maszyny dostaje też PGN 229 (do 64 sekcji)", p.numSections.toDouble(), "", 1.0, 1.0, 64.0, 0) {
        set(p.copy(numSections = it.toInt(), sectionWidths = emptyList()))
    }
    val custom = p.sectionWidths.size == p.numSections
    Choice("Szerokości sekcji", listOf("Równe", "Różne"), if (custom) 1 else 0) {
        set(if (it == 1) p.copy(sectionWidths = List(p.numSections) { p.toolWidth / p.numSections }) else p.copy(sectionWidths = emptyList()))
    }
    if (!custom) {
        Label("Każda sekcja: ${(p.toolWidth / p.numSections).pl(2)} m", Ss.TextSoft, 14.sp)
    } else {
        p.sectionWidths.forEachIndexed { i, w ->
            Num("Sekcja ${i + 1}", if (i == 0) "Od lewej" else null, w, "m", 0.05, 0.05, 30.0, 2) { v ->
                val nw = p.sectionWidths.toMutableList().also { it[i] = v }
                set(p.copy(sectionWidths = nw, toolWidth = nw.sum()))
            }
        }
        Label("Razem: ${p.sectionWidths.sum().pl(2)} m (= szerokość robocza)", Ss.TextSoft, 14.sp)
    }
    Num("Włączanie z wyprzedzeniem", "Czas otwarcia zaworu / opadania", p.lookAheadOn, "s", 0.1, 0.0, 5.0, 1) { set(p.copy(lookAheadOn = it)) }
    Num("Wyłączanie z wyprzedzeniem", "Czas zamknięcia zaworu", p.lookAheadOff, "s", 0.1, 0.0, 5.0, 1) { set(p.copy(lookAheadOff = it)) }
    Num("Sekcje stoją poniżej", null, p.slowSpeedCutoff, "km/h", 0.1, 0.0, 5.0, 1) { set(p.copy(slowSpeedCutoff = it)) }
    Toggle("Wyłączaj poza granicą pola", p.sectionOffWhenOut) { set(p.copy(sectionOffWhenOut = it)) }
    Toggle("Wyłączaj na uwrociu", p.sectionOffInHeadland) { set(p.copy(sectionOffInHeadland = it)) }
    SectionTitle("Ścieżki technologiczne")
    Num("Przejazdów siewnika na opryskiwacz", "Szerokość opryskiwacza ÷ szerokość siewnika (np. 18 m / 3 m = 6); 0 = wyłączone", p.tramEvery.toDouble(), "", 1.0, 0.0, 30.0, 0) { set(p.copy(tramEvery = it.toInt())) }
    if (p.tramEvery > 0) {
        Num("Rozstaw kół opryskiwacza", null, p.tramTrack, "m", 0.05, 1.0, 3.5, 2) { set(p.copy(tramTrack = it)) }
        Num("Szerokość śladu koła", null, p.tramWheel, "m", 0.05, 0.1, 1.0, 2) { set(p.copy(tramWheel = it)) }
        Num("Przesunięcie układu", "O ile przejazdów przesunąć ścieżki względem linii AB", p.tramShift.toDouble(), "", 1.0, -30.0, 30.0, 0) { set(p.copy(tramShift = it.toInt())) }
        Label("Moduł maszyny dostaje bity śladu (PGN 239): wyłącza sekcje nad śladem kół — po prawej albo lewej stronie.", Ss.TextMuted, 12.sp)
    }
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
    GuidanceTuning(p, set)
}

/** Algorytm prowadzenia i jego wzmocnienia (Ustawienia → Prowadzenie i ekran Sterowanie). */
@Composable
private fun ColumnScope.GuidanceTuning(p: VehicleProfile, set: (VehicleProfile) -> Unit) {
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
    Num("Poprawka kursu z dwóch anten", "Gdy anteny nie stoją dokładnie w poprzek ciągnika", p.dualHeadingOffset, "°", 0.1, -180.0, 180.0, 1) { set(p.copy(dualHeadingOffset = it)) }
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
    WasCalibrationPanel(hw, p, set)
    Num("Ackermann", "Korekta różnicy skrętu w lewo i w prawo", p.ackerman.toDouble(), "%", 1.0, 1.0, 200.0, 0) { set(p.copy(ackerman = it.toInt())) }
    SectionTitle("Martwa strefa")
    Num("Martwa strefa kąta", "Poniżej tej różnicy kąta silnik nie poprawia", p.deadZoneDeg, "°", 0.05, 0.0, 2.0, 2) { set(p.copy(deadZoneDeg = it)) }
    Num("Opóźnienie martwej strefy", "Ile kroków po 0,1 s", p.deadZoneDelay.toDouble(), "", 1.0, 0.0, 50.0, 0) { set(p.copy(deadZoneDelay = it.toInt())) }
    SectionTitle("Silnik / zawór")
    Num("Wzmocnienie P", "Jak mocno silnik reaguje na różnicę kąta", p.gainP.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(gainP = it.toInt())) }
    Num("Maksymalny PWM", null, p.highPwm.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(highPwm = it.toInt())) }
    Num("Niski PWM", "Moc przy małych korektach", p.lowPwm.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(lowPwm = it.toInt())) }
    Num("Minimalny PWM", "Najmniejsza moc, przy której koła ruszają", p.minPwm.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(minPwm = it.toInt())) }
}

/** Samouczenie prowadzenia: tryb, ocena jazdy, propozycje, historia, cofnięcie. */
@Composable
private fun ColumnScope.AutoTunePanel(hw: HardwareController) {
    SectionTitle("Samouczenie (dobiera ustawienia w czasie jazdy)")
    Label(
        "Jak adaptacja w LPG: co 30 s spokojnej jazdy na autosterze ocenia odchyłkę i pracę kół i zmienia po trochu wyprzedzenie, " +
            "człon całkujący, wzmocnienie P i minimalny PWM. PODPOWIEDZI — pyta przed zmianą, AUTOMAT — zmienia sam. Zawsze można cofnąć.",
        Ss.TextSoft, 13.sp,
    )
    Choice("Samouczenie", AutoTune.Mode.entries.map { it.label }, hw.tuneMode.ordinal) { hw.changeTuneMode(AutoTune.Mode.entries[it]) }
    hw.tuneStats?.let { st ->
        Label(
            "Ostatnie 30 s: odchyłka średnio ${st.rmsCm.pl(1)} cm (maks. ${st.maxCm.pl(0)} cm), przesunięcie ${st.meanCm.pl(1, plusSign = true)} cm, " +
                "${st.crossingsPerMin.pl(0)} przejść/min, koła: opóźnienie ${(st.wheelLagS * 1000).pl(0)} ms, siła ${(st.wheelGain * 100).pl(0)}%",
            if (st.rmsCm < 2.5) Ss.Accent else Ss.TextSoft, 13.sp,
        )
    }
    TunePendingCard(hw, Modifier.fillMaxWidth())
    hw.tuneHistory.take(5).forEach { Label(it, Ss.TextMuted, 12.sp) }
    if (hw.tuneBaseline != null) {
        ActionButton(onClick = { hw.revertTune() }) { ButtonText("Cofnij zmiany samouczenia", color = Ss.Warning) }
    }
}

/** Propozycja samouczenia do przyjęcia (też na mapie). */
@Composable
fun TunePendingCard(hw: HardwareController, modifier: Modifier) {
    val prop = hw.tunePending ?: return
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Ss.Bar.copy(alpha = 0.96f)).border(2.dp, Ss.Info, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Label("Samouczenie proponuje:", Ss.Info, 12.sp)
        prop.reasons.forEach { Text(it, color = Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = { hw.acceptTune() }) { ButtonText("Zastosuj", color = Ss.OnAccent, weight = FontWeight.Bold) }
            ActionButton(onClick = { hw.dismissTune() }) { ButtonText("Pomiń", color = Ss.TextSoft) }
        }
    }
}

/** Autostart z systemem i zamknięcie po zaniku zasilania z ciągnika. */
@Composable
private fun ColumnScope.PowerSettings(hw: HardwareController) {
    val pp = eu.smartsteer.navigator.ui.power.PowerPlatform
    var auto by remember { mutableStateOf(runCatching { pp.isAutostart() }.getOrDefault(false)) }
    var msg by remember { mutableStateOf<String?>(null) }
    SectionTitle("Zasilanie")
    val ext = eu.smartsteer.navigator.ui.power.PowerState.external
    Label(
        when (ext) { true -> "Teraz: zasilanie z ciągnika (zasilacz) jest"; false -> "Teraz: praca na baterii — brak zasilania z ciągnika"; null -> "Teraz: stan zasilania nieznany (komputer bez baterii?)" },
        if (ext == false) Ss.Warning else Ss.TextMuted, 13.sp,
    )
    if (pp.autostartSupported) {
        Toggle("Uruchamiaj program razem z Windows", auto) { on -> msg = pp.setAutostart(on); auto = runCatching { pp.isAutostart() }.getOrDefault(on) }
        Label("Żeby komputer sam się włączał po podaniu zasilania, w BIOS-ie ustaw „AC Power Recovery / Power On after AC loss” = On.", Ss.TextMuted, 12.sp)
    } else {
        Label("Autostart: ${pp.setAutostart(true) ?: "—"}", Ss.TextMuted, 12.sp)
    }
    msg?.let { Label(it, Ss.Warning, 12.sp) }
    Toggle("Zamknij po zaniku zasilania", hw.powerAutoClose) { hw.setPowerOptions(autoClose = it) }
    if (hw.powerAutoClose) {
        Num("Po ilu sekundach", "Krótkie przerwy (rozruch silnika) nie zamykają programu", hw.powerDelayS.toDouble(), "s", 1.0, 3.0, 600.0, 0) { hw.setPowerOptions(delayS = it.toInt()) }
        if (pp.canShutdownSystem) Toggle("Wyłącz też komputer", hw.powerShutdownPc) { hw.setPowerOptions(shutdownPc = it) }
        Label("Przed zamknięciem: autoster wyłączony, pole zapisane. Na pasku odliczania można anulować.", Ss.TextMuted, 12.sp)
    }
}

/** Kalibracja czujnika kąta z jazdy po okręgu. */
@Composable
private fun WasCalibrationPanel(hw: HardwareController, p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    val cal = hw.session.wasCal
    SectionTitle("Kalibracja z jazdy w kółko")
    Label(
        "Najpierw wyzeruj (koła prosto). Potem na równym terenie: skręć kierownicą mniej więcej do połowy, „Start”, " +
            "jedź wolno (3–6 km/h) pełne kółko, nie ruszając kierownicą. Program policzy prawdziwy kąt z promienia okręgu.",
        Ss.TextSoft, 13.sp,
    )
    val res = cal.result(p.wheelbase, p.countsPerDegree)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            background = if (cal.running) Ss.Warning else Ss.SurfaceHigh, border = if (cal.running) Ss.Warning else Ss.BorderStrong,
            onClick = { if (cal.running) cal.stop() else cal.start(); hw.touch() },
        ) { ButtonText(if (cal.running) "Stop" else "Start", color = if (cal.running) Ss.OnWarning else Ss.Text, weight = FontWeight.Bold) }
        if (cal.running) Label("Przejechano ${cal.turnedDeg.pl(0)}° z 360°", Ss.TextSoft, 14.sp)
    }
    if (res != null) {
        Label(
            "Promień ${res.radiusM.pl(1)} m (dopasowanie ±${(res.fitErrorM * 100).pl(0)} cm) · prawdziwy kąt ${res.trueDeg.pl(1)}°, czujnik pokazywał ${res.reportedDeg.pl(1)}° " +
                "→ impulsy na stopień: ${p.countsPerDegree} → ${res.newCountsPerDeg}",
            Ss.Accent, 14.sp,
        )
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = { set(p.copy(countsPerDegree = res.newCountsPerDeg)); cal.stop(); hw.touch() }) {
            ButtonText("Zastosuj ${res.newCountsPerDeg}", color = Ss.OnAccent, weight = FontWeight.Bold)
        }
        if (res.fitErrorM > 0.3) Label("Okrąg nierówny (teren, poślizg?) — lepiej powtórzyć.", Ss.Warning, 12.sp)
    }
}

/** Nadzór autosteru: progi samoczynnego wyłączenia i dziennik wyłączeń. */
@Composable
private fun ColumnScope.SupervisorTab(hw: HardwareController, p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    SectionTitle("Nadzór autosteru")
    Label(
        "Autoster wyłącza się sam (z alarmem), gdy dzieje się coś niebezpiecznego. Zawsze działa: brak GPS lub modułu skrętu ponad 1 s, " +
            "prędkość poza zakresem. Płytka AIO dodatkowo zatrzymuje silnik, gdy przestanie dostawać polecenia z programu. 0 = sprawdzanie wyłączone.",
        Ss.TextSoft, 13.sp,
    )
    Num("Brak RTK FIX dłużej niż", "FLOAT, DGPS albo zwykły GPS; 0 = jeździ także bez RTK", p.supRtkLossS, "s", 1.0, 0.0, 60.0, 0) { set(p.copy(supRtkLossS = it)) }
    Num("Poprawki starsze niż", "Wiek poprawek z odbiornika (GGA)", p.supCorrAgeS, "s", 1.0, 0.0, 60.0, 0) { set(p.copy(supCorrAgeS = it)) }
    Num("Odjazd od linii większy niż", "Liczy się dopiero, gdy ciągnik już raz był na linii; nie w trakcie zawrotu", p.supMaxXteM, "m", 0.1, 0.0, 5.0, 1) { set(p.copy(supMaxXteM = it)) }
    Num("Koła nie słuchają: różnica kąta", "Zadany a rzeczywisty kąt kół (awaria silnika, zaworu, czujnika)", p.supSteerErrDeg, "°", 1.0, 0.0, 30.0, 0) { set(p.copy(supSteerErrDeg = it)) }
    Num("…przez", null, p.supSteerErrS, "s", 0.5, 0.5, 10.0, 1) { set(p.copy(supSteerErrS = it)) }
    Num("Przerwa w pracy programu", "Watchdog: gdy program nie liczył prowadzenia dłużej (zawieszenie komputera)", p.supLoopGapS, "s", 0.1, 0.0, 3.0, 1) { set(p.copy(supLoopGapS = it)) }
    SectionTitle("Ostatnie wyłączenia")
    if (hw.recentTrips.isEmpty()) Label("Brak — autoster nie wyłączył się sam.", Ss.TextMuted, 13.sp)
    hw.recentTrips.take(10).forEach { Label(it, Ss.TextSoft, 13.sp) }
    Label("Pełny dziennik: ${hw.tripLogLocation}", Ss.TextMuted, 12.sp)
}

@Composable
private fun ColumnScope.BoardTab(hw: HardwareController, p: VehicleProfile, set: (VehicleProfile) -> Unit) {
    SectionTitle("Kierunki i odwrócenia (jak w AgOpenGPS)")
    Toggle("Odwróć czujnik kąta kół (WAS)", p.invertWas) { set(p.copy(invertWas = it)) }
    Toggle("Odwróć kierunek silnika / zaworu", p.invertMotor) { set(p.copy(invertMotor = it)) }
    Toggle("Odwróć przekaźniki modułu skrętu", p.invertRelays) { set(p.copy(invertRelays = it)) }
    Toggle("Odwróć przekaźniki modułu maszyny (sekcje)", p.machineInvertRelays) { set(p.copy(machineInvertRelays = it)) }
    Toggle("Odwróć przechył IMU", p.invertRoll) { set(p.copy(invertRoll = it)) }
    Choice("Przełącznik roboczy włączony, gdy styk", listOf("Zwarty (niski)", "Rozwarty (wysoki)"), if (p.workSwitchActiveLow) 0 else 1) {
        set(p.copy(workSwitchActiveLow = it == 0))
    }
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
    SectionTitle("Moduł maszyny (PGN 238)")
    Toggle("Podnośnik hydrauliczny", p.hydEnabled) { set(p.copy(hydEnabled = it)) }
    if (p.hydEnabled) {
        Num("Czas podnoszenia", null, p.hydRaiseTime.toDouble(), "s", 1.0, 0.0, 255.0, 0) { set(p.copy(hydRaiseTime = it.toInt())) }
        Num("Czas opuszczania", null, p.hydLowerTime.toDouble(), "s", 1.0, 0.0, 255.0, 0) { set(p.copy(hydLowerTime = it.toInt())) }
        Toggle("Podnoś sam na uwrociu", p.hydAuto) { set(p.copy(hydAuto = it)) }
        Label("Działa przy wyznaczonym uwrociu i włączonym przycisku „Podnośnik” na mapie. Wyprzedzenie = czas włączania sekcji.", Ss.TextMuted, 12.sp)
    }
    Num("Użytkownik 1", "Wartości dla własnego programu modułu", p.machineUser1.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(machineUser1 = it.toInt())) }
    Num("Użytkownik 2", null, p.machineUser2.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(machineUser2 = it.toInt())) }
    Num("Użytkownik 3", null, p.machineUser3.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(machineUser3 = it.toInt())) }
    Num("Użytkownik 4", null, p.machineUser4.toDouble(), "", 1.0, 0.0, 255.0, 0) { set(p.copy(machineUser4 = it.toInt())) }
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
    Field("Login", user, "") { user = it }
    Field("Hasło", pass, "", password = true) { pass = it }
    Field("Punkt montowania (mountpoint)", mount, "np. NAWGEO_POJ_3_1") { mount = it }
    MountPicker(hw, host, port, user, pass, mount) { mount = it }
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

/** Lista aktywnych punktów montowania z castera — najbliższe najpierw; dotknięcie wybiera punkt. */
@Composable
private fun MountPicker(hw: HardwareController, host: String, port: String, user: String, pass: String, selected: String, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton(onClick = {
            scope.launch { hw.fetchMounts(host, port.toIntOrNull() ?: 2101, user, pass) }
        }) { ButtonText(if (hw.mountsLoading) "Pobieram…" else "Pobierz aktywne punkty z castera") }
        hw.mountsStatus?.let { Label(it, if (hw.mounts.isEmpty() && !hw.mountsLoading) Ss.Warning else Ss.TextSoft, 13.sp) }
    }
    if (hw.mounts.isEmpty()) return
    val g = hw.session.lastGga
    val sorted = remember(hw.mounts, g?.latitude?.let { (it * 100).toInt() }, g?.longitude?.let { (it * 100).toInt() }) {
        Ntrip.sortByDistance(hw.mounts, g?.latitude, g?.longitude)
    }
    if (g == null) Label("Brak pozycji GPS — lista alfabetyczna. Z pozycją najbliższe stacje będą na górze.", Ss.TextMuted, 12.sp)
    val shown = if (showAll) sorted else sorted.take(12)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ss.Surface).border(1.dp, Ss.Border, RoundedCornerShape(12.dp)),
    ) {
        shown.forEachIndexed { i, (m, km) ->
            val cur = m.name == selected
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Ss.Border))
            Row(
                Modifier.fillMaxWidth().background(if (cur) Ss.AccentSoft else Ss.Surface)
                    .clickable { onPick(m.name) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = if (cur) Ss.Accent else Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Label(
                        listOf(m.identifier, m.format, m.navSystem).filter { it.isNotEmpty() }.joinToString(" · "),
                        Ss.TextMuted, 12.sp,
                    )
                }
                if (m.nmea || m.networkSolution) Pill("VRS / sieć", Ss.Info)
                Text(km?.let { "${it.pl(if (it < 10) 1 else 0)} km" } ?: "—", color = Ss.TextSoft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (sorted.size > 12) {
        ActionButton(onClick = { showAll = !showAll }) {
            ButtonText(if (showAll) "Pokaż tylko najbliższe" else "Pokaż wszystkie (${sorted.size})", color = Ss.TextSoft)
        }
    }
    Label("Po wybraniu punktu naciśnij „Zapisz i połącz”.", Ss.TextMuted, 12.sp)
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
private fun PickRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    ActionButton(
        Modifier.fillMaxWidth(),
        background = if (selected) Ss.AccentSoft else Ss.SurfaceHigh,
        border = if (selected) Ss.Accent else Ss.BorderStrong,
        onClick = onClick,
    ) {
        Text(if (selected) "✓" else "○", color = if (selected) Ss.Accent else Ss.TextMuted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(title, color = Ss.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Label(sub, Ss.TextMuted, 12.sp)
        }
    }
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
        TextInput(
            value, onChange, title = label, modifier = Modifier.weight(1f), hint = hint, password = password,
            mode = if (number) KeypadMode.Number else KeypadMode.Text,
            filter = if (number) { v -> v.filter { it.isDigit() } } else { v -> v },
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
        TextInput(
            newName, { newName = it }, title = "Nazwa nowego profilu", modifier = Modifier.weight(1f),
            hint = "np. Fendt 724 + siewnik 6 m", maxLength = 40, fontSize = 18.sp,
        )
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = {
            val err = hw.saveProfileAs(newName)
            msg = err ?: "Zapisano „${hw.profileName}”"
            if (err == null) newName = ""
        }) { ButtonText("Zapisz", color = Ss.OnAccent, weight = FontWeight.Bold) }
    }
    msg?.let { Label(it, Ss.TextSoft, 14.sp) }
    Label("Pliki profili: ${hw.profilesLocation}", Ss.TextMuted, 12.sp)

    // --- Kopiuj z AgOpenGPS ---
    SectionTitle("Kopiuj ustawienia z AgOpenGPS")
    val vehicles = remember(hw.tick / 50) { hw.agoVehicles() }
    val tools = remember(hw.tick / 50) { hw.agoTools() }
    var pickV by remember { mutableStateOf<String?>(null) }
    var pickT by remember { mutableStateOf<String?>(null) }
    if (vehicles.isEmpty() && tools.isEmpty()) {
        Label("Nie znalazłem AgOpenGPS na tym komputerze (szukam w: ${hw.agoFolder}).", Ss.TextMuted, 13.sp)
    } else {
        Label("Wybierz pojazd i (w nowszym AgOpenGPS) narzędzie — powstanie nowy profil ze wszystkimi ustawieniami: wymiary, antena, prowadzenie, moduł skrętu, płytka, IMU, sekcje, zawrót.", Ss.TextSoft, 13.sp)
        vehicles.forEach { v ->
            PickRow("Pojazd: " + v.substringAfter('/').removeSuffix(".xml"), v.substringBefore('/'), pickV == v) { pickV = if (pickV == v) null else v }
        }
        tools.forEach { t ->
            PickRow("Narzędzie: " + t.substringAfter('/').removeSuffix(".xml"), "ToolProfiles", pickT == t) { pickT = if (pickT == t) null else t }
        }
        ActionButton(background = Ss.Accent, border = Ss.Accent, onClick = { msg = hw.copyFromAgo(pickV, pickT) }) {
            ButtonText("Kopiuj do nowego profilu", color = Ss.OnAccent, weight = FontWeight.Bold)
        }
        msg?.let { Label(it, Ss.TextSoft, 14.sp) }
    }
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

/** Wiersz z liczbą: duże przyciski − / + (w rękawicy) albo dotknięcie liczby i wpisanie dokładnej wartości. */
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
        NumberField(
            label, value, decimals, fmt?.invoke(value) ?: (value.pl(decimals) + if (unit.isNotEmpty()) " $unit" else ""),
            min, max, onChange, Modifier.width(150.dp),
        )
        ActionButton(Modifier.width(56.dp), onClick = { change(1) }) {
            if (plusLabel != null) ButtonText(plusLabel, size = 18.sp) else NavIcon(IconKind.Plus, Ss.Text, 20.dp)
        }
    }
}

/** Liczba, którą można wpisać: dotknięcie → klawisz numeryczny na ekranie (Windows i Android), OK → zapis. */
@Composable
private fun NumberField(
    title: String, value: Double, decimals: Int, display: String, min: Double, max: Double,
    onCommit: (Double) -> Unit, modifier: Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.clip(shape).background(Ss.Ground).border(1.dp, Ss.Border, shape)
            .clickable {
                Keypad.open(KeypadRequest(
                    title = title,
                    initial = value.pl(decimals).replace('−', '-').replace(" ", ""),
                    mode = KeypadMode.Number,
                    hint = "Zakres: ${min.pl(decimals)} … ${max.pl(decimals)}",
                    maxLength = 12,
                    filter = { v -> v.filter { it.isDigit() || it in ".,-−" } },
                ) { t ->
                    t.replace('−', '-').replace(',', '.').toDoubleOrNull()?.let { onCommit(it.coerceIn(min, max)) }
                })
            }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(display, color = Ss.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
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

// ---------------- ekrany Sterowanie i Maszyna w trybie Sprzęt ----------------

/** Sterowanie (tryb Sprzęt): odczyty modułu skrętu na żywo, strojenie prowadzenia i silnika, test silnika. */
@Composable
fun HardwareSteerScreen(hw: HardwareController) {
    hw.tick
    val p = hw.profile
    val set: (VehicleProfile) -> Unit = { hw.updateProfile(it) }
    val s = hw.session
    val now = hw.now()
    val st = s.autosteer?.takeIf { s.autosteerAlive(now) }
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { Stat("Autoster", if (s.engaged) "WŁĄCZONY" else "wyłączony", valueSize = 20.sp, valueColor = if (s.engaged) Ss.Accent else Ss.TextSoft) }
                    Box(Modifier.weight(1f)) { Stat("Moduł skrętu", if (st != null) "połączony" else "brak", valueSize = 20.sp, valueColor = if (st != null) Ss.Accent else Ss.Warning) }
                    Box(Modifier.weight(1f)) { Stat("Przełącznik", st?.let { if (it.steerSwitch) "WŁ." else "WYŁ." } ?: "—", valueSize = 20.sp) }
                    Box(Modifier.weight(1f)) { Stat("Prędkość", "${s.speedKmh.pl(1)} km/h", valueSize = 20.sp) }
                }
                s.message?.let { Label(it, Ss.Warning, 13.sp) }
            }
            SectionTitle("Ograniczenia")
            Num("Maksymalny skręt kół", null, p.maxSteerAngle, "°", 1.0, 10.0, 90.0, 0) { set(p.copy(maxSteerAngle = it)) }
            Num("Wyłącz powyżej", "Autoster wyłączy się przy większej prędkości", p.maxSteerSpeed, "km/h", 1.0, 1.0, 40.0, 0) { set(p.copy(maxSteerSpeed = it)) }
            AutoTunePanel(hw)
            GuidanceTuning(p, set)
            ModuleTab(hw, p, set)
            SupervisorTab(hw, p, set)
            Label("Ustawienia modułu skrętu wysyłają się do płytki same (po 1,5 s od ostatniej zmiany) i zapisują w profilu „${hw.profileName}”.", Ss.TextMuted, 12.sp)
        }
        LivePanel(hw)
    }
}

/** Maszyna (tryb Sprzęt): rysunek zestawu z profilu, sekcje na żywo, ustawienia maszyny. */
@Composable
fun HardwareImplementScreen(hw: HardwareController) {
    hw.tick
    val p = hw.profile
    val set: (VehicleProfile) -> Unit = { hw.updateProfile(it) }
    val sec = hw.session.sections
    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Panel(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionTitle("Zestaw — profil „${hw.profileName}”") }
                Segmented(
                    options = SectionMode.entries.map { it.label },
                    selected = sec.mode.ordinal,
                    onSelect = {
                        val m = SectionMode.entries[it]
                        if (m == SectionMode.MANUAL && sec.mode != SectionMode.MANUAL) sec.manual.fill(true)
                        sec.mode = m
                        hw.touch()
                    },
                    selectedColor = { when (SectionMode.entries[it]) { SectionMode.AUTO -> Ss.Accent; SectionMode.MANUAL -> Ss.Warning; SectionMode.OFF -> Ss.TextMuted } },
                )
            }
            ToolPicker(hw)
            RigDrawing(hw, Modifier.weight(1f).fillMaxWidth())
            Text(
                "Szerokość robocza ${p.toolWidth.pl(2)} m · ${p.numSections} sekcji · odstęp przejazdów ${p.passSpacing.pl(2)} m",
                color = Ss.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Label(
                "Rozstaw osi ${p.wheelbase.pl(2)} m · antena ${abs(p.antennaPivot).pl(2)} m ${if (p.antennaPivot >= 0) "przed" else "za"} tylną osią · " +
                    "zaczep ${abs(p.hitchLength).pl(1)} m ${if (p.hitchLength < 0) "za" else "przed"} osią" +
                    (if (p.toolTrailing) " · dyszel ${abs(p.trailingHitchLength).pl(1)} m" else "") +
                    (if (abs(p.toolOffset) >= 0.005) " · maszyna ${abs(p.toolOffset).pl(2)} m w ${if (p.toolOffset > 0) "prawo" else "lewo"}" else ""),
                Ss.TextSoft, 13.sp,
            )
        }
        Column(
            Modifier.width(470.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { ToolTab(p, set) }
    }
}

/** Szybka zmiana maszyny: zapisane maszyny (dotknięcie = podepnij), zapis bieżącej pod nazwą. */
@Composable
private fun ToolPicker(hw: HardwareController) {
    var newName by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("Maszyna:", Ss.TextMuted, 13.sp)
        hw.toolNames.forEach { n ->
            val cur = n == hw.currentTool
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (cur) Ss.AccentSoft else Ss.SurfaceHigh)
                    .border(1.dp, if (cur) Ss.Accent else Ss.Border, RoundedCornerShape(10.dp))
                    .clickable { msg = hw.loadTool(n) ?: "Podpięto „$n”" }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n, color = if (cur) Ss.Accent else Ss.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Box(Modifier.padding(horizontal = 6.dp).clickable { hw.deleteTool(n) }.padding(8.dp)) { Text("✕", color = Ss.TextMuted, fontSize = 12.sp) }
            }
        }
        TextInput(newName, { newName = it }, title = "Nazwa maszyny", modifier = Modifier.width(200.dp), hint = "np. Talerzówka 3 m", maxLength = 40, fontSize = 14.sp)
        ActionButton(onClick = { val e = hw.saveToolAs(newName); msg = e ?: "Zapisano maszynę"; if (e == null) newName = "" }) { ButtonText("Zapisz maszynę") }
    }
    msg?.let { Label(it, Ss.TextSoft, 12.sp) }
    Label("Odległość od tylnej osi do maszyny (zaczep + dyszel) ustawiasz po prawej — od niej zależy, gdzie maszyna maluje pokrycie i kiedy włączają się sekcje i podnośnik.", Ss.TextMuted, 12.sp)
}

/** Widok z góry: ciągnik, antena, zaczep, maszyna i sekcje (zielona = pracuje). Wymiary z profilu, w skali. */
@Composable
private fun RigDrawing(hw: HardwareController, modifier: Modifier) {
    val p = hw.profile
    val sec = hw.session.sections
    val tm = rememberTextMeasurer()
    Canvas(modifier.padding(vertical = 12.dp)) {
        val widths = p.sectionWidthsEffective
        val total = widths.sum().coerceAtLeast(0.1)
        val track = p.trackWidth.coerceAtLeast(0.5)
        val front = p.wheelbase + 0.9
        val rear = -0.7
        val hitchY = p.hitchLength
        val toolY = if (p.toolTrailing) hitchY + p.trailingHitchLength else hitchY
        val depth = 0.7
        val minY = minOf(rear, toolY - depth) - 0.3
        val maxY = front + 0.3
        val left = -total / 2 + p.toolOffset
        val minX = minOf(-track / 2 - 0.3, left) - 0.3
        val maxX = maxOf(track / 2 + 0.3, left + total) + 0.3
        val k = minOf(size.width / (maxX - minX), size.height / (maxY - minY)).toFloat()
        val ox = size.width / 2f - ((minX + maxX) / 2).toFloat() * k
        val oy = size.height / 2f + ((minY + maxY) / 2).toFloat() * k
        fun pt(x: Double, y: Double) = Offset(ox + x.toFloat() * k, oy - y.toFloat() * k)

        // ciągnik: kadłub, koła, kabina
        val body = Path().apply {
            val a = pt(-track / 2 + 0.25, front); val b = pt(track / 2 - 0.25, rear)
            addRoundRect(androidx.compose.ui.geometry.RoundRect(a.x, a.y, b.x, b.y, androidx.compose.ui.geometry.CornerRadius(0.3f * k)))
        }
        drawPath(body, Ss.Tractor)
        fun wheel(x: Double, y: Double, w: Double, l: Double) {
            val a = pt(x - w / 2, y + l / 2); val b = pt(x + w / 2, y - l / 2)
            drawRect(Color(0xFF1B1F1D), a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y))
        }
        wheel(-track / 2, 0.0, 0.5, 1.5); wheel(track / 2, 0.0, 0.5, 1.5)
        wheel(-track / 2 + 0.1, p.wheelbase, 0.4, 1.0); wheel(track / 2 - 0.1, p.wheelbase, 0.4, 1.0)
        drawLine(Ss.Text.copy(alpha = 0.5f), pt(-track / 2, 0.0), pt(track / 2, 0.0), 2f)
        drawLine(Ss.Text.copy(alpha = 0.5f), pt(-track / 2, p.wheelbase), pt(track / 2, p.wheelbase), 2f)
        // antena
        val ant = pt(-p.antennaOffset, p.antennaPivot)
        drawCircle(Ss.GuideLine, 0.22f * k + 3f, ant)
        drawCircle(Ss.Ground, 0.22f * k + 3f, ant, style = Stroke(2f))
        // zaczep i dyszel
        val hitch = pt(0.0, hitchY)
        drawLine(Ss.Text, pt(0.0, minOf(0.0, hitchY.coerceAtLeast(rear))), hitch, 4f)
        drawCircle(Ss.Text, 6f, hitch)
        val toolC = pt(p.toolOffset, toolY)
        if (p.toolTrailing) drawLine(Ss.Text, hitch, toolC, 4f)
        // belka maszyny i sekcje
        drawLine(Ss.TextMuted, pt(left, toolY), pt(left + total, toolY), 3f)
        var x = left
        widths.forEachIndexed { i, w ->
            val a = pt(x + 0.03, toolY + depth / 2)
            val b = pt(x + w - 0.03, toolY - depth / 2)
            val on = i < sec.on.size && sec.on[i]
            drawRoundRect(
                if (on) Ss.Accent else if (sec.mode == SectionMode.OFF) Ss.Dim else Ss.SurfaceHigh,
                a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y), androidx.compose.ui.geometry.CornerRadius(6f),
            )
            if (b.x - a.x > 18f) {
                val t = tm.measure("${i + 1}", TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (on) Ss.OnAccent else Ss.Text))
                drawText(t, topLeft = Offset((a.x + b.x) / 2 - t.size.width / 2f, (a.y + b.y) / 2 - t.size.height / 2f))
            }
            x += w
        }
        // podpis anteny
        val t = tm.measure("antena", TextStyle(fontSize = 12.sp, color = Ss.TextSoft))
        drawText(t, topLeft = Offset(ant.x + 0.3f * k + 6f, ant.y - t.size.height / 2f))
    }
}
