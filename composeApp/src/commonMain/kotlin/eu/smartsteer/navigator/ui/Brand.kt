package eu.smartsteer.navigator.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Napis „WAR Navigator” ze znakiem WAR (branding/war-navigator-naglowek.svg → PNG). Null = nie udało się wczytać. */
/** [day] = wersja na jasny motyw („Navigator” ciemnym kolorem zamiast białego). */
expect fun brandImage(day: Boolean = false): ImageBitmap?

/** Pionowe logo „WAR Navigator” na ekran startowy i zamykania (branding/war-navigator-logo.svg → PNG). */
expect fun splashImage(): ImageBitmap?
