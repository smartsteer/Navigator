package eu.smartsteer.navigator.core.update

/** Część zależna od platformy: wersja zainstalowanej aplikacji, pobranie informacji o wydaniu, instalacja. */
interface PlatformUpdater {
    val currentVersion: String
    /** Numer budowy z CI; 0 = wersja deweloperska (aktualizacje wyłączone). */
    val currentBuild: Int
    /** true: aplikacja sama pobiera i instaluje (Windows, MSI). false: tylko link do pobrania (Android). */
    val canInstall: Boolean
    /** Treść odpowiedzi GitHub API /releases/latest. Rzuca wyjątek przy braku sieci. */
    suspend fun fetchLatestJson(): String
    /** Pobiera instalator i uruchamia instalację; po sukcesie aplikacja się zamyka. */
    suspend fun install(release: ReleaseInfo, onProgress: (Float) -> Unit)
}

expect fun platformUpdater(): PlatformUpdater
