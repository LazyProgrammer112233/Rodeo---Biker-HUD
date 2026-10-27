package com.rodeo.hud.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.sdp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** SOS tile: hold for [holdMs]; a white ring around the emergency icon fills while held. */
@Composable
fun HoldToTrigger(
    label: String,
    sublabel: String,
    color: Color,
    modifier: Modifier = Modifier,
    holdMs: Int = 2000,
    onTriggered: () -> Unit,
) {
    val c = LocalHud.current
    val t = LocalType.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = buttonShape()
    Box(
        modifier
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(c.dangerContainer, if (c.dark) Color(0xFFB3000F) else color)))
            .border(c.borderWidth, if (c.dark) color.copy(alpha = 0.5f) else c.glassBorder, shape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        onTriggered()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(200)) }
                })
            }
            .padding(horizontal = 6.sdp()),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.sdp()), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(30.sdp())) {
                    val sw = 3.dp.toPx()
                    drawArc(Color.White.copy(alpha = 0.25f), 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
                    if (progress.value > 0f) {
                        drawArc(Color.White, -90f, 360f * progress.value, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
                    }
                }
                // Six-armed emergency asterisk, drawn so it looks the same on every phone.
                Canvas(Modifier.size(14.sdp())) {
                    val r = size.minDimension / 2f
                    for (k in 0 until 3) {
                        val a = Math.toRadians(90.0 + k * 60.0)
                        val d = Offset((kotlin.math.cos(a) * r).toFloat(), (kotlin.math.sin(a) * r).toFloat())
                        drawLine(Color.White, center - d * 0.85f, center + d * 0.85f, r * 0.42f, StrokeCap.Round)
                    }
                }
            }
            Spacer(Modifier.width(6.sdp()))
            Column {
                FitText(label.uppercase(), t.headlineSm, Color.White)
                FitText(sublabel.uppercase(), t.micro, Color.White.copy(alpha = 0.9f))
            }
        }
    }
}

/** Slide-to-confirm bar, so End Ride is never hit by accident. */
@Composable
fun SlideToConfirm(text: String, modifier: Modifier = Modifier, color: Color = LocalHud.current.accent, onConfirm: () -> Unit) {
    val c = LocalHud.current
    val density = LocalDensity.current
    val h = 60.sdp()
    val shape = RoundedCornerShape(50)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(h)
            .clip(shape)
            .background(c.surfaceHigh)
            .border(c.borderWidth, c.glassBorder, shape),
    ) {
        val knob = h - 8.dp
        val maxPx = with(density) { (maxWidth - knob - 8.dp).toPx() }
        var x by remember { mutableFloatStateOf(0f) }
        FitText(
            text.uppercase(), LocalType.current.label, c.textMuted,
            modifier = Modifier.align(Alignment.Center).padding(start = knob + 12.dp, end = 12.dp),
        )
        Box(
            Modifier
                .offset { IntOffset(x.roundToInt() + with(density) { 4.dp.roundToPx() }, with(density) { 4.dp.roundToPx() }) }
                .size(knob)
                .clip(CircleShape)
                .background(color)
                .pointerInput(maxPx) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (x > maxPx * 0.85f) onConfirm()
                            x = 0f
                        },
                        onDragCancel = { x = 0f },
                    ) { _, d -> x = (x + d).coerceIn(0f, maxPx) }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.ChevronRight, null, tint = c.onAccent, modifier = Modifier.size(30.dp)) }
    }
}
