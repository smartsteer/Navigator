package eu.smartsteer.navigator.ui.voice

/** Nagrywanie i odtwarzanie wiadomości głosowych (WAR Sync / telefon gospodarza). PCM 16 bit mono 16 kHz. */
expect object VoiceAudio {
    /** Zaczyna nagrywać; zwraca błąd dla rolnika albo null. */
    fun start(): String?
    /** Kończy nagrywanie; próbki PCM 16 kHz (null — nic nie nagrano). */
    fun stop(): ShortArray?
    val recording: Boolean
    /** Odtwarza plik WAV (PCM) przez głośnik. */
    fun play(wav: ByteArray)
}
