package eu.smartsteer.navigator.core.net

/**
 * Płytka Maszyna (opryskiwacz) na USB: port szeregowy (USB CDC) z ramkami binarnymi PGN 160/161 (docs/MASZYNA.md).
 * Windows: port COM (jSerialComm). Android: USB host, klasa CDC ACM — bez dodatkowych sterowników.
 */
interface ByteLink {
    fun open(port: String, onBytes: (ByteArray, Int) -> Unit, onClosed: (String?) -> Unit): String?
    fun write(bytes: ByteArray)
    fun close()
}

expect object SprayerPorts {
    val supported: Boolean
    fun list(): List<String>
    fun create(): ByteLink
}
