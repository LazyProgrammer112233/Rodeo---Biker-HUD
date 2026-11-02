package com.rodeo.hud.nav.gmaps

import com.rodeo.hud.nav.Maneuver
import java.util.Locale
import kotlin.math.roundToInt

/** The text of Google Maps' navigation notification, as it arrived. */
data class MapsNotice(
    val title: String? = null,
    val text: String? = null,
    val subText: String? = null,
    val bigText: String? = null,
    /** Text found in a custom notification layout (older Google Maps versions). */
    val extraLines: List<String> = emptyList(),
    /** Resource name of the notification's small icon, when Google Maps used a named resource. */
    val iconName: String? = null,
)

enum class NavStatus { NAVIGATING, REROUTING, NO_GPS, ARRIVED }

/** What the rider needs from one notification. Plain values so it is easy to test and show. */
data class MapsGuidance(
    val maneuver: Maneuver,
    /** The turn came from the words ("Turn left onto…"), not only from the picture. */
    val fromText: Boolean = false,
    /** The arrow on the HUD is Rodeo's own; false means show Google's picture instead. */
    val arrowTrusted: Boolean = true,
    /** As Google wrote it, e.g. "450 m". */
    val distanceText: String? = null,
    val distanceM: Double? = null,
    val imperial: Boolean = false,
    /** Road or place name, e.g. "MG Road". */
    val street: String? = null,
    /** True when the street is where the road leads ("towards Indiranagar"), not the road itself. */
    val towards: Boolean = false,
    /** The whole instruction line as Google wrote it. */
    val instruction: String? = null,
    val roundaboutExit: Int? = null,
    val remainingText: String? = null,
    val remainingM: Double? = null,
    val durationText: String? = null,
    val durationMin: Int? = null,
    /** Arrival clock time, e.g. "1:35 PM". */
    val eta: String? = null,
    val status: NavStatus = NavStatus.NAVIGATING,
)

/**
 * Reads Google Maps' turn-by-turn notification. Google Maps puts the distance to the next turn,
 * the road and a "15 min · 6.8 km · 1:35 PM ETA" summary in the notification, in whichever fields
 * its current version uses, so each line is recognised by what it looks like rather than by where
 * it sits. The turn itself comes from the words when Google spells it out ("Turn left onto…"),
 * else from the arrow picture ([ArrowReader]).
 */
object NoticeParser {

    private const val NUM = """(\d{1,3}(?:[,  ]\d{3})*(?:[.,]\d+)?|\d+(?:[.,]\d+)?)"""
    private const val UNIT = """(km|kms|kilometres?|kilometers?|m|metres?|meters?|mi|miles?|ft|feet|foot|yd|yards?|किमी|कि\.मी\.|कि॰मी॰|मी|मी\.|मी॰|मीटर|किलोमीटर)"""
    private val DISTANCE = Regex("""(?i)(?:^|\s|in\s)$NUM\s?$UNIT(?=$|[\s.,·•|)])""")
    private val ONLY_DISTANCE = Regex("""(?i)^\s*(?:in\s+)?$NUM\s?$UNIT\.?\s*$""")
    private val LEADING_DISTANCE = Regex("""(?i)^\s*(?:in\s+)?$NUM\s?$UNIT\s*(?:[,·•|:–—-]\s*|\s+)(.+)$""")
    private val CLOCK = Regex("""(?i)\b(\d{1,2}[:.]\d{2})\s*(a\.?m\.?|p\.?m\.?|पूर्वाह्न|अपराह्न)?""")
    private val DURATION = Regex("""(?i)(?:(\d+)\s*(?:h|hr|hrs|hour|hours|घंटे|घंटा)\.?\s*)?(?:(\d+)\s*(?:min|mins|minute|minutes|मिनट))|(\d+)\s*(?:h|hr|hrs|hour|hours|घंटे|घंटा)\b""")
    private val SEPARATORS = Regex("""\s*[·•|]\s*|\s+-\s+|\s+–\s+""")
    /** "100 Feet Road", "80 Ft Rd": a road name, not a distance. */
    private val ROAD_WORD = Regex("""(?i)^(road|rd\b|ring|main|cross|street|st\b|marg|path|highway)""")
    private val ORDINAL = Regex("""(?i)\b(\d+)(?:st|nd|rd|th)\s+exit""")
    private val ORDINAL_WORDS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")

