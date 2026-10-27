package com.rodeo.hud.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Hazard(
    val id: String,
    val lat: Double,
    val lng: Double,
    val hits: Int,
    val lastHit: Long,
    val severity: Float,
    val shared: Boolean = false,
)

/** Local pothole / rough-road log. Nearby hits within 25 m merge into one hazard. */
class HazardStore(context: Context) {
    private val file = File(context.filesDir, "hazards.json")
    private val _hazards = MutableStateFlow(load())
    val hazards: StateFlow<List<Hazard>> = _hazards.asStateFlow()

    /** Hazards from other riders (pothole radar sharing). */
    private val _remote = MutableStateFlow<List<Hazard>>(emptyList())
    val remote: StateFlow<List<Hazard>> = _remote.asStateFlow()

    fun all(): List<Hazard> = _hazards.value + _remote.value

    @Synchronized
    fun record(lat: Double, lng: Double, severity: Float): Hazard {
        val now = System.currentTimeMillis()
        val list = _hazards.value.toMutableList()
        val idx = list.indexOfFirst { Geo.distanceM(it.lat, it.lng, lat, lng) < 25.0 }
        val h = if (idx >= 0) {
            val old = list[idx]
            old.copy(
                lat = (old.lat * old.hits + lat) / (old.hits + 1),
                lng = (old.lng * old.hits + lng) / (old.hits + 1),
                hits = old.hits + 1, lastHit = now, severity = maxOf(old.severity, severity),
            ).also { list[idx] = it }
        } else {
            Hazard(UUID.randomUUID().toString(), lat, lng, 1, now, severity).also { list.add(it) }
        }
        _hazards.value = list
        save()
        return h
    }

    fun markShared(id: String) {
        _hazards.value = _hazards.value.map { if (it.id == id) it.copy(shared = true) else it }
        save()
    }

    fun setRemote(list: List<Hazard>) {
        _remote.value = list
    }

    private fun load(): List<Hazard> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Hazard(o.getString("id"), o.getDouble("lat"), o.getDouble("lng"), o.getInt("hits"), o.getLong("lastHit"),
                o.optDouble("severity", 1.0).toFloat(), o.optBoolean("shared"))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val a = JSONArray()
        _hazards.value.forEach {
            a.put(JSONObject().put("id", it.id).put("lat", it.lat).put("lng", it.lng).put("hits", it.hits)
                .put("lastHit", it.lastHit).put("severity", it.severity.toDouble()).put("shared", it.shared))
        }
        runCatching { file.writeText(a.toString()) }
    }
}
