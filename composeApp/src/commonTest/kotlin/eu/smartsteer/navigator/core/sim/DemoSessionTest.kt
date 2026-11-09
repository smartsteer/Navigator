package eu.smartsteer.navigator.core.sim

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class DemoSessionTest {

    @Test
    fun autosteerCatchesTheLine() {
        val d = DemoSession()
        d.engaged = true
        repeat(100) { d.step(0.1) } // 10 s
        assertTrue(abs(d.xteCm) < 3.0, "po 10 s odchyłka powinna być < 3 cm, jest ${d.xteCm}")
    }

    @Test
    fun wholeFieldWithAutosteer() {
        val d = DemoSession()
        d.engaged = true
        var t = 0.0
        var sumSq = 0.0
        var n = 0
        while (!d.finished && t < 4000) {
            d.step(0.1)
            t += 0.1
            if (t > 15 && d.plan.passAt(d.station) >= 0) { sumSq += d.xteCm * d.xteCm; n++ }
        }
        assertTrue(d.finished, "symulacja powinna dojechać do końca pola")
        val rms = sqrt(sumSq / n)
        assertTrue(rms < 3.0, "RMS odchyłki na przejazdach $rms cm")
        assertTrue(d.workedM2 > d.interiorAreaM2 * 0.98, "pokrycie ${d.workedM2} z ${d.interiorAreaM2} m²")
        val p = d.sim.position
        assertTrue(p.y in -0.5..(d.config.fieldLength + 0.5), "ciągnik nie powinien wyjechać za pole")
    }
}
