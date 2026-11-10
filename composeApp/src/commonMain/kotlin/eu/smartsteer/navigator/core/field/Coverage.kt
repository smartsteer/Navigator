package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Mapa pokrycia: co zostało obrobione.
 *  - siatka komórek [CELL] m (w kawałkach 64×64) — szybkie pytanie „czy tu już było” dla sekcji
 *    i uczciwe hektary bez liczenia zakładek dwa razy,
 *  - pasy (lewa/prawa krawędź sekcji) — do rysowania i zapisu.
 * W AgOpenGPS tę rolę pełni odczyt pikseli mapy (CSection, CPatches.cs); tu jest to zwykła siatka.
 */
class Coverage {
    class Strip {
        val left = ArrayList<Vec2>()
        val right = ArrayList<Vec2>()
    }

    val strips = ArrayList<Strip>()
    private val chunks = HashMap<Long, LongArray>()
    var cellCount = 0L; private set
    val workedM2: Double get() = cellCount * CELL * CELL
    /** Zmienia się przy każdej zmianie — mapa wie, kiedy przerysować. */
    var version = 0L; private set

    fun clear() {
        strips.clear(); chunks.clear(); cellCount = 0; version++
    }

    fun isCovered(p: Vec2): Boolean {
        val cx = floor(p.x / CELL).toLong()
        val cy = floor(p.y / CELL).toLong()
        val ch = chunks[key(floorDiv(cx), floorDiv(cy))] ?: return false
        val lx = (cx - floorDiv(cx) * 64).toInt()
        val ly = (cy - floorDiv(cy) * 64).toInt()
        return (ch[ly] ushr lx) and 1L == 1L
    }

    /** Zamalowuje czworokąt a-b-c-d (krawędzie sekcji: poprzednia i obecna). */
    fun paintQuad(a: Vec2, b: Vec2, c: Vec2, d: Vec2) {
        val minX = min(min(a.x, b.x), min(c.x, d.x))
        val maxX = max(max(a.x, b.x), max(c.x, d.x))
        val minY = min(min(a.y, b.y), min(c.y, d.y))
        val maxY = max(max(a.y, b.y), max(c.y, d.y))
        val quad = listOf(a, b, c, d)
        var cy = floor(minY / CELL).toLong()
        val cyEnd = ceil(maxY / CELL).toLong()
        while (cy <= cyEnd) {
            var cx = floor(minX / CELL).toLong()
            val cxEnd = ceil(maxX / CELL).toLong()
            val y = (cy + 0.5) * CELL
            while (cx <= cxEnd) {
                val q = Vec2((cx + 0.5) * CELL, y)
                if (Polygon.contains(quad, q)) set(cx, cy)
                cx++
            }
            cy++
        }
        version++
    }

    private fun set(cx: Long, cy: Long) {
        val kx = floorDiv(cx)
        val ky = floorDiv(cy)
        val ch = chunks.getOrPut(key(kx, ky)) { LongArray(64) }
        val lx = (cx - kx * 64).toInt()
        val ly = (cy - ky * 64).toInt()
        val bit = 1L shl lx
        if (ch[ly] and bit == 0L) {
            ch[ly] = ch[ly] or bit
            cellCount++
        }
    }

    /** Nowy odcinek pasa sekcji: krawędzie [l] i [r] w obecnej pozycji. */
    fun extend(strip: Strip, l: Vec2, r: Vec2) {
        if (strip.left.isNotEmpty()) {
            val pl = strip.left.last()
            val pr = strip.right.last()
            if (pl.distanceTo(l) < MIN_STEP && pr.distanceTo(r) < MIN_STEP) return
            paintQuad(pl, pr, r, l)
        }
        strip.left.add(l)
        strip.right.add(r)
        version++
    }

    fun newStrip(): Strip = Strip().also { strips.add(it) }

    /** Zapis pasów: jeden pas w linii, pary krawędzi „lx ly rx ry” z dokładnością do 1 cm. */
    fun toText(): String = buildString {
        for (s in strips) {
            if (s.left.size < 2) continue
            for (i in s.left.indices) {
                if (i > 0) append(' ')
                append(r2(s.left[i].x)).append(' ').append(r2(s.left[i].y)).append(' ')
                append(r2(s.right[i].x)).append(' ').append(r2(s.right[i].y))
            }
            append('\n')
        }
    }

    fun loadText(text: String) {
        clear()
        for (line in text.lineSequence()) {
            val v = line.trim().split(' ').mapNotNull { it.toDoubleOrNull() }
            if (v.size < 8 || v.size % 4 != 0) continue
            val s = newStrip()
            for (i in v.indices step 4) extend(s, Vec2(v[i], v[i + 1]), Vec2(v[i + 2], v[i + 3]))
        }
    }

    private fun r2(v: Double): String {
        val r = kotlin.math.round(v * 100) / 100
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    companion object {
        const val CELL = 0.25
        const val MIN_STEP = 0.3
        private fun floorDiv(v: Long) = if (v >= 0) v / 64 else -((-v + 63) / 64)
        private fun key(kx: Long, ky: Long) = (kx shl 32) xor (ky and 0xFFFFFFFFL)
    }
}
