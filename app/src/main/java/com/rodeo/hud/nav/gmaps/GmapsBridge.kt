package com.rodeo.hud.nav.gmaps

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import com.rodeo.hud.core.Speaker
import com.rodeo.hud.nav.Maneuver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the HUD shows about Google Maps' navigation. */
data class GmapsState(
    /** Google Maps is navigating (its notification is up), or just finished and the HUD still says so. */
    val active: Boolean = false,
    val guidance: MapsGuidance? = null,
    /** Google's own arrow picture, shown when Rodeo could not read it with confidence. */
    val picture: Bitmap? = null,
    /** Navigation ended a moment ago; the panel says so for a few seconds, then gives the map back. */
    val ended: Boolean = false,
    val voiceOn: Boolean = true,
    /** The rider asked for the map back while Google Maps navigates (a big card then sits over it). */
    val showMap: Boolean = false,
    /** This is the built-in preview, not a real Google Maps trip. */
    val demo: Boolean = false,
    /** For the Settings check: when the last Google Maps notification arrived and what it said. */
    val lastSeenAt: Long = 0L,
    val lastNotice: MapsNotice? = null,
    val lastArrow: ArrowReading? = null,
)

/**
 * Carries Google Maps' turn-by-turn (read from its notification by
 * [com.rodeo.hud.media.MediaListenerService]) onto the HUD and into the rider's helmet.
 * All calls come on the main thread.
 */
class GmapsBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val speaker: Speaker,
    private val voicePref: () -> Boolean,
    private val saveVoicePref: (Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(GmapsState(voiceOn = voicePref()))
    val state: StateFlow<GmapsState> = _state.asStateFlow()

    private val planner = VoicePlanner { text, urgent ->
        if (_state.value.voiceOn) speaker.say(text, Speaker.Channel.NAV, flush = urgent)
    }
    private var lastFix: Location? = null
    private var endJob: Job? = null
    private var demoJob: Job? = null

    /** A Google Maps navigation notification arrived or changed. */
    fun onNotice(notice: MapsNotice, picture: Bitmap?, arrow: ArrowReading?, now: Long = System.currentTimeMillis()) {
        if (_state.value.demo) stopDemo()
        show(notice, picture, arrow, now, demo = false)
    }

    private fun show(notice: MapsNotice, picture: Bitmap?, arrow: ArrowReading?, now: Long, demo: Boolean) {
        val g = NoticeParser.parse(notice, arrow)
        endJob?.cancel()
        if (!_state.value.active) {
            planner.reset()
            speaker.warmUp()
        }
        _state.update {
            it.copy(
                active = g != null || it.active,
                guidance = g ?: it.guidance,
                picture = picture ?: it.picture.takeIf { _ -> g == null },
                ended = false,
                demo = demo,
                lastSeenAt = now,
                lastNotice = notice,
                lastArrow = arrow,
                voiceOn = if (!it.active) voicePref() else it.voiceOn,
            )
        }
        if (g != null) planner.onGuidance(g, now)
    }

    /** The navigation notification went away: Google Maps stopped navigating. */
    fun onGone(now: Long = System.currentTimeMillis(), demoEnd: Boolean = false) {
        val s = _state.value
        if (!s.active || s.ended) return
        // The preview runs to its own end; a listener reconnect must not cut it short.
        if (s.demo && !demoEnd) return
        planner.onEnded(now)
        val arrived = _state.value.guidance?.status == NavStatus.ARRIVED || _state.value.guidance?.maneuver == Maneuver.ARRIVE
        _state.update { it.copy(ended = true) }
        endJob?.cancel()
        endJob = scope.launch {
            delay(if (arrived) ARRIVED_HOLD_MS else ENDED_HOLD_MS)
            _state.update { GmapsState(voiceOn = it.voiceOn, lastSeenAt = it.lastSeenAt, lastNotice = it.lastNotice, lastArrow = it.lastArrow) }
            planner.reset()
        }
    }

    /** Between notification updates the planner counts down with the bike's own GPS. */
    fun onLocation(loc: Location) {
        val prev = lastFix
        lastFix = loc
        val s = _state.value
        if (!s.active || s.ended || s.demo) return
        if (loc.hasSpeed()) planner.setSpeed(loc.speed.toDouble())
        if (prev != null && loc.accuracy < 40f) {
            val d = prev.distanceTo(loc).toDouble()
            if (d in 0.5..200.0) planner.onTravel(d, System.currentTimeMillis())
        }
    }

    fun toggleVoice() {
        val on = !_state.value.voiceOn
        if (!on) speaker.stop()
        saveVoicePref(on)
        _state.update { it.copy(voiceOn = on) }
        if (on) _state.value.guidance?.let { speaker.say(VoicePlanner.sentence(it), Speaker.Channel.NAV, flush = true) }
    }

    /** The profile switch changed in Settings. */
    fun setVoice(on: Boolean) {
        if (!on && _state.value.voiceOn) speaker.stop()
        _state.update { it.copy(voiceOn = on) }
    }

    fun toggleMap() = _state.update { it.copy(showMap = !it.showMap) }

    /** Bring Google Maps to the front (to set or change the destination). */
    fun openGoogleMaps(): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(GOOGLE_MAPS)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")).setPackage(GOOGLE_MAPS)
        return try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun googleMapsInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo(GOOGLE_MAPS, 0); true }.getOrDefault(false)

    /**
     * A short made-up trip through every kind of turn, so the rider can see and hear how Google
     * Maps' directions will look on the HUD before the first real ride.
     */
    fun startDemo(stepMs: Long = 4_500L) {
        if (_state.value.active && !_state.value.demo) return
        demoJob?.cancel()
        demoJob = scope.launch {
            val t0 = System.currentTimeMillis()
            GmapsDemo.TRIP.forEachIndexed { i, n ->
                show(n.first, null, n.second, t0 + i * stepMs, demo = true)
                delay(stepMs)
            }
            onGone(demoEnd = true)
        }
    }

    fun stopDemo() {
        if (!_state.value.demo) return
        demoJob?.cancel()
        demoJob = null
        speaker.stop()
        endJob?.cancel()
        planner.reset()
        _state.update { GmapsState(voiceOn = it.voiceOn, lastSeenAt = it.lastSeenAt, lastNotice = it.lastNotice, lastArrow = it.lastArrow) }
    }

    companion object {
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        const val ENDED_HOLD_MS = 6_000L
        const val ARRIVED_HOLD_MS = 12_000L
    }
}
