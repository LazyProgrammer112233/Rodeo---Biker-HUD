package com.rodeo.hud.nav

import android.content.Context
import android.location.Geocoder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rodeo.hud.data.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class Place(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double,
    /** A road rather than a place; search lists these after places. */
    val road: Boolean = false,
)

/**
 * Place search without an API key: the phone's own geocoder (strong on landmarks and addresses
 * where the phone has one), OpenStreetMap's Photon (shops, petrol pumps) and Nominatim run side
 * by side. Results are ordered by how well the name matches, then by distance, with roads below
 * places unless the rider asked for a road: "Nandi Hills" is the hill, not Nandi Hills Road.
 */
object Places {
    suspend fun search(context: Context, query: String, nearLat: Double?, nearLng: Double?): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val found = coroutineScope {
            val phone = async { withTimeoutOrNull(8_000) { runCatching { platform(context, q, nearLat, nearLng) }.getOrNull() }.orEmpty() }
            val photon = async { withTimeoutOrNull(8_000) { runCatching { photon(q, nearLat, nearLng) }.getOrNull() }.orEmpty() }
            val osm = async { withTimeoutOrNull(8_000) { runCatching { nominatim(q, nearLat, nearLng) }.getOrNull() }.orEmpty() }
            merge(merge(phone.await(), photon.await()), osm.await())
        }
        rank(q, found, nearLat, nearLng).take(8)
    }

    /** Whether the phone has a working internet connection, to explain an empty search. */
    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Best name match first; among equal matches, the closest. Roads sink unless asked for. */
    fun rank(query: String, places: List<Place>, lat: Double?, lng: Double?): List<Place> {
        val q = words(query)
        val wantsRoad = q.any { it in ROAD_WORDS }
        return places.sortedWith(
            compareBy<Place>({ match(q, it) + (if (it.road && !wantsRoad) 1.5 else 0.0) + far(it, lat, lng) }, { km(it, lat, lng) }),
        )
    }

    /** 0 exact name, 1 the name starts with (or is the start of) the query, 1.5 all words in it, 3 other. */
    private fun match(q: List<String>, p: Place): Double {
        val t = words(p.title)
        return when {
            t == q -> 0.0
            t.size >= q.size && t.subList(0, q.size) == q -> 1.0
            q.size > t.size && t.isNotEmpty() && q.subList(0, t.size) == t -> 1.0
            q.all { w -> t.any { it.startsWith(w) } } -> 1.5
            else -> 3.0
        }
    }

    private fun km(p: Place, lat: Double?, lng: Double?): Double =
        if (lat == null || lng == null) 0.0 else Geo.distanceM(lat, lng, p.lat, p.lng) / 1000.0

    /** A nudge for distance: a matching place in town beats one across the country. */
    private fun far(p: Place, lat: Double?, lng: Double?): Double = km(p, lat, lng).let {
        when {
            it < 15 -> 0.0
            it < 100 -> 0.4
            it < 500 -> 1.2
            // A partial match in town beats an exact one in another state; a match anywhere still beats no match.
            else -> 2.5
        }
    }

    private fun words(s: String): List<String> =
        s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

    private val ROAD_WORDS = setOf("road", "rd", "street", "st", "marg", "main", "cross", "highway", "nh", "sh", "flyover", "lane", "avenue", "salai", "path", "bypass", "expressway")

    /** Drops duplicates (same name within 150 m) when two sources find the same place. */
    fun merge(a: List<Place>, b: List<Place>): List<Place> {
        val out = ArrayList<Place>(a)
        for (p in b) {
            val dup = out.any { o -> Geo.distanceM(o.lat, o.lng, p.lat, p.lng) < 150 && (o.title.equals(p.title, true) || Geo.distanceM(o.lat, o.lng, p.lat, p.lng) < 25) }
            if (!dup) out += p
        }
        return out
    }

    private fun photon(q: String, lat: Double?, lng: Double?): List<Place> {
        val bias = if (lat != null && lng != null) "&lat=$lat&lon=$lng&location_bias_scale=0.4" else ""
        val body = Router.get("https://photon.komoot.io/api/?q=${enc(q)}&limit=8&lang=en$bias", 8_000)
        return parsePhoton(body)
    }

    fun parsePhoton(body: String): List<Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            val f = features.getJSONObject(i)
            val props = f.optJSONObject("properties") ?: return@mapNotNull null
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val name = props.optString("name").ifBlank { listOf(props.optString("housenumber"), props.optString("street")).filter { it.isNotBlank() }.joinToString(" ") }
            if (name.isBlank()) return@mapNotNull null
            val sub = listOf("street", "district", "city", "state")
                .map { props.optString(it) }
                .filter { it.isNotBlank() && it != name }
                .distinct()
                .joinToString(", ")
            val road = props.optString("osm_key") == "highway" || props.optString("type") == "street"
            Place(name, sub, coords.getDouble(1), coords.getDouble(0), road)
        }
    }

    private fun nominatim(q: String, lat: Double?, lng: Double?): List<Place> {
        val box = if (lat != null && lng != null) "&viewbox=${lng - 1.5},${lat + 1.5},${lng + 1.5},${lat - 1.5}" else ""
        return parseNominatim(Router.get("https://nominatim.openstreetmap.org/search?q=${enc(q)}&format=jsonv2&limit=8&countrycodes=in$box", 8_000))
    }

    fun parseNominatim(body: String): List<Place> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",") }
            Place(name, display.substringAfter(", ", "").take(80), o.getString("lat").toDouble(), o.getString("lon").toDouble(), o.optString("category") == "highway")
        }
    }

    @Suppress("DEPRECATION")
    private fun platform(context: Context, q: String, lat: Double?, lng: Double?): List<Place> {
        if (!Geocoder.isPresent()) return emptyList()
        val g = Geocoder(context, Locale("en", "IN"))
        val results = if (lat != null && lng != null) {
            g.getFromLocationName(q, 6, lat - 1.5, lng - 1.5, lat + 1.5, lng + 1.5)?.takeIf { it.isNotEmpty() } ?: g.getFromLocationName(q, 6)
        } else g.getFromLocationName(q, 6)
        return results.orEmpty().map { a ->
            val title = a.featureName?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } } ?: a.getAddressLine(0) ?: q
            val road = a.thoroughfare != null && a.featureName == a.thoroughfare
            Place(title, a.getAddressLine(0) ?: listOfNotNull(a.locality, a.adminArea).joinToString(", "), a.latitude, a.longitude, road)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
