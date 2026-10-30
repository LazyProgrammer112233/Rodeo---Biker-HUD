package com.rodeo.hud.logic

import com.rodeo.hud.core.Knock
import com.rodeo.hud.core.KnockDetector
import com.rodeo.hud.core.MusicAction
import com.rodeo.hud.core.TapClassifier
import com.rodeo.hud.core.TapGrouper
import com.rodeo.hud.core.TapTemplates
import com.rodeo.hud.core.TapZone
import com.rodeo.hud.core.WaveCounter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
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

    // --- Taps on the frame ---

    /**
     * 400 Hz accelerometer and gyroscope: gravity, engine buzz and sensor noise, plus whatever
     * [extra] adds at time t (ms) as (accel x, y, z, gyro z).
     */
    private fun ride(ms: Int, buzz: Float = 0.25f, seed: Long = 1, extra: (Double) -> FloatArray = { FloatArray(4) }): List<Knock> {
        val found = mutableListOf<Knock>()
        val d = KnockDetector { found += it }
        val rnd = Random(seed)
        var t = 0.0
        while (t < ms) {
            val e = extra(t)
            val hum = buzz * sin(2 * PI * 62 * t / 1000).toFloat()
            d.gyro(t, 0.01f * rnd.nextGaussian().toFloat(), 0.01f * rnd.nextGaussian().toFloat(), e[3] + 0.01f * rnd.nextGaussian().toFloat())
            d.accel(t, -9.6f + e[0] + hum + 0.03f * rnd.nextGaussian().toFloat(), 1.2f + e[1] + 0.5f * hum, 0.8f + e[2] + 0.03f * rnd.nextGaussian().toFloat())
            t += 2.5
        }
        return found
    }

    /** A fingertip knock at [at]: a ringing push along x that dies in a few ms, with a twist about z. */
    private fun knock(at: Double, size: Float = 4f, twist: Float = 0f): (Double) -> FloatArray = { t ->
        val dt = t - at
        if (dt < 0 || dt > 40) FloatArray(4)
        else {
            val ring = (size * exp(-dt / 5) * sin(2 * PI * 170 * dt / 1000 + PI / 2)).toFloat()
            floatArrayOf(ring, 0.2f * ring, 0f, if (dt < 15) twist else 0f)
        }
    }

    private fun sum(vararg fs: (Double) -> FloatArray): (Double) -> FloatArray = { t ->
        fs.map { it(t) }.reduce { a, b -> FloatArray(4) { a[it] + b[it] } }
    }

    @Test fun aKnockIsFoundOnceWithItsTwist() {
        val k = ride(2_000, extra = knock(1_000.0, twist = 0.9f))
        assertEquals(1, k.size)
        assertEquals(1_000.0, k[0].atMs.toDouble(), 5.0)
        assertEquals(TapZone.LEFT, TapClassifier().zone(k[0]))
    }

    @Test fun untrainedZonesFollowTheTwist() {
        val c = TapClassifier()
        assertEquals(TapZone.RIGHT, c.zone(ride(2_000, extra = knock(1_000.0, twist = -0.9f)).single()))
        assertEquals(TapZone.MIDDLE, c.zone(ride(2_000, extra = knock(1_000.0, twist = 0.05f)).single()))
    }

    @Test fun twoQuickKnocksAreTwo() {
        assertEquals(2, ride(2_000, extra = sum(knock(800.0), knock(1_050.0))).size)
    }

    @Test fun engineBuzzAndPotholesAreNotKnocks() {
        // Hard buzz on its own.
        assertEquals(0, ride(4_000, buzz = 2.5f).size)
        // A pothole: a big, slow jolt (about 120 ms long).
        val pothole: (Double) -> FloatArray = { t ->
            val dt = t - 1_500
            if (dt < 0 || dt > 120) FloatArray(4) else floatArrayOf((7 * sin(PI * dt / 120)).toFloat(), 0f, 3f * sin(PI * dt / 120).toFloat(), 0f)
        }
        assertEquals(0, ride(3_000, extra = pothole).size)
    }

    @Test fun aKnockStillShowsThroughHeavyBuzz() {
        assertEquals(1, ride(3_000, buzz = 0.8f, extra = knock(1_500.0, size = 6f)).size)
    }

    private fun group(vararg knocks: Pair<TapZone, Long>): List<Any> {
        val out = mutableListOf<Any>()
        val g = TapGrouper({ a, _, _ -> out += a }, { z, n -> out += "hint $z $n" })
        knocks.forEach { (z, at) -> g.flush(at); g.knock(z, at) }
        g.flush(knocks.last().second + 1_000)
        return out
    }

    @Test fun tapsBecomeMusicActions() {
        assertEquals(listOf(MusicAction.PREVIOUS), group(TapZone.LEFT to 0))
        assertEquals(listOf(MusicAction.PLAY_PAUSE), group(TapZone.MIDDLE to 0))
        assertEquals(listOf(MusicAction.NEXT), group(TapZone.RIGHT to 0, TapZone.RIGHT to 250))
        assertEquals(listOf("hint RIGHT 1"), group(TapZone.RIGHT to 0))
        // Three in a row is the road.
        assertEquals(emptyList<Any>(), group(TapZone.MIDDLE to 0, TapZone.MIDDLE to 200, TapZone.MIDDLE to 400))
        // Separate taps are separate.
        assertEquals(listOf(MusicAction.PREVIOUS, MusicAction.PLAY_PAUSE), group(TapZone.LEFT to 0, TapZone.MIDDLE to 1_000))
    }

    @Test fun trainedTapsMatchTheRidersOwnKnocks() {
        fun f(px: Float, tz: Float) = floatArrayOf(px, 0.1f, 0f, 0f, 0f, tz)
        val t = TapTemplates(
            mapOf(
                TapZone.LEFT to listOf(f(-1f, 0.9f), f(-0.95f, 1.1f), f(-1f, 1.0f)),
                TapZone.MIDDLE to listOf(f(-1f, 0f), f(-0.98f, 0.1f), f(-1f, -0.1f)),
                TapZone.RIGHT to listOf(f(-1f, -1f), f(-0.97f, -0.9f), f(-1f, -1.1f)),
            ),
        )
        assertEquals(TapZone.LEFT, t.nearest(f(-0.99f, 0.95f)))
        assertEquals(TapZone.RIGHT, t.nearest(f(-1f, -0.8f)))
        assertEquals(TapZone.MIDDLE, t.nearest(f(-1f, 0.05f)))
        // A jolt from a direction the rider never tapped (from below, say) is not a tap.
        assertNull(t.nearest(floatArrayOf(0f, 0f, 1f, 2f, 0f, 0f)))
        // Saved and loaded.
        assertEquals(TapZone.LEFT, TapTemplates.fromJson(t.toJson())!!.nearest(f(-1f, 1f)))
    }
}
