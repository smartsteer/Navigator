package eu.smartsteer.navigator.core.sprayer

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/** Dysza ISO: przepływ przy 3 bar w l/min; przepływ ∝ √ciśnienia. */
data class Nozzle(val name: String, val color: String, val lpmAt3Bar: Double) {
    fun flowAt(bar: Double): Double = if (bar <= 0) 0.0 else lpmAt3Bar * sqrt(bar / 3.0)
    fun pressureFor(lpm: Double): Double = 3.0 * (lpm / lpmAt3Bar).let { it * it }

    companion object {
        /** Tabela dysz ISO 10625 (wydatek przy 3 bar). */
        val ISO = listOf(
            Nozzle("01", "pomarańczowa", 0.39),
            Nozzle("015", "zielona", 0.59),
            Nozzle("02", "żółta", 0.79),
            Nozzle("025", "fioletowa", 0.98),
            Nozzle("03", "niebieska", 1.18),
            Nozzle("04", "czerwona", 1.58),
            Nozzle("05", "brązowa", 1.97),
            Nozzle("06", "szara", 2.37),
            Nozzle("08", "biała", 3.16),
        )
    }
}

/** Rodzaj dyszy — zakres ciśnień roboczych (do podpowiedzi i alarmu). Wydatek wg rozmiaru ISO. */
enum class NozzleKind(val label: String, val minBar: Double, val maxBar: Double) {
    FLAT("Płaskostrumieniowa", 1.5, 5.0),
    AIR_INDUCTION("Eżektorowa (antyznoszeniowa)", 3.0, 8.0),
    RSM_3("RSM 3‑strumieniowa", 1.0, 3.0),
    RSM_7("RSM wielootworowa (5–7 otworów)", 1.0, 4.0),
    RSM_PLATE("RSM płytka kalibrowana / węże", 1.0, 3.0),
}

/**
 * Ciecz robocza. Tabele dysz są dla wody — ciecz gęstsza płynie wolniej: przepływ = przepływ wody / √gęstości
 * (np. RSM 1,28 kg/l → ×0,88). Dawka w kg/ha = l/ha × gęstość.
 */
enum class Liquid(val label: String, val density: Double) {
    WATER("Woda / ciecz robocza", 1.0),
    RSM28("RSM 28 %", 1.28),
    RSM30("RSM 30 %", 1.30),
    RSM32("RSM 32 %", 1.32),
}

/** Skąd brać prędkość do dawki. */
enum class SpeedSource(val label: String) {
    GPS("GPS tabletu / nawigacji"),
    BOARD_GPS("GPS płytki opryskiwacza"),
    MANUAL("Stała (wpisana)"),
}

/** Rodzaj sygnału czujnika ciśnienia (zworka na płytce). */
enum class PressureInput(val label: String) {
    VOLT("0,5–4,5 V"),
    MA("4–20 mA"),
}

/** Skąd brać przepływ do regulacji. */
enum class FlowMode(val label: String) {
    PRESSURE("Przepływ z ciśnienia (tabela dysz)"),
    PRESSURE_AND_FLOWMETER("Ciśnienie + przepływomierz"),
    FLOWMETER("Sam przepływomierz"),
}

