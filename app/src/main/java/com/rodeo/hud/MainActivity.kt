package com.rodeo.hud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
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
import com.rodeo.hud.core.Gesture
import com.rodeo.hud.core.Severity
import com.rodeo.hud.core.VoiceCommand
import com.rodeo.hud.core.Warning
import com.rodeo.hud.core.WaveDetector
import com.rodeo.hud.data.Ride
import com.rodeo.hud.nav.Place
import com.rodeo.hud.ride.RideService
import com.rodeo.hud.sos.SosReason
import com.rodeo.hud.ui.garage.GarageScreen
import com.rodeo.hud.ui.hud.HudActions
import com.rodeo.hud.ui.hud.HudScreen
import com.rodeo.hud.ui.hud.RideSummary
import com.rodeo.hud.ui.hud.SosOverlay
import com.rodeo.hud.ui.hud.VoiceOverlay
import com.rodeo.hud.ui.pack.PackScreen
import com.rodeo.hud.ui.rides.RidesScreen
import com.rodeo.hud.ui.settings.SettingsScreen
import com.rodeo.hud.ui.setup.Onboarding
import com.rodeo.hud.ui.theme.RodeoTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen { HUD, RIDES, GARAGE, SETTINGS, PACK }

class MainActivity : ComponentActivity(), HudActions {
    private val g by lazy { graph }
    private var screen by mutableStateOf(Screen.HUD)
    private var showEndSlider by mutableStateOf(false)
    private var summary by mutableStateOf<Ride?>(null)
    private var voiceOpen by mutableStateOf(false)
    private var holdingLocation = false
    private lateinit var wave: WaveDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        wave = WaveDetector(this) { gesture -> onGesture(gesture) }
        handleIntent(intent)

        setContent {
            val profile by g.profile.profile.collectAsState()
            val dark by g.ambient.dark.collectAsState()
            val loc by g.location.location.collectAsState()

            LaunchedEffect(profile.themeMode) { g.ambient.mode = profile.themeMode }
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
            LaunchedEffect(profile.onboarded) {
                if (profile.onboarded) g.nav.init(this@MainActivity)
            }

            RodeoTheme(profile, dark) {
                Box(Modifier.fillMaxSize()) {
                    if (!profile.onboarded) {
                        Onboarding(onDone = { ensureLocation(); g.nav.init(this@MainActivity) })
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
                            )
                            Screen.PACK -> PackScreen(onBack = { screen = Screen.HUD })
                        }
                        summary?.let { RideSummary(it) { summary = null } }
                        if (voiceOpen) VoiceOverlay { voiceOpen = false; g.voice.stop() }
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
    }

    override fun onStart() {
        super.onStart()
        ensureLocation()
        g.ambient.start()
        g.guardian.start()
        g.music.start()
        if (g.profile.profile.value.waveControl) wave.start()
    }

    override fun onResume() {
        super.onResume()
        ensureLocation()
        g.music.stop(); g.music.start() // pick up newly granted notification access
    }

    override fun onStop() {
        wave.stop()
        g.ambient.stop()
        g.music.stop()
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        if (!g.voice.available) {
            Toast.makeText(this, "Voice recognition isn't available on this phone", Toast.LENGTH_SHORT).show()
            return
        }
        voiceOpen = true
        g.voice.listen { cmd, _ ->
            voiceOpen = false
            runCommand(cmd)
        }
    }

    override fun openRides() { screen = Screen.RIDES }
    override fun openGarage() { screen = Screen.GARAGE }
    override fun openSettings() { screen = Screen.SETTINGS }
    override fun openPack() { screen = Screen.PACK }

    private fun runCommand(cmd: VoiceCommand) {
        when (cmd) {
            VoiceCommand.NEXT -> g.music.next()
            VoiceCommand.PREVIOUS -> g.music.previous()
            VoiceCommand.PAUSE -> g.music.pause()
            VoiceCommand.PLAY -> g.music.play()
            VoiceCommand.START_RIDE -> if (!g.ride.live.value.active) startRide()
            VoiceCommand.END_RIDE -> if (g.ride.live.value.active) requestEndRide()
            VoiceCommand.SOS -> sos()
            VoiceCommand.DISMISS -> g.warnings.dismissTop()
            VoiceCommand.FIND_BIKE -> {
                val parked = g.rides.lastParked()
                if (parked?.parkLat != null && g.nav.state.value.ready) {
                    g.nav.navigateTo(Place("Parked bike", "", parked.parkLat, parked.parkLng!!), walking = true)
                } else screen = Screen.RIDES
            }
            VoiceCommand.NAVIGATE_HOME -> {
                val p = g.profile.profile.value
                if (p.homeLat != null && p.homeLng != null && g.nav.state.value.ready) {
                    g.nav.navigateTo(Place(p.homeLabel.ifBlank { "Home" }, "", p.homeLat, p.homeLng))
                } else g.warnings.post(Warning("home", "Home not set", "Set it in Settings > Home", Severity.INFO))
            }
            VoiceCommand.UNKNOWN -> Toast.makeText(this, "Didn't catch that", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onGesture(gesture: Gesture) {
        if (!g.profile.profile.value.waveControl) return
        when (gesture) {
            Gesture.WAVE -> g.music.next()
            Gesture.DOUBLE_WAVE -> g.warnings.dismissTop()
            Gesture.HOLD -> voice()
        }
    }

    /** Bluetooth handlebar remotes: media keys control music; D-pad / Enter move focus (handled by Compose). */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { g.music.next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { g.music.previous(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { g.music.playPause(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { g.music.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { g.music.pause(); return true }
            KeyEvent.KEYCODE_V -> { voice(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
