package eu.smartsteer.navigator.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap

actual fun brandImage(day: Boolean): ImageBitmap? = try {
    Thread.currentThread().contextClassLoader?.getResourceAsStream(if (day) "brand_day.png" else "brand.png")?.use { loadImageBitmap(it) }
} catch (e: Exception) {
    null
}

actual fun splashImage(): ImageBitmap? = try {
    Thread.currentThread().contextClassLoader?.getResourceAsStream("splash.png")?.use { loadImageBitmap(it) }
} catch (e: Exception) {
    null
}
