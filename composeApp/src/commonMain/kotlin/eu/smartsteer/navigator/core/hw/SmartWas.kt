package eu.smartsteer.navigator.core.hw

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Smart WAS — zero czujnika kąta kół z jazdy z autosterem: zbiera kąty zadane na prostej i z ich mediany
 * podpowiada poprawkę zera (gdy przy jeździe prosto kąt zadany stale jest np. +1,2°, zero jest przesunięte).
 *
 * Wierne przeniesienie z AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Classes/CSmartWAS.cs
 * i przycisk „Smart WAS Zero” z SourceCode/GPS/Forms/Settings/FormSteer.cs.
 */
class SmartWas {
    private val history = ArrayList<Double>()

    var collecting = false; private set
    val sampleCount: Int get() = history.size
    var mean = 0.0; private set
    var median = 0.0; private set
    var stdDev = 0.0; private set
    var recommendedOffset = 0.0; private set
    var confidence = 0.0; private set
    var hasValidCalibration = false; private set

    fun start() { collecting = true }
    fun stop() { collecting = false }

    fun reset() {
        history.clear()
        mean = 0.0; median = 0.0; stdDev = 0.0; recommendedOffset = 0.0; confidence = 0.0; hasValidCalibration = false
    }

    /** Po zmianie zera: przesuwa zebrane próbki, żeby nie poprawić dwa razy. */
    fun applyOffsetCorrection(offsetDeg: Double) {
        if (history.isEmpty()) return
        for (i in history.indices) history[i] += offsetDeg
        analyze()
    }

    /** Próbka kąta zadanego (co krok prowadzenia). */
    fun addSample(steerAngleDeg: Double, engaged: Boolean, speedKmh: Double, xteMm: Double, wasInverted: Boolean) {
        if (!collecting || !engaged) return
        if (speedKmh < MIN_SPEED_KMH) return
        if (abs(xteMm) > MAX_DIST_OFF_MM) return
        if (abs(steerAngleDeg) > MAX_ANGLE_DEG) return
        val a = if (wasInverted) -steerAngleDeg else steerAngleDeg
        history.add(a)
        if (history.size > MAX_SAMPLES) history.removeAt(0)
        if (history.size >= MIN_SAMPLES) analyze()
    }

    /** Poprawka w impulsach czujnika; 0 = brak pewnej kalibracji. */
    fun offsetCounts(countsPerDegree: Int): Int = if (!hasValidCalibration) 0 else (recommendedOffset * countsPerDegree).roundToInt()

    private fun analyze() {
        if (history.size < MIN_SAMPLES) { hasValidCalibration = false; return }
        statistics()
        recommendedOffset = -median
        confidence = calcConfidence()
        hasValidCalibration = confidence > 40 && abs(recommendedOffset) < 10.0
    }

    private fun statistics() {
        val n = history.size
        mean = history.average()
        val sorted = history.sorted()
        median = if (n % 2 == 0) (sorted[n / 2 - 1] + sorted[n / 2]) * 0.5 else sorted[n / 2]
        stdDev = if (n > 1) sqrt(history.sumOf { (it - mean) * (it - mean) } / (n - 1)) else 0.0
    }

    private fun calcConfidence(): Double {
        if (history.size < MIN_SAMPLES) return 0.0
        var w1 = 0
        var w2 = 0
        for (a in history) {
            val d = abs(a - median)
            if (d <= stdDev) w1++
            if (d <= 2 * stdDev) w2++
        }
        val p1 = w1.toDouble() / history.size
        val p2 = w2.toDouble() / history.size
        val s1 = 1 - abs(p1 - 0.68) / 0.68
        val s2 = 1 - abs(p2 - 0.95) / 0.95
        val mag = max(0.0, 1 - abs(recommendedOffset) / 10.0)
        val size = min(1.0, history.size.toDouble() / (MIN_SAMPLES * 3))
        val c = (s1 * 0.3 + s2 * 0.3 + mag * 0.2 + size * 0.2) * 100
        return max(0.0, min(100.0, c))
    }

    companion object {
        const val MAX_SAMPLES = 2000
        const val MIN_SAMPLES = 200
        const val MIN_SPEED_KMH = 2.0
        const val MAX_ANGLE_DEG = 15.0
        const val MAX_DIST_OFF_MM = 500.0
        /** „Smart WAS Zero”: większa poprawka niż tyle impulsów jest odrzucana. */
        const val MAX_APPLY_COUNTS = 50
    }
}
