package eu.smartsteer.navigator.core.update

import eu.smartsteer.navigator.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual fun platformUpdater(): PlatformUpdater = AndroidUpdater

/** Android: tylko informacja o nowej wersji i link do APK (instalację robi użytkownik). */
private object AndroidUpdater : PlatformUpdater {
    override val currentVersion: String = BuildConfig.VERSION_NAME
    override val currentBuild: Int = Releases.buildNumber(currentVersion) ?: 0
    override val canInstall: Boolean = false

    override suspend fun fetchLatestJson(): String = withContext(Dispatchers.IO) {
        val c = (URL(Releases.LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "SmartSteer-Navigator/$currentVersion")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (c.responseCode != 200) error("GitHub: HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    override suspend fun install(release: ReleaseInfo, onProgress: (Float) -> Unit) =
        error("Na Androidzie aktualizację pobiera się z linku")
}
