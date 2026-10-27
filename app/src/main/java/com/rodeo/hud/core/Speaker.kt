package com.rodeo.hud.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone's own text-to-speech (free, offline once the voice is installed), for turn
 * instructions, assistant replies and SOS warnings. Navigation speech ducks the music; the
 * assistant has already paused it by the time it speaks.
 */
class Speaker(context: Context) {
    enum class Channel { NAV, ASSISTANT, ALERT }

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queued = ArrayList<Triple<String, Channel, (() -> Unit)?>>()
    private val callbacks = HashMap<String, () -> Unit>()
    private var navFocus: AudioFocusRequest? = null
    private var outstandingNav = 0

    /** Rider muted the turn voice on the turn card. */
    @Volatile var navMuted = false

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(app) { status ->
            main.post {
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.let { t ->
                        pickVoice(t)
                        t.setSpeechRate(1.05f)
                        t.setOnUtteranceProgressListener(listener)
                    }
                    queued.toList().forEach { (text, ch, done) -> say(text, ch, done = done) }
                } else queued.toList().forEach { it.third?.invoke() }
                queued.clear()
            }
        }
    }

    /** Indian English where the phone has it, then British, then whatever is installed. */
    private fun pickVoice(t: TextToSpeech) {
        for (loc in listOf(Locale("en", "IN"), Locale.UK, Locale.US)) {
            val ok = runCatching { t.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
            if (ok) {
                t.language = loc
                return
            }
        }
    }

    fun warmUp() = ensure()

    /**
     * Speak [text]. [flush] drops anything still queued (a newer instruction replaces an
     * older one). [done] runs on the main thread when the speech ends or fails.
     */
    fun say(text: String, channel: Channel = Channel.NAV, flush: Boolean = false, done: (() -> Unit)? = null) {
        if (channel == Channel.NAV && navMuted) { done?.invoke(); return }
        ensure()
        val t = tts
        if (t == null || !ready) {
            if (flush) queued.removeAll { it.second == channel }
            queued += Triple(text, channel, done)
            return
        }
        val usage = when (channel) {
            Channel.NAV -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            Channel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
            Channel.ALERT -> AudioAttributes.USAGE_ALARM
        }
        t.setAudioAttributes(AudioAttributes.Builder().setUsage(usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val id = "${channel.name}-${ids.incrementAndGet()}"
        if (done != null) callbacks[id] = done
        if (channel == Channel.NAV) {
            duck()
            outstandingNav++
        }
        val params = Bundle().apply {
            if (channel == Channel.ALERT) putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
        }
        val r = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
        if (r != TextToSpeech.SUCCESS) finished(id)
    }

    fun stop() {
        runCatching { tts?.stop() }
        queued.clear()
        callbacks.values.toList().also { callbacks.clear() }.forEach { it() }
        outstandingNav = 0
        releaseDuck()
    }

    val speaking: Boolean get() = runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit
        override fun onDone(utteranceId: String?) { main.post { finished(utteranceId) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { finished(utteranceId) } }
        override fun onError(utteranceId: String?, errorCode: Int) { main.post { finished(utteranceId) } }
        override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { finished(utteranceId) } }
    }

    private fun finished(id: String?) {
        if (id == null) return
        callbacks.remove(id)?.invoke()
        if (id.startsWith(Channel.NAV.name)) {
            outstandingNav = (outstandingNav - 1).coerceAtLeast(0)
            if (outstandingNav == 0) releaseDuck()
        }
    }

    /** Lower the music while a turn instruction plays; it comes back up straight after. */
    private fun duck() {
        if (navFocus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        if (runCatching { audio.requestAudioFocus(req) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            navFocus = req
        }
    }

    private fun releaseDuck() {
        navFocus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        navFocus = null
    }
}
