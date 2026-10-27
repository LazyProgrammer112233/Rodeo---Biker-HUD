package com.rodeo.hud.sos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.rodeo.hud.data.Geo
import com.rodeo.hud.data.Profile
import com.rodeo.hud.data.SosContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SosReason { MANUAL, CRASH }

sealed interface SosState {
    data object Idle : SosState
    data class Countdown(val secondsLeft: Int, val total: Int, val reason: SosReason, val dryRun: Boolean) : SosState
    data class Active(
        val reason: SosReason,
        val dryRun: Boolean,
        val message: String,
        val smsSentTo: List<String>,
        val smsFailed: List<String>,
        /** Contacts still to be messaged on WhatsApp (one tap each). */
        val whatsAppPending: List<SosContact>,
        val startedAt: Long,
        val updates: Int,
    ) : SosState
}

/** What the SOS message needs to know about the ride right now. */
data class SosSnapshot(val lat: Double?, val lng: Double?, val speedKmh: Float, val heading: Float?)

class SosManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val profile: () -> Profile,
    private val snapshot: () -> SosSnapshot,
) {
    private val _state = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = _state.asStateFlow()
    private var job: Job? = null
    private var ringtone: android.media.Ringtone? = null

    fun startCountdown(reason: SosReason, dryRun: Boolean = false) {
        if (_state.value !is SosState.Idle) return
        val total = if (reason == SosReason.CRASH) 30 else 10
        job?.cancel()
        job = scope.launch {
            if (reason == SosReason.CRASH) startAlarm()
            for (s in total downTo 1) {
                _state.value = SosState.Countdown(s, total, reason, dryRun)
                buzz(if (reason == SosReason.CRASH) 400 else 120)
                delay(1000)
            }
            stopAlarm()
            fire(reason, dryRun)
        }
    }

    fun cancel() {
        job?.cancel()
        stopAlarm()
        _state.value = SosState.Idle
    }

    /** Sends immediately (used by voice command "SOS" after confirmation, and by the countdown). */
    private suspend fun fire(reason: SosReason, dryRun: Boolean) {
        val p = profile()
        val msg = composeMessage(p, reason)
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        if (p.sosSms && !dryRun) {
            p.sosContacts.forEach { c -> if (sendSms(c.phone, msg)) sent += c.name else failed += c.name }
        } else if (dryRun) {
            p.sosContacts.forEach { sent += "${it.name} (test, not sent)" }
        }
        val pending = if (p.sosWhatsApp && !dryRun) p.sosContacts else emptyList()
        val started = System.currentTimeMillis()
        _state.value = SosState.Active(reason, dryRun, msg, sent, failed, pending, started, 0)

        // Repeat location by SMS every 2 minutes until cancelled.
        if (!dryRun && p.sosSms) {
            var updates = 0
            while (scope.isActive && _state.value is SosState.Active) {
                delay(120_000)
                val cur = _state.value as? SosState.Active ?: break
                val s = snapshot()
                val update = "Rodeo SOS update: " + (if (s.lat != null && s.lng != null) Geo.mapsLink(s.lat, s.lng) else "location unavailable") +
                    " speed ${s.speedKmh.toInt()} km/h at ${time()}"
                p.sosContacts.forEach { sendSms(it.phone, update) }
                updates++
                _state.value = cur.copy(updates = updates)
            }
        }
    }

    /** Opens WhatsApp for the next pending contact with the message pre-filled. */
    fun openNextWhatsApp(): Boolean {
        val cur = _state.value as? SosState.Active ?: return false
        val c = cur.whatsAppPending.firstOrNull() ?: return false
        val ok = openWhatsApp(c.phone, cur.message)
        _state.value = cur.copy(whatsAppPending = cur.whatsAppPending.drop(1))
        return ok
    }

    fun openWhatsApp(phone: String, text: String): Boolean {
        val digits = normalise(phone)
        val uri = Uri.parse("https://wa.me/$digits?text=" + URLEncoder.encode(text, "UTF-8"))
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(it) }?.let { intent.setPackage(it) }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    fun composeMessage(p: Profile, reason: SosReason): String {
        val s = snapshot()
        val where = if (s.lat != null && s.lng != null) Geo.mapsLink(s.lat, s.lng) else "location unavailable"
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return buildString {
            append(if (reason == SosReason.CRASH) "RODEO SOS: Possible crash detected. " else "RODEO SOS: ${p.riderName} needs help. ")
            append("Location: $where. ")
            append("Speed ${s.speedKmh.toInt()} km/h")
            s.heading?.let { append(", heading ${it.toInt()}°") }
            append(". ")
            if (p.bikeLabel.isNotBlank()) append("Bike: ${p.bikeLabel}${p.paintName?.let { " ($it)" } ?: ""}")
            if (p.regNumber.isNotBlank()) append(", reg ${p.regNumber}")
            append(". ")
            if (p.bloodGroup.isNotBlank()) append("Blood group ${p.bloodGroup}. ")
            if (p.allergies.isNotBlank()) append("Allergies: ${p.allergies}. ")
            append("Phone battery $battery%. Time ${time()}.")
        }
    }

    private fun sendSms(phone: String, text: String): Boolean {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val sms = context.getSystemService(SmsManager::class.java) ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(phone, null, parts, null, null)
            true
        }.getOrDefault(false)
    }

    private fun normalise(phone: String): String {
        val d = phone.filter { it.isDigit() }
        return when {
            d.length == 10 -> "91$d" // Indian mobile without country code
            d.startsWith("0") && d.length == 11 -> "91" + d.drop(1)
            else -> d
        }
    }

    private fun isInstalled(pkg: String) = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    private fun time() = SimpleDateFormat("HH:mm, d MMM", Locale.getDefault()).format(Date())

    private fun buzz(ms: Long) {
        val v = context.getSystemService(Vibrator::class.java) ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun startAlarm() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true
                play()
            }
        }
    }

    private fun stopAlarm() {
        runCatching { ringtone?.stop() }
        ringtone = null
    }
}
