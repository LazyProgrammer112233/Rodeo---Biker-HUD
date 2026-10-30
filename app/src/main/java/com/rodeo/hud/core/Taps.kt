package com.rodeo.hud.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.sqrt

/** Where on the phone's top edge (as the rider sees it) a knock landed. */
enum class TapZone { LEFT, MIDDLE, RIGHT }

/** What a group of knocks asks for. */
enum class MusicAction { PREVIOUS, PLAY_PAUSE, NEXT }

/**
 * One knock on the phone's frame, as the motion sensors felt it. Device axes: x across the short
 * side, y along the long side, z out of the screen.
 */
class Knock(
    val atMs: Long,
    /** Peak of the knock over the background shake. */
    val snr: Float,
    /** Peak acceleration of the knock (m/s², gravity and slow motion removed). */
    val peak: Float,
    /** Direction of the first push, unit vector. */
    val push: FloatArray,
    /** Rotation in the first 30 ms after the knock (rad). */
    val twist: FloatArray,
    /** How much shake is left 25 to 45 ms later, against the first 12 ms (a knock dies fast, a pothole doesn't). */
    val ring: Float,
) {
    /** The features the classifier compares: push direction plus the twist, scaled to a similar size. */
    fun features(): FloatArray = floatArrayOf(push[0], push[1], push[2], twist[0] * TWIST_SCALE, twist[1] * TWIST_SCALE, twist[2] * TWIST_SCALE)

    override fun toString() = "Knock(snr=%.1f peak=%.2f push=%.2f,%.2f,%.2f twist=%.4f,%.4f,%.4f ring=%.2f)"
        .format(snr, peak, push[0], push[1], push[2], twist[0], twist[1], twist[2], ring)

    companion object {
        /** A crisp knock twists a mounted phone by about 0.005 to 0.03 rad; this brings that near 1. */
        const val TWIST_SCALE = 60f
    }
}

/**
 * Finds knocks in the accelerometer and gyroscope streams (400 Hz on most phones, works from about
 * 100 Hz). A knock is a sharp jolt well above the background shake that dies away within a few
 * tens of milliseconds; engine buzz is steady and potholes are slow, so both are left out.
 *
 * Pure logic, fed by [TapSensor]; times are milliseconds (fractions allowed).
 */
