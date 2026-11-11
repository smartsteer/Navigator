package eu.smartsteer.navigator.core.war

import eu.smartsteer.navigator.core.field.RateMap
import eu.smartsteer.navigator.core.field.RateZone
import eu.smartsteer.navigator.core.geo.LocalPlane

/**
 * Mapa zmiennych dawek z WAR (paczka `mapa_dawek`, docs/WAR.md 3.8): strefy ze zdjęcia NDVI jako siatka oczek ~10 m.
 * Wiersze od południa, w napisie kod strefy w każdym oczku: 0 poza polem, 1 słabsza, 2 typowa, 3 mocniejsza;
 * [rates] = dawka dla kodu. Siatka jest zwięzła (pole 50 ha to kilka KB); na pole zamieniamy ją w prostokąty
 * (oczka tej samej strefy w wierszu, a potem takie same odcinki w kolejnych wierszach — jeden prostokąt).
 */
data class WarRateMap(
    val id: String,
    val warId: String,
    val fieldName: String,
    val product: String,
    val kindName: String,
    val unit: String,
    val date: String,
    val sentAt: String,
    val rates: Map<Int, Double>,
    val minN: Double, val minE: Double, val dn: Double, val de: Double,
    val rows: List<String>,
) {
    /** Nazwa mapy w polu — po niej poznajemy, że mapa przyszła z WAR (i czy jest nowsza). */
    val mapName: String get() = PREFIX + listOf(product.ifEmpty { kindName }, date).filter { it.isNotEmpty() }.joinToString(" · ")

    fun toRateMap(plane: LocalPlane): RateMap {
        data class Run(val c0: Int, val c1: Int, val code: Int)
        val zones = ArrayList<RateZone>()
        fun close(run: Run, r0: Int, r1: Int) {
            val rate = rates[run.code] ?: return
            val s = minN + r0 * dn; val n = minN + (r1 + 1) * dn
            val w = minE + run.c0 * de; val e = minE + (run.c1 + 1) * de
            zones.add(RateZone(listOf(plane.toLocal(s, w), plane.toLocal(s, e), plane.toLocal(n, e), plane.toLocal(n, w)), rate))
        }
        var open = HashMap<Run, Int>()                       // odcinek -> wiersz, w którym się zaczął
        for ((r, row) in rows.withIndex()) {
            val runs = ArrayList<Run>()
            var c = 0
            while (c < row.length) {
                val code = row[c].digitToIntOrNull() ?: 0
                var k = c
                while (k + 1 < row.length && row[k + 1] == row[c]) k++
                if (code != 0) runs.add(Run(c, k, code))
                c = k + 1
            }
            val next = HashMap<Run, Int>()
            for (run in runs) next[run] = open.remove(run) ?: r
            for ((run, r0) in open) close(run, r0, r - 1)
            open = next
        }
        for ((run, r0) in open) close(run, r0, rows.size - 1)
        return RateMap(mapName, unit, zones, rates[2] ?: 0.0)        // poza oczkami (brzeg pola) — dawka typowa
    }

    companion object {
        const val PREFIX = "WAR: "

        fun parse(m: Map<*, *>): WarRateMap? {
            fun s(v: Any?) = (v as? String).orEmpty()
            fun d(v: Any?) = (v as? Number)?.toDouble()
            val pole = m["pole"] as? Map<*, *> ?: return null
            val warId = s(pole["war_id"]).ifEmpty { return null }
            val g = m["siatka"] as? Map<*, *> ?: return null
            val rows = (g["wiersze"] as? List<*>).orEmpty().map { it as? String ?: return null }
            if (rows.isEmpty() || rows.size > 5000 || rows.any { it.length > 5000 }) return null
            val rates = (m["strefy"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                val code = (k as? String)?.toIntOrNull() ?: return@mapNotNull null
                d(v)?.let { code to it }
            }.toMap()
            if (rates.isEmpty()) return null
            val dn = d(g["dn"]) ?: return null
            val de = d(g["de"]) ?: return null
            if (dn <= 0 || de <= 0) return null
            return WarRateMap(
                id = s(m["id"]), warId = warId, fieldName = s(m["nazwa"]).ifEmpty { s(pole["nazwa"]) },
                product = s(m["produkt"]), kindName = s(m["rodzaj_nazwa"]), unit = s(m["jednostka"]).ifEmpty { "kg/ha" },
                date = s(m["data"]), sentAt = s(m["t"]), rates = rates,
                minN = d(g["min_n"]) ?: return null, minE = d(g["min_e"]) ?: return null, dn = dn, de = de, rows = rows,
            )
        }

        /** Nazwa pliku na tablecie dla mapy pola WAR. */
        fun fileName(warId: String) = "mapa_dawek_" + warId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(60) + ".json"
    }
}
