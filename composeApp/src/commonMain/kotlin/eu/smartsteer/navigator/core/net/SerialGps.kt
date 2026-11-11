package eu.smartsteer.navigator.core.net

/**
 * Odbiornik GPS na USB/COM (jak w AgIO): czytamy zdania NMEA z portu, a poprawki RTCM z NTRIP zapisujemy do tego samego portu.
 * Windows: jSerialComm (też odbiornik Bluetooth jako port COM). Android: GPS telefonu i odbiornik GNSS na Bluetooth (SPP).
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
