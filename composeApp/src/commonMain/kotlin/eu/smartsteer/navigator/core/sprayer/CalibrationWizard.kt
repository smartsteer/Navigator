package eu.smartsteer.navigator.core.sprayer

import kotlin.math.abs

/**
 * Kreator kalibracji opryskiwacza — na samej wodzie, na postoju, „krok po kroku”:
 *
 * 1. Przygotowanie (łączność z płytką, woda, pompa).
 * 2. Czujnik ciśnienia: rodzaj sygnału, zakres i zero (zawór główny zamknięty).
 * 3. Sprawdzenie przepływu: czy jest ciśnienie i impulsy przepływomierza.
 * 4. Nauka zaworu regulacyjnego — sama: zamknięcie, otwieranie krokami (wzmocnienie, czas przesuwu, czas ustalania), luz.
 * 5. Próba z miarką: program ustawia ~3 bar, rolnik łapie wodę z 1–3 dysz przez minutę → impulsy/l i zużycie dysz.
 * 6. Próba dawki: program „jedzie” wirtualnie (prędkość z zakresu dyszy), sam dochodzi do dawki i zmienia prędkość.
 * 7. Podsumowanie: co się zmieniło; zapisz albo odrzuć.
 *
 * Czysta logika (bez ekranu): co krok dostaje pomiary [In] i oddaje polecenie [Out] dla komputera opryskiwacza.
 */
class CalibrationWizard(val original: SprayerConfig) {
    enum class Step(val title: String) {
        PREPARE("Przygotowanie"),
        SENSOR("Czujnik ciśnienia"),
        FLOW_CHECK("Sprawdzenie przepływu"),
        VALVE("Nauka zaworu regulacyjnego"),
        NOZZLE("Próba z miarką"),
        RATE("Próba dawki"),
        DONE("Gotowe"),
    }

    data class In(
        val dtMs: Int,
        val linkOk: Boolean,
        /** Licznik impulsów przepływomierza z płytki (null = brak stanu). */
        val pulseCount: Long?,
        /** Przepływ użyty do regulacji (wg trybu pomiaru), l/min. */
        val flowLpm: Double,
        /** Ciśnienie po przeliczeniu czujnika (null = brak czujnika). */
        val pressureBar: Double?,
        /** Ciśnienie surowe z płytki (do zerowania). */
        val rawPressureBar: Double?,
        /** Przepływ docelowy komputera (próba dawki), l/min. */
        val targetLpm: Double = 0.0,
    )

    data class Out(val running: Boolean, val mask: Int, val autoRate: Boolean, val pulseMs: Int, val speedKmh: Double? = null)

    var result = original; private set
    var step = Step.PREPARE; private set
    /** Bieżący opis tego, co się dzieje (na ekran). */
    var status = ""; private set
    var error: String? = null; private set
    var progress = 0f; private set
    /** Krok zrobiony — można iść dalej. */
    var stepDone = false; private set
    /** Automatyczna procedura w toku (nauka zaworu, próba dawki). */
    var busy = false; private set
    /** Prośba do sterownika: zastosuj [result] w komputerze (przed próbą dawki). */
    var applyRequest = false

    val hasMeter get() = original.flowMode != FlowMode.PRESSURE
    val hasSensor get() = original.flowMode != FlowMode.FLOWMETER
    private val allMask get() = (1 shl original.sectionCount) - 1
    private val openNozzles get() = original.nozzlesPerSection.sum()

    // ---------------- nawigacja ----------------

    val steps: List<Step> get() = Step.entries.filter { it != Step.SENSOR || hasSensor }

    fun next() {
        val i = steps.indexOf(step)
        if (i < steps.lastIndex) enter(steps[i + 1])
    }

    fun back() {
        val i = steps.indexOf(step)
        if (i > 0) enter(steps[i - 1])
    }

    private fun enter(s: Step) {
        step = s
        error = null
        progress = 0f
        busy = false
        stepDone = s == Step.SENSOR || s == Step.DONE
        phase = 0
        phaseMs = 0
        status = ""
        nozzleState = NozzleState.IDLE
        if (s == Step.RATE) applyRequest = true
    }

    // ---------------- czujnik ciśnienia ----------------

