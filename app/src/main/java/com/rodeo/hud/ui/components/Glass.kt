package com.rodeo.hud.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalScale
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.sdp

/** Panel corner radius: 28 px on the 2400 px canvas, scaled to the screen. */
@Composable
fun panelShape(): Shape = RoundedCornerShape((20 * LocalScale.current).dp)

@Composable
fun buttonShape(): Shape = RoundedCornerShape((14 * LocalScale.current).dp)

/** Fallback shape for code that needs a constant. */
val CardShape = RoundedCornerShape(20.dp)

/**
 * Text that never clips: it shrinks (down to [minScale]) until it fits its width and line count.
 * Use for every label that sits in a fixed-size box.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    minScale: Float = 0.45f,
    textAlign: TextAlign? = null,
) {
    var factor by remember(text, style) { mutableFloatStateOf(1f) }
    var ready by remember(text, style) { mutableStateOf(false) }
    // When the space changes (resize, split screen), start again from full size.
    var lastMax by remember(text, style) { mutableIntStateOf(-1) }
    val size: TextUnit = style.fontSize * factor
    Text(
        text,
        color = color,
        style = style.copy(fontSize = size, lineHeight = if (style.lineHeight.isSpecified()) style.lineHeight * factor else style.lineHeight),
        maxLines = maxLines,
        softWrap = maxLines > 1,
        // Last resort once it is as small as it may go: an ellipsis rather than a hard cut.
        overflow = if (factor <= minScale) TextOverflow.Ellipsis else TextOverflow.Clip,
        textAlign = textAlign,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r ->
            val max = r.layoutInput.constraints.maxWidth
            if (lastMax != -1 && max != lastMax && factor < 1f) {
                lastMax = max
                factor = 1f
                ready = false
            } else {
                lastMax = max
                if (r.hasVisualOverflow && factor > minScale) factor = (factor * 0.9f).coerceAtLeast(minScale)
                else ready = true
            }
        },
    )
}

private fun TextUnit.isSpecified() = this != TextUnit.Unspecified

/**
 * Frosted-glass plate. Night: a translucent tinted pane with a light top edge and a rim that fades
 * from bright at the top to faint at the bottom, so the accent glow behind shows through. Day: a
 * near-opaque white pane with a dark rule, because translucency washes out in direct sun.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    padding: PaddingValues = PaddingValues(14.sdp()),
    rim: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHud.current
    val shape = panelShape()
    Column(
        modifier
            .clip(shape)
            .background(c.glass)
            .then(if (c.dark) Modifier.background(glassSheen()) else Modifier)
            .border(
                if (accentBorder != null) BorderStroke(maxOf(c.borderWidth, 1.5.dp), accentBorder)
                else if (c.dark) BorderStroke(1.dp, glassRim())
                else BorderStroke(c.borderWidth, c.glassBorder),
                shape,
            )
            .then(
                if (rim) Modifier.drawWithContent {
                    drawContent()
                    drawLine(
                        Brush.horizontalGradient(listOf(Color.Transparent, c.accent, Color.Transparent)),
                        Offset(size.width * 0.05f, 1f), Offset(size.width * 0.95f, 1f), 2.dp.toPx(),
                    )
                } else Modifier,
            )
            .padding(padding),
        content = content,
    )
}

/** Light falling on the top of a glass pane. */
fun glassSheen(strength: Float = 1f) = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.075f * strength),
    0.45f to Color.White.copy(alpha = 0.02f * strength),
    1f to Color.Transparent,
)

/** A glass pane's edge: bright where light catches the top, nearly gone at the bottom. */
fun glassRim(alpha: Float = 1f) = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.30f * alpha), Color.White.copy(alpha = 0.08f * alpha), Color.White.copy(alpha = 0.04f * alpha)),
)

/** Fill and edge for buttons and tiles: frosted at night, solid in the day. */
@Composable
private fun Modifier.glassButton(shape: Shape, active: Boolean = false): Modifier {
    val c = LocalHud.current
    return if (c.dark) {
        this.background(
            if (active) Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.34f), c.accent.copy(alpha = 0.14f)))
            else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.04f))),
        ).border(BorderStroke(1.dp, if (active) Brush.verticalGradient(listOf(c.accent, c.accent.copy(alpha = 0.5f))) else glassRim()), shape)
    } else {
        this.background(if (active) c.accent.copy(alpha = 0.12f) else c.surfaceHigh)
            .border(c.borderWidth, if (active) c.accent else c.glassBorder, shape)
    }
}

