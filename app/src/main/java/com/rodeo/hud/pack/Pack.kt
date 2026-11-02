package com.rodeo.hud.pack

import android.content.Context
import android.location.Location
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase
import com.rodeo.hud.BuildConfig
import com.rodeo.hud.core.Severity
import com.rodeo.hud.core.Speaker
import com.rodeo.hud.core.Warning
import com.rodeo.hud.core.WarningCenter
import com.rodeo.hud.data.Geo
import com.rodeo.hud.data.Hazard
import com.rodeo.hud.data.HazardStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pack mode: a group ride where everyone sees everyone.
 *
 * - Over mobile data ([CloudLink]): positions every 3 seconds through Firebase, any distance apart.
 * - Without signal ([NearbyLink]): phones in Bluetooth / Wi-Fi range swap positions directly.
 *   Both run together; for each rider the newest position wins, whichever way it came.
 * - Voice ([PackVoice]): a peer-to-peer intercom between the riders' phones.
 * - Alerts ([PackAlerts]): spoken and on screen when someone falls behind, stops, loses contact,
 *   triggers SOS, or when the pack regroups.
 */
class PackService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val warnings: WarningCenter,
    private val speaker: Speaker,
    private val myName: () -> String,
) {
    private val prefs = context.getSharedPreferences("rodeo_pack", Context.MODE_PRIVATE)
    val cloud = CloudLink(context, scope)
    private val nearby = NearbyLink(context) { p, from -> onNearby(p, from) }
    val voice = PackVoice(
        context, cloud,
        PackVoice.iceServers(BuildConfig.TURN_URLS, BuildConfig.TURN_USERNAME, BuildConfig.TURN_PASSWORD),
    )

    /** Firebase is set up in this build, so packs work over mobile data. */
    val online: Boolean get() = cloud.configured

    private val _state = MutableStateFlow(PackState(cloud = cloud.status.value, gapM = prefs.getInt("gap", 2_000)))
    val state: StateFlow<PackState> = _state.asStateFlow()

    private val localId: String = prefs.getString("id", null)
        ?: UUID.randomUUID().toString().replace("-", "").take(16).also { prefs.edit().putString("id", it).apply() }

    /** The id other phones know this rider by: the cloud id once signed in. */
    val myId: String get() = cloud.uid ?: localId

    private val alerts = PackAlerts(gapM = _state.value.gapM.toDouble())
    private val gate = SpeechGate()
    private val posted = HashSet<String>()
    private var ticker: Job? = null
    private var demoJob: Job? = null
    private val heardNearby = HashMap<String, Pair<WirePosition, Long>>()
    private var me: Location? = null
    private var myStillSince = 0L
    private var lastHeading: Double? = null
    private var lastHeadingAt = 0L
    private var lastCloudSend = 0L
    private var lastNearbySend = 0L
    private var lastSentLat = 0.0
    private var lastSentLng = 0.0
    private var mySos = false
    private var isLead = false
    private var demoMembers: List<PackMember> = emptyList()

    init {
        cloud.warmUp()
        scope.launch { cloud.status.collect { s -> _state.update { it.copy(cloud = s) } } }
        scope.launch { cloud.error.collect { e -> _state.update { it.copy(error = e) } } }
        scope.launch { cloud.voiceRoster.collect { voice.onRoster(it) } }
        scope.launch { voice.ui.collect { v -> _state.update { it.copy(voice = v) } } }
        scope.launch { nearby.status.collect { s -> _state.update { it.copy(nearby = s) } } }
        scope.launch { nearby.peers.collect { n -> _state.update { it.copy(nearbyPeers = n) } } }
        scope.launch { nearby.note.collect { n -> _state.update { it.copy(nearbyNote = n) } } }
        restore()
    }

    // --- Joining and leaving ---

    /** Start a new pack with a fresh code; this rider leads it. */
    fun create() {
        join(PackLogic.newCode(), lead = true)
    }

    /** Join by code. With Firebase, an unknown code is refused (when the server can be reached). */
    fun join(rawCode: String, lead: Boolean = false) {
        val code = PackLogic.cleanCode(rawCode)
        if (!PackLogic.isValidCode(code)) {
            _state.update { it.copy(error = "A pack code is 6 letters and numbers") }
            return
        }
        leave()
        isLead = lead
        _state.update { it.copy(code = code, demo = false, lead = lead, error = null, members = emptyList(), lastEvent = null) }
        prefs.edit().putString("code", code).putBoolean("lead", lead).putLong("joinedAt", System.currentTimeMillis()).apply()
        scope.launch {
            if (cloud.configured) {
                if (cloud.signIn() != null) {
                    if (!lead) {
                        when (cloud.exists(code)) {
                            false -> {
                                leave()
                                _state.update { it.copy(error = "No pack with code $code. Check the code with the rider who started it.") }
                                return@launch
                            }
                            null -> event("No signal: joining pack $code over the short-range link", 0)
                            true -> Unit
                        }
                    }
                    if (_state.value.code != code) return@launch
                    cloud.join(code, lead, myName())
                    me?.let { publishCloud(it, force = true) }
                }
            }
            if (_state.value.code != code) return@launch
            nearby.start(code, myId)
        }
        startTicker()
    }

    /** Try a pack mode demo with three simulated riders around you. */
    fun demo() {
        leave()
        _state.update { it.copy(code = "DEMO42", demo = true, lead = true, error = null) }
        startDemo()
        startTicker()
    }

    fun leave() {
        val was = _state.value.code
        voice.leave()
        cloud.leave()
        nearby.stop()
        demoJob?.cancel(); demoJob = null
        ticker?.cancel(); ticker = null
        heardNearby.clear()
        demoMembers = emptyList()
        alerts.reset()
        gate.clear()
        posted.forEach { warnings.clear(it) }
        posted.clear()
        isLead = false
        if (was != null) prefs.edit().remove("code").remove("lead").apply()
        _state.update { PackState(cloud = cloud.status.value, gapM = it.gapM, voice = voice.ui.value, nearby = NearbyStatus.OFF) }
    }

    /** A pack is remembered for 12 hours, so a phone restart mid-ride drops the rider straight back in. */
    private fun restore() {
        val code = prefs.getString("code", null) ?: return
        val at = prefs.getLong("joinedAt", 0L)
        if (System.currentTimeMillis() - at > 12 * 3_600_000L) {
            prefs.edit().remove("code").apply()
            return
        }
        join(code, lead = prefs.getBoolean("lead", false))
    }

    /** Nearby permissions were just granted (or Bluetooth switched on): start the short-range link. */
    fun retryNearby() {
        val code = _state.value.code ?: return
        if (_state.value.demo) return
        nearby.stop()
        nearby.start(code, myId)
    }

    fun nearbyPermitted() = nearby.permitted()

    fun setGap(m: Int) {
        prefs.edit().putInt("gap", m).apply()
        alerts.gapM = m.toDouble()
        _state.update { it.copy(gapM = m) }
    }

    // --- Voice ---

    fun voiceOn() {
        if (_state.value.demo || !cloud.configured) {
            _state.update { it.copy(error = if (_state.value.demo) "Voice needs a real pack" else "Voice needs the pack server (Firebase) in this build") }
            return
        }
        prefs.edit().putBoolean("voice", true).apply()
        voice.join()
    }

    fun voiceOff() {
        prefs.edit().putBoolean("voice", false).apply()
        voice.leave()
    }

    // --- Inputs ---

    /** Every location fix. */
    fun onLocation(loc: Location) {
        me = loc
        val speed = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        val now = System.currentTimeMillis()
        myStillSince = if (speed < 3f) (if (myStillSince == 0L) now else myStillSince) else 0L
        if (speed > 8f && loc.hasBearing()) {
            lastHeading = loc.bearing.toDouble()
            lastHeadingAt = now
        }
        if (_state.value.active && !_state.value.demo) publishCloud(loc, force = false)
    }

    /** This rider's SOS started or ended: the pack is told straight away. */
    fun setSos(on: Boolean) {
        if (mySos == on) return
        mySos = on
        me?.let { if (_state.value.active && !_state.value.demo) { publishCloud(it, force = true); publishNearby(it) } }
    }

    // --- Sending ---

    /** Every 3 s while moving (or after 25 m), every 20 s while parked: enough for the HUD, light on data. */
    private fun publishCloud(loc: Location, force: Boolean) {
        val now = System.currentTimeMillis()
        val moved = Geo.distanceM(lastSentLat, lastSentLng, loc.latitude, loc.longitude)
        val interval = if (myStillSince != 0L) 20_000 else 3_000
        if (!force && now - lastCloudSend < interval && moved < 25) return
        if (!force && now - lastCloudSend < 1_500) return
        lastCloudSend = now
        lastSentLat = loc.latitude
        lastSentLng = loc.longitude
        cloud.publish(myName(), loc.latitude, loc.longitude, if (loc.hasSpeed()) loc.speed * 3.6f else 0f,
            if (loc.hasBearing()) loc.bearing else -1f, myStillSince, mySos)
    }

    private fun publishNearby(loc: Location) {
        lastNearbySend = System.currentTimeMillis()
        nearby.send(
            WirePosition(
                id = myId, name = myName(), lat = loc.latitude, lng = loc.longitude,
                speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f, bearing = if (loc.hasBearing()) loc.bearing else -1f,
                ts = loc.time.takeIf { it > 0 && it <= System.currentTimeMillis() } ?: System.currentTimeMillis(),
                stillSince = myStillSince, sos = mySos, lead = isLead, hops = 0, inVoice = voice.ui.value.live,
            ),
        )
    }

    private fun onNearby(p: WirePosition, @Suppress("UNUSED_PARAMETER") from: String) {
        if (p.id == myId) return
        // The sender's clock may be a little off; never trust a time from the future.
        heardNearby[p.id] = p.copy(ts = minOf(p.ts, System.currentTimeMillis())) to System.currentTimeMillis()
    }

    // --- The loop: merge, alert, speak, send ---

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                tick()
                delay(1_000)
            }
        }
    }

    private fun tick() {
        val s = _state.value
        if (!s.active) return
        val now = System.currentTimeMillis()
        val loc = me
        if (!s.demo && loc != null && now - lastNearbySend > 3_000) publishNearby(loc)

        val members = merged(now)
        val mine = members.firstOrNull { it.isMe }
        val others = members.filter { !it.isMe }
        val heading = lastHeading?.takeIf { now - lastHeadingAt < 30_000 }

        // Alerts: banners for what is still going on, a pop-up and a voice line for each change.
        var latest: PackEvent? = s.lastEvent
        for (a in alerts.update(now, mine, others, heading)) {
            latest = PackEvent(a.text, a.severity, now, a.riderId)
            if (a.sticky && a.riderId != null) {
                val key = key(a.kind, a.riderId)
                posted += key
                warnings.post(Warning(key, a.text, PACK_DETAIL[a.kind] ?: "", when (a.severity) { 2 -> Severity.DANGER; 1 -> Severity.WARN; else -> Severity.INFO }))
            }
            gate.offer(a)
        }
        val ongoing = alerts.ongoing.map { key(it.first, it.second) }.toSet()
        posted.filter { it !in ongoing }.forEach { warnings.clear(it); posted -= it }
        gate.next(now)?.let { a ->
            val channel = when {
                a.severity >= 2 -> Speaker.Channel.ALERT
                speaker.navMuted -> Speaker.Channel.ASSISTANT
                else -> Speaker.Channel.NAV
            }
            speaker.say(a.speech, channel)
        }

        var spread = 0.0
        for (i in members.indices) for (j in i + 1 until members.size) {
            spread = maxOf(spread, Geo.distanceM(members[i].lat, members[i].lng, members[j].lat, members[j].lng))
        }
        _state.update { it.copy(members = members, spreadKm = spread / 1000.0, lastEvent = latest) }
    }

    private fun key(kind: PackAlerts.Kind, id: String) = "pack-" + when (kind) {
        PackAlerts.Kind.FELL_BEHIND, PackAlerts.Kind.PULLED_AHEAD -> "gap"
        PackAlerts.Kind.STOPPED -> "stopped"
        PackAlerts.Kind.LOST -> "lost"
        PackAlerts.Kind.SOS -> "sos"
        else -> kind.name.lowercase()
    } + "-" + id

    /** Everyone, newest position per rider from whichever link had it, plus this rider. */
    private fun merged(now: Long): List<PackMember> {
        val s = _state.value
        val out = LinkedHashMap<String, PackMember>()
        if (s.demo) demoMembers.forEach { out[it.id] = it }
        val roster = cloud.voiceRoster.value
        val lead = cloud.lead.value
        for (m in cloud.members.value) {
            if (m.id == myId || m.ts == 0L) continue
            out[m.id] = PackMember(
                m.id, m.name, 0, m.lat, m.lng, m.speedKmh, minOf(m.ts, now), m.stillSince,
                isLead = m.id == lead, bearing = m.bearing, link = Link.CLOUD, inVoice = m.id in roster, sos = m.sos, online = m.online,
            )
        }
        for ((id, pair) in heardNearby) {
            val p = pair.first
            val old = out[id]
            if (old != null && old.updatedAt >= p.ts) continue
            out[id] = PackMember(
                id, p.name, 0, p.lat, p.lng, p.speedKmh, p.ts, p.stillSince,
                isLead = p.lead || id == lead, bearing = p.bearing, link = Link.NEARBY,
                inVoice = id in roster || p.inVoice, sos = p.sos, online = true,
            )
        }
        out.values.removeAll { it.ageMs(now) > PackMember.FORGET_MS }
        me?.let { l ->
            out[myId] = PackMember(
                myId, myName(), 0, l.latitude, l.longitude, if (l.hasSpeed()) l.speed * 3.6f else 0f, now, myStillSince,
                isMe = true, isLead = isLead || lead == myId, bearing = if (l.hasBearing()) l.bearing else -1f,
                link = Link.SELF, inVoice = voice.ui.value.live, sos = mySos,
            )
        }
        val colors = PackLogic.assignColors(out.keys)
        return out.values.map { it.copy(colorIndex = colors[it.id] ?: 0) }
            .sortedWith(compareByDescending<PackMember> { it.isMe }.thenBy { it.name.lowercase() })
    }

    private fun event(text: String, severity: Int) {
        _state.update { it.copy(lastEvent = PackEvent(text, severity, System.currentTimeMillis())) }
    }

    /** One spoken summary for "Hey Rodeo, where's the pack?". */
    fun spokenSummary(): String {
        val s = _state.value
        if (!s.active) return "You're not in a pack"
        val mine = s.members.firstOrNull { it.isMe } ?: return "Waiting for your location"
        val others = s.others
        if (others.isEmpty()) return "Nobody else has joined yet. Your code is ${s.code?.toCharArray()?.joinToString(" ")}"
        val heading = lastHeading?.takeIf { System.currentTimeMillis() - lastHeadingAt < 30_000 }
        val now = System.currentTimeMillis()
        val parts = others.sortedBy { Geo.distanceM(mine.lat, mine.lng, it.lat, it.lng) }.take(5).map { m ->
            val d = Geo.distanceM(mine.lat, mine.lng, m.lat, m.lng)
            val rel = heading?.let { PackLogic.relativeBearing(it, Geo.bearing(mine.lat, mine.lng, m.lat, m.lng)) }
            val where = if (d < 30) "with you" else "${PackLogic.distanceSpoken(d)} ${PackLogic.sideWord(PackLogic.side(rel))}"
            when {
                m.sos -> "${m.name} needs help, $where"
                m.stale(now) -> "${m.name}, no contact, last seen $where"
                m.stopped(now) -> "${m.name} has stopped, $where"
                else -> "${m.name} $where"
            }
        }
        return parts.joinToString(". ")
    }

    // --- Demo ---

    /** Three simulated riders: one close by (mini-map), one ahead, one parked far behind (alerts). */
    private fun startDemo() {
        demoJob = scope.launch {
            val names = listOf("Kabir", "Ananya Rao", "Vikram")
            var t = 0.0
            val stoppedAt = System.currentTimeMillis()
            while (isActive) {
                val base = me
                val lat0 = base?.latitude ?: 12.9716
                val lng0 = base?.longitude ?: 77.5946
                val hd = Math.toRadians(lastHeading ?: 0.0)
                val now = System.currentTimeMillis()
                // Ahead / behind follow the rider's own heading so the HUD reads naturally.
                fun along(m: Double, side: Double) = Geo.offset(lat0, lng0, m * sin(hd) + side * cos(hd), m * cos(hd) - side * sin(hd))
                demoMembers = names.mapIndexed { i, n ->
                    val (lat, lng) = when (i) {
                        0 -> along(-180.0 + 60 * sin(t / 6), 40.0 * cos(t / 5))
                        1 -> along(1_150.0 + 80 * sin(t / 9), -60.0)
                        else -> along(-2_300.0, 300.0)
                    }
                    PackMember(
                        "demo$i", n, 0, lat, lng, if (i == 2) 0f else 42f + i * 5, now,
                        if (i == 2) stoppedAt - 150_000 else 0L, isLead = i == 1, link = Link.DEMO, inVoice = i == 0,
                    )
                }
                t += 1.0
                delay(1_000)
            }
        }
    }

    companion object {
        private val PACK_DETAIL = mapOf(
            PackAlerts.Kind.FELL_BEHIND to "Pack mode · ease off and let them catch up",
            PackAlerts.Kind.PULLED_AHEAD to "Pack mode · they may wait at the next stop",
            PackAlerts.Kind.STOPPED to "Pack mode · stationary for over 2 minutes",
            PackAlerts.Kind.LOST to "Pack mode · no signal from them for 90 seconds",
            PackAlerts.Kind.SOS to "Pack mode · their emergency alert is on",
        )
    }
}