    fun setSensor(input: PressureInput, rangeBar: Double) {
        result = result.copy(pressureInput = input, pressureRangeBar = rangeBar)
    }

    /** Zero przy zamkniętym zaworze głównym: to, co czujnik pokazuje teraz, staje się zerem. */
    fun zeroPressure(raw: Double?): String {
        if (raw == null) return "Brak odczytu ciśnienia — czujnik podłączony?"
        val p = result.copy(pressureZeroBar = 0.0).sensorBar(raw) ?: 0.0
        if (p > 1.5) return "Czujnik pokazuje ${fmt(p)} bar przy zamkniętym zaworze — zły zakres albo pompa tłoczy na czujnik. Nie zeruję."
        result = result.copy(pressureZeroBar = p)
        return "Wyzerowano (poprawka ${fmt(p)} bar)."
    }

    // ---------------- krok co dtMs ----------------

    private var phase = 0
    private var phaseMs = 0
    private var startPulses: Long? = null
    private var sentMs = 0

    fun step(i: In): Out {
        phaseMs += i.dtMs
        return when (step) {
            Step.PREPARE -> {
                stepDone = i.linkOk
                status = if (i.linkOk) "Płytka połączona." else "Czekam na łączność z płytką…"
                Out(false, 0, false, 0)
            }
            Step.SENSOR -> {
                status = i.pressureBar?.let { "Ciśnienie teraz: ${fmt(it)} bar (zawór główny zamknięty — powinno być ~0)" } ?: "Brak odczytu ciśnienia"
                Out(false, 0, false, 0)
            }
            Step.FLOW_CHECK -> flowCheck(i)
            Step.VALVE -> valveLearn(i)
            Step.NOZZLE -> nozzle(i)
            Step.RATE -> rateTest(i)
            Step.DONE -> Out(false, 0, false, 0)
        }
    }

    // ---------------- 3. sprawdzenie przepływu ----------------

    private fun flowCheck(i: In): Out {
        if (stepDone || error != null) return Out(stepDone, allMask, false, 0)
        var pulse = 0
        if (phase == 0) { phase = 1; phaseMs = 0; startPulses = i.pulseCount; pulse = 2000 }  // zawór regulacyjny mocniej otwarty
        val pulses = (i.pulseCount ?: 0) - (startPulses ?: i.pulseCount ?: 0)
        val pOk = !hasSensor || (i.pressureBar ?: 0.0) > 0.3
        val mOk = !hasMeter || pulses >= 20
        progress = (phaseMs / 10_000f).coerceAtMost(1f)
        status = "Wszystkie sekcje otwarte. " + (if (hasSensor) "Ciśnienie ${i.pressureBar?.let { fmt(it) } ?: "—"} bar. " else "") +
            (if (hasMeter) "Impulsy przepływomierza: $pulses." else "")
        if (pOk && mOk && phaseMs > 2000) { stepDone = true; status = "Przepływ jest. " + status }
        else if (phaseMs > 10_000) error = when {
            !pOk && !mOk -> "Brak przepływu: pompa włączona (WOM)? Woda w zbiorniku? Zawór główny i sekcje się otwierają?"
            !mOk -> "Ciśnienie jest, ale przepływomierz nie daje impulsów: wtyczka i przewód przepływomierza, zasilanie 12 V czujnika."
            else -> "Przepływomierz liczy, ale czujnik ciśnienia nic nie pokazuje: wtyczka, rodzaj sygnału (V / mA) i zakres."
        }
        return Out(true, allMask, false, pulse)
    }

    /** Ponów krok po usunięciu usterki. */
    fun retry() { enter(step) }

    // ---------------- 4. nauka zaworu regulacyjnego ----------------

    private data class Sample(val ms: Int, val lpm: Double)
    private val stepResults = mutableListOf<Pair<Double, Int>>()   // (Δ przepływu, czas odpowiedzi 90 %)
    private val window = mutableListOf<Sample>()
    private var fStart = 0.0
    private var fMin = 0.0
    private var fMaxSeen = 0.0
    private var openSteps = 0
    private var smallSteps = 0
    private val moves = mutableListOf<Double>()
    private val stepMs = 400
    private val bigStepMs = 600

