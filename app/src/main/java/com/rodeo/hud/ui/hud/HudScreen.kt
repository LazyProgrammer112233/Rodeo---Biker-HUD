package com.rodeo.hud.ui.hud

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.core.Severity
import com.rodeo.hud.core.VibrationLevel
import com.rodeo.hud.graph
import com.rodeo.hud.nav.MapPane
import com.rodeo.hud.ride.formatDuration
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.HoldToTrigger
import com.rodeo.hud.ui.components.HudTile
import com.rodeo.hud.ui.components.SlideToConfirm
import com.rodeo.hud.ui.components.SpeedGauge
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalHudStyle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Actions the HUD (and voice / wave / remote controls) can trigger. */
interface HudActions {
    fun startRide()
    fun requestEndRide()
    fun confirmEndRide()
    fun cancelEndRide()
    fun sos()
    fun voice()
    fun openRides()
    fun openGarage()
    fun openSettings()
    fun openPack()
}

@Composable
fun HudScreen(actions: HudActions, showEndSlider: Boolean, dark: Boolean) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val live by g.ride.live.collectAsState()
    val speed by g.ride.speedKmh.collectAsState()
    val health by g.guardian.health.collectAsState()
    val pack by g.pack.state.collectAsState()
    val localHazards by g.hazards.hazards.collectAsState()
    val remoteHazards by g.hazards.remote.collectAsState()
    val lockInput = speed > 10f

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .background(Brush.horizontalGradient(listOf(c.tint.copy(alpha = if (c.dark) 0.10f else 0.07f), Color.Transparent, Color.Transparent))),
    ) {
        Row(Modifier.fillMaxSize()) {
            val panel: @Composable RowScope.() -> Unit = {
                ControlPanel(
                    Modifier.weight(1f).fillMaxHeight().padding(12.dp),
                    actions, showEndSlider, lockInput, live.active, speed,
                )
            }
            val map: @Composable RowScope.() -> Unit = {
                MapPane(
                    Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, bottom = 12.dp, end = if (profile.mapOnRight) 12.dp else 0.dp, start = if (profile.mapOnRight) 0.dp else 12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(c.surface),
                    dark = dark, lowPower = health.lowPower, lockInput = lockInput,
                    members = pack.members, hazards = localHazards + remoteHazards,
                )
            }
            if (profile.mapOnRight) { panel(); map() } else { map(); panel() }
        }
    }
}

@Composable
private fun ControlPanel(
    modifier: Modifier,
    actions: HudActions,
    showEndSlider: Boolean,
    lockInput: Boolean,
    riding: Boolean,
    speed: Float,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusRow(actions, lockInput)
        WarningBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (riding) RideCard(Modifier.fillMaxSize(), speed) else IdleCard(Modifier.fillMaxSize(), actions)
            if (showEndSlider) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(LocalHud.current.surface).padding(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("End this ride?", color = LocalHud.current.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                        Box(Modifier.size(44.dp).clickable { actions.cancelEndRide() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, "Cancel", tint = LocalHud.current.textMuted)
                        }
                    }
                    SlideToConfirm("Slide to end ride and save parking spot") { actions.confirmEndRide() }
                }
            }
        }
        MusicStrip()
        BottomTiles(actions, riding)
    }
}

