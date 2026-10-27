package com.rodeo.hud.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rodeo.hud.core.Severity
import com.rodeo.hud.core.VibrationLevel
import com.rodeo.hud.data.GaugeStyle
import com.rodeo.hud.ui.components.BrandDecal
import com.rodeo.hud.ui.components.FitText
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.HoldToTrigger
import com.rodeo.hud.ui.components.HudTile
import com.rodeo.hud.ui.components.RevBar
import com.rodeo.hud.ui.components.SlideToConfirm
import com.rodeo.hud.ui.components.StatBox
import com.rodeo.hud.ui.components.StatusChip
import com.rodeo.hud.ui.components.buttonShape
import com.rodeo.hud.ui.components.panelShape
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalHudStyle
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.sdp

/** Actions the HUD (and voice, gestures and handlebar remotes) can trigger. */
interface HudActions {
    fun startRide()
    fun requestEndRide()
    fun confirmEndRide()
    fun cancelEndRide()
    fun sos()
    fun voice()
    fun openRides()
    fun openGarage()
    fun openSettings()
    fun openPack()
}

/** Cockpit callbacks beyond [HudActions]. */
interface CockpitControls : HudActions {
    fun dismissWarning()
    fun musicPrevious()
    fun musicPlayPause()
    fun musicNext()
    fun bluetooth()
    fun wifi()
    fun toggleGestures()
}

/** Everything the cockpit shows, as plain values, so the layout can be previewed and screenshot-tested. */
data class CockpitState(
    val time: String = "18:42",
    val timeSuffix: String = "",
    val weatherTemp: String? = null,
    val weatherLabel: String? = null,
    val sunny: Boolean = true,
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val phoneTempC: Int = 30,
    val lowPower: Boolean = false,
    val gpsOk: Boolean = true,
    val model: String = "",
    val year: String = "",
    val paintName: String = "",
    val riding: Boolean = false,
    val speedKmh: Float = 0f,
    val distanceKm: Float = 0f,
    val avgKmh: Int = 0,
    val elapsed: String = "0:00",
    val topKmh: Int = 0,
    val paused: Boolean = false,
    val vibration: VibrationLevel = VibrationLevel.LOW,
    val bumps: Int = 0,
    val lastDistanceKm: Float? = null,
    val lastAvgKmh: Int? = null,
    val lastTopKmh: Int? = null,
    val warning: WarningUi? = null,
    val music: MusicUi = MusicUi(),
    val btOn: Boolean = false,
    /** Name of the connected headset or intercom, if any. */
    val btDevice: String? = null,
    val wiredHeadset: Boolean = false,
    /** A headset is connected but music is playing from the phone speaker. */
    val musicOnPhone: Boolean = false,
    val wifiOn: Boolean = false,
    val packCount: Int = 0,
    val packActive: Boolean = false,
    val listening: Boolean = false,
    /** The wake phrase is armed (the assistant is listening in the background). */
    val wakeArmed: Boolean = false,
    val gesturesOn: Boolean = false,
    val showEndSlider: Boolean = false,
    val lockInput: Boolean = false,
)

data class WarningUi(val title: String, val detail: String, val severity: Severity, val more: Int = 0)

data class MusicUi(
    val title: String = "",
    val artist: String = "",
    val app: String = "",
    val playing: Boolean = false,
    val art: ImageBitmap? = null,
    val hasAccess: Boolean = false,
)

/**
 * The whole HUD: cockpit panel and map side by side on the horizon backdrop.
 * [backdrop] draws behind (particles), [map] fills the map plate, [overlay] floats on top.
 */
@Composable
fun CockpitLayout(
    s: CockpitState,
    actions: CockpitControls,
    mapOnRight: Boolean,
    map: @Composable (Modifier) -> Unit,
    backdrop: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val c = LocalHud.current
    Box(Modifier.fillMaxSize().background(c.background)) {
        HorizonBackdrop(Modifier.fillMaxSize())
        backdrop()
        Row(Modifier.fillMaxSize().padding(10.sdp()), horizontalArrangement = Arrangement.spacedBy(10.sdp())) {
            val panelWeight = if (s.riding) 1.15f else 1f
            val shape = panelShape()
            val mapModifier = Modifier.weight(1f).fillMaxHeight().clip(shape).background(c.surface).border(c.borderWidth, c.glassBorder, shape)
            if (!mapOnRight) map(mapModifier)
            CockpitPanel(s, actions, Modifier.weight(panelWeight).fillMaxHeight())
            if (mapOnRight) map(mapModifier)
        }
        overlay()
    }
}

