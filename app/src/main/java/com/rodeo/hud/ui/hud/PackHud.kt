package com.rodeo.hud.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.SignalCellularConnectedNoInternet0Bar
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.pack.Link
import com.rodeo.hud.pack.PackEvent
import com.rodeo.hud.pack.PackLogic
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.pack.PeerLink
import com.rodeo.hud.pack.VoiceMode
import com.rodeo.hud.pack.VoiceUi
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.sdp
import kotlinx.coroutines.delay
import kotlin.math.floor

/** Everything the pack layer over the map needs, as plain values (so it can be drawn in tests). */
data class PackHudState(
    val members: List<PackMember>,
    /** The rider's direction of travel while moving (the map is heading-up then), null when parked (north-up). */
    val headingDeg: Double?,
    val voice: VoiceUi,
    val event: PackEvent?,
    val now: Long,
    /** The big Google Maps guidance panel is up: no mini-map, smaller chips. */
    val compact: Boolean = false,
)

interface PackHudActions {
    fun toggleMute()
    fun talk(down: Boolean)
    fun openPack()
}

/** A rider's colour from the shared palette. */
fun riderColor(m: PackMember): Color = Color(PackLogic.PALETTE[m.colorIndex.mod(PackLogic.PALETTE.size)])

/**
 * The pack, drawn over the navigation map:
 * - a chip for every rider on the left or right edge, placed by the direction they are in (ahead at
 *   the top, behind at the bottom), with distance and "ahead / behind" in large type;
 * - a heading-up mini-map when anyone is within 500 m;
 * - a pop-up for pack news ("Rahul fell 2.1 km behind");
 * - the voice channel button (tap to mute, or hold to talk in push-to-talk).
 * Keeps clear of the top band (search, turn card) and the bottom strip (ETA, zoom).
 */
@Composable
fun PackHud(s: PackHudState, actions: PackHudActions, modifier: Modifier = Modifier) {
    val me = s.members.firstOrNull { it.isMe }
    val others = s.members.filter { !it.isMe }
    BoxWithConstraints(modifier.testTag("pack-hud")) {
        val density = LocalDensity.current
        val small = maxHeight < 300.dp || maxWidth < 330.dp || s.compact
        val chipH = if (small) 44.dp else 54.dp
        val gap = 6.dp
        // Bands, top to bottom: search bar, alert banner, chips. The left edge stops above the zoom buttons.
        val toastTop = 54.dp
        val top = maxOf(maxHeight * 0.22f, toastTop + 42.dp)
        val bottom = maxHeight * 0.80f
        val leftBottom = maxHeight * 0.72f
        val close = me != null && others.any { !it.stale(s.now) && PackLogic.distanceM(me.lat, me.lng, it.lat, it.lng) <= MINI_RANGE_M }
        val showMini = close && !s.compact && maxHeight >= 260.dp
        val miniSize = if (maxHeight < 380.dp) 112.dp else 148.dp
        val voiceOn = s.voice.wanted

        // Right edge: chips above the mini-map. Left edge: the voice button, then chips.
        val rightBottom = if (showMini) bottom - miniSize - gap else bottom
        val leftTop = if (voiceOn) top + (if (small) 50.dp else 60.dp) + gap else top

        // Direction of each rider from the way the map points (heading-up while moving, else north-up).
        val mapUp = s.headingDeg ?: 0.0
        val rel = others.associate { m ->
            m.id to me?.let { PackLogic.relativeBearing(mapUp, PackLogic.bearing(it.lat, it.lng, m.lat, m.lng)) }
        }
        val dist = others.associate { m -> m.id to me?.let { PackLogic.distanceM(it.lat, it.lng, m.lat, m.lng) } }
        // The nearest riders win the space when an edge is full.
        val byDistance = others.sortedBy { dist[it.id] ?: Double.MAX_VALUE }
        fun capacity(from: Dp, to: Dp) = maxOf(1, floor(((to - from + gap) / (chipH + gap))).toInt())
        val leftCap = capacity(leftTop, leftBottom)
        val rightCap = capacity(top, rightBottom)
        val leftIds = byDistance.filter { (rel[it.id] ?: 180.0) < 0 }.take(leftCap).map { it.id }
        val rightIds = byDistance.filter { (rel[it.id] ?: 180.0) >= 0 }.take(rightCap).map { it.id }
        val slots = with(density) {
            PackLogic.layoutChips(leftIds.map { it to rel[it] }, leftTop.toPx(), leftBottom.toPx(), chipH.toPx(), gap.toPx()) +
                PackLogic.layoutChips(rightIds.map { it to (rel[it] ?: 180.0) }, top.toPx(), rightBottom.toPx(), chipH.toPx(), gap.toPx())
        }.filter { (it.right && it.id in rightIds) || (!it.right && it.id in leftIds) }

        for (slot in slots) {
            val m = others.firstOrNull { it.id == slot.id } ?: continue
            val y = with(density) { slot.y.toDp() }
            val describeRel = s.headingDeg?.let { rel[m.id] }
            RiderChip(
                m, dist[m.id], rel[m.id], describeRel, s.now, small, s.voice.peers[m.id],
                Modifier.align(if (slot.right) Alignment.TopEnd else Alignment.TopStart)
                    .offset(y = y)
                    .padding(horizontal = 8.sdp())
                    .height(chipH),
                right = slot.right,
            )
        }

        if (showMini && me != null) {
            MiniMap(
                me, others, s.headingDeg, s.now,
                Modifier.align(Alignment.TopEnd).offset(y = bottom - miniSize).padding(end = 8.sdp()).size(miniSize),
            )
        }

        if (voiceOn) {
            VoiceButton(
                s.voice, small, actions,
                Modifier.align(Alignment.TopStart).offset(y = top).padding(start = 8.sdp()),
            )
        }

        PackToast(s.event, s.now, Modifier.align(Alignment.TopCenter).offset(y = toastTop).padding(horizontal = 8.dp))
    }
}

