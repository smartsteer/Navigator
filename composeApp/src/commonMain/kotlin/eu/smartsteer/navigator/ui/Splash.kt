package eu.smartsteer.navigator.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.smartsteer.navigator.core.update.platformUpdater

/** Tło ekranu startowego i zamykania — zawsze ciemne, jak logo WAR (niezależnie od trybu dzień/noc). */
private val SplashBg = Color(0xFF0B0F0C)
private val SplashText = Color(0xFFB9C7BB)
private val SplashAccent = Color(0xFF3DDC6A)

/**
 * Pełny ekran z logo WAR Navigator: przy starcie programu i przy zamykaniu (zasłania zmianę ekranów,
 * gdy program wyłącza sprzęt). Przechwytuje dotyk, żeby nic pod spodem nie dało się kliknąć.
 */
@Composable
fun SplashScreen(message: String, visible: Boolean = true, onHidden: () -> Unit = {}) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) a.animateTo(1f, tween(250)) else { a.animateTo(0f, tween(400)); onHidden() }
    }
    val logo = remember { splashImage() }
    val version = remember { runCatching { platformUpdater().currentVersion }.getOrNull()?.takeIf { it.isNotBlank() } }
    Box(
        Modifier.fillMaxSize().alpha(a.value).background(SplashBg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (logo != null) Image(logo, contentDescription = "WAR Navigator", modifier = Modifier.height(260.dp))
            else Text("WAR Navigator", color = SplashAccent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Text(message, color = SplashText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(modifier = Modifier.width(220.dp).height(4.dp), color = SplashAccent, trackColor = Color(0xFF1E2A21))
        }
        Text(
            "SmartSteer${version?.let { " · wersja $it" } ?: ""}",
            color = SplashText.copy(alpha = 0.6f), fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}