/** The cockpit half (status, hero, music, tiles), laid out to fill whatever height it gets. */
@Composable
fun CockpitPanel(s: CockpitState, actions: CockpitControls, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val narrow = maxWidth < 380.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.sdp())) {
            if (s.riding) RideStatusStrip(s) else StatusBar(s, actions, narrow)
            s.warning?.let { WarningRibbon(it, actions::dismissWarning) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.riding) SpeedCluster(s, Modifier.fillMaxSize()) else HeroIdle(s, actions, Modifier.fillMaxSize())
                if (s.showEndSlider) EndRideSheet(actions, Modifier.align(Alignment.BottomCenter))
            }
            MusicStrip(s.music, actions, compact = s.riding)
            TileRow(s, actions, narrow)
        }
    }
}

@Composable
private fun EndRideSheet(actions: CockpitControls, modifier: Modifier) {
    val c = LocalHud.current
    GlassCard(modifier.fillMaxWidth().clip(panelShape()).background(c.surface), accentBorder = c.accent, padding = PaddingValues(8.sdp())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitText("END THIS RIDE?", LocalType.current.headlineLg, c.text, Modifier.weight(1f).padding(start = 8.sdp()))
            Box(Modifier.size(48.sdp()).clip(CircleShape).clickable { actions.cancelEndRide() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, "Keep riding", tint = c.textMuted)
            }
        }
        Spacer(Modifier.height(4.sdp()))
        SlideToConfirm("Slide to end and save parking") { actions.confirmEndRide() }
    }
}

/** Parked status bar: clock, weather and phone badges, and the three big menu buttons. */
@Composable
private fun StatusBar(s: CockpitState, actions: CockpitControls, narrow: Boolean) {
    val c = LocalHud.current
    val t = LocalType.current
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 12.sdp(), vertical = 6.sdp())) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.sdp())) {
            Clock(s, t.headlineXl.copy(fontWeight = FontWeight.ExtraBold))
            // Badges give way first so the buttons never get pushed off; on narrow phones they stack.
            if (narrow) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.sdp())) {
                    MiniStat(if (s.sunny) Icons.Filled.WbSunny else Icons.Filled.Cloud, c.warning, s.weatherTemp ?: "--°")
                    MiniStat(
                        if (s.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                        if (s.batteryPct <= 20) c.danger else c.ok, "${s.batteryPct}%",
                    )
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.sdp()), verticalAlignment = Alignment.CenterVertically) {
                    Badge(
                        if (s.sunny) Icons.Filled.WbSunny else Icons.Filled.Cloud, c.warning,
                        s.weatherTemp ?: "--°", s.weatherLabel ?: "Weather", Modifier.weight(1f, fill = false),
                    )
                    Badge(
                        if (s.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                        if (s.batteryPct <= 20) c.danger else c.ok,
                        "${s.batteryPct}%",
                        "${s.phoneTempC}°C" + if (s.lowPower) " · saver" else "",
                        Modifier.weight(1f, fill = false),
                    )
                }
            }
            SquareButton(Icons.Filled.Timeline, "Rides", !s.lockInput) { actions.openRides() }
            SquareButton(Icons.Filled.TwoWheeler, "Garage", !s.lockInput) { actions.openGarage() }
            SquareButton(Icons.Filled.Tune, "Settings", !s.lockInput) { actions.openSettings() }
        }
    }
}

@Composable
private fun Clock(s: CockpitState, style: TextStyle) {
    val c = LocalHud.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(s.time, color = c.text, style = style, maxLines = 1, softWrap = false)
        if (s.timeSuffix.isNotEmpty()) {
            Spacer(Modifier.width(3.sdp()))
            Text(s.timeSuffix, color = c.accent, style = LocalType.current.label, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 2.sdp()))
        }
    }
}

