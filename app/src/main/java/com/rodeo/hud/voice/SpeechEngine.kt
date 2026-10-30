package com.rodeo.hud.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Offline speech recognition: a streaming Zipformer transducer (Kroko English, CC-BY-SA) run by
 * sherpa-onnx (Apache 2.0), shipped inside the APK. No internet, no account, no cost.
 *
 * Unlike the old grammar-limited recogniser it writes down any words, made-up wake names
 * included ("hey doku"), and keeps doing so through engine and wind noise; the wake phrase,
 * commands and SOS words are all found in its text by [VoiceBrain].
 */
class SpeechEngine(private val context: Context) {
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

    @Volatile private var recognizer: OnlineRecognizer? = null
    private var thread: Thread? = null
    private val stopFlag = AtomicBoolean(false)
    private val resetFlag = AtomicBoolean(false)
    @Volatile private var muted = false
    @Volatile private var helmet = false

    /** Loads the model on a background thread (a second or two, once per app start). */
    fun prepare() {
        if (_model.value == ModelState.READY || _model.value == ModelState.LOADING) return
        val names = runCatching { context.assets.list(ASSET_DIR) }.getOrNull().orEmpty()
        if (MODEL_FILES.any { it !in names }) {
            _model.value = ModelState.MISSING
            return
        }
        _model.value = ModelState.LOADING
        Thread({
            val state = runCatching {
                val t0 = System.currentTimeMillis()
                recognizer = OnlineRecognizer(context.assets, config())
                Log.i(TAG, "Speech model loaded in ${System.currentTimeMillis() - t0} ms")
                ModelState.READY
            }.getOrElse {
                Log.w(TAG, "Speech model failed", it)
                ModelState.FAILED
            }
            _model.value = state
        }, "speech-load").start()
    }

    private fun config() = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80, dither = 0f),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "$ASSET_DIR/encoder.onnx", decoder = "$ASSET_DIR/decoder.onnx", joiner = "$ASSET_DIR/joiner.onnx",
            ),
            tokens = "$ASSET_DIR/tokens.txt",
            numThreads = 2,
            provider = "cpu",
        ),
        // An utterance ends after half a second of quiet (once something was said), or 2.4 s of
        // nothing at all, or 15 s in any case.
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, 0.5f, 0f),
            rule3 = EndpointRule(false, 0f, 15f),
        ),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
    )

    val ready: Boolean get() = recognizer != null

    /** Starts listening. Safe to call repeatedly. */
    fun start() {
        if (thread?.isAlive == true || recognizer == null) return
        stopFlag.set(false)
        val t = Thread({ loop() }, "speech-mic")
        t.priority = Thread.MAX_PRIORITY
        thread = t
        t.start()
    }

    fun stop() {
        stopFlag.set(true)
        thread = null
    }

    /** Forget the utterance in progress, e.g. after the assistant acted on it. */
    fun resetOpen() = resetFlag.set(true)

    /** Ignore the microphone while Rodeo itself is talking, so it doesn't hear its own voice. */
    fun mute(on: Boolean) { muted = on }

    /** Listen through the helmet's Bluetooth microphone (call-quality link) instead of the phone. */
    fun useHelmet(on: Boolean) {
        if (helmet == on) return
        helmet = on
        if (thread?.isAlive == true) {
            stop()
            main.postDelayed({ start() }, 150)
        }
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val r = recognizer ?: return
        var rec: AudioRecord? = null
        var sco = false
        val stream = r.createStream("")
        try {
            var input: AudioDeviceInfo? = null
            if (helmet) {
                input = startSco()
                sco = input != null
            }
            val source = if (sco) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, RATE))
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                post(Event.MicError("Microphone unavailable"))
                return
            }
            input?.let { rec.setPreferredDevice(it) }
            rec.startRecording()
            _running.value = true
            Log.i(TAG, "Listening (${if (sco) "helmet" else "phone"} microphone)")
            val buf = ShortArray(RATE / 10)
            val clean = ShortArray(buf.size)
            val samples = FloatArray(buf.size)
            val highPass = HighPass()
            var last = ""
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
                // Engine thump and wind rumble sit below speech; take them out before recognition.
                highPass.process(buf, n, clean)
                if (muted) samples.fill(0f, 0, n) else for (i in 0 until n) samples[i] = clean[i] / 32768f
                if (resetFlag.getAndSet(false)) { r.reset(stream); last = "" }
                stream.acceptWaveform(if (n == samples.size) samples else samples.copyOf(n), RATE)
                while (r.isReady(stream)) r.decode(stream)
                val text = r.getResult(stream).text.trim()
                if (text != last && text.isNotEmpty()) {
                    last = text
                    post(Event.Partial(text, VoiceBrain.OPEN))
                }
                if (r.isEndpoint(stream)) {
                    if (text.isNotEmpty()) {
                        Log.i(TAG, "Heard: $text")
                        post(Event.Final(listOf(text), VoiceBrain.OPEN))
                    }
                    r.reset(stream)
                    last = ""
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Voice loop failed", e)
            post(Event.MicError(e.message ?: "Voice stopped"))
        } finally {
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            runCatching { stream.release() }
            if (sco) stopSco()
            _level.value = 0f
            _running.value = false
            Log.i(TAG, "Stopped listening")
        }
    }

    private fun post(e: Event) = main.post { onEvent(e) }

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
        const val TAG = "RodeoVoice"
        const val ASSET_DIR = "speech"
        private const val RATE = 16_000
        private val MODEL_FILES = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    }
}
