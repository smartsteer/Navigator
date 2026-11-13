package eu.smartsteer.navigator.ui

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import eu.smartsteer.navigator.R
import eu.smartsteer.navigator.core.settings.AndroidStorage

actual fun brandImage(): ImageBitmap? = try {
    AndroidStorage.resources?.let { BitmapFactory.decodeResource(it, R.drawable.brand)?.asImageBitmap() }
} catch (e: Exception) {
    null
}

actual fun splashImage(): ImageBitmap? = try {
    AndroidStorage.resources?.let { BitmapFactory.decodeResource(it, R.drawable.splash)?.asImageBitmap() }
} catch (e: Exception) {
    null
}