/** Thin status strip while riding: GPS, weather, battery and time, leaving height for the speed. */
@Composable
private fun RideStatusStrip(s: CockpitState) {
    val c = LocalHud.current
    val t = LocalType.current
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 12.sdp(), vertical = 4.sdp())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.sdp()).clip(CircleShape).background(if (s.gpsOk) c.ok else c.danger))
            Spacer(Modifier.width(6.sdp()))
            Text(if (s.gpsOk) "GPS LOCK" else "NO GPS", color = if (s.gpsOk) c.ok else c.danger, style = t.label, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(12.sdp()))
            Icon(if (s.sunny) Icons.Filled.WbSunny else Icons.Filled.Cloud, null, tint = c.warning, modifier = Modifier.size(16.sdp()))
            Spacer(Modifier.width(4.sdp()))
            FitText(listOfNotNull(s.weatherTemp, s.weatherLabel).joinToString(" ").uppercase().ifBlank { "--" }, t.label, c.text, Modifier.weight(1f))
            Spacer(Modifier.width(8.sdp()))
            Icon(
                if (s.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull, null,
                tint = if (s.batteryPct <= 20) c.danger else c.ok, modifier = Modifier.size(16.sdp()),
            )
            Text("${s.batteryPct}%", color = c.text, style = t.headlineSm, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(12.sdp()))
            Clock(s, t.headlineMd.copy(fontWeight = FontWeight.ExtraBold))
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, iconTint: Color, value: String, sub: String?, modifier: Modifier = Modifier) {
    val c = LocalHud.current
    val t = LocalType.current
    val shape = RoundedCornerShape(10.sdp())
    Row(
        modifier.clip(shape).background(c.surfaceHigh)
            .then(if (c.dark) Modifier else Modifier.border(c.borderWidth, c.glassBorder, shape))
            .padding(horizontal = 8.sdp(), vertical = 4.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.sdp()))
        Spacer(Modifier.width(5.sdp()))
        Column {
            FitText(value, t.headlineSm, c.text)
            if (sub != null) FitText(sub.uppercase(), t.micro, c.textMuted)
        }
    }
}

@Composable
private fun MiniStat(icon: ImageVector, tint: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.sdp()))
        Spacer(Modifier.width(4.sdp()))
        FitText(value, LocalType.current.headlineSm, LocalHud.current.text)
    }
}

@Composable
private fun SquareButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalHud.current
    val shape = buttonShape()
    Column(
        Modifier.size(52.sdp()).clip(shape).background(c.surfaceHigh)
            .border(c.borderWidth, c.glassBorder, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(3.sdp()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = if (enabled) c.text else c.textSubtle, modifier = Modifier.size(22.sdp()))
        FitText(label.uppercase(), LocalType.current.micro, c.textMuted)
    }
}

/** Hazard / weather / device warning. Daylight: solid sign-style amber. Night: tinted plate. */
@Composable
fun WarningRibbon(w: WarningUi, onDismiss: () -> Unit) {
    val c = LocalHud.current
    val t = LocalType.current
    val color = when (w.severity) {
        Severity.DANGER -> c.danger
        Severity.WARN -> c.warning
        Severity.INFO -> c.accent
    }
    val shape = buttonShape()
    val solid = !c.dark
    val fg = if (solid) (if (w.severity == Severity.WARN) Color.Black else Color.White) else c.text
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (solid) color else color.copy(alpha = 0.16f))
            .border(maxOf(c.borderWidth, 1.5.dp), if (solid) c.glassBorder else color.copy(alpha = 0.7f), shape)
            .padding(start = 10.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.sdp()).clip(RoundedCornerShape(8.sdp())).background(if (solid) fg.copy(alpha = 0.12f) else color),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Warning, null, tint = if (solid) fg else c.background, modifier = Modifier.size(20.sdp())) }
        Spacer(Modifier.width(10.sdp()))
        Column(Modifier.weight(1f).padding(vertical = 5.sdp())) {
            FitText(w.title.uppercase(), t.headlineMd.copy(fontWeight = FontWeight.Black), if (solid) fg else color)
            if (w.detail.isNotBlank()) FitText(w.detail.uppercase(), t.micro, if (solid) fg else c.textMuted)
        }
        if (w.more > 0) Text("+${w.more}", color = fg, style = t.label, maxLines = 1)
        Box(Modifier.size(48.sdp()).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "Dismiss", tint = fg)
        }
    }
}

