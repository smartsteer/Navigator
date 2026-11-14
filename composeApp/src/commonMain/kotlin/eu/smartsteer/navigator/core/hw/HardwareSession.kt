package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.settings.HeadingFrom
import eu.smartsteer.navigator.core.settings.SteerEnable
import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.AutosteerStatus
import eu.smartsteer.navigator.core.aog.SteerConfig
import eu.smartsteer.navigator.core.aog.SteerData
import eu.smartsteer.navigator.core.aog.SteerSettings
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.gps.Gga
import eu.smartsteer.navigator.core.gps.Nmea
import eu.smartsteer.navigator.core.field.Coverage
import eu.smartsteer.navigator.core.field.FieldData
import eu.smartsteer.navigator.core.field.RateMap
import eu.smartsteer.navigator.core.field.FieldPlanner
import eu.smartsteer.navigator.core.field.Flag
import eu.smartsteer.navigator.core.field.Track
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.field.SectionMode
import eu.smartsteer.navigator.core.field.Sections
import eu.smartsteer.navigator.core.guidance.AbSteering
import eu.smartsteer.navigator.core.guidance.Contour
import eu.smartsteer.navigator.core.guidance.Curves
import eu.smartsteer.navigator.core.guidance.Pose
import eu.smartsteer.navigator.core.guidance.YouTurn
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Linia AB w płaszczyźnie lokalnej pola. Kurs AB w radianach (0 = północ). */
data class AbLine(val a: Vec2, val b: Vec2) {
    val heading: Double get() = atan2(b.x - a.x, b.y - a.y)
}

/** Rodzaj linii prowadzenia. */
enum class GuidanceMode(val label: String) { AB("AB"), CURVE("Krzywa"), CONTOUR("Kontur") }

/** Źródło kursu ciągnika — pokazywane na ekranie słowem. */
enum class HeadingSource(val label: String) { NONE("brak"), MOTION("z ruchu"), VTG("VTG"), DUAL("2 anteny"), IMU("IMU + GPS") }

/**
 * Tryb Sprzęt: pozycja z prawdziwego odbiornika, linia AB z przycisków, autoster na tej linii.
 * Czysta logika (bez sieci i UI) — sieć podaje tu odebrane datagramy, a co 100 ms pobiera ramkę PGN 254.
 */
class HardwareSession(profile: VehicleProfile = VehicleProfile()) {

    /** Ustawienia z profilu pojazdu; zmiana zeruje człony całkujące. */
    var profile: VehicleProfile = profile
        set(v) { if (v != field) { field = v; steering.reset() } }

    var plane: LocalPlane? = null; private set
    var lastGga: Gga? = null; private set
    var fix: FixQuality = FixQuality.NONE; private set
    /** Pozycja anteny i punktu sterowania (tylna oś) w metrach. */
    var antenna: Vec2? = null; private set
    var pivot: Vec2? = null; private set
    var headingRad: Double = 0.0; private set
    var headingSource = HeadingSource.NONE; private set
    var speedKmh = 0.0; private set
    var lastFixTimeS = Double.NEGATIVE_INFINITY; private set
    var autosteer: AutosteerStatus? = null; private set
    var lastAutosteerTimeS = Double.NEGATIVE_INFINITY; private set

    // --- linia i prowadzenie ---
    var pointA: Vec2? = null; private set
    var line: AbLine? = null; private set
    /** Numer przejazdu względem AB (0 = sama linia AB, +1 = jeden pas w prawo). */
    var passIndex = 0; private set
    var reversed = false; private set
    var lineShiftCm = 0
    var engaged = false; private set
    var xteCm = 0.0; private set
    var steerSetDeg = 0.0; private set
    var goal: Vec2? = null; private set
    var message: String? = null; private set

    private val steering = AbSteering()
    private var slowCounter = 0
    /** Człon całkujący prowadzenia, m (podgląd na ekranie Ustawienia). */
    val integral: Double get() = steering.integral

    // --- pole: granica, uwrocie, pokrycie, sekcje ---
    /** Nazwa otwartego pola (null = praca bez pola, nic się nie zapisuje). */
    var fieldName: String? = null; private set
    /** Id pola w WAR, jeśli pole przyszło z WAR. */
    var fieldWarId: String? = null; private set
    var boundary: List<Vec2>? = null; private set
    var headlandWidth = 0.0; private set
    /** Wnętrze pola bez uwrocia. */
    var headland: List<Vec2>? = null; private set
    var recordingBoundary: MutableList<Vec2>? = null; private set
    /** Z której strony ciągnika nagrywamy granicę: −1 = lewa krawędź maszyny, 0 = środek, 1 = prawa. */
    var boundarySide = 0
    val coverage = Coverage()
    val sections = Sections()

    // --- krzywa i kontur ---
    var mode = GuidanceMode.AB; private set
    /** Punkty nagrywanej właśnie krzywej (null = nie nagrywamy). */
    var recordingCurve: MutableList<Vec2>? = null; private set
    /** Krzywa odniesienia (przejazd 0). */
    var curveRef: List<Pose>? = null; private set
    /** Aktywny przejazd krzywej, skierowany zgodnie z jazdą. */
    var curvePath: List<Pose> = emptyList(); private set
    var curvePassIndex = 0; private set
    var curveReversed = false; private set
    private var curveShiftCm = 0
    private var curveSpacing = 0.0
    private var curveToolOffset = 0.0
    private var pathHint = -1
    val contour = Contour()
    private var prevContourPos: Vec2? = null
    private var contourBuiltS = Double.NEGATIVE_INFINITY

    /** Ślad jazdy (co ok. 0,5 m) do rysowania na mapie. */
    val trail = ArrayDeque<Vec2>()

    private var vtgCourse: Double? = null
    private var vtgTimeS = Double.NEGATIVE_INFINITY
    private var dualHeading: Double? = null
    private var dualTimeS = Double.NEGATIVE_INFINITY

    /** Żyroskop: przechył i kurs z PGN 253, PANDA/PAOGI albo zewnętrznego IMU (PGN 211). */
    val imu = Imu()
    /** Kurs ze źródeł (przed odwróceniem przodu/tyłu). */
    private var rawHeadingRad = 0.0

    /**
     * Dotknięcie ciągnika na mapie — jak w AgOpenGPS (GUI.Designer.cs, „Reset Direction”): kierunek liczony od nowa,
     * przód ustawia się przy następnym ruszeniu do przodu szybciej niż 1,5 km/h. Z dwiema antenami nic nie robi.
     */
    fun flipHeading() {
        if (headingSource == HeadingSource.DUAL) { ok("Kurs z dwóch anten — kierunku nie trzeba ustawiać"); return }
        resetDirection()
        engaged = false; pathHint = -1; steering.reset()
        ok("Kierunek od nowa — ruszaj do przodu szybciej niż 1,5 km/h")
    }

    /** Kasuje ustalony przód (punkty kursu, offset IMU, cofanie). */
    fun resetDirection() {
        stepFix.clear()
        lastReverseFix = null
        firstHeadingSet = false
        reversing = false; reverseWithImu = false; changingDirection = false; filteredDelta = 0.0
        imu.resetAlignment()
    }

    private fun norm(h: Double): Double { var r = h % (2 * PI); if (r < 0) r += 2 * PI; return r }

    /** Ostatnie przesunięcie anteny przez przechył, m (+ = w prawo) — do podglądu. */
    var rollCorrectionM = 0.0; private set

    /** Po ilu sekundach bez pozycji GPS uznajemy za utracony: 1 s (płytka/RTK, 10 Hz); GPS telefonu (1 Hz) — 3 s. */
    var gpsTimeoutS = GPS_TIMEOUT_S
    fun gpsAlive(nowS: Double) = nowS - lastFixTimeS <= gpsTimeoutS && fix != FixQuality.NONE
    fun autosteerAlive(nowS: Double) = nowS - lastAutosteerTimeS <= 1.0

    // ---------------- wejście ----------------

    /** Datagram z sieci modułów: NMEA (tekst, może zawierać kilka zdań) albo ramka PGN. */
    // ---------------- diagnostyka czujnika kąta (PGN 253) ----------------
    /** Kto nadaje PGN 253: adres i bajt źródła → liczba ramek i czas ostatniej. Więcej niż jedno źródło = dwa urządzenia mieszają kąt. */
    val wasSources = LinkedHashMap<String, Pair<Int, Double>>()
    /** Ostatnie odczyty kąta z płytki (czas, kąt, surowe bajty ramki) — do rozrzutu i pliku diagnostyki. */
    val wasSamples = ArrayDeque<Triple<Double, Double, String>>()

    /** Źródła PGN 253 aktywne w ostatnich 3 s. */
    fun activeWasSources(nowS: Double) = wasSources.filterValues { nowS - it.second < 3.0 }

    /** Rozrzut kąta kół w ostatnich 2 s (max − min), °; null = za mało odczytów. */
    fun wasSpread(nowS: Double): Double? {
        val v = wasSamples.filter { nowS - it.first < 2.0 }.map { it.second }
        return if (v.size < 5) null else v.max() - v.min()
    }

    /** Ramek PGN 253 na sekundę (z ostatnich 2 s). */
    fun wasRate(nowS: Double): Double = wasSamples.count { nowS - it.first < 2.0 } / 2.0

