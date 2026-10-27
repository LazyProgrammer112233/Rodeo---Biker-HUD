package com.rodeo.hud.ui.garage

import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rodeo.hud.data.BikeDocument
import com.rodeo.hud.data.DocType
import com.rodeo.hud.garage.ReminderWorker
import com.rodeo.hud.graph
import com.rodeo.hud.ui.components.GlassCard
import com.rodeo.hud.ui.components.SectionLabel
import com.rodeo.hud.ui.setup.Field
import com.rodeo.hud.ui.theme.LocalHud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Bike garage and document wallet: profile, odometer, service, papers with expiry reminders, emergency card. */
@Composable
fun GarageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val c = LocalHud.current
    val profile by g.profile.profile.collectAsState()
    val garage by g.garage.garage.collectAsState()
    val rides by g.rides.rides.collectAsState()
    val totalKm = rides.sumOf { it.distanceM } / 1000.0
    val odo = g.garage.odometerKm(totalKm)

    Row(Modifier.fillMaxSize().background(c.background).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
                }
                Text("Garage", color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            GlassCard(Modifier.fillMaxWidth(), accentBorder = c.accent.copy(alpha = 0.4f)) {
                Text(profile.bikeLabel.ifBlank { "Your bike" }, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(profile.paintName.orEmpty(), color = c.textMuted)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Registration")
                        Field(profile.regNumber, { v -> g.profile.update { it.copy(regNumber = v.uppercase()) } }, "KA 01 AB 1234")
                    }
                    Column(Modifier.weight(1f)) {
                        SectionLabel("Odometer (km)")
                        Field(
                            garage.baseOdometerKm.takeIf { it > 0 }?.toString() ?: "",
                            { v -> v.filter { it.isDigit() }.toIntOrNull()?.let { km -> g.garage.update { it.copy(baseOdometerKm = km, odometerSetAtRideKm = totalKm) } } },
                            "$odo", keyboard = KeyboardType.Number,
                        )
                    }
                }
                Text("Now $odo km · Rodeo adds every ride automatically", color = c.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                SectionLabel("Next service at (km)")
                Field(garage.nextServiceKm?.toString() ?: "", { v -> g.garage.update { it.copy(nextServiceKm = v.filter { ch -> ch.isDigit() }.toIntOrNull()) } }, "e.g. ${odo + 3000}", keyboard = KeyboardType.Number)
                garage.nextServiceKm?.let { s ->
                    val left = s - odo
                    Text(if (left > 0) "$left km to service" else "Service overdue by ${-left} km", color = if (left <= 200) c.warning else c.textMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
            GlassCard(Modifier.fillMaxWidth()) {
                SectionLabel("Emergency card (sent with SOS)")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(profile.bloodGroup, { v -> g.profile.update { it.copy(bloodGroup = v.uppercase()) } }, "Blood group", Modifier.weight(0.4f))
                    Field(profile.allergies, { v -> g.profile.update { it.copy(allergies = v) } }, "Allergies / conditions", Modifier.weight(1f))
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Document wallet", Modifier.padding(top = 16.dp))
            DocType.entries.forEach { t -> DocCard(garage.doc(t)) { d -> g.garage.updateDoc(d); ReminderWorker.runNow(ctx) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocCard(doc: BikeDocument, onChange: (BikeDocument) -> Unit) {
    val ctx = LocalContext.current
    val c = LocalHud.current
    var picking by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onChange(doc.copy(photoUri = uri.toString()))
        }
    }
    val days = doc.due?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
    val status = when {
        days == null -> "Not set" to c.textMuted
        days < 0 -> "Expired ${-days} days ago" to c.danger
        days <= 15 -> "Due in $days days" to c.warning
        else -> "Valid · ${days} days left" to c.ok
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(doc.type.label, color = c.text, fontWeight = FontWeight.SemiBold)
                Text(status.first, color = status.second, fontSize = 13.sp)
            }
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(c.glass).clickable { picking = true }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Event, null, tint = c.text, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(doc.due?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: if (doc.type == DocType.SERVICE) "Set date" else "Set expiry", color = c.text, fontSize = 14.sp)
            }
            Spacer(Modifier.width(8.dp))
            val thumb = rememberThumb(doc.photoUri)
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(c.glass).clickable {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) Image(thumb, doc.type.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.AddAPhoto, "Attach photo", tint = c.textMuted)
            }
        }
        if (doc.type != DocType.SERVICE) {
            Spacer(Modifier.height(6.dp))
            Field(doc.number, { onChange(doc.copy(number = it)) }, "Policy / certificate number")
        }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = doc.due?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { ms -> onChange(doc.copy(dueEpochDay = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay())) }
                    picking = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun rememberThumb(uri: String?): ImageBitmap? {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val src = ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
                ImageDecoder.decodeBitmap(src) { d, _, _ -> d.setTargetSize(96, 96) }.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

@Suppress("unused")
@Composable
private fun Unused() = LaunchedEffect(Unit) {}
