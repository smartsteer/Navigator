package eu.smartsteer.navigator.core.net

/**
 * Połączenie UDP z siecią modułów (domyślnie 192.168.5.x): nasłuch na 9999,
 * wysyłka rozgłoszeniowa na <sieć>.255:8888. Odbiór działa w osobnym wątku; [onPacket] jest wywoływane z tego wątku.
 */
interface UdpLink {
    /** Uruchamia nasłuch. Zwraca opis błędu (np. port zajęty przez AgIO) albo null. */
    fun start(onPacket: (bytes: ByteArray, length: Int, from: String) -> Unit): String?
    /** Wysyła ramkę do modułów (domyślnie port 8888). Zwraca opis błędu albo null. */
    fun send(bytes: ByteArray, port: Int = 8888): String?
    /**
     * Tryb Wi-Fi: wysyłka bezpośrednio na adresy modułów zamiast rozgłoszenia. Przez Wi-Fi rozgłoszenia idą
     * najwolniejszą prędkością i bez potwierdzeń (gubią się), a pakiety na konkretny adres są potwierdzane i ponawiane.
     * Pusta lista = rozgłoszenie jak zwykle.
     */
    fun setUnicast(ips: List<String>)
    /** Sieć modułów: trzy pierwsze liczby adresu, np. 192.168.5. */
    fun setSubnet(a: Int, b: Int, c: Int)
    /**
     * Rozgłoszenie 255.255.255.255 przez każdą kartę sieciową z osobna (jak AgIO przy szukaniu modułów
     * i zmianie ich sieci) — dociera do modułów także wtedy, gdy są w innej sieci niż komputer.
     */
    fun broadcastAll(bytes: ByteArray, port: Int = 8888): String?
    fun stop()
}

/** Adres IPv4 karty sieciowej komputera/tabletu. */
data class LocalAddress(val address: String, val name: String) {
    val octets: List<Int> get() = address.split('.').mapNotNull { it.toIntOrNull() }
    fun inSubnet(a: Int, b: Int, c: Int) = octets.size == 4 && octets[0] == a && octets[1] == b && octets[2] == c
}

expect fun createUdpLink(): UdpLink

/** Adresy IPv4 włączonych kart sieciowych (bez pętli zwrotnej). */
expect fun localAddresses(): List<LocalAddress>
