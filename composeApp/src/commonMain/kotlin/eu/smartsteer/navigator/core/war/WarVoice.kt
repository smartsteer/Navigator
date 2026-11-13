package eu.smartsteer.navigator.core.war

import eu.smartsteer.navigator.core.ntrip.Ntrip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Wiadomości głosowe (docs/WAR.md 3.11): WAV PCM 16 bit mono 12 kHz, do 30 s.
 *  - z telefonu do tego tabletu: skrzynka `war-sync-glos:` + id maszyny + `:` + kod (jak WAR Sync), paczka `glos`,
 *  - z tabletu do telefonu: paczka `glos` do skrzynki prac (`POST /api/sync/praca` z `"glos": true`).
 */
object WarVoice {
    const val RATE = 12_000
    const val MAX_S = 30

    fun mailboxText(code: String, machineId: String) = "war-sync-glos:$machineId:$code"

    /** 16 kHz → 12 kHz (interpolacja liniowa), najwyżej 30 s, z normalizacją głośności (szczyt ok. 90%). */
    fun resample16to12(pcm: ShortArray): ShortArray {
        val n = min(pcm.size * 3 / 4, RATE * MAX_S)
        var peak = 1
        for (s in pcm) peak = max(peak, abs(s.toInt()))
        val gain = (29_000.0 / peak).coerceIn(1.0, 8.0)
        return ShortArray(n) { i ->
            val x = i * 4.0 / 3.0
            val a = x.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val f = x - a
            ((pcm[a] * (1 - f) + pcm[b] * f) * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun wav(samples: ShortArray, rate: Int = RATE): ByteArray {
        val data = samples.size * 2
        val b = ByteArray(44 + data)
        fun str(o: Int, s: String) { for (i in s.indices) b[o + i] = s[i].code.toByte() }
        fun i32(o: Int, v: Int) { for (k in 0..3) b[o + k] = (v shr (8 * k)).toByte() }
        fun i16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        str(0, "RIFF"); i32(4, 36 + data); str(8, "WAVE"); str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, 1)
        i32(24, rate); i32(28, rate * 2); i16(32, 2); i16(34, 16); str(36, "data"); i32(40, data)
        for (i in samples.indices) i16(44 + i * 2, samples[i].toInt())
        return b
    }

    fun isWav(b: ByteArray) = b.size > 44 && b.decodeToString(0, 4) == "RIFF" && b.decodeToString(8, 12) == "WAVE"

    /** Szczyt nagrania 0–1 — cisza (mikrofon wyciszony) nie idzie do WAR. */
    fun level(samples: ShortArray): Double = if (samples.isEmpty()) 0.0 else samples.maxOf { abs(it.toInt()) } / 32768.0

    fun packet(id: String, from: String, machineId: String, t: String, seconds: Double, wav: ByteArray): String = Json.obj(
        "typ" to "glos", "v" to 1, "id" to id, "od" to from, "maszyna" to machineId, "czas" to t,
        "s" to Json.Raw(((seconds * 10).toInt() / 10.0).toString()), "wav" to Ntrip.base64(wav),
    )
}

/** Wiadomość głosowa na liście (ostatnie kilka). */
data class VoiceMsg(val id: String, val from: String, val at: String, val seconds: Double, val mine: Boolean, val wav: ByteArray)
