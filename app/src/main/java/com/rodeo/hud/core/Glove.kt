package com.rodeo.hud.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
