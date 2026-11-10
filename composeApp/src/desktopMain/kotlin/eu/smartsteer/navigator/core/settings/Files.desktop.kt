package eu.smartsteer.navigator.core.settings

import java.io.File

actual fun vehicleFiles(): TextFiles {
    val home = System.getProperty("user.home") ?: "."
    val docs = File(home, "Documents").takeIf { it.isDirectory } ?: File(home)
    return DirFiles(File(docs, "SmartSteer/Pojazdy"))
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
