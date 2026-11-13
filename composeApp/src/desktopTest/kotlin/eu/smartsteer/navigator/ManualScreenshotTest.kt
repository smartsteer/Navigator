package eu.smartsteer.navigator

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.hw.AbLine
import eu.smartsteer.navigator.core.sim.SimReceiver
import eu.smartsteer.navigator.core.war.WarProtocol
import eu.smartsteer.navigator.ui.HardwareController
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Zrzuty ekranów do instrukcji obsługi (docs/instrukcja): prawdziwe ekrany programu z symulowaną jazdą po polu,
 * połączeniem z WAR (kod nawigacji), flotą gospodarstwa i wiadomością głosową. Zapis: build/screenshots/instrukcja.
 * Test nigdy nie przerywa budowy (błąd ląduje w pliku blad.txt).
 */
class ManualScreenshotTest {
    @OptIn(ExperimentalComposeUiApi::class)
    private fun shot(dir: File, name: String, frames: Int = 6, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(1280, 800, Density(1f), content = content)
        try {
            var img = scene.render(0)
            for (k in 1..frames) img = scene.render(k * 100_000_000L)
            img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, "$name.png").writeBytes(it) }
        } finally {
            scene.close()
        }
    }

    @Test
    fun manualScreenshots() {
        val dir = File("build/screenshots/instrukcja").apply { mkdirs() }
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

            // połączenie z WAR (kod nawigacji gospodarstwa), dwie inne maszyny i lokalizator WAR Track, wiadomość od gospodarza
            hw.war.update(hw.war.config.copy(code = "ABCD-EFGH-JKLM-NPQR", tractorName = "Renault 133.14", operator = "Marek"))
            val pl = s.plane!!
            fun geo(x: Double, y: Double) = pl.toGeo(Vec2(x, y))
            val (la1, lo1) = geo(-36.0, 120.0)
            val (la2, lo2) = geo(30.0, 60.0)
            val now = System.currentTimeMillis()
            hw.war.setFleetForScreenshots(listOf(
                WarProtocol.FleetMachine("tab-zetor", "Zetor 12045", "Agregat 4 m", 4.0, la1, lo1, 0.0, 8.5, true, null, "Za stodołą", "Janek", now, "#FF8C1A"),
                WarProtocol.FleetMachine("trk-ursus", "Ursus C-360", "", 0.0, la2, lo2, 180.0, 12.0, true, null, "", "", now, tracker = true),
            ))
            hw.war.addVoiceForScreenshots("Szef")

            // tryb „tylko nawigacja” — mapa bez opryskiwacza
            hw.updateWorkMode(1)
            shot(dir, "01_mapa") { App(hw, Screen.Map) }
            shot(dir, "02_pola") { App(hw, Screen.Fields) }
            shot(dir, "03_sterowanie") { App(hw, Screen.Steer) }
            shot(dir, "04_maszyna") { App(hw, Screen.Implement) }
            shot(dir, "05_polaczenia") { App(hw, Screen.Connections) }
            for ((tab, name) in listOf(0 to "06_ust_ciagnik", 1 to "07_ust_maszyna", 2 to "08_ust_prowadzenie", 5 to "09_ust_nadzor", 7 to "10_ust_ntrip", 8 to "11_ust_war")) {
                shot(dir, name, 4) { App(hw, Screen.Settings, tab) }
            }
            // nawigacja + opryskiwacz
            hw.updateWorkMode(0)
            hw.updateProfile(hw.sprayer.applyTo(hw.profile))
            hw.sprayer.setTank(0.0); hw.sprayer.fill(850.0)
            hw.sprayer.toggleRun()
            repeat(80) { sim.step(0.1, if (s.engaged) s.steerSetDeg else null); t += 0.1; feed(); hw.sprayer.step(100, s.speedKmh) { true } }
            shot(dir, "12_mapa_oprysk") { App(hw, Screen.Map) }
            shot(dir, "13_oprysk") { App(hw, Screen.Sprayer) }
            hw.setHardwareMode(false)
        } catch (e: Throwable) {
            File(dir, "blad.txt").writeText(e.stackTraceToString())
        }
    }
}
