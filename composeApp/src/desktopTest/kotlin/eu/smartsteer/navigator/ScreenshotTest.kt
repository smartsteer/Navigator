package eu.smartsteer.navigator

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import eu.smartsteer.navigator.core.sim.SimReceiver
import eu.smartsteer.navigator.ui.HardwareController
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Zrzuty ekranów (tryb Sprzęt z symulowaną jazdą) do podglądu wyglądu — zapisywane w composeApp/build/screenshots,
 * w CI dostępne jako artefakt „zrzuty-ekranu”. Test nigdy nie przerywa budowy (błąd ląduje w pliku blad.txt).
 */
class ScreenshotTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun screenshots() {
        val dir = File("build/screenshots").apply { mkdirs() }
        try {
            val hw = HardwareController()
            hw.setHardwareMode(true)
            val s = hw.session
            val sim = SimReceiver()
            sim.targetSpeedKmh = 9.0
            var t = hw.now()
            fun feed() {
                s.onNmea(sim.vtg(), t); s.onNmea(sim.gga(), t)
                val f = sim.pgn253(); s.onDatagram(f, f.size, t)
                s.update(t)
            }
            feed()
            s.restoreFieldForTest(listOf(Vec2(-60.0, -20.0), Vec2(60.0, -20.0), Vec2(60.0, 300.0), Vec2(-60.0, 300.0)))
            s.restoreLine(AbLine(Vec2(0.5, -20.0), Vec2(0.5, 300.0)))
            repeat(30) { sim.step(0.1, null); t += 0.1; feed() }
            s.setEngaged(true, t)
            repeat(350) { sim.step(0.1, if (s.engaged) s.steerSetDeg else null); t += 0.1; feed() }
            // lekki zjazd z linii, żeby lightbar coś pokazywał
            repeat(6) { sim.step(0.1, 6.0); t += 0.1; feed() }

            for (screen in listOf(Screen.Map, Screen.Steer, Screen.Implement, Screen.Connections, Screen.Settings)) {
                val scene = ImageComposeScene(1280, 800, Density(1f)) { App(hw, screen) }
                try {
                    var img = scene.render(0)
                    for (k in 1..6) img = scene.render(k * 100_000_000L)
                    img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, "${screen.name.lowercase()}.png").writeBytes(it) }
                } finally {
                    scene.close()
                }
            }
            hw.setHardwareMode(false)
        } catch (e: Throwable) {
            File(dir, "blad.txt").writeText(e.stackTraceToString())
        }
    }
}
