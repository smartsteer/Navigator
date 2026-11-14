package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.Pgn
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Tryb sterowania sekcjami (przycisk główny). */
enum class SectionMode(val label: String) { OFF("WYŁ."), AUTO("AUTO"), MANUAL("RĘCZNIE") }

/**
 * Położenie maszyny i sterowanie sekcjami.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3):
 *  - SourceCode/GPS/Forms/Position.designer.cs — zaczep i maszyna ciągniona (hitchPos, toolPivotPos),
 *  - SourceCode/GPS/Forms/OpenGL.Designer.cs (oglBack_Paint: Section control, timery sekcji i malowania),
 *    Position.designer.cs (CalculateSectionLookAhead), Classes/CHead.cs (WhereAreToolLookOnPoints), CSection.cs,
 *  - SourceCode/GPS/Forms/PGN.Designer.cs — PGN 239 (CPGN_EF).
 */
class Sections {
    var mode = SectionMode.AUTO
    /** Sekcje włączone ręcznie (tryb RĘCZNIE). */
    val manual = BooleanArray(MAX) { true }
    /** Stan sekcji wysyłany do modułu. */
    val on = BooleanArray(MAX)
    private val strips = arrayOfNulls<Coverage.Strip>(MAX)

    /** Środek maszyny i jej kurs. */
    var toolPos: Vec2? = null; private set
    var toolHeading = 0.0; private set
    var hitchPos: Vec2? = null; private set
    private var lastPivot: Vec2? = null

    private var tankPos: Vec2? = null
    private var tankHeading = 0.0
    private var toolPivot: Vec2? = null
    private var startCounter = 0

    /**
     * Krok położenia maszyny przy każdej nowej pozycji — CalculatePositionHeading (Position.designer.cs) i CTool
     * (GetHitchLengthFromVehiclePivot / GetHitchHeadingFromVehiclePivot): zaczep, wózek z beczką (TBT), maszyna ciągniona,
     * „sprężynowanie” za ciągnik przy złożeniu i przez pierwsze 50 pozycji. [actualSteerDeg] — kąt przegubu dla przegubowych.
     */
    fun updateTool(pivot: Vec2, heading: Double, p: VehicleProfile, actualSteerDeg: Double = 0.0) {
        startCounter++
        var hitchLen = p.hitchLength
        var hitchHeading = heading
        if (p.articulated && abs(hitchLen) > 1e-9) {
            val half = 0.5 * p.wheelbase
            if (abs(half) > 1e-9) hitchLen += kotlin.math.sign(hitchLen) * half
            val rear = 0.25 * actualSteerDeg * PI / 180
            hitchHeading = if (hitchLen > 0) hitchHeading + rear else hitchHeading - rear
        }
        val hitch = pivot + Vec2.forward(hitchHeading) * hitchLen
        hitchPos = hitch
        val moved = lastPivot?.let { it.distanceTo(pivot) > 0.0 } ?: false
        lastPivot = pivot
        if (!p.toolTrailing) {
            toolHeading = hitchHeading; toolPos = hitch; toolPivot = hitch; tankPos = hitch; tankHeading = hitchHeading
            return
        }
        // wózek z beczką (TBT)
        val tank: Vec2
        if (p.toolTbt) {
            val tp = tankPos
            if (moved && tp != null) tankHeading = norm(atan2(hitch.x - tp.x, hitch.y - tp.y))
            val over = abs(PI - abs(abs(tankHeading - hitchHeading) - PI))
            if (over > 2.0 || startCounter < 51 || tp == null) tankHeading = hitchHeading
            tank = hitch + Vec2.forward(tankHeading) * p.tankHitchLength
        } else {
            tankHeading = hitchHeading
            tank = hitch
        }
        tankPos = tank
        // maszyna ciągniona
        val prev = toolPivot
        if (moved && prev != null) toolHeading = norm(atan2(tank.x - prev.x, tank.y - prev.y))
        val over = abs(PI - abs(abs(toolHeading - tankHeading) - PI))
        if (over > 1.9 || startCounter < 51 || prev == null) toolHeading = tankHeading
        toolPivot = tank + Vec2.forward(toolHeading) * p.trailingHitchLength
        toolPos = tank + Vec2.forward(toolHeading) * (p.trailingHitchLength - p.trailingToolToPivot)
    }