/** Parked hero: brand wordmark with race decals, the bike, last-trip stats and the START dial. */
@Composable
private fun HeroIdle(s: CockpitState, actions: CockpitControls, modifier: Modifier) {
    val c = LocalHud.current
    val t = LocalType.current
    val style = LocalHudStyle.current
    GlassCard(modifier, padding = PaddingValues(0.dp), rim = true) {
        Box(Modifier.fillMaxSize()) {
            BrandDecal(Modifier.fillMaxSize(), alpha = 0.3f)
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.sdp(), vertical = 8.sdp())) {
                val showStats = maxHeight >= 132.sdp()
                val showModel = maxHeight >= 84.sdp()
                val dial = minOf(maxHeight, maxWidth * 0.38f)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        val roomy = maxWidth >= 230.dp
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FitText(
                                    style.brandName.uppercase(),
                                    t.display.copy(fontStyle = if (style.gauge == GaugeStyle.CLASSIC) FontStyle.Normal else FontStyle.Italic),
                                    c.text, Modifier.weight(1f, fill = false),
                                )
                                if (roomy) {
                                    Spacer(Modifier.width(8.sdp()))
                                    RaceStripes(Modifier.width(48.sdp()).height(20.sdp()))
                                }
                            }
                            if (showModel) Column {
                                FitText(
                                    listOf(s.model, s.year).filter { it.isNotBlank() }.joinToString(" · ").uppercase().ifBlank { "YOUR BIKE" },
                                    t.headlineLg, c.text,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (s.paintName.isNotBlank()) FitText(s.paintName.uppercase(), t.label, c.accent, Modifier.weight(1f, fill = false))
                                    if (roomy) {
                                        Spacer(Modifier.width(8.sdp()))
                                        StatusChip("Parked", c.ok)
                                    }
                                }
                            }
                            if (showStats) Row(horizontalArrangement = Arrangement.spacedBy(6.sdp())) {
                                StatBox("Last trip", s.lastDistanceKm?.let { "%.1f".format(it) } ?: "--", "km", Modifier.weight(1f), unitInLabel = !roomy)
                                StatBox("Avg", s.lastAvgKmh?.toString() ?: "--", "km/h", Modifier.weight(1f), unitInLabel = !roomy)
                                StatBox("Top", s.lastTopKmh?.toString() ?: "--", "km/h", Modifier.weight(1f), valueColor = c.ok, unitInLabel = !roomy)
                            }
                        }
                    }
                    Spacer(Modifier.width(10.sdp()))
                    StartDial(dial) { actions.startRide() }
                }
            }
        }
    }
}

/** Angled brand stripes and a checker strip (the decal beside the wordmark). */
@Composable
fun RaceStripes(modifier: Modifier) {
    val c = LocalHud.current
    Canvas(modifier) {
        val h = size.height * 0.55f
        val skew = h * 0.6f
        val widths = listOf(0.42f, 0.26f, 0.14f)
        val colors = listOf(c.accent, c.text, c.textSubtle)
        var x = 0f
        widths.forEachIndexed { i, w ->
            val ww = size.width * w
            val p = Path().apply {
                moveTo(x + skew, 0f); lineTo(x + skew + ww, 0f); lineTo(x + ww, h); lineTo(x, h); close()
            }
            drawPath(p, colors[i])
            x += ww + size.width * 0.04f
        }
        val sq = (size.height - h) * 0.8f
        var cx = 0f
        var n = 0
        while (cx + sq <= size.width * 0.6f) {
            if (n % 2 == 0) drawRect(c.text.copy(alpha = 0.8f), Offset(cx, size.height - sq), Size(sq, sq))
            cx += sq; n++
        }
    }
}

