package com.rodeo.hud.ui.hud

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.gmaps.GmapsState
import com.rodeo.hud.nav.gmaps.GmapsText
import com.rodeo.hud.nav.gmaps.MapsGuidance
import com.rodeo.hud.nav.gmaps.NavStatus
import com.rodeo.hud.nav.gmaps.VoicePlanner
import com.rodeo.hud.ui.components.FitText
import com.rodeo.hud.ui.components.panelShape
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.sdp

interface GmapsActions {
    fun toggleVoice()
    fun toggleMap()
    fun stopDemo() {}
}

/**
 * Google Maps' next turn, filling the map pane: a big arrow on the left, the distance and road
 * on the right, the trip along the bottom. Built to be read in a glance from the saddle.
 */
@Composable
fun GmapsPanel(s: GmapsState, actions: GmapsActions, modifier: Modifier = Modifier) {
    val c = LocalHud.current
    val t = LocalType.current
    val g = s.guidance
    BoxWithConstraints(
        modifier.clip(panelShape()).background(if (c.dark) c.surfaceLowest else Color.White)
            .border(c.borderWidth, c.glassBorder, panelShape()).testTag("gmaps-panel")
            // Touches stop here instead of panning the hidden map underneath.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        val pad = (maxHeight.value * 0.045f).dp
        val stripH = maxHeight * 0.17f
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth().padding(start = pad, top = pad, end = pad), verticalAlignment = Alignment.CenterVertically) {
                // The arrow: a square of about 40% of the width, as tall as the row allows.
                val side = minOf(maxWidth * 0.4f, (maxHeight - stripH - pad) * 0.94f)
                ArrowTile(s, Modifier.size(side))
                Spacer(Modifier.width(pad))
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 54.sdp()), verticalArrangement = Arrangement.Center) {
                    if (g == null) {
                        FitText("GOOGLE MAPS", t.headlineXl.copy(fontWeight = FontWeight.Black), c.text)
                        return@Column
                    }
                    Distance(g, maxHeight)
                    FitText(GmapsText.action(g), t.headlineXl.copy(fontWeight = FontWeight.Black, fontSize = t.headlineXl.fontSize * 1.25f), c.text, Modifier.testTag("gmaps-action"))
                    GmapsText.road(g)?.let { road ->
                        FitText(road.uppercase(), t.headlineLg.copy(fontWeight = FontWeight.Bold), c.textMuted, maxLines = 2, modifier = Modifier.testTag("gmaps-road"))
                    }
                }
            }
            TripStrip(s, Modifier.fillMaxWidth().height(stripH))
        }
        // Voice and map buttons, top right, clear of the text.
        Row(Modifier.align(Alignment.TopEnd).padding(10.sdp()), horizontalArrangement = Arrangement.spacedBy(8.sdp())) {
            if (s.demo) RoundButton(Icons.Filled.Close, "Stop preview", c.text, actions::stopDemo)
            RoundButton(if (s.voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, if (s.voiceOn) "Mute turn voice" else "Unmute turn voice", if (s.voiceOn) c.ok else c.textMuted, actions::toggleVoice)
            RoundButton(Icons.Filled.Map, "Show map", c.text, actions::toggleMap)
        }
        StatusBanner(s, Modifier.align(Alignment.TopCenter).padding(10.sdp()))
    }
}

/** Over the map ("show map"): a large card with the same turn, the trip and a way back. */
@Composable
fun GmapsCard(s: GmapsState, actions: GmapsActions, modifier: Modifier = Modifier) {
    val c = LocalHud.current
    val t = LocalType.current
    val g = s.guidance
    val shape = panelShape()
    Column(
        modifier.fillMaxWidth().clip(shape).background(if (c.dark) c.surface.copy(alpha = 0.97f) else Color.White)
            .border(maxOf(c.borderWidth, 1.dp), c.glassBorder, shape).padding(8.sdp()).testTag("gmaps-card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArrowTile(s, Modifier.size(88.sdp()))
            Spacer(Modifier.width(12.sdp()))
            Column(Modifier.weight(1f)) {
                if (g != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        g.distanceText?.let {
                            FitText(it.uppercase(), t.display.copy(fontSize = t.display.fontSize * 0.9f), c.accent, Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(10.sdp()))
                        }
                        FitText(GmapsText.action(g), t.headlineLg.copy(fontWeight = FontWeight.Black), c.text, Modifier.weight(1f, fill = false).padding(bottom = 4.sdp()))
                    }
                    GmapsText.road(g)?.let { FitText(it.uppercase(), t.headlineMd.copy(fontWeight = FontWeight.Bold), c.textMuted) }
                    val trip = GmapsText.trip(g)
                    FitText(listOfNotNull(statusWord(s), trip).joinToString("  ·  ").uppercase(), t.micro, if (statusWord(s) != null) c.warning else c.textMuted)
                } else FitText("GOOGLE MAPS", t.headlineLg, c.text)
            }
            Spacer(Modifier.width(8.sdp()))
            Column(verticalArrangement = Arrangement.spacedBy(6.sdp())) {
                RoundButton(if (s.voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, if (s.voiceOn) "Mute turn voice" else "Unmute turn voice", if (s.voiceOn) c.ok else c.textMuted, actions::toggleVoice, size = 40.sdp())
                RoundButton(Icons.Filled.Navigation, "Big turn view", c.accent, actions::toggleMap, size = 40.sdp())
            }
        }
    }
}

