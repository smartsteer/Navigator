package eu.smartsteer.navigator.ui.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import eu.smartsteer.navigator.ui.power.PowerPlatform
import java.io.File

actual object VoiceAudio {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val buf = ArrayList<ShortArray>()
    @Volatile private var on = false
    private var player: MediaPlayer? = null

    actual val recording: Boolean get() = on

    @SuppressLint("MissingPermission")
    actual fun start(): String? {
        if (on) return null
        val act = PowerPlatform.activity?.get() ?: return "Brak dostępu do systemu"
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 4713)
            return "Zezwól na mikrofon (okno systemu) i naciśnij jeszcze raz"
        }
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return "Mikrofon nie obsługuje nagrywania"
        val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return "Nie udało się włączyć mikrofonu"
        synchronized(buf) { buf.clear() }
        rec = r
        on = true
        r.startRecording()
        thread = Thread({
            val b = ShortArray(1600)
            while (on) {
                val n = r.read(b, 0, b.size)
                if (n > 0) synchronized(buf) { buf.add(b.copyOf(n)) }
            }
        }, "glos-nagranie").apply { isDaemon = true; start() }
        return null
    }

    actual fun stop(): ShortArray? {
        if (!on) return null
        on = false
        runCatching { thread?.join(500) }
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        rec = null
        val all = synchronized(buf) { buf.toList().also { buf.clear() } }
        val out = ShortArray(all.sumOf { it.size })
        var i = 0
        for (c in all) { c.copyInto(out, i); i += c.size }
        return out.takeIf { it.isNotEmpty() }
    }

    actual fun play(wav: ByteArray) {
        val ctx = PowerPlatform.activity?.get()?.applicationContext ?: return
        runCatching {
            val f = File(ctx.cacheDir, "glos.wav").apply { writeBytes(wav) }
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        }
    }
}
