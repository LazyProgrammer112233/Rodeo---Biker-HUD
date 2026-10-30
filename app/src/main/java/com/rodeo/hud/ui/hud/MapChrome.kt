package com.rodeo.hud.ui.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rodeo.hud.nav.GuidanceUi
import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.Place
import com.rodeo.hud.ui.components.FitText
import com.rodeo.hud.ui.components.buttonShape
import com.rodeo.hud.ui.components.panelShape
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.sdp
import kotlin.math.cos
import kotlin.math.sin

/** Everything drawn on top of the map, as plain values. */
data class MapChromeState(
    val following: Boolean = true,
    val lockInput: Boolean = false,
    val guidance: GuidanceUi? = null,
    val home: Place? = null,
    val homeSub: String? = null,
    val parked: Place? = null,
    val parkedSub: String? = null,
    val results: List<Place> = emptyList(),
    /** Distance to each result, in the same order. */
    val resultKm: List<String?> = emptyList(),
    val searching: Boolean = false,
    /** Why a search came back empty. */
    val searchNote: String? = null,
    val bearingDeg: Float? = null,
    val speedKmh: Float = 0f,
    val attribution: String = "© OpenStreetMap contributors",
)

interface MapChromeActions {
    fun search(query: String)
    fun clearResults()
    fun pick(place: Place, walking: Boolean)
    fun stopGuidance()
    fun startGuidance() {}
    fun toggleGuidanceVoice()
    fun recenter()
    fun zoomIn()
    fun zoomOut()
    fun voiceSearch()
}

/** Search, quick places, turn card, map buttons, bearing and the OSM credit, laid over the map. */
@Composable
fun MapChrome(s: MapChromeState, actions: MapChromeActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.padding(10.sdp())) {
        val narrow = maxWidth < 330.dp
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.sdp())) {
            val g = s.guidance
            if (g != null) TurnCard(g, actions) else {
                SearchRow(s, actions, narrow)
            }
        }
        Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(8.sdp())) {
            if (!s.following) MapButton(Icons.Filled.MyLocation, "Recentre", accent = true, onClick = actions::recenter)
            if (!s.lockInput) {
                MapButton(Icons.Filled.Add, "Zoom in", onClick = actions::zoomIn)
                MapButton(Icons.Filled.Remove, "Zoom out", onClick = actions::zoomOut)
            }
            if (s.lockInput) LockNote()
        }
        Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.sdp())) {
            s.bearingDeg?.takeIf { s.speedKmh > 3f }?.let { BearingBadge(it) }
            Attribution(s.attribution)
        }
    }
}

