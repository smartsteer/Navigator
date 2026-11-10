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

private fun agoBase(): File {
    val home = System.getProperty("user.home") ?: "."
    val docs = File(home, "Documents").takeIf { it.isDirectory } ?: File(home)
    val main = File(docs, "AgOpenGPS")
    return if (main.isDirectory) main else File(smartSteerDir(), "Import/AgOpenGPS")
}

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
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
    }
    override fun delete(name: String) { File(dir, name).delete() }
}