/** Ustawienia opryskiwacza. */
data class SprayerConfig(
    /** Liczba dysz w każdej sekcji (rozmiar listy = liczba sekcji 1–13). */
    val nozzlesPerSection: List<Int> = List(7) { 6 },
    /** Rozstaw dysz, m. */
    val nozzleSpacingM: Double = 0.5,
    val nozzle: Nozzle = Nozzle.ISO[4],
    val nozzleKind: NozzleKind = NozzleKind.FLAT,
    val liquid: Liquid = Liquid.WATER,
    val valveType: ValveType = ValveType.ARAG_3PIN,
    val flowMode: FlowMode = FlowMode.PRESSURE_AND_FLOWMETER,
    /** Impulsy przepływomierza na litr (różne u producentów, np. Arag Wolf 255). */
    val pulsesPerLiter: Double = 255.0,
    /** Dawka zadana, l/ha. */
    val targetLha: Double = 200.0,
    /** Strefa martwa regulacji (ułamek dawki). */
    val deadband: Double = 0.025,
    /** Poniżej tej prędkości regulacja stoi, zawór zostaje w miejscu. */
    val minSpeedKmh: Double = 1.0,
    /** Czas pełnego przesuwu zaworu regulacyjnego, ms (punkt startowy uczenia). */
    val valveTravelMs: Int = 4000,
    val speedSource: SpeedSource = SpeedSource.GPS,
    /** Prędkość przy źródle „stała”, km/h. */
    val manualSpeedKmh: Double = 8.0,
    /** Pojemność zbiornika, l. */
    val tankCapacityL: Double = 1000.0,
    /** Próg rezerwy, l. */
    val tankReserveL: Double = 100.0,
    /** Rzeczywisty wydatek dysz / katalogowy (próba z miarką w kreatorze; zużyte dysze > 1). */
    val nozzleFactor: Double = 1.0,
    /** Czujnik ciśnienia: rodzaj sygnału, zakres i zero (kreator). */
    val pressureInput: PressureInput = PressureInput.VOLT,
    val pressureRangeBar: Double = 10.0,
    val pressureZeroBar: Double = 0.0,
    /** Wyuczony zawór regulacyjny (kreator i praca): l/min pełnej belki na ms silnika (0 = jeszcze nie), czas ustalania, luz. */
    val regGain: Double = 0.0,
    val regSettleMs: Int = 450,
    val regBacklashMs: Int = 0,
) {
    init {
        require(nozzlesPerSection.size in 1..SprayerPgn.MAX_SECTIONS) { "Liczba sekcji 1–13" }
    }

    val sectionCount get() = nozzlesPerSection.size
    fun sectionWidthM(i: Int) = nozzlesPerSection[i] * nozzleSpacingM
    val totalWidthM get() = nozzlesPerSection.sum() * nozzleSpacingM

    fun openNozzles(sectionMask: Int) = nozzlesPerSection.indices.sumOf { if (sectionMask and (1 shl it) != 0) nozzlesPerSection[it] else 0 }
    fun openWidthM(sectionMask: Int) = openNozzles(sectionMask) * nozzleSpacingM

    /** Przepływ potrzebny do dawki: l/min = l/ha × km/h × m / 600. */
    fun targetFlowLpm(speedKmh: Double, sectionMask: Int, rateLha: Double = targetLha) =
        rateLha * speedKmh * openWidthM(sectionMask) / 600.0

    /** Dawka z przepływu: l/ha = l/min × 600 / (km/h × m). */
    fun rateLha(flowLpm: Double, speedKmh: Double, sectionMask: Int): Double {
        val w = openWidthM(sectionMask)
        return if (speedKmh < 0.1 || w <= 0) 0.0 else flowLpm * 600.0 / (speedKmh * w)
    }

    /** Przelicznik przepływu cieczy względem wody (1/√gęstości). */
    val liquidFactor get() = 1.0 / sqrt(liquid.density)

    /** Wydatek jednej dyszy dla wybranej cieczy przy danym ciśnieniu. */
    fun nozzleFlowAt(bar: Double) = nozzle.flowAt(bar) * liquidFactor * nozzleFactor

    /**
     * Ciśnienie z czujnika wg ustawień. Płytka przelicza napięcie jak dla czujnika 0,5–4,5 V / 0–10 bar —
     * tu wracamy do napięcia i liczymy dla prawdziwego czujnika (inny zakres albo 4–20 mA na boczniku 150 Ω), minus zero.
     */
    fun sensorBar(reported: Double?): Double? {
        if (reported == null) return null
        val v = 0.5 + reported / 10.0 * 4.0
        val p = when (pressureInput) {
            PressureInput.VOLT -> (v - 0.5) / 4.0 * pressureRangeBar
            PressureInput.MA -> (v / 150.0 * 1000.0 - 4.0) / 16.0 * pressureRangeBar
        }
        return (p - pressureZeroBar).coerceAtLeast(0.0)
    }

    /** Ciśnienie, przy którym dysza da dawkę przy danej prędkości (do podpowiedzi i alarmu). */
    fun pressureFor(speedKmh: Double, rateLha: Double = targetLha) =
        nozzle.pressureFor(rateLha * speedKmh * nozzleSpacingM / 600.0 / liquidFactor / nozzleFactor)

    /** Zakres prędkości, w którym dysza utrzyma dawkę w swoim zakresie ciśnień. */
    fun speedRangeKmh(rateLha: Double = targetLha): ClosedFloatingPointRange<Double> {
        fun v(bar: Double) = nozzleFlowAt(bar) * 600.0 / (rateLha * nozzleSpacingM)
        return v(nozzleKind.minBar)..v(nozzleKind.maxBar)
    }

    /** Dysze na metr belki. */
    val nozzlesPerMeter get() = 1.0 / nozzleSpacingM
}