@Composable
private fun SearchRow(s: MapChromeState, actions: MapChromeActions, narrow: Boolean) {
    val c = LocalHud.current
    val t = LocalType.current
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.sdp())) {
            val pill = RoundedCornerShape(50)
            Row(
                Modifier.weight(1f).height(52.sdp()).clip(pill).background(chromeBg())
                    .border(c.borderWidth, c.glassBorder, pill).padding(start = 16.sdp(), end = 6.sdp()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = c.accent, modifier = Modifier.size(22.sdp()))
                Spacer(Modifier.width(8.sdp()))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        FitText(if (s.lockInput) "Say \"navigate home\"" else "Where to?", t.headlineMd.copy(fontWeight = FontWeight.Bold), c.textMuted)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it; if (it.isEmpty()) actions.clearResults() },
                        enabled = !s.lockInput,
                        singleLine = true,
                        textStyle = t.headlineMd.copy(color = c.text, fontWeight = FontWeight.Bold),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        // Put the keyboard away so the results (and the map) are visible in landscape.
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus(); actions.search(query) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    Modifier.size(40.sdp()).clip(CircleShape).background(c.surfaceHigh).clickable(onClick = actions::voiceSearch),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Mic, "Speak a destination", tint = c.text, modifier = Modifier.size(20.sdp())) }
            }
            s.home?.let { h -> PlaceChip(Icons.Filled.Home, "Home", s.homeSub, narrow) { actions.pick(h, false) } }
            s.parked?.let { p -> PlaceChip(Icons.Filled.TwoWheeler, "Bike", s.parkedSub, narrow) { actions.pick(p, true) } }
        }
        if (s.results.isNotEmpty() || s.searching || s.searchNote != null) {
            val shape = panelShape()
            LazyColumn(
                Modifier.padding(top = 6.sdp()).fillMaxWidth().heightIn(max = 220.sdp()).clip(shape).background(c.surface)
                    .border(c.borderWidth, c.glassBorder, shape),
            ) {
                if (s.searching && s.results.isEmpty()) item {
                    Text("Searching…", color = c.textMuted, style = t.body, modifier = Modifier.padding(14.sdp()))
                } else if (s.results.isEmpty() && s.searchNote != null) item {
                    Text(s.searchNote, color = c.textMuted, style = t.body, modifier = Modifier.padding(14.sdp()))
                }
                itemsIndexed(s.results) { i, p ->
                    Row(
                        Modifier.fillMaxWidth().testTag("place-result").clickable { query = ""; actions.pick(p, false) }.padding(horizontal = 14.sdp(), vertical = 10.sdp()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Place, null, tint = c.accent, modifier = Modifier.size(20.sdp()))
                        Spacer(Modifier.width(10.sdp()))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, color = c.text, style = t.headlineSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.subtitle.isNotBlank()) Text(p.subtitle, color = c.textMuted, style = t.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        s.resultKm.getOrNull(i)?.let { km ->
                            Spacer(Modifier.width(8.sdp()))
                            Text(km, color = c.accent, style = t.label, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun chromeBg(): Color {
    val c = LocalHud.current
    return if (c.dark) c.surfaceLowest.copy(alpha = 0.92f) else Color.White
}

/** Home / Bike shortcut. On narrow maps it collapses to an icon square. */
@Composable
private fun PlaceChip(icon: ImageVector, title: String, sub: String?, compact: Boolean, onClick: () -> Unit) {
    val c = LocalHud.current
    val t = LocalType.current
    val shape = buttonShape()
    Row(
        Modifier.height(52.sdp()).then(if (compact) Modifier.width(52.sdp()) else Modifier.widthIn(max = 130.sdp()))
            .clip(shape).background(chromeBg()).border(c.borderWidth, c.glassBorder, shape)
            .clickable(onClick = onClick).padding(horizontal = if (compact) 0.dp else 10.sdp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(32.sdp()).clip(RoundedCornerShape(9.sdp())).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, title, tint = c.accent, modifier = Modifier.size(20.sdp()))
        }
        if (!compact) {
            Spacer(Modifier.width(8.sdp()))
            Column(Modifier.weight(1f, fill = false)) {
                FitText(title.uppercase(), t.headlineSm, c.text)
                if (!sub.isNullOrBlank()) FitText(sub.uppercase(), t.micro, c.textMuted)
            }
        }
    }
}

/** Top-of-map turn card: manoeuvre glyph, distance, street, destination and what is left. */
@Composable
fun TurnCard(g: GuidanceUi, actions: MapChromeActions, modifier: Modifier = Modifier) {
    val c = LocalHud.current
    val t = LocalType.current
    val shape = panelShape()
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (c.dark) c.surface.copy(alpha = 0.96f) else Color.White)
            .border(maxOf(c.borderWidth, 1.dp), if (c.dark) c.glassBorder else c.glassBorder, shape)
            .padding(8.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(64.sdp()).clip(RoundedCornerShape(14.sdp())).background(c.accent),
            contentAlignment = Alignment.Center,
        ) { ManeuverGlyph(g.maneuver, c.onAccent, Modifier.size(44.sdp())) }
        Spacer(Modifier.width(10.sdp()))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                FitText(g.distance.uppercase(), t.headlineXl.copy(fontWeight = FontWeight.Black), c.accent, Modifier.weight(1f, fill = false))
                g.status?.let {
                    Spacer(Modifier.width(8.sdp()))
                    FitText(it.uppercase(), t.micro, c.warning, Modifier.weight(1f, fill = false).padding(bottom = 3.sdp()))
                }
            }
            FitText(g.instruction.uppercase(), t.headlineMd.copy(fontWeight = FontWeight.Bold), c.text)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FitText(
                    listOfNotNull(g.destination, g.remaining, g.eta).joinToString(" · ").uppercase(),
                    t.micro, c.textMuted, Modifier.weight(1f, fill = false),
                )
            }
        }
        Spacer(Modifier.width(6.sdp()))
        if (g.preview) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.sdp()), verticalAlignment = Alignment.CenterVertically) {
                val go = RoundedCornerShape(14.sdp())
                Row(
                    Modifier.height(56.sdp()).clip(go).background(c.accent).clickable(onClick = actions::startGuidance)
                        .testTag("start-guidance").padding(horizontal = 16.sdp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Navigation, null, tint = c.onAccent, modifier = Modifier.size(22.sdp()))
                    Spacer(Modifier.width(6.sdp()))
                    Text("START", color = c.onAccent, style = t.headlineSm.copy(fontWeight = FontWeight.Black))
                }
                SmallSquare(Icons.Filled.Close, "Cancel", c.text, actions::stopGuidance)
            }
            return@Row
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.sdp())) {
            SmallSquare(if (g.voiceOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, if (g.voiceOn) "Mute voice" else "Unmute voice", if (g.voiceOn) c.ok else c.textMuted, actions::toggleGuidanceVoice)
            SmallSquare(Icons.Filled.Close, "End navigation", c.text, actions::stopGuidance)
        }
    }
}

