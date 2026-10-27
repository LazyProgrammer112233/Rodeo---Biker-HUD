package com.rodeo.hud.ui.hud

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.Ride
import com.rodeo.hud.graph
import com.rodeo.hud.ride.formatDuration
import com.rodeo.hud.sos.SosReason
import com.rodeo.hud.sos.SosState
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.rides.RouteTrace
import com.rodeo.hud.ui.theme.LocalHud

/** Full-screen SOS countdown and the "help is on the way" screen. */
@Composable
fun SosOverlay() {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val state by g.sos.state.collectAsState()
    when (val s = state) {
        is SosState.Idle -> Unit
        is SosState.Countdown -> Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable(enabled = false) {},
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (s.reason == SosReason.CRASH) "Possible crash detected" else "Sending SOS",
                        color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    )
                    if (s.dryRun) Text("Test mode: nothing will be sent", color = c.warning, fontSize = 14.sp)
                    Text("${s.secondsLeft}", color = c.danger, fontSize = 140.sp, fontWeight = FontWeight.Black, lineHeight = 140.sp)
                    Text("Your contacts get your location, speed and bike details.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                Box(
                    Modifier.size(200.dp).clip(CircleShape).background(Color.White).clickable { g.sos.cancel() },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("I'M OK", color = Color.Black, fontSize = 30.sp, fontWeight = FontWeight.Black)
                        Text("tap to cancel", color = Color.Black.copy(alpha = 0.6f), fontSize = 14.sp)
                    }
                }
            }
        }
        is SosState.Active -> Box(
            Modifier.fillMaxSize().background(c.background.copy(alpha = 0.97f)),
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(if (s.dryRun) "SOS test complete" else "SOS sent", color = c.danger, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(8.dp))
                    if (s.smsSentTo.isNotEmpty()) Text("SMS: " + s.smsSentTo.joinToString(), color = c.text, fontSize = 16.sp)
                    if (s.smsFailed.isNotEmpty()) Text("SMS failed: " + s.smsFailed.joinToString(), color = c.warning, fontSize = 14.sp)
                    if (s.smsSentTo.isEmpty() && s.smsFailed.isEmpty()) Text("No SOS contacts set. Add them in Settings.", color = c.warning)
                    if (!s.dryRun) Text("Location updates every 2 minutes · ${s.updates} sent", color = c.textMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    GlassCard { Text(s.message, color = c.textMuted, fontSize = 13.sp) }
                }
                Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    val next = s.whatsAppPending.firstOrNull()
                    if (next != null) {
                        PrimaryButton("WhatsApp ${next.name}", Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Filled.Send) { g.sos.openNextWhatsApp() }
                        Text("${s.whatsAppPending.size} contact(s) left on WhatsApp", color = c.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(16.dp))
                    }
                    GhostButton(if (s.dryRun) "Close" else "I'm safe · stop SOS", Modifier.fillMaxWidth(), icon = Icons.Filled.Check) { g.sos.cancel() }
                }
            }
        }
    }
}

@Composable
fun VoiceOverlay(onClose: () -> Unit) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val listening by g.voice.listening.collectAsState()
    val heard by g.voice.heard.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
        GlassCard(Modifier.width(460.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(if (listening) c.accent else c.textMuted.copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, null, tint = c.onAccent, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(if (listening) "Listening…" else "Say a command", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text(heard.ifBlank { "\"next song\" · \"pause\" · \"where is my bike\" · \"navigate home\" · \"end ride\" · \"SOS\"" }, color = c.textMuted, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun RideSummary(ride: Ride, onClose: () -> Unit) {
    val c = LocalHud.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.fillMaxWidth(0.85f).fillMaxHeight(0.85f).background(c.surface, RoundedCornerShape(20.dp))) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RouteTrace(ride, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ride saved", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("%.1f km".format(ride.distanceM / 1000), color = c.text, fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    SummaryLine("Time", formatDuration(ride.durationMs))
                    SummaryLine("Average", "${ride.avgSpeedKmh.toInt()} km/h")
                    SummaryLine("Top speed", "${ride.maxSpeedKmh.toInt()} km/h")
                    SummaryLine("Vibration", "avg %.2f g · peak %.2f g · %d s high".format(ride.vibrationAvg, ride.vibrationPeak, ride.highVibrationSec))
                    SummaryLine("Bumps logged", "${ride.hazards}")
                    SummaryLine("Parked at", if (ride.parkLat != null) "saved · find it from Rides" else "no GPS fix")
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Done", Modifier.fillMaxWidth()) { onClose() }
                }
            }
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    val c = LocalHud.current
    Row {
        Text(label, color = c.textMuted, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Text(value, color = c.text, fontSize = 15.sp, textAlign = TextAlign.Start)
    }
}
