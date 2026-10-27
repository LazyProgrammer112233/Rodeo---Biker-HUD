package com.rodeo.hud.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.content.ContextCompat
import com.rodeo.hud.AppGraph
import com.rodeo.hud.core.Speaker
import com.rodeo.hud.data.ListenMode
import com.rodeo.hud.data.Profile
import com.rodeo.hud.nav.Navigator
import com.rodeo.hud.nav.Place
import com.rodeo.hud.nav.Places
import com.rodeo.hud.sos.SosReason
import com.rodeo.hud.sos.SosState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the voice panel and Settings show. */
data class AssistantUi(
    /** Listening in the background for the wake phrase. */
    val listening: Boolean = false,
    /** Waiting for a command right now. */
    val awake: Boolean = false,
    val heard: String = "",
    val reply: String = "",
    val model: VoskEngine.ModelState = VoskEngine.ModelState.MISSING,
    val error: String? = null,
    val training: Training? = null,
)

/** Settings: the rider says the wake phrase three times so Rodeo learns how it hears them. */
data class Training(val heard: List<String> = emptyList(), val matched: Int = 0, val done: Boolean = false, val testOnly: Boolean = false)

/** Things only the screen can do (the ride service needs the activity to start in the foreground). */
interface AssistantHost {
    fun startRide()
    fun endRideNow()
}

/**
 * Rodeo's voice assistant. Offline wake phrase and commands ([VoskEngine] + [VoiceBrain]); music
 * pauses the moment it wakes and comes back when it is done; voice SOS is the lifeline.
 */
class Assistant(private val context: Context, private val g: AppGraph) : VoiceBrain.Output {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val engine = VoskEngine(context)
    private val dictation = Dictation(context)
    private val brain = VoiceBrain(WakeMatcher("Hey Rodeo"), this)
    private val _ui = MutableStateFlow(AssistantUi())
    val ui: StateFlow<AssistantUi> = _ui.asStateFlow()
    val level: StateFlow<Float> get() = engine.level

    var host: AssistantHost? = null
    /** The HUD is on screen (the activity is started). */
    private var visible = false
    private var focus: AudioFocusRequest? = null
    private var pausedMusic = false
    private var expectDestination = false
    private var replyJob: Job? = null
    private var tickJob: Job? = null
    private var lastSos: SosState = SosState.Idle
    private var restartAt = 0L

    init {
        engine.onEvent = ::onEngine
        g.scope.launch {
            engine.modelState.collect { m ->
                _ui.update { it.copy(model = m) }
                sync()
            }
        }
        g.scope.launch {
            g.profile.profile.map { Triple(it.wakePhrase, it.wakeAliases, it.sosWithoutWake) }.distinctUntilChanged().collect { (phrase, aliases, sos) ->
                brain.setWake(WakeMatcher(phrase, aliases))
                engine.setWake(phrase, aliases)
                brain.repeatedSosEnabled = sos
            }
        }
        g.scope.launch { g.profile.profile.map { it.listenMode }.distinctUntilChanged().collect { sync() } }
        g.scope.launch { g.ride.live.map { it.active }.distinctUntilChanged().collect { sync() } }
        g.scope.launch { g.sos.state.collect { onSos(it) } }
        g.scope.launch { engine.running.collect { r -> _ui.update { it.copy(listening = r) } } }
    }

    val micAllowed: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun setVisible(v: Boolean) {
        visible = v
        if (v) engine.prepare()
        sync()
    }

    /** Starts or stops the microphone to match settings, ride state and SOS. */
    fun sync() {
        val p = g.profile.profile.value
        val countdown = g.sos.state.value is SosState.Countdown
        val training = _ui.value.training?.done == false
        val awake = brain.mode == VoiceBrain.Mode.AWAKE
        val wanted = micAllowed && engine.ready && (
            countdown || training || awake || expectDestination ||
                (visible && p.listenMode == ListenMode.ALWAYS) ||
                (p.listenMode != ListenMode.OFF && g.ride.live.value.active)
            )
        if (wanted) {
            engine.start()
            if (tickJob?.isActive != true) tickJob = g.scope.launch {
                while (isActive) {
                    brain.tick(System.currentTimeMillis())
                    delay(100)
                }
            }
        } else {
            engine.stop()
            tickJob?.cancel()
        }
    }

    // --- Ways to wake it ---

    /** The Voice tile, a handlebar remote key, or the helmet's assistant button. */
    fun wakeManually() {
        if (!micAllowed) return
        if (!engine.ready) {
            // No offline model in this build: fall back to the phone's own recogniser.
            onWake()
            dictate { texts -> handleDictated(texts) }
            return
        }
        brain.wakeManually(System.currentTimeMillis())
        sync()
    }

