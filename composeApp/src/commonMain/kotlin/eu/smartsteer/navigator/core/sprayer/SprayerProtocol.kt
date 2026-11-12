package eu.smartsteer.navigator.core.sprayer

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.Frame

/**
 * Protokół opryskiwacza SmartSteer (opis: docs/MASZYNA.md).
 *
 * Navigator ↔ płytka Ster: ramki AgOpenGPS (0x80 0x81 źródło PGN długość dane CRC) po UDP,
 * PGN z zakresu SmartSteer (160/161, nieużywane przez AgOpenGPS).
 * Płytka Ster przekłada je 1:1 na ramki CAN do płytki Maszyna (250 kbit/s, ID 11‑bit).
 * Wartości wielobajtowe little-endian.
 */
object SprayerPgn {
    const val COMMAND = 0xA0   // 160: Navigator -> Ster -> Maszyna, co 100 ms
    const val STATUS = 0xA1    // 161: Maszyna -> Ster -> Navigator, co 100 ms

    const val MAX_SECTIONS = 9

    /** Identyfikatory CAN (11 bit) — płytka Ster i Maszyna. */
    const val CAN_CMD = 0x300
    const val CAN_VALVE = 0x301
    const val CAN_FLOW = 0x380
    const val CAN_STATE = 0x381
    const val CAN_SPREADER = 0x382 // rezerwa: zasuwy rozsiewacza
    const val CAN_BITRATE = 250_000

    /** Brak polecenia przez tyle ms → płytka Maszyna zamyka zawór główny i sekcje. */
    const val FAILSAFE_MS = 500
}

enum class ValveType { ARAG_2PIN, ARAG_3PIN }

/**
 * PGN 160 — polecenie dla opryskiwacza.
 *
 * [0] licznik (0–255, rośnie z każdą ramką)
 * [1] flagi: bit0 zawór główny otwarty, bit1 oświetlenie robocze, bit2 zawory 3‑pin (0 = 2‑pin)
 * [2..3] maska sekcji (bit0 = sekcja 1 … bit8 = sekcja 9), 1 = otwarta
 * [4] liczba sekcji 1–9
 * [5] czas zasilania zaworu 2‑pin po przestawieniu, ×100 ms (0 = domyślnie 3 s)
 * [6..7] impuls zaworu regulacyjnego w ms, ze znakiem: + otwiera (więcej cieczy), − zamyka.
 *        Wykonywany raz dla danego licznika (powtórzona ramka nie dubluje impulsu).
 */
data class SprayerCommand(
    val counter: Int,
    val mainOpen: Boolean,
    val sections: Int,
    val sectionCount: Int,
    val valveType: ValveType = ValveType.ARAG_3PIN,
    val workLights: Boolean = false,
    val twoPinDriveMs: Int = 3000,
    val regPulseMs: Int = 0,
) {
    init {
        require(sectionCount in 1..SprayerPgn.MAX_SECTIONS) { "Liczba sekcji 1–9: $sectionCount" }
    }

    fun data(): ByteArray {
        val d = ByteArray(8)
        d[0] = (counter and 0xFF).toByte()
        var f = 0
        if (mainOpen) f = f or 1
        if (workLights) f = f or 2
        if (valveType == ValveType.ARAG_3PIN) f = f or 4
        d[1] = f.toByte()
        val m = sections and ((1 shl sectionCount) - 1)
        d[2] = (m and 0xFF).toByte()
        d[3] = (m shr 8).toByte()
        d[4] = sectionCount.toByte()
        d[5] = (twoPinDriveMs / 100).coerceIn(0, 255).toByte()
        val p = regPulseMs.coerceIn(-32768, 32767)
        d[6] = (p and 0xFF).toByte()
        d[7] = (p shr 8).toByte()
        return d
    }

    fun toFrame(): ByteArray = AogFrame.build(SprayerPgn.COMMAND, data())

    fun isSectionOpen(i: Int) = sections and (1 shl i) != 0

    companion object {
        fun from(frame: Frame): SprayerCommand? {
            if (frame.pgn != SprayerPgn.COMMAND || frame.data.size < 8) return null
            val d = frame.data
            val n = d[4].u8()
            if (n !in 1..SprayerPgn.MAX_SECTIONS) return null
            val f = d[1].u8()
            val t = d[5].u8()
            return SprayerCommand(
                counter = d[0].u8(),
                mainOpen = f and 1 != 0,
                workLights = f and 2 != 0,
                valveType = if (f and 4 != 0) ValveType.ARAG_3PIN else ValveType.ARAG_2PIN,
                sections = d[2].u8() or (d[3].u8() shl 8),
                sectionCount = n,
                twoPinDriveMs = if (t == 0) 3000 else t * 100,
                regPulseMs = (d[6].u8() or (d[7].u8() shl 8)).toShort().toInt(),
            )
        }
    }
}