    fun parse(n: MapsNotice, arrow: ArrowReading? = null): MapsGuidance? {
        val lines = listOfNotNull(n.title, n.text, n.subText, n.bigText).plus(n.extraLines)
            .map { clean(it) }.filter { it.isNotBlank() }.distinct()
        if (lines.isEmpty()) return null

        var distanceText: String? = null
        var distanceM: Double? = null
        var imperial = false
        var summary: Summary? = null
        val rest = ArrayList<String>()
        fun takeDistance(num: String, unit: String) {
            distanceText = shortDistance(num, unit)
            distanceM = metres(num, unit)
            imperial = isImperial(unit)
        }
        // A line that is only a distance ("450 m") wins; failing that, a line that starts with one
        // ("450 m · MG Road"), but not a road named after its width ("100 Feet Road").
        val onlyLine = lines.firstOrNull { ONLY_DISTANCE.matchEntire(it) != null && parseSummary(it) == null }
        val leadLine = if (onlyLine != null) null else lines.firstOrNull { l ->
            val m = LEADING_DISTANCE.matchEntire(l) ?: return@firstOrNull false
            parseSummary(l) == null && !ROAD_WORD.containsMatchIn(m.groupValues[3])
        }
        for (line in lines) {
            if (line == onlyLine) {
                ONLY_DISTANCE.matchEntire(line)!!.let { takeDistance(it.groupValues[1], it.groupValues[2]) }
                continue
            }
            if (line == leadLine) {
                val m = LEADING_DISTANCE.matchEntire(line)!!
                takeDistance(m.groupValues[1], m.groupValues[2])
                rest += m.groupValues[3].trim()
                continue
            }
            if (summary == null) {
                val sm = parseSummary(line)
                if (sm != null) { summary = sm; continue }
            }
            rest += line
        }

        val all = lines.joinToString(" ").lowercase(Locale.ROOT)
        val status = when {
            REROUTE.any { it in all } -> NavStatus.REROUTING
            NO_GPS.any { it in all } -> NavStatus.NO_GPS
            ARRIVED.any { it in all } && distanceM == null -> NavStatus.ARRIVED
            else -> NavStatus.NAVIGATING
        }

        // The instruction: the first remaining line that is not a status message.
        val instruction = rest.firstOrNull { line -> val l = line.lowercase(Locale.ROOT); STATUS_WORDS.none { it in l } }
        val words = (instruction ?: "").lowercase(Locale.ROOT)
        val textTurn = turnFromWords(words)
        val iconTurn = n.iconName?.let { turnFromIconName(it.lowercase(Locale.ROOT)) }
        val exit = ORDINAL.find(words)?.groupValues?.get(1)?.toIntOrNull()
            ?: ORDINAL_WORDS.indexOfFirst { Regex("""\b$it exit\b""").containsMatchIn(words) }.takeIf { it >= 0 }?.plus(1)

        var maneuver: Maneuver
        var fromText = false
        var trusted = true
        when {
            status == NavStatus.ARRIVED -> maneuver = Maneuver.ARRIVE
            textTurn != null -> {
                maneuver = refine(textTurn, arrow, iconTurn)
                fromText = true
            }
            iconTurn != null -> maneuver = iconTurn
            arrow != null && arrow.maneuver != Maneuver.UNKNOWN && arrow.confident -> maneuver = arrow.maneuver
            arrow != null && arrow.maneuver != Maneuver.UNKNOWN -> {
                // A guess: keep it for the layout, but show Google's own picture and speak it plainly.
                maneuver = arrow.maneuver
                trusted = false
            }
            else -> {
                maneuver = Maneuver.UNKNOWN
                trusted = false
            }
        }
        if (exit != null && maneuver !in ROUNDABOUTS) {
            maneuver = roundaboutFor(maneuver)
        }

        var (street, towards) = streetOf(instruction, textTurn != null)
        if (status == NavStatus.ARRIVED) {
            // "You have arrived" + the place: the place is what the rider wants to see.
            street = rest.firstOrNull { line -> val l = line.lowercase(Locale.ROOT); ARRIVED.none { it in l } && STATUS_WORDS.none { it in l } }
            towards = false
        }
        val s = summary
        return MapsGuidance(
            maneuver = maneuver,
            fromText = fromText,
            arrowTrusted = trusted,
            distanceText = distanceText,
            distanceM = distanceM,
            imperial = imperial,
            street = street,
            towards = towards,
            instruction = instruction,
            roundaboutExit = exit,
            remainingText = s?.distanceText,
            remainingM = s?.distanceM,
            durationText = s?.durationText,
            durationMin = s?.durationMin,
            eta = s?.eta,
            status = status,
        )
    }