    /** The rider tapped away the voice panel. */
    fun dismiss() {
        brain.cancelAwake()
        expectDestination = false
        dictation.stop()
        done("", speak = false)
    }

    /** The microphone button in the map search: say a place name. */
    fun askForDestination() {
        if (!micAllowed) return
        expectDestination = true
        onWake()
        say("Where to?", Speaker.Channel.ASSISTANT) {
            if (dictation.available) {
                dictate { texts ->
                    expectDestination = false
                    texts.firstOrNull()?.let { navigateTo(it) } ?: done("Didn't catch the place", speak = true)
                }
            } else sync() // offline model takes the next utterance as the place
        }
    }

    private fun dictate(onDone: (List<String>) -> Unit) {
        engine.stop() // the phone's recogniser needs the microphone to itself
        dictation.listen(
            onPartial = { t -> _ui.update { it.copy(heard = t) } },
            onDone = { texts ->
                onDone(texts)
                sync()
            },
        )
    }

    private fun handleDictated(texts: List<String>) {
        val p = texts.map { Commands.parse(it) }.firstOrNull { it.command != VoiceCommand.UNKNOWN }
        if (p != null) onCommand(p, texts.first()) else done(if (texts.isEmpty()) "" else "Sorry, I didn't get that", speak = texts.isNotEmpty())
    }

    // --- Training and testing the wake phrase (Settings) ---

    fun startTraining(testOnly: Boolean = false) {
        if (!micAllowed) return
        engine.prepare()
        _ui.update { it.copy(training = Training(testOnly = testOnly), heard = "") }
        stopMusicForAssistant()
        sync()
        g.scope.launch {
            delay(25_000)
            if (_ui.value.training?.done == false) finishTraining()
        }
    }

    fun cancelTraining() {
        if (_ui.value.training == null) return
        _ui.update { it.copy(training = null) }
        releaseFocus(resume = true)
        sync()
    }

    private fun onTrainingFinal(alternatives: List<String>) {
        val t = _ui.value.training ?: return
        val text = alternatives.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        val p = g.profile.profile.value
        // Training measures the bare phrase; a test measures what will be used on the road.
        val matcher = WakeMatcher(p.wakePhrase, if (t.testOnly) p.wakeAliases else emptyList())
        val plain = alternatives.any { matcher.find(it) != null }
        val next = t.copy(heard = t.heard + text, matched = t.matched + if (plain) 1 else 0)
        _ui.update { it.copy(training = next, heard = text) }
        if (next.heard.size >= 3) finishTraining()
    }

    private fun finishTraining() {
        val t = _ui.value.training ?: return
        if (!t.testOnly && t.heard.isNotEmpty()) {
            val aliases = t.heard.map { it.trim().lowercase() }.filter { Phonetic.words(it).size in 1..6 }.distinct()
            g.profile.update { it.copy(wakeAliases = aliases) }
        }
        _ui.update { it.copy(training = t.copy(done = true)) }
        releaseFocus(resume = true)
        sync()
    }

    // --- Engine events ---

    private fun onEngine(e: VoskEngine.Event) {
        val now = System.currentTimeMillis()
        val training = _ui.value.training
        when (e) {
            is VoskEngine.Event.Partial -> {
                if (training != null && !training.done) { if (e.source == VoiceBrain.OPEN) _ui.update { it.copy(heard = e.text) }; return }
                if (expectDestination && e.source == VoiceBrain.OPEN) { _ui.update { it.copy(heard = e.text) }; return }
                brain.partial(e.text, now, e.source)
            }
            is VoskEngine.Event.Final -> {
                if (training != null && !training.done) { if (e.source == VoiceBrain.OPEN) onTrainingFinal(e.alternatives); return }
                if (expectDestination && e.source == VoiceBrain.OPEN) {
                    val place = e.alternatives.firstOrNull() ?: return
                    expectDestination = false
                    navigateTo(place)
                    return
                }
                brain.final(e.alternatives, now, e.source)
            }
            is VoskEngine.Event.MicError -> {
                _ui.update { it.copy(error = e.message) }
                // Another app may have taken the mic for a moment (a call); try again shortly.
                if (now - restartAt > 5_000) {
                    restartAt = now
                    g.scope.launch { delay(3_000); _ui.update { it.copy(error = null) }; sync() }
                }
            }
        }
    }

    // --- VoiceBrain.Output ---

