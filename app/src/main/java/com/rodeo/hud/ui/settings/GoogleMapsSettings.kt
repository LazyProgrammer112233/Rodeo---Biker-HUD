package com.rodeo.hud.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rodeo.hud.graph
import com.rodeo.hud.nav.gmaps.GmapsText
import com.rodeo.hud.nav.gmaps.NoticeParser
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.setup.ToggleRow
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType

/** Settings for Google Maps' turns on the HUD: access, voice, layout, a preview and a check. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoogleMapsCard(onShowHud: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val t = LocalType.current
    val profile by g.profile.profile.collectAsState()
    val gm by g.gmaps.state.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val access = remember(tick) { g.music.hasAccess() }
    val installed = remember(tick) { g.gmaps.googleMapsInstalled() }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionLabel("Google Maps on the HUD")
        Text(
            "Start navigation in Google Maps as usual, then come back to Rodeo: the next turn, distance, road and arrival time show here in large type, " +
                "with Rodeo's arrows and voice. Rodeo reads Google Maps' own turn notification on the phone, so it is free and needs no account.",
            color = c.text, style = t.body, modifier = Modifier.padding(vertical = 4.dp),
        )
        val status = when {
            !installed -> "Google Maps is not installed on this phone."
            !access -> "Notification access is off: turn it on so Rodeo can read Google Maps' turns."
            gm.active && !gm.demo -> "Reading Google Maps now."
            else -> "Ready. Turns appear as soon as Google Maps starts navigating."
        }
        Text(status, color = if (installed && access) c.ok else c.warning, style = t.body, modifier = Modifier.padding(vertical = 4.dp))
        ToggleRow(
            "Rodeo speaks the turns", profile.gmapsVoice,
            sub = "In your helmet, with the music lowered for a moment. Mute Google Maps' own voice (the speaker button in Google Maps) so you hear each turn once.",
        ) { v -> g.profile.update { it.copy(gmapsVoice = v) } }
        ToggleRow(
            "Big turn view", profile.gmapsBigPanel,
            sub = "The next turn fills the map side of the HUD. Off: a large turn card sits over the map. The map button on the HUD switches between the two.",
        ) { v -> g.profile.update { it.copy(gmapsBigPanel = v) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            if (!access) {
                GhostButton("Turn on notification access", icon = Icons.Filled.NotificationsActive) {
                    runCatching { ctx.startActivity(g.music.accessIntent()) }
                        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
            }
            GhostButton("Preview on the HUD", icon = Icons.Filled.PlayArrow) {
                g.gmaps.startDemo()
                onShowHud()
            }
            if (installed) GhostButton("Open Google Maps", icon = Icons.Filled.Directions) { g.gmaps.openGoogleMaps() }
        }
        // A plain check of what Rodeo last read, for when something looks wrong.
        val last = gm.lastNotice
        if (last != null && gm.lastSeenAt > 0) {
            val parsed = remember(last, gm.lastArrow) { NoticeParser.parse(last, gm.lastArrow) }
            val ago = ((System.currentTimeMillis() - gm.lastSeenAt) / 1000).coerceAtLeast(0)
            val agoText = if (ago < 90) "$ago s ago" else "${ago / 60} min ago"
            val said = listOfNotNull(last.title, last.text, last.subText).joinToString(" | ")
            val read = parsed?.let { p -> listOfNotNull(p.distanceText, GmapsText.action(p), GmapsText.road(p), GmapsText.trip(p)).joinToString(" · ") } ?: "nothing"
            val arrow = gm.lastArrow?.let { a -> if (a.confident) "arrow ${a.maneuver.name.lowercase().replace('_', ' ')}" else "arrow unclear: Google's picture shown" } ?: "no arrow picture"
            Text("Last from Google Maps ($agoText): $said", color = c.textMuted, style = t.bodySm, modifier = Modifier.padding(top = 8.dp))
            Text("Rodeo read: $read · $arrow", color = c.textMuted, style = t.bodySm)
        }
    }
}
