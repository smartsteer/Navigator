package eu.smartsteer.navigator.core.geo

import kotlin.math.PI
import kotlin.math.cos

/**
 * Płaszczyzna lokalna pola: szerokość/długość geograficzna -> metry (x = wschód, y = północ)
 * względem punktu początkowego pola. Dokładność rzędu milimetrów na odległościach kilku km,
 * czyli w zupełności wystarcza na pojedyncze pole.
 */
class LocalPlane(val originLat: Double, val originLon: Double) {
    private val latRad = originLat * PI / 180.0
    private val metersPerDegLat = 111132.92 - 559.82 * cos(2 * latRad) + 1.175 * cos(4 * latRad) - 0.0023 * cos(6 * latRad)
    private val metersPerDegLon = 111412.84 * cos(latRad) - 93.5 * cos(3 * latRad) + 0.118 * cos(5 * latRad)

    fun toLocal(lat: Double, lon: Double) = Vec2((lon - originLon) * metersPerDegLon, (lat - originLat) * metersPerDegLat)

    fun toGeo(p: Vec2): Pair<Double, Double> = Pair(originLat + p.y / metersPerDegLat, originLon + p.x / metersPerDegLon)
}
