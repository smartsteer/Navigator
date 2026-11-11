package eu.smartsteer.navigator.ui.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import eu.smartsteer.navigator.ui.map.decodeTile

/** Czytnik obrazu z kamery IP: strumień MJPEG albo pojedyncze zdjęcia JPEG (odpytywane). */
expect class CameraReader() {
    /** Wątek w tle; [onJpeg] dostaje kolejne klatki JPEG, [onError] opis błędu. */
    fun start(url: String, onJpeg: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun stop()
}

/** Podgląd z kamery IP (cofanie, siewnik, przyczepa) w rogu mapy. */
object Camera {
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var status by mutableStateOf("Wyłączona")
        private set
    var running by mutableStateOf(false)
        private set
    private var reader: CameraReader? = null

    fun start(url: String) {
        stop()
        if (url.isBlank()) { status = "Wpisz adres kamery"; return }
        status = "Łączę…"
        running = true
        reader = CameraReader().also { r ->
            r.start(url.trim(), onJpeg = { b -> decodeTile(b)?.let { frame = it; status = "Obraz" } }, onError = { e -> status = e })
        }
    }

    fun stop() {
        reader?.stop(); reader = null
        running = false
        frame = null
        status = "Wyłączona"
    }
}