@Composable
private fun RiderChip(
    m: PackMember,
    distanceM: Double?,
    placeRel: Double?,
    describeRel: Double?,
    now: Long,
    small: Boolean,
    voiceLink: PeerLink?,
    modifier: Modifier,
    right: Boolean,
) {
    val c = LocalHud.current
    val color = riderColor(m)
    val stale = m.stale(now)
    val stopped = m.stopped(now)
    val pulse = if (m.sos) {
        val t = rememberInfiniteTransition(label = "sos")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "sos").value
    } else 1f
    val ring = when {
        m.sos -> c.danger
        stale -> c.textSubtle
        else -> color
    }
    val avatar = if (small) 30.dp else 38.dp
    val main = when {
        distanceM == null -> "—"
        else -> PackLogic.distanceText(distanceM)
    }
    val sub = when {
        m.sos -> "SOS"
        stale -> "no contact · " + ago(m.ageMs(now))
        stopped -> "stopped"
        distanceM != null && distanceM < 10 -> "with you"
        else -> PackLogic.side(describeRel).let { side ->
            when (side) {
                PackLogic.Side.LEFT -> "left"
                PackLogic.Side.RIGHT -> "right"
                else -> PackLogic.sideWord(side)
            }
        }
    }
    Row(
        modifier
            .alpha(if (stale) 0.7f else 1f)
            .clip(RoundedCornerShape(50))
            .background(c.glass.copy(alpha = 0.92f))
            .background(if (m.sos) c.danger.copy(alpha = 0.35f * pulse) else Color.Transparent)
            .border(2.dp, ring.copy(alpha = if (m.sos) pulse else 0.95f), RoundedCornerShape(50))
            .padding(horizontal = 5.dp)
            .widthIn(max = if (small) 150.dp else 190.dp)
            .testTag("pack-chip-${m.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The side the rider is on reads first: the avatar sits on the screen edge.
        val avatarBox = @Composable {
            Box(Modifier.size(avatar), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(avatar).clip(CircleShape).background(if (stale) c.textSubtle else if (m.sos) c.danger else color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(PackLogic.initials(m.name), color = Color.White, fontWeight = FontWeight.Black, fontSize = if (small) 12.sp else 15.sp, maxLines = 1)
                }
                // Direction pointer round the avatar.
                if (placeRel != null && distanceM != null && distanceM >= 10) {
                    Canvas(Modifier.requiredSize(avatar + 12.dp).rotate(placeRel.toFloat())) {
                        val w = size.width
                        val tip = 1.dp.toPx()
                        val p = Path().apply {
                            moveTo(w / 2f, tip)
                            lineTo(w / 2f - 5.dp.toPx(), tip + 7.dp.toPx())
                            lineTo(w / 2f + 5.dp.toPx(), tip + 7.dp.toPx())
                            close()
                        }
                        drawPath(p, if (stale) c.textSubtle else color)
                        drawPath(p, Color.White, style = Stroke(1.dp.toPx()))
                    }
                }
            }
        }
        if (!right) avatarBox()
        Column(Modifier.padding(horizontal = 2.dp), horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    main, color = c.text, fontWeight = FontWeight.ExtraBold,
                    style = if (small) LocalType.current.headlineSm else LocalType.current.headlineMd, maxLines = 1,
                )
                when {
                    m.sos -> Icon(Icons.Filled.Warning, "SOS", tint = c.danger, modifier = Modifier.padding(start = 3.dp).size(14.dp))
                    stale -> Icon(Icons.Filled.SignalCellularConnectedNoInternet0Bar, "No contact", tint = c.textMuted, modifier = Modifier.padding(start = 3.dp).size(13.dp))
                    stopped -> Icon(Icons.Filled.PauseCircle, "Stopped", tint = c.warning, modifier = Modifier.padding(start = 3.dp).size(14.dp))
                    m.link == Link.NEARBY -> Icon(Icons.Filled.Bluetooth, "Linked directly", tint = c.textMuted, modifier = Modifier.padding(start = 3.dp).size(13.dp))
                }
                if (voiceLink == PeerLink.CONNECTED) Icon(Icons.Filled.Mic, "In voice", tint = c.ok, modifier = Modifier.padding(start = 2.dp).size(13.dp))
            }
            Text(
                (m.name.substringBefore(' ').take(10)) + " · " + sub,
                color = if (m.sos) c.danger else if (stopped) c.warning else c.textMuted,
                fontSize = if (small) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (right) avatarBox()
    }
}

