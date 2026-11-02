package com.rodeo.hud.nav.gmaps

import com.rodeo.hud.nav.Maneuver
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decides what to say, and when, for Google Maps guidance shown on Rodeo's HUD. Each turn gets
 * up to three prompts: one when it first appears ("In 800 metres, turn left onto MG Road"), a
 * reminder close in ("In 300 metres, turn left") and one at the turn ("Turn left now").
 *
 * Google Maps only updates its notification every so often, so between updates the distance is
 * counted down with the phone's own GPS ([onTravel]); that keeps "now" on time at speed.
 *
 * Plain Kotlin with an injected clock and speech callback, so it is fully unit-tested.
 */
class VoicePlanner(private val say: (text: String, urgent: Boolean) -> Unit) {

    private data class StepKey(val maneuver: Maneuver, val street: String?, val exit: Int?)

    private var key: StepKey? = null
    private var guidance: MapsGuidance? = null
    private var lastDistance: Double? = null
    private var travelled = 0.0
    private var stepStart: Double? = null
    private var saidIntro = false
    private var saidPrepare = false
    private var saidNow = false
    private var started = false
    private var reroutingSaid = false
    private var noGpsSaid = false
    private var arrivedSaid = false
    private var lastSpokeAt = Long.MIN_VALUE / 2
    private var speedMps = 0.0

    /** Speeds up "now" at speed: it comes about seven seconds before the turn. */
    fun setSpeed(mps: Double) { speedMps = mps.coerceAtLeast(0.0) }

    fun reset() {
        key = null; guidance = null; lastDistance = null; travelled = 0.0; stepStart = null
        saidIntro = false; saidPrepare = false; saidNow = false; started = false
        reroutingSaid = false; noGpsSaid = false; arrivedSaid = false
    }

    /** A fresh notification from Google Maps. */
    fun onGuidance(g: MapsGuidance, nowMs: Long) {
        when (g.status) {
            NavStatus.REROUTING -> {
                if (!reroutingSaid) { speak("Rerouting", nowMs, urgent = true); reroutingSaid = true }
                return
            }
            NavStatus.NO_GPS -> {
                if (!noGpsSaid) { speak("GPS signal lost. Google Maps is looking for it.", nowMs, urgent = false); noGpsSaid = true }
                return
            }
            NavStatus.ARRIVED -> {
                if (!arrivedSaid) { speak(arrivedLine(g), nowMs, urgent = true); arrivedSaid = true }
                return
            }
            NavStatus.NAVIGATING -> { reroutingSaid = false; noGpsSaid = false }
        }
        val k = StepKey(spokenManeuver(g), g.street, g.roundaboutExit)
        val d = g.distanceM
        val prev = lastDistance
        // The same kind of turn again further on (two lefts in a row): the distance jumps back up.
        val jumpedBack = d != null && prev != null && d > prev + max(80.0, prev * 0.25)
        val newStep = k != key || jumpedBack
        guidance = g
        lastDistance = d
        travelled = 0.0
        if (newStep) {
            key = k
            stepStart = d
            saidIntro = false; saidPrepare = false; saidNow = false
            arrivedSaid = false
            announceNewStep(g, d, nowMs)
        } else {
            check(nowMs)
        }
    }

    /** The rider moved [metres] since the last fix (from the phone's GPS). */
    fun onTravel(metres: Double, nowMs: Long) {
        if (guidance == null || metres <= 0) return
        travelled += metres
        check(nowMs)
    }

    /** Google Maps stopped navigating (its notification went away). */
    fun onEnded(nowMs: Long) {
        val wasArrival = guidance?.maneuver == Maneuver.ARRIVE || arrivedSaid
        if (started && !wasArrival) speak("Google Maps navigation ended.", nowMs, urgent = false)
        reset()
    }

    /** Best guess of the distance left to the turn. */
    fun estimate(): Double? = lastDistance?.let { (it - travelled).coerceAtLeast(0.0) }

    private fun nowDistance(): Double = (speedMps * 7.0).coerceIn(45.0, 160.0)

