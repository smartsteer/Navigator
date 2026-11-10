package eu.smartsteer.navigator.ui.screens

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
    Vehicle("Ciągnik"), Tool("Maszyna"), Guidance("Prowadzenie"), Imu("IMU"), Module("Moduł skrętu"), Board("Płytka"), Profiles("Profile"),
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
                    Tab.Vehicle -> VehicleTab(p, set)
                    Tab.Tool -> ToolTab(p, set)
                    Tab.Guidance -> GuidanceTab(p, set)
                    Tab.Imu -> ImuTab(hw, p, set)
                    Tab.Module -> ModuleTab(hw, p, set)
                    Tab.Board -> BoardTab(hw, p, set)
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
}

@Composable
private fun ColumnScope.GuidanceTab(p: VehicleProfile, set: (VehicleProfile) -> Unit) {
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
    Panel(Modifier.width(280.dp).fillMaxHeight()) {
        SectionTitle("Na żywo")
        if (!hw.hardware) {
            Label("Włącz tryb SPRZĘT na ekranie Połączenia, żeby widzieć odczyty z płytki.", Ss.TextSoft, 14.sp)
        }
        Stat("Odchyłka", if (s.line != null) "${s.xteCm.pl(1, plusSign = true)} cm" else "—", valueSize = 22.sp)
        Stat("Kąt zadany", "${s.steerSetDeg.pl(1, plusSign = true)}°", valueSize = 22.sp)
        Stat("Kąt kół", st?.let { "${it.actualAngleDeg.pl(1, plusSign = true)}°" } ?: "—", valueSize = 22.sp)
        Stat("PWM", st?.pwm?.toString() ?: "—", valueSize = 22.sp)
        val imu = hw.session.imu
        Stat("Przechył", imu.rollDeg?.takeIf { imu.rollAlive(now) }?.let { "${it.pl(1, plusSign = true)}° · poprawka ${(hw.session.rollCorrectionM * 100).pl(0, plusSign = true)} cm" } ?: "brak IMU", valueSize = 18.sp)
        Stat("Człon całkujący", "${(s.integral * 100).pl(1, plusSign = true)} cm", valueSize = 18.sp)
        Stat("Przełącznik autosteru", st?.let { if (it.steerSwitch) "WŁĄCZONY" else "WYŁĄCZONY" } ?: "—", valueSize = 18.sp)
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
