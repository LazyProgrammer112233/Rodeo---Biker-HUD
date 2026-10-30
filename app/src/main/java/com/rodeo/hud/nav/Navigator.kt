package com.rodeo.hud.nav

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where the rider is along the route, recomputed on every GPS fix. */
data class NavProgress(
    /** Index into [Route.steps] of the next manoeuvre. */
    val stepIndex: Int,
    val toManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Distance from the route line right now. */
    val offRouteM: Double,
    /** Off the line for long enough that a new route is needed. */
    val offRoute: Boolean,
    val arrived: Boolean,
    /** Position snapped onto the route, for drawing the road still ahead. */
    val snapped: LatLng,
    /** Route vertex just behind [snapped]. */
    val segment: Int,
)

/**
 * The turn-by-turn engine: snaps GPS onto the route, finds the next manoeuvre, decides when to
 * speak, and spots when the rider has left the route. No Android types, so it is unit tested by
 * replaying rides along real routes.
 */
class Navigator(val route: Route, private val speak: (String) -> Unit) {
    private val pts = route.points
    private val cum = DoubleArray(pts.size)
    private val stepAt: DoubleArray
    private var seg = 0
    private var offSince = -1L
    private var offCount = 0
    private val spoken = HashMap<Int, Int>()
    private var arrivedSaid = false
    private var started = false
    private var onRouteSeen = false
    private var firstFix: LatLng? = null

    init {
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + metres(pts[i - 1], pts[i])
        stepAt = DoubleArray(route.steps.size) { cum[route.steps[it].at.coerceIn(0, pts.size - 1)] }
    }

    val totalM: Double get() = if (pts.isEmpty()) 0.0 else cum.last()

    /**
     * Feed one GPS fix. [speedMps] and [bearing] may be unknown (null), [accuracyM] is the fix's
     * reported accuracy, [nowMs] the fix time.
     */
    fun update(lat: Double, lng: Double, speedMps: Float?, bearing: Float?, accuracyM: Float?, nowMs: Long): NavProgress {
        val here = LatLng(lat, lng)
        val (bestSeg, bestT, bestD) = match(here, bearing, speedMps)
        seg = bestSeg
        val a = pts[bestSeg]
        val b = pts[(bestSeg + 1).coerceAtMost(pts.size - 1)]
        val along = cum[bestSeg] + (cum[(bestSeg + 1).coerceAtMost(pts.size - 1)] - cum[bestSeg]) * bestT
        val snapped = LatLng(a.lat + (b.lat - a.lat) * bestT, a.lng + (b.lng - a.lng) * bestT)

        // Off route: clearly away from the line (beyond what GPS error explains) for 3 fixes / 4 s.
        val limit = max(40.0, (accuracyM ?: 10f) * 1.5 + 15.0)
        if (bestD > limit) {
            if (offSince < 0) offSince = nowMs
            offCount++
        } else {
            offSince = -1
            offCount = 0
        }
        if (bestD <= limit) onRouteSeen = true
        val start = firstFix ?: here.also { firstFix = it }
        // Starting in a car park or a lane the map doesn't know is not "off route" until the
        // rider has either been on the line once or clearly moved off on their own way.
        val offRoute = offCount >= 3 && nowMs - offSince >= 4_000 && (onRouteSeen || metres(start, here) > 150.0)

        val remaining = (totalM - along).coerceAtLeast(0.0)
        val toDest = metres(here, pts.last())
        val arrived = !offRoute && (remaining < 30.0 || (toDest < 35.0 && remaining < 120.0))

        var k = route.steps.indices.firstOrNull { it > 0 && stepAt[it] > along + 3.0 } ?: (route.steps.size - 1)
        if (k < 0) k = 0
        val toManeuver = (stepAt.getOrElse(k) { totalM } - along).coerceAtLeast(0.0)
        val remainingS = remainingTime(k, toManeuver)

        if (!offRoute) voice(k, toManeuver, speedMps ?: 0f, arrived)

        return NavProgress(k, toManeuver, remaining, remainingS, bestD, offRoute, arrived, snapped, bestSeg)
    }

    /** Time left: the unfinished part of the current stretch plus every stretch after it. */
    private fun remainingTime(k: Int, toManeuver: Double): Double {
        if (route.steps.isEmpty()) return 0.0
        val prev = route.steps.getOrNull(k - 1)
        var t = if (prev != null && prev.lengthM > 1) prev.timeS * (toManeuver / prev.lengthM).coerceIn(0.0, 1.0) else 0.0
        for (j in k until route.steps.size) t += route.steps[j].timeS
        return t
    }

