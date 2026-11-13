package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.Pgn
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.gps.Nmea
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareSessionTest {

    private val plane = LocalPlane(52.0, 21.0)

    private fun cs(body: String): String {
        var x = 0
        for (c in body) x = x xor c.code
        return "$" + body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }

    private fun dm(v: Double, degDigits: Int): String {
        val a = abs(v)
        val d = floor(a).toInt()
        val m = (a - d) * 60
        val mm = ((m * 1e7).toLong()).toString().padStart(9, '0')
        return d.toString().padStart(degDigits, '0') + mm.dropLast(7) + "." + mm.takeLast(7)
    }

    private fun gga(p: Vec2, fix: Int = 4): String {
        val (lat, lon) = plane.toGeo(p)
        return cs("GNGGA,120000.00,${dm(lat, 2)},N,${dm(lon, 3)},E,$fix,12,0.8,100.0,M,40.0,M,1.0,0000")
    }

    private fun vtg(courseDeg: Double, kmh: Double) =
        cs("GNVTG,${courseDeg},T,,M,${kmh / 1.852},N,$kmh,K,A")

    private fun steerReply(): ByteArray =
        AogFrame.build(Pgn.FROM_AUTOSTEER, byteArrayOf(0, 0, 0x0F, 0x27, 0xB8.toByte(), 0x22, 0, 0), source = 0x7E)

    /** Jazda na północ od (x, 0) do (x, toY), krok 1 m co 0,5 s. Zwraca czas końcowy. */
    private fun driveNorth(h: HardwareSession, x: Double, fromY: Double, toY: Double, t0: Double): Double {
        var t = t0
        var y = fromY
        while (y <= toY) {
            h.onNmea(vtg(0.0, 7.2), t)
            h.onNmea(gga(Vec2(x, y)), t)
            y += 1.0
            t += 0.5
        }
        return t
    }

    @Test
    fun abLineFromButtonsAndParallelPass() {
        val h = HardwareSession(VehicleProfile(toolWidth = 3.0, antennaPivot = 0.0))
        var t = driveNorth(h, 0.0, 0.0, 2.0, 0.0)
        assertEquals(HeadingSource.VTG, h.headingSource)
        assertTrue(h.markA())
        assertFalse(h.markB(), "za blisko A")
        t = driveNorth(h, 0.0, 3.0, 30.0, t)
        assertTrue(h.markB())
        val l = assertNotNull(h.line)
        assertTrue(abs(l.heading) < 0.01, "kurs AB ≈ północ: ${l.heading}")

        // zjeżdżamy na sąsiedni przejazd: 3,2 m w prawo → przejazd 1, odchyłka +20 cm
        t = driveNorth(h, 3.2, 31.0, 40.0, t)
        h.update(t)
        assertEquals(1, h.passIndex)
        assertTrue(abs(h.xteCm - 20.0) < 2.0, "xte ${h.xteCm}")
        assertTrue(h.steerSetDeg < 0, "skręt w lewo do linii")
    }

    @Test
    fun drivingBackReversesLine() {
        val h = HardwareSession()
        h.onNmea(gga(Vec2(0.0, 0.0)), 0.0) // pierwsza pozycja wyznacza początek pola
        h.restoreLine(AbLine(Vec2(0.0, 0.0), Vec2(0.0, 50.0)))
        var t = 0.5
        var y = 60.0
        while (y >= 40.0) {
            h.onNmea(vtg(180.0, 7.2), t)
            h.onNmea(gga(Vec2(-0.3, y)), t)
            y -= 1.0; t += 0.5
        }
        h.update(t)
        assertTrue(h.reversed)
        assertEquals(0, h.passIndex)
        // jedziemy na południe 30 cm na zachód od linii = 30 cm na prawo
        assertTrue(abs(h.xteCm - 30.0) < 2.0, "xte ${h.xteCm}")
    }

    @Test
    fun engageNeedsGpsAndModuleAndDropsOnGpsLoss() {
        val h = HardwareSession()
        assertNotNull(h.setEngaged(true, 0.0)) // brak linii
        h.restoreLine(AbLine(Vec2(0.0, 0.0), Vec2(0.0, 50.0)))
        val t = driveNorth(h, 0.0, 0.0, 5.0, 0.0)
        assertNotNull(h.setEngaged(true, t)) // brak PGN 253
        val reply = steerReply()
        h.onDatagram(reply, reply.size, t)
        assertNotNull(h.autosteer)
        assertNull(h.setEngaged(true, t))
        assertTrue(h.engaged)
        val frame = AogFrame.parse(h.steerFrame())!!
        assertEquals(Pgn.STEER_DATA, frame.pgn)
        assertEquals(1, frame.data[2].toInt())
        h.update(t + 0.5)
        assertTrue(h.engaged)
        h.update(t + 3.0) // GPS milczy > 1 s
        assertFalse(h.engaged)
    }

    @Test
    fun phoneGpsLongerTimeout() {
        val h = HardwareSession()
        val t = driveNorth(h, 0.0, 0.0, 5.0, 0.0)
        assertFalse(h.gpsAlive(t + 1.8), "płytka/RTK: po 1 s bez pozycji — brak GPS")
        h.gpsTimeoutS = 3.0                                        // GPS telefonu, 1 Hz z opóźnieniami
        assertTrue(h.gpsAlive(t + 1.8))
        assertFalse(h.gpsAlive(t + 3.5))
    }

    @Test
    fun phoneGpsBlocksAutosteer() {
        val h = HardwareSession()
        h.restoreLine(AbLine(Vec2(0.0, 0.0), Vec2(0.0, 50.0)))
        val t = driveNorth(h, 0.0, 0.0, 5.0, 0.0)
        val reply = steerReply()
        h.onDatagram(reply, reply.size, t)
        h.steerBlockedReason = "GPS z telefonu"
        assertEquals("GPS z telefonu", h.setEngaged(true, t))      // wszystko inne gotowe, a jednak nie
        assertFalse(h.engaged)
        h.steerBlockedReason = null
        assertNull(h.setEngaged(true, t))
        h.steerBlockedReason = "GPS z telefonu"                    // zmiana źródła w trakcie — wyłącza
        h.update(t + 0.1)
        assertFalse(h.engaged)
    }

    @Test
    fun nmeaDatagramWithSeveralSentences() {
        val h = HardwareSession()
        val text = vtg(90.0, 5.0) + "\r\n" + gga(Vec2(10.0, 20.0)) + "\r\n"
        val b = text.encodeToByteArray()
        h.onDatagram(b, b.size, 1.0)
        val p = assertNotNull(h.antenna)
        assertTrue(p.distanceTo(Vec2(0.0, 0.0)) < 0.01, "pierwsza pozycja = początek pola")
        assertEquals(5.0, h.speedKmh, 1e-9)
        assertTrue(abs(h.headingRad - PI / 2) < 1e-9)
    }

    @Test
    fun pandaAndHdt() {
        val (lat, lon) = plane.toGeo(Vec2(0.0, 0.0))
        val paogi = cs("PAOGI,120000.00,${dm(lat, 2)},N,${dm(lon, 3)},E,4,14,0.7,101.2,0.8,3.24,271.5,1.5,0.2,0.0")
        val p = assertNotNull(Nmea.parsePanda(paogi))
        assertTrue(p.dualAntenna)
        assertEquals(271.5, p.headingDeg)
        assertEquals(3.24 * 1.852, p.speedKmh, 1e-9)
        assertEquals(1.5, p.rollDeg)
        assertEquals(88.25, assertNotNull(Nmea.parseHdt(cs("GNHDT,88.25,T"))).headingDeg)

        val h = HardwareSession()
        h.onNmea(paogi, 0.0)
        assertEquals(HeadingSource.DUAL, h.headingSource)
        assertTrue(abs(h.headingRad - 271.5 * PI / 180) < 1e-9)
    }

    @Test
    fun recordedCurveAndNeighbourPass() {
        val h = HardwareSession(VehicleProfile(toolWidth = 3.0, antennaPivot = 0.0))
        var t = 0.0
        // pierwsza pozycja wyznacza początek pola; jedziemy łukiem R = 60 m w prawo
        fun at(p: Vec2, courseDeg: Double) { h.onNmea(vtg(courseDeg, 7.2), t); h.onNmea(gga(p), t); t += 0.25 }
        val r = 60.0
        fun arcPt(s: Double, off: Double): Pair<Vec2, Double> {
            val a = s / r
            val rr = r - off // przesunięcie w prawo = do środka
            return Vec2(r - rr * kotlin.math.cos(a), rr * kotlin.math.sin(a)) to a * 180 / PI
        }
        at(Vec2(0.0, 0.0), 0.0)
        assertTrue(h.startCurve())
        var s = 0.0
        while (s <= 50.0) { val (p, c) = arcPt(s, 0.0); at(p, c); s += 0.5 }
        assertTrue(h.finishCurve(), h.message)
        h.setMode(GuidanceMode.CURVE)
        // drugi przejazd: 3,1 m w prawo od krzywej, ta sama strona jazdy
        s = 10.0
        while (s <= 30.0) { val (p, c) = arcPt(s, 3.1); at(p, c); s += 0.5 }
        h.update(t)
        assertEquals(1, h.curvePassIndex)
        assertTrue(abs(h.xteCm - 10.0) < 3.0, "xte ${h.xteCm}")
    }

    @Test
    fun autoUturnAtFieldEnd() {
        val h = HardwareSession(VehicleProfile(toolWidth = 6.0, antennaPivot = 0.0, supSteerErrDeg = 0.0)) // moduł w teście zawsze odpowiada kątem 0°
        var t = 0.0
        val reply = steerReply()
        fun at(p: Vec2, course: Double) {
            h.onDatagram(reply, reply.size, t)
            h.onNmea(vtg(course, 7.2), t)
            h.onNmea(gga(p), t)
            h.update(t)
            t += 0.25
        }
        at(Vec2(0.0, 0.0), 0.0)
        h.newField("Test")
        // pole 200 × 200 m wokół początku, linia AB na północ
        h.restoreFieldForTest(listOf(Vec2(-100.0, -20.0), Vec2(100.0, -20.0), Vec2(100.0, 180.0), Vec2(-100.0, 180.0)))
        h.restoreLine(AbLine(Vec2(0.0, 0.0), Vec2(0.0, 50.0)))
        at(Vec2(0.0, 1.0), 0.0)
        assertNull(h.setEngaged(true, t))
        h.autoUturn = true
        var y = 2.0
        while (h.youTurnPath == null && y < 175.0) { at(Vec2(0.0, y), 0.0); y += 1.0 }
        val turn = assertNotNull(h.youTurnPath, "zawrót powinien się zacząć")
        assertTrue(y < 170.0)
        // jedziemy po ścieżce zawrotu
        for (p in turn) {
            at(p.p, p.heading * 180 / PI)
            if (h.youTurnPath == null) break
        }
        assertNull(h.youTurnPath)
        assertTrue(h.engaged)
        assertTrue(!h.uTurnRight, "następny zawrót w lewo")
        h.update(t)
        assertEquals(1, h.passIndex)
        assertTrue(h.reversed)
    }

    @Test
    fun tapOnTractorFlipsFrontAndBack() {
        val h = HardwareSession(VehicleProfile(antennaPivot = 0.0))
        h.onNmea(vtg(10.0, 7.2), 0.0)
        h.onNmea(gga(Vec2(0.0, 0.0)), 0.0)
        assertTrue(abs(h.headingRad - 10 * PI / 180) < 1e-9)
        h.flipHeading()
        assertTrue(abs(h.headingRad - 190 * PI / 180) < 1e-9)
        h.onNmea(vtg(12.0, 7.2), 0.5)
        h.onNmea(gga(Vec2(0.0, 1.0)), 0.5)
        assertTrue(abs(h.headingRad - 192 * PI / 180) < 1e-9, "odwrócenie zostaje przy nowych pozycjach")
        h.flipHeading()
        assertTrue(abs(h.headingRad - 12 * PI / 180) < 1e-9)
    }

    @Test
    fun simulatedDrivingFollowsAbLine() {
        val sim = eu.smartsteer.navigator.core.sim.SimReceiver()
        val h = HardwareSession(VehicleProfile(antennaPivot = 0.0))
        var t = 0.0
        fun feed() {
            h.onNmea(sim.vtg(), t); h.onNmea(sim.gga(), t)
            val f = sim.pgn253(); h.onDatagram(f, f.size, t)
            h.update(t)
        }
        feed()
        h.restoreLine(AbLine(Vec2(1.0, 0.0), Vec2(1.0, 50.0))) // linia 1 m w prawo
        repeat(20) { sim.step(0.1, null); t += 0.1; feed() }
        assertNull(h.setEngaged(true, t))
        repeat(400) { sim.step(0.1, if (h.engaged) h.steerSetDeg else null); t += 0.1; feed() }
        assertTrue(h.engaged)
        assertTrue(abs(h.xteCm) < 5.0, "odchyłka ${h.xteCm} cm")
    }

    @Test
    fun boardSwitchEngagesAndDisengagesLikeAgOpenGps() {
        val sim = eu.smartsteer.navigator.core.sim.SimReceiver()
        val h = HardwareSession(VehicleProfile(antennaPivot = 0.0))
        var t = 0.0
        var sw = 0x02 // bit 1 = przełącznik rozwarty (wyłączony)
        fun feed() {
            h.onNmea(sim.vtg(), t); h.onNmea(sim.gga(), t)
            val f = AogFrame.build(Pgn.FROM_AUTOSTEER, byteArrayOf(0, 0, 0x0F, 0x27, 0xB8.toByte(), 0x22, sw.toByte(), 0), source = 0x7E)
            h.onDatagram(f, f.size, t)
            h.update(t)
        }
        feed()
        h.restoreLine(AbLine(Vec2(1.0, 0.0), Vec2(1.0, 50.0)))
        repeat(20) { sim.step(0.1, null); t += 0.1; feed() }
        // przełącznik wyłączony: z ekranu nie da się włączyć
        assertNotNull(h.setEngaged(true, t))
        assertFalse(h.engaged)
        // przełącznik włączony → autoster włącza się sam
        sw = 0; sim.step(0.1, null); t += 0.1; feed()
        assertTrue(h.engaged)
        // przełącznik wyłączony → autoster wyłączony
        sw = 0x02; sim.step(0.1, h.steerSetDeg); t += 0.1; feed()
        assertFalse(h.engaged)
    }
}
