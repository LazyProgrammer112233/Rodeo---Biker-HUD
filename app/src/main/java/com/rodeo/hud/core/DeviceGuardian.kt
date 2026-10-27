package com.rodeo.hud.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceHealth(
    val batteryPct: Int = 100,
    val charging: Boolean = false,
    val batteryTempC: Float = 30f,
    /** PowerManager thermal status (0 = none .. 6 = shutdown). */
    val thermalStatus: Int = 0,
    /** Battery drain in %/hour over the last readings; null until known. */
    val drainPerHour: Float? = null,
    /** True when the HUD should run lean: hot phone or low battery. */
    val lowPower: Boolean = false,
    val reason: String = "",
)

/**
 * Thermal and battery guardian: drops the HUD to a low-power mode before Android
 * throttles or kills navigation on long, hot rides.
 */
class DeviceGuardian(private val context: Context, private val warnings: WarningCenter) {
    private val _health = MutableStateFlow(DeviceHealth())
    val health: StateFlow<DeviceHealth> = _health.asStateFlow()
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** Rider override from the HUD ("keep full power" / "save power"). */
    var forceLowPower: Boolean? = null
        set(value) { field = value; recompute() }

    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) level * 100 / scale else _health.value.batteryPct
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) / 10f
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val now = System.currentTimeMillis()
            if (samples.isEmpty() || samples.last().second != pct) samples.addLast(now to pct)
            while (samples.size > 2 && now - samples.first().first > 30 * 60_000) samples.removeFirst()
            _health.value = _health.value.copy(batteryPct = pct, batteryTempC = temp, charging = charging, drainPerHour = drain(charging))
            recompute()
        }
    }

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _health.value = _health.value.copy(thermalStatus = status)
        recompute()
    }

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        runCatching { pm.addThermalStatusListener(thermalListener) }
        _health.value = _health.value.copy(thermalStatus = runCatching { pm.currentThermalStatus }.getOrDefault(0))
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { pm.removeThermalStatusListener(thermalListener) }
    }

    private fun drain(charging: Boolean): Float? {
        if (charging || samples.size < 2) return null
        val (t0, p0) = samples.first()
        val (t1, p1) = samples.last()
        val hours = (t1 - t0) / 3_600_000f
        if (hours < 0.05f) return null
        return ((p0 - p1) / hours).coerceAtLeast(0f)
    }

    private fun recompute() {
        val h = _health.value
        val hot = h.thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || h.batteryTempC >= HOT_C
        val low = !h.charging && h.batteryPct <= 15
        val auto = hot || low
        val lowPower = forceLowPower ?: auto
        val reason = when {
            hot -> "Phone hot (${h.batteryTempC.toInt()}°C)"
            low -> "Battery ${h.batteryPct}%"
            else -> ""
        }
        _health.value = h.copy(lowPower = lowPower, reason = reason)

        if (hot) warnings.post(Warning(KEY_HOT, "Phone is hot", "Low-power HUD on. Shade the phone or lower brightness.", if (h.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) Severity.DANGER else Severity.WARN))
        else warnings.clear(KEY_HOT)

        when {
            !h.charging && h.batteryPct <= 10 -> warnings.post(Warning(KEY_BATT, "Battery ${h.batteryPct}%", "Plug in now. SOS still works by SMS.", Severity.DANGER))
            !h.charging && h.batteryPct <= 20 -> warnings.post(Warning(KEY_BATT, "Battery ${h.batteryPct}%", h.drainPerHour?.let { "Draining ${it.toInt()}% per hour" } ?: "Consider charging", Severity.WARN))
            else -> warnings.clear(KEY_BATT)
        }
    }

    companion object {
        const val HOT_C = 42f
        const val KEY_HOT = "thermal"
        const val KEY_BATT = "battery"
    }
}