    /** Start automatycznej nauki (przycisk „START” na ekranie). */
    fun startValveLearn() {
        if (step != Step.VALVE) return
        busy = true; error = null; stepDone = false
        phase = 1; phaseMs = 0; sentMs = 0
        stepResults.clear(); window.clear(); openSteps = 0; smallSteps = 0
    }

    private fun valveLearn(i: In): Out {
        if (!busy) return Out(true, allMask, false, 0)
        var pulse = 0
        val f = i.flowLpm
        when (phase) {
            1 -> { // zamykanie do końca (10 s silnika, po 100 ms na krok)
                status = "Zamykam zawór regulacyjny do końca…"
                progress = 0.05f + 0.15f * (sentMs / CLOSE_MS.toFloat())
                if (sentMs < CLOSE_MS) { pulse = -i.dtMs; sentMs += i.dtMs }
                else if (phaseMs > CLOSE_MS + 3000) { fMin = f; fMaxSeen = f; phase = 2; phaseMs = 0 }
            }
            2 -> { // otwieranie krokami: impuls, potem okno pomiaru do ustalenia
                if (phaseMs == i.dtMs) { pulse = stepMs; fStart = f; window.clear() }
                else {
                    window += Sample(phaseMs, f)
                    val after = phaseMs - stepMs
                    if (settled(after, f, i, 1000, 4000)) {
                        val fEnd = window.takeLast(5).map { it.lpm }.average()
                        val d = fEnd - fStart
                        val resp = window.firstOrNull { it.ms > stepMs && it.lpm - fStart >= 0.9 * d }?.let { it.ms - stepMs } ?: after
                        stepResults += d to resp
                        fMaxSeen = maxOf(fMaxSeen, fEnd)
                        openSteps++
                        smallSteps = if (d < 0.015 * maxOf(fEnd, 1.0)) smallSteps + 1 else 0
                        status = "Otwieram krokami: krok $openSteps, przepływ ${fmt(fEnd)} l/min"
                        progress = 0.2f + 0.5f * (openSteps / 40f).coerceAtMost(1f)
                        phaseMs = 0
                        if ((smallSteps >= 2 && openSteps > 3) || openSteps >= 40) phase = 3
                    }
                }
            }
            3 -> { // wynik otwierania, cofnięcie do połowy
                val useful = stepResults.indexOfLast { it.first >= 0.015 * maxOf(fMaxSeen, 1.0) } + 1
                if (fMaxSeen - fMin < 0.5 || useful == 0) {
                    error = "Zawór regulacyjny nie zmienia przepływu: przewody zaworu (zawór regulacyjny na płytce), pompa, zawór główny."
                    busy = false
                    return Out(true, allMask, false, 0)
                }
                val travel = (useful * stepMs).coerceIn(800, 30_000)
                // tylko kroki z wyraźną zmianą (≥ 5 % przepływu maks.) — na końcach przesuwu odpowiedź ginie w szumie
                val big = stepResults.take(useful).filter { it.first >= 0.05 * fMaxSeen }.ifEmpty { stepResults.take(useful).filter { it.first > 0 } }
                val gains = big.map { it.first / stepMs }.sorted()
                val gain = gains[gains.size / 2]
                val resp = big.map { it.second }.sorted()
                val settle = (resp[resp.size / 2] * 1.3).toInt().coerceIn(RateController.MIN_SETTLE, RateController.MAX_SETTLE)
                result = result.copy(valveTravelMs = travel, regGain = gain, regSettleMs = settle)
                phase = 4; phaseMs = 0; sentMs = 0
            }
            4 -> { // do połowy: zamykanie o połowę przesuwu, czekanie na ustalenie
                status = "Mierzę luz zaworu…"
                progress = 0.75f
                val half = result.valveTravelMs / 2
                if (sentMs < half) { pulse = -minOf(i.dtMs, half - sentMs); sentMs += -pulse; window.clear() }
                else { window += Sample(phaseMs, f); if (phaseMs > half + 800 && (stable(window, 1600, i) || phaseMs > half + 8000)) { phase = 5; phaseMs = 0; moves.clear() } }
            }
            5 -> { // cztery ruchy: +S (po zmianie kierunku, z luzem), +S (czysty), −S (z luzem), −S (czysty)
                val sMs = maxOf(bigStepMs, result.valveTravelMs / 8)
                val dir = if (moves.size < 2) 1 else -1
                if (phaseMs == i.dtMs) { pulse = sMs * dir; fStart = f; window.clear() }
                else {
                    window += Sample(phaseMs, f)
                    val after = phaseMs - sMs
                    if (settled(after, f, i, 1600, 6000)) {
                        moves += window.takeLast(5).map { it.lpm }.average() - fStart
                        phaseMs = 0
                        progress = 0.75f + 0.06f * moves.size
                        if (moves.size == 4) {
                            // luz = część kroku, która nic nie zmieniła; średnia z otwierania i zamykania (krzywizna zaworu się znosi)
                            fun lost(withBl: Double, clean: Double) = if (abs(clean) > 0.1) (1 - (withBl / clean).coerceIn(0.0, 1.0)) * sMs else 0.0
                            val backlash = ((lost(moves[0], moves[1]) + lost(moves[2], moves[3])) / 2).toInt().coerceIn(0, RateController.MAX_BACKLASH)
                            result = result.copy(regBacklashMs = backlash)
                            busy = false; stepDone = true; progress = 1f
                            status = "Zawór nauczony: pełny przesuw ok. ${fmt(result.valveTravelMs / 1000.0)} s, ustalanie ${fmt(result.regSettleMs / 1000.0)} s, luz ${result.regBacklashMs} ms."
                        }
                    }
                }
            }
        }
        return Out(true, allMask, false, pulse)
    }

