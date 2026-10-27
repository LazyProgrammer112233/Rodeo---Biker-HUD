package com.rodeo.hud.logic

import com.rodeo.hud.nav.LatLng
import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.Navigator
import com.rodeo.hud.nav.Route
import com.rodeo.hud.nav.RouteParser
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rides real routes from the free routers (saved by CI from Indian cities) through the
 * turn-by-turn engine with GPS noise, and writes what the rider would hear.
 */
class NavReplayTest {
    private val dir = File("src/test/resources/live")
    private val report = File("build/reports-rodeo/nav-replay.txt")

    @Test fun replayRealRoutes() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") && (f.name.startsWith("valhalla-") || f.name.startsWith("osrm-")) }
            ?.filter { it.length() > 200 }.orEmpty().sortedBy { it.name }
        assumeTrue("no live fixtures (CI saves them)", files.isNotEmpty())
        report.parentFile?.mkdirs()
        val out = StringBuilder()
        val problems = ArrayList<String>()
        for (f in files) {
            val parsed = runCatching {
                if (f.name.startsWith("osrm-")) RouteParser.osrm(f.readText()) else RouteParser.valhalla(f.readText())
            }
            val route = parsed.getOrNull()
            if (route == null) {
                problems += "${f.name}: parse failed: ${parsed.exceptionOrNull()?.message}"
                continue
            }
            val walking = "walk" in f.name
            val speed = if (walking) 1.4 else 11.0
            out.append("=== ${f.name}  (${route.provider}, ${"%.1f".format(route.lengthM / 1000)} km, ${Navigator.shortDuration(route.timeS)}, ${route.points.size} points, ${route.steps.size} steps)\n")
            route.steps.forEachIndexed { i, s -> out.append("   step $i ${s.maneuver} at ${s.at}: ${s.instruction}\n") }
            val heard = ArrayList<Pair<Double, String>>()
            var along = 0.0
            val nav = Navigator(route) { heard += along to it }
            var t = 0L
            var rerouted = false
            var arrived = false
            val ride = resample(route.points, speed)
            for ((i, p) in ride.withIndex()) {
                val j = jitter(p, 6.0, i)
                // Like a phone's GPS: a heading once moving, with a few degrees of noise.
                val next = ride.getOrNull(i + 1) ?: p
                val heading = if (next == p) null else (bearing(p, next) + 6 * sin(i * 0.9)).toFloat()
                val pr = nav.update(j.lat, j.lng, speed.toFloat(), heading, 8f, t)
                along = nav.totalM - pr.remainingM
                if (pr.offRoute) rerouted = true
                if (pr.arrived) arrived = true
                t += 1_000
            }
            heard.forEach { (m, s) -> out.append("   @%6.0f m  \"%s\"\n".format(m, s)) }
            // The at-the-turn prompts, in the order heard, must follow the route's turns in order,
            // each at most once: a turn out of order means the matcher jumped ahead.
            val expected = route.steps.drop(1).map { it.now }
            val actual = heard.map { it.second }.filter { !it.startsWith("In ") && !it.startsWith("Route ready") && it != "You have arrived" }
            var k = 0
            var inOrder = true
            for (h in actual) {
                while (k < expected.size && expected[k] != h) k++
                if (k == expected.size) { inOrder = false; break }
                k++
            }
            val missing = if (inOrder) expected.size - actual.size else 0
            out.append("   turns=${expected.size} spokenAtTurn=${actual.size} inOrder=$inOrder offRoute=$rerouted arrived=$arrived\n\n")
            if (!inOrder) problems += "${f.name}: turns announced out of order or twice"
            // Steps closer together than the "now" distance can merge; allow a few on long routes.
            if (missing > maxOf(1, expected.size / 10)) problems += "${f.name}: $missing of ${expected.size} turns never announced"
            if (rerouted) problems += "${f.name}: went off route while following it"
            if (!arrived) problems += "${f.name}: never arrived"
        }
        report.writeText(out.toString() + "\nproblems:\n" + problems.joinToString("\n"))
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun resample(path: List<LatLng>, step: Double): List<LatLng> {
        val out = ArrayList<LatLng>()
        out += path.first()
        var carry = 0.0
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val d = Navigator.metres(a, b)
            if (d < 1e-6) continue
            var s = step - carry
            while (s <= d) {
                val f = s / d
                out += LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
                s += step
            }
            carry = d - (s - step)
        }
        out += path.last()
        return out
    }

    private fun bearing(a: LatLng, b: LatLng): Double {
        val dx = (b.lng - a.lng) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return (Math.toDegrees(kotlin.math.atan2(dx, dy)) + 360.0) % 360.0
    }

    private fun jitter(p: LatLng, m: Double, i: Int): LatLng {
        val n = m * sin(i * 1.7)
        val e = m * cos(i * 2.3)
        return LatLng(p.lat + n / 111_320.0, p.lng + e / (111_320.0 * cos(Math.toRadians(p.lat))))
    }
}
