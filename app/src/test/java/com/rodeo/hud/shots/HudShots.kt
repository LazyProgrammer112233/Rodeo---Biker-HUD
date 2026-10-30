package com.rodeo.hud.shots

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rodeo.hud.core.Severity
import com.rodeo.hud.core.VibrationLevel
import com.rodeo.hud.data.Catalog
import com.rodeo.hud.data.Ride
import com.rodeo.hud.data.SosContact
import com.rodeo.hud.data.TrackPoint
import com.rodeo.hud.nav.GuidanceUi
import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.Place
import com.rodeo.hud.sos.SmsStatus
import com.rodeo.hud.ui.hud.CockpitControls
import com.rodeo.hud.ui.hud.CockpitLayout
import com.rodeo.hud.ui.hud.CockpitState
import com.rodeo.hud.ui.hud.MapChrome
import com.rodeo.hud.ui.hud.MapChromeActions
import com.rodeo.hud.ui.hud.MapChromeState
import com.rodeo.hud.ui.hud.MusicUi
import com.rodeo.hud.ui.hud.RideSummary
import com.rodeo.hud.ui.hud.SosActiveScreen
import com.rodeo.hud.ui.hud.SosCountdownScreen
import com.rodeo.hud.ui.hud.VoicePanel
import com.rodeo.hud.ui.hud.WarningUi
import com.rodeo.hud.ui.theme.LocalHud
import com.rodeo.hud.ui.theme.RodeoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every HUD screen at phone and tablet sizes, in day and night, for several brands,
 * into app/build/shots so clipped text or buttons can be spotted without a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h900dp-land-xhdpi", application = Application::class)
class HudShots {
    @get:Rule val compose = createComposeRule()

    data class Frame(val name: String, val w: Int, val h: Int)
    data class Look(val name: String, val brand: String, val paint: Long?, val dark: Boolean)

    private val frames = listOf(
        Frame("640x360", 640, 360),
        Frame("800x360", 800, 360),
        Frame("915x412", 915, 412),
        Frame("1280x800", 1280, 800),
    )
    private val looks = listOf(
        Look("ktm-dark", "ktm", 0xFFFF6600, true),
        Look("ktm-day", "ktm", 0xFFFF6600, false),
        Look("re-dark", "royal_enfield", 0xFF141414, true),
        Look("ather-day", "ather", null, false),
    )
    private val ktmDark = listOf(looks[0])

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

    private val parked = CockpitState(
        time = "21:45", timeSuffix = "IST", weatherTemp = "29°C", weatherLabel = "Partly cloudy", batteryPct = 84, phoneTempC = 36,
        model = "390 Duke", year = "2023", paintName = "Electronic Orange",
        lastDistanceKm = 48.2f, lastAvgKmh = 41, lastTopKmh = 112,
        music = MusicUi(
            "Hyper-Velocity (VIP Mix)", "Apex Drivers", "Spotify", playing = true, hasAccess = true,
            durationMs = 224_000, position = { 83_000L }, volumePct = 60,
        ),
        btOn = true, btDevice = "SENA 50S", wifiOn = true, packCount = 4, packActive = true, musicPadOn = true,
    )

    private val riding = parked.copy(
        riding = true, speedKmh = 72f, distanceKm = 12.4f, avgKmh = 38, elapsed = "0:21", topKmh = 96,
        vibration = VibrationLevel.MEDIUM, bumps = 3, lockInput = true,
        warning = WarningUi("Pothole ahead in 120 m", "Reported by 3 riders · slow down", Severity.WARN, more = 1),
    )

    private val guidance = GuidanceUi(
        Maneuver.RIGHT, "450 m", "Turn right onto Nandi Hills Road", "Nandi Hills", "38.4 km", "42 min",
    )

    @Test fun parked() = shoot("hud-parked", frames, looks) {
        CockpitLayout(parked, NoActions, mapOnRight = true, map = { FakeMap(it, MapChromeState(home = home, homeSub = "18 km", parked = bike, parkedSub = "0.2 km")) })
    }

