package eu.smartsteer.navigator.core.net

/**
 * Połączenie UDP z siecią modułów (192.168.5.x): nasłuch na 9999, wysyłka rozgłoszeniowa na 192.168.5.255:8888.
 * Odbiór działa w osobnym wątku; [onPacket] jest wywoływane z tego wątku.
 */
interface UdpLink {
    /** Uruchamia nasłuch. Zwraca opis błędu (np. port zajęty przez AgIO) albo null. */
    fun start(onPacket: (bytes: ByteArray, length: Int, from: String) -> Unit): String?
    /** Wysyła ramkę do modułów (domyślnie port 8888). Zwraca opis błędu albo null. */
    fun send(bytes: ByteArray, port: Int = 8888): String?
    fun stop()
}

const val MODULES_BROADCAST = "192.168.5.255"

expect fun createUdpLink(): UdpLink
