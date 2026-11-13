package eu.smartsteer.navigator.ui.war

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Czy WAR Cam działa w okienku w programie (Android: strona kamery WAR w WebView). Na Windows — przeglądarka. */
expect val warCamInApp: Boolean

/**
 * WAR Cam: strona kamery z serwera WAR (`/kamera#s=<skrzynka prac>&auto=1&n=<nazwa>`) nadaje obraz z kamery tabletu
 * do telefonu gospodarza (WebRTC) — tak samo jak WAR Sync. Navigator tylko otwiera tę stronę (bez kodu WAR).
 */
@Composable
expect fun WarCamPane(url: String, modifier: Modifier)
