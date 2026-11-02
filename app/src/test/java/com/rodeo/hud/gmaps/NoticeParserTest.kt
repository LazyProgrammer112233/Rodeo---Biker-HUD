package com.rodeo.hud.gmaps

import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.gmaps.ArrowReading
import com.rodeo.hud.nav.gmaps.MapsNotice
import com.rodeo.hud.nav.gmaps.NavStatus
import com.rodeo.hud.nav.gmaps.NoticeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Google Maps' navigation notification text, in the shapes it has been seen in. */
class NoticeParserTest {
    private val left = ArrowReading(Maneuver.LEFT, confident = true, endAngle = -90f, turn = -90f)
    private val uLeft = ArrowReading(Maneuver.UTURN_LEFT, confident = true, endAngle = -180f, turn = -180f)
    private val unsure = ArrowReading(Maneuver.SLIGHT_RIGHT, confident = false)

    @Test fun currentLayout_distanceTitle_streetText_summarySubText() {
        val g = NoticeParser.parse(MapsNotice("50 m", "Railway St", "15 min · 6.8 km · 1:35 PM ETA"), left)!!
        assertEquals(Maneuver.LEFT, g.maneuver)
        assertTrue(g.arrowTrusted)
        assertFalse(g.fromText)
        assertEquals("50 m", g.distanceText)
        assertEquals(50.0, g.distanceM!!, 0.01)
        assertEquals("Railway St", g.street)
        assertEquals("6.8 km", g.remainingText)
        assertEquals(6800.0, g.remainingM!!, 0.01)
        assertEquals(15, g.durationMin)
        assertEquals("1:35 PM", g.eta)
        assertEquals(NavStatus.NAVIGATING, g.status)
    }

    @Test fun noSpaceBeforeUnit() {
        val g = NoticeParser.parse(MapsNotice("50m", "Railway St", "15 min · 6.8km · 1:35 PM ETA"), left)!!
        assertEquals(50.0, g.distanceM!!, 0.01)
        assertEquals(6800.0, g.remainingM!!, 0.01)
    }

    @Test fun wordsBeatThePicture() {
        val g = NoticeParser.parse(MapsNotice("Turn right onto MG Road", "300 m", "8 min · 2.1 km · 18:42 ETA"), left)!!
        assertEquals(Maneuver.RIGHT, g.maneuver)
        assertTrue(g.fromText)
        assertEquals("MG Road", g.street)
        assertEquals(300.0, g.distanceM!!, 0.01)
        assertEquals("18:42", g.eta)
    }

    @Test fun distanceAndInstructionOnOneLine() {
        val g = NoticeParser.parse(MapsNotice("200 m · Turn left onto 100 Feet Road", null, "6 min · 1.9 km · 9:10 AM ETA"))!!
        assertEquals(Maneuver.LEFT, g.maneuver)
        assertEquals(200.0, g.distanceM!!, 0.01)
        assertEquals("100 Feet Road", g.street)
    }

    @Test fun roadNamedAfterItsWidthIsNotADistance() {
        val g = NoticeParser.parse(MapsNotice("1.2 km", "100 Feet Road", "6 min · 3 km · 9:10 AM ETA"), left)!!
        assertEquals(1200.0, g.distanceM!!, 0.01)
        assertEquals("100 Feet Road", g.street)
        val h = NoticeParser.parse(MapsNotice("80 Feet Rd", null, "6 min · 3 km · 9:10 AM ETA"), left)!!
        assertNull(h.distanceM)
        assertEquals("80 Feet Rd", h.street)
    }

    @Test fun roundaboutWithExitNumber() {
        val g = NoticeParser.parse(MapsNotice("400 m", "At the roundabout, take the 2nd exit onto NH 44", "22 min · 14 km · 7:05 PM ETA"))!!
        assertTrue(g.maneuver.name.startsWith("ROUNDABOUT"))
        assertEquals(2, g.roundaboutExit)
        assertEquals("NH 44", g.street)
        val w = NoticeParser.parse(MapsNotice("400 m", "At the roundabout, take the third exit", null))!!
        assertEquals(3, w.roundaboutExit)
    }

    @Test fun roundaboutSideFromPicture() {
        val g = NoticeParser.parse(MapsNotice("400 m", "At the roundabout, take the 1st exit", null), left)!!
        assertEquals(Maneuver.ROUNDABOUT_LEFT, g.maneuver)
    }

    @Test fun uTurnSide() {
        assertEquals(Maneuver.UTURN_RIGHT, NoticeParser.parse(MapsNotice("150 m", "Make a U-turn", null))!!.maneuver)
        assertEquals(Maneuver.UTURN_LEFT, NoticeParser.parse(MapsNotice("150 m", "Make a U-turn", null), uLeft)!!.maneuver)
    }

    @Test fun reroutingAndGps() {
        assertEquals(NavStatus.REROUTING, NoticeParser.parse(MapsNotice("Rerouting…", null, null))!!.status)
        assertEquals(NavStatus.NO_GPS, NoticeParser.parse(MapsNotice("Searching for GPS…", null, "15 min · 6.8 km · 1:35 PM ETA"))!!.status)
    }

