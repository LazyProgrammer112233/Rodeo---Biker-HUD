package com.rodeo.hud.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioRoute(
    val btOn: Boolean = false,
    /** Name of the connected Bluetooth headset, intercom or speaker. */
    val btDevice: String? = null,
    /** The Bluetooth device also has a microphone the voice assistant can use. */
    val btMic: Boolean = false,
    val wired: Boolean = false,
    val wiredName: String? = null,
)

/**
 * Which headset the rider's audio is going to, straight from the audio system, so the HUD shows
 * the real intercom name (for example "SENA 50S") the moment it connects or drops.
 */
class AudioRoutes(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _route = MutableStateFlow(AudioRoute())
    val route: StateFlow<AudioRoute> = _route.asStateFlow()
    private var started = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            // The audio route can lag the adapter broadcast by a moment.
            refresh()
            handler.postDelayed({ refresh() }, 1_500)
        }
    }

    fun start() {
        if (started) return
        started = true
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        audio.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { context.unregisterReceiver(receiver) }
        handler.removeCallbacksAndMessages(null)
    }

    fun refresh() {
        val outs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val ins = runCatching { audio.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }.getOrDefault(emptyList())
        val bt = outs.filter { it.type in btTypes }.maxByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) 2 else 1 }
        val wired = outs.firstOrNull { it.type in wiredTypes }
        _route.value = AudioRoute(
            btOn = adapterOn(),
            btDevice = bt?.let { d -> d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "Headset" },
            btMic = ins.any { it.type in btTypes },
            wired = wired != null,
            wiredName = wired?.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun adapterOn(): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true
    }.getOrDefault(false)

    companion object {
        private val btTypes = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
        private val wiredTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
    }
}
