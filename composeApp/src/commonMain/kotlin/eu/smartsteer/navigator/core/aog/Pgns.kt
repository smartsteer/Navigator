package eu.smartsteer.navigator.core.aog

import kotlin.math.roundToInt

/** Numery PGN używane przez płytki AgOpenGPS. */
object Pgn {
    const val STEER_DATA = 0xFE        // 254: aplikacja -> moduł skrętu
    const val FROM_AUTOSTEER = 0xFD    // 253: moduł skrętu -> aplikacja
    const val STEER_SETTINGS = 0xFC    // 252
    const val STEER_CONFIG = 0xFB      // 251
    const val MACHINE_DATA = 0xEF      // 239: aplikacja -> moduł maszyny (sekcje)
}

/**
 * PGN 254 — dane sterowania wysyłane ok. 10 razy na sekundę.
 * Skalowanie: prędkość w km/h × 10, kąt zadany w stopniach × 100.
 * Odległość od linii (lightbar): jednostki 2 cm z przesunięciem +127, 255 = autoster wyłączony.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs
 * (wypełnianie p_254: distanceX2).
 */
data class SteerData(
    val speedKmh: Double,
    val autosteerOn: Boolean,
    val steerAngleDeg: Double,
    val lineDistanceCm: Int,
    val sections: Int = 0, // bit 0 = sekcja 1 ... bit 15 = sekcja 16
) {
    fun toFrame(): ByteArray {
        val speed = (speedKmh * 10).roundToInt().coerceIn(0, 0xFFFF)
        val angle = (steerAngleDeg * 100).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val data = ByteArray(8)
        data[0] = (speed and 0xFF).toByte()
        data[1] = (speed shr 8).toByte()
        data[2] = if (autosteerOn) 1 else 0
        data[3] = (angle and 0xFF).toByte()
        data[4] = (angle shr 8).toByte()
        data[5] = lineDistanceByte(autosteerOn, lineDistanceCm).toByte()
        data[6] = (sections and 0xFF).toByte()
        data[7] = ((sections shr 8) and 0xFF).toByte()
        return AogFrame.build(Pgn.STEER_DATA, data)
    }

    companion object {
        fun lineDistanceByte(on: Boolean, cm: Int): Int =
            if (!on) 255 else ((cm * 10) * 0.05).toInt().coerceIn(-127, 127) + 127
    }
}

/**
 * PGN 252 — ustawienia modułu skrętu (wzmocnienie, PWM, czujnik kąta kół).
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/PGN.Designer.cs (CPGN_FC).
 */
data class SteerSettings(
    val gainP: Int, val highPwm: Int, val lowPwm: Int, val minPwm: Int,
    val countsPerDegree: Int, val wasOffset: Int, val ackerman: Int,
) {
    fun toFrame(): ByteArray {
        val d = ByteArray(8)
        d[0] = gainP.coerceIn(0, 255).toByte()
        d[1] = highPwm.coerceIn(0, 255).toByte()
        d[2] = lowPwm.coerceIn(0, 255).toByte()
        d[3] = minPwm.coerceIn(0, 255).toByte()
        d[4] = countsPerDegree.coerceIn(0, 255).toByte()
        val off = wasOffset.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        d[5] = (off and 0xFF).toByte()
        d[6] = ((off shr 8) and 0xFF).toByte()
        d[7] = ackerman.coerceIn(0, 255).toByte()
        return AogFrame.build(Pgn.STEER_SETTINGS, d)
    }
}

/**
 * PGN 251 — konfiguracja płytki (bity ustawień, próg czujnika, minimalna prędkość).
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki SourceCode/GPS/Forms/PGN.Designer.cs (CPGN_FB)
 * i SourceCode/GPS/Forms/Settings/FormSteer.cs.
 */
data class SteerConfig(
    val setting0: Int, val maxPulseCounts: Int, val minSteerSpeedKmh: Double, val setting1: Int,
) {
    fun toFrame(): ByteArray {
        val d = ByteArray(8)
        d[0] = setting0.toByte()
        d[1] = maxPulseCounts.coerceIn(0, 255).toByte()
        d[2] = (minSteerSpeedKmh * 10).toInt().coerceIn(0, 255).toByte()
        d[3] = setting1.toByte()
        d[4] = 0 // prędkość kątowa (stały kontur) — wyłączone
        return AogFrame.build(Pgn.STEER_CONFIG, d)
    }
}

/** PGN 253 — odpowiedź modułu skrętu. */
data class AutosteerStatus(
    val actualAngleDeg: Double,
    val headingDeg: Double?,   // null = brak IMU (9999)
    val rollDeg: Double?,      // null = brak IMU (8888)
    val workSwitch: Boolean,
    val steerSwitch: Boolean,
    val pwm: Int,
) {
    companion object {
        fun from(frame: Frame): AutosteerStatus? {
            if (frame.pgn != Pgn.FROM_AUTOSTEER || frame.data.size < 8) return null
            val d = frame.data
            val angle = int16(d, 0)
            val heading = int16(d, 2)
            val roll = int16(d, 4)
            val switches = d[6].u8()
            return AutosteerStatus(
                actualAngleDeg = angle / 100.0,
                headingDeg = if (heading == 9999) null else heading / 10.0,
                rollDeg = if (roll == 8888) null else roll / 10.0,
                workSwitch = switches and 0x01 != 0,
                steerSwitch = switches and 0x02 != 0,
                pwm = d[7].u8(),
            )
        }

        private fun int16(d: ByteArray, i: Int): Int = ((d[i].u8()) or (d[i + 1].u8() shl 8)).toShort().toInt()
    }
}
