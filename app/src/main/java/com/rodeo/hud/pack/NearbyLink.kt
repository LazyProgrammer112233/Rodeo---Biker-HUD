package com.rodeo.hud.pack

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The pack without mobile signal: phones within Bluetooth / Wi-Fi range of each other link up
 * directly with Google Nearby Connections (it picks Bluetooth, BLE or Wi-Fi Direct by itself) and
 * swap positions. Typical reach between bikes is 50 to 150 m, more in the open; positions are
 * relayed up to two phones down the line, so a strung-out pack still hears its tail.
 *
 * Runs all the time while in a pack, so it is already linked when the signal drops in a ghat.
 */
class NearbyLink(private val context: Context, private val onPosition: (WirePosition, String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val client: ConnectionsClient? = runCatching { Nearby.getConnectionsClient(context) }.getOrNull()

    private val _status = MutableStateFlow(NearbyStatus.OFF)
    val status: StateFlow<NearbyStatus> = _status.asStateFlow()
    private val _peers = MutableStateFlow(0)
    val peers: StateFlow<Int> = _peers.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    private var code: String? = null
    private var myKey = ""
    private var running = false
    /** Endpoints seen nearby in this pack, with when they were first seen. */
    private val found = HashMap<String, Long>()
    private val pending = HashSet<String>()
    private val connected = LinkedHashSet<String>()
    /** Newest position per rider id that went through here, so relays don't loop. */
    private val newest = HashMap<String, Long>()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            // A phone that saw us but never called: call it ourselves after a while.
            val now = System.currentTimeMillis()
            found.forEach { (id, at) -> if (id !in connected && id !in pending && now - at > 8_000) request(id) }
            main.postDelayed(this, 5_000)
        }
    }

    /** Everything Nearby needs on this Android version is granted. */
    fun permitted(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun start(packCode: String, memberId: String) {
        if (running && code == packCode) return
        stop()
        val c = client
        if (c == null) {
            _status.value = NearbyStatus.ERROR
            _note.value = "Google Play services needed for the no-signal link"
            return
        }
        if (!permitted()) {
            _status.value = NearbyStatus.NO_PERMISSION
            _note.value = "Allow Nearby devices for the no-signal link"
            return
        }
        if (!bluetoothOn()) {
            _status.value = NearbyStatus.BLUETOOTH_OFF
            _note.value = "Turn on Bluetooth for the no-signal link"
            // Try again later: the rider may switch it on.
            code = packCode
            myKey = memberId.take(10)
            main.postDelayed({ if (code == packCode && !running) { code = null; start(packCode, memberId) } }, 15_000)
            return
        }
        code = packCode
        myKey = memberId.take(10)
        running = true
        _note.value = null
        _status.value = NearbyStatus.SEARCHING
        val strategy = Strategy.P2P_CLUSTER
        c.startAdvertising(endpointName(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { onError("advertise", it) }
        c.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { onError("discover", it) }
        main.postDelayed(tick, 5_000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        if (running) {
            client?.let { c ->
                runCatching { c.stopAdvertising() }
                runCatching { c.stopDiscovery() }
                runCatching { c.stopAllEndpoints() }
            }
        }
        running = false
        code = null
        found.clear()
        pending.clear()
        connected.clear()
        newest.clear()
        _peers.value = 0
        _status.value = NearbyStatus.OFF
        _note.value = null
    }

    /** Sends a position to every linked phone (except the one it came from, when relaying). */
    fun send(p: WirePosition, except: String? = null) {
        val c = client ?: return
        if (!running) return
        newest[p.id] = maxOf(newest[p.id] ?: 0L, p.ts)
        val to = connected.filter { it != except }
        if (to.isEmpty()) return
        c.sendPayload(to, Payload.fromBytes(p.encode().toByteArray(Charsets.UTF_8)))
            .addOnFailureListener { Log.d(TAG, "send failed: ${it.message}") }
    }

    private fun endpointName() = "$PREFIX|${code}|$myKey"

    /** Same pack? Returns the other phone's key, or null for a stranger (another pack, another app). */
    private fun keyOf(name: String): String? {
        val f = name.split('|')
        if (f.size != 3 || f[0] != PREFIX || f[1] != code) return null
        return f[2]
    }

    private fun request(endpointId: String) {
        val c = client ?: return
        if (!running || endpointId in pending || endpointId in connected) return
        pending += endpointId
        c.requestConnection(endpointName(), endpointId, lifecycle)
            .addOnFailureListener { e ->
                pending -= endpointId
                val status = (e as? ApiException)?.statusCode
                if (status == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) {
                    connected += endpointId
                    publish()
                }
            }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val key = keyOf(info.endpointName) ?: return
            found.putIfAbsent(endpointId, System.currentTimeMillis())
            // The phone with the smaller key calls, so two phones don't call each other at once.
            if (myKey < key) request(endpointId)
        }

        override fun onEndpointLost(endpointId: String) {
            found.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val c = client ?: return
            if (keyOf(info.endpointName) == null) {
                c.rejectConnection(endpointId)
                return
            }
            c.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending -= endpointId
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK, ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> connected += endpointId
                else -> Unit
            }
            publish()
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            pending -= endpointId
            publish()
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val p = WirePosition.decode(String(bytes, Charsets.UTF_8)) ?: return
            if (p.ts <= (newest[p.id] ?: 0L)) return
            newest[p.id] = p.ts
            onPosition(p, endpointId)
            // Pass it down the line so riders out of our partner's range still get it.
            if (p.hops < WirePosition.MAX_HOPS) send(p.copy(hops = p.hops + 1), except = endpointId)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private fun publish() {
        _peers.value = connected.size
        if (running) _status.value = if (connected.isEmpty()) NearbyStatus.SEARCHING else NearbyStatus.CONNECTED
    }

    private fun onError(what: String, e: Exception) {
        val status = (e as? ApiException)?.statusCode
        // Already advertising / discovering is fine (a quick leave and rejoin).
        if (status == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING || status == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) return
        Log.w(TAG, "$what failed: $status ${e.message}")
        _status.value = NearbyStatus.ERROR
        _note.value = when {
            // 8030 and up: one of the MISSING_PERMISSION_* codes.
            status != null && status in 8030..8060 -> "Allow Nearby devices for the no-signal link"
            status == ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth error: switch Bluetooth off and on"
            else -> "No-signal link unavailable ($status)"
        }
    }

    private fun bluetoothOn(): Boolean = runCatching {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    }.getOrDefault(true)

    companion object {
        private const val TAG = "NearbyLink"
        const val SERVICE_ID = "com.rodeo.hud.pack"
        private const val PREFIX = "R1"

        /** Runtime permissions Nearby Connections needs on this Android version. */
        fun requiredPermissions(): List<String> = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
}