/**
 * Przepływ z przepływomierza: z czasu między impulsami (świeży pomiar także przy małym przepływie),
 * lekki filtr, zero gdy licznik stoi dłużej niż 2 s.
 */
class FlowMeter(var pulsesPerLiter: Double) {
    var lpm = 0.0; private set
    var totalLiters = 0.0; private set
    private var lastCount: Long? = null
    private var sinceChangeMs = 0

    fun update(status: SprayerStatus, dtMs: Int, alpha: Double = 0.5) {
        val prev = lastCount
        lastCount = status.pulseCount
        if (prev != null) {
            val d = pulseDelta(prev, status.pulseCount)
            if (d in 1..100_000) { totalLiters += d / pulsesPerLiter; sinceChangeMs = 0 } else sinceChangeMs += dtMs
        }
        val raw = if (sinceChangeMs > 2000) 0.0 else status.flowLpm(pulsesPerLiter)
        lpm += (raw - lpm) * alpha
        if (raw == 0.0 && lpm < 0.05) lpm = 0.0
    }

    fun resetTotal() { totalLiters = 0.0 }
}

/** Wyuczone cechy zaworu regulacyjnego (zapisywane w ustawieniach). */
data class ValveLearned(val gain: Double, val settleMs: Int, val backlashMs: Int)

/**
 * Regulator dawki — impulsy silnika zaworu regulacyjnego. Bez współczynników do ręcznego ustawiania: wszystko się uczy.
 *
 * 1. Cel liczony od razu z prędkości i włączonych sekcji (wyprzedzenie), regulator dostaje gotowy przepływ docelowy.
 * 2. Impuls o długości z wyuczonego wzmocnienia zaworu (l/min na ms pracy silnika, odniesione do pełnej szerokości):
 *    daleko od celu = długi impuls, blisko = krótki; strefa martwa ±[SprayerConfig.deadband].
 * 3. Po impulsie czeka na ustalenie: najpierw wyuczony czas, potem dopóki przepływ jeszcze się zmienia (długie węże,
 *    wolna pompa) — i uczy się tego czasu, żeby następnym razem nie pytać za wcześnie (to powoduje „bujanie”).
 * 4. Luz zaworu: przy zmianie kierunku impuls wydłużony o wyuczony luz; luz mierzony z impulsów po zmianie kierunku.
 * 5. Zmiana sekcji / zatrzymanie: pomiar w trakcie zmiany nie uczy, regulacja czeka na ustalenie; na postoju zawór stoi.
 */
