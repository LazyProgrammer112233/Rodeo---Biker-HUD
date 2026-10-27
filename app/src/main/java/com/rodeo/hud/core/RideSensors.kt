package com.rodeo.hud.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

enum class VibrationLevel { LOW, MEDIUM, HIGH }

data class VibrationState(
    val rmsG: Float = 0f,
    val level: VibrationLevel = VibrationLevel.LOW,
    /** Seconds the current HIGH streak has lasted. */
    val highStreakSec: Int = 0,
)

/**
 * Motion sensing during a ride: mount vibration, pothole jolts and crash impacts.
 * Thresholds are starting points to tune on test rides (see acceptance AC-26..AC-35).
 */
class RideSensors(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravitySensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _vibration = MutableStateFlow(VibrationState())
    val vibration: StateFlow<VibrationState> = _vibration.asStateFlow()

    /** Called with severity (in g) when a vertical jolt looks like a pothole. */
    var onJolt: ((Float) -> Unit)? = null
    /** Called with peak g when a crash-level impact happens. */
    var onImpact: ((Float) -> Unit)? = null
    /** Current speed, supplied by the ride engine; jolts below 15 km/h are ignored. */
    var speedKmh: () -> Float = { 0f }

    private val gravity = FloatArray(3) { if (it == 2) SensorManager.GRAVITY_EARTH else 0f }
    private var sumSq = 0.0
    private var samples = 0
    private var windowStart = 0L
    private var highStreak = 0
    private var lastJoltAt = 0L
    private var lastImpactAt = 0L

    // Ride statistics for the summary.
    var vibSum = 0.0; private set
    var vibWindows = 0; private set
    var vibPeak = 0f; private set
    var highSeconds = 0; private set

    fun start() {
        reset()
        linear?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravitySensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
        _vibration.value = VibrationState()
    }

    private fun reset() {
        vibSum = 0.0; vibWindows = 0; vibPeak = 0f; highSeconds = 0
        sumSq = 0.0; samples = 0; windowStart = 0L; highStreak = 0
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> System.arraycopy(e.values, 0, gravity, 0, 3)
            Sensor.TYPE_ACCELEROMETER -> {
                // Raw magnitude including gravity, in g: a crash shows as a large spike.
                val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                if (g > CRASH_G && now - lastImpactAt > 5_000) {
                    lastImpactAt = now
                    onImpact?.invoke(g)
                }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> onLinear(e.values[0], e.values[1], e.values[2])
        }
    }

    private fun onLinear(x: Float, y: Float, z: Float) {
        val now = System.currentTimeMillis()
        val mag2 = (x * x + y * y + z * z).toDouble()
        sumSq += mag2
        samples++
        if (windowStart == 0L) windowStart = now
        if (now - windowStart >= 1000 && samples > 0) {
            val rmsG = (sqrt(sumSq / samples) / SensorManager.GRAVITY_EARTH).toFloat()
            val level = when {
                rmsG >= HIGH_G -> VibrationLevel.HIGH
                rmsG >= MEDIUM_G -> VibrationLevel.MEDIUM
                else -> VibrationLevel.LOW
            }
            highStreak = if (level == VibrationLevel.HIGH) highStreak + 1 else 0
            if (level == VibrationLevel.HIGH) highSeconds++
            vibSum += rmsG; vibWindows++; vibPeak = maxOf(vibPeak, rmsG)
            _vibration.value = VibrationState(rmsG, level, highStreak)
            sumSq = 0.0; samples = 0; windowStart = now
        }

        // Vertical component = linear acceleration projected on gravity.
        val gn = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).coerceAtLeast(0.1f)
        val vertical = (x * gravity[0] + y * gravity[1] + z * gravity[2]) / gn
        val vg = abs(vertical) / SensorManager.GRAVITY_EARTH
        if (vg > JOLT_G && speedKmh() > 15f && now - lastJoltAt > 2_500) {
            lastJoltAt = now
            onJolt?.invoke(vg)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val MEDIUM_G = 0.25f
        const val HIGH_G = 0.6f
        const val JOLT_G = 0.9f
        const val CRASH_G = 4.0f
    }
}