    private fun prepareDistance(start: Double): Double? = when {
        start >= 2_500 -> if (speedMps > 22) 1_000.0 else 500.0
        start >= 750 -> if (speedMps > 22) 800.0 else 400.0
        start >= 350 -> 200.0
        else -> null
    }

    private fun announceNewStep(g: MapsGuidance, d: Double?, nowMs: Long) {
        val lead = if (!started) "Following Google Maps. " else ""
        started = true
        saidIntro = true
        val line = when {
            d == null -> sentence(g)
            d <= nowDistance() -> { saidPrepare = true; saidNow = true; nowLine(g) }
            d > 2_500 && g.maneuver != Maneuver.ARRIVE -> "Continue for ${spokenDistance(d, g.imperial)}, then ${action(g, withStreet = true)}."
            else -> {
                if (prepareDistance(d) == null || d <= (prepareDistance(d) ?: 0.0) + 100) saidPrepare = true
                "In ${spokenDistance(d, g.imperial)}, ${action(g, withStreet = true)}."
            }
        }
        speak(lead + line.replaceFirstChar { it.uppercase() }, nowMs, urgent = lead.isEmpty())
    }

    private fun check(nowMs: Long) {
        val g = guidance ?: return
        val d = estimate() ?: return
        val start = stepStart ?: return
        if (!saidNow && d <= nowDistance()) {
            saidNow = true
            saidPrepare = true
            speak(nowLine(g), nowMs, urgent = true)
            return
        }
        val prep = prepareDistance(start) ?: return
        if (!saidPrepare && d <= prep) {
            saidPrepare = true
            if (nowMs - lastSpokeAt < 3_000) return
            speak("In ${spokenDistance(d, g.imperial)}, ${action(g, withStreet = false)}.", nowMs, urgent = false)
        }
    }

    private fun speak(text: String, nowMs: Long, urgent: Boolean) {
        lastSpokeAt = nowMs
        say(text, urgent)
    }

