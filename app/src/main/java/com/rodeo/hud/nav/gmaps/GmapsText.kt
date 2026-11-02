package com.rodeo.hud.nav.gmaps

import com.rodeo.hud.nav.Maneuver

/** The words on the HUD for a Google Maps turn. Plain functions so they are unit-tested. */
object GmapsText {

    /** Short, glanceable action: "TURN LEFT", "2ND EXIT", "U-TURN". */
    fun action(g: MapsGuidance): String {
        when (g.status) {
            NavStatus.REROUTING -> return "REROUTING"
            NavStatus.NO_GPS -> return "FINDING GPS"
            NavStatus.ARRIVED -> return "ARRIVED"
            NavStatus.NAVIGATING -> Unit
        }
        val m = VoicePlanner.spokenManeuver(g)
        val exit = g.roundaboutExit
        return when (m) {
            Maneuver.DEPART -> "START"
            Maneuver.STRAIGHT -> "CONTINUE STRAIGHT"
            Maneuver.SLIGHT_LEFT -> "SLIGHT LEFT"
            Maneuver.LEFT -> "TURN LEFT"
            Maneuver.SHARP_LEFT -> "SHARP LEFT"
            Maneuver.SLIGHT_RIGHT -> "SLIGHT RIGHT"
            Maneuver.RIGHT -> "TURN RIGHT"
            Maneuver.SHARP_RIGHT -> "SHARP RIGHT"
            Maneuver.KEEP_LEFT -> "KEEP LEFT"
            Maneuver.KEEP_RIGHT -> "KEEP RIGHT"
            Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> "U-TURN"
            Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT, Maneuver.ROUNDABOUT_STRAIGHT, Maneuver.ROUNDABOUT_UTURN ->
                if (exit != null) "${ordinal(exit)} EXIT" else "ROUNDABOUT"
            Maneuver.MERGE -> "MERGE"
            Maneuver.ARRIVE -> "ARRIVING"
            Maneuver.UNKNOWN -> "NEXT TURN"
        }
    }

    /** The road line under the action: "ONTO MG ROAD", "TOWARD HOSUR", or Google's own words. */
    fun road(g: MapsGuidance): String? {
        if (g.status == NavStatus.REROUTING) return "Google Maps is finding a new route"
        if (g.status == NavStatus.NO_GPS) return "Keep riding: Google Maps is looking for GPS"
        val s = g.street?.takeIf { it.isNotBlank() }
        if (g.maneuver == Maneuver.ARRIVE) return s ?: g.instruction
        if (s == null) return g.instruction?.takeIf { it.isNotBlank() && it != g.distanceText }
        return when {
            g.towards -> "toward $s"
            g.maneuver == Maneuver.DEPART -> s
            else -> "onto $s"
        }
    }

    /** "15 min · 6.8 km · ETA 1:35 PM". */
    fun trip(g: MapsGuidance): String? {
        val parts = listOfNotNull(
            g.durationText ?: g.durationMin?.let { duration(it) },
            g.remainingText,
            g.eta?.let { "ETA $it" },
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    fun duration(min: Int): String = if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"

    fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "TH"
        n % 10 == 1 -> "ST"
        n % 10 == 2 -> "ND"
        n % 10 == 3 -> "RD"
        else -> "TH"
    }

    /** Big distance as Google wrote it, split into number and unit: "450" + "M". */
    fun distanceParts(g: MapsGuidance): Pair<String, String>? {
        val raw = g.distanceText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = Regex("""^(\d[\d.,  ]*\d|\d)\s*([^\d\s].*)$""").find(raw) ?: return raw.uppercase() to ""
        return m.groupValues[1] to m.groupValues[2].uppercase()
    }
}
