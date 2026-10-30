package com.rodeo.hud.ui.pack

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.Geo
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.components.Radar
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.setup.Field
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType

/** Pack mode: start or join a group ride by code, see everyone live. */
@Composable
fun PackScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val pack by g.pack.state.collectAsState()
    val loc by g.location.location.collectAsState()
    val hazards by g.hazards.hazards.collectAsState()
    var code by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize().background(c.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                }
                Text("Pack mode", color = c.text, style = LocalType.current.headlineXl, modifier = Modifier.weight(1f))
                if (pack.active) {
                    // Leave sits up here so the rider list keeps the height on a landscape phone.
                    GhostButton("Leave", icon = Icons.AutoMirrored.Filled.ExitToApp) { g.pack.leave() }
                }
            }
            if (!pack.active) {
                Text(
                    if (g.pack.online) "Ride together: everyone sees each other live, with alerts when someone stops or the pack spreads out."
                    else "Firebase isn't connected in this build, so pack mode runs a demo with three simulated riders.",
                    color = c.textMuted,
                )
                PrimaryButton("Start a pack", Modifier.fillMaxWidth(), icon = Icons.Filled.Add) { g.pack.create() }
                SectionLabel("Or join with a code")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Field(code, { code = it.uppercase().take(6) }, "ABC123", Modifier.weight(1f))
                    GhostButton("Join", icon = Icons.Filled.Groups) { if (code.length == 6) g.pack.join(code) }
                }
            } else {
                GlassCard(Modifier.fillMaxWidth(), accentBorder = c.accent, padding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel(if (pack.demo) "Demo pack" else "Pack code · share it")
                            Text(pack.code.orEmpty(), color = c.text, style = LocalType.current.headlineXl.copy(letterSpacing = 4.sp), maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            SectionLabel("Spread")
                            Text("%.1f km".format(pack.spreadKm), color = c.accent, style = LocalType.current.headlineMd, maxLines = 1)
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(pack.members, key = { it.id }) { m ->
                        val d = loc?.let { Geo.distanceM(it.latitude, it.longitude, m.lat, m.lng) }
                        val stopped = m.stillSince != 0L && System.currentTimeMillis() - m.stillSince > 120_000
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.glass).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(if (stopped) c.warning else if (m.isLead) c.accent else c.text))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    m.name + (if (m.isMe) " (you)" else "") + (if (m.isLead) " · lead" else ""),
                                    color = c.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    (if (stopped) "Stopped" else "${m.speedKmh.toInt()} km/h") + (d?.takeIf { !m.isMe }?.let { " · %.1f km away".format(it / 1000) } ?: ""),
                                    color = if (stopped) c.warning else c.textMuted, style = LocalType.current.bodySm, maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(c.surface)) {
            Radar(Modifier.fillMaxSize(), loc?.latitude, loc?.longitude, loc?.bearing ?: 0f, pack.members, hazards, rangeM = 3000.0)
            Spacer(Modifier.height(1.dp))
        }
    }
}