    /** Text gives the kind of turn; the picture can still say which side or which way out. */
    private fun refine(text: Maneuver, arrow: ArrowReading?, icon: Maneuver?): Maneuver {
        val pic = icon ?: arrow?.takeIf { it.maneuver != Maneuver.UNKNOWN }?.maneuver
        return when (text) {
            Maneuver.UTURN_RIGHT -> if (pic == Maneuver.UTURN_LEFT) Maneuver.UTURN_LEFT else text
            Maneuver.ROUNDABOUT_STRAIGHT -> if (pic != null) roundaboutFor(pic) else text
            else -> text
        }
    }

    private fun roundaboutFor(m: Maneuver): Maneuver = when (m) {
        Maneuver.LEFT, Maneuver.SLIGHT_LEFT, Maneuver.SHARP_LEFT, Maneuver.KEEP_LEFT, Maneuver.ROUNDABOUT_LEFT -> Maneuver.ROUNDABOUT_LEFT
        Maneuver.RIGHT, Maneuver.SLIGHT_RIGHT, Maneuver.SHARP_RIGHT, Maneuver.KEEP_RIGHT, Maneuver.ROUNDABOUT_RIGHT -> Maneuver.ROUNDABOUT_RIGHT
        Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT, Maneuver.ROUNDABOUT_UTURN -> Maneuver.ROUNDABOUT_UTURN
        else -> Maneuver.ROUNDABOUT_STRAIGHT
    }

    private val ROUNDABOUTS = setOf(Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT, Maneuver.ROUNDABOUT_STRAIGHT, Maneuver.ROUNDABOUT_UTURN)

    private val REROUTE = listOf("rerouting", "re-routing", "recalculating", "finding a new route", "finding route", "नया रास्ता", "रास्ता बदल")
    private val NO_GPS = listOf("searching for gps", "gps signal lost", "gps signal", "waiting for location", "waiting for gps", "no gps", "location unavailable", "जीपीएस")
    private val ARRIVED = listOf("you have arrived", "you've arrived", "arrived at", "you arrived", "पहुंच गए", "पहुँच गए")
    private val STATUS_WORDS = REROUTE + NO_GPS + listOf("starting navigation", "navigation paused", "tap to return", "tap to view")

    /** The kind of turn, from Google's words (English, with common Hindi words). */
    fun turnFromWords(w: String): Maneuver? {
        if (w.isBlank()) return null
        fun has(vararg k: String) = k.any { it in w }
        val left = has(" left", "left ", "बाएं", "बाएँ", "बायें", "बाईं", "बायीं") || w == "left"
        val right = has(" right", "right ", "दाएं", "दाएँ", "दायें", "दाईं", "दायीं") || w == "right"
        return when {
            has("u-turn", "u turn", "uturn", "यू-टर्न", "यू टर्न") -> if (left && !right) Maneuver.UTURN_LEFT else Maneuver.UTURN_RIGHT
            has("roundabout", "traffic circle", "rotary", "गोल चक्कर", "गोलचक्कर", "चौराहे पर") -> when {
                has("straight", "सीधे") -> Maneuver.ROUNDABOUT_STRAIGHT
                left && !right -> Maneuver.ROUNDABOUT_LEFT
                right && !left -> Maneuver.ROUNDABOUT_RIGHT
                else -> Maneuver.ROUNDABOUT_STRAIGHT
            }
            has("destination", "arrive", "गंतव्य", "मंज़िल", "मंजिल") -> Maneuver.ARRIVE
            has("sharp left", "sharply left", "तेज़ बाएं", "तेज बाएं", "तीखा बाएं") -> Maneuver.SHARP_LEFT
            has("sharp right", "sharply right", "तेज़ दाएं", "तेज दाएं", "तीखा दाएं") -> Maneuver.SHARP_RIGHT
            has("slight left", "slightly left", "bear left", "हल्का बाएं", "थोड़ा बाएं") -> Maneuver.SLIGHT_LEFT
            has("slight right", "slightly right", "bear right", "हल्का दाएं", "थोड़ा दाएं") -> Maneuver.SLIGHT_RIGHT
            has("keep left", "stay left", "keep to the left", "fork left", "left fork", "बाईं ओर रहें", "बाएं रहें") -> Maneuver.KEEP_LEFT
            has("keep right", "stay right", "keep to the right", "fork right", "right fork", "दाईं ओर रहें", "दाएं रहें") -> Maneuver.KEEP_RIGHT
            has("merge") -> when {
                left && !right -> Maneuver.KEEP_LEFT
                right && !left -> Maneuver.KEEP_RIGHT
                else -> Maneuver.MERGE
            }
            has("exit", "ramp") && left -> Maneuver.SLIGHT_LEFT
            has("exit", "ramp") && right -> Maneuver.SLIGHT_RIGHT
            has("turn left", "left onto", "left on ", "left at", "left to", "left toward", "मुड़ें") && left && !right -> Maneuver.LEFT
            has("turn right", "right onto", "right on ", "right at", "right to", "right toward", "मुड़ें") && right && !left -> Maneuver.RIGHT
            has("continue straight", "go straight", "straight ahead", "keep straight", "सीधे") -> Maneuver.STRAIGHT
            Regex("""^head (north|south|east|west|northeast|northwest|southeast|southwest)\b""").containsMatchIn(w) -> Maneuver.DEPART
            else -> null
        }
    }

