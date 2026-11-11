package eu.smartsteer.navigator.ui.voice

/** Mowa po polsku z systemu (Windows: głos „Paulina” z pakietu języka polskiego; Android: syntezator Google). */
expect object Speech {
    /** Czy na tym urządzeniu jest polski głos. */
    fun available(): Boolean
    /** Mówi zdanie (kolejne czekają w kolejce). */
    fun say(text: String)
    /** Nazwa użytego głosu (do pokazania w ustawieniach). */
    fun voiceName(): String
}
