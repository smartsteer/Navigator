package eu.smartsteer.navigator.core.aog

/**
 * Ramka protokołu płytek AgOpenGPS (UDP):
 *
 *  [0] 0x80  [1] 0x81  [2] źródło  [3] PGN  [4] długość danych  [5..N-2] dane  [N-1] CRC
 *
 * CRC = młodszy bajt sumy bajtów od indeksu 2 (źródło) do ostatniego bajtu danych.
 * Wartości wielobajtowe są little-endian (najpierw bajt Lo).
 */
object AogFrame {
    const val HEADER_0 = 0x80
    const val HEADER_1 = 0x81

    /** Adres źródła używany przez program sterujący (AgIO / ta aplikacja). */
    const val SOURCE_APP = 0x7F

    /** Porty UDP w sieci modułów 192.168.5.x. */
    const val PORT_TO_MODULES = 8888
    const val PORT_FROM_MODULES = 9999

    fun build(pgn: Int, data: ByteArray, source: Int = SOURCE_APP): ByteArray {
        require(pgn in 0..255) { "PGN poza zakresem: $pgn" }
        require(data.size <= 255) { "Za dużo danych: ${data.size}" }
        val out = ByteArray(data.size + 6)
        out[0] = HEADER_0.toByte()
        out[1] = HEADER_1.toByte()
        out[2] = source.toByte()
        out[3] = pgn.toByte()
        out[4] = data.size.toByte()
        data.copyInto(out, destinationOffset = 5)
        out[out.lastIndex] = crc(out, out.size).toByte()
        return out
    }

    /** Zwraca ramkę albo null, gdy nagłówek, długość lub CRC się nie zgadzają. */
    fun parse(bytes: ByteArray, length: Int = bytes.size): Frame? {
        if (length < 6) return null
        if (bytes[0].u8() != HEADER_0 || bytes[1].u8() != HEADER_1) return null
        val dataLen = bytes[4].u8()
        val total = dataLen + 6
        if (length < total) return null
        if (bytes[total - 1].u8() != crc(bytes, total)) return null
        return Frame(
            source = bytes[2].u8(),
            pgn = bytes[3].u8(),
            data = bytes.copyOfRange(5, 5 + dataLen),
        )
    }

    internal fun crc(frame: ByteArray, total: Int): Int {
        var sum = 0
        for (i in 2 until total - 1) sum += frame[i].u8()
        return sum and 0xFF
    }
}

class Frame(val source: Int, val pgn: Int, val data: ByteArray)

internal fun Byte.u8(): Int = toInt() and 0xFF
