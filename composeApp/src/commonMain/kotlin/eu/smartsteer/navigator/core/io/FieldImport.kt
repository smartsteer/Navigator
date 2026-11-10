package eu.smartsteer.navigator.core.io

/** Pole wczytane z pliku: współrzędne geograficzne (szerokość, długość). */
data class ImportedField(
    val name: String,
    val boundary: List<Pair<Double, Double>>? = null,
    val ab: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null,
    val curve: List<Pair<Double, Double>>? = null,
)

/**
 * Import granic i linii z KML (Google Earth, większość programów rolniczych) i ISOXML (TASKDATA.XML, ISO 11783-10).
 * Prosty, tolerancyjny odczyt bez biblioteki XML — wystarczy do granic pól i linii prowadzenia.
 */
object FieldImport {

    // ---------------- KML ----------------

    /** Wszystkie placemarki z pliku KML łączymy w jedno pole: pierwszy wielokąt = granica, linie = AB / krzywa. */
    fun parseKml(text: String, fallbackName: String): List<ImportedField> {
        val docName = tag(text, "Document")?.let { firstTag(it, "name") }?.trim()?.takeIf { it.isNotEmpty() }
        var boundary: List<Pair<Double, Double>>? = null
        var ab: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null
        var curve: List<Pair<Double, Double>>? = null
        var firstPolyName: String? = null
        for (pm in tags(text, "Placemark")) {
            val name = firstTag(pm, "name")?.trim()
            tag(pm, "Polygon")?.let { poly ->
                val outer = tag(poly, "outerBoundaryIs") ?: poly
                val pts = firstTag(outer, "coordinates")?.let { kmlCoords(it) }
                if (boundary == null && pts != null && pts.size >= 3) { boundary = closeOpen(pts); firstPolyName = name }
            }
            tag(pm, "LineString")?.let { ls ->
                val pts = firstTag(ls, "coordinates")?.let { kmlCoords(it) } ?: return@let
                if (pts.size == 2 && ab == null) ab = pts[0] to pts[1]
                else if (pts.size > 2 && curve == null) curve = pts
            }
        }
        if (boundary == null && ab == null && curve == null) return emptyList()
        val name = clean(docName ?: firstPolyName ?: fallbackName)
        return listOf(ImportedField(name, boundary, ab, curve))
    }

    /** „lon,lat[,alt] lon,lat …” → (lat, lon). */
    internal fun kmlCoords(s: String): List<Pair<Double, Double>> =
        s.trim().split(Regex("\\s+")).mapNotNull { t ->
            val p = t.split(',')
            val lon = p.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val lat = p.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            lat to lon
        }

    // ---------------- ISOXML ----------------

    /**
     * TASKDATA.XML: każde pole (PFD, nazwa w atrybucie C) → granica z PLN typu 1 (LSG typu 1),
     * linie prowadzenia z GPN (LSG typu 5): 2 punkty = AB, więcej = krzywa. Punkty PNT: C = szerokość, D = długość.
     */
    fun parseIsoxml(text: String): List<ImportedField> {
        val out = ArrayList<ImportedField>()
        for (pfd in elements(text, "PFD")) {
            val name = clean(attr(pfd.open, "C") ?: attr(pfd.open, "A") ?: "Pole")
            var boundary: List<Pair<Double, Double>>? = null
            for (pln in elements(pfd.body, "PLN")) {
                if (attr(pln.open, "A") != "1") continue
                val lsg = elements(pln.body, "LSG").firstOrNull { attr(it.open, "A") == "1" } ?: continue
                val pts = points(lsg.body)
                if (pts.size >= 3) { boundary = closeOpen(pts); break }
            }
            var ab: Pair<Pair<Double, Double>, Pair<Double, Double>>? = null
            var curve: List<Pair<Double, Double>>? = null
            for (lsg in elements(pfd.body, "LSG")) {
                if (attr(lsg.open, "A") != "5") continue
                val pts = points(lsg.body)
                if (pts.size == 2 && ab == null) ab = pts[0] to pts[1]
                else if (pts.size > 2 && curve == null) curve = pts
            }
            if (boundary != null || ab != null || curve != null) out.add(ImportedField(name, boundary, ab, curve))
        }
        return out
    }

    private fun points(body: String): List<Pair<Double, Double>> =
        Regex("<PNT\\b[^>]*>").findAll(body).mapNotNull { m ->
            val lat = attr(m.value, "C")?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = attr(m.value, "D")?.toDoubleOrNull() ?: return@mapNotNull null
            lat to lon
        }.toList()

    // ---------------- pomocnicze ----------------

    private class El(val open: String, val body: String)

    /** Elementy <NAME ...>…</NAME> (albo samozamykające) na dowolnym poziomie, bez zagnieżdżeń tej samej nazwy. */
    private fun elements(text: String, name: String): List<El> {
        val out = ArrayList<El>()
        val re = Regex("<$name\\b[^>]*?(/?)>")
        var from = 0
        while (true) {
            val m = re.find(text, from) ?: break
            if (m.groupValues[1] == "/") { out.add(El(m.value, "")); from = m.range.last + 1; continue }
            val close = text.indexOf("</$name>", m.range.last + 1)
            if (close < 0) { out.add(El(m.value, text.substring(m.range.last + 1))); break }
            out.add(El(m.value, text.substring(m.range.last + 1, close)))
            from = close + name.length + 3
        }
        return out
    }

    private fun attr(open: String, a: String): String? =
        Regex("\\s$a=\"([^\"]*)\"").find(open)?.groupValues?.get(1)?.let { unescape(it) }

    private fun tags(text: String, name: String): List<String> = elements(text, name).map { it.body }
    private fun tag(text: String, name: String): String? = elements(text, name).firstOrNull()?.body
    private fun firstTag(text: String, name: String): String? = tag(text, name)?.let { unescape(stripCdata(it)) }

    private fun stripCdata(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** Wielokąt bez powtórzonego ostatniego punktu. */
    private fun closeOpen(p: List<Pair<Double, Double>>) = if (p.size > 3 && p.first() == p.last()) p.dropLast(1) else p

    private fun clean(s: String) = s.trim().filter { it !in "\\/:*?\"<>|" && it.code >= 32 }.take(40).ifEmpty { "Pole" }
}
