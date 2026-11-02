package com.rodeo.hud

import android.Manifest
import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.rodeo.hud.data.ThemeMode
import com.rodeo.hud.media.MediaListenerService
import com.rodeo.hud.nav.gmaps.GmapsBridge
import com.rodeo.hud.nav.LatLng
import com.rodeo.hud.nav.Navigator
import com.rodeo.hud.nav.MapTiles
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos

/**
 * The real app on an emulator (CI): search a place, get a route from the free routers, ride it
 * with simulated GPS to arrival, go off route and get rerouted, and read a music app's song.
 * Screenshots and a report of what was shown and spoken go to the app's files folder.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DeviceTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    @get:Rule val compose = createEmptyComposeRule()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext.applicationContext
    private val g get() = ctx.graph
    private val out = File(ctx.getExternalFilesDir(null), "e2e").apply { mkdirs() }
    private val fused = LocationServices.getFusedLocationProviderClient(ctx)
    private var mock = false
    private val spoken = ArrayList<String>()

    // Central Bengaluru (Vidhana Soudha side of Cubbon Park).
    private val start = LatLng(12.9716, 77.5946)

    @Before fun setUp() {
        g.profile.update { it.copy(onboarded = true, musicPad = false, musicPadOnRide = false, waveControl = false, themeMode = ThemeMode.LIGHT, model = "390 Duke", year = 2023) }
        mock = runCatching { Tasks.await(fused.setMockMode(true), 15, TimeUnit.SECONDS); true }
            .getOrElse { note("! mock GPS unavailable ($it); injecting positions instead"); false }
        g.speaker.onSay = { ch, text -> synchronized(spoken) { if (spoken.lastOrNull() != text) spoken += text }; Log.i(TAG, "say[$ch] $text") }
        fix(start, 0f, null)
    }

    @After fun tearDown() {
        g.speaker.onSay = null
        g.nav.stop()
        if (mock) runCatching { Tasks.await(fused.setMockMode(false), 10, TimeUnit.SECONDS) }
    }

    @Test fun a_searchRouteRideAndArrive() = launch {
        note("\n== Search, route, ride, arrive")
        waitForText("Where to?")
        shot("01-hud-day")
        search("Lalbagh")
        shot("02-search-results")
        val results = resultRows()
        note("results: " + results.joinToString(" | "))
        assertTrue("no search results", results.isNotEmpty())
        compose.onAllNodes(resultMatcher("Lalbagh"))[0].performClick()
        val route = waitForRoute()
        sleep(1_000)
        shot("03a-route-preview")
        compose.onNode(hasTestTag("start-guidance")).performClick()
        note("route: ${route.provider}, ${"%.1f".format(route.lengthM / 1000)} km, ${Navigator.shortDuration(route.timeS)}, ${route.steps.size} steps")
        route.steps.forEachIndexed { i, s -> note("   step $i ${s.maneuver}: ${s.instruction}") }
        sleep(1_500)
        shot("03-route-ready")
        val ride = resample(route.points, 12.0)
        var offRoute = 0
        var lastInstruction = ""
        for ((i, p) in ride.withIndex()) {
            val next = ride.getOrNull(i + 1)
            fix(p, 12f, next?.let { bearing(p, it) })
            sleep(450)
            val s = g.nav.state.value
            if (s.progress?.offRoute == true) offRoute++
            g.nav.guidance(s, g.location.location.value)?.let { gu ->
                val line = "${gu.distance} · ${gu.instruction}${gu.status?.let { " [$it]" } ?: ""}"
                if (gu.instruction != lastInstruction) note("   @$i card: $line")
                lastInstruction = gu.instruction
            }
            if (i == 25) shot("04-riding-day")
            if (i == ride.size / 2) {
                g.profile.update { it.copy(themeMode = ThemeMode.DARK) }
                sleep(2_500)
                shot("05-riding-night")
            }
        }
        repeat(6) { fix(ride.last(), 0f, null); sleep(700) }
        shot("06-arrived")
        val end = g.nav.state.value
        note("spoken: " + synchronized(spoken) { spoken.joinToString(" / ") })
        note("arrived=${end.progress?.arrived} offRouteFixes=$offRoute tiles=${MapTiles.style.value} tileCache=${tileCacheKb()} KB")
        assertTrue("never arrived", end.progress?.arrived == true)
        assertTrue("went off route $offRoute times while on the route", offRoute <= 2)
        assertTrue("no turn was spoken", synchronized(spoken) { spoken.any { it.contains("turn", true) || it.contains("keep", true) } })
    }

    @Test fun b_longRouteAndReroute() = launch {
        note("\n== Nandi Hills: search ranking and rerouting")
        waitForText("Where to?")
        search("Nandi Hills")
        val results = resultRows()
        note("results: " + results.joinToString(" | "))
        shot("07-search-nandi")
        compose.onAllNodes(resultMatcher("Nandi"))[0].performClick()
        val route = waitForRoute()
        compose.onNode(hasTestTag("start-guidance")).performClick()
        note("route: ${route.provider}, ${"%.1f".format(route.lengthM / 1000)} km, ${Navigator.shortDuration(route.timeS)}, ${route.steps.size} steps")
        assertTrue("Nandi Hills route is only ${route.lengthM} m", route.lengthM > 40_000)
        val ride = resample(route.points, 12.0).take(25)
        ride.forEachIndexed { i, p -> fix(p, 12f, ride.getOrNull(i + 1)?.let { bearing(p, it) }); sleep(500) }
        // Turn off the route: head 90° to the left of it for a while.
        val last = ride.last()
        val away = (bearing(ride[ride.size - 2], last) + 270f) % 360f
        var p = last
        var rerouted = false
        for (i in 0 until 60) {
            p = move(p, away.toDouble(), 12.0)
            fix(p, 12f, away)
            sleep(700)
            val s = g.nav.state.value
            if (i % 5 == 0) note("   off@$i offRoute=${s.progress?.offRoute} routing=${s.routing}")
            if (s.route != null && s.route !== route) { rerouted = true; break }
        }
        sleep(1_500)
        shot("08-rerouted")
        val now = g.nav.state.value.route
        note("rerouted=$rerouted new route ${now?.lengthM?.let { "%.1f km".format(it / 1000) }} spoken: " + synchronized(spoken) { spoken.takeLast(4).joinToString(" / ") })
        assertTrue("no new route after leaving the route", rerouted)
    }

    @Test fun c_searchWithNoMatchSaysSo() = launch {
        note("\n== Search with no match")
        waitForText("Where to?")
        search("qwzxv plorkt")
        compose.waitUntil(30_000) { compose.onAllNodes(hasText("Nothing found", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("09-search-none")
        note("message shown")
    }

    @Test fun d_musicCardShowsTheSong() {
        note("\n== Music card")
        var paused = false
        lateinit var session: MediaSession
        // A media session needs a thread with a looper.
        inst.runOnMainSync { session = MediaSession(ctx, "rodeo-e2e").apply {
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Highway Test Track")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "The Rodeo Band")
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, 215_000)
                    .build(),
            )
            setPlaybackState(playing(true, 42_000))
            setCallback(object : MediaSession.Callback() {
                override fun onPause() { paused = true; setPlaybackState(playing(false, 50_000)) }
                override fun onPlay() { setPlaybackState(playing(true, 50_000)) }
            })
            isActive = true
        } }
        try {
            launch {
                note("access=${g.music.hasAccess()}")
                waitForText("Highway Test Track", 20_000)
                sleep(1_500)
                shot("10-music-card")
                assertTrue("song length not shown", compose.onAllNodes(hasText("3:35")).fetchSemanticsNodes().isNotEmpty())
                val audio = ctx.getSystemService(AudioManager::class.java)
                val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                compose.onNode(hasContentDescription("Volume down")).performClick()
                sleep(500)
                compose.onNode(hasContentDescription("Volume up")).performClick()
                compose.onNode(hasContentDescription("Volume up")).performClick()
                sleep(500)
                val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                note("volume $before -> $after of ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
                compose.onNode(hasContentDescription("Play or pause")).performClick()
                compose.waitUntil(5_000) { paused }
                sleep(800)
                shot("11-music-paused")
                note("pause reached the player: $paused")
                assertTrue(after >= before || before == audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            }
        } finally {
            inst.runOnMainSync { session.release() }
        }
    }

    @Test fun e_googleMapsTurnsOnTheHud() {
        note("\n== Google Maps turns on the HUD (from a navigation notification)")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("e2e-nav", "E2E navigation", NotificationManager.IMPORTANCE_LOW))
        // Google Maps' own notification can't be posted from a test, so Rodeo listens to itself here.
        MediaListenerService.sources = setOf(GmapsBridge.GOOGLE_MAPS, ctx.packageName)
        g.profile.update { it.copy(gmapsVoice = true, gmapsBigPanel = true) }
        fun post(title: String, text: String?, sub: String?, arrow: Bitmap?) {
            val n = Notification.Builder(ctx, "e2e-nav")
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .apply { if (text != null) setContentText(text); if (sub != null) setSubText(sub); if (arrow != null) setLargeIcon(arrow) }
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setOngoing(true)
                .build()
            nm.notify(NAV_ID, n)
        }
        try {
            launch {
                note("access=${g.music.hasAccess()}")
                post("450 m", "MG Road", "15 min · 6.8 km · 1:35 PM ETA", arrowPicture(left = true))
                compose.waitUntil(15_000) { g.gmaps.state.value.guidance?.distanceText == "450 m" }
                sleep(1_500)
                val s1 = g.gmaps.state.value
                note("read: ${s1.guidance?.maneuver} trusted=${s1.guidance?.arrowTrusted} arrow=${s1.lastArrow?.maneuver}/${s1.lastArrow?.confident} street=${s1.guidance?.street} eta=${s1.guidance?.eta}")
                assertTrue("panel not shown", compose.onAllNodes(hasTestTag("gmaps-panel")).fetchSemanticsNodes().isNotEmpty())
                assertTrue("left turn not read from the picture", s1.guidance?.maneuver == com.rodeo.hud.nav.Maneuver.LEFT)
                shot("20-gmaps-left")
                compose.waitUntil(10_000) { synchronized(spoken) { spoken.any { it.contains("turn left", ignoreCase = true) } } }

                post("Rerouting…", null, null, null)
                compose.waitUntil(10_000) { g.gmaps.state.value.guidance?.status == com.rodeo.hud.nav.gmaps.NavStatus.REROUTING }
                sleep(800)
                shot("21-gmaps-rerouting")

                post("2 km", "At the roundabout, take the 2nd exit onto 100 Feet Rd", "13 min · 5.9 km · 1:35 PM ETA", null)
                compose.waitUntil(10_000) { g.gmaps.state.value.guidance?.roundaboutExit == 2 }
                sleep(800)
                shot("22-gmaps-roundabout")

                post("120 m", "Make a U-turn", "8 min · 3.7 km · 1:35 PM ETA", arrowPicture(left = false))
                compose.waitUntil(10_000) { g.gmaps.state.value.guidance?.maneuver?.name?.startsWith("UTURN") == true }
                sleep(800)
                shot("23-gmaps-uturn")

                compose.onNode(hasContentDescription("Show map")).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("gmaps-card")).fetchSemanticsNodes().isNotEmpty() }
                sleep(800)
                shot("24-gmaps-card-over-map")
                compose.onNode(hasContentDescription("Big turn view")).performClick()
                compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("gmaps-panel")).fetchSemanticsNodes().isNotEmpty() }

                nm.cancel(NAV_ID)
                compose.waitUntil(10_000) { g.gmaps.state.value.ended }
                sleep(800)
                shot("25-gmaps-ended")
                compose.waitUntil(15_000) { !g.gmaps.state.value.active }
                sleep(500)
                assertTrue("panel still up after Google Maps stopped", compose.onAllNodes(hasTestTag("gmaps-panel")).fetchSemanticsNodes().isEmpty())
                note("spoken: " + synchronized(spoken) { spoken.takeLast(8).joinToString(" | ") })

                // The preview trip from Settings.
                inst.runOnMainSync { g.gmaps.startDemo(stepMs = 1_200) }
                sleep(2_600)
                shot("26-gmaps-preview")
                inst.runOnMainSync { g.gmaps.stopDemo() }
            }
        } finally {
            nm.cancel(NAV_ID)
            MediaListenerService.sources = setOf(GmapsBridge.GOOGLE_MAPS)
        }
    }

    /** A white turn arrow on a clear background, like the one Google Maps puts in its notification. */
    private fun arrowPicture(left: Boolean): Bitmap {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 16f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        if (left) {
            c.drawPath(Path().apply { moveTo(80f, 116f); lineTo(80f, 56f); lineTo(44f, 56f) }, p)
            p.style = Paint.Style.FILL
            c.drawPath(Path().apply { moveTo(14f, 56f); lineTo(46f, 30f); lineTo(46f, 82f); close() }, p)
        } else {
            c.drawPath(Path().apply { moveTo(40f, 116f); lineTo(40f, 50f); arcTo(40f, 22f, 92f, 74f, 180f, 180f, false); lineTo(92f, 76f) }, p)
            p.style = Paint.Style.FILL
            c.drawPath(Path().apply { moveTo(92f, 108f); lineTo(68f, 76f); lineTo(116f, 76f); close() }, p)
        }
        return b
    }

    // --- helpers ---

    private fun playing(on: Boolean, pos: Long) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
        .setState(if (on) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, 1f, SystemClock.elapsedRealtime())
        .build()

    private fun launch(body: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                body()
            } catch (t: Throwable) {
                shot("fail-${System.currentTimeMillis() % 100000}")
                note("FAILED: $t")
                throw t
            }
        }
    }

    private fun search(text: String) {
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput(text)
        field.performImeAction()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("place-result")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("Nothing found", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText("No internet", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        sleep(500)
    }

    /** A search result row. */
    private fun resultMatcher(contains: String): SemanticsMatcher =
        hasTestTag("place-result") and hasText(contains, substring = true, ignoreCase = true)

    private fun resultRows(): List<String> = compose.onAllNodes(hasTestTag("place-result")).fetchSemanticsNodes()
        .map { n -> n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" — ") { it.text }.orEmpty() }

    private fun waitForRoute(): com.rodeo.hud.nav.Route {
        compose.waitUntil(60_000) { g.nav.state.value.let { it.route != null || it.error != null } }
        val s = g.nav.state.value
        note("route state: error=${s.error}")
        assertNotNull("no route: ${s.error}", s.route)
        return s.route!!
    }

    private fun waitForText(text: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun fix(p: LatLng, speed: Float, bearing: Float?) {
        val l = Location("fused").apply {
            latitude = p.lat
            longitude = p.lng
            accuracy = 5f
            altitude = 900.0
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            this.speed = speed
            if (bearing != null) this.bearing = bearing
        }
        if (mock) runCatching { Tasks.await(fused.setMockLocation(l), 5, TimeUnit.SECONDS) }.onFailure { inst.runOnMainSync { g.location.inject(l) } }
        else inst.runOnMainSync { g.location.inject(l) }
    }

    private fun shot(name: String) {
        runCatching {
            sleep(300)
            val bmp = inst.uiAutomation.takeScreenshot() ?: return
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { note("screenshot $name failed: $it") }
    }

    private fun note(s: String) {
        Log.i(TAG, s)
        File(out, "report.txt").appendText(s + "\n")
    }

    private fun tileCacheKb(): Long = File(ctx.cacheDir, "osm").walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024

    /**
     * Waits in real time while letting the UI keep drawing: the test rule owns Compose's frame
     * clock, so it is advanced alongside.
     */
    private fun sleep(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(100L, left)
            SystemClock.sleep(step)
            compose.mainClock.advanceTimeBy(step)
            left -= step
        }
    }

    private fun resample(path: List<LatLng>, step: Double): List<LatLng> {
        val out = ArrayList<LatLng>()
        out += path.first()
        var carry = 0.0
        for (i in 1 until path.size) {
            val a = path[i - 1]
            val b = path[i]
            val d = Navigator.metres(a, b)
            if (d < 1e-6) continue
            var s = step - carry
            while (s <= d) {
                val f = s / d
                out += LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
                s += step
            }
            carry = d - (s - step)
        }
        out += path.last()
        return out
    }

    private fun bearing(a: LatLng, b: LatLng): Float {
        val dx = (b.lng - a.lng) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return ((Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0).toFloat()
    }

    private fun move(p: LatLng, bearingDeg: Double, m: Double): LatLng {
        val r = Math.toRadians(bearingDeg)
        return LatLng(
            p.lat + m * kotlin.math.cos(r) / 111_320.0,
            p.lng + m * kotlin.math.sin(r) / (111_320.0 * cos(Math.toRadians(p.lat))),
        )
    }

    private companion object {
        const val TAG = "RodeoE2E"
        const val NAV_ID = 4242
    }
}
