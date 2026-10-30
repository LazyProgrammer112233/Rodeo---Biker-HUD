package com.rodeo.hud.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.rodeo.hud.ui.theme.isChromatic
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.Catalog
import com.rodeo.hud.data.Paint
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.components.SpeedGauge
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.ui.theme.LocalHudStyle
import java.time.Year

private enum class Step { WELCOME, PERMISSIONS, BRAND, MODEL, PAINT, SOS }

/**
 * First run: stub login, permissions, bike (brand → model → year + paint) with live theme preview,
 * SOS contacts. With [editBike] it is only the bike steps, opened from Settings: back on the first
 * step puts the old bike back, and finishing the colour step returns to Settings.
 */
@Composable
fun Onboarding(editBike: Boolean = false, onCancel: () -> Unit = {}, onDone: () -> Unit) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val steps = if (editBike) listOf(Step.BRAND, Step.MODEL, Step.PAINT) else Step.entries
    var step by remember { mutableStateOf(steps.first()) }
    var query by remember { mutableStateOf("") }
    val original = remember { profile }

    fun next() {
        step = steps.getOrNull(steps.indexOf(step) + 1) ?: run {
            g.profile.update { it.copy(onboarded = true) }
            onDone()
            step
        }
    }
    fun back() {
        val i = steps.indexOf(step)
        if (i == 0) {
            if (editBike) {
                g.profile.update {
                    it.copy(
                        brandId = original.brandId, model = original.model, year = original.year, paintName = original.paintName,
                        paintHex = original.paintHex, useHeritageAccent = original.useHeritageAccent,
                    )
                }
                onCancel()
            }
        } else step = steps[i - 1]
    }
    BackHandler(enabled = editBike || step != steps.first()) { back() }

    Row(Modifier.fillMaxSize().background(c.background).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // Left: the current step.
        Column(Modifier.weight(1.25f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (editBike || step != steps.first()) {
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable { back() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                    }
                }
                Text(
                    when (step) {
                        Step.WELCOME -> "Welcome to Rodeo"
                        Step.PERMISSIONS -> "What Rodeo needs"
                        Step.BRAND -> "Which bike do you ride?"
                        Step.MODEL -> "Pick your ${Catalog.brand(profile.brandId)?.name ?: ""} model"
                        Step.PAINT -> "Year and colour"
                        Step.SOS -> "Who should we alert in an emergency?"
                    },
                    color = c.text, style = LocalType.current.headlineXl,
                )
                Spacer(Modifier.weight(1f))
                StepDots(steps.indexOf(step), steps.size)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(step, label = "step") { s ->
                    when (s) {
                        Step.WELCOME -> Welcome()
                        Step.PERMISSIONS -> PermissionsPanel(Modifier.verticalScroll(rememberScrollState()))
                        Step.BRAND -> BrandGrid(query, { query = it }) { id ->
                            g.profile.update { it.copy(brandId = id, model = null, paintHex = null, paintName = null, year = null) }
                            next()
                        }
                        Step.MODEL -> ModelList { m ->
                            g.profile.update { it.copy(model = m) }
                            next()
                        }
                        Step.PAINT -> PaintPicker()
                        Step.SOS -> SosContactsEditor(Modifier.verticalScroll(rememberScrollState()))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                when (step) {
                    Step.WELCOME -> PrimaryButton("Continue", icon = Icons.AutoMirrored.Filled.ArrowForward) { next() }
                    Step.PERMISSIONS -> PrimaryButton("Continue", icon = Icons.AutoMirrored.Filled.ArrowForward) { next() }
                    Step.BRAND, Step.MODEL -> Text("Tap to choose", color = c.textMuted)
                    Step.PAINT -> PrimaryButton(if (editBike) "Save bike" else "Continue", enabled = profile.paintHex != null && profile.year != null, icon = Icons.AutoMirrored.Filled.ArrowForward) { next() }
                    Step.SOS -> {
                        PrimaryButton("Start riding", icon = Icons.Filled.Check) { next() }
                        if (profile.sosContacts.isEmpty()) GhostButton("Skip for now") { next() }
                    }
                }
            }
        }
        // Right: live preview of the HUD in the chosen theme.
        ThemePreview(Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun StepDots(index: Int, total: Int) {
    val c = LocalHud.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            Box(Modifier.size(if (i == index) 22.dp else 8.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(if (i <= index) c.accent else c.textMuted.copy(alpha = 0.3f)))
        }
    }
}

@Composable
private fun Welcome() {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your bike's own HUD. Mount the phone sideways, tap Start Ride, and Rodeo handles maps, music, safety and your ride log.", color = c.textMuted, style = LocalType.current.bodyLg)
        SectionLabel("Sign in")
        Text("Login comes in a later build. For now, just tell us your name.", color = c.textMuted, style = LocalType.current.body)
        Field(if (profile.riderName == "Rider") "" else profile.riderName, { v -> g.profile.update { it.copy(riderName = v.ifBlank { "Rider" }) } }, "Your name", Modifier.width(320.dp))
    }
}

@Composable
private fun BrandGrid(query: String, onQuery: (String) -> Unit, onPick: (String) -> Unit) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val brands = Catalog.brands.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    Column {
        Field(query, onQuery, "Search brands", Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(brands, key = { it.id }) { b ->
                val accent = Color(b.accent)
                val sel = profile.brandId == b.id
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(c.glass)
                        .border(if (sel) 2.dp else 1.dp, if (sel) accent else c.glassBorder, RoundedCornerShape(18.dp))
                        .clickable { onPick(b.id) }.padding(14.dp),
                ) {
                    Box(Modifier.size(28.dp, 6.dp).clip(RoundedCornerShape(3.dp)).background(accent))
                    Spacer(Modifier.height(10.dp))
                    Text(b.name, color = c.text, fontWeight = FontWeight.SemiBold, style = LocalType.current.bodyLg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${b.models.size} models", color = c.textMuted, style = LocalType.current.bodySm)
                }
            }
        }
    }
}

@Composable
private fun ModelList(onPick: (String) -> Unit) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val brand = Catalog.brand(profile.brandId) ?: return
    LazyVerticalGrid(GridCells.Adaptive(200.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(brand.models, key = { it.name }) { m ->
            val sel = profile.model == m.name
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(c.glass)
                    .border(if (sel) 2.dp else 1.dp, if (sel) c.accent else c.glassBorder, RoundedCornerShape(16.dp))
                    .clickable { onPick(m.name) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Since ${m.firstYear} · ${m.paints.size} colours", color = c.textMuted, style = LocalType.current.bodySm)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    m.paints.take(4).forEach { p ->
                        Box(Modifier.size(16.dp).clip(CircleShape).background(Color(p.hex)).border(1.dp, c.background, CircleShape))
                    }
                }
            }
        }
    }
}