@Composable
private fun SmallSquare(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val c = LocalHud.current
    val shape = RoundedCornerShape(10.sdp())
    Box(
        Modifier.size(40.sdp()).clip(shape).background(c.surfaceHigh).border(c.borderWidth, c.glassBorder, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.sdp())) }
}

@Composable
private fun MapButton(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = LocalHud.current
    val shape = buttonShape()
    Box(
        Modifier.size(52.sdp()).clip(shape).background(if (accent) c.accent else chromeBg())
            .border(c.borderWidth, if (accent) Color.Transparent else c.glassBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (accent) c.onAccent else c.text, modifier = Modifier.size(24.sdp())) }
}

@Composable
private fun LockNote() {
    val c = LocalHud.current
    val shape = RoundedCornerShape(10.sdp())
    Row(
        Modifier.clip(shape).background(chromeBg()).border(c.borderWidth, c.glassBorder, shape).padding(horizontal = 10.sdp(), vertical = 6.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, null, tint = c.warning, modifier = Modifier.size(14.sdp()))
        Spacer(Modifier.width(6.sdp()))
        Text("TYPING LOCKED WHILE MOVING", color = c.textMuted, style = LocalType.current.micro, maxLines = 1)
    }
}

@Composable
private fun BearingBadge(deg: Float) {
    val c = LocalHud.current
    val t = LocalType.current
    val shape = RoundedCornerShape(12.sdp())
    Column(
        Modifier.clip(shape).background(chromeBg()).border(c.borderWidth, c.glassBorder, shape).padding(horizontal = 12.sdp(), vertical = 5.sdp()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("BEARING", color = c.textMuted, style = t.micro, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%03d°".format(((deg % 360 + 360) % 360).toInt()), color = c.accent, style = t.headlineMd.copy(fontWeight = FontWeight.Black), maxLines = 1)
            Spacer(Modifier.width(4.sdp()))
            Text(compass(deg), color = c.text, style = t.headlineSm, maxLines = 1)
        }
    }
}

private fun compass(deg: Float): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8]
}

