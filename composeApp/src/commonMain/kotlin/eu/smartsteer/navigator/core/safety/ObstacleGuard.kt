package eu.smartsteer.navigator.core.safety

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Czujnik przeszkód (docs/PRZESZKODY.md): kamera z pomiarem odległości (np. OAK-D) przez mały program pośredni
 * wysyła do Navigatora co ~0,1 s listę przeszkód przed maszyną (UDP, JSON). Navigator liczy strefę stopu —
 * szerokość maszyny z zapasem, długość wg prędkości (reakcja + droga hamowania) — i gdy coś w niej jest:
 * wyłącza autoster, alarm, ramka STOP dla modułu bezpieczeństwa. Brak danych z czujnika = nie wolno prowadzić.
 *
 * Autoster tylko skręca kołami — sam nie zatrzyma ciągnika. Do zatrzymania potrzebny moduł z przekaźnikiem,
 * który reaguje na ramkę STOP (PGN 225) albo na jej brak.
 */

/** Przeszkoda w układzie maszyny: [forwardM] przed anteną/przodem, [lateralM] w prawo (+) / w lewo (−). */
data class Obstacle(val forwardM: Double, val lateralM: Double, val kind: String = "", val confidence: Double = 1.0)

/** Jedna paczka z czujnika. [sensorOk] = czujnik działa (false — np. zasłonięta kamera, błąd). */
data class ObstacleFrame(val atS: Double, val obstacles: List<Obstacle>, val sensorOk: Boolean, val source: String = "")

object ObstacleProtocol {
    const val DEFAULT_PORT = 17777

    /** {"v":1,"zrodlo":"oak-przod","ok":true,"przeszkody":[{"z":6.2,"x":-0.4,"klasa":"osoba","p":0.83}]} */
    fun parse(text: String, nowS: Double): ObstacleFrame? {
        val m = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
        val list = (m["przeszkody"] as? List<*>).orEmpty().mapNotNull { o ->
            val x = o as? Map<*, *> ?: return@mapNotNull null
            val z = (x["z"] as? Number)?.toDouble() ?: return@mapNotNull null
            if (z.isNaN() || z < 0 || z > 200) return@mapNotNull null
            Obstacle(z, (x["x"] as? Number)?.toDouble() ?: 0.0, (x["klasa"] as? String).orEmpty(), ((x["p"] as? Number)?.toDouble() ?: 1.0).coerceIn(0.0, 1.0))
        }.take(64)
        return ObstacleFrame(nowS, list, m["ok"] as? Boolean ?: true, (m["zrodlo"] as? String).orEmpty().take(40))
    }
}

data class ObstacleConfig(
    val enabled: Boolean = false,
    val port: Int = ObstacleProtocol.DEFAULT_PORT,
    /** Najkrótsza strefa stopu (m) — także na postoju. */
    val minStopM: Double = 4.0,
    /** Czas reakcji: od wykrycia do zadziałania hamulca/stopu (s). */
    val reactionS: Double = 0.8,
    /** Opóźnienie hamowania (m/s²) — ostrożnie, w polu ok. 2. */
    val decelMps2: Double = 2.0,
    /** Zapas z każdej strony szerokości maszyny (m). */
    val sideMarginM: Double = 0.7,
    /** Najmniejsza pewność wykrycia, która się liczy. */
    val minConfidence: Double = 0.5,
    /** Po ilu sekundach bez danych czujnik uznajemy za martwy. */
    val sensorTimeoutS: Double = 1.0,
) {
    fun toJson(): String = buildString {
        append("{\"enabled\":$enabled,\"port\":$port,\"minStopM\":$minStopM,\"reactionS\":$reactionS,\"decelMps2\":$decelMps2,")
        append("\"sideMarginM\":$sideMarginM,\"minConfidence\":$minConfidence,\"sensorTimeoutS\":$sensorTimeoutS}")
    }

    companion object {
        fun fromJson(text: String): ObstacleConfig? {
            val m = runCatching { MiniJson.parse(text) }.getOrNull() as? Map<*, *> ?: return null
            val d = ObstacleConfig()
            fun n(k: String, def: Double) = (m[k] as? Number)?.toDouble() ?: def
            return ObstacleConfig(
                enabled = m["enabled"] as? Boolean ?: d.enabled,
                port = ((m["port"] as? Number)?.toInt() ?: d.port).coerceIn(1024, 65535),
                minStopM = n("minStopM", d.minStopM).coerceIn(1.0, 50.0),
                reactionS = n("reactionS", d.reactionS).coerceIn(0.1, 5.0),
                decelMps2 = n("decelMps2", d.decelMps2).coerceIn(0.5, 8.0),
                sideMarginM = n("sideMarginM", d.sideMarginM).coerceIn(0.0, 5.0),
                minConfidence = n("minConfidence", d.minConfidence).coerceIn(0.05, 1.0),
                sensorTimeoutS = n("sensorTimeoutS", d.sensorTimeoutS).coerceIn(0.2, 10.0),
            )
        }
    }
}

