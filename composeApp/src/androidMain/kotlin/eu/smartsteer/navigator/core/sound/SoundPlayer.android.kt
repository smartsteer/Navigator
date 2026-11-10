package eu.smartsteer.navigator.core.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors

actual object SoundPlayer {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "dzwieki").apply { isDaemon = true } }

    actual fun play(kind: SoundKind) {
        exec.execute {
            try {
                val pcm = Tones.pcm(kind)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Tones.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / Tones.RATE + 50)
                track.release()
            } catch (_: Exception) {
            }
        }
    }
}