/** Heading-up circle of the riders within 500 m, rings at 100 and 250 m. */
@Composable
private fun MiniMap(me: PackMember, others: List<PackMember>, headingDeg: Double?, now: Long, modifier: Modifier) {
    val c = LocalHud.current
    val near = others.filter { PackLogic.distanceM(me.lat, me.lng, it.lat, it.lng) <= MINI_RANGE_M * 1.05 }
    Box(modifier.clip(CircleShape).background(c.glass.copy(alpha = 0.94f)).border(1.5.dp, c.accent.copy(alpha = 0.7f), CircleShape).testTag("pack-minimap")) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            listOf(100.0, 250.0).forEach { m ->
                drawCircle(c.textMuted.copy(alpha = 0.3f), (m / MINI_RANGE_M * (r - 6.dp.toPx())).toFloat(), center, style = Stroke(1.dp.toPx()))
            }
            for (m in near) {
                val d = PackLogic.distanceM(me.lat, me.lng, m.lat, m.lng)
                val rel = PackLogic.relativeBearing(headingDeg ?: 0.0, PackLogic.bearing(me.lat, me.lng, m.lat, m.lng))
                val (x, y) = PackLogic.miniMapPoint(d, rel, MINI_RANGE_M)
                val p = Offset(center.x + x * (r - 8.dp.toPx()), center.y + y * (r - 8.dp.toPx()))
                val col = if (m.sos) c.danger else if (m.stale(now)) c.textSubtle else riderColor(m)
                drawCircle(Color.White, 7.dp.toPx(), p)
                drawCircle(col, 5.5f.dp.toPx(), p)
            }
            // This rider: an arrow pointing up.
            val a = 9.dp.toPx()
            val path = Path().apply {
                moveTo(center.x, center.y - a)
                lineTo(center.x - a * 0.7f, center.y + a * 0.7f)
                lineTo(center.x, center.y + a * 0.3f)
                lineTo(center.x + a * 0.7f, center.y + a * 0.7f)
                close()
            }
            drawPath(path, c.accent)
            drawPath(path, Color.White, style = Stroke(1.5f.dp.toPx()))
        }
        Text(
            "500 m", color = c.textMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
        )
    }
}

