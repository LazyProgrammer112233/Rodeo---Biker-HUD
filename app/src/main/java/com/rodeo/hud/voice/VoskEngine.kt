package com.rodeo.hud.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Offline speech recognition with Vosk and its small Indian-English model, shipped inside the
 * APK: no internet, no account, no cost, and no beeps or audio-focus grabs from the Google app.
 *
 * One microphone stream, high-passed to take out engine and wind rumble, feeds up to three
 * recognisers: an open one (any words, for the wake phrase and destinations), a keyword spotter
 * that only knows the wake phrase and SOS words (it keeps hearing them in noise), and, while a
 * command is expected, one limited to the command phrases.
 */
class VoskEngine(private val context: Context) {
    enum class ModelState { MISSING, LOADING, READY, FAILED }

    sealed interface Event {
        data class Partial(val text: String, val source: Int) : Event
        data class Final(val alternatives: List<String>, val source: Int) : Event
        data class MicError(val message: String) : Event
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _model = MutableStateFlow(ModelState.MISSING)
    val modelState: StateFlow<ModelState> = _model.asStateFlow()
    private val _level = MutableStateFlow(0f)
    /** Microphone loudness 0..1 for the on-screen meter. */
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    var onEvent: (Event) -> Unit = {}

    @Volatile private var model: Model? = null
    private var thread: Thread? = null
    private val stopFlag = AtomicBoolean(false)
    private val commandsOn = AtomicBoolean(false)
    private val commandsReset = AtomicBoolean(false)
    private val openReset = AtomicBoolean(false)
    @Volatile private var muted = false
    @Volatile private var helmet = false
    @Volatile var grammar: List<String> = Commands.GRAMMAR
    @Volatile private var wake: Pair<String, List<String>> = "Hey Rodeo" to emptyList()
    private val keywordsChanged = AtomicBoolean(true)

    /** The rider's wake phrase and what training heard; rebuilds the keyword spotter. */
    fun setWake(phrase: String, aliases: List<String>) {
        wake = phrase to aliases
        keywordsChanged.set(true)
    }

    /** Unpacks (first run only) and loads the model on a background thread. */
    fun prepare() {
        if (_model.value == ModelState.READY || _model.value == ModelState.LOADING) return
        _model.value = ModelState.LOADING
        Thread({
            val state = runCatching {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val dir = unpack() ?: return@runCatching ModelState.MISSING
                model = Model(dir.absolutePath)
                ModelState.READY
            }.getOrElse {
                Log.w(TAG, "Vosk model failed", it)
                ModelState.FAILED
            }
            _model.value = state
        }, "vosk-load").start()
    }

    /** Copies assets/vosk-model to app storage once per app version (Vosk needs real files). */
    private fun unpack(): File? {
        val assets = context.assets
        val names = runCatching { assets.list(ASSET_DIR) }.getOrNull()
        if (names.isNullOrEmpty()) return null
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        val dest = File(context.filesDir, "vosk-model")
        val stamp = File(dest, ".stamp")
        if (stamp.exists() && stamp.readText() == version.toString()) return dest
        dest.deleteRecursively()
        copyDir(ASSET_DIR, dest)
        stamp.writeText(version.toString())
        return dest
    }

    private fun copyDir(path: String, to: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        children.forEach { copyDir("$path/$it", File(to, it)) }
    }

    val ready: Boolean get() = model != null

