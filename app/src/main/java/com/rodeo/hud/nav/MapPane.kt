package com.rodeo.hud.nav

import android.os.Bundle
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.libraries.navigation.NavigationView
import com.rodeo.hud.data.Hazard
import com.rodeo.hud.graph
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.ui.components.Radar
import com.rodeo.hud.ui.theme.LocalHud
import kotlinx.coroutines.launch

/** The map half of the HUD: embedded Google navigation, or the radar when no Maps key is set. */
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
    val nav by g.nav.state.collectAsState()
    val loc by g.location.location.collectAsState()
    val parked = g.rides.rides.collectAsState().value.firstOrNull { it.parkLat != null }
    val profile by g.profile.profile.collectAsState()

    Box(modifier) {
        if (g.nav.enabled && nav.ready) {
            NavMap(Modifier.fillMaxSize(), dark, lowPower, members, hazards)
        } else {
            Radar(
                Modifier.fillMaxSize(), loc?.latitude, loc?.longitude, loc?.bearing ?: 0f,
                members, hazards, parked?.parkLat, parked?.parkLng,
            )
            val msg = when {
                !g.nav.enabled -> "Radar view · add a Google Maps key to enable turn-by-turn"
                nav.error != null -> nav.error!!
                else -> "Starting navigation…"
            }
            Text(msg, color = LocalHud.current.textMuted, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp))
        }

        Column(Modifier.align(Alignment.TopStart).padding(10.dp).fillMaxWidth()) {
            if (nav.guiding) {
                GuidanceChip(nav) { g.nav.stop() }
            } else if (g.nav.enabled && nav.ready) {
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
private fun GuidanceChip(nav: NavState, onStop: () -> Unit) {
    val c = LocalHud.current
    Row(
        Modifier.clip16().background(c.glass).border(1.dp, c.glassBorder, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(nav.destination?.title ?: "Navigating", color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val km = nav.remainingMeters?.let { "%.1f km".format(it / 1000f) } ?: ""
            val min = nav.remainingSeconds?.let { " · ${it / 60} min" } ?: ""
            Text("$km$min", color = c.textMuted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(44.dp).clip16().background(c.danger.copy(alpha = 0.15f)).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Stop navigation", tint = c.danger)
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

/** NavigationView hosted in Compose, with pack members and hazards drawn on the map. */
@Composable
private fun NavMap(modifier: Modifier, dark: Boolean, lowPower: Boolean, members: List<PackMember>, hazards: List<Hazard>) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val c = LocalHud.current
    var map by remember { mutableStateOf<GoogleMap?>(null) }
    val markers = remember { mutableMapOf<String, Marker>() }
    val circles = remember { mutableMapOf<String, Circle>() }
    val ctx = LocalContext.current
    val view = remember {
        NavigationView(ctx).apply {
            onCreate(Bundle())
            getMapAsync { m ->
                m.isTrafficEnabled = true
                map = m
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            view.onPause(); view.onStop(); view.onDestroy()
        }
    }

    LaunchedEffect(dark) {
        view.setForceNightMode(
            if (dark) com.google.android.libraries.navigation.ForceNightMode.FORCE_NIGHT
            else com.google.android.libraries.navigation.ForceNightMode.FORCE_DAY,
        )
    }
    LaunchedEffect(lowPower, map) {
        // Low-power HUD: flat map and no traffic layer to cut GPU work and heat.
        map?.isBuildingsEnabled = !lowPower
        map?.isTrafficEnabled = !lowPower
    }

    LaunchedEffect(members, map) {
        val m = map ?: return@LaunchedEffect
        val ids = members.filter { !it.isMe }.map { it.id }.toSet()
        markers.keys.filter { it !in ids }.forEach { markers.remove(it)?.remove() }
        members.filter { !it.isMe }.forEach { r ->
            val pos = LatLng(r.lat, r.lng)
            val existing = markers[r.id]
            if (existing != null) existing.position = pos
            else markers[r.id] = m.addMarker(
                MarkerOptions().position(pos).title(r.name)
                    .icon(BitmapDescriptorFactory.defaultMarker(if (r.isLead) BitmapDescriptorFactory.HUE_ORANGE else BitmapDescriptorFactory.HUE_AZURE)),
            ) ?: return@forEach
        }
    }

    LaunchedEffect(hazards, map) {
        val m = map ?: return@LaunchedEffect
        val ids = hazards.map { it.id }.toSet()
        circles.keys.filter { it !in ids }.forEach { circles.remove(it)?.remove() }
        hazards.filter { it.id !in circles }.forEach { h ->
            circles[h.id] = m.addCircle(
                CircleOptions().center(LatLng(h.lat, h.lng)).radius(12.0 + h.hits * 3)
                    .fillColor(c.warning.copy(alpha = 0.35f).toArgb()).strokeColor(c.warning.toArgb()).strokeWidth(2f),
            )
        }
    }

    AndroidView(factory = { view }, modifier = modifier)
}
