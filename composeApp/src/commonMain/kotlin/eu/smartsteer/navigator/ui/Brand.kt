package eu.smartsteer.navigator.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Napis „WAR Navigator” ze znakiem WAR (branding/war-navigator-naglowek.svg → PNG). Null = nie udało się wczytać. */
expect fun brandImage(): ImageBitmap?

/** Pionowe logo „WAR Navigator” na ekran startowy i zamykania (branding/war-navigator-logo.svg → PNG). */
expect fun splashImage(): ImageBitmap?
