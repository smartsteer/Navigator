package eu.smartsteer.navigator.core.weather

import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.math.roundToInt

/** Pogoda w miejscu ciągnika (Open-Meteo, bez klucza, dane modeli pogodowych). */
data class Weather(
    val tempC: Double?, val humidity: Double?, val windMs: Double, val gustMs: Double?, val windDirDeg: Double?, val precipMm: Double?,
    /** Zachmurzenie, % (do nieba w widoku 3D). */
    val cloudPct: Double? = null,
    /** Kod pogody WMO (45/48 mgła, 51–67 i 80–82 deszcz, 71–77 i 85–86 śnieg, 95–99 burza). */
    val code: Int? = null,
) {
    val fog: Boolean get() = code == 45 || code == 48
    val snow: Boolean get() = code != null && (code in 71..77 || code in 85..86)
    val rain: Boolean get() = !snow && ((code != null && (code in 51..67 || code in 80..82 || code >= 95)) || (precipMm ?: 0.0) > 0.05)
    val storm: Boolean get() = code != null && code >= 95

    /** Kierunek, z którego wieje, po polsku (N → „północny”). */
    val windFrom: String get() {
        val d = windDirDeg ?: return ""
        val names = listOf("północny", "północno-wschodni", "wschodni", "południowo-wschodni", "południowy", "południowo-zachodni", "zachodni", "północno-zachodni")
        return names[(((d % 360) + 360) % 360 / 45.0).roundToInt() % 8]
    }

    companion object {
        /** Do oprysku: średni wiatr do 4 m/s (dobra praktyka i etykiety większości środków). */
        const val SPRAY_MAX_WIND = 4.0

        fun url(lat: Double, lon: Double): String =
            "https://api.open-meteo.com/v1/forecast?latitude=${r(lat)}&longitude=${r(lon)}" +
                "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m,precipitation,cloud_cover,weather_code&wind_speed_unit=ms"

        private fun r(v: Double) = ((v * 1000).roundToInt() / 1000.0).toString()

        fun parse(json: String): Weather? {
            val m = MiniJson.parse(json) as? Map<*, *> ?: return null
            val c = m["current"] as? Map<*, *> ?: return null
            val wind = c["wind_speed_10m"] as? Double ?: return null
            return Weather(
                c["temperature_2m"] as? Double, c["relative_humidity_2m"] as? Double, wind,
                c["wind_gusts_10m"] as? Double, c["wind_direction_10m"] as? Double, c["precipitation"] as? Double,
                (c["cloud_cover"] as? Number)?.toDouble(), (c["weather_code"] as? Number)?.toInt(),
            )
        }
    }
}
