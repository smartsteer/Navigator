package eu.smartsteer.navigator.core.sprayer

import eu.smartsteer.navigator.core.aog.AogFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SprayerProtocolTest {

    @Test
    fun commandRoundTrip() {
        val c = SprayerCommand(
            counter = 300, mainOpen = true, sections = 0b1_0110_0101, sectionCount = 9,
            valveType = ValveType.ARAG_2PIN, workLights = true, twoPinDriveMs = 2500, regPulseMs = -180,
        )
        val frame = c.toFrame()
        assertEquals(8 + 6, frame.size)
        val back = assertNotNull(SprayerCommand.from(assertNotNull(AogFrame.parse(frame))))
        assertEquals(c.copy(counter = 300 and 0xFF), back)
        assertTrue(back.isSectionOpen(8))
        assertFalse(back.isSectionOpen(1))
    }

    @Test
    fun commandMasksSectionsAboveCount() {
        val c = SprayerCommand(counter = 1, mainOpen = true, sections = 0x1FF, sectionCount = 5)
        val back = assertNotNull(SprayerCommand.from(assertNotNull(AogFrame.parse(c.toFrame()))))
        assertEquals(0x1F, back.sections)
        assertEquals(ValveType.ARAG_3PIN, back.valveType)
    }

    @Test
    fun statusRoundTripAndFlow() {
        val s = SprayerStatus(
            counter = 7, linkOk = true, mainOpen = true, workLights = false, failsafe = false,
            pulseCount = 0xFFFF_FFF0L, pulsePeriodUs = 11_764, pressureBar = 3.25,
            sectionFaults = 0b100, faults = SprayerStatus.FAULT_REG, supplyVolts = 13.8,
        )
        val back = assertNotNull(SprayerStatus.from(assertNotNull(AogFrame.parse(s.toFrame()))))
        assertEquals(s, back)
        // 255 imp./l, impuls co 11,764 ms → ok. 20 l/min
        assertTrue(abs(back.flowLpm(255.0) - 20.0) < 0.01)
        assertEquals(0x20L, pulseDelta(0xFFFF_FFF0L, 0x10L))
    }

    @Test
    fun noPressureSensor() {
        val s = SprayerStatus(0, true, false, false, false, 0, 0, null)
        val back = assertNotNull(SprayerStatus.from(assertNotNull(AogFrame.parse(s.toFrame()))))
        assertNull(back.pressureBar)
        assertEquals(0.0, back.flowLpm(255.0))
    }

    @Test
    fun simulatorFlowAndPulses() {
        val sim = SprayerSim()
        var counter = 0
        var prev = 0L
        repeat(30) { // 3 s, 9 sekcji otwartych
            sim.receive(SprayerCommand(counter++, mainOpen = true, sections = 0x1FF, sectionCount = 9))
            repeat(10) { sim.step(10) }
            if (it == 19) prev = sim.status().pulseCount
        }
        val st = sim.status()
        // 0,4 × 8 bar = 3,2 bar; 54 dysze × 1,18 × √(3,2/3) ≈ 65,8 l/min
        assertTrue(abs(st.pressureBar!! - 3.2) < 0.05, "ciśnienie ${st.pressureBar}")
        assertTrue(abs(st.flowLpm(255.0) - 65.8) < 1.0, "przepływ ${st.flowLpm(255.0)}")
        // impulsy przez ostatnią 1 s ≈ 65,8 / 60 × 255
        val perSecond = pulseDelta(prev, st.pulseCount)
        assertTrue(abs(perSecond - 280) < 10, "impulsy $perSecond")
    }

    @Test
    fun simulatorPulseOncePerCounterAndFailsafe() {
        val sim = SprayerSim(valveTravelMs = 4000)
        val start = sim.valvePosition
        val cmd = SprayerCommand(1, mainOpen = true, sections = 1, sectionCount = 1, regPulseMs = 400)
        repeat(5) { sim.receive(cmd); sim.step(100) } // ta sama ramka 5× → jeden impuls
        assertTrue(abs(sim.valvePosition - (start + 0.1)) < 1e-9)

        repeat(6) { sim.step(100) } // 0,6 s bez poleceń
        assertTrue(sim.failsafe)
        val st = sim.status()
        assertTrue(st.failsafe)
        assertFalse(st.mainOpen)
        repeat(20) { sim.step(100) }
        assertTrue(sim.flowLpm == 0.0 && sim.pressureBar < 0.01)
    }
}
