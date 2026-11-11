package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import kotlin.math.roundToInt

/**
 * Mapa wykonania (as-applied): co ok. 2 m pracy — miejsce maszyny, czas, prędkość, szerokość pracujących sekcji
 * i dawka docelowa z mapy dawek. Do raportu (GeoJSON / WAR), ewidencji i rozliczenia zużycia.
 */
class AppliedLog {
    data class Point(val p: Vec2, val tS: Long, val speedKmh: Double, val widthM: Double, val rate: Double?)

    val points = ArrayList<Point>()

    fun clear() = points.clear()

    /** Wołane przy każdej pozycji; zapis tylko gdy maszyna pracuje i przejechała ≥ 2 m. */
    fun record(tool: Vec2, unixS: Long, speedKmh: Double, widthM: Double, rate: Double?) {
        if (widthM <= 0.0 || speedKmh < 0.5) return
        val last = points.lastOrNull()
        if (last != null && last.p.distanceTo(tool) < 2.0) return
        points.add(Point(tool, unixS, speedKmh, widthM, rate))
    }

    /** Obrobiona powierzchnia z odcinków (z nakładkami), ha. */
    val hectares: Double get() = segments().sumOf { (a, b) -> a.p.distanceTo(b.p) * a.widthM } / 10_000

    /** Zużycie z dawek z mapy (dawka/ha × powierzchnia odcinka); null bez mapy dawek. */
    val appliedAmount: Double? get() {
        val segs = segments().filter { it.first.rate != null }
        if (segs.isEmpty()) return null
        return segs.sumOf { (a, b) -> a.rate!! * a.p.distanceTo(b.p) * a.widthM / 10_000 }
    }

    val avgSpeedKmh: Double? get() = points.takeIf { it.isNotEmpty() }?.map { it.speedKmh }?.average()

    /** Kolejne punkty tej samej jazdy (przerwa > 10 m albo > 30 s = nowy odcinek). */
    private fun segments(): List<Pair<Point, Point>> = points.zipWithNext().filter { (a, b) -> a.p.distanceTo(b.p) < 10.0 && b.tS - a.tS < 30 }

    fun toText(): String = buildString {
        for (q in points) {
            append(r(q.p.x)).append(';').append(r(q.p.y)).append(';').append(q.tS).append(';')
                .append(r(q.speedKmh)).append(';').append(r(q.widthM)).append(';').append(q.rate?.let { r(it) } ?: "").append('\n')
        }
    }

    fun loadText(text: String) {
        points.clear()
        for (l in text.lineSequence()) {
            val f = l.split(';')
            if (f.size < 5) continue
            val x = f[0].toDoubleOrNull() ?: continue
            val y = f[1].toDoubleOrNull() ?: continue
            points.add(Point(Vec2(x, y), f[2].toLongOrNull() ?: 0, f[3].toDoubleOrNull() ?: 0.0, f[4].toDoubleOrNull() ?: 0.0, f.getOrNull(5)?.toDoubleOrNull()))
        }
    }

    /** GeoJSON: odcinki jazdy z prędkością, szerokością i dawką (WGS84). */
    fun toGeoJson(plane: LocalPlane, fieldName: String): String = buildString {
        append("{\"type\":\"FeatureCollection\",\"name\":\"").append(fieldName.replace("\"", "'")).append(" — mapa wykonania\",\"features\":[")
        var first = true
        for ((a, b) in segments()) {
            val (la, lo) = plane.toGeo(a.p); val (lb, lob) = plane.toGeo(b.p)
            if (!first) append(','); first = false
            append("{\"type\":\"Feature\",\"properties\":{\"czas\":").append(a.tS).append(",\"predkosc_kmh\":").append(r(a.speedKmh))
                .append(",\"szerokosc_m\":").append(r(a.widthM))
            a.rate?.let { append(",\"dawka\":").append(r(it)) }
            append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[").append(g(lo)).append(',').append(g(la)).append("],[")
                .append(g(lob)).append(',').append(g(lb)).append("]]}}")
        }
        append("]}")
    }

    private fun r(v: Double): String = ((v * 100).roundToInt() / 100.0).toString()
    private fun g(v: Double): String = (kotlin.math.round(v * 1e8) / 1e8).toString()
}
