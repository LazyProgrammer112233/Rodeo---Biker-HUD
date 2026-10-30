package com.rodeo.hud.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

enum class CrashMode { OFF, RIDE, TEST }

/** What the Settings screen shows while a drop test runs. */
data class CrashTestStatus(val mode: CrashMode = CrashMode.OFF, val text: String = "", val peakG: Float = 0f)

/**
 * Crash detection from the raw accelerometer, sampled at 200 Hz on its own thread.
 *
 * Ride: an impact over 4 g, then 10 s later the bike is below 5 km/h and either the phone has
 * tipped over (more than 45 degrees) or the impact was severe (over 6 g at speed).
 * Test: a free fall, a landing over 2 g, then 2 s of stillness. Drop the phone onto a bed.
 */
class CrashDetector(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    val available get() = accel != null

    /** GPS speed, supplied by the ride engine. */
    var speedKmh: () -> Float = { 0f }
    /** Called on the main thread when a crash is confirmed. */
    var onCrash: (dryRun: Boolean, peakG: Float) -> Unit = { _, _ -> }

    private val _status = MutableStateFlow(CrashTestStatus())
    val status: StateFlow<CrashTestStatus> = _status.asStateFlow()

    @Volatile private var mode = CrashMode.OFF
    private var freeFallStart = 0L
    private var freeFallAt = 0L
    private var impactAt = 0L
    private var impactG = 0f
    private var speedBefore = 0f
    private var stillSince = 0L
    private var testEndsAt = 0L
    private val stable = FloatArray(3) { if (it == 2) 1f else 0f }
    private val beforeImpact = FloatArray(3)

    @Volatile private var rideArmed = false

    fun armForRide() {
        rideArmed = true
        if (mode != CrashMode.TEST) start(CrashMode.RIDE)
    }

    fun disarmRide() {
        rideArmed = false
        if (mode == CrashMode.RIDE) shutdown()
    }

    private fun shutdown() {
        mode = CrashMode.OFF
        sm.unregisterListener(this)
        worker?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        worker = null
        _status.value = _status.value.copy(mode = CrashMode.OFF)
    }

    /** Arms a 60 s drop test that ends in a dry-run SOS countdown (nothing is sent). */
    fun startTest() {
        start(CrashMode.TEST)
        testEndsAt = now() + 60_000
        post("Armed. Drop the phone from about 50 cm onto a bed or sofa.")
        worker?.postDelayed({
            if (mode == CrashMode.TEST) {
                post("No drop detected. Try again from a little higher.")
                stopTestSoon()
            }
        }, 60_000)
    }

    private fun start(m: CrashMode) {
        if (accel == null) {
            _status.value = CrashTestStatus(CrashMode.OFF, "This phone has no accelerometer")
            return
        }
        if (thread == null) {
            thread = HandlerThread("crash-detector").also { it.start() }
            worker = Handler(thread!!.looper)
            sm.registerListener(this, accel, 5_000, worker)
        }
        mode = m
        impactAt = 0L; freeFallAt = 0L; freeFallStart = 0L; stillSince = 0L
        _status.value = CrashTestStatus(m)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0] / SensorManager.GRAVITY_EARTH
        val y = e.values[1] / SensorManager.GRAVITY_EARTH
        val z = e.values[2] / SensorManager.GRAVITY_EARTH
        val g = sqrt(x * x + y * y + z * z)
        val t = now()

        // Gravity direction while the phone is steady, to measure how far it tipped.
        if (abs(g - 1f) < 0.08f) {
            stable[0] = stable[0] * 0.9f + x * 0.1f
            stable[1] = stable[1] * 0.9f + y * 0.1f
            stable[2] = stable[2] * 0.9f + z * 0.1f
            if (stillSince == 0L) stillSince = t
        } else if (abs(g - 1f) > 0.2f) {
            stillSince = 0L
        }

        // Free fall: near-zero g for at least 80 ms.
        if (g < 0.4f) {
            if (freeFallStart == 0L) freeFallStart = t
            if (t - freeFallStart >= 80) freeFallAt = t
        } else freeFallStart = 0L

        when (mode) {
            CrashMode.OFF -> Unit
            CrashMode.TEST -> test(g, t)
            CrashMode.RIDE -> ride(g, t)
        }
    }

    private fun test(g: Float, t: Long) {
        if (impactAt == 0L) {
            val fell = t - freeFallAt < 1_000
            if ((fell && g > 2.0f) || g > 3.0f) {
                impactAt = t; impactG = g
                post("Impact %.1f g detected. Leave the phone still…".format(g), g)
            }
            return
        }
        impactG = maxOf(impactG, g)
        when {
            stillSince != 0L && stillSince > impactAt && t - stillSince >= 2_000 -> {
                val peak = impactG
                post("Crash confirmed (%.1f g). Starting the test countdown.".format(peak), peak)
                stopTestSoon()
                main.post { onCrash(true, peak) }
            }
            t - impactAt > 8_000 -> {
                impactAt = 0L
                post("The phone kept moving after the impact. Drop it again and leave it still.")
            }
        }
    }

    private fun ride(g: Float, t: Long) {
        if (impactAt != 0L || g < RIDE_IMPACT_G) return
        impactAt = t; impactG = g
        speedBefore = speedKmh()
        System.arraycopy(stable, 0, beforeImpact, 0, 3)
        // Check again once things have settled.
        worker?.postDelayed({ confirmRideCrash() }, 10_000)
    }

    private fun confirmRideCrash() {
        if (mode != CrashMode.RIDE) return
        val speedNow = speedKmh()
        val tipped = angle(beforeImpact, stable) > 45f
        val severe = impactG > 6f && speedBefore > 15f
        val peak = impactG
        impactAt = 0L
        if (speedNow < 5f && (tipped || severe)) main.post { onCrash(false, peak) }
    }

    /** After a test, go back to ride protection if a ride is on, otherwise stop sampling. */
    private fun stopTestSoon() {
        if (rideArmed) {
            mode = CrashMode.RIDE
            impactAt = 0L
            return
        }
        mode = CrashMode.OFF
        main.postDelayed({ if (mode == CrashMode.OFF) shutdown() }, 4_000)
    }

    private fun post(text: String, peak: Float = _status.value.peakG) {
        _status.value = CrashTestStatus(mode, text, peak)
    }

    private fun angle(a: FloatArray, b: FloatArray): Float {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 0.1f || nb < 0.1f) return 0f
        val cos = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun now() = System.currentTimeMillis()

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val RIDE_IMPACT_G = 4.0f
    }
}
