package com.rodeo.hud.pack

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** One rider as the cloud holds it. [ts] is already on this phone's clock. */
data class CloudMember(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val bearing: Float,
    val ts: Long,
    val stillSince: Long,
    val sos: Boolean,
    val online: Boolean,
    val inVoice: Boolean,
)

/**
 * The pack over mobile data: Firebase Realtime Database on the free Spark plan, with anonymous
 * sign-in so the security rules can make sure a rider only ever writes their own position.
 *
 * Layout (see docs/firebase-rules.json):
 *   packs/{code}/meta              { lead, created }
 *   packs/{code}/members/{uid}     { name, lat, lng, speed, brg, ts, still, sos, online }
 *   packs/{code}/voice/{uid}       true while in the voice channel
 *   packs/{code}/signal/{to}/{id}  voice connection setup messages, deleted once read
 *
 * The phone only holds a connection while in a pack (Firebase closes an idle one by itself), which
 * keeps the free plan's 100 simultaneous connections for riders who are actually riding together.
 */
class CloudLink(context: Context, private val scope: CoroutineScope) : VoiceSignaling {
    private val firebase: FirebaseApp? = runCatching { FirebaseApp.getApps(context).firstOrNull() }.getOrNull()
    private val db: FirebaseDatabase? = firebase?.let { runCatching { FirebaseDatabase.getInstance(it) }.getOrNull() }
    private val auth: FirebaseAuth? = firebase?.let { runCatching { FirebaseAuth.getInstance(it) }.getOrNull() }

    /** Firebase keys are in this build. */
    val configured: Boolean get() = db != null && auth != null

    private val _status = MutableStateFlow(if (configured) CloudStatus.OFFLINE else CloudStatus.NOT_SET_UP)
    val status: StateFlow<CloudStatus> = _status.asStateFlow()
    private val _members = MutableStateFlow<List<CloudMember>>(emptyList())
    val members: StateFlow<List<CloudMember>> = _members.asStateFlow()
    private val _lead = MutableStateFlow<String?>(null)
    val lead: StateFlow<String?> = _lead.asStateFlow()
    private val _roster = MutableStateFlow<Set<String>>(emptySet())
    override val voiceRoster: StateFlow<Set<String>> = _roster.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    override var onSignal: ((String, Map<String, Any?>) -> Unit)? = null

    /** Signed-in id, or "" before sign-in. */
    override val myId: String get() = uid ?: ""
    /** The signed-in rider (anonymous until they make an account). */
    val uid: String? get() = runCatching { auth?.currentUser?.uid }.getOrNull()

    /** Server clock minus this phone's clock. */
    @Volatile private var serverOffset = 0L
    private var pack: DatabaseReference? = null
    private val listeners = ArrayList<Pair<com.google.firebase.database.Query, Any>>()
    private var connectedListener: ValueEventListener? = null
    private var offsetListener: ValueEventListener? = null
    private var inVoice = false
    private var lastSent: Map<String, Any>? = null

    /** Anonymous sign-in, once per install (Firebase keeps it). Returns the rider's id or null. */
    suspend fun signIn(): String? {
        val a = auth ?: return null
        a.currentUser?.uid?.let { return it }
        _status.value = CloudStatus.SIGNING_IN
        val id = runCatching { withTimeoutOrNull(15_000) { a.signInAnonymously().await().user?.uid } }
            .onFailure {
                Log.w(TAG, "sign-in failed", it)
                val msg = it.message.orEmpty()
                _error.value = if ("ADMIN_ONLY_OPERATION" in msg || "OPERATION_NOT_ALLOWED" in msg || "disabled" in msg.lowercase()) {
                    "Pack server: turn on Anonymous sign-in in Firebase"
                } else "Couldn't reach the pack server"
            }.getOrNull()
        _status.value = if (id != null) CloudStatus.OFFLINE else CloudStatus.ERROR
        return id
    }

    /** Checks a code before joining. True = exists, false = no such pack, null = couldn't tell (offline). */
    suspend fun exists(code: String): Boolean? {
        val d = db ?: return null
        if (signIn() == null) return null
        return runCatching {
            withTimeoutOrNull(8_000) { d.getReference("packs").child(code).child("meta").get().await().exists() }
        }.getOrNull()
    }

