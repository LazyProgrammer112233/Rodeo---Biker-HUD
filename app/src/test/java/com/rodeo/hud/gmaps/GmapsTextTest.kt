package com.rodeo.hud.gmaps

import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.gmaps.ArrowReading
import com.rodeo.hud.nav.gmaps.GmapsText
import com.rodeo.hud.nav.gmaps.MapsGuidance
import com.rodeo.hud.nav.gmaps.MapsNotice
import com.rodeo.hud.nav.gmaps.NavStatus
import com.rodeo.hud.nav.gmaps.NoticeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmapsTextTest {
    private fun parse(t: String?, x: String?, s: String?, m: Maneuver? = null) =
        NoticeParser.parse(MapsNotice(t, x, s), m?.let { ArrowReading(it, confident = true) })!!

    @Test fun everyTurnHasAHeadline() {
        val words = Maneuver.entries.associateWith { GmapsText.action(MapsGuidance(it)) }
        assertEquals("TURN LEFT", words[Maneuver.LEFT])
        assertEquals("SHARP RIGHT", words[Maneuver.SHARP_RIGHT])
        assertEquals("U-TURN", words[Maneuver.UTURN_LEFT])
        assertEquals("KEEP RIGHT", words[Maneuver.KEEP_RIGHT])
        assertEquals("MERGE", words[Maneuver.MERGE])
        assertEquals("ROUNDABOUT", words[Maneuver.ROUNDABOUT_STRAIGHT])
        assertEquals("NEXT TURN", words[Maneuver.UNKNOWN])
        words.values.forEach { assert(it.isNotBlank()) }
    }

    @Test fun roundaboutExitHeadline() {
        assertEquals("2ND EXIT", GmapsText.action(MapsGuidance(Maneuver.ROUNDABOUT_LEFT, roundaboutExit = 2)))
        assertEquals("11TH EXIT", GmapsText.action(MapsGuidance(Maneuver.ROUNDABOUT_LEFT, roundaboutExit = 11)))
        assertEquals("3RD EXIT", GmapsText.action(MapsGuidance(Maneuver.ROUNDABOUT_RIGHT, roundaboutExit = 3)))
    }

    @Test fun unclearPictureIsNotNamed() {
        assertEquals("NEXT TURN", GmapsText.action(MapsGuidance(Maneuver.SLIGHT_LEFT, arrowTrusted = false)))
    }

    @Test fun fullNotice() {
        val g = parse("450 m", "MG Road", "15 min · 6.8 km · 1:35 PM ETA", Maneuver.RIGHT)
        assertEquals("TURN RIGHT", GmapsText.action(g))
        assertEquals("onto MG Road", GmapsText.road(g))
        assertEquals("15 min  ·  6.8 km  ·  ETA 1:35 PM", GmapsText.trip(g))
        assertEquals("450" to "M", GmapsText.distanceParts(g))
    }

    @Test fun towardAndStatus() {
        assertEquals("toward Hosur", GmapsText.road(parse("2 km", "toward Hosur", null, Maneuver.KEEP_LEFT)))
        val r = parse("Rerouting…", null, null)
        assertEquals("REROUTING", GmapsText.action(r))
        assertEquals("1.2" to "KM", GmapsText.distanceParts(parse("1.2 km", "NH 44", null, Maneuver.LEFT)))
        assertNull(GmapsText.distanceParts(r))
        assertEquals("ARRIVED", GmapsText.action(MapsGuidance(Maneuver.ARRIVE, status = NavStatus.ARRIVED)))
    }

    @Test fun hours() = assertEquals("1 h 5 min", GmapsText.duration(65))

    @Test fun demoTripReadsCleanly() {
        com.rodeo.hud.nav.gmaps.GmapsDemo.TRIP.forEach { p ->
            val n = p.first; val a = p.second
            val g = NoticeParser.parse(n, a)!!
            assert(GmapsText.action(g).isNotBlank())
        }
    }
}
