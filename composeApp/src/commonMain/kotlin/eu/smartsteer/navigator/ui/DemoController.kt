package eu.smartsteer.navigator.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.smartsteer.navigator.core.sim.DemoConfig
import eu.smartsteer.navigator.core.sim.DemoSession

/**
 * Łączy symulację z interfejsem. [frame] rośnie co klatkę — ekrany, które go czytają,
 * odświeżają się na bieżąco.
 */
class DemoController {
    var session by mutableStateOf(DemoSession())
        private set
    var frame by mutableStateOf(0L)
        private set
    var paused by mutableStateOf(false)
    var headingUp by mutableStateOf(true) // jak w AgOpenGPS: obraca się pole, ciągnik patrzy w górę
    var zoom by mutableStateOf(20f) // piksele na metr (przed skalą ekranu)

    fun advance(dtSeconds: Double) {
        if (!paused) session.step(dtSeconds.coerceAtMost(0.25))
        frame++
    }

    fun rebuild(config: DemoConfig) {
        val keep = session
        session = DemoSession(config).also {
            it.targetSpeedKmh = keep.targetSpeedKmh
            it.lookaheadSeconds = keep.lookaheadSeconds
            it.timeScale = keep.timeScale
        }
        frame++
    }

    fun restart() = rebuild(session.config)

    /** Wywołanie po zmianie ustawień z UI, żeby od razu było widać efekt. */
    fun touch() { frame++ }
}
