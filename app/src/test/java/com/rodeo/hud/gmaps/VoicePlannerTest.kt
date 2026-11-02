package com.rodeo.hud.gmaps

import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.gmaps.MapsGuidance
import com.rodeo.hud.nav.gmaps.NavStatus
import com.rodeo.hud.nav.gmaps.VoicePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicePlannerTest {
    private val said = ArrayList<String>()
    private val planner = VoicePlanner { text, _ -> said += text }
    private var t = 0L

    private fun g(m: Maneuver, d: Double?, street: String? = "MG Road", exit: Int? = null, trusted: Boolean = true, status: NavStatus = NavStatus.NAVIGATING) =
        MapsGuidance(m, arrowTrusted = trusted, distanceM = d, distanceText = d?.let { "${it.toInt()} m" }, street = street, roundaboutExit = exit, status = status)

    private fun update(x: MapsGuidance) { t += 5_000; planner.onGuidance(x, t) }

    @Test fun threePromptsPerTurn() {
        planner.setSpeed(10.0)
        update(g(Maneuver.LEFT, 1500.0))
        update(g(Maneuver.LEFT, 1200.0))
        update(g(Maneuver.LEFT, 900.0))
        update(g(Maneuver.LEFT, 600.0))
        update(g(Maneuver.LEFT, 400.0))
        update(g(Maneuver.LEFT, 250.0))
        update(g(Maneuver.LEFT, 100.0))
        update(g(Maneuver.LEFT, 60.0))
        assertEquals(
            listOf(
                "Following Google Maps. In 1.5 kilometres, turn left onto M G Road.",
                "In 400 metres, turn left.",
                "Turn left now, onto M G Road.",
            ),
            said,
        )
    }

    @Test fun gpsCountsDownBetweenUpdates() {
        planner.setSpeed(15.0)
        update(g(Maneuver.RIGHT, 300.0))
        said.clear()
        // No new notification, but the bike covers 200 m.
        repeat(20) { t += 1_000; planner.onTravel(10.0, t) }
        assertEquals(listOf("Turn right now, onto M G Road."), said)
    }

    @Test fun sameTurnTwiceInARow() {
        planner.setSpeed(8.0)
        update(g(Maneuver.LEFT, 120.0))
        update(g(Maneuver.LEFT, 40.0))
        update(g(Maneuver.LEFT, 600.0))
        assertEquals(3, said.size)
        assertEquals("In 600 metres, turn left onto M G Road.", said.last())
    }

    @Test fun reroutingSaidOnce() {
        update(g(Maneuver.LEFT, 800.0))
        update(g(Maneuver.UNKNOWN, null, status = NavStatus.REROUTING))
        update(g(Maneuver.UNKNOWN, null, status = NavStatus.REROUTING))
        assertEquals(1, said.count { it == "Rerouting" })
    }

    @Test fun unclearPictureIsNotGuessedAloud() {
        update(g(Maneuver.SLIGHT_RIGHT, 300.0, street = "Railway St", trusted = false))
        assertEquals("Following Google Maps. In 300 metres, take the next turn onto Railway Street.", said.single())
    }

    @Test fun roundaboutExit() {
        update(g(Maneuver.ROUNDABOUT_LEFT, 500.0, street = "NH 44", exit = 2))
        assertTrue(said.single(), said.single().endsWith("at the roundabout, take the second exit onto N H 44."))
    }

    @Test fun uTurnAndArrival() {
        update(g(Maneuver.UTURN_RIGHT, 200.0, street = null))
        assertEquals("Following Google Maps. In 200 metres, make a U-turn.", said.last())
        update(MapsGuidance(Maneuver.ARRIVE, distanceM = 150.0, instruction = "Destination will be on the right"))
        assertEquals("In 150 metres, your destination will be on the right.", said.last())
        update(MapsGuidance(Maneuver.ARRIVE, street = "Lalbagh", status = NavStatus.ARRIVED))
        assertEquals("You have arrived at Lalbagh.", said.last())
        t += 1000
        planner.onEnded(t)
        assertEquals("You have arrived at Lalbagh.", said.last())
    }

    @Test fun endedMidRoute() {
        update(g(Maneuver.LEFT, 800.0))
        t += 1000
        planner.onEnded(t)
        assertEquals("Google Maps navigation ended.", said.last())
    }

    @Test fun longStretch() {
        update(g(Maneuver.KEEP_LEFT, 12_000.0, street = "Hosur"))
        assertEquals("Following Google Maps. Continue for 12 kilometres, then keep left onto Hosur.", said.single())
    }

    @Test fun distances() {
        assertEquals("50 metres", VoicePlanner.spokenDistance(52.0, false))
        assertEquals("300 metres", VoicePlanner.spokenDistance(310.0, false))
        assertEquals("1 kilometre", VoicePlanner.spokenDistance(990.0, false))
        assertEquals("1.5 kilometres", VoicePlanner.spokenDistance(1_480.0, false))
        assertEquals("12 kilometres", VoicePlanner.spokenDistance(12_300.0, false))
        assertEquals("500 feet", VoicePlanner.spokenDistance(152.4, true))
        assertEquals("half a mile", VoicePlanner.spokenDistance(800.0, true))
    }

    @Test fun roadNamesReadInFull() {
        assertEquals("Brigade Road", VoicePlanner.speakable("Brigade Rd"))
        assertEquals("Saint Mark's Road", VoicePlanner.speakable("St Mark's Rd"))
        assertEquals("Church Street", VoicePlanner.speakable("Church St"))
        assertEquals("Hosur Road and N H 44", VoicePlanner.speakable("Hosur Rd/NH 44"))
    }
}
