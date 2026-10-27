package com.rodeo.hud.ui.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rodeo.hud.data.Ride
import com.rodeo.hud.data.SosContact
import com.rodeo.hud.graph
import com.rodeo.hud.ride.formatDuration
import com.rodeo.hud.sos.SmsStatus
import com.rodeo.hud.sos.SosReason
import com.rodeo.hud.sos.SosState
import com.rodeo.hud.ui.components.FitText
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.components.SlideToConfirm
import com.rodeo.hud.ui.components.StatBox
import com.rodeo.hud.ui.rides.RouteTrace
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.sdp
import java.util.Locale

/** Emergency screens keep one fixed high-contrast palette whatever the bike or time of day. */
private object Sos {
    val base = Color(0xFF0B0709)
    val plate = Color(0xFF191B22)
    val plateHigh = Color(0xFF282A30)
    val red = Color(0xFFFF3B3B)
    val redDeep = Color(0xFF93000A)
    val salmon = Color(0xFFFFB4AB)
    val onSalmon = Color(0xFF690005)
    val white = Color(0xFFF4F4F8)
    val muted = Color(0xFFC9C3C6)
    val ok = Color(0xFF43E188)
    val amber = Color(0xFFF9BD2D)
}

/** Live SOS state from the app, drawn by [SosCountdownScreen] and [SosActiveScreen]. */
@Composable
fun SosOverlay() {
    val g = LocalContext.current.graph
    val state by g.sos.state.collectAsState()
    val profile by g.profile.profile.collectAsState()
    val sms by g.sos.sms.collectAsState()
    val loc by g.location.location.collectAsState()
    val speed by g.ride.speedKmh.collectAsState()
    when (val s = state) {
        is SosState.Idle -> Unit
        is SosState.Countdown -> SosCountdownScreen(
            secondsLeft = s.secondsLeft,
            total = s.total,
            crash = s.reason == SosReason.CRASH,
            dryRun = s.dryRun,
            contacts = profile.sosContacts,
            gps = loc?.let { "%.4f°N, %.4f°E".format(Locale.US, it.latitude, it.longitude) },
            smsReady = g.sos.canSendSms,
            speedKmh = speed.toInt(),
            bloodGroup = profile.bloodGroup,
            onOk = { g.sos.cancel() },
            onSendNow = { g.sos.fireNow() },
        )
        is SosState.Active -> SosActiveScreen(
            dryRun = s.dryRun,
            crash = s.reason == SosReason.CRASH,
            sms = sms,
            hasContacts = profile.sosContacts.isNotEmpty(),
            updates = s.updates,
            message = s.message,
            onStop = { g.sos.cancel() },
        )
    }
}

