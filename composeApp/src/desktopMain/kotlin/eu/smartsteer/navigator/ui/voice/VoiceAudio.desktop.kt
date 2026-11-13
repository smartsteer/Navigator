package eu.smartsteer.navigator.ui.voice

import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

actual object VoiceAudio {
    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    private val buf = java.io.ByteArrayOutputStream()
    @Volatile private var on = false

    actual val recording: Boolean get() = on

    actual fun start(): String? {
        if (on) return null
        val fmt = AudioFormat(16_000f, 16, 1, true, false)
        val l = runCatching { AudioSystem.getTargetDataLine(fmt).apply { open(fmt) } }.getOrNull()
            ?: return "Nie widzę mikrofonu (Windows: Ustawienia → Prywatność → Mikrofon)"
        synchronized(buf) { buf.reset() }
        line = l
        on = true
        l.start()
        thread = Thread({
            val b = ByteArray(3200)
            while (on) {
                val n = l.read(b, 0, b.size)
                if (n > 0) synchronized(buf) { buf.write(b, 0, n) }
            }
        }, "glos-nagranie").apply { isDaemon = true; start() }
        return null
    }

    actual fun stop(): ShortArray? {
        if (!on) return null
        on = false
        runCatching { line?.stop(); line?.close() }
        runCatching { thread?.join(500) }
        line = null
        val b = synchronized(buf) { buf.toByteArray().also { buf.reset() } }
        if (b.size < 2) return null
        return ShortArray(b.size / 2) { i -> ((b[2 * i].toInt() and 0xFF) or (b[2 * i + 1].toInt() shl 8)).toShort() }
    }

    actual fun play(wav: ByteArray) {
        Thread({
            runCatching {
                val s = AudioSystem.getAudioInputStream(ByteArrayInputStream(wav))
                val c = AudioSystem.getClip()
                c.open(s)
                c.addLineListener { e -> if (e.type == javax.sound.sampled.LineEvent.Type.STOP) c.close() }
                c.start()
            }
        }, "glos-odtwarzanie").apply { isDaemon = true; start() }
    }
}
