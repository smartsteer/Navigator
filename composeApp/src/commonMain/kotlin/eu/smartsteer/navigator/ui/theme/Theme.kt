package eu.smartsteer.navigator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kolory z projektu interfejsu SmartSteer Navigator (ciemny motyw do kabiny). */
object Ss {
    val Ground = Color(0xFF0A0D0B)
    val Surface = Color(0xFF121614)
    val SurfaceHigh = Color(0xFF1E2622)
    val Panel2 = Color(0xFF1A211E)
    val Border = Color(0xFF26302C)
    val BorderStrong = Color(0xFF2F3A35)
    val Dim = Color(0xFF3A4641)
    val Text = Color(0xFFEEF2EF)
    val TextSoft = Color(0xFFC9D2CE)
    val TextMuted = Color(0xFF9AA7A1)
    val Accent = Color(0xFF3DDC6A) // zieleń WAR (znak WAR)
    val AccentSoft = Color(0xFF12301C)
    val OnAccent = Color(0xFF04140A)
    val Warning = Color(0xFFFFB547)
    val OnWarning = Color(0xFF1A1205)
    val Info = Color(0xFF5CC8FF)
    val MapGround = Color(0xFF16201A)
    val FieldFill = Color(0xFF1A2620)
    val MapLine = Color(0xFFD9E2DD)
    val TractorBody = Color(0xFFFFB547)
    /** Druga zieleń WAR (liść w znaku). */
    val Leaf = Color(0xFF5CBF4A)
    /** Aktywna linia prowadzenia — żółta, dobrze widoczna na zielonym pokryciu. */
    val GuideLine = Color(0xFFF5D90A)
    /** Ciągnik na mapie. */
    val Tractor = Color(0xFFFF5A4E)
    /** Duża odchyłka, brak GPS. */
    val Danger = Color(0xFFFF5A4E)
    /** Pasek górny i pływające panele mapy. */
    val Bar = Color(0xFF0E1210)
}

private val scheme = darkColorScheme(
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
    MaterialTheme(colorScheme = scheme, content = content)
}