@Composable
private fun Attribution(text: String) {
    Text(
        text, color = Color(0xFF333333), style = LocalType.current.micro.copy(letterSpacing = LocalType.current.bodySm.letterSpacing),
        maxLines = 1,
        modifier = Modifier.background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Turn arrows drawn in code so every manoeuvre has a clear, bold glyph. */
@Composable
fun ManeuverGlyph(m: Maneuver, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val sw = s * 0.13f
        val stroke = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float, mirror: Boolean) = Offset((if (mirror) 1f - x else x) * s, y * s)
        when (m) {
            Maneuver.DEPART, Maneuver.STRAIGHT -> arrow(color, stroke, listOf(p(0.5f, 0.92f, false), p(0.5f, 0.12f, false)), sw)
            Maneuver.ARRIVE -> {
                drawLine(color, p(0.3f, 0.92f, false), p(0.3f, 0.1f, false), sw, StrokeCap.Round)
                val flag = Path().apply {
                    moveTo(0.3f * s, 0.12f * s); lineTo(0.82f * s, 0.12f * s); lineTo(0.68f * s, 0.31f * s)
                    lineTo(0.82f * s, 0.5f * s); lineTo(0.3f * s, 0.5f * s); close()
                }
                drawPath(flag, color)
            }
            Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> {
                // Drawn as a left U-turn (up on the right, back down on the left); mirrored for right.
                val mir = m == Maneuver.UTURN_RIGHT
                val path = Path().apply {
                    val a = p(0.68f, 0.92f, mir); moveTo(a.x, a.y)
                    val b = p(0.68f, 0.4f, mir); lineTo(b.x, b.y)
                    val c1 = p(0.68f, 0.08f, mir); val c2 = p(0.3f, 0.08f, mir); val e = p(0.3f, 0.4f, mir)
                    cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
                    val f = p(0.3f, 0.62f, mir); lineTo(f.x, f.y)
                }
                drawPath(path, color, style = stroke)
                head(color, p(0.3f, 0.66f, mir), Offset(0f, 1f), sw, s)
            }
            Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT -> {
                // Ring with the approach from below and the exit leaving at the top corner.
                val mir = m == Maneuver.ROUNDABOUT_LEFT
                val ring = p(0.5f, 0.5f, false)
                drawCircle(color, s * 0.2f, ring, style = Stroke(sw * 0.75f))
                drawLine(color, p(0.5f, 0.95f, false), p(0.5f, 0.71f, false), sw, StrokeCap.Round)
                arrow(color, stroke, listOf(p(0.63f, 0.36f, mir), p(0.9f, 0.08f, mir)), sw)
            }
            else -> {
                val (deg, mir) = when (m) {
                    Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> 40f to false
                    Maneuver.RIGHT -> 90f to false
                    Maneuver.SHARP_RIGHT -> 135f to false
                    Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> 40f to true
                    Maneuver.LEFT -> 90f to true
                    else -> 135f to true
                }
                val rad = Math.toRadians(deg.toDouble())
                val dx = sin(rad).toFloat()
                val dy = -cos(rad).toFloat()
                if (deg > 100) {
                    // Sharp turns: the stem rises on the far side and the leg comes back down,
                    // with a smaller head so it stays clear of the stem.
                    arrow(color, stroke, listOf(p(0.26f, 0.94f, mir), p(0.26f, 0.16f, mir), p(0.26f + dx * 0.66f, 0.16f + dy * 0.66f, mir)), sw, headScale = 0.8f)
                } else {
                    val corner = if (deg > 60) 0.46f else 0.5f
                    arrow(color, stroke, listOf(p(0.34f, 0.94f, mir), p(0.34f, corner, mir), p(0.34f + dx * 0.46f, corner + dy * 0.46f, mir)), sw)
                }
            }
        }
    }
}

private fun DrawScope.arrow(color: Color, stroke: Stroke, pts: List<Offset>, sw: Float, headScale: Float = 1f) {
    val end = pts.last()
    val prev = pts[pts.size - 2]
    val d = (end - prev).let { it / it.getDistance() }
    // Stop the shaft short so the tip stays sharp.
    val shaftEnd = end - d * sw * 0.9f
    val shaft = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).dropLast(1).forEach { lineTo(it.x, it.y) }
        lineTo(shaftEnd.x, shaftEnd.y)
    }
    drawPath(shaft, color, style = stroke)
    head(color, end, d, sw, size.minDimension * headScale)
}

/** Filled triangular arrow head with its tip at [tip], pointing along [dir] (unit vector). */
private fun DrawScope.head(color: Color, tip: Offset, dir: Offset, sw: Float, s: Float) {
    val len = s * 0.26f
    val half = s * 0.17f
    val base = tip - dir * len
    val n = Offset(-dir.y, dir.x)
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + n.x * half, base.y + n.y * half)
        lineTo(base.x - n.x * half, base.y - n.y * half)
        close()
    }
    drawPath(p, color)
    drawPath(p, color, style = Stroke(sw * 0.3f, join = StrokeJoin.Round))
}
