package com.rodeo.hud.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File

/** A knock as the tap setup screen shows it. */
data class KnockSeen(val knock: Knock, val zone: TapZone?, val at: Long)

/** Last music action from a tap, for the on-screen confirmation. */
data class TapEvent(val action: MusicAction, val zone: TapZone, val taps: Int, val at: Long)

/**
 * Taps on the phone's frame, felt by the accelerometer and gyroscope at their fastest rate (the
 * same idea as Back Tap). Works in the dark, in glare and with gloves. Runs on its own thread while
 * the HUD is on screen.
 */
class TapSensor(private val context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var running = false

    val available: Boolean get() = accel != null

    /** Display rotation (Surface.ROTATION_*), read when a knock arrives. */
    @Volatile var rotation: () -> Int = { Surface.ROTATION_90 }
    @Volatile var onAction: (MusicAction, TapZone, Int) -> Unit = { _, _, _ -> }
    @Volatile var onHint: (TapZone, Int) -> Unit = { _, _ -> }
    /** Knock strength learned in tap setup, or null for the default. */
    var minPeak: Float? = null
        set(v) { field = v; detector.minPeak = v ?: DEFAULT_MIN_PEAK }

    /** Setup mode: knocks are reported (to [seen]) but don't control music. */
    @Volatile var training = false

    val classifier = TapClassifier()
    private val grouper = TapGrouper(
        onAction = { a, z, n -> main.post { _last.value = TapEvent(a, z, n, System.currentTimeMillis()); onAction(a, z, n) } },
        onHint = { z, n -> main.post { onHint(z, n) } },
    )
    private val detector = KnockDetector { k -> onKnock(k) }

    private val _seen = MutableStateFlow<KnockSeen?>(null)
    val seen: StateFlow<KnockSeen?> = _seen.asStateFlow()
    private val _last = MutableStateFlow<TapEvent?>(null)
    val last: StateFlow<TapEvent?> = _last.asStateFlow()

    private val lock = Any()
    private var log: BufferedWriter? = null

    private fun write(line: String) = synchronized(lock) { runCatching { log?.write(line) } }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val t = e.timestamp / 1e6
            if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                detector.gyro(t, e.values[0], e.values[1], e.values[2])
                write("g,%.2f,%.4f,%.4f,%.4f\n".format(t, e.values[0], e.values[1], e.values[2]))
            } else {
                detector.accel(t, e.values[0], e.values[1], e.values[2])
                write("a,%.2f,%.4f,%.4f,%.4f\n".format(t, e.values[0], e.values[1], e.values[2]))
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val tick = object : Runnable {
        override fun run() {
            grouper.flush(System.currentTimeMillis())
            thread?.let { Handler(it.looper).postDelayed(this, 60) }
        }
    }

    /** Features in the frame the templates are kept in: mounted with the phone's top to the left (rotation 90). */
    fun normalise(f: FloatArray): FloatArray = if (rotation() == Surface.ROTATION_270) flipKnock(f) else f

    private fun onKnock(k: Knock) {
        val f = normalise(k.features())
        val zone = classifier.templates?.takeIf { it.ready }?.nearest(f) ?: if (classifier.templates?.ready == true) null else classifier.zone(k)
        Log.i(TAG, "$k zone=$zone training=$training")
        write("k,%d,%s,%s\n".format(k.atMs, f.joinToString(",") { "%.4f".format(it) }, zone))
        main.post { _seen.value = KnockSeen(k, zone, System.currentTimeMillis()) }
        if (!training && zone != null) grouper.knock(zone, System.currentTimeMillis())
    }

    fun start() {
        if (running || accel == null) return
        running = true
        val t = HandlerThread("rodeo-taps").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        detector.reset()
        grouper.reset()
        // Fastest rate: about 400 Hz on current phones (needs HIGH_SAMPLING_RATE_SENSORS on Android 12+).
        sm?.registerListener(listener, accel, SensorManager.SENSOR_DELAY_FASTEST, h)
        gyro?.let { sm?.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
        h.postDelayed(tick, 60)
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
        stopRecording()
    }

    /** Raw sensor log for tuning (tap setup writes one per session into the app's files folder). */
    fun startRecording(name: String): File? {
        stopRecording()
        val dir = context.getExternalFilesDir("taps") ?: return null
        val f = File(dir, "$name.csv")
        synchronized(lock) { log = runCatching { f.bufferedWriter() }.getOrNull() }
        return f
    }

    fun stopRecording() = synchronized(lock) {
        val l = log ?: return@synchronized
        log = null
        runCatching { l.flush(); l.close() }
    }

    companion object {
        const val TAG = "RodeoTaps"
        const val DEFAULT_MIN_PEAK = 2.0f
    }
}
