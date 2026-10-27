package com.rodeo.hud.nav

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rodeo.hud.data.Geo
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rodeo.hud.data.Hazard
import com.rodeo.hud.graph
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.ui.theme.LocalHud
import kotlinx.coroutines.launch

/**
 * The map half of the HUD: OpenStreetMap inside Rodeo (no API key), heading-up while riding.
 * Turn-by-turn voice guidance hands off to the Google Maps app in two-wheeler mode.
 */
@Composable
fun MapPane(
    modifier: Modifier,
    dark: Boolean,
    lowPower: Boolean,
    lockInput: Boolean,
    members: List<PackMember>,
    hazards: List<Hazard>,
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val nav by g.nav.state.collectAsState()
    val loc by g.location.location.collectAsState()
    val parked = g.rides.rides.collectAsState().value.firstOrNull { it.parkLat != null }
    val profile by g.profile.profile.collectAsState()
    var follow by remember { mutableStateOf(true) }

    Box(modifier) {
        OsmMap(
            Modifier.fillMaxSize(), dark, lowPower, follow, loc, members, hazards,
            parked?.parkLat?.let { lat -> parked.parkLng?.let { lng -> GeoPoint(lat, lng) } },
            nav.destination,
            onUserPan = { follow = false },
        )
        if (follow && loc != null) {
            // Rider arrow sits at the centre of the heading-up map.
            Canvas(Modifier.align(Alignment.Center).size(34.dp)) {
                val w = size.width
                val path = Path().apply {
                    moveTo(w / 2f, 0f); lineTo(w, w); lineTo(w / 2f, w * 0.74f); lineTo(0f, w); close()
                }
                drawPath(path, Color.White, style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
                drawPath(path, c.accent)
            }
        }
        Text(
            "© OpenStreetMap contributors", color = Color(0xFF333333), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (!follow) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(10.dp).size(56.dp).clip16().background(c.accent).clickable { follow = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.MyLocation, "Recenter", tint = c.onAccent) }
        }

        Column(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxWidth()) {
            val dest = nav.destination
            if (dest != null) {
                val km = loc?.let { Geo.distanceM(it.latitude, it.longitude, dest.lat, dest.lng) / 1000.0 }
                GuidanceChip(dest, km, onOpen = { g.nav.openGuidance() }, onStop = { g.nav.stop() })
            } else {
                SearchBox(
                    enabled = !lockInput,
                    nearLat = loc?.latitude, nearLng = loc?.longitude,
                    home = if (profile.homeLat != null) Place(profile.homeLabel.ifBlank { "Home" }, "", profile.homeLat!!, profile.homeLng!!) else null,
                    parked = parked?.let { r -> r.parkLat?.let { lat -> Place("Parked bike", "", lat, r.parkLng ?: 0.0) } },
                    onPick = { p, walk -> g.nav.navigateTo(p, walking = walk) },
                )
            }
        }
    }
}

