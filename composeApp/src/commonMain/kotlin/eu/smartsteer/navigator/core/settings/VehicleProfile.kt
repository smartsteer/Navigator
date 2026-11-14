package eu.smartsteer.navigator.core.settings

import eu.smartsteer.navigator.core.update.MiniJson

enum class SteerAlgorithm(val label: String) { PURE_PURSUIT("Pure pursuit"), STANLEY("Stanley") }

/** Skąd kurs przy jednej antenie (setGPS_headingFromWhichSource w AgOpenGPS): z kolejnych pozycji („Fix”) albo z VTG odbiornika. */
enum class HeadingFrom(val label: String, val ago: String) { FIX("Z pozycji (Fix)", "Fix"), VTG("Z odbiornika (VTG)", "VTG"), DUAL("Dwie anteny", "Dual") }

/** Sposób włączania autosteru na płytce (PGN 251, bity 5–6). */
enum class SteerEnable(val label: String) { NONE("Brak"), SWITCH("Przełącznik"), BUTTON("Przycisk") }

/**
 * Profil pojazdu — wszystkie ustawienia ciągnika, maszyny, prowadzenia i płytki AIO w jednym pliku,
 * jak profile pojazdów w AgOpenGPS. Wartości domyślne takie jak w AgOpenGPS.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki
 * SourceCode/GPS/Properties/VehicleSettings.cs i ToolSettings.cs (nazwy i wartości domyślne ustawień, w tym setIMU_*).
 */
