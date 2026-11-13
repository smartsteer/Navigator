package eu.smartsteer.navigator.ui.war

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual val warCamInApp: Boolean = false

/** Windows: kamera nadaje z okna przeglądarki (openUrl), tu nic nie rysujemy. */
@Composable
actual fun WarCamPane(url: String, modifier: Modifier) {}