/** START RIDE inside a slowly turning segmented rev ring. */
@Composable
private fun StartDial(size: Dp, onClick: () -> Unit) {
    val c = LocalHud.current
    val t = LocalType.current
    val spin by rememberInfiniteTransition(label = "dial").animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "spin")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().rotate(spin)) {
            val sw = this.size.minDimension * 0.035f
            val r = this.size.minDimension / 2f - sw
            val tl = Offset(center.x - r, center.y - r)
            drawArc(
                if (c.dark) Color(0xFF282A30) else Color(0xFFE5E7EB), 0f, 360f, false, tl, Size(r * 2, r * 2),
                style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 1.3f, sw * 0.8f))),
            )
            drawArc(
                c.accent, 0f, 360f, false, tl, Size(r * 2, r * 2),
                style = Stroke(sw, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 3f, sw * 1.6f))),
            )
            drawCircle(c.warning.copy(alpha = 0.6f), r * 0.89f, style = Stroke(sw * 0.3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(sw * 0.5f, sw))))
        }
        val inner = size * 0.78f
        Box(
            Modifier.size(inner)
                .drawBehind { drawCircle(c.accent.copy(alpha = if (c.dark) 0.3f else 0.16f), this.size.minDimension / 2f * 1.1f) }
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(lighten(c.accent), c.accent)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.FlashOn, null, tint = c.onAccent, modifier = Modifier.size(inner * 0.22f))
                val big = with(LocalDensity.current) { (inner * 0.21f).toSp() }
                Text("START", color = c.onAccent, style = t.headlineLg.copy(fontSize = big, lineHeight = big, fontWeight = FontWeight.Black), maxLines = 1, softWrap = false)
                Text("RIDE", color = c.onAccent.copy(alpha = 0.9f), style = t.label.copy(fontSize = big * 0.55f, lineHeight = big * 0.6f), maxLines = 1, softWrap = false)
            }
        }
    }
}

private fun lighten(c: Color) = Color(c.red + (1f - c.red) * 0.18f, c.green + (1f - c.green) * 0.18f, c.blue + (1f - c.blue) * 0.18f)

/** Riding hero: rev bar, the giant speed and the trip stats. Speed size follows the space it gets. */
@Composable
private fun SpeedCluster(s: CockpitState, modifier: Modifier) {
    val c = LocalHud.current
    val t = LocalType.current
    val anim by animateFloatAsState(s.speedKmh.coerceAtLeast(0f), tween(500), label = "speed")
    GlassCard(modifier, padding = PaddingValues(0.dp), rim = true) {
        Box(Modifier.fillMaxSize()) {
            BrandDecal(Modifier.fillMaxSize(), alpha = 0.18f)
            Column(Modifier.fillMaxSize().padding(horizontal = 14.sdp(), vertical = 8.sdp())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.sdp())) {
                    Text("SPEED", color = c.textMuted, style = t.label, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    StatusChip(
                        "Vibration " + s.vibration.name.lowercase(),
                        when (s.vibration) { VibrationLevel.HIGH -> c.danger; VibrationLevel.MEDIUM -> c.warning; else -> c.ok },
                        Modifier.weight(1f, fill = false),
                    )
                    if (s.bumps > 0) StatusChip("${s.bumps} bumps", c.warning, Modifier.weight(1f, fill = false))
                }
                Spacer(Modifier.height(5.sdp()))
                RevBar(anim / 160f, Modifier.fillMaxWidth().height(12.sdp()))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val gap = 8.sdp()
                    if (maxHeight > maxWidth * 0.5f) {
                        // Tall cards (tablets): the speed spans the width and the stats sit in one row below.
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                SpeedReadout(anim, s.paused, minOf(maxHeight * 0.95f, (maxWidth - 70.sdp()) / 1.65f))
                            }
                            StatsWell(s, Modifier.fillMaxWidth(), columns = 4)
                        }
                    } else {
                        val statsW = maxWidth * 0.42f
                        val speedW = maxWidth - statsW - gap
                        // Condensed italic digits run about half an em wide; three of them plus the unit must fit.
                        val font = minOf(maxHeight * 0.95f, (speedW - 62.sdp()) / 1.65f)
                        val statCols = if (maxHeight >= 96.sdp()) 2 else 1
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(speedW)) { SpeedReadout(anim, s.paused, font) }
                            Spacer(Modifier.width(gap))
                            StatsWell(s, Modifier.width(statsW), columns = statCols)
                        }
                    }
                }
            }
        }
    }
}

