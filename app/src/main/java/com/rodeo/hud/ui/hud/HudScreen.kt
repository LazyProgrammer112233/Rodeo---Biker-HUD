package com.rodeo.hud.ui.hud

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rodeo.hud.core.ControlSource
import com.rodeo.hud.core.MusicAction
import com.rodeo.hud.graph
import com.rodeo.hud.nav.MapPane
import com.rodeo.hud.ride.formatDuration
import com.rodeo.hud.ui.components.ParticleField
import com.rodeo.hud.ui.theme.LocalHud
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Collects the live app state into a [CockpitState] and draws the cockpit with the map. */
@Composable
fun HudScreen(actions: HudActions, showEndSlider: Boolean, dark: Boolean) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val profile by g.profile.profile.collectAsState()
    val live by g.ride.live.collectAsState()
    val speed by g.ride.speedKmh.collectAsState()
    val health by g.guardian.health.collectAsState()
    val pack by g.pack.state.collectAsState()
    val localHazards by g.hazards.hazards.collectAsState()
    val remoteHazards by g.hazards.remote.collectAsState()
    val weather by g.weather.now.collectAsState()
    val warnings by g.warnings.warnings.collectAsState()
    val music by g.music.now.collectAsState()
    val vib by g.sensors.vibration.collectAsState()
    val rides by g.rides.rides.collectAsState()
    val voice by g.assistant.ui.collectAsState()
    val route by g.audioRoutes.route.collectAsState()
    val lastFix by g.location.lastFixAt.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var wifi by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            wifi = wifiOn(ctx)
            delay(5_000)
        }
    }

    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val date = Date(now)
    val last = rides.firstOrNull()
    val top = warnings.firstOrNull()
    val art = remember(music.art) { music.art?.asImageBitmap() }
    val position = remember(music) { { music.positionNow() } }
    var explainMusicAccess by remember { mutableStateOf(false) }
    val state = CockpitState(
        time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(date),
        timeSuffix = if (is24) zoneShort() else SimpleDateFormat("a", Locale.ENGLISH).format(date).uppercase(),
        weatherTemp = weather?.let { "${it.tempC.toInt()}°C" },
        weatherLabel = weather?.shortLabel,
        sunny = weather?.code?.let { it <= 2 } ?: true,
        batteryPct = health.batteryPct,
        charging = health.charging,
        phoneTempC = health.batteryTempC.toInt(),
        lowPower = health.lowPower,
        gpsOk = lastFix != 0L && now - lastFix < 20_000,
        model = profile.model.orEmpty(),
        year = profile.year?.toString().orEmpty(),
        paintName = profile.paintName.orEmpty(),
        riding = live.active,
        speedKmh = speed,
        distanceKm = (live.distanceM / 1000).toFloat(),
        avgKmh = live.avgSpeedKmh.toInt(),
        elapsed = formatDuration(live.elapsedMs),
        topKmh = live.maxSpeedKmh.toInt(),
        paused = live.paused,
        vibration = vib.level,
        bumps = live.hazards,
        lastDistanceKm = last?.let { (it.distanceM / 1000).toFloat() },
        lastAvgKmh = last?.avgSpeedKmh?.toInt(),
        lastTopKmh = last?.maxSpeedKmh?.toInt(),
        warning = top?.let { WarningUi(it.title, it.detail, it.severity, warnings.size - 1) },
        music = MusicUi(
            music.title, music.artist, music.app, music.playing, art, music.hasAccess,
            durationMs = music.durationMs,
            position = position,
            volumePct = music.volume * 100 / music.maxVolume.coerceAtLeast(1),
        ),
        btOn = route.btOn,
        btDevice = route.btDevice,
        wiredHeadset = route.wired,
        musicOnPhone = route.musicOnPhone,
        wifiOn = wifi,
        packCount = pack.members.size,
        packActive = pack.active,
        listening = voice.awake,
        wakeArmed = voice.listening,
        musicPadOn = profile.musicPad,
        showEndSlider = showEndSlider,
        lockInput = speed > 10f,
    )

    val controls = remember(actions) {
        object : CockpitControls {
            override fun startRide() = actions.startRide()
            override fun requestEndRide() = actions.requestEndRide()
            override fun confirmEndRide() = actions.confirmEndRide()
            override fun cancelEndRide() = actions.cancelEndRide()
            override fun sos() = actions.sos()
            override fun voice() = actions.voice()
            override fun openRides() = actions.openRides()
            override fun openGarage() = actions.openGarage()
            override fun openSettings() = actions.openSettings()
            override fun openPack() = actions.openPack()
            override fun dismissWarning() = g.warnings.dismissTop()
            override fun musicPrevious() = g.music.previous()
            override fun musicPlayPause() = g.music.playPause()
            override fun musicNext() = g.music.next()
            override fun musicVolumeUp() = g.music.volumeUp()
            override fun musicVolumeDown() = g.music.volumeDown()
            override fun musicAccess() { explainMusicAccess = true }
            override fun bluetooth() = openBluetooth(ctx, g.audioRoutes.route.value.btOn)
            override fun wifi() {
                runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            override fun toggleMusicPad() = g.profile.update { it.copy(musicPad = !it.musicPad) }
        }
    }

    CockpitLayout(
        state, controls, profile.mapOnRight,
        map = { m ->
            MapPane(m, dark = dark, lowPower = health.lowPower, lockInput = state.lockInput, members = pack.members, hazards = localHazards + remoteHazards)
        },
        backdrop = { ParticleField(Modifier.fillMaxSize(), speed, lowPower = health.lowPower) },
        overlay = { GestureFlash(Modifier.align(Alignment.TopCenter).padding(top = 18.dp)) },
    )
    if (profile.musicPad) {
        MusicPad(
            playing = music.playing,
            onAction = { a -> g.controls.act(a, ControlSource.TAP, "Screen tap") },
            onExit = { g.profile.update { it.copy(musicPad = false) } },
        )
    }
    if (explainMusicAccess) {
        MusicAccessDialog(
            onOpen = {
                explainMusicAccess = false
                runCatching { ctx.startActivity(g.music.accessIntent()) }
                    .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            },
            onAppInfo = {
                explainMusicAccess = false
                runCatching { ctx.startActivity(g.music.appInfoIntent()) }
            },
            onDismiss = { explainMusicAccess = false },
        )
    }
}

