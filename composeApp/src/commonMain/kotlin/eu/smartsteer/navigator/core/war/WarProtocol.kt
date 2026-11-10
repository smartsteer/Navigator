package eu.smartsteer.navigator.core.war

import eu.smartsteer.navigator.core.field.Coverage
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.ntrip.Ntrip
import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/** Ustawienia połączenia z WAR (Ustawienia → WAR). */
data class WarConfig(
    val server: String = "https://www.agro.smartsteer.eu",
    /** Kod nawigacji z telefonu (znormalizowany: 16 znaków). */
    val code: String = "",
    /** Stały identyfikator tego komputera/ciągnika (losowany raz). */
    val machineId: String = "",
    /** Nazwa ciągnika widoczna w WAR. */
    val tractorName: String = "",
    /** Stała nazwa narzędzia — WAR przypisuje do niej rodzaj zabiegu. Pusta = nazwa profilu pojazdu. */
    val toolName: String = "",
    /** Kto jedzie (opcjonalnie). */
    val operator: String = "",
    /** Pokazuj pozycję na mapie „Na żywo” w WAR. */
    val live: Boolean = true,
    /** Kiedy ostatnio wysłano paczkę „urzadzenie” (ms). */
    val lastDeviceMs: Long = 0,
) {
    fun toJson(): String = Json.obj(
        "server" to server, "code" to code, "machineId" to machineId, "tractorName" to tractorName,
        "toolName" to toolName, "operator" to operator, "live" to live, "lastDeviceMs" to lastDeviceMs,
    )

    companion object {
        fun fromJson(text: String): WarConfig? {
            val m = MiniJson.parse(text) as? Map<*, *> ?: return null
            val d = WarConfig()
            return WarConfig(
                server = m["server"] as? String ?: d.server,
                code = m["code"] as? String ?: d.code,
                machineId = m["machineId"] as? String ?: d.machineId,
                tractorName = m["tractorName"] as? String ?: d.tractorName,
                toolName = m["toolName"] as? String ?: d.toolName,
                operator = m["operator"] as? String ?: d.operator,
                live = m["live"] as? Boolean ?: d.live,
                lastDeviceMs = (m["lastDeviceMs"] as? Double)?.toLong() ?: d.lastDeviceMs,
            )
        }
    }
}

/** Pole przysłane z WAR (paczka `pola_war` albo pole w zleceniu). Współrzędne [długość, szerokość]. */
data class WarField(
    val id: String,
    val name: String,
    val ha: Double,
    val crop: String,
    val ring: List<Pair<Double, Double>>,
    val holes: List<List<Pair<Double, Double>>>,
    val parts: Int,
    val centerLat: Double,
    val centerLon: Double,
)

/** Zlecenie z WAR (paczka `zlecenie`). */
data class WarOrder(
    val id: String,
    val status: String,
    val kind: String,
    val kindName: String,
    val product: String,
    val dose: Double?,
    val unit: String,
    val water: Double?,
    val due: String,
    val forWho: String,
    val from: String,
    val notes: String,
    val warField: WarField?,
    /** Stan po stronie Navigatora: "" (nowe), przyjete, odrzucone, zrobione. */
    val local: String = "",
) {
    val title: String get() = "${kindName.ifEmpty { kind }}${warField?.let { " — ${it.name}" } ?: ""}"
    val details: String get() = listOfNotNull(
        product.takeIf { it.isNotEmpty() },
        dose?.let { "${fmt(it)} $unit" },
        water?.let { "ciecz ${fmt(it)} l/ha" },
        due.takeIf { it.isNotEmpty() }?.let { "termin $it" },
        forWho.takeIf { it.isNotEmpty() }?.let { "dla: $it" },
    ).joinToString(" · ")

    private fun fmt(v: Double) = if (v == floor(v)) v.toLong().toString() else v.toString().replace('.', ',')
}

/**
 * Protokół WAR według docs/WAR.md — implementacja własna (bez kodu WAR / WAR Sync).
 */
object WarProtocol {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Wielkie litery, tylko A–Z i 0–9. */
    fun normalizeCode(raw: String): String = raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

    fun isValidCode(code: String): Boolean = code.length == 16 && code.all { it in ALPHABET }

    /** „ABCDEFGHJKLMNPQR” → „ABCD-EFGH-JKLM-NPQR”. */
    fun pretty(code: String): String = code.chunked(4).joinToString("-")

