package com.rodeo.hud.pack

import android.content.Context
import android.location.Location
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.rodeo.hud.core.Severity
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

data class PackMember(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val updatedAt: Long,
    /** When this rider stopped moving; 0 while moving. */
    val stillSince: Long,
    val isMe: Boolean = false,
    val isLead: Boolean = false,
)

data class PackState(
    val code: String? = null,
    val demo: Boolean = false,
    val members: List<PackMember> = emptyList(),
    val spreadKm: Double = 0.0,
    val error: String? = null,
) {
    val active get() = code != null
}

/** Pack mode: live positions of everyone on a group ride, with "rider stopped" and gap alerts. */
class PackService(
    context: Context,
    private val scope: CoroutineScope,
    private val warnings: WarningCenter,
    private val myName: () -> String,
) {
    private val db: FirebaseDatabase? = runCatching { if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseDatabase.getInstance() else null }.getOrNull()
    val online: Boolean get() = db != null

    private val _state = MutableStateFlow(PackState())
    val state: StateFlow<PackState> = _state.asStateFlow()

    private val myId: String = context.getSharedPreferences("rodeo_pack", Context.MODE_PRIVATE).let { p ->
        p.getString("id", null) ?: UUID.randomUUID().toString().take(12).also { p.edit().putString("id", it).apply() }
    }
    private var ref: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var demoJob: Job? = null
    private var me: Location? = null
    private var myStillSince = 0L
    private var lastPublish = 0L

    fun create() {
        val code = (1..6).map { ALPHABET.random() }.joinToString("")
        join(code, lead = true)
    }

    fun join(code: String, lead: Boolean = false) {
        leave()
        val c = code.trim().uppercase()
        val database = db
        if (database == null) {
            startDemo()
            return
        }
        val r = database.getReference("packs").child(c)
        ref = r
        if (lead) r.child("lead").setValue(myId)
        _state.value = PackState(code = c)
        listener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val leadId = s.child("lead").getValue(String::class.java)
                val members = s.child("members").children.mapNotNull { m ->
                    val lat = m.child("lat").getValue(Double::class.java) ?: return@mapNotNull null
                    val lng = m.child("lng").getValue(Double::class.java) ?: return@mapNotNull null
                    PackMember(
                        id = m.key ?: return@mapNotNull null,
                        name = m.child("name").getValue(String::class.java) ?: "Rider",
                        colorIndex = (m.child("color").getValue(Long::class.java) ?: 0L).toInt(),
                        lat = lat, lng = lng,
                        speedKmh = (m.child("speed").getValue(Double::class.java) ?: 0.0).toFloat(),
                        updatedAt = m.child("ts").getValue(Long::class.java) ?: 0L,
                        stillSince = m.child("still").getValue(Long::class.java) ?: 0L,
                        isMe = m.key == myId,
                        isLead = m.key == leadId,
                    )
                }.filter { System.currentTimeMillis() - it.updatedAt < 15 * 60_000 }
                update(members)
            }
            override fun onCancelled(error: DatabaseError) {
                _state.value = _state.value.copy(error = error.message)
            }
        }
        r.addValueEventListener(listener!!)
        me?.let { publish(it, force = true) }
    }

    fun leave() {
        listener?.let { l -> ref?.removeEventListener(l) }
        ref?.child("members")?.child(myId)?.removeValue()
        ref = null
        listener = null
        demoJob?.cancel()
        demoJob = null
        _state.value = PackState()
        warnings.clear(KEY_STOPPED); warnings.clear(KEY_GAP)
    }

    /** Called on each location fix; publishes at most every 5 seconds. */
    fun onLocation(loc: Location) {
        me = loc
        val speed = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
        myStillSince = if (speed < 3f) (if (myStillSince == 0L) System.currentTimeMillis() else myStillSince) else 0L
        if (_state.value.demo) return
        publish(loc, force = false)
    }

    private fun publish(loc: Location, force: Boolean) {
        val r = ref ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 5_000) return
        lastPublish = now
        r.child("members").child(myId).setValue(
            mapOf(
                "name" to myName(), "lat" to loc.latitude, "lng" to loc.longitude,
                "speed" to (if (loc.hasSpeed()) loc.speed * 3.6 else 0.0), "ts" to now,
                "still" to myStillSince, "color" to (myId.hashCode() and 0x7).toLong(),
            ),
        )
    }

    private fun update(members: List<PackMember>) {
        var spread = 0.0
        for (i in members.indices) for (j in i + 1 until members.size) {
            spread = maxOf(spread, Geo.distanceM(members[i].lat, members[i].lng, members[j].lat, members[j].lng))
        }
        _state.value = _state.value.copy(members = members, spreadKm = spread / 1000.0)

        val now = System.currentTimeMillis()
        val stopped = members.filter { !it.isMe && it.stillSince != 0L && now - it.stillSince > 120_000 }
        if (stopped.isNotEmpty()) {
            val who = stopped.joinToString { it.name }
            warnings.post(Warning(KEY_STOPPED, "$who stopped", "Stationary for ${(now - stopped.first().stillSince) / 60_000} min", Severity.WARN))
        } else warnings.clear(KEY_STOPPED)

        if (spread > 2_000) warnings.post(Warning(KEY_GAP, "Pack spread %.1f km".format(spread / 1000.0), "Lead, slow down and regroup", Severity.INFO))
        else warnings.clear(KEY_GAP)
    }

    /** Demo pack: three simulated riders around you so the UI can be tried solo. */
    private fun startDemo() {
        _state.value = PackState(code = "DEMO42", demo = true)
        demoJob = scope.launch {
            val names = listOf("Kabir", "Ananya", "Vikram")
            var t = 0.0
            val stoppedAt = System.currentTimeMillis()
            while (isActive) {
                val base = me
                val lat0 = base?.latitude ?: 12.9716
                val lng0 = base?.longitude ?: 77.5946
                val now = System.currentTimeMillis()
                val members = names.mapIndexed { i, n ->
                    val r = 250.0 + i * 600.0
                    val a = t / (8 + i * 4) + i * 2.1
                    // Vikram (index 2) is parked to show the "stopped" alert.
                    val (lat, lng) = if (i == 2) Geo.offset(lat0, lng0, -2_300.0, -900.0) else Geo.offset(lat0, lng0, r * cos(a), r * sin(a))
                    PackMember("demo$i", n, i + 1, lat, lng, if (i == 2) 0f else 38f + i * 6, now,
                        if (i == 2) stoppedAt - 150_000 else 0L, isLead = i == 0)
                }
                val mine = PackMember("me", myName(), 0, lat0, lng0, base?.speed?.times(3.6f) ?: 0f, now, myStillSince, isMe = true)
                update(listOf(mine) + members)
                t += 1.0
                delay(2_000)
            }
        }
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val KEY_STOPPED = "pack-stopped"
        const val KEY_GAP = "pack-gap"
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