    fun onDatagram(bytes: ByteArray, length: Int, nowS: Double, from: String = "") {
        if (length <= 0) return
        if (bytes[0] == '$'.code.toByte()) {
            bytes.decodeToString(0, length).split('\n').forEach { onNmea(it.trim(), nowS) }
            return
        }
        val frame = AogFrame.parse(bytes, length) ?: return
        AutosteerStatus.from(frame)?.let {
            val key = "${from.ifEmpty { "?" }} (źródło 0x${frame.source.toString(16).uppercase()})"
            wasSources[key] = ((wasSources[key]?.first ?: 0) + 1) to nowS
            wasSamples.addLast(Triple(nowS, it.actualAngleDeg, bytes.take(length).joinToString(" ") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }))
            while (wasSamples.size > 200) wasSamples.removeFirst()
            val prevSwitch = autosteer?.steerSwitch
            val prevWork = autosteer?.workSwitch ?: false     // oldWorkSwitchHigh = false na starcie
            autosteer = it
            lastAutosteerTimeS = nowS
            // jak w AgOpenGPS: przełącznik / przycisk na płytce włącza i wyłącza autoster (gdy ustawione „Przełącznik” albo „Przycisk”)
            if (profile.remoteAutoSteer && profile.steerEnable != SteerEnable.NONE && prevSwitch != null && prevSwitch != it.steerSwitch) {
                if (it.steerSwitch) { if (!engaged && !freeDrive) setEngaged(true, nowS) }
                else if (engaged) { engaged = false; fail("Autoster wyłączony przełącznikiem") }
            }
            // CModuleComm.CheckWorkAndSteerSwitch: przełącznik roboczy i przełącznik autostera sterują przyciskami sekcji przy zmianie stanu
            if (profile.useWorkSwitch && prevWork != it.workSwitch) {
                sectionMasterFromSwitch(it.workSwitch != profile.workSwitchActiveLow, profile.workSwitchManualSections)
            }
            if (profile.steerWorkSwitch && prevSwitch != null && prevSwitch != it.steerSwitch) {
                val auto = profile.remoteAutoSteer && profile.steerEnable != SteerEnable.NONE
                sectionMasterFromSwitch((engaged && auto) || (!auto && it.steerSwitch), profile.steerWorkSwitchManualSections)
            }
            lastAutosteerTimeS = nowS
            it.headingDeg?.let { h -> imu.onHeading(h, nowS) }
            it.rollDeg?.let { r -> imu.onRoll(r, filtered = true, profile, nowS) }
            return
        }
        val d = frame.data
        when (frame.pgn) {
            // Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/UDPComm.Designer.cs (0xD3, 0xD4)
            PGN_EXTERNAL_IMU -> if (d.size >= 6) {
                imu.onHeading(int16(d, 0) * 0.1, nowS)
                imu.onRoll(int16(d, 2) * 0.1, filtered = true, profile, nowS)
            }
            PGN_IMU_DISCONNECT -> if (d.isNotEmpty() && d[0].toInt() == 1) imu.disconnect()
        }
    }

    private fun int16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    /** pn.AverageTheSpeed (AgOpenGPS, CNMEA.cs): avgSpeed = 0,75 × poprzednia + 0,25 × nowa. */
    private fun averageSpeed(raw: Double) { speedKmh = speedKmh * 0.75 + raw * 0.25 }

    fun onNmea(sentence: String, nowS: Double) {
        if (sentence.length < 6) return
        val type = sentence.substring(3, 6)
        when {
            sentence.startsWith("\$PANDA") || sentence.startsWith("\$PAOGI") -> Nmea.parsePanda(sentence)?.let { p ->
                averageSpeed(p.speedKmh)
                if (p.dualAntenna && p.headingDeg != null) { dualHeading = (p.headingDeg + profile.dualHeadingOffset) * PI / 180; dualTimeS = nowS }
                if (!p.dualAntenna) p.headingDeg?.let { imu.onHeading(it, nowS) }
                p.rollDeg?.let { imu.onRoll(it, filtered = !p.dualAntenna, profile, nowS) }
                onPosition(p.gga, nowS)
            }
            type == "GGA" -> Nmea.parseGga(sentence)?.let { onPosition(it, nowS) }
            type == "VTG" -> Nmea.parseVtg(sentence)?.let { v ->
                averageSpeed(v.speedKmh)
                vtgCourse = v.courseDeg?.let { it * PI / 180 }
                vtgTimeS = nowS
            }
            type == "GST" -> Nmea.parseGstHorizontal(sentence)?.let { gstAccuracyM = it; gstTimeS = nowS }
            type == "HDT" -> Nmea.parseHdt(sentence)?.let { dualHeading = (it.headingDeg + profile.dualHeadingOffset) * PI / 180; dualTimeS = nowS }
        }
    }

    /** Częstotliwość pozycji GPS, Hz (gpsHz w AgOpenGPS). */
    var gpsHz = 10.0; private set

    private fun onPosition(g: Gga, nowS: Double) {
        lastGga = g
        fix = g.fix
        if (g.fix == FixQuality.NONE) return
        val pl = plane ?: LocalPlane(g.latitude, g.longitude).also { plane = it }
        val p = pl.toLocal(g.latitude, g.longitude)
        antenna = p
        // gpsHz jak w AgOpenGPS (UpdateFixPosition): 1 / czas od poprzedniej pozycji, 3–70 Hz, wygładzone 0,98/0,02
        if (lastFixTimeS.isFinite() && nowS > lastFixTimeS) gpsHz = 0.98 * gpsHz + 0.02 * (1 / (nowS - lastFixTimeS)).coerceIn(3.0, 70.0)
        lastFixTimeS = nowS
        updateHeading(p, nowS)
        // antena → środek ciągnika (przesunięcie w bok, + = antena po lewej; przechył na zboczu) → tylna oś
        rollCorrectionM = imu.rollCorrection(profile, nowS)
        val center = p + Vec2.right(headingRad) * (profile.antennaOffset + rollCorrectionM)
        val pv = center - Vec2.forward(headingRad) * profile.antennaPivot
        pivot = pv
        if (trail.isEmpty() || trail.last().distanceTo(pv) >= 0.5) {
            trail.addLast(pv)
            while (trail.size > 4000) trail.removeFirst()
        }
        // sectionTriggerStepDistance (Position.designer.cs): krótszy krok na zakręcie, 0,7–5 m, przy nagrywaniu krzywej połowa
        val tw = profile.toolWidth
        val twistRaw = if (sections.farLeftSpeed < sections.farRightSpeed) sections.farLeftSpeed * (tw / 50) / sections.farRightSpeed * (50 / tw)
            else sections.farRightSpeed * (tw / 50) / sections.farLeftSpeed * (50 / tw)
        val twist = (if (twistRaw.isNaN()) 1.0 else twistRaw).let { it * it }.coerceAtLeast(0.1)
        var stepDist = (minOf(tw * 0.75, 5.0) * twist).coerceAtLeast(0.7)
        if (recordingCurve != null) stepDist *= 0.5
        // AddSectionOrPathPoints: punkt krzywej co krok
        recordingCurve?.let { r -> if (r.isEmpty() || r.last().distanceTo(pv) > stepDist) r.add(pv) }
        // AddContourPoints: co (szerokość − zakładka)/3 albo krok; nagrywanie tylko gdy maluje choć jedna sekcja
        val contourWidth = (profile.toolWidth - profile.toolOverlap) / 3.0
        val lastC = prevContourPos
        if (lastC == null || lastC.distanceTo(pv) > contourWidth || lastC.distanceTo(pv) > stepDist) {
            contour.record(Pose(pv, headingRad), working = sections.mapping.any { it }, toolOffset = profile.toolOffset)
            if (mode == GuidanceMode.CONTOUR && nowS - contourBuiltS >= (if (contour.guide.isEmpty()) 0.3 else 2.0)) {
                contour.build(pv, headingRad, profile.toolWidth, profile.toolOverlap, autosteerOn = engaged, toolOffset = profile.toolOffset)
                contourBuiltS = nowS
            }
            prevContourPos = pv
        }
        recordingBoundary?.let { r ->
            val q = pv + Vec2.right(headingRad) * (boundarySide * profile.toolWidth / 2)
            if (r.isEmpty() || r.last().distanceTo(q) >= 1.0) r.add(q)
        }
        recordingObstacle?.let { r -> if (r.isEmpty() || r.last().distanceTo(pv) >= 0.5) r.add(pv) }
        autosteer?.let { wasCal.sample(pv, headingRad, it.actualAngleDeg, speedKmh) }
        sections.updateTool(pv, headingRad, profile, autosteer?.actualAngleDeg ?: 0.0)
        // słaby GPS (np. telefon w budynku, ±20 m): pozycja skacze, a mapa malowałaby obrobione „gwiazdy” — sekcje czekają
        gpsTooPoorForSections = (horizontalAccuracy(nowS)?.first ?: 0.0) > POOR_GPS_SECTIONS_M
        // dziennik powodów, dla których sekcje stoją (do pliku diagnostyki)
        val why = when {
            sections.mode == eu.smartsteer.navigator.core.field.SectionMode.OFF -> "sekcje WYŁ."
            gpsTooPoorForSections -> "słaby GPS"
            speedKmh < profile.slowSpeedCutoff -> "prędkość ${(speedKmh * 10).roundToInt() / 10.0} < ${profile.slowSpeedCutoff} km/h"
            reversing -> "cofanie (sekcje gasną, gdy jadą do tyłu)"
            sections.mode == SectionMode.MANUAL -> "sekcje ręcznie"
            else -> "pracują"
        }
        if (why != lastSectionWhy) {
            lastSectionWhy = why
            sectionLog.addLast("${(nowS * 10).roundToInt() / 10.0}s ${(speedKmh * 10).roundToInt() / 10.0} km/h: $why")
            while (sectionLog.size > 60) sectionLog.removeFirst()
        }
        sections.update(profile, speedKmh, coverage, boundary, headland, workSwitchOn(nowS), obstacles, othersSameWork.takeIf { it.cellCount > 0 }, gpsOk = !gpsTooPoorForSections, gpsHz = gpsHz)
        hydStep()
        // mapa wykonania: szerokość pracujących sekcji i dawka z mapy
        if (sections.anyOn()) {
            val w = profile.sectionWidthsEffective.withIndex().sumOf { (i, sw) -> if (i < Sections.MAX && sections.on[i]) sw else 0.0 }
            sections.toolPos?.let { tp -> applied.record(tp, eu.smartsteer.navigator.core.war.nowMillis() / 1000, speedKmh, w, rateNow()?.first) }
        }
    }

    /**
     * Kurs: dwie anteny > IMU skorygowane GPS-em > VTG (przy ruchu) > z kolejnych pozycji.
     * Z IMU kurs jest stabilny także na postoju i przy wolnej jeździe.
     */
    private fun updateHeading(p: Vec2, nowS: Double) {
        rawHeading(p, nowS)
        headingRad = rawHeadingRad
    }

    /**
     * Jazda do tyłu: kurs z GPS (kierunek ruchu) jest wtedy odwrotny do kursu ciągnika. Ciągnik nie obraca się
     * w miejscu o 180°, więc nagły przeskok kursu ruchu o ponad 120° przy małej prędkości = zmiana kierunku jazdy
     * (jak wykrywanie cofania w AgOpenGPS, Position.designer.cs). Z IMU / dwiema antenami porównujemy wprost.
     */
    var reversing = false; private set

    private fun angDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return abs(d)
    }

    private fun rawHeading(p: Vec2, nowS: Double) {
        val dual = dualHeading?.takeIf { nowS - dualTimeS < 1.0 }
        // dwie anteny jako IMU (AgOpenGPS: isDualAsIMU → ahrs.imuHeading = headingTrueDual)
        val autoFix = dual != null && profile.autoSwitchDualFix && abs(speedKmh) > profile.autoSwitchDualFixSpeed
        if (dual != null && (profile.dualAsImu || autoFix)) imu.onHeading(dual * 180 / PI, nowS)
        // kurs z dwóch anten: ustawiony w profilu albo (bez wyboru) gdy odbiornik je podaje
        if (dual != null && !autoFix && profile.headingFrom != HeadingFrom.VTG) { dualAntennaHeading(dual, p); return }
        when (profile.headingFrom) {
            HeadingFrom.FIX, HeadingFrom.DUAL -> fixHeading(p, nowS)
            HeadingFrom.VTG -> vtgHeading(nowS)
        }
    }

    // --- kurs jak w AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), SourceCode/GPS/Forms/Position.designer.cs ---

    /** Ostatnie pozycje anteny użyte do kursu, [0] = najnowsza (stepFixPts). */
    private val stepFix = ArrayDeque<Vec2>()
    private var firstHeadingSet = false
    /** Ostatni kurs z GPS (gpsHeading), rad. */
    private var gpsHeadingRad = 0.0
    private var filteredDelta = 0.0
    var changingDirection = false; private set
    private var reverseWithImu = false

    private fun imuOn(nowS: Double) = imu.headingAlive(nowS) && profile.fusionWeight > 0

    /** byPass: na postoju i między krokami — kurs z IMU (gdy jest), inaczej ostatni. */
    private fun headingFromImuOnly(nowS: Double) {
        if (imuOn(nowS)) { rawHeadingRad = imu.corrected(); headingSource = HeadingSource.IMU }
    }

    private fun pushStep(p: Vec2) {
        stepFix.addFirst(p)
        while (stepFix.size > TOTAL_FIX_STEPS) stepFix.removeLast()
    }

    /** Źródło „Fix”: kurs z kolejnych pozycji anteny. [antenna] = pozycja po poprawce przesunięcia anteny i przechyłu. */
    private fun fixHeading(antenna: Vec2, nowS: Double) {
        // poprawki jak w AgOpenGPS: przesunięcie anteny w bok i przechył — z poprzednim kursem
        val roll = imu.rollCorrection(profile, nowS)
        val fix = antenna + Vec2.right(gpsHeadingRad) * (profile.antennaOffset + roll)
        if (!firstHeadingSet) {
            if (abs(speedKmh) < 1.5) { headingFromImuOnly(nowS); return }
            pushStep(fix)
            if (stepFix.size < 3) { headingFromImuOnly(nowS); return }
            gpsHeadingRad = norm(atan2(fix.x - stepFix[2].x, fix.y - stepFix[2].y))
            rawHeadingRad = gpsHeadingRad
            headingSource = HeadingSource.MOTION
            if (imu.headingDeg != null && imu.headingAlive(nowS)) {
                imu.align(gpsHeadingRad)
                if (profile.fusionWeight > 0) { rawHeadingRad = imu.corrected(); headingSource = HeadingSource.IMU }
            }
            reversing = false
            firstHeadingSet = true
            ok("Przód ustawiony")
            return
        }
        val last = stepFix.firstOrNull()
        if (last == null) { pushStep(fix); return }
        if (last.distanceTo(fix) < profile.minStepM) { headingFromImuOnly(nowS); return }
        val minSq = profile.minHeadingStepM * profile.minHeadingStepM
        var idx = 0
        var dSq = 0.0
        for (i in stepFix.indices) {
            idx = i
            dSq = Curves.d2(stepFix[i], fix)
            if (dSq > minSq) break
        }
        if (dSq < minSq * 0.5) { headingFromImuOnly(nowS); return }
        var h = norm(atan2(fix.x - stepFix[idx].x, fix.y - stepFix[idx].y))
        val steer = autosteer?.actualAngleDeg ?: 0.0
        if (imuOn(nowS)) {
            changingDirection = false
            if (profile.reverseDetect) {
                val delta = abs(PI - abs(abs(h - imu.corrected()) - PI))
                if (delta > 1.57) { reversing = true; h = norm(h + PI); reverseWithImu = true }
                else { reversing = false; reverseWithImu = false }
            } else reversing = false
            h = norm(h - (profile.antennaPivot * steer * (if (reversing) profile.reverseComp else profile.forwardComp)) * PI / 180)
            gpsHeadingRad = h
            rawHeadingRad = imu.fuse(h, if (reverseWithImu) 0.02 else profile.fusionWeight)
            headingSource = HeadingSource.IMU
        } else {
            if (profile.reverseDetect) {
                val delta = abs(PI - abs(abs(h - gpsHeadingRad) - PI))
                filteredDelta = delta * 0.2 + filteredDelta * 0.8
                changingDirection = abs(filteredDelta - delta) > 0.5
                if (changingDirection) return // nie wiadomo, czy zmienia kierunek — czekamy
                if (filteredDelta > 1.57) { reversing = true; h = norm(h + PI) } else reversing = false
                h = norm(h - (profile.antennaPivot * steer * (if (reversing) profile.reverseComp else profile.forwardComp)) * PI / 180)
            } else { reversing = false; changingDirection = false }
            gpsHeadingRad = h
            rawHeadingRad = h
            headingSource = HeadingSource.MOTION
        }
        pushStep(fix)
    }

    private var lastReverseFix: Vec2? = null

    /** Źródło „Dual” (AgOpenGPS, case "Dual"): kurs z dwóch anten, cofanie z ruchu co [VehicleProfile.dualReverseDistM] m. */
    private fun dualAntennaHeading(dual: Double, antenna: Vec2) {
        firstHeadingSet = true
        changingDirection = false
        rawHeadingRad = norm(dual); gpsHeadingRad = rawHeadingRad
        headingSource = HeadingSource.DUAL
        val fix = antenna + Vec2.right(rawHeadingRad) * profile.antennaOffset
        val last = lastReverseFix
        if (last == null) lastReverseFix = fix
        else if (last.distanceTo(fix) > profile.dualReverseDistM) {
            val nh = norm(atan2(fix.x - last.x, fix.y - last.y))
            val delta = abs(PI - abs(abs(nh - rawHeadingRad) - PI))
            reversing = delta > 2
            lastReverseFix = fix
        }
        pushStep(fix) // płynne przejście na kurs z pozycji (autoSwitchDualFix)
    }

    /** Źródło „VTG”: kurs z odbiornika przy prędkości > 1 km/h, z IMU korygowanym jak w AgOpenGPS (bez wykrywania cofania). */
    private fun vtgHeading(nowS: Double) {
        firstHeadingSet = true
        reversing = false
        val v = vtgCourse
        if (speedKmh > 1 && v != null && nowS - vtgTimeS < 1.0) {
            gpsHeadingRad = norm(v); rawHeadingRad = gpsHeadingRad; headingSource = HeadingSource.VTG
        }
        if (imu.headingDeg != null && imu.headingAlive(nowS)) {
            rawHeadingRad = imu.fuseVtg(gpsHeadingRad)
            headingSource = HeadingSource.IMU
        }
    }

    // ---------------- linia AB ----------------

    fun markA(): Boolean {
        val p = pivot ?: return fail("Brak pozycji GPS — nie można zapisać punktu A")
        pointA = p
        line = null
        engaged = false
        return ok("Punkt A zapisany. Przejedź co najmniej 20 m i naciśnij B")
    }

    fun markB(): Boolean {
        val a = pointA ?: return fail("Najpierw naciśnij A")
        val b = pivot ?: return fail("Brak pozycji GPS — nie można zapisać punktu B")
        if (a.distanceTo(b) < MIN_AB_M) return fail("Za blisko punktu A (${a.distanceTo(b).roundToInt()} m). Potrzeba co najmniej ${MIN_AB_M.roundToInt()} m")
        line = AbLine(a, b)
        passIndex = 0
        addTrack(Track(nextName("AB"), line, null))
        return ok("Linia AB gotowa")
    }

    fun clearLine() {
        val t = tracks.getOrNull(activeTrack)
        if (t != null && !t.isCurve) { tracks.removeAt(activeTrack); activeTrack = -1 }
        pointA = null
        line = null
        engaged = false
        ok("Linia usunięta")
    }

    /**
     * Linia A+ (jak w AgOpenGPS): od miejsca, gdzie stoi ciągnik, w kierunku jazdy albo w podanym kursie —
     * bez przejeżdżania 20 m.
     */
    fun markAPlus(headingDeg: Double? = null): Boolean {
        val p = pivot ?: return fail("Brak pozycji GPS — nie można utworzyć linii")
        val h = headingDeg?.let { it * PI / 180 } ?: headingRad
        val l = AbLine(p, p + Vec2.forward(h) * 50.0)
        line = l; pointA = p; passIndex = 0; engaged = false
        if (mode != GuidanceMode.AB) mode = GuidanceMode.AB
        val deg = ((h * 180 / PI) % 360 + 360) % 360
        addTrack(Track("A+ ${deg.roundToInt()}°", l, null))
        return ok("Linia A+ gotowa (kurs ${deg.roundToInt()}°)")
    }

    /** Przesuwa linię AB tak, żeby bieżący przejazd szedł dokładnie przez ciągnik (bez zmiany kierunku). */
    fun snapToTractor(): Boolean {
        val l = line ?: return fail("Najpierw utwórz linię AB")
        val p = pivot ?: return fail("Brak pozycji GPS")
        val right = Vec2.right(l.heading)
        val w = profile.passSpacing
        val d = (p - l.a) dot right
        // jak w activeSegment: przesunięcie linii/maszyny działa w prawo względem kierunku jazdy
        val side = (if (reversed) -1.0 else 1.0) * (lineShiftCm / 100.0 - profile.toolOffset)
        val k = kotlin.math.round((d - side) / w)
        val off = d - k * w - side
        val nl = AbLine(l.a + right * off, l.b + right * off)
        line = nl; pointA = nl.a
        if (activeTrack in tracks.indices) tracks[activeTrack] = tracks[activeTrack].copy(ab = nl)
        return ok("Linia przesunięta o ${(off * 100).roundToInt()} cm — przejazd idzie przez ciągnik")
    }

    // ---------------- lista linii pola ----------------
    /** Wszystkie linie pola (AB, A+, krzywe, plan). */
    val tracks = ArrayList<Track>()
    var activeTrack = -1; private set

    private fun nextName(prefix: String): String {
        var k = 1
        while (tracks.any { it.name == "$prefix $k" }) k++
        return "$prefix $k"
    }

    /** Dodaje linię do listy i ją uaktywnia. */
    fun addTrack(t: Track) {
        tracks.add(t)
        activeTrack = tracks.size - 1
    }

    /** Wybór linii z listy: ustawia tryb (AB / krzywa) i linię. */
    fun selectTrack(i: Int): Boolean {
        val t = tracks.getOrNull(i) ?: return false
        engaged = false
        activeTrack = i
        val ab = t.ab
        val c = t.curve
        if (ab != null) {
            mode = GuidanceMode.AB
            restoreLine(ab)
        } else if (c != null && c.size >= 2) {
            mode = GuidanceMode.CURVE
            restoreCurve(Curves.headings(c))
        }
        pathHint = -1; steering.reset()
        return ok("Linia „${t.name}”")
    }

    fun deleteTrack(i: Int) {
        if (i !in tracks.indices) return
        val t = tracks.removeAt(i)
        if (i == activeTrack) {
            activeTrack = -1
            if (t.isCurve) { curveRef = null; curvePath = emptyList(); curList = emptyList() } else { line = null; pointA = null }
            engaged = false
        } else if (i < activeTrack) activeTrack--
        ok("Usunięto „${t.name}”")
    }

    /** Pokrycie innych maszyn na otwartym polu (z WAR) i ich nazwy. */
    val othersCoverage = Coverage()
    var othersMachines: Map<String, String> = emptyMap()
    /** Komórki pracy innych maszyn do rysowania (narożniki) i licznik zmian. */
    var othersCells: Map<String, List<List<Vec2>>> = emptyMap()
    var othersVersion = 0; private set
    /** Kolor śladu każdej z innych maszyn („#RRGGBB”, pusty = domyślny). */
    var othersColors: Map<String, String> = emptyMap()

    /** Pokrycie innych maszyn robiących ten sam rodzaj pracy — tylko to omijają sekcje. */
    val othersSameWork = Coverage()
    private val othersGrids = HashMap<String, Pair<eu.smartsteer.navigator.core.war.CoverageGrid, String>>()
    /** Rodzaj pracy każdej z innych maszyn. */
    var othersWork: Map<String, String> = emptyMap(); private set

    /** Pokrycie innej maszyny z WAR (cała jej dotychczasowa praca na tym polu). */
    fun addOthersCoverage(machine: String, name: String, g: eu.smartsteer.navigator.core.war.CoverageGrid, color: String = "", work: String = "", sameWork: Boolean = false) {
        val pl = plane ?: return
        othersGrids[machine] = g to work
        othersWork = othersWork + (machine to work)
        if (sameWork) g.paintInto(othersSameWork, pl)
        val quads = g.paintInto(othersCoverage, pl)
        othersMachines = othersMachines + (machine to name)
        othersColors = othersColors + (machine to color)
        othersCells = othersCells + (machine to quads)
        othersVersion++
    }

    /** Po zmianie rodzaju pracy tej maszyny: na nowo, które cudze pokrycia omijają sekcje. */
    fun setOthersForSections(same: (String) -> Boolean) {
        val pl = plane ?: return
        othersSameWork.clear()
        for ((g, w) in othersGrids.values) if (same(w)) g.paintInto(othersSameWork, pl)
    }

    /** Mapa wykonania otwartego pola. */
    val applied = eu.smartsteer.navigator.core.field.AppliedLog()

    // ---------------- mapa dawek ----------------
    var rateMap: RateMap? = null

    /** Dawka docelowa tam, gdzie za chwilę będzie maszyna (wyprzedzenie włączania sekcji) i numer strefy. */
    fun rateNow(): Pair<Double, Int>? {
        val m = rateMap ?: return null
        val tp = sections.toolPos ?: return null
        val q = tp + Vec2.forward(sections.toolHeading) * (speedKmh / 3.6 * profile.lookAheadOn)
        for (i in m.zones.indices.reversed()) if (Polygon.contains(m.zones[i].poly, q)) return m.zones[i].rate to i
        return m.defaultRate to -1
    }

    // dokładność pozycji: z GST (odbiornik / telefon), inaczej szacunek z rodzaju poprawki i HDOP
    private var gstAccuracyM: Double? = null
    private var gstTimeS = -1e9

    /** Błąd poziomy w metrach i czy to pomiar (GST), czy szacunek; null — brak pozycji. */
    fun horizontalAccuracy(nowS: Double): Pair<Double, Boolean>? {
        if (!gpsAlive(nowS)) return null
        gstAccuracyM?.let { if (nowS - gstTimeS < 3.0) return it to true }
        val hdop = lastGga?.hdop?.takeIf { it < 50 } ?: 2.0
        return when (fix) {
            FixQuality.RTK_FIX -> 0.02
            FixQuality.RTK_FLOAT -> 0.3
            FixQuality.DGPS -> 0.7 * hdop
            else -> 2.5 * hdop
        } to false
    }

    /** Dokładność gorsza niż [POOR_GPS_SECTIONS_M] — sekcje nie pracują i nic się nie maluje. */
    var gpsTooPoorForSections = false
        private set

    /** Gdy ustawione (np. GPS z telefonu, 2–5 m) — autostera nie da się włączyć, a przycisk znika z mapy. */
    var steerBlockedReason: String? = null

    // ---------------- czujnik przeszkód (core/safety, docs/PRZESZKODY.md) ----------------
    val obstacleGuard = eu.smartsteer.navigator.core.safety.ObstacleGuard()
    /** Ostatnia paczka z czujnika (ustawia kontroler po odebraniu). */
    var obstacleFrame: eu.smartsteer.navigator.core.safety.ObstacleFrame? = null
    var obstacleVerdict: eu.smartsteer.navigator.core.safety.ObstacleVerdict? = null; private set
    /** Szerokość pasa, który maszyna zajmuje: szersze z ciągnika (rozstaw kół + opony) i narzędzia. */
    val machineWidthM: Double get() = kotlin.math.max(profile.trackWidth + 0.5, profile.toolWidth)

    private fun obstacleStep(nowS: Double) {
        if (!obstacleGuard.config.enabled) { obstacleVerdict = null; return }
        val v = obstacleGuard.check(obstacleFrame, nowS, speedKmh, machineWidthM)
        obstacleVerdict = v
        if (v.level == 2 && engaged) trip("${v.message} — autoster wyłączony")
    }

    /** PGN 225 dla modułu bezpieczeństwa (gdy czujnik włączony). */
    fun safetyFrame(): ByteArray? = obstacleVerdict?.let { eu.smartsteer.navigator.core.safety.ObstacleGuard.frame(it) }

    /** PGN 224 z dawką (tylko gdy jest mapa dawek). */
    fun rateFrame(): ByteArray? {
        val (r, z) = rateNow() ?: return null
        return RateMap.frame(r, z, sections.anyOn())
    }

    // ---------------- linie z granicy ----------------
    /**
     * Linia AB wzdłuż najbliższego boku pola (dłuższego niż 20 m), odsunięta do środka o pół szerokości maszyny —
     * pierwszy przejazd idzie tuż przy granicy.
     */
    fun abFromBoundary(): Boolean {
        val b = boundary ?: return fail("Najpierw granica pola")
        val p = pivot ?: return fail("Brak pozycji GPS")
        val half = profile.passSpacing / 2
        var best: Pair<Vec2, Vec2>? = null
        var bestD = Double.MAX_VALUE
        for (i in b.indices) {
            val a = b[i]; val c = b[(i + 1) % b.size]
            if (a.distanceTo(c) < 20.0) continue
            val d = distToSegment(p, a, c)
            if (d < bestD) { bestD = d; best = a to c }
        }
        val (a, c) = best ?: return fail("Granica nie ma boku dłuższego niż 20 m")
        val dir = c - a
        val h = atan2(dir.x, dir.y)
        var n = Vec2.right(h) * half
        val mid = (a + c) * 0.5
        if (!Polygon.contains(b, mid + n)) n = n * -1.0
        val l = AbLine(a + n, c + n)
        engaged = false
        mode = GuidanceMode.AB
        restoreLine(l)
        val deg = ((h * 180 / PI) % 360 + 360) % 360
        addTrack(Track("Bok pola ${deg.roundToInt()}°", l, null))
        pathHint = -1; steering.reset()
        return ok("Linia wzdłuż boku pola (${a.distanceTo(c).roundToInt()} m)")
    }

    /**
     * Uwrocie: zamknięta krzywa dookoła pola pół szerokości od granicy. Kolejne przejazdy uwrocia (do środka)
     * to kolejne przejazdy tej krzywej — jak „Headland” w AgOpenGPS.
     */
    fun headlandTrack(): Boolean {
        val b = boundary ?: return fail("Najpierw granica pola")
        val inner = Polygon.inset(b, profile.passSpacing / 2)
        if (inner.size < 3) return fail("Pole za małe na uwrocie tą maszyną")
        // zamknięcie pętli: dokładamy początek, żeby prowadzenie nie kończyło się w rogu startu
        val loop = ArrayList(inner)
        var acc = 0.0
        var i = 0
        loop.add(inner[0])
        while (acc < 20.0 && i < inner.size - 1) { acc += inner[i].distanceTo(inner[i + 1]); loop.add(inner[i + 1]); i++ }
        val dense = Curves.densify(loop, 1.0)
        engaged = false
        mode = GuidanceMode.CURVE
        restoreCurve(Curves.headings(dense))
        addTrack(Track("Uwrocie dookoła", null, dense))
        return ok("Uwrocie: jedź dookoła pola, kolejne okrążenia do środka")
    }

    private fun distToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val l2 = ab dot ab
        val t = if (l2 < 1e-9) 0.0 else (((p - a) dot ab) / l2).coerceIn(0.0, 1.0)
        return p.distanceTo(a + ab * t)
    }

    // ---------------- plan przejazdów ----------------
    /** Kierunki przejazdów z najmniejszą liczbą przejazdów (wnętrze pola bez uwrocia). */
    fun planPasses(): List<FieldPlanner.Plan> {
        val area = headland ?: boundary ?: return emptyList()
        return FieldPlanner.plan(area, profile.passSpacing)
    }

    fun applyPlan(plan: FieldPlanner.Plan): Boolean {
        engaged = false
        mode = GuidanceMode.AB
        restoreLine(plan.line)
        addTrack(Track("Plan ${plan.headingDeg}° · ${plan.passes} przejazdów", plan.line, null))
        pathHint = -1; steering.reset()
        return ok("Plan: ${plan.passes} przejazdów w kierunku ${plan.headingDeg}°")
    }

    // ---------------- znaczniki i przeszkody ----------------
    val flags = ArrayList<Flag>()
    val obstacles = ArrayList<List<Vec2>>()
    /** Nagrywana przeszkoda (objazd dookoła). */
    var recordingObstacle: MutableList<Vec2>? = null; private set

    fun addFlag(color: Int): Boolean {
        val p = pivot ?: return fail("Brak pozycji GPS")
        flags.add(Flag(p, color))
        return ok("Znacznik ${flags.size} postawiony")
    }

    fun removeNearestFlag(): Boolean {
        val p = pivot ?: return fail("Brak pozycji GPS")
        val i = flags.indices.minByOrNull { flags[it].p.distanceTo(p) } ?: return fail("Brak znaczników")
        flags.removeAt(i)
        return ok("Znacznik usunięty")
    }

    fun startObstacle(): Boolean {
        if (pivot == null) return fail("Brak pozycji GPS")
        recordingObstacle = ArrayList()
        return ok("Objedź przeszkodę dookoła i naciśnij „Zakończ”")
    }

    fun finishObstacle(): Boolean {
        val r = recordingObstacle ?: return fail("Nagrywanie nie trwa")
        recordingObstacle = null
        val poly = Polygon.simplify(r)
        if (poly.size < 3 || Polygon.area(poly) < 4.0) return fail("Za mała przeszkoda — objedź ją dookoła")
        obstacles.add(poly)
        return ok("Przeszkoda zapisana (${Polygon.area(poly).roundToInt()} m²)")
    }

    fun cancelObstacle() { recordingObstacle = null; ok("Nagrywanie przeszkody przerwane") }

    fun removeNearestObstacle(): Boolean {
        val p = pivot ?: return fail("Brak pozycji GPS")
        val i = obstacles.indices.minByOrNull { k -> obstacles[k].minOf { it.distanceTo(p) } } ?: return fail("Brak przeszkód")
        obstacles.removeAt(i)
        return ok("Przeszkoda usunięta")
    }

    fun restoreLine(l: AbLine) { line = l; pointA = l.a; passIndex = 0 }

    // ---------------- autoster ----------------

    /** Zwraca komunikat, dlaczego nie można włączyć, albo null, gdy włączono. */
    /** Kiedy rolnik ostatnio dotknął przycisku autosteru (do rozróżnienia dźwięku: wyłączył sam / wyłączył się). */
    var lastUserToggleS = Double.NEGATIVE_INFINITY; private set

    fun setEngaged(on: Boolean, nowS: Double): String? {
        lastUserToggleS = nowS
        if (!on) { engaged = false; return null }
        val why = when {
            steerBlockedReason != null -> steerBlockedReason
            mode == GuidanceMode.AB && line == null -> "Najpierw utwórz linię AB"
            mode == GuidanceMode.CURVE && curveRef == null -> "Najpierw nagraj krzywą"
            mode == GuidanceMode.CONTOUR && contour.guide.isEmpty() -> "Brak wcześniejszego przejazdu obok — kontur nie ma czego się trzymać"
            !gpsAlive(nowS) -> "Brak pozycji GPS"
            !autosteerAlive(nowS) -> "Brak odpowiedzi modułu autosterowania (PGN 253)"
            profile.steerEnable == SteerEnable.SWITCH && autosteer?.steerSwitch == false -> "Włącz przełącznik autosteru — przy wyłączonym płytka nie da prądu na silnik"
            obstacleGuard.config.enabled && obstacleGuard.check(obstacleFrame, nowS, speedKmh, machineWidthM).level == 2 ->
                obstacleGuard.check(obstacleFrame, nowS, speedKmh, machineWidthM).message ?: "Czujnik przeszkód"
            else -> null
        }
        engaged = why == null
        if (engaged) freeDrive = false
        if (why != null) fail(why)
        return why
    }

    /** Krok prowadzenia (np. 10 razy na sekundę). */
    fun update(nowS: Double) {
        updateTimeS = nowS
        supervisor.check(
            Supervisor.Input(
                nowS = nowS, engaged = engaged, inTurn = youTurnPath != null, fix = fix,
                correctionAgeS = lastGga?.correctionAge, xteM = xteCm / 100, setDeg = steerSetDeg,
                actualDeg = autosteer?.takeIf { autosteerAlive(nowS) }?.actualAngleDeg, speedKmh = speedKmh,
            ),
            profile,
        )?.let { trip(it) }
        obstacleStep(nowS)
        steerBlockedReason?.let { if (engaged) trip("$it — autoster wyłączony") }
        // jak w AgOpenGPS (isSteerInReverse = false): przy cofaniu autoster zostaje włączony, ale płytka dostaje status 0
        if (engaged && !gpsAlive(nowS)) trip("Utracono GPS — autoster wyłączony")
        if (engaged && !autosteerAlive(nowS)) trip("Moduł autosterowania nie odpowiada — autoster wyłączony")
        checkSpeedLimits()
        if (freeDrive && speedKmh > FREE_DRIVE_MAX_KMH) { freeDrive = false; fail("Test silnika wyłączony — ciągnik jedzie") }
        // płytka nie da prądu na silnik, gdy jej włącznik autosteru jest wyłączony (ustawienie „Przełącznik” / „Przycisk” w PGN 251)
        val boardSwitchOff = (engaged || freeDrive) && profile.steerEnable != SteerEnable.NONE && autosteerAlive(nowS) && autosteer?.steerSwitch == false
        if (boardSwitchOff && !boardSwitchWarned) fail("Włącznik autosteru na płytce jest wyłączony — silnik nie ruszy. Włącz go albo w Ustawienia → Płytka wybierz włączanie „Brak”.")
        boardSwitchWarned = boardSwitchOff
        val p = pivot
        val roll = imu.rollDeg?.takeIf { imu.rollAlive(nowS) }
        // auto-zawrót: zaczynamy przed końcem przejazdu, a gdy trwa — jedziemy po nim
        // średni błąd od linii (crossTrackError, mm) — przy > 1 m budowany zawrót jest kasowany
        xteAvgMm = if (abs(xteCm * 10) < 29000) xteAvgMm * 0.9 + abs(xteCm * 10) * 0.1 else 0.0
        if (p != null && youTurnPath == null) {
            if (profile.youTurnStyle == 1) maybeStartTurn(p, nowS) else aogTurnStep(p, nowS)
        }
        if (kPhase != 0 && p != null && kTurnStep(p, roll)) return
        val turn = youTurnPath
        if (turn != null && p != null) {
            // CYouTurn.DistanceFromYouTurnLine 1:1 (YouTurnFollow); null = zawrót zakończony (CompleteYouTurn)
            if (!engaged && !aogTurnActive) {
                finishTurn("Zawrót przerwany", flip = false)
            } else {
                val res = youTurnFollow.step(turn, p, headingRad, speedKmh, reversing, profile, steering)
                if (res == null) {
                    finishTurn("Zawrót zakończony", flip = true)
                } else {
                    xteCm = res.distanceFromLine * 100
                    steerSetDeg = res.steerAngleDeg
                    goal = res.goal
                    return
                }
            }
        }
        val r: AbSteering.Output? = when (mode) {
            GuidanceMode.AB -> line?.let { l ->
                if (p == null) return@let null
                val (a, b) = activeSegment(l, p)
                steering.compute(a, b, p, headingRad, speedKmh, engaged && !reversing, profile, roll)
            }
            GuidanceMode.CURVE -> if (p != null && curveRef != null) {
                val path = activeCurve(p)
                if (profile.algorithm == eu.smartsteer.navigator.core.settings.SteerAlgorithm.STANLEY) {
                    followPath(path, p, roll, endMessage = "Koniec krzywej — autoster wyłączony")
                } else {
                    // CABCurve.GetCurrentCurveLine (pure pursuit) 1:1
                    curveFollow.step(curList, curveSameWay, p, headingRad, speedKmh, reversing, engaged, roll, profile, steering)?.let { cr ->
                        if (engaged && cr.pastEnd) { engaged = false; fail("Koniec krzywej — autoster wyłączony") }
                        AbSteering.Output(cr.steerAngleDeg, cr.distanceFromLine, cr.goal)
                    }
                }
            } else null
            GuidanceMode.CONTOUR -> if (p != null) {
                if (engaged && contour.guide.isEmpty()) trip("Kontur zgubiony — autoster wyłączony")
                // CContour.DistanceFromContourLine 1:1
                contour.follow(p, antenna ?: p, headingRad, speedKmh, reversing, engaged, youTurnPath != null, roll, profile, steering)
                    ?.let { AbSteering.Output(it.steerAngleDeg, it.distanceFromLine, contour.goal ?: p) }
            } else null
        }
        if (r == null) { xteCm = 0.0; steerSetDeg = 0.0; goal = null; return }
        xteCm = r.distanceFromLine * 100
        steerSetDeg = r.steerAngleDeg
        goal = r.goal
        smartWas.addSample(steerSetDeg, engaged, speedKmh, xteCm * 10, profile.invertWas)
    }

    // ---------------- auto-zawrót ----------------

    /** Auto-zawrót włączony (przycisk na mapie). Działa na linii AB i krzywej, gdy pole ma granicę. */
    var autoUturn = false
    /** Kierunek najbliższego zawrotu względem jazdy; po każdym zawrocie zmienia się sam. */
    var uTurnRight = true
    /** Ścieżka trwającego zawrotu (null = jedziemy po linii). */
    var youTurnPath: List<Pose>? = null; private set
    private val youTurnFollow = eu.smartsteer.navigator.core.guidance.YouTurnFollow()
    /** Podgląd najbliższego zawrotu (rysowany na mapie przed jego rozpoczęciem). */
    private var turnHint = -1
    private var lastTurnTryS = Double.NEGATIVE_INFINITY
    private var turnFailKey: String? = null
    private var turnLineCache: Pair<Pair<List<Vec2>, Double>, List<Vec2>>? = null

    /** Linia zawrotu: granica odsunięta do środka o ustawiony odstęp (cache). */
    val turnLine: List<Vec2>?
        get() {
            val b = boundary ?: return null
            val key = b to profile.youTurnFromBoundary
            turnLineCache?.let { if (it.first == key) return it.second.takeIf { l -> l.size >= 3 } }
            val l = if (profile.youTurnFromBoundary > 0) Polygon.inset(b, profile.youTurnFromBoundary) else b
            turnLineCache = key to l
            return l.takeIf { it.size >= 3 }
        }

    private var xteAvgMm = 0.0
    /** Zawrót jak w AgOpenGPS (CYouTurn) — budowany przed linią zawrotu i uruchamiany 1 m przed jego trzecim punktem. */
    val ytAog = eu.smartsteer.navigator.core.guidance.YouTurnAog()
    private var ytTurnLinesKey: Any? = null
    private var ytTurnLines: eu.smartsteer.navigator.core.guidance.YouTurnAog.TurnLines? = null
    /** Zbudowany, jeszcze nieuruchomiony zawrót (rysowany na mapie). */
    val turnPreview: List<Pose>? get() = ytAog.ytList.takeIf { ytAog.phase == 10 && !ytAog.triggered && it.size > 5 }
    private var ytFailShown = false

    private fun aogTurnLines(): eu.smartsteer.navigator.core.guidance.YouTurnAog.TurnLines? {
        val b = boundary ?: return null
        val key = listOf(b, obstacles.toList(), profile.youTurnFromBoundary)
        if (key != ytTurnLinesKey) {
            ytTurnLinesKey = key
            ytTurnLines = eu.smartsteer.navigator.core.guidance.YouTurnAog.TurnLines.build(b, obstacles, profile.youTurnFromBoundary)
        }
        return ytTurnLines
    }

    /** Stan linii dla budowy zawrotu (mf.ABLine / mf.curve). */
    private fun aogLine(p: Vec2): eu.smartsteer.navigator.core.guidance.YouTurnAog.Line? {
        val w = profile.passSpacing
        val turnLeft = ytAog.isTurnLeft
        return when (mode) {
            GuidanceMode.AB -> {
                val l = line ?: return null
                val sameWay = !reversed
                val dir = Vec2.forward(l.heading) * (if (reversed) -1.0 else 1.0)
                fun base(k: Int, travelDir: Vec2) = l.a + Vec2.right(l.heading) * (k * w) + Vec2.right(atan2(travelDir.x, travelDir.y)) * (lineShiftCm / 100.0 - profile.toolOffset)
                val b0 = base(passIndex, dir)
                val fw = Vec2.forward(l.heading)
                val proj = b0 + fw * ((p - b0) dot fw)
                eu.smartsteer.navigator.core.guidance.YouTurnAog.Line(
                    isAB = true, abHeading = l.heading, sameWay = sameWay, rPoint = proj,
                    curA = b0 - fw * 2000.0, curB = b0 + fw * 2000.0, curList = emptyList(), currentLocationIndex = 0,
                    nextLine = { skip ->
                        val k = passIndex + (if (turnLeft xor sameWay) skip else -skip)
                        val nb = base(k, dir * -1.0)
                        listOf(Pose(nb - fw * 2000.0, l.heading), Pose(nb + fw * 2000.0, l.heading))
                    },
                )
            }
            GuidanceMode.CURVE -> {
                val ref = curveRef ?: return null
                if (curList.size < 5) return null
                val same = curveSameWay
                eu.smartsteer.navigator.core.guidance.YouTurnAog.Line(
                    isAB = false, abHeading = 0.0, sameWay = same, rPoint = p, curA = p, curB = p,
                    curList = curList, currentLocationIndex = curveFollow.currentIndex,
                    nextLine = { skip ->
                        val k = curvePassIndex + (if (turnLeft xor same) skip else -skip)
                        val shift = (lineShiftCm / 100.0 - profile.toolOffset) * (if (!same) 1 else -1)
                        Curves.offsetList(ref, k * w + shift, w, boundary?.takeIf { it.size >= 3 })
                    },
                )
            }
            GuidanceMode.CONTOUR -> null
        }
    }

    /** Position.designer.cs, region „Youturn”: budowa w fazach, kasowanie poza polem i przy dużym błędzie, uruchomienie. */
    private fun aogTurnStep(p: Vec2, nowS: Double) {
        if (!autoUturn || mode == GuidanceMode.CONTOUR) { if (ytAog.phase != 0) ytAog.resetCreated(nowS); return }
        val tl = aogTurnLines() ?: return
        // zmiana kierunku przyciskiem kasuje zbudowany zawrót
        if (ytAog.isTurnLeft != !uTurnRight) { ytAog.isTurnLeft = !uTurnRight; ytAog.resetCreated(nowS) }
        if (tl.inside(p) == -1) { ytAog.resetCreated(nowS); return }
        if (ytAog.phase != 10) {
            if (xteAvgMm > 1000) { ytAog.resetCreated(nowS); return }
            val ln = aogLine(p) ?: return
            val st = eu.smartsteer.navigator.core.guidance.YouTurnAog.Settings(
                profile.youTurnRadius, profile.youTurnSkip, profile.passSpacing, profile.toolOffset, profile.youTurnExtension, profile.youTurnStyle,
            )
            ytAog.build(ln, tl, p, st, nowS)
            if (ytAog.phase == 11 && !ytFailShown) { ytFailShown = true; fail("Zawrót się nie mieści — poszerz uwrocie albo zmniejsz promień zawrotu") }
            if (ytAog.phase == 10) ytFailShown = false
        } else if (ytAog.ytList.size > 5) {
            val d = ytAog.ytList[2].p.distanceTo(p)
            if (d <= 1.0) triggerAogTurn(nowS)
        }
    }

    /** YouTurnTrigger: przejazd i kierunek zmieniają się od razu, prowadzi ścieżka zawrotu. */
    private fun triggerAogTurn(nowS: Double) {
        val skip = profile.youTurnSkip
        val left = ytAog.isTurnLeft
        savedPass = passIndex; savedReversed = reversed; savedTurnRight = uTurnRight
        savedCurvePass = curvePassIndex; savedCurveSameWay = curveSameWay
        if (!ytAog.isGoingStraightThrough) {
            when (mode) {
                GuidanceMode.AB -> { passIndex += if (left xor !reversed) skip else -skip; reversed = !reversed }
                GuidanceMode.CURVE -> {
                    curvePassIndex += if (left xor curveSameWay) skip else -skip
                    curveSameWay = !curveSameWay; curveReversed = !curveSameWay
                    curveRef?.let { buildCurList(it, profile.passSpacing) }
                }
                GuidanceMode.CONTOUR -> {}
            }
        }
        ytAog.trigger()
        uTurnRight = !ytAog.isTurnLeft
        youTurnPath = ytAog.ytList
        aogTurnActive = true
        turnHint = -1
        youTurnFollow.reset()
        steering.reset()
        ok("Zawracam ${if (left) "w lewo" else "w prawo"}")
    }
    private var aogTurnActive = false

    /** BuildManualYouLateral: przeskok o jeden przejazd w lewo / prawo (poniżej setAS_functionSpeedLimit). */
    fun manualLateral(left: Boolean): Boolean {
        if (speedKmh >= profile.functionSpeedLimit) return fail("Za szybko — przeskok tylko poniżej ${profile.functionSpeedLimit.roundToInt()} km/h")
        when (mode) {
            GuidanceMode.AB -> { if (line == null) return fail("Brak linii"); val same = !reversed; passIndex += if (same == left) 1 else -1 }
            GuidanceMode.CURVE -> {
                val ref = curveRef ?: return fail("Brak krzywej")
                curvePassIndex += if (curveSameWay == left) 1 else -1
                buildCurList(ref, profile.passSpacing)
            }
            GuidanceMode.CONTOUR -> return fail("W konturze nie ma przejazdów")
        }
        steering.reset(); pathHint = -1
        return ok("Przejazd ${if (left) "w lewo" else "w prawo"}")
    }

    /** BuildManualYouTurn: zawrót od razu (Dubins od punktu 4 m przed ciągnikiem), poniżej setAS_functionSpeedLimit. */
    fun manualTurn(right: Boolean, nowS: Double): Boolean {
        if (speedKmh >= profile.functionSpeedLimit) return fail("Za szybko — ręczny zawrót tylko poniżej ${profile.functionSpeedLimit.roundToInt()} km/h")
        if (youTurnPath != null) { cancelTurn(); return true }
        val p = pivot ?: return fail("Brak pozycji GPS")
        val ln = aogLine(p) ?: return fail("Brak linii")
        var head: Double
        var r: Vec2
        if (ln.isAB) { head = ln.abHeading; r = ln.rPoint }
        else {
            val c = curList.getOrNull(curveFollow.currentIndex) ?: return fail("Brak krzywej")
            head = c.heading; r = c.p
        }
        val same = ln.sameWay
        val turnOffset = profile.passSpacing * profile.youTurnSkip + (if (right) profile.toolOffset * 2.0 else -profile.toolOffset * 2.0)
        if (!same) head += 3.14 else head -= 0.01
        r = Vec2(r.x + sin(head) * 4, r.y + cos(head) * 4)
        val start = Pose(r, head)
        head -= PI
        if (head < 0) head += 2 * PI
        val goal = if (right) Vec2(r.x - cos(-head) * turnOffset, r.y - sin(-head) * turnOffset)
            else Vec2(r.x + cos(-head) * turnOffset, r.y + sin(-head) * turnOffset)
        val path = eu.smartsteer.navigator.core.guidance.Dubins(profile.youTurnRadius).generate(start, Pose(goal, head))
        if (path.size < 3) return fail("Nie da się zbudować zawrotu")
        ytAog.resetCreated(nowS)
        ytAog.isTurnLeft = !right
        uTurnRight = right
        ytAog.setManual(path)
        triggerAogTurn(nowS)
        return true
    }
    private var savedPass = 0
    private var savedReversed = false
    private var savedTurnRight = true
    private var savedCurvePass = 0
    private var savedCurveSameWay = true

    private fun maybeStartTurn(p: Vec2, nowS: Double) {
        if (!autoUturn || !engaged || mode == GuidanceMode.CONTOUR) return
        val tl = turnLine ?: return
        if (nowS - lastTurnTryS < 0.5) return
        lastTurnTryS = nowS
        if (!Polygon.contains(tl, p)) {
            // ciągnik już za linią zawrotu, a zawrotu nie ma — zatrzymujemy autoster zamiast wyjechać z pola
            trip("Koniec pola — autoster wyłączony (zawrót się nie zmieścił)")
            return
        }
        val guide = currentGuide(p) ?: return
        val from = Curves.nearestIndex(guide, p)
        val exit = YouTurn.exitIndex(guide, from, tl)
        if (exit < 0) return
        val radius = profile.youTurnRadius
        // jak turnOffset w AgOpenGPS: odstęp przejazdów + podwójne przesunięcie maszyny w stronę zawrotu
        val width = profile.youTurnSkip * profile.passSpacing + (if (uTurnRight) 2 * profile.toolOffset else -2 * profile.toolOffset)
        val reach = YouTurn.depth(YouTurn.shape(radius, width, uTurnRight)) + TURN_TRIGGER_M
        if (guide[exit].p.distanceTo(p) > reach) return
        val key = "$mode:$passIndex:$curvePassIndex:$reversed:$curveReversed"
        if (profile.youTurnStyle == 1 && width < 2 * radius) {
            val k = YouTurn.buildK(guide, from, tl, radius, width, uTurnRight)
            if (k == null) {
                if (turnFailKey != key) { turnFailKey = key; fail("Zawrót K się nie mieści — poszerz uwrocie") }
                return
            }
            kForwardEnd = k.forward.last(); kReverseEnd = k.reverse.last()
            youTurnPath = extend(k.forward, 8.0); kReverse = extend(k.reverse, 6.0); kExit = k.exit; kPhase = 1
            turnHint = -1
            steering.reset()
            ok("Zawrót K ${if (uTurnRight) "w prawo" else "w lewo"}: naprzód, potem wsteczny")
            return
        }
        val path = YouTurn.build(guide, from, tl, radius, width, uTurnRight)
        if (path == null) {
            if (turnFailKey != key) { turnFailKey = key; fail("Zawrót się nie mieści — poszerz uwrocie albo zmniejsz promień zawrotu") }
            return
        }
        youTurnPath = path
        turnHint = -1
        youTurnFollow.reset()
        steering.reset()
        ok("Zawracam ${if (uTurnRight) "w prawo" else "w lewo"}")
    }

    /** Linia, po której jedziemy, jako gęsta łamana w kierunku jazdy (do szukania końca przejazdu). */
    private fun currentGuide(p: Vec2): List<Pose>? = when (mode) {
        GuidanceMode.AB -> line?.let { l ->
            val (a, b) = activeSegment(l, p)
            Curves.headings(Curves.densify(listOf(a, b), 1.0))
        }
        GuidanceMode.CURVE -> curvePath.takeIf { it.size >= 2 }
        GuidanceMode.CONTOUR -> null
    }

    // ---------------- zawrót K (z cofaniem) ----------------
    /** 0 = brak, 1 = łuk naprzód, 2 = łuk wstecz, 3 = wyjazd naprzód na nowy przejazd. */
    var kPhase = 0; private set
    var kReverse: List<Pose>? = null; private set
    private var kExit: List<Pose>? = null

    private var kForwardEnd: Pose? = null
    private var kReverseEnd: Pose? = null

    /** Ścieżka przedłużona prosto o [m] (żeby wyprzedzenie nie kończyło łuku za wcześnie). */
    private fun extend(path: List<Pose>, m: Double): List<Pose> {
        val e = path.last()
        val d = Vec2.forward(e.heading)
        return path + (1..(m * 2).toInt()).map { Pose(e.p + d * (it * 0.5), e.heading) }
    }

    /** Czy punkt minął koniec odcinka (w kierunku jazdy po nim). */
    private fun passed(p: Vec2, end: Pose?): Boolean =
        end != null && p.distanceTo(end.p) < 4.0 && ((p - end.p) dot Vec2.forward(end.heading)) >= -0.2

    /** Krok zawrotu K; true = prowadzenie obsłużone. */
    private fun kTurnStep(p: Vec2, roll: Double?): Boolean {
        if (!engaged) { finishTurn("Zawrót przerwany", flip = false); return false }
        val pp = profile.copy(algorithm = eu.smartsteer.navigator.core.settings.SteerAlgorithm.PURE_PURSUIT)
        when (kPhase) {
            1 -> {
                val path = youTurnPath ?: return false
                val res = steering.computePath(path, turnHint, p, headingRad, speedKmh, false, profile, roll) ?: run { finishTurn("Zawrót przerwany", false); return false }
                turnHint = res.index
                xteCm = res.out.distanceFromLine * 100; goal = res.out.goal
                steerSetDeg = res.out.steerAngleDeg
                if (passed(p, kForwardEnd) || res.pastEnd) {
                    kPhase = 2; youTurnPath = kReverse; turnHint = -1; steering.reset()
                    ok("STOP — wrzuć wsteczny")
                }
            }
            2 -> {
                val path = kReverse ?: return false
                // prowadzenie wstecz: „przód” wirtualnie z tyłu ciągnika, skręt odwrotny
                val res = steering.computePath(path, turnHint, p, norm(headingRad + PI), speedKmh, false, pp, roll) ?: run { finishTurn("Zawrót przerwany", false); return false }
                xteCm = res.out.distanceFromLine * 100; goal = res.out.goal
                if (reversing) {
                    turnHint = res.index
                    steerSetDeg = -res.out.steerAngleDeg
                    if (passed(p, kReverseEnd) || res.pastEnd) {
                        kPhase = 3; youTurnPath = kExit; turnHint = -1; steering.reset()
                        ok("STOP — naprzód")
                    }
                } else {
                    // jeszcze się toczymy naprzód: koła zostają jak na łuku; na postoju ustawiamy je już do cofania
                    if (speedKmh < 0.8) steerSetDeg = -res.out.steerAngleDeg
                    val start = path.first().p
                    if (speedKmh > 1.0 && p.distanceTo(start) > 6.0) { trip("Zawrót K: ciągnik pojechał dalej naprzód — autoster wyłączony"); finishTurn("Zawrót przerwany", false) }
                }
            }
            3 -> {
                val path = kExit ?: return false
                if (reversing) { steerSetDeg = 0.0; return true }
                val res = steering.computePath(path, turnHint, p, headingRad, speedKmh, false, profile, roll) ?: run { finishTurn("Zawrót przerwany", false); return false }
                turnHint = res.index
                xteCm = res.out.distanceFromLine * 100; goal = res.out.goal
                steerSetDeg = res.out.steerAngleDeg
                if (res.pastEnd || (p - path.first().p).length > 8.0) finishTurn("Zawrót K zakończony", flip = true)
            }
        }
        return true
    }

    private fun finishTurn(msg: String, flip: Boolean) {
        kPhase = 0; kReverse = null; kExit = null
        youTurnPath = null
        turnHint = -1
        pathHint = -1
        steering.reset()
        if (aogTurnActive) {
            // CompleteYouTurn: przejazd zmieniony już przy uruchomieniu (YouTurnTrigger)
            aogTurnActive = false
            ytAog.complete(updateTimeS)
        } else {
            if (flip) uTurnRight = !uTurnRight
            passRecheck = true // jak isABValid = false po zawrocie w AgOpenGPS: przejazd wybierany od nowa
        }
        ok(msg)
    }

    /** Ręczne przerwanie zawrotu. */
    fun cancelTurn() {
        if (aogTurnActive) {
            // RestorePreTriggerState + ResetYouTurn: wracamy do przejazdu sprzed zawrotu
            aogTurnActive = false
            passIndex = savedPass; reversed = savedReversed; uTurnRight = savedTurnRight
            if (curvePassIndex != savedCurvePass || curveSameWay != savedCurveSameWay) {
                curvePassIndex = savedCurvePass; curveSameWay = savedCurveSameWay; curveReversed = !curveSameWay
                curveRef?.let { buildCurList(it, profile.passSpacing) }
            }
            ytAog.reset(updateTimeS)
        }
        if (youTurnPath != null) { youTurnPath = null; kPhase = 0; kReverse = null; kExit = null; pathHint = -1; steering.reset(); ok("Zawrót przerwany") }
    }

    private fun followPath(path: List<Pose>, p: Vec2, roll: Double?, endMessage: String?): AbSteering.Output? {
        val res = steering.computePath(path, pathHint, p, headingRad, speedKmh, engaged && !reversing, profile, roll) ?: return null
        pathHint = res.index
        if (engaged && res.pastEnd && endMessage != null) { engaged = false; fail(endMessage) }
        return res.out
    }

    // ---------------- krzywa ----------------

    fun setMode(m: GuidanceMode) {
        if (m == mode) return
        mode = m
        engaged = false
        pathHint = -1
        steering.reset()
        ok(
            when (m) {
                GuidanceMode.AB -> "Linia AB"
                GuidanceMode.CURVE -> if (curveRef == null) "Naciśnij „Nagraj krzywą” i przejedź pierwszy przejazd" else "Krzywa"
                GuidanceMode.CONTOUR -> "Kontur: prowadzenie wzdłuż poprzedniego przejazdu"
            },
        )
    }

    fun startCurve(): Boolean {
        if (pivot == null) return fail("Brak pozycji GPS — nie można nagrywać")
        recordingCurve = arrayListOf(pivot!!)
        engaged = false
        return ok("Nagrywam krzywą — jedź pierwszy przejazd, na końcu naciśnij „Zakończ”")
    }

    fun finishCurve(): Boolean {
        val rec = recordingCurve ?: return fail("Nagrywanie nie trwa")
        recordingCurve = null
        val len = Curves.length(rec)
        if (len < MIN_AB_M || rec.size < 10) return fail("Za krótka krzywa (${len.roundToInt()} m). Potrzeba co najmniej ${MIN_AB_M.roundToInt()} m")
        restoreCurve(Curves.buildReference(rec, hasBoundary = boundary != null))
        addTrack(Track(nextName("Krzywa"), null, curveRef?.map { it.p }))
        return ok("Krzywa gotowa (${len.roundToInt()} m)")
    }

    fun cancelCurve() { recordingCurve = null; ok("Nagrywanie przerwane") }

    fun clearCurve() {
        val t = tracks.getOrNull(activeTrack)
        if (t != null && t.isCurve) { tracks.removeAt(activeTrack); activeTrack = -1 }
        recordingCurve = null; curveRef = null; curvePath = emptyList(); curList = emptyList(); engaged = false
        ok("Krzywa usunięta")
    }

    fun restoreCurve(ref: List<Pose>) {
        curveRef = ref
        curvePath = emptyList()
        curList = emptyList()
        curvePassIndex = 0
        pathHint = -1
    }

    fun clearContour() { contour.clear(); if (mode == GuidanceMode.CONTOUR) engaged = false; ok("Ślady konturu wyczyszczone") }

    /** Długość nagrywanej krzywej, m (do wyświetlenia). */
    val recordingLength: Double get() = recordingCurve?.let { Curves.length(it) } ?: 0.0

    /**
     * Najbliższy równoległy przejazd krzywej i kierunek jazdy po nim (histereza jak przy AB);
     * nowa ścieżka liczona tylko przy zmianie przejazdu, kierunku lub przesunięcia linii.
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CABCurve.cs (BuildCurveCurrentList).
     */
    /** Bieżący przejazd krzywej w kierunku krzywej odniesienia (curList w AgOpenGPS) i czy jedziemy zgodnie z nią. */
    private var curList: List<Pose> = emptyList()
    private var curveSameWay = true
    private val curveFollow = eu.smartsteer.navigator.core.guidance.CurveFollow()

    /**
     * CABCurve.BuildCurveCurrentList 1:1: najbliższy punkt krzywej do guidanceLookPos (co 10, potem ±7), odległość od stycznej
     * w punkcie rA, zaokrąglenie do przejazdu; nowa linia (BuildNewOffsetList) tylko gdy zmienił się przejazd albo —
     * przy przesuniętej maszynie — kierunek jazdy.
     */
    private fun activeCurve(p: Vec2): List<Pose> {
        val ref = curveRef ?: return emptyList()
        val w = profile.passSpacing
        val invalid = curList.isEmpty()
        if (invalid || passCheckAllowed()) {
            val look = guidanceLookPos(p)
            val refCount = ref.size
            if (refCount < 2) { curList = emptyList(); curvePath = emptyList(); return curvePath }
            var minA = Double.MAX_VALUE
            var cc = 0
            var j = 0
            while (j < refCount) { val d = Curves.d2(look, ref[j].p); if (d < minA) { minA = d; cc = j }; j += 10 }
            minA = Double.MAX_VALUE
            var minB = Double.MAX_VALUE
            var dd = cc + 7; if (dd > refCount - 1) dd = refCount
            cc -= 7; if (cc < 0) cc = 0
            var rA = 0; var rB = 0
            for (k in cc until dd) {
                val d = Curves.d2(look, ref[k].p)
                if (d < minA) { minB = minA; rB = rA; minA = d; rA = k } else if (d < minB) { minB = d; rB = k }
            }
            if (rA > rB) { val c = rA; rA = rB; rB = c }
            val same = PI - abs(abs(headingRad - ref[rA].heading) - PI) < PI / 2
            val r1 = ref[rA].p - Vec2.forward(ref[rA].heading) * 300.0
            val r2 = ref[rA].p + Vec2.forward(ref[rA].heading) * 300.0
            val dx = r2.x - r1.x; val dz = r2.y - r1.y
            val distanceFromRefLine = ((dz * look.x) - (dx * look.y) + (r2.x * r1.y) - (r2.y * r1.x)) / kotlin.math.sqrt(dz * dz + dx * dx)
            val sign = if (same) 1.0 else -1.0
            val refDist = (distanceFromRefLine + sign * (profile.toolOffset - lineShiftCm / 100.0)) / w
            val k = if (refDist < 0) (refDist - 0.5).toInt() else (refDist + 0.5).toInt()
            val rebuild = invalid || k != curvePassIndex || (same != curveSameWay && (profile.toolOffset != 0.0 || lineShiftCm != 0))
            curvePassIndex = k
            if (same != curveSameWay) { curveSameWay = same; curveReversed = !same; curvePath = if (same) curList else Curves.reversed(curList) }
            if (rebuild) buildCurList(ref, w)
        }
        if (lineShiftCm != curveShiftCm || w != curveSpacing || profile.toolOffset != curveToolOffset) buildCurList(ref, w)
        return curvePath
    }

    private fun buildCurList(ref: List<Pose>, w: Double) {
        curveShiftCm = lineShiftCm
        curveSpacing = w
        curveToolOffset = profile.toolOffset
        // przesunięcie linii jest „w prawo względem jazdy”, więc przy jeździe pod prąd krzywej zmienia znak
        val shift = (lineShiftCm / 100.0 - profile.toolOffset) * (if (curveSameWay) 1 else -1)
        curList = Curves.offsetList(ref, curvePassIndex * w + shift, w, boundary?.takeIf { it.size >= 3 })
        curvePath = if (curveSameWay) curList else Curves.reversed(curList)
        curveFollow.reset()
        pathHint = -1
        steering.reset()
    }

    /**
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs:
     * wyłączenie powyżej prędkości maksymalnej i po 80 krokach poniżej minimalnej.
     */
    private fun checkSpeedLimits() {
        if (!engaged) { slowCounter = 0; return }
        if (speedKmh > profile.maxSteerSpeed) {
            trip("Za szybko (ponad ${profile.maxSteerSpeed.roundToInt()} km/h) — autoster wyłączony")
            return
        }
        if (kPhase == 0 && profile.minSteerSpeed >= 0.1 && speedKmh < profile.minSteerSpeed) {
            if (++slowCounter > 80) {
                slowCounter = 0
                trip("Za wolno (poniżej ${profile.minSteerSpeed} km/h) — autoster wyłączony")
            }
        } else {
            slowCounter = 0
        }
    }

    /**
     * Najbliższy równoległy przejazd jako odcinek skierowany zgodnie z jazdą (z przesunięciem linii).
     * Przejazd zmienia się dopiero, gdy jesteśmy wyraźnie bliżej sąsiedniego (histereza 10% odstępu).
     */
    internal fun activeSegment(l: AbLine, p: Vec2): Pair<Vec2, Vec2> {
        val w = profile.passSpacing
        val right = Vec2.right(l.heading)
        val oldPass = passIndex
        val oldRev = reversed
        // jak w AgOpenGPS (CABLine.BuildCurrentABLineList): przejazd i kierunek wybierane co 0,66 s i tylko przy
        // wyłączonym autosterze, z punktu przed ciągnikiem (guidanceLookPos) — ciągnik od razu „łapie” przejazd, w który wjeżdża
        if (passCheckAllowed()) {
            var diff = headingRad - l.heading
            while (diff > PI) diff -= 2 * PI
            while (diff < -PI) diff += 2 * PI
            reversed = abs(diff) >= PI / 2
            val look = guidanceLookPos(p)
            val sign = if (reversed) -1.0 else 1.0
            val refDist = (((look - l.a) dot right) + sign * (profile.toolOffset - lineShiftCm / 100.0)) / w
            passIndex = if (refDist < 0) (refDist - 0.5).toInt() else (refDist + 0.5).toInt()
        }
        if (oldPass != passIndex || oldRev != reversed) steering.reset()
        val dir = Vec2.forward(l.heading) * (if (reversed) -1.0 else 1.0)
        // przesunięcie linii w prawo względem kierunku jazdy
        // przesunięcie linii i maszyny w bok (w prawo względem kierunku jazdy): ciągnik jedzie obok przejazdu o −offset
        val base = l.a + right * (passIndex * w) + Vec2.right(atan2(dir.x, dir.y)) * (lineShiftCm / 100.0 - profile.toolOffset)
        val along = (p - base) dot dir
        // krótki odcinek wokół ciągnika: liczby zostają małe, a linia i tak jest prosta
        return Pair(base + dir * (along - SEGMENT_M), base + dir * (along + SEGMENT_M))
    }

    private var updateTimeS = 0.0
    private val steerFrameTimeS get() = updateTimeS
    private var lastSectionWhy = ""
    /** Ostatnie zmiany stanu sekcji z powodem (plik diagnostyki). */
    val sectionLog = ArrayDeque<String>()

    /** Do pliku diagnostyki: stan kursu. */
    fun headingDebug(nowS: Double): String = "IMU ${imu.headingDeg ?: "—"}° (żywe ${imu.headingAlive(nowS)}) · IMU+offset ${(imu.corrected() * 180 / PI * 10).roundToInt() / 10.0}° · GPS ${(gpsHeadingRad * 180 / PI * 10).roundToInt() / 10.0}° · kurs ${(headingRad * 180 / PI * 10).roundToInt() / 10.0}° · cofanie $reversing · przód ustalony $firstHeadingSet · źródło ${profile.headingFrom}"
    private var lastPassCheckS = -1e9

    /** Wybór przejazdu: co 0,66 s i tylko gdy autoster wyłączony (albo przełącznik na płytce wyłączony) — jak w AgOpenGPS. */
    private var passRecheck = false

    private fun passCheckAllowed(): Boolean {
        if (passRecheck) { passRecheck = false; lastPassCheckS = updateTimeS; return true }
        if (engaged && autosteer?.steerSwitch != false) return false
        if (updateTimeS - lastPassCheckS < 0.66) return false
        lastPassCheckS = updateTimeS
        return true
    }

    /** guidanceLookPos (AgOpenGPS): punkt przed tylną osią o max(pół szerokości maszyny, prędkość × 1,5 s). */
    private fun guidanceLookPos(pivot: Vec2): Vec2 =
        pivot + Vec2.forward(headingRad) * kotlin.math.max(profile.toolWidth * 0.5, abs(speedKmh) / 3.6 * profile.guidanceLookAheadS)

    /** Ramka PGN 252 (ustawienia modułu skrętu) z profilu. */
    fun steerSettingsFrame(): ByteArray = with(profile) {
        SteerSettings(gainP, highPwm, lowPwm, minPwm, countsPerDegree, wasOffset, ackerman).toFrame()
    }

    /** Ramka PGN 251 (konfiguracja płytki) z profilu. */
    fun steerConfigFrame(): ByteArray = with(profile) {
        SteerConfig(setting0, maxPulseCounts, minSteerSpeed, setting1, constantContour).toFrame()
    }

    /** Status w PGN 254 — AgOpenGPS: status 0 przy cofaniu (poza cofaniem w zawrocie K) i przy zmianie kierunku bez IMU. */
    private fun steerStatus() = engaged && !(reversing && !profile.steerInReverse && kPhase != 2 && kPhase != 3) && !(changingDirection && !imu.headingAlive(steerFrameTimeS))

    /** Ramka PGN 254 do modułu skrętu. */
    fun steerFrame(): ByteArray = if (freeDrive) {
        // Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs
        // („Drive button”): prędkość na sztywno 8 km/h, status włączony, kąt z przycisków.
        SteerData(speedKmh = 8.0, autosteerOn = true, steerAngleDeg = freeDriveAngle, lineDistanceCm = 0, sections = (sections.bits() and 0xFFFF).toInt()).toFrame()
    } else {
        SteerData(
            speedKmh = speedKmh,
            // AgOpenGPS: status 0 przy cofaniu (poza cofaniem w zawrocie K) i przy zmianie kierunku bez IMU
            autosteerOn = steerStatus(),
            steerAngleDeg = if (engaged) deadZoned(steerSetDeg, steerStatus()) else 0.0,
            lineDistanceCm = xteCm.roundToInt(),
            sections = (sections.bits() and 0xFFFF).toInt(),
            lineValid = engaged,
        ).toFrame()
    }

    // ---------------- martwa strefa ----------------
    private var deadZoneCounter = 0
    private var deadZoneTickS = Double.NEGATIVE_INFINITY
    private var inDeadZone = false
    private var lastSentAngle = 0.0

    /**
     * Martwa strefa 1:1 z AgOpenGPS (Position.designer.cs „delay on dead zone”, GUI.Designer.cs: licznik rośnie raz na sekundę):
     * gdy autoster pracuje, nie cofamy, a kąt zadany różni się od kąta kół mniej niż [VehicleProfile.deadZoneDeg] dłużej niż
     * [VehicleProfile.deadZoneDelay] sekund — płytka dostaje dalej poprzedni kąt, aż różnica wyjdzie poza strefę.
     */
    private fun deadZoned(set: Double, status: Boolean): Double {
        val now = steerFrameTimeS
        if (now - deadZoneTickS >= 1.0) { deadZoneCounter++; deadZoneTickS = now }
        val actual = autosteer?.actualAngleDeg
        // AgOpenGPS porównuje w setnych stopnia: guidanceLineSteerAngle (short) − actual × 100 < deadZoneHeading
        if (status && !reversing && actual != null && kotlin.math.abs((set * 100).toInt() - actual * 100) < profile.deadZoneDeg * 100) {
            if (deadZoneCounter > profile.deadZoneDelay) inDeadZone = true
        } else {
            deadZoneCounter = 0
            inDeadZone = false
        }
        if (!inDeadZone) lastSentAngle = set
        return lastSentAngle
    }

    /** Ramka PGN 238 (konfiguracja modułu maszyny) z profilu. */
    fun machineConfigFrame(): ByteArray = Sections.machineConfigFrame(profile)

    /** Ramka PGN 236 (piny przekaźników modułu maszyny). */
    fun relayConfigFrame(): ByteArray = Sections.relayConfigFrame(profile)

    // ---------------- test silnika (Free drive) ----------------
    /** Ręczne kręcenie kołami z ekranu — do sprawdzenia silnika i czujnika kąta bez jazdy. */
    var freeDrive = false; private set
    private var boardSwitchWarned = false
    var freeDriveAngle = 0.0; private set

    fun setFreeDrive(on: Boolean) {
        freeDrive = on
        freeDriveAngle = 0.0
        if (on) engaged = false
        ok(if (on) "Test silnika: koła kręcą się przyciskami" else "Test silnika wyłączony")
    }

    fun nudgeFreeDrive(deltaDeg: Double) {
        if (!freeDrive) return
        freeDriveAngle = (freeDriveAngle + deltaDeg).coerceIn(-profile.maxSteerAngle, profile.maxSteerAngle)
    }

    fun centerFreeDrive() { freeDriveAngle = 0.0 }

    /** Ramka PGN 239 do modułu maszyny (sekcje). */
    fun machineFrame(): ByteArray = Sections.machineFrame(speedKmh, sections.bits(), hydLiftState(), tramBits(), uturn = aogTurnActive, geoStop = outOfBounds())

    /**
     * mc.isOutOfBounds (Position.designer.cs, region „Youturn”): bez auto-zawrotu — oś tylna poza granicą pola;
     * z auto-zawrotem — poza obszarem zawracania (gdy zawrót nie trwa).
     */
    fun outOfBounds(): Boolean {
        val p = pivot ?: return false
        val b = boundary?.takeIf { it.size >= 3 } ?: return false
        val inFence = Polygon.contains(b, p) && obstacles.none { it.size >= 3 && Polygon.contains(it, p) }
        if (!autoUturn || mode == GuidanceMode.CONTOUR) return !inFence
        val tl = aogTurnLines() ?: return !inFence
        if (tl.inside(p) != -1) return false
        return if (aogTurnActive) false else !inFence
    }

    /** PGN 229 — jak w AgOpenGPS wysyłany zawsze (przy otwartym polu), z prędkościami krawędzi maszyny. */
    fun sections64Frame(): ByteArray? = if (fieldName != null || profile.numSections > 16) Sections.sections64Frame(sections.bits(), sections.farLeftSpeed, sections.farRightSpeed) else null

    /** Podnośnik włączony przyciskiem na mapie (działa, gdy w profilu jest podnośnik i automat). */
    var hydLiftOn = false

    /**
     * Podnośnik na uwrociu: 2 = podnieś, gdy punkt przed maszyną (wyprzedzenie włączania sekcji) jest na uwrociu
     * albo trwa zawrót; 1 = opuść w polu roboczym; 0 = nie sterujemy.
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs (hydLift).
     */
    /** Podnośnik ustawiony przez sekwencję uwrocia (1 = opuść, 2 = podnieś; 0 = automat). */
    var hydOverride = 0

    fun hydLiftState(): Int {
        if (profile.hydEnabled && hydOverride != 0) return hydOverride
        if (!profile.hydEnabled || !profile.hydAuto) return 0
        return hydLift
    }

    /** Stan podnośnika wysyłany w PGN 239 (p_239.hydLift): 0 nieustawiony, 1 opuść, 2 podnieś — trzymany do następnej zmiany. */
    private var hydLift = 0

    /**
     * Podnośnik na uwrociu 1:1 z AgOpenGPS (OpenGL.Designer.cs „determine if in or out of headland”, CHead.WhereAreToolCorners,
     * SetHydPosition): maszyna jest na uwrociu, gdy obie skrajne krawędzie są poza polem roboczym, a w pasie przed nią
     * (prędkość krawędzi × setVehicle_hydraulicLiftLookAhead, najwyżej 20 m) nie ma linii uwrocia. Zmiana tylko przy
     * włączonym podnośniku, prędkości > 0,2 km/h i jeździe naprzód.
     */
    private fun hydStep() {
        val inner = headland ?: return
        if (inner.size < 3 || boundary == null) return
        val p = profile
        val n = p.sectionWidthsEffective.size.coerceAtMost(Sections.MAX)
        val left = sections.edges(0, p)?.first ?: return
        val right = sections.edges(n - 1, p)?.second ?: return
        val outerIn = !Polygon.contains(inner, left) && !Polygon.contains(inner, right)
        val dL = (sections.farLeftSpeed * p.hydLiftLookAhead).coerceAtMost(20.0)
        val dR = (sections.farRightSpeed * p.hydLiftLookAhead).coerceAtMost(20.0)
        val fwd = Vec2.forward(sections.toolHeading)
        var anyIn = false
        var anyOut = false
        val cols = 40
        for (c in 0 until cols) {
            val f = (c + 0.5) / cols
            val base = left + (right - left) * f
            val rows = ((dL + (dR - dL) * f) * 10).toInt() - 1
            var r = 0
            val step = maxOf(1, rows / 60)
            while (r < rows) {
                if (Polygon.contains(inner, base + fwd * ((r + 0.5) * 0.1))) anyIn = true else anyOut = true
                r += step
            }
        }
        val headlandClose = anyIn && anyOut
        val toolInHeadland = outerIn && !headlandClose
        if (hydLiftOn && speedKmh > 0.2 && !reversing) hydLift = if (toolInHeadland) 2 else 1
    }

    /**
     * Ile metrów ma maszyna (jej rzeczywiste położenie za ciągnikiem) do linii uwrocia, jadąc prosto:
     * first = odległość, second = true gdy maszyna wjedzie na uwrocie (podnieś), false gdy zjedzie z uwrocia w pole (opuść).
     * Null, gdy brak uwrocia albo linia jest dalej niż 60 m.
     */
    fun toolToHeadland(): Pair<Double, Boolean>? {
        val inner = headland ?: return null
        val tp = sections.toolPos ?: return null
        val dir = Vec2.forward(sections.toolHeading)
        val inside = Polygon.contains(inner, tp)
        var best = Double.MAX_VALUE
        for (i in inner.indices) {
            val a = inner[i]; val b = inner[(i + 1) % inner.size]
            val e = b - a
            val den = dir.x * e.y - dir.y * e.x
            if (abs(den) < 1e-9) continue
            val w = a - tp
            val t = (w.x * e.y - w.y * e.x) / den
            val u = (w.x * dir.y - w.y * dir.x) / den
            if (t > 0 && u in 0.0..1.0 && t < best) best = t
        }
        if (best > 60.0) return null
        return best to inside
    }

    /** Bity ścieżek technologicznych dla bieżącego przejazdu (AB albo krzywa). */
    /** Ręczne włączenie śladu lewego / prawego (tram.isLeftManualOn / isRightManualOn). */
    var tramManualLeft = false
    var tramManualRight = false
    private var tramKey: Any? = null
    private var tramCache = eu.smartsteer.navigator.core.field.Tramlines.EMPTY

    /** Ślady technologiczne dla bieżącej linii/krzywej i granicy — budowane od nowa po każdej zmianie (FormTramLine, CTram). */
    fun tramSet(): eu.smartsteer.navigator.core.field.Tramlines.Set {
        val p = profile
        val T = eu.smartsteer.navigator.core.field.Tramlines
        if (p.tramWidth <= 0 || p.tramMode == 0) return T.EMPTY
        val fence = boundary?.takeIf { it.size >= 3 }
        val ref: Any? = when (mode) { GuidanceMode.AB -> line; GuidanceMode.CURVE -> curveRef; GuidanceMode.CONTOUR -> null }
        val key = listOf(ref, fence, p.tramWidth, p.tramTrack, p.tramPasses, p.tramStartPass, p.tramFlip, p.tramMode)
        if (key == tramKey) return tramCache
        val half = p.tramTrack * 0.5
        val (outer, inner) = if (fence != null && T.usesBoundary(p.tramMode)) T.boundaryTracks(fence, p.tramWidth, half) else (emptyList<Vec2>() to emptyList())
        // ile przejazdów: z ustawienia albo tyle, żeby przykryć pole (maxFieldDistance)
        val maxField = fence?.let { f -> val c = f.reduce { a, b -> a + b } * (1.0 / f.size); f.maxOf { it.distanceTo(c) } * 2 + 50 } ?: 1000.0
        val passes = if (p.tramPasses > 0) p.tramPasses else (maxField / p.tramWidth).toInt() + 2
        val lines = if (!T.usesLines(p.tramMode)) emptyList() else when (mode) {
            GuidanceMode.AB -> line?.let { l -> T.abLines(l.a, atan2(l.b.x - l.a.x, l.b.y - l.a.y), p.tramWidth, half, passes, p.tramStartPass, p.tramFlip, fence, maxField) }
            GuidanceMode.CURVE -> curveRef?.let { c -> T.curveLines(c, p.tramWidth, half, passes, p.tramStartPass, p.tramFlip, fence) }
            GuidanceMode.CONTOUR -> null
        }.orEmpty()
        tramCache = eu.smartsteer.navigator.core.field.Tramlines.Set(outer, inner, lines)
        tramKey = key
        return tramCache
    }

    /** Bajt „tram” w PGN 239 — tram.controlByte z AgOpenGPS. */
    fun tramBits(): Int {
        val p = profile
        if (p.tramWidth <= 0 || p.tramMode == 0) return 0
        val n = p.sectionWidthsEffective.size.coerceAtMost(Sections.MAX)
        val left = sections.edges(0, p)?.first ?: return 0
        val right = sections.edges(n - 1, p)?.second ?: return 0
        val T = eu.smartsteer.navigator.core.field.Tramlines
        val w = p.sectionWidthsEffective.take(n).sum()
        return T.controlByte(tramSet(), p.tramMode, left, right, w, p.tramTrack, p.tramTrack * 0.5,
            T.isOuter(p.tramWidth, w, p.tramOuterInverted), tramManualLeft, tramManualRight)
    }

    /** Przełącznik roboczy z PGN 253 (bit 0); aktywny stan wg ustawień. Bez modułu = wyłączony. */
    /** Przycisk główny sekcji z przełącznika: włączenie = automat albo wszystkie ręcznie, wyłączenie = WYŁ. */
    private fun sectionMasterFromSwitch(on: Boolean, manual: Boolean) {
        if (!on) { sections.mode = SectionMode.OFF; return }
        if (manual) { sections.manual.fill(true); sections.mode = SectionMode.MANUAL } else sections.mode = SectionMode.AUTO
    }

    fun workSwitchOn(nowS: Double): Boolean {
        val st = autosteer ?: return false
        if (!autosteerAlive(nowS)) return false
        return st.workSwitch != profile.workSwitchActiveLow
    }

    // ---------------- pole ----------------

    /** Nowe pole z początkiem w bieżącej pozycji. Czyści linie, granicę i pokrycie. */
    fun newField(name: String): Boolean {
        val g = lastGga?.takeIf { fix != FixQuality.NONE } ?: return fail("Brak pozycji GPS — pole zakłada się w miejscu ciągnika")
        resetForField(LocalPlane(g.latitude, g.longitude))
        fieldName = name
        fieldWarId = null
        return ok("Nowe pole „$name”. Objedź granicę albo od razu utwórz linię.")
    }

    /** Wczytanie zapisanego pola: wspólny początek płaszczyzny, granica, linie i pokrycie. */
    fun openField(f: FieldData, coverageText: String) {
        resetForField(LocalPlane(f.originLat, f.originLon))
        fieldName = f.name
        fieldWarId = f.warId
        f.boundary?.let { if (it.size >= 3) boundary = it }
        setHeadlandWidth(f.headlandWidth)
        f.ab?.let { restoreLine(it) }
        f.curve?.let { if (it.size >= 2) restoreCurve(Curves.headings(it)) }
        if (f.tracks.isNotEmpty()) {
            tracks.addAll(f.tracks)
            activeTrack = f.activeTrack.takeIf { it in tracks.indices } ?: -1
            tracks.getOrNull(activeTrack)?.let { if (it.isCurve) mode = GuidanceMode.CURVE else mode = GuidanceMode.AB }
        } else {
            // starsze pliki: jedna AB i jedna krzywa
            f.ab?.let { tracks.add(Track("AB 1", it, null)) }
            f.curve?.let { if (it.size >= 2) tracks.add(Track("Krzywa 1", null, it)) }
            activeTrack = if (f.ab != null) 0 else if (tracks.isNotEmpty()) 0 else -1
        }
        flags.addAll(f.flags)
        obstacles.addAll(f.obstacles)
        rateMap = f.rateMap
        coverage.loadText(coverageText)
        ok("Pole „${f.name}” wczytane")
    }

    /** Stan pola do zapisu (null = żadne pole nie jest otwarte). */
    fun fieldData(): FieldData? {
        val n = fieldName ?: return null
        val pl = plane ?: return null
        return FieldData(
            n, pl.originLat, pl.originLon, boundary, headlandWidth, line, curveRef?.map { it.p }, fieldWarId,
            tracks.toList(), activeTrack, flags.toList(), obstacles.toList(), rateMap,
        )
    }

    fun closeField() {
        fieldName = null
        fieldWarId = null
        boundary = null; headland = null; headlandWidth = 0.0; recordingBoundary = null
        coverage.clear(); sections.reset()
        line = null; pointA = null; curveRef = null; curvePath = emptyList(); curList = emptyList(); recordingCurve = null
        contour.clear(); engaged = false; youTurnPath = null
        tracks.clear(); activeTrack = -1; flags.clear(); obstacles.clear(); recordingObstacle = null; rateMap = null; applied.clear(); othersCoverage.clear(); othersSameWork.clear(); othersGrids.clear(); othersWork = emptyMap(); othersMachines = emptyMap(); othersCells = emptyMap(); othersColors = emptyMap()
        ok("Pole zamknięte")
    }

    private fun resetForField(pl: LocalPlane) {
        plane = pl
        antenna = null; pivot = null; resetDirection()
        trail.clear(); contour.clear(); coverage.clear(); sections.reset()
        boundary = null; headland = null; headlandWidth = 0.0; recordingBoundary = null
        line = null; pointA = null; curveRef = null; curvePath = emptyList(); curList = emptyList(); recordingCurve = null
        engaged = false; pathHint = -1; steering.reset(); youTurnPath = null
        tracks.clear(); activeTrack = -1; flags.clear(); obstacles.clear(); recordingObstacle = null; rateMap = null; applied.clear(); othersCoverage.clear(); othersSameWork.clear(); othersGrids.clear(); othersWork = emptyMap(); othersMachines = emptyMap(); othersCells = emptyMap(); othersColors = emptyMap()
    }

    fun startBoundary(): Boolean {
        if (pivot == null) return fail("Brak pozycji GPS — nie można nagrywać granicy")
        recordingBoundary = ArrayList()
        return ok("Nagrywam granicę — objedź pole i naciśnij „Zakończ”")
    }

    fun finishBoundary(): Boolean {
        val r = recordingBoundary ?: return fail("Nagrywanie granicy nie trwa")
        recordingBoundary = null
        val poly = Polygon.simplify(r)
        val area = if (poly.size >= 3) Polygon.area(poly) else 0.0
        if (area < 100.0) return fail("Za mała granica — objedź całe pole")
        boundary = poly
        setHeadlandWidth(headlandWidth)
        return ok("Granica zapisana: ${(area / 10_000).let { kotlin.math.round(it * 100) / 100 }} ha")
    }

    /** Granica z obrobionej powierzchni (zewnętrzny obrys pokrycia) — np. po objechaniu pola z rozsiewaczem. */
    fun boundaryFromCoverage(): Boolean {
        val o = coverage.outline() ?: return fail("Za mało obrobionej powierzchni — najpierw przejedź pole (sekcje AUTO)")
        val area = Polygon.area(o)
        if (area < 100.0) return fail("Za mała obrobiona powierzchnia")
        boundary = o
        setHeadlandWidth(headlandWidth)
        return ok("Granica z pracy: ${(area / 10_000).let { kotlin.math.round(it * 100) / 100 }} ha")
    }

    fun cancelBoundary() { recordingBoundary = null; ok("Nagrywanie granicy przerwane") }

    /** Tylko do testów: granica bez nagrywania. */
    internal fun restoreFieldForTest(b: List<Vec2>) { boundary = b; setHeadlandWidth(headlandWidth) }

    fun clearBoundary() { boundary = null; headland = null; ok("Granica usunięta") }

    fun setHeadlandWidth(w: Double) {
        headlandWidth = w.coerceAtLeast(0.0)
        val b = boundary
        headland = if (b != null && headlandWidth > 0) Polygon.inset(b, headlandWidth).takeIf { it.size >= 3 } else null
    }

    private fun ok(m: String): Boolean { message = m; return true }
    private fun fail(m: String): Boolean { message = m; return false }

    /** Kalibracja czujnika kąta z jazdy po okręgu (Ustawienia → Moduł skrętu). */
    val wasCal = WasCalibration()
    /** Smart WAS (AgOpenGPS): zero czujnika kół z mediany kąta zadanego przy jeździe z autosterem. */
    val smartWas = SmartWas()

    // ---------------- nadzór ----------------
    private val supervisor = Supervisor()
    /** Samoczynne wyłączenia autosteru (przyczyny) — kontroler zapisuje je do dziennika i opróżnia. */
    val tripLog = ArrayDeque<String>()

    /** Samoczynne wyłączenie autosteru z podaniem przyczyny. */
    private fun trip(m: String) {
        if (!engaged) return
        engaged = false
        fail(m)
        tripLog.addLast(m)
        while (tripLog.size > 50) tripLog.removeFirst()
    }

    companion object {
        const val MIN_AB_M = 20.0
        const val SEGMENT_M = 200.0
        /** Zawrót budujemy, gdy do jego najdalszego punktu zostaje tyle metrów. */
        const val TURN_TRIGGER_M = 25.0
        const val FREE_DRIVE_MAX_KMH = 3.0
        const val CURVE_STEP_M = 0.5
        const val CONTOUR_MIN_KMH = 1.0
        /** Powyżej tego błędu pozycji (m) sekcje nie pracują — tak jest np. z GPS telefonu w budynku albo pod drzewami. */
        const val POOR_GPS_SECTIONS_M = 10.0
        const val PGN_EXTERNAL_IMU = 0xD3
        const val PGN_IMU_DISCONNECT = 0xD4
        const val GPS_TIMEOUT_S = 1.0
        /** Liczba zapamiętanych pozycji do kursu (totalFixSteps w AgOpenGPS). */
        const val TOTAL_FIX_STEPS = 10
        /** setAS_guidanceLookAheadTime w AgOpenGPS, s. */
        const val GUIDANCE_LOOK_AHEAD_S = 1.5
        /** Poniżej tej prędkości nie liczymy kursu z ruchu (szum RTK), km/h. */
        const val MIN_HEADING_KMH = 1.0
    }
}
