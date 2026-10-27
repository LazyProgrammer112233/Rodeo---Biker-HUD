package com.rodeo.hud.core

import android.location.Location
import com.rodeo.hud.data.Geo
import com.rodeo.hud.data.Hazard
import com.rodeo.hud.data.HazardStore
import com.rodeo.hud.data.Ride
import com.rodeo.hud.data.RideRepository
import com.rodeo.hud.data.TrackPoint
import com.rodeo.hud.sos.SosManager
import com.rodeo.hud.sos.SosReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class LiveRide(
    val active: Boolean = false,
    val id: String = "",
    val startTime: Long = 0L,
    val elapsedMs: Long = 0L,
    val movingMs: Long = 0L,
    val distanceM: Double = 0.0,
    val speedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val hazards: Int = 0,
    val paused: Boolean = false,
) {
    val avgSpeedKmh: Float
        get() = if (movingMs < 1000) 0f else (distanceM / 1000.0 / (movingMs / 3_600_000.0)).toFloat()
}

/** Owns the live ride: distance, speed, track, and the safety features that run while riding. */
class RideEngine(
    private val scope: CoroutineScope,
    private val location: LocationHub,
    private val sensors: RideSensors,
    private val rides: RideRepository,
    private val hazards: HazardStore,
    private val warnings: WarningCenter,
    private val sos: SosManager,
    private val crashEnabled: () -> Boolean,
    private val onHazardLogged: (Hazard) -> Unit,
) {
    private val _live = MutableStateFlow(LiveRide())
    val live: StateFlow<LiveRide> = _live.asStateFlow()

    /** Speed from GPS even when no ride is active (for the idle HUD). */
    private val _speed = MutableStateFlow(0f)
    val speedKmh: StateFlow<Float> = _speed.asStateFlow()

    private val points = mutableListOf<TrackPoint>()
    private var last: Location? = null
    private var stillSince = 0L
    private var ticker: Job? = null
    private var crashWatch: Job? = null

    private val onLocation: (Location) -> Unit = { loc -> handle(loc) }

    init {
        location.addListener { loc -> if (loc.hasSpeed()) _speed.value = loc.speed * 3.6f }
        sensors.speedKmh = { _speed.value }
        sensors.onJolt = { g -> onJolt(g) }
        sensors.onImpact = { g -> onImpact(g) }
    }

    fun start() {
        if (_live.value.active) return
        points.clear()
        last = null
        stillSince = 0L
        val here = location.location.value
        _live.value = LiveRide(
            active = true,
            id = UUID.randomUUID().toString(),
            startTime = System.currentTimeMillis(),
            startLat = here?.latitude,
            startLng = here?.longitude,
        )
        here?.let { points += TrackPoint(it.latitude, it.longitude, System.currentTimeMillis(), 0f) }
        location.addListener(onLocation)
        sensors.start()
        ticker = scope.launch {
            while (true) {
                delay(1000)
                val l = _live.value
                if (!l.active) break
                val now = System.currentTimeMillis()
                val moving = _speed.value > 3f
                val paused = !moving && stillSince != 0L && now - stillSince > 180_000
                _live.value = l.copy(
                    elapsedMs = now - l.startTime,
                    movingMs = if (moving) l.movingMs + 1000 else l.movingMs,
                    speedKmh = _speed.value,
                    paused = paused,
                )
                checkVibration()
            }
        }
    }

    /** Ends the ride, saves it with the parked location, and returns it. */
    suspend fun end(): Ride? {
        val l = _live.value
        if (!l.active) return null
        ticker?.cancel()
        crashWatch?.cancel()
        location.removeListener(onLocation)
        sensors.stop()
        val park = location.location.value ?: last
        val ride = Ride(
            id = l.id,
            startTime = l.startTime,
            endTime = System.currentTimeMillis(),
            startLat = l.startLat ?: points.firstOrNull()?.lat,
            startLng = l.startLng ?: points.firstOrNull()?.lng,
            parkLat = park?.latitude,
            parkLng = park?.longitude,
            distanceM = l.distanceM,
            maxSpeedKmh = l.maxSpeedKmh,
            avgSpeedKmh = l.avgSpeedKmh,
            vibrationAvg = if (sensors.vibWindows == 0) 0f else (sensors.vibSum / sensors.vibWindows).toFloat(),
            vibrationPeak = sensors.vibPeak,
            highVibrationSec = sensors.highSeconds,
            hazards = l.hazards,
            points = points.toList(),
        )
        _live.value = LiveRide()
        warnings.clear(KEY_VIB)
        warnings.clear(KEY_HAZARD)
        rides.save(ride)
        return ride
    }

    private fun handle(loc: Location) {
        val l = _live.value
        if (!l.active) return
        // Ignore poor fixes; they inflate distance.
        if (loc.hasAccuracy() && loc.accuracy > 35f) return
        val speed = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        val prev = last
        var dist = l.distanceM
        if (prev != null) {
            val d = Geo.distanceM(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
            // Count movement only when actually moving, to avoid GPS drift while parked.
            if (d > 2.0 && (speed > 2f || d > 15.0)) dist += d
        }
        val now = System.currentTimeMillis()
        stillSince = if (speed < 3f) (if (stillSince == 0L) now else stillSince) else 0L
        if (prev == null || Geo.distanceM(prev.latitude, prev.longitude, loc.latitude, loc.longitude) > 2.0) {
            points += TrackPoint(loc.latitude, loc.longitude, now, speed)
            last = loc
        }
        _live.value = l.copy(
            distanceM = dist,
            speedKmh = speed,
            maxSpeedKmh = maxOf(l.maxSpeedKmh, speed),
            startLat = l.startLat ?: loc.latitude,
            startLng = l.startLng ?: loc.longitude,
        )
        checkHazardsAhead(loc, speed)
    }

    private fun checkVibration() {
        val v = sensors.vibration.value
        if (v.level == VibrationLevel.HIGH && v.highStreakSec >= 10) {
            warnings.post(Warning(KEY_VIB, "High vibration at the mount", "Can damage the phone camera. Ease off or add a damper.", Severity.WARN))
        } else if (v.level == VibrationLevel.LOW) {
            warnings.clear(KEY_VIB)
        }
    }

    private fun onJolt(g: Float) {
        val loc = location.location.value ?: return
        if (!_live.value.active) return
        val h = hazards.record(loc.latitude, loc.longitude, g)
        _live.value = _live.value.copy(hazards = _live.value.hazards + 1)
        onHazardLogged(h)
    }

    private fun checkHazardsAhead(loc: Location, speed: Float) {
        if (speed < 10f || !loc.hasBearing()) return
        val ahead = hazards.all()
            .map { it to Geo.distanceM(loc.latitude, loc.longitude, it.lat, it.lng) }
            .filter { (h, d) ->
                d in 20.0..150.0 &&
                    Geo.angleDiff(Geo.bearing(loc.latitude, loc.longitude, h.lat, h.lng), loc.bearing.toDouble()) < 25.0
            }
            .minByOrNull { it.second }
        if (ahead != null) {
            val (h, d) = ahead
            val what = if (h.hits >= 2) "Rough road ahead" else "Possible pothole ahead"
            warnings.post(Warning(KEY_HAZARD, "$what in ${d.toInt()} m", "Logged ${h.hits} times", Severity.WARN))
        } else {
            warnings.clear(KEY_HAZARD)
        }
    }

    /** Crash rule: big impact, then 10 s later the bike is still (under 5 km/h). */
    private fun onImpact(g: Float) {
        if (!_live.value.active || !crashEnabled()) return
        val speedBefore = _speed.value
        crashWatch?.cancel()
        crashWatch = scope.launch {
            delay(10_000)
            val still = _speed.value < 5f
            if (still && (speedBefore > 8f || g > 6f)) sos.startCountdown(SosReason.CRASH)
        }
    }

    /** Settings "Simulate crash": full flow, no messages sent. */
    fun simulateCrash() = sos.startCountdown(SosReason.CRASH, dryRun = true)

    companion object {
        const val KEY_VIB = "vibration"
        const val KEY_HAZARD = "hazard-ahead"
    }
}