/**
 * Song names, length and art need notification access. On Android 13 and later an app installed
 * from an APK has that switch greyed out ("Restricted setting") until it is allowed in App info.
 */
@Composable
private fun MusicAccessDialog(onOpen: () -> Unit, onAppInfo: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalHud.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.textMuted,
        title = { Text("Show what's playing") },
        text = {
            Text(
                "Rodeo reads the song name, length and cover from your music app's notification. " +
                    "Turn on Rodeo on the next screen.\n\n" +
                    "If the switch is greyed out or says \"Restricted setting\": open App info, tap ⋮ at the top right, " +
                    "choose \"Allow restricted settings\", then come back and tap Turn on again.",
            )
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("Turn on", color = c.accent) } },
        dismissButton = { TextButton(onClick = onAppInfo) { Text("App info", color = c.text) } },
    )
}

/** Big, brief confirmation of a tap, volume key or wave (or a hint when it was nearly right). */
@Composable
private fun GestureFlash(modifier: Modifier) {
    val g = LocalContext.current.graph
    val last by g.controls.last.collectAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(last) {
        // Screen taps light up their own zone on the music pad.
        if (last == null || last?.source == ControlSource.TAP) return@LaunchedEffect
        visible = true
        delay(1_400)
        visible = false
    }
    val ev = last
    val icon = when (ev?.action) {
        MusicAction.NEXT -> Icons.Filled.SkipNext
        MusicAction.PREVIOUS -> Icons.Filled.SkipPrevious
        MusicAction.PLAY_PAUSE -> Icons.Filled.PlayArrow
        null -> if (ev?.source == ControlSource.TAP) Icons.Filled.TouchApp else null
    }
    val label = when {
        ev == null -> ""
        ev.action == null -> ev.detail
        else -> ev.detail + " · " + com.rodeo.hud.core.MusicControls.label(ev.action)
    }
    ActionToast(visible, icon, label, modifier)
}

/** "IST" rather than "GMT+05:30" where the phone has a short name for the zone. */
private fun zoneShort(): String {
    val name = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT, Locale.ENGLISH)
    return name.takeIf { it.length <= 4 && it.all(Char::isLetter) }.orEmpty()
}

private fun wifiOn(ctx: Context): Boolean = runCatching {
    (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
}.getOrDefault(false)

private fun openBluetooth(ctx: Context, on: Boolean) {
    val intent = if (!on) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