/** Tap to mute / unmute (open mic), or hold to talk (push-to-talk). Big enough for gloves. */
@Composable
private fun VoiceButton(v: VoiceUi, small: Boolean, actions: PackHudActions, modifier: Modifier) {
    val c = LocalHud.current
    val size = if (small) 50.dp else 60.dp
    val connected = v.peers.count { it.value == PeerLink.CONNECTED }
    val failed = v.peers.count { it.value == PeerLink.FAILED }
    val bg = when {
        v.paused || !v.live -> c.surfaceHigh
        v.sending -> c.ok
        else -> c.surfaceHigh
    }
    val label = when {
        v.paused -> "Paused"
        !v.live -> "Joining"
        v.mode == VoiceMode.PUSH_TO_TALK -> if (v.talking) "Talking" else "Hold to talk"
        v.muted -> "Muted"
        else -> "Live"
    }
    Row(modifier.testTag("pack-voice"), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(size).clip(CircleShape).background(bg).border(2.dp, if (v.sending) Color.White else c.glassBorder, CircleShape)
                .pointerInput(v.mode) {
                    if (v.mode == VoiceMode.PUSH_TO_TALK) {
                        detectTapGestures(onPress = {
                            actions.talk(true)
                            tryAwaitRelease()
                            actions.talk(false)
                        })
                    } else detectTapGestures(onTap = { actions.toggleMute() })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (v.sending) Icons.Filled.Mic else Icons.Filled.MicOff, label,
                tint = if (v.sending) Color.White else c.text, modifier = Modifier.size(size * 0.5f),
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.glass.copy(alpha = 0.9f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(label, color = if (v.sending) c.ok else c.text, fontWeight = FontWeight.Bold, fontSize = if (small) 12.sp else 14.sp, maxLines = 1)
            Text(
                when {
                    failed > 0 && connected == 0 -> "can't connect"
                    v.peers.isEmpty() -> "waiting for riders"
                    else -> "$connected of ${v.peers.size} linked"
                },
                color = if (failed > 0) c.warning else c.textMuted, fontSize = 10.sp, maxLines = 1,
            )
        }
    }
}

/** The latest pack news, for six seconds (danger stays until it is over). */
@Composable
private fun PackToast(e: PackEvent?, now: Long, modifier: Modifier) {
    val c = LocalHud.current
    var shown by remember { mutableStateOf<PackEvent?>(null) }
    LaunchedEffect(e) {
        if (e == null || now - e.at > 8_000) return@LaunchedEffect
        shown = e
        delay(if (e.severity >= 2) 20_000 else 6_000)
        if (shown == e) shown = null
    }
    AnimatedVisibility(shown != null, modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val ev = shown ?: return@AnimatedVisibility
        val col = when (ev.severity) { 2 -> c.danger; 1 -> c.warning; else -> c.accent }
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface.copy(alpha = 0.96f)).border(2.dp, col, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp).testTag("pack-toast"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (ev.severity >= 1) Icons.Filled.Warning else Icons.Filled.Groups, null, tint = col, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(ev.text, color = c.text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun ago(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000}s"
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> "${ms / 3_600_000} h"
}

private const val MINI_RANGE_M = 500.0
