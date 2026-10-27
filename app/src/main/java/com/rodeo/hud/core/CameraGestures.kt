package com.rodeo.hud.core

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Where a gesture came from, for the on-screen confirmation. */
enum class GestureSource { CAMERA, WAVE }

/** Last recognised gesture, for the on-screen confirmation. */
data class GestureEvent(val gesture: HandGesture, val at: Long, val source: GestureSource = GestureSource.CAMERA, val waves: Int = 0)

/**
 * Hand gestures in front of the selfie camera, recognised on the phone by Google's MediaPipe hand
 * model (free, offline). Nothing is recorded or uploaded. If the model can't run on a phone, the
 * older brightness-based detector takes over.
 */
class CameraGestures(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _last = MutableStateFlow<GestureEvent?>(null)
    val last: StateFlow<GestureEvent?> = _last.asStateFlow()

    /** Swap if the mount makes swipes feel reversed. */
    @Volatile var swap = false
    var onGesture: (HandGesture) -> Unit = {}
    private var recognizer: GestureRecognizer? = null
    private val tracker = HandTracker()
    @Volatile private var lastFrameAt = 0L

    val hasPermission get() = ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun start(owner: LifecycleOwner) {
        if (!hasPermission || analysis != null) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false) != true) return@addListener
            val model = recognizer ?: createRecognizer().also { recognizer = it }
            val a = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .apply { if (model != null) setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888) }
                .build()
            if (model != null) {
                a.setAnalyzer(executor) { img -> handFrame(model, img) }
            } else {
                val analyzer = Analyzer()
                a.setAnalyzer(executor) { img -> analyzer.analyze(img) }
            }
            runCatching {
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, a)
                provider = p
                analysis = a
                _running.value = true
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        analysis?.let { a -> provider?.unbind(a); a.clearAnalyzer() }
        analysis = null
        _running.value = false
    }

    /** Proximity-sensor waves show the same confirmation as camera gestures. */
    fun announce(g: HandGesture, waves: Int) {
        _last.value = GestureEvent(g, System.currentTimeMillis(), GestureSource.WAVE, waves)
    }

    /** The hand model, or null if this phone can't run it (the fallback detector is used). */
    private fun createRecognizer(): GestureRecognizer? = runCatching {
        val base = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(base)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ -> onResult(result) }
            .setErrorListener { e -> Log.w(TAG, "Gesture model error", e) }
            .build()
        GestureRecognizer.createFromOptions(context, options)
    }.onFailure { Log.w(TAG, "Gesture model unavailable; using the simple detector", it) }.getOrNull()

    /** Camera thread: upright, mirrored like a selfie so "right" is the rider's right. */
    private fun handFrame(model: GestureRecognizer, img: ImageProxy) {
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAt < 60) return // ~15 fps is plenty and saves battery
            lastFrameAt = now
            val bmp = img.toBitmap()
            val m = Matrix().apply {
                postRotate(img.imageInfo.rotationDegrees.toFloat())
                postScale(-1f, 1f)
            }
            val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            model.recognizeAsync(BitmapImageBuilder(upright).build(), now)
        } catch (e: Exception) {
            Log.w(TAG, "Gesture frame failed", e)
        } finally {
            img.close()
        }
    }

    private fun onResult(r: GestureRecognizerResult) {
        val lm = r.landmarks().firstOrNull()
        val obs = if (lm.isNullOrEmpty()) null else {
            // Centre of the palm: wrist and the knuckles of the index and little fingers.
            val idx = listOf(0, 5, 17).filter { it < lm.size }
            val x = idx.map { lm[it].x() }.average().toFloat()
            val top = r.gestures().firstOrNull()?.firstOrNull()
            HandObservation(x, top?.categoryName() ?: "None", top?.score() ?: 0f)
        }
        tracker.onFrame(SystemClock.uptimeMillis(), obs)?.let { emit(it) }
    }

    private fun emit(g: HandGesture) {
        val gesture = when {
            !swap -> g
            g == HandGesture.SWIPE_LEFT -> HandGesture.SWIPE_RIGHT
            g == HandGesture.SWIPE_RIGHT -> HandGesture.SWIPE_LEFT
            else -> g
        }
        main.post {
            _last.value = GestureEvent(gesture, System.currentTimeMillis())
            onGesture(gesture)
        }
    }

    /** Runs on the camera thread. Works on a 32 x 24 grid of brightness averages. */
    private inner class Analyzer {
        private val cols = 32
        private val rows = 24
        private var prev: FloatArray? = null
        private var baseline = -1f
        private var darkSince = 0L
        private var covered = false
        private var cooldownUntil = 0L
        private val track = ArrayList<Pair<Long, Float>>()
        private var quietSince = 0L

        fun analyze(img: ImageProxy) {
            try {
                val grid = grid(img) ?: return
                val now = System.currentTimeMillis()
                val mean = grid.average().toFloat()
                val spread = sqrt(grid.fold(0.0) { acc, v -> acc + (v - mean) * (v - mean) } / grid.size).toFloat()

                if (palm(mean, spread, now)) { prev = grid; return }
                if (baseline < 0) baseline = mean else if (!covered) baseline = baseline * 0.97f + mean * 0.03f

                val p = prev
                prev = grid
                if (p == null || now < cooldownUntil) return
                swipe(p, grid, img.imageInfo.rotationDegrees, now)
            } finally {
                img.close()
            }
        }

        /** Dark and flat for 0.4 s = palm over the lens; fires once, re-arms when uncovered. */
        private fun palm(mean: Float, spread: Float, now: Long): Boolean {
            val dark = baseline > 0 && (mean < baseline * 0.3f || mean < 18f) && spread < 14f
            if (dark) {
                if (darkSince == 0L) darkSince = now
                if (!covered && now - darkSince >= 400) {
                    covered = true
                    track.clear()
                    emit(HandGesture.PALM_COVER)
                }
                return true
            }
            darkSince = 0L
            if (covered && mean > baseline * 0.55f) {
                covered = false
                cooldownUntil = now + 700 // the hand leaving the lens is not a swipe
            }
            return covered
        }

        private fun swipe(a: FloatArray, b: FloatArray, rotation: Int, now: Long) {
            var count = 0
            var sum = 0f
            for (r in 0 until rows) for (c in 0 until cols) {
                val i = r * cols + c
                if (abs(b[i] - a[i]) > 22f) {
                    count++
                    sum += screenX(c, r, rotation)
                }
            }
            val moving = count >= cols * rows * 0.06f
            if (moving) {
                quietSince = 0L
                track += now to (sum / count)
                if (now - track.first().first > 1_000) track.removeAt(0)
            } else {
                if (quietSince == 0L) quietSince = now
                if (now - quietSince > 120 && track.isNotEmpty()) finish(now)
            }
        }

        private fun finish(now: Long) {
            val pts = track.toList()
            track.clear()
            if (pts.size < 3) return
            val duration = pts.last().first - pts.first().first
            val travel = pts.last().second - pts.first().second
            var same = 0
            for (i in 1 until pts.size) if ((pts[i].second - pts[i - 1].second) * travel > 0) same++
            val steady = same >= (pts.size - 1) * 0.6f
            if (duration in 100..1_000 && abs(travel) > 0.35f && steady) {
                cooldownUntil = now + 900
                // The selfie camera sees the rider mirrored: a hand moving to the rider's right
                // moves left in the camera's upright frame.
                emit(if (travel < 0) HandGesture.SWIPE_RIGHT else HandGesture.SWIPE_LEFT)
            }
        }

        /** Horizontal position (0..1) of a grid cell as it appears upright on screen. */
        private fun screenX(c: Int, r: Int, rotation: Int): Float {
            val x = (c + 0.5f) / cols
            val y = (r + 0.5f) / rows
            return when (rotation) {
                90 -> 1f - y
                180 -> 1f - x
                270 -> y
                else -> x
            }
        }

        private fun grid(img: ImageProxy): FloatArray? {
            val plane = img.planes.firstOrNull() ?: return null
            val buf = plane.buffer
            val stride = plane.rowStride
            val pix = plane.pixelStride
            val w = img.width
            val h = img.height
            val out = FloatArray(cols * rows)
            val cw = w / cols
            val ch = h / rows
            if (cw == 0 || ch == 0) return null
            // Sample every other pixel in each cell; plenty for a brightness average.
            for (r in 0 until rows) for (c in 0 until cols) {
                var s = 0
                var n = 0
                var y = r * ch
                while (y < (r + 1) * ch) {
                    var x = c * cw
                    while (x < (c + 1) * cw) {
                        s += buf.get(y * stride + x * pix).toInt() and 0xFF
                        n++
                        x += 2
                    }
                    y += 2
                }
                out[r * cols + c] = if (n == 0) 0f else s.toFloat() / n
            }
            return out
        }
    }

    private companion object {
        const val TAG = "RodeoGestures"
        /** MediaPipe's gesture recogniser, downloaded into assets at build time. */
        const val MODEL = "gesture_recognizer.task"
    }
}
