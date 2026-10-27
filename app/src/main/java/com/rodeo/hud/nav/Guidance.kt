package com.rodeo.hud.nav

/** The next manoeuvre, in the shapes a rider needs to read at a glance. */
enum class Maneuver {
    DEPART, STRAIGHT,
    SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    KEEP_LEFT, KEEP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT,
    ROUNDABOUT_LEFT, ROUNDABOUT_RIGHT,
    ARRIVE,
}

/** What the turn card shows. Plain strings so the card can be previewed and tested. */
data class GuidanceUi(
    val maneuver: Maneuver,
    /** Distance to the manoeuvre, e.g. "450 m". */
    val distance: String,
    /** e.g. "Turn right onto MG Road". */
    val instruction: String,
    val destination: String,
    /** Distance left to the destination, e.g. "38.4 km". */
    val remaining: String? = null,
    /** e.g. "42 min". */
    val eta: String? = null,
    val voiceOn: Boolean = true,
    /** A short status line such as "Rerouting" or "No route: straight line". */
    val status: String? = null,
)