    /**
     * Koniec okna pomiaru po impulsie: przepływ najpierw musi ruszyć (opóźnienie w wężach i pompie — płaski przepływ
     * zaraz po impulsie to nie „ustalenie”), potem stać; bez ruchu — dopiero po 3 s; zawsze najpóźniej po [maxMs].
     */
    private fun settled(after: Int, f: Double, i: In, stableMs: Int, maxMs: Int): Boolean {
        if (after < 300) return false
        if (after >= maxMs) return true
        val moved = abs(f - fStart) > maxOf(0.02 * fStart, 0.1)
        return if (moved) stable(window, stableMs, i) else after >= 3000
    }

    /** Przepływ stoi: średnia z ostatnich [ms]/2 prawie równa średniej z [ms]/2 wcześniej (odporne na szum przepływomierza). */
    private fun stable(w: List<Sample>, ms: Int, i: In): Boolean {
        val end = w.lastOrNull()?.ms ?: return false
        val a = w.filter { it.ms > end - ms / 2 }.map { it.lpm }
        val b = w.filter { it.ms in (end - ms + 1)..(end - ms / 2) }.map { it.lpm }
        if (a.size < ms / 2 / i.dtMs || b.size < ms / 2 / i.dtMs) return false
        val ma = a.average()
        return abs(ma - b.average()) <= maxOf(0.008 * ma, 0.05)
    }

    // ---------------- 5. próba z miarką ----------------

    enum class NozzleState { IDLE, STABILIZING, READY, MEASURING, ENTER, DONE }

    var nozzleState = NozzleState.IDLE; private set
    /** Sekundy do końca minuty pomiaru. */
    var measureLeftS = 0; private set
    /** Ciśnienie, przy którym robimy próbę (dysze płaskie 3 bar, eżektorowe 4 bar). */
    val testBar get() = if (original.nozzleKind.minBar > 3.0) 4.0 else if (original.nozzleKind.maxBar < 3.0) 2.0 else 3.0
    private var nozzleCtrl: RateController? = null
    private var okMs = 0
    private var measPulses0: Long? = null
    private var measPulses = 0L
    private var pSum = 0.0
    private var pN = 0
    /** Wyniki ostatniej próby z miarką (na ekran). */
    var nozzleReport = ""; private set

    fun startNozzle() {
        if (step != Step.NOZZLE) return
        nozzleState = NozzleState.STABILIZING
        okMs = 0
        nozzleCtrl = RateController(
            nominalFlowLpm = original.nozzle.lpmAt3Bar * openNozzles,
            valveTravelMs = result.valveTravelMs,
            learned = if (result.regGain > 0) ValveLearned(result.regGain, result.regSettleMs, result.regBacklashMs) else null,
        )
    }

