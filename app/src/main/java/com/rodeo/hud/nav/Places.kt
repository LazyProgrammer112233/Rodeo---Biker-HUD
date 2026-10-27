package com.rodeo.hud.nav

import android.content.Context
import android.location.Geocoder
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

/**
 * Place search without an API key: OpenStreetMap's Photon search (good at shops, petrol pumps,
 * landmarks) and the phone's own geocoder (good at street addresses) run side by side, with
 * Nominatim as the backstop. Results near the rider come first.
 */
object Places {
    suspend fun search(context: Context, query: String, nearLat: Double?, nearLng: Double?): List<Place> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val merged = coroutineScope {
            val photon = async { withTimeoutOrNull(8_000) { runCatching { photon(q, nearLat, nearLng) }.getOrNull() }.orEmpty() }
            val phone = async { withTimeoutOrNull(8_000) { runCatching { platform(context, q, nearLat, nearLng) }.getOrNull() }.orEmpty() }
            merge(photon.await(), phone.await())
        }
        val results = merged.ifEmpty { runCatching { nominatim(q, nearLat, nearLng) }.getOrDefault(emptyList()) }
        rank(results, nearLat, nearLng).take(8)
    }

    /** Closest to the rider first. */
    fun rank(places: List<Place>, lat: Double?, lng: Double?): List<Place> {
        if (lat == null || lng == null) return places
        return places.sortedBy { Geo.distanceM(lat, lng, it.lat, it.lng) }
    }

    /** Drops duplicates (same name within 150 m) when both sources find the same place. */
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
            Place(name, sub, coords.getDouble(1), coords.getDouble(0))
        }
    }

    private fun nominatim(q: String, lat: Double?, lng: Double?): List<Place> {
        val box = if (lat != null && lng != null) "&viewbox=${lng - 1.5},${lat + 1.5},${lng + 1.5},${lat - 1.5}" else ""
        val body = Router.get("https://nominatim.openstreetmap.org/search?q=${enc(q)}&format=jsonv2&limit=8&countrycodes=in$box", 8_000)
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val display = o.optString("display_name")
            val name = o.optString("name").ifBlank { display.substringBefore(",") }
            Place(name, display.substringAfter(", ", "").take(80), o.getString("lat").toDouble(), o.getString("lon").toDouble())
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
            Place(title, a.getAddressLine(0) ?: listOfNotNull(a.locality, a.adminArea).joinToString(", "), a.latitude, a.longitude)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