class RateController(
    nominalFlowLpm: Double,
    valveTravelMs: Int,
    learned: ValveLearned? = null,
    val aggressiveness: Double = 0.7,
    val minPulseMs: Int = 15,
    val maxPulseMs: Int = 1000,
) {
    private val gain0 = nominalFlowLpm / valveTravelMs
    /** Wyuczone wzmocnienie: zmiana przepływu (l/min przy pełnej szerokości) na ms pracy silnika. */
    var gain = learned?.gain?.takeIf { it > 0 } ?: gain0; private set
    private val gainMin = minOf(gain, gain0) / 8
    private val gainMax = maxOf(gain, gain0) * 8
    /** Czas ustalania się przepływu po impulsie (uczony). */
    var settleMs = (learned?.settleMs ?: 450).coerceIn(MIN_SETTLE, MAX_SETTLE); private set
    /** Luz zaworu przy zmianie kierunku (uczony). */
    var backlashMs = (learned?.backlashMs ?: 0).coerceIn(0, MAX_BACKLASH); private set

    fun learned() = ValveLearned(gain, settleMs, backlashMs)

    /** Kilka impulsów w tę samą stronę bez reakcji: zawór regulacyjny na końcu (pompa za słaba / za mała dysza do dawki). */
    val atLimit get() = noEffect >= 3
    private var noEffect = 0

    private var waitMs = 0
    private var lastPulse = 0
    private var lastDir = 0
    private var lastWasReversal = false
    private var flowBefore = 0.0
    private var openFracAtPulse = 1.0
    private var lastMask = -1
    // oczekiwanie na odpowiedź po impulsie
    private var sincePulseMs = 0
    private var expected = 0.0
    private var responseMs = -1
    private var holdMs = 0
    private var timedOut = false
    private var targetAtPulse = 0.0
    private var prevMeasured = Double.NaN
    private var unstableMs = 0
    /** Szum pomiaru przepływu (średnia |zmiana| na 100 ms w spoczynku) — progi „stoi / rusza się” dopasowane do przepływomierza. */
    private var noise = 0.0

    /**
     * Jeden krok co [dtMs]. Zwraca impuls zaworu w ms (+ więcej cieczy, − mniej), 0 = nic.
     * [openFraction] — udział otwartych dysz w całej belce (skaluje wzmocnienie).
     */
    fun step(dtMs: Int, active: Boolean, targetLpm: Double, measuredLpm: Double, sectionMask: Int, openFraction: Double, deadband: Double): Int {
        val slope = if (prevMeasured.isNaN()) 0.0 else (measuredLpm - prevMeasured) * 100.0 / dtMs   // l/min na 100 ms
        prevMeasured = measuredLpm
        if (sectionMask != lastMask) { lastMask = sectionMask; waitMs = maxOf(waitMs, settleMs); lastPulse = 0 }
        if (!active || targetLpm <= 0 || openFraction <= 0) { waitMs = settleMs; lastPulse = 0; return 0 }
        if (waitMs > 0) { waitMs -= dtMs; if (waitMs > 0) return 0 }

        // duża zmiana celu (prędkość, dawka) w trakcie czekania — nie czekamy na stary impuls, reagujemy od razu (bez nauki)
        if (lastPulse != 0 && abs(targetLpm - targetAtPulse) > 0.08 * targetAtPulse) { lastPulse = 0; responseMs = -1 }
        if (lastPulse != 0) {
            // Czekamy na odpowiedź: silnik musi skończyć, potem przepływ ma się zmienić o ~60 % oczekiwanej zmiany
            // (opóźnienie w wężach / pompie bywa długie — pytanie za wcześnie powoduje „bujanie”), potem jeszcze chwila na resztę.
            sincePulseMs += dtMs
            if (sincePulseMs < abs(lastPulse)) return 0
            val after = sincePulseMs - abs(lastPulse)
            if (responseMs < 0) {
                val d = (measuredLpm - flowBefore) * sign(lastPulse.toDouble())   // tylko zmiana w stronę impulsu
                val nz = maxOf(0.02 * maxOf(flowBefore, targetLpm), 3 * noise)
                if (d >= maxOf(0.6 * expected, nz)) { responseMs = after; timedOut = false }
                else if (after < MAX_WAIT) return 0
                else { responseMs = MAX_WAIT; timedOut = true }
                holdMs = if (timedOut) 0 else (responseMs * 0.8).toInt().coerceIn(150, 1500)
            }
            if (holdMs > 0) { holdMs -= dtMs; return 0 }
            // czas ustalania na przyszłość (zmiana sekcji, start) — tylko z prawdziwej odpowiedzi, nie z przekroczenia czasu
            if (!timedOut) settleMs = (settleMs * 0.7 + responseMs * 1.8 * 0.3).toInt().coerceIn(MIN_SETTLE, MAX_SETTLE)
            if (abs(lastPulse) >= 40 && abs(openFraction - openFracAtPulse) < 1e-6) {
                val effLpm = (measuredLpm - flowBefore) / openFraction
                if (lastWasReversal) {
                    // ile z impulsu „zjadł” luz
                    val effMs = effLpm / gain * sign(lastPulse.toDouble())
                    val lost = (abs(lastPulse) - effMs).coerceIn(0.0, abs(lastPulse).toDouble())
                    backlashMs = (backlashMs * 0.6 + lost * 0.4).toInt().coerceIn(0, MAX_BACKLASH)
                } else {
                    val observed = effLpm / lastPulse
                    // brak reakcji (< 30 % oczekiwanej) — zawór na końcu albo pompa nie daje rady: nie uczymy się z tego
                    if (observed > 0 && abs(effLpm * openFraction) >= 0.3 * expected) {
                        gain = (gain * 0.6 + observed * 0.4).coerceIn(gainMin, gainMax)
                        noEffect = 0
                    } else noEffect++
                }
            }
            lastPulse = 0
        }

        val err = targetLpm - measuredLpm
        // szum mierzony tylko w spokoju (dawka w strefie martwej), nie w trakcie zmian
        if (abs(err) <= deadband * targetLpm) { noise = noise * 0.95 + minOf(abs(slope), 0.05 * targetLpm) * 0.05; unstableMs = 0; return 0 }
        // przepływ jeszcze się zmienia (po zmianie prędkości, ciśnienie dochodzi) — nie poprawiamy „w biegu”, chwilę czekamy
        if (abs(slope) > maxOf(0.01 * targetLpm, 3 * noise, 0.05) && unstableMs < 1500) { unstableMs += dtMs; return 0 }
        unstableMs = 0
        val ms = (abs(err) / (gain * openFraction) * aggressiveness).toInt().coerceIn(minPulseMs, maxPulseMs)
        val dir = if (err > 0) 1 else -1
        val reversal = lastDir != 0 && dir != lastDir
        if (reversal) noEffect = 0
        val pulse = (ms + if (reversal) backlashMs else 0) * dir
        lastDir = dir
        lastWasReversal = reversal
        lastPulse = pulse
        flowBefore = measuredLpm
        targetAtPulse = targetLpm
        openFracAtPulse = openFraction
        expected = gain * ms * openFraction
        sincePulseMs = 0
        responseMs = -1
        return pulse
    }

    companion object {
        const val MIN_SETTLE = 250
        const val MAX_SETTLE = 4000
        const val MAX_WAIT = 3000
        const val MAX_BACKLASH = 1500
    }
}