    fun worksMailboxText(code: String) = "war-sync-id:$code"
    fun navMailboxText(code: String) = "war-nav-zl:$code"

    /** Losowe id maszyny: „tab-” + 8 znaków z alfabetu kodu (mieści się w [A-Za-z0-9_-]). */
    fun machineId(random: ByteArray): String = "tab-" + random.take(8).joinToString("") { ALPHABET[(it.toInt() and 0xFF) % ALPHABET.length].lowercase() }

    // ---------------- paczki Navigator → WAR ----------------

    fun device(c: WarConfig, version: String, t: String): String = Json.obj(
        "typ" to "urzadzenie", "v" to 1, "program" to "navigator", "maszyna" to c.machineId,
        "nazwa" to c.tractorName.ifEmpty { "Navigator" }, "wersja" to version, "t" to t,
    )

    fun orderStatus(orderId: String, state: String, c: WarConfig, t: String): String = Json.obj(
        "typ" to "zlecenie_status", "v" to 1, "zlecenie_id" to orderId, "stan" to state,
        "kto" to c.operator, "komputer" to c.machineId, "t" to t,
    )

    fun position(
        lat: Double, lon: Double, courseDeg: Double, kmh: Double, fix: Int, sats: Int,
        tool: String, width: Double, field: String, working: Boolean, c: WarConfig, t: String,
    ): String = Json.obj(
        "typ" to "pozycja", "v" to 1, "lat" to Json.Raw(r(lat, 7)), "lon" to Json.Raw(r(lon, 7)),
        "kurs" to Json.Raw(r(courseDeg, 1)), "kmh" to Json.Raw(r(kmh, 1)), "fix" to fix, "sat" to sats,
        "narzedzie" to tool, "szer" to width, "pole" to field, "pracuje" to working,
        "kto" to c.operator, "komputer" to c.machineId, "t" to t,
    )

    /** Dane pracy w polu (§3.5). */
    class Work(
        val id: String, val fieldName: String, val warId: String?, val tool: String, val width: Double,
        val tractor: String, val start: String, val end: String, val ha: Double, val haPasses: Double, val haField: Double?,
        val pointLat: Double, val pointLon: Double, val boundary: List<Pair<Double, Double>>?, val orderId: String?,
        val version: String, val grid: CoverageGrid?,
    )

    fun work(w: Work, c: WarConfig): String {
        val pole = Json.obj("nazwa" to w.fieldName, "folder" to w.fieldName, "war_id" to w.warId)
        val granica = w.boundary?.let { b ->
            val closed = if (b.first() == b.last()) b else b + b.first()
            Json.Raw("{\"type\":\"Polygon\",\"coordinates\":[" + closed.joinToString(",", "[", "]") { "[${r(it.second, 7)},${r(it.first, 7)}]" } + "]}")
        }
        val pokrycie = w.grid?.let { g ->
            Json.Raw(
                Json.obj(
                    "lat0" to Json.Raw(r(g.lat0, 7)), "lon0" to Json.Raw(r(g.lon0, 7)), "e0" to g.e0, "n0" to g.n0,
                    "k" to g.k, "w" to g.w, "h" to g.h, "b" to Ntrip.base64(g.bits),
                ),
            )
        }
        return Json.obj(
            "v" to 1, "id" to w.id, "zrodlo" to "SmartSteer Navigator", "war_sync" to w.version, "komputer" to c.machineId,
            "pole" to Json.Raw(pole),
            "narzedzie" to Json.Raw(Json.obj("nazwa" to w.tool, "szerokosc" to w.width)), "pojazd" to w.tractor,
            "start" to w.start, "koniec" to w.end,
            "ha" to Json.Raw(r(w.ha, 4)), "ha_przejazdow" to Json.Raw(r(w.haPasses, 4)), "ha_pola" to w.haField?.let { Json.Raw(r(it, 4)) },
            "punkt" to Json.Raw(Json.obj("lat" to Json.Raw(r(w.pointLat, 7)), "lon" to Json.Raw(r(w.pointLon, 7)))),
            "granica" to granica, "zlecenie" to w.orderId, "kto" to c.operator.takeIf { it.isNotEmpty() },
            "pokrycie" to pokrycie,
        )
    }

    // ---------------- paczki WAR → Navigator ----------------

