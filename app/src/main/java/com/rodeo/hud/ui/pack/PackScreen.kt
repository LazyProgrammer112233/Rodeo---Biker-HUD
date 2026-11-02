package com.rodeo.hud.ui.pack

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import com.rodeo.hud.data.Geo
import com.rodeo.hud.graph
import com.rodeo.hud.pack.CloudStatus
import com.rodeo.hud.pack.Link
import com.rodeo.hud.pack.NearbyLink
import com.rodeo.hud.pack.NearbyStatus
import com.rodeo.hud.pack.PackLinkService
import com.rodeo.hud.pack.PackLogic
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.pack.PackState
import com.rodeo.hud.pack.PeerLink
import com.rodeo.hud.pack.VoiceMode
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.PrimaryButton
import com.rodeo.hud.ui.components.Radar
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.hud.riderColor
import com.rodeo.hud.ui.setup.Field
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType
import kotlinx.coroutines.delay

/** Pack mode: start or join a group ride by code or QR, see everyone live, talk on the voice channel. */
@Composable
fun PackScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val pack by g.pack.state.collectAsState()
    val loc by g.location.location.collectAsState()
    val hazards by g.hazards.hazards.collectAsState()
    var code by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    // Nearby (and notifications for the pack's status) are asked for when the rider joins a pack.
    val linkPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        g.pack.retryNearby()
        PackLinkService.start(ctx)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            g.pack.voiceOn()
            PackLinkService.start(ctx)
        }
    }
    fun askLinkPermissions() {
        val want = NearbyLink.requiredPermissions() + (if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        val missing = want.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) linkPermissions.launch(missing.toTypedArray())
    }
    fun afterJoin() {
        askLinkPermissions()
        PackLinkService.start(ctx)
    }
    LaunchedEffect(pack.code) {
        if (pack.active && !pack.demo) PackLinkService.start(ctx)
    }

    Row(Modifier.fillMaxSize().background(c.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(380.dp).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                }
                Text("Pack mode", color = c.text, style = LocalType.current.headlineXl, modifier = Modifier.weight(1f))
                if (pack.active) GhostButton("Leave", icon = Icons.AutoMirrored.Filled.ExitToApp) { g.pack.leave() }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                (pack.error ?: scanNote)?.let { ErrorNote(it) }
                if (!pack.active) {
                    Text(
                        "Ride together: everyone sees everyone on the map with distance and direction, the pack talks on a " +
                            "voice channel, and you're told when someone falls behind, stops or needs help. Works over mobile " +
                            "data at any distance, and phone-to-phone when there's no signal.",
                        color = c.textMuted,
                    )
                    if (!g.pack.online) {
                        Text("This build has no pack server, so packs work phone-to-phone only (about 100 m). Try the demo to see it.", color = c.warning, style = LocalType.current.bodySm)
                    }
                    PrimaryButton("Start a pack", Modifier.fillMaxWidth().testTag("pack-start"), icon = Icons.Filled.Add) {
                        g.pack.create()
                        afterJoin()
                    }
                    SectionLabel("Or join a friend's pack")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Field(code, { code = PackLogic.cleanCode(it).take(PackLogic.CODE_LENGTH) }, "Code", Modifier.weight(1f))
                        GhostButton("Join", icon = Icons.Filled.Groups) {
                            if (PackLogic.isValidCode(code)) {
                                g.pack.join(code)
                                afterJoin()
                            }
                        }
                    }
                    GhostButton("Scan their QR code", Modifier.fillMaxWidth(), icon = Icons.Filled.QrCodeScanner) {
                        scanNote = null
                        scanQr(ctx, onCode = { scanned ->
                            g.pack.join(scanned)
                            afterJoin()
                        }, onError = { scanNote = it })
                    }
                    GhostButton("Try a demo pack", Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow) { g.pack.demo() }
                } else {
                    CodeCard(pack, ctx)
                    LinksCard(pack, onFixNearby = { askLinkPermissions(); g.pack.retryNearby() })
                    VoiceCard(
                        pack,
                        onJoin = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                g.pack.voiceOn()
                                PackLinkService.start(ctx)
                            } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onLeave = { g.pack.voiceOff() },
                        onMode = { g.pack.voice.setMode(it) },
                        onMute = { g.pack.voice.setMuted(!pack.voice.muted) },
                    )
                    GapCard(pack.gapM) { g.pack.setGap(it) }
                    SectionLabel("Riders (${pack.members.size})")
                    for (m in pack.members) MemberRow(m, loc?.let { Geo.distanceM(it.latitude, it.longitude, m.lat, m.lng) }, now, pack.voice.peers[m.id])
                    if (pack.members.none { !it.isMe }) {
                        Text("Waiting for riders. Share the code or let them scan the QR.", color = c.textMuted, style = LocalType.current.bodySm)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp)).background(c.surface)) {
            Radar(Modifier.fillMaxSize(), loc?.latitude, loc?.longitude, loc?.bearing ?: 0f, pack.members, hazards, rangeM = 3000.0)
        }
    }
}

