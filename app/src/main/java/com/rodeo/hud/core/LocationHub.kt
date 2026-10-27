package com.rodeo.hud.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared fused-location stream. The HUD and the ride service each hold a reference. */
class LocationHub(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    private val _lastFixAt = MutableStateFlow(0L)
    val lastFixAt: StateFlow<Long> = _lastFixAt.asStateFlow()
    private val listeners = mutableListOf<(Location) -> Unit>()
    private var holders = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { loc ->
                _location.value = loc
                _lastFixAt.value = System.currentTimeMillis()
                listeners.toList().forEach { it(loc) }
            }
        }
    }

    fun addListener(l: (Location) -> Unit) { listeners += l }
    fun removeListener(l: (Location) -> Unit) { listeners -= l }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun acquire() {
        holders++
        if (holders > 1) return
        runCatching {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .setWaitForAccurateLocation(false)
                .build()
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            client.lastLocation.addOnSuccessListener { if (it != null && _location.value == null) _location.value = it }
        }
    }

    @Synchronized
    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) runCatching { client.removeLocationUpdates(callback) }
    }

    /** Allows the pack demo / simulator to inject positions. */
    fun inject(loc: Location) {
        _location.value = loc
        listeners.toList().forEach { it(loc) }
    }
}
