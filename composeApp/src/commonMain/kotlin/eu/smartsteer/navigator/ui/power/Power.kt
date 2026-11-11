package eu.smartsteer.navigator.ui.power

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Zasilanie z instalacji ciągnika: true = jest, false = praca na baterii, null = nie wiadomo (komputer bez baterii). */
object PowerState {
    var external by mutableStateOf<Boolean?>(null)
}

/** Funkcje systemu: autostart, obserwacja zasilania, zamknięcie. */
expect object PowerPlatform {
    /** Czy system pozwala ustawić autostart z programu. */
    val autostartSupported: Boolean
    fun isAutostart(): Boolean
    /** Zwraca błąd albo null. */
    fun setAutostart(on: Boolean): String?
    /** Zaczyna aktualizować [PowerState.external]. */
    fun startMonitor()
    /** Zamyka program, a przy [shutdownSystem] także komputer (Windows). */
    fun exit(shutdownSystem: Boolean)
    val canShutdownSystem: Boolean
}