@Composable
private fun ErrorNote(text: String) {
    val c = LocalHud.current
    Text(
        text, color = c.text, style = LocalType.current.bodySm,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warning.copy(alpha = 0.22f))
            .border(1.dp, c.warning, RoundedCornerShape(12.dp)).padding(10.dp).testTag("pack-error"),
    )
}

@Composable
private fun CodeCard(pack: PackState, ctx: Context) {
    val c = LocalHud.current
    val code = pack.code.orEmpty()
    GlassCard(Modifier.fillMaxWidth(), accentBorder = c.accent, padding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel(if (pack.demo) "Demo pack" else "Pack code · share it")
                Text(code, color = c.text, style = LocalType.current.headlineXl.copy(letterSpacing = 4.sp), maxLines = 1, modifier = Modifier.testTag("pack-code"))
                Text("Spread %.1f km".format(pack.spreadKm), color = c.accent, style = LocalType.current.label)
                if (!pack.demo) {
                    Spacer(Modifier.height(6.dp))
                    GhostButton("Share", icon = Icons.Filled.Share) { share(ctx, code) }
                }
            }
            if (!pack.demo && PackLogic.isValidCode(code)) QrCode(PackLogic.joinLink(code), 112.dp)
        }
    }
}

/** The pack's QR code, drawn module by module (black on white, with the quiet zone scanners need). */
@Composable
fun QrCode(text: String, size: Dp) {
    val matrix = remember(text) { runCatching { Encoder.encode(text, ErrorCorrectionLevel.M).matrix }.getOrNull() } ?: return
    Canvas(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.White).testTag("pack-qr")) {
        val quiet = 2
        val n = matrix.width + quiet * 2
        val cell = this.size.minDimension / n
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y).toInt() == 1) {
                drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

@Composable
private fun LinksCard(pack: PackState, onFixNearby: () -> Unit) {
    val c = LocalHud.current
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        SectionLabel("Connection")
        val (cloudText, cloudOk) = if (pack.demo) "Mobile data · off in the demo" to false else when (pack.cloud) {
            CloudStatus.ONLINE -> "Mobile data · live" to true
            CloudStatus.OFFLINE -> "Mobile data · no signal right now" to false
            CloudStatus.SIGNING_IN -> "Mobile data · connecting…" to false
            CloudStatus.ERROR -> "Mobile data · can't reach the pack server" to false
            CloudStatus.NOT_SET_UP -> "Mobile data · not set up in this build" to false
        }
        StatusLine(if (cloudOk) Icons.Filled.Cloud else Icons.Filled.CloudOff, cloudText, if (cloudOk) c.ok else c.warning)
        val (nearText, nearOk) = when (pack.nearby) {
            NearbyStatus.CONNECTED -> "No-signal link · ${pack.nearbyPeers} ${if (pack.nearbyPeers == 1) "phone" else "phones"} linked" to true
            NearbyStatus.SEARCHING -> "No-signal link · looking for riders nearby" to true
            NearbyStatus.OFF -> (if (pack.demo) "No-signal link · off in the demo" else "No-signal link · off") to false
            NearbyStatus.NO_PERMISSION -> "No-signal link · needs Nearby devices permission" to false
            NearbyStatus.BLUETOOTH_OFF -> "No-signal link · turn on Bluetooth" to false
            NearbyStatus.ERROR -> (pack.nearbyNote ?: "No-signal link · unavailable") to false
        }
        StatusLine(Icons.Filled.Bluetooth, nearText, if (nearOk) c.ok else c.warning)
        if (pack.nearby == NearbyStatus.NO_PERMISSION || pack.nearby == NearbyStatus.ERROR || pack.nearby == NearbyStatus.BLUETOOTH_OFF) {
            GhostButton("Fix", icon = Icons.Filled.Bluetooth) { onFixNearby() }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    val c = LocalHud.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.text, style = LocalType.current.bodySm, maxLines = 2)
    }
}

