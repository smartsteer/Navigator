package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.math.roundToInt

/** Strefa mapy dawek: wielokąt w płaszczyźnie pola i dawka (np. kg/ha, l/ha). */
data class RateZone(val poly: List<Vec2>, val rate: Double)

/**
 * Mapa zmiennych dawek (aplikacyjna): strefy z dawką. Dawka w danym miejscu = strefa, w której leży punkt
 * (przy nakładaniu — ostatnia na liście), poza strefami — [defaultRate].
 */
data class RateMap(val name: String, val unit: String, val zones: List<RateZone>, val defaultRate: Double = 0.0) {
    val minRate: Double get() = zones.minOfOrNull { it.rate } ?: defaultRate
    val maxRate: Double get() = zones.maxOfOrNull { it.rate } ?: defaultRate

    fun rateAt(p: Vec2): Double {
        for (i in zones.indices.reversed()) if (Polygon.contains(zones[i].poly, p)) return zones[i].rate
        return defaultRate
    }

    companion object {
        /** Własny PGN WAR Navigatora dla sterownika dawki (moduł maszyny / własna płytka). */
        const val PGN_RATE = 0xE0

        /**
         * PGN 224 — dawka docelowa: bajty 0–1 = dawka × 10 (bez znaku, LE), 2 = nr strefy + 1 (0 = poza strefami),
         * 3 = 1 gdy sekcje pracują. Moduły AgOpenGPS nieznane PGN-y pomijają.
         */
        fun frame(rate: Double, zone: Int, working: Boolean): ByteArray {
            val r = (rate * 10).roundToInt().coerceIn(0, 65535)
            val d = byteArrayOf((r and 0xFF).toByte(), (r shr 8).toByte(), (zone + 1).coerceIn(0, 255).toByte(), (if (working) 1 else 0).toByte(), 0, 0, 0, 0)
            return AogFrame.build(PGN_RATE, d)
        }
    }
}

/** Wczytanie map dawek: GeoJSON (Polygon/MultiPolygon z liczbą we właściwościach) i KML (Placemark z liczbą w danych). */
object RateMapImport {
    private val rateKeys = Regex("(?i)rate|dawk|dose|norm|target|amount|value|wart|kg|ilosc|ilość|seed|n_")

    fun parse(text: String, name: String, plane: LocalPlane): RateMap? =
        if (text.trimStart().startsWith("{")) parseGeoJson(text, name, plane) else if (text.contains("<kml", ignoreCase = true)) parseKml(text, name, plane) else null

    fun parseGeoJson(text: String, name: String, plane: LocalPlane): RateMap? {
        val root = MiniJson.parse(text) as? Map<*, *> ?: return null
        val feats = (root["features"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val zones = ArrayList<RateZone>()
        var unit = ""
        for (f in feats) {
            val props = f["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val (key, rate) = pickRate(props) ?: continue
            if (unit.isEmpty()) unit = (props["unit"] as? String) ?: (props["jednostka"] as? String) ?: guessUnit(key)
            val g = f["geometry"] as? Map<*, *> ?: continue
            val polys: List<List<*>> = when (g["type"]) {
                "Polygon" -> listOfNotNull(g["coordinates"] as? List<*>)
                "MultiPolygon" -> (g["coordinates"] as? List<*>).orEmpty().mapNotNull { it as? List<*> }
                else -> emptyList()
            }
            for (poly in polys) {
                val outer = poly.firstOrNull() as? List<*> ?: continue
                val pts = outer.mapNotNull { c ->
                    val l = c as? List<*> ?: return@mapNotNull null
                    val lon = (l.getOrNull(0) as? Double) ?: return@mapNotNull null
                    val lat = (l.getOrNull(1) as? Double) ?: return@mapNotNull null
                    plane.toLocal(lat, lon)
                }.let { if (it.size > 3 && it.first() == it.last()) it.dropLast(1) else it }
                if (pts.size >= 3) zones.add(RateZone(pts, rate))
            }
        }
        return if (zones.isEmpty()) null else RateMap(name, unit, zones)
    }

    fun parseKml(text: String, name: String, plane: LocalPlane): RateMap? {
        val zones = ArrayList<RateZone>()
        var unit = ""
        for (pm in Regex("(?s)<Placemark[^>]*>(.*?)</Placemark>").findAll(text)) {
            val body = pm.groupValues[1]
            val props = HashMap<String, Any>()
            Regex("(?s)<Data\\s+name=\"([^\"]+)\"[^>]*>\\s*<value>([^<]*)</value>").findAll(body).forEach { props[it.groupValues[1]] = it.groupValues[2].trim() }
            Regex("(?s)<SimpleData\\s+name=\"([^\"]+)\"[^>]*>([^<]*)</SimpleData>").findAll(body).forEach { props[it.groupValues[1]] = it.groupValues[2].trim() }
            Regex("(?s)<name>([^<]*)</name>").find(body)?.let { props["name"] = it.groupValues[1].trim() }
            val (key, rate) = pickRate(props) ?: continue
            if (unit.isEmpty()) unit = guessUnit(key)
            val coords = Regex("(?s)<outerBoundaryIs>.*?<coordinates>(.*?)</coordinates>").find(body)?.groupValues?.get(1)
                ?: Regex("(?s)<coordinates>(.*?)</coordinates>").find(body)?.groupValues?.get(1) ?: continue
            val pts = eu.smartsteer.navigator.core.io.FieldImport.kmlCoords(coords).map { (lat, lon) -> plane.toLocal(lat, lon) }
                .let { if (it.size > 3 && it.first() == it.last()) it.dropLast(1) else it }
            if (pts.size >= 3) zones.add(RateZone(pts, rate))
        }
        return if (zones.isEmpty()) null else RateMap(name, unit, zones)
    }

    /** Pierwsza liczbowa właściwość o nazwie jak dawka; inaczej pierwsza liczba. */
    private fun pickRate(props: Map<*, *>): Pair<String, Double>? {
        fun num(v: Any?): Double? = when (v) { is Double -> v; is String -> v.replace(',', '.').trim().toDoubleOrNull(); else -> null }
        props.entries.firstOrNull { (k, v) -> (k as? String)?.let { rateKeys.containsMatchIn(it) } == true && num(v) != null }
            ?.let { return (it.key as String) to num(it.value)!! }
        props.entries.firstOrNull { (k, v) -> k != "id" && k != "fid" && num(v) != null }?.let { return (it.key as? String).orEmpty() to num(it.value)!! }
        return null
    }

    private fun guessUnit(key: String): String {
        val k = key.lowercase()
        return when {
            "l" == k || k.contains("l_ha") || k.contains("lha") -> "l/ha"
            k.contains("seed") || k.contains("nasion") || k.contains("szt") -> "szt./m²"
            else -> "kg/ha"
        }
    }
}
