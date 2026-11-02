package com.rodeo.hud.shots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rodeo.hud.data.Catalog
import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.gmaps.ArrowReading
import com.rodeo.hud.nav.gmaps.GmapsDemo
import com.rodeo.hud.nav.gmaps.GmapsState
import com.rodeo.hud.nav.gmaps.MapsGuidance
import com.rodeo.hud.nav.gmaps.MapsNotice
import com.rodeo.hud.nav.gmaps.NavStatus
import com.rodeo.hud.nav.gmaps.NoticeParser
import com.rodeo.hud.ui.hud.CockpitControls
import com.rodeo.hud.ui.hud.CockpitLayout
import com.rodeo.hud.ui.hud.CockpitState
import com.rodeo.hud.ui.hud.GmapsActions
import com.rodeo.hud.ui.hud.GmapsCard
import com.rodeo.hud.ui.hud.GmapsPanel
import com.rodeo.hud.ui.hud.ManeuverGlyph
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.RodeoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Google Maps' turns on the HUD, at the phone sizes and looks the other shots use. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h900dp-land-xhdpi", application = Application::class)
class GmapsShots {
    @get:Rule val compose = createComposeRule()

    data class Frame(val name: String, val w: Int, val h: Int)
    data class Look(val name: String, val brand: String, val paint: Long?, val dark: Boolean)

    private val frames = listOf(Frame("640x360", 640, 360), Frame("915x412", 915, 412), Frame("1280x800", 1280, 800))
    private val looks = listOf(
        Look("ktm-dark", "ktm", 0xFFFF6600, true),
        Look("ktm-day", "ktm", 0xFFFF6600, false),
        Look("re-dark", "royal_enfield", 0xFF141414, true),
    )

    private fun shoot(prefix: String, frames: List<Frame>, looks: List<Look>, content: @Composable () -> Unit) {
        var frame by mutableStateOf(frames.first())
        var look by mutableStateOf(looks.first())
        compose.setContent {
            Box(Modifier.requiredSize(frame.w.dp, frame.h.dp).testTag("frame")) {
                RodeoTheme(Catalog.brand(look.brand), look.paint, look.dark) { content() }
            }
        }
        for (f in frames) for (l in looks) {
            frame = f
            look = l
            compose.waitForIdle()
            compose.onNodeWithTag("frame").captureRoboImage("build/shots/$prefix-${f.name}-${l.name}.png")
        }
    }

    private fun state(title: String?, text: String?, sub: String?, m: Maneuver?, confident: Boolean = true, picture: Bitmap? = null, voice: Boolean = true) =
        GmapsState(
            active = true,
            guidance = NoticeParser.parse(MapsNotice(title, text, sub), m?.let { ArrowReading(it, confident) }),
            picture = picture,
            voiceOn = voice,
        )

    private val riding = CockpitState(
        time = "21:45", timeSuffix = "IST", batteryPct = 84, phoneTempC = 36, model = "390 Duke", year = "2023",
        riding = true, speedKmh = 46f, distanceKm = 12.4f, avgKmh = 38, elapsed = "0:21", topKmh = 96, lockInput = true,
    )

    private val left = state("450 m", "MG Road", "15 min · 6.8 km · 1:35 PM ETA", Maneuver.LEFT)

    @Test fun hudWithGoogleMapsTurn() = shoot("gmaps-hud", frames, looks) {
        CockpitLayout(riding, NoCockpit, mapOnRight = true, map = { GmapsPanel(left, NoGmaps, it) })
    }

    @Test fun hudMapOnLeft() = shoot("gmaps-hud-left", listOf(frames[1]), listOf(looks[0])) {
        val s = state("1.2 km", "At the roundabout, take the 2nd exit onto 100 Feet Rd", "13 min · 5.9 km · 1:35 PM ETA", Maneuver.ROUNDABOUT_LEFT)
        CockpitLayout(riding, NoCockpit, mapOnRight = false, map = { GmapsPanel(s, NoGmaps, it) })
    }

