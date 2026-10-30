package com.rodeo.hud.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { AUTO, LIGHT, DARK }

/** When the assistant listens for the wake phrase. */
enum class ListenMode { OFF, RIDING, ALWAYS }

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
    /**
     * Music pad: the whole screen becomes three music buttons (left = previous, middle = play /
     * pause, right = next) and every other touch is ignored, for a mounted phone on the move.
     */
    val musicPad: Boolean = false,
    /** Switch the music pad on when a ride starts (and off when it ends). */
    val musicPadOnRide: Boolean = true,
    /** On the HUD, the left volume key = previous song, the right one = next, holding either = play / pause. */
    val volumeKeyMusic: Boolean = true,
    val regNumber: String = "",
    val bloodGroup: String = "",
    val allergies: String = "",
    val homeLabel: String = "",
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    /** The rider's own wake phrase, e.g. "Hey Doku". */
    val wakePhrase: String = "Hey Rodeo",
    /** What the recogniser heard when the rider trained the wake phrase. */
    val wakeAliases: List<String> = emptyList(),
    val listenMode: ListenMode = ListenMode.ALWAYS,
    /** Take commands through the helmet intercom's microphone after waking. */
    val helmetMic: Boolean = false,
    /** "Emergency emergency" starts SOS even without the wake phrase. */
    val sosWithoutWake: Boolean = true,
    /** Wave a hand over the top of the phone to play / pause. */
    val waveControl: Boolean = false,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
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
        put("musicPad", p.musicPad)
        put("musicPadOnRide", p.musicPadOnRide)
        put("volumeKeyMusic", p.volumeKeyMusic)
        put("regNumber", p.regNumber)
        put("bloodGroup", p.bloodGroup)
        put("allergies", p.allergies)
        put("homeLabel", p.homeLabel)
        p.homeLat?.let { put("homeLat", it) }
        p.homeLng?.let { put("homeLng", it) }
        put("wakePhrase", p.wakePhrase)
        put("wakeAliases", JSONArray().apply { p.wakeAliases.forEach { put(it) } })
        put("listenMode2", p.listenMode.name)
        put("helmetMic", p.helmetMic)
        put("sosWithoutWake", p.sosWithoutWake)
        put("waveControl2", p.waveControl)
        put("avoidTolls", p.avoidTolls)
        put("avoidHighways", p.avoidHighways)
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
            musicPad = o.optBoolean("musicPad", false),
            musicPadOnRide = o.optBoolean("musicPadOnRide", true),
            volumeKeyMusic = o.optBoolean("volumeKeyMusic", true),
            regNumber = o.optString("regNumber"),
            bloodGroup = o.optString("bloodGroup"),
            allergies = o.optString("allergies"),
            homeLabel = o.optString("homeLabel"),
            homeLat = if (o.has("homeLat")) o.getDouble("homeLat") else null,
            homeLng = if (o.has("homeLng")) o.getDouble("homeLng") else null,
            wakePhrase = o.optString("wakePhrase").ifBlank { "Hey Rodeo" },
            wakeAliases = o.optJSONArray("wakeAliases")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            // Saved under a new key: older builds defaulted to "during rides only", which left the wake
            // phrase deaf on the parked HUD.
            listenMode = runCatching { ListenMode.valueOf(o.optString("listenMode2")) }.getOrDefault(ListenMode.ALWAYS),
            helmetMic = o.optBoolean("helmetMic", false),
            sosWithoutWake = o.optBoolean("sosWithoutWake", true),
            waveControl = o.optBoolean("waveControl2", false),
            avoidTolls = o.optBoolean("avoidTolls", false),
            avoidHighways = o.optBoolean("avoidHighways", false),
        )
    }

    private companion object {
        const val KEY = "profile"
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