    fun join(code: String, lead: Boolean, name: String) {
        leave()
        val d = db ?: return
        val me = uid ?: return
        val p = d.getReference("packs").child(code)
        pack = p
        _error.value = null
        if (lead) p.child("meta").setValue(mapOf("lead" to me, "created" to ServerValue.TIMESTAMP))

        connectedListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val on = s.getValue(Boolean::class.java) == true
                _status.value = if (on) CloudStatus.ONLINE else CloudStatus.OFFLINE
                if (on) onConnected(p, me, name)
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }.also { d.getReference(".info/connected").addValueEventListener(it) }
        offsetListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                serverOffset = (s.value as? Number)?.toLong() ?: 0L
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }.also { d.getReference(".info/serverTimeOffset").addValueEventListener(it) }

        listen(p.child("meta").child("lead"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) { _lead.value = s.getValue(String::class.java) }
            override fun onCancelled(error: DatabaseError) = Unit
        })
        listen(p.child("members"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                _members.value = s.children.mapNotNull { parse(it) }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "members: ${error.message}")
                _error.value = if (error.code == DatabaseError.PERMISSION_DENIED) "Pack server refused access (check the database rules)" else error.message
            }
        })
        listen(p.child("voice"), object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                _roster.value = s.children.mapNotNull { c -> c.key?.takeIf { c.value != null } }.toSet()
            }
            override fun onCancelled(error: DatabaseError) = Unit
        })
        // Voice set-up messages addressed to this rider: handle, then delete.
        val inbox = p.child("signal").child(me)
        inbox.removeValue()
        listen(inbox, object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, previousChildName: String?) {
                @Suppress("UNCHECKED_CAST")
                val msg = s.value as? Map<String, Any?>
                s.ref.removeValue()
                val from = msg?.get("from") as? String ?: return
                onSignal?.invoke(from, msg)
            }
            override fun onChildChanged(s: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(s: DataSnapshot) = Unit
            override fun onChildMoved(s: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) = Unit
        })
    }

    /** On every (re)connection: mark this rider online, and have the server mark them offline if the phone drops. */
    private fun onConnected(p: DatabaseReference, me: String, name: String) {
        val m = p.child("members").child(me)
        m.child("online").onDisconnect().setValue(false)
        m.child("online").setValue(true)
        if (inVoice) {
            p.child("voice").child(me).onDisconnect().removeValue()
            p.child("voice").child(me).setValue(true)
        }
        // Re-send the last position so a rider who reconnects appears straight away.
        lastSent?.let { m.updateChildren(it + mapOf("online" to true, "name" to name)) }
    }

    private fun listen(q: com.google.firebase.database.Query, l: ValueEventListener) {
        q.addValueEventListener(l)
        listeners += q to l
    }

    private fun listen(q: com.google.firebase.database.Query, l: ChildEventListener) {
        q.addChildEventListener(l)
        listeners += q to l
    }

    private fun parse(s: DataSnapshot): CloudMember? {
        val id = s.key ?: return null
        val lat = (s.child("lat").value as? Number)?.toDouble() ?: return null
        val lng = (s.child("lng").value as? Number)?.toDouble() ?: return null
        val serverTs = (s.child("ts").value as? Number)?.toLong() ?: 0L
        val still = (s.child("still").value as? Number)?.toLong() ?: 0L
        return CloudMember(
            id = id,
            name = (s.child("name").value as? String)?.take(24)?.ifBlank { null } ?: "Rider",
            lat = lat, lng = lng,
            speedKmh = (s.child("speed").value as? Number)?.toFloat() ?: 0f,
            bearing = (s.child("brg").value as? Number)?.toFloat() ?: -1f,
            ts = if (serverTs == 0L) 0L else serverTs - serverOffset,
            stillSince = if (still == 0L) 0L else still - serverOffset,
            sos = s.child("sos").value == true,
            online = s.child("online").value != false,
            inVoice = false,
        )
    }

    /** Sends this rider's position. Timestamps go out on the server's clock so every phone agrees. */
    fun publish(name: String, lat: Double, lng: Double, speedKmh: Float, bearing: Float, stillSince: Long, sos: Boolean) {
        val p = pack ?: return
        val me = uid ?: return
        val data = mapOf<String, Any>(
            "name" to name.take(24),
            "lat" to lat, "lng" to lng,
            "speed" to Math.round(speedKmh * 10) / 10.0,
            "brg" to Math.round(bearing).toInt(),
            "ts" to ServerValue.TIMESTAMP,
            "still" to (if (stillSince == 0L) 0L else stillSince + serverOffset),
            "sos" to sos,
            "online" to true,
        )
        lastSent = data
        p.child("members").child(me).updateChildren(data)
    }

    override fun setVoicePresence(on: Boolean) {
        inVoice = on
        val p = pack ?: return
        val me = uid ?: return
        val r = p.child("voice").child(me)
        if (on) {
            r.onDisconnect().removeValue()
            r.setValue(true)
        } else {
            r.removeValue()
            r.onDisconnect().cancel()
        }
    }

    override fun sendSignal(to: String, msg: Map<String, Any>) {
        val p = pack ?: return
        val me = uid ?: return
        p.child("signal").child(to).push().setValue(msg + ("from" to me))
    }

    /** Leave the pack: this rider disappears from everyone's list. */
    fun leave() {
        val d = db
        listeners.forEach { (q, l) ->
            when (l) {
                is ValueEventListener -> q.removeEventListener(l)
                is ChildEventListener -> q.removeEventListener(l)
            }
        }
        listeners.clear()
        connectedListener?.let { d?.getReference(".info/connected")?.removeEventListener(it) }
        connectedListener = null
        offsetListener?.let { d?.getReference(".info/serverTimeOffset")?.removeEventListener(it) }
        offsetListener = null
        val p = pack
        val me = uid
        if (p != null && me != null) {
            p.child("members").child(me).child("online").onDisconnect().cancel()
            p.child("voice").child(me).onDisconnect().cancel()
            p.child("members").child(me).removeValue()
            p.child("voice").child(me).removeValue()
            p.child("signal").child(me).removeValue()
        }
        pack = null
        lastSent = null
        inVoice = false
        _members.value = emptyList()
        _roster.value = emptySet()
        _lead.value = null
        if (configured) _status.value = if (uid != null) CloudStatus.OFFLINE else _status.value
    }

    fun warmUp() {
        if (configured && uid == null) scope.launch { signIn() }
    }

    companion object {
        private const val TAG = "CloudLink"
    }
}