    private fun voice(k: Int, d: Double, speed: Float, arrived: Boolean) {
        if (arrived) {
            if (!arrivedSaid) {
                arrivedSaid = true
                speak("You have arrived")
            }
            return
        }
        val step = route.steps.getOrNull(k) ?: return
        val v = speed.toDouble()
        val far = (v * 45).coerceIn(400.0, 1500.0)
        val near = (v * 15).coerceIn(120.0, 400.0)
        val now = (v * 4.5).coerceIn(25.0, 90.0)
        val done = spoken[k] ?: 0
        if (!started) {
            // First words: the overall trip, then the first instruction if it is already close.
            started = true
            speak("Route ready. ${spokenDistance(totalM)}, about ${spokenDuration(route.timeS)}")
        }
        val stage = when {
            d <= now -> 3
            d <= near -> 2
            d <= far -> 1
            else -> 0
        }
        if (stage <= done || stage == 0) return
        spoken[k] = stage
        when (stage) {
            3 -> {
                speak(step.now)
                // The arrival sentence ("Your destination is on the left") already said it.
                if (step.maneuver == Maneuver.ARRIVE) arrivedSaid = true
            }
            // A long way out only if there was room for a separate warning before the near one.
            1 -> if (d > near + 150) speak("In ${spokenDistance(d)}, ${lowerFirst(step.alert)}")
            else -> speak("In ${spokenDistance(d)}, ${lowerFirst(step.alert)}")
        }
    }

    /** Closest route segment to [p], searched near the last match first so loops don't confuse it. */
    private fun match(p: LatLng, bearing: Float?, speed: Float?): Triple<Int, Double, Double> {
        if (pts.size < 2) return Triple(0, 0.0, if (pts.isEmpty()) 0.0 else metres(p, pts[0]))
        val local = search(p, (seg - 3).coerceAtLeast(0), aheadEnd(seg), bearing, speed, cum[seg])
        if (local.third <= 60.0) return local
        val global = search(p, 0, pts.size - 2, bearing, speed)
        return if (global.third + 20.0 < local.third) global else local
    }

    private fun aheadEnd(from: Int): Int {
        var j = from
        while (j < pts.size - 2 && cum[j] - cum[from] < 2_500.0) j++
        return j
    }

    /**
     * Best segment for [p] between [from] and [to]. With [origin] (where the rider was along the
     * route) a jump far ahead costs a little, so on a road ridden out and back the matcher keeps
     * the leg the rider is on instead of skipping to the return leg lying on top of it.
     */
    private fun search(p: LatLng, from: Int, to: Int, bearing: Float?, speed: Float?, origin: Double? = null): Triple<Int, Double, Double> {
        val cosLat = cos(p.lat * PI / 180.0)
        var best = Triple(from, 0.0, Double.MAX_VALUE)
        var bestScore = Double.MAX_VALUE
        val useBearing = bearing != null && (speed ?: 0f) > 3f
        for (i in from..to.coerceAtMost(pts.size - 2)) {
            val a = pts[i]
            val b = pts[i + 1]
            val ax = (a.lng - p.lng) * cosLat * M_PER_DEG
            val ay = (a.lat - p.lat) * M_PER_DEG
            val bx = (b.lng - p.lng) * cosLat * M_PER_DEG
            val by = (b.lat - p.lat) * M_PER_DEG
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val x = ax + dx * t
            val y = ay + dy * t
            val d = sqrt(x * x + y * y)
            var score = d
            if (useBearing && len2 > 4.0) {
                val segBearing = (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
                val diff = abs(RouteParser.signedTurn(segBearing, bearing!!.toDouble()))
                if (diff > 100) score += 30.0 // the other carriageway, or the road coming back
            }
            if (origin != null) score += max(0.0, cum[i] - origin - 80.0) / 25.0
            if (score < bestScore) {
                bestScore = score
                best = Triple(i, t, d)
            }
        }
        return best
    }

    companion object {
        private const val M_PER_DEG = 111_320.0

        fun metres(a: LatLng, b: LatLng): Double {
            val cosLat = cos((a.lat + b.lat) / 2 * PI / 180.0)
            val dx = (b.lng - a.lng) * cosLat * M_PER_DEG
            val dy = (b.lat - a.lat) * M_PER_DEG
            return sqrt(dx * dx + dy * dy)
        }

        /** "450 m", "1.2 km" for the turn card. */
        fun shortDistance(m: Double): String = when {
            m < 100 -> "${(m / 10).roundToInt() * 10} m"
            m < 1_000 -> "${(m / 50).roundToInt() * 50} m"
            m < 10_000 -> "%.1f km".format(java.util.Locale.US, m / 1000)
            else -> "${(m / 1000).roundToInt()} km"
        }

        /** "300 metres", "1.5 kilometres" for the voice. */
        fun spokenDistance(m: Double): String = when {
            m < 100 -> "${max(10, (m / 10).roundToInt() * 10)} metres"
            m < 1_000 -> "${(m / 50).roundToInt() * 50} metres"
            m < 1_050 -> "1 kilometre"
            m < 10_000 -> {
                val km = (m / 100).roundToInt() / 10.0
                if (km % 1.0 == 0.0) "${km.toInt()} kilometres" else "$km kilometres"
            }
            else -> "${(m / 1000).roundToInt()} kilometres"
        }

        fun spokenDuration(s: Double): String {
            val min = (s / 60).roundToInt().coerceAtLeast(1)
            return if (min < 60) "$min minute${if (min == 1) "" else "s"}"
            else {
                val h = min / 60
                val m = min % 60
                "$h hour${if (h == 1) "" else "s"}" + if (m > 0) " $m minutes" else ""
            }
        }

        /** "42 min", "1 h 5 min" for the turn card. */
        fun shortDuration(s: Double): String {
            val min = (s / 60).roundToInt().coerceAtLeast(1)
            return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
        }

        private fun lowerFirst(s: String) = s.replaceFirstChar { it.lowercase() }
    }
}