/** The big number, its unit and the riding state. */
@Composable
private fun SpeedReadout(speed: Float, paused: Boolean, fontSize: Dp) {
    val c = LocalHud.current
    val t = LocalType.current
    val sp = with(LocalDensity.current) { fontSize.coerceAtLeast(16.dp).toSp() }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${speed.toInt()}", color = c.text, style = t.speed.copy(fontSize = sp, lineHeight = sp), maxLines = 1, softWrap = false)
        Column(Modifier.padding(start = 4.sdp(), bottom = 4.sdp())) {
            Text("KM/H", color = c.accent, style = t.headlineLg.copy(fontStyle = FontStyle.Italic, fontWeight = FontWeight.Black), maxLines = 1, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.sdp()).clip(CircleShape).background(if (paused) c.warning else c.ok))
                Spacer(Modifier.width(4.sdp()))
                Text(
                    if (paused) "PAUSED" else if (speed > 3f) "CRUISE" else "STOPPED",
                    color = c.textMuted, style = t.micro, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/**
 * Trip figures in an inset well. [columns] 4 puts all four in a row (tablets), 2 makes a 2 x 2 grid,
 * and 1 keeps only distance and time when the card is very short.
 */
@Composable
private fun StatsWell(s: CockpitState, modifier: Modifier, columns: Int) {
    val c = LocalHud.current
    val shape = RoundedCornerShape(12.sdp())
    Column(
        modifier.clip(shape).background(if (c.dark) c.surfaceLowest.copy(alpha = 0.7f) else c.surfaceHigh)
            .then(if (c.dark) Modifier else Modifier.border(c.borderWidth, c.glassBorder, shape))
            .padding(horizontal = 8.sdp(), vertical = 6.sdp()),
        verticalArrangement = Arrangement.spacedBy(6.sdp()),
    ) {
        val rows = when (columns) {
            4 -> listOf(listOf(0, 1, 2, 3))
            2 -> listOf(listOf(0, 1), listOf(2, 3))
            else -> listOf(listOf(0, 1))
        }
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.sdp())) { row.forEach { TripStat(it, s, Modifier.weight(1f)) } }
        }
    }
}

@Composable
private fun TripStat(index: Int, s: CockpitState, modifier: Modifier) {
    when (index) {
        0 -> StatBox("Trip", "%.1f".format(s.distanceKm), "km", modifier, boxed = false)
        1 -> StatBox("Time", s.elapsed, "", modifier, boxed = false)
        2 -> StatBox("Avg", "${s.avgKmh}", "km/h", modifier, boxed = false)
        else -> StatBox("Top", "${s.topKmh}", "km/h", modifier, boxed = false, valueColor = LocalHud.current.accent)
    }
}

