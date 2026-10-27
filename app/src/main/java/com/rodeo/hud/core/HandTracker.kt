package com.rodeo.hud.core

import kotlin.math.abs

enum class HandGesture { SWIPE_RIGHT, SWIPE_LEFT, PALM_COVER }

/** One camera frame's view of the rider's hand, from the on-device hand model. */
data class HandObservation(
    /** Hand centre across the picture, 0 = rider's left edge, 1 = rider's right edge. */
    val x: Float,
    /** The model's best guess at the pose ("Open_Palm", "Closed_Fist", ...) and its score. */
    val pose: String,
    val score: Float,
)

/**
 * Turns hand observations into gestures. Unlike the old brightness trick it only reacts to an
 * actual hand, so passing traffic, shadows and road vibration don't trigger anything.
 *
 *  - Open palm held still for [PALM_HOLD_MS]: play / pause ("talk to the hand").
 *  - Hand sweeping across at least [SWIPE_TRAVEL] of the picture: next / previous song.
 */
class HandTracker {
    private val track = ArrayDeque<Pair<Long, Float>>()
    private var palmSince = -1L
    private var palmX = 0f
    private var palmArmed = true
    private var cooldownUntil = 0L
    private var lastSeen = 0L

    /** Feed every analysed frame ([hand] null when no hand is in view). Returns a gesture or null. */
    fun onFrame(now: Long, hand: HandObservation?): HandGesture? {
        if (hand == null) {
            // A fast sweep often blurs out of view before it finishes; judge it on what was seen.
            val g = if (now - lastSeen < 250) swipe(now, leaving = true) else null
            if (now - lastSeen > 400) {
                track.clear()
                palmSince = -1
                palmArmed = true
            }
            return g
        }
        lastSeen = now
        track.addLast(now to hand.x)
        while (track.isNotEmpty() && now - track.first().first > WINDOW_MS) track.removeFirst()
        if (now < cooldownUntil) return null

        swipe(now, leaving = false)?.let { return it }

        val palm = hand.pose == "Open_Palm" && hand.score >= 0.55f
        if (!palm) {
            palmSince = -1
            palmArmed = true
            return null
        }
        if (palmSince < 0) {
            palmSince = now
            palmX = hand.x
        }
        if (abs(hand.x - palmX) > 0.12f) {
            // Moving palm: that's a sweep in progress, not a stop sign.
            palmSince = now
            palmX = hand.x
        }
        if (palmArmed && now - palmSince >= PALM_HOLD_MS) {
            palmArmed = false // one play/pause per raise of the hand
            cooldownUntil = now + 800
            track.clear()
            return HandGesture.PALM_COVER
        }
        return null
    }

    private fun swipe(now: Long, leaving: Boolean): HandGesture? {
        if (track.size < 3 || now < cooldownUntil) return null
        val first = track.first()
        val last = track.last()
        val travel = last.second - first.second
        val need = if (leaving) SWIPE_TRAVEL * 0.75f else SWIPE_TRAVEL
        if (abs(travel) < need || last.first - first.first > WINDOW_MS) return null
        // Mostly one direction, not a wobble.
        var with = 0
        val pts = track.toList()
        for (i in 1 until pts.size) if ((pts[i].second - pts[i - 1].second) * travel >= 0) with++
        if (with < (pts.size - 1) * 0.7f) return null
        cooldownUntil = now + 900
        track.clear()
        palmSince = -1
        return if (travel > 0) HandGesture.SWIPE_RIGHT else HandGesture.SWIPE_LEFT
    }

    companion object {
        const val PALM_HOLD_MS = 450L
        const val SWIPE_TRAVEL = 0.3f
        const val WINDOW_MS = 900L
    }
}
