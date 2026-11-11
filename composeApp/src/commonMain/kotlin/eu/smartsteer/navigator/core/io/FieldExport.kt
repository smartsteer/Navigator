package eu.smartsteer.navigator.core.io

import eu.smartsteer.navigator.core.field.FieldData
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2

/**
 * Eksport pola do innych programów i terminali:
 *  - KML (Google Earth, WAR, większość programów rolniczych): granica, przeszkody, linie, znaczniki,
 *  - ISOXML TASKDATA.XML (terminale ISOBUS): pole (PFD) z granicą (PLN typ 1), przeszkodami (PLN typ 6)
 *    i liniami prowadzenia (GGP/GPN, LSG typ 5).
 */
object FieldExport {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun f(v: Double, d: Int = 9): String {
        var m = 1.0
        repeat(d) { m *= 10 }
        return (kotlin.math.round(v * m) / m).toString()
    }

    fun kml(field: FieldData): String {
        val pl = LocalPlane(field.originLat, field.originLon)
        fun c(p: Vec2): String { val (lat, lon) = pl.toGeo(p); return "${f(lon)},${f(lat)},0" }
        fun ring(pts: List<Vec2>) = (pts + pts.first()).joinToString(" ") { c(it) }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        sb.append("<name>").append(esc(field.name)).append("</name>\n")
        sb.append("<Style id=\"granica\"><LineStyle><color>ff00ff00</color><width>3</width></LineStyle><PolyStyle><color>3300ff00</color></PolyStyle></Style>\n")
        sb.append("<Style id=\"przeszkoda\"><LineStyle><color>ff0000ff</color><width>2</width></LineStyle><PolyStyle><color>550000ff</color></PolyStyle></Style>\n")
        sb.append("<Style id=\"linia\"><LineStyle><color>ff00d7ff</color><width>2</width></LineStyle></Style>\n")
        field.boundary?.let { b ->
            sb.append("<Placemark><name>").append(esc(field.name)).append("</name><styleUrl>#granica</styleUrl><Polygon><outerBoundaryIs><LinearRing><coordinates>")
                .append(ring(b)).append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n")
        }
        field.obstacles.forEachIndexed { i, o ->
            sb.append("<Placemark><name>Przeszkoda ${i + 1}</name><styleUrl>#przeszkoda</styleUrl><Polygon><outerBoundaryIs><LinearRing><coordinates>")
                .append(ring(o)).append("</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>\n")
        }
        val tracks = field.tracks.ifEmpty {
            listOfNotNull(field.ab?.let { eu.smartsteer.navigator.core.field.Track("AB", it) }, field.curve?.let { eu.smartsteer.navigator.core.field.Track("Krzywa", null, it) })
        }
        for (t in tracks) {
            val pts = t.ab?.let { listOf(it.a, it.b) } ?: t.curve ?: continue
            sb.append("<Placemark><name>").append(esc(t.name)).append("</name><styleUrl>#linia</styleUrl><LineString><coordinates>")
                .append(pts.joinToString(" ") { c(it) }).append("</coordinates></LineString></Placemark>\n")
        }
        field.flags.forEachIndexed { i, fl ->
            sb.append("<Placemark><name>").append(esc(fl.note.ifBlank { "Znacznik ${i + 1}" })).append("</name><Point><coordinates>")
                .append(c(fl.p)).append("</coordinates></Point></Placemark>\n")
        }
        sb.append("</Document>\n</kml>\n")
        return sb.toString()
    }

    fun isoxml(field: FieldData): String {
        val pl = LocalPlane(field.originLat, field.originLon)
        val sb = StringBuilder()
        var pnt = 0
        fun pts(list: List<Vec2>, type: Int, close: Boolean): String {
            val all = if (close) list + list.first() else list
            return all.joinToString("") { p ->
                val (lat, lon) = pl.toGeo(p); pnt++
                "<PNT A=\"$type\" C=\"${f(lat)}\" D=\"${f(lon)}\"/>"
            }
        }
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<ISO11783_TaskData VersionMajor=\"4\" VersionMinor=\"2\" ManagementSoftwareManufacturer=\"WAR Navigator\" ManagementSoftwareVersion=\"1\" DataTransferOrigin=\"1\">\n")
        sb.append("<CTR A=\"CTR1\" B=\"WAR\"/>\n<FRM A=\"FRM1\" B=\"Gospodarstwo\" I=\"CTR1\"/>\n")
        val area = field.boundary?.let { eu.smartsteer.navigator.core.field.Polygon.area(it).toLong() } ?: 0L
        sb.append("<PFD A=\"PFD1\" C=\"").append(esc(field.name)).append("\" D=\"$area\" E=\"CTR1\" F=\"FRM1\">\n")
        field.boundary?.let { b ->
            sb.append("<PLN A=\"1\" C=\"$area\"><LSG A=\"1\">").append(pts(b, 2, true)).append("</LSG></PLN>\n")
        }
        for (o in field.obstacles) sb.append("<PLN A=\"6\"><LSG A=\"1\">").append(pts(o, 2, true)).append("</LSG></PLN>\n")
        val tracks = field.tracks.ifEmpty {
            listOfNotNull(field.ab?.let { eu.smartsteer.navigator.core.field.Track("AB", it) }, field.curve?.let { eu.smartsteer.navigator.core.field.Track("Krzywa", null, it) })
        }
        if (tracks.isNotEmpty()) {
            sb.append("<GGP A=\"GGP1\" B=\"Linie\">\n")
            tracks.forEachIndexed { i, t ->
                val ab = t.ab
                val list = ab?.let { listOf(it.a, it.b) } ?: t.curve ?: return@forEachIndexed
                val type = if (ab != null) 1 else 3 // 1 = AB, 3 = krzywa
                sb.append("<GPN A=\"GPN${i + 1}\" B=\"").append(esc(t.name)).append("\" C=\"$type\">")
                if (ab != null) {
                    val (la, lo) = pl.toGeo(ab.a); val (lb, lob) = pl.toGeo(ab.b)
                    sb.append("<LSG A=\"5\"><PNT A=\"6\" C=\"${f(la)}\" D=\"${f(lo)}\"/><PNT A=\"7\" C=\"${f(lb)}\" D=\"${f(lob)}\"/></LSG>")
                } else sb.append("<LSG A=\"5\">").append(pts(list, 9, false)).append("</LSG>")
                sb.append("</GPN>\n")
            }
            sb.append("</GGP>\n")
        }
        sb.append("</PFD>\n</ISO11783_TaskData>\n")
        return sb.toString()
    }
}
