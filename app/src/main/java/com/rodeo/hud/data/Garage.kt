package com.rodeo.hud.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

enum class DocType(val label: String) {
    INSURANCE("Insurance"), PUC("PUC certificate"), LICENCE("Driving licence"), RC("Registration (RC)"), SERVICE("Next service")
}

data class BikeDocument(
    val type: DocType,
    /** Expiry or due date as epoch day; null when not set. */
    val dueEpochDay: Long? = null,
    val number: String = "",
    val photoUri: String? = null,
) {
    val due: LocalDate? get() = dueEpochDay?.let { LocalDate.ofEpochDay(it) }
}

data class Garage(
    val documents: List<BikeDocument> = DocType.entries.map { BikeDocument(it) },
    /** Odometer the rider entered; ride distance is added on top. */
    val baseOdometerKm: Int = 0,
    val odometerSetAtRideKm: Double = 0.0,
    val nextServiceKm: Int? = null,
) {
    fun doc(type: DocType) = documents.firstOrNull { it.type == type } ?: BikeDocument(type)
}

class GarageStore(context: Context) {
    private val prefs = context.getSharedPreferences("rodeo_garage", Context.MODE_PRIVATE)
    private val _garage = MutableStateFlow(load())
    val garage: StateFlow<Garage> = _garage.asStateFlow()

    fun update(transform: (Garage) -> Garage) {
        val next = transform(_garage.value)
        _garage.value = next
        prefs.edit().putString("garage", toJson(next).toString()).apply()
    }

    fun updateDoc(doc: BikeDocument) = update { g ->
        g.copy(documents = g.documents.filter { it.type != doc.type } + doc)
    }

    /** Current odometer = what the rider entered + km ridden in Rodeo since then. */
    fun odometerKm(totalRideKm: Double): Int {
        val g = _garage.value
        return g.baseOdometerKm + (totalRideKm - g.odometerSetAtRideKm).coerceAtLeast(0.0).toInt()
    }

    private fun toJson(g: Garage) = JSONObject().apply {
        put("base", g.baseOdometerKm)
        put("setAt", g.odometerSetAtRideKm)
        g.nextServiceKm?.let { put("nextServiceKm", it) }
        put("docs", JSONArray().apply {
            g.documents.forEach { d ->
                put(JSONObject().apply {
                    put("type", d.type.name)
                    d.dueEpochDay?.let { put("due", it) }
                    put("number", d.number)
                    d.photoUri?.let { put("photo", it) }
                })
            }
        })
    }

    private fun load(): Garage {
        val raw = prefs.getString("garage", null) ?: return Garage()
        return runCatching {
            val o = JSONObject(raw)
            val docs = o.optJSONArray("docs")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val d = a.getJSONObject(i)
                    val type = runCatching { DocType.valueOf(d.getString("type")) }.getOrNull() ?: return@mapNotNull null
                    BikeDocument(type, if (d.has("due")) d.getLong("due") else null, d.optString("number"), d.optStringOrNull("photo"))
                }
            } ?: emptyList()
            val all = DocType.entries.map { t -> docs.firstOrNull { it.type == t } ?: BikeDocument(t) }
            Garage(all, o.optInt("base"), o.optDouble("setAt", 0.0), if (o.has("nextServiceKm")) o.getInt("nextServiceKm") else null)
        }.getOrDefault(Garage())
    }
}