    private fun norm(h: Double): Double { var x = h; if (x < 0) x += 2 * PI; return x }

    /** Lewa i prawa krawędź sekcji [i] (od lewej; własne szerokości i przesunięcie maszyny w bok). */
    fun edges(i: Int, p: VehicleProfile): Pair<Vec2, Vec2>? {
        val c = toolPos ?: return null
        val w = p.sectionWidthsEffective
        if (i !in w.indices) return null
        val total = w.sum()
        val right = Vec2.right(toolHeading)
        var l = -total / 2 + p.toolOffset
        for (k in 0 until i) l += w[k]
        return Pair(c + right * l, c + right * (l + w[i]))
    }

    /** Czy sekcja maluje pokrycie (isMappingOn) — z opóźnieniem względem [on], jak w AgOpenGPS. */
    val mapping = BooleanArray(MAX)
    private val offTimer = IntArray(MAX)
    private val mappingOnTimer = IntArray(MAX)
    private val mappingOffTimer = IntArray(MAX)
    /** Prędkość krawędzi sekcji w „pikselach” AgOpenGPS (10 cm/s), ujemna = sekcja jedzie do tyłu. */
    private val speedPixels = DoubleArray(MAX)
    private val lastLeft = arrayOfNulls<Vec2>(MAX)
    private val lastRight = arrayOfNulls<Vec2>(MAX)
    /** Prędkość skrajnej lewej i prawej krawędzi maszyny, m/s (tool.farLeftSpeed / farRightSpeed). */
    var farLeftSpeed = 0.0; private set
    var farRightSpeed = 0.0; private set

    /**
     * CalculateSectionLookAhead (Position.designer.cs): prędkość każdej krawędzi sekcji z przesunięcia od poprzedniej
     * pozycji, kierunek (przód/tył) i wygładzone prędkości skrajnych krawędzi — na zakręcie wewnętrzna strona patrzy bliżej.
     */
    private fun sectionLookAhead(p: VehicleProfile, n: Int, avgSpeedKmh: Double, gpsHz: Double) {
        val maxPx = abs(avgSpeedKmh) * 4.5
        var leftSpeed = 0.0
        var rightSpeed = 0.0
        for (j in 0 until n) {
            val (lp, rp) = edges(j, p) ?: return
            val left = lp - (lastLeft[j] ?: lp)
            lastLeft[j] = lp
            if (j == 0) {
                leftSpeed = left.length * gpsHz * 10
                if (leftSpeed > maxPx) leftSpeed = maxPx
            } else if (leftSpeed > rightSpeed) leftSpeed = rightSpeed
            val right = rp - (lastRight[j] ?: rp)
            lastRight[j] = rp
            rightSpeed = right.length * gpsHz * 10
            if (rightSpeed > maxPx) rightSpeed = maxPx
            if (backwards(left) && leftSpeed > 0) leftSpeed = -leftSpeed
            if (backwards(right) && rightSpeed > 0) rightSpeed = -rightSpeed
            if (j == 0) farLeftSpeed = farLeftSpeed * 0.7 + (leftSpeed * 0.1).coerceAtLeast(0.1) * 0.3
            if (j == n - 1) farRightSpeed = farRightSpeed * 0.7 + (rightSpeed * 0.1).coerceAtLeast(0.1) * 0.3
            val sped: Double
            if (leftSpeed > rightSpeed) { sped = leftSpeed; leftSpeed = rightSpeed } else sped = rightSpeed
            speedPixels[j] = speedPixels[j] * 0.7 + sped * 0.3
        }
    }

    private fun backwards(d: Vec2): Boolean {
        var head = atan2(d.x, d.y)
        if (head < 0) head += 2 * PI
        return PI - abs(abs(head - toolHeading) - PI) > PI / 2
    }