class KnockDetector(
    /** Smallest jolt that counts, in m/s² after removing gravity. */
    var minPeak: Float = 2.0f,
    /** How far above the background shake a knock must rise. */
    var minSnr: Float = 5f,
    /** Most shake allowed after the knock, against its start (see [Knock.ring]). */
    var maxRing: Float = 0.6f,
    private val onKnock: (Knock) -> Unit,
) {
    private val n = 128
    private val ta = DoubleArray(n)
    private val ax = FloatArray(n)
    private val ay = FloatArray(n)
    private val az = FloatArray(n)
    private var ai = 0
    private var count = 0

    private val tg = DoubleArray(n)
    private val gx = FloatArray(n)
    private val gy = FloatArray(n)
    private val gz = FloatArray(n)
    private var gi = 0
    private var gCount = 0

    private var lastT = Double.NaN
    private val raw = FloatArray(3)
    private val hp = FloatArray(3)
    /** Background shake: a slow average of the filtered magnitude. */
    private var floor = 0.3f
    private var pendingAt = Double.NaN
    private var pendingPeak = 0f
    private var lastKnockAt = -1e9

    fun reset() {
        count = 0; gCount = 0; lastT = Double.NaN; pendingAt = Double.NaN; floor = 0.3f
        hp.fill(0f)
    }

    fun gyro(t: Double, x: Float, y: Float, z: Float) {
        tg[gi] = t; gx[gi] = x; gy[gi] = y; gz[gi] = z
        gi = (gi + 1) % n; gCount++
    }

    fun accel(t: Double, x: Float, y: Float, z: Float) {
        if (lastT.isNaN()) {
            lastT = t; raw[0] = x; raw[1] = y; raw[2] = z
            return
        }
        val dt = (t - lastT).coerceIn(0.5, 50.0)
        lastT = t
        // First-order high-pass at about 25 Hz: removes gravity, leaning, braking and road swell.
        val rc = 1000.0 / (2 * Math.PI * 25.0)
        val a = (rc / (rc + dt)).toFloat()
        hp[0] = a * (hp[0] + x - raw[0]); hp[1] = a * (hp[1] + y - raw[1]); hp[2] = a * (hp[2] + z - raw[2])
        raw[0] = x; raw[1] = y; raw[2] = z
        ta[ai] = t; ax[ai] = hp[0]; ay[ai] = hp[1]; az[ai] = hp[2]
        ai = (ai + 1) % n; count++
        val m = sqrt(hp[0] * hp[0] + hp[1] * hp[1] + hp[2] * hp[2])

        if (pendingAt.isNaN()) {
            if (count > 40 && m >= minPeak && m >= minSnr * floor && t - lastKnockAt > 110) {
                pendingAt = t; pendingPeak = m
            } else {
                // The floor follows the shake over about two seconds; knocks are clipped so they don't raise it.
                val w = 1 - exp(-dt / 2000.0).toFloat()
                floor += w * (minOf(m, floor * 3f) - floor)
                floor = floor.coerceAtLeast(0.02f)
            }
        } else {
            pendingPeak = maxOf(pendingPeak, if (t - pendingAt <= 12) m else 0f)
            if (t - pendingAt >= 46) {
                val at = pendingAt
                pendingAt = Double.NaN
                lastKnockAt = at
                measure(at)?.let(onKnock)
            }
        }
    }

    /** Builds the knock that started at [t0] from the samples around it. */
    private fun measure(t0: Double): Knock? {
        var early = 0.0; var earlyN = 0; var late = 0.0; var lateN = 0
        var best = -1f; val push = FloatArray(3)
        val avail = minOf(count, n)
        for (k in 0 until avail) {
            val i = (ai - 1 - k + n * 2) % n
            val dt = ta[i] - t0
            val m2 = ax[i] * ax[i] + ay[i] * ay[i] + az[i] * az[i]
            when {
                dt in -3.0..12.0 -> {
                    early += m2; earlyN++
                    if (m2 > best) { best = m2; push[0] = ax[i]; push[1] = ay[i]; push[2] = az[i] }
                }
                dt in 25.0..46.0 -> { late += m2; lateN++ }
            }
        }
        if (earlyN == 0 || best <= 0f) return null
        val ring = if (lateN == 0) 0f else sqrt((late / lateN) / (early / earlyN)).toFloat()
        val len = sqrt(best)
        for (j in 0..2) push[j] /= len

        // Twist: the gyro over the first 30 ms, less its average in the 60 ms before (the bike's own lean and sway).
        val twist = FloatArray(3)
        val base = FloatArray(3); var baseN = 0
        val gAvail = minOf(gCount, n)
        for (k in 0 until gAvail) {
            val i = (gi - 1 - k + n * 2) % n
            val dt = tg[i] - t0
            if (dt in -60.0..-3.0) { base[0] += gx[i]; base[1] += gy[i]; base[2] += gz[i]; baseN++ }
        }
        if (baseN > 0) for (j in 0..2) base[j] /= baseN
        var prevT = Double.NaN
        for (k in gAvail - 1 downTo 0) {
            val i = (gi - 1 - k + n * 2) % n
            val dt = tg[i] - t0
            if (dt in 0.0..30.0) {
                val step = if (prevT.isNaN()) 2.5 else (tg[i] - prevT)
                val s = (step / 1000.0).toFloat()
                twist[0] += (gx[i] - base[0]) * s; twist[1] += (gy[i] - base[1]) * s; twist[2] += (gz[i] - base[2]) * s
            }
            prevT = tg[i]
        }
        val knock = Knock(t0.toLong(), pendingPeak / floor, pendingPeak, push, twist, ring)
        return if (ring <= maxRing) knock else null
    }

    val background: Float get() = floor
}

/**
 * Tells which part of the edge a knock hit. After the rider's tap setup it compares each knock with
 * the knocks they made on their own mount; before that it goes by the twist (a knock on the left end
 * turns the phone one way, on the right end the other way, in the middle hardly at all).
 */
class TapClassifier(var templates: TapTemplates? = null) {
    /** Zone of a knock, or null when it looks like none of the trained taps (a bump). */
    fun zone(k: Knock): TapZone? {
        val t = templates
        if (t != null && t.ready) return t.nearest(k.features())
        // Untrained: a knock pushing in the screen's plane, sorted by the twist about the screen's axis.
        val turn = k.twist[2]
        return when {
            turn > UNTRAINED_TWIST -> TapZone.LEFT
            turn < -UNTRAINED_TWIST -> TapZone.RIGHT
            else -> TapZone.MIDDLE
        }
    }

