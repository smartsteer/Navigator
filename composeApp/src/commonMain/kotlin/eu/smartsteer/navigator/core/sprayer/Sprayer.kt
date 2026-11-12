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

/** Skąd brać przepływ do regulacji. */
enum class FlowMode(val label: String) {
    PRESSURE("Przepływ z ciśnienia (tabela dysz)"),
    PRESSURE_AND_FLOWMETER("Ciśnienie + przepływomierz"),
    FLOWMETER("Sam przepływomierz"),
}

/** Ustawienia opryskiwacza. */
data class SprayerConfig(
    /** Liczba dysz w każdej sekcji (rozmiar listy = liczba sekcji 1–9). */
    val nozzlesPerSection: List<Int> = List(7) { 6 },
    /** Rozstaw dysz, m. */
    val nozzleSpacingM: Double = 0.5,
    val nozzle: Nozzle = Nozzle.ISO[4],
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
    /** Pojemność zbiornika, l. */
    val tankCapacityL: Double = 1000.0,
    /** Próg rezerwy, l. */
    val tankReserveL: Double = 100.0,
) {
    init {
        require(nozzlesPerSection.size in 1..SprayerPgn.MAX_SECTIONS) { "Liczba sekcji 1–9" }
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

    /** Ciśnienie, przy którym dysza da dawkę przy danej prędkości (do podpowiedzi i alarmu). */
    fun pressureFor(speedKmh: Double, rateLha: Double = targetLha) =
        nozzle.pressureFor(rateLha * speedKmh * nozzleSpacingM / 600.0)
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

/**
 * Regulator dawki — impulsy silnika zaworu regulacyjnego.
 *
 * 1. Cel liczony od razu z prędkości i włączonych sekcji (wyprzedzenie), regulator dostaje gotowy przepływ docelowy.
 * 2. Impuls o długości z wyuczonego wzmocnienia zaworu (l/min na ms pracy silnika, odniesione do pełnej szerokości):
 *    daleko od celu = długi impuls, blisko = krótki; strefa martwa ±[SprayerConfig.deadband].
 * 3. Po impulsie przerwa na ustalenie się ciśnienia, potem pomiar i uczenie wzmocnienia (średnia ruchoma, w granicach).
 * 4. Zmiana sekcji / zatrzymanie: pomiar w trakcie zmiany nie uczy, regulacja czeka na ustalenie; na postoju zawór stoi.
 */
class RateController(
    nominalFlowLpm: Double,
    valveTravelMs: Int,
    val aggressiveness: Double = 0.7,
    val minPulseMs: Int = 15,
    val maxPulseMs: Int = 1000,
    val settleMs: Int = 450,
) {
    /** Wyuczone wzmocnienie: zmiana przepływu (l/min przy pełnej szerokości) na ms pracy silnika. */
    var gain = nominalFlowLpm / valveTravelMs; private set
    private val gainMin = gain / 8
    private val gainMax = gain * 8

    private var waitMs = 0
    private var lastPulse = 0
    private var flowBefore = 0.0
    private var openFracAtPulse = 1.0
    private var lastMask = -1

    /**
     * Jeden krok co [dtMs]. Zwraca impuls zaworu w ms (+ więcej cieczy, − mniej), 0 = nic.
     * [openFraction] — udział otwartych dysz w całej belce (skaluje wzmocnienie).
     */
    fun step(dtMs: Int, active: Boolean, targetLpm: Double, measuredLpm: Double, sectionMask: Int, openFraction: Double, deadband: Double): Int {
        if (sectionMask != lastMask) { lastMask = sectionMask; waitMs = maxOf(waitMs, settleMs); lastPulse = 0 }
        if (!active || targetLpm <= 0 || openFraction <= 0) { waitMs = settleMs; lastPulse = 0; return 0 }
        if (waitMs > 0) { waitMs -= dtMs; if (waitMs > 0) return 0 }

        // uczenie z poprzedniego impulsu
        if (lastPulse != 0 && abs(lastPulse) >= 40 && abs(openFraction - openFracAtPulse) < 1e-6) {
            val observed = (measuredLpm - flowBefore) / openFraction / lastPulse
            if (observed > 0) gain = (gain * 0.6 + observed * 0.4).coerceIn(gainMin, gainMax)
        }
        lastPulse = 0

        val err = targetLpm - measuredLpm
        if (abs(err) <= deadband * targetLpm) return 0
        val ms = (abs(err) / (gain * openFraction) * aggressiveness).toInt().coerceIn(minPulseMs, maxPulseMs)
        val pulse = (ms * sign(err)).toInt()
        lastPulse = pulse
        flowBefore = measuredLpm
        openFracAtPulse = openFraction
        waitMs = abs(pulse) + settleMs
        return pulse
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
    )

    fun reconfigure(c: SprayerConfig) {
        val relearn = c.nozzle != config.nozzle || c.nozzlesPerSection != config.nozzlesPerSection
        config = c
        flowMeter.pulsesPerLiter = c.pulsesPerLiter
        tank.capacityL = c.tankCapacityL
        if (relearn) controller = newController()
    }

    /** Przepływ użyty do regulacji wg wybranego trybu. */
    fun measuredFlow(status: SprayerStatus?, sectionMask: Int): Double {
        val p = status?.pressureBar
        val fromPressure = if (p == null || status.mainOpen.not()) 0.0 else config.openNozzles(sectionMask) * config.nozzle.flowAt(p)
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
        pressureBar = status?.pressureBar
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
    fun rateOk() = targetLpm <= 0 || abs(flowLpm - targetLpm) <= 0.1 * targetLpm
}
