package eu.smartsteer.navigator.core.field

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import eu.smartsteer.navigator.core.settings.Profiles
import eu.smartsteer.navigator.core.settings.TextFiles
import eu.smartsteer.navigator.core.update.MiniJson

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
        append("  \"warId\": ").append(if (warId == null) "null" else "\"" + esc(warId) + "\"").append('\n')
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
