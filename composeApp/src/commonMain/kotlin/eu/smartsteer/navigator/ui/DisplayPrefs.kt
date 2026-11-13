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

    /** Jasny motyw „dzień” (czytelny w pełnym słońcu). */
    var day by mutableStateOf(saved["dzien"] == "1")
        private set
    fun updateDay(on: Boolean) { day = on; save() }

    /** Kolor śladu (pokrycia) tej maszyny, „#RRGGBB”; pusty = domyślny zielony. Inne Navigatory gospodarstwa rysują jej pracę tym kolorem. */
    var trailColor by mutableStateOf(saved["kolor"]?.takeIf { TrailColors.valid(it) } ?: "")
        private set
    fun updateTrailColor(hex: String) { trailColor = if (TrailColors.valid(hex)) hex.uppercase() else ""; save() }

    /** Ikona ciągnika na mapie: 0 = strzałka, 1 = ciągnik z góry (domyślnie), 2 = własny obraz (TractorIcons). */
    var tractorIcon by mutableStateOf(saved["ikona"]?.toIntOrNull()?.coerceIn(0, 2) ?: 1)
        private set
    fun updateTractorIcon(i: Int) { tractorIcon = i.coerceIn(0, 2); save() }

    /** Kolor ciągnika „#RRGGBB”; pusty = czerwony z motywu. */
    var tractorColor by mutableStateOf(saved["kolor_c"]?.takeIf { TrailColors.valid(it) } ?: "")
        private set
    fun updateTractorColor(hex: String) { tractorColor = if (TrailColors.valid(hex)) hex.uppercase() else ""; save() }

    /** Ustawia okno Windows (main.kt); na Androidzie pełny ekran jest zawsze. */
    var fullScreenSupported = false

    fun updateTablet(on: Boolean) { tablet = on; save() }
    fun updateFullScreen(on: Boolean) { fullScreen = on; save() }
    fun toggleFullScreen() = updateFullScreen(!fullScreen)

    private fun save() {
        runCatching { vehicleFiles().write(FILE, "tablet=${if (tablet) 1 else 0}\npelny=${if (fullScreen) 1 else 0}\npodklad=$background\ndzien=${if (day) 1 else 0}\nkolor=$trailColor\nikona=$tractorIcon\nkolor_c=$tractorColor\n") }
    }
}

/** Kolory śladu do wyboru (jak w AgOpenGPS) i zamiana „#RRGGBB” → ARGB. */
object TrailColors {
    val palette = listOf("#3DDC6A", "#2F9BFF", "#FFD23F", "#FF8C1A", "#FF4D4D", "#B36BFF", "#FF6EC7", "#2EE6D6", "#A0A0A0")
    fun valid(hex: String) = Regex("#[0-9A-Fa-f]{6}").matches(hex)
    /** ARGB albo null, gdy zły zapis. */
    fun argb(hex: String?): Long? = hex?.takeIf { valid(it) }?.substring(1)?.toLong(16)?.let { 0xFF000000L or it }
}