    /** Older Google Maps versions name their turn icons ("da_turn_slight_left", "ic_roundabout_…"). */
    fun turnFromIconName(n: String): Maneuver? {
        if (!(n.contains("turn") || n.contains("roundabout") || n.contains("fork") || n.contains("ramp") || n.contains("merge") ||
                n.contains("straight") || n.contains("uturn") || n.contains("u_turn") || n.contains("destination") || n.contains("arrive"))
        ) return null
        val left = n.contains("left")
        val right = n.contains("right")
        return when {
            n.contains("uturn") || n.contains("u_turn") -> if (left) Maneuver.UTURN_LEFT else Maneuver.UTURN_RIGHT
            n.contains("roundabout") -> when {
                n.contains("straight") -> Maneuver.ROUNDABOUT_STRAIGHT
                n.contains("u_turn") || n.contains("uturn") -> Maneuver.ROUNDABOUT_UTURN
                left -> Maneuver.ROUNDABOUT_LEFT
                right -> Maneuver.ROUNDABOUT_RIGHT
                else -> null
            }
            n.contains("destination") || n.contains("arrive") -> Maneuver.ARRIVE
            n.contains("sharp") -> if (left) Maneuver.SHARP_LEFT else if (right) Maneuver.SHARP_RIGHT else null
            n.contains("slight") -> if (left) Maneuver.SLIGHT_LEFT else if (right) Maneuver.SLIGHT_RIGHT else null
            n.contains("fork") || n.contains("keep") -> if (left) Maneuver.KEEP_LEFT else if (right) Maneuver.KEEP_RIGHT else null
            n.contains("ramp") -> if (left) Maneuver.SLIGHT_LEFT else if (right) Maneuver.SLIGHT_RIGHT else null
            n.contains("merge") -> Maneuver.MERGE
            n.contains("straight") -> Maneuver.STRAIGHT
            left -> Maneuver.LEFT
            right -> Maneuver.RIGHT
            else -> null
        }
    }

    /** The road from "Turn left onto MG Road", "Continue on NH 44", "toward Indiranagar", or a bare name. */
    private fun streetOf(instruction: String?, hadTurnWords: Boolean): Pair<String?, Boolean> {
        val s = instruction?.trim()?.trimEnd('.') ?: return null to false
        val l = s.lowercase(Locale.ROOT)
        fun after(word: String): String? {
            val i = l.indexOf(word)
            return if (i >= 0) s.substring(i + word.length).trim().takeIf { it.isNotBlank() } else null
        }
        // "onto X toward Y": the road is X.
        after(" onto ")?.let { return it.substringBefore(" toward").trim() to false }
        after(" on to ")?.let { return it to false }
        if (l.startsWith("toward ") || l.startsWith("towards ")) return s.substringAfter(' ').trim() to true
        after(" towards ")?.let { return it to true }
        after(" toward ")?.let { return it to true }
        if (hadTurnWords) {
            after(" on ")?.let { return it to false }
            after(" into ")?.let { return it to false }
            after(" at ")?.let { return it to false }
            return null to false
        }
        return s to false
    }