    /** Starts listening. Safe to call repeatedly. */
    fun start() {
        if (thread?.isAlive == true || model == null) return
        stopFlag.set(false)
        val t = Thread({ loop() }, "vosk-mic")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    fun stop() {
        stopFlag.set(true)
        thread = null
    }

    /** Turn the command recogniser on (fresh) or off. */
    fun setCommands(on: Boolean) {
        commandsOn.set(on)
        if (on) commandsReset.set(true)
    }

    /** Forget the utterance in progress, e.g. after the assistant acted on it. */
    fun resetOpen() = openReset.set(true)

    /** Ignore the microphone while Rodeo itself is talking, so it doesn't hear its own voice. */
    fun mute(on: Boolean) { muted = on }

    /** Listen through the helmet's Bluetooth microphone (call-quality link) instead of the phone. */
    fun useHelmet(on: Boolean) {
        if (helmet == on) return
        helmet = on
        // Restart the stream on the new input.
        if (thread?.isAlive == true) {
            stop()
            main.postDelayed({ start() }, 150)
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val m = model ?: return
        val rate = 16_000
        var open: Recognizer? = null
        var cmd: Recognizer? = null
        var kws: Recognizer? = null
        var rec: AudioRecord? = null
        var ns: NoiseSuppressor? = null
        var sco = false
        try {
            open = Recognizer(m, rate.toFloat()).apply {
                setMaxAlternatives(3)
                setEndpointerMode(ENDPOINT_SHORT)
            }
            var input: AudioDeviceInfo? = null
            if (helmet) {
                input = startSco()
                sco = input != null
            }
            val source = if (sco) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                post(Event.MicError("Microphone unavailable"))
                return
            }
            input?.let { rec.setPreferredDevice(it) }
            // Phone mic on a handlebar: take the edge off wind and engine noise where the phone can.
            if (!sco && NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { setEnabled(true) } }.getOrNull()
            rec.startRecording()
            _running.value = true
            val buf = ShortArray(rate / 10)
            val clean = ShortArray(buf.size)
            val silence = ShortArray(buf.size)
            val highPass = HighPass()
            var lastPartial = ""
            var lastCmdPartial = ""
            var lastKwsPartial = ""
            var errors = 0
            while (!stopFlag.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (++errors > 20) { post(Event.MicError("Microphone stopped ($n)")); break }
                    Thread.sleep(50)
                    continue
                }
                errors = 0
                var sum = 0.0
                for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                _level.value = (sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f)
                highPass.process(buf, n, clean)
                // While Rodeo speaks, feed silence: the recogniser closes the utterance cleanly.
                val data = if (muted) silence else clean

                if (keywordsChanged.getAndSet(false)) {
                    runCatching { kws?.close() }
                    val (phrase, aliases) = wake
                    val words = Commands.keywordGrammar(phrase, aliases)
                    kws = if (words.isEmpty()) null else runCatching {
                        Recognizer(m, rate.toFloat(), JSONArray().apply { words.forEach { put(it) }; put("[unk]") }.toString())
                            .apply { setEndpointerMode(ENDPOINT_SHORT) }
                    }.getOrNull()
                    lastKwsPartial = ""
                }
                val k = kws
                if (k != null) {
                    if (k.acceptWaveForm(data, n)) {
                        post(Event.Final(alternatives(k.result), VoiceBrain.KEYWORDS))
                        lastKwsPartial = ""
                    } else {
                        val p = partial(k.partialResult)
                        if (p != lastKwsPartial) { lastKwsPartial = p; if (p.isNotBlank()) post(Event.Partial(p, VoiceBrain.KEYWORDS)) }
                    }
                }

                if (openReset.getAndSet(false)) { open.reset(); lastPartial = "" }
                if (open.acceptWaveForm(data, n)) {
                    post(Event.Final(alternatives(open.result), VoiceBrain.OPEN))
                    lastPartial = ""
                } else {
                    val p = partial(open.partialResult)
                    if (p != lastPartial) { lastPartial = p; if (p.isNotBlank()) post(Event.Partial(p, VoiceBrain.OPEN)) }
                }

                if (commandsOn.get()) {
                    if (commandsReset.getAndSet(false) || cmd == null) {
                        if (cmd == null) {
                            cmd = runCatching { Recognizer(m, rate.toFloat(), grammarJson()) }.getOrNull()
                            cmd?.setEndpointerMode(ENDPOINT_SHORT)
                        } else cmd.reset()
                        lastCmdPartial = ""
                    }
                    val c = cmd
                    if (c != null) {
                        if (c.acceptWaveForm(data, n)) {
                            post(Event.Final(alternatives(c.result), VoiceBrain.COMMANDS))
                            lastCmdPartial = ""
                        } else {
                            val p = partial(c.partialResult)
                            if (p != lastCmdPartial) { lastCmdPartial = p; if (p.isNotBlank()) post(Event.Partial(p, VoiceBrain.COMMANDS)) }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Voice loop failed", e)
            post(Event.MicError(e.message ?: "Voice stopped"))
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            runCatching { ns?.release() }
            runCatching { open?.close() }
            runCatching { cmd?.close() }
            runCatching { kws?.close() }
            keywordsChanged.set(true)
            if (sco) stopSco()
            _level.value = 0f
            _running.value = false
        }
    }

    private fun grammarJson(): String = JSONArray().apply {
        grammar.forEach { put(it) }
        put("[unk]")
    }.toString()

    private fun post(e: Event) = main.post { onEvent(e) }

    private fun partial(json: String): String = runCatching { JSONObject(json).optString("partial") }.getOrDefault("").trim()

    private fun alternatives(json: String): List<String> = runCatching {
        val o = JSONObject(json)
        val alts = o.optJSONArray("alternatives")
        if (alts != null) (0 until alts.length()).map { alts.getJSONObject(it).optString("text").trim() }.filter { it.isNotEmpty() && it != "[unk]" }
        else listOf(o.optString("text").trim()).filter { it.isNotEmpty() && it != "[unk]" }
    }.getOrDefault(emptyList())

    /** Opens the helmet's call-quality link and returns its microphone, or null if there is none. */
    private fun startSco(): AudioDeviceInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = audio.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } ?: return@runCatching null
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!audio.setCommunicationDevice(dev)) return@runCatching null
            Thread.sleep(600)
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == dev.type }
        } else {
            @Suppress("DEPRECATION")
            run {
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                audio.startBluetoothSco()
                audio.isBluetoothScoOn = true
                var waited = 0
                while (!audio.isBluetoothScoOn && waited < 30) { Thread.sleep(100); waited++ }
            }
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }
    }.getOrNull()

    private fun stopSco() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            else @Suppress("DEPRECATION") run { audio.stopBluetoothSco(); audio.isBluetoothScoOn = false }
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    companion object {
        private const val TAG = "RodeoVoice"
        const val ASSET_DIR = "vosk-model"
        /** Vosk's short endpointer: an utterance ends after about half a second of quiet. */
        private const val ENDPOINT_SHORT = 1
    }
}
