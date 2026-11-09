package eu.smartsteer.navigator.core.sim

import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.guidance.FieldPlan
import eu.smartsteer.navigator.core.guidance.PurePursuit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

data class DemoConfig(
    val fieldWidth: Double = 72.0,
    val fieldLength: Double = 160.0,
    val implementWidth: Double = 3.0,
    val sections: Int = 6,
    val headland: Double = 9.0,
    val wheelbase: Double = 2.65,
    /** Odległość od tylnej osi do środka maszyny, m. */
    val hitchToImplement: Double = 1.6,
    val maxSteerDeg: Double = 35.0,
    val minWorkSpeedKmh: Double = 1.5,
)

/** Pas obrobiony jedną sekcją: lewa i prawa krawędź. */
class CoverageStrip(val section: Int) {
    val left = ArrayList<Vec2>()
    val right = ArrayList<Vec2>()
}

/**
 * Całe demo w jednym miejscu: pole, ścieżka, symulowany ciągnik, autosterowanie, sekcje i pokrycie.
 * Krok symulacji wywołuje UI (np. 10 razy na sekundę); stąd potem trafią też ramki PGN na płytkę.
 */
class DemoSession(val config: DemoConfig = DemoConfig()) {

    val plan = FieldPlan(
        widthM = config.fieldWidth,
        lengthM = config.fieldLength,
        implementWidth = config.implementWidth,
        requestedHeadland = config.headland,
        minTurnRadius = config.wheelbase / kotlin.math.tan(config.maxSteerDeg * PI / 180.0) * 1.15,
    )

    private val autoPilot = PurePursuit(wheelbase = config.wheelbase, maxSteerDeg = config.maxSteerDeg, lookaheadSeconds = 1.6)
    private val driver = PurePursuit(wheelbase = config.wheelbase, maxSteerDeg = config.maxSteerDeg, lookaheadSeconds = 2.6, minLookahead = 5.0)
    private val rnd = Random(7)

    lateinit var sim: TractorSim
        private set

    // --- sterowanie z UI ---
    var engaged = false
    var targetSpeedKmh = 8.0
    var lineShiftCm = 0
    var autoSections = true
    var autoUturn = true
    var timeScale = 1.0
    val manualSections = BooleanArray(config.sections) { true }

    /** Czas wyprzedzenia autosteru (strojenie na ekranie Sterowanie). */
    var lookaheadSeconds: Double
        get() = autoPilot.lookaheadSeconds
        set(v) { autoPilot.lookaheadSeconds = v }

    // --- wyniki do wyświetlenia ---
    val sectionOn = BooleanArray(config.sections)
    var xteCm = 0.0; private set
    var steerSetDeg = 0.0; private set
    var station = 0.0; private set
    var currentPass = 0; private set
    var goal = Vec2.ZERO; private set
    var workedM2 = 0.0; private set
    var workTimeS = 0.0; private set
    var timeS = 0.0; private set
    var finished = false; private set
    val coverage = ArrayList<CoverageStrip>()
    val historySet = ArrayDeque<Double>()
    val historyActual = ArrayDeque<Double>()

    private val openStrip = arrayOfNulls<CoverageStrip>(config.sections)
    private var gpsNoise = Vec2.ZERO
    private var historyTimer = 0.0

    val speedKmh: Double get() = sim.speedMs * 3.6
    val workRateHaH: Double get() = if (workTimeS < 5) 0.0 else workedM2 / 10_000.0 / (workTimeS / 3600.0)
    val interiorAreaM2: Double get() = config.fieldWidth * (config.fieldLength - 2 * plan.headland)

    init { reset() }

    fun reset() {
        val start = plan.path.pointAt(0.0)
        val h = plan.path.headingAt(0.0)
        // startujemy lekko obok linii, żeby było widać, jak autoster ją łapie
        sim = TractorSim(position = start + Vec2.right(h) * 0.6, heading = h + 4 * PI / 180, wheelbase = config.wheelbase)
        engaged = false
        finished = false
        station = 0.0
        workedM2 = 0.0
        workTimeS = 0.0
        timeS = 0.0
        coverage.clear()
        openStrip.fill(null)
        sectionOn.fill(false)
        historySet.clear()
        historyActual.clear()
    }

