package eu.smartsteer.navigator.ui

import android.graphics.BitmapFactory
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

/** Wybór obrazu z galerii / plików: okno rejestruje MainActivity (registerForActivityResult). */
object ImagePicker {
    var launcher: ActivityResultLauncher<String>? = null
    var pending: ((ByteArray?) -> Unit)? = null
}

actual fun pickImageFile(onResult: (ByteArray?) -> Unit) {
    val l = ImagePicker.launcher ?: return onResult(null)
    ImagePicker.pending = onResult
    l.launch("image/*")
}
