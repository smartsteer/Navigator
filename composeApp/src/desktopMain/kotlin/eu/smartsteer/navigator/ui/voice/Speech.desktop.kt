package eu.smartsteer.navigator.ui.voice

import java.io.BufferedWriter
import java.util.concurrent.LinkedBlockingQueue

/** Jeden proces PowerShell z System.Speech: czyta zdania z wejścia i mówi polskim głosem (jeśli jest). */
actual object Speech {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private val queue = LinkedBlockingQueue<String>()
    @Volatile private var voice = "—"
    @Volatile private var started = false
    @Volatile private var ok = windows

    private fun start() {
        if (started || !windows) return
        started = true
        Thread({
            while (true) {
                try {
                    val script = listOf(
                        "[Console]::InputEncoding = [System.Text.Encoding]::UTF8",
                        "Add-Type -AssemblyName System.Speech",
                        "${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer",
                        "${'$'}pl = ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled -and ${'$'}_.VoiceInfo.Culture.Name -eq 'pl-PL' } | Select-Object -First 1",
                        "if (${'$'}pl) { ${'$'}s.SelectVoice(${'$'}pl.VoiceInfo.Name); [Console]::Out.WriteLine('VOICE ' + ${'$'}pl.VoiceInfo.Name) } else { [Console]::Out.WriteLine('VOICE brak') }",
                        "[Console]::Out.Flush()",
                        "while ((${'$'}line = [Console]::In.ReadLine()) -ne ${'$'}null) { if (${'$'}line.Length -gt 0) { ${'$'}s.Speak(${'$'}line) } }",
                    ).joinToString("\r\n")
                    val f = java.io.File.createTempFile("war-mowa", ".ps1").apply { deleteOnExit(); writeText(script, Charsets.UTF_8) }
                    val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", f.absolutePath)
                        .redirectErrorStream(true).start()
                    val w: BufferedWriter = p.outputStream.bufferedWriter(Charsets.UTF_8)
                    Thread({
                        p.inputStream.bufferedReader().forEachLine { l -> if (l.startsWith("VOICE ")) { voice = l.removePrefix("VOICE "); ok = voice != "brak" } }
                    }, "speech-out").apply { isDaemon = true }.start()
                    while (p.isAlive) {
                        val t = queue.take()
                        w.write(t.replace("\n", " ") + "\n")
                        w.flush()
                    }
                } catch (_: Exception) {}
                Thread.sleep(5000)
            }
        }, "speech").apply { isDaemon = true }.start()
    }

    actual fun available(): Boolean { start(); return ok }
    actual fun say(text: String) { start(); if (queue.size < 5) queue.offer(text) }
    actual fun voiceName(): String { start(); return voice }
}
