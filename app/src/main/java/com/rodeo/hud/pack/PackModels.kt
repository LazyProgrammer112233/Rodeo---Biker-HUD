package com.rodeo.hud.pack

/** How a rider's latest position reached this phone. */
enum class Link { CLOUD, NEARBY, DEMO, SELF }

data class PackMember(
    val id: String,
    val name: String,
    /** Index into [PackLogic.PALETTE]. */
    val colorIndex: Int,
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    /** This phone's clock when the position was taken (server and sender clocks are converted). */
    val updatedAt: Long,
    /** When this rider stopped moving; 0 while moving. */
    val stillSince: Long,
    val isMe: Boolean = false,
    val isLead: Boolean = false,
    /** Direction of travel, or negative when unknown. */
    val bearing: Float = -1f,
    val link: Link = Link.CLOUD,
    /** In the pack's voice channel. */
    val inVoice: Boolean = false,
    /** Their SOS is counting down or has been sent. */
    val sos: Boolean = false,
    /** The cloud still has a live connection from them (false once their phone dropped off). */
    val online: Boolean = true,
) {
    fun stopped(now: Long) = stillSince != 0L && now - stillSince > STOPPED_MS
    fun ageMs(now: Long) = (now - updatedAt).coerceAtLeast(0)
    fun stale(now: Long) = ageMs(now) > STALE_MS

    companion object {
        /** Stationary this long counts as "stopped". */
        const val STOPPED_MS = 120_000L
        /** No position for this long: contact lost. */
        const val STALE_MS = 90_000L
        /** Riders not heard from for this long drop off the list. */
        const val FORGET_MS = 45 * 60_000L
    }
}

enum class CloudStatus { NOT_SET_UP, SIGNING_IN, ONLINE, OFFLINE, ERROR }

enum class NearbyStatus { OFF, NO_PERMISSION, BLUETOOTH_OFF, SEARCHING, CONNECTED, ERROR }

enum class VoiceMode { OPEN, PUSH_TO_TALK }

enum class PeerLink { CONNECTING, CONNECTED, RETRYING, FAILED }

data class VoiceUi(
    /** The rider wants to be in the voice channel. */
    val wanted: Boolean = false,
    /** Audio is flowing (the channel is up, even if nobody else has joined yet). */
    val live: Boolean = false,
    val mode: VoiceMode = VoiceMode.OPEN,
    val muted: Boolean = false,
    /** Push-to-talk button held. */
    val talking: Boolean = false,
    /** Paused while the voice assistant or an SOS countdown has the microphone. */
    val paused: Boolean = false,
    /** Voice link to each other rider in the channel, by member id. */
    val peers: Map<String, PeerLink> = emptyMap(),
    /** Name of the headset the voice goes to, or null for the phone. */
    val output: String? = null,
    val error: String? = null,
) {
    /** The rider's microphone is actually being sent. */
    val sending get() = live && !paused && !muted && (mode == VoiceMode.OPEN || talking)
}

/** A short pop-up on the HUD: "Rahul fell 2.1 km behind". */
data class PackEvent(val text: String, val severity: Int, val at: Long, val riderId: String? = null)

data class PackState(
    val code: String? = null,
    val demo: Boolean = false,
    val members: List<PackMember> = emptyList(),
    val spreadKm: Double = 0.0,
    val error: String? = null,
    val cloud: CloudStatus = CloudStatus.NOT_SET_UP,
    val nearby: NearbyStatus = NearbyStatus.OFF,
    val nearbyPeers: Int = 0,
    val nearbyNote: String? = null,
    val voice: VoiceUi = VoiceUi(),
    /** Distance at which "fell behind" alerts fire. */
    val gapM: Int = 2_000,
    val lastEvent: PackEvent? = null,
    val lead: Boolean = false,
) {
    val active get() = code != null
    val others get() = members.filter { !it.isMe }
}