    /**
     * Decyzja o sekcjach i malowanie pokrycia — przeniesione 1:1 z AgOpenGPS (OpenGL.Designer.cs, oglBack_Paint, region
     * „Section control” i timery sekcji). Zamiast odczytu pikseli z karty graficznej sprawdzamy te same punkty w siatce
     * pokrycia: pas przed każdą sekcją od odległości wyłączania do odległości włączania (prędkość krawędzi × czas),
     * sekcja potrzebna, gdy nieobrobionych jest więcej niż (100 − minCoverage)% punktów.
     * Do modułu idzie [on] (isSectionOn), mapa maluje się według [mapping] (isMappingOn).
     * @param boundary granica pola (null = brak), [inner] = wnętrze bez uwrocia
     * @param workOn przełącznik roboczy pozwala pracować
     * @param speedKmh średnia prędkość (avgSpeed)
     * @param gpsHz częstotliwość pozycji (gpsHz) — timery sekcji liczą się w pozycjach
     */
    fun update(
        p: VehicleProfile, speedKmh: Double, coverage: Coverage,
        boundary: List<Vec2>?, inner: List<Vec2>?, workOn: Boolean,
        obstacles: List<List<Vec2>> = emptyList(),
        /** Pokrycie innych maszyn na tym polu (wspólna praca przez WAR) — tam też nie trzeba pracować. */
        others: Coverage? = null,
        /** false — pozycja zbyt niepewna (np. ±20 m), sekcje stoją. */
        gpsOk: Boolean = true,
        gpsHz: Double = 10.0,
    ) {
        val widths = p.sectionWidthsEffective
        val n = minOf(widths.size, MAX)
        if (toolPos == null || n == 0) return
        sectionLookAhead(p, n, speedKmh, gpsHz)
        val fwd = Vec2.forward(toolHeading)
        val total = widths.take(n).sum().coerceAtLeast(0.01)
        // odległości wyprzedzenia lewej i prawej krawędzi, m (piksele AgOpenGPS / 10, limity 200 i 160 pikseli)
        val onL = (farLeftSpeed * p.lookAheadOn).coerceAtMost(20.0)
        val onR = (farRightSpeed * p.lookAheadOn).coerceAtMost(20.0)
        val offL = (farLeftSpeed * p.lookAheadOff).coerceAtMost(16.0)
        val offR = (farRightSpeed * p.lookAheadOff).coerceAtMost(16.0)
        val hasBnd = boundary != null && boundary.size >= 3
        val headOn = hasBnd && p.sectionOffInHeadland && inner != null && inner.size >= 3
        fun inFence(q: Vec2) = Polygon.contains(boundary!!, q) && obstacles.none { it.size >= 3 && Polygon.contains(it, q) }
        fun inHead(q: Vec2) = Polygon.contains(inner!!, q)

        for (j in n until MAX) { on[j] = false; mapping[j] = false; strips[j] = null }
        val onRequest = BooleanArray(n)
        var x0 = 0.0     // lewa krawędź sekcji od lewej krawędzi maszyny, m
        for (j in 0 until n) {
            val w = widths[j]
            val xs = x0
            x0 += w
            val (a, b) = edges(j, p) ?: continue
            // stan przycisku sekcji: przycisk główny WYŁ., brak pracy przełącznika lub słaby GPS = Off
            val blocked = !gpsOk || (p.useWorkSwitch && !workOn)
            val btnOff = blocked || mode == SectionMode.OFF || (mode == SectionMode.MANUAL && !manual[j])
            val btnOn = !blocked && mode == SectionMode.MANUAL && manual[j]
            // wyłączona, za wolno albo do tyłu — chyba że ręcznie włączona
            if (btnOff || speedKmh < p.slowSpeedCutoff || speedPixels[j] < 0) { onRequest[j] = btnOn; continue }
            if (btnOn) { onRequest[j] = true; continue }

            // pas przed sekcją: kolumny co ~10 cm w poprzek, wiersze co 10 cm od odległości wyłączania do włączania
            val cols = (w / COL_STEP).toInt().coerceIn(1, MAX_COLS)
            var totalPixel = 1
            var tagged = 0
            var bandIn = false
            var bandOut = false
            for (c in 0 until cols) {
                val xr = xs + w * (c + 0.5) / cols                   // od lewej krawędzi maszyny
                val f = xr / total
                val dOff = offL + (offR - offL) * f
                val dOn = onL + (onR - onL) * f
                val r0 = (dOff * 10).toInt()
                val r1 = (dOn * 10).toInt()
                if (r1 < r0) continue
                val rows = r1 - r0 + 1
                val step = if (rows > MAX_ROWS) rows.toDouble() / MAX_ROWS else 1.0
                val base = a + (b - a) * ((xr - xs) / w)
                var k = 0.0
                while (k < rows) {
                    val q = base + fwd * ((r0 + k + 0.5) * 0.1)
                    totalPixel++
                    if (!coverage.isCovered(q) && others?.isCovered(q) != true) tagged++
                    if (headOn) { if (inHead(q)) bandIn = true else bandOut = true }
                    k += step
                }
            }
            var required = (tagged * 100) / totalPixel > (100 - p.minCoverage)

            if (hasBnd) {
                // w granicy: obie krawędzie (sekcje gasną poza polem) albo choć jedna
                val li = inFence(a); val ri = inFence(b)
                val inBoundary = if (p.sectionOffWhenOut) li && ri else li || ri
                if (!inBoundary) {
                    onRequest[j] = false
                    offTimer[j] = 0
                    continue
                }
                if (headOn) {
                    // WhereAreToolLookOnPoints: oba punkty włączania na uwrociu? linia uwrocia w pasie przed sekcją?
                    val lookL = a + fwd * (onL + (onR - onL) * (xs / total))
                    val lookR = b + fwd * (onL + (onR - onL) * ((xs + w) / total))
                    val lookOnInHeadland = !inHead(lookL) && !inHead(lookR)
                    val headlandInLookOn = bandIn && bandOut
                    if (required && lookOnInHeadland && !headlandInLookOn) required = false
                }
            }
            onRequest[j] = required
        }

        // timery: włączenie od razu, wyłączenie z opóźnieniem; malowanie z wyprzedzeniem włączania/wyłączania
        for (j in 0 until n) {
            val req = onRequest[j]
            val offReq = !req
            if (req) on[j] = true
            if (p.turnOffDelay > 0) {
                if (!offReq) offTimer[j] = (gpsHz * p.turnOffDelay).toInt()
                if (offTimer[j] > 0) offTimer[j]--
                if (offReq && offTimer[j] == 0) on[j] = false
            } else if (offReq) on[j] = false

            if (req && !mapping[j] && mappingOnTimer[j] == 0) {
                mappingOnTimer[j] = (p.lookAheadOn * gpsHz - 1).toInt()
            } else if (req && mapping[j] && mappingOffTimer[j] > 1) {
                mappingOffTimer[j] = 0
                mappingOnTimer[j] = (p.lookAheadOn * gpsHz - 1).toInt()
            }
            if (p.lookAheadOff > 0) {
                if (offReq && mapping[j] && mappingOffTimer[j] == 0) mappingOffTimer[j] = (p.lookAheadOff * gpsHz + 4).toInt()
            } else if (p.turnOffDelay > 0) {
                if (offReq && mapping[j] && mappingOffTimer[j] == 0) mappingOffTimer[j] = (p.turnOffDelay * gpsHz).toInt()
            } else mappingOffTimer[j] = 0

            if (req) {
                mappingOffTimer[j] = 0
                if (mappingOnTimer[j] > 1) mappingOnTimer[j]-- else mapping[j] = true
            }
            if (offReq) {
                mappingOnTimer[j] = 0
                if (mappingOffTimer[j] > 1) mappingOffTimer[j]-- else mapping[j] = false
            }

            // malowanie pokrycia
            val e = edges(j, p)
            if (mapping[j] && e != null) {
                val (a, b) = e
                // skok pozycji (np. po utracie GPS) — zaczynamy nowy pas zamiast malować wielki czworokąt
                val old = strips[j]
                val jump = old != null && old.left.isNotEmpty() && old.left.last().distanceTo(a) > MAX_JUMP
                val s = if (old == null || jump) coverage.newStrip().also { strips[j] = it } else old
                coverage.extend(s, a, b)
            } else {
                strips[j] = null
            }
        }
    }

