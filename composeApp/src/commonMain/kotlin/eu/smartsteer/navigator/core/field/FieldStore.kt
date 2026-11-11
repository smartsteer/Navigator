package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import eu.smartsteer.navigator.core.settings.Profiles
import eu.smartsteer.navigator.core.settings.TextFiles
import eu.smartsteer.navigator.core.update.MiniJson

/** Linia prowadzenia zapisana w polu (jak lista linii w AgOpenGPS): prosta AB albo krzywa. */
data class Track(val name: String, val ab: AbLine? = null, val curve: List<Vec2>? = null) {
    val isCurve: Boolean get() = ab == null && curve != null
}

/** Znacznik na polu (kamień, mokre miejsce…). [color]: 0 = czerwony, 1 = żółty, 2 = niebieski. */
data class Flag(val p: Vec2, val color: Int = 0, val note: String = "")

/** Zapisane pole: początek płaszczyzny lokalnej, granica, uwrocie i linie prowadzenia. */
data class FieldData(
    val name: String,
    val originLat: Double,
    val originLon: Double,
    val boundary: List<Vec2>? = null,
    val headlandWidth: Double = 0.0,
    val ab: AbLine? = null,
    /** Nagrana krzywa odniesienia (punkty; kursy liczone przy wczytaniu). */
    val curve: List<Vec2>? = null,
    /** Id pola w WAR (pole przyszło z WAR) — wraca w raporcie pracy. */
    val warId: String? = null,
    /** Wszystkie linie pola; [ab]/[curve] to linia aktywna (zgodność ze starszymi plikami). */
    val tracks: List<Track> = emptyList(),
    val activeTrack: Int = -1,
    val flags: List<Flag> = emptyList(),
    /** Przeszkody wewnątrz pola (oczko wodne, słup, kępa drzew) — sekcje się na nich wyłączają. */
    val obstacles: List<List<Vec2>> = emptyList(),
    /** Mapa zmiennych dawek (aplikacyjna) wczytana do pola. */
    val rateMap: RateMap? = null,
) {
    fun toJson(): String = buildString {
        append("{\n")
        append("  \"name\": \"").append(esc(name)).append("\",\n")
        append("  \"originLat\": ").append(originLat).append(",\n")
        append("  \"originLon\": ").append(originLon).append(",\n")
        append("  \"headlandWidth\": ").append(headlandWidth).append(",\n")
        append("  \"boundary\": ").append(pts(boundary)).append(",\n")
        append("  \"ab\": ")
        if (ab == null) append("null") else append("[${n(ab.a.x)}, ${n(ab.a.y)}, ${n(ab.b.x)}, ${n(ab.b.y)}]")
        append(",\n")
        append("  \"curve\": ").append(pts(curve)).append(",\n")
        append("  \"warId\": ").append(if (warId == null) "null" else "\"" + esc(warId) + "\"").append(",\n")
        append("  \"tracks\": [")
        tracks.forEachIndexed { i, t ->
            if (i > 0) append(", ")
            append("{\"name\": \"").append(esc(t.name)).append("\", \"ab\": ")
            val ab = t.ab
            if (ab == null) append("null") else append("[${n(ab.a.x)}, ${n(ab.a.y)}, ${n(ab.b.x)}, ${n(ab.b.y)}]")
            append(", \"curve\": ").append(pts(t.curve)).append("}")
        }
        append("],\n")
        append("  \"activeTrack\": ").append(activeTrack).append(",\n")
        append("  \"flags\": [")
        flags.forEachIndexed { i, f ->
            if (i > 0) append(", ")
            append("{\"x\": ${n(f.p.x)}, \"y\": ${n(f.p.y)}, \"color\": ${f.color}, \"note\": \"").append(esc(f.note)).append("\"}")
        }
        append("],\n")
        append("  \"obstacles\": [").append(obstacles.joinToString(", ") { pts(it) }).append("],\n")
        append("  \"rateMap\": ")
        val rm = rateMap
        if (rm == null) append("null") else {
            append("{\"name\": \"").append(esc(rm.name)).append("\", \"unit\": \"").append(esc(rm.unit)).append("\", \"default\": ").append(rm.defaultRate)
            append(", \"zones\": [")
            rm.zones.forEachIndexed { i, z -> if (i > 0) append(", "); append("{\"rate\": ").append(z.rate).append(", \"poly\": ").append(pts(z.poly)).append("}") }
            append("]}")
        }
        append("\n")
        append("}\n")
    }

    companion object {
        fun fromJson(text: String): FieldData? {
            val m = MiniJson.parse(text) as? Map<*, *> ?: return null
            val name = m["name"] as? String ?: return null
            val lat = m["originLat"] as? Double ?: return null
            val lon = m["originLon"] as? Double ?: return null
            val ab = (m["ab"] as? List<*>)?.mapNotNull { it as? Double }?.takeIf { it.size == 4 }
                ?.let { AbLine(Vec2(it[0], it[1]), Vec2(it[2], it[3])) }
            return FieldData(
                name = name,
                originLat = lat,
                originLon = lon,
                boundary = readPts(m["boundary"]),
                headlandWidth = (m["headlandWidth"] as? Double) ?: 0.0,
                ab = ab,
                curve = readPts(m["curve"]),
                warId = m["warId"] as? String,
                tracks = (m["tracks"] as? List<*>).orEmpty().mapNotNull { e ->
                    val t = e as? Map<*, *> ?: return@mapNotNull null
                    val tab = (t["ab"] as? List<*>)?.mapNotNull { it as? Double }?.takeIf { it.size == 4 }
                        ?.let { AbLine(Vec2(it[0], it[1]), Vec2(it[2], it[3])) }
                    val tc = readPts(t["curve"])
                    if (tab == null && tc == null) null else Track((t["name"] as? String) ?: "Linia", tab, tc)
                },
                activeTrack = ((m["activeTrack"] as? Double) ?: -1.0).toInt(),
                flags = (m["flags"] as? List<*>).orEmpty().mapNotNull { e ->
                    val f = e as? Map<*, *> ?: return@mapNotNull null
                    val x = f["x"] as? Double ?: return@mapNotNull null
                    val y = f["y"] as? Double ?: return@mapNotNull null
                    Flag(Vec2(x, y), ((f["color"] as? Double) ?: 0.0).toInt(), (f["note"] as? String).orEmpty())
                },
                obstacles = (m["obstacles"] as? List<*>).orEmpty().mapNotNull { readPts(it)?.takeIf { p -> p.size >= 3 } },
                rateMap = (m["rateMap"] as? Map<*, *>)?.let { r ->
                    val zones = (r["zones"] as? List<*>).orEmpty().mapNotNull { e ->
                        val z = e as? Map<*, *> ?: return@mapNotNull null
                        val rate = z["rate"] as? Double ?: return@mapNotNull null
                        val poly = readPts(z["poly"])?.takeIf { it.size >= 3 } ?: return@mapNotNull null
                        RateZone(poly, rate)
                    }
                    if (zones.isEmpty()) null else RateMap((r["name"] as? String).orEmpty(), (r["unit"] as? String).orEmpty(), zones, (r["default"] as? Double) ?: 0.0)
                },
            )
        }

        private fun readPts(v: Any?): List<Vec2>? = (v as? List<*>)?.mapNotNull { e ->
            val l = e as? List<*> ?: return@mapNotNull null
            val x = l.getOrNull(0) as? Double ?: return@mapNotNull null
            val y = l.getOrNull(1) as? Double ?: return@mapNotNull null
            Vec2(x, y)
        }?.takeIf { it.size >= 2 }

        private fun pts(p: List<Vec2>?): String =
            if (p == null) "null" else p.joinToString(", ", "[", "]") { "[${n(it.x)}, ${n(it.y)}]" }

        private fun n(v: Double): String {
            val r = kotlin.math.round(v * 1000) / 1000
            return r.toString()
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

/**
 * Pola w katalogu aplikacji: „nazwa.json” (granica, linie) i „nazwa.pokrycie.txt” (mapa pokrycia),
 * przed nadpisaniem kopia „.last” — tak jak profile pojazdów.
 */
class FieldStore(private val files: TextFiles) {
    val location: String get() = files.location

    fun names(): List<String> = files.list().filter { it.endsWith(EXT) }.map { it.removeSuffix(EXT) }.sortedBy { it.lowercase() }

    fun exists(name: String) = files.read(name + EXT) != null

    fun load(name: String): Pair<FieldData, String>? {
        val f = files.read(name + EXT)?.let { FieldData.fromJson(it) }
            ?: files.read(name + BACKUP)?.let { FieldData.fromJson(it) }
            ?: return null
        return f to (files.read(name + COVERAGE) ?: "")
    }

    /** Samo pole (bez mapy pokrycia) — do szukania, na którym polu stoi ciągnik. */
    fun loadData(name: String): FieldData? = files.read(name + EXT)?.let { FieldData.fromJson(it) }

    fun save(field: FieldData, coverageText: String) {
        val name = Profiles.sanitize(field.name)
        require(name.isNotEmpty()) { "Pusta nazwa pola" }
        val old = files.read(name + EXT)
        val json = field.copy(name = name).toJson()
        if (old != json) {
            if (old != null && FieldData.fromJson(old) != null) files.write(name + BACKUP, old)
            files.write(name + EXT, json)
        }
        files.write(name + COVERAGE, coverageText)
    }

    fun delete(name: String) {
        files.delete(name + EXT); files.delete(name + BACKUP); files.delete(name + COVERAGE)
    }

    companion object {
        const val EXT = ".json"
        const val BACKUP = ".last"
        const val COVERAGE = ".pokrycie.txt"
    }
}