    fun step(realDt: Double) {
        val dt = realDt * timeScale
        // przy przyspieszonej symulacji liczymy w małych krokach, żeby model był stabilny
        var left = dt
        while (left > 1e-9) {
            val h = minOf(0.1, left)
            tick(h)
            left -= h
        }
    }

    private fun tick(dt: Double) {
        if (finished) return
        timeS += dt

        // prędkość dąży do zadanej (ok. 1 m/s²)
        val target = targetSpeedKmh / 3.6
        val dv = (target - sim.speedMs).coerceIn(-1.0 * dt, 1.0 * dt)
        sim.speedMs += dv

        // GPS RTK: kilka mm–cm szumu (błądzenie losowe z powrotem do zera)
        gpsNoise = gpsNoise * 0.9 + Vec2(rnd.nextDouble(-1.0, 1.0), rnd.nextDouble(-1.0, 1.0)) * 0.004
        val measured = sim.position + gpsNoise

        val shift = lineShiftCm / 100.0
        val pilot = if (engaged) autoPilot else driver
        val r = pilot.compute(plan.path, measured, sim.heading, sim.speedMs, station, shift)
        station = r.projection.station
        goal = r.goal
        xteCm = r.projection.lateral * 100.0

        val pass = plan.passAt(station)
        if (pass >= 0) currentPass = pass

        steerSetDeg = if (engaged) {
            r.steerDeg
        } else {
            // "kierowca": spóźniony i niedokładny
            r.steerDeg + 1.6 * sin(timeS * 0.45) + 0.8 * sin(timeS * 1.3 + 1.0)
        }

        // koniec przejazdu bez auto-zawrotu: autoster się wyłącza, zawraca kierowca
        if (engaged && !autoUturn && pass < 0 && station > 1.0) engaged = false

        sim.step(dt, steerSetDeg)

        if (station >= plan.path.length - 0.5) {
            finished = true
            sim.speedMs = 0.0
            engaged = false
        }

        updateSections(dt)

        historyTimer += dt
        if (historyTimer >= 0.1) {
            historyTimer = 0.0
            historySet.addLast(steerSetDeg)
            historyActual.addLast(sim.actualSteerDeg)
            while (historySet.size > 200) historySet.removeFirst()
            while (historyActual.size > 200) historyActual.removeFirst()
        }
    }

    /** Środek maszyny i wektor "w prawo" maszyny. */
    fun implementCenter(): Vec2 = sim.position - Vec2.forward(sim.heading) * config.hitchToImplement

    fun sectionEdges(i: Int): Pair<Vec2, Vec2> {
        val ws = config.implementWidth / config.sections
        val off = -config.implementWidth / 2 + ws * i
        val c = implementCenter()
        val right = Vec2.right(sim.heading)
        return Pair(c + right * off, c + right * (off + ws))
    }

    private fun updateSections(dt: Double) {
        val moving = speedKmh >= config.minWorkSpeedKmh
        var anyOn = false
        for (i in 0 until config.sections) {
            val (a, b) = sectionEdges(i)
            val mid = (a + b) * 0.5
            val on = moving && if (autoSections) plan.isInInterior(mid) else manualSections[i]
            sectionOn[i] = on
            if (on) {
                anyOn = true
                val strip = openStrip[i] ?: CoverageStrip(i).also {
                    it.left.add(a); it.right.add(b)
                    coverage.add(it)
                    openStrip[i] = it
                }
                val lastL = strip.left.last()
                if (lastL.distanceTo(a) >= 0.5) {
                    val lastR = strip.right.last()
                    workedM2 += quadArea(lastL, lastR, b, a)
                    strip.left.add(a)
                    strip.right.add(b)
                }
            } else {
                openStrip[i] = null
            }
        }
        if (anyOn) workTimeS += dt
    }

    private fun quadArea(a: Vec2, b: Vec2, c: Vec2, d: Vec2): Double {
        val s = a.x * b.y - b.x * a.y + b.x * c.y - c.x * b.y + c.x * d.y - d.x * c.y + d.x * a.y - a.x * d.y
        return abs(s) / 2.0
    }
}
