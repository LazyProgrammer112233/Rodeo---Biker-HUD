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
        report.parentFile.mkdirs()
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
            for ((i, p) in resample(route.points, speed).withIndex()) {
                val j = jitter(p, 6.0, i)
                val pr = nav.update(j.lat, j.lng, speed.toFloat(), null, 8f, t)
                along = nav.totalM - pr.remainingM
                if (pr.offRoute) rerouted = true
                if (pr.arrived) arrived = true
                t += 1_000
            }
            heard.forEach { (m, s) -> out.append("   @%6.0f m  \"%s\"\n".format(m, s)) }
            val turns = route.steps.drop(1).filter { it.maneuver != Maneuver.ARRIVE }
            val missing = turns.filter { st -> heard.none { it.second == st.now } }
            out.append("   turns=${turns.size} spokenAtTurn=${turns.size - missing.size} offRoute=$rerouted arrived=$arrived\n\n")
            // Steps closer together than the "now" distance can merge; allow a few on long routes.
            if (missing.size > maxOf(1, turns.size / 10)) problems += "${f.name}: ${missing.size} of ${turns.size} turns never announced: ${missing.take(3).map { it.instruction }}"
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

    private fun jitter(p: LatLng, m: Double, i: Int): LatLng {
        val n = m * sin(i * 1.7)
        val e = m * cos(i * 2.3)
        return LatLng(p.lat + n / 111_320.0, p.lng + e / (111_320.0 * cos(Math.toRadians(p.lat))))
    }
}
