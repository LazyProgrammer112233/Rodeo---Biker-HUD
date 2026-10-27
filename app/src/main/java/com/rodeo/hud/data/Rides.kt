package com.rodeo.hud.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TrackPoint(val lat: Double, val lng: Double, val time: Long, val speedKmh: Float)

data class Ride(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val startLat: Double?,
    val startLng: Double?,
    val parkLat: Double?,
    val parkLng: Double?,
    val distanceM: Double,
    val maxSpeedKmh: Float,
    val avgSpeedKmh: Float,
    val vibrationAvg: Float,
    val vibrationPeak: Float,
    val highVibrationSec: Int,
    val hazards: Int,
    val points: List<TrackPoint> = emptyList(),
) {
    val durationMs: Long get() = (endTime - startTime).coerceAtLeast(0)
}

/** Rides are kept as one JSON file each under filesDir/rides. */
class RideRepository(context: Context) {
    private val dir = File(context.filesDir, "rides").apply { mkdirs() }
    private val _rides = MutableStateFlow<List<Ride>>(emptyList())
    /** Summaries (no track points), newest first. */
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _rides.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { parse(JSONObject(f.readText()), withPoints = false) }.getOrNull() }
            .sortedByDescending { it.startTime }
    }

    suspend fun save(ride: Ride) = withContext(Dispatchers.IO) {
        File(dir, "${ride.id}.json").writeText(toJson(ride).toString())
        refresh()
    }

    suspend fun load(id: String): Ride? = withContext(Dispatchers.IO) {
        val f = File(dir, "$id.json")
        if (!f.exists()) null else runCatching { parse(JSONObject(f.readText()), withPoints = true) }.getOrNull()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        refresh()
    }

    fun totalKm(): Double = _rides.value.sumOf { it.distanceM } / 1000.0

    fun lastParked(): Ride? = _rides.value.firstOrNull { it.parkLat != null }

    private fun toJson(r: Ride) = JSONObject().apply {
        put("id", r.id); put("startTime", r.startTime); put("endTime", r.endTime)
        r.startLat?.let { put("startLat", it) }; r.startLng?.let { put("startLng", it) }
        r.parkLat?.let { put("parkLat", it) }; r.parkLng?.let { put("parkLng", it) }
        put("distanceM", r.distanceM); put("maxSpeed", r.maxSpeedKmh.toDouble()); put("avgSpeed", r.avgSpeedKmh.toDouble())
        put("vibAvg", r.vibrationAvg.toDouble()); put("vibPeak", r.vibrationPeak.toDouble())
        put("vibHighSec", r.highVibrationSec); put("hazards", r.hazards)
        // Points as a flat array: lat, lng, time, speed, ...
        put("points", JSONArray().apply {
            r.points.forEach { p -> put(p.lat); put(p.lng); put(p.time); put(p.speedKmh.toDouble()) }
        })
    }

    private fun parse(o: JSONObject, withPoints: Boolean): Ride {
        val pts = if (!withPoints) emptyList() else o.optJSONArray("points")?.let { a ->
            (0 until a.length() / 4).map { i ->
                TrackPoint(a.getDouble(i * 4), a.getDouble(i * 4 + 1), a.getLong(i * 4 + 2), a.getDouble(i * 4 + 3).toFloat())
            }
        } ?: emptyList()
        fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
        return Ride(
            id = o.getString("id"),
            startTime = o.getLong("startTime"),
            endTime = o.getLong("endTime"),
            startLat = d("startLat"), startLng = d("startLng"),
            parkLat = d("parkLat"), parkLng = d("parkLng"),
            distanceM = o.optDouble("distanceM", 0.0),
            maxSpeedKmh = o.optDouble("maxSpeed", 0.0).toFloat(),
            avgSpeedKmh = o.optDouble("avgSpeed", 0.0).toFloat(),
            vibrationAvg = o.optDouble("vibAvg", 0.0).toFloat(),
            vibrationPeak = o.optDouble("vibPeak", 0.0).toFloat(),
            highVibrationSec = o.optInt("vibHighSec"),
            hazards = o.optInt("hazards"),
            points = pts,
        )
    }
}
