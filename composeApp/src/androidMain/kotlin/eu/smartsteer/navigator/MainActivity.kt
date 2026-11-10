package eu.smartsteer.navigator

import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    /** Bez tej blokady część tabletów odrzuca po Wi‑Fi pakiety rozgłoszeniowe z płytki AIO. */
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Ekran nie gaśnie podczas pracy w polu
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("navigator-udp")
            ?.apply { setReferenceCounted(false); acquire() }
        setContent { App() }
    }

    override fun onDestroy() {
        multicastLock?.release()
        super.onDestroy()
    }
}
