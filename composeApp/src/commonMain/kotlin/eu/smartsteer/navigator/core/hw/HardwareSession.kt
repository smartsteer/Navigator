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
import eu.smartsteer.navigator.core.guidance.AbSteering
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Linia AB w płaszczyźnie lokalnej pola. Kurs AB w radianach (0 = północ). */
data class AbLine(val a: Vec2, val b: Vec2) {
    val heading: Double get() = atan2(b.x - a.x, b.y - a.y)
}

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

    /** Ślad jazdy (co ok. 0,5 m) do rysowania na mapie. */
    val trail = ArrayDeque<Vec2>()

    private var motionRef: Vec2? = null
    private var vtgCourse: Double? = null
    private var vtgTimeS = Double.NEGATIVE_INFINITY
    private var dualHeading: Double? = null
    private var dualTimeS = Double.NEGATIVE_INFINITY

    /** Żyroskop: przechył i kurs z PGN 253, PANDA/PAOGI albo zewnętrznego IMU (PGN 211). */
    val imu = Imu()
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
                if (p.dualAntenna && p.headingDeg != null) { dualHeading = p.headingDeg * PI / 180; dualTimeS = nowS }
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
            type == "HDT" -> Nmea.parseHdt(sentence)?.let { dualHeading = it.headingDeg * PI / 180; dualTimeS = nowS }
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
    }

    /**
     * Kurs: dwie anteny > IMU skorygowane GPS-em > VTG (przy ruchu) > z kolejnych pozycji.
     * Z IMU kurs jest stabilny także na postoju i przy wolnej jeździe.
     */
    private fun updateHeading(p: Vec2, nowS: Double) {
        val dual = dualHeading
        if (dual != null && nowS - dualTimeS < 1.0) { headingRad = dual; headingSource = HeadingSource.DUAL; return }
        val moving = speedKmh >= MIN_HEADING_KMH
        val vtg = vtgCourse
        val ref = motionRef
        var gps: Double? = null
        var src = HeadingSource.NONE
        when {
            vtg != null && moving && nowS - vtgTimeS < 1.0 -> { gps = vtg; src = HeadingSource.VTG }
            ref == null -> motionRef = p
            p.distanceTo(ref) >= 0.5 -> {
                gps = atan2(p.x - ref.x, p.y - ref.y)
                src = HeadingSource.MOTION
                motionRef = p
            }
        }
        if (imu.headingAlive(nowS) && profile.fusionWeight > 0) {
            headingRad = if (gps != null) imu.fuse(gps, profile.fusionWeight) else imu.corrected()
            headingSource = HeadingSource.IMU
        } else if (gps != null) {
            headingRad = gps
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
        return ok("Linia AB gotowa")
    }

    fun clearLine() {
        pointA = null
        line = null
        engaged = false
        ok("Linia usunięta")
    }

    fun restoreLine(l: AbLine) { line = l; pointA = l.a; passIndex = 0 }

    // ---------------- autoster ----------------

    /** Zwraca komunikat, dlaczego nie można włączyć, albo null, gdy włączono. */
    fun setEngaged(on: Boolean, nowS: Double): String? {
        if (!on) { engaged = false; return null }
        val why = when {
            line == null -> "Najpierw utwórz linię AB"
            !gpsAlive(nowS) -> "Brak pozycji GPS"
            !autosteerAlive(nowS) -> "Brak odpowiedzi modułu autosterowania (PGN 253)"
            else -> null
        }
        engaged = why == null
        if (why != null) fail(why)
        return why
    }

    /** Krok prowadzenia (np. 10 razy na sekundę). */
    fun update(nowS: Double) {
        if (engaged && !gpsAlive(nowS)) { engaged = false; fail("Utracono GPS — autoster wyłączony") }
        if (engaged && !autosteerAlive(nowS)) { engaged = false; fail("Moduł autosterowania nie odpowiada — autoster wyłączony") }
        checkSpeedLimits()
        val l = line
        val p = pivot
        if (l == null || p == null) { xteCm = 0.0; steerSetDeg = 0.0; goal = null; return }
        val (a, b) = activeSegment(l, p)
        val r = steering.compute(a, b, p, headingRad, speedKmh, engaged, profile, imu.rollDeg?.takeIf { imu.rollAlive(nowS) })
        xteCm = r.distanceFromLine * 100
        steerSetDeg = r.steerAngleDeg
        goal = r.goal
    }

    /**
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs:
     * wyłączenie powyżej prędkości maksymalnej i po 80 krokach poniżej minimalnej.
     */
    private fun checkSpeedLimits() {
        if (!engaged) { slowCounter = 0; return }
        if (speedKmh > profile.maxSteerSpeed) {
            engaged = false
            fail("Za szybko (ponad ${profile.maxSteerSpeed.roundToInt()} km/h) — autoster wyłączony")
            return
        }
        if (profile.minSteerSpeed >= 0.1 && speedKmh < profile.minSteerSpeed) {
            if (++slowCounter > 80) {
                engaged = false
                slowCounter = 0
                fail("Za wolno (poniżej ${profile.minSteerSpeed} km/h) — autoster wyłączony")
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
        val base = l.a + right * (passIndex * w) + Vec2.right(atan2(dir.x, dir.y)) * (lineShiftCm / 100.0)
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
    fun steerFrame(): ByteArray = SteerData(
        speedKmh = speedKmh,
        autosteerOn = engaged,
        steerAngleDeg = if (engaged) steerSetDeg else 0.0,
        lineDistanceCm = xteCm.roundToInt(),
    ).toFrame()

    private fun ok(m: String): Boolean { message = m; return true }
    private fun fail(m: String): Boolean { message = m; return false }

    companion object {
        const val MIN_AB_M = 20.0
        const val SEGMENT_M = 200.0
        const val PGN_EXTERNAL_IMU = 0xD3
        const val PGN_IMU_DISCONNECT = 0xD4
        const val GPS_TIMEOUT_S = 1.0
        /** Poniżej tej prędkości nie liczymy kursu z ruchu (szum RTK), km/h. */
        const val MIN_HEADING_KMH = 1.0
    }
}