/** Wirtualny zbiornik: wlana woda wpisana przy napełnianiu, potem odejmowane zużycie. */
class VirtualTank(var capacityL: Double) {
    var liters = 0.0; private set

    fun fill(addedL: Double) { liters = (liters + addedL).coerceIn(0.0, capacityL) }
    fun setLevel(l: Double) { liters = l.coerceIn(0.0, capacityL) }
    fun consume(l: Double) { liters = (liters - l).coerceAtLeast(0.0) }

    /** Ile hektarów jeszcze starczy przy danej dawce. */
    fun hectaresLeft(rateLha: Double) = if (rateLha <= 0) 0.0 else liters / rateLha
}

/**
 * Komputer opryskiwacza — składa wszystko: tryb pomiaru, dawkę, regulację, zbiornik i polecenie PGN 160.
 * Wywoływany co ~100 ms z bieżącą prędkością, maską sekcji (z automatu sekcji Navigatora) i stanem płytki.
 */
class SprayerComputer(var config: SprayerConfig) {
    val flowMeter = FlowMeter(config.pulsesPerLiter)
    val tank = VirtualTank(config.tankCapacityL)
    private var controller = newController()
    private var counter = 0

    var running = false          // START/STOP
    var autoRate = true          // AUTO / RĘCZNIE
    var workLights = false
    /** Ręczna zmiana dawki w %, przyciski ±. */
    var rateAdjustPercent = 0

    var flowLpm = 0.0; private set
    var targetLpm = 0.0; private set
    var actualLha = 0.0; private set
    var pressureBar: Double? = null; private set
    var lastPulseMs = 0; private set

    val targetLha get() = config.targetLha * (1 + rateAdjustPercent / 100.0)

    private fun newController() = RateController(
        nominalFlowLpm = config.nozzle.lpmAt3Bar * config.nozzlesPerSection.sum(),
        valveTravelMs = config.valveTravelMs,
        learned = if (config.regGain > 0) ValveLearned(config.regGain, config.regSettleMs, config.regBacklashMs) else null,
    )

    /** Co regulator wie o zaworze teraz (do zapisu w ustawieniach). */
    fun learned() = controller.learned()

    fun reconfigure(c: SprayerConfig) {
        val nozzlesChanged = c.nozzle != config.nozzle || c.nozzlesPerSection != config.nozzlesPerSection || c.liquid != config.liquid
        val learnedChanged = c.regGain != config.regGain || c.regSettleMs != config.regSettleMs || c.regBacklashMs != config.regBacklashMs ||
            c.valveTravelMs != config.valveTravelMs
        config = if (nozzlesChanged && !learnedChanged) c.copy(regGain = 0.0) else c
        flowMeter.pulsesPerLiter = c.pulsesPerLiter
        tank.capacityL = c.tankCapacityL
        if (nozzlesChanged || learnedChanged) controller = newController()
    }

