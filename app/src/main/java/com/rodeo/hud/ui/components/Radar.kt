package com.rodeo.hud.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.rodeo.hud.data.Geo
import com.rodeo.hud.data.Hazard
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.ui.theme.LocalHud
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Heading-up radar centred on the rider: pack members, logged hazards and the parked bike.
 * Used as the map half when no Maps key is configured, and as the pack overview.
 */
@Composable
fun Radar(
    modifier: Modifier,
    myLat: Double?,
    myLng: Double?,
    headingDeg: Float,
    members: List<PackMember>,
    hazards: List<Hazard>,
    parkedLat: Double? = null,
    parkedLng: Double? = null,
    rangeM: Double = 1500.0,
) {
    val c = LocalHud.current
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height * 0.58f)
        val radiusPx = min(size.width, size.height) * 0.46f
        val scale = radiusPx / rangeM.toFloat()
        val ring = c.textMuted.copy(alpha = 0.22f)
        val text = Paint().apply {
            isAntiAlias = true
            textSize = 11.dp.toPx()
            color = c.textMuted.toArgb()
            typeface = Typeface.DEFAULT
        }

        listOf(250.0, 750.0, 1500.0).forEach { m ->
            val r = (m * scale).toFloat()
            drawCircle(ring, r, center, style = Stroke(1.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(if (m >= 1000) "${m / 1000} km" else "${m.toInt()} m", center.x + 4.dp.toPx(), center.y - r - 4.dp.toPx(), text)
        }
        // Heading cone.
        val cone = Path().apply {
            moveTo(center.x, center.y)
            lineTo(center.x - radiusPx * 0.35f, center.y - radiusPx)
            lineTo(center.x + radiusPx * 0.35f, center.y - radiusPx)
            close()
        }
        drawPath(cone, c.accent.copy(alpha = 0.06f))

        fun project(lat: Double, lng: Double): Offset? {
            if (myLat == null || myLng == null) return null
            val d = Geo.distanceM(myLat, myLng, lat, lng)
            val b = Math.toRadians(Geo.bearing(myLat, myLng, lat, lng) - headingDeg)
            val r = (min(d, rangeM * 1.05) * scale).toFloat()
            return Offset(center.x + r * sin(b).toFloat(), center.y - r * cos(b).toFloat())
        }

        hazards.forEach { h ->
            val p = project(h.lat, h.lng) ?: return@forEach
            val s = (5 + min(h.hits, 4) * 1.5f).dp.toPx()
            val tri = Path().apply {
                moveTo(p.x, p.y - s); lineTo(p.x - s, p.y + s * 0.8f); lineTo(p.x + s, p.y + s * 0.8f); close()
            }
            drawPath(tri, c.warning.copy(alpha = 0.85f))
        }

        if (parkedLat != null && parkedLng != null) {
            project(parkedLat, parkedLng)?.let { p ->
                drawCircle(c.text, 7.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText("P", p.x - 3.5f.dp.toPx(), p.y + 4.dp.toPx(), text.apply { color = c.text.toArgb() })
            }
        }

        val label = Paint(text).apply { color = c.text.toArgb(); textSize = 12.dp.toPx() }
        members.filter { !it.isMe }.forEach { m ->
            val p = project(m.lat, m.lng) ?: return@forEach
            val stopped = m.stillSince != 0L && System.currentTimeMillis() - m.stillSince > 120_000
            val fill = when {
                stopped -> c.warning
                m.isLead -> c.accent
                else -> c.text.copy(alpha = 0.85f)
            }
            drawCircle(fill, 8.dp.toPx(), p)
            drawCircle(c.background, 8.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            drawContext.canvas.nativeCanvas.drawText(m.name, p.x + 11.dp.toPx(), p.y + 4.dp.toPx(), label)
        }

        // The rider: an arrow pointing up (heading-up).
        val me = Path().apply {
            moveTo(center.x, center.y - 14.dp.toPx())
            lineTo(center.x - 10.dp.toPx(), center.y + 10.dp.toPx())
            lineTo(center.x, center.y + 4.dp.toPx())
            lineTo(center.x + 10.dp.toPx(), center.y + 10.dp.toPx())
            close()
        }
        drawPath(me, c.accent)
        drawPath(me, Color.White.copy(alpha = 0.6f), style = Stroke(1.dp.toPx()))
    }
}
