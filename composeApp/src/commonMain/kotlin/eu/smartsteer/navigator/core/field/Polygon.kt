package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.abs
import kotlin.math.sqrt

/** Wielokąt pola (granica, uwrocie) w metrach płaszczyzny lokalnej. Ostatni punkt łączy się z pierwszym. */
object Polygon {

    /** Pole ze znakiem: + = punkty przeciwnie do ruchu wskazówek zegara. */
    fun signedArea(p: List<Vec2>): Double {
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2
    }

    fun area(p: List<Vec2>) = abs(signedArea(p))

    /** Czy punkt leży w środku (promień w prawo, parzysta liczba przecięć). */
    fun contains(p: List<Vec2>, q: Vec2): Boolean {
        var inside = false
        var j = p.size - 1
        for (i in p.indices) {
            val a = p[i]
            val b = p[j]
            if ((a.y > q.y) != (b.y > q.y) && q.x < (b.x - a.x) * (q.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    /** Odległość punktu od obwodu. */
    fun distanceToEdge(p: List<Vec2>, q: Vec2): Double {
        var best = Double.MAX_VALUE
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            val ab = b - a
            val len2 = ab dot ab
            val t = if (len2 < 1e-12) 0.0 else (((q - a) dot ab) / len2).coerceIn(0.0, 1.0)
            val d = (a + ab * t).distanceTo(q)
            if (d < best) best = d
        }
        return best
    }

    /**
     * Upraszcza nagraną granicę: punkty co najmniej [minSpacing] m od siebie,
     * bez punktów leżących prawie na prostej (odchyłka < [tolerance] m).
     */
    fun simplify(raw: List<Vec2>, minSpacing: Double = 1.0, tolerance: Double = 0.05): List<Vec2> {
        if (raw.size < 3) return raw
        val spaced = ArrayList<Vec2>()
        for (q in raw) if (spaced.isEmpty() || spaced.last().distanceTo(q) >= minSpacing) spaced.add(q)
        if (spaced.size > 3 && spaced.last().distanceTo(spaced.first()) < minSpacing) spaced.removeAt(spaced.lastIndex)
        if (spaced.size < 4) return spaced
        val out = ArrayList<Vec2>()
        for (i in spaced.indices) {
            val prev = spaced[(i - 1 + spaced.size) % spaced.size]
            val cur = spaced[i]
            val next = spaced[(i + 1) % spaced.size]
            if (lineDistance(prev, next, cur) >= tolerance) out.add(cur)
        }
        return if (out.size >= 3) out else spaced
    }

    /**
     * Wielokąt odsunięty do środka o [d] m (uwrocie). Wierzchołki przesuwamy po dwusiecznej,
     * a punkty, które wypadły bliżej granicy niż [d] (ciasne zakręty), usuwamy.
     */
    fun inset(p: List<Vec2>, d: Double): List<Vec2> {
        if (p.size < 3 || d <= 0) return p
        val ccw = signedArea(p) > 0
        val n = p.size
        val raw = ArrayList<Vec2>(n)
        for (i in 0 until n) {
            val prev = p[(i - 1 + n) % n]
            val cur = p[i]
            val next = p[(i + 1) % n]
            val n1 = inward(prev, cur, ccw)
            val n2 = inward(cur, next, ccw)
            val bis = n1 + n2
            val bl = bis.length
            if (bl < 1e-9) { raw.add(cur + n1 * d); continue }
            val b = bis * (1 / bl)
            val cos = (b dot n1).coerceAtLeast(0.3) // ostre narożniki: ograniczamy wysunięcie
            raw.add(cur + b * (d / cos))
        }
        val cleaned = raw.filter { distanceToEdge(p, it) >= d * 0.98 && contains(p, it) }
        return if (cleaned.size >= 3) simplify(cleaned, minSpacing = 0.5, tolerance = 0.0) else emptyList()
    }

    private fun inward(a: Vec2, b: Vec2, ccw: Boolean): Vec2 {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-12)
        // dla wielokąta przeciwnie do zegara wnętrze jest po lewej
        return if (ccw) Vec2(-dy / l, dx / l) else Vec2(dy / l, -dx / l)
    }

    private fun lineDistance(a: Vec2, b: Vec2, q: Vec2): Double {
        val ab = b - a
        val l = ab.length
        if (l < 1e-12) return q.distanceTo(a)
        return abs((ab.x * (q.y - a.y) - ab.y * (q.x - a.x)) / l)
    }
}