    companion object {
        const val UNTRAINED_TWIST = 0.004f
    }
}

/** The rider's own knocks from tap setup, per zone, in device axes. */
class TapTemplates(val samples: Map<TapZone, List<FloatArray>>) {
    val ready: Boolean get() = TapZone.entries.all { (samples[it]?.size ?: 0) >= 3 }

    private val centres: Map<TapZone, FloatArray> = samples.mapValues { (_, list) -> mean(list) }

    /** How far a knock may sit from its zone's taps, from how spread out the rider's own taps were. */
    private val radius: Float = run {
        val d = samples.flatMap { (z, list) -> list.map { dist(it, centres.getValue(z)) } }
        if (d.isEmpty()) 1f else (d.average().toFloat() * 3f).coerceIn(0.6f, 1.6f)
    }

    fun nearest(f: FloatArray): TapZone? {
        // Nearest of the rider's own taps (k = 3 vote), then a check that it is close enough to that zone at all.
        val near = samples.flatMap { (z, list) -> list.map { z to dist(f, it) } }.sortedBy { it.second }.take(3)
        val zone = near.groupingBy { it.first }.eachCount().maxByOrNull { it.value }?.key ?: return null
        return if (dist(f, centres.getValue(zone)) <= radius) zone else null
    }

    fun toJson(): String = JSONObject().apply {
        samples.forEach { (z, list) -> put(z.name, JSONArray(list.map { f -> JSONArray(f.map { it.toDouble() }) })) }
    }.toString()

    companion object {
        fun fromJson(s: String?): TapTemplates? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(s)
                TapTemplates(
                    TapZone.entries.associateWith { z ->
                        val a = o.optJSONArray(z.name) ?: JSONArray()
                        (0 until a.length()).map { i -> a.getJSONArray(i).let { f -> FloatArray(f.length()) { j -> f.getDouble(j).toFloat() } } }
                    },
                )
            }.getOrNull()
        }

        private fun mean(list: List<FloatArray>): FloatArray {
            val m = FloatArray(list.firstOrNull()?.size ?: 6)
            list.forEach { f -> for (j in m.indices) m[j] += f[j] / list.size }
            return m
        }

        fun dist(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (j in a.indices) { val d = a[j] - b[j]; s += d * d }
            return sqrt(s)
        }
    }
}

/**
 * Turns knocks into music actions: one tap on the left = previous, one in the middle = play / pause,
 * two quick taps on the right = next. Waits [GAP_MS] after the last knock to count them. More than
 * two knocks in a row is the road, not the rider, and is ignored.
 */
class TapGrouper(private val onAction: (MusicAction, TapZone, Int) -> Unit, private val onHint: (TapZone, Int) -> Unit = { _, _ -> }) {
    private var zone: TapZone? = null
    private var taps = 0
    private var lastAt = 0L

    fun knock(z: TapZone, at: Long) {
        if (taps > 0 && at - lastAt > GAP_MS) flush(at)
        if (taps == 0) zone = z
        taps++
        lastAt = at
    }

    /** Call regularly (or [GAP_MS] after a knock); fires the action once the taps have finished. */
    fun flush(now: Long) {
        if (taps == 0 || now - lastAt < GAP_MS) return
        val z = zone ?: return
        val n = taps
        taps = 0
        zone = null
        val action = when {
            z == TapZone.LEFT && n == 1 -> MusicAction.PREVIOUS
            z == TapZone.MIDDLE && n == 1 -> MusicAction.PLAY_PAUSE
            z == TapZone.RIGHT && n == 2 -> MusicAction.NEXT
            else -> null
        }
        if (action != null) onAction(action, z, n) else if (n <= 2) onHint(z, n)
    }

    fun reset() { taps = 0; zone = null }

    companion object {
        const val GAP_MS = 380L
    }
}

/** Keeps the trained knocks right when the phone is mounted the other way round (turned 180°). */
fun flipKnock(f: FloatArray): FloatArray = floatArrayOf(-f[0], -f[1], f[2], -f[3], -f[4], f[5])