    private data class Summary(val durationText: String?, val durationMin: Int?, val distanceText: String?, val distanceM: Double?, val eta: String?)

    /** "15 min · 6.8 km · 1:35 PM ETA" (in any order, with any of the usual separators). */
    private fun parseSummary(line: String): Summary? {
        val l = line.lowercase(Locale.ROOT)
        val parts = line.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        val hasEtaWord = listOf("eta", "arrival", "arrive", "पहुंच", "पहुँच").any { it in l }
        val dur = DURATION.find(line)
        val clock = CLOCK.find(line)
        // A summary has a duration or arrival time, plus something else (distance, clock, ETA word).
        val pieces = listOf(dur != null, clock != null, hasEtaWord, DISTANCE.containsMatchIn(line)).count { it }
        if (pieces < 2 || (dur == null && clock == null)) return null
        // Not the turn line: a lone "500 m" with a clock in it is unlikely; require separators or ETA words.
        if (parts.size < 2 && !hasEtaWord) return null
        var dText: String? = null
        var dM: Double? = null
        DISTANCE.find(line)?.let { m ->
            dText = shortDistance(m.groupValues[1], m.groupValues[2]); dM = metres(m.groupValues[1], m.groupValues[2])
        }
        val durMin = dur?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: m.groupValues[3].toIntOrNull() ?: 0
            val min = m.groupValues[2].toIntOrNull() ?: 0
            h * 60 + min
        }
        val durText = dur?.value?.trim()?.let { normaliseDuration(it) }
        val eta = clock?.let { c ->
            val suffix = c.groupValues[2].replace(".", "").uppercase(Locale.ROOT)
            (c.groupValues[1].replace('.', ':') + if (suffix == "AM" || suffix == "PM") " $suffix" else "").trim()
        }
        return Summary(durText, durMin, dText, dM, eta)
    }

    private fun normaliseDuration(s: String): String = s.replace(Regex("""\s+"""), " ")

    private fun clean(s: String): String = s.replace(' ', ' ').replace(' ', ' ').replace(Regex("""[‎‏‪-‮]"""), "")
        .replace(Regex("""\s+"""), " ").trim()

    private fun number(s: String): Double? {
        val t = s.replace(' ', ' ').trim()
        // "1,2" is a decimal comma; "1,200" is thousands.
        val normal = when {
            Regex("""^\d{1,3}([, ]\d{3})+$""").matches(t) -> t.replace(",", "").replace(" ", "")
            else -> t.replace(',', '.').replace(" ", "")
        }
        return normal.toDoubleOrNull()
    }

    private fun isImperial(unit: String): Boolean = unit.lowercase(Locale.ROOT).let { it.startsWith("mi") || it.startsWith("f") || it.startsWith("y") }

    fun metres(num: String, unit: String): Double? {
        val v = number(num) ?: return null
        val u = unit.lowercase(Locale.ROOT)
        return when {
            u.startsWith("k") || u.startsWith("कि") -> v * 1000.0
            u.startsWith("mi") && u != "मी" -> v * 1609.344
            u == "ft" || u.startsWith("fe") || u.startsWith("fo") -> v * 0.3048
            u.startsWith("y") -> v * 0.9144
            else -> v
        }
    }

    /** "450 m", "1.2 km", "500 ft", "0.3 mi" for the HUD. */
    private fun shortDistance(num: String, unit: String): String {
        val u = unit.lowercase(Locale.ROOT)
        val short = when {
            u.startsWith("k") || u.startsWith("कि") -> "km"
            u.startsWith("mi") && u != "मी" -> "mi"
            u == "ft" || u.startsWith("fe") || u.startsWith("fo") -> "ft"
            u.startsWith("y") -> "yd"
            else -> "m"
        }
        val n = number(num)
        val shown = if (n == null) num else if (n % 1.0 == 0.0) n.roundToInt().toString() else n.toString()
        return "$shown $short"
    }
}
