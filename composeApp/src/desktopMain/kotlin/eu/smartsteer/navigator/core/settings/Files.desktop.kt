package eu.smartsteer.navigator.core.settings

import java.io.File

actual fun vehicleFiles(): TextFiles {
    val home = System.getProperty("user.home") ?: "."
    val docs = File(home, "Documents").takeIf { it.isDirectory } ?: File(home)
    return DirFiles(File(docs, "SmartSteer/Pojazdy"))
}

actual fun fieldFiles(): TextFiles {
    val home = System.getProperty("user.home") ?: "."
    val docs = File(home, "Documents").takeIf { it.isDirectory } ?: File(home)
    return DirFiles(File(docs, "SmartSteer/Pola"))
}

private fun smartSteerDir(): File {
    val home = System.getProperty("user.home") ?: "."
    val docs = File(home, "Documents").takeIf { it.isDirectory } ?: File(home)
    return File(docs, "SmartSteer")
}

actual fun importFiles(): TextFiles = DirFiles(File(smartSteerDir(), "Import").apply { mkdirs() })

actual fun exportFiles(): TextFiles = DirFiles(File(smartSteerDir(), "Raporty").apply { mkdirs() })

private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

/** Wartość z rejestru Windows (reg query, wynik w UTF-8, żeby polskie litery w ścieżkach były dobre). */
private fun regValue(key: String, name: String): String? = if (!isWindows) null else runCatching {
    val p = ProcessBuilder("cmd", "/c", "chcp", "65001", ">nul", "&", "reg", "query", key, "/v", name).redirectErrorStream(true).start()
    val out = p.inputStream.bufferedReader(Charsets.UTF_8).readText()
    p.waitFor()
    out.lineSequence().firstOrNull { it.trim().startsWith(name) }
        ?.let { Regex("REG_(?:EXPAND_)?SZ\\s+(.*)$").find(it.trim())?.groupValues?.get(1)?.trim() }
}.getOrNull()

/** Rozwija %USERPROFILE% i inne zmienne środowiska. */
private fun expandEnv(s: String): String = Regex("%([^%]+)%").replace(s) { System.getenv(it.groupValues[1]) ?: it.value }

private val agoChoiceFile get() = File(smartSteerDir(), "folder_agopengps.txt")

private fun looksLikeAgo(d: File) = listOf("VehicleProfiles", "Vehicles", "Fields", "ToolProfiles").any { File(d, it).isDirectory }

/**
 * Folder AgOpenGPS tak, jak szuka go AgOpenGPS: najpierw wskazany ręcznie w Navigatorze, potem „WorkingDirectory”
 * z rejestru (HKCU\SOFTWARE\AgOpenGPS), potem prawdziwy folder Dokumenty z rejestru (często przeniesiony do OneDrive),
 * na końcu typowe miejsca.
 */
@Volatile private var agoCached: File? = null

@Volatile private var agoMissAt = 0L

/** Wynik zapamiętany (rejestr pytamy rzadko — to osobny proces); gdy nie ma AgOpenGPS, ponowne szukanie co 30 s. */
private fun agoBase(): File {
    agoCached?.takeIf { looksLikeAgo(it) }?.let { return it }
    val fallback = File(smartSteerDir(), "Import/AgOpenGPS")
    if (System.currentTimeMillis() - agoMissAt < 30_000) return fallback
    val f = findAgoBase()
    if (looksLikeAgo(f)) agoCached = f else agoMissAt = System.currentTimeMillis()
    return f
}

private fun findAgoBase(): File {
    val home = System.getProperty("user.home") ?: "."
    val cands = mutableListOf<File>()
    runCatching { agoChoiceFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cands += File(it) }
    regValue("HKCU\\SOFTWARE\\AgOpenGPS", "WorkingDirectory")?.takeIf { it.isNotBlank() && it != "Default" }?.let { cands += File(expandEnv(it), "AgOpenGPS") }
    regValue("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders", "Personal")?.let { cands += File(expandEnv(it), "AgOpenGPS") }
    regValue("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders", "Personal")?.let { cands += File(it, "AgOpenGPS") }
    for (d in listOf("Documents", "OneDrive/Documents", "OneDrive/Dokumenty", "Dokumenty")) cands += File(home, "$d/AgOpenGPS")
    cands += File("C:/AgOpenGPS")
    return cands.firstOrNull { looksLikeAgo(it) } ?: File(smartSteerDir(), "Import/AgOpenGPS")
}

actual fun chooseAgoFolder(): String? = runCatching {
    val ch = javax.swing.JFileChooser().apply {
        dialogTitle = "Wskaż folder AgOpenGPS (ten z VehicleProfiles, Fields…)"
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    }
    if (ch.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return@runCatching null
    var d = ch.selectedFile ?: return@runCatching null
    // wskazany folder z profilem (np. VehicleProfiles) — bierzemy folder wyżej
    if (!looksLikeAgo(d) && d.parentFile?.let { looksLikeAgo(it) } == true) d = d.parentFile
    // wskazany folder nadrzędny (np. Dokumenty) — bierzemy AgOpenGPS w środku
    if (!looksLikeAgo(d) && looksLikeAgo(File(d, "AgOpenGPS"))) d = File(d, "AgOpenGPS")
    agoChoiceFile.parentFile?.mkdirs()
    agoChoiceFile.writeText(d.absolutePath)
    agoCached = null; agoMissAt = 0L
    d.absolutePath
}.getOrNull()

actual fun agoFiles(sub: String): TextFiles? = File(agoBase(), sub).takeIf { it.isDirectory }?.let { DirFiles(it) }

actual fun agoLocation(): String = agoBase().absolutePath

actual fun warFiles(): TextFiles = DirFiles(File(smartSteerDir(), "WAR").apply { mkdirs() })

actual fun nowText(): String =
    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

actual fun openFolder(path: String): Boolean = try {
    File(path).mkdirs()
    java.awt.Desktop.getDesktop().open(File(path))
    true
} catch (e: Exception) {
    false
}

internal class DirFiles(private val dir: File) : TextFiles {
    override val location: String get() = dir.absolutePath
    override fun list(): List<String> = dir.list()?.toList().orEmpty()
    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    override fun write(name: String, text: String) {
        val target = File(dir, name)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
    override fun delete(name: String) { File(dir, name).delete() }
}
