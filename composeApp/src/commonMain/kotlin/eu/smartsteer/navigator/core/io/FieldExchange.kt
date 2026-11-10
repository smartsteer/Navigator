package eu.smartsteer.navigator.core.io

import eu.smartsteer.navigator.core.field.Coverage
import eu.smartsteer.navigator.core.field.FieldData
import eu.smartsteer.navigator.core.field.Polygon
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine

/** Zamiana pól z plików na pola Navigatora i raport zabiegu dla WAR / innych programów (GeoJSON). */
object FieldExchange {

    /** Pole z importu: początek płaszczyzny w środku wszystkich punktów. */
    fun toFieldData(imp: ImportedField): FieldData? {
        val all = (imp.boundary.orEmpty() + imp.curve.orEmpty() + listOfNotNull(imp.ab?.first, imp.ab?.second))
        if (all.isEmpty()) return null
        val lat0 = all.sumOf { it.first } / all.size
        val lon0 = all.sumOf { it.second } / all.size
        val pl = LocalPlane(lat0, lon0)
        fun loc(p: Pair<Double, Double>) = pl.toLocal(p.first, p.second)
        val boundary = imp.boundary?.map { loc(it) }?.let { Polygon.simplify(it, minSpacing = 0.5, tolerance = 0.02) }?.takeIf { it.size >= 3 }
        return FieldData(
            name = imp.name,
            originLat = lat0,
            originLon = lon0,
            boundary = boundary,
            headlandWidth = 0.0,
            ab = imp.ab?.let { AbLine(loc(it.first), loc(it.second)) },
            curve = imp.curve?.map { loc(it) },
        )
    }

    /**
     * Raport zabiegu jako GeoJSON (współrzędne WGS84: długość, szerokość):
     * granica pola i obrobione pasy jako wielokąty, w „properties” — pole, data, hektary, maszyna.
     */
    fun reportGeoJson(
        field: FieldData, coverage: Coverage, profileName: String, toolWidth: Double, sections: Int,
        startedText: String, finishedText: String,
    ): String {
        val pl = LocalPlane(field.originLat, field.originLon)
        fun ring(pts: List<Vec2>): String {
            val closed = if (pts.first() == pts.last()) pts else pts + pts.first()
            return closed.joinToString(",", "[", "]") { p ->
                val (lat, lon) = pl.toGeo(p)
                "[${r7(lon)},${r7(lat)}]"
            }
        }
        val sb = StringBuilder()
        sb.append("{\"type\":\"FeatureCollection\",\n")
        sb.append("\"properties\":{")
        sb.append("\"program\":\"SmartSteer Navigator\",")
        sb.append("\"pole\":\"").append(esc(field.name)).append("\",")
        sb.append("\"start\":\"").append(esc(startedText)).append("\",")
        sb.append("\"koniec\":\"").append(esc(finishedText)).append("\",")
        sb.append("\"obrobiono_ha\":").append(r4(coverage.workedM2 / 10_000)).append(',')
        field.boundary?.let { sb.append("\"powierzchnia_pola_ha\":").append(r4(Polygon.area(it) / 10_000)).append(',') }
        sb.append("\"profil\":\"").append(esc(profileName)).append("\",")
        sb.append("\"szerokosc_m\":").append(toolWidth).append(',')
        sb.append("\"sekcje\":").append(sections)
        sb.append("},\n\"features\":[\n")
        val feats = ArrayList<String>()
        field.boundary?.let { b ->
            feats.add("{\"type\":\"Feature\",\"properties\":{\"rodzaj\":\"granica\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[${ring(b)}]}}")
        }
        for (s in coverage.strips) {
            if (s.left.size < 2) continue
            val poly = s.left + s.right.asReversed()
            feats.add("{\"type\":\"Feature\",\"properties\":{\"rodzaj\":\"pokrycie\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[${ring(poly)}]}}")
        }
        sb.append(feats.joinToString(",\n"))
        sb.append("\n]}\n")
        return sb.toString()
    }

    private fun r7(v: Double) = (kotlin.math.round(v * 1e7) / 1e7).toString()
    private fun r4(v: Double) = (kotlin.math.round(v * 1e4) / 1e4).toString()
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
