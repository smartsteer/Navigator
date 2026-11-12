package eu.smartsteer.navigator.core.aog

/**
 * Składa ramki AgOpenGPS z ciągłego strumienia bajtów (port szeregowy USB płytki Maszyna):
 * szuka nagłówka 0x80 0x81, czeka na całą ramkę, sprawdza CRC; przy błędzie przesuwa się o bajt i szuka dalej.
 */
class AogStream(private val maxBuffer: Int = 4096) {
    private var buf = ByteArray(512)
    private var len = 0

    fun feed(data: ByteArray, n: Int = data.size, onFrame: (Frame) -> Unit) {
        for (i in 0 until n) {
            if (len == buf.size) buf = buf.copyOf(minOf(buf.size * 2, maxBuffer).coerceAtLeast(buf.size + 1))
            if (len >= maxBuffer) len = 0
            buf[len++] = data[i]
        }
        var start = 0
        while (true) {
            while (start + 1 < len && !(buf[start].u8() == AogFrame.HEADER_0 && buf[start + 1].u8() == AogFrame.HEADER_1)) start++
            if (len - start < 6) break
            val total = buf[start + 4].u8() + 6
            if (len - start < total) break
            val f = AogFrame.parse(buf.copyOfRange(start, start + total))
            if (f != null) { onFrame(f); start += total } else start++
        }
        if (start > 0) { buf.copyInto(buf, 0, start, len); len -= start }
    }
}
