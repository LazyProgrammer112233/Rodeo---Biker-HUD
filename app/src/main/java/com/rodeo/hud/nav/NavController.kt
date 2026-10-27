package com.rodeo.hud.nav

import android.app.Activity
import android.content.Context
import android.location.Geocoder
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import com.rodeo.hud.BuildConfig
import com.rodeo.hud.core.Severity
import com.rodeo.hud.core.Warning
import com.rodeo.hud.core.WarningCenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class Place(val title: String, val subtitle: String, val lat: Double, val lng: Double)

data class NavState(
    val ready: Boolean = false,
    val error: String? = null,
    val destination: Place? = null,
    val guiding: Boolean = false,
    val remainingMeters: Int? = null,
    val remainingSeconds: Int? = null,
)

/** Wraps the Google Navigation SDK: two-wheeler turn-by-turn inside Rodeo. */
class NavController(private val warnings: WarningCenter) {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()
    var navigator: Navigator? = null
        private set
    private var askedTerms = false

    val enabled: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

    fun init(activity: Activity) {
        if (!enabled || navigator != null) return
        NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(nav: Navigator) {
                navigator = nav
                nav.addReroutingListener {
                    warnings.post(Warning(KEY_REROUTE, "Rerouting", "Route changed ahead (closure, traffic or a missed turn).", Severity.INFO))
                }
                nav.addRouteChangedListener { warnings.clear(KEY_REROUTE) }
                nav.addArrivalListener {
                    warnings.post(Warning(KEY_ARRIVED, "You have arrived", _state.value.destination?.title.orEmpty(), Severity.INFO))
                    stop()
                }
                nav.addRemainingTimeOrDistanceChangedListener(10, 50) {
                    val td = nav.currentTimeAndDistance
                    _state.value = _state.value.copy(remainingMeters = td?.meters, remainingSeconds = td?.seconds)
                }
                _state.value = _state.value.copy(ready = true, error = null)
            }

            override fun onError(errorCode: Int) {
                if (errorCode == NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED && !askedTerms) {
                    askedTerms = true
                    NavigationApi.showTermsAndConditionsDialog(activity, "Rodeo") { accepted ->
                        if (accepted) init(activity)
                    }
                }
                val msg = when (errorCode) {
                    NavigationApi.ErrorCode.NOT_AUTHORIZED -> "Maps key not authorised for the Navigation SDK"
                    NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> "Navigation terms not accepted"
                    NavigationApi.ErrorCode.NETWORK_ERROR -> "No network for navigation"
                    else -> "Navigation unavailable ($errorCode)"
                }
                _state.value = _state.value.copy(ready = false, error = msg)
            }
        })
    }

    fun navigateTo(place: Place, walking: Boolean = false) {
        val nav = navigator ?: return
        val wp = Waypoint.builder().setLatLng(place.lat, place.lng).setTitle(place.title).build()
        val opts = RoutingOptions().travelMode(if (walking) RoutingOptions.TravelMode.WALKING else RoutingOptions.TravelMode.TWO_WHEELER)
        _state.value = _state.value.copy(destination = place)
        nav.setDestination(wp, opts).setOnResultListener { status ->
            if (status == Navigator.RouteStatus.OK) {
                nav.startGuidance()
                _state.value = _state.value.copy(guiding = true)
            } else {
                warnings.post(Warning(KEY_ROUTE, "No route found", status.name, Severity.WARN))
                _state.value = _state.value.copy(guiding = false)
            }
        }
    }

    fun stop() {
        navigator?.let {
            it.stopGuidance()
            it.clearDestinations()
        }
        _state.value = _state.value.copy(guiding = false, destination = null, remainingMeters = null, remainingSeconds = null)
    }

    fun cleanup() {
        navigator?.cleanup()
        navigator = null
        _state.value = NavState()
    }

    companion object {
        const val KEY_REROUTE = "nav-reroute"
        const val KEY_ARRIVED = "nav-arrived"
        const val KEY_ROUTE = "nav-route"

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
