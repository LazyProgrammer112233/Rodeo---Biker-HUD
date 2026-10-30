package com.rodeo.hud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
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
import com.rodeo.hud.core.ControlSource
import com.rodeo.hud.core.MusicAction
import com.rodeo.hud.core.TapTemplates
import com.rodeo.hud.core.TapZone
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
import com.rodeo.hud.ui.settings.TapSetupScreen
import com.rodeo.hud.ui.setup.Onboarding
import com.rodeo.hud.ui.theme.RodeoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { HUD, RIDES, GARAGE, SETTINGS, PACK, BIKE, TAPS }

private const val ACTION_VOICE_HANDS_FREE = "android.speech.action.VOICE_SEARCH_HANDS_FREE"

class MainActivity : ComponentActivity(), HudActions, AssistantHost {
    private val g by lazy { graph }
    private var screen by mutableStateOf(Screen.HUD)
    private var showEndSlider by mutableStateOf(false)
    private var summary by mutableStateOf<Ride?>(null)
    private var holdingLocation = false
    private var askedMic = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        g.taps.rotation = ::rotation
        g.taps.onAction = { action, zone, n ->
            val how = when {
                n == 2 -> "Double tap ${zone.name.lowercase()}"
                else -> "Tap ${zone.name.lowercase()}"
            }
            g.controls.act(action, ControlSource.TAP, how)
        }
        g.taps.onHint = { zone, n ->
            if (zone == TapZone.RIGHT && n == 1) g.controls.hint(ControlSource.TAP, "Double-tap the right end for next song")
            else if (n == 2) g.controls.hint(ControlSource.TAP, "Single tap ${zone.name.lowercase()} · double tap is for the right end")
        }
        g.waves.onWaves = { n ->
            if (g.profile.profile.value.waveControl) {
                val action = when (n) {
                    1 -> MusicAction.PLAY_PAUSE
                    2 -> MusicAction.NEXT
                    else -> MusicAction.PREVIOUS
                }
                g.controls.act(action, ControlSource.WAVE, if (n == 1) "Wave" else "$n waves")
            }
        }
        g.assistant.host = this
        handleIntent(intent)

        setContent {
            val profile by g.profile.profile.collectAsState()
            val dark by g.ambient.dark.collectAsState()
            val loc by g.location.location.collectAsState()

            LaunchedEffect(profile.themeMode) { g.ambient.mode = profile.themeMode }
            LaunchedEffect(profile.tapControl, profile.waveControl, profile.onboarded, screen) { syncGestures() }
            LaunchedEffect(profile.tapTemplates, profile.tapMinPeak) {
                g.taps.classifier.templates = TapTemplates.fromJson(profile.tapTemplates)
                g.taps.minPeak = profile.tapMinPeak
            }
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
                        BackHandler(enabled = screen != Screen.HUD && screen != Screen.BIKE && screen != Screen.TAPS) { screen = Screen.HUD }
                        when (screen) {
                            Screen.HUD -> HudScreen(this@MainActivity, showEndSlider, dark)
                            Screen.RIDES -> RidesScreen(onBack = { screen = Screen.HUD }, onNavigated = { screen = Screen.HUD })
                            Screen.GARAGE -> GarageScreen(onBack = { screen = Screen.HUD })
                            Screen.SETTINGS -> SettingsScreen(
                                onBack = { screen = Screen.HUD },
                                onChangeBike = { screen = Screen.BIKE },
                                onTapSetup = { screen = Screen.TAPS },
                                onSimulateCrash = { screen = Screen.HUD; g.ride.simulateCrash() },
                                onDropTest = { g.ride.startDropTest() },
                            )
                            Screen.PACK -> PackScreen(onBack = { screen = Screen.HUD })
                            Screen.TAPS -> TapSetupScreen(onBack = { screen = Screen.SETTINGS })
                            Screen.BIKE -> Onboarding(editBike = true, onCancel = { screen = Screen.SETTINGS }, onDone = { screen = Screen.SETTINGS })
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
        g.taps.stop()
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

    /** Taps and waves run only while Rodeo is on screen and the rider has them switched on (tap setup needs taps too). */
    private fun syncGestures() {
        val p = g.profile.profile.value
        val started = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (p.onboarded && p.waveControl && started) g.waves.start() else g.waves.stop()
        if (p.onboarded && started && (p.tapControl || screen == Screen.TAPS)) g.taps.start() else g.taps.stop()
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = runCatching { windowManager.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_90)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 || requestCode == 9) {
            g.assistant.sync()
            if (requestCode == 7 && g.assistant.micAllowed) g.assistant.wakeManually()
        }
    }

    private var volumeDownAt = 0L
    private var volumeHeld = false

    /**
     * On the HUD the volume keys skip songs: the key on the rider's left = previous, on the right =
     * next, holding either = play / pause. Most phones have both keys on one long edge, volume up
     * nearer the top, so which key is "left" depends on which way round the phone is mounted.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val p = g.profile.profile.value
        if ((code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            p.onboarded && p.volumeKeyMusic && screen == Screen.HUD && g.sos.state.value is com.rodeo.hud.sos.SosState.Idle
        ) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    volumeDownAt = event.eventTime
                    volumeHeld = false
                } else if (!volumeHeld && event.eventTime - volumeDownAt >= 550) {
                    volumeHeld = true
                    g.controls.act(MusicAction.PLAY_PAUSE, ControlSource.KEY, "Hold volume key")
                }
                KeyEvent.ACTION_UP -> if (!volumeHeld) {
                    val upIsLeft = rotation() != Surface.ROTATION_270
                    val left = (code == KeyEvent.KEYCODE_VOLUME_UP) == upIsLeft
                    g.controls.act(if (left) MusicAction.PREVIOUS else MusicAction.NEXT, ControlSource.KEY, if (left) "Left volume key" else "Right volume key")
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
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
