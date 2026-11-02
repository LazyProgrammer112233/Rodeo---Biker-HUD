package com.rodeo.hud.pack

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** How the voice channel finds the other riders and swaps connection details (through the pack's cloud). */
interface VoiceSignaling {
    val myId: String
    /** Announce (or withdraw) this rider in the pack's voice room. */
    fun setVoicePresence(on: Boolean)
    /** Ids of everyone in the voice room, including this rider. */
    val voiceRoster: StateFlow<Set<String>>
    fun sendSignal(to: String, msg: Map<String, Any>)
    /** Set by [PackVoice]; called with (sender id, message) for every signal addressed to this rider. */
    var onSignal: ((String, Map<String, Any?>) -> Unit)?
}

/**
 * The pack's voice channel: every rider in it talks straight to every other rider's phone over the
 * internet (WebRTC, peer to peer, Opus at ~20 kbit/s), so there is no server and no bill. Free
 * public STUN servers find the route through home and mobile networks; a TURN relay can be added in
 * the build for the strict carrier networks where a direct link can't be made.
 *
 * Up to [MAX_PEOPLE] riders, because each phone sends its voice to each other phone. Open mic or
 * push-to-talk. The phone goes into call mode so the voice uses the helmet intercom's microphone
 * and speaker when one is connected.
 */
class PackVoice(
    context: Context,
    private val signaling: VoiceSignaling,
    private val iceServers: List<PeerConnection.IceServer>,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pack-voice") }

    private val _ui = MutableStateFlow(VoiceUi())
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val peers = HashMap<String, Peer>()
    /** Link state of riders between attempts (after a failure), and how many attempts failed in a row. */
    private val waiting = HashMap<String, PeerLink>()
    private val failures = HashMap<String, Int>()
    private val retries = HashMap<String, ScheduledFuture<*>>()
    private var started = false
    private var holdReasons = HashSet<String>()
    private var savedMode = AudioManager.MODE_NORMAL
    private var usedSco = false

    private class Peer(val id: String, val offerer: Boolean, val sid: String, val pc: PeerConnection) {
        var remoteSet = false
        val pendingIce = ArrayList<IceCandidate>()
        var state = PeerLink.CONNECTING
        var retry: ScheduledFuture<*>? = null
        var connectTimeout: ScheduledFuture<*>? = null
    }

    init {
        signaling.onSignal = { from, msg -> exec.execute { onSignal(from, msg) } }
    }

    // --- Public controls (any thread) ---

    /** Join the channel. */
    fun join() {
        _ui.update { it.copy(wanted = true, error = null) }
        exec.execute { syncRunning() }
    }

    fun leave() {
        _ui.update { it.copy(wanted = false, talking = false) }
        exec.execute { syncRunning() }
    }

    fun setMode(mode: VoiceMode) {
        _ui.update { it.copy(mode = mode, talking = false) }
        exec.execute { applyMic() }
    }

    fun setMuted(muted: Boolean) {
        _ui.update { it.copy(muted = muted) }
        exec.execute { applyMic() }
    }

    /** Push-to-talk button down / up. */
    fun setTalking(on: Boolean) {
        _ui.update { it.copy(talking = on) }
        exec.execute { applyMic() }
    }

    /**
     * Give the microphone to something more important for a moment (the voice assistant, an SOS
     * countdown). The channel closes and comes back by itself when every hold is released.
     */
    fun hold(reason: String, on: Boolean) {
        exec.execute {
            val changed = if (on) holdReasons.add(reason) else holdReasons.remove(reason)
            if (!changed) return@execute
            _ui.update { it.copy(paused = holdReasons.isNotEmpty()) }
            syncRunning()
        }
    }

    /** The roster changed: connect to riders who joined, drop those who left. */
    fun onRoster(ids: Set<String>) = exec.execute { reconcile(ids) }

    fun release() {
        leave()
        exec.execute {
            stopAll()
            runCatching { factory?.dispose() }
            factory = null
            runCatching { adm?.release() }
            adm = null
        }
    }

    // --- Everything below runs on [exec] ---

    private fun syncRunning() {
        val want = _ui.value.wanted && holdReasons.isEmpty()
        if (want && !started) startAll() else if (!want && started) stopAll()
    }

    private fun startAll() {
        val f = runCatching { ensureFactory() }.getOrElse {
            Log.e(TAG, "WebRTC failed to start", it)
            _ui.update { u -> u.copy(error = "Voice isn't available on this phone", wanted = false) }
            return
        }
        val roster = signaling.voiceRoster.value
        if ((roster - signaling.myId).size >= MAX_PEOPLE) {
            _ui.update { it.copy(error = "Voice channel is full ($MAX_PEOPLE riders)", wanted = false) }
            return
        }
        source = f.createAudioSource(MediaConstraints())
        track = f.createAudioTrack("mic", source)
        started = true
        enterCallAudio()
        applyMic()
        signaling.setVoicePresence(true)
        _ui.update { it.copy(live = true, error = null) }
        reconcile(signaling.voiceRoster.value)
    }

    private fun stopAll() {
        if (!started) return
        started = false
        signaling.setVoicePresence(false)
        peers.values.toList().forEach { closePeer(it) }
        peers.clear()
        retries.values.forEach { it.cancel(false) }
        retries.clear()
        waiting.clear()
        failures.clear()
        runCatching { track?.dispose() }
        track = null
        runCatching { source?.dispose() }
        source = null
        leaveCallAudio()
        _ui.update { it.copy(live = false, peers = emptyMap(), output = null) }
    }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
        )
        val module = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()
        adm = module
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private fun applyMic() {
        val u = _ui.value
        track?.setEnabled(u.sending)
    }

    private fun reconcile(roster: Set<String>) {
        if (!started) return
        val others = roster - signaling.myId
        // Riders who left the room.
        peers.keys.filter { it !in others }.forEach { id -> peers.remove(id)?.let { closePeer(it) } }
        (waiting.keys + failures.keys + retries.keys).filter { it !in others }.toSet().forEach { id ->
            waiting.remove(id); failures.remove(id); retries.remove(id)?.cancel(false)
        }
        // The rider with the smaller id calls; the other answers. No glare, no double links.
        for (id in others) if (id !in peers && id !in retries && signaling.myId < id) call(id)
        publishPeers()
    }

    private fun call(id: String) {
        val peer = newPeer(id, offerer = true, sid = UUID.randomUUID().toString().take(8)) ?: return
        peers[id] = peer
        peer.pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                val tuned = SessionDescription(sdp.type, PackLogic.tuneOpus(sdp.description))
                peer.pc.setLocalDescription(SdpAdapter(), tuned)
                signaling.sendSignal(id, mapOf("type" to "offer", "sid" to peer.sid, "sdp" to tuned.description))
            }
            override fun onCreateFailure(error: String?) = exec.execute { failed(peer, "offer: $error") }
        }, MediaConstraints())
    }

    private fun newPeer(id: String, offerer: Boolean, sid: String): Peer? {
        val f = factory ?: return null
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Mobile data: switch networks (Wi-Fi to 4G) without dropping the call.
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            iceConnectionReceivingTimeout = 6_000
        }
        var self: Peer? = null
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                exec.execute { self?.let { onIceState(it, state) } }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
            override fun onIceCandidate(c: IceCandidate) {
                exec.execute {
                    val p = self ?: return@execute
                    if (peers[p.id] !== p) return@execute
                    signaling.sendSignal(
                        p.id,
                        mapOf("type" to "ice", "sid" to p.sid, "mid" to (c.sdpMid ?: ""), "idx" to c.sdpMLineIndex.toLong(), "cand" to c.sdp),
                    )
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(channel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // Remote audio plays by itself; make sure it isn't muted.
                (receiver?.track() as? AudioTrack)?.setEnabled(true)
            }
        }) ?: return null
        track?.let { pc.addTrack(it, listOf("pack")) }
        val p = Peer(id, offerer, sid, pc)
        self = p
        p.connectTimeout = exec.schedule({ if (peers[id] === p && p.state != PeerLink.CONNECTED) failed(p, "timeout") }, CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        return p
    }

    private fun onSignal(from: String, msg: Map<String, Any?>) {
        if (!started) return
        val type = msg["type"] as? String ?: return
        val sid = msg["sid"] as? String ?: return
        when (type) {
            "offer" -> {
                val sdp = msg["sdp"] as? String ?: return
                // A new call from them replaces any older link (they restarted it).
                peers.remove(from)?.let { closePeer(it) }
                val peer = newPeer(from, offerer = false, sid = sid) ?: return
                peers[from] = peer
                publishPeers()
                peer.pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() = exec.execute {
                        peer.remoteSet = true
                        peer.pendingIce.forEach { peer.pc.addIceCandidate(it) }
                        peer.pendingIce.clear()
                        peer.pc.createAnswer(object : SdpAdapter() {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                val tuned = SessionDescription(answer.type, PackLogic.tuneOpus(answer.description))
                                peer.pc.setLocalDescription(SdpAdapter(), tuned)
                                signaling.sendSignal(from, mapOf("type" to "answer", "sid" to sid, "sdp" to tuned.description))
                            }
                            override fun onCreateFailure(error: String?) = exec.execute { failed(peer, "answer: $error") }
                        }, MediaConstraints())
                    }
                    override fun onSetFailure(error: String?) = exec.execute { failed(peer, "remote offer: $error") }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            "answer" -> {
                val peer = peers[from]?.takeIf { it.sid == sid && it.offerer } ?: return
                val sdp = msg["sdp"] as? String ?: return
                peer.pc.setRemoteDescription(object : SdpAdapter() {
                    override fun onSetSuccess() = exec.execute {
                        peer.remoteSet = true
                        peer.pendingIce.forEach { peer.pc.addIceCandidate(it) }
                        peer.pendingIce.clear()
                    }
                    override fun onSetFailure(error: String?) = exec.execute { failed(peer, "remote answer: $error") }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice" -> {
                val peer = peers[from]?.takeIf { it.sid == sid } ?: return
                val cand = IceCandidate(
                    msg["mid"] as? String ?: "",
                    (msg["idx"] as? Number)?.toInt() ?: 0,
                    msg["cand"] as? String ?: return,
                )
                if (peer.remoteSet) peer.pc.addIceCandidate(cand) else peer.pendingIce += cand
            }
        }
    }

    private fun onIceState(p: Peer, s: PeerConnection.IceConnectionState?) {
        if (peers[p.id] !== p) return
        when (s) {
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> {
                p.state = PeerLink.CONNECTED
                failures.remove(p.id)
                waiting.remove(p.id)
                p.connectTimeout?.cancel(false)
                p.retry?.cancel(false)
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                // Often a tunnel or a handover; ICE recovers by itself. Give it a while.
                p.state = PeerLink.RETRYING
                p.retry?.cancel(false)
                p.retry = exec.schedule({
                    if (peers[p.id] === p && p.state != PeerLink.CONNECTED) failed(p, "disconnected")
                }, 12, TimeUnit.SECONDS)
            }
            PeerConnection.IceConnectionState.FAILED -> failed(p, "ice failed")
            else -> Unit
        }
        publishPeers()
    }

    /** A link failed: the caller tries again with growing pauses; after a few tries it shows as failed. */
    private fun failed(p: Peer, why: String) {
        if (peers[p.id] !== p) return
        Log.w(TAG, "voice link to ${p.id} failed: $why")
        val n = (failures[p.id] ?: 0) + 1
        failures[p.id] = n
        closePeer(p)
        peers.remove(p.id)
        waiting[p.id] = if (n >= GIVE_UP_AFTER) PeerLink.FAILED else PeerLink.RETRYING
        if (started && signaling.myId < p.id) {
            retries.remove(p.id)?.cancel(false)
            retries[p.id] = exec.schedule({
                retries.remove(p.id)
                if (started && p.id !in peers && p.id in signaling.voiceRoster.value) call(p.id)
                publishPeers()
            }, minOf(30L, 3L * n), TimeUnit.SECONDS)
        }
        publishPeers()
    }

    private fun closePeer(p: Peer) {
        p.retry?.cancel(false)
        p.connectTimeout?.cancel(false)
        runCatching { p.pc.dispose() }
    }

    private fun publishPeers() {
        val links = HashMap<String, PeerLink>(waiting)
        peers.forEach { (id, p) -> links[id] = p.state }
        val roster = signaling.voiceRoster.value
        _ui.update { u -> u.copy(peers = links.filterKeys { it in roster && it != signaling.myId }) }
    }

    // --- Call audio: helmet intercom when there is one ---

    private fun enterCallAudio() {
        runCatching {
            savedMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            var output: String? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audio.availableCommunicationDevices
                val pick = PREFERRED.firstNotNullOfOrNull { t -> devices.firstOrNull { it.type == t } }
                if (pick != null && pick.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    audio.setCommunicationDevice(pick)
                    output = pick.productName?.toString()?.takeIf { pick.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { audio.setCommunicationDevice(it) }
                }
            } else {
                @Suppress("DEPRECATION")
                if (audio.isBluetoothScoAvailableOffCall) {
                    audio.startBluetoothSco()
                    audio.isBluetoothScoOn = true
                    usedSco = true
                } else audio.isSpeakerphoneOn = true
            }
            _ui.update { it.copy(output = output) }
        }.onFailure { Log.w(TAG, "call audio", it) }
    }

    private fun leaveCallAudio() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
            else {
                @Suppress("DEPRECATION")
                if (usedSco) {
                    audio.isBluetoothScoOn = false
                    audio.stopBluetoothSco()
                    usedSco = false
                }
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = false
            }
            audio.mode = savedMode
        }
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private const val TAG = "PackVoice"
        /** Everyone sends to everyone: past six riders a phone's upload and battery suffer. */
        const val MAX_PEOPLE = 6
        private const val CONNECT_TIMEOUT_S = 25L
        private const val GIVE_UP_AFTER = 4

        /** Helmet intercom first, then wired, then the phone's loudspeaker. */
        private val PREFERRED = listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )

        /** Google's free public STUN servers, plus a TURN relay when the build has one. */
        fun iceServers(turnUrls: String, turnUser: String, turnPass: String): List<PeerConnection.IceServer> {
            val list = arrayListOf(
                PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            )
            val turn = turnUrls.split(',').map { it.trim() }.filter { it.startsWith("turn") }
            if (turn.isNotEmpty()) {
                list += PeerConnection.IceServer.builder(turn).setUsername(turnUser).setPassword(turnPass).createIceServer()
            }
            return list
        }
    }
}
