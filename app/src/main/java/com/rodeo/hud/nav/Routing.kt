package com.rodeo.hud.nav

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

data class LatLng(val lat: Double, val lng: Double)

/** One manoeuvre and the stretch of road that follows it, up to the next manoeuvre. */
data class RouteStep(
    val maneuver: Maneuver,
    /** Shown on the turn card, e.g. "Turn right onto MG Road". */
    val instruction: String,
    /** Spoken a few hundred metres out, e.g. "Turn right onto MG Road". */
    val alert: String,
    /** Spoken at the turn; may chain the next one ("Turn right. Then turn left."). */
    val now: String,
    /** Index into [Route.points] where the manoeuvre happens. */
    val at: Int,
    /** Length and time of the road after this manoeuvre. */
    val lengthM: Double,
    val timeS: Double,
)

data class Route(
    val points: List<LatLng>,
    val steps: List<RouteStep>,
    val lengthM: Double,
    val timeS: Double,
    /** Which free service answered, for the status line and the logs. */
    val provider: String,
)

enum class Travel { MOTORCYCLE, WALK }

/** Avoid options the rider sets in Settings. */
data class RouteOptions(val avoidTolls: Boolean = false, val avoidHighways: Boolean = false)

/** Google-style encoded polyline; Valhalla uses 6 decimal places, OSRM 5 unless asked otherwise. */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 6): List<LatLng> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLng>(encoded.length / 4)
        var i = 0
        var lat = 0L
        var lng = 0L
        while (i < encoded.length) {
            for (k in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = encoded[i++].code - 63
                    result = result or ((b and 0x1F).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += delta else lng += delta
            }
            out += LatLng(lat / factor, lng / factor)
        }
        return out
    }
}

/** Turns the JSON of the free OpenStreetMap routers into a [Route]. Pure, so it is unit tested. */
object RouteParser {

    /** Valhalla (FOSSGIS): has motorcycle costing and ready-made English instructions. */
    fun valhalla(body: String): Route {
        val trip = JSONObject(body).getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val points = PolylineCodec.decode(leg.getString("shape"), 6)
        val ms = leg.getJSONArray("maneuvers")
        val steps = ArrayList<RouteStep>(ms.length())
        for (i in 0 until ms.length()) {
            val m = ms.getJSONObject(i)
            val type = m.optInt("type")
            if (type == 27) continue // "exit the roundabout": already said when entering it
            val instruction = m.optString("instruction").trimEnd('.').ifBlank { "Continue" }
            val maneuver = valhallaManeuver(type, m, ms.optJSONObject(i + 1))
            val alert = m.optString("verbal_transition_alert_instruction").ifBlank { m.optString("verbal_pre_transition_instruction") }
            steps += RouteStep(
                maneuver = maneuver,
                instruction = instruction,
                alert = alert.trimEnd('.').ifBlank { instruction },
                now = m.optString("verbal_pre_transition_instruction").trim().ifBlank { instruction },
                at = m.optInt("begin_shape_index").coerceIn(0, (points.size - 1).coerceAtLeast(0)),
                lengthM = m.optDouble("length", 0.0) * 1000.0,
                timeS = m.optDouble("time", 0.0),
            )
        }
        val summary = trip.optJSONObject("summary") ?: leg.optJSONObject("summary")
        return Route(
            points = points,
            steps = steps,
            lengthM = (summary?.optDouble("length", 0.0) ?: 0.0) * 1000.0,
            timeS = summary?.optDouble("time", 0.0) ?: 0.0,
            provider = "Valhalla",
        )
    }

