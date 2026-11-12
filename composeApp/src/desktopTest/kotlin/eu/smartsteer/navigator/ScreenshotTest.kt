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
import androidx.compose.foundation.background
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
            // opryskiwacz: sekcje z opryskiwacza, pełny zbiornik, START, kilka sekund pracy na symulatorze
            hw.updateProfile(hw.sprayer.applyTo(hw.profile))
            hw.sprayer.setTank(0.0); hw.sprayer.fill(850.0)
            hw.sprayer.toggleRun()
            hw.sprayer.cycleOverride(6); hw.sprayer.cycleOverride(6)
            repeat(80) { sim.step(0.1, if (s.engaged) s.steerSetDeg else null); t += 0.1; feed(); hw.sprayer.step(100, s.speedKmh) { true } }

            for (screen in listOf(Screen.Map, Screen.Steer, Screen.Implement, Screen.Sprayer, Screen.Connections, Screen.Settings)) {
                val scene = ImageComposeScene(1280, 800, Density(1f)) { App(hw, screen) }
                try {
                    var img = scene.render(0)
                    for (k in 1..6) img = scene.render(k * 100_000_000L)
                    img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, "${screen.name.lowercase()}.png").writeBytes(it) }
                } finally {
                    scene.close()
                }
            }
            run {
                val scene = ImageComposeScene(1280, 800, Density(1f)) {
                    eu.smartsteer.navigator.ui.theme.SmartSteerTheme {
                        androidx.compose.foundation.layout.Box(
                            androidx.compose.ui.Modifier.background(eu.smartsteer.navigator.ui.theme.Ss.Ground),
                        ) { eu.smartsteer.navigator.ui.screens.SprayerScreen(hw, startInSettings = true) }
                    }
                }
                try {
                    var img = scene.render(0)
                    for (k in 1..3) img = scene.render(k * 100_000_000L)
                    img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, "sprayer_ustawienia.png").writeBytes(it) }
                } finally { scene.close() }
            }
            // podkład: sprawdzenie adresów kafelków (wynik w kafelki.txt) i zrzuty mapy z podkładem
            val (lat, lon) = s.plane!!.toGeo(s.pivot!!)
            val z = 18
            val tx = eu.smartsteer.navigator.core.map.TileMath.tileX(lon, z).toInt()
            val ty = eu.smartsteer.navigator.core.map.TileMath.tileY(lat, z).toInt()
            val report = StringBuilder("kafelek z=$z x=$tx y=$ty\n")
            for (src in listOf(eu.smartsteer.navigator.core.map.TileSource.ORTO_PL, eu.smartsteer.navigator.core.map.TileSource.ESRI)) {
                val b = eu.smartsteer.navigator.core.map.TileIo.download(src.url(z, tx, ty)!!)
                report.append("${src.name}: ${b?.size ?: "BŁĄD"}\n")
            }
            File(dir, "kafelki.txt").writeText(report.toString())
            for ((i, name) in listOf(1 to "map_orto", 2 to "map_sat")) {
                eu.smartsteer.navigator.ui.DisplayPrefs.updateBackground(i)
                val scene = ImageComposeScene(1280, 800, Density(1f)) { App(hw, Screen.Map) }
                try {
                    scene.render(0)
                    Thread.sleep(6000)
                    var img = scene.render(1_000_000_000L)
                    Thread.sleep(1000)
                    img = scene.render(2_000_000_000L)
                    img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, "$name.png").writeBytes(it) }
                } finally { scene.close() }
            }
            eu.smartsteer.navigator.ui.DisplayPrefs.updateBackground(0)
            hw.setHardwareMode(false)
        } catch (e: Throwable) {
            File(dir, "blad.txt").writeText(e.stackTraceToString())
        }
    }
}
