package eu.smartsteer.navigator.core.sound

/** Odtwarzanie (Windows: javax.sound, Android: AudioTrack). Nie blokuje — gra w osobnym wątku. */
expect object SoundPlayer {
    fun play(kind: SoundKind)
}
