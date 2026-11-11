package eu.smartsteer.navigator.core.weather

import eu.smartsteer.navigator.core.update.MiniJson
import kotlin.math.roundToInt

/** Pogoda w miejscu ciągnika (Open-Meteo, bez klucza, dane modeli pogodowych). */
data class Weather(
    val tempC: Double?, val humidity: Double?, val windMs: Double, val gustMs: Double?, val windDirDeg: Double?, val precipMm: Double?,
) {
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
                "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m,precipitation&wind_speed_unit=ms"

        private fun r(v: Double) = ((v * 1000).roundToInt() / 1000.0).toString()

        fun parse(json: String): Weather? {
            val m = MiniJson.parse(json) as? Map<*, *> ?: return null
            val c = m["current"] as? Map<*, *> ?: return null
            val wind = c["wind_speed_10m"] as? Double ?: return null
            return Weather(
                c["temperature_2m"] as? Double, c["relative_humidity_2m"] as? Double, wind,
                c["wind_gusts_10m"] as? Double, c["wind_direction_10m"] as? Double, c["precipitation"] as? Double,
            )
        }
    }
}