    private fun valhallaManeuver(type: Int, m: JSONObject, next: JSONObject?): Maneuver = when (type) {
        1, 2, 3 -> Maneuver.DEPART
        4, 5, 6 -> Maneuver.ARRIVE
        9 -> Maneuver.SLIGHT_RIGHT
        10 -> Maneuver.RIGHT
        11 -> Maneuver.SHARP_RIGHT
        12 -> Maneuver.UTURN_RIGHT
        13 -> Maneuver.UTURN_LEFT
        14 -> Maneuver.SHARP_LEFT
        15 -> Maneuver.LEFT
        16 -> Maneuver.SLIGHT_LEFT
        18, 20, 23, 37 -> Maneuver.KEEP_RIGHT
        19, 21, 24, 38 -> Maneuver.KEEP_LEFT
        26 -> {
            // Which way the rider leaves the roundabout, from the heading in and the heading out.
            val inDeg = m.optDouble("bearing_before", Double.NaN)
            val outDeg = next?.optDouble("bearing_after", Double.NaN) ?: Double.NaN
            if (!inDeg.isNaN() && !outDeg.isNaN() && signedTurn(inDeg, outDeg) < 0) Maneuver.ROUNDABOUT_LEFT else Maneuver.ROUNDABOUT_RIGHT
        }
        else -> Maneuver.STRAIGHT
    }

    /** OSRM (FOSSGIS): the fallback. It gives no sentences, so they are written here. */
    fun osrm(body: String): Route {
        val route = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        val points = PolylineCodec.decode(route.getString("geometry"), 6)
        val legSteps = route.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
        val steps = ArrayList<RouteStep>(legSteps.length())
        var searchFrom = 0
        for (i in 0 until legSteps.length()) {
            val s = legSteps.getJSONObject(i)
            val man = s.getJSONObject("maneuver")
            val type = man.optString("type")
            val modifier = man.optString("modifier")
            if (type == "exit roundabout" || type == "exit rotary" || type == "notification") continue
            // A road changing its name is not something to say out loud.
            if ((type == "new name" || type == "continue") && modifier in setOf("straight", "")) continue
            val loc = man.optJSONArray("location")
            val at = if (loc != null) nearestIndex(points, LatLng(loc.getDouble(1), loc.getDouble(0)), searchFrom) else searchFrom
            searchFrom = at
            val name = s.optString("name").takeIf { it.isNotBlank() }
            val exit = man.optInt("exit", 0)
            val maneuver = osrmManeuver(type, modifier)
            val sentence = osrmSentence(type, modifier, name, exit)
            val alert = if (type == "arrive") "You will arrive at your destination" else sentence
            steps += RouteStep(maneuver, sentence, alert, sentence, at, s.optDouble("distance", 0.0), s.optDouble("duration", 0.0))
        }
        return Route(points, steps, route.optDouble("distance", 0.0), route.optDouble("duration", 0.0), "OSRM")
    }

    private fun osrmManeuver(type: String, modifier: String): Maneuver = when {
        type == "depart" -> Maneuver.DEPART
        type == "arrive" -> Maneuver.ARRIVE
        type == "roundabout" || type == "rotary" || type == "roundabout turn" ->
            if ("left" in modifier) Maneuver.ROUNDABOUT_LEFT else Maneuver.ROUNDABOUT_RIGHT
        modifier == "uturn" -> Maneuver.UTURN_RIGHT // India drives on the left: U-turns go right
        type == "fork" || type == "on ramp" || type == "off ramp" || type == "merge" -> when {
            "left" in modifier -> Maneuver.KEEP_LEFT
            "right" in modifier -> Maneuver.KEEP_RIGHT
            else -> Maneuver.STRAIGHT
        }
        else -> when (modifier) {
            "sharp right" -> Maneuver.SHARP_RIGHT
            "right" -> Maneuver.RIGHT
            "slight right" -> Maneuver.SLIGHT_RIGHT
            "sharp left" -> Maneuver.SHARP_LEFT
            "left" -> Maneuver.LEFT
            "slight left" -> Maneuver.SLIGHT_LEFT
            else -> Maneuver.STRAIGHT
        }
    }

    private fun osrmSentence(type: String, modifier: String, name: String?, exit: Int): String {
        val onto = name?.let { " onto $it" }.orEmpty()
        return when (type) {
            "depart" -> "Head off" + (name?.let { " on $it" }.orEmpty())
            "arrive" -> "You have arrived"
            "roundabout", "rotary", "roundabout turn" ->
                if (exit > 0) "At the roundabout, take the ${ordinal(exit)} exit$onto" else "Go through the roundabout$onto"
            "fork" -> "Keep ${side(modifier)} at the fork$onto"
            "on ramp" -> "Take the ramp on the ${side(modifier)}$onto"
            "off ramp" -> "Take the exit on the ${side(modifier)}$onto"
            "merge" -> "Merge$onto"
            "end of road" -> "At the end of the road, turn ${side(modifier)}$onto"
            else -> when (modifier) {
                "uturn" -> "Make a U-turn$onto"
                "straight", "" -> "Continue straight$onto"
                else -> "Turn $modifier$onto"
            }
        }.replaceFirstChar { it.uppercase() }
    }

