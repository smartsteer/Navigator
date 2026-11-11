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
 *  - SourceCode/GPS/Forms/Sections.Designer.cs i Classes/CSection.cs — włączanie z wyprzedzeniem
 *    (lookAheadOn / lookAheadOff), wyłączanie poza granicą i przy małej prędkości,
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

    /** Krok położenia maszyny przy każdej nowej pozycji. */
    fun updateTool(pivot: Vec2, heading: Double, p: VehicleProfile) {
        val fwd = Vec2.forward(heading)
        val hitch = pivot + fwd * p.hitchLength
        hitchPos = hitch
        val moved = lastPivot?.let { it.distanceTo(pivot) > 0.01 } ?: false
        lastPivot = pivot
        val prev = toolPos
        if (!p.toolTrailing || prev == null) {
            toolHeading = heading
            toolPos = if (p.toolTrailing) hitch + Vec2.forward(heading) * p.trailingHitchLength else hitch
            return
        }
        // maszyna ciągniona: oś maszyny idzie za zaczepem
        if (moved) toolHeading = atan2(hitch.x - prev.x, hitch.y - prev.y)
        val over = abs(PI - abs(abs(toolHeading - heading) - PI))
        if (over > 1.9) toolHeading = heading
        toolPos = hitch + Vec2.forward(toolHeading) * p.trailingHitchLength
    }

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

    /**
     * Decyzja o sekcjach i malowanie pokrycia.
     * @param boundary granica pola (null = brak), [inner] = wnętrze bez uwrocia
     * @param workOn przełącznik roboczy pozwala pracować
     */
    fun update(
        p: VehicleProfile, speedKmh: Double, coverage: Coverage,
        boundary: List<Vec2>?, inner: List<Vec2>?, workOn: Boolean,
        obstacles: List<List<Vec2>> = emptyList(),
    ) {
        val n = p.sectionWidthsEffective.size
        val fwd = Vec2.forward(toolHeading)
        val v = speedKmh / 3.6
        val lookOn = (v * p.lookAheadOn).coerceAtLeast(MIN_LOOK)
        val lookOff = (v * p.lookAheadOff).coerceAtLeast(MIN_LOOK)
        val master = mode != SectionMode.OFF && speedKmh >= p.slowSpeedCutoff && (workOn || !p.useWorkSwitch)

        fun needsWork(a: Vec2, b: Vec2, ahead: Double): Boolean {
            for (k in 0..SAMPLES) {
                // próbki wewnątrz sekcji (nie na samej krawędzi — tam zaczyna się sąsiedni pas)
                val q = a + (b - a) * ((k + 0.5) / (SAMPLES + 1)) + fwd * ahead
                if (boundary != null && p.sectionOffWhenOut && !Polygon.contains(boundary, q)) continue
                if (inner != null && p.sectionOffInHeadland && inner.size >= 3 && !Polygon.contains(inner, q)) continue
                if (p.sectionOffWhenOut && obstacles.any { Polygon.contains(it, q) }) continue
                if (!coverage.isCovered(q)) return true
            }
            return false
        }

        for (i in 0 until MAX) {
            if (i >= n) { on[i] = false; strips[i] = null; continue }
            val e = edges(i, p)
            if (e == null) { on[i] = false; continue }
            val (a, b) = e
            on[i] = when {
                !master -> false
                mode == SectionMode.MANUAL -> manual[i]
                on[i] -> needsWork(a, b, lookOff)
                else -> needsWork(a, b, lookOn)
            }
            if (on[i]) {
                // skok pozycji (np. po utracie GPS) — zaczynamy nowy pas zamiast malować wielki czworokąt
                val old = strips[i]
                val jump = old != null && old.left.isNotEmpty() && old.left.last().distanceTo(a) > MAX_JUMP
                val s = if (old == null || jump) coverage.newStrip().also { strips[i] = it } else old
                coverage.extend(s, a, b)
            } else {
                strips[i] = null
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
        on.fill(false); strips.fill(null); toolPos = null; hitchPos = null; lastPivot = null
    }

    companion object {
        const val MAX = 64
        private const val SAMPLES = 4
        /** Najkrótsze wyprzedzenie — żeby sekcja nie „widziała” pasa, który sama właśnie maluje. */
        private const val MIN_LOOK = 0.4
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