    @Test fun everyTurn() = shoot("gmaps-turns", listOf(Frame("1500x1100", 1500, 1100)), listOf(looks[0], looks[1])) {
        val list = Maneuver.entries.filter { it != Maneuver.UNKNOWN }
        Column(Modifier.fillMaxSize().background(LocalHud.current.background)) {
            list.chunked(4).forEach { row ->
                Row {
                    row.forEach { m ->
                        val exit = if (m.name.startsWith("ROUNDABOUT")) "At the roundabout, take the 3rd exit" else "Brigade Rd"
                        Box(Modifier.requiredSize(375.dp, 180.dp).padding(4.dp)) {
                            GmapsPanel(state("300 m", exit, "8 min · 3.7 km · 18:42 ETA", m), NoGmaps, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }

    @Test fun glyphs() = shoot("gmaps-glyphs", listOf(Frame("900x700", 900, 700)), listOf(looks[0])) {
        val c = LocalHud.current
        Column(Modifier.fillMaxSize().background(c.background)) {
            Maneuver.entries.chunked(6).forEach { row ->
                Row {
                    row.forEach { m ->
                        Box(Modifier.requiredSize(150.dp, 140.dp).padding(10.dp).background(c.accent), contentAlignment = Alignment.Center) {
                            ManeuverGlyph(m, c.onAccent, Modifier.requiredSize(96.dp))
                        }
                    }
                }
            }
        }
    }

    @Test fun statuses() = shoot("gmaps-status", listOf(frames[1]), listOf(looks[0], looks[1])) {
        Column {
            Row {
                Box(Modifier.requiredSize(457.dp, 206.dp)) { GmapsPanel(state("Rerouting…", null, null, null), NoGmaps, Modifier.fillMaxSize()) }
                Box(Modifier.requiredSize(457.dp, 206.dp)) { GmapsPanel(state("Searching for GPS…", null, "15 min · 6.8 km · 1:35 PM ETA", null), NoGmaps, Modifier.fillMaxSize()) }
            }
            Row {
                val arrived = state("You have arrived", "Lalbagh Botanical Garden", null, Maneuver.ARRIVE).copy(ended = true)
                Box(Modifier.requiredSize(457.dp, 206.dp)) { GmapsPanel(arrived, NoGmaps, Modifier.fillMaxSize()) }
                // Rodeo could not read the arrow: Google's own picture is shown, and the voice says "next turn".
                Box(Modifier.requiredSize(457.dp, 206.dp)) {
                    GmapsPanel(state("350 m", "Church St", "3 min · 1.2 km · 1:35 PM ETA", Maneuver.SLIGHT_LEFT, confident = false, picture = googlePicture(), voice = false), NoGmaps, Modifier.fillMaxSize())
                }
            }
        }
    }

    @Test fun cardOverMap() = shoot("gmaps-card", frames, listOf(looks[0], looks[1])) {
        CockpitLayout(riding, NoCockpit, mapOnRight = true, map = { m ->
            Box(m) {
                val c = LocalHud.current
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(if (c.dark) Color(0xFF1B1E24) else Color(0xFFE9E6DF))
                    for (k in 0..6) drawLine(if (c.dark) Color(0xFF3A3F49) else Color.White, Offset(0f, size.height * k / 6f + 30f), Offset(size.width, size.height * k / 6f - 40f), 10f)
                }
                GmapsCard(state("80 m", "Make a U-turn", "8 min · 3.7 km · 1:35 PM ETA", Maneuver.UTURN_RIGHT), NoGmaps, Modifier.align(Alignment.TopCenter).padding(10.dp))
            }
        })
    }

    @Test fun panelSaysTheTurnAndButtonsWork() {
        var voice = 0
        var map = 0
        compose.setContent {
            Box(Modifier.requiredSize(915.dp, 412.dp)) {
                RodeoTheme(Catalog.brand("ktm"), 0xFFFF6600, true) {
                    GmapsPanel(left, object : GmapsActions {
                        override fun toggleVoice() { voice++ }
                        override fun toggleMap() { map++ }
                    }, Modifier.fillMaxSize())
                }
            }
        }
        compose.onNodeWithTag("gmaps-action").assertTextContains("TURN LEFT")
        compose.onNodeWithTag("gmaps-road").assertTextContains("ONTO MG ROAD")
        compose.onNodeWithTag("gmaps-distance").assertIsDisplayed()
        compose.onNodeWithText("450").assertExists()
        compose.onNodeWithContentDescription("Mute turn voice").performClick()
        compose.onNodeWithContentDescription("Show map").performClick()
        assertEquals(1, voice)
        assertEquals(1, map)
    }

    @Test fun demoTripAllDraws() {
        var step by mutableStateOf(0)
        val trip = GmapsDemo.TRIP
        compose.setContent {
            Box(Modifier.requiredSize(915.dp, 412.dp)) {
                RodeoTheme(Catalog.brand("ktm"), 0xFFFF6600, true) {
                    val (n, a) = trip[step]
                    GmapsPanel(GmapsState(active = true, guidance = NoticeParser.parse(n, a)), NoGmaps, Modifier.fillMaxSize())
                }
            }
        }
        for (i in trip.indices) {
            step = i
            compose.waitForIdle()
            compose.onNodeWithTag("gmaps-arrow").assertIsDisplayed()
        }
    }

    /** Stand-in for Google's own arrow bitmap. */
    private fun googlePicture(): Bitmap {
        val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; strokeWidth = 12f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        c.drawLine(56f, 88f, 56f, 48f, p)
        c.drawLine(56f, 48f, 30f, 20f, p)
        p.style = Paint.Style.FILL
        val path = android.graphics.Path().apply { moveTo(16f, 6f); lineTo(44f, 14f); lineTo(24f, 34f); close() }
        c.drawPath(path, p)
        return b
    }
}

private object NoGmaps : GmapsActions {
    override fun toggleVoice() = Unit
    override fun toggleMap() = Unit
}

private object NoCockpit : CockpitControls {
    override fun startRide() = Unit
    override fun requestEndRide() = Unit
    override fun confirmEndRide() = Unit
    override fun cancelEndRide() = Unit
    override fun sos() = Unit
    override fun voice() = Unit
    override fun openRides() = Unit
    override fun openGarage() = Unit
    override fun openSettings() = Unit
    override fun openPack() = Unit
    override fun dismissWarning() = Unit
    override fun musicPrevious() = Unit
    override fun musicPlayPause() = Unit
    override fun musicNext() = Unit
    override fun musicVolumeUp() = Unit
    override fun musicVolumeDown() = Unit
    override fun musicAccess() = Unit
    override fun bluetooth() = Unit
    override fun wifi() = Unit
    override fun toggleMusicPad() = Unit
}
