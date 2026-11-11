package eu.smartsteer.navigator.ui.power

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.lang.ref.WeakReference

actual object PowerPlatform {
    var activity: WeakReference<Activity>? = null

    // na Androidzie autostart = ustawienie aplikacji jako ekranu głównego (launcher) — w ustawieniach systemu
    actual val autostartSupported: Boolean = false
    actual val canShutdownSystem: Boolean = false
    actual fun isAutostart(): Boolean = false
    actual fun setAutostart(on: Boolean): String? = "Na Androidzie: Ustawienia systemu → Aplikacje domyślne → Ekran główny → WAR Navigator"

    private var registered = false

    actual fun startMonitor() {
        val a = activity?.get() ?: return
        if (registered) return
        registered = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_POWER_CONNECTED -> PowerState.external = true
                    Intent.ACTION_POWER_DISCONNECTED -> PowerState.external = false
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }
        if (android.os.Build.VERSION.SDK_INT >= 33) a.applicationContext.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else a.applicationContext.registerReceiver(r, f)
        val st = a.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        PowerState.external = if (plugged < 0) null else plugged != 0
    }

    actual fun exit(shutdownSystem: Boolean) {
        activity?.get()?.finishAndRemoveTask()
    }
}
