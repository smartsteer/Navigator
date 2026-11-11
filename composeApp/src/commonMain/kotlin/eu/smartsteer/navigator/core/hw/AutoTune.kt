package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.settings.SteerAlgorithm
import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Samouczenie prowadzenia (jak adaptacja w sterowniku LPG): podczas jazdy na autosterze po prostej lub krzywej
 * zbiera 30 s przebiegu odchyłki i kąta kół, ocenia jazdę i proponuje małą zmianę jednego–dwóch parametrów:
 *  - kołysanie wokół linii (częste przejścia przez zero przy dużej odchyłce) → dłuższe wyprzedzenie (łagodniej),
 *  - powolne dochodzenie do linii, długie odjazdy w jedną stronę → krótsze wyprzedzenie (ostrzej),
 *  - stała odchyłka w jedną stronę (zbocze, krzywo zawieszona maszyna) → człon całkujący,
 *  - koła nie nadążają za zadanym kątem → większe wzmocnienie P; koła przestrzeliwują i drgają → mniejsze,
 *  - małe korekty nie ruszają kół (stały błąd kąta przy małym zadanym) → wyższy minimalny PWM.
 * Zmiany są małe, w bezpiecznych granicach i nie częściej niż raz na okno; każdą można cofnąć.
 * Ocenia się tylko „spokojną” jazdę: prędkość stała, bez zawrotu, ciągnik już na linii.
 */
class AutoTune {
    enum class Mode(val label: String) { OFF("WYŁ."), SUGGEST("PODPOWIEDZI"), AUTO("AUTOMAT") }

    data class Stats(val rmsCm: Double, val maxCm: Double, val meanCm: Double, val crossingsPerMin: Double, val wheelLagS: Double, val wheelGain: Double)
    data class Proposal(val profile: VehicleProfile, val reasons: List<String>, val stats: Stats)

    private class S(val t: Double, val xte: Double, val set: Double, val act: Double, val speed: Double)
    private val buf = ArrayList<S>()
    private var lastProposalS = Double.NEGATIVE_INFINITY
    private var motorWindows = 0

    /** Ostatnia ocena jazdy (do pokazania rolnikowi). */
    var lastStats: Stats? = null; private set

    fun reset() { buf.clear() }

    /**
     * Próbka z kroku prowadzenia. Zwraca propozycję zmiany, gdy zebrało się pełne okno.
     * [steady] = autoster prowadzi po linii (nie zawrót, nie dojazd).
     */
    fun sample(nowS: Double, steady: Boolean, xteCm: Double, setDeg: Double, actualDeg: Double?, speedKmh: Double, p: VehicleProfile): Proposal? {
        if (!steady || actualDeg == null || speedKmh < 3.0 || abs(xteCm) > 50.0) { buf.clear(); return null }
        if (buf.isNotEmpty()) {
            val first = buf.first()
            // zmiana prędkości o więcej niż 1,5 km/h albo przerwa w danych — zaczynamy okno od nowa
            if (abs(speedKmh - first.speed) > 1.5 || nowS - buf.last().t > 0.5) buf.clear()
        }
        buf.add(S(nowS, xteCm, setDeg, actualDeg, speedKmh))
        if (buf.last().t - buf.first().t < WINDOW_S) return null
        val win = buf.toList()
        buf.clear()
        val st = stats(win)
        lastStats = st
        if (nowS - lastProposalS < WINDOW_S) return null
        val prop = decide(st, p, win) ?: return null
        lastProposalS = nowS
        return prop
    }

    private fun stats(w: List<S>): Stats {
        val n = w.size
        val mean = w.sumOf { it.xte } / n
        val rms = sqrt(w.sumOf { it.xte * it.xte } / n)
        val max = w.maxOf { abs(it.xte) }
        // przejścia przez linię (z histerezą 1 cm)
        var cross = 0
        var side = 0
        for (s in w) {
            val now = if (s.xte > 1.0) 1 else if (s.xte < -1.0) -1 else side
            if (side != 0 && now != side) cross++
            side = now
        }
        val minutes = (w.last().t - w.first().t) / 60.0
        // opóźnienie kół: przesunięcie (0–1,5 s) z największą korelacją zadany→rzeczywisty
        val dt = (w.last().t - w.first().t) / (n - 1).coerceAtLeast(1)
        var bestLag = 0.0; var bestC = -1.0
        val ms = w.sumOf { it.set } / n
        val ma = w.sumOf { it.act } / n
        var k = 0
        while (k * dt <= 1.5 && k < n / 2) {
            var c = 0.0; var vs = 0.0; var va = 0.0
            for (i in 0 until n - k) {
                val a = w[i].set - ms; val b = w[i + k].act - ma
                c += a * b; vs += a * a; va += b * b
            }
            val r = if (vs > 1e-9 && va > 1e-9) c / sqrt(vs * va) else 0.0
            if (r > bestC) { bestC = r; bestLag = k * dt }
            k++
        }
        val sdSet = sqrt(w.sumOf { (it.set - ms) * (it.set - ms) } / n)
        val sdAct = sqrt(w.sumOf { (it.act - ma) * (it.act - ma) } / n)
        val gain = if (sdSet > 0.05) sdAct / sdSet else 1.0
        return Stats(rms, max, mean, cross / minutes.coerceAtLeast(0.1), bestLag, gain)
    }

