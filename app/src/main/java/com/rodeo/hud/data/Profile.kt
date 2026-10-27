package com.rodeo.hud.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { AUTO, LIGHT, DARK }

data class SosContact(val name: String, val phone: String)

data class Profile(
    val onboarded: Boolean = false,
    val riderName: String = "Rider",
    val brandId: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val paintName: String? = null,
    val paintHex: Long? = null,
    val useHeritageAccent: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val mapOnRight: Boolean = true,
    val sosContacts: List<SosContact> = emptyList(),
    val crashDetection: Boolean = true,
    /** Camera swipe gestures for music (front camera, on-device only). */
    val gestureControl: Boolean = true,
    /** Flip left and right if swipes feel reversed on a particular mount. */
    val swapSwipe: Boolean = false,
    val regNumber: String = "",
    val bloodGroup: String = "",
    val allergies: String = "",
    val homeLabel: String = "",
    val homeLat: Double? = null,
    val homeLng: Double? = null,
) {
    val bikeLabel: String
        get() = listOfNotNull(Catalog.brand(brandId)?.name, model, year?.toString()).joinToString(" ")
}

/** Small SharedPreferences-backed store; the whole profile is one JSON blob. */
class ProfileStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("rodeo_profile", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<Profile> = _profile.asStateFlow()

    fun update(transform: (Profile) -> Profile) {
        val next = transform(_profile.value)
        _profile.value = next
        prefs.edit().putString(KEY, toJson(next).toString()).apply()
    }

    private fun load(): Profile {
        val raw = prefs.getString(KEY, null) ?: return Profile()
        return runCatching { fromJson(JSONObject(raw)) }.getOrDefault(Profile())
    }

    private fun toJson(p: Profile) = JSONObject().apply {
        put("onboarded", p.onboarded)
        put("riderName", p.riderName)
        put("brandId", p.brandId)
        put("model", p.model)
        p.year?.let { put("year", it) }
        put("paintName", p.paintName)
        p.paintHex?.let { put("paintHex", it) }
        put("useHeritageAccent", p.useHeritageAccent)
        put("themeMode", p.themeMode.name)
        put("mapOnRight", p.mapOnRight)
        put("sosContacts", JSONArray().apply {
            p.sosContacts.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        })
        put("crashDetection", p.crashDetection)
        put("gestureControl", p.gestureControl)
        put("swapSwipe", p.swapSwipe)
        put("regNumber", p.regNumber)
        put("bloodGroup", p.bloodGroup)
        put("allergies", p.allergies)
        put("homeLabel", p.homeLabel)
        p.homeLat?.let { put("homeLat", it) }
        p.homeLng?.let { put("homeLng", it) }
    }

    private fun fromJson(o: JSONObject): Profile {
        val contacts = o.optJSONArray("sosContacts")?.let { arr ->
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                SosContact(c.optString("name"), c.optString("phone"))
            }
        } ?: emptyList()
        return Profile(
            onboarded = o.optBoolean("onboarded"),
            riderName = o.optString("riderName", "Rider"),
            brandId = o.optStringOrNull("brandId"),
            model = o.optStringOrNull("model"),
            year = if (o.has("year")) o.getInt("year") else null,
            paintName = o.optStringOrNull("paintName"),
            paintHex = if (o.has("paintHex")) o.getLong("paintHex") else null,
            useHeritageAccent = o.optBoolean("useHeritageAccent"),
            themeMode = runCatching { ThemeMode.valueOf(o.optString("themeMode")) }.getOrDefault(ThemeMode.AUTO),
            mapOnRight = o.optBoolean("mapOnRight", true),
            sosContacts = contacts,
            crashDetection = o.optBoolean("crashDetection", true),
            gestureControl = o.optBoolean("gestureControl", true),
            swapSwipe = o.optBoolean("swapSwipe", false),
            regNumber = o.optString("regNumber"),
            bloodGroup = o.optString("bloodGroup"),
            allergies = o.optString("allergies"),
            homeLabel = o.optString("homeLabel"),
            homeLat = if (o.has("homeLat")) o.getDouble("homeLat") else null,
            homeLng = if (o.has("homeLng")) o.getDouble("homeLng") else null,
        )
    }

    private companion object {
        const val KEY = "profile"
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
