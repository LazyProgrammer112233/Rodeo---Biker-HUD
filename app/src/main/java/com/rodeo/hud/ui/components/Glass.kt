package com.rodeo.hud.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.ui.theme.LocalHud

val CardShape = RoundedCornerShape(20.dp)

/** Frosted glass card: translucent fill tinted by the bike's paint, hairline border, soft top sheen. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentBorder: Color? = null,
    padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalHud.current
    Column(
        modifier
            .clip(CardShape)
            .background(c.glass)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.dark) 0.04f else 0.25f), Color.Transparent)))
            .border(BorderStroke(if (accentBorder != null) 1.5.dp else 1.dp, accentBorder ?: c.glassBorder), CardShape)
            .padding(padding),
        content = content,
    )
}

/** Glove-size tile button (min 64 dp). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HudTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color? = null,
    minSize: Dp = 64.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalHud.current
    val fg = when {
        tint != null -> tint
        active -> c.accent
        else -> c.text
    }
    Box(
        modifier
            .defaultMinSize(minSize, minSize)
            .clip(CardShape)
            .background(if (active) c.accent.copy(alpha = 0.14f) else c.glass)
            .border(1.dp, if (active) c.accent.copy(alpha = 0.6f) else c.glassBorder, CardShape)
            .alpha(if (enabled) 1f else 0.4f)
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(26.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, color = c.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Primary pill button used outside the ride screen. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = LocalHud.current
    Row(
        modifier
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (enabled) c.accent else c.textMuted.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = LocalHud.current
    Row(
        modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .border(1.dp, c.glassBorder, RoundedCornerShape(26.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.text, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c.text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = LocalHud.current.textMuted, fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}
