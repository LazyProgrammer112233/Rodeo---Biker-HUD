package com.rodeo.hud.core

/** Counts covers of the sensor into gestures. No Android types, so it is unit tested. */
class WaveCounter(private val emit: (Int) -> Unit) {
    private var nearSince = -1L
    private var count = 0
    private var lastRelease = 0L
    private var blocked = false

    fun onChange(near: Boolean, now: Long) {
        if (near) {
            if (nearSince < 0) nearSince = now
            return
        }
        if (nearSince < 0) return
        val held = now - nearSince
        nearSince = -1
        when {
            held > MAX_COVER_MS -> { blocked = false; count = 0 } // not a gesture
            held < MIN_COVER_MS -> Unit // sensor flicker
            else -> {
                if (count > 0 && now - lastRelease > GAP_MS) flush(now)
                count++
                lastRelease = now
                if (count >= 3) flush(now)
            }
        }
    }

    /** Called a moment after the last change: if no further wave came, the gesture is done. */
    fun flush(now: Long) {
        if (nearSince >= 0) {
            // Still covered: a long hold is not a wave; wait for it to clear.
            if (now - nearSince > MAX_COVER_MS) { count = 0; blocked = true }
            return
        }
        if (blocked) { blocked = false; count = 0; return }
        if (count > 0 && now - lastRelease >= GAP_MS) {
            val n = count
            count = 0
            emit(n)
        } else if (count >= 3) {
            count = 0
            emit(3)
        }
    }

    fun reset() {
        nearSince = -1
        count = 0
        blocked = false
    }

    companion object {
        /** Shorter than this is sensor noise. */
        const val MIN_COVER_MS = 60L
        /** Waves closer together than this belong to the same gesture. */
        const val GAP_MS = 650L
        /** Longer than this is rain, a mount arm or a pocket, not a hand. */
        const val MAX_COVER_MS = 2_500L
    }
}