/** Crash / SOS countdown: who gets the SMS, the countdown, and a giant I'M OK target. */
@Composable
fun SosCountdownScreen(
    secondsLeft: Int,
    total: Int,
    crash: Boolean,
    dryRun: Boolean,
    contacts: List<SosContact>,
    gps: String?,
    smsReady: Boolean,
    speedKmh: Int,
    bloodGroup: String,
    onOk: () -> Unit,
    onSendNow: () -> Unit,
) {
    val t = LocalType.current
    EmergencyBackdrop {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.sdp(), vertical = 12.sdp())) {
            // Title row
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot(30.sdp())
                Spacer(Modifier.width(12.sdp()))
                Column(Modifier.weight(1f)) {
                    Tag(if (dryRun) "Test · nothing will be sent" else "Critical alert", if (dryRun) Sos.amber else Sos.red)
                    FitText(if (crash) "POSSIBLE CRASH DETECTED" else "SENDING SOS", t.display.copy(fontSize = t.display.fontSize * 0.8f), Sos.white)
                }
                Spacer(Modifier.width(10.sdp()))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.sdp())) {
                    Pill(gps?.let { "GPS $it" } ?: "No GPS fix yet", if (gps != null) Sos.ok else Sos.amber)
                    Pill(if (smsReady) "SMS ready · default SIM" else "SMS unavailable", if (smsReady) Sos.ok else Sos.red)
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val target = minOf(maxHeight * 0.96f, maxWidth * 0.36f)
                val ring = minOf(maxHeight * 0.52f, maxWidth * 0.2f)
                val rows = ((maxHeight - 120.sdp()) / 40.sdp()).toInt().coerceIn(1, 5)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.sdp())) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CountdownRing(secondsLeft, total, ring)
                            Spacer(Modifier.width(16.sdp()))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.sdp())) {
                                FitText("SENDING SOS IN ${secondsLeft}S", t.headlineLg, Sos.white)
                                Text(
                                    if (contacts.isEmpty()) "No SOS contacts set. Add them in Settings so this reaches someone."
                                    else "An SMS with your live location goes to ${contacts.size} contact${if (contacts.size == 1) "" else "s"}, then updates every 2 minutes.",
                                    color = if (contacts.isEmpty()) Sos.amber else Sos.muted, style = t.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                contacts.take(rows).forEach { ContactRow(it.name, if (dryRun) "TEST" else "SMS QUEUED", if (dryRun) Sos.amber else Sos.ok) }
                                if (contacts.size > rows) Text("+${contacts.size - rows} more", color = Sos.muted, style = t.label)
                            }
                        }
                        SirenNote(crash, speedKmh, bloodGroup)
                    }
                    Spacer(Modifier.width(16.sdp()))
                    OkTarget(target, onOk)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText("RODEO CRASH RESPONSE · TAP I'M OK IF YOU ARE SAFE", t.label, Sos.muted, Modifier.weight(1f))
                Spacer(Modifier.width(12.sdp()))
                val shape = RoundedCornerShape(14.sdp())
                Box(
                    Modifier.heightIn(min = 52.sdp()).clip(shape).background(Sos.salmon).clickable(onClick = onSendNow)
                        .padding(horizontal = 22.sdp(), vertical = 10.sdp()),
                    contentAlignment = Alignment.Center,
                ) { Text("SEND SOS NOW", color = Sos.onSalmon, style = t.headlineMd.copy(fontWeight = FontWeight.Black), maxLines = 1) }
            }
        }
    }
}

/** After sending: per-contact delivery, the repeat timer, and a deliberate slide to stop. */
@Composable
fun SosActiveScreen(
    dryRun: Boolean,
    crash: Boolean,
    sms: List<SmsStatus>,
    hasContacts: Boolean,
    updates: Int,
    message: String,
    onStop: () -> Unit,
) {
    val t = LocalType.current
    EmergencyBackdrop {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.sdp(), vertical = 14.sdp()), horizontalArrangement = Arrangement.spacedBy(18.sdp())) {
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.sdp(), Alignment.CenterVertically)) {
                Tag(if (dryRun) "Test complete" else if (crash) "Crash SOS active" else "SOS active", if (dryRun) Sos.amber else Sos.red)
                FitText(if (dryRun) "SOS TEST DONE" else "SOS SENT", t.display, if (dryRun) Sos.amber else Sos.red)
                if (!hasContacts) Text("No SOS contacts set. Add them in Settings.", color = Sos.amber, style = t.bodyLg)
                sms.take(5).forEach { s ->
                    val (label, color) = when (s.state) {
                        SmsStatus.State.SENDING -> "SENDING…" to Sos.muted
                        SmsStatus.State.SENT -> "SENT" to Sos.ok
                        SmsStatus.State.FAILED -> "FAILED: ${s.detail}".uppercase() to Sos.red
                        SmsStatus.State.TEST -> "TEST, NOT SENT" to Sos.amber
                    }
                    ContactRow(s.contact.name, label, color)
                }
                if (!dryRun) FitText("LIVE LOCATION EVERY 2 MINUTES · $updates UPDATE${if (updates == 1) "" else "S"} SENT", t.label, Sos.muted)
                Text(
                    message, color = Sos.muted, style = t.bodySm, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.sdp())).background(Sos.plate).padding(10.sdp()),
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (dryRun) {
                    OkTarget(minOf(220.sdp(), 260.dp), onStop, title = "CLOSE", sub = "TAP TO FINISH")
                } else {
                    Text("Safe now? Stop the SOS and the location updates.", color = Sos.white, style = t.bodyLg, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.sdp()))
                    SlideToConfirm("Slide to stop SOS", color = Sos.ok, onConfirm = onStop)
                }
            }
        }
    }
}

@Composable
private fun EmergencyBackdrop(content: @Composable () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "sos").animateFloat(0.25f, 0.55f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
    Box(
        Modifier.fillMaxSize()
            .background(Sos.base)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(Sos.redDeep.copy(alpha = pulse), Color.Transparent), center, size.maxDimension * 0.7f))
                drawRect(Sos.red.copy(alpha = pulse * 0.6f), style = Stroke(6.dp.toPx()))
            }
            // Swallow touches so nothing behind the emergency screen can be pressed.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) { content() }
}

