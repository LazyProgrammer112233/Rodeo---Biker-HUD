package com.rodeo.hud.nav

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import com.rodeo.hud.core.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Place(val title: String, val subtitle: String, val lat: Double, val lng: Double)

data class NavState(
    val destination: Place? = null,
    val walking: Boolean = false,
    val route: Route? = null,
    val progress: NavProgress? = null,
    /** Asking the routing service (first route or a reroute). */
    val routing: Boolean = false,
    /** Why there is no route, e.g. no internet. */
    val error: String? = null,
    val voiceOn: Boolean = true,
) {
    val active: Boolean get() = destination != null
}

/**
 * Turn-by-turn inside Rodeo on free OpenStreetMap routing: no API key, no billing, no Google
 * Maps hand-off. Routes come from [Router], progress and voice from [Navigator].
 */
class NavController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val here: () -> Location?,
    private val options: () -> RouteOptions,
) {
    private val _state = MutableStateFlow(NavState())
    val state: StateFlow<NavState> = _state.asStateFlow()

    private var navigator: Navigator? = null
    private var job: Job? = null
    private var lastRouteAt = 0L
    private var arrivedAt = 0L

    init {
        Router.userAgent = "RodeoHUD/${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1"} (Android)"
    }

    /** Sets the destination and starts guidance. Returns false only if there is nowhere to go. */
    fun navigateTo(place: Place, walking: Boolean = false): Boolean {
        navigator = null
        arrivedAt = 0L
        _state.update { NavState(destination = place, walking = walking, voiceOn = it.voiceOn, routing = true) }
        speaker.warmUp()
        requestRoute(announce = true)
        return true
    }

    fun stop() {
        job?.cancel()
        navigator = null
        speaker.stop()
        _state.update { NavState(voiceOn = it.voiceOn) }
    }

    fun toggleVoice() {
        val on = !_state.value.voiceOn
        speaker.navMuted = !on
        if (!on) speaker.stop()
        _state.update { it.copy(voiceOn = on) }
    }

    /** Called for every GPS fix. */
    fun onLocation(loc: Location) {
        val s = _state.value
        if (s.destination == null) return
        val nav = navigator
        if (nav == null) {
            // No route yet (offline at start): keep trying every 20 s.
            if (!s.routing && System.currentTimeMillis() - lastRouteAt > 20_000) requestRoute(announce = false)
            return
        }
        val p = nav.update(
            loc.latitude, loc.longitude,
            loc.takeIf { it.hasSpeed() }?.speed,
            loc.takeIf { it.hasBearing() }?.bearing,
            loc.takeIf { it.hasAccuracy() }?.accuracy,
            System.currentTimeMillis(),
        )
        _state.update { it.copy(progress = p) }
        if (p.offRoute && !s.routing && System.currentTimeMillis() - lastRouteAt > 12_000) {
            speaker.say("Rerouting", flush = true)
            requestRoute(announce = false)
        }
        if (p.arrived) {
            if (arrivedAt == 0L) arrivedAt = System.currentTimeMillis()
            else if (System.currentTimeMillis() - arrivedAt > 45_000) stop()
        }
    }

    private fun requestRoute(announce: Boolean) {
        val dest = _state.value.destination ?: return
        val walking = _state.value.walking
        lastRouteAt = System.currentTimeMillis()
        job?.cancel()
        _state.update { it.copy(routing = true) }
        job = scope.launch {
            var from = here()
            // Right after the app opens there may be no fix yet; give GPS a few seconds.
            var waited = 0
            while (from == null && waited < 10) {
                delay(1_000); waited++; from = here()
            }
            if (from == null) {
                _state.update { it.copy(routing = false, error = "Waiting for GPS") }
                return@launch
            }
            val heading = from.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 3f }?.bearing
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Router.route(
                        LatLng(from.latitude, from.longitude), LatLng(dest.lat, dest.lng),
                        if (walking) Travel.WALK else Travel.MOTORCYCLE, heading, options(),
                    )
                }
            }
            if (_state.value.destination != dest) return@launch
            result.onSuccess { route ->
                val nav = Navigator(route) { text -> speaker.say(text, Speaker.Channel.NAV) }
                navigator = nav
                val p = nav.update(from.latitude, from.longitude, from.speed, from.takeIf { it.hasBearing() }?.bearing, from.accuracy, System.currentTimeMillis())
                _state.update { it.copy(route = route, progress = p, routing = false, error = null) }
            }.onFailure {
                _state.update { it.copy(routing = false, error = "No route yet: check mobile data") }
                if (announce) speaker.say("No route yet. I'll keep trying.", flush = true)
            }
        }
    }

    /** The turn card for the HUD. Falls back to "head this way" while there is no route. */
    fun guidance(s: NavState, from: Location?): GuidanceUi? {
        val dest = s.destination ?: return null
        val route = s.route
        val p = s.progress
        if (route == null || p == null) {
            val d = from?.let { Navigator.metres(LatLng(it.latitude, it.longitude), LatLng(dest.lat, dest.lng)) }
            return GuidanceUi(
                maneuver = Maneuver.DEPART,
                distance = d?.let { Navigator.shortDistance(it) } ?: "--",
                instruction = "Head to ${dest.title}",
                destination = dest.title,
                voiceOn = s.voiceOn,
                status = when {
                    s.routing -> "Finding route"
                    s.error != null -> s.error
                    else -> null
                },
            )
        }
        if (p.arrived) {
            return GuidanceUi(Maneuver.ARRIVE, "Arrived", "You have arrived", dest.title, voiceOn = s.voiceOn)
        }
        val step = route.steps.getOrNull(p.stepIndex)
        return GuidanceUi(
            maneuver = step?.maneuver ?: Maneuver.STRAIGHT,
            distance = Navigator.shortDistance(p.toManeuverM),
            instruction = step?.instruction ?: "Continue",
            destination = dest.title,
            remaining = Navigator.shortDistance(p.remainingM),
            eta = Navigator.shortDuration(p.remainingS),
            voiceOn = s.voiceOn,
            status = when {
                s.routing -> "Rerouting"
                p.offRoute -> "Off route"
                else -> null
            },
        )
    }

    /** Optional: open the same destination in Google Maps, for riders who prefer it. */
    fun openInGoogleMaps(): Boolean {
        val s = _state.value
        val p = s.destination ?: return false
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lng}&mode=${if (s.walking) "w" else "l"}"))
            .setPackage(GOOGLE_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(nav); true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
    }
}
