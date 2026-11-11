package eu.smartsteer.navigator

import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    /** Bez tej blokady część tabletów odrzuca po Wi‑Fi pakiety rozgłoszeniowe z płytki AIO. */
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Ekran nie gaśnie podczas pracy w polu
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Wi-Fi bez oszczędzania energii (mniejsze opóźnienia przy łączności z płytką przez Wi-Fi)
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF, "navigator-wifi")
            ?.apply { setReferenceCounted(false); acquire() }
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("navigator-udp")
            ?.apply { setReferenceCounted(false); acquire() }
        eu.smartsteer.navigator.core.settings.AndroidStorage.filesDir = filesDir
        eu.smartsteer.navigator.core.settings.AndroidStorage.resources = resources
        eu.smartsteer.navigator.ui.power.PowerPlatform.activity = java.lang.ref.WeakReference(this)
        setContent { App() }
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Pełny ekran w kabinie: paski systemu chowają się, wracają po przeciągnięciu od krawędzi. */
    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    override fun onDestroy() {
        multicastLock?.release()
        wifiLock?.release()
        super.onDestroy()
    }
}