    fun startMeasure() { if (nozzleState == NozzleState.READY) { nozzleState = NozzleState.MEASURING; phaseMs = 0; measPulses0 = null; pSum = 0.0; pN = 0 } }

    private fun nozzle(i: In): Out {
        // cel: katalogowy wydatek przy ciśnieniu próby (woda) — tylko do ustawienia ciśnienia, dokładność tu niepotrzebna
        val target = original.nozzle.flowAt(testBar) * openNozzles
        var pulse = 0
        if (nozzleState == NozzleState.STABILIZING || nozzleState == NozzleState.READY) {
            val measured = i.pressureBar?.let { original.nozzle.flowAt(it) * openNozzles } ?: i.flowLpm
            pulse = nozzleCtrl?.step(i.dtMs, true, target, measured, allMask, 1.0, 0.03) ?: 0
            okMs = if (abs(measured - target) <= 0.05 * target) okMs + i.dtMs else 0
            if (okMs >= 3000) nozzleState = NozzleState.READY
            status = "Ustawiam ok. ${fmt(testBar)} bar: " + (i.pressureBar?.let { "${fmt(it)} bar" } ?: "${fmt(i.flowLpm)} l/min")
        }
        if (nozzleState == NozzleState.MEASURING) {
            if (measPulses0 == null) measPulses0 = i.pulseCount
            measPulses = (i.pulseCount ?: 0) - (measPulses0 ?: 0)
            i.pressureBar?.let { pSum += it; pN++ }
            measureLeftS = ((60_000 - phaseMs) / 1000).coerceAtLeast(0)
            progress = phaseMs / 60_000f
            status = "Łap wodę! Zostało $measureLeftS s"
            if (phaseMs >= 60_000) nozzleState = NozzleState.ENTER
        }
        if (nozzleState == NozzleState.ENTER) status = "Wpisz, ile litrów nalało się z każdej dyszy."
        return Out(true, allMask, false, pulse)
    }

    /** Litry z 1–3 dysz po minucie. Zwraca błąd albo null. */
    fun enterNozzleLiters(liters: List<Double>): String? {
        val v = liters.filter { it > 0 }
        if (v.isEmpty()) return "Wpisz co najmniej jeden wynik."
        val avg = v.average()
        val total = avg * openNozzles
        val sb = StringBuilder()
        if (v.size > 1 && (v.max() - v.min()) > 0.10 * avg) sb.append("Dysze różnią się o więcej niż 10 % — sprawdź filtry i zużycie dysz. ")
        if (hasMeter) {
            if (measPulses < 50) return "Przepływomierz dał za mało impulsów ($measPulses) — sprawdź go i powtórz próbę."
            val ppl = measPulses / total
            sb.append("Przepływomierz: ${fmt(result.pulsesPerLiter, 0)} → ${fmt(ppl, 1)} imp./l. ")
            result = result.copy(pulsesPerLiter = ppl)
        }
        if (hasSensor && pN > 0) {
            val p = pSum / pN
            val nominal = original.nozzle.flowAt(p)
            if (nominal > 0) {
                val f = (avg / nominal).coerceIn(0.6, 1.6)
                val pct = (f - 1) * 100
                sb.append("Dysze: ${if (pct >= 0) "+" else ""}${fmt(pct, 0)} % względem tabeli przy ${fmt(p)} bar")
                if (pct > 10) sb.append(" — zużyte, warto wymienić")
                sb.append(". ")
                result = result.copy(nozzleFactor = f)
            }
        }
        nozzleReport = sb.toString().trim()
        nozzleState = NozzleState.DONE
        stepDone = true
        status = nozzleReport
        return null
    }

    // ---------------- 6. próba dawki ----------------

    /** Prędkość próby: środek zakresu dyszy dla dawki (wirtualna jazda na postoju). */
    val testSpeedKmh: Double get() {
        val r = result.speedRangeKmh()
        return ((r.start + r.endInclusive) / 2).coerceIn(4.0, 14.0)
    }
    var rateReport = ""; private set
    private var reachMs1 = -1
    private var reachMs2 = -1
    private var okRun = 0
    private val errs = mutableListOf<Double>()

