package com.rodeo.hud.gmaps

import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.Maneuver.*
import com.rodeo.hud.nav.gmaps.ArrowReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.Inflater

/**
 * Reads several hundred turn-arrow pictures (two open icon sets, each at three sizes and styles)
 * the way Google Maps' notification arrow is read on the phone. Rodeo must never draw a wrong
 * arrow: a picture it cannot read with confidence falls back to Google's own picture.
 */
class ArrowReaderTest {
    private data class Case(val file: String, val expected: Set<Maneuver>, val got: Maneuver, val confident: Boolean)

    private val cases: List<Case> by lazy {
        val root = File(javaClass.classLoader!!.getResource("arrows/README.md")!!.toURI()).parentFile
        listOf("material", "mapbox").flatMap { fam ->
            File(root, fam).listFiles()!!.sortedBy { it.name }.mapNotNull { f ->
                val exp = expected(fam, f.name.substringBefore("@")) ?: return@mapNotNull null
                val (w, h, px) = readPng(f)
                val r = ArrowReader.read(w, h, px)
                Case("$fam/${f.name}", exp, r.maneuver, r.confident)
            }
        }
    }

    /**
     * Minimal PNG reader for the fixtures (8-bit RGB / RGBA, not interlaced): javax.imageio is
     * not on the Android unit-test classpath.
     */
    private fun readPng(f: File): Triple<Int, Int, IntArray> {
        val inp = DataInputStream(f.inputStream().buffered())
        inp.skipBytes(8)
        var w = 0; var h = 0; var type = 0
        val idat = ByteArrayOutputStream()
        while (true) {
            val len = inp.readInt()
            val tag = String(ByteArray(4).also { inp.readFully(it) }, Charsets.US_ASCII)
            val data = ByteArray(len).also { inp.readFully(it) }
            inp.readInt()
            when (tag) {
                "IHDR" -> {
                    w = java.nio.ByteBuffer.wrap(data, 0, 4).int; h = java.nio.ByteBuffer.wrap(data, 4, 4).int
                    check(data[8].toInt() == 8 && data[12].toInt() == 0) { "${f.name}: unsupported PNG" }
                    type = data[9].toInt()
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val bpp = when (type) { 6 -> 4; 2 -> 3; else -> error("${f.name}: colour type $type") }
        val stride = w * bpp
        val raw = ByteArray(h * (stride + 1))
        Inflater().apply { setInput(idat.toByteArray()); var o = 0; while (o < raw.size && !finished()) o += inflate(raw, o, raw.size - o); end() }
        val cur = ByteArray(stride); val prev = ByteArray(stride)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val filter = raw[y * (stride + 1)].toInt()
            for (i in 0 until stride) {
                val x = raw[y * (stride + 1) + 1 + i].toInt() and 0xFF
                val a = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> { val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                    else -> error("filter $filter")
                }
                cur[i] = v.toByte()
            }
            for (xx in 0 until w) {
                val o = xx * bpp
                val r = cur[o].toInt() and 0xFF; val g = cur[o + 1].toInt() and 0xFF; val bl = cur[o + 2].toInt() and 0xFF
                val al = if (bpp == 4) cur[o + 3].toInt() and 0xFF else 255
                px[y * w + xx] = (al shl 24) or (r shl 16) or (g shl 8) or bl
            }
            cur.copyInto(prev)
        }
        return Triple(w, h, px)
    }

    @Test fun enoughPictures() = assertTrue("only ${cases.size} pictures", cases.size > 300)

    @Test fun neverConfidentlyWrong() {
        val wrong = cases.filter { it.confident && it.got !in it.expected && it.got != UNKNOWN }
        assertEquals(wrong.joinToString("\n") { "${it.file}: read ${it.got}, expected ${it.expected}" }, 0, wrong.size)
    }

    @Test fun neverWrongEvenUnsure() {
        // An unsure reading is not drawn, but it should still not name the wrong turn.
        val wrong = cases.filter { it.got !in it.expected && it.got != UNKNOWN }
        assertEquals(wrong.joinToString("\n") { "${it.file}: read ${it.got}, expected ${it.expected}" }, 0, wrong.size)
    }

    @Test fun readsAlmostAll() {
        val right = cases.count { it.got in it.expected }
        val share = right.toDouble() / cases.size
        assertTrue("read ${"%.1f".format(share * 100)}% right", share >= 0.95)
    }

    @Test fun blankAndNoiseAreNotTurns() {
        val blank = ArrowReader.read(64, 64, IntArray(64 * 64))
        assertEquals(UNKNOWN, blank.maneuver)
        val rnd = java.util.Random(7)
        val noise = IntArray(64 * 64) { if (rnd.nextInt(5) == 0) -0x1 else 0 }
        val r = ArrowReader.read(64, 64, noise)
        assertTrue("noise read as ${r.maneuver}", r.maneuver == UNKNOWN || !r.confident)
    }

    private fun expected(fam: String, name: String): Set<Maneuver>? {
        val n = name.substringBefore("__")
        if (fam == "material") return when (n) {
            "arrow_upward", "straight" -> setOf(STRAIGHT)
            "fork_left" -> setOf(KEEP_LEFT, SLIGHT_LEFT, LEFT, STRAIGHT, UNKNOWN)
            "fork_right" -> setOf(KEEP_RIGHT, SLIGHT_RIGHT, RIGHT, STRAIGHT, UNKNOWN)
            // Material draws these as a straight arrow with the side road joining: any of these reads fine.
            "merge" -> setOf(STRAIGHT, MERGE, UNKNOWN, KEEP_LEFT, KEEP_RIGHT)
            "ramp_left" -> setOf(SLIGHT_LEFT, KEEP_LEFT, STRAIGHT)
            "ramp_right" -> setOf(SLIGHT_RIGHT, KEEP_RIGHT, STRAIGHT)
            "roundabout_left" -> setOf(ROUNDABOUT_LEFT)
            "roundabout_right" -> setOf(ROUNDABOUT_RIGHT)
            "turn_left" -> setOf(LEFT)
            "turn_right" -> setOf(RIGHT)
            "turn_sharp_left" -> setOf(SHARP_LEFT, KEEP_LEFT, LEFT)
            "turn_sharp_right" -> setOf(SHARP_RIGHT, KEEP_RIGHT, RIGHT)
            "turn_slight_left" -> setOf(SLIGHT_LEFT)
            "turn_slight_right" -> setOf(SLIGHT_RIGHT)
            "u_turn_left" -> setOf(UTURN_LEFT)
            "u_turn_right" -> setOf(UTURN_RIGHT)
            else -> null
        }
        return when {
            n in setOf("rotary", "roundabout", "updown", "close", "flag", "fork", "arrive", "arrive_straight", "depart") -> null
            n.startsWith("roundabout_") || n.startsWith("rotary_") -> when (n.substringAfter("_")) {
                "left", "sharp_left", "slight_left" -> setOf(ROUNDABOUT_LEFT)
                // Mapbox draws the ring for these only as a hairline outline, so the solid part is a plain turn.
                "right" -> setOf(ROUNDABOUT_RIGHT, RIGHT)
                "sharp_right" -> setOf(ROUNDABOUT_RIGHT, SHARP_RIGHT, RIGHT)
                "slight_right" -> setOf(ROUNDABOUT_RIGHT, SLIGHT_RIGHT, RIGHT)
                "straight" -> setOf(ROUNDABOUT_STRAIGHT, STRAIGHT)
                else -> null
            }
            n.endsWith("uturn") -> setOf(UTURN_LEFT)
            n.startsWith("merge") -> when {
                n.endsWith("slight_left") -> setOf(SLIGHT_LEFT, KEEP_LEFT, MERGE, STRAIGHT)
                n.endsWith("slight_right") -> setOf(SLIGHT_RIGHT, KEEP_RIGHT, MERGE, STRAIGHT)
                n.endsWith("left") -> setOf(KEEP_LEFT, SLIGHT_LEFT, LEFT, MERGE, STRAIGHT)
                n.endsWith("right") -> setOf(KEEP_RIGHT, SLIGHT_RIGHT, RIGHT, MERGE, STRAIGHT)
                else -> setOf(STRAIGHT)
            }
            n.endsWith("sharp_left") -> setOf(SHARP_LEFT)
            n.endsWith("sharp_right") || n == "notificaiton_sharp_right" -> setOf(SHARP_RIGHT)
            n.endsWith("slight_left") -> setOf(SLIGHT_LEFT, KEEP_LEFT)
            n.endsWith("slight_right") -> setOf(SLIGHT_RIGHT, KEEP_RIGHT)
            n.startsWith("fork_") && n.endsWith("left") -> setOf(LEFT, SLIGHT_LEFT, KEEP_LEFT)
            n.startsWith("fork_") && n.endsWith("right") -> setOf(RIGHT, SLIGHT_RIGHT, KEEP_RIGHT)
            n.startsWith("off_ramp") && n.endsWith("left") -> setOf(LEFT, SLIGHT_LEFT, KEEP_LEFT)
            n.startsWith("off_ramp") && n.endsWith("right") -> setOf(RIGHT, SLIGHT_RIGHT, KEEP_RIGHT)
            n.endsWith("_left") -> setOf(LEFT)
            n.endsWith("_right") -> setOf(RIGHT)
            n.endsWith("straight") || n in setOf("continue", "invalid") -> setOf(STRAIGHT)
            else -> null
        }
    }
}
