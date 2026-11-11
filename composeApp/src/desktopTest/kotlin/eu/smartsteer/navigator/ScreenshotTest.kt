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
            // podkład: sprawdzenie adresów kafelków (wynik w kafelki.txt) i zrzuty mapy z podkładem
            val (lat, lon) = s.plane!!.toGeo(s.pivot!!)
            val z = 18
            val tx = eu.smartsteer.navigator.core.map.TileMath.tileX(lon, z).toInt()
            val ty = eu.smartsteer.navigator.core.map.TileMath.tileY(lat, z).toInt()
            val geo = "https://mapy.geoportal.gov.pl/wss/service/PZGIK/ORTO/WMTS/"
            val variants = listOf(
                "HighRes 3857" to geo + "HighResolution?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTOFOTOMAPA&STYLE=default&FORMAT=image/jpeg&TILEMATRIXSET=EPSG:3857&TILEMATRIX=EPSG:3857:{z}&TILEROW={y}&TILECOL={x}",
                "StdRes 3857" to geo + "StandardResolution?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTOFOTOMAPA&STYLE=default&FORMAT=image/jpeg&TILEMATRIXSET=EPSG:3857&TILEMATRIX=EPSG:3857:{z}&TILEROW={y}&TILECOL={x}",
                "HighRes GoogleMaps" to geo + "HighResolution?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTOFOTOMAPA&STYLE=default&FORMAT=image/jpeg&TILEMATRIXSET=GoogleMapsCompatible&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
            )
            val report = StringBuilder("kafelek z=$z x=$tx y=$ty\n")
            var orto: String? = null
            for ((name, tpl) in variants) {
                val url = tpl.replace("{z}", "$z").replace("{x}", "$tx").replace("{y}", "$ty")
                val b = eu.smartsteer.navigator.core.map.TileIo.download(url)
                report.append("$name: ${b?.size ?: "BŁĄD"}\n")
                if (b != null && orto == null) orto = tpl
            }
            val esri = eu.smartsteer.navigator.core.map.TileIo.download(eu.smartsteer.navigator.core.map.TileSource.ESRI.url(z, tx, ty)!!)
            report.append("Esri: ${esri?.size ?: "BŁĄD"}\n")
            if (orto == null) {
                // pełna odpowiedź pierwszego wariantu do diagnozy
                runCatching {
                    val c = java.net.URL(variants[0].second.replace("{z}", "$z").replace("{x}", "$tx").replace("{y}", "$ty")).openConnection() as java.net.HttpURLConnection
                    report.append("HTTP ${c.responseCode} ${c.contentType}\n")
                    report.append((if (c.responseCode >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString().take(1500) })
                }
                runCatching {
                    val c = java.net.URL(geo + "HighResolution?SERVICE=WMTS&REQUEST=GetCapabilities").openConnection() as java.net.HttpURLConnection
                    val t = c.inputStream.use { it.readBytes().decodeToString() }
                    report.append("\n--- capabilities ---\n")
                    Regex("<ows:Identifier>[^<]+</ows:Identifier>").findAll(t).take(60).forEach { report.append(it.value).append('\n') }
                    Regex("template=\"[^\"]+\"").findAll(t).take(5).forEach { report.append(it.value).append('\n') }
                }
            }
            File(dir, "kafelki.txt").writeText(report.toString())
            orto?.let { eu.smartsteer.navigator.core.map.TileSource.ORTO_URL = it }
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