    fun startRateTest() {
        if (step != Step.RATE) return
        busy = true; stepDone = false; error = null
        phase = 1; phaseMs = 0; okRun = 0; reachMs1 = -1; reachMs2 = -1; errs.clear()
    }

    private fun rateTest(i: In): Out {
        if (!busy) return Out(true, allMask, false, 0, testSpeedKmh)
        val speed = if (phase == 1) testSpeedKmh else testSpeedKmh * 1.25
        val err = if (i.targetLpm > 0) (i.flowLpm - i.targetLpm) / i.targetLpm else 1.0
        okRun = if (abs(err) <= 0.05) okRun + i.dtMs else 0
        if (phase == 1) {
            progress = phaseMs / 60_000f
            if (reachMs1 < 0 && okRun >= 3000) reachMs1 = phaseMs - okRun
            status = "Wirtualna jazda ${fmt(speed)} km/h — dochodzę do dawki (błąd ${fmt(err * 100, 0)} %)"
            if (phaseMs >= 30_000) { phase = 2; phaseMs = 0; okRun = 0 }
        } else {
            progress = 0.5f + phaseMs / 60_000f
            if (reachMs2 < 0 && okRun >= 3000) reachMs2 = phaseMs - okRun
            if (phaseMs > 20_000) errs += err
            status = "Przyspieszam do ${fmt(speed)} km/h — poprawiam dawkę (błąd ${fmt(err * 100, 0)} %)"
            if (phaseMs >= 30_000) {
                busy = false
                val mean = errs.average()
                rateReport = "Dojście do dawki: ${sec(reachMs1)}, po zmianie prędkości: ${sec(reachMs2)}, średni błąd ${fmt(mean * 100, 1)} %."
                when {
                    reachMs1 in 0..20_000 && reachMs2 in 0..20_000 && abs(mean) < 0.03 -> { stepDone = true; status = "Próba dawki udana. $rateReport" }
                    abs(mean) < 0.05 && (reachMs1 >= 0 || reachMs2 >= 0) -> {
                        stepDone = true
                        status = "Regulacja działa, ale wolno (długie węże, wolna pompa albo zawór). Można pracować — po zmianie prędkości dawka " +
                            "dochodzi dłużej. $rateReport"
                    }
                    else -> error = "Próba dawki nieudana. $rateReport Sprawdź przewody zaworu regulacyjnego (czy „więcej” otwiera), " +
                        "czy pompa daje radę przy tej dawce, i powtórz naukę zaworu."
                }
            }
        }
        return Out(true, allMask, true, 0, speed)
    }

    /** Podsumowanie zmian do pokazania na końcu. */
    fun summary(): List<String> {
        val o = original
        val r = result
        val l = mutableListOf<String>()
        if (hasSensor) l += "Czujnik ciśnienia: ${r.pressureInput.label}, 0–${fmt(r.pressureRangeBar, 0)} bar, zero ${fmt(r.pressureZeroBar)} bar"
        if (hasMeter && r.pulsesPerLiter != o.pulsesPerLiter) l += "Przepływomierz: ${fmt(o.pulsesPerLiter, 1)} → ${fmt(r.pulsesPerLiter, 1)} imp./l"
        if (r.nozzleFactor != o.nozzleFactor) l += "Dysze: wydatek ${fmt((r.nozzleFactor - 1) * 100, 0)} % względem tabeli"
        if (r.regGain != o.regGain) l += "Zawór regulacyjny: przesuw ${fmt(r.valveTravelMs / 1000.0)} s, ustalanie ${fmt(r.regSettleMs / 1000.0)} s, luz ${r.regBacklashMs} ms"
        if (rateReport.isNotEmpty()) l += "Próba dawki: $rateReport"
        return l
    }

    companion object {
        const val CLOSE_MS = 10_000
        private fun sec(ms: Int) = if (ms < 0) "nie doszedł" else "${fmt(ms / 1000.0)} s"
        fun fmt(v: Double, d: Int = 1): String {
            val s = if (d == 0) kotlin.math.round(v).toLong().toString()
            else { var m = 1.0; repeat(d) { m *= 10.0 }; (kotlin.math.round(v * m) / m).toString() }
            return s.replace('.', ',')
        }
    }
}
