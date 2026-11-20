package eu.smartsteer.navigator

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import eu.smartsteer.navigator.ui.DisplayPrefs
import kotlinx.coroutines.delay
import kotlin.system.exitProcess
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window as AwtWindow

fun main() {
    // tylko jeden Navigator naraz — drugie uruchomienie pokazuje okno pierwszego (SingleInstance)
    if (!SingleInstance.acquire()) exitProcess(0)
    navigatorApp()
}

private fun navigatorApp() = application {
    // ikona okna i paska zadań (z branding/logo.png — tools/make_icons.py)
    val icon = remember {
        Thread.currentThread().contextClassLoader?.getResourceAsStream("icon.png")?.use { BitmapPainter(loadImageBitmap(it)) }
    }
    DisplayPrefs.fullScreenSupported = true
    val state = rememberWindowState(width = 1280.dp, height = 800.dp)
    // Okno bez ramki (paska tytułu) na cały ekran — tablet w kabinie. WindowPlacement.Fullscreen na Windows przy skalowaniu
    // ekranu 125–200 % zostawiał pasek tytułu i pas pulpitu z boku, dlatego granice liczymy sami z ekranu, na którym jest okno:
    //  - PEŁNY EKRAN (Ustawienia → Ekran albo F11): cały ekran, pasek zadań zakryty,
    //  - wyłączony: cały obszar roboczy (pasek zadań Windows widoczny).
    Window(
        icon = icon,
        onCloseRequest = { eu.smartsteer.navigator.ui.power.AppExit.onRequest?.invoke() ?: exitApplication() },
        title = "WAR Navigator",
        state = state,
        undecorated = true,
        resizable = false,
        onPreviewKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.F11) { DisplayPrefs.toggleFullScreen(); true } else false
        },
    ) {
        SingleInstance.onActivate = {
            EventQueue.invokeLater {
                if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                window.isAlwaysOnTop = true   // Windows nie pozwala zwykłym toFront() wyskoczyć nad inne okno
                window.toFront()
                window.requestFocus()
                window.isAlwaysOnTop = false
            }
        }
        LaunchedEffect(DisplayPrefs.fullScreen) {
            var last: Rectangle? = null
            while (true) {
                // co 2 s: obrót tabletu albo zmiana rozdzielczości / skalowania → dopasuj okno od nowa
                val r = screenArea(window, DisplayPrefs.fullScreen)
                if (r != last) {
                    applyBounds(state, r)
                    last = r
                }
                delay(2000)
            }
        }
        App()
    }
}

/** Obszar ekranu z oknem w jednostkach okna (te same co dp w WindowState — Windows sam przelicza skalowanie). */
private fun screenArea(w: AwtWindow, full: Boolean): Rectangle {
    val gc = w.graphicsConfiguration
    val b = gc.bounds
    if (full) return Rectangle(b)
    val i = Toolkit.getDefaultToolkit().getScreenInsets(gc)
    return Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
}

private fun applyBounds(state: WindowState, r: Rectangle) {
    state.placement = WindowPlacement.Floating
    state.position = WindowPosition(r.x.dp, r.y.dp)
    state.size = DpSize(r.width.dp, r.height.dp)
}
