package com.rodeo.hud.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rodeo.hud.core.TapTemplates
import com.rodeo.hud.core.TapZone
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val PER_ZONE = 6

/**
 * Teaches Rodeo how the rider's taps feel on their own mount: a few knocks on the left end, the
 * middle and the right end of the top edge, then a free test. Mounts differ a lot (clamp, rubber,
 * a case), so learning them is what keeps bumps from counting as taps.
 */
@Composable
fun TapSetupScreen(onBack: () -> Unit) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val t = LocalType.current
    val profile by g.profile.profile.collectAsState()
    val seen by g.taps.seen.collectAsState()
    val samples = remember { TapZone.entries.associateWith { mutableStateListOf<FloatArray>() } }
    val peaks = remember { mutableStateListOf<Float>() }
    var step by remember { mutableStateOf(TapZone.LEFT as TapZone?) } // null = testing
    var lastZone by remember { mutableStateOf<TapZone?>(null) }
    var lastSeenAt by remember { mutableStateOf(0L) }
    var testCount by remember { mutableStateOf(0) }
    val flash = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        g.taps.training = true
        g.taps.startRecording("setup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()))
        onDispose {
            g.taps.training = false
            g.taps.stopRecording()
        }
    }
    BackHandler { onBack() }

    LaunchedEffect(seen) {
        val s = seen ?: return@LaunchedEffect
        if (s.at <= lastSeenAt) return@LaunchedEffect
        lastSeenAt = s.at
        val zone = step
        if (zone != null) {
            samples.getValue(zone).add(g.taps.normalise(s.knock.features()))
            peaks.add(s.knock.peak)
            lastZone = zone
            if (samples.getValue(zone).size >= PER_ZONE) {
                step = TapZone.entries.getOrNull(zone.ordinal + 1)
                if (step == null) {
                    // All three learned: save, and use them straight away for the test.
                    val templates = TapTemplates(samples.mapValues { it.value.toList() })
                    val minPeak = (peaks.sorted()[peaks.size / 2] * 0.4f).coerceIn(1.0f, 8f)
                    g.profile.update { it.copy(tapTemplates = templates.toJson(), tapMinPeak = minPeak, tapControl = true) }
                    g.taps.classifier.templates = templates
                    g.taps.minPeak = minPeak
                }
            }
        } else {
            lastZone = s.zone
            testCount++
        }
        flash.snapTo(1f)
        flash.animateTo(0f, tween(600))
    }

    Row(Modifier.fillMaxSize().background(c.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                }
                Text("Tap setup", color = c.text, style = t.headlineXl)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                val zone = step
                if (zone != null) {
                    SectionLabel("Step ${zone.ordinal + 1} of 3")
                    Text(
                        "Knock $PER_ZONE times on the ${zone.name.lowercase()} ${if (zone == TapZone.MIDDLE) "of the" else "end of the"} top edge",
                        color = c.text, style = t.headlineLg,
                    )
                    Text(
                        "Mount the phone the way you ride, then knock firmly with a fingertip (or a gloved knuckle) on the edge facing up, about one knock a second.",
                        color = c.textMuted, style = t.body,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        repeat(PER_ZONE) { i ->
                            val done = i < samples.getValue(zone).size
                            Box(Modifier.size(18.dp).clip(CircleShape).background(if (done) c.accent else c.surfaceHigh))
                        }
                    }
                } else {
                    SectionLabel("Try it")
                    Text("Learned. Now knock anywhere on the top edge", color = c.text, style = t.headlineLg)
                    Text(
                        when {
                            testCount == 0 -> "Each knock shows where Rodeo felt it. Left end = previous song, middle = play / pause, two quick knocks on the right end = next song."
                            lastZone == null -> "That one didn't look like your taps, so it would be ignored."
                            else -> "Felt: ${lastZone!!.name.lowercase()} → " + when (lastZone) {
                                TapZone.LEFT -> "previous song"
                                TapZone.MIDDLE -> "play / pause"
                                else -> "next song (when you knock twice)"
                            }
                        },
                        color = c.textMuted, style = t.body,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == null) PrimaryButton("Done", icon = Icons.Filled.Check) { onBack() }
                GhostButton("Start again", icon = Icons.Filled.Refresh) {
                    samples.values.forEach { it.clear() }
                    peaks.clear()
                    step = TapZone.LEFT
                    lastZone = null
                    testCount = 0
                }
            }
            if (!g.taps.available) Text("This phone has no accelerometer, so taps can't be used.", color = c.danger, style = t.body)
            if (profile.tapTemplates != null && step == TapZone.LEFT && samples.getValue(TapZone.LEFT).isEmpty()) {
                Text("You set this up before; doing it again replaces it.", color = c.textMuted, style = t.bodySm)
            }
        }
        // The phone as mounted, with the part of the top edge to knock on lit up.
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val target = step
            val lit = target ?: lastZone
            val accent = c.accent
            val edge = c.text
            val pulse = flash.value
            Canvas(Modifier.fillMaxWidth(0.9f).aspectRatio(2.1f)) {
                val w = size.width
                val h = size.height
                val top = h * 0.22f
                drawRoundRect(edge.copy(alpha = 0.08f), Offset(0f, top), Size(w, h - top), CornerRadius(h * 0.12f))
                drawRoundRect(edge.copy(alpha = 0.5f), Offset(0f, top), Size(w, h - top), CornerRadius(h * 0.12f), style = Stroke(3.dp.toPx()))
                TapZone.entries.forEach { z ->
                    val x0 = w * (0.06f + z.ordinal * 0.3f)
                    val on = z == lit
                    drawRoundRect(
                        if (on) accent.copy(alpha = 0.4f + 0.6f * if (target == null) pulse else 1f) else edge.copy(alpha = 0.18f),
                        Offset(x0, top - h * 0.06f), Size(w * 0.28f, h * 0.09f), CornerRadius(h * 0.045f),
                    )
                    if (on && pulse > 0f) {
                        drawCircle(accent.copy(alpha = 0.5f * pulse), radius = h * (0.1f + 0.25f * (1 - pulse)), center = Offset(x0 + w * 0.14f, top - h * 0.02f))
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Top edge, as you see it on the mount", color = c.textMuted, style = t.bodySm)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
