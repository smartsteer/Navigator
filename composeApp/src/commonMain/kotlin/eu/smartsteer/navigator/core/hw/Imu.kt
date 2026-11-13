package eu.smartsteer.navigator.core.hw

import eu.smartsteer.navigator.core.settings.VehicleProfile
import kotlin.math.PI
import kotlin.math.sin

/**
 * IMU (żyroskop): przechył z zerem, odwróceniem i filtrem, przesunięcie anteny na zboczu
 * oraz fuzja kursu IMU z kursem GPS.
 *
 * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), pliki
 * SourceCode/GPS/Forms/UDPComm.Designer.cs (odczyt przechyłu: invert, rollZero, rollFilter),
 * SourceCode/GPS/Forms/Position.designer.cs (rollCorrectionDistance, region „IMU Fusion”),
 * SourceCode/GPS/Classes/CAHRS.cs.
 */
class Imu {
    /** Przechył po zerowaniu i filtrze, ° (+ = prawa strona w dół). Null = brak IMU. */
    var rollDeg: Double? = null; private set
    var rollTimeS = Double.NEGATIVE_INFINITY; private set
    /** Ostatni odczyt przed odjęciem zera — do „Wyzeruj przechył”. */
    var rawRollDeg: Double? = null; private set

    /** Kurs z IMU, ° (surowy, bez korekty GPS). */
    var headingDeg: Double? = null; private set
    var headingTimeS = Double.NEGATIVE_INFINITY; private set

    /** Przesunięcie kursu IMU względem GPS, rad (imuGPS_Offset). */
    var imuGpsOffset = 0.0; private set
    /** Czy offset był już ustawiony z pierwszego kursu GPS (IMU ma dowolne zero — BNO085 nie wskazuje północy). */
    var aligned = false; private set

    /**
     * Ustawia offset tak, żeby kurs IMU zgadzał się z podanym kursem ciągnika (rad).
     * Na podstawie AgOpenGPS (© Brian Tischler i współtwórcy, GPLv3), plik SourceCode/GPS/Forms/Position.designer.cs
     * (pierwszy kurs: imuGPS_Offset = kurs GPS − kurs IMU, przy założeniu jazdy do przodu).
     */
    fun align(vehicleHeading: Double) {
        val imu = (headingDeg ?: return) * PI / 180
        var o = (vehicleHeading - imu) % TWO_PI
        if (o < 0) o += TWO_PI
        imuGpsOffset = o
        aligned = true
    }

    /** Kasuje dopasowanie kursu IMU do GPS (reset kierunku: imuGPS_Offset = 0). */
    fun resetAlignment() { imuGpsOffset = 0.0; aligned = false }

    /**
     * Fuzja dla źródła kursu „VTG” (AgOpenGPS, Position.designer.cs, case "VTG"): offset przesuwany o 0,1 różnicy,
     * gdy różnica < 0,18 rad, inaczej o 0,2.
     */
    fun fuseVtg(gpsHeading: Double): Double {
        val imu = (headingDeg ?: return gpsHeading) * PI / 180
        var gyroDelta = (imu + imuGpsOffset) - gpsHeading
        if (gyroDelta < 0) gyroDelta += TWO_PI
        if (gyroDelta >= -HALF_PI && gyroDelta <= HALF_PI) gyroDelta *= -1.0
        else gyroDelta = if (gyroDelta > HALF_PI) TWO_PI - gyroDelta else (TWO_PI + gyroDelta) * -1.0
        if (gyroDelta > TWO_PI) gyroDelta -= TWO_PI
        if (gyroDelta < -TWO_PI) gyroDelta += TWO_PI
        imuGpsOffset += gyroDelta * (if (kotlin.math.abs(gyroDelta) < 0.18) 0.1 else 0.2)
        if (imuGpsOffset > TWO_PI) imuGpsOffset -= TWO_PI
        if (imuGpsOffset < -TWO_PI) imuGpsOffset += TWO_PI
        aligned = true
        return corrected()
    }

    fun rollAlive(nowS: Double) = rollDeg != null && nowS - rollTimeS <= TIMEOUT_S
    fun headingAlive(nowS: Double) = headingDeg != null && nowS - headingTimeS <= TIMEOUT_S

    /**
     * Nowy przechył. [filtered] = odczyt z IMU (PGN 253, PANDA, 0xD3) przechodzi przez filtr;
     * z dwóch anten (PAOGI) — bez filtra, jak w AgOpenGPS.
     */
    fun onRoll(raw: Double, filtered: Boolean, p: VehicleProfile, nowS: Double) {
        val r = if (p.invertRoll) -raw else raw
        rawRollDeg = r
        val k = r - p.rollZero
        val prev = rollDeg
        val f = p.rollFilter.coerceIn(0.0, 0.98)
        rollDeg = if (filtered && prev != null && rollAlive(nowS)) prev * f + k * (1 - f) else k
        rollTimeS = nowS
    }

    fun onHeading(deg: Double, nowS: Double) {
        headingDeg = deg
        headingTimeS = nowS
    }

    fun disconnect() {
        rollDeg = null; headingDeg = null; aligned = false
        rollTimeS = Double.NEGATIVE_INFINITY; headingTimeS = Double.NEGATIVE_INFINITY
    }

    /**
     * Przesunięcie anteny w bok przez przechył, m (+ = w prawo):
     * rollCorrectionDistance = sin(przechył) · −wysokość anteny.
     */
    fun rollCorrection(p: VehicleProfile, nowS: Double): Double {
        val r = rollDeg ?: return 0.0
        if (!rollAlive(nowS) || p.antennaHeight == 0.0) return 0.0
        return sin(r * PI / 180) * -p.antennaHeight
    }

    /**
     * Fuzja: przy nowym kursie z GPS przesuwa offset IMU o część różnicy (waga fuzji)
     * i zwraca kurs IMU skorygowany offsetem, rad.
     */
    fun fuse(gpsHeading: Double, weight: Double): Double {
        val imu = (headingDeg ?: return gpsHeading) * PI / 180
        var gyroDelta = (imu + imuGpsOffset) - gpsHeading
        if (gyroDelta < 0) gyroDelta += TWO_PI else if (gyroDelta >= TWO_PI) gyroDelta -= TWO_PI
        // różnica na okręgu 0..360..0, sprowadzona do ±π
        if (gyroDelta >= -HALF_PI && gyroDelta <= HALF_PI) {
            gyroDelta *= -1.0
        } else {
            gyroDelta = if (gyroDelta > HALF_PI) TWO_PI - gyroDelta else (TWO_PI + gyroDelta) * -1.0
        }
        if (gyroDelta > TWO_PI) gyroDelta -= TWO_PI else if (gyroDelta < -TWO_PI) gyroDelta += TWO_PI
        imuGpsOffset += gyroDelta * weight
        if (imuGpsOffset > TWO_PI) imuGpsOffset -= TWO_PI else if (imuGpsOffset < 0) imuGpsOffset += TWO_PI
        return corrected()
    }

    /** Kurs IMU + offset (na postoju i między pozycjami GPS), rad. */
    fun corrected(): Double {
        var c = (headingDeg ?: 0.0) * PI / 180 + imuGpsOffset
        if (c >= TWO_PI) c -= TWO_PI else if (c < 0) c += TWO_PI
        return c
    }

    companion object {
        const val TIMEOUT_S = 1.0
        private const val TWO_PI = 2 * PI
        private const val HALF_PI = PI / 2
    }
}