    override fun onWake() {
        replyJob?.cancel()
        _ui.update { it.copy(awake = true, heard = "", reply = "") }
        engine.setCommands(true)
        stopMusicForAssistant()
        g.scope.launch {
            delay(220)
            tone(ToneGenerator.TONE_PROP_ACK)
            if (g.profile.profile.value.helmetMic && g.audioRoutes.route.value.btMic) engine.useHelmet(true)
        }
        sync()
    }

    override fun onHeard(text: String) {
        _ui.update { it.copy(heard = text) }
    }

    override fun onNothing(heard: String, final: Boolean) {
        if (!final) {
            tone(ToneGenerator.TONE_PROP_NACK)
            say("Sorry, say that again?", Speaker.Channel.ASSISTANT)
            _ui.update { it.copy(reply = "Say that again?") }
        } else done(if (heard.isBlank()) "" else "Sorry, I didn't get that", speak = heard.isNotBlank())
    }

    override fun onCommand(p: Parsed, heard: String) {
        engine.setCommands(brain.mode == VoiceBrain.Mode.COUNTDOWN)
        engine.resetOpen()
        val s = g.sos.state.value
        when (p.command) {
            VoiceCommand.SOS -> when (s) {
                is SosState.Countdown -> { g.sos.fireNow(); done("Sending SOS now", speak = true, resume = false, channel = Speaker.Channel.ALERT) }
                is SosState.Active -> done("SOS is already active", speak = true, resume = false, channel = Speaker.Channel.ALERT)
                SosState.Idle -> {
                    g.sos.startCountdown(SosReason.MANUAL)
                    done("Emergency. Sending SOS in 10 seconds. Say cancel to stop.", speak = true, resume = false, channel = Speaker.Channel.ALERT)
                }
            }
            VoiceCommand.CANCEL -> when (s) {
                is SosState.Countdown -> { g.sos.cancel(); done("SOS cancelled", speak = true, channel = Speaker.Channel.ALERT) }
                is SosState.Active -> done("SOS is live. Slide on the screen to stop it.", speak = true, resume = false)
                SosState.Idle -> done("Okay", speak = false)
            }
            VoiceCommand.NEXT -> { done("Next song", speak = false); g.music.next() }
            VoiceCommand.PREVIOUS -> { done("Previous song", speak = false); g.music.previous() }
            VoiceCommand.PAUSE -> { pausedMusic = false; done("Music paused", speak = false, resume = false); g.music.pause() }
            VoiceCommand.PLAY -> { done("Playing", speak = false, resume = false); g.music.play() }
            VoiceCommand.NAVIGATE_HOME -> {
                val pr = g.profile.profile.value
                if (pr.homeLat != null && pr.homeLng != null) {
                    g.nav.navigateTo(Place(pr.homeLabel.ifBlank { "Home" }, "", pr.homeLat, pr.homeLng))
                    done("Navigating home", speak = true)
                } else done("Home isn't set yet. Add it in Settings.", speak = true)
            }
            VoiceCommand.NAVIGATE_TO -> navigateTo(p.arg)
            VoiceCommand.FIND_BIKE -> {
                val parked = g.rides.lastParked()
                if (parked?.parkLat != null && parked.parkLng != null) {
                    g.nav.navigateTo(Place("Parked bike", "", parked.parkLat, parked.parkLng), walking = true)
                    done("Showing the way to your bike", speak = true)
                } else done("No parking spot saved yet", speak = true)
            }
            VoiceCommand.STOP_NAVIGATION -> { g.nav.stop(); done("Navigation stopped", speak = true) }
            VoiceCommand.MUTE_GUIDANCE -> { if (g.nav.state.value.voiceOn) g.nav.toggleVoice(); done("Directions muted", speak = true) }
            VoiceCommand.UNMUTE_GUIDANCE -> { if (!g.nav.state.value.voiceOn) g.nav.toggleVoice(); done("Directions on", speak = true) }
            VoiceCommand.START_RIDE -> {
                if (g.ride.live.value.active) done("Your ride is already running", speak = true)
                else { host?.startRide(); done("Ride started. Ride safe.", speak = true) }
            }
            VoiceCommand.END_RIDE -> {
                if (!g.ride.live.value.active) done("No ride is running", speak = true)
                else {
                    val km = g.ride.live.value.distanceM / 1000
                    host?.endRideNow()
                    done("Ride saved. ${"%.1f".format(Locale.US, km)} kilometres.", speak = true)
                }
            }
            VoiceCommand.DISMISS -> { g.warnings.dismissTop(); done("Cleared", speak = false) }
            VoiceCommand.VOLUME_UP -> { volume(AudioManager.ADJUST_RAISE); done("Louder", speak = false) }
            VoiceCommand.VOLUME_DOWN -> { volume(AudioManager.ADJUST_LOWER); done("Quieter", speak = false) }
            VoiceCommand.SPEED -> done("${g.ride.speedKmh.value.toInt()} kilometres per hour", speak = true)
            VoiceCommand.TIME -> done("It's " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date()), speak = true)
            VoiceCommand.BATTERY -> done("Phone battery ${g.guardian.health.value.batteryPct} percent", speak = true)
            VoiceCommand.ETA -> {
                val pr = g.nav.state.value.progress
                if (pr != null && !pr.arrived) {
                    done("${Navigator.spokenDistance(pr.remainingM)} to go, about ${Navigator.spokenDuration(pr.remainingS)}", speak = true)
                } else done("No route is set", speak = true)
            }
            VoiceCommand.UNKNOWN -> done("Sorry, I didn't get that", speak = true)
        }
    }

    private fun navigateTo(query: String) {
        if (query.isBlank()) { done("Where to?", speak = true); return }
        _ui.update { it.copy(reply = "Finding $query…") }
        g.scope.launch {
            val here = g.location.location.value
            val found = runCatching { Places.search(context, query, here?.latitude, here?.longitude) }.getOrDefault(emptyList())
            val place = found.firstOrNull()
            if (place == null) done("I couldn't find $query", speak = true)
            else {
                g.nav.navigateTo(place)
                done("Navigating to ${place.title}", speak = true)
            }
        }
    }

    // --- SOS countdown: listen for "I'm okay" without the wake phrase ---

    private fun onSos(s: SosState) {
        val prev = lastSos
        lastSos = s
        val now = System.currentTimeMillis()
        when (s) {
            is SosState.Countdown -> if (prev !is SosState.Countdown) {
                brain.setCountdown(true, now)
                engine.prepare()
                engine.setCommands(true)
                if (s.reason == SosReason.CRASH) {
                    say("Possible crash detected. Sending SOS in ${s.secondsLeft} seconds. Say I'm okay to cancel.", Speaker.Channel.ALERT, flush = true)
                }
            }
            is SosState.Active -> if (prev !is SosState.Active) {
                brain.setCountdown(false, now)
                engine.setCommands(false)
                val n = g.profile.profile.value.sosContacts.size
                if (!s.dryRun) say("SOS sent to $n contact${if (n == 1) "" else "s"} with your location.", Speaker.Channel.ALERT)
            }
            SosState.Idle -> if (prev !is SosState.Idle) {
                brain.setCountdown(false, now)
                engine.setCommands(false)
            }
        }
        sync()
    }

    // --- Music, speech and focus ---

    /** Pause whatever is playing straight away; most apps pause on the focus request alone. */
    private fun stopMusicForAssistant() {
        if (focus == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener { }
                .build()
            runCatching { audio.requestAudioFocus(req) }
            focus = req
        }
        g.scope.launch {
            delay(200)
            // Some players only lower the volume; pause those directly and resume them after.
            if (focus != null && audio.isMusicActive && !pausedMusic) {
                pausedMusic = true
                g.music.pause()
            }
        }
    }

    private fun releaseFocus(resume: Boolean) {
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        if (pausedMusic && resume) g.music.play()
        pausedMusic = false
    }

    /** End of an exchange: show the reply, maybe say it, then give the music back. */
    private fun done(reply: String, speak: Boolean, resume: Boolean = true, channel: Speaker.Channel = Speaker.Channel.ASSISTANT) {
        engine.useHelmet(false)
        _ui.update { it.copy(awake = false, reply = reply) }
        val finish = {
            releaseFocus(resume)
            replyJob?.cancel()
            replyJob = g.scope.launch {
                delay(1_800)
                _ui.update { it.copy(reply = "", heard = "") }
            }
            sync()
        }
        if (speak && reply.isNotBlank()) say(reply, channel, flush = true) { finish() } else finish()
    }

    private fun say(text: String, channel: Speaker.Channel, flush: Boolean = false, after: (() -> Unit)? = null) {
        engine.mute(true)
        g.speaker.say(text, channel, flush) {
            g.scope.launch {
                delay(250)
                engine.mute(false)
                after?.invoke()
            }
        }
    }

    private fun tone(kind: Int) {
        runCatching {
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            t.startTone(kind, 160)
            g.scope.launch { delay(400); t.release() }
        }
    }

    private fun volume(direction: Int) {
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
    }

    fun wakePhraseHint(p: Profile = g.profile.profile.value): String = p.wakePhrase.trim().ifBlank { "Hey Rodeo" }
}
