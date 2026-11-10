package eu.smartsteer.navigator.core.aog

/**
 * Szukanie modułów w sieci i zmiana ich sieci (adresu 192.168.X.x).
 * Na podstawie AgOpenGPS/AgIO (© Brian Tischler i współtwórcy, GPLv3), pliki
 * SourceCode/AgIO/Source/Forms/FormUDP.cs (PGN 202 — pytanie, PGN 201 — nowa sieć)
 * i SourceCode/AgIO/Source/Forms/UDP.designer.cs (PGN 203 — odpowiedź modułu).
 */
object ModuleScan {
    const val PGN_SUBNET_CHANGE = 201
    const val PGN_SCAN_REQUEST = 202
    const val PGN_SCAN_REPLY = 203

    enum class Kind(val source: Int, val label: String) {
        STEER(126, "Moduł skrętu (AIO)"), MACHINE(123, "Moduł maszyny"), IMU(121, "IMU"), GPS(120, "Moduł GPS");
        companion object { fun of(src: Int) = entries.firstOrNull { it.source == src } }
    }

    data class Module(val kind: Kind?, val source: Int, val ip: String, val subnet: List<Int>) {
        val subnetText: String get() = subnet.joinToString(".")
        val label: String get() = kind?.label ?: "Moduł $source"
    }

    fun scanRequest(): ByteArray = AogFrame.build(PGN_SCAN_REQUEST, byteArrayOf(202.toByte(), 202.toByte(), 5))

    fun subnetChange(a: Int, b: Int, c: Int): ByteArray =
        AogFrame.build(PGN_SUBNET_CHANGE, byteArrayOf(201.toByte(), 201.toByte(), a.toByte(), b.toByte(), c.toByte()))

    /** Odpowiedź na szukanie: IP modułu (4 bajty) i jego sieć (3 bajty). */
    fun parseReply(f: Frame): Module? {
        if (f.pgn != PGN_SCAN_REPLY || f.data.size < 7) return null
        val d = f.data.map { it.toInt() and 0xFF }
        return Module(Kind.of(f.source), f.source, "${d[0]}.${d[1]}.${d[2]}.${d[3]}", listOf(d[4], d[5], d[6]))
    }

    /** „192.168.5” → [192, 168, 5]; null, gdy to nie trzy liczby 0–255. */
    fun parseSubnet(text: String): List<Int>? {
        val p = text.trim().removeSuffix(".").split('.')
        if (p.size != 3) return null
        val v = p.map { it.trim().toIntOrNull() ?: return null }
        return v.takeIf { it.all { x -> x in 0..255 } && it[0] in 1..223 }
    }
}
