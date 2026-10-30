package com.rodeo.hud.shots

import com.rodeo.hud.ui.settings.TapSetupScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rodeo.hud.data.Catalog
import com.rodeo.hud.ui.garage.GarageScreen
import com.rodeo.hud.ui.pack.PackScreen
import com.rodeo.hud.ui.rides.RidesScreen
import com.rodeo.hud.ui.settings.SettingsScreen
import com.rodeo.hud.ui.setup.Onboarding
import com.rodeo.hud.ui.theme.RodeoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The menu screens, which read the live app state, rendered with the real app object. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h900dp-land-xhdpi")
class ScreenShots {
    @get:Rule val compose = createComposeRule()

    private val sizes = listOf(640 to 360, 915 to 412, 1280 to 800)
    private val looks = listOf(true, false)

    private fun shoot(prefix: String, sizes: List<Pair<Int, Int>> = this.sizes, content: @Composable () -> Unit) {
        var size by mutableStateOf(sizes.first())
        var dark by mutableStateOf(true)
        compose.setContent {
            Box(Modifier.requiredSize(size.first.dp, size.second.dp).testTag("frame")) {
                RodeoTheme(Catalog.brand("ktm"), 0xFFFF6600, dark) { content() }
            }
        }
        for (s in sizes) for (d in looks) {
            size = s
            dark = d
            compose.waitForIdle()
            compose.onNodeWithTag("frame").captureRoboImage("build/shots/$prefix-${s.first}x${s.second}-${if (d) "dark" else "day"}.png")
        }
    }

    @Test fun settings() = shoot("screen-settings") { SettingsScreen(onBack = {}, onChangeBike = {}, onSimulateCrash = {}, onDropTest = {}, onTapSetup = {}) }

    /** The whole of Settings at phone widths, tall enough that nothing is scrolled out of view. */
    @Config(qualifiers = "w1400dp-h2400dp-xhdpi")
    @Test fun settingsFull() = shoot("screen-settings-full", listOf(640 to 2100, 915 to 1900)) {
        SettingsScreen(onBack = {}, onChangeBike = {}, onSimulateCrash = {}, onDropTest = {}, onTapSetup = {})
    }

    @Test fun garage() = shoot("screen-garage") { GarageScreen(onBack = {}) }

    @Test fun rides() = shoot("screen-rides") { RidesScreen(onBack = {}, onNavigated = {}) }

    @Test fun pack() = shoot("screen-pack") { PackScreen(onBack = {}) }

    @Test fun tapSetup() = shoot("screen-taps") { TapSetupScreen(onBack = {}) }

    @Test fun onboarding() = shoot("screen-onboarding") { Onboarding(onDone = {}) }
}
