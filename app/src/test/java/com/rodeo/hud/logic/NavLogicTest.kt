package com.rodeo.hud.logic

import com.rodeo.hud.nav.LatLng
import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.Navigator
import com.rodeo.hud.nav.PolylineCodec
import com.rodeo.hud.nav.Route
import com.rodeo.hud.nav.RouteParser
import com.rodeo.hud.nav.RouteStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToLong

class NavLogicTest {
    private val start = LatLng(12.9716, 77.5946)

    /** Moves [m] metres north and [e] metres east. */
    private fun at(p: LatLng, north: Double, east: Double) =
        LatLng(p.lat + north / 111_320.0, p.lng + east / (111_320.0 * cos(Math.toRadians(p.lat))))

    /** 1 km north, right turn, 600 m east, arrive. Points every 50 m like a real router. */
    private fun lRoute(): Route {
        val pts = ArrayList<LatLng>()
        for (i in 0..20) pts += at(start, i * 50.0, 0.0)
        val corner = pts.last()
        for (i in 1..12) pts += at(corner, 0.0, i * 50.0)
        val steps = listOf(
            RouteStep(Maneuver.DEPART, "Head north on MG Road", "Head north", "Head north on MG Road", 0, 1_000.0, 90.0),
            RouteStep(Maneuver.RIGHT, "Turn right onto Brigade Road", "Turn right onto Brigade Road", "Turn right onto Brigade Road.", 20, 600.0, 60.0),
            RouteStep(Maneuver.ARRIVE, "You have arrived", "You have arrived", "You have arrived.", pts.size - 1, 0.0, 0.0),
        )
        return Route(pts, steps, 1_600.0, 150.0, "test")
    }