@Composable
private fun PaintPicker() {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val model = Catalog.model(profile.brandId, profile.model) ?: return
    val brand = Catalog.brand(profile.brandId) ?: return
    val now = Year.now().value
    val years = (now downTo maxOf(model.firstYear, now - 25)).toList()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Model year")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(years) { y ->
                val sel = profile.year == y
                Text(
                    "$y", color = if (sel) c.onAccent else c.text, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) c.accent else c.surfaceHigh)
                        .border(1.dp, if (sel) c.accent else c.glassBorder, RoundedCornerShape(14.dp))
                        .clickable { g.profile.update { it.copy(year = y) } }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        SectionLabel("Factory colours")
        PaintRow(model.paints, profile.paintHex, profile.paintName) { p -> g.profile.update { it.copy(paintHex = p.hex, paintName = p.name) } }
        SectionLabel("Or a custom colour")
        PaintRow(CUSTOM_PAINTS, profile.paintHex, profile.paintName) { p -> g.profile.update { it.copy(paintHex = p.hex, paintName = p.name) } }
        val hex = profile.paintHex
        if (hex != null) {
            Text(
                if (profile.useHeritageAccent && brand.heritageAccent != null) "The HUD uses the ${brand.name} heritage colour."
                else if (isChromatic(hex)) "The HUD takes its accent from this colour."
                else "Black, white, grey and silver bikes use the ${brand.name} colour as the accent.",
                color = c.textMuted, style = LocalType.current.bodySm,
            )
        }
        if (brand.heritageAccent != null) {
            ToggleRow(
                "Use ${brand.name} heritage accent", profile.useHeritageAccent,
                sub = "Use the racing colour instead of the bike's paint",
            ) { v -> g.profile.update { it.copy(useHeritageAccent = v) } }
        }
    }
}