    @Test fun arrival() {
        val a = NoticeParser.parse(MapsNotice("You have arrived", "Lalbagh Botanical Garden", null))!!
        assertEquals(NavStatus.ARRIVED, a.status)
        assertEquals(Maneuver.ARRIVE, a.maneuver)
        val ahead = NoticeParser.parse(MapsNotice("150 m", "Destination will be on the right", "1 min · 150 m · 6:02 PM ETA"))!!
        assertEquals(Maneuver.ARRIVE, ahead.maneuver)
        assertEquals(NavStatus.NAVIGATING, ahead.status)
    }

    @Test fun imperialUnits() {
        val g = NoticeParser.parse(MapsNotice("500 ft", "Main St", "5 min · 0.8 mi · 10:05 AM ETA"), left)!!
        assertTrue(g.imperial)
        assertEquals(152.4, g.distanceM!!, 0.1)
        assertEquals(1287.5, g.remainingM!!, 0.5)
    }

    @Test fun decimalCommaAndThousands() {
        assertEquals(1200.0, NoticeParser.parse(MapsNotice("1,2 km", "MG Road", null), left)!!.distanceM!!, 0.01)
        assertEquals(1200.0, NoticeParser.parse(MapsNotice("1,200 m", "MG Road", null), left)!!.distanceM!!, 0.01)
    }

    @Test fun hindi() {
        val g = NoticeParser.parse(MapsNotice("200 मी", "बाएं मुड़ें", "15 मिनट · 6.8 किमी · 1:35 PM"))!!
        assertEquals(Maneuver.LEFT, g.maneuver)
        assertEquals(200.0, g.distanceM!!, 0.01)
        assertEquals(15, g.durationMin)
        assertEquals(6800.0, g.remainingM!!, 0.01)
    }

    @Test fun towards() {
        val g = NoticeParser.parse(MapsNotice("2 km", "toward Indiranagar", "9 min · 4 km · 6:10 PM ETA"), left)!!
        assertTrue(g.towards)
        assertEquals("Indiranagar", g.street)
        val h = NoticeParser.parse(MapsNotice("2 km", "Keep left toward Hosur", null))!!
        assertEquals(Maneuver.KEEP_LEFT, h.maneuver)
        assertEquals("Hosur", h.street)
        assertTrue(h.towards)
    }

    @Test fun unreadablePictureFallsBackToGooglesOwn() {
        val g = NoticeParser.parse(MapsNotice("300 m", "Railway St", null), null)!!
        assertEquals(Maneuver.UNKNOWN, g.maneuver)
        assertFalse(g.arrowTrusted)
        val h = NoticeParser.parse(MapsNotice("300 m", "Railway St", null), unsure)!!
        assertEquals(Maneuver.SLIGHT_RIGHT, h.maneuver)
        assertFalse(h.arrowTrusted)
    }

    @Test fun hoursInSummary() {
        val g = NoticeParser.parse(MapsNotice("3 km", "NH 48", "1 hr 5 min · 52 km · 14:20 ETA"), left)!!
        assertEquals(65, g.durationMin)
        assertEquals(52_000.0, g.remainingM!!, 0.01)
        assertEquals("14:20", g.eta)
    }

    @Test fun startingNotificationWithoutSummary() {
        val g = NoticeParser.parse(MapsNotice("Starting navigation…", "Head north on Residency Rd", null))!!
        assertEquals(Maneuver.DEPART, g.maneuver)
        assertEquals("Residency Rd", g.street)
    }

    @Test fun iconResourceNames() {
        assertEquals(Maneuver.SLIGHT_LEFT, NoticeParser.turnFromIconName("da_turn_slight_left_white"))
        assertEquals(Maneuver.ROUNDABOUT_RIGHT, NoticeParser.turnFromIconName("da_turn_roundabout_right"))
        assertEquals(Maneuver.UTURN_RIGHT, NoticeParser.turnFromIconName("da_turn_uturn"))
        assertNull(NoticeParser.turnFromIconName("quantum_ic_maps_white_24"))
        val g = NoticeParser.parse(MapsNotice("80 m", "Brigade Rd", null, iconName = "da_turn_sharp_right"))!!
        assertEquals(Maneuver.SHARP_RIGHT, g.maneuver)
    }

    @Test fun blankIsNothing() {
        assertNull(NoticeParser.parse(MapsNotice(null, " ", null)))
    }

    @Test fun customLayoutLines() {
        val g = NoticeParser.parse(MapsNotice(extraLines = listOf("350 m", "Turn left onto Church St", "12 min · 3.4 km · 11:03 AM")))!!
        assertEquals(Maneuver.LEFT, g.maneuver)
        assertEquals(350.0, g.distanceM!!, 0.01)
        assertEquals("Church St", g.street)
        assertEquals(12, g.durationMin)
    }
}
