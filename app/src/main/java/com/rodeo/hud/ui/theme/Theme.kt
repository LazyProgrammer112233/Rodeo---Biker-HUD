package com.rodeo.hud.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rodeo.hud.R
import com.rodeo.hud.data.Brand
import com.rodeo.hud.data.Catalog
import com.rodeo.hud.data.GaugeStyle
import com.rodeo.hud.data.Profile

/** Display face for numbers, headings and labels (condensed, motorsport). */
val Barlow = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_extrabold, FontWeight.ExtraBold),
    Font(R.font.barlow_condensed_black, FontWeight.Black),
    Font(R.font.barlow_condensed_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(R.font.barlow_condensed_extrabold_italic, FontWeight.ExtraBold, FontStyle.Italic),
    Font(R.font.barlow_condensed_black_italic, FontWeight.Black, FontStyle.Italic),
)

/** Body face for sentences and settings. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/**
 * Colour tokens from the Apex Telemetry design (DESIGN.md). One brand accent per bike;
 * everything else is graphite (night) or white with dark rules (day, for direct sun).
 */
@Immutable
data class HudColors(
    val dark: Boolean,
    /** Page base behind everything. */
    val background: Color,
    /** Lowest plate (map chips, inner wells). */
    val surfaceLowest: Color,
    /** Standard panel plate. */
    val surface: Color,
    /** Raised plate: buttons and inner stat boxes. */
    val surfaceHigh: Color,
    val surfaceHighest: Color,
    /** Panel fill used by cards; same as surface but kept for older call sites. */
    val glass: Color,
    /** Panel outline: faint in the dark, a 2 dp near-black rule in daylight. */
    val glassBorder: Color,
    val borderWidth: Dp,
    val text: Color,
    val textMuted: Color,
    val textSubtle: Color,
    val accent: Color,
    val onAccent: Color,
    /** Paint colour of the rider's bike, used as a faint tint only. */
    val tint: Color,
    val warning: Color,
    val onWarning: Color,
    val danger: Color,
    val dangerContainer: Color,
    val ok: Color,
)

@Immutable
data class HudStyle(
    val gauge: GaugeStyle,
    val brandName: String,
    val tagline: String,
    val numeralFont: FontFamily,
    val numeralWeight: FontWeight,
)

/**
 * Type ramp from DESIGN.md, scaled to the screen. The reference canvas is 2400 x 1080 px,
 * about 914 x 411 dp on a typical phone; [scale] is 1 there and grows on tablets.
 */
@Immutable
class HudType(val scale: Float) {
    private fun s(v: Int): TextUnit = (v * scale).sp
    private fun barlow(size: Int, weight: FontWeight, spacing: Float = 0f, italic: Boolean = false, line: Float = 1.1f) = TextStyle(
        fontFamily = Barlow, fontWeight = weight, fontSize = s(size), letterSpacing = spacing.em,
        lineHeight = (size * scale * line).sp, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
        fontFeatureSettings = "tnum",
    )
    private fun inter(size: Int, weight: FontWeight) = TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = s(size), lineHeight = (size * scale * 1.35f).sp)

    val speed = barlow(96, FontWeight.ExtraBold, -0.02f, italic = true, line = 0.95f)
    val countdown = barlow(110, FontWeight.ExtraBold, -0.02f, line = 0.95f)
    val display = barlow(46, FontWeight.Black, 0f, italic = true, line = 1f)
    val headlineXl = barlow(30, FontWeight.Bold, 0.02f)
    val headlineLg = barlow(24, FontWeight.Bold, 0.03f)
    val headlineMd = barlow(20, FontWeight.SemiBold, 0.04f)
    val headlineSm = barlow(17, FontWeight.Bold, 0.05f)
    val label = barlow(14, FontWeight.Bold, 0.1f)
    val micro = barlow(11, FontWeight.SemiBold, 0.12f)
    val bodyLg = inter(16, FontWeight.Medium)
    val body = inter(14, FontWeight.Normal)
    val bodySm = inter(12, FontWeight.Normal)
}

val LocalHud = staticCompositionLocalOf { hudColors(null, null, dark = true) }
val LocalHudStyle = staticCompositionLocalOf { HudStyle(GaugeStyle.MINIMAL, "Rodeo", "", Barlow, FontWeight.ExtraBold) }
val LocalType = staticCompositionLocalOf { HudType(1f) }
/** Size multiplier for spacing and touch targets, from the screen size. */
val LocalScale = staticCompositionLocalOf { 1f }

private fun contrast(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return if (la > lb) (la / lb).toDouble() else (lb / la).toDouble()
}

/** Keep the brand hue but shift lightness until it reads on the base. */
private fun guard(accent: Color, base: Color, min: Double): Color {
    var c = accent
    var i = 0
    val toward = if (base.luminance() < 0.5) Color.White else Color.Black
    while (contrast(c, base) < min && i < 20) {
        c = lerp(c, toward, 0.08f)
        i++
    }
    return c
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/** Push the brand colour toward full saturation so it pops in sun and at night. */
private fun vivid(c: Color, amount: Float = 0.2f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] + (1f - hsv[1]) * amount).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun onColor(c: Color) = if (c.luminance() > 0.4f) Color(0xFF0C0E14) else Color.White

/**
 * The accent: the bike's own paint when it has a real colour (a blue Hunter gets a blue HUD), the
 * brand colour for black, white, grey and silver bikes, and the heritage colour when asked for.
 */
fun accentFor(brand: Brand?, paintHex: Long?, heritage: Boolean = false): Long {
    if (heritage) brand?.heritageAccent?.let { return it }
    if (paintHex != null && isChromatic(paintHex)) return paintHex
    return brand?.accent ?: 0xFFFF6600
}

