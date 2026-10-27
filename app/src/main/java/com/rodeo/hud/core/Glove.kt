package com.rodeo.hud.core

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Gesture { WAVE, DOUBLE_WAVE, HOLD }

/**
 * Glove-free control with the proximity sensor at the top of the phone:
 * one wave = next track, two waves = dismiss warning, hold a hand over it = voice command.
 */
class WaveDetector(context: Context, private val onGesture: (Gesture) -> Unit) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private var nearAt = 0L
    private var near = false
    private var waves = 0
    private var holdFired = false

    val available get() = prox != null

    private val flush = Runnable {
        when (waves) {
            0 -> Unit
            1 -> onGesture(Gesture.WAVE)
            else -> onGesture(Gesture.DOUBLE_WAVE)
        }
        waves = 0
    }
    private val hold = Runnable {
        if (near) {
            holdFired = true
            waves = 0
            handler.removeCallbacks(flush)
            onGesture(Gesture.HOLD)
        }
    }

    fun start() { prox?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }

    fun stop() {
        sm.unregisterListener(this)
        handler.removeCallbacksAndMessages(null)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val isNear = e.values[0] < (prox?.maximumRange ?: 5f).coerceAtMost(5f)
        val now = System.currentTimeMillis()
        if (isNear && !near) {
            near = true
            nearAt = now
            holdFired = false
            handler.postDelayed(hold, HOLD_MS)
        } else if (!isNear && near) {
            near = false
            handler.removeCallbacks(hold)
            if (!holdFired && now - nearAt < WAVE_MAX_MS) {
                waves++
                handler.removeCallbacks(flush)
                handler.postDelayed(flush, MULTI_WINDOW_MS)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val WAVE_MAX_MS = 900L
        const val HOLD_MS = 1500L
        const val MULTI_WINDOW_MS = 700L
    }
}

enum class VoiceCommand { NEXT, PREVIOUS, PAUSE, PLAY, FIND_BIKE, START_RIDE, END_RIDE, SOS, NAVIGATE_HOME, DISMISS, UNKNOWN }

/** On-device speech for short rider commands. */
class VoiceCommander(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _heard = MutableStateFlow("")
    val heard: StateFlow<String> = _heard.asStateFlow()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun listen(onCommand: (VoiceCommand, String) -> Unit) {
        if (!available) return
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true; _heard.value = "" }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { _listening.value = false }
            override fun onError(error: Int) { _listening.value = false }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { _heard.value = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle?) {
                _listening.value = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = texts.firstOrNull().orEmpty()
                _heard.value = text
                val cmd = texts.map { parse(it) }.firstOrNull { it != VoiceCommand.UNKNOWN } ?: VoiceCommand.UNKNOWN
                onCommand(cmd, text)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent) }.onFailure { _listening.value = false }
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
        _listening.value = false
    }

    companion object {
        fun parse(raw: String): VoiceCommand {
            val t = raw.lowercase()
            return when {
                "sos" in t || "help" in t || "emergency" in t -> VoiceCommand.SOS
                "next" in t || "skip" in t || "agla" in t -> VoiceCommand.NEXT
                "previous" in t || "back" in t || "pichla" in t -> VoiceCommand.PREVIOUS
                "pause" in t || "stop music" in t || "ruko" in t -> VoiceCommand.PAUSE
                "play" in t || "resume" in t || "chalao" in t -> VoiceCommand.PLAY
                "bike" in t && ("where" in t || "find" in t || "parked" in t) -> VoiceCommand.FIND_BIKE
                "start" in t && "ride" in t -> VoiceCommand.START_RIDE
                ("end" in t || "stop" in t || "finish" in t) && "ride" in t -> VoiceCommand.END_RIDE
                "home" in t -> VoiceCommand.NAVIGATE_HOME
                "dismiss" in t || "clear" in t || "ok" == t.trim() -> VoiceCommand.DISMISS
                else -> VoiceCommand.UNKNOWN
            }
        }
    }
}
