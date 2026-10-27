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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.ui.theme.LocalHud
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** SOS: hold for [holdMs] to start the countdown; a ring fills while held. */
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
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.16f))
            .border(2.dp, color, RoundedCornerShape(20.dp))
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
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            if (progress.value > 0f) {
                drawArc(color, -90f, 360f * progress.value, false, Offset.Zero, Size(size.width, size.height),
                    style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = c.text, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Text(sublabel, color = c.textMuted, fontSize = 11.sp)
        }
    }
}

/** Slide-to-confirm bar, so End Ride is never hit by accident. */
@Composable
fun SlideToConfirm(text: String, modifier: Modifier = Modifier, color: Color = LocalHud.current.accent, onConfirm: () -> Unit) {
    val c = LocalHud.current
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(c.glass)
            .border(1.dp, c.glassBorder, RoundedCornerShape(32.dp)),
    ) {
        val knob = 56.dp
        val maxPx = with(density) { (maxWidth - knob - 8.dp).toPx() }
        var x by remember { mutableFloatStateOf(0f) }
        Text(text, color = c.textMuted, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
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
