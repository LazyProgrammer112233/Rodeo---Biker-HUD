package com.rodeo.hud.pack

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pack mode's plain logic, with no Android in it so it is unit tested on the JVM: pack codes,
 * distances and directions in words, rider colours, where the rider chips sit on the HUD, the
 * short-range wire format and the voice codec settings.
 */
object PackLogic {
    /** No 0/O or 1/I, so a code read out over the intercom or typed with gloves can't be confused. */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 6
    const val LINK_SCHEME = "rodeo"
    const val LINK_HOST = "pack"

    fun newCode(random: (Int) -> Int = { kotlin.random.Random.nextInt(it) }): String =
        (1..CODE_LENGTH).map { ALPHABET[random(ALPHABET.length)] }.joinToString("")

    fun isValidCode(code: String) = code.length == CODE_LENGTH && code.all { it in ALPHABET }

    /** The text inside the pack's QR code; phone camera apps open Rodeo straight into the pack. */
    fun joinLink(code: String) = "$LINK_SCHEME://$LINK_HOST/$code"

    /** A pack code from a scanned QR, a pasted link or plain text, or null. */
    fun codeFrom(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = text.trim()
        val tail = t.substringAfterLast('/').substringBefore('?').trim()
        val candidate = cleanCode(tail)
        if (isValidCode(candidate)) return candidate
        // "Join my Rodeo pack: ABC123" and the like.
        return t.split(Regex("[\\s:,.!/?]+"))
            .filter { Regex("^[A-Za-z0-9]{3}-?[A-Za-z0-9]{3}$").matches(it) }
            .map { cleanCode(it) }
            .firstOrNull { isValidCode(it) }
    }

    /** Upper case, and only the letters a code can hold. */
    fun cleanCode(raw: String): String = raw.uppercase().filter { it in ALPHABET }

    // --- Distances and directions in words ---