    /** Typ paczki („pola_war”, „zlecenie”, …) i jej treść. */
    fun parse(json: String): Pair<String, Map<*, *>>? {
        val m = MiniJson.parse(json) as? Map<*, *> ?: return null
        return (m["typ"] as? String ?: "") to m
    }

    fun parseField(m: Map<*, *>): WarField? {
        val id = (m["id"] as? String) ?: (m["id"] as? Double)?.toLong()?.toString() ?: return null
        val ring = ring(m["granica"]) ?: return null
        val holes = (m["dziury"] as? List<*>).orEmpty().mapNotNull { ring(it) }
        val c = m["srodek"] as? Map<*, *>
        val lat = c?.get("lat") as? Double ?: ring.sumOf { it.second } / ring.size
        val lon = c?.get("lon") as? Double ?: ring.sumOf { it.first } / ring.size
        return WarField(
            id = id, name = m["nazwa"] as? String ?: "Pole $id", ha = m["ha"] as? Double ?: 0.0,
            crop = m["uprawa"] as? String ?: "", ring = ring, holes = holes,
            parts = (m["czesci"] as? Double)?.toInt() ?: 1, centerLat = lat, centerLon = lon,
        )
    }

    fun parseOrder(m: Map<*, *>): WarOrder? {
        val id = m["id"] as? String ?: return null
        return WarOrder(
            id = id, status = m["status"] as? String ?: "nowe", kind = m["rodzaj"] as? String ?: "inne",
            kindName = m["rodzaj_nazwa"] as? String ?: "", product = m["produkt"] as? String ?: "",
            dose = m["dawka"] as? Double, unit = m["jedn"] as? String ?: "", water = m["ciecz"] as? Double,
            due = m["termin"] as? String ?: "", forWho = m["dla"] as? String ?: "", from = m["od"] as? String ?: "",
            notes = m["uwagi"] as? String ?: "", warField = (m["pole"] as? Map<*, *>)?.let { parseField(it) },
        )
    }

    /** Zapis pola w tym samym układzie, co przysyła WAR (do pamięci podręcznej na dysku). */
    fun fieldJson(f: WarField): String {
        fun ring(r: List<Pair<Double, Double>>) = Json.Raw(r.joinToString(",", "[", "]") { "[${it.first},${it.second}]" })
        return Json.obj(
            "id" to f.id, "nazwa" to f.name, "ha" to f.ha, "uprawa" to f.crop, "granica" to ring(f.ring),
            "dziury" to Json.Raw(f.holes.joinToString(",", "[", "]") { ring(it).text }), "czesci" to f.parts,
            "srodek" to Json.Raw(Json.obj("lat" to f.centerLat, "lon" to f.centerLon)),
        )
    }

    fun orderJson(o: WarOrder): String = Json.obj(
        "id" to o.id, "status" to o.status, "rodzaj" to o.kind, "rodzaj_nazwa" to o.kindName, "produkt" to o.product,
        "dawka" to o.dose, "jedn" to o.unit, "ciecz" to o.water, "termin" to o.due, "dla" to o.forWho, "od" to o.from,
        "uwagi" to o.notes, "pole" to o.warField?.let { Json.Raw(fieldJson(it)) }, "lokalnie" to o.local,
    )

    fun parseOrderWithLocal(m: Map<*, *>): WarOrder? = parseOrder(m)?.copy(local = m["lokalnie"] as? String ?: "")

    /** [[lon, lat], …] → lista (lon, lat), bez powtórzonego ostatniego punktu. */
    private fun ring(v: Any?): List<Pair<Double, Double>>? {
        val pts = (v as? List<*>)?.mapNotNull { p ->
            val l = p as? List<*> ?: return@mapNotNull null
            val lon = l.getOrNull(0) as? Double ?: return@mapNotNull null
            val lat = l.getOrNull(1) as? Double ?: return@mapNotNull null
            lon to lat
        } ?: return null
        val open = if (pts.size > 3 && pts.first() == pts.last()) pts.dropLast(1) else pts
        return open.takeIf { it.size >= 3 }
    }

    internal fun r(v: Double, d: Int): String {
        var s = 1.0
        repeat(d) { s *= 10 }
        return (kotlin.math.round(v * s) / s).toString()
    }
}

/** Składanie listy pól z WAR z części o tym samym `skrot` (§3.2). */
class FieldPartsAssembler {
    private val parts = HashMap<String, HashMap<Int, List<WarField>>>()

