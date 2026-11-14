package eu.smartsteer.navigator.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import eu.smartsteer.navigator.core.map.TileIo
import eu.smartsteer.navigator.core.map.TileMath
import eu.smartsteer.navigator.core.map.TileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

expect fun decodeTile(bytes: ByteArray): ImageBitmap?

/**
 * Kafelki podkładu: pamięć (ostatnie ~250), dysk (zostają na zawsze — w polu działa bez internetu), sieć.
 * get() wołane przy rysowaniu; brakujące kafelki dociągają się w tle, potem mapa się odświeża.
 */
object TileManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val net = Semaphore(4)
    private val mem = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)
    private val pending = HashSet<String>()
    private val failedAt = HashMap<String, Double>()
    private val clock = TimeSource.Monotonic.markNow()
    private fun now() = clock.elapsedNow().inWholeMilliseconds / 1000.0

    /** Zmienia się po dociągnięciu kafelka — mapa czyta, żeby się przerysować. */
    var version by mutableStateOf(0)
        private set

    fun key(src: TileSource, z: Int, x: Int, y: Int) = "${src.name.lowercase()}/$z/$x/$y.jpg"

    /** Kafelek z pamięci albo null (wtedy zaczyna się ładowanie). Tylko z wątku UI. */
    fun get(src: TileSource, z: Int, x: Int, y: Int): ImageBitmap? {
        val k = key(src, z, x, y)
        mem[k]?.let { return it }
        if (k in pending) return null
        failedAt[k]?.let { if (now() - it < 30) return null }
        pending.add(k)
        scope.launch {
            val img = load(src, z, x, y, k)
            withContext(Dispatchers.Main) {
                pending.remove(k)
                if (img != null) {
                    mem[k] = img
                    while (mem.size > 420) mem.remove(mem.keys.first())
                    failedAt.remove(k)
                } else failedAt[k] = now()
                version++
            }
        }
        return null
    }

    private suspend fun load(src: TileSource, z: Int, x: Int, y: Int, k: String): ImageBitmap? {
        val bytes = TileIo.read(k) ?: net.withPermit {
            val url = src.url(z, x, y) ?: return null
            TileIo.download(url)?.also { TileIo.write(k, it) }
        } ?: return null
        return decodeTile(bytes)
    }

    // ---------------- pobieranie mapy całego pola (do pracy bez internetu) ----------------
    var prefetchText by mutableStateOf<String?>(null)
        private set
    var prefetching by mutableStateOf(false)
        private set

    /** Pobiera kafelki prostokąta pola (z zapasem) na zbliżeniach [zMin]..[zMax]. */
    fun prefetch(src: TileSource, latMin: Double, latMax: Double, lonMin: Double, lonMax: Double, zMin: Int = 14, zMax: Int = 19) {
        if (src == TileSource.NONE || prefetching) return
        val tiles = (zMin..minOf(zMax, src.maxZoom)).flatMap { z -> TileMath.range(latMin, latMax, lonMin, lonMax, z).map { Triple(z, it.first, it.second) } }
        if (tiles.size > 6000) { prefetchText = "Za duży obszar (${tiles.size} kafelków)"; return }
        prefetching = true
        prefetchText = "Pobieram mapę pola: 0 / ${tiles.size}"
        scope.launch {
            var done = 0
            var fail = 0
            for ((z, x, y) in tiles) {
                val k = key(src, z, x, y)
                if (TileIo.read(k) == null) {
                    val b = net.withPermit { src.url(z, x, y)?.let { TileIo.download(it) } }
                    if (b != null) TileIo.write(k, b) else fail++
                }
                done++
                if (done % 10 == 0 || done == tiles.size) withContext(Dispatchers.Main) {
                    prefetchText = "Pobieram mapę pola: $done / ${tiles.size}"
                }
            }
            withContext(Dispatchers.Main) {
                prefetching = false
                prefetchText = if (fail == 0) "Mapa pola zapisana (${tiles.size} kafelków) — działa bez internetu"
                else "Pobrano ${tiles.size - fail} z ${tiles.size} kafelków (brak internetu albo poza zasięgiem mapy)"
                version++
            }
        }
    }
}
