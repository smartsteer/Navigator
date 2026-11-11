package eu.smartsteer.navigator.core.hw

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
    private var contourBuiltS = Double.NEGATIVE_INFINITY

    /** Ślad jazdy (co ok. 0,5 m) do rysowania na mapie. */
    val trail = ArrayDeque<Vec2>()

    private var motionRef: Vec2? = null
    private var vtgCourse: Double? = null
    private var vtgTimeS = Double.NEGATIVE_INFINITY
    private var dualHeading: Double? = null
    private var dualTimeS = Double.NEGATIVE_INFINITY

    /** Żyroskop: przechył i kurs z PGN 253, PANDA/PAOGI albo zewnętrznego IMU (PGN 211). */
    val imu = Imu()
    /** Kurs ze źródeł (przed odwróceniem przodu/tyłu). */
    private var rawHeadingRad = 0.0
    /** Przód/tył odwrócony dotknięciem ciągnika na mapie (jak w AgOpenGPS) — gdy kurs wyszedł odwrotny. */
    var headingFlipped = false; private set

    /** Dotknięcie ciągnika: zamiana przodu z tyłem (kurs + 180°). */
    fun flipHeading() {
        headingFlipped = !headingFlipped
        headingRad = norm(rawHeadingRad + if (headingFlipped) PI else 0.0)
        engaged = false
        pathHint = -1
        steering.reset()
        ok(if (headingFlipped) "Przód i tył zamienione" else "Przód i tył jak z GPS")
    }

    private fun norm(h: Double): Double { var r = h % (2 * PI); if (r < 0) r += 2 * PI; return r }

    /** Ostatnie przesunięcie anteny przez przechył, m (+ = w prawo) — do podglądu. */
    var rollCorrectionM = 0.0; private set

    fun gpsAlive(nowS: Double) = nowS - lastFixTimeS <= GPS_TIMEOUT_S && fix != FixQuality.NONE
    fun autosteerAlive(nowS: Double) = nowS - lastAutosteerTimeS <= 1.0

    // ---------------- wejście ----------------

    /** Datagram z sieci modułów: NMEA (tekst, może zawierać kilka zdań) albo ramka PGN. */
    fun onDatagram(bytes: ByteArray, length: Int, nowS: Double) {
        if (length <= 0) return
        if (bytes[0] == '$'.code.toByte()) {
            bytes.decodeToString(0, length).split('\n').forEach { onNmea(it.trim(), nowS) }
            return
        }
        val frame = AogFrame.parse(bytes, length) ?: return
        AutosteerStatus.from(frame)?.let {
            autosteer = it
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

    fun onNmea(sentence: String, nowS: Double) {
        if (sentence.length < 6) return
        val type = sentence.substring(3, 6)
        when {
            sentence.startsWith("\$PANDA") || sentence.startsWith("\$PAOGI") -> Nmea.parsePanda(sentence)?.let { p ->
                speedKmh = p.speedKmh
                if (p.dualAntenna && p.headingDeg != null) { dualHeading = (p.headingDeg + profile.dualHeadingOffset) * PI / 180; dualTimeS = nowS }
                if (!p.dualAntenna) p.headingDeg?.let { imu.onHeading(it, nowS) }
                p.rollDeg?.let { imu.onRoll(it, filtered = !p.dualAntenna, profile, nowS) }
                onPosition(p.gga, nowS)
            }
            type == "GGA" -> Nmea.parseGga(sentence)?.let { onPosition(it, nowS) }
            type == "VTG" -> Nmea.parseVtg(sentence)?.let { v ->
                speedKmh = v.speedKmh
                vtgCourse = v.courseDeg?.let { it * PI / 180 }
                vtgTimeS = nowS
            }
            type == "HDT" -> Nmea.parseHdt(sentence)?.let { dualHeading = (it.headingDeg + profile.dualHeadingOffset) * PI / 180; dualTimeS = nowS }
        }
    }

    private fun onPosition(g: Gga, nowS: Double) {
        lastGga = g
        fix = g.fix
        if (g.fix == FixQuality.NONE) return
        val pl = plane ?: LocalPlane(g.latitude, g.longitude).also { plane = it }
        val p = pl.toLocal(g.latitude, g.longitude)
        antenna = p
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
        recordingCurve?.let { r -> if (r.isEmpty() || r.last().distanceTo(pv) >= CURVE_STEP_M) r.add(pv) }
        // kontur nagrywamy zawsze podczas jazdy (później: gdy pracują sekcje — etap 6)
        contour.record(Pose(pv, headingRad), working = speedKmh >= CONTOUR_MIN_KMH)
        recordingBoundary?.let { r ->
            val q = pv + Vec2.right(headingRad) * (boundarySide * profile.toolWidth / 2)
            if (r.isEmpty() || r.last().distanceTo(q) >= 1.0) r.add(q)
        }
        recordingObstacle?.let { r -> if (r.isEmpty() || r.last().distanceTo(pv) >= 0.5) r.add(pv) }
        autosteer?.let { wasCal.sample(pv, headingRad, it.actualAngleDeg, speedKmh) }
        sections.updateTool(pv, headingRad, profile)
        sections.update(profile, speedKmh, coverage, boundary, headland, workSwitchOn(nowS) && !reversing, obstacles, othersCoverage.takeIf { othersMachines.isNotEmpty() })
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
        headingRad = norm(rawHeadingRad + if (headingFlipped) PI else 0.0)
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
        val dual = dualHeading
        if (dual != null && nowS - dualTimeS < 1.0) {
            rawHeadingRad = dual; headingSource = HeadingSource.DUAL
            val v = vtgCourse
            if (v != null && speedKmh >= MIN_HEADING_KMH && nowS - vtgTimeS < 1.0) reversing = angDiff(v, dual) > PI / 2
            return
        }
        val moving = speedKmh >= MIN_HEADING_KMH
        val vtg = vtgCourse
        val ref = motionRef
        var gps: Double? = null
        var src = HeadingSource.NONE
        when {
            vtg != null && moving && nowS - vtgTimeS < 1.0 -> { gps = vtg; src = HeadingSource.VTG; motionRef = p }
            ref == null -> motionRef = p
            p.distanceTo(ref) >= 0.5 -> {
                gps = atan2(p.x - ref.x, p.y - ref.y)
                src = HeadingSource.MOTION
                motionRef = p
            }
        }
        if (imu.headingAlive(nowS) && profile.fusionWeight > 0) {
            if (gps != null) reversing = angDiff(gps, imu.corrected()) > PI / 2
            val g = gps?.let { if (reversing) norm(it + PI) else it }
            rawHeadingRad = if (g != null) imu.fuse(g, profile.fusionWeight) else imu.corrected()
            headingSource = HeadingSource.IMU
        } else if (gps != null) {
            // bez IMU: kierunek jazdy z ciągłości kursu ciągnika
            val prev = if (headingSource == HeadingSource.NONE) gps else rawHeadingRad
            val d = angDiff(gps, prev)
            if (speedKmh < REVERSE_DETECT_KMH) {
                val vehicleIfFwd = angDiff(gps, prev)
                val vehicleIfRev = angDiff(norm(gps + PI), prev)
                reversing = vehicleIfRev < vehicleIfFwd && (reversing || d > REVERSE_JUMP)
            }
            rawHeadingRad = if (reversing) norm(gps + PI) else gps
            headingSource = src
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
            if (t.isCurve) { curveRef = null; curvePath = emptyList() } else { line = null; pointA = null }
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

    /** Pokrycie innej maszyny z WAR (cała jej dotychczasowa praca na tym polu). */
    fun addOthersCoverage(machine: String, name: String, g: eu.smartsteer.navigator.core.war.CoverageGrid) {
        val pl = plane ?: return
        val quads = g.paintInto(othersCoverage, pl)
        othersMachines = othersMachines + (machine to name)
        othersCells = othersCells + (machine to quads)
        othersVersion++
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
            mode == GuidanceMode.AB && line == null -> "Najpierw utwórz linię AB"
            mode == GuidanceMode.CURVE && curveRef == null -> "Najpierw nagraj krzywą"
            mode == GuidanceMode.CONTOUR && contour.guide.isEmpty() -> "Brak wcześniejszego przejazdu obok — kontur nie ma czego się trzymać"
            !gpsAlive(nowS) -> "Brak pozycji GPS"
            !autosteerAlive(nowS) -> "Brak odpowiedzi modułu autosterowania (PGN 253)"
            else -> null
        }
        engaged = why == null
        if (engaged) freeDrive = false
        if (why != null) fail(why)
        return why
    }

    /** Krok prowadzenia (np. 10 razy na sekundę). */
    fun update(nowS: Double) {
        supervisor.check(
            Supervisor.Input(
                nowS = nowS, engaged = engaged, inTurn = youTurnPath != null, fix = fix,
                correctionAgeS = lastGga?.correctionAge, xteM = xteCm / 100, setDeg = steerSetDeg,
                actualDeg = autosteer?.takeIf { autosteerAlive(nowS) }?.actualAngleDeg, speedKmh = speedKmh,
            ),
            profile,
        )?.let { trip(it) }
        if (engaged && reversing && kPhase != 2 && kPhase != 3) trip("Jazda do tyłu — autoster wyłączony")
        if (engaged && !gpsAlive(nowS)) trip("Utracono GPS — autoster wyłączony")
        if (engaged && !autosteerAlive(nowS)) trip("Moduł autosterowania nie odpowiada — autoster wyłączony")
        checkSpeedLimits()
        if (freeDrive && speedKmh > FREE_DRIVE_MAX_KMH) { freeDrive = false; fail("Test silnika wyłączony — ciągnik jedzie") }
        val p = pivot
        val roll = imu.rollDeg?.takeIf { imu.rollAlive(nowS) }
        // auto-zawrót: zaczynamy przed końcem przejazdu, a gdy trwa — jedziemy po nim
        if (p != null && youTurnPath == null) maybeStartTurn(p, nowS)
        if (kPhase != 0 && p != null && kTurnStep(p, roll)) return
        val turn = youTurnPath
        if (turn != null && p != null) {
            val res = if (engaged) steering.computePath(turn, turnHint, p, headingRad, speedKmh, true, profile, roll) else null
            if (res == null) {
                finishTurn("Zawrót przerwany", flip = false)
            } else if (res.pastEnd) {
                finishTurn("Zawrót zakończony", flip = true)
            } else {
                turnHint = res.index
                xteCm = res.out.distanceFromLine * 100
                steerSetDeg = res.out.steerAngleDeg
                goal = res.out.goal
                return
            }
        }
        val r: AbSteering.Output? = when (mode) {
            GuidanceMode.AB -> line?.let { l ->
                if (p == null) return@let null
                val (a, b) = activeSegment(l, p)
                steering.compute(a, b, p, headingRad, speedKmh, engaged, profile, roll)
            }
            GuidanceMode.CURVE -> if (p != null && curveRef != null) followPath(activeCurve(p), p, roll, endMessage = "Koniec krzywej — autoster wyłączony") else null
            GuidanceMode.CONTOUR -> if (p != null) {
                if (nowS - contourBuiltS >= (if (contour.guide.isEmpty()) 0.3 else 1.0)) {
                    val before = contour.guide
                    contour.build(p, headingRad, profile.toolWidth, profile.toolOverlap, hold = engaged)
                    contourBuiltS = nowS
                    if (contour.guide !== before) pathHint = -1
                }
                if (engaged && contour.guide.isEmpty()) trip("Kontur zgubiony — autoster wyłączony")
                followPath(contour.guide, p, roll, endMessage = null)
            } else null
        }
        if (r == null) { xteCm = 0.0; steerSetDeg = 0.0; goal = null; return }
        xteCm = r.distanceFromLine * 100
        steerSetDeg = r.steerAngleDeg
        goal = r.goal
    }

    // ---------------- auto-zawrót ----------------

    /** Auto-zawrót włączony (przycisk na mapie). Działa na linii AB i krzywej, gdy pole ma granicę. */
    var autoUturn = false
    /** Kierunek najbliższego zawrotu względem jazdy; po każdym zawrocie zmienia się sam. */
    var uTurnRight = true
    /** Ścieżka trwającego zawrotu (null = jedziemy po linii). */
    var youTurnPath: List<Pose>? = null; private set
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
        val width = profile.youTurnSkip * profile.passSpacing
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
                val res = steering.computePath(path, turnHint, p, headingRad, speedKmh, true, profile, roll) ?: run { finishTurn("Zawrót przerwany", false); return false }
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
                val res = steering.computePath(path, turnHint, p, norm(headingRad + PI), speedKmh, true, pp, roll) ?: run { finishTurn("Zawrót przerwany", false); return false }
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
                val res = steering.computePath(path, turnHint, p, headingRad, speedKmh, true, profile, roll) ?: run { finishTurn("Zawrót przerwany", false); return false }
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
        if (flip) uTurnRight = !uTurnRight
        ok(msg)
    }

    /** Ręczne przerwanie zawrotu. */
    fun cancelTurn() { if (youTurnPath != null) { youTurnPath = null; kPhase = 0; kReverse = null; kExit = null; pathHint = -1; steering.reset(); ok("Zawrót przerwany") } }

    private fun followPath(path: List<Pose>, p: Vec2, roll: Double?, endMessage: String?): AbSteering.Output? {
        val res = steering.computePath(path, pathHint, p, headingRad, speedKmh, engaged, profile, roll) ?: return null
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
        restoreCurve(Curves.buildReference(rec))
        addTrack(Track(nextName("Krzywa"), null, curveRef?.map { it.p }))
        return ok("Krzywa gotowa (${len.roundToInt()} m)")
    }

    fun cancelCurve() { recordingCurve = null; ok("Nagrywanie przerwane") }

    fun clearCurve() {
        val t = tracks.getOrNull(activeTrack)
        if (t != null && t.isCurve) { tracks.removeAt(activeTrack); activeTrack = -1 }
        recordingCurve = null; curveRef = null; curvePath = emptyList(); engaged = false
        ok("Krzywa usunięta")
    }

    fun restoreCurve(ref: List<Pose>) {
        curveRef = ref
        curvePath = emptyList()
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
    private fun activeCurve(p: Vec2): List<Pose> {
        val ref = curveRef ?: return emptyList()
        val w = profile.passSpacing
        val i = Curves.nearestIndex(ref, p)
        val r = ref[i]
        val right = Vec2.right(r.heading)
        val exact = ((p - r.p) dot right) / w
        var changed = curvePath.isEmpty()
        if (abs(exact - curvePassIndex) > 0.6) { curvePassIndex = kotlin.math.round(exact).toInt(); changed = true }
        var diff = headingRad - r.heading
        while (diff > PI) diff -= 2 * PI
        while (diff < -PI) diff += 2 * PI
        val rev = if (!curveReversed && abs(diff) > 0.6 * PI) true else if (curveReversed && abs(diff) < 0.4 * PI) false else curveReversed
        if (rev != curveReversed) { curveReversed = rev; changed = true }
        if (lineShiftCm != curveShiftCm || w != curveSpacing || profile.toolOffset != curveToolOffset) changed = true
        if (changed) {
            curveShiftCm = lineShiftCm
            curveSpacing = w
            curveToolOffset = profile.toolOffset
            // przesunięcie linii jest „w prawo względem jazdy”, więc przy jeździe pod prąd krzywej zmienia znak
            val shift = (lineShiftCm / 100.0 - profile.toolOffset) * (if (curveReversed) -1 else 1)
            val off = Curves.offset(ref, curvePassIndex * w + shift, w)
            curvePath = if (curveReversed) Curves.reversed(off) else off
            pathHint = -1
            steering.reset()
        }
        return curvePath
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
        val offset = (p - l.a) dot right
        val exact = offset / w
        val oldPass = passIndex
        val oldRev = reversed
        if (abs(exact - passIndex) > 0.6) passIndex = kotlin.math.round(exact).toInt()
        var diff = headingRad - l.heading
        while (diff > PI) diff -= 2 * PI
        while (diff < -PI) diff += 2 * PI
        // histereza kierunku: zmiana dopiero przy wyraźnie przeciwnym kursie
        if (!reversed && abs(diff) > 0.6 * PI) reversed = true
        else if (reversed && abs(diff) < 0.4 * PI) reversed = false
        if (oldPass != passIndex || oldRev != reversed) steering.reset()
        val dir = Vec2.forward(l.heading) * (if (reversed) -1.0 else 1.0)
        // przesunięcie linii w prawo względem kierunku jazdy
        // przesunięcie linii i maszyny w bok (w prawo względem kierunku jazdy): ciągnik jedzie obok przejazdu o −offset
        val base = l.a + right * (passIndex * w) + Vec2.right(atan2(dir.x, dir.y)) * (lineShiftCm / 100.0 - profile.toolOffset)
        val along = (p - base) dot dir
        // krótki odcinek wokół ciągnika: liczby zostają małe, a linia i tak jest prosta
        return Pair(base + dir * (along - SEGMENT_M), base + dir * (along + SEGMENT_M))
    }

    /** Ramka PGN 252 (ustawienia modułu skrętu) z profilu. */
    fun steerSettingsFrame(): ByteArray = with(profile) {
        SteerSettings(gainP, highPwm, lowPwm, minPwm, countsPerDegree, wasOffset, ackerman).toFrame()
    }

    /** Ramka PGN 251 (konfiguracja płytki) z profilu. */
    fun steerConfigFrame(): ByteArray = with(profile) {
        SteerConfig(setting0, maxPulseCounts, minSteerSpeed, setting1).toFrame()
    }

    /** Ramka PGN 254 do modułu skrętu. */
    fun steerFrame(): ByteArray = if (freeDrive) {
        // Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs
        // („Drive button”): prędkość na sztywno 8 km/h, status włączony, kąt z przycisków.
        SteerData(speedKmh = 8.0, autosteerOn = true, steerAngleDeg = freeDriveAngle, lineDistanceCm = 0, sections = (sections.bits() and 0xFFFF).toInt()).toFrame()
    } else {
        SteerData(
            speedKmh = speedKmh,
            autosteerOn = engaged,
            steerAngleDeg = if (engaged) deadZoned(steerSetDeg) else 0.0,
            lineDistanceCm = xteCm.roundToInt(),
            sections = (sections.bits() and 0xFFFF).toInt(),
        ).toFrame()
    }

    // ---------------- martwa strefa ----------------
    private var deadZoneCounter = 0
    private var lastSentAngle = 0.0

    /**
     * Martwa strefa: gdy kąt zadany jest blisko kąta kół przez [VehicleProfile.deadZoneDelay] kroków,
     * wysyłamy dalej poprzedni kąt (silnik nie „drga”).
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs (dead zone).
     */
    private fun deadZoned(set: Double): Double {
        val actual = autosteer?.actualAngleDeg
        if (actual != null && kotlin.math.abs(set - actual) < profile.deadZoneDeg) {
            if (++deadZoneCounter > profile.deadZoneDelay) return lastSentAngle
        } else {
            deadZoneCounter = 0
        }
        lastSentAngle = set
        return set
    }

    /** Ramka PGN 238 (konfiguracja modułu maszyny) z profilu. */
    fun machineConfigFrame(): ByteArray = Sections.machineConfigFrame(profile)

    // ---------------- test silnika (Free drive) ----------------
    /** Ręczne kręcenie kołami z ekranu — do sprawdzenia silnika i czujnika kąta bez jazdy. */
    var freeDrive = false; private set
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
    fun machineFrame(): ByteArray = Sections.machineFrame(speedKmh, sections.bits(), hydLiftState(), tramBits())

    /** PGN 229 — tylko przy więcej niż 16 sekcjach. */
    fun sections64Frame(): ByteArray? = if (profile.numSections > 16) Sections.sections64Frame(sections.bits()) else null

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
        if (!profile.hydEnabled || !profile.hydAuto || !hydLiftOn) return 0
        val inner = headland ?: return 0
        if (youTurnPath != null) return 2
        val tp = sections.toolPos ?: return 0
        val look = (speedKmh / 3.6 * profile.lookAheadOn).coerceAtLeast(0.5)
        val q = tp + Vec2.forward(sections.toolHeading) * look
        return if (Polygon.contains(inner, q)) 1 else 2
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
    fun tramBits(): Int {
        val p = profile
        if (p.tramEvery <= 0) return 0
        return when (mode) {
            GuidanceMode.AB -> if (line == null) 0 else
                eu.smartsteer.navigator.core.field.Tramlines.bits(passIndex, p.passSpacing, p.tramEvery, p.tramTrack, p.tramWheel, p.tramShift, reversed)
            GuidanceMode.CURVE -> if (curveRef == null) 0 else
                eu.smartsteer.navigator.core.field.Tramlines.bits(curvePassIndex, p.passSpacing, p.tramEvery, p.tramTrack, p.tramWheel, p.tramShift, curveReversed)
            GuidanceMode.CONTOUR -> 0
        }
    }

    /** Przełącznik roboczy z PGN 253 (bit 0); aktywny stan wg ustawień. Bez modułu = wyłączony. */
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
        line = null; pointA = null; curveRef = null; curvePath = emptyList(); recordingCurve = null
        contour.clear(); engaged = false; youTurnPath = null
        tracks.clear(); activeTrack = -1; flags.clear(); obstacles.clear(); recordingObstacle = null; rateMap = null; applied.clear(); othersCoverage.clear(); othersMachines = emptyMap(); othersCells = emptyMap()
        ok("Pole zamknięte")
    }

    private fun resetForField(pl: LocalPlane) {
        plane = pl
        antenna = null; pivot = null; motionRef = null
        trail.clear(); contour.clear(); coverage.clear(); sections.reset()
        boundary = null; headland = null; headlandWidth = 0.0; recordingBoundary = null
        line = null; pointA = null; curveRef = null; curvePath = emptyList(); recordingCurve = null
        engaged = false; pathHint = -1; steering.reset(); youTurnPath = null
        tracks.clear(); activeTrack = -1; flags.clear(); obstacles.clear(); recordingObstacle = null; rateMap = null; applied.clear(); othersCoverage.clear(); othersMachines = emptyMap(); othersCells = emptyMap()
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
        const val PGN_EXTERNAL_IMU = 0xD3
        const val PGN_IMU_DISCONNECT = 0xD4
        const val GPS_TIMEOUT_S = 1.0
        /** Wykrywanie zmiany kierunku jazdy tylko przy małej prędkości. */
        const val REVERSE_DETECT_KMH = 6.0
        /** Przeskok kursu ruchu, który znaczy „zmiana kierunku jazdy”. */
        const val REVERSE_JUMP = 2.1
        /** Poniżej tej prędkości nie liczymy kursu z ruchu (szum RTK), km/h. */
        const val MIN_HEADING_KMH = 1.0
    }
}