    /** Zwraca pełną listę pól, gdy przyszły wszystkie części; inaczej null. */
    fun add(m: Map<*, *>): List<WarField>? {
        val skrot = m["skrot"] as? String ?: return null
        val part = (m["czesc"] as? Double)?.toInt() ?: 1
        val total = (m["czesci"] as? Double)?.toInt() ?: 1
        val fields = (m["pola"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let { f -> WarProtocol.parseField(f) } }
        val map = parts.getOrPut(skrot) { HashMap() }
        map[part] = fields
        if ((1..total).all { it in map }) {
            parts.remove(skrot)
            return (1..total).flatMap { map[it]!! }
        }
        return null
    }
}

/**
 * Mapa obrobionych komórek dla WAR (§3.5 „pokrycie”): siatka [k] m w układzie WAR
 * (północ = (lat − lat0)·mLat(lat0), wschód = (lon − lon0)·mLon(lat)), lewy dolny róg e0/n0,
 * bit nr y·w + x (bajt >> 3, bit & 7, najmłodszy pierwszy).
 */
class CoverageGrid(
    val lat0: Double, val lon0: Double, val e0: Double, val n0: Double,
    val k: Double, val w: Int, val h: Int, val bits: ByteArray,
) {
    fun isSet(x: Int, y: Int): Boolean {
        val i = y * w + x
        return (bits[i shr 3].toInt() shr (i and 7)) and 1 == 1
    }

    companion object {
        fun mLat(latDeg: Double): Double {
            val p = latDeg * PI / 180
            return 111132.92 - 559.82 * cos(2 * p) + 1.175 * cos(4 * p) - 0.0023 * cos(6 * p)
        }

        fun mLon(latDeg: Double): Double {
            val p = latDeg * PI / 180
            return 111412.84 * cos(p) - 93.5 * cos(3 * p) + 0.118 * cos(5 * p)
        }

        /** Siatka z mapy pokrycia pola (płaszczyzna [plane] = układ pola w Navigatorze). Null = nic nie obrobiono. */
        fun from(coverage: Coverage, plane: LocalPlane, k: Double = 2.0, maxCells: Int = 4_000_000): CoverageGrid? {
            val lat0 = plane.originLat
            val lon0 = plane.originLon
            val mLat0 = mLat(lat0)
            var minE = Double.MAX_VALUE; var minN = Double.MAX_VALUE
            var maxE = -Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
            for (s in coverage.strips) for (p in s.left + s.right) {
                val (lat, lon) = plane.toGeo(p)
                val e = (lon - lon0) * mLon(lat)
                val n = (lat - lat0) * mLat0
                if (e < minE) minE = e; if (e > maxE) maxE = e
                if (n < minN) minN = n; if (n > maxN) maxN = n
            }
            if (minE > maxE) return null
            val e0 = floor(minE / k) * k
            val n0 = floor(minN / k) * k
            val w = ceil((maxE - e0) / k).toInt().coerceAtLeast(1)
            val h = ceil((maxN - n0) / k).toInt().coerceAtLeast(1)
            if (w.toLong() * h > maxCells) return null
            val bits = ByteArray((w * h + 7) / 8)
            for (y in 0 until h) {
                val n = n0 + (y + 0.5) * k
                val lat = lat0 + n / mLat0
                val ml = mLon(lat)
                for (x in 0 until w) {
                    val e = e0 + (x + 0.5) * k
                    val lon = lon0 + e / ml
                    if (coverage.isCovered(plane.toLocal(lat, lon))) {
                        val i = y * w + x
                        bits[i shr 3] = (bits[i shr 3].toInt() or (1 shl (i and 7))).toByte()
                    }
                }
            }
            return CoverageGrid(lat0, lon0, e0, n0, k, w, h, bits)
        }
    }
}

/** Mały zapis JSON (bez biblioteki). */
object Json {
    /** Wartość wstawiana bez cudzysłowów (gotowy JSON albo liczba). */
    class Raw(val text: String)

    /** Pola z wartością null są pomijane. */
    fun obj(vararg kv: Pair<String, Any?>): String = kv.filter { it.second != null }.joinToString(",", "{", "}") { (k, v) ->
        "\"" + esc(k) + "\":" + value(v)
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.text
        is String -> "\"" + esc(v) + "\""
        is Boolean, is Int, is Long -> v.toString()
        is Double -> if (v == floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() + ".0" else v.toString()
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> "\"" + esc(v.toString()) + "\""
    }

    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
}
