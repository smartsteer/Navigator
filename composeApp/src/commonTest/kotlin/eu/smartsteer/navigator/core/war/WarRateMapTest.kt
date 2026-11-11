package eu.smartsteer.navigator.core.war

import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarRateMapTest {
    // tak jak wysyła WAR (isobus.dla_nawigacji + telefon): wiersze od południa, oczka 0,001° × 0,001°
    private val json = """
        {"typ": "mapa_dawek", "v": 1, "id": "md-p1", "pole": {"war_id": "p1", "nazwa": "Za stodołą"}, "nazwa": "Za stodołą",
         "produkt": "Saletra amonowa 34", "rodzaj": "nawoz_kg", "rodzaj_nazwa": "Nawóz", "data": "2026-05-06", "t": "2026-11-11T10:00:00Z",
         "jednostka": "kg/ha", "strefy": {"1": 115, "2": 100, "3": 85},
         "siatka": {"min_n": 52.0, "min_e": 22.0, "dn": 0.001, "de": 0.001, "kol": 4, "rz": 3,
                    "wiersze": ["3300", "3300", "1120"]}}
    """.trimIndent()

    @Test
    fun parseAndZones() {
        val (typ, m) = WarProtocol.parse(json)!!
        assertEquals("mapa_dawek", typ)
        val rm = assertNotNull(WarRateMap.parse(m))
        assertEquals("p1", rm.warId)
        assertEquals("WAR: Saletra amonowa 34 · 2026-05-06", rm.mapName)
        val pl = LocalPlane(52.0015, 22.002)
        val map = rm.toRateMap(pl)
        // dwa dolne wiersze „33” → jeden prostokąt; w górnym „11” i „2”; zera poza polem — bez stref
        assertEquals(3, map.zones.size)
        assertEquals("kg/ha", map.unit)
        assertEquals(100.0, map.defaultRate)
        assertEquals(85.0, map.rateAt(pl.toLocal(52.0015, 22.0005)))     // południowy zachód, środek prostokąta 2 wierszy
        assertEquals(115.0, map.rateAt(pl.toLocal(52.0025, 22.0015)))
        assertEquals(100.0, map.rateAt(pl.toLocal(52.0025, 22.0025)))
        assertEquals(100.0, map.rateAt(pl.toLocal(52.0005, 22.0035)), "poza oczkami — dawka typowa")
        assertTrue(map.minRate == 85.0 && map.maxRate == 115.0)
    }

    @Test
    fun rejectsBadPackages() {
        val m = MiniJson.parse(json) as Map<*, *>
        assertNull(WarRateMap.parse(m - "pole"))
        assertNull(WarRateMap.parse(m - "strefy"))
        assertNull(WarRateMap.parse(m + ("siatka" to mapOf("dn" to 0.0, "de" to 0.001, "min_n" to 52.0, "min_e" to 22.0, "wiersze" to listOf("1")))))
        assertEquals("mapa_dawek_p1.json", WarRateMap.fileName("p1"))
        assertEquals("mapa_dawek_ab.json", WarRateMap.fileName("a/../b"))
    }
}
