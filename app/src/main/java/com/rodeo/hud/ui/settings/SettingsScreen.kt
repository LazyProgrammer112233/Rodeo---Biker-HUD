package com.rodeo.hud.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.BuildConfig
import com.rodeo.hud.core.HandGesture
import com.rodeo.hud.data.ThemeMode
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.setup.PermissionsPanel
import com.rodeo.hud.ui.setup.SosContactsEditor
import com.rodeo.hud.ui.setup.ToggleRow
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType

@Composable
fun SettingsScreen(onBack: () -> Unit, onChangeBike: () -> Unit, onSimulateCrash: () -> Unit, onDropTest: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val loc by g.location.location.collectAsState()
    val lux by g.ambient.lux.collectAsState()
    val crashTest by g.crash.status.collectAsState()
    val camOn by g.gestures.running.collectAsState()
    val lastGesture by g.gestures.last.collectAsState()

    Row(Modifier.fillMaxSize().background(c.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                }
                Text("Settings", color = c.text, style = LocalType.current.headlineXl)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Bike and look")
                Text(profile.bikeLabel + (profile.paintName?.let { " · $it" } ?: ""), color = c.text, modifier = Modifier.padding(vertical = 6.dp))
                GhostButton("Change bike", icon = Icons.Filled.TwoWheeler) { onChangeBike() }
                Spacer(Modifier.height(8.dp))
                Text("Theme", color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    ThemeMode.entries.forEach { m ->
                        val sel = profile.themeMode == m
                        Text(
                            when (m) { ThemeMode.AUTO -> "Auto (sun + tunnels)"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" },
                            color = if (sel) c.onAccent else c.text,
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) c.accent else c.glass)
                                .clickable { g.profile.update { it.copy(themeMode = m) } }.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                Text(
                    if (g.ambient.hasLightSensor) "Light sensor: ${lux?.toInt() ?: "–"} lux" else "No light sensor: theme follows sunrise and sunset only",
                    color = c.textMuted, style = LocalType.current.bodySm,
                )
                ToggleRow("Map on the right", profile.mapOnRight, sub = "Turn off to mirror the HUD") { v -> g.profile.update { it.copy(mapOnRight = v) } }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Safety")
                ToggleRow("Crash detection", profile.crashDetection, sub = "Hard impact, then the bike stops and tips over: 30 s alarm countdown, then SOS by SMS") { v ->
                    g.profile.update { it.copy(crashDetection = v) }
                    g.ride.setCrashDetection(v)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Drop test", icon = Icons.Filled.Sensors) { onDropTest() }
                    GhostButton("Simulate crash", icon = Icons.Filled.Warning) { onSimulateCrash() }
                }
                Text(
                    crashTest.text.ifBlank { "Drop test runs the real sensor check: drop the phone onto a bed and leave it still. Both tests send nothing." },
                    color = if (crashTest.text.isBlank()) c.textMuted else c.text, style = LocalType.current.body, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                SectionLabel("SOS contacts")
                Spacer(Modifier.height(6.dp))
                SosContactsEditor(showTest = true)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Controls")
                ToggleRow("Camera gestures", profile.gestureControl, sub = "Swipe right = next song · swipe left = previous · cover the selfie camera with your palm = pause / play") { v ->
                    g.profile.update { it.copy(gestureControl = v) }
                }
                ToggleRow("Swap left and right", profile.swapSwipe, sub = "Use this if swipes feel reversed on your mount") { v -> g.profile.update { it.copy(swapSwipe = v) } }
                Text(
                    when {
                        !profile.gestureControl -> "Gestures are off"
                        !g.gestures.hasPermission -> "Needs camera permission (see Permissions)"
                        !camOn -> "Camera starting…"
                        lastGesture != null -> "Watching · last: " + when (lastGesture!!.gesture) {
                            HandGesture.SWIPE_RIGHT -> "next song"
                            HandGesture.SWIPE_LEFT -> "previous song"
                            HandGesture.PALM_COVER -> "pause / play"
                        }
                        else -> "Watching for gestures. Swipe about 10 to 20 cm from the camera."
                    },
                    color = c.textMuted, style = LocalType.current.bodySm,
                )
                Text("Bluetooth remotes: media keys control music; arrow keys and Enter move between HUD buttons.", color = c.textMuted, style = LocalType.current.bodySm)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Home")
                Text(if (profile.homeLat != null) profile.homeLabel.ifBlank { "Home saved" } else "Not set", color = c.text, modifier = Modifier.padding(vertical = 6.dp))
                GhostButton("Use my current location as home", icon = Icons.Filled.Home) {
                    loc?.let { l -> g.profile.update { it.copy(homeLat = l.latitude, homeLng = l.longitude, homeLabel = "Home") } }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Permissions", Modifier.padding(top = 18.dp))
            PermissionsPanel()
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Services")
                Text("Map: OpenStreetMap (no key) · voice guidance: Google Maps app, two-wheeler mode", color = c.text, style = LocalType.current.body)
                Text("SOS: SMS from the default SIM" + if (g.sos.canSendSms) "" else " · SMS permission off", color = c.text, style = LocalType.current.body)
                Text("Pack mode and pothole sharing: " + if (g.pack.online) "online (Firebase)" else "demo / on-device only", color = c.text, style = LocalType.current.body)
                Text("Weather: Open-Meteo", color = c.text, style = LocalType.current.body)
                Text("Version ${BuildConfig.VERSION_NAME}", color = c.textMuted, style = LocalType.current.bodySm)
            }
        }
    }
}
