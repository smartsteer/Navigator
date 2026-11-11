package eu.smartsteer.navigator.core.net

/**
 * Odbiornik GPS na USB/COM (jak w AgIO): czytamy zdania NMEA z portu, a poprawki RTCM z NTRIP zapisujemy do tego samego portu.
 * Windows: jSerialComm. Android: na razie nie (odbiornik przez płytkę AIO / sieć).
 */
interface SerialLink {
    fun open(port: String, baud: Int, onLine: (String) -> Unit, onClosed: (String?) -> Unit): String?
    fun write(bytes: ByteArray)
    fun close()
}

expect object SerialPorts {
    val supported: Boolean
    fun list(): List<String>
    fun create(): SerialLink
}