/** Shares confirmed potholes (2+ hits) and pulls other riders' hazards near you. */
class HazardSync(context: Context, private val store: HazardStore) {
    private val db: FirebaseDatabase? = runCatching { if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseDatabase.getInstance() else null }.getOrNull()
    val online get() = db != null
    private var lastCell: String? = null

    private fun cell(lat: Double, lng: Double) = "%d_%d".format((lat * 50).toInt(), (lng * 50).toInt())

    fun onHazard(h: Hazard) {
        val d = db ?: return
        if (h.hits < 2 || h.shared) return
        d.getReference("hazards").child(cell(h.lat, h.lng)).child(h.id)
            .setValue(mapOf("lat" to h.lat, "lng" to h.lng, "hits" to h.hits.toLong(), "ts" to h.lastHit, "sev" to h.severity.toDouble()))
            .addOnSuccessListener { store.markShared(h.id) }
    }

    fun onLocation(lat: Double, lng: Double) {
        val d = db ?: return
        val c = cell(lat, lng)
        if (c == lastCell) return
        lastCell = c
        d.getReference("hazards").child(c).get().addOnSuccessListener { snap ->
            val mine = store.hazards.value.map { it.id }.toSet()
            val list = snap.children.mapNotNull { s ->
                val id = s.key ?: return@mapNotNull null
                if (id in mine) return@mapNotNull null
                Hazard(id, s.child("lat").getValue(Double::class.java) ?: return@mapNotNull null,
                    s.child("lng").getValue(Double::class.java) ?: return@mapNotNull null,
                    (s.child("hits").getValue(Long::class.java) ?: 1L).toInt(),
                    s.child("ts").getValue(Long::class.java) ?: 0L,
                    (s.child("sev").getValue(Double::class.java) ?: 1.0).toFloat(), shared = true)
            }
            store.setRemote(list)
        }
    }
}
