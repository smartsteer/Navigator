package eu.smartsteer.navigator.core.net

/** Połączenie TCP (NTRIP). Łączenie i odbiór w osobnym wątku; wywołania zwrotne z tego wątku. */
interface TcpClient {
    fun start(
        host: String,
        port: Int,
        onConnected: () -> Unit,
        onData: (ByteArray, Int) -> Unit,
        onClosed: (error: String?) -> Unit,
    )
    /** Zwraca opis błędu albo null. Nie wywoływać z wątku UI (Android). */
    fun send(bytes: ByteArray): String?
    fun close()
}

expect fun createTcpClient(): TcpClient
