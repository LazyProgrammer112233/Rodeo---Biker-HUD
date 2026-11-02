package com.rodeo.hud.pack

/**
 * Decides what the pack should tell the rider, by voice and on screen, as positions come in.
 * Plain logic with hysteresis so an alert fires once when something changes, never on every fix:
 *
 * - a rider falls [gapM] behind (or gets that far ahead), and "caught up" when they close back in
 * - the whole pack regroups within [regroupM] after being spread out
 * - a rider has stopped for two minutes
 * - contact with a rider is lost (no position for 90 s) and comes back
 * - a rider's SOS
 * - riders joining and leaving
 */
class PackAlerts(var gapM: Double = 2_000.0, var regroupM: Double = 500.0) {

    enum class Kind { JOINED, LEFT, FELL_BEHIND, PULLED_AHEAD, CAUGHT_UP, REGROUPED, STOPPED, MOVING_AGAIN, LOST, BACK, SOS, SOS_OVER }

    data class Alert(
        val kind: Kind,
        val riderId: String?,
        /** Banner / pop-up text. */
        val text: String,
        /** What the voice says. */
        val speech: String,
        /** 0 info, 1 warning, 2 danger. */
        val severity: Int,
        /** True while the condition lasts (the banner stays); false for one-off news. */
        val sticky: Boolean,
    )

    private class Track(var name: String) {
        var apart = false
        var stopped = false
        var lost = false
        var sos = false
    }

    private val tracks = HashMap<String, Track>()
    private var started = false
    private var spread = false

    /** Riders whose conditions are still on (for the banners): kind + rider id. */
    val ongoing: Set<Pair<Kind, String>>
        get() = tracks.flatMap { (id, t) ->
            listOfNotNull(
                (Kind.FELL_BEHIND to id).takeIf { t.apart },
                (Kind.STOPPED to id).takeIf { t.stopped },
                (Kind.LOST to id).takeIf { t.lost },
                (Kind.SOS to id).takeIf { t.sos },
            )
        }.toSet()

    fun reset() {
        tracks.clear()
        started = false
        spread = false
    }

