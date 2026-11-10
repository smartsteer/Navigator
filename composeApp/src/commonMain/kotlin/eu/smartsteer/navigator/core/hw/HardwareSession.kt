package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.AutosteerStatus
import eu.smartsteer.navigator.core.aog.SteerData
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.gps.Gga
import eu.smartsteer.navigator.core.gps.Nmea
import eu.smartsteer.navigator.core.guidance.GuidancePath
import eu.smartsteer.navigator.core.guidance.PurePursuit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

data class HardwareConfig(
    val wheelbase: Double = 2.65,
    val maxSteerDeg: Double = 35.0,
    /** Szerokość robocza = odstęp między sąsiednimi przejazdami, m. */
    val implementWidth: Double = 3.0,
    /** Odległość anteny przed tylną osią, m (ujemna = za osią). */
    val antennaAheadOfAxle: Double = 0.0,
    /** Po tylu sekundach bez pozycji uznajemy, że GPS zniknął, i wyłączamy autoster. */
    val gpsTimeoutS: Double = 1.0,
    /** Poniżej tej prędkości nie liczymy kursu z ruchu (szum RTK), km/h. */
    val minHeadingSpeedKmh: Double = 1.0,
)

/** Linia AB w płaszczyźnie lokalnej pola. Kurs AB w radianach (0 = północ). */
data class AbLine(val a: Vec2, val b: Vec2) {
    val heading: Double get() = atan2(b.x - a.x, b.y - a.y)
}

/** Źródło kursu ciągnika — pokazywane na ekranie słowem. */
enum class HeadingSource(val label: String) { NONE("brak"), MOTION("z ruchu"), VTG("VTG"), DUAL("2 anteny") }

/**
 * Tryb Sprzęt: pozycja z prawdziwego odbiornika, linia AB z przycisków, autoster na tej linii.
 * Czysta logika (bez sieci i UI) — sieć podaje tu odebrane datagramy, a co 100 ms pobiera ramkę PGN 254.
 */
class HardwareSession(var config: HardwareConfig = HardwareConfig()) {

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

    val pilot = PurePursuit(wheelbase = config.wheelbase, maxSteerDeg = config.maxSteerDeg, lookaheadSeconds = 1.6)

    /** Ślad jazdy (co ok. 0,5 m) do rysowania na mapie. */
    val trail = ArrayDeque<Vec2>()

    private var motionRef: Vec2? = null
    private var vtgCourse: Double? = null
    private var vtgTimeS = Double.NEGATIVE_INFINITY
    private var dualHeading: Double? = null
    private var dualTimeS = Double.NEGATIVE_INFINITY

    fun gpsAlive(nowS: Double) = nowS - lastFixTimeS <= config.gpsTimeoutS && fix != FixQuality.NONE
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
        AutosteerStatus.from(frame)?.let { autosteer = it; lastAutosteerTimeS = nowS }
    }

    fun onNmea(sentence: String, nowS: Double) {
        if (sentence.length < 6) return
        val type = sentence.substring(3, 6)
        when {
            sentence.startsWith("\$PANDA") || sentence.startsWith("\$PAOGI") -> Nmea.parsePanda(sentence)?.let { p ->
                speedKmh = p.speedKmh
                if (p.dualAntenna && p.headingDeg != null) { dualHeading = p.headingDeg * PI / 180; dualTimeS = nowS }
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
        val pv = p - Vec2.forward(headingRad) * config.antennaAheadOfAxle
        pivot = pv
        if (trail.isEmpty() || trail.last().distanceTo(pv) >= 0.5) {
            trail.addLast(pv)
            while (trail.size > 4000) trail.removeFirst()
        }
    }

    /** Kurs: dwie anteny > VTG (przy ruchu) > z kolejnych pozycji. Na postoju zostaje ostatni. */
    private fun updateHeading(p: Vec2, nowS: Double) {
        val moving = speedKmh >= config.minHeadingSpeedKmh
        val dual = dualHeading
        val vtg = vtgCourse
        val ref = motionRef
        when {
            dual != null && nowS - dualTimeS < 1.0 -> { headingRad = dual; headingSource = HeadingSource.DUAL }
            vtg != null && moving && nowS - vtgTimeS < 1.0 -> { headingRad = vtg; headingSource = HeadingSource.VTG }
            ref == null -> motionRef = p
            p.distanceTo(ref) >= 0.5 -> {
                headingRad = atan2(p.x - ref.x, p.y - ref.y)
                headingSource = HeadingSource.MOTION
                motionRef = p
            }
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
        val l = line
        val p = pivot
        if (l == null || p == null) { xteCm = 0.0; steerSetDeg = 0.0; goal = null; return }
        val path = activePath(l, p)
        val r = pilot.compute(path, p, headingRad, speedKmh / 3.6, null, lineShiftCm / 100.0)
        xteCm = r.projection.lateral * 100
        steerSetDeg = r.steerDeg
        goal = r.goal
    }

    /**
     * Najbliższy równoległy przejazd i kierunek jazdy po nim.
     * Przejazd zmienia się dopiero, gdy jesteśmy wyraźnie bliżej sąsiedniego (histereza 10% szerokości).
     */
    internal fun activePath(l: AbLine, p: Vec2): GuidancePath {
        val w = config.implementWidth
        val right = Vec2.right(l.heading)
        val offset = (p - l.a) dot right
        val exact = offset / w
        if (abs(exact - passIndex) > 0.6) passIndex = kotlin.math.round(exact).toInt()
        var diff = headingRad - l.heading
        while (diff > PI) diff -= 2 * PI
        while (diff < -PI) diff += 2 * PI
        // histereza kierunku: zmiana dopiero przy wyraźnie przeciwnym kursie
        if (!reversed && abs(diff) > 0.6 * PI) reversed = true
        else if (reversed && abs(diff) < 0.4 * PI) reversed = false
        val shift = right * (passIndex * w)
        val dir = Vec2.forward(l.heading)
        val s = l.a + shift - dir * EXTEND_M
        val e = l.a + shift + dir * EXTEND_M
        return GuidancePath(if (reversed) listOf(e, s) else listOf(s, e))
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
        const val EXTEND_M = 3000.0
    }
}