/**
 * PGN 161 — stan opryskiwacza.
 *
 * [0] licznik ostatnio wykonanego polecenia
 * [1] flagi: bit0 łączność CAN z płytką Maszyna, bit1 zawór główny otwarty, bit2 oświetlenie wł.,
 *     bit3 zabezpieczenie aktywne (brak poleceń > 0,5 s — wszystko zamknięte)
 * [2..5] licznik impulsów przepływomierza od startu (uint32, przekręca się)
 * [6..9] czas między dwoma ostatnimi impulsami w µs (uint32, 0 = brak impulsów od 2 s)
 * [10..11] ciśnienie ×0,01 bar (0xFFFF = brak czujnika)
 * [12..13] maska błędów wyjść sekcji (przerwa lub zwarcie)
 * [14] błędy: bit0 zawór główny, bit1 zawór regulacyjny, bit2 oświetlenie, bit3 napięcie zasilania
 * [15] napięcie zasilania ×0,1 V
 */
data class SprayerStatus(
    val counter: Int,
    val linkOk: Boolean,
    val mainOpen: Boolean,
    val workLights: Boolean,
    val failsafe: Boolean,
    val pulseCount: Long,
    val pulsePeriodUs: Long,
    val pressureBar: Double?,
    val sectionFaults: Int = 0,
    val faults: Int = 0,
    val supplyVolts: Double = 0.0,
) {
    fun data(): ByteArray {
        val d = ByteArray(16)
        d[0] = (counter and 0xFF).toByte()
        var f = 0
        if (linkOk) f = f or 1
        if (mainOpen) f = f or 2
        if (workLights) f = f or 4
        if (failsafe) f = f or 8
        d[1] = f.toByte()
        putU32(d, 2, pulseCount)
        putU32(d, 6, pulsePeriodUs)
        val p = pressureBar?.let { (it * 100).toInt().coerceIn(0, 0xFFFE) } ?: 0xFFFF
        d[10] = (p and 0xFF).toByte(); d[11] = (p shr 8).toByte()
        d[12] = (sectionFaults and 0xFF).toByte(); d[13] = (sectionFaults shr 8 and 0xFF).toByte()
        d[14] = faults.toByte()
        d[15] = (supplyVolts * 10).toInt().coerceIn(0, 255).toByte()
        return d
    }

    fun toFrame(source: Int = 0x7E): ByteArray = AogFrame.build(SprayerPgn.STATUS, data(), source)

    /** Przepływ w l/min z czasu między impulsami (szybka odpowiedź także przy małym przepływie). */
    fun flowLpm(pulsesPerLiter: Double): Double =
        if (pulsePeriodUs <= 0 || pulsesPerLiter <= 0) 0.0 else 60_000_000.0 / (pulsePeriodUs * pulsesPerLiter)

    companion object {
        const val FAULT_MAIN = 1
        const val FAULT_REG = 2
        const val FAULT_LIGHTS = 4
        const val FAULT_SUPPLY = 8

        fun from(frame: Frame): SprayerStatus? {
            if (frame.pgn != SprayerPgn.STATUS || frame.data.size < 16) return null
            val d = frame.data
            val f = d[1].u8()
            val p = d[10].u8() or (d[11].u8() shl 8)
            return SprayerStatus(
                counter = d[0].u8(),
                linkOk = f and 1 != 0,
                mainOpen = f and 2 != 0,
                workLights = f and 4 != 0,
                failsafe = f and 8 != 0,
                pulseCount = u32(d, 2),
                pulsePeriodUs = u32(d, 6),
                pressureBar = if (p == 0xFFFF) null else p / 100.0,
                sectionFaults = d[12].u8() or (d[13].u8() shl 8),
                faults = d[14].u8(),
                supplyVolts = d[15].u8() / 10.0,
            )
        }

        private fun u32(d: ByteArray, i: Int): Long =
            (d[i].u8().toLong()) or (d[i + 1].u8().toLong() shl 8) or
                (d[i + 2].u8().toLong() shl 16) or (d[i + 3].u8().toLong() shl 24)
    }
}

/** Różnica liczników impulsów z uwzględnieniem przekręcenia uint32. */
fun pulseDelta(previous: Long, current: Long): Long = (current - previous) and 0xFFFF_FFFFL

private fun putU32(d: ByteArray, i: Int, v: Long) {
    d[i] = (v and 0xFF).toByte()
    d[i + 1] = (v shr 8 and 0xFF).toByte()
    d[i + 2] = (v shr 16 and 0xFF).toByte()
    d[i + 3] = (v shr 24 and 0xFF).toByte()
}

private fun Byte.u8(): Int = toInt() and 0xFF