private fun statusWord(s: GmapsState): String? = when {
    s.ended && (s.guidance?.status == NavStatus.ARRIVED || s.guidance?.maneuver == Maneuver.ARRIVE) -> "Arrived"
    s.ended -> "Navigation ended"
    s.guidance?.status == NavStatus.REROUTING -> "Rerouting"
    s.guidance?.status == NavStatus.NO_GPS -> "GPS lost"
    else -> null
}

/** The turn picture: Rodeo's own bold arrow, or Google's picture when Rodeo could not read it. */
@Composable
private fun ArrowTile(s: GmapsState, modifier: Modifier) {
    val c = LocalHud.current
    val g = s.guidance
    val m = when {
        g == null -> Maneuver.UNKNOWN
        g.status == NavStatus.ARRIVED -> Maneuver.ARRIVE
        else -> VoicePlanner.spokenManeuver(g)
    }
    val picture: Bitmap? = s.picture.takeIf { m == Maneuver.UNKNOWN && g?.status != NavStatus.REROUTING }
    val image = remember(picture) { picture?.asImageBitmap() }
    val shape = RoundedCornerShape(18.sdp())
    val busy = g?.status == NavStatus.REROUTING || g?.status == NavStatus.NO_GPS
    BoxWithConstraints(
        modifier.clip(shape).background(if (image != null) Color(0xFF15181C) else if (busy) c.surfaceHigh else c.accent)
            .semantics { contentDescription = g?.let { GmapsText.action(it) } ?: "Google Maps" }.testTag("gmaps-arrow"),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        when {
            g?.status == NavStatus.REROUTING -> Icon(Icons.Filled.Refresh, null, tint = c.warning, modifier = Modifier.size(side * 0.6f))
            g?.status == NavStatus.NO_GPS -> Icon(Icons.Filled.GpsOff, null, tint = c.warning, modifier = Modifier.size(side * 0.55f))
            image != null -> Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.size(side * 0.8f))
            else -> ManeuverGlyph(m, c.onAccent, Modifier.size(side * 0.72f))
        }
        val exit = g?.roundaboutExit
        if (exit != null && m.name.startsWith("ROUNDABOUT") && !busy) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(side * 0.05f).size(side * 0.3f).clip(CircleShape)
                    .background(c.onAccent).testTag("gmaps-exit"),
                contentAlignment = Alignment.Center,
            ) { FitText(exit.toString(), LocalType.current.headlineXl.copy(fontWeight = FontWeight.Black, fontSize = LocalType.current.headlineXl.fontSize * (side.value / 160f).coerceIn(0.8f, 2.2f)), c.accent) }
        }
    }
}

@Composable
private fun Distance(g: MapsGuidance, paneHeight: Dp) {
    val c = LocalHud.current
    val t = LocalType.current
    val parts = GmapsText.distanceParts(g) ?: return
    // As big as the pane allows: roughly a third of its height.
    val big = t.speed.copy(fontSize = t.speed.fontSize * (paneHeight.value / 300f).coerceIn(0.7f, 1.6f))
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.testTag("gmaps-distance")) {
        FitText(parts.first, big, c.accent, Modifier.weight(1f, fill = false), minScale = 0.35f)
        if (parts.second.isNotEmpty()) {
            Spacer(Modifier.width(6.sdp()))
            Text(parts.second, color = c.accent, style = t.headlineXl.copy(fontWeight = FontWeight.Black), maxLines = 1, modifier = Modifier.offset(y = (-6).sdp()))
        }
    }
}

@Composable
private fun TripStrip(s: GmapsState, modifier: Modifier) {
    val c = LocalHud.current
    val t = LocalType.current
    val g = s.guidance
    Row(
        modifier.background(if (c.dark) c.surface else c.surfaceHigh).padding(horizontal = 16.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("GOOGLE MAPS", color = c.textMuted, style = t.micro, maxLines = 1)
        Spacer(Modifier.width(14.sdp()))
        val trip = g?.let { GmapsText.trip(it) }
        FitText((trip ?: "").uppercase(), t.headlineLg.copy(fontWeight = FontWeight.Bold), c.text, Modifier.weight(1f).testTag("gmaps-trip"))
        if (s.demo) {
            Spacer(Modifier.width(8.sdp()))
            Text("PREVIEW", color = c.warning, style = t.label, maxLines = 1)
        }
    }
}

@Composable
private fun StatusBanner(s: GmapsState, modifier: Modifier) {
    val c = LocalHud.current
    val word = statusWord(s) ?: return
    val arrived = word == "Arrived"
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(if (arrived) c.ok else c.warning).padding(horizontal = 14.sdp(), vertical = 6.sdp()).testTag("gmaps-status"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(word.uppercase(), color = if (arrived) Color.Black else c.onWarning, style = LocalType.current.label.copy(fontWeight = FontWeight.Black), maxLines = 1)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit, size: Dp = 48.sdp()) {
    val c = LocalHud.current
    Box(
        Modifier.size(size).clip(CircleShape).background(c.surfaceHigh).border(c.borderWidth, c.glassBorder, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}
