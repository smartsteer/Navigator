package eu.smartsteer.navigator.ui.voice

import android.speech.tts.TextToSpeech
import java.util.Locale

actual object Speech {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var name = "—"

    private fun init() {
        if (tts != null) return
        val ctx = eu.smartsteer.navigator.ui.power.PowerPlatform.activity?.get()?.applicationContext ?: return
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("pl", "PL"))
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                name = tts?.voice?.name ?: "polski"
            }
        }
    }

    actual fun available(): Boolean { init(); return ready }
    actual fun say(text: String) { init(); if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null) }
    actual fun voiceName(): String { init(); return if (ready) name else "brak polskiego głosu" }
}
