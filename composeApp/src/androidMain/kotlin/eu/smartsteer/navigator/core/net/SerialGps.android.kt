package eu.smartsteer.navigator.core.net

actual object SerialPorts {
    actual val supported: Boolean = false
    actual fun list(): List<String> = emptyList()
    actual fun create(): SerialLink = object : SerialLink {
        override fun open(port: String, baud: Int, onLine: (String) -> Unit, onClosed: (String?) -> Unit): String? = "Na Androidzie odbiornik podłącza się przez płytkę AIO"
        override fun write(bytes: ByteArray) {}
        override fun close() {}
    }
}
