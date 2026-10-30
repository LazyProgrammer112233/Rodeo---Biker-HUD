package com.rodeo.hud.logic

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
}
