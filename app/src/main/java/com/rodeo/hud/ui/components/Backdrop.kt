package com.rodeo.hud.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.GaugeStyle
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalHudStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animated 3D particles behind the HUD, in the brand's personality:
 * race brands get warp streaks and sparks, heritage brands get drifting embers,
 * electric and commuter brands get a receding light grid. Speed drives the motion.
 */
@Composable
fun ParticleField(modifier: Modifier, speedKmh: Float, lowPower: Boolean = false) {
    val style = LocalHudStyle.current.gauge
    val c = LocalHud.current
    val speed by rememberUpdatedState(speedKmh)
    val count = if (lowPower) 28 else 90
    val field = remember(style, count) { Particles(count, style) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(field, lowPower) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceAtMost(0.05f)
                last = t
                field.step(dt, speed)
                frame = t
            }
            // Low power: about 20 fps is plenty for a background.
            if (lowPower) kotlinx.coroutines.delay(33)
        }
    }

    Canvas(modifier) {
        if (frame < 0) return@Canvas // reading frame redraws the canvas every tick
        val strength = if (c.dark) 1f else 0.75f
        when (style) {
            GaugeStyle.RACE -> field.drawStreaks(this, c.accent, c.text, strength)
            GaugeStyle.CLASSIC -> field.drawEmbers(this, c.accent, c.tint, strength)
            GaugeStyle.MINIMAL -> field.drawGrid(this, c.accent, strength, field.gridOffset)
        }
    }
}

private class Particles(n: Int, private val style: GaugeStyle) {
    // Positions in a unit cube: x, y in -1..1, z depth in 0.05..1 (1 = far).
    val x = FloatArray(n)
    val y = FloatArray(n)
    val z = FloatArray(n)
    val pz = FloatArray(n)
    val seed = FloatArray(n)
    var gridOffset = 0f
    private val rnd = Random(7)

    init {
        for (i in 0 until n) respawn(i, initial = true)
    }

    private fun respawn(i: Int, initial: Boolean = false) {
        x[i] = rnd.nextFloat() * 2f - 1f
        y[i] = rnd.nextFloat() * 2f - 1f
        z[i] = if (initial) 0.05f + rnd.nextFloat() * 0.95f else 1f
        if (style == GaugeStyle.CLASSIC && !initial) { y[i] = 1.1f; z[i] = 0.2f + rnd.nextFloat() * 0.8f }
        pz[i] = z[i]
        seed[i] = rnd.nextFloat()
    }

    fun step(dt: Float, speedKmh: Float) {
        val v = 0.12f + (speedKmh / 120f).coerceIn(0f, 1.6f) // idle drift, faster with speed
        gridOffset = (gridOffset + dt * v * 2.2f) % 1f
        for (i in x.indices) {
            pz[i] = z[i]
            when (style) {
                GaugeStyle.CLASSIC -> {
                    // Embers rise and sway.
                    y[i] -= dt * (0.06f + seed[i] * 0.08f) * (1f + v)
                    x[i] += dt * 0.05f * sin((y[i] * 6f + seed[i] * 10f).toDouble()).toFloat()
                    if (y[i] < -1.1f) respawn(i)
                }
                else -> {
                    z[i] -= dt * v * (0.5f + seed[i] * 0.5f)
                    if (z[i] < 0.05f) respawn(i)
                }
            }
        }
    }

    private fun project(scope: DrawScope, px: Float, py: Float, pz: Float): Offset {
        val cx = scope.size.width / 2f
        val cy = scope.size.height / 2f
        val f = 0.35f / pz
        return Offset(cx + px * cx * f, cy + py * cy * f)
    }

    fun drawStreaks(s: DrawScope, accent: Color, light: Color, strength: Float) {
        for (i in x.indices) {
            val head = project(s, x[i], y[i], z[i])
            val tail = project(s, x[i], y[i], (pz[i] + 0.04f + (1f - z[i]) * 0.06f).coerceAtMost(1f))
            val near = (1f - z[i]).coerceIn(0f, 1f)
            val color = if (seed[i] > 0.85f) light else accent
            s.drawLine(
                color.copy(alpha = (0.15f + near * 0.75f) * strength), tail, head,
                strokeWidth = (1f + near * 4f) * s.density, cap = StrokeCap.Round,
            )
        }
    }