@Composable
private fun PulseDot(size: Dp) {
    val p by rememberInfiniteTransition(label = "dot").animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "p")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        drawCircle(Sos.red.copy(alpha = (1f - p) * 0.5f), r * (0.6f + p * 0.4f))
        drawCircle(Sos.red, r * 0.55f)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text.uppercase(), color = Color.White, style = LocalType.current.micro, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.sdp())).background(color.copy(alpha = 0.85f)).padding(horizontal = 6.sdp(), vertical = 2.sdp()),
    )
}

@Composable
private fun Pill(text: String, dot: Color) {
    Row(
        Modifier.widthIn(max = 260.sdp()).clip(RoundedCornerShape(10.sdp())).background(Sos.plate).padding(horizontal = 10.sdp(), vertical = 6.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.sdp()).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.sdp()))
        FitText(text.uppercase(), LocalType.current.label, Sos.white)
    }
}

@Composable
private fun ContactRow(name: String, status: String, color: Color) {
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 34.sdp()).clip(RoundedCornerShape(10.sdp())).background(Sos.plate).padding(horizontal = 10.sdp(), vertical = 5.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Person, null, tint = Sos.salmon, modifier = Modifier.size(18.sdp()))
        Spacer(Modifier.width(8.sdp()))
        Text(name, color = Sos.white, style = t.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.sdp()))
        FitText(status, t.label, color, Modifier.widthIn(max = 160.sdp()))
    }
}

@Composable
private fun SirenNote(crash: Boolean, speedKmh: Int, bloodGroup: String) {
    val t = LocalType.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.sdp())).background(Sos.plate).padding(horizontal = 12.sdp(), vertical = 8.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.VolumeUp, null, tint = Sos.salmon, modifier = Modifier.size(18.sdp()))
        Spacer(Modifier.width(8.sdp()))
        Text(
            buildString {
                append(if (crash) "Alarm sounding. " else "")
                append("The SMS carries your map link, speed ($speedKmh km/h), bike details")
                if (bloodGroup.isNotBlank()) append(" and blood group ($bloodGroup)")
                append(".")
            },
            color = Sos.muted, style = t.bodySm, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CountdownRing(secondsLeft: Int, total: Int, size: Dp) {
    val t = LocalType.current
    val frac by animateFloatAsState(secondsLeft / total.coerceAtLeast(1).toFloat(), tween(900, easing = LinearEasing), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = this.size.minDimension * 0.07f
            val r = this.size.minDimension / 2f - sw / 2f
            val tl = Offset(center.x - r, center.y - r)
            drawArc(Sos.plateHigh, 0f, 360f, false, tl, Size(r * 2, r * 2), style = Stroke(sw))
            drawArc(Sos.red, -90f, 360f * frac, false, tl, Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val big = with(LocalDensity.current) { (size * 0.42f).toSp() }
            Text("%02d".format(secondsLeft), color = Sos.white, style = t.countdown.copy(fontSize = big, lineHeight = big), maxLines = 1, softWrap = false)
            FitText("SECONDS", t.micro, Sos.salmon, Modifier.widthIn(max = size * 0.7f))
        }
    }
}

/** The big white target. Anywhere on it cancels. */
@Composable
private fun OkTarget(size: Dp, onClick: () -> Unit, title: String = "I'M OK", sub: String = "TAP TO CANCEL") {
    val t = LocalType.current
    Box(
        Modifier.size(size)
            .drawBehind { drawCircle(Brush.radialGradient(listOf(Sos.red.copy(alpha = 0.18f), Color.Transparent)), this.size.minDimension / 2f) }
            .padding(size * 0.06f)
            .clip(CircleShape)
            .background(Sos.plateHigh)
            .padding(size * 0.035f)
            .clip(CircleShape)
            .background(Sos.white)
            .border(2.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(size * 0.16f).clip(CircleShape).background(Color(0xFF111318)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.11f))
            }
            val big = with(LocalDensity.current) { (size * 0.2f).toSp() }
            Text(title, color = Color(0xFF111318), style = t.countdown.copy(fontSize = big, lineHeight = big, fontWeight = FontWeight.Black), maxLines = 1, softWrap = false)
            Text(sub, color = Color(0xFF34373C), style = t.headlineSm.copy(fontSize = big * 0.32f, lineHeight = big * 0.36f), maxLines = 1, softWrap = false)
        }
    }
}