    @Test fun riding() = shoot("hud-riding", frames, looks) {
        CockpitLayout(riding, NoActions, mapOnRight = true, map = { FakeMap(it, MapChromeState(guidance = guidance, lockInput = true, bearingDeg = 38f, speedKmh = 72f)) })
    }

    @Test fun musicPad() = shoot("hud-music-pad", frames, ktmDark) {
        androidx.compose.foundation.layout.Box {
            CockpitLayout(riding.copy(warning = null), NoActions, mapOnRight = true, map = { FakeMap(it, MapChromeState(guidance = guidance, lockInput = true)) })
            com.rodeo.hud.ui.hud.MusicPad(playing = true, onAction = {}, onExit = {})
        }
    }

    @Test fun ridingEndSlider() = shoot("hud-riding-end", frames, ktmDark) {
        CockpitLayout(
            riding.copy(warning = null, showEndSlider = true, speedKmh = 0f, btDevice = null, wiredHeadset = true),
            NoActions, mapOnRight = false,
            map = { FakeMap(it, MapChromeState(guidance = guidance.copy(maneuver = Maneuver.ROUNDABOUT_LEFT, status = "rerouting"))) },
        )
    }

    @Test fun searchResults() = shoot("hud-search", listOf(frames[0], frames[2]), listOf(looks[0], looks[1])) {
        CockpitLayout(
            parked.copy(music = MusicUi(volumePct = 47), btOn = false, btDevice = null, warning = WarningUi("Rain in 20 minutes", "70% chance", Severity.INFO)),
            NoActions, mapOnRight = true,
            map = {
                FakeMap(
                    it,
                    MapChromeState(
                        home = home, homeSub = "18 km",
                        results = listOf(
                            Place("Nandi Hills", "Chikkaballapur, Karnataka", 13.37, 77.68),
                            Place("Indian Oil petrol pump, Hebbal", "Bellary Road, Bengaluru, Karnataka 560024", 13.03, 77.59),
                            Place("Cafe Coffee Day Highway Point", "NH 44, Devanahalli", 13.24, 77.71),
                        ),
                    ),
                )
            },
        )
    }

    @Test fun searchNothingFound() = shoot("hud-search-none", listOf(frames[0]), listOf(looks[0], looks[1])) {
        CockpitLayout(
            parked, NoActions, mapOnRight = true,
            map = { FakeMap(it, MapChromeState(home = home, homeSub = "18 km", searchNote = "Nothing found for \"qwzx\". Try adding the area or town.")) },
        )
    }

    @Test fun maneuvers() = shoot("turn-glyphs", listOf(Frame("600x900", 600, 900)), listOf(looks[0], looks[1])) {
        androidx.compose.foundation.layout.Column {
            Maneuver.entries.chunked(3).forEach { row ->
                androidx.compose.foundation.layout.Row {
                    row.forEach { m ->
                        Box(Modifier.requiredSize(200.dp, 160.dp)) {
                            com.rodeo.hud.ui.hud.TurnCard(guidance.copy(maneuver = m, instruction = m.name), NoMap)
                        }
                    }
                }
            }
        }
    }

    @Test fun crashCountdown() = shoot("sos-countdown", frames, listOf(looks[0], looks[1])) {
        SosCountdownScreen(
            secondsLeft = 23, total = 30, crash = true, dryRun = false,
            contacts = listOf(SosContact("Mom (Emergency #1)", "9876543210"), SosContact("Priya K.", "9876501234"), SosContact("Arjun", "9988776655")),
            gps = "12.9716°N, 77.5946°E", smsReady = true, speedKmh = 0, bloodGroup = "O+",
            onOk = {}, onSendNow = {},
        )
    }

