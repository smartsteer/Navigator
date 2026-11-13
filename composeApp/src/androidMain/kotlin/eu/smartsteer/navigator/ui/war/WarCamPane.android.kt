package eu.smartsteer.navigator.ui.war

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import eu.smartsteer.navigator.ui.power.PowerPlatform

actual val warCamInApp: Boolean = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun WarCamPane(url: String, modifier: Modifier) {
    val holder = remember { arrayOfNulls<WebView>(1) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // kamera i mikrofon: okno systemu przy pierwszym włączeniu
            val act = PowerPlatform.activity?.get()
            val brak = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                .filter { act?.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (brak.isNotEmpty()) act?.requestPermissions(brak.toTypedArray(), 4712)
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = object : WebChromeClient() {
                    override fun onPermissionRequest(request: PermissionRequest) {
                        // tylko kamera i mikrofon
                        val ok = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
                        if (ok.isNotEmpty()) request.grant(ok.toTypedArray()) else request.deny()
                    }
                }
                loadUrl(url)
                holder[0] = this
                // po zgodzie w oknie systemu strona z kamerą ładuje się jeszcze raz (wcześniej kamera była zablokowana)
                if (brak.isNotEmpty() && act != null) {
                    var proby = 0
                    val czekaj = object : Runnable {
                        override fun run() {
                            if (holder[0] !== this@apply) return
                            if (act.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { reload(); return }
                            if (++proby < 60) postDelayed(this, 1000)
                        }
                    }
                    postDelayed(czekaj, 1000)
                }
            }
        },
    )
    DisposableEffect(url) { onDispose { holder[0]?.apply { loadUrl("about:blank"); destroy() }; holder[0] = null } }
}
