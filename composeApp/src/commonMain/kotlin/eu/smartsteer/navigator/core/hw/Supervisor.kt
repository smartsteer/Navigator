package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.gps.FixQuality
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Nadzór autosteru (supervisor + watchdog pętli): sprawdza co krok prowadzenia warunki, przy których
 * autoster nie powinien dalej skręcać kołami, i podaje przyczynę wyłączenia. Progi z profilu (0 = wyłączone).
 * Uzupełnia sprawdzanie GPS i modułu skrętu w HardwareSession oraz watchdog w płytce AIO (brak PGN 254 → silnik stop).
 */
class Supervisor {
    data class Input(
        val nowS: Double,
        val engaged: Boolean,
        val inTurn: Boolean,
        val fix: FixQuality,
        val correctionAgeS: Double?,
        val xteM: Double?,
        val setDeg: Double,
        val actualDeg: Double?,
        val speedKmh: Double,
    )

    private var lastCheckS = Double.NaN
    private var rtkBadSinceS = Double.NaN
    private var steerBadSinceS = Double.NaN
    private var wasOnLine = false

    fun reset() {
        lastCheckS = Double.NaN; rtkBadSinceS = Double.NaN; steerBadSinceS = Double.NaN; wasOnLine = false
    }

    /** Zwraca przyczynę wyłączenia autosteru albo null. */
    fun check(i: Input, p: VehicleProfile): String? {
        val gap = if (lastCheckS.isNaN()) 0.0 else i.nowS - lastCheckS
        lastCheckS = i.nowS
        if (!i.engaged) { rtkBadSinceS = Double.NaN; steerBadSinceS = Double.NaN; wasOnLine = false; return null }

        // watchdog pętli: program przez dłuższą chwilę nie liczył prowadzenia
        if (p.supLoopGapS > 0 && gap > p.supLoopGapS) return "Przerwa w pracy programu (${s1(gap)} s) — autoster wyłączony"

        // RTK
        if (p.supRtkLossS > 0) {
            if (i.fix != FixQuality.RTK_FIX) {
                if (rtkBadSinceS.isNaN()) rtkBadSinceS = i.nowS
                if (i.nowS - rtkBadSinceS >= p.supRtkLossS) return "Brak RTK FIX od ${(i.nowS - rtkBadSinceS).roundToInt()} s (${i.fix.label}) — autoster wyłączony"
            } else rtkBadSinceS = Double.NaN
        }
        if (p.supCorrAgeS > 0 && i.correctionAgeS != null && i.correctionAgeS > p.supCorrAgeS) {
            return "Stare poprawki RTK (${i.correctionAgeS.roundToInt()} s) — autoster wyłączony"
        }

        // odjazd od linii (po tym, jak ciągnik już był na linii; nie w trakcie zawrotu)
        if (p.supMaxXteM > 0 && i.xteM != null && !i.inTurn) {
            if (abs(i.xteM) < p.supMaxXteM * 0.5) wasOnLine = true
            if (wasOnLine && abs(i.xteM) > p.supMaxXteM) return "Odjazd od linii o ${s1(abs(i.xteM))} m — autoster wyłączony"
        }
        if (i.inTurn) wasOnLine = false

        // koła nie idą za programem (silnik, zawór, czujnik kąta)
        if (p.supSteerErrDeg > 0 && p.supSteerErrS > 0 && i.actualDeg != null && i.speedKmh >= 1.0) {
            if (abs(i.setDeg - i.actualDeg) > p.supSteerErrDeg) {
                if (steerBadSinceS.isNaN()) steerBadSinceS = i.nowS
                if (i.nowS - steerBadSinceS >= p.supSteerErrS) {
                    return "Koła nie skręcają jak trzeba (zadane ${s1(i.setDeg)}°, jest ${s1(i.actualDeg)}°) — sprawdź silnik/zawór i czujnik kąta"
                }
            } else steerBadSinceS = Double.NaN
        } else steerBadSinceS = Double.NaN
        return null
    }

    private fun s1(v: Double): String {
        val r = (v * 10).roundToInt()
        val a = abs(r)
        return (if (r < 0) "-" else "") + "${a / 10},${a % 10}"
    }
}