/** True when a paint has enough colour to be an accent (not black, white, grey or silver). */
fun isChromatic(hex: Long): Boolean {
    val r = (hex shr 16 and 0xFF) / 255f
    val g = (hex shr 8 and 0xFF) / 255f
    val b = (hex and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val saturation = if (max == 0f) 0f else (max - minOf(r, g, b)) / max
    return saturation >= 0.3f && max >= 0.22f
}

fun hudColors(brand: Brand?, paintHex: Long?, dark: Boolean, heritage: Boolean = false): HudColors {
    val raw = vivid(Color(accentFor(brand, paintHex, heritage)))
    val paint = Color(paintHex ?: 0xFF6E7176)
    return if (dark) {
        // Night: graphite plates on a deep void; accent lifted to 4.5:1 on the base if needed.
        val base = Color(0xFF05060A)
        val accent = guard(raw, Color(0xFF191B22), 3.5)
        HudColors(
            dark = true,
            background = base,
            surfaceLowest = Color(0xFF0C0E14),
            surface = Color(0xFF191B22),
            surfaceHigh = Color(0xFF282A30),
            surfaceHighest = Color(0xFF33343B),
            glass = Color(0xFF191B22).copy(alpha = 0.92f),
            glassBorder = Color.White.copy(alpha = 0.07f),
            borderWidth = 1.dp,
            text = Color(0xFFFFFFFF),
            textMuted = Color(0xFFB4B9C0),
            textSubtle = Color(0xFF7D8591),
            accent = accent,
            onAccent = onColor(accent),
            tint = paint,
            warning = Color(0xFFF9BD2D),
            onWarning = Color(0xFF261900),
            danger = Color(0xFFFF3B3B),
            dangerContainer = Color(0xFF93000A),
            ok = Color(0xFF43E188),
        )
    } else {
        // Day: white plates, 2 dp near-black rules, black text; accent darkened to 4.5:1 on white.
        val accent = guard(raw, Color.White, 4.5)
        HudColors(
            dark = false,
            background = Color(0xFFF4F5F7),
            surfaceLowest = Color(0xFFFFFFFF),
            surface = Color(0xFFFFFFFF),
            surfaceHigh = Color(0xFFF4F5F7),
            surfaceHighest = Color(0xFFE5E7EB),
            glass = Color.White,
            glassBorder = Color(0xFF1F232B),
            borderWidth = 2.dp,
            text = Color(0xFF000000),
            textMuted = Color(0xFF34373C),
            textSubtle = Color(0xFF5A606A),
            accent = accent,
            onAccent = onColor(accent),
            tint = paint,
            warning = Color(0xFFFFB703),
            onWarning = Color(0xFF000000),
            danger = Color(0xFFD90429),
            dangerContainer = Color(0xFFD90429),
            ok = Color(0xFF008A42),
        )
    }
}

fun hudStyle(brand: Brand?): HudStyle {
    val gauge = brand?.gauge ?: GaugeStyle.MINIMAL
    return HudStyle(
        gauge = gauge,
        brandName = brand?.name ?: "Rodeo",
        tagline = brand?.tagline ?: "",
        numeralFont = Barlow,
        numeralWeight = when (gauge) {
            GaugeStyle.RACE -> FontWeight.Black
            GaugeStyle.CLASSIC -> FontWeight.Bold
            GaugeStyle.MINIMAL -> FontWeight.SemiBold
        },
    )
}

/** Scale from the reference landscape phone (914 x 411 dp), clamped so small phones stay usable. */
fun scaleFor(widthDp: Float, heightDp: Float): Float =
    minOf(widthDp / 914f, heightDp / 411f).coerceIn(0.78f, 1.7f)

@Composable
fun RodeoTheme(profile: Profile, dark: Boolean, content: @Composable () -> Unit) {
    val brand = Catalog.brand(profile.brandId)
    RodeoTheme(brand, profile.paintHex, dark, profile.useHeritageAccent, content)
}

@Composable
fun RodeoTheme(brand: Brand?, paintHex: Long?, dark: Boolean, heritage: Boolean = false, content: @Composable () -> Unit) {
    val target = hudColors(brand, paintHex, dark, heritage)
    val spec = tween<Color>(400)
    val bg by animateColorAsState(target.background, spec, label = "bg")
    val text by animateColorAsState(target.text, spec, label = "text")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val colors = target.copy(background = bg, text = text, accent = accent, surface = surface)
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.text, onBackground = colors.text, error = colors.danger)
    } else {
        lightColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.text, onBackground = colors.text, error = colors.danger)
    }
    BoxWithConstraints {
        val scale = scaleFor(maxWidth.value, maxHeight.value)
        val type = remember(scale) { HudType(scale) }
        CompositionLocalProvider(
            LocalHud provides colors,
            LocalHudStyle provides hudStyle(brand),
            LocalType provides type,
            LocalScale provides scale,
        ) {
            MaterialTheme(
                colorScheme = scheme,
                typography = MaterialTheme.typography.copy(
                    bodyLarge = type.bodyLg, bodyMedium = type.body, bodySmall = type.bodySm,
                    labelLarge = type.headlineSm, titleLarge = type.headlineLg, titleMedium = type.headlineMd,
                ),
                content = content,
            )
        }
    }
}

/** dp scaled to the screen; use for padding, gaps and touch targets. */
@Composable
fun Int.sdp(): Dp = (this * LocalScale.current).dp

@Composable
fun Float.sdp(): Dp = (this * LocalScale.current).dp
