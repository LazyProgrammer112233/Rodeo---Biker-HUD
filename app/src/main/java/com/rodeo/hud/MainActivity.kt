package com.rodeo.hud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.rodeo.hud.core.HandGesture
import com.rodeo.hud.core.Severity
import com.rodeo.hud.core.Warning
import com.rodeo.hud.data.Ride
import com.rodeo.hud.ride.RideService
import com.rodeo.hud.sos.SosReason
import com.rodeo.hud.ui.garage.GarageScreen
import com.rodeo.hud.ui.hud.HudActions
import com.rodeo.hud.ui.hud.HudScreen
import com.rodeo.hud.ui.hud.RideSummary
import com.rodeo.hud.ui.hud.SosOverlay
import com.rodeo.hud.ui.hud.VoiceOverlay
import com.rodeo.hud.voice.AssistantHost
import com.rodeo.hud.ui.pack.PackScreen
import com.rodeo.hud.ui.rides.RidesScreen
import com.rodeo.hud.ui.settings.SettingsScreen
import com.rodeo.hud.ui.setup.Onboarding
import com.rodeo.hud.ui.theme.RodeoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { HUD, RIDES, GARAGE, SETTINGS, PACK }

private const val ACTION_VOICE_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE"

class MainActivity : ComponentActivity(), HudActions, AssistantHost {
    private val g by lazy { graph }
    private var screen by mutableStateOf(Screen.HUD)
    private var showEndSlider by mutableStateOf(false)
    private var summary by mutableStateOf<Ride?>(null)
    private var holdingLocation = false
    private var askedCamera = false
    private var askedMic = false
    private var lastGestureAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        g.gestures.onGesture = { gesture -> onGesture(gesture, wave = false) }
        g.waves.onWaves = { n ->
            val gesture = when (n) {
                1 -> HandGesture.PALM_COVER
                2 -> HandGesture.SWIPE_RIGHT
                else -> HandGesture.SWIPE_LEFT
            }
            if (onGesture(gesture, wave = true)) g.gestures.announce(gesture, n)
        }
        g.assistant.host = this
        handleIntent(intent)