private val CUSTOM_PAINTS = listOf(
    Paint("Custom black", 0xFF101114), Paint("Custom white", 0xFFF2F2F2), Paint("Custom grey", 0xFF7A7D82),
    Paint("Custom red", 0xFFD0101E), Paint("Custom orange", 0xFFFF6A00), Paint("Custom yellow", 0xFFFFC300),
    Paint("Custom lime", 0xFF8CD600), Paint("Custom green", 0xFF15A34A), Paint("Custom teal", 0xFF00A99D),
    Paint("Custom cyan", 0xFF00B4E6), Paint("Custom blue", 0xFF1F5FD6), Paint("Custom purple", 0xFF7B3FE4),
    Paint("Custom pink", 0xFFE6337A), Paint("Custom gold", 0xFFC9A227),
)

/** Swatches that wrap onto more lines instead of scrolling sideways, so every one stays visible. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaintRow(paints: List<Paint>, selectedHex: Long?, selectedName: String?, onPick: (Paint) -> Unit) {
    val c = LocalHud.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        paints.forEach { p ->
            val sel = selectedHex == p.hex && selectedName == p.name
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable { onPick(p) }.padding(vertical = 4.dp),
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color(p.hex))
                        .border(if (sel) 3.dp else 1.dp, if (sel) c.text else c.glassBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sel) Icon(Icons.Filled.Check, null, tint = if (Color(p.hex).luminance() > 0.5f) Color.Black else Color.White)
                }
                Text(
                    p.name.removePrefix("Custom ").replaceFirstChar { it.uppercase() }, color = if (sel) c.text else c.textMuted,
                    style = LocalType.current.bodySm, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Mini HUD that re-themes instantly as the rider picks brand and colour. */
@Composable
private fun ThemePreview(modifier: Modifier) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val style = LocalHudStyle.current
    val profile by g.profile.profile.collectAsState()
    GlassCard(modifier, accentBorder = c.accent.copy(alpha = 0.6f), padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
      Box(Modifier.fillMaxSize()) {
        com.rodeo.hud.ui.components.ParticleField(Modifier.matchParentSize(), 70f)
        com.rodeo.hud.ui.components.BrandDecal(Modifier.matchParentSize(), alpha = 0.5f)
      Column(Modifier.fillMaxSize().padding(18.dp)) {
        SectionLabel("Live preview")
        Spacer(Modifier.height(6.dp))
        com.rodeo.hud.ui.components.BrandWordmark(big = false)
        Text(listOfNotNull(profile.model, profile.year?.toString(), profile.paintName).joinToString(" · ").ifBlank { "Choose your bike" }, color = c.text, style = LocalType.current.bodyLg)
        Spacer(Modifier.weight(1f))
        SpeedGauge(62f, Modifier.fillMaxWidth(), height = 130.dp)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("48.2 km", "41 avg", "1:12").forEach {
                Text(it, color = c.text, style = LocalType.current.body, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.tint.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.accent))
        if (style.tagline.isNotBlank()) Text(style.tagline, color = c.textMuted, style = LocalType.current.bodySm, modifier = Modifier.padding(top = 8.dp))
      }
      }
    }
}

@Suppress("unused")
@Composable
private fun EmptyList() = LazyColumn { items(emptyList<String>()) { } }
