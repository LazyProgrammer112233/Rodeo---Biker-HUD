package com.rodeo.hud.logic

import com.rodeo.hud.core.HandGesture
import com.rodeo.hud.core.HandObservation
import com.rodeo.hud.core.HandTracker
import com.rodeo.hud.core.WaveCounter
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureLogicTest {

    /** Replays near/far changes as (time, near), flushing every 10 ms like the sensor wrapper. */
    private fun replay(changes: List<Pair<Long, Boolean>>): List<Int> {
        val out = ArrayList<Int>()
        val c = WaveCounter { out += it }
        var i = 0
        var t = 0L
        val end = (changes.lastOrNull()?.first ?: 0) + 2_000
        while (t <= end) {
            while (i < changes.size && changes[i].first <= t) { c.onChange(changes[i].second, changes[i].first); i++ }
            c.flush(t)
            t += 10
        }
        return out
    }

    @Test fun oneWaveIsPlayPause() {
        assertEquals(listOf(1), replay(listOf(1_000L to true, 1_250L to false)))
    }

    @Test fun twoQuickWavesAreNext() {
        assertEquals(listOf(2), replay(listOf(1_000L to true, 1_200L to false, 1_500L to true, 1_700L to false)))
    }

    @Test fun threeQuickWavesArePrevious() {
        assertEquals(listOf(3), replay(listOf(0L to true, 150L to false, 400L to true, 550L to false, 800L to true, 950L to false)))
    }

    @Test fun separateWavesAreSeparate() {
        assertEquals(listOf(1, 1), replay(listOf(0L to true, 200L to false, 2_000L to true, 2_200L to false)))
    }

    @Test fun longCoverIsIgnored() {
        assertEquals(emptyList<Int>(), replay(listOf(0L to true, 4_000L to false)))
    }

    @Test fun flickerIsIgnored() {
        assertEquals(emptyList<Int>(), replay(listOf(0L to true, 30L to false)))
    }

    // --- Camera hand tracking ---

    private fun frames(from: Long, to: Long, step: Long = 66, f: (Long) -> HandObservation?): List<Pair<Long, HandObservation?>> =
        (from..to step step).map { it to f(it) }

    private fun run(seq: List<Pair<Long, HandObservation?>>): List<HandGesture> {
        val t = HandTracker()
        return seq.mapNotNull { (time, obs) -> t.onFrame(time, obs) }
    }

    @Test fun palmHeldStillPauses() {
        val g = run(frames(0, 1_200) { HandObservation(0.5f, "Open_Palm", 0.9f) })
        assertEquals(listOf(HandGesture.PALM_COVER), g)
    }

    @Test fun briefPalmDoesNothing() {
        val g = run(frames(0, 300) { HandObservation(0.5f, "Open_Palm", 0.9f) } + frames(366, 1_000) { null })
        assertEquals(emptyList<HandGesture>(), g)
    }

    @Test fun sweepRightIsNext() {
        val g = run(frames(0, 600) { t -> HandObservation(0.15f + t / 600f * 0.7f, "None", 0.3f) } + frames(666, 1_200) { null })
        assertEquals(listOf(HandGesture.SWIPE_RIGHT), g)
    }

    @Test fun sweepLeftThatBlursOutStillCounts() {
        // Seen for 300 ms moving left, then the hand blurs out of view.
        val g = run(frames(0, 330) { t -> HandObservation(0.8f - t / 330f * 0.28f, "None", 0.3f) } + frames(396, 1_000) { null })
        assertEquals(listOf(HandGesture.SWIPE_LEFT), g)
    }

    @Test fun wobbleIsNotASwipe() {
        val g = run(frames(0, 900) { t -> HandObservation(0.5f + if ((t / 66) % 2 == 0L) 0.12f else -0.12f, "Closed_Fist", 0.8f) })
        assertEquals(emptyList<HandGesture>(), g)
    }

    @Test fun holdingPalmOnlyTriggersOnce() {
        val g = run(frames(0, 4_000) { HandObservation(0.5f, "Open_Palm", 0.9f) })
        assertEquals(listOf(HandGesture.PALM_COVER), g)
    }
}
