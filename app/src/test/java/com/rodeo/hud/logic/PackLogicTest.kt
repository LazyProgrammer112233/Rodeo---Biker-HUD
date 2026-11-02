package com.rodeo.hud.logic

import com.rodeo.hud.pack.PackAlerts
import com.rodeo.hud.pack.PackLogic
import com.rodeo.hud.pack.PackMember
import com.rodeo.hud.pack.SpeechGate
import com.rodeo.hud.pack.WirePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PackLogicTest {

    // Bengaluru, MG Road; offsets in metres north.
    private val lat0 = 12.9750
    private val lng0 = 77.6060
    private fun north(m: Double) = lat0 + m / 111_195.0
    private fun east(m: Double) = lng0 + m / (111_195.0 * Math.cos(Math.toRadians(lat0)))

    private fun rider(id: String, lat: Double = lat0, lng: Double = lng0, ts: Long = 0, still: Long = 0, sos: Boolean = false, speed: Float = 40f, online: Boolean = true) =
        PackMember(id, id.replaceFirstChar { it.uppercase() }, 0, lat, lng, speed, ts, still, sos = sos, online = online)

    @Test fun codes() {
        repeat(200) {
            val c = PackLogic.newCode()
            assertTrue(c, PackLogic.isValidCode(c))
            assertFalse(c.any { it in "01OI" })
        }
        assertEquals("ABC234", PackLogic.codeFrom("rodeo://pack/ABC234"))
        assertEquals("ABC234", PackLogic.codeFrom("  abc234 "))
        assertEquals("ABC234", PackLogic.codeFrom("Join my Rodeo pack: ABC234. See you!"))
        assertEquals("ABC234", PackLogic.codeFrom("ABC-234"))
        assertNull(PackLogic.codeFrom("hello"))
        assertNull(PackLogic.codeFrom(null))
        assertNull(PackLogic.codeFrom("rodeo://pack/AB"))
        assertEquals("rodeo://pack/XYZ789", PackLogic.joinLink("XYZ789"))
    }

    @Test fun distanceWords() {
        assertEquals("here", PackLogic.distanceText(4.0))
        assertEquals("350 m", PackLogic.distanceText(347.0))
        assertEquals("1.0 km", PackLogic.distanceText(996.0))
        assertEquals("1.2 km", PackLogic.distanceText(1_240.0))
        assertEquals("12 km", PackLogic.distanceText(12_400.0))
        assertEquals("350 metres", PackLogic.distanceSpoken(347.0))
        assertEquals("2 kilometres", PackLogic.distanceSpoken(2_010.0))
        assertEquals("2.4 kilometres", PackLogic.distanceSpoken(2_410.0))
    }

    @Test fun directions() {
        assertEquals(90.0, PackLogic.relativeBearing(0.0, 90.0), 1e-9)
        assertEquals(-90.0, PackLogic.relativeBearing(0.0, 270.0), 1e-9)
        assertEquals(-20.0, PackLogic.relativeBearing(350.0, 330.0), 1e-9)
        assertEquals(20.0, PackLogic.relativeBearing(350.0, 10.0), 1e-9)
        assertEquals(180.0, abs(PackLogic.relativeBearing(90.0, 270.0)), 1e-9)
        assertEquals(PackLogic.Side.AHEAD, PackLogic.side(10.0))
        assertEquals(PackLogic.Side.BEHIND, PackLogic.side(-170.0))
        assertEquals(PackLogic.Side.RIGHT, PackLogic.side(90.0))
        assertEquals(PackLogic.Side.LEFT, PackLogic.side(-90.0))
        assertEquals(PackLogic.Side.AWAY, PackLogic.side(null))
        assertEquals("1.2 km behind", PackLogic.describe(1_200.0, 175.0))
        assertEquals("350 m ahead", PackLogic.describe(350.0, 3.0))
        assertEquals("800 m left", PackLogic.describe(800.0, -80.0))
        assertEquals("800 m away", PackLogic.describe(800.0, null))
        // Heading north, a rider 1 km due south is straight behind.
        val b = PackLogic.bearing(lat0, lng0, north(-1_000.0), lng0)
        assertEquals(PackLogic.Side.BEHIND, PackLogic.side(PackLogic.relativeBearing(0.0, b)))
        assertEquals(1_000.0, PackLogic.distanceM(lat0, lng0, north(-1_000.0), lng0), 2.0)
    }

    @Test fun initials() {
        assertEquals("RV", PackLogic.initials("Rahul Verma"))
        assertEquals("KA", PackLogic.initials("Kabir"))
        assertEquals("?", PackLogic.initials("   "))
        assertEquals("AS", PackLogic.initials("  ananya   s "))
    }

    @Test fun coloursAreStableAndDistinct() {
        val ids = listOf("u1", "u2", "u3", "u4", "u5", "u6")
        val a = PackLogic.assignColors(ids)
        val b = PackLogic.assignColors(ids.reversed())
        assertEquals(a, b)
        assertEquals(ids.size, a.values.toSet().size)
        // More riders than colours: still assigned, within the palette.
        val many = PackLogic.assignColors((1..20).map { "r$it" })
        assertEquals(20, many.size)
        assertTrue(many.values.all { it in PackLogic.PALETTE.indices })
    }

    @Test fun chipsNeverOverlap() {
        val riders = listOf("a" to 170.0, "b" to 175.0, "c" to 178.0, "d" to -10.0, "e" to 90.0, "f" to null, "g" to 179.0)
        val slots = PackLogic.layoutChips(riders, top = 100f, bottom = 400f, chipH = 60f, gap = 6f)
        assertEquals(riders.size, slots.size)
        for (side in listOf(true, false)) {
            val ys = slots.filter { it.right == side }.map { it.y }.sorted()
            for (i in 1 until ys.size) assertTrue("overlap $ys", ys[i] - ys[i - 1] >= 66f - 0.01f)
            ys.forEach { assertTrue("out of band $it", it >= 100f - 0.01f && it <= 340f + 0.01f || ys.size * 66 > 300) }
        }
        assertFalse(slots.first { it.id == "d" }.right)
        assertTrue(slots.first { it.id == "e" }.right)
        // Straight ahead sits at the top of the band.
        assertEquals(100f + 10f / 180f * 240f, slots.first { it.id == "d" }.y, 0.5f)
    }

    @Test fun miniMap() {
        val (x, y) = PackLogic.miniMapPoint(250.0, 90.0, 500.0)
        assertEquals(0.5f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
        val (x2, y2) = PackLogic.miniMapPoint(5_000.0, 0.0, 500.0)
        assertEquals(0f, x2, 1e-4f)
        assertEquals(-1f, y2, 1e-4f)
    }

    @Test fun wireRoundTrip() {
        val p = WirePosition("uid|1", "Rahul|V", 12.975123, 77.606456, 42.5f, 270f, 1_700_000_000_000, 0, sos = true, lead = false, hops = 1, inVoice = true)
        val back = WirePosition.decode(p.encode())
        assertNotNull(back)
        back!!
        assertEquals("uid/1", back.id)
        assertEquals("Rahul/V", back.name)
        assertEquals(12.975123, back.lat, 1e-6)
        assertEquals(77.606456, back.lng, 1e-6)
        assertTrue(back.sos)
        assertTrue(back.inVoice)
        assertEquals(1, back.hops)
        assertNull(WirePosition.decode("garbage"))
        assertNull(WirePosition.decode("P1|id|n|999|0|0|0|0|0|0|0|0"))
    }

    @Test fun opusTuning() {
        val sdp = listOf(
            "v=0", "m=audio 9 UDP/TLS/RTP/SAVPF 111 63 9", "a=rtpmap:111 opus/48000/2", "a=fmtp:111 minptime=10;useinbandfec=1", "a=rtpmap:9 G722/8000",
        ).joinToString("\r\n")
        val t = PackLogic.tuneOpus(sdp)
        val line = t.split("\r\n").first { it.startsWith("a=fmtp:111") }
        assertTrue(line, "usedtx=1" in line && "maxaveragebitrate=20000" in line && "useinbandfec=1" in line && "stereo=0" in line)
        assertEquals(1, Regex("useinbandfec").findAll(line).count())
        // No fmtp line yet: one is added after the rtpmap.
        val t2 = PackLogic.tuneOpus("m=audio 9 x 111\r\na=rtpmap:111 opus/48000/2\r\na=other")
        assertTrue(t2.contains("a=rtpmap:111 opus/48000/2\r\na=fmtp:111 "))
        assertEquals("no opus", PackLogic.tuneOpus("no opus"))
    }

    @Test fun alertsFallBehindAndRegroup() {
        val al = PackAlerts(gapM = 2_000.0, regroupM = 500.0)
        val me = rider("me")
        var now = 1_000_000L
        // Joining: riders already in the pack are not announced.
        var out = al.update(now, me, listOf(rider("rahul", north(-300.0), ts = now)), 0.0)
        assertTrue(out.isEmpty())
        // Rahul drops 2.3 km back while we ride north.
        now += 60_000
        out = al.update(now, me, listOf(rider("rahul", north(-2_300.0), ts = now)), 0.0)
        assertEquals(1, out.size)
        assertEquals(PackAlerts.Kind.FELL_BEHIND, out[0].kind)
        assertEquals("Rahul fell 2.3 km behind", out[0].text)
        assertEquals("Rahul fell 2.3 kilometres behind", out[0].speech)
        // Still behind: no repeat. Inside the hysteresis band (1.5 km): no "caught up" yet.
        out = al.update(now + 1_000, me, listOf(rider("rahul", north(-2_500.0), ts = now)), 0.0)
        assertTrue(out.isEmpty())
        out = al.update(now + 2_000, me, listOf(rider("rahul", north(-1_500.0), ts = now)), 0.0)
        assertTrue(out.isEmpty())
        out = al.update(now + 3_000, me, listOf(rider("rahul", north(-900.0), ts = now)), 0.0)
        assertEquals(listOf(PackAlerts.Kind.CAUGHT_UP), out.map { it.kind })
        out = al.update(now + 4_000, me, listOf(rider("rahul", north(-200.0), ts = now)), 0.0)
        assertEquals(listOf(PackAlerts.Kind.REGROUPED), out.map { it.kind })
        assertTrue(out[0].speech.startsWith("Pack regrouped"))
        // Once regrouped, staying together says nothing more.
        assertTrue(al.update(now + 5_000, me, listOf(rider("rahul", north(-100.0), ts = now)), 0.0).isEmpty())
    }

    @Test fun alertsAhead() {
        val al = PackAlerts(gapM = 1_000.0)
        val me = rider("me")
        al.update(0, me, listOf(rider("kabir", north(100.0))), 0.0)
        val out = al.update(1, me, listOf(rider("kabir", north(1_400.0))), 0.0)
        assertEquals(PackAlerts.Kind.PULLED_AHEAD, out.single().kind)
        assertEquals("Kabir is 1.4 km ahead", out.single().text)
    }

    @Test fun alertsParkedSaysAway() {
        val al = PackAlerts(gapM = 1_000.0)
        val me = rider("me")
        al.update(0, me, listOf(rider("kabir", north(100.0))), null)
        val out = al.update(1, me, listOf(rider("kabir", lng = east(1_500.0))), null)
        assertEquals("Kabir is 1.5 km away", out.single().text)
    }

    @Test fun alertsStoppedLostSosJoinLeave() {
        val al = PackAlerts()
        val me = rider("me")
        var now = 10_000_000L
        al.update(now, me, listOf(rider("vikram", north(-500.0), ts = now)), 0.0)
        // Vikram parks: nothing for the first two minutes, then one "stopped".
        now += 1_000
        val parked = now
        assertTrue(al.update(now, me, listOf(rider("vikram", north(-500.0), ts = now, still = parked, speed = 0f)), 0.0).isEmpty())
        now += 130_000
        var out = al.update(now, me, listOf(rider("vikram", north(-500.0), ts = now, still = parked, speed = 0f)), 0.0)
        assertEquals(listOf(PackAlerts.Kind.STOPPED), out.map { it.kind })
        assertEquals("Vikram has stopped · 500 m behind", out[0].text)
        assertTrue(PackAlerts.Kind.STOPPED to "vikram" in al.ongoing)
        out = al.update(now + 1_000, me, listOf(rider("vikram", north(-500.0), ts = now, still = parked, speed = 0f)), 0.0)
        assertTrue(out.isEmpty())
        // Moving again.
        now += 5_000
        out = al.update(now, me, listOf(rider("vikram", north(-450.0), ts = now, speed = 20f)), 0.0)
        assertEquals(listOf(PackAlerts.Kind.MOVING_AGAIN), out.map { it.kind })
        // Contact lost after 90 s of silence, back when a position arrives.
        val lastTs = now
        out = al.update(now + 95_000, me, listOf(rider("vikram", north(-450.0), ts = lastTs)), 0.0)
        assertEquals(listOf(PackAlerts.Kind.LOST), out.map { it.kind })
        assertTrue(out[0].text.startsWith("Lost contact with Vikram"))
        now += 100_000
        out = al.update(now, me, listOf(rider("vikram", north(-450.0), ts = now)), 0.0)
        assertEquals(listOf(PackAlerts.Kind.BACK), out.map { it.kind })
        // SOS is danger.
        out = al.update(now + 1, me, listOf(rider("vikram", north(-450.0), ts = now, sos = true)), 0.0)
        assertEquals(PackAlerts.Kind.SOS, out.single().kind)
        assertEquals(2, out.single().severity)
        assertTrue(out.single().speech.contains("S O S"))
        out = al.update(now + 2, me, listOf(rider("vikram", north(-450.0), ts = now)), 0.0)
        assertEquals(PackAlerts.Kind.SOS_OVER, out.single().kind)
        // Someone joins, someone leaves.
        out = al.update(now + 3, me, listOf(rider("vikram", ts = now), rider("ananya", ts = now)), 0.0)
        assertEquals("Ananya joined the pack", out.single().text)
        out = al.update(now + 4, me, listOf(rider("ananya", ts = now)), 0.0)
        assertEquals("Vikram left the pack", out.single().text)
    }

    @Test fun offlineRiderCountsAsLostSooner() {
        val al = PackAlerts()
        val me = rider("me")
        al.update(0, me, listOf(rider("kabir", ts = 0)), 0.0)
        assertTrue(al.update(10_000, me, listOf(rider("kabir", ts = 0, online = false)), 0.0).isEmpty())
        assertEquals(PackAlerts.Kind.LOST, al.update(25_000, me, listOf(rider("kabir", ts = 0, online = false)), 0.0).single().kind)
    }

    @Test fun speechGate() {
        val g = SpeechGate(minGapMs = 6_000)
        fun a(id: String, sev: Int, text: String = id) = PackAlerts.Alert(PackAlerts.Kind.JOINED, id, text, text, sev, false)
        g.offer(a("a", 0))
        assertEquals("a", g.next(10_000)?.text)
        g.offer(a("b", 1))
        assertNull(g.next(12_000))
        // Danger never waits and jumps the queue.
        g.offer(a("c", 2))
        assertEquals("c", g.next(12_500)?.text)
        assertNull(g.next(13_000))
        assertEquals("b", g.next(19_000)?.text)
        // A newer alert about the same rider replaces the old one.
        g.offer(a("d", 0, "old"))
        g.offer(a("d", 0, "new"))
        assertEquals("new", g.next(30_000)?.text)
        assertNull(g.next(40_000))
    }
}