@Composable
private fun VoiceCard(pack: PackState, onJoin: () -> Unit, onLeave: () -> Unit, onMode: (VoiceMode) -> Unit, onMute: () -> Unit) {
    val c = LocalHud.current
    val v = pack.voice
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        SectionLabel("Voice channel")
        if (!v.wanted) {
            Text(
                "Talk to the pack through your helmet intercom. Free and peer to peer, up to 6 riders. " +
                    "Uses about 10 MB of data an hour. The wake phrase rests while you're on it; tap Voice to talk to Rodeo.",
                color = c.textMuted, style = LocalType.current.bodySm,
            )
            Spacer(Modifier.height(6.dp))
            PrimaryButton("Join voice", Modifier.fillMaxWidth().testTag("pack-voice-join"), icon = Icons.Filled.Headset, enabled = !pack.demo) { onJoin() }
        } else {
            val connected = v.peers.count { it.value == PeerLink.CONNECTED }
            StatusLine(
                if (v.sending) Icons.Filled.Mic else Icons.Filled.MicOff,
                when {
                    v.paused -> "Paused while Rodeo listens"
                    !v.live -> "Joining…"
                    v.peers.isEmpty() -> "On · waiting for others to join voice"
                    else -> "On · $connected of ${v.peers.size} linked" + (v.output?.let { " · $it" } ?: "")
                },
                if (v.live && !v.paused) c.ok else c.warning,
            )
            if (v.peers.any { it.value == PeerLink.FAILED }) {
                Text(
                    "Some riders can't be reached by voice: their mobile network blocks direct calls. Positions still work.",
                    color = c.warning, style = LocalType.current.bodySm,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Open mic", v.mode == VoiceMode.OPEN) { onMode(VoiceMode.OPEN) }
                Chip("Push to talk", v.mode == VoiceMode.PUSH_TO_TALK) { onMode(VoiceMode.PUSH_TO_TALK) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (v.mode == VoiceMode.OPEN) GhostButton(if (v.muted) "Unmute" else "Mute", icon = if (v.muted) Icons.Filled.Mic else Icons.Filled.MicOff) { onMute() }
                GhostButton("Leave voice", icon = Icons.AutoMirrored.Filled.ExitToApp) { onLeave() }
            }
        }
        v.error?.let { Text(it, color = c.warning, style = LocalType.current.bodySm) }
    }
}

@Composable
private fun GapCard(gapM: Int, onGap: (Int) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = PaddingValues(12.dp)) {
        SectionLabel("Tell me when a rider is more than")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in listOf(500, 1_000, 2_000, 5_000)) Chip(PackLogic.distanceText(m.toDouble()), gapM == m) { onGap(m) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalHud.current
    Text(
        text, color = if (selected) c.onAccent else c.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else c.surfaceHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun MemberRow(m: PackMember, distanceM: Double?, now: Long, voice: PeerLink?) {
    val c = LocalHud.current
    val stopped = m.stopped(now)
    val stale = !m.isMe && m.stale(now)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.glass).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(if (m.sos) c.danger else if (stale) c.textSubtle else riderColor(m)), contentAlignment = Alignment.Center) {
            Text(PackLogic.initials(m.name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                m.name + (if (m.isMe) " (you)" else "") + (if (m.isLead) " · lead" else ""),
                color = c.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val status = when {
                m.sos -> "SOS"
                stale -> "No contact for ${(m.ageMs(now) / 60_000).coerceAtLeast(1)} min"
                stopped -> "Stopped"
                else -> "${m.speedKmh.toInt()} km/h"
            }
            val via = when (m.link) {
                Link.NEARBY -> " · direct link"
                Link.CLOUD -> ""
                else -> ""
            }
            Text(
                status + (distanceM?.takeIf { !m.isMe }?.let { " · " + PackLogic.distanceText(it) + " away" } ?: "") + via,
                color = if (m.sos) c.danger else if (stopped || stale) c.warning else c.textMuted, style = LocalType.current.bodySm, maxLines = 1,
            )
        }
        when (voice) {
            PeerLink.CONNECTED -> Icon(Icons.Filled.Mic, "In voice", tint = c.ok, modifier = Modifier.size(18.dp))
            PeerLink.FAILED -> Icon(Icons.Filled.MicOff, "Voice can't connect", tint = c.warning, modifier = Modifier.size(18.dp))
            PeerLink.CONNECTING, PeerLink.RETRYING -> Icon(Icons.Filled.Mic, "Connecting voice", tint = c.textSubtle, modifier = Modifier.size(18.dp))
            null -> Unit
        }
    }
}

private fun share(ctx: Context, code: String) {
    val text = "Ride with me on Rodeo! Open Rodeo › Pack › Join and enter $code, or scan the QR on my screen."
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share pack code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Google's code scanner: no camera permission, the scanning screen comes from Play services. */
private fun scanQr(ctx: Context, onCode: (String) -> Unit, onError: (String) -> Unit) {
    runCatching {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, options).startScan()
            .addOnSuccessListener { b ->
                val code = PackLogic.codeFrom(b.rawValue)
                if (code != null) onCode(code) else onError("That QR code isn't a Rodeo pack")
            }
            .addOnFailureListener { onError("Scanner not ready yet. Type the code instead, or try again in a minute.") }
    }.onFailure { onError("Scanner unavailable on this phone. Type the code instead.") }
}
