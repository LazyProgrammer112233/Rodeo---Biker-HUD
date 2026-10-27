package com.rodeo.hud.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class Place(val title: String, val subtitle: String, val lat: Double, val lng: Double)

data class NavState(val destination: Place? = null, val walking: Boolean = false)

/**
 * Keyless navigation: Rodeo shows the map and the destination itself (OpenStreetMap), and hands
 * turn-by-turn to the Google Maps app in two-wheeler mode. No API key or billing is involved.
 */
class NavController(private val context: Context) {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    /** Sets the destination on Rodeo's map and starts Google Maps voice guidance. */
    fun navigateTo(place: Place, walking: Boolean = false): Boolean {
        _state.value = NavState(place, walking)
        return openGuidance()
    }

    /** Opens (or returns to) Google Maps turn-by-turn for the current destination. */
    fun openGuidance(): Boolean {
        val s = _state.value
        val p = s.destination ?: return false
        // mode=l is two-wheeler (India), w is walking.
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lng}&mode=${if (s.walking) "w" else "l"}"))
            .setPackage(GOOGLE_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lng}&travelmode=${if (s.walking) "walking" else "two-wheeler"}&dir_action=navigate"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(nav); true
        } catch (_: ActivityNotFoundException) {
            runCatching { context.startActivity(web); true }.getOrDefault(false)
        }
    }

    fun stop() { _state.value = NavState() }

    companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"

        /** Free-text place search with the platform geocoder (no extra API needed). */
        @Suppress("DEPRECATION")
        suspend fun search(context: Context, query: String, nearLat: Double?, nearLng: Double?): List<Place> = withContext(Dispatchers.IO) {
            if (query.isBlank() || !Geocoder.isPresent()) return@withContext emptyList()
            runCatching {
                val g = Geocoder(context, Locale("en", "IN"))
                val results = if (nearLat != null && nearLng != null) {
                    g.getFromLocationName(query, 6, nearLat - 1.5, nearLng - 1.5, nearLat + 1.5, nearLng + 1.5)
                        ?.takeIf { it.isNotEmpty() } ?: g.getFromLocationName(query, 6)
                } else g.getFromLocationName(query, 6)
                results.orEmpty().map { a ->
                    val title = a.featureName?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() } } ?: a.getAddressLine(0) ?: query
                    Place(title, a.getAddressLine(0) ?: listOfNotNull(a.locality, a.adminArea).joinToString(", "), a.latitude, a.longitude)
                }
            }.getOrDefault(emptyList())
        }
    }
}
