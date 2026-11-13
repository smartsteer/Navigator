package eu.smartsteer.navigator.core.war

import eu.smartsteer.navigator.core.field.Coverage
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WarProtocolTest {
    @Test
    fun codeNormalisation() {
        val c = WarProtocol.normalizeCode("abcd-efgh jklm-npqr")
        assertEquals("ABCDEFGHJKLMNPQR", c)
        assertTrue(WarProtocol.isValidCode(c))
        assertFalse(WarProtocol.isValidCode("ABCDEFGHJKLMNPQ1")) // „1” nie ma w alfabecie
        assertFalse(WarProtocol.isValidCode("ABCD"))
        assertEquals("ABCD-EFGH-JKLM-NPQR", WarProtocol.pretty(c))
        assertEquals("war-sync-id:ABCDEFGHJKLMNPQR", WarProtocol.worksMailboxText(c))
        assertEquals("war-nav-zl:ABCDEFGHJKLMNPQR", WarProtocol.navMailboxText(c))
        val id = WarProtocol.machineId(ByteArray(8) { it.toByte() })
        assertTrue(Regex("^[A-Za-z0-9_-]{4,64}$").matches(id), id)
    }

    @Test
    fun fieldsInParts() {
        val a = FieldPartsAssembler()
        fun pkt(part: Int, id: String) = MiniJson.parse(
            """{"typ":"pola_war","v":1,"skrot":"abc","czesc":$part,"czesci":2,"t":"x","pola":[{"id":"$id","nazwa":"Pole $id","ha":6.0,
            "uprawa":"Pszenica","granica":[[19.468,52.176],[19.471,52.176],[19.471,52.1787],[19.468,52.1787],[19.468,52.176]],
            "dziury":[],"czesci":1,"srodek":{"lat":52.1773,"lon":19.4695}}]}""",
        ) as Map<*, *>
        assertNull(a.add(pkt(2, "p2")))
        val all = assertNotNull(a.add(pkt(1, "p1")))
        assertEquals(listOf("p1", "p2"), all.map { it.id })
        assertEquals(4, all[0].ring.size)
        assertEquals(19.468 to 52.176, all[0].ring[0])
    }

    @Test
    fun orderParsing() {
        val m = MiniJson.parse(
            """{"typ":"zlecenie","v":1,"id":"zl1abc","status":"nowe","rodzaj":"oprysk","rodzaj_nazwa":"Oprysk",
            "produkt":"Axial 50 EC","dawka":0.9,"jedn":"l/ha","ciecz":200,"termin":"2026-11-12","dla":"Marek","od":"Michał",
            "uwagi":"od strony lasu","pole":{"id":"p1","nazwa":"Za stodołą","ha":6.0,"uprawa":"","granica":[[19.468,52.176],[19.471,52.176],[19.471,52.1787]],"dziury":[],"czesci":1}}""",
        ) as Map<*, *>
        val o = assertNotNull(WarProtocol.parseOrder(m))
        assertEquals("Oprysk — Za stodołą", o.title)
        assertEquals("Axial 50 EC · 0,9 l/ha · ciecz 200 l/ha · termin 2026-11-12 · dla: Marek", o.details)
        assertEquals("p1", o.warField!!.id)
    }

    @Test
    fun outgoingPacketsAreJson() {
        val c = WarConfig(code = "ABCDEFGHJKLMNPQR", machineId = "tab-abc", tractorName = "Renault \"133\"", operator = "Marek")
        val d = MiniJson.parse(WarProtocol.device(c, "0.1.30", "2026-11-10T08:00:00+01:00")) as Map<*, *>
        assertEquals("urzadzenie", d["typ"]); assertEquals("Renault \"133\"", d["nazwa"]); assertEquals("navigator", d["program"])
        val st = MiniJson.parse(WarProtocol.orderStatus("zl1", "przyjete", c, "t")) as Map<*, *>
        assertEquals("zl1", st["zlecenie_id"]); assertEquals("tab-abc", st["komputer"])
        val pos = MiniJson.parse(WarProtocol.position(52.17731244, 19.4695011, 87.54, 8.44, 4, 18, "Siewnik 3 m", 3.0, "Za stodołą", true, c, "t")) as Map<*, *>
        assertEquals(52.1773124, pos["lat"]); assertEquals(87.5, pos["kurs"]); assertEquals(true, pos["pracuje"])
        val c2 = WarConfig(code = c.code, machineId = c.machineId)
        assertNull((MiniJson.parse(WarProtocol.orderStatus("z", "odrzucone", c2, "t")) as Map<*, *>)["nonexistent"])
    }

    @Test
    fun workPacketWithCoverageGrid() {
        val plane = LocalPlane(52.1773, 19.4695)
        val cov = Coverage()
        val s = cov.newStrip()
        cov.extend(s, Vec2(0.0, 0.0), Vec2(6.0, 0.0))
        cov.extend(s, Vec2(0.0, 20.0), Vec2(6.0, 20.0))
        val g = assertNotNull(CoverageGrid.from(cov, plane))
        assertTrue(g.w in 3..4 && g.h in 10..11, "${g.w}×${g.h}")
        var set = 0
        for (y in 0 until g.h) for (x in 0 until g.w) if (g.isSet(x, y)) set++
        assertEquals(30, set)
        val w = WarProtocol.Work(
            "nav-tab-abc-1", "Za stodołą", "p1", "Siewnik 3 m", 3.0, "Renault", "s", "e", 0.012, 0.012, 6.0,
            52.1773, 19.4695, listOf(52.176 to 19.468, 52.176 to 19.471, 52.1787 to 19.471), "zl1", "0.1.30", g,
        )
        val m = MiniJson.parse(WarProtocol.work(w, WarConfig(machineId = "tab-abc"))) as Map<*, *>
        assertEquals("nav-tab-abc-1", m["id"])
        assertEquals("p1", (m["pole"] as Map<*, *>)["war_id"])
        assertEquals("zl1", m["zlecenie"])
        val gr = m["granica"] as Map<*, *>
        assertEquals(4, ((gr["coordinates"] as List<*>)[0] as List<*>).size)
        assertEquals(19.468, (((gr["coordinates"] as List<*>)[0] as List<*>)[0] as List<*>)[0])
        val pk = m["pokrycie"] as Map<*, *>
        assertEquals(g.w.toDouble(), pk["w"]); assertEquals(2.0, pk["k"])
        assertNull(m["kto"])
        assertEquals(cov.passesM2, 120.0, 1e-6)
    }

    /** Kolor śladu w paczce floty (§3.10 „kolor”): przechodzi w obie strony, zły zapis odrzucony. */
    @Test
    fun fleetColor() {
        val c = WarConfig(machineId = "tab-abc")
        val js = WarProtocol.fleetPosition(52.1, 19.4, 90.0, 8.0, 4, "Opryskiwacz", 21.0, "p1", "Za stodołą", true, "Renault", c, "t", "#FF8C1A")
        val m = MiniJson.parse(js) as Map<*, *>
        assertEquals("#FF8C1A", WarProtocol.parseFleetPosition(m, "tab-abc", 1L)!!.color)
        assertEquals("", WarProtocol.fleetColor(mapOf("kolor" to "czerwony")))
        assertNull((MiniJson.parse(WarProtocol.fleetPosition(52.1, 19.4, 0.0, 0.0, 1, "", 0.0, null, "", false, "R", c, "t")) as Map<*, *>)["kolor"])
        val cov = MiniJson.parse(WarProtocol.fleetCoverage(c, "p1", "R", eu.smartsteer.navigator.core.war.CoverageGrid(52.0, 19.0, 0.0, 0.0, 2.0, 1, 1, ByteArray(1)), "t", "#2F9BFF")) as Map<*, *>
        assertEquals("#2F9BFF", WarProtocol.fleetColor(cov))
    }

    /** Zlecenie przyjęte na innej maszynie (`przyjal`) i własna skrzynka Navigatora. */
    @Test
    fun orderTakenElsewhereAndOwnMailbox() {
        val o = WarProtocol.parseOrder(MiniJson.parse("""{"id":"zl1","status":"nowe","rodzaj":"oprysk","przyjal":{"komputer":"tab-inny","kto":"Janek"}}""") as Map<*, *>)!!
        assertEquals("tab-inny", o.takenByMachine); assertEquals("Janek", o.takenBy)
        val back = WarProtocol.parseOrderWithLocal(MiniJson.parse(WarProtocol.orderJson(o)) as Map<*, *>)!!
        assertEquals("tab-inny", back.takenByMachine)
        assertEquals("", WarProtocol.parseOrder(mapOf("id" to "zl2"))!!.takenByMachine)
        assertEquals("war-nav-zl:ABCDEFGHJKLMNPQR:tab-abc", WarProtocol.ownNavMailboxText("ABCDEFGHJKLMNPQR", "tab-abc"))
        val dev = MiniJson.parse(WarProtocol.device(WarConfig(machineId = "tab-abc"), "0.1.1", "t")) as Map<*, *>
        assertEquals(true, dev["skrzynka_wlasna"])
    }

    @Test
    fun trackersFromFleetResponse() {
        val l = WarProtocol.parseTrackers(listOf(
            mapOf("id" to "trk-abc", "nazwa" to "Ursus", "lat" to 52.1, "lon" to 19.4, "kmh" to 12.0, "kurs" to 90.0, "zaplon" to true),
            mapOf("id" to "zly", "lat" to 1.0, "lon" to 1.0),
        ), 7L)
        assertEquals(1, l.size)
        assertTrue(l[0].tracker && l[0].working); assertEquals("Ursus", l[0].tractor); assertEquals(7L, l[0].receivedMs)
    }
}
