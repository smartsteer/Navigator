package eu.smartsteer.navigator.core.war

import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarTreatmentTest {
    @Test
    fun treatmentJson() {
        val t = WarTreatment("oprysk", "Mospilan 20 SP", "R-12/2020", 0.2, "kg/ha")
        val m = MiniJson.parse(t.toJson()) as Map<*, *>
        assertEquals("oprysk", m["rodzaj"]); assertEquals("Mospilan 20 SP", m["produkt"]); assertEquals("R-12/2020", m["nr"])
        assertEquals(0.2, m["dawka"]); assertEquals("kg/ha", m["jedn"])
        assertNull(m["uprawa"]); assertNull(m["zmienna_dawka"])
        val s = MiniJson.parse(WarTreatment("siew", "Adrenalina", dose = 180.0, unit = "kg/ha", crop = "pszenica ozima", variableRate = true).toJson()) as Map<*, *>
        assertEquals("pszenica ozima", s["uprawa"]); assertEquals(true, s["zmienna_dawka"])
        assertTrue(WarTreatment.units("nawoz").first() == "kg/ha" && WarTreatment.units("uprawa").isEmpty())
    }

    @Test
    fun productsFromWar() {
        val (typ, m) = WarProtocol.parse(
            """{"typ":"produkty_war","v":1,"srodki":[{"n":"Mospilan 20 SP","nr":"R-12/2020","d":0.2,"j":"kg/ha","r":"sor"},{"n":"Bormax","r":"dolistny"}],
               "nawozy":[{"n":"Saletra amonowa 34","d":150,"j":"kg/ha","r":"nawoz"},{"n":""}],"odmiany":[{"n":"Adrenalina","u":"pszenica ozima"}]}""",
        )!!
        assertEquals("produkty_war", typ)
        val p = WarProducts.parse(m)
        assertEquals(listOf("Mospilan 20 SP", "Bormax"), p.forKind("oprysk").map { it.name })
        assertEquals("Bormax", p.forKind("dolistny").first().name)
        assertEquals(1, p.forKind("nawoz").size, "pusta nazwa pominięta")
        assertEquals(150.0, p.fertilizers[0].dose)
        assertEquals("pszenica ozima", p.forKind("siew")[0].crop)
        assertTrue(p.forKind("uprawa").isEmpty())
    }
}
