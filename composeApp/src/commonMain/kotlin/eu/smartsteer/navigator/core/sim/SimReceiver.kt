package eu.smartsteer.navigator.core.sim

import eu.smartsteer.navigator.core.aog.AogFrame
import eu.smartsteer.navigator.core.aog.Pgn
import eu.smartsteer.navigator.core.geo.LocalPlane
import eu.smartsteer.navigator.core.geo.Vec2
import eu.smartsteer.navigator.core.ntrip.Ntrip
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Symulacja jazdy bez płytki: udaje odbiornik RTK (zdania GGA i VTG) i moduł skrętu (PGN 253),
 * a ciągnik skręca tak, jak każe autoster (PGN 254). Cała reszta aplikacji działa jak na prawdziwym sprzęcie.
 */
class SimReceiver(originLat: Double = 52.2297, originLon: Double = 21.0122, wheelbase: Double = 3.3) {
    private val plane = LocalPlane(originLat, originLon)
    val tractor = TractorSim(Vec2.ZERO, 0.0, wheelbase)
    /** Zadana prędkość, km/h. */
    var targetSpeedKmh = 8.0
    /** Skręt kierownicą „ręcznie” (gdy autoster nie prowadzi), °. */
    var manualSteerDeg = 0.0
    private var utcS = 8 * 3600.0

    fun step(dt: Double, autosteerDeg: Double?) {
        utcS += dt
        val target = targetSpeedKmh / 3.6
        val dv = (target - tractor.speedMs).coerceIn(-1.5 * dt, 1.0 * dt)
        tractor.speedMs += dv
        tractor.step(dt, autosteerDeg ?: manualSteerDeg)
    }

    private val courseDeg: Double get() = ((tractor.heading * 180 / PI) % 360 + 360) % 360

    fun gga(): String {
        val (lat, lon) = plane.toGeo(tractor.position)
        return Ntrip.gga(lat, lon, utcS, 4, 18, 0.6, 110.0).trim()
    }

    fun vtg(): String {
        val kmh = tractor.speedMs * 3.6
        val body = "GNVTG,${r(courseDeg, 2)},T,,M,${r(kmh / 1.852, 3)},N,${r(kmh, 3)},K,A"
        var x = 0
        for (c in body) x = x xor c.code
        return "$" + body + "*" + x.toString(16).uppercase().padStart(2, '0')
    }

    /** Odpowiedź modułu skrętu: kąt kół, brak IMU, przełącznik autosteru włączony. */
    fun pgn253(): ByteArray {
        val a = (tractor.actualSteerDeg * 100).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val d = byteArrayOf(
            (a and 0xFF).toByte(), (a shr 8).toByte(),
            0x0F, 0x27, // 9999 = brak kursu IMU
            0xB8.toByte(), 0x22, // 8888 = brak przechyłu
            0x02, // przełącznik autosteru
            0,
        )
        return AogFrame.build(Pgn.FROM_AUTOSTEER, d, source = 0x7E)
    }

    private fun r(v: Double, d: Int): String {
        var s = 1.0
        repeat(d) { s *= 10 }
        return (kotlin.math.round(v * s) / s).toString()
    }
}
