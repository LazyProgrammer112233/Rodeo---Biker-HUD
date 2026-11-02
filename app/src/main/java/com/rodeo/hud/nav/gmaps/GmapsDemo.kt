package com.rodeo.hud.nav.gmaps

import com.rodeo.hud.nav.Maneuver

/** The preview trip in Settings: one of each turn shape the HUD can draw, as Google Maps would send it. */
object GmapsDemo {
    private fun a(m: Maneuver) = ArrowReading(m, confident = true)
    private const val S = "· 1:35 PM ETA"

    /** The preview trip: one of each turn shape the HUD can draw. */
    val TRIP: List<Pair<MapsNotice, ArrowReading?>> = listOf(
        MapsNotice("Starting navigation…", "Head north on Residency Rd", "24 min · 9.6 km $S") to a(Maneuver.DEPART),
        MapsNotice("800 m", "MG Road", "22 min · 9.1 km $S") to a(Maneuver.LEFT),
        MapsNotice("300 m", "Brigade Rd", "20 min · 8.3 km $S") to a(Maneuver.RIGHT),
        MapsNotice("450 m", "toward Hosur Rd", "18 min · 7.9 km $S") to a(Maneuver.SLIGHT_LEFT),
        MapsNotice("1.2 km", "Keep right toward Electronic City", "16 min · 7.2 km $S") to a(Maneuver.KEEP_RIGHT),
        MapsNotice("600 m", "At the roundabout, take the 2nd exit onto 100 Feet Rd", "13 min · 5.9 km $S") to a(Maneuver.ROUNDABOUT_LEFT),
        MapsNotice("200 m", "80 Feet Rd", "10 min · 4.4 km $S") to a(Maneuver.SHARP_RIGHT),
        MapsNotice("150 m", "Make a U-turn", "8 min · 3.7 km $S") to a(Maneuver.UTURN_RIGHT),
        MapsNotice("Rerouting…", null, null) to null,
        MapsNotice("900 m", "Merge onto NH 44", "6 min · 2.9 km $S") to a(Maneuver.MERGE),
        MapsNotice("350 m", "Church St", "3 min · 1.2 km $S") to null,
        MapsNotice("120 m", "Destination will be on the right", "1 min · 120 m $S") to a(Maneuver.ARRIVE),
        MapsNotice("You have arrived", "Lalbagh Botanical Garden", null) to a(Maneuver.ARRIVE),
    )
}
