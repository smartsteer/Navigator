package eu.smartsteer.navigator.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

actual fun pickImageFile(onResult: (ByteArray?) -> Unit) {
    val d = FileDialog(null as Frame?, "Wybierz obraz ciągnika (PNG, widok z góry, przód u góry)", FileDialog.LOAD)
    d.setFilenameFilter { _, n -> n.lowercase().let { it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg") } }
    d.file = "*.png;*.jpg;*.jpeg"
    d.isVisible = true
    val f = d.files?.firstOrNull() ?: d.file?.let { n -> d.directory?.let { File(it, n) } }
    onResult(f?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() })
}
