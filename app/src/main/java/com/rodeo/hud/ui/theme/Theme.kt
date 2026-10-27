package com.rodeo.hud.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.Brand
import com.rodeo.hud.data.Catalog
import com.rodeo.hud.data.GaugeStyle
import com.rodeo.hud.data.Profile

/**
 * Minimal palette: a neutral base, ONE brand accent and a faint tint from the bike's paint.
 * Everything else is greyscale.
 */
@Immutable
data class HudColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val glass: Color,
    val glassBorder: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val tint: Color,
    val warning: Color,
    val danger: Color,
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

val LocalHud = staticCompositionLocalOf { hudColors(null, null, dark = true) }
val LocalHudStyle = staticCompositionLocalOf { HudStyle(GaugeStyle.MINIMAL, "Rodeo", "", FontFamily.SansSerif, FontWeight.SemiBold) }

private fun contrast(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return if (la > lb) (la / lb).toDouble() else (lb / la).toDouble()
}

/** Keep the brand hue but shift lightness until it reads on the base (3:1 for large UI). */
private fun guard(accent: Color, base: Color, min: Double = 3.0): Color {
    var c = accent
    var i = 0
    val toward = if (base.luminance() < 0.5) Color.White else Color.Black
    while (contrast(c, base) < min && i < 12) {
        c = lerp(c, toward, 0.12f)
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

fun hudColors(brand: Brand?, paintHex: Long?, dark: Boolean, heritage: Boolean = false): HudColors {
    val rawAccent = Color(((if (heritage) brand?.heritageAccent else null) ?: brand?.accent ?: 0xFFFF6A13))
    val paint = Color(paintHex ?: 0xFF6E7176)
    return if (dark) {
        val base = Color(0xFF0B0C0E)
        val accent = guard(rawAccent, base)
        HudColors(
            dark = true,
            background = base,
            surface = Color(0xFF15171A),
            glass = lerp(Color(0xFF1B1D21), paint, 0.10f).copy(alpha = 0.72f),
            glassBorder = Color.White.copy(alpha = 0.09f),
            text = Color(0xFFF2F3F5),
            textMuted = Color(0xFF8E939A),
            accent = accent,
            onAccent = if (accent.luminance() > 0.45f) Color(0xFF0B0C0E) else Color.White,
            tint = paint,
            warning = Color(0xFFF2B33D),
            danger = Color(0xFFFF4D4D),
            ok = Color(0xFF4CD08A),
        )
    } else {
        val base = Color(0xFFF3F4F6)
        val accent = guard(rawAccent, Color.White)
        HudColors(
            dark = false,
            background = base,
            surface = Color.White,
            glass = lerp(Color.White, paint, 0.06f).copy(alpha = 0.86f),
            glassBorder = Color.Black.copy(alpha = 0.08f),
            text = Color(0xFF0E0F11),
            textMuted = Color(0xFF5D6168),
            accent = accent,
            onAccent = if (accent.luminance() > 0.45f) Color(0xFF0E0F11) else Color.White,
            tint = paint,
            warning = Color(0xFFB7791F),
            danger = Color(0xFFD32F2F),
            ok = Color(0xFF1E8E57),
        )
    }
}

fun hudStyle(brand: Brand?): HudStyle {
    val gauge = brand?.gauge ?: GaugeStyle.MINIMAL
    return HudStyle(
        gauge = gauge,
        brandName = brand?.name ?: "Rodeo",
        tagline = brand?.tagline ?: "",
        numeralFont = when (gauge) {
            GaugeStyle.CLASSIC -> FontFamily.Serif
            GaugeStyle.RACE -> FontFamily.SansSerif
            GaugeStyle.MINIMAL -> FontFamily.SansSerif
        },
        numeralWeight = when (gauge) {
            GaugeStyle.RACE -> FontWeight.Black
            GaugeStyle.CLASSIC -> FontWeight.Medium
            GaugeStyle.MINIMAL -> FontWeight.Light
        },
    )
}

@Composable
fun RodeoTheme(profile: Profile, dark: Boolean, content: @Composable () -> Unit) {
    val brand = Catalog.brand(profile.brandId)
    val target = hudColors(brand, profile.paintHex, dark, profile.useHeritageAccent)
    // Tunnel switches are instant; scheduled switches crossfade.
    val spec = tween<Color>(400)
    val bg by animateColorAsState(target.background, spec, label = "bg")
    val text by animateColorAsState(target.text, spec, label = "text")
    val accent by animateColorAsState(target.accent, spec, label = "accent")
    val colors = target.copy(background = bg, text = text, accent = accent)
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.text, onBackground = colors.text, error = colors.danger)
    } else {
        lightColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            surface = colors.surface, onSurface = colors.text, onBackground = colors.text, error = colors.danger)
    }
    CompositionLocalProvider(LocalHud provides colors, LocalHudStyle provides hudStyle(brand)) {
        MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography.copy(
            bodyLarge = TextStyle(fontSize = 18.sp),
            bodyMedium = TextStyle(fontSize = 16.sp),
            labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        ), content = content)
    }
}
