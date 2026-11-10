package eu.smartsteer.navigator

import androidx.compose.runtime.remember
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
    Window(
        icon = icon,
        onCloseRequest = ::exitApplication,
        title = "SmartSteer Navigator",
        state = rememberWindowState(width = 1280.dp, height = 800.dp),
    ) {
        App()
    }
}