    @Test fun crashCountdownManyContacts() = shoot("sos-countdown-5", listOf(frames[0], frames[2]), ktmDark) {
        SosCountdownScreen(
            secondsLeft = 7, total = 10, crash = false, dryRun = true,
            contacts = (1..5).map { SosContact("Contact number $it with a long name", "98765432$it$it") },
            gps = null, smsReady = false, speedKmh = 48, bloodGroup = "",
            onOk = {}, onSendNow = {},
        )
    }

    @Test fun sosActive() = shoot("sos-active", frames, ktmDark) {
        SosActiveScreen(
            dryRun = false, crash = true,
            sms = listOf(
                SmsStatus(SosContact("Mom", "9876543210"), SmsStatus.State.SENT),
                SmsStatus(SosContact("Priya K.", "9876501234"), SmsStatus.State.SENDING),
                SmsStatus(SosContact("Arjun", "9988776655"), SmsStatus.State.FAILED, "no mobile signal"),
            ),
            hasContacts = true, updates = 2,
            message = "RODEO SOS: Possible crash detected. Location: https://maps.google.com/?q=12.97160,77.59460. Speed 0 km/h, heading 38 deg. Bike: KTM 390 Duke 2023 (Electronic Orange), reg KA01AB1234. Blood group O+. Phone battery 84%. Time 21:45, 27 Oct.",
            onStop = {},
        )
    }

    @Test fun voice() = shoot("voice", listOf(frames[0], frames[2]), listOf(looks[0], looks[1])) {
        VoicePanel(listening = true, heard = "hey doku navigate home", reply = "", wakePhrase = "Hey Doku", level = 0.4f, onClose = {})
    }

    @Test fun voiceReply() = shoot("voice-reply", listOf(frames[0], frames[2]), listOf(looks[0])) {
        VoicePanel(listening = false, heard = "hey doku emergency", reply = "Emergency. Sending SOS in 10 seconds.", wakePhrase = "Hey Doku", level = 0f, onClose = {})
    }

    @Test fun summary() = shoot("ride-summary", listOf(frames[0], frames[2], frames[3]), listOf(looks[0], looks[1])) {
        RideSummary(sampleRide, onClose = {})
    }

    private val home = Place("Home", "", 12.97, 77.59)
    private val bike = Place("Parked bike", "", 12.98, 77.6)

    private val sampleRide = Ride(
        id = "r1", startTime = 0L, endTime = 3_780_000L, startLat = 12.97, startLng = 77.59, parkLat = 13.37, parkLng = 77.68,
        distanceM = 62_400.0, maxSpeedKmh = 104f, avgSpeedKmh = 46f, vibrationAvg = 0.21f, vibrationPeak = 1.4f, highVibrationSec = 40, hazards = 3,
        points = (0..60).map { i -> TrackPoint(12.97 + i * 0.0066, 77.59 + kotlin.math.sin(i / 8.0) * 0.03 + i * 0.0015, i * 60_000L, 40f) },
    )
}

/** A stand-in map (osmdroid cannot draw under Robolectric) with the real chrome on top. */
@Composable
private fun FakeMap(modifier: Modifier, chrome: MapChromeState) {
    val c = LocalHud.current
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(if (c.dark) Color(0xFF1B1E24) else Color(0xFFE9E6DF))
            val road = if (c.dark) Color(0xFF3A3F49) else Color.White
            for (k in 0..6) drawLine(road, Offset(0f, size.height * k / 6f + 30f), Offset(size.width, size.height * k / 6f - 40f), 10f)
            for (k in 0..4) drawLine(road, Offset(size.width * k / 4f, 0f), Offset(size.width * k / 4f + 60f, size.height), 7f)
            val route = Path().apply {
                moveTo(size.width * 0.5f, size.height)
                cubicTo(size.width * 0.45f, size.height * 0.6f, size.width * 0.8f, size.height * 0.5f, size.width * 0.7f, 0f)
            }
            drawPath(route, c.accent, style = Stroke(9f, cap = StrokeCap.Round))
        }
        MapChrome(chrome, NoMap, Modifier.fillMaxSize())
    }
}

private object NoActions : CockpitControls {
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

private object NoMap : MapChromeActions {
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