    /**
     * [me] is this rider (null before the first fix), [others] everyone else, [headingDeg] this
     * rider's direction of travel when moving (null when parked, so "behind" isn't guessed).
     */
    fun update(now: Long, me: PackMember?, others: List<PackMember>, headingDeg: Double?): List<Alert> {
        val out = ArrayList<Alert>()
        val ids = others.map { it.id }.toSet()

        // Joins and leaves. The riders already there when we join are not news.
        for (m in others) if (m.id !in tracks) {
            tracks[m.id] = Track(m.name)
            if (started) out += Alert(Kind.JOINED, m.id, "${m.name} joined the pack", "${m.name} joined the pack", 0, false)
        }
        for (id in tracks.keys.toList()) if (id !in ids) {
            val t = tracks.remove(id) ?: continue
            out += Alert(Kind.LEFT, id, "${t.name} left the pack", "${t.name} left the pack", 0, false)
        }
        started = true

        for (m in others) {
            val t = tracks.getValue(m.id)
            t.name = m.name

            // SOS first: it matters more than anything else.
            if (m.sos && !t.sos) {
                t.sos = true
                val where = me?.let { " " + where(it, m, headingDeg, spoken = false) }.orEmpty()
                val whereSpoken = me?.let { ", " + where(it, m, headingDeg, spoken = true) }.orEmpty()
                out += Alert(Kind.SOS, m.id, "${m.name} SOS$where", "Alert. ${m.name} has triggered S O S$whereSpoken.", 2, true)
            } else if (!m.sos && t.sos) {
                t.sos = false
                out += Alert(Kind.SOS_OVER, m.id, "${m.name} cancelled SOS", "${m.name} is OK", 0, false)
            }

            // Contact lost / back.
            val stale = m.stale(now) || !m.online && m.ageMs(now) > 20_000
            if (stale && !t.lost) {
                t.lost = true
                val where = me?.let { " · last seen " + where(it, m, headingDeg, spoken = false) }.orEmpty()
                out += Alert(Kind.LOST, m.id, "Lost contact with ${m.name}$where", "Lost contact with ${m.name}", 1, true)
                continue
            } else if (!stale && t.lost) {
                t.lost = false
                out += Alert(Kind.BACK, m.id, "${m.name} is back in contact", "${m.name} is back", 0, false)
            }
            if (t.lost) continue

            // Stopped for two minutes / moving again.
            val stopped = m.stopped(now)
            if (stopped && !t.stopped) {
                t.stopped = true
                val where = me?.let { " · " + where(it, m, headingDeg, spoken = false) }.orEmpty()
                val whereSpoken = me?.let { ", " + where(it, m, headingDeg, spoken = true) }.orEmpty()
                out += Alert(Kind.STOPPED, m.id, "${m.name} has stopped$where", "${m.name} has stopped$whereSpoken", 1, true)
            } else if (!stopped && t.stopped && m.speedKmh > 5f) {
                t.stopped = false
                out += Alert(Kind.MOVING_AGAIN, m.id, "${m.name} is riding again", "${m.name} is riding again", 0, false)
            }

            // Gap: fires past gapM, re-arms once they are back inside 60 % of it.
            if (me != null) {
                val d = PackLogic.distanceM(me.lat, me.lng, m.lat, m.lng)
                if (!t.apart && d > gapM) {
                    t.apart = true
                    val rel = headingDeg?.let { PackLogic.relativeBearing(it, PackLogic.bearing(me.lat, me.lng, m.lat, m.lng)) }
                    val side = PackLogic.side(rel)
                    val (text, speech, kind) = when (side) {
                        PackLogic.Side.AHEAD -> Triple(
                            "${m.name} is ${PackLogic.distanceText(d)} ahead", "${m.name} is ${PackLogic.distanceSpoken(d)} ahead", Kind.PULLED_AHEAD,
                        )
                        PackLogic.Side.BEHIND -> Triple(
                            "${m.name} fell ${PackLogic.distanceText(d)} behind", "${m.name} fell ${PackLogic.distanceSpoken(d)} behind", Kind.FELL_BEHIND,
                        )
                        else -> Triple(
                            "${m.name} is ${PackLogic.distanceText(d)} ${PackLogic.sideWord(side)}",
                            "${m.name} is ${PackLogic.distanceSpoken(d)} ${PackLogic.sideWord(side)}", Kind.FELL_BEHIND,
                        )
                    }
                    out += Alert(kind, m.id, text, speech, 1, true)
                } else if (t.apart && d < gapM * 0.6) {
                    t.apart = false
                    out += Alert(Kind.CAUGHT_UP, m.id, "${m.name} caught up · ${PackLogic.distanceText(d)}", "${m.name} caught up", 0, false)
                }
            }
        }

        // Regrouped: everyone in contact is within regroupM of this rider after the pack was spread.
        if (me != null) {
            val live = others.filter { !tracks.getValue(it.id).lost }
            val far = live.maxOfOrNull { PackLogic.distanceM(me.lat, me.lng, it.lat, it.lng) } ?: 0.0
            if (far > gapM) spread = true
            if (spread && live.isNotEmpty() && far <= regroupM) {
                spread = false
                out += Alert(
                    Kind.REGROUPED, null, "Pack regrouped · everyone within ${PackLogic.distanceText(regroupM)}",
                    "Pack regrouped. Everyone is within ${PackLogic.distanceSpoken(regroupM)}", 0, false,
                )
            }
        }
        return out
    }

    private fun where(me: PackMember, m: PackMember, headingDeg: Double?, spoken: Boolean): String {
        val d = PackLogic.distanceM(me.lat, me.lng, m.lat, m.lng)
        val rel = headingDeg?.let { PackLogic.relativeBearing(it, PackLogic.bearing(me.lat, me.lng, m.lat, m.lng)) }
        val side = PackLogic.sideWord(PackLogic.side(rel))
        return if (spoken) "${PackLogic.distanceSpoken(d)} $side" else "${PackLogic.distanceText(d)} $side"
    }
}

/** Keeps the voice from talking over itself: one pack announcement at a time, danger first. */
class SpeechGate(private val minGapMs: Long = 6_000) {
    private var lastAt = 0L
    private val pending = ArrayList<PackAlerts.Alert>()

    fun offer(a: PackAlerts.Alert) {
        // A newer alert about the same rider replaces an older unspoken one.
        pending.removeAll { it.riderId != null && it.riderId == a.riderId }
        pending += a
        pending.sortByDescending { it.severity }
        while (pending.size > 4) pending.removeAt(pending.lastIndex)
    }

    /** The next line to say now, or null to wait. Danger never waits. */
    fun next(now: Long): PackAlerts.Alert? {
        val top = pending.firstOrNull() ?: return null
        if (top.severity < 2 && now - lastAt < minGapMs) return null
        pending.removeAt(0)
        lastAt = now
        return top
    }

    fun clear() = pending.clear()
}
