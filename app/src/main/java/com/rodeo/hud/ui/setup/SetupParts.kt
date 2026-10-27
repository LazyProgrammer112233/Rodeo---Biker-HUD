package com.rodeo.hud.ui.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rodeo.hud.data.SosContact
import com.rodeo.hud.sos.SmsStatus
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GhostButton
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.LocalType

private data class Perm(val title: String, val why: String, val perms: List<String>, val special: String? = null)

private val PERMS = listOf(
    Perm("Location", "Navigation, ride tracking and the SOS location.", listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Perm("Location all the time", "Keeps tracking when the screen is off mid-ride.", listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
    Perm("Notifications", "Ride-in-progress card and garage reminders.", listOf(Manifest.permission.POST_NOTIFICATIONS)),
    Perm("Bluetooth", "Shows your headset and handlebar remote.", listOf(Manifest.permission.BLUETOOTH_CONNECT)),
    Perm("SMS", "Sends SOS texts from your default SIM automatically, even with no data.", listOf(Manifest.permission.SEND_SMS)),
    Perm("Contacts", "Pick SOS contacts.", listOf(Manifest.permission.READ_CONTACTS)),
    Perm("Physical activity", "Auto-pause and crash detection.", listOf(Manifest.permission.ACTIVITY_RECOGNITION)),
    Perm("Camera", "Swipe gestures for music. Frames are read on the phone and never saved.", listOf(Manifest.permission.CAMERA)),
    Perm("Microphone", "Voice commands with gloves on.", listOf(Manifest.permission.RECORD_AUDIO)),
    Perm("Music access", "Show and control what's playing.", emptyList(), special = "media"),
)

private fun granted(ctx: Context, p: Perm): Boolean = when (p.special) {
    "media" -> ctx.graph.music.hasAccess()
    else -> p.perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}

/** Permission cards; each explains why, and denying any of them keeps the app usable. */
@Composable
fun PermissionsPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = LocalHud.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PERMS.forEach { p ->
            val ok = remember(tick) { granted(ctx, p) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.glass).border(1.dp, c.glassBorder, RoundedCornerShape(16.dp))
                    .clickable(enabled = !ok) {
                        when {
                            p.special == "media" -> ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            p.perms.contains(Manifest.permission.ACCESS_BACKGROUND_LOCATION) &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
                                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            else -> launcher.launch(p.perms.toTypedArray())
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, tint = if (ok) c.ok else c.textMuted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, color = c.text, fontWeight = FontWeight.SemiBold)
                    Text(p.why, color = c.textMuted, style = LocalType.current.body)
                }
                if (!ok) Text("Grant", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** SOS contacts: pick from the phone book or type a number; up to five. */
@Composable
fun SosContactsEditor(modifier: Modifier = Modifier, showTest: Boolean = false) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) readContact(ctx, uri)?.let { add(ctx, it) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        profile.sosContacts.forEach { contact ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.glass).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(contact.name, color = c.text, fontWeight = FontWeight.Medium)
                    Text(contact.phone, color = c.textMuted, style = LocalType.current.body)
                }
                Box(Modifier.size(48.dp).clickable { g.profile.update { p -> p.copy(sosContacts = p.sosContacts - contact) } }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Remove", tint = c.textMuted)
                }
            }
        }
        if (profile.sosContacts.size < 5) {
            GhostButton("Pick from contacts", Modifier.fillMaxWidth(), icon = Icons.Filled.PersonAdd) {
                picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(name, { name = it }, "Name", Modifier.weight(1f))
                Field(phone, { phone = it }, "Phone", Modifier.weight(1f), KeyboardType.Phone)
                Text(
                    "Add", color = if (phone.length >= 10) c.accent else c.textMuted, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = phone.length >= 10) {
                        add(ctx, SosContact(name.ifBlank { phone }, phone)); name = ""; phone = ""
                    }.padding(12.dp),
                )
            }
        }
        Text("SOS goes out as SMS from your default SIM, in the background. No internet or WhatsApp needed.", color = c.textMuted, style = LocalType.current.bodySm)
        if (showTest && profile.sosContacts.isNotEmpty()) {
            GhostButton("Send a test SMS to my contacts", Modifier.fillMaxWidth(), icon = Icons.Filled.Sms) { g.sos.sendTest() }
            SmsStatusList()
        }
    }
}

/** One line per contact: sending, sent, or why it failed. */
@Composable
fun SmsStatusList(modifier: Modifier = Modifier) {
    val g = LocalContext.current.graph
    val c = LocalHud.current
    val list by g.sos.sms.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.forEach { s ->
            val (label, color) = when (s.state) {
                SmsStatus.State.SENDING -> "sending…" to c.textMuted
                SmsStatus.State.SENT -> "sent" to c.ok
                SmsStatus.State.FAILED -> "failed: ${s.detail}" to c.danger
                SmsStatus.State.TEST -> s.detail to c.warning
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(8.dp))
                Text(s.contact.name, color = c.text, fontWeight = FontWeight.Medium, style = LocalType.current.bodyLg)
                Spacer(Modifier.width(8.dp))
                Text(label, color = color, style = LocalType.current.body)
            }
        }
    }
}

private fun add(ctx: Context, contact: SosContact) {
    ctx.graph.profile.update { p ->
        if (p.sosContacts.size >= 5 || p.sosContacts.any { it.phone == contact.phone }) p else p.copy(sosContacts = p.sosContacts + contact)
    }
}

private fun readContact(ctx: Context, uri: Uri): SosContact? = runCatching {
    ctx.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null,
    )?.use { cur ->
        if (cur.moveToFirst()) SosContact(cur.getString(0) ?: "Contact", cur.getString(1) ?: return@use null) else null
    }
}.getOrNull()

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text) {
    val c = LocalHud.current
    Box(
        modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.glass).border(1.dp, c.glassBorder, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = c.textMuted)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = LocalType.current.bodyLg.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    val c = LocalHud.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = c.text)
            if (sub != null) Text(sub, color = c.textMuted, style = LocalType.current.bodySm)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent),
        )
    }
}

@Suppress("unused")
@Composable
private fun Hint(text: String) = GlassCard { Text(text, color = LocalHud.current.textMuted) }
