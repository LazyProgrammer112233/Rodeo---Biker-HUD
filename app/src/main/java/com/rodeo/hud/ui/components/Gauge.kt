package com.rodeo.hud.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.GaugeStyle
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalHudStyle
import kotlin.math.cos
import kotlin.math.sin

/** Speed readout drawn in the brand's gauge personality. */
@Composable
fun SpeedGauge(speedKmh: Float, modifier: Modifier = Modifier, maxKmh: Float = 160f, height: Dp = 120.dp) {
    val style = LocalHudStyle.current
    val c = LocalHud.current
    val anim by animateFloatAsState(speedKmh.coerceIn(0f, maxKmh), tween(600), label = "speed")
    val frac = anim / maxKmh
    when (style.gauge) {
        GaugeStyle.RACE -> Column(modifier) {
            // Segmented rev bar, last segments in the accent.
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val n = 24
                val gap = 3.dp.toPx()
                val w = (size.width - gap * (n - 1)) / n
                for (i in 0 until n) {
                    val on = i < (frac * n).toInt().coerceAtLeast(if (anim > 1) 1 else 0)
                    val color = when {
                        !on -> c.textMuted.copy(alpha = 0.18f)
                        i >= n - 5 -> c.danger
                        else -> c.accent
                    }
                    drawRoundRect(color, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${anim.toInt()}", color = c.text, fontSize = 84.sp, fontWeight = style.numeralWeight, fontFamily = style.numeralFont, lineHeight = 84.sp)
                Spacer(Modifier.size(8.dp))
                Text("km/h", color = c.textMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 16.dp))
            }
        }

        GaugeStyle.CLASSIC -> Box(modifier.height(height), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxWidth().height(height)) {
                val r = minOf(size.width / 2f, size.height) - 8.dp.toPx()
                val center = Offset(size.width / 2f, size.height - 4.dp.toPx())
                val tl = Offset(center.x - r, center.y - r)
                drawArc(c.textMuted.copy(alpha = 0.25f), 180f, 180f, false, tl, Size(r * 2, r * 2), style = Stroke(3.dp.toPx()))
                drawArc(c.accent, 180f, 180f * frac, false, tl, Size(r * 2, r * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                for (i in 0..8) {
                    val a = Math.toRadians((180.0 + i * 22.5))
                    val o = Offset(center.x + (r - 2.dp.toPx()) * cos(a).toFloat(), center.y + (r - 2.dp.toPx()) * sin(a).toFloat())
                    val inr = Offset(center.x + (r - 12.dp.toPx()) * cos(a).toFloat(), center.y + (r - 12.dp.toPx()) * sin(a).toFloat())
                    drawLine(c.textMuted, inr, o, 2.dp.toPx())
                }
                val na = Math.toRadians(180.0 + 180.0 * frac)
                drawLine(c.accent, center, Offset(center.x + (r - 18.dp.toPx()) * cos(na).toFloat(), center.y + (r - 18.dp.toPx()) * sin(na).toFloat()), 3.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(c.text, 5.dp.toPx(), center)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 18.dp)) {
                Text("${anim.toInt()}", color = c.text, fontSize = 52.sp, fontWeight = style.numeralWeight, fontFamily = style.numeralFont, lineHeight = 52.sp)
                Text("km/h", color = c.textMuted, fontSize = 13.sp)
            }
        }

        GaugeStyle.MINIMAL -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(height), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(height)) {
                    val sw = 6.dp.toPx()
                    drawArc(c.textMuted.copy(alpha = 0.2f), 135f, 270f, false, Offset(sw, sw), Size(size.width - sw * 2, size.height - sw * 2), style = Stroke(sw, cap = StrokeCap.Round))
                    drawArc(c.accent, 135f, 270f * frac, false, Offset(sw, sw), Size(size.width - sw * 2, size.height - sw * 2), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${anim.toInt()}", color = c.text, fontSize = 46.sp, fontWeight = FontWeight.Light, lineHeight = 46.sp)
                    Text("km/h", color = c.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
}
