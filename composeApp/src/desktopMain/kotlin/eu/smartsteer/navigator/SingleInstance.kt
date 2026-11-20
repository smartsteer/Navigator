package eu.smartsteer.navigator

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Tylko jeden Navigator naraz (dwa programy walczyłyby o porty UDP modułów, autoster i zapis pola).
 * Pierwszy trzyma blokadę pliku w katalogu tymczasowym użytkownika i słucha na 127.0.0.1:[PORT];
 * drugi uruchomiony prosi go o pokazanie okna i sam się zamyka.
 */
object SingleInstance {
    private const val PORT = 47917
    private const val HELLO = "SMARTSTEER-POKAZ"

    // trzymane do końca programu (inaczej blokada zniknęłaby razem z obiektem)
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /** Wywoływane, gdy ktoś uruchomił Navigator drugi raz — pokaż okno. */
    @Volatile var onActivate: (() -> Unit)? = null

    /** true = jesteśmy jedyni (można startować); false = inny Navigator działa i został poproszony o pokazanie się. */
    fun acquire(): Boolean {
        val f = File(System.getProperty("java.io.tmpdir") ?: ".", "smartsteer-navigator.lock")
        // kilka prób: po aktualizacji stary proces może jeszcze kończyć pracę
        repeat(10) { attempt ->
            val l = runCatching {
                val ch = RandomAccessFile(f, "rw").channel
                ch.tryLock()?.also { channel = ch } ?: run { ch.close(); null }
            }.getOrNull()
            if (l != null) {
                lock = l
                listen()
                return true
            }
            if (attempt == 0 && poke()) return false
            Thread.sleep(300)
        }
        // blokady nie ma, a nikt nie odpowiada — lepiej wystartować niż nie pokazać nic
        return !poke()
    }

    private fun poke(): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 1000)
            s.getOutputStream().write((HELLO + "\n").toByteArray())
            s.getOutputStream().flush()
        }
        true
    }.getOrDefault(false)

    private fun listen() {
        val server = runCatching { ServerSocket(PORT, 4, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        Thread({
            while (true) {
                runCatching {
                    server.accept().use { s ->
                        s.soTimeout = 2000
                        val line = s.getInputStream().bufferedReader().readLine()
                        if (line == HELLO) onActivate?.invoke()
                    }
                }
            }
        }, "jedna-instancja").apply { isDaemon = true }.start()
    }
}