    /** Rides [path] at [speed] m/s with a fix every second; returns what was spoken and the last progress. */
    private fun ride(route: Route, path: List<LatLng>, speed: Double = 10.0, jitterM: Double = 0.0): Pair<List<String>, com.rodeo.hud.nav.NavProgress> {
        val said = ArrayList<String>()
        val nav = Navigator(route) { said += it }
        var t = 0L
        var last: com.rodeo.hud.nav.NavProgress? = null
        // Resample the path at speed metres per fix.
        var carry = 0.0
        val fixes = ArrayList<LatLng>()
        fixes += path.first()
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val d = Navigator.metres(a, b)
            var s = speed - carry
            while (s <= d) {
                val f = s / d
                fixes += LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
                s += speed
            }
            carry = d - (s - speed)
        }
        fixes += path.last()
        for ((i, p) in fixes.withIndex()) {
            val j = if (jitterM > 0) at(p, jitterM * Math.sin(i * 1.7), jitterM * Math.cos(i * 2.3)) else p
            last = nav.update(j.lat, j.lng, speed.toFloat(), null, 8f, t)
            t += 1_000
        }
        return said to last!!
    }

    @Test fun ridesTheRouteWithTimelyPrompts() {
        val route = lRoute()
        val (said, last) = ride(route, route.points, jitterM = 6.0)
        assertTrue(said.first().startsWith("Route ready"))
        // Far and near warnings for the turn, then the turn itself, then arrival.
        val turn = said.filter { "brigade road" in it.lowercase() }
        assertTrue("got $said", turn.size >= 2)
        assertTrue(turn.any { it.startsWith("In ") })
        assertTrue(turn.last() == "Turn right onto Brigade Road.")
        assertEquals("You have arrived.", said.last())
        assertEquals("arrival said once", 1, said.count { it.startsWith("You have arrived") })
        assertTrue(last.arrived)
        assertFalse(said.any { it == "Rerouting" })
    }

    @Test fun progressCountsDown() {
        val route = lRoute()
        val nav = Navigator(route) {}
        val p1 = nav.update(start.lat, start.lng, 10f, 0f, 5f, 0)
        assertEquals(1, p1.stepIndex)
        assertEquals(1_000.0, p1.toManeuverM, 5.0)
        val mid = at(start, 600.0, 0.0)
        val p2 = nav.update(mid.lat, mid.lng, 10f, 0f, 5f, 60_000)
        assertEquals(400.0, p2.toManeuverM, 5.0)
        assertEquals(1_000.0, p2.remainingM, 5.0)
        assertTrue(p2.remainingS in 80.0..110.0)
    }

    @Test fun leavingTheRouteTriggersReroute() {
        val route = lRoute()
        val nav = Navigator(route) {}
        var t = 0L
        // Ride 300 m on the route, then turn off west for 200 m.
        for (n in 0..30 step 1) { val p = at(start, n * 10.0, 0.0); nav.update(p.lat, p.lng, 10f, 0f, 6f, t); t += 1_000 }
        val turnOff = at(start, 300.0, 0.0)
        var off = false
        for (w in 1..20) {
            val p = at(turnOff, 0.0, -w * 10.0)
            off = nav.update(p.lat, p.lng, 10f, 270f, 6f, t).offRoute
            t += 1_000
            if (off) break
        }
        assertTrue(off)
    }

    @Test fun gpsDriftNearTheLineIsNotOffRoute() {
        val route = lRoute()
        val nav = Navigator(route) {}
        var t = 0L
        for (n in 0..60) {
            val p = at(start, n * 10.0, if (n % 2 == 0) 25.0 else -25.0)
            assertFalse(nav.update(p.lat, p.lng, 10f, 0f, 15f, t).offRoute)
            t += 1_000
        }
    }

    @Test fun startingInACarParkIsNotOffRoute() {
        val route = lRoute()
        val nav = Navigator(route) {}
        val carPark = at(start, 0.0, -70.0)
        for (i in 0..10) assertFalse(nav.update(carPark.lat, carPark.lng, 0f, null, 10f, i * 1_000L).offRoute)
    }

    @Test fun polylineRoundTrip() {
        val pts = listOf(LatLng(12.971600, 77.594600), LatLng(12.972000, 77.595100), LatLng(13.370200, 77.683500))
        assertEquals(pts, PolylineCodec.decode(encode(pts), 6).map { LatLng(round6(it.lat), round6(it.lng)) })
    }

    @Test fun parsesValhalla() {
        val pts = listOf(start, at(start, 500.0, 0.0), at(start, 500.0, 300.0))
        val json = """
            {"trip":{"legs":[{"shape":"${encode(pts)}","maneuvers":[
              {"type":1,"instruction":"Drive north on MG Road.","verbal_pre_transition_instruction":"Drive north on MG Road.","begin_shape_index":0,"length":0.5,"time":45},
              {"type":10,"instruction":"Turn right onto Brigade Road.","verbal_transition_alert_instruction":"Turn right onto Brigade Road.","verbal_pre_transition_instruction":"Turn right onto Brigade Road.","begin_shape_index":1,"length":0.3,"time":30,"bearing_before":0,"bearing_after":90},
              {"type":26,"instruction":"Enter the roundabout and take the 2nd exit.","begin_shape_index":2,"length":0.1,"time":10,"bearing_before":90},
              {"type":27,"instruction":"Exit the roundabout.","begin_shape_index":2,"length":0.0,"time":0,"bearing_after":0},
              {"type":4,"instruction":"You have arrived at your destination.","begin_shape_index":2,"length":0,"time":0}
            ]}],"summary":{"length":0.8,"time":75}}}
        """.trimIndent()
        val r = RouteParser.valhalla(json)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.RIGHT, Maneuver.ROUNDABOUT_LEFT, Maneuver.ARRIVE), r.steps.map { it.maneuver })
        assertEquals("Turn right onto Brigade Road", r.steps[1].instruction)
        assertEquals(800.0, r.lengthM, 0.1)
        assertEquals(3, r.points.size)
    }

    @Test fun parsesOsrm() {
        val pts = listOf(start, at(start, 500.0, 0.0), at(start, 500.0, 300.0))
        val p1 = pts[1]
        val json = """
            {"routes":[{"geometry":"${encode(pts)}","distance":800,"duration":75,"legs":[{"steps":[
              {"name":"MG Road","distance":500,"duration":45,"maneuver":{"type":"depart","modifier":"","location":[${start.lng},${start.lat}]}},
              {"name":"Brigade Road","distance":300,"duration":30,"maneuver":{"type":"turn","modifier":"right","location":[${p1.lng},${p1.lat}]}},
              {"name":"","distance":0,"duration":0,"maneuver":{"type":"arrive","modifier":"","location":[${pts[2].lng},${pts[2].lat}]}}
            ]}]}]}
        """.trimIndent()
        val r = RouteParser.osrm(json)
        assertEquals(listOf(Maneuver.DEPART, Maneuver.RIGHT, Maneuver.ARRIVE), r.steps.map { it.maneuver })
        assertEquals("Turn right onto Brigade Road", r.steps[1].instruction)
        assertEquals(1, r.steps[1].at)
    }

    @Test fun distances() {
        assertEquals("450 m", Navigator.shortDistance(447.0))
        assertEquals("1.2 km", Navigator.shortDistance(1_234.0))
        assertEquals("300 metres", Navigator.spokenDistance(310.0))
        assertEquals("1 kilometre", Navigator.spokenDistance(1_020.0))
        assertEquals("1.5 kilometres", Navigator.spokenDistance(1_480.0))
        assertEquals("42 min", Navigator.shortDuration(2_520.0))
    }

    private fun round6(v: Double) = (v * 1e6).roundToLong() / 1e6

    private fun encode(pts: List<LatLng>): String {
        val sb = StringBuilder()
        var pl = 0L
        var pg = 0L
        fun enc(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in pts) {
            val la = (p.lat * 1e6).roundToLong()
            val lo = (p.lng * 1e6).roundToLong()
            enc(la - pl); enc(lo - pg)
            pl = la; pg = lo
        }
        return sb.toString()
    }
}
