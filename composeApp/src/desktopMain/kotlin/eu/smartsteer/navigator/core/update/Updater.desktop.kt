package eu.smartsteer.navigator.core.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.system.exitProcess

actual fun platformUpdater(): PlatformUpdater = DesktopUpdater

private object DesktopUpdater : PlatformUpdater {
    override val currentVersion: String =
        System.getProperty("navigator.version") ?: System.getProperty("jpackage.app-version") ?: "0.1.0"
    override val currentBuild: Int = Releases.buildNumber(currentVersion) ?: 0

    /** Ścieżka do uruchamiacza .exe — jest tylko w zainstalowanej aplikacji (jpackage). */
    private val launcher: String? = System.getProperty("jpackage.app-path")
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    override val canInstall: Boolean get() = isWindows

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SmartSteer-Navigator/$currentVersion")
        }

    override suspend fun fetchLatestJson(): String = withContext(Dispatchers.IO) {
        val c = open(Releases.LATEST_API)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) error("GitHub: HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    override suspend fun install(release: ReleaseInfo, onProgress: (Float) -> Unit) {
        val url = release.msiUrl ?: error("Wydanie nie ma instalatora MSI")
        val msi = withContext(Dispatchers.IO) {
            val dir = File(System.getProperty("java.io.tmpdir"), "SmartSteerUpdate").apply { mkdirs() }
            val out = File(dir, "SmartSteer-Navigator-${release.version}.msi")
            val part = File(dir, out.name + ".part")
            val c = open(url)
            try {
                if (c.responseCode != 200) error("Pobieranie: HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                var done = 0L
                c.inputStream.use { inp ->
                    part.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toDouble() / total).toFloat())
                        }
                    }
                }
                if (total > 0 && done != total) error("Pobieranie przerwane")
            } finally {
                c.disconnect()
            }
            out.delete()
            if (!part.renameTo(out)) error("Nie można zapisać instalatora")
            out
        }
        onProgress(1f)
        // PowerShell z -EncodedCommand (UTF‑16): bezpieczne dla polskich znaków w ścieżce TEMP.
        // Czeka na msiexec, potem uruchamia aplikację ponownie (nową albo starą, jeśli instalację anulowano).
        fun q(s: String) = "'" + s.replace("'", "''") + "'"
        val script = buildString {
            append("Start-Sleep -Seconds 2; ")
            append("Start-Process -FilePath 'msiexec.exe' -ArgumentList @('/i', ")
            append(q("\"" + msi.absolutePath + "\""))
            append(", '/passive', '/norestart') -Wait; ")
            if (launcher != null) append("Start-Process -FilePath ").append(q(launcher))
        }
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        withContext(Dispatchers.IO) {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded,
            ).start()
        }
        exitProcess(0)
    }
}