    fun drawEmbers(s: DrawScope, accent: Color, tint: Color, strength: Float) {
        for (i in x.indices) {
            val p = project(s, x[i] * 2.4f, y[i] * 2.4f, 0.3f + z[i])
            val near = (1f - z[i]).coerceIn(0f, 1f)
            val r = (1.5f + near * 4.5f) * s.density
            val col = if (seed[i] > 0.6f) accent else lerpColor(accent, Color(0xFFFFC56B), 0.6f)
            val flicker = 0.6f + 0.4f * sin((seed[i] * 40f + y[i] * 30f).toDouble()).toFloat()
            s.drawCircle(col.copy(alpha = 0.18f * flicker * strength), r * 3f, p)
            s.drawCircle(col.copy(alpha = (0.35f + near * 0.5f) * flicker * strength), r, p)
        }
    }

    fun drawGrid(s: DrawScope, accent: Color, strength: Float, offset: Float) {
        val w = s.size.width
        val h = s.size.height
        val horizon = h * 0.42f
        // Receding floor lines.
        for (k in 0 until 14) {
            val d = (k + 1f - offset) / 14f // 0 near .. 1 far
            val yLine = horizon + (h - horizon) * (1f - d) * (1f - d)
            s.drawLine(accent.copy(alpha = (0.05f + (1f - d) * 0.3f) * strength), Offset(0f, yLine), Offset(w, yLine), 1.2f * s.density)
        }
        for (k in -8..8) {
            val bx = w / 2f + k * w / 8f * 2.2f
            s.drawLine(accent.copy(alpha = 0.16f * strength), Offset(w / 2f + k * w / 60f, horizon), Offset(bx, h), 1.2f * s.density)
        }
        // Light motes flying above the floor.
        for (i in x.indices) {
            val p = project(s, x[i], -kotlin.math.abs(y[i]) * 0.9f, z[i])
            val near = (1f - z[i]).coerceIn(0f, 1f)
            s.drawCircle(accent.copy(alpha = (0.2f + near * 0.6f) * strength), (1f + near * 3f) * s.density, p)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
)

/** Brand decal drawn in the card background: race stripes, pinstripes with wings, or circuit chevrons. */
@Composable
fun BrandDecal(modifier: Modifier, alpha: Float = 1f) {
    val style = LocalHudStyle.current.gauge
    val c = LocalHud.current
    val shimmer by rememberInfiniteTransition(label = "decal").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart), label = "shimmer",
    )
    Canvas(modifier) {
        val a = alpha * if (c.dark) 1f else 0.9f
        when (style) {
            GaugeStyle.RACE -> {
                // Three slanted race stripes from the right edge, with a sweeping highlight.
                val w = size.width
                val h = size.height
                val stripe = h * 0.16f
                val all = Path()
                for (i in 0 until 3) {
                    val x0 = w * (0.52f + i * 0.11f)
                    val path = Path().apply {
                        moveTo(x0, h); lineTo(x0 + stripe, h); lineTo(x0 + stripe + h * 0.55f, 0f); lineTo(x0 + h * 0.55f, 0f); close()
                    }
                    all.addPath(path)
                    drawPath(path, c.accent.copy(alpha = (0.55f - i * 0.15f) * a))
                }
                // A highlight sweeps across the stripes.
                val sx = w * (shimmer * 1.6f - 0.3f)
                clipPath(all) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f * a), Color.Transparent), sx - 80f, sx + 80f))
                }
                // Checker strip along the bottom.
                val sq = 7.dp.toPx()
                var cx = 0f
                var col = 0
                while (cx < w * 0.45f) {
                    for (r in 0 until 2) {
                        if ((col + r) % 2 == 0) drawRect(c.text.copy(alpha = 0.22f * a), Offset(cx, h - sq * (2 - r)), Size(sq, sq))
                    }
                    cx += sq; col++
                }
            }
            GaugeStyle.CLASSIC -> {
                // Double pinstripe and a winged roundel, like a tank badge.
                val y1 = size.height - 10.dp.toPx()
                drawLine(c.accent.copy(alpha = 0.7f * a), Offset(0f, y1), Offset(size.width, y1), 2.dp.toPx())
                drawLine(c.accent.copy(alpha = 0.4f * a), Offset(0f, y1 - 5.dp.toPx()), Offset(size.width, y1 - 5.dp.toPx()), 1.dp.toPx())
                val center = Offset(size.width * 0.82f, size.height * 0.42f)
                val r = size.height * 0.2f
                drawCircle(c.accent.copy(alpha = 0.55f * a), r, center, style = Stroke(2.5.dp.toPx()))
                drawCircle(c.accent.copy(alpha = 0.25f * a), r * 0.72f, center, style = Stroke(1.dp.toPx()))
                for (side in listOf(-1f, 1f)) for (f in 0 until 4) {
                    val y = center.y - r * 0.35f + f * r * 0.24f
                    val len = r * (1.9f - f * 0.32f)
                    drawLine(
                        c.accent.copy(alpha = (0.55f - f * 0.1f) * a),
                        Offset(center.x + side * r * 1.08f, y), Offset(center.x + side * (r * 1.08f + len), y - r * 0.18f),
                        2.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
            GaugeStyle.MINIMAL -> {
                // Circuit traces ending in nodes, plus chevrons that pulse forward.
                val w = size.width
                val h = size.height
                val stroke = 1.6.dp.toPx()
                val col = c.accent.copy(alpha = 0.45f * a)
                for (k in 0 until 3) {
                    val y = h * (0.25f + k * 0.2f)
                    val x1 = w * (0.62f + k * 0.05f)
                    drawLine(col, Offset(w, y), Offset(x1, y), stroke)
                    drawLine(col, Offset(x1, y), Offset(x1 - h * 0.12f, y + h * 0.12f), stroke)
                    drawCircle(col, 3.dp.toPx(), Offset(x1 - h * 0.12f, y + h * 0.12f))
                }
                for (k in 0 until 3) {
                    val phase = ((shimmer * 3f - k) % 3f + 3f) % 3f
                    val ca = (1f - phase / 3f)
                    val x = w * 0.08f + k * 14.dp.toPx()
                    val y = h - 18.dp.toPx()
                    drawLine(c.accent.copy(alpha = ca * a), Offset(x, y - 8.dp.toPx()), Offset(x + 8.dp.toPx(), y), 3.dp.toPx(), StrokeCap.Round)
                    drawLine(c.accent.copy(alpha = ca * a), Offset(x + 8.dp.toPx(), y), Offset(x, y + 8.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    }
}

/** The brand name set as a wordmark in the brand's personality (not the official logo artwork). */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, big: Boolean = true) {
    val s = LocalHudStyle.current
    val c = LocalHud.current
    val fontSize = if (big) 34.sp else 18.sp
    val text = s.brandName.uppercase()
    val style = when (s.gauge) {
        GaugeStyle.RACE -> TextStyle(
            fontSize = fontSize, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = 1.sp,
            textGeometricTransform = TextGeometricTransform(scaleX = 1.08f, skewX = -0.18f),
        )
        GaugeStyle.CLASSIC -> TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, fontFamily = s.numeralFont, letterSpacing = 3.sp)
        GaugeStyle.MINIMAL -> TextStyle(fontSize = fontSize, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
    }
    Column(modifier) {
        Box {
            // Offset shadow in the accent gives the wordmark a decal feel.
            if (s.gauge == GaugeStyle.RACE) Text(text, color = c.accent, style = style, modifier = Modifier.offset(3.dp, 2.dp))
            Text(text, color = if (s.gauge == GaugeStyle.RACE) c.text else c.accent, style = style)
        }
        Canvas(Modifier.offset(y = (-2).dp).size(if (big) 160.dp else 90.dp, 8.dp)) {
            when (s.gauge) {
                GaugeStyle.RACE -> rotate(-12f) { drawRect(c.accent, size = Size(size.width * 0.6f, 4.dp.toPx())) }
                GaugeStyle.CLASSIC -> drawLine(c.accent, Offset(0f, 2.dp.toPx()), Offset(size.width * 0.5f, 2.dp.toPx()), 1.5.dp.toPx())
                GaugeStyle.MINIMAL -> drawCircle(c.accent, 3.dp.toPx(), Offset(3.dp.toPx(), 3.dp.toPx()))
            }
        }
    }
}

/** Slow breathing glow ring for the START button. */
@Composable
fun PulseRing(modifier: Modifier, color: Color) {
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t",
    )
    Canvas(modifier) {
        val r = size.minDimension / 2f
        for (k in 0 until 2) {
            val p = (t + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = (1f - p) * 0.45f), r * (0.72f + p * 0.28f), style = Stroke((3f - p * 2f) * density * 1.5f))
        }
        // Rotating tick marks like a rev counter.
        rotate(t * 360f) {
            for (i in 0 until 24) {
                val a = (i / 24f) * 2f * PI.toFloat()
                val o = Offset(center.x + cos(a) * r * 0.98f, center.y + sin(a) * r * 0.98f)
                val inner = Offset(center.x + cos(a) * r * 0.9f, center.y + sin(a) * r * 0.9f)
                drawLine(color.copy(alpha = if (i % 6 == 0) 0.9f else 0.3f), inner, o, 2f * density)
            }
        }
    }
}
