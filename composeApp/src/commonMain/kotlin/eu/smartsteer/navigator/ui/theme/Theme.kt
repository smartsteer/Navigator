package eu.smartsteer.navigator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import eu.smartsteer.navigator.ui.DisplayPrefs
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kolory interfejsu: ciemny motyw do kabiny (domyślny) i jasny „dzień” do pracy w pełnym słońcu. */
object Ss {
    val Ground: Color get() = if (DisplayPrefs.day) Color(0xFFF1F4F0) else Color(0xFF0A0D0B)
    val Surface: Color get() = if (DisplayPrefs.day) Color(0xFFFFFFFF) else Color(0xFF121614)
    val SurfaceHigh: Color get() = if (DisplayPrefs.day) Color(0xFFE4E9E4) else Color(0xFF1E2622)
    val Panel2: Color get() = if (DisplayPrefs.day) Color(0xFFEDF1ED) else Color(0xFF1A211E)
    val Border: Color get() = if (DisplayPrefs.day) Color(0xFFCDD5CF) else Color(0xFF26302C)
    val BorderStrong: Color get() = if (DisplayPrefs.day) Color(0xFFB4BFB8) else Color(0xFF2F3A35)
    val Dim: Color get() = if (DisplayPrefs.day) Color(0xFF98A39D) else Color(0xFF3A4641)
    val Text: Color get() = if (DisplayPrefs.day) Color(0xFF0F1411) else Color(0xFFEEF2EF)
    val TextSoft: Color get() = if (DisplayPrefs.day) Color(0xFF2C3732) else Color(0xFFC9D2CE)
    val TextMuted: Color get() = if (DisplayPrefs.day) Color(0xFF56625C) else Color(0xFF9AA7A1)
    val Accent: Color get() = if (DisplayPrefs.day) Color(0xFF17A045) else Color(0xFF3DDC6A) // zieleń WAR (znak WAR)
    val AccentSoft: Color get() = if (DisplayPrefs.day) Color(0xFFD3F1DC) else Color(0xFF12301C)
    val OnAccent: Color get() = if (DisplayPrefs.day) Color(0xFFFFFFFF) else Color(0xFF04140A)
    val Warning: Color get() = if (DisplayPrefs.day) Color(0xFFD27D00) else Color(0xFFFFB547)
    val OnWarning: Color get() = if (DisplayPrefs.day) Color(0xFFFFFFFF) else Color(0xFF1A1205)
    val Info: Color get() = if (DisplayPrefs.day) Color(0xFF1677B8) else Color(0xFF5CC8FF)
    val MapGround: Color get() = if (DisplayPrefs.day) Color(0xFFDCE5D8) else Color(0xFF16201A)
    val FieldFill: Color get() = if (DisplayPrefs.day) Color(0xFFC9DBC3) else Color(0xFF1A2620)
    val MapLine: Color get() = if (DisplayPrefs.day) Color(0xFF34403A) else Color(0xFFD9E2DD)
    val TractorBody: Color get() = if (DisplayPrefs.day) Color(0xFFD27D00) else Color(0xFFFFB547)
    /** Druga zieleń WAR (liść w znaku). */
    val Leaf: Color get() = if (DisplayPrefs.day) Color(0xFF3E9B30) else Color(0xFF5CBF4A)
    /** Aktywna linia prowadzenia — żółta, dobrze widoczna na zielonym pokryciu. */
    val GuideLine: Color get() = if (DisplayPrefs.day) Color(0xFFC99A00) else Color(0xFFF5D90A)
    /** Ciągnik na mapie. */
    val Tractor: Color get() = if (DisplayPrefs.day) Color(0xFFE0352A) else Color(0xFFFF5A4E)
    /** Duża odchyłka, brak GPS. */
    val Danger: Color get() = if (DisplayPrefs.day) Color(0xFFD0261B) else Color(0xFFFF5A4E)
    /** Pasek górny i pływające panele mapy. */
    val Bar: Color get() = if (DisplayPrefs.day) Color(0xFFFAFBF9) else Color(0xFF0E1210)
}

@Composable
private fun scheme() = (if (DisplayPrefs.day) lightColorScheme() else darkColorScheme()).copy(
    primary = Ss.Accent,
    onPrimary = Ss.OnAccent,
    secondary = Ss.Warning,
    background = Ss.Ground,
    onBackground = Ss.Text,
    surface = Ss.Surface,
    onSurface = Ss.Text,
    surfaceVariant = Ss.SurfaceHigh,
    onSurfaceVariant = Ss.TextMuted,
    outline = Ss.Border,
)

@Composable
fun SmartSteerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme(), content = content)
}
