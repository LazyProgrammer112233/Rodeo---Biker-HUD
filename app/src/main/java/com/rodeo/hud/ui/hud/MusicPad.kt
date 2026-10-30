package com.rodeo.hud.ui.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rodeo.hud.core.MusicAction
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import kotlinx.coroutines.launch

/**
 * Music pad for a mounted phone on the move: the whole screen is three music buttons, and every
 * other touch on the HUD is blocked so a glove, rain or a brush of the hand can't press anything
 * else. Left third = previous song, middle = play / pause, right third = next song. A tap lights its
 * zone with a glassy wash of the accent and the action's icon. Holding the lock pill for a second
 * switches the pad off.
 */
@Composable
fun MusicPad(playing: Boolean, onAction: (MusicAction) -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("music-pad")) {
        Row(Modifier.fillMaxSize()) {
            PadZone(MusicAction.PREVIOUS, Icons.Filled.SkipPrevious, "Previous", Modifier.weight(1f), onAction)
            PadDivider()
            PadZone(MusicAction.PLAY_PAUSE, if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", Modifier.weight(1f), onAction)
            PadDivider()
            PadZone(MusicAction.NEXT, Icons.Filled.SkipNext, "Next", Modifier.weight(1f), onAction)
        }
        UnlockPill(onExit, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
    }
}

@Composable
private fun PadZone(action: MusicAction, icon: ImageVector, label: String, modifier: Modifier, onAction: (MusicAction) -> Unit) {
    val c = LocalHud.current
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.fillMaxHeight().testTag("pad-${action.name.lowercase()}")
            .pointerInput(action) {
                detectTapGestures(onTap = {
                    onAction(action)
                    scope.launch {
                        flash.snapTo(1f)
                        flash.animateTo(0f, tween(650, easing = LinearEasing))
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        // The tap's wash: a soft accent glow from the centre with a bright rim, fading out.
        if (flash.value > 0f) {
            Box(
                Modifier.fillMaxSize().alpha(flash.value)
                    .background(Brush.radialGradient(listOf(c.accent.copy(alpha = 0.42f), c.accent.copy(alpha = 0.16f))))
                    .border(2.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), c.accent.copy(alpha = 0.6f))), RoundedCornerShape(18.dp)),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(flash.value)) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(84.dp))
                Text(label.uppercase(), color = Color.White, style = LocalType.current.headlineMd.copy(fontWeight = FontWeight.Black))
            }
        }
        // A faint label at the bottom so the rider can see the pad is on and what each part does.
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(RoundedCornerShape(50))
                .background(c.glass).border(1.dp, c.glassBorder, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp).alpha(0.85f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), color = c.text, style = LocalType.current.micro)
        }
    }
}

@Composable
private fun PadDivider() {
    val c = LocalHud.current
    Box(
        Modifier.width(1.dp).fillMaxHeight().padding(vertical = 40.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, c.accent.copy(alpha = 0.35f), Color.Transparent))),
    )
}

/** Hold for a second to switch the pad off; a tap does nothing, so a brush of the hand can't unlock it. */
@Composable
private fun UnlockPill(onExit: () -> Unit, modifier: Modifier) {
    val c = LocalHud.current
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(c.glass).border(1.dp, c.glassBorder, shape).testTag("pad-unlock")
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(1_000, easing = LinearEasing))
                        onExit()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(150)) }
                })
            },
    ) {
        Box(Modifier.matchParentSizeFraction(progress.value).background(c.accent.copy(alpha = 0.35f)))
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Lock, null, tint = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("MUSIC PAD · HOLD TO UNLOCK", color = c.text, style = LocalType.current.micro)
        }
    }
}

private fun Modifier.matchParentSizeFraction(f: Float): Modifier = this.fillMaxHeight().fillMaxWidth(f.coerceIn(0f, 1f))
