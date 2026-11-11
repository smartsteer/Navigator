package eu.smartsteer.navigator

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.WindowPlacement
import eu.smartsteer.navigator.ui.DisplayPrefs
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    // ikona okna i paska zadań (z branding/logo.png — tools/make_icons.py)
    val icon = remember {
        Thread.currentThread().contextClassLoader?.getResourceAsStream("icon.png")?.use { BitmapPainter(loadImageBitmap(it)) }
    }
    DisplayPrefs.fullScreenSupported = true
    val state = rememberWindowState(width = 1280.dp, height = 800.dp)
    // pełny ekran (Ustawienia → Ekran albo F11)
    LaunchedEffect(DisplayPrefs.fullScreen) {
        state.placement = if (DisplayPrefs.fullScreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
    }
    Window(
        icon = icon,
        onCloseRequest = { eu.smartsteer.navigator.ui.power.AppExit.onRequest?.invoke() ?: exitApplication() },
        title = "WAR Navigator",
        state = state,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.F11) { DisplayPrefs.toggleFullScreen(); true } else false
        },
    ) {
        App()
    }
}
