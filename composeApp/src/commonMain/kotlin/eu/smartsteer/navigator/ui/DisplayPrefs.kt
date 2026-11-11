package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.settings.vehicleFiles

/**
 * Ustawienia ekranu (zapisywane w ekran.cfg):
 *  - tryb tablet: interfejs powiększa się do całego ekranu (większe przyciski na dużym tablecie / monitorze),
 *  - pełny ekran: okno bez ramek i paska zadań (Windows; F11 przełącza). Na Androidzie aplikacja zawsze działa na pełnym ekranie.
 */
object DisplayPrefs {
    private const val FILE = "ekran.cfg"
    private val saved: Map<String, String> = runCatching {
        vehicleFiles().read(FILE).orEmpty().lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i > 0) l.substring(0, i).trim() to l.substring(i + 1).trim() else null
        }.toMap()
    }.getOrDefault(emptyMap())

    var tablet by mutableStateOf(saved["tablet"] == "1")
        private set
    var fullScreen by mutableStateOf(saved["pelny"] == "1")
        private set
    /** Podkład mapy: indeks w TileSource (0 = brak). */
    var background by mutableStateOf(saved["podklad"]?.toIntOrNull() ?: 0)
        private set

    fun updateBackground(i: Int) { background = i; save() }

    /** Ustawia okno Windows (main.kt); na Androidzie pełny ekran jest zawsze. */
    var fullScreenSupported = false

    fun updateTablet(on: Boolean) { tablet = on; save() }
    fun updateFullScreen(on: Boolean) { fullScreen = on; save() }
    fun toggleFullScreen() = updateFullScreen(!fullScreen)

    private fun save() {
        runCatching { vehicleFiles().write(FILE, "tablet=${if (tablet) 1 else 0}\npelny=${if (fullScreen) 1 else 0}\npodklad=$background\n") }
    }
}
