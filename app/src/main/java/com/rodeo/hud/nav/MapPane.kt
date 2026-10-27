package com.rodeo.hud.nav

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rodeo.hud.data.Geo
import com.rodeo.hud.data.Hazard
import com.rodeo.hud.graph
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.ui.hud.MapChrome
import com.rodeo.hud.ui.hud.MapChromeActions
import com.rodeo.hud.ui.hud.MapChromeState
import com.rodeo.hud.ui.theme.LocalHud
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * The map half of the HUD: OpenStreetMap inside Rodeo (no API key), heading-up while riding,
 * with the search, quick places, turn card and map buttons drawn over it by [MapChrome].
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
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val results = remember { mutableStateListOf<Place>() }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun kmTo(lat: Double, lng: Double): String? = loc?.let { "%.1f km".format(Geo.distanceM(it.latitude, it.longitude, lat, lng) / 1000.0) }

    val home = if (profile.homeLat != null && profile.homeLng != null) Place(profile.homeLabel.ifBlank { "Home" }, "", profile.homeLat!!, profile.homeLng!!) else null
    val parkedPlace = parked?.let { r -> r.parkLat?.let { lat -> r.parkLng?.let { lng -> Place("Parked bike", "", lat, lng) } } }
    val dest = nav.destination
    val chrome = MapChromeState(
        following = follow,
        lockInput = lockInput,
        guidance = dest?.let { d ->
            GuidanceUi(
                maneuver = Maneuver.DEPART,
                distance = kmTo(d.lat, d.lng) ?: "--",
                instruction = "Head to ${d.title}",
                destination = d.title,
                status = "straight line",
            )
        },
        home = home, homeSub = home?.let { kmTo(it.lat, it.lng) },
        parked = parkedPlace, parkedSub = parkedPlace?.let { kmTo(it.lat, it.lng) },
        results = results.toList(),
        searching = searching,
        bearingDeg = loc?.takeIf { it.hasBearing() }?.bearing,
        speedKmh = loc?.takeIf { it.hasSpeed() }?.let { it.speed * 3.6f } ?: 0f,
    )
    val actions = remember {
        object : MapChromeActions {
            override fun search(query: String) {
                if (query.isBlank()) return
                searching = true
                results.clear()
                scope.launch {
                    val l = g.location.location.value
                    results.addAll(NavController.search(ctx, query, l?.latitude, l?.longitude))
                    searching = false
                }
            }
            override fun clearResults() { results.clear(); searching = false }
            override fun pick(place: Place, walking: Boolean) {
                results.clear()
                follow = true
                g.nav.navigateTo(place, walking)
            }
            override fun stopGuidance() = g.nav.stop()
            override fun toggleGuidanceVoice() { g.nav.openGuidance() }
            override fun recenter() { follow = true }
            override fun zoomIn() { mapView?.controller?.zoomIn() }
            override fun zoomOut() { mapView?.controller?.zoomOut() }
            override fun voiceSearch() { g.nav.openGuidance() }
        }
    }

    Box(modifier) {
        OsmMap(
            Modifier.fillMaxSize(), dark, lowPower, follow, loc, members, hazards,
            parkedPlace?.let { GeoPoint(it.lat, it.lng) },
            dest,
            onUserPan = { follow = false },
            onView = { mapView = it },
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
        MapChrome(chrome, actions, Modifier.fillMaxSize())
    }
}

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
    onView: (MapView) -> Unit,
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
    LaunchedEffect(view) { onView(view) }

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
