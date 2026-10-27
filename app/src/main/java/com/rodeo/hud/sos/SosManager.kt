package com.rodeo.hud.sos

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class SosReason { MANUAL, CRASH }

/** Where one contact's SMS is: the phone's radio reports back for every part. */
data class SmsStatus(val contact: SosContact, val state: State, val detail: String = "") {
    enum class State { SENDING, SENT, FAILED, TEST }
}

sealed interface SosState {
    data object Idle : SosState
    data class Countdown(val secondsLeft: Int, val total: Int, val reason: SosReason, val dryRun: Boolean) : SosState
    data class Active(
        val reason: SosReason,
        val dryRun: Boolean,
        val message: String,
        val startedAt: Long,
        val updates: Int,
    ) : SosState
}

/** What the SOS message needs to know about the ride right now. */
data class SosSnapshot(val lat: Double?, val lng: Double?, val speedKmh: Float, val heading: Float?)

/**
 * SOS by plain SMS from the phone's default SIM, sent in the background with no app to open.
 * No WhatsApp and no internet needed, so it works on weak highway signal.
 */
class SosManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val profile: () -> Profile,
    private val snapshot: () -> SosSnapshot,
) {
    private val _state = MutableStateFlow<SosState>(SosState.Idle)
    val state: StateFlow<SosState> = _state.asStateFlow()

    /** Per-contact delivery for the current SOS (or the last test SMS). */
    private val _sms = MutableStateFlow<List<SmsStatus>>(emptyList())
    val sms: StateFlow<List<SmsStatus>> = _sms.asStateFlow()

    private var job: Job? = null
    private var ringtone: android.media.Ringtone? = null
    private val requestCodes = AtomicInteger(1)
    /** Parts still outstanding per contact phone, and whether any part failed. */
    private val pending = mutableMapOf<String, Int>()
    private val failedReason = mutableMapOf<String, String>()

    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val phone = intent.getStringExtra(EXTRA_PHONE) ?: return
            if (resultCode != Activity.RESULT_OK) failedReason.putIfAbsent(phone, reason(resultCode))
            val left = (pending[phone] ?: 1) - 1
            pending[phone] = left
            if (left > 0) return
            val failure = failedReason.remove(phone)
            setStatus(phone, if (failure == null) SmsStatus.State.SENT else SmsStatus.State.FAILED, failure ?: "")
        }
    }

    init {
        ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    val canSendSms: Boolean
        get() = ContextCompat.checkSelfPermission(context, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY,
            )

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

    private suspend fun fire(reason: SosReason, dryRun: Boolean) {
        val p = profile()
        val msg = composeMessage(p, reason)
        _state.value = SosState.Active(reason, dryRun, msg, System.currentTimeMillis(), 0)
        if (dryRun) {
            _sms.value = p.sosContacts.map { SmsStatus(it, SmsStatus.State.TEST, "test, not sent") }
            return
        }
        sendToAll(p.sosContacts, msg)

        // Repeat the live location every 2 minutes until the rider stops the SOS.
        var updates = 0
        while (scope.isActive && _state.value is SosState.Active) {
            delay(120_000)
            val cur = _state.value as? SosState.Active ?: break
            val s = snapshot()
            val update = "Rodeo SOS update: " + (if (s.lat != null && s.lng != null) Geo.mapsLink(s.lat, s.lng) else "location unavailable") +
                " speed ${s.speedKmh.toInt()} km/h at ${time()}"
            sendToAll(p.sosContacts, update)
            updates++
            _state.value = cur.copy(updates = updates)
        }
    }

    /** Settings button: sends a real, clearly labelled test SMS so the rider knows SOS works. */
    fun sendTest() {
        val p = profile()
        val msg = "Rodeo test from ${p.riderName}: this is how an SOS will reach you. " +
            "No action needed. Sample: " + composeMessage(p, SosReason.MANUAL)
        sendToAll(p.sosContacts, msg)
    }

    private fun sendToAll(contacts: List<SosContact>, text: String) {
        _sms.value = contacts.map { SmsStatus(it, SmsStatus.State.SENDING) }
        contacts.forEach { send(it, text) }
    }

    private fun send(contact: SosContact, text: String) {
        val phone = normalise(contact.phone)
        if (!canSendSms) {
            setStatus(contact.phone, SmsStatus.State.FAILED, "SMS permission is off")
            return
        }
        runCatching {
            val sms = defaultSimSms()
            val parts = sms.divideMessage(text)
            pending[contact.phone] = parts.size
            failedReason.remove(contact.phone)
            val intents = ArrayList(parts.map {
                val i = Intent(ACTION_SENT).setPackage(context.packageName).putExtra(EXTRA_PHONE, contact.phone)
                PendingIntent.getBroadcast(context, requestCodes.getAndIncrement(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            })
            sms.sendMultipartTextMessage(phone, null, parts, intents, null)
        }.onFailure { setStatus(contact.phone, SmsStatus.State.FAILED, it.message ?: "could not send") }
    }

    /** The SIM the user picked for SMS in system settings (falls back to the only/first SIM). */
    private fun defaultSimSms(): SmsManager {
        val sub = SubscriptionManager.getDefaultSmsSubscriptionId()
        val base = context.getSystemService(SmsManager::class.java)
        return if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) base.createForSubscriptionId(sub)
            else @Suppress("DEPRECATION") SmsManager.getSmsManagerForSubscriptionId(sub)
        } else base ?: @Suppress("DEPRECATION") SmsManager.getDefault()
    }

    private fun setStatus(phone: String, state: SmsStatus.State, detail: String) {
        _sms.update { list -> list.map { if (it.contact.phone == phone) it.copy(state = state, detail = detail) else it } }
    }

    private fun reason(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile signal"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode or radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "message error"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "carrier rejected it (check SMS balance)"
        else -> "error $code"
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
            s.heading?.let { append(", heading ${it.toInt()} deg") }
            append(". ")
            if (p.bikeLabel.isNotBlank()) append("Bike: ${p.bikeLabel}${p.paintName?.let { " ($it)" } ?: ""}")
            if (p.regNumber.isNotBlank()) append(", reg ${p.regNumber}")
            append(". ")
            if (p.bloodGroup.isNotBlank()) append("Blood group ${p.bloodGroup}. ")
            if (p.allergies.isNotBlank()) append("Allergies: ${p.allergies}. ")
            append("Phone battery $battery%. Time ${time()}.")
        }
    }

    private fun normalise(phone: String): String {
        if (phone.trim().startsWith("+")) return "+" + phone.filter { it.isDigit() }
        val d = phone.filter { it.isDigit() }
        return when {
            d.length == 10 -> "+91$d" // Indian mobile without country code
            d.startsWith("0") && d.length == 11 -> "+91" + d.drop(1)
            d.startsWith("91") && d.length == 12 -> "+$d"
            else -> d
        }
    }

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

    companion object {
        private const val ACTION_SENT = "com.rodeo.hud.SOS_SMS_SENT"
        private const val EXTRA_PHONE = "phone"
    }
}
