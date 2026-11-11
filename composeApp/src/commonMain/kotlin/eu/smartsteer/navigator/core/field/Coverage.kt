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
    /** Suma pasów sekcji z zakładkami, m² („ha_przejazdów” dla WAR). */
    var passesM2 = 0.0; private set
    /** Zmienia się przy każdej zmianie — mapa wie, kiedy przerysować. */
    var version = 0L; private set

    fun clear() {
        strips.clear(); chunks.clear(); cellCount = 0; passesM2 = 0.0; version++
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
            passesM2 += Polygon.area(listOf(pl, pr, r, l))
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

    /**
     * Zewnętrzny obrys obrobionej powierzchni — „granica z pracy” (np. po objechaniu pola z rozsiewaczem).
     * Siatka [grid] m, domknięcie przerw do ok. 2·[close] m, wypełnienie dziur, największy spójny kawałek,
     * obrys po krawędziach komórek i wygładzenie. Null, gdy obrobiono za mało.
     */
    fun outline(grid: Double = 1.0, close: Int = 2): List<Vec2>? {
        if (cellCount == 0L) return null
        val occ = HashSet<Long>()
        fun k(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
        for ((key, rows) in chunks) {
            val kx = key shr 32
            val ky = (key and 0xFFFFFFFFL).toInt().toLong()
            for (ly in 0 until 64) {
                val row = rows[ly]
                if (row == 0L) continue
                for (lx in 0 until 64) if ((row ushr lx) and 1L == 1L) {
                    val x = ((kx * 64 + lx) + 0.5) * CELL
                    val y = ((ky * 64 + ly) + 0.5) * CELL
                    occ.add(k(floor(x / grid).toInt(), floor(y / grid).toInt()))
                }
            }
        }
        if (occ.size < 20) return null
        var minX = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var minY = Int.MAX_VALUE; var maxY = Int.MIN_VALUE
        for (v in occ) { val x = (v shr 32).toInt(); val y = v.toInt(); minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
        val pad = close + 2
        val x0 = minX - pad; val y0 = minY - pad
        val w = maxX - minX + 1 + 2 * pad; val h = maxY - minY + 1 + 2 * pad
        if (w.toLong() * h > 6_000_000L) return null
        var g = BooleanArray(w * h)
        for (v in occ) g[((v.toInt()) - y0) * w + ((v shr 32).toInt() - x0)] = true
        fun morph(src: BooleanArray, dilate: Boolean): BooleanArray {
            val out = BooleanArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var any = false; var all = true
                for (dy in -1..1) for (dx in -1..1) {
                    val xx = x + dx; val yy = y + dy
                    val v = xx in 0 until w && yy in 0 until h && src[yy * w + xx]
                    if (v) any = true else all = false
                }
                out[y * w + x] = if (dilate) any else all
            }
            return out
        }
        repeat(close) { g = morph(g, true) }
        repeat(close) { g = morph(g, false) }
        // dziury w środku (np. nieobrobiona kępa) też należą do pola
        val outside = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        for (x in 0 until w) { stack.add(x); stack.add((h - 1) * w + x) }
        for (y in 0 until h) { stack.add(y * w); stack.add(y * w + w - 1) }
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            if (outside[i] || g[i]) continue
            outside[i] = true
            val x = i % w; val y = i / w
            if (x > 0) stack.add(i - 1); if (x < w - 1) stack.add(i + 1)
            if (y > 0) stack.add(i - w); if (y < h - 1) stack.add(i + w)
        }
        for (i in g.indices) if (!outside[i]) g[i] = true
        // największy spójny kawałek
        val label = IntArray(w * h)
        var best = 0; var bestSize = 0; var next = 0
        for (i in g.indices) {
            if (!g[i] || label[i] != 0) continue
            next++
            var size = 0
            stack.add(i)
            while (stack.isNotEmpty()) {
                val j = stack.removeAt(stack.size - 1)
                if (!g[j] || label[j] != 0) continue
                label[j] = next; size++
                val x = j % w; val y = j / w
                if (x > 0) stack.add(j - 1); if (x < w - 1) stack.add(j + 1)
                if (y > 0) stack.add(j - w); if (y < h - 1) stack.add(j + w)
            }
            if (size > bestSize) { bestSize = size; best = next }
        }
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h && label[y * w + x] == best
        // obrys: krawędzie komórek z wnętrzem po lewej (przeciwnie do wskazówek zegara), łączone w pętlę
        val edges = HashMap<Long, ArrayList<Long>>()
        fun vk(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
        fun add(ax: Int, ay: Int, bx: Int, by: Int) { edges.getOrPut(vk(ax, ay)) { ArrayList(2) }.add(vk(bx, by)) }
        for (y in 0 until h) for (x in 0 until w) {
            if (!inside(x, y)) continue
            if (!inside(x, y - 1)) add(x, y, x + 1, y)
            if (!inside(x + 1, y)) add(x + 1, y, x + 1, y + 1)
            if (!inside(x, y + 1)) add(x + 1, y + 1, x, y + 1)
            if (!inside(x - 1, y)) add(x, y + 1, x, y)
        }
        if (edges.isEmpty()) return null
        val start = edges.keys.minWith(compareBy<Long>({ (it.toInt()) }, { (it shr 32).toInt() }))
        val loop = ArrayList<Vec2>()
        var cur = start
        var guard = 0
        do {
            loop.add(Vec2(((cur shr 32).toInt() + x0) * grid, ((cur.toInt()) + y0) * grid))
            val outs = edges[cur] ?: break
            val nxt = outs.removeAt(outs.size - 1)
            if (outs.isEmpty()) edges.remove(cur)
            cur = nxt
        } while (cur != start && ++guard < 1_000_000)
        if (loop.size < 4) return null
        return Polygon.simplify(loop, minSpacing = grid, tolerance = grid * 0.6).takeIf { it.size >= 3 }
    }

    companion object {
        const val CELL = 0.25
        const val MIN_STEP = 0.3
        private fun floorDiv(v: Long) = if (v >= 0) v / 64 else -((-v + 63) / 64)
        private fun key(kx: Long, ky: Long) = (kx shl 32) xor (ky and 0xFFFFFFFFL)
    }
}