/** Glove tile: big icon with a status jewel, label underneath that shrinks rather than clips. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HudTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    jewel: Color? = null,
    minSize: Dp = 56.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalHud.current
    val t = LocalType.current
    val shape = buttonShape()
    val fg = tint ?: if (active) c.accent else c.text
    Box(
        modifier
            .defaultMinSize(minSize, minSize)
            .clip(shape)
            .glassButton(shape, active)
            .alpha(if (enabled) 1f else 0.4f)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 6.sdp(), vertical = 6.sdp()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(24.sdp()))
                if (jewel != null) {
                    Spacer(Modifier.width(4.sdp()))
                    Box(Modifier.size(7.sdp()).clip(CircleShape).background(jewel))
                }
            }
            Spacer(Modifier.height(3.sdp()))
            FitText(label.uppercase(), t.micro, c.textMuted, textAlign = TextAlign.Center)
        }
    }
}

/** Solid accent button, Barlow caps. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, color: Color? = null, onClick: () -> Unit) {
    val c = LocalHud.current
    val bg = color ?: c.accent
    val fg = if (color == null) c.onAccent else Color.White
    val shape = buttonShape()
    Row(
        modifier
            .defaultMinSize(minHeight = 52.sdp())
            .clip(shape)
            .background(
                if (enabled) Brush.verticalGradient(listOf(lerp(bg, Color.White, 0.18f), bg, lerp(bg, Color.Black, 0.08f)))
                else SolidColor(c.textSubtle.copy(alpha = 0.3f)),
            )
            .then(if (enabled) Modifier.border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent))), shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.sdp(), vertical = 10.sdp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(22.sdp()))
            Spacer(Modifier.width(8.sdp()))
        }
        FitText(text.uppercase(), LocalType.current.headlineSm, fg)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = LocalHud.current
    val shape = buttonShape()
    Row(
        modifier
            .defaultMinSize(minHeight = 48.sdp())
            .clip(shape)
            .glassButton(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.sdp(), vertical = 8.sdp()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.sdp()))
            Spacer(Modifier.width(8.sdp()))
        }
        FitText(text.uppercase(), LocalType.current.headlineSm, c.text)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = LocalHud.current.textMuted, style = LocalType.current.label, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Status pill with a glowing jewel, e.g. VIBRATION LOW. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    val c = LocalHud.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(color.copy(alpha = if (c.dark) 0.16f else 0.12f))
            .then(if (c.dark) Modifier else Modifier.border(2.dp, color, shape))
            .padding(horizontal = 10.sdp(), vertical = 4.sdp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.sdp()).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.sdp()))
        FitText(text.uppercase(), LocalType.current.label, if (c.dark) color else c.text)
    }
}

/** Small boxed metric: micro label over a big Barlow figure and unit. */
@Composable
fun StatBox(
    label: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    boxed: Boolean = true,
    /** Narrow boxes: put the unit beside the label so the figure keeps its full size. */
    unitInLabel: Boolean = false,
) {
    val c = LocalHud.current
    val t = LocalType.current
    val shape = RoundedCornerShape(12.sdp())
    Column(
        modifier
            .then(
                if (boxed) Modifier.clip(shape).background(if (c.dark) c.surfaceLowest.copy(alpha = 0.8f) else c.surfaceHigh)
                    .then(if (c.dark) Modifier else Modifier.border(c.borderWidth, c.glassBorder, shape))
                    .padding(horizontal = 10.sdp(), vertical = 6.sdp())
                else Modifier,
            ),
    ) {
        FitText((if (unitInLabel && unit.isNotEmpty()) "$label · $unit" else label).uppercase(), t.micro, c.textMuted)
        Row(verticalAlignment = Alignment.Bottom) {
            FitText(value, t.headlineLg, valueColor ?: c.text, modifier = Modifier.weight(1f, fill = false))
            if (unit.isNotEmpty() && !unitInLabel) {
                Spacer(Modifier.width(3.sdp()))
                Text(unit.uppercase(), color = c.accent, style = t.label, maxLines = 1, modifier = Modifier.padding(bottom = 2.sdp()))
            }
        }
    }
}

/**
 * 24-segment skewed bar (DESIGN.md rev-bar). Lit segments run accent, then amber, then red.
 * Rodeo drives it with speed, since a phone cannot read engine RPM.
 */
@Composable
fun RevBar(fraction: Float, modifier: Modifier = Modifier, segments: Int = 24) {
    val c = LocalHud.current
    Canvas(modifier) {
        val gap = 3.dp.toPx()
        val skew = size.height * 0.35f
        val w = (size.width - gap * (segments - 1) - skew) / segments
        val lit = (fraction.coerceIn(0f, 1f) * segments).let { if (fraction > 0.005f) maxOf(1f, it) else it }.toInt()
        for (i in 0 until segments) {
            val x = i * (w + gap)
            val zone = i.toFloat() / segments
            val on = i < lit
            val col = when {
                !on -> if (c.dark) Color.White.copy(alpha = 0.08f) else Color(0xFFE5E7EB)
                zone >= 0.85f -> c.danger
                zone >= 0.65f -> c.warning
                else -> c.accent
            }
            val p = Path().apply {
                moveTo(x + skew, 0f); lineTo(x + skew + w, 0f); lineTo(x + w, size.height); lineTo(x, size.height); close()
            }
            drawPath(p, col)
        }
    }
}
