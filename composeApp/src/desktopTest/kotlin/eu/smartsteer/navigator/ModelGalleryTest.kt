package eu.smartsteer.navigator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.ui.MachineModels
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test

/**
 * Galeria modeli maszyn (build/screenshots/modele): każdy model z kilku stron w prostej perspektywie — do oglądania
 * wyglądu bez jazdy po polu. Nie przerywa budowy.
 */
class ModelGalleryTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun gallery() {
        val dir = File("build/screenshots/modele").apply { mkdirs() }
        runCatching {
            val kinds = listOf(
                Triple("kombajn", MachineModels.Kind.COMBINE, Color(0xFF2E8B3E)),
                Triple("ciagnik", MachineModels.Kind.TRACTOR, Color(0xFFD8322B)),
                Triple("opryskiwacz", MachineModels.Kind.SPRAYER, Color(0xFFFF8C1A)),
                Triple("siewnik", MachineModels.Kind.SEEDER, Color(0xFF2F6BD8)),
            )
            for ((name, kind, color) in kinds) {
                val scene = ImageComposeScene(1600, 500, Density(1f)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color(0xFF7FA05A))
                        // cztery ujęcia: z przodu-boku, z boku, z tyłu-boku, z przodu
                        val views = listOf(35.0, 90.0, 150.0, 0.0)
                        for ((i, yawDeg) in views.withIndex()) {
                            val cx = size.width * (i + 0.5f) / views.size
                            val cy = size.height * 0.62f
                            val cam = 26.0; val elev = 22.0 * PI / 180
                            val sc = 26f
                            val yaw = yawDeg * PI / 180
                            // kamera patrzy na (0,0,1.5) z odległości cam, pod kątem elev, z kierunku yaw (0 = z przodu maszyny)
                            fun proj(p: Vec2, z: Double): Pair<Float, Offset>? {
                                // obrót świata tak, by kamera była na osi −y patrząc w +y
                                val a = PI - yaw
                                val x = p.x * cos(a) - p.y * sin(a)
                                val y = p.x * sin(a) + p.y * cos(a)
                                val zz = z - 1.5
                                // kamera nad ziemią patrzy w dół: dalsze punkty wyżej na ekranie, wyższe bliżej kamery
                                val depth = y * cos(elev) - zz * sin(elev) + cam
                                val up = y * sin(elev) + zz * cos(elev)
                                if (depth < 1) return null
                                val k = (cam / depth).toFloat() * sc
                                return depth.toFloat() to Offset(cx + x.toFloat() * k, cy - up.toFloat() * k)
                            }
                            with(MachineModels) {
                                drawMachine({ p, z -> proj(p, z) }, Vec2(0.0, if (kind == MachineModels.Kind.TRACTOR) -1.2 else 0.0), 0.0, kind, color, 6.0, true, Color.White)
                            }
                        }
                    }
                }
                try {
                    scene.render(0).encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(dir, "$name.png").writeBytes(it) }
                } finally { scene.close() }
            }
        }.onFailure { File(dir, "blad.txt").writeText(it.stackTraceToString()) }
    }
}
