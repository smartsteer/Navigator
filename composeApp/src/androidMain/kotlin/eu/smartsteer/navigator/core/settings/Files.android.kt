package eu.smartsteer.navigator.core.settings

import java.io.File

/** Ustawiane w MainActivity (katalog plików aplikacji). */
object AndroidStorage {
    var filesDir: File? = null
    /** Zasoby aplikacji (logo). */
    var resources: android.content.res.Resources? = null
}

actual fun vehicleFiles(): TextFiles =
    DirFiles(File(AndroidStorage.filesDir ?: File("/data/local/tmp"), "Pojazdy"))

actual fun fieldFiles(): TextFiles =
    DirFiles(File(AndroidStorage.filesDir ?: File("/data/local/tmp"), "Pola"))

actual fun importFiles(): TextFiles =
    DirFiles(File(AndroidStorage.filesDir ?: File("/data/local/tmp"), "Import"))

actual fun exportFiles(): TextFiles =
    DirFiles(File(AndroidStorage.filesDir ?: File("/data/local/tmp"), "Raporty"))

private fun agoBase(): File = File(AndroidStorage.filesDir ?: File("/data/local/tmp"), "Import/AgOpenGPS")

actual fun agoFiles(sub: String): TextFiles? = File(agoBase(), sub).takeIf { it.isDirectory }?.let { DirFiles(it) }

actual fun agoLocation(): String = agoBase().absolutePath

actual fun chooseAgoFolder(): String? = null

actual fun warFiles(): TextFiles =
    DirFiles(File(AndroidStorage.filesDir ?: File("/data/local/tmp"), "WAR"))

actual fun nowText(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())

actual fun openFolder(path: String): Boolean = false

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
