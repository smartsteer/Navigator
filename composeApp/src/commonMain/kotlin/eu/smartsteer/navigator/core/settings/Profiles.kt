package eu.smartsteer.navigator.core.settings

/** Prosty magazyn plików tekstowych w jednym katalogu (profile pojazdów). */
interface TextFiles {
    fun list(): List<String>
    fun read(name: String): String?
    fun write(name: String, text: String)
    fun delete(name: String)
    /** Ścieżka katalogu do pokazania rolnikowi. */
    val location: String
}

/**
 * Profile pojazdów jak w AgOpenGPS: nazwane pliki, przed nadpisaniem kopia „.last”,
 * a przy uszkodzonym pliku wczytanie z tej kopii. Pamiętamy ostatnio używany profil.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik
 * SourceCode/GPS/Properties/VehicleSettings.cs (zapis z kopią .last i odczyt awaryjny).
 */
class Profiles(private val files: TextFiles) {

    fun names(): List<String> = files.list()
        .filter { it.endsWith(EXT) }
        .map { it.removeSuffix(EXT) }
        .sortedBy { it.lowercase() }

    fun exists(name: String) = files.read(name + EXT) != null

    /** Wczytuje profil; przy uszkodzonym pliku próbuje kopii .last. Null = brak profilu. */
    fun load(name: String): VehicleProfile? =
        files.read(name + EXT)?.let { VehicleProfile.fromJson(it) }
            ?: files.read(name + BACKUP)?.let { VehicleProfile.fromJson(it) }

    fun save(name: String, profile: VehicleProfile) {
        val clean = sanitize(name)
        require(clean.isNotEmpty()) { "Pusta nazwa profilu" }
        val text = profile.toJson()
        val old = files.read(clean + EXT)
        if (old == text) return
        if (old != null && VehicleProfile.fromJson(old) != null) files.write(clean + BACKUP, old)
        files.write(clean + EXT, text)
    }

    fun delete(name: String) {
        files.delete(name + EXT)
        files.delete(name + BACKUP)
        if (lastUsed() == name) files.delete(LAST_USED)
    }

    fun lastUsed(): String? = files.read(LAST_USED)?.trim()?.takeIf { it.isNotEmpty() }

    fun setLastUsed(name: String) = files.write(LAST_USED, name)

    /** Otwiera ostatni profil albo tworzy domyślny. */
    fun openStartup(): Pair<String, VehicleProfile> {
        val last = lastUsed()
        if (last != null) load(last)?.let { return last to it }
        names().firstOrNull()?.let { n -> load(n)?.let { return n to it } }
        val p = VehicleProfile()
        save(DEFAULT_NAME, p)
        setLastUsed(DEFAULT_NAME)
        return DEFAULT_NAME to p
    }

    companion object {
        const val EXT = ".json"
        const val BACKUP = ".last"
        const val LAST_USED = "ostatni-profil.txt"
        const val DEFAULT_NAME = "Mój ciągnik"

        /** Usuwa znaki niedozwolone w nazwach plików Windows. */
        fun sanitize(name: String): String =
            name.trim().filter { it !in "\\/:*?\"<>|" && it.code >= 32 }.take(40).trim()
    }
}