        setContent {
            val profile by g.profile.profile.collectAsState()
            val dark by g.ambient.dark.collectAsState()
            val loc by g.location.location.collectAsState()

            LaunchedEffect(profile.themeMode) { g.ambient.mode = profile.themeMode }
            LaunchedEffect(profile.gestureControl, profile.waveControl, profile.onboarded) { syncGestures() }
            LaunchedEffect(profile.swapSwipe) { g.gestures.swap = profile.swapSwipe }
            LaunchedEffect(Unit) {
                while (true) {
                    g.ambient.tick()
                    checkGps()
                    delay(10_000)
                }
            }
            LaunchedEffect(loc?.latitude?.let { (it * 20).toInt() }, loc?.longitude?.let { (it * 20).toInt() }) {
                loc?.let { g.weather.refreshIfStale(it.latitude, it.longitude) }
            }

            RodeoTheme(profile, dark) {
                Box(Modifier.fillMaxSize()) {
                    if (!profile.onboarded) {
                        Onboarding(onDone = { ensureLocation() })
                    } else {
                        BackHandler(enabled = screen != Screen.HUD) { screen = Screen.HUD }
                        when (screen) {
                            Screen.HUD -> HudScreen(this@MainActivity, showEndSlider, dark)
                            Screen.RIDES -> RidesScreen(onBack = { screen = Screen.HUD }, onNavigated = { screen = Screen.HUD })
                            Screen.GARAGE -> GarageScreen(onBack = { screen = Screen.HUD })
                            Screen.SETTINGS -> SettingsScreen(
                                onBack = { screen = Screen.HUD },
                                onChangeBike = { g.profile.update { it.copy(onboarded = false) } },
                                onSimulateCrash = { screen = Screen.HUD; g.ride.simulateCrash() },
                                onDropTest = { g.ride.startDropTest() },
                            )
                            Screen.PACK -> PackScreen(onBack = { screen = Screen.HUD })
                        }
                        summary?.let { RideSummary(it) { summary = null } }
                        VoiceOverlay()
                        SosOverlay()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("open") == "garage") screen = Screen.GARAGE
        // The helmet intercom's assistant button (or a long press on a wired headset button).
        if (intent?.action == Intent.ACTION_VOICE_COMMAND || intent?.action == ACTION_VOICE_HANDS_FREE || intent?.action == Intent.ACTION_ASSIST) {
            screen = Screen.HUD
            voice()
        }
    }

    override fun onDestroy() {
        if (g.assistant.host === this) g.assistant.host = null
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        ensureLocation()
        g.ambient.start()
        g.guardian.start()
        g.music.start()
        g.audioRoutes.start()
        g.assistant.setVisible(true)
        syncGestures()
        askMic()
    }

    override fun onResume() {
        super.onResume()
        ensureLocation()
        g.music.stop(); g.music.start() // pick up newly granted notification access
    }

    override fun onStop() {
        g.gestures.stop()
        g.waves.stop()
        g.ambient.stop()
        g.music.stop()
        g.audioRoutes.stop()
        g.assistant.setVisible(false)
        if (holdingLocation) {
            g.location.release()
            holdingLocation = false
        }
        super.onStop()
    }

    private fun ensureLocation() {
        if (holdingLocation) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            g.location.acquire()
            holdingLocation = true
        }
    }

    private fun checkGps() {
        val live = g.ride.live.value
        val last = g.location.lastFixAt.value
        if (live.active && last != 0L && System.currentTimeMillis() - last > 20_000) {
            g.warnings.post(Warning("gps", "GPS signal lost", "Ride stats pause until the signal returns.", Severity.WARN))
        } else g.warnings.clear("gps")
    }

    // --- HudActions ---

    override fun startRide() {
        ensureLocation()
        RideService.start(this)
    }

    override fun requestEndRide() { showEndSlider = true }
    override fun cancelEndRide() { showEndSlider = false }

    override fun confirmEndRide() {
        showEndSlider = false
        lifecycleScope.launch {
            val ride = g.ride.end()
            RideService.stop(this@MainActivity)
            summary = ride
        }
    }

    override fun sos() = g.sos.startCountdown(SosReason.MANUAL)

    override fun voice() {
        if (!g.assistant.micAllowed) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        g.assistant.wakeManually()
    }

    /** The wake phrase needs the microphone; ask once, after setup. */
    private fun askMic() {
        if (askedMic || !g.profile.profile.value.onboarded || g.assistant.micAllowed) return
        askedMic = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9)
    }

    // --- AssistantHost ---

    override fun endRideNow() = confirmEndRide()

    override fun openRides() { screen = Screen.RIDES }
    override fun openGarage() { screen = Screen.GARAGE }
    override fun openSettings() { screen = Screen.SETTINGS }
    override fun openPack() { screen = Screen.PACK }

    /** Gestures run only while the HUD is on screen and the rider has them switched on. */
    private fun syncGestures() {
        val p = g.profile.profile.value
        val started = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (p.onboarded && p.waveControl && started) g.waves.start() else g.waves.stop()
        if (p.onboarded && p.gestureControl && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            if (g.gestures.hasPermission) g.gestures.start(this)
            else if (!askedCamera) {
                askedCamera = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 8)
            }
        } else g.gestures.stop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) syncGestures()
        if (requestCode == 7 || requestCode == 9) {
            g.assistant.sync()
            if (requestCode == 7 && g.assistant.micAllowed) g.assistant.wakeManually()
        }
    }

    /** Music from a camera gesture or a wave over the phone. Returns whether it was acted on. */
    private fun onGesture(gesture: HandGesture, wave: Boolean): Boolean {
        val p = g.profile.profile.value
        if (!(if (wave) p.waveControl else p.gestureControl)) return false
        if (g.sos.state.value !is com.rodeo.hud.sos.SosState.Idle) return false
        // One hand movement can reach both the camera and the proximity sensor: act once.
        val now = System.currentTimeMillis()
        if (now - lastGestureAt < 1_200) return false
        lastGestureAt = now
        when (gesture) {
            HandGesture.SWIPE_RIGHT -> g.music.next()
            HandGesture.SWIPE_LEFT -> g.music.previous()
            HandGesture.PALM_COVER -> g.music.playPause()
        }
        return true
    }

    /** Bluetooth handlebar remotes: media keys control music; D-pad / Enter move focus (handled by Compose). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { g.music.next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { g.music.previous(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { g.music.playPause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { g.music.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { g.music.pause(); return true }
            KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> { voice(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