/** Werdykt: 0 = czysto, 1 = ostrzeżenie (strefa ×2), 2 = STOP; [message] dla rolnika, [nearest] najbliższa w strefie. */
data class ObstacleVerdict(val level: Int, val message: String?, val nearest: Obstacle?, val stopLengthM: Double, val halfWidthM: Double, val sensorAlive: Boolean)

class ObstacleGuard(var config: ObstacleConfig = ObstacleConfig()) {
    /** Długość strefy stopu dla prędkości: droga w czasie reakcji + droga hamowania + 1 m zapasu, nie mniej niż minimum. */
    fun stopLength(speedKmh: Double): Double {
        val v = max(0.0, speedKmh) / 3.6
        return max(config.minStopM, v * config.reactionS + v * v / (2 * config.decelMps2) + 1.0)
    }

    fun sensorAlive(frame: ObstacleFrame?, nowS: Double) = frame != null && frame.sensorOk && nowS - frame.atS <= config.sensorTimeoutS

    fun check(frame: ObstacleFrame?, nowS: Double, speedKmh: Double, machineWidthM: Double): ObstacleVerdict {
        val len = stopLength(speedKmh)
        val half = machineWidthM / 2 + config.sideMarginM
        if (!sensorAlive(frame, nowS)) {
            val why = if (frame == null) "Czujnik przeszkód nie nadaje" else if (!frame.sensorOk) "Czujnik przeszkód zgłasza błąd (zasłonięty, brudny?)" else "Czujnik przeszkód nie odpowiada od ${(nowS - frame.atS).roundToInt()} s"
            return ObstacleVerdict(2, why, null, len, half, false)
        }
        val inLane = frame!!.obstacles.filter { it.confidence >= config.minConfidence && abs(it.lateralM) <= half }.minByOrNull { it.forwardM }
            ?: return ObstacleVerdict(0, null, null, len, half, true)
        val what = inLane.kind.ifEmpty { "przeszkoda" }
        val d = (inLane.forwardM * 10).roundToInt() / 10.0
        return when {
            inLane.forwardM <= len -> ObstacleVerdict(2, "STOP — $what ${fmt(d)} m przed maszyną", inLane, len, half, true)
            inLane.forwardM <= len * 2 -> ObstacleVerdict(1, "Uwaga — $what ${fmt(d)} m przed maszyną", inLane, len, half, true)
            else -> ObstacleVerdict(0, null, inLane, len, half, true)
        }
    }

    companion object {
        /** Własny PGN WAR Navigatora dla modułu bezpieczeństwa (przekaźnik stopu). */
        const val PGN_SAFETY = 0xE1

        /**
         * PGN 225 co 100 ms, gdy czujnik włączony: bajt 0 = 0 czysto / 1 uwaga / 2 STOP, 1–2 = odległość do najbliższej
         * przeszkody w strefie ×10 (dm, 0xFFFF = brak), 3 = 1 gdy czujnik żyje. Moduł powinien zatrzymać maszynę przy
         * STOP oraz gdy przez 0,5 s nie dostanie żadnej ramki (zerwany kabel, zawieszony program).
         */
        fun frame(v: ObstacleVerdict): ByteArray {
            val dm = v.nearest?.let { (it.forwardM * 10).roundToInt().coerceIn(0, 0xFFFE) } ?: 0xFFFF
            val d = byteArrayOf(v.level.toByte(), (dm and 0xFF).toByte(), (dm shr 8).toByte(), (if (v.sensorAlive) 1 else 0).toByte(), 0, 0, 0, 0)
            return AogFrame.build(PGN_SAFETY, d)
        }

        private fun fmt(d: Double) = d.toString().replace('.', ',')
    }
}

/** Odbiór paczek z czujnika przeszkód (UDP na wszystkich kartach — czujnik może być na innym komputerze w kabinie). */
interface ObstacleReceiver {
    /** Zwraca opis błędu (np. port zajęty) albo null. [onText] z osobnego wątku. */
    fun start(port: Int, onText: (String) -> Unit): String?
    fun stop()
}

expect fun createObstacleReceiver(): ObstacleReceiver
