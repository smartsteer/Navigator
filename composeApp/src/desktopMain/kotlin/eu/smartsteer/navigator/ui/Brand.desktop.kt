package eu.smartsteer.navigator.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap

actual fun brandImage(): ImageBitmap? = try {
    Thread.currentThread().contextClassLoader?.getResourceAsStream("brand.png")?.use { loadImageBitmap(it) }
} catch (e: Exception) {
    null
}
