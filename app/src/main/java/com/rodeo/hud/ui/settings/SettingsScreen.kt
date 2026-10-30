package com.rodeo.hud.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.BuildConfig
import com.rodeo.hud.core.TapTemplates
import com.rodeo.hud.data.ListenMode
import com.rodeo.hud.data.ThemeMode
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.setup.Field
import com.rodeo.hud.ui.setup.PermissionsPanel
import com.rodeo.hud.ui.setup.SosContactsEditor
import com.rodeo.hud.ui.setup.ToggleRow
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import com.rodeo.hud.voice.SpeechEngine

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onChangeBike: () -> Unit, onTapSetup: () -> Unit, onSimulateCrash: () -> Unit, onDropTest: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val loc by g.location.location.collectAsState()
    val lux by g.ambient.lux.collectAsState()
    val crashTest by g.crash.status.collectAsState()
    val voice by g.assistant.ui.collectAsState()
    val navState by g.nav.state.collectAsState()
    val level by g.assistant.level.collectAsState()
    // Leaving Settings mid-training must not leave the microphone on.
    DisposableEffect(Unit) { onDispose { g.assistant.cancelTraining() } }

    Row(Modifier.fillMaxSize().background(c.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                }
                Text("Settings", color = c.text, style = LocalType.current.headlineXl)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Bike and look")
                Text(profile.bikeLabel + (profile.paintName?.let { " · $it" } ?: ""), color = c.text, modifier = Modifier.padding(vertical = 6.dp))
                GhostButton("Change bike", icon = Icons.Filled.TwoWheeler) { onChangeBike() }
                Spacer(Modifier.height(8.dp))
                Text("Theme", color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    ThemeMode.entries.forEach { m ->
                        Choice(
                            when (m) { ThemeMode.AUTO -> "Auto (sun + tunnels)"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" },
                            profile.themeMode == m,
                        ) { g.profile.update { it.copy(themeMode = m) } }
                    }
                }
                Text(
                    if (g.ambient.hasLightSensor) "Light sensor: ${lux?.toInt() ?: "–"} lux" else "No light sensor: theme follows sunrise and sunset only",
                    color = c.textMuted, style = LocalType.current.bodySm,
                )
                ToggleRow("Map on the right", profile.mapOnRight, sub = "Turn off to mirror the HUD") { v -> g.profile.update { it.copy(mapOnRight = v) } }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Safety")
                ToggleRow("Crash detection", profile.crashDetection, sub = "Hard impact, then the bike stops and tips over: 30 s alarm countdown, then SOS by SMS") { v ->
                    g.profile.update { it.copy(crashDetection = v) }
                    g.ride.setCrashDetection(v)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Drop test", icon = Icons.Filled.Sensors) { onDropTest() }
                    GhostButton("Simulate crash", icon = Icons.Filled.Warning) { onSimulateCrash() }
                }
                Text(
                    crashTest.text.ifBlank { "Drop test runs the real sensor check: drop the phone onto a bed and leave it still. Both tests send nothing." },
                    color = if (crashTest.text.isBlank()) c.textMuted else c.text, style = LocalType.current.body, modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(8.dp))
                SectionLabel("SOS contacts")
                Spacer(Modifier.height(6.dp))
                SosContactsEditor(showTest = true)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Voice assistant")
                Text("Wake phrase", color = c.text, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                var phrase by remember { mutableStateOf(profile.wakePhrase) }
                Field(phrase, { v ->
                    phrase = v.take(32)
                    // Old training belongs to the old phrase.
                    if (phrase.isNotBlank()) g.profile.update { it.copy(wakePhrase = phrase.trim(), wakeAliases = emptyList()) }
                }, "Hey Rodeo", Modifier.fillMaxWidth())
                Text(
                    "Say it, then the command: \"${g.assistant.wakePhraseHint(profile)}, emergency\" starts SOS, \"${g.assistant.wakePhraseHint(profile)}, next song\". " +
                        "Two words work best, like \"Hey Doku\". Train it once so Rodeo learns your voice.",
                    color = c.textMuted, style = LocalType.current.bodySm, modifier = Modifier.padding(top = 6.dp),
                )
                val t = voice.training
                if (t != null && !t.done) {
                    val n = t.heard.size + 1
                    Text(
                        (if (t.testOnly) "Test: say \"" else "Say \"") + g.assistant.wakePhraseHint(profile) + "\" now (${n.coerceAtMost(3)} of 3)",
                        color = c.accent, style = LocalType.current.headlineSm, modifier = Modifier.padding(top = 10.dp),
                    )
                    Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.glass)) {
                        Box(Modifier.fillMaxWidth(level.coerceIn(0.02f, 1f)).fillMaxHeight().background(c.accent))
                    }
                    Text(voice.heard.ifBlank { "Listening…" }.let { "Heard: $it" }, color = c.text, style = LocalType.current.body)
                    GhostButton("Cancel", icon = Icons.Filled.Close) { g.assistant.cancelTraining() }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        GhostButton("Train my voice", icon = Icons.Filled.Mic) { g.assistant.startTraining(testOnly = false) }
                        GhostButton("Test it", icon = Icons.Filled.Hearing) { g.assistant.startTraining(testOnly = true) }
                    }
                    if (t != null && t.done) Text(
                        if (t.testOnly) "Recognised ${t.matched} of ${t.heard.size}" + if (t.heard.isNotEmpty() && t.matched < t.heard.size) ". Train it to fix the misses." else ""
                        else if (t.heard.isEmpty()) "Didn't hear anything. Check the microphone permission and try again."
                        else "Learned how you say it: " + t.heard.joinToString(" · ") { "\"$it\"" },
                        color = c.text, style = LocalType.current.body, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    when {
                        !g.assistant.micAllowed -> "Needs microphone permission (see Permissions)"
                        voice.model == SpeechEngine.ModelState.MISSING -> "Offline speech model is missing from this build; using Google voice typing when you tap the mic"
                        voice.model == SpeechEngine.ModelState.LOADING -> "Preparing offline speech (first launch only)…"
                        voice.model == SpeechEngine.ModelState.FAILED -> "Offline speech failed to load; using Google voice typing when you tap the mic"
                        voice.listening -> "Listening for \"${g.assistant.wakePhraseHint(profile)}\" · works offline"
                        else -> "Ready · works offline"
                    } + if (profile.wakeAliases.isNotEmpty()) " · trained" else "",
                    color = c.textMuted, style = LocalType.current.bodySm, modifier = Modifier.padding(top = 8.dp),
                )
                Text("Listen for the wake phrase", color = c.text, modifier = Modifier.padding(top = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    ListenMode.entries.forEach { m ->
                        Choice(
                            when (m) { ListenMode.OFF -> "Off (tap the mic)"; ListenMode.RIDING -> "On the HUD screen"; ListenMode.ALWAYS -> "HUD and background rides" },
                            profile.listenMode == m,
                        ) { g.profile.update { it.copy(listenMode = m) } }
                    }
                }
                ToggleRow("\"Emergency, emergency\" without the wake phrase", profile.sosWithoutWake, sub = "Saying a strong SOS word twice starts the SOS countdown even if the wake phrase was missed") { v ->
                    g.profile.update { it.copy(sosWithoutWake = v) }
                }
                ToggleRow("Use the helmet headset microphone", profile.helmetMic, sub = "Experimental. Hears you better at speed through a Bluetooth helmet headset; music drops to call quality while it listens") { v ->
                    g.profile.update { it.copy(helmetMic = v) }
                }
                Text(
                    "Helmet intercom assistant button: in Android Settings › Apps › Default apps › Digital assistant app, choose Rodeo. The button then opens Rodeo's assistant. Music pauses while it listens and comes back after.",
                    color = c.textMuted, style = LocalType.current.bodySm, modifier = Modifier.padding(top = 4.dp),
                )
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Controls")
                val templates = remember(profile.tapTemplates) { TapTemplates.fromJson(profile.tapTemplates) }
                ToggleRow(
                    "Tap the phone's top edge", profile.tapControl,
                    sub = "Knock on the edge facing up: left end = previous song · middle = play / pause · double-tap the right end = next. Works with gloves, in the dark and in glare.",
                ) { v -> g.profile.update { it.copy(tapControl = v) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton(if (templates?.ready == true) "Redo tap setup" else "Set up taps on my mount", icon = Icons.Filled.TouchApp) { onTapSetup() }
                    Text(
                        when {
                            !g.taps.available -> "This phone has no motion sensor"
                            templates?.ready == true -> "Learned from ${templates.samples.values.sumOf { it.size }} of your taps"
                            else -> "Not set up yet: about one minute, on the bike's mount"
                        },
                        color = if (templates?.ready == true) c.textMuted else c.warning, style = LocalType.current.bodySm, modifier = Modifier.weight(1f),
                    )
                }
                ToggleRow(
                    "Volume keys skip songs", profile.volumeKeyMusic,
                    sub = "On the HUD: the volume key on your left = previous, on your right = next, hold either = play / pause. Change the volume by voice or on the music card.",
                ) { v -> g.profile.update { it.copy(volumeKeyMusic = v) } }
                ToggleRow("Wave over the phone", profile.waveControl, sub = if (g.waves.available)
                    "Wave over the top of the screen (the earpiece sensor): 1 wave = pause / play · 2 quick waves = next · 3 = previous"
                else "This phone has no proximity sensor") { v -> g.profile.update { it.copy(waveControl = v) } }
                Text("Bluetooth remotes: media keys control music; arrow keys and Enter move between HUD buttons.", color = c.textMuted, style = LocalType.current.bodySm)
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Home")
                Text(if (profile.homeLat != null) profile.homeLabel.ifBlank { "Home saved" } else "Not set", color = c.text, modifier = Modifier.padding(vertical = 6.dp))
                GhostButton("Use my current location as home", icon = Icons.Filled.Home) {
                    loc?.let { l -> g.profile.update { it.copy(homeLat = l.latitude, homeLng = l.longitude, homeLabel = "Home") } }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Permissions", Modifier.padding(top = 18.dp))
            PermissionsPanel()
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Navigation")
                ToggleRow("Avoid tolls", profile.avoidTolls) { v -> g.profile.update { it.copy(avoidTolls = v) } }
                ToggleRow("Avoid highways", profile.avoidHighways, sub = "Prefer city and state roads") { v -> g.profile.update { it.copy(avoidHighways = v) } }
                ToggleRow("Spoken turn-by-turn", navState.voiceOn, sub = "Turn prompts lower the music for a moment instead of stopping it") { v -> if (v != navState.voiceOn) g.nav.toggleVoice() }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Services")
                Text("Map and turn-by-turn: OpenStreetMap data, free routing servers (Valhalla, OSRM) · no keys, no fees", color = c.text, style = LocalType.current.body)
                Text("Voice assistant: on the phone, works offline · speech model Kroko ASR by Banafo (CC BY-SA 4.0) on sherpa-onnx (Apache 2.0)", color = c.text, style = LocalType.current.body)
                Text("SOS: SMS from the default SIM" + if (g.sos.canSendSms) "" else " · SMS permission off", color = c.text, style = LocalType.current.body)
                Text("Pack mode and pothole sharing: " + if (g.pack.online) "online (Firebase)" else "demo / on-device only", color = c.text, style = LocalType.current.body)
                Text("Weather: Open-Meteo", color = c.text, style = LocalType.current.body)
                Text("Version ${BuildConfig.VERSION_NAME}", color = c.textMuted, style = LocalType.current.bodySm)
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalHud.current
    Text(
        label,
        color = if (selected) c.onAccent else c.text,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) c.accent else c.glass)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