    private fun side(modifier: String) = if ("left" in modifier) "left" else if ("right" in modifier) "right" else "straight"

    fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }

    /** Closest polyline vertex to [p] at or after [from]. */
    private fun nearestIndex(points: List<LatLng>, p: LatLng, from: Int): Int {
        var best = from.coerceIn(0, (points.size - 1).coerceAtLeast(0))
        var bestD = Double.MAX_VALUE
        for (i in best until points.size) {
            val d = abs(points[i].lat - p.lat) + abs(points[i].lng - p.lng)
            if (d < bestD) { bestD = d; best = i }
            if (d < 1e-6) break
        }
        return best
    }

    /** Turn from heading [from] to heading [to] in degrees, -180..180, negative = left. */
    fun signedTurn(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
}

/**
 * Routes from free, keyless OpenStreetMap services: Valhalla with motorcycle costing first,
 * then OSRM. Both run on the FOSSGIS servers that OpenStreetMap itself links to.
 */
object Router {
    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"
    private const val OSRM = "https://routing.openstreetmap.de"
    var userAgent = "RodeoHUD/1.0"

    /** Blocking; call off the main thread. Throws when every service fails. */
    fun route(from: LatLng, to: LatLng, travel: Travel, headingDeg: Float?, options: RouteOptions = RouteOptions()): Route {
        val errors = ArrayList<String>()
        runCatching { return RouteParser.valhalla(get(valhallaUrl(from, to, travel, headingDeg, options))) }
            .onFailure { errors += "Valhalla: ${it.message}" }
        runCatching { return RouteParser.osrm(get(osrmUrl(from, to, travel))) }
            .onFailure { errors += "OSRM: ${it.message}" }
        throw IllegalStateException(errors.joinToString("; "))
    }

    fun valhallaRequest(from: LatLng, to: LatLng, travel: Travel, headingDeg: Float?, options: RouteOptions): JSONObject {
        val origin = JSONObject().put("lat", from.lat).put("lon", from.lng)
        // While riding, the heading stops the route from starting with a U-turn behind the rider.
        if (headingDeg != null) origin.put("heading", headingDeg.toInt()).put("heading_tolerance", 60)
        val costing = if (travel == Travel.WALK) "pedestrian" else "motorcycle"
        val body = JSONObject()
            .put("locations", JSONArray().put(origin).put(JSONObject().put("lat", to.lat).put("lon", to.lng)))
            .put("costing", costing)
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", "en-US"))
        if (travel == Travel.MOTORCYCLE) {
            body.put(
                "costing_options",
                JSONObject().put(
                    "motorcycle",
                    JSONObject()
                        .put("use_highways", if (options.avoidHighways) 0.0 else 1.0)
                        .put("use_tolls", if (options.avoidTolls) 0.0 else 0.5)
                        .put("use_trails", 0.0),
                ),
            )
        }
        return body
    }

    private fun valhallaUrl(from: LatLng, to: LatLng, travel: Travel, headingDeg: Float?, options: RouteOptions) =
        "$VALHALLA?json=" + URLEncoder.encode(valhallaRequest(from, to, travel, headingDeg, options).toString(), "UTF-8")

    private fun osrmUrl(from: LatLng, to: LatLng, travel: Travel): String {
        val profile = if (travel == Travel.WALK) "routed-foot/route/v1/foot" else "routed-car/route/v1/driving"
        return "$OSRM/$profile/${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&geometries=polyline6&steps=true"
    }

    fun get(url: String, timeoutMs: Int = 12_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${text.take(160)}")
            return text
        } finally {
            c.disconnect()
        }
    }
}