data class VehicleProfile(
    // --- Pojazd ---
    /** Rozstaw osi, m. */
    val wheelbase: Double = 3.3,
    /** Rozstaw kół, m (rysunek, później kompensacja). */
    val trackWidth: Double = 1.9,
    /** Wysokość anteny nad ziemią, m (kompensacja przechyłu — etap IMU). */
    val antennaHeight: Double = 3.0,
    /** Odległość anteny przed tylną osią, m (ujemna = za osią). */
    val antennaPivot: Double = 0.1,
    /** Przesunięcie anteny w bok, m: + w lewo, − w prawo (jak w AgOpenGPS). */
    val antennaOffset: Double = 0.0,
    /** Maksymalny kąt skrętu kół, °. */
    val maxSteerAngle: Double = 30.0,
    /** Autoster wyłącza się powyżej tej prędkości, km/h. */
    val maxSteerSpeed: Double = 15.0,
    /** Autoster wyłącza się poniżej tej prędkości (po 8 s), km/h; 0 = wyłączone. */
    val minSteerSpeed: Double = 0.0,

    // --- Maszyna ---
    /** Szerokość robocza, m. */
    val toolWidth: Double = 4.0,
    /** Zakładka między przejazdami, m (+ = zachodzą na siebie). */
    val toolOverlap: Double = 0.0,
    /** Przesunięcie maszyny w bok względem ciągnika, m: + w prawo (setVehicle_toolOffset). */
    val toolOffset: Double = 0.0,
    /** Szerokości sekcji od lewej, m; pusta = równe (szerokość / liczba sekcji). */
    val sectionWidths: List<Double> = emptyList(),
    /** Zaczep: odległość od tylnej osi, m; ujemna = za ciągnikiem (jak w AgOpenGPS). */
    val hitchLength: Double = -1.0,
    /** Maszyna ciągniona (na dyszlu), a nie zawieszona sztywno. */
    val toolTrailing: Boolean = true,
    /** Maszyna ciągniona: od zaczepu do osi maszyny, m; ujemna = za zaczepem. */
    val trailingHitchLength: Double = -2.5,
    /** Wózek z beczką między ciągnikiem a maszyną (setTool_isToolTBT) i jego dyszel, m (setVehicle_tankTrailingHitchLength). */
    val toolTbt: Boolean = false,
    val tankHitchLength: Double = 3.0,
    /** Odległość osi maszyny ciągnionej od jej środka roboczego, m (setTool_trailingToolToPivotLength). */
    val trailingToolToPivot: Double = 0.0,
    /** Ciągnik przegubowy (setVehicle_vehicleType = 2). */
    val articulated: Boolean = false,
    /** Liczba sekcji (równe szerokości), 1–16. */
    val numSections: Int = 3,
    /** Wyprzedzenie włączania sekcji, s. */
    val lookAheadOn: Double = 1.0,
    /** Wyprzedzenie wyłączania sekcji, s. */
    val lookAheadOff: Double = 0.5,
    /** Opóźnienie wyłączenia sekcji, s (AgOpenGPS setVehicle_toolOffDelay; działa tylko przy wyprzedzeniu wyłączania 0). */
    val turnOffDelay: Double = 0.0,
    /** Minimalne pokrycie, %: sekcja włącza się, gdy nieobrobione jest więcej niż (100 − to)% pola przed nią (setVehicle_minCoverage). */
    val minCoverage: Int = 100,
    /** Poniżej tej prędkości sekcje się wyłączają, km/h. */
    val slowSpeedCutoff: Double = 0.5,
    /** Sekcje wyłączają się poza granicą pola. */
    val sectionOffWhenOut: Boolean = true,
    /** Sekcje wyłączają się na uwrociu. */
    val sectionOffInHeadland: Boolean = true,
    /** Przełącznik roboczy (PGN 253) włącza i wyłącza sekcje — przy zmianie stanu, jak w AgOpenGPS (setF_isWorkSwitchEnabled). */
    val useWorkSwitch: Boolean = false,
    /** Przełącznik roboczy aktywny przy stanie niskim (zwarty do masy). */
    val workSwitchActiveLow: Boolean = true,
    /** Przełącznik roboczy włącza sekcje ręcznie (wszystkie), a nie automat (setF_isWorkSwitchManualSections). */
    val workSwitchManualSections: Boolean = false,
    /** Przełącznik autostera też włącza i wyłącza sekcje (setF_isSteerWorkSwitchEnabled). */
    val steerWorkSwitch: Boolean = false,
    /** …i włącza je ręcznie, a nie automat (setF_isSteerWorkSwitchManualSections). */
    val steerWorkSwitchManualSections: Boolean = false,

    // --- Auto-zawrót (CYouTurn; set_youTurnRadius, set_youSkipWidth) ---
    /** Promień zawrotu, m. */
    val youTurnRadius: Double = 8.1,
    /** Na który przejazd zawracać: 1 = sąsiedni. */
    val youTurnSkip: Int = 1,
    /** Odstęp zawrotu od granicy pola, m. */
    val youTurnFromBoundary: Double = 2.0,
    /** Mnożnik kąta skrętu na zawrocie (setAS_uTurnCompensation). */
    val uTurnCompensation: Double = 1.0,
    /** Długość prostych na wjeździe/wyjeździe zawrotu, m (set_youTurnExtensionLength). */
    val youTurnExtension: Double = 20.0,
    /** Wygładzanie ścieżki zawrotu (setAS_uTurnSmoothing). */
    val uTurnSmoothing: Int = 14,
    /** Ręczny zawrót i przesunięcie w bok tylko poniżej tej prędkości, km/h (setAS_functionSpeedLimit). */
    val functionSpeedLimit: Double = 12.0,

    // --- Prowadzenie ---
    val algorithm: SteerAlgorithm = SteerAlgorithm.PURE_PURSUIT,
    /** Pure pursuit: wyprzedzenie przy trzymaniu linii, m. */
    val lookAheadHold: Double = 3.0,
    /** Pure pursuit: mnożnik wyprzedzenia zależny od prędkości. */
    val lookAheadMult: Double = 1.5,
    /** Pure pursuit: współczynnik wyprzedzenia przy dojeżdżaniu do linii (× wyprzedzenie trzymania). */
    val acquireFactor: Double = 0.9,
    /** Pure pursuit: człon całkujący (0 = wyłączony). */
    val purePursuitIntegral: Double = 0.0,
    /** Stanley: wzmocnienie odchyłki. */
    val stanleyDistanceGain: Double = 1.0,
    /** Stanley: wzmocnienie błędu kursu. */
    val stanleyHeadingGain: Double = 1.0,
    /** Stanley: człon całkujący (0 = wyłączony). */
    val stanleyIntegral: Double = 0.0,
    /** Kompensacja zbocza: ° skrętu na ° przechyłu (wymaga IMU). */
    val sideHillComp: Double = 0.0,

    /** Martwa strefa: kąt zadany bliżej kąta kół niż tyle stopni nie jest poprawiany (setAS_deadZoneHeading / 100). */
    val deadZoneDeg: Double = 0.1,
    /** Martwa strefa: po ilu sekundach zaczyna działać (setAS_deadZoneDelay; w AgOpenGPS licznik rośnie raz na sekundę). */
    val deadZoneDelay: Int = 5,
    /** Autoster pracuje także przy cofaniu (setAS_isSteerInReverse; w AgOpenGPS domyślnie nie — status 0 przy cofaniu). */
    val steerInReverse: Boolean = false,
    /** Wyprzedzenie wyboru przejazdu, s (setAS_guidanceLookAheadTime). */
    val guidanceLookAheadS: Double = 1.5,
    /** Przełącznik/przycisk na płytce włącza i wyłącza autoster (setAS_isAutoSteerAutoOn — „zdalny autoster”). */
    val remoteAutoSteer: Boolean = true,
    /** Stały kontur — bajt prędkości kątowej w PGN 251 (setAS_isConstantContourOn). */
    val constantContour: Boolean = false,

    // --- IMU (żyroskop) ---
    /** Przechył odczytany na płaskim terenie, ° (odejmowany od odczytu). */
    val rollZero: Double = 0.0,
    /** Filtr przechyłu 0–0,98: większy = gładszy, ale wolniejszy. */
    val rollFilter: Double = 0.0,
    /** Odwrócenie znaku przechyłu (IMU zamontowane odwrotnie). */
    val invertRoll: Boolean = false,
    /** Waga korekty kursu IMU przez GPS (fuzja); 0 = kurs tylko z GPS. */
    val fusionWeight: Double = 0.06,
    /** Poprawka kursu z dwóch anten, ° (setGPS_dualHeadingOffset) — gdy anteny nie stoją dokładnie w poprzek. */
    val dualHeadingOffset: Double = 0.0,
    /** Kurs przy jednej antenie — jak w AgOpenGPS domyślnie z kolejnych pozycji. */
    val headingFrom: HeadingFrom = HeadingFrom.FIX,
    /** Wykrywanie jazdy do tyłu (setIMU_isReverseOn). */
    val reverseDetect: Boolean = true,
    /** Najmniejszy ruch anteny, przy którym liczy się nowy kurs, m (setGPS_minimumStepLimit). */
    val minStepM: Double = 0.05,
    /** Odległość między pozycjami do kursu, m (setF_minHeadingStepDistance). */
    val minHeadingStepM: Double = 0.5,
    /** Poprawka kursu o skręt kół przy jeździe do przodu / do tyłu (setGPS_forwardComp, setGPS_reverseComp). */
    val forwardComp: Double = 0.15,
    val reverseComp: Double = 0.3,
    /** Dwie anteny: powyżej [autoSwitchDualFixSpeed] km/h kurs z pozycji, a kurs z dwóch anten służy jak IMU (setAutoSwitchDualFixOn). */
    val autoSwitchDualFix: Boolean = false,
    val autoSwitchDualFixSpeed: Double = 2.0,
    /** Dwie anteny: kurs z dwóch anten jako IMU (setIMU_isDualAsIMU). */
    val dualAsImu: Boolean = false,
    /** Dwie anteny: co tyle metrów sprawdzane jest cofanie (setGPS_dualReverseDetectionDistance). */
    val dualReverseDistM: Double = 0.25,

    // --- Moduł maszyny (PGN 238) ---
    val machineInvertRelays: Boolean = false,
    /** Podnośnik hydrauliczny sterowany przez moduł maszyny. */
    val hydEnabled: Boolean = false,
    /** Czas podnoszenia / opuszczania, s. */
    val hydRaiseTime: Int = 3,
    val hydLowerTime: Int = 4,
    val machineUser1: Int = 1,
    val machineUser2: Int = 2,
    val machineUser3: Int = 3,
    val machineUser4: Int = 4,

    // --- Ścieżki technologiczne (tramline) ---
    /** Co ile przejazdów siewnika wypada przejazd opryskiwacza (szerokość opryskiwacza / szerokość siewnika); 0 = wyłączone. */
    val tramEvery: Int = 0,
    /** Rozstaw kół ciągnika z opryskiwaczem, m. */
    val tramTrack: Double = 1.8,
    /** Szerokość śladu koła, m. */
    val tramWheel: Double = 0.5,
    /** Przesunięcie układu ścieżek o tyle przejazdów siewnika. */
    val tramShift: Int = 0,
    /** Ścieżki jak w AgOpenGPS: szerokość opryskiwacza, m (setTram_tramWidth); 0 = wyłączone. */
    val tramWidth: Double = 0.0,
    /** Ile przejazdów opryskiwacza ze śladami (passes); 0 = na całe pole. */
    val tramPasses: Int = 0,
    /** Od którego przejazdu opryskiwacza zaczynają się ślady (startPass; przy śladzie wokół granicy zwykle 1). */
    val tramStartPass: Int = 1,
    /** Ślady po lewej stronie linii zamiast po prawej. */
    val tramFlip: Boolean = false,
    /** 0 wył., 1 wszystko, 2 tylko wzdłuż linii, 3 tylko wokół granicy (tram.displayMode). */
    val tramMode: Int = 1,
    /** Odwróć ślad przy krawędzi / przy środku maszyny (setTool_isTramOuterInverted). */
    val tramOuterInverted: Boolean = false,
    /** Automatyczny podnośnik na uwrociu (gdy włączony podnośnik w module maszyny). */
    val hydAuto: Boolean = true,
    /** Wyprzedzenie podnoszenia na uwrociu, s (setVehicle_hydraulicLiftLookAhead). */
    val hydLiftLookAhead: Double = 2.0,

    // --- Zmienne dawkowanie bez sterownika (rozsiewacz z dawką zależną od prędkości) ---
    /** Dawka ustawiona na rozsiewaczu przy prędkości [rateBaseSpeed]; 0 = sterownik dawki (PGN 224), bez podpowiedzi. */
    val rateBase: Double = 0.0,
    val rateBaseSpeed: Double = 10.0,
    /** Jak podpowiadać dawkę: 0 = prędkością (stałe otwarcie), 1 = krokami w sterowniku rozsiewacza (np. ±10%). */
    val rateHintMode: Int = 0,
    /** Krok zmiany dawki w sterowniku, %. */
    val rateStepPct: Double = 10.0,

    /** Sekwencja na uwrociu (HeadlandSequence.format); pusta = brak. */
    val headlandSeq: String = "",

    /** Maszyna to opryskiwacz — ostrzeżenia o wietrze (pogoda z internetu). */
    val isSprayer: Boolean = false,

    /** Kształt zawrotu: 0 = U / omega (sam dobiera), 1 = K z cofaniem (gdy przejazd węższy niż 2 promienie). */
    val youTurnStyle: Int = 0,

    // --- Nadzór autosteru (0 = wyłączone) ---
    /** Wyłącz, gdy brak RTK FIX dłużej niż tyle sekund. */
    val supRtkLossS: Double = 5.0,
    /** Wyłącz, gdy ciągnik był już na linii i odjechał od niej dalej niż tyle metrów. */
    val supMaxXteM: Double = 1.0,
    /** Wyłącz, gdy kąt kół różni się od zadanego o więcej niż tyle stopni… */
    val supSteerErrDeg: Double = 6.0,
    /** …przez tyle sekund (awaria silnika, zaworu albo czujnika kąta). */
    val supSteerErrS: Double = 2.0,
    /** Wyłącz, gdy poprawki RTK są starsze niż tyle sekund. */
    val supCorrAgeS: Double = 10.0,
    /** Wyłącz, gdy program przez tyle sekund nie liczył prowadzenia (zawieszenie, przeciążony komputer). */
    val supLoopGapS: Double = 0.5,

    // --- Moduł skrętu (PGN 252) ---
    val gainP: Int = 50,
    val highPwm: Int = 180,
    val lowPwm: Int = 30,
    val minPwm: Int = 25,
    val countsPerDegree: Int = 110,
    val wasOffset: Int = 3,
    val ackerman: Int = 100,

    // --- Płytka (PGN 251) ---
    val invertWas: Boolean = false,
    val invertRelays: Boolean = false,
    val invertMotor: Boolean = false,
    /** Przetwornik czujnika kąta: true = pojedynczy (Single), false = różnicowy. */
    val singleInput: Boolean = true,
    /** Sterownik silnika: true = Cytron, false = IBT2. */
    val cytron: Boolean = true,
    val steerEnable: SteerEnable = SteerEnable.SWITCH,
    val encoder: Boolean = false,
    val pressureSensor: Boolean = false,
    val currentSensor: Boolean = false,
    val danfoss: Boolean = false,
    /** Oś czujnika: false = X, true = Y. */
    val axisY: Boolean = false,
    /** Próg wyłączenia: impulsy enkodera albo % czujnika ciśnienia / prądu. */
    val maxPulseCounts: Int = 3,
) {
    /** Szerokości sekcji (równe, gdy nie wpisano własnych albo liczba się nie zgadza). */
    val sectionWidthsEffective: List<Double>
        get() {
            val n = numSections.coerceIn(1, 64)
            return if (sectionWidths.size == n && sectionWidths.all { it > 0 }) sectionWidths else List(n) { toolWidth / n }
        }

    /** Bajt ustawień modułu maszyny (PGN 238): bit 0 odwrócone przekaźniki, bit 1 podnośnik. */
    val machineSetting0: Int get() = (if (machineInvertRelays) 1 else 0) or (if (hydEnabled) 2 else 0)

    /** Odstęp między sąsiednimi przejazdami, m. */
    val passSpacing: Double get() = (toolWidth - toolOverlap).coerceAtLeast(0.1)

    /** Bajt ustawień 0 dla PGN 251 (kolejność bitów jak w AgOpenGPS FormSteer.cs). */
    val setting0: Int get() {
        var s = 0
        if (invertWas) s = s or 1
        if (invertRelays) s = s or 2
        if (invertMotor) s = s or 4
        if (singleInput) s = s or 8
        if (cytron) s = s or 16
        if (steerEnable == SteerEnable.SWITCH) s = s or 32
        if (steerEnable == SteerEnable.BUTTON) s = s or 64
        if (encoder) s = s or 128
        return s
    }

    /** Bajt ustawień 1 dla PGN 251. */
    val setting1: Int get() {
        var s = 0
        if (danfoss) s = s or 1
        if (pressureSensor) s = s or 2
        if (currentSensor) s = s or 4
        if (axisY) s = s or 8
        return s
    }

    fun toJson(): String = buildString {
        append("{\n")
        val e = entries()
        e.forEachIndexed { i, (k, v) ->
            append("  \"").append(k).append("\": ")
            when (v) {
                is String -> append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
                else -> append(v.toString())
            }
            if (i < e.lastIndex) append(',')
            append('\n')
        }
        append("}\n")
    }

    private fun entries(): List<Pair<String, Any>> = listOf(
        "wheelbase" to wheelbase, "trackWidth" to trackWidth, "antennaHeight" to antennaHeight,
        "antennaPivot" to antennaPivot, "antennaOffset" to antennaOffset, "maxSteerAngle" to maxSteerAngle,
        "maxSteerSpeed" to maxSteerSpeed, "minSteerSpeed" to minSteerSpeed,
        "toolWidth" to toolWidth, "toolOverlap" to toolOverlap, "toolOffset" to toolOffset,
        "sectionWidths" to sectionWidths.joinToString(";"),
        "deadZoneDeg" to deadZoneDeg, "deadZoneDelay" to deadZoneDelay, "dualHeadingOffset" to dualHeadingOffset,
        "machineInvertRelays" to machineInvertRelays, "hydEnabled" to hydEnabled, "hydRaiseTime" to hydRaiseTime,
        "hydLowerTime" to hydLowerTime, "machineUser1" to machineUser1, "machineUser2" to machineUser2,
        "machineUser3" to machineUser3, "machineUser4" to machineUser4,
        "tramEvery" to tramEvery, "tramTrack" to tramTrack, "tramWheel" to tramWheel, "tramShift" to tramShift, "tramWidth" to tramWidth, "tramPasses" to tramPasses, "tramStartPass" to tramStartPass,
        "tramFlip" to tramFlip, "tramMode" to tramMode, "tramOuterInverted" to tramOuterInverted, "hydAuto" to hydAuto,
        "rateBase" to rateBase, "rateBaseSpeed" to rateBaseSpeed, "rateHintMode" to rateHintMode, "rateStepPct" to rateStepPct,
        "youTurnStyle" to youTurnStyle, "uTurnCompensation" to uTurnCompensation, "youTurnExtension" to youTurnExtension,
        "uTurnSmoothing" to uTurnSmoothing, "functionSpeedLimit" to functionSpeedLimit, "steerInReverse" to steerInReverse,
        "guidanceLookAheadS" to guidanceLookAheadS, "remoteAutoSteer" to remoteAutoSteer, "constantContour" to constantContour,
        "hydLiftLookAhead" to hydLiftLookAhead, "isSprayer" to isSprayer, "headlandSeq" to headlandSeq,
        "supRtkLossS" to supRtkLossS, "supMaxXteM" to supMaxXteM, "supSteerErrDeg" to supSteerErrDeg,
        "supSteerErrS" to supSteerErrS, "supCorrAgeS" to supCorrAgeS, "supLoopGapS" to supLoopGapS,
        "hitchLength" to hitchLength, "toolTrailing" to toolTrailing, "trailingHitchLength" to trailingHitchLength,
        "toolTbt" to toolTbt, "tankHitchLength" to tankHitchLength, "trailingToolToPivot" to trailingToolToPivot, "articulated" to articulated,
        "numSections" to numSections, "lookAheadOn" to lookAheadOn, "lookAheadOff" to lookAheadOff, "turnOffDelay" to turnOffDelay, "minCoverage" to minCoverage,
        "slowSpeedCutoff" to slowSpeedCutoff, "sectionOffWhenOut" to sectionOffWhenOut,
        "sectionOffInHeadland" to sectionOffInHeadland, "useWorkSwitch" to useWorkSwitch,
        "workSwitchActiveLow" to workSwitchActiveLow, "workSwitchManualSections" to workSwitchManualSections,
        "steerWorkSwitch" to steerWorkSwitch, "steerWorkSwitchManualSections" to steerWorkSwitchManualSections,
        "youTurnRadius" to youTurnRadius, "youTurnSkip" to youTurnSkip, "youTurnFromBoundary" to youTurnFromBoundary,
        "algorithm" to algorithm.name, "lookAheadHold" to lookAheadHold, "lookAheadMult" to lookAheadMult,
        "acquireFactor" to acquireFactor, "purePursuitIntegral" to purePursuitIntegral,
        "stanleyDistanceGain" to stanleyDistanceGain, "stanleyHeadingGain" to stanleyHeadingGain,
        "stanleyIntegral" to stanleyIntegral, "sideHillComp" to sideHillComp,
        "rollZero" to rollZero, "rollFilter" to rollFilter, "invertRoll" to invertRoll, "fusionWeight" to fusionWeight,
        "headingFrom" to headingFrom.name, "reverseDetect" to reverseDetect, "minStepM" to minStepM, "minHeadingStepM" to minHeadingStepM,
        "forwardComp" to forwardComp, "reverseComp" to reverseComp,
        "autoSwitchDualFix" to autoSwitchDualFix, "autoSwitchDualFixSpeed" to autoSwitchDualFixSpeed, "dualAsImu" to dualAsImu, "dualReverseDistM" to dualReverseDistM,
        "gainP" to gainP, "highPwm" to highPwm, "lowPwm" to lowPwm, "minPwm" to minPwm,
        "countsPerDegree" to countsPerDegree, "wasOffset" to wasOffset, "ackerman" to ackerman,
        "invertWas" to invertWas, "invertRelays" to invertRelays, "invertMotor" to invertMotor,
        "singleInput" to singleInput, "cytron" to cytron, "steerEnable" to steerEnable.name,
        "encoder" to encoder, "pressureSensor" to pressureSensor, "currentSensor" to currentSensor,
        "danfoss" to danfoss, "axisY" to axisY, "maxPulseCounts" to maxPulseCounts,
    )

    companion object {
        /** Odczyt profilu; brakujące pola dostają wartości domyślne (zgodność ze starszymi plikami). Null = uszkodzony plik. */
        fun fromJson(text: String): VehicleProfile? {
            val m = MiniJson.parse(text) as? Map<*, *> ?: return null
            val d = VehicleProfile()
            fun num(k: String, def: Double) = (m[k] as? Double) ?: def
            fun int(k: String, def: Int) = (m[k] as? Double)?.toInt() ?: def
            fun bool(k: String, def: Boolean) = (m[k] as? Boolean) ?: def
            return VehicleProfile(
                wheelbase = num("wheelbase", d.wheelbase),
                trackWidth = num("trackWidth", d.trackWidth),
                antennaHeight = num("antennaHeight", d.antennaHeight),
                antennaPivot = num("antennaPivot", d.antennaPivot),
                antennaOffset = num("antennaOffset", d.antennaOffset),
                maxSteerAngle = num("maxSteerAngle", d.maxSteerAngle),
                maxSteerSpeed = num("maxSteerSpeed", d.maxSteerSpeed),
                minSteerSpeed = num("minSteerSpeed", d.minSteerSpeed),
                toolWidth = num("toolWidth", d.toolWidth),
                toolOverlap = num("toolOverlap", d.toolOverlap),
                toolOffset = num("toolOffset", d.toolOffset),
                sectionWidths = (m["sectionWidths"] as? String).orEmpty().split(';').mapNotNull { it.trim().toDoubleOrNull() },
                deadZoneDeg = num("deadZoneDeg", d.deadZoneDeg),
                deadZoneDelay = int("deadZoneDelay", d.deadZoneDelay),
                dualHeadingOffset = num("dualHeadingOffset", d.dualHeadingOffset),
                machineInvertRelays = bool("machineInvertRelays", d.machineInvertRelays),
                hydEnabled = bool("hydEnabled", d.hydEnabled),
                hydRaiseTime = int("hydRaiseTime", d.hydRaiseTime),
                hydLowerTime = int("hydLowerTime", d.hydLowerTime),
                machineUser1 = int("machineUser1", d.machineUser1),
                machineUser2 = int("machineUser2", d.machineUser2),
                machineUser3 = int("machineUser3", d.machineUser3),
                machineUser4 = int("machineUser4", d.machineUser4),
                tramEvery = int("tramEvery", d.tramEvery),
                tramTrack = num("tramTrack", d.tramTrack),
                tramWheel = num("tramWheel", d.tramWheel),
                tramShift = int("tramShift", d.tramShift),
                // stare profile: „co ile przejazdów” → szerokość opryskiwacza
                tramWidth = if (m.containsKey("tramWidth")) num("tramWidth", d.tramWidth)
                    else int("tramEvery", 0).let { e -> if (e > 0) e * (num("toolWidth", d.toolWidth) - num("toolOverlap", d.toolOverlap)) else 0.0 },
                tramPasses = int("tramPasses", d.tramPasses),
                tramStartPass = int("tramStartPass", d.tramStartPass),
                tramFlip = bool("tramFlip", d.tramFlip),
                tramMode = int("tramMode", d.tramMode),
                tramOuterInverted = bool("tramOuterInverted", d.tramOuterInverted),
                hydAuto = bool("hydAuto", d.hydAuto),
                rateBase = num("rateBase", d.rateBase),
                rateBaseSpeed = num("rateBaseSpeed", d.rateBaseSpeed),
                rateHintMode = int("rateHintMode", d.rateHintMode),
                rateStepPct = num("rateStepPct", d.rateStepPct),
                youTurnStyle = int("youTurnStyle", d.youTurnStyle),
                uTurnCompensation = num("uTurnCompensation", d.uTurnCompensation),
                youTurnExtension = num("youTurnExtension", d.youTurnExtension),
                uTurnSmoothing = int("uTurnSmoothing", d.uTurnSmoothing),
                functionSpeedLimit = num("functionSpeedLimit", d.functionSpeedLimit),
                steerInReverse = bool("steerInReverse", d.steerInReverse),
                guidanceLookAheadS = num("guidanceLookAheadS", d.guidanceLookAheadS),
                remoteAutoSteer = bool("remoteAutoSteer", d.remoteAutoSteer),
                constantContour = bool("constantContour", d.constantContour),
                hydLiftLookAhead = num("hydLiftLookAhead", d.hydLiftLookAhead),
                isSprayer = bool("isSprayer", d.isSprayer),
                headlandSeq = (m["headlandSeq"] as? String) ?: d.headlandSeq,
                supRtkLossS = num("supRtkLossS", d.supRtkLossS),
                supMaxXteM = num("supMaxXteM", d.supMaxXteM),
                supSteerErrDeg = num("supSteerErrDeg", d.supSteerErrDeg),
                supSteerErrS = num("supSteerErrS", d.supSteerErrS),
                supCorrAgeS = num("supCorrAgeS", d.supCorrAgeS),
                supLoopGapS = num("supLoopGapS", d.supLoopGapS),
                hitchLength = num("hitchLength", d.hitchLength),
                toolTrailing = bool("toolTrailing", d.toolTrailing),
                trailingHitchLength = num("trailingHitchLength", d.trailingHitchLength),
                toolTbt = bool("toolTbt", d.toolTbt),
                tankHitchLength = num("tankHitchLength", d.tankHitchLength),
                trailingToolToPivot = num("trailingToolToPivot", d.trailingToolToPivot),
                articulated = bool("articulated", d.articulated),
                numSections = int("numSections", d.numSections).coerceIn(1, 64),
                lookAheadOn = num("lookAheadOn", d.lookAheadOn),
                lookAheadOff = num("lookAheadOff", d.lookAheadOff),
                turnOffDelay = num("turnOffDelay", d.turnOffDelay),
                minCoverage = num("minCoverage", d.minCoverage.toDouble()).toInt().coerceIn(0, 100),
                slowSpeedCutoff = num("slowSpeedCutoff", d.slowSpeedCutoff),
                sectionOffWhenOut = bool("sectionOffWhenOut", d.sectionOffWhenOut),
                sectionOffInHeadland = bool("sectionOffInHeadland", d.sectionOffInHeadland),
                useWorkSwitch = bool("useWorkSwitch", d.useWorkSwitch),
                workSwitchActiveLow = bool("workSwitchActiveLow", d.workSwitchActiveLow),
                workSwitchManualSections = bool("workSwitchManualSections", d.workSwitchManualSections),
                steerWorkSwitch = bool("steerWorkSwitch", d.steerWorkSwitch),
                steerWorkSwitchManualSections = bool("steerWorkSwitchManualSections", d.steerWorkSwitchManualSections),
                youTurnRadius = num("youTurnRadius", d.youTurnRadius),
                youTurnSkip = int("youTurnSkip", d.youTurnSkip).coerceIn(1, 20),
                youTurnFromBoundary = num("youTurnFromBoundary", d.youTurnFromBoundary),
                algorithm = SteerAlgorithm.entries.firstOrNull { it.name == m["algorithm"] } ?: d.algorithm,
                lookAheadHold = num("lookAheadHold", d.lookAheadHold),
                lookAheadMult = num("lookAheadMult", d.lookAheadMult),
                acquireFactor = num("acquireFactor", d.acquireFactor),
                purePursuitIntegral = num("purePursuitIntegral", d.purePursuitIntegral),
                stanleyDistanceGain = num("stanleyDistanceGain", d.stanleyDistanceGain),
                stanleyHeadingGain = num("stanleyHeadingGain", d.stanleyHeadingGain),
                stanleyIntegral = num("stanleyIntegral", d.stanleyIntegral),
                sideHillComp = num("sideHillComp", d.sideHillComp),
                rollZero = num("rollZero", d.rollZero),
                rollFilter = num("rollFilter", d.rollFilter),
                invertRoll = bool("invertRoll", d.invertRoll),
                fusionWeight = num("fusionWeight", d.fusionWeight),
                headingFrom = HeadingFrom.entries.firstOrNull { it.name == m["headingFrom"] } ?: d.headingFrom,
                reverseDetect = bool("reverseDetect", d.reverseDetect),
                minStepM = num("minStepM", d.minStepM),
                minHeadingStepM = num("minHeadingStepM", d.minHeadingStepM),
                forwardComp = num("forwardComp", d.forwardComp),
                reverseComp = num("reverseComp", d.reverseComp),
                autoSwitchDualFix = bool("autoSwitchDualFix", d.autoSwitchDualFix),
                autoSwitchDualFixSpeed = num("autoSwitchDualFixSpeed", d.autoSwitchDualFixSpeed),
                dualAsImu = bool("dualAsImu", d.dualAsImu),
                dualReverseDistM = num("dualReverseDistM", d.dualReverseDistM),
                gainP = int("gainP", d.gainP),
                highPwm = int("highPwm", d.highPwm),
                lowPwm = int("lowPwm", d.lowPwm),
                minPwm = int("minPwm", d.minPwm),
                countsPerDegree = int("countsPerDegree", d.countsPerDegree),
                wasOffset = int("wasOffset", d.wasOffset),
                ackerman = int("ackerman", d.ackerman),
                invertWas = bool("invertWas", d.invertWas),
                invertRelays = bool("invertRelays", d.invertRelays),
                invertMotor = bool("invertMotor", d.invertMotor),
                singleInput = bool("singleInput", d.singleInput),
                cytron = bool("cytron", d.cytron),
                steerEnable = SteerEnable.entries.firstOrNull { it.name == m["steerEnable"] } ?: d.steerEnable,
                encoder = bool("encoder", d.encoder),
                pressureSensor = bool("pressureSensor", d.pressureSensor),
                currentSensor = bool("currentSensor", d.currentSensor),
                danfoss = bool("danfoss", d.danfoss),
                axisY = bool("axisY", d.axisY),
                maxPulseCounts = int("maxPulseCounts", d.maxPulseCounts),
            )
        }
    }
}