    /** "350 m", "1.2 km", "12 km". */
    fun distanceText(m: Double): String = when {
        m < 10 -> "here"
        m < 995 -> "${(m / 10).roundToInt() * 10} m"
        m < 9_950 -> "%.1f km".format(java.util.Locale.US, m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    /** The same for speech: "350 metres", "1.2 kilometres". */
    fun distanceSpoken(m: Double): String = when {
        m < 995 -> "${maxOf(10, (m / 10).roundToInt() * 10)} metres"
        m < 9_950 -> "%.1f kilometres".format(java.util.Locale.US, m / 1000).replace(".0 ", " ")
        else -> "${(m / 1000).roundToInt()} kilometres"
    }

    /** Angle from the rider's heading to the other rider, -180..180, positive = to the right. */
    fun relativeBearing(headingDeg: Double, bearingDeg: Double): Double {
        var d = (bearingDeg - headingDeg) % 360.0
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    enum class Side { AHEAD, BEHIND, LEFT, RIGHT, AWAY }

    /** Ahead / behind within 50° of the heading, otherwise left / right. AWAY when the heading is unknown. */
    fun side(rel: Double?): Side = when {
        rel == null -> Side.AWAY
        abs(rel) <= 50 -> Side.AHEAD
        abs(rel) >= 130 -> Side.BEHIND
        rel > 0 -> Side.RIGHT
        else -> Side.LEFT
    }

    fun sideWord(s: Side) = when (s) {
        Side.AHEAD -> "ahead"
        Side.BEHIND -> "behind"
        Side.LEFT -> "to your left"
        Side.RIGHT -> "to your right"
        Side.AWAY -> "away"
    }

    /** Short form for a chip: "1.2 km behind", "350 m ahead", "800 m left", "800 m away". */
    fun describe(distanceM: Double, rel: Double?): String {
        if (distanceM < 10) return "with you"
        val w = when (side(rel)) {
            Side.LEFT -> "left"
            Side.RIGHT -> "right"
            else -> sideWord(side(rel))
        }
        return "${distanceText(distanceM)} $w"
    }

    /** Up to two letters for a rider's chip: "Rahul Verma" -> "RV", "Kabir" -> "KA". */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> (words[0].take(1) + words[1].take(1)).uppercase()
        }
    }

    // --- Rider colours ---

    /** Eight colours that stay apart from each other on a day or night map, and in sunlight. */
    val PALETTE: List<Long> = listOf(
        0xFF2D9CFF, // blue
        0xFFFFB020, // amber
        0xFF3DDC84, // green
        0xFFFF4FA3, // pink
        0xFF9B6BFF, // violet
        0xFF19D3C5, // teal
        0xFFFF7A3D, // orange
        0xFFC6E03A, // lime
    )

    /**
     * A colour for every rider, the same on every phone in the pack: each rider prefers the
     * colour their id hashes to; a clash moves the later id (in sorted order) to the next free one.
     */
    fun assignColors(ids: Collection<String>): Map<String, Int> {
        val taken = BooleanArray(PALETTE.size)
        val out = HashMap<String, Int>()
        for (id in ids.distinct().sorted()) {
            var i = Math.floorMod(stableHash(id), PALETTE.size)
            if (taken.all { it }) taken.fill(false)
            while (taken[i]) i = (i + 1) % PALETTE.size
            taken[i] = true
            out[id] = i
        }
        return out
    }

    /** String.hashCode is the same on every JVM and Android, but spell it out so it never changes. */
    fun stableHash(s: String): Int {
        var h = 0
        for (ch in s) h = 31 * h + ch.code
        return h
    }

    // --- Chips on the HUD edges ---

    data class ChipSlot(val id: String, val right: Boolean, val y: Float)

    /**
     * Where each rider's chip goes on the map's left or right edge band. A rider to the right sits on
     * the right edge, to the left on the left edge; straight ahead at the top of the band and
     * straight behind at the bottom. Chips on one edge never overlap: they are pushed apart and, if
     * the band is full, packed from the bottom up. [top] and [bottom] bound the band (pixels).
     */
    fun layoutChips(riders: List<Pair<String, Double?>>, top: Float, bottom: Float, chipH: Float, gap: Float): List<ChipSlot> {
        val span = (bottom - top - chipH).coerceAtLeast(0f)
        val raw = riders.map { (id, rel) ->
            val r = rel ?: 180.0
            // Unknown heading: list down the right edge, in the order given.
            val right = rel == null || r >= 0
            val t = (abs(r) / 180.0).toFloat()
            Triple(id, right, top + t * span)
        }
        val out = ArrayList<ChipSlot>()
        for (side in listOf(false, true)) {
            val list = raw.filter { it.second == side }.sortedBy { it.third }.map { it.first to it.third }.toMutableList()
            val ys = list.map { it.second }.toMutableList()
            for (i in 1 until ys.size) ys[i] = maxOf(ys[i], ys[i - 1] + chipH + gap)
            // Off the bottom: pull everything back up, keeping the gaps.
            val last = ys.lastIndex
            if (last >= 0 && ys[last] > top + span) {
                ys[last] = top + span
                for (i in last - 1 downTo 0) ys[i] = minOf(ys[i], ys[i + 1] - chipH - gap)
            }
            list.forEachIndexed { i, p -> out += ChipSlot(p.first, side, ys[i]) }
        }
        return out
    }

    /** Position of a rider on a heading-up mini-map, in units of its radius (x right, y down). */
    fun miniMapPoint(distanceM: Double, rel: Double, rangeM: Double): Pair<Float, Float> {
        val r = (minOf(distanceM, rangeM) / rangeM).toFloat()
        val a = Math.toRadians(rel)
        return Pair((r * sin(a)).toFloat(), (-r * cos(a)).toFloat())
    }

    /** Distance in metres between two points (haversine), duplicated from Geo so this file stays plain JVM. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    // --- Voice codec ---

    /**
     * Opus tuned for a helmet intercom over mobile data: mono, about 20 kbit/s, in-band error
     * correction for patchy networks, and no packets at all while nobody speaks (DTX).
     */
    fun tuneOpus(sdp: String, maxBitrate: Int = 20_000): String {
        val lines = sdp.split("\r\n")
        val pt = lines.firstNotNullOfOrNull { l ->
            Regex("^a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)
        } ?: return sdp
        val want = linkedMapOf("minptime" to "10", "useinbandfec" to "1", "usedtx" to "1", "stereo" to "0", "maxaveragebitrate" to "$maxBitrate")
        var found = false
        val out = lines.map { l ->
            if (l.startsWith("a=fmtp:$pt ")) {
                found = true
                val params = linkedMapOf<String, String>()
                l.removePrefix("a=fmtp:$pt ").split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { kv ->
                    val k = kv.substringBefore('=')
                    params[k] = kv.substringAfter('=', "")
                }
                params.putAll(want)
                "a=fmtp:$pt " + params.entries.joinToString(";") { "${it.key}=${it.value}" }
            } else l
        }.toMutableList()
        if (!found) {
            val i = out.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
            if (i >= 0) out.add(i + 1, "a=fmtp:$pt " + want.entries.joinToString(";") { "${it.key}=${it.value}" })
        }
        return out.joinToString("\r\n")
    }
}

/**
 * One rider's position as it travels between phones over the short-range link: a single line of
 * text, fields split by '|'. Kept tiny because Bluetooth payloads are small and frequent.
 */
data class WirePosition(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val speedKmh: Float,
    val bearing: Float,
    /** The sender's clock when the fix was taken. */
    val ts: Long,
    val stillSince: Long,
    val sos: Boolean,
    val lead: Boolean,
    /** How many phones it has passed through; relayed at most twice. */
    val hops: Int,
    val inVoice: Boolean = false,
) {
    fun encode(): String = listOf(
        PREFIX, id.clean(40), name.clean(24),
        "%.6f".format(java.util.Locale.US, lat), "%.6f".format(java.util.Locale.US, lng),
        "%.1f".format(java.util.Locale.US, speedKmh), "%.0f".format(java.util.Locale.US, bearing),
        ts.toString(), stillSince.toString(), if (sos) "1" else "0", if (lead) "1" else "0", hops.toString(), if (inVoice) "1" else "0",
    ).joinToString("|")

    companion object {
        const val PREFIX = "P1"
        const val MAX_HOPS = 2

        private fun String.clean(max: Int) = replace('|', '/').replace('\n', ' ').take(max)

        fun decode(s: String): WirePosition? = runCatching {
            val f = s.split('|')
            if (f.size < 12 || f[0] != PREFIX) return null
            val lat = f[3].toDouble()
            val lng = f[4].toDouble()
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || f[1].isBlank()) return null
            WirePosition(
                id = f[1], name = f[2].ifBlank { "Rider" }, lat = lat, lng = lng,
                speedKmh = f[5].toFloat(), bearing = f[6].toFloat(), ts = f[7].toLong(), stillSince = f[8].toLong(),
                sos = f[9] == "1", lead = f[10] == "1", hops = f[11].toInt(), inVoice = f.getOrNull(12) == "1",
            )
        }.getOrNull()
    }
}
