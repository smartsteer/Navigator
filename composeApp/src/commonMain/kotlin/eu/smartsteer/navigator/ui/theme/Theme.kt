package eu.smartsteer.navigator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Kolory z projektu interfejsu SmartSteer Navigator (ciemny motyw do kabiny). */
object Ss {
    val Ground = Color(0xFF0C100E)
    val Surface = Color(0xFF141A18)
    val SurfaceHigh = Color(0xFF1E2622)
    val Border = Color(0xFF26302C)
    val Text = Color(0xFFEEF2EF)
    val TextMuted = Color(0xFF9AA7A1)
    val Accent = Color(0xFF8BE36B)
    val OnAccent = Color(0xFF0B1408)
    val Warning = Color(0xFFFFB547)
    val MapGround = Color(0xFF141C18)
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
