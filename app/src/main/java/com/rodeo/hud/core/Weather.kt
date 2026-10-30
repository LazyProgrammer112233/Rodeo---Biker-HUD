package com.rodeo.hud.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WeatherNow(
    val tempC: Double,
    val code: Int,
    val windKmh: Double,
    val rainChanceNextHour: Int,
    val fetchedAt: Long,
    val lat: Double,
    val lng: Double,
) {
    val label: String get() = describe(code)
    /** One or two words, for tight spots on the HUD. */
    val shortLabel: String get() = when (code) {
        0 -> "Clear"
        1, 2 -> "Cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 80, 81 -> "Rain"
        65, 82, 66, 67 -> "Heavy rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Storm"
        else -> ""
    }

    companion object {
        fun describe(code: Int) = when (code) {
            0 -> "Clear"
            1, 2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 80, 81 -> "Rain"
            65, 82, 66, 67 -> "Heavy rain"
            71, 73, 75, 77, 85, 86 -> "Snow"
            95, 96, 99 -> "Thunderstorm"
            else -> "—"
        }
    }
}

/** Current weather + rider warnings from Open-Meteo (no API key needed). Refreshes every 15 minutes. */
class WeatherService(private val warnings: WarningCenter) {
    private val _now = MutableStateFlow<WeatherNow?>(null)
    val now: StateFlow<WeatherNow?> = _now.asStateFlow()

    suspend fun refreshIfStale(lat: Double, lng: Double) {
        val cur = _now.value
        val moved = cur == null || com.rodeo.hud.data.Geo.distanceM(cur.lat, cur.lng, lat, lng) > 5_000
        if (cur != null && !moved && System.currentTimeMillis() - cur.fetchedAt < 15 * 60_000) return
        fetch(lat, lng)?.let { w ->
            _now.value = w
            postWarnings(w)
        }
    }

    private suspend fun fetch(lat: Double, lng: Double): WeatherNow? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                        "&current=temperature_2m,weather_code,wind_speed_10m&hourly=precipitation_probability&forecast_hours=2&timezone=auto",
                    lat, lng,
                ),
            )
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val o = JSONObject(body)
            val c = o.getJSONObject("current")
            val probs = o.optJSONObject("hourly")?.optJSONArray("precipitation_probability")
            val nextHour = if (probs != null && probs.length() > 0) (0 until probs.length()).maxOf { probs.optInt(it) } else 0
            WeatherNow(
                tempC = c.getDouble("temperature_2m"),
                code = c.getInt("weather_code"),
                windKmh = c.optDouble("wind_speed_10m", 0.0),
                rainChanceNextHour = nextHour,
                fetchedAt = System.currentTimeMillis(),
                lat = lat, lng = lng,
            )
        }.getOrNull()
    }

    private fun postWarnings(w: WeatherNow) {
        when {
            w.code in listOf(95, 96, 99) -> warnings.post(Warning(KEY, "Thunderstorm nearby", "Find shelter; avoid open highways.", Severity.DANGER))
            w.code in listOf(65, 82, 66, 67) -> warnings.post(Warning(KEY, "Heavy rain", "Slow down, braking distances double.", Severity.WARN))
            w.code in listOf(45, 48) -> warnings.post(Warning(KEY, "Fog", "Low visibility. Lights on, slow down.", Severity.WARN))
            w.rainChanceNextHour >= 60 -> warnings.post(Warning(KEY, "Rain likely within the hour", "${w.rainChanceNextHour}% chance. Gear up.", Severity.INFO))
            w.tempC >= 40 -> warnings.post(Warning(KEY, "Extreme heat ${w.tempC.toInt()}°C", "Hydrate every 30 minutes; watch phone heat.", Severity.WARN))
            w.windKmh >= 45 -> warnings.post(Warning(KEY, "Strong crosswinds", "${w.windKmh.toInt()} km/h winds.", Severity.WARN))
            else -> warnings.clear(KEY)
        }
    }

    companion object { const val KEY = "weather" }
}