@Composable
private fun StatusRow(actions: HudActions, lockInput: Boolean) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val weather by g.weather.now.collectAsState()
    val health by g.guardian.health.collectAsState()
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) { now = Date(); delay(5_000) }
    }
    val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now), color = c.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.weight(1f)) {
            Text(
                weather?.let { "${it.tempC.toInt()}°C · ${it.label}" } ?: "Weather…",
                color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("${health.batteryPct}%")
                    if (health.charging) append(" charging")
                    append(" · ${health.batteryTempC.toInt()}°C")
                    if (health.lowPower) append(" · low-power")
                },
                color = if (health.lowPower) c.warning else c.textMuted, fontSize = 12.sp, maxLines = 1,
            )
        }
        SmallIcon(Icons.Filled.History, "Rides", enabled = !lockInput) { actions.openRides() }
        SmallIcon(Icons.Filled.Warehouse, "Garage", enabled = !lockInput) { actions.openGarage() }
        SmallIcon(Icons.Filled.Settings, "Settings", enabled = !lockInput) { actions.openSettings() }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalHud.current
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.glass).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) c.text else c.textMuted.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun WarningBanner() {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val warnings by g.warnings.warnings.collectAsState()
    val top = warnings.firstOrNull()
    AnimatedVisibility(top != null) {
        if (top == null) return@AnimatedVisibility
        val color = when (top.severity) {
            Severity.DANGER -> c.danger
            Severity.WARN -> c.warning
            Severity.INFO -> c.accent
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.14f))
                .border(1.5.dp, color, RoundedCornerShape(16.dp)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(top.title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (top.detail.isNotBlank()) Text(top.detail, color = c.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (warnings.size > 1) Text("+${warnings.size - 1}", color = c.textMuted, fontSize = 12.sp)
            Box(Modifier.size(52.dp).clickable { g.warnings.dismissTop() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Dismiss", tint = c.textMuted)
            }
        }
    }
}

@Composable
private fun RideCard(modifier: Modifier, speed: Float) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val live by g.ride.live.collectAsState()
    val vib by g.sensors.vibration.collectAsState()
    GlassCard(modifier, accentBorder = c.accent.copy(alpha = 0.5f)) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            SpeedGauge(speed, Modifier.weight(1.2f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("Distance", "%.1f km".format(live.distanceM / 1000))
                Stat("Average", "${live.avgSpeedKmh.toInt()} km/h")
                Stat("Riding", formatDuration(live.elapsedMs) + if (live.paused) " · paused" else "")
                Stat("Top", "${live.maxSpeedKmh.toInt()} km/h")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(
                        "Vibe " + vib.level.name.lowercase(),
                        when (vib.level) { VibrationLevel.HIGH -> c.danger; VibrationLevel.MEDIUM -> c.warning; else -> c.ok },
                    )
                    if (live.hazards > 0) Pill("${live.hazards} bumps", c.warning)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    val c = LocalHud.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, color = c.textMuted, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Text(value, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun IdleCard(modifier: Modifier, actions: HudActions) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val style = LocalHudStyle.current
    val profile by g.profile.profile.collectAsState()
    val rides by g.rides.rides.collectAsState()
    GlassCard(modifier) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(style.brandName.uppercase(), color = c.accent, fontSize = 13.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(profile.model, profile.year?.toString()).joinToString(" · "), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(profile.paintName.orEmpty(), color = c.textMuted, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                val last = rides.firstOrNull()
                Text(
                    if (last != null) "Last ride %.1f km · avg %d km/h".format(last.distanceM / 1000, last.avgSpeedKmh.toInt()) else "No rides yet",
                    color = c.textMuted, fontSize = 12.sp,
                )
                if (style.tagline.isNotBlank()) Text(style.tagline, color = c.textMuted.copy(alpha = 0.7f), fontSize = 11.sp)
            }
            Box(
                Modifier.size(132.dp).clip(CircleShape).background(c.accent).clickable { actions.startRide() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.TwoWheeler, null, tint = c.onAccent, modifier = Modifier.size(40.dp))
                    Text("START", color = c.onAccent, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 2.sp)
                    Text("RIDE", color = c.onAccent.copy(alpha = 0.8f), fontSize = 12.sp, letterSpacing = 2.sp)
                }
            }
        }
    }
}

@Composable
private fun MusicStrip() {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val now by g.music.now.collectAsState()
    GlassCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = now.art
            if (art != null) {
                Image(art.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(now.title.ifBlank { if (now.hasAccess) "Nothing playing" else "Music" }, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(now.artist, now.app).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Controls any music app" }, color = c.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MusicButton(Icons.Filled.SkipPrevious, "Previous") { g.music.previous() }
            MusicButton(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", big = true) { g.music.playPause() }
            MusicButton(Icons.Filled.SkipNext, "Next") { g.music.next() }
        }
    }
}

@Composable
private fun MusicButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, big: Boolean = false, onClick: () -> Unit) {
    val c = LocalHud.current
    Box(
        Modifier.size(if (big) 60.dp else 54.dp).clip(CircleShape).background(if (big) c.text.copy(alpha = 0.08f) else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = c.text, modifier = Modifier.size(if (big) 32.dp else 28.dp)) }
}

@Composable
private fun BottomTiles(actions: HudActions, riding: Boolean) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val pack by g.pack.state.collectAsState()
    val listening by g.voice.listening.collectAsState()
    var bt by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            bt = btOn(ctx); wifi = wifiOn(ctx)
            delay(3_000)
        }
    }
    Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HudTile(if (bt) Icons.Filled.Bluetooth else Icons.Filled.BluetoothDisabled, "Bluetooth", Modifier.weight(1f).fillMaxHeight(), active = bt) { openBluetooth(ctx, bt) }
        HudTile(if (wifi) Icons.Filled.Wifi else Icons.Filled.WifiOff, "Wi-Fi", Modifier.weight(1f).fillMaxHeight(), active = wifi) {
            runCatching { ctx.startActivity(Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        HudTile(Icons.Filled.Groups, if (pack.active) "${pack.members.size} pack" else "Pack", Modifier.weight(1f).fillMaxHeight(), active = pack.active) { actions.openPack() }
        HudTile(Icons.Filled.Mic, "Voice", Modifier.weight(1f).fillMaxHeight(), active = listening) { actions.voice() }
        HoldToTrigger("SOS", "hold 2 s", c.danger, Modifier.weight(1.3f).fillMaxHeight()) { actions.sos() }
        if (riding) {
            HudTile(Icons.Filled.Stop, "End", Modifier.weight(0.8f).fillMaxHeight(), minSize = 64.dp) { actions.requestEndRide() }
        }
    }
}

private fun btOn(ctx: Context): Boolean = runCatching {
    (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
}.getOrDefault(false)

private fun wifiOn(ctx: Context): Boolean = runCatching {
    (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
}.getOrDefault(false)

private fun openBluetooth(ctx: Context, on: Boolean) {
    val intent = if (!on) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