    companion object {
        /** The manoeuvre to speak: a guess from an unclear picture is not spoken as a direction. */
        fun spokenManeuver(g: MapsGuidance): Maneuver = if (!g.arrowTrusted && !g.fromText) Maneuver.UNKNOWN else g.maneuver

        fun sentence(g: MapsGuidance): String = action(g, withStreet = true).replaceFirstChar { it.uppercase() } + "."

        fun nowLine(g: MapsGuidance): String {
            val m = spokenManeuver(g)
            val street = streetPart(g)
            val core = when (m) {
                Maneuver.ARRIVE -> return "Your destination is just ahead" + sidePart(g) + "."
                Maneuver.UNKNOWN -> "Take the next turn now"
                Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT, Maneuver.ROUNDABOUT_STRAIGHT, Maneuver.ROUNDABOUT_UTURN ->
                    return action(g, withStreet = true).replaceFirstChar { it.uppercase() } + "."
                Maneuver.STRAIGHT, Maneuver.DEPART -> "Continue straight"
                else -> verb(m).replaceFirstChar { it.uppercase() } + " now"
            }
            return core + (if (street.isNotEmpty()) ",$street" else "") + "."
        }

        fun arrivedLine(g: MapsGuidance): String =
            "You have arrived" + (g.street?.let { " at ${speakable(it)}" } ?: "") + "."

        /** "turn left onto MG Road", "at the roundabout, take the second exit". */
        fun action(g: MapsGuidance, withStreet: Boolean): String {
            val m = spokenManeuver(g)
            val street = if (withStreet) streetPart(g) else ""
            return when (m) {
                Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT, Maneuver.ROUNDABOUT_STRAIGHT, Maneuver.ROUNDABOUT_UTURN -> {
                    val exit = g.roundaboutExit
                    val how = when {
                        exit != null -> "take the ${ordinal(exit)} exit"
                        m == Maneuver.ROUNDABOUT_LEFT -> "take the exit on the left"
                        m == Maneuver.ROUNDABOUT_RIGHT -> "take the exit on the right"
                        m == Maneuver.ROUNDABOUT_UTURN -> "go all the way round and come back"
                        else -> "go straight on"
                    }
                    "at the roundabout, $how$street"
                }
                Maneuver.ARRIVE -> "your destination" + (g.street?.let { ", ${speakable(it)}," } ?: "") + " will be" + sidePart(g).ifEmpty { " ahead" }
                Maneuver.UNKNOWN -> if (street.isNotEmpty()) "take the next turn$street" else "take the next turn"
                else -> verb(m) + street
            }
        }

        private fun verb(m: Maneuver): String = when (m) {
            Maneuver.LEFT -> "turn left"
            Maneuver.RIGHT -> "turn right"
            Maneuver.SLIGHT_LEFT -> "take a slight left"
            Maneuver.SLIGHT_RIGHT -> "take a slight right"
            Maneuver.SHARP_LEFT -> "take a sharp left"
            Maneuver.SHARP_RIGHT -> "take a sharp right"
            Maneuver.KEEP_LEFT -> "keep left"
            Maneuver.KEEP_RIGHT -> "keep right"
            Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT -> "make a U-turn"
            Maneuver.MERGE -> "merge"
            Maneuver.STRAIGHT -> "continue straight"
            Maneuver.DEPART -> "head off"
            else -> "take the next turn"
        }

        private fun streetPart(g: MapsGuidance): String {
            val s = g.street?.takeIf { it.isNotBlank() } ?: return ""
            if (spokenManeuver(g) == Maneuver.ARRIVE) return ""
            return (if (g.towards) " towards " else " onto ") + speakable(s)
        }

        private fun sidePart(g: MapsGuidance): String {
            val w = (g.instruction ?: "").lowercase()
            return when {
                "on the left" in w || "on your left" in w -> " on the left"
                "on the right" in w || "on your right" in w -> " on the right"
                else -> ""
            }
        }

        fun ordinal(n: Int): String = when (n) {
            1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"; 5 -> "fifth"
            6 -> "sixth"; 7 -> "seventh"; 8 -> "eighth"
            else -> "${n}th"
        }

        /** Road abbreviations read aloud in full ("Rd" as "Road"); slashes between names as "and". */
        fun speakable(name: String): String {
            val parts = name.split('/').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
            return parts.joinToString(" and ") { p ->
                val words = p.split(' ').toMutableList()
                for (i in words.indices) {
                    val w = words[i].trimEnd('.', ',')
                    val last = i == words.lastIndex
                    words[i] = when (w) {
                        "Rd" -> "Road"
                        "Ave" -> "Avenue"
                        "Hwy" -> "Highway"
                        "Ln" -> "Lane"
                        "Blvd" -> "Boulevard"
                        "Expy" -> "Expressway"
                        "Mg", "MG" -> "M G"
                        "St" -> if (last) "Street" else "Saint"
                        "Nh", "NH" -> "N H"
                        "Sh", "SH" -> if (i + 1 < words.size && words[i + 1].firstOrNull()?.isDigit() == true) "S H" else words[i]
                        else -> words[i]
                    }
                }
                words.joinToString(" ")
            }
        }

        /** "300 metres", "1.5 kilometres", "500 feet", "half a mile". */
        fun spokenDistance(m: Double, imperial: Boolean): String {
            if (imperial) {
                val miles = m / 1609.344
                return when {
                    miles < 0.1 -> "${max(50, ((m / 0.3048) / 50).roundToInt() * 50)} feet"
                    miles < 0.35 -> "a quarter of a mile".takeIf { miles >= 0.2 } ?: String.format(java.util.Locale.US, "%.1f miles", miles)
                    miles < 0.65 -> "half a mile"
                    miles < 1.05 -> "1 mile"
                    miles < 10 -> String.format(java.util.Locale.US, "%.1f miles", miles).replace(".0 ", " ")
                    else -> "${miles.roundToInt()} miles"
                }
            }
            return when {
                m < 100 -> "${max(10, (m / 10).roundToInt() * 10)} metres"
                m < 1_000 -> "${(m / 50).roundToInt() * 50} metres".let { if (it == "1000 metres") "1 kilometre" else it }
                m < 1_050 -> "1 kilometre"
                m < 10_000 -> {
                    val km = (m / 100).roundToInt() / 10.0
                    if (km % 1.0 == 0.0) "${km.toInt()} kilometres" else "$km kilometres"
                }
                else -> "${(m / 1000).roundToInt()} kilometres"
            }
        }
    }
}
