package com.rodeo.hud.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper

/**
 * Hand waves over the top of the phone, read from the proximity sensor next to the earpiece.
 * It is a hardware on/off signal, so it works with gloves, in the dark and in full sun:
 *
 *  - one wave, or a palm held over it for a moment: play / pause
 *  - two quick waves: next song
 *  - three quick waves: previous song
 *
 * A cover that lasts more than [WaveCounter.MAX_COVER_MS] (rain on the sensor, a mount arm, a pocket) is
 * ignored until the sensor clears.
 */
class WaveSensor(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val main = Handler(Looper.getMainLooper())
    private val counter = WaveCounter { n -> onWaves(n) }
    private var running = false

    val available: Boolean get() = sensor != null
    var onWaves: (Int) -> Unit = {}

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val s = sensor ?: return
            // Most sensors report 0 (near) or their max range (far); some report centimetres.
            val near = e.values[0] < minOf(s.maximumRange, 4f)
            counter.onChange(near, System.currentTimeMillis())
            main.removeCallbacks(flush)
            main.postDelayed(flush, WaveCounter.GAP_MS + 20)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val flush = Runnable { counter.flush(System.currentTimeMillis()) }

    fun start() {
        val s = sensor ?: return
        if (running) return
        running = true
        sm?.registerListener(listener, s, SensorManager.SENSOR_DELAY_GAME, main)
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(listener)
        main.removeCallbacks(flush)
        counter.reset()
    }
}