@Composable
private fun GuidanceChip(dest: Place, km: Double?, onOpen: () -> Unit, onStop: () -> Unit) {
    val c = LocalHud.current
    Row(
        Modifier.clip16().background(c.surface).border(1.5.dp, c.accent, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(dest.title, color = c.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(km?.let { "%.1f km away (straight line)".format(it) } ?: "Destination set", color = c.textMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(48.dp).clip16().background(c.accent).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Navigation, "Voice guidance in Google Maps", tint = c.onAccent)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(48.dp).clip16().background(c.danger.copy(alpha = 0.15f)).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Clear destination", tint = c.danger)
        }
    }
}

@Composable
private fun SearchBox(
    enabled: Boolean,
    nearLat: Double?, nearLng: Double?,
    home: Place?, parked: Place?,
    onPick: (Place, Boolean) -> Unit,
) {
    val c = LocalHud.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<Place>() }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).heightIn(min = 52.dp).clip16().background(c.glass).border(1.dp, c.glassBorder, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = c.textMuted)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(if (enabled) "Where to?" else "Stop to search · or say \"navigate home\"", color = c.textMuted, fontSize = 15.sp)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        enabled = enabled,
                        singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            scope.launch {
                                results.clear()
                                results.addAll(NavController.search(ctx, query, nearLat, nearLng))
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (home != null) QuickChip(Icons.Filled.Home, "Home") { onPick(home, false) }
            if (parked != null) QuickChip(Icons.Filled.LocalParking, "Bike") { onPick(parked, true) }
        }
        if (results.isNotEmpty()) {
            LazyColumn(Modifier.padding(top = 6.dp).heightIn(max = 220.dp).clip16().background(c.surface)) {
                items(results) { p ->
                    Column(Modifier.fillMaxWidth().clickable { results.clear(); query = ""; onPick(p, false) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(p.title, color = c.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(p.subtitle, color = c.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalHud.current
    Box(
        Modifier.size(52.dp).clip16().background(c.glass).border(1.dp, c.glassBorder, RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = c.text) }
}

private fun Modifier.clip16() = this.clip(RoundedCornerShape(16.dp))

/** Dark map: invert the light OSM style and pull the saturation down so it reads as night mode. */
private val NIGHT = ColorMatrixColorFilter(
    ColorMatrix().apply {
        set(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
        postConcat(ColorMatrix().apply { setSaturation(0.35f) })
        postConcat(ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, -12f, 0f, 0.9f, 0f, 0f, -12f, 0f, 0f, 0.95f, 0f, -6f, 0f, 0f, 0f, 1f, 0f)))
    },
)

/** Daylight map: a touch more contrast and colour so roads stand out in sun glare. */
private val DAY = ColorMatrixColorFilter(
    ColorMatrix().apply {
        setSaturation(1.25f)
        val k = 1.18f
        val t = -(k - 1f) * 128f
        postConcat(ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, t, 0f, k, 0f, 0f, t, 0f, 0f, k, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    },
)

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun OsmMap(
    modifier: Modifier,
    dark: Boolean,
    lowPower: Boolean,
    follow: Boolean,
    loc: android.location.Location?,
    members: List<PackMember>,
    hazards: List<Hazard>,
    parked: GeoPoint?,
    destination: Place?,
    onUserPan: () -> Unit,
) {
    val ctx = LocalContext.current
    val c = LocalHud.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pan by rememberUpdatedState(onUserPan)
    val view = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0
            maxZoomLevel = 19.0
            controller.setZoom(16.5)
            controller.setCenter(GeoPoint(20.59, 78.96))
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_MOVE) pan()
                false
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            view.onPause()
            view.onDetach()
        }
    }

    LaunchedEffect(dark) {
        view.overlayManager.tilesOverlay.setColorFilter(if (dark) NIGHT else DAY)
        view.overlayManager.tilesOverlay.setLoadingBackgroundColor(if (dark) 0xFF111214.toInt() else 0xFFE9EAEC.toInt())
        view.invalidate()
    }

    // Follow the rider; turn the map heading-up once moving so the road ahead points up.
    LaunchedEffect(loc, follow) {
        val l = loc ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val p = GeoPoint(l.latitude, l.longitude)
        val moving = l.hasSpeed() && l.speed * 3.6f > 8f && l.hasBearing()
        view.mapOrientation = if (moving) -l.bearing else 0f
        if (view.zoomLevelDouble < 13.0) view.controller.setZoom(16.5)
        if (lowPower) view.controller.setCenter(p) else view.controller.animateTo(p, null, 800L)
    }

    LaunchedEffect(members, hazards, parked, destination, loc?.latitude, loc?.longitude, follow, dark) {
        view.overlays.clear()
        val accent = c.accent.toArgb()
        val warn = c.warning.toArgb()
        hazards.forEach { h ->
            view.overlays += Polygon(view).apply {
                points = Polygon.pointsAsCircle(GeoPoint(h.lat, h.lng), 12.0 + h.hits * 3)
                fillPaint.color = (warn and 0x00FFFFFF) or (0x66 shl 24)
                outlinePaint.color = warn
                outlinePaint.strokeWidth = 3f
                setOnClickListener { _, _, _ -> true }
            }
        }
        val here = loc?.let { GeoPoint(it.latitude, it.longitude) }
        if (destination != null) {
            val d = GeoPoint(destination.lat, destination.lng)
            if (here != null) {
                view.overlays += Polyline(view).apply {
                    setPoints(listOf(here, d))
                    outlinePaint.color = accent
                    outlinePaint.strokeWidth = 8f
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(24f, 18f), 0f)
                    setOnClickListener { _, _, _ -> true }
                }
            }
            view.overlays += pin(view, d, destination.title, dot(ctx, accent, ring = true))
        }
        parked?.let { view.overlays += pin(view, it, "Parked bike", dot(ctx, 0xFF2D7FF9.toInt(), letter = "P")) }
        members.filter { !it.isMe }.forEach { r ->
            view.overlays += pin(view, GeoPoint(r.lat, r.lng), r.name, dot(ctx, if (r.isLead) accent else 0xFF19A7CE.toInt(), letter = r.name.take(1).uppercase()))
        }
        // When the rider pans away, show their position as a dot instead of the centre arrow.
        if (!follow && here != null) view.overlays += pin(view, here, "You", dot(ctx, accent, ring = true))
        view.invalidate()
    }

    AndroidView(factory = { view }, modifier = modifier)
}

private fun pin(map: MapView, at: GeoPoint, title: String, icon: android.graphics.drawable.Drawable) = Marker(map).apply {
    position = at
    this.title = title
    this.icon = icon
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    setOnMarkerClickListener { _, _ -> true }
}

/** Round map marker drawn in code: brand colour, white rim for contrast on any map. */
private fun dot(ctx: android.content.Context, color: Int, ring: Boolean = false, letter: String? = null): android.graphics.drawable.Drawable {
    val d = ctx.resources.displayMetrics.density
    val size = (if (letter != null) 30 else 22) * d
    val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = size / 2f
    p.color = android.graphics.Color.WHITE
    cv.drawCircle(r, r, r, p)
    p.color = color
    cv.drawCircle(r, r, r - 3 * d, p)
    if (ring) {
        p.color = android.graphics.Color.WHITE
        cv.drawCircle(r, r, r * 0.3f, p)
    }
    if (letter != null) {
        p.color = android.graphics.Color.WHITE
        p.textSize = 14 * d
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textAlign = android.graphics.Paint.Align.CENTER
        cv.drawText(letter, r, r - (p.descent() + p.ascent()) / 2f, p)
    }
    return BitmapDrawable(ctx.resources, bmp)
}
