package eu.smartsteer.navigator.core.sound

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Rodzaje dźwięków — każdy inny, żeby rozpoznać go bez patrzenia na ekran. */
enum class SoundKind {
    /** Autoster włączony: dwa tony w górę. */
    AUTOSTEER_ON,
    /** Autoster wyłączony (przez rolnika): dwa tony w dół. */
    AUTOSTEER_OFF,
    /** Alarm: autoster wyłączył się sam, GPS zniknął, koniec pola — trzy wysokie piknięcia. */
    ALARM,
    /** Ostrzeżenie: utrata RTK FIX — dwa średnie piknięcia. */
    WARNING,
    /** Zaraz zawrót. */
    UTURN,
    /** Sekcje: krótki niski klik przy włączeniu / wyłączeniu. */
    SECTION_ON, SECTION_OFF,
    /** Nowe zlecenie z WAR. */
    ORDER,
}

/**
 * Generator dźwięków: proste tony z łagodnym narastaniem i wygasaniem (bez plików z dźwiękiem).
 * PCM 16 bit, mono, [RATE] Hz.
 */
object Tones {
    const val RATE = 22050

    /** (częstotliwość Hz, czas ms) — 0 Hz = cisza. */
    fun pattern(kind: SoundKind): List<Pair<Int, Int>> = when (kind) {
        SoundKind.AUTOSTEER_ON -> listOf(660 to 110, 0 to 30, 990 to 160)
        SoundKind.AUTOSTEER_OFF -> listOf(990 to 110, 0 to 30, 550 to 200)
        SoundKind.ALARM -> listOf(1400 to 120, 0 to 70, 1400 to 120, 0 to 70, 1400 to 220)
        SoundKind.WARNING -> listOf(880 to 140, 0 to 90, 880 to 140)
        SoundKind.UTURN -> listOf(740 to 90, 0 to 40, 740 to 90, 0 to 40, 1100 to 140)
        SoundKind.SECTION_ON -> listOf(420 to 45)
        SoundKind.SECTION_OFF -> listOf(300 to 45)
        SoundKind.ORDER -> listOf(784 to 120, 988 to 120, 1175 to 220)
    }

    fun pcm(kind: SoundKind, volume: Double = 0.7): ShortArray {
        val parts = pattern(kind)
        val total = parts.sumOf { it.second } * RATE / 1000
        val out = ShortArray(total)
        var i = 0
        for ((f, ms) in parts) {
            val n = ms * RATE / 1000
            val ramp = min(n / 4, RATE / 200) // 5 ms narastania/wygasania — bez trzasków
            for (k in 0 until n) {
                val env = when {
                    f == 0 -> 0.0
                    k < ramp -> k / ramp.toDouble()
                    k > n - ramp -> (n - k) / ramp.toDouble()
                    else -> 1.0
                }
                out[i++] = (sin(2 * PI * f * k / RATE) * env * volume * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
