package com.rodeo.hud.ui.rides

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.Geo
import com.rodeo.hud.data.Ride
import com.rodeo.hud.graph
import com.rodeo.hud.nav.Place
import com.rodeo.hud.ride.formatDuration
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RidesScreen(onBack: () -> Unit, onNavigated: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val rides by g.rides.rides.collectAsState()
    var selected by remember { mutableStateOf<Ride?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(rides) {
        if (selected == null && rides.isNotEmpty()) selected = g.rides.load(rides.first().id)
    }

    Row(Modifier.fillMaxSize().background(c.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(340.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                }
                Text("Rides", color = c.text, style = LocalType.current.headlineXl)
                Spacer(Modifier.weight(1f))
                Text("%.0f km total".format(g.rides.totalKm()), color = c.textMuted, style = LocalType.current.body)
            }
            Spacer(Modifier.height(8.dp))
            if (rides.isEmpty()) Text("Your rides will show up here after you tap Start Ride.", color = c.textMuted)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rides, key = { it.id }) { r ->
                    val sel = selected?.id == r.id
                    GlassCard(
                        Modifier.fillMaxWidth().clickable { scope.launch { selected = g.rides.load(r.id) } },
                        accentBorder = if (sel) c.accent else null,
                    ) {
                        Text(SimpleDateFormat("EEE d MMM · h:mm a", Locale.getDefault()).format(Date(r.startTime)), color = c.text, fontWeight = FontWeight.Medium)
                        Text("%.1f km · %s · avg %d km/h".format(r.distanceM / 1000, formatDuration(r.durationMs), r.avgSpeedKmh.toInt()), color = c.textMuted, style = LocalType.current.body)
                    }
                }
            }
        }
        val r = selected
        if (r != null) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                RouteTrace(r, Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Metric("Distance", "%.1f km".format(r.distanceM / 1000))
                    Metric("Time", formatDuration(r.durationMs))
                    Metric("Avg", "${r.avgSpeedKmh.toInt()} km/h")
                    Metric("Top", "${r.maxSpeedKmh.toInt()} km/h")
                    Metric("High vibe", "${r.highVibrationSec} s")
                    Metric("Bumps", "${r.hazards}")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (r.parkLat != null && r.parkLng != null) {
                        PrimaryButton("Find my bike", icon = Icons.AutoMirrored.Filled.DirectionsWalk) {
                            g.nav.navigateTo(Place("Parked bike", "", r.parkLat, r.parkLng), walking = true)
                            onNavigated()
                        }
                    }
                    GhostButton("Share", icon = Icons.Filled.Share) { share(ctx, r) }
                    GhostButton("Delete", icon = Icons.Filled.Delete) {
                        scope.launch { g.rides.delete(r.id); selected = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    val c = LocalHud.current
    Column {
        SectionLabel(label)
        Text(value, color = c.text, style = LocalType.current.headlineMd)
    }
}

/** Ride trace drawn from GPS points (works offline, no map key needed). S = engine start, P = parked. */
@Composable
fun RouteTrace(ride: Ride, modifier: Modifier) {
    val c = LocalHud.current
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(c.surface)) {
        val pts = ride.points
        if (pts.size < 2) {
            Text("Not enough GPS points to draw this ride", color = c.textMuted, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 44.dp)) {
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
            val minLng = pts.minOf { it.lng }; val maxLng = pts.maxOf { it.lng }
            val midLat = Math.toRadians((minLat + maxLat) / 2)
            val w = ((maxLng - minLng) * kotlin.math.cos(midLat)).coerceAtLeast(1e-6)
            val h = (maxLat - minLat).coerceAtLeast(1e-6)
            val s = minOf(size.width / w, size.height / h)
            val ox = (size.width - w * s) / 2
            val oy = (size.height - h * s) / 2
            fun p(lat: Double, lng: Double) = Offset(
                (ox + (lng - minLng) * kotlin.math.cos(midLat) * s).toFloat(),
                (oy + (maxLat - lat) * s).toFloat(),
            )
            val path = Path()
            pts.forEachIndexed { i, t -> val o = p(t.lat, t.lng); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, c.accent.copy(alpha = 0.25f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, c.accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val start = p(pts.first().lat, pts.first().lng)
            drawCircle(c.ok, 8.dp.toPx(), start)
            val endLat = ride.parkLat ?: pts.last().lat
            val endLng = ride.parkLng ?: pts.last().lng
            val end = p(endLat, endLng)
            drawCircle(c.text, 9.dp.toPx(), end)
            drawCircle(c.surface, 5.dp.toPx(), end)
        }
        Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(c.ok, "Engine start")
            Legend(c.text, "Parked")
        }
    }
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = LocalHud.current.textMuted, style = LocalType.current.bodySm)
    }
}

private fun share(ctx: Context, r: Ride) {
    val text = "Rode %.1f km in %s with Rodeo · avg %d km/h, top %d km/h".format(
        r.distanceM / 1000, formatDuration(r.durationMs), r.avgSpeedKmh.toInt(), r.maxSpeedKmh.toInt(),
    )
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, "Share ride").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