    /** Przepływ użyty do regulacji wg wybranego trybu. */
    fun measuredFlow(status: SprayerStatus?, sectionMask: Int): Double {
        val p = config.sensorBar(status?.pressureBar)
        val fromPressure = if (p == null || status?.mainOpen != true) 0.0 else config.openNozzles(sectionMask) * config.nozzleFlowAt(p)
        return when (config.flowMode) {
            FlowMode.PRESSURE -> fromPressure
            FlowMode.FLOWMETER -> flowMeter.lpm
            // przepływomierz; gdy nie daje impulsów przy ciśnieniu — zapas z ciśnienia
            FlowMode.PRESSURE_AND_FLOWMETER -> if (flowMeter.lpm == 0.0 && fromPressure > 0.5) fromPressure else flowMeter.lpm
        }
    }

    /** Krok co [dtMs]; zwraca polecenie do wysłania. [manualPulseMs] — impuls z przycisków ± w trybie RĘCZNIE. */
    fun step(dtMs: Int, speedKmh: Double, sectionMask: Int, status: SprayerStatus?, manualPulseMs: Int = 0): SprayerCommand {
        val n = config.sectionCount
        val mask = sectionMask and ((1 shl n) - 1)
        if (status != null) flowMeter.update(status, dtMs)
        pressureBar = config.sensorBar(status?.pressureBar)
        val main = running && mask != 0
        flowLpm = measuredFlow(status, mask)
        if (main) tank.consume(flowLpm * dtMs / 60_000.0)
        targetLpm = if (main) config.targetFlowLpm(speedKmh, mask, targetLha) else 0.0
        actualLha = if (main) config.rateLha(flowLpm, speedKmh, mask) else 0.0

        val totalNozzles = config.nozzlesPerSection.sum().toDouble()
        val active = main && autoRate && speedKmh >= config.minSpeedKmh && (status?.mainOpen ?: false)
        val pulse = controller.step(dtMs, active, targetLpm, flowLpm, mask, config.openNozzles(mask) / totalNozzles, config.deadband)
        lastPulseMs = if (autoRate) pulse else manualPulseMs
        counter = (counter + 1) and 0xFF
        return SprayerCommand(
            counter = counter,
            mainOpen = main,
            sections = if (running) mask else 0,
            sectionCount = n,
            valveType = config.valveType,
            workLights = workLights,
            regPulseMs = lastPulseMs,
        )
    }

    /** Dawka w tolerancji (±10 %), do alarmu. */
    /** Ciśnienie teoretyczne z przepływu i tabeli dysz (gdy brak czujnika); null = nic nie płynie. */
    fun theoreticalBar(sectionMask: Int): Double? {
        val open = config.openNozzles(sectionMask)
        if (open == 0 || flowLpm <= 0.05) return null
        return config.nozzle.pressureFor(flowLpm / open / config.liquidFactor / config.nozzleFactor)
    }

    fun rateOk() = targetLpm <= 0 || abs(flowLpm - targetLpm) <= 0.1 * targetLpm
}

/** Zapis ustawień opryskiwacza w prostym pliku „klucz=wartość”. */
object SprayerConfigText {
    fun write(c: SprayerConfig): String = buildString {
        appendLine("sekcje=${c.nozzlesPerSection.joinToString(";")}")
        appendLine("rozstaw=${c.nozzleSpacingM}")
        appendLine("dysza=${c.nozzle.name}")
        appendLine("rodzaj=${c.nozzleKind.name}")
        appendLine("ciecz=${c.liquid.name}")
        appendLine("zawory=${c.valveType.name}")
        appendLine("pomiar=${c.flowMode.name}")
        appendLine("impulsy=${c.pulsesPerLiter}")
        appendLine("dawka=${c.targetLha}")
        appendLine("zbiornik=${c.tankCapacityL}")
        appendLine("rezerwa=${c.tankReserveL}")
        appendLine("przesuw=${c.valveTravelMs}")
        appendLine("predkosc=${c.speedSource.name}")
        appendLine("stala=${c.manualSpeedKmh}")
        appendLine("dysze_wsp=${c.nozzleFactor}")
        appendLine("cisn_sygnal=${c.pressureInput.name}")
        appendLine("cisn_zakres=${c.pressureRangeBar}")
        appendLine("cisn_zero=${c.pressureZeroBar}")
        appendLine("zawor_wzm=${c.regGain}")
        appendLine("zawor_ustal=${c.regSettleMs}")
        appendLine("zawor_luz=${c.regBacklashMs}")
    }

