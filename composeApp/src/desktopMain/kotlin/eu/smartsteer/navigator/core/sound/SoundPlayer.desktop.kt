package eu.smartsteer.navigator.core.sound

import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

actual object SoundPlayer {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dzwieki").apply { isDaemon = true } }

    actual fun play(kind: SoundKind) {
        exec.execute {
            try {
                val pcm = Tones.pcm(kind)
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
                }
                val fmt = AudioFormat(Tones.RATE.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getSourceDataLine(fmt)
                line.open(fmt)
                line.start()
                line.write(bytes, 0, bytes.size)
                line.drain()
                line.close()
            } catch (_: Exception) {
                // brak karty dźwiękowej — po cichu
            }
        }
    }
}