    private fun decide(st: Stats, p: VehicleProfile, w: List<S>): Proposal? {
        var q = p
        val why = ArrayList<String>()
        val pure = p.algorithm == SteerAlgorithm.PURE_PURSUIT
        // --- prowadzenie ---
        if (st.rmsCm > 2.5 && st.crossingsPerMin > 6) {
            if (pure && p.lookAheadHold < 8.0) {
                q = q.copy(lookAheadHold = r1(p.lookAheadHold + 0.2).coerceAtMost(8.0))
                why += "kołysze się wokół linii (${st.crossingsPerMin.roundToInt()} przejść/min) → wyprzedzenie ${q.lookAheadHold} m"
            } else if (!pure && p.stanleyDistanceGain > 0.3) {
                q = q.copy(stanleyDistanceGain = r1(p.stanleyDistanceGain - 0.1))
                why += "kołysze się wokół linii → wzmocnienie odchyłki ${q.stanleyDistanceGain}"
            }
        } else if (st.rmsCm > 3.0 && st.crossingsPerMin < 2 && abs(st.meanCm) < st.rmsCm * 0.7) {
            if (pure && p.lookAheadHold > 1.5) {
                q = q.copy(lookAheadHold = r1(p.lookAheadHold - 0.2).coerceAtLeast(1.5))
                why += "wolno wraca na linię → wyprzedzenie ${q.lookAheadHold} m"
            } else if (!pure && p.stanleyDistanceGain < 3.0) {
                q = q.copy(stanleyDistanceGain = r1(p.stanleyDistanceGain + 0.1))
                why += "wolno wraca na linię → wzmocnienie odchyłki ${q.stanleyDistanceGain}"
            }
        }
        if (abs(st.meanCm) > 2.0 && abs(st.meanCm) > st.rmsCm * 0.7 && st.crossingsPerMin < 4) {
            if (pure && p.purePursuitIntegral < 0.5) {
                q = q.copy(purePursuitIntegral = r2(p.purePursuitIntegral + 0.05).coerceAtMost(0.5))
                why += "stała odchyłka ${st.meanCm.roundToInt()} cm w ${if (st.meanCm > 0) "prawo" else "lewo"} → człon całkujący ${q.purePursuitIntegral}"
            } else if (!pure && p.stanleyIntegral < 0.5) {
                q = q.copy(stanleyIntegral = r2(p.stanleyIntegral + 0.05).coerceAtMost(0.5))
                why += "stała odchyłka → człon całkujący ${q.stanleyIntegral}"
            }
        }
        // --- silnik / zawór (rzadziej: co drugie okno) ---
        motorWindows++
        if (motorWindows >= 2) {
            motorWindows = 0
            if ((st.wheelLagS > 0.5 || st.wheelGain < 0.7) && p.gainP < 200) {
                q = q.copy(gainP = (p.gainP + 5).coerceAtMost(200))
                why += "koła nie nadążają (opóźnienie ${(st.wheelLagS * 1000).roundToInt()} ms) → wzmocnienie P ${q.gainP}"
            } else if (st.wheelGain > 1.3 && st.wheelLagS < 0.2 && p.gainP > 10) {
                q = q.copy(gainP = (p.gainP - 5).coerceAtLeast(10))
                why += "koła przestrzeliwują → wzmocnienie P ${q.gainP}"
            }
            // małe korekty: zadany mały, a błąd kąta stale większy od martwej strefy
            val small = w.filter { abs(it.set) < 2.0 }
            if (small.size > w.size / 2) {
                val err = small.sumOf { abs(it.set - it.act) } / small.size
                if (err > p.deadZoneDeg + 0.6 && p.minPwm < 100) {
                    q = q.copy(minPwm = (p.minPwm + 2).coerceAtMost(100))
                    why += "małe korekty nie ruszają kół (błąd ${r1(err)}°) → minimalny PWM ${q.minPwm}"
                }
            }
        }
        return if (why.isEmpty()) null else Proposal(q, why, st)
    }

    private fun r1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun r2(v: Double) = (v * 100).roundToInt() / 100.0

    companion object {
        const val WINDOW_S = 30.0
    }
}