    fun read(text: String?): SprayerConfig {
        val d = SprayerConfig()
        if (text.isNullOrBlank()) return d
        val m = text.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        fun dbl(k: String, def: Double) = m[k]?.toDoubleOrNull() ?: def
        val sections = m["sekcje"]?.split(';')?.mapNotNull { it.toIntOrNull()?.coerceIn(1, 60) }
            ?.takeIf { it.size in 1..SprayerPgn.MAX_SECTIONS } ?: d.nozzlesPerSection
        return runCatching {
            SprayerConfig(
                nozzlesPerSection = sections,
                nozzleSpacingM = dbl("rozstaw", d.nozzleSpacingM).coerceIn(0.1, 2.0),
                nozzle = Nozzle.ISO.firstOrNull { it.name == m["dysza"] } ?: d.nozzle,
                nozzleKind = NozzleKind.entries.firstOrNull { it.name == m["rodzaj"] } ?: d.nozzleKind,
                liquid = Liquid.entries.firstOrNull { it.name == m["ciecz"] } ?: d.liquid,
                valveType = ValveType.entries.firstOrNull { it.name == m["zawory"] } ?: d.valveType,
                flowMode = FlowMode.entries.firstOrNull { it.name == m["pomiar"] } ?: d.flowMode,
                pulsesPerLiter = dbl("impulsy", d.pulsesPerLiter).coerceIn(1.0, 10_000.0),
                targetLha = dbl("dawka", d.targetLha).coerceIn(10.0, 2000.0),
                tankCapacityL = dbl("zbiornik", d.tankCapacityL).coerceIn(50.0, 20_000.0),
                tankReserveL = dbl("rezerwa", d.tankReserveL).coerceAtLeast(0.0),
                valveTravelMs = dbl("przesuw", d.valveTravelMs.toDouble()).toInt().coerceIn(500, 30_000),
                speedSource = SpeedSource.entries.firstOrNull { it.name == m["predkosc"] } ?: d.speedSource,
                manualSpeedKmh = dbl("stala", d.manualSpeedKmh).coerceIn(0.5, 40.0),
                nozzleFactor = dbl("dysze_wsp", d.nozzleFactor).coerceIn(0.5, 2.0),
                pressureInput = PressureInput.entries.firstOrNull { it.name == m["cisn_sygnal"] } ?: d.pressureInput,
                pressureRangeBar = dbl("cisn_zakres", d.pressureRangeBar).coerceIn(1.0, 100.0),
                pressureZeroBar = dbl("cisn_zero", d.pressureZeroBar).coerceIn(-2.0, 2.0),
                regGain = dbl("zawor_wzm", d.regGain).coerceAtLeast(0.0),
                regSettleMs = dbl("zawor_ustal", d.regSettleMs.toDouble()).toInt().coerceIn(RateController.MIN_SETTLE, RateController.MAX_SETTLE),
                regBacklashMs = dbl("zawor_luz", d.regBacklashMs.toDouble()).toInt().coerceIn(0, RateController.MAX_BACKLASH),
            )
        }.getOrDefault(d)
    }
}

/**
 * Opis oprysku do raportu pola (GeoJSON) i paczki pracy WAR (pole `oprysk`, docs/WAR.md):
 * ciecz, zużyte litry, dawka rzeczywista (litry / obrobione ha) i zadana, przy RSM także kg/ha, dysza.
 * Null, gdy na polu nie zużyto cieczy.
 */
fun sprayReportJson(c: SprayerConfig, usedL: Double, workedHa: Double): String? {
    if (usedL < 0.5 || workedHa < 0.001) return null
    fun r(v: Double, d: Int): String {
        val m = if (d == 0) 1.0 else if (d == 1) 10.0 else 100.0
        val x = kotlin.math.round(v * m) / m
        return if (d == 0) x.toLong().toString() else x.toString()
    }
    val perHa = usedL / workedHa
    fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return "{\"ciecz\":${q(c.liquid.label)},\"ciecz_l\":${r(usedL, 1)},\"dawka_lha\":${r(perHa, 1)}," +
        "\"dawka_zadana_lha\":${r(c.targetLha, 0)}" +
        (if (c.liquid.density > 1.0) ",\"dawka_kgha\":${r(perHa * c.liquid.density, 1)}" else "") +
        ",\"dysza\":${q("${c.nozzle.name} ${c.nozzle.color}")},\"rodzaj_dyszy\":${q(c.nozzleKind.label)}}"
}
