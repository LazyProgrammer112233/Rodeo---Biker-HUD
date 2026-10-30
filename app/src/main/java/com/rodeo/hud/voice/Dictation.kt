package com.rodeo.hud.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One-off dictation with the phone's own speech service (free), used for place names, which the
 * small offline model finds hard. The assistant pauses music and its own microphone first.
 */
class Dictation(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    /** [onPartial] streams what is heard; [onDone] gets the best guesses (empty on failure). */
    fun listen(onPartial: (String) -> Unit, onDone: (List<String>) -> Unit) {
        stop()
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return onDone(emptyList())
        recognizer = r
        var finished = false
        fun done(list: List<String>) {
            if (finished) return
            finished = true
            onDone(list)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) = done(emptyList())
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) =
                done(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { r.startListening(intent) }.onFailure { done(emptyList()) }
    }

    fun stop() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }
}
