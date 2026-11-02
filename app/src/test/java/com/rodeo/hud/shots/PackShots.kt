package com.rodeo.hud.shots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rodeo.hud.data.Catalog
import com.rodeo.hud.nav.Place
import com.rodeo.hud.pack.Link
import com.rodeo.hud.pack.PackEvent
import com.rodeo.hud.pack.PackLogic
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.pack.PeerLink
import com.rodeo.hud.pack.VoiceUi
import com.rodeo.hud.ui.hud.MapChrome
import com.rodeo.hud.ui.hud.MapChromeActions
import com.rodeo.hud.ui.hud.MapChromeState
import com.rodeo.hud.ui.hud.PackHud
import com.rodeo.hud.ui.hud.PackHudActions
import com.rodeo.hud.ui.hud.PackHudState
import com.rodeo.hud.ui.theme.RodeoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pack layer over the map (chips, mini-map, pop-up, voice button) at the map pane's real sizes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h900dp-land-xhdpi", application = Application::class)
class PackShots {
    @get:Rule val compose = createComposeRule()

    /** Map pane sizes: phone parked / riding, wide phone, tablet. */
    private val panes = listOf(300 to 340, 360 to 340, 430 to 392, 620 to 780)

    private val now = 1_800_000_000_000L
    private val lat0 = 12.9750
    private val lng0 = 77.6060
    private fun at(northM: Double, eastM: Double) =
        (lat0 + northM / 111_195.0) to (lng0 + eastM / (111_195.0 * Math.cos(Math.toRadians(lat0))))

    private fun rider(id: String, name: String, northM: Double, eastM: Double, still: Long = 0, sos: Boolean = false, age: Long = 2_000, link: Link = Link.CLOUD) =
        at(northM, eastM).let { (la, ln) -> PackMember(id, name, 0, la, ln, if (still != 0L) 0f else 44f, now - age, still, link = link, sos = sos) }

    private val members = listOf(
        PackMember("me", "Ishan", 0, lat0, lng0, 52f, now, 0, isMe = true),
        rider("k", "Kabir", -180.0, 60.0, link = Link.NEARBY),
        rider("a", "Ananya Rao", 1_150.0, -80.0),
        rider("v", "Vikram", -2_300.0, 300.0, still = now - 200_000),
        rider("r", "Rahul Verma", 200.0, -600.0, sos = true),
        rider("p", "Priya", -900.0, -700.0, age = 150_000),
    ).let { list -> val colors = PackLogic.assignColors(list.map { it.id }); list.map { it.copy(colorIndex = colors.getValue(it.id)) } }

    private val voice = VoiceUi(wanted = true, live = true, peers = mapOf("k" to PeerLink.CONNECTED, "a" to PeerLink.CONNECTED, "v" to PeerLink.FAILED))

    private val noActions = object : PackHudActions {
        override fun toggleMute() = Unit
        override fun talk(down: Boolean) = Unit
        override fun openPack() = Unit
    }
    private val chromeActions = object : MapChromeActions {
        override fun search(query: String) = Unit
        override fun clearResults() = Unit
        override fun pick(place: Place, walking: Boolean) = Unit
        override fun stopGuidance() = Unit
        override fun toggleGuidanceVoice() = Unit
        override fun recenter() = Unit
        override fun zoomIn() = Unit
        override fun zoomOut() = Unit
        override fun voiceSearch() = Unit
    }

    private fun shoot(prefix: String, state: PackHudState) {
        var pane by mutableStateOf(panes.first())
        var dark by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.requiredSize(pane.first.dp, pane.second.dp).testTag("frame")) {
                RodeoTheme(Catalog.brand("ktm"), 0xFFFF6600, dark) {
                    Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF1C2230) else Color(0xFFE6E2D6))) {
                        MapChrome(MapChromeState(speedKmh = 52f, bearingDeg = 0f), chromeActions, Modifier.fillMaxSize())
                        PackHud(state, noActions, Modifier.fillMaxSize())
                    }
                }
            }
        }
        for (p in panes) for (d in listOf(true, false)) {
            pane = p
            dark = d
            compose.mainClock.advanceTimeBy(700)
            compose.onNodeWithTag("frame").captureRoboImage("build/shots/$prefix-${p.first}x${p.second}-${if (d) "dark" else "day"}.png")
        }
    }

    @Test fun riding() = shoot("pack-hud-riding", PackHudState(members, 0.0, voice, PackEvent("Vikram fell 2.3 km behind", 1, now, "v"), now))

    @Test fun parked() = shoot("pack-hud-parked", PackHudState(members.take(3), null, VoiceUi(), null, now))

    @Test fun compact() = shoot("pack-hud-compact", PackHudState(members, 0.0, voice.copy(muted = true), null, now, compact = true))
}