/** Voice panel: what the assistant heard and what it can do. */
@Composable
fun VoiceOverlay() {
    val g = LocalContext.current.graph
    val ui by g.assistant.ui.collectAsState()
    val profile by g.profile.profile.collectAsState()
    val level by g.assistant.level.collectAsState()
    // Shown while it waits for a command and briefly with its reply; training has its own card.
    if (ui.training == null && (ui.awake || ui.reply.isNotBlank())) {
        VoicePanel(ui.awake, ui.heard, ui.reply, profile.wakePhrase, level) { g.assistant.dismiss() }
    }
}

@Composable
fun VoicePanel(listening: Boolean, heard: String, reply: String, wakePhrase: String, level: Float, onClose: () -> Unit) {
    val c = LocalHud.current
    val t = LocalType.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(Modifier.widthIn(max = 600.sdp()).fillMaxWidth(0.72f), accentBorder = c.accent, padding = PaddingValues(18.sdp())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val pulse by rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.18f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s")
                Box(Modifier.size(76.sdp()), contentAlignment = Alignment.Center) {
                    // The ring grows with the rider's voice so they can see they're being heard.
                    if (listening) Box(Modifier.size(76.sdp() * (0.78f + 0.22f * maxOf(level * 2.5f, pulse - 1f).coerceIn(0f, 1f))).clip(CircleShape).background(c.accent.copy(alpha = 0.28f)))
                    Box(Modifier.size(58.sdp()).clip(CircleShape).background(if (listening) c.accent else c.surfaceHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Mic, null, tint = if (listening) c.onAccent else c.text, modifier = Modifier.size(30.sdp()))
                    }
                }
                Spacer(Modifier.width(16.sdp()))
                Column(Modifier.weight(1f)) {
                    FitText(
                        when {
                            reply.isNotBlank() -> reply.uppercase()
                            listening -> "LISTENING…"
                            else -> "SAY A COMMAND"
                        },
                        t.headlineXl, if (reply.isNotBlank() && !listening) c.accent else c.text,
                    )
                    Text(
                        heard.ifBlank { "\"Next song\" · \"Pause\" · \"Navigate home\" · \"Where is my bike\" · \"Emergency\"" }.let {
                            if (heard.isBlank()) it else "\u201C$it\u201D"
                        },
                        color = if (heard.isBlank()) c.textMuted else c.text, style = t.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (heard.isBlank()) Text(
                        "Wake phrase: \u201C$wakePhrase\u201D", color = c.textMuted, style = t.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** End-of-ride card: route trace on the left, the numbers on the right. */
@Composable
fun RideSummary(ride: Ride, onClose: () -> Unit) {
    val c = LocalHud.current
    val t = LocalType.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        GlassCard(Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f), rim = true, padding = PaddingValues(14.sdp())) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.sdp())) {
                RouteTrace(ride, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.sdp())) {
                    Text("RIDE SAVED", color = c.accent, style = t.label, maxLines = 1)
                    Row(verticalAlignment = Alignment.Bottom) {
                        FitText("%.1f".format(ride.distanceM / 1000), t.display, c.text, Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(6.sdp()))
                        Text("KM", color = c.accent, style = t.headlineLg, maxLines = 1, modifier = Modifier.padding(bottom = 4.sdp()))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.sdp())) {
                        StatBox("Time", formatDuration(ride.durationMs), "", Modifier.weight(1f))
                        StatBox("Avg", "${ride.avgSpeedKmh.toInt()}", "km/h", Modifier.weight(1f))
                        StatBox("Top", "${ride.maxSpeedKmh.toInt()}", "km/h", Modifier.weight(1f), valueColor = c.accent)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.sdp())) {
                        StatBox("Vibration", "%.2f".format(ride.vibrationAvg), "g avg", Modifier.weight(1f))
                        StatBox("Bumps", "${ride.hazards}", "", Modifier.weight(1f))
                    }
                    Text(
                        if (ride.parkLat != null) "Parking spot saved. Find it from Rides or say \"where is my bike\"." else "No GPS fix at the end, so no parking spot was saved.",
                        color = c.textMuted, style = t.bodySm, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Done", Modifier.fillMaxWidth()) { onClose() }
                }
            }
        }
    }
}
