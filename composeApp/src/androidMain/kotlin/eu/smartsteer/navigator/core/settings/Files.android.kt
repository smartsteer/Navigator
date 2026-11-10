package eu.smartsteer.navigator.core.settings

import java.io.File

/** Ustawiane w MainActivity (katalog plików aplikacji). */
object AndroidStorage {
    var filesDir: File? = null
}

actual fun vehicleFiles(): TextFiles =
    DirFiles(File(AndroidStorage.filesDir ?: File("/data/local/tmp"), "Pojazdy"))

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