    /** Bity sekcji: bit 0 = sekcja 1. */
    /** Bity sekcji (do 64): bit 0 = sekcja 1. */
    fun bits(): Long {
        var b = 0L
        for (i in 0 until MAX) if (on[i]) b = b or (1L shl i)
        return b
    }

    fun anyOn() = on.any { it }

    fun reset() {
        on.fill(false); mapping.fill(false); strips.fill(null); toolPos = null; hitchPos = null; lastPivot = null
        offTimer.fill(0); mappingOnTimer.fill(0); mappingOffTimer.fill(0); speedPixels.fill(0.0)
        lastLeft.fill(null); lastRight.fill(null); farLeftSpeed = 0.0; farRightSpeed = 0.0
        tankPos = null; toolPivot = null; startCounter = 0
    }

    companion object {
        const val MAX = 64
        /** Kolumny pasa przed sekcją co tyle metrów (piksel AgOpenGPS = 10 cm; siatka pokrycia ma 25 cm). */
        private const val COL_STEP = 0.1
        private const val MAX_COLS = 40
        private const val MAX_ROWS = 80
        private const val MAX_JUMP = 5.0

        /** PGN 238 — konfiguracja modułu maszyny (CPGN_EE): czasy podnośnika, włączenie, ustawienia, 4 wartości użytkownika. */
        fun machineConfigFrame(p: VehicleProfile): ByteArray {
            val d = byteArrayOf(
                p.hydRaiseTime.coerceIn(0, 255).toByte(), p.hydLowerTime.coerceIn(0, 255).toByte(),
                (if (p.hydEnabled) 1 else 0).toByte(), p.machineSetting0.toByte(),
                p.machineUser1.coerceIn(0, 255).toByte(), p.machineUser2.coerceIn(0, 255).toByte(),
                p.machineUser3.coerceIn(0, 255).toByte(), p.machineUser4.coerceIn(0, 255).toByte(),
            )
            return AogFrame.build(PGN_MACHINE_CONFIG, d)
        }

        const val PGN_MACHINE_CONFIG = 0xEE

        /** PGN 239 — dane dla modułu maszyny: prędkość × 10 i sekcje. */
        /**
         * [hydLift]: 0 = podnośnik nieużywany, 1 = opuść (praca), 2 = podnieś (uwrocie) — jak w AgOpenGPS
         * (Position.designer.cs, p_239.hydLift). [tram]: bit 0 = ślad po prawej, bit 1 = po lewej.
         */
        fun machineFrame(speedKmh: Double, bits: Long, hydLift: Int = 0, tram: Int = 0): ByteArray {
            val d = ByteArray(8)
            d[0] = 0 // zawrót
            d[1] = (speedKmh * 10).roundToInt().coerceIn(0, 255).toByte()
            d[2] = hydLift.coerceIn(0, 2).toByte() // podnośnik
            d[3] = (tram and 3).toByte() // ścieżki technologiczne
            d[4] = 0 // geo-stop
            d[5] = 0
            d[6] = (bits and 0xFF).toByte()
            d[7] = ((bits shr 8) and 0xFF).toByte()
            return AogFrame.build(Pgn.MACHINE_DATA, d)
        }

        const val PGN_SECTIONS_64 = 0xE5

        /**
         * PGN 229 — stan do 64 sekcji (8 bajtów, bit 0 bajtu 0 = sekcja 1), wysyłany przy więcej niż 16 sekcjach.
         * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/PGN.Designer.cs (CPGN_E5).
         */
        fun sections64Frame(bits: Long): ByteArray {
            val d = ByteArray(8) { i -> ((bits ushr (8 * i)) and 0xFF).toByte() }
            return AogFrame.build(PGN_SECTIONS_64, d)
        }
    }
}