@Composable
private fun MusicStrip(m: MusicUi, actions: CockpitControls, compact: Boolean) {
    val c = LocalHud.current
    val t = LocalType.current
    val art = if (compact) 38.sdp() else 44.sdp()
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 10.sdp(), vertical = if (compact) 4.sdp() else 6.sdp())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val artShape = RoundedCornerShape(10.sdp())
            if (m.art != null) {
                Image(m.art, null, contentScale = ContentScale.Crop, modifier = Modifier.size(art).clip(artShape))
            } else {
                Box(Modifier.size(art).clip(artShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MusicNote, null, tint = c.accent, modifier = Modifier.size(22.sdp()))
                }
            }
            Spacer(Modifier.width(10.sdp()))
            Column(Modifier.weight(1f)) {
                Text(
                    m.title.ifBlank { if (m.hasAccess) "Nothing playing" else "Music" }, color = c.text,
                    style = t.headlineMd.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(m.artist, m.app).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Controls any music app" },
                    color = c.textMuted, style = t.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val h = if (compact) 44.sdp() else 50.sdp()
            Spacer(Modifier.width(6.sdp()))
            MediaButton(Icons.Filled.SkipPrevious, "Previous", false, h, actions::musicPrevious)
            Spacer(Modifier.width(6.sdp()))
            MediaButton(if (m.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", true, h, actions::musicPlayPause)
            Spacer(Modifier.width(6.sdp()))
            MediaButton(Icons.Filled.SkipNext, "Next", false, h, actions::musicNext)
        }
    }
}

@Composable
private fun MediaButton(icon: ImageVector, label: String, primary: Boolean, height: Dp, onClick: () -> Unit) {
    val c = LocalHud.current
    val shape = buttonShape()
    val bg = when {
        primary && c.dark -> c.accent
        primary -> Color.Black
        else -> c.surfaceHigh
    }
    val fg = when {
        primary && c.dark -> c.onAccent
        primary -> Color.White
        else -> c.text
    }
    Box(
        Modifier.width(if (primary) 60.sdp() else 52.sdp()).height(height).clip(shape).background(bg)
            .border(c.borderWidth, if (primary && c.dark) Color.Transparent else c.glassBorder, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = fg, modifier = Modifier.size(28.sdp())) }
}

@Composable
private fun TileRow(s: CockpitState, actions: CockpitControls, narrow: Boolean) {
    val c = LocalHud.current
    val tileH = if (s.riding) 54.sdp() else 58.sdp()
    val tile = Modifier.fillMaxHeight()
    Row(Modifier.fillMaxWidth().height(tileH), horizontalArrangement = Arrangement.spacedBy(6.sdp())) {
        val connected = s.btDevice != null || s.wiredHeadset
        HudTile(
            when {
                s.wiredHeadset && s.btDevice == null -> Icons.Filled.Headphones
                !s.btOn -> Icons.Filled.BluetoothDisabled
                s.btDevice != null -> Icons.Filled.BluetoothConnected
                else -> Icons.Filled.Bluetooth
            },
            if (s.musicOnPhone) "Phone speaker" else s.btDevice ?: if (s.wiredHeadset) "Wired" else if (s.btOn) "Bluetooth" else "BT off",
            tile.weight(1.15f), active = connected, jewel = if (s.musicOnPhone) c.warning else if (connected) c.ok else null,
        ) { actions.bluetooth() }
        if (!s.riding && !narrow) {
            HudTile(
                if (s.wifiOn) Icons.Filled.Wifi else Icons.Filled.WifiOff, if (s.wifiOn) "Wi-Fi" else "Wi-Fi off",
                tile.weight(1f), active = s.wifiOn, jewel = if (s.wifiOn) c.ok else null,
            ) { actions.wifi() }
        }
        HudTile(Icons.Filled.Groups, if (s.packActive) "Pack ${s.packCount}" else "Pack", tile.weight(1f), active = s.packActive) { actions.openPack() }
        HudTile(Icons.Filled.Mic, "Voice", tile.weight(1f), active = s.listening, jewel = if (s.listening || s.wakeArmed) c.ok else null) { actions.voice() }
        if (!s.riding) {
            HudTile(
                Icons.Filled.PanTool, "Gestures", tile.weight(1f), active = s.gesturesOn,
                jewel = if (s.gesturesOn) c.ok else null,
            ) { actions.toggleGestures() }
        }
        HoldToTrigger("SOS", "Hold 2 s", c.danger, tile.weight(1.35f)) { actions.sos() }
        if (s.riding) HudTile(Icons.Filled.PowerSettingsNew, "End", tile.weight(1f)) { actions.requestEndRide() }
    }
}

/** Big, brief confirmation when a hand gesture or voice command lands. */
@Composable
fun ActionToast(visible: Boolean, icon: ImageVector?, label: String, modifier: Modifier = Modifier) {
    val c = LocalHud.current
    AnimatedVisibility(visible, modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.accent).padding(horizontal = 22.sdp(), vertical = 10.sdp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(28.sdp()))
                Spacer(Modifier.width(10.sdp()))
            }
            Text(label.uppercase(), color = c.onAccent, style = LocalType.current.headlineLg, maxLines = 1)
        }
    }
}

/** Perspective grid and a soft accent bloom behind the cockpit (the design's horizon backdrop). */
@Composable
fun HorizonBackdrop(modifier: Modifier) {
    val c = LocalHud.current
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.42f
        val col = c.accent.copy(alpha = if (c.dark) 0.12f else 0.07f)
        for (k in -10..10) drawLine(col, Offset(w / 2f, horizon), Offset(w / 2f + k * w / 7f, h), 1.dp.toPx())
        var y = horizon
        var step = h * 0.02f
        while (y < h) {
            drawLine(col, Offset(0f, y), Offset(w, y), 1.dp.toPx())
            y += step
            step *= 1.45f
        }
        val bloom = Offset(w * 0.25f, h * 0.3f)
        drawCircle(Brush.radialGradient(listOf(c.accent.copy(alpha = if (c.dark) 0.12f else 0.05f), Color.Transparent), bloom, w * 0.3f), w * 0.3f, bloom)
    }
}
