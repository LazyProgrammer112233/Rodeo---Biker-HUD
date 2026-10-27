package com.rodeo.hud.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.rodeo.hud.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset (NOAA approximation), good to a couple of minutes. */
object SunClock {
    /** Returns sunrise and sunset as epoch millis for [date] at the location, or null in polar day/night. */
    fun times(lat: Double, lng: Double, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long>? {
        val rise = calc(lat, lng, date, true, zone) ?: return null
        val set = calc(lat, lng, date, false, zone) ?: return null
        return rise to set
    }

    private fun calc(lat: Double, lng: Double, date: LocalDate, sunrise: Boolean, zone: ZoneId): Long? {
        val n = date.dayOfYear
        val lngHour = lng / 15.0
        val t = if (sunrise) n + (6 - lngHour) / 24 else n + (18 - lngHour) / 24
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = (l + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = (ra + 360) % 360
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(90.833)) - sinDec * sin(Math.toRadians(lat))) / (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = (if (sunrise) 360 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))) / 15
        val localMeanTime = h + ra - 0.06571 * t - 6.622
        val utc = ((localMeanTime - lngHour) % 24 + 24) % 24
        val utcStart = date.atStartOfDay(ZoneId.of("UTC"))
        return utcStart.plusSeconds((utc * 3600).toLong()).withZoneSameInstant(zone).toInstant().toEpochMilli()
    }

    fun isNight(lat: Double?, lng: Double?, now: Long = System.currentTimeMillis()): Boolean {
        if (lat == null || lng == null) {
            val hour = ZonedDateTime.now().hour
            return hour < 6 || hour >= 19 // fallback for India until we have a fix
        }
        val t = times(lat, lng, LocalDate.now()) ?: return false
        return now < t.first || now > t.second
    }
}

/**
 * Decides light vs dark: the schedule (sunrise/sunset) plus the ambient light sensor, so tunnels
 * and covered parking flip to dark at once and flip back after a few seconds of daylight.
 */
class AmbientController(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val light = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

    private val _dark = MutableStateFlow(true)
    val dark: StateFlow<Boolean> = _dark.asStateFlow()
    private val _tunnel = MutableStateFlow(false)
    val tunnel: StateFlow<Boolean> = _tunnel.asStateFlow()
    private val _lux = MutableStateFlow<Float?>(null)
    val lux: StateFlow<Float?> = _lux.asStateFlow()

    var mode: ThemeMode = ThemeMode.AUTO
        set(v) { field = v; recompute() }
    private var lat: Double? = null
    private var lng: Double? = null
    private var darkSince = 0L
    private var brightSince = 0L

    val hasLightSensor get() = light != null

    fun start() { light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
    fun stop() { sm.unregisterListener(this) }

    fun setLocation(lat: Double, lng: Double) {
        this.lat = lat; this.lng = lng
        recompute()
    }

    /** Called every few seconds so sunset flips the theme even without sensor events. */
    fun tick() = recompute()

    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0]
        _lux.value = lux
        val now = System.currentTimeMillis()
        if (lux < TUNNEL_LUX) {
            brightSince = 0L
            if (darkSince == 0L) darkSince = now
            if (!_tunnel.value && now - darkSince >= 2_000 && !SunClock.isNight(lat, lng)) _tunnel.value = true
        } else if (lux > BRIGHT_LUX) {
            darkSince = 0L
            if (brightSince == 0L) brightSince = now
            if (_tunnel.value && now - brightSince >= 5_000) _tunnel.value = false
        }
        recompute()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun recompute() {
        _dark.value = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.AUTO -> _tunnel.value || SunClock.isNight(lat, lng)
        }
    }

    companion object {
        const val TUNNEL_LUX = 15f
        const val BRIGHT_LUX = 60f
    }
}
