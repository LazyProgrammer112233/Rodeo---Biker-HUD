package com.rodeo.hud

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.rodeo.hud.core.AmbientController
import com.rodeo.hud.core.AudioRoutes
import com.rodeo.hud.core.CrashDetector
import com.rodeo.hud.core.DeviceGuardian
import com.rodeo.hud.core.LocationHub
import com.rodeo.hud.core.MusicControls
import com.rodeo.hud.core.RideEngine
import com.rodeo.hud.core.RideSensors
import com.rodeo.hud.core.Speaker
import com.rodeo.hud.core.WarningCenter
import com.rodeo.hud.core.WaveSensor
import com.rodeo.hud.core.WeatherService
import com.rodeo.hud.data.GarageStore
import com.rodeo.hud.data.HazardStore
import com.rodeo.hud.data.ProfileStore
import com.rodeo.hud.data.RideRepository
import com.rodeo.hud.garage.ReminderWorker
import com.rodeo.hud.media.MusicController
import com.rodeo.hud.nav.NavController
import com.rodeo.hud.nav.RouteOptions
import com.rodeo.hud.pack.HazardSync
import com.rodeo.hud.pack.PackService
import com.rodeo.hud.sos.SosManager
import com.rodeo.hud.sos.SosSnapshot
import com.rodeo.hud.voice.Assistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Every long-lived piece of the app, created once. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val profile = ProfileStore(app)
    val rides = RideRepository(app)
    val hazards = HazardStore(app)
    val garage = GarageStore(app)
    val warnings = WarningCenter()
    val location = LocationHub(app)
    val sensors = RideSensors(app)
    val crash = CrashDetector(app)
    val guardian = DeviceGuardian(app, warnings)
    val weather = WeatherService(warnings)
    val ambient = AmbientController(app)
    val music = MusicController(app)
    val audioRoutes = AudioRoutes(app)
    val speaker = Speaker(app)
    val waves = WaveSensor(app)
    val pack = PackService(app, scope, warnings) { profile.profile.value.riderName }
    val hazardSync = HazardSync(app, hazards)
    val nav = NavController(app, scope, speaker, { location.location.value }) {
        profile.profile.value.let { RouteOptions(avoidTolls = it.avoidTolls, avoidHighways = it.avoidHighways) }
    }

    val sos: SosManager = SosManager(app, scope, { profile.profile.value }) {
        val loc = location.location.value
        SosSnapshot(loc?.latitude, loc?.longitude, ride.speedKmh.value, loc?.takeIf { it.hasBearing() }?.bearing)
    }

    val ride: RideEngine = RideEngine(
        scope, location, sensors, crash, rides, hazards, warnings, sos,
        crashEnabled = { profile.profile.value.crashDetection },
        onHazardLogged = { hazardSync.onHazard(it) },
    )

    val controls = MusicControls(music) { sos.state.value !is com.rodeo.hud.sos.SosState.Idle }

    /** Created last: it listens to everything above. */
    val assistant = Assistant(app, this)

    init {
        location.addListener { loc ->
            nav.onLocation(loc)
            pack.onLocation(loc)
            hazardSync.onLocation(loc.latitude, loc.longitude)
            ambient.setLocation(loc.latitude, loc.longitude)
        }
        scope.launch { rides.refresh() }
        // The music pad follows the ride: on when it starts, off when it ends (if the rider wants that).
        scope.launch {
            ride.live.map { it.active }.distinctUntilChanged().drop(1).collect { active ->
                if (profile.profile.value.musicPadOnRide) profile.update { it.copy(musicPad = active) }
            }
        }
    }
}

class RodeoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        initFirebase()
        initMap()
        graph = AppGraph(this)
        channels()
        runCatching { ReminderWorker.schedule(this) }
    }

    /** Firebase is optional: only pack mode and pothole sharing use it. */
    private fun initFirebase() {
        if (BuildConfig.FIREBASE_DB_URL.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank()) return
        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setDatabaseUrl(BuildConfig.FIREBASE_DB_URL)
                        .build(),
                )
            }
        }
    }

    /** OpenStreetMap tiles: identify the app and keep the tile cache inside app storage. */
    private fun initMap() {
        org.osmdroid.config.Configuration.getInstance().apply {
            load(this@RodeoApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "$packageName/${BuildConfig.VERSION_NAME}"
            osmdroidBasePath = java.io.File(cacheDir, "osm")
            osmdroidTileCache = java.io.File(cacheDir, "osm/tiles")
        }
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RIDE, "Ride tracking", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Garage reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CH_RIDE = "ride"
        const val CH_REMINDERS = "reminders"
    }
}

val Context.graph: AppGraph get() = (applicationContext as RodeoApp).graph
