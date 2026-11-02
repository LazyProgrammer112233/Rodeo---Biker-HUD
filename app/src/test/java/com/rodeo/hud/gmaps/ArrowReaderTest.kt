package com.rodeo.hud.gmaps

import com.rodeo.hud.nav.Maneuver
import com.rodeo.hud.nav.Maneuver.*
import com.rodeo.hud.nav.gmaps.ArrowReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

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
                val img = ImageIO.read(f)
                val px = IntArray(img.width * img.height)
                img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
                val r = ArrowReader.read(img.width, img.height, px)
                Case("$fam/${f.name}", exp, r.maneuver, r.confident)
            }
        }
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
