package eu.smartsteer.navigator.ui.power

import kotlin.system.exitProcess

actual object PowerPlatform {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE = "WAR Navigator"
    /** Ścieżka do programu z instalatora (jpackage); przy uruchomieniu z IDE brak. */
    private val exe: String? get() = System.getProperty("jpackage.app-path")

    actual val autostartSupported: Boolean get() = windows && exe != null
    actual val canShutdownSystem: Boolean get() = windows

    private fun run(vararg cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() to out
    } catch (e: Exception) { -1 to (e.message ?: e.toString()) }

    actual fun isAutostart(): Boolean = windows && run("reg", "query", RUN_KEY, "/v", VALUE).first == 0

    actual fun setAutostart(on: Boolean): String? {
        if (!windows) return "Tylko Windows"
        val path = exe ?: return "Autostart działa tylko w programie zainstalowanym z instalatora"
        val r = if (on) run("reg", "add", RUN_KEY, "/v", VALUE, "/t", "REG_SZ", "/d", "\"$path\"", "/f")
        else run("reg", "delete", RUN_KEY, "/v", VALUE, "/f")
        return if (r.first == 0) null else "Nie udało się: ${r.second.trim().take(120)}"
    }

    @Volatile private var started = false

    /** Jeden proces PowerShell co 2 s podaje, czy laptop/tablet jest na zasilaczu (Online) czy na baterii (Offline). */
    actual fun startMonitor() {
        if (!windows || started) return
        started = true
        Thread({
            while (true) {
                try {
                    val script = "Add-Type -AssemblyName System.Windows.Forms; while(\$true){ [System.Windows.Forms.SystemInformation]::PowerStatus.PowerLineStatus; Start-Sleep -Seconds 2 }"
                    val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start()
                    p.inputStream.bufferedReader().forEachLine { l ->
                        when (l.trim()) {
                            "Online" -> PowerState.external = true
                            "Offline" -> PowerState.external = false
                            "Unknown" -> PowerState.external = null
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(10_000)
            }
        }, "power-monitor").apply { isDaemon = true }.start()
    }

    actual fun exit(shutdownSystem: Boolean) {
        if (shutdownSystem && windows) run("shutdown", "/s", "/t", "5", "/c", "WAR Navigator: brak zasilania z ciągnika")
        exitProcess(0)
    }
}
