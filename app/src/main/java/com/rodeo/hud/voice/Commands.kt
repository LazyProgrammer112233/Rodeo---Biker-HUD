package com.rodeo.hud.voice

enum class VoiceCommand {
    SOS, CANCEL,
    NEXT, PREVIOUS, PAUSE, PLAY,
    NAVIGATE_HOME, NAVIGATE_TO, FIND_BIKE, STOP_NAVIGATION, MUTE_GUIDANCE, UNMUTE_GUIDANCE,
    START_RIDE, END_RIDE, DISMISS,
    VOLUME_UP, VOLUME_DOWN,
    SPEED, TIME, BATTERY, ETA,
    UNKNOWN,
}

data class Parsed(val command: VoiceCommand, val arg: String = "")

/**
 * What the rider asked for, from the words the recogniser heard. Keyword based with sound-alike
 * matching, plus a few Hinglish forms. SOS always wins over everything else.
 */
object Commands {
    /** Words that mean "send SOS" whatever else is said. "Help" counts only when nothing else matched. */
    val SOS_WORDS = listOf("emergency", "sos", "accident", "ambulance", "bachao", "madad", "mayday")

    private val CANCEL_PHRASES = listOf(
        "cancel", "i am ok", "i am okay", "im ok", "im okay", "i am fine", "im fine", "i am alright", "false alarm",
        "dont send", "do not send", "stop sos", "abort", "theek hoon", "thik hoon", "main theek",
    )

    /** The phrases the command recogniser is limited to (its grammar), plus "[unk]". */
    val GRAMMAR: List<String> = listOf(
        "emergency", "help", "help me", "s o s", "send s o s", "accident", "ambulance", "send now",
        "cancel", "i am okay", "i am ok", "i am fine", "false alarm", "do not send",
        "next", "next song", "skip", "skip song", "previous", "previous song", "go back", "last song",
        "pause", "pause music", "stop music", "stop", "play", "play music", "resume", "resume music",
        "navigate home", "take me home", "go home", "where is my bike", "find my bike", "where did i park",
        "stop navigation", "end navigation", "cancel navigation", "mute navigation", "mute voice", "voice off", "voice on", "guidance off", "guidance on",
        "start ride", "start the ride", "end ride", "end the ride", "stop ride", "finish ride",
        "dismiss", "clear", "volume up", "louder", "volume down", "quieter",
        "what is my speed", "speed", "what time is it", "time", "battery", "how far", "how long", "how much longer",
    )

    /**
     * The keyword spotter's phrases: the wake phrase (and what training heard) and the SOS words.
     * Everything else is heard as "[unk]". A made-up name the recogniser doesn't know ("doku")
     * is dropped by it, which is why training matters: the aliases are words it knows.
     */
    fun keywordGrammar(phrase: String, aliases: List<String>, known: (String) -> Boolean = { true }): List<String> {
        val wakes = (listOf(phrase) + aliases).map { Phonetic.words(it) }
            .filter { it.isNotEmpty() && it.size <= 6 && it.all(known) }
            .map { it.joinToString(" ") }
        val sos = listOf("emergency", "accident", "ambulance", "s o s", "help", "help me", "mayday", "bachao")
            .filter { p -> p.split(' ').all(known) }
        return (wakes + sos).distinct()
    }

    fun parse(text: String, countdown: Boolean = false): Parsed {
        val words = Phonetic.words(text)
        if (words.isEmpty()) return Parsed(VoiceCommand.UNKNOWN)
        val t = " " + words.joinToString(" ") + " "
        fun has(vararg phrases: String) = phrases.any { " $it " in t }
        fun hasWord(vararg w: String) = words.any { Phonetic.soundsLike(it, w.toList()) }

        // During an SOS countdown "stop" and "no" mean "don't send".
        if (CANCEL_PHRASES.any { " $it " in t } || (countdown && hasWord("stop", "no", "nahi", "ruko"))) {
            return Parsed(VoiceCommand.CANCEL)
        }
        if (isSos(words) || (countdown && has("send now", "send it", "send", "help"))) return Parsed(VoiceCommand.SOS)

        destination(words)?.let { return it }

        val parsed = when {
            has("stop navigation", "end navigation", "cancel navigation", "stop navigating", "exit navigation", "stop route",
                // The recogniser can clip the first consonant when the wake word runs straight into the command.
                "top navigation", "dop navigation", "stop the navigation", "top the navigation") ->
                Parsed(VoiceCommand.STOP_NAVIGATION)
            has("unmute navigation", "unmute voice", "unmute directions", "on mute navigation", "voice on", "guidance on") -> Parsed(VoiceCommand.UNMUTE_GUIDANCE)
            has("mute navigation", "mute voice", "mute directions", "voice off", "guidance off", "quiet navigation") -> Parsed(VoiceCommand.MUTE_GUIDANCE)
            has("where is my bike", "find my bike", "where did i park", "find bike", "my bike", "parked bike", "where is the bike") ->
                Parsed(VoiceCommand.FIND_BIKE)
            has("start ride", "start the ride", "start riding", "begin ride", "lets ride", "start my ride", "ride start", "dart the ride", "art the ride", "tart the ride", "dart ride") ->
                Parsed(VoiceCommand.START_RIDE)
            has("end ride", "end the ride", "stop ride", "stop the ride", "finish ride", "finish the ride", "end my ride", "and the ride", "and my ride", "end the right") ->
                Parsed(VoiceCommand.END_RIDE)
            has("volume up", "louder", "increase volume", "turn it up") -> Parsed(VoiceCommand.VOLUME_UP)
            has("volume down", "quieter", "softer", "decrease volume", "lower volume", "turn it down") -> Parsed(VoiceCommand.VOLUME_DOWN)
            has("what is my speed", "whats my speed", "my speed", "how fast", "speed") -> Parsed(VoiceCommand.SPEED)
            has("how far", "how long", "how much longer", "eta", "time to destination", "kitna door") -> Parsed(VoiceCommand.ETA)
            has("what time", "the time", "time is it", "time") -> Parsed(VoiceCommand.TIME)
            has("battery", "charge left", "phone battery") -> Parsed(VoiceCommand.BATTERY)
            has("previous", "go back", "last song", "previous song", "play previous", "pichla", "pichhla", "back") ||
                hasWord("previous") -> Parsed(VoiceCommand.PREVIOUS)
            has("next", "skip", "next song", "change song", "change the song", "agla", "agla gaana", "next track", "keep this song", "kept this song", "keep the song", "kip this song") ||
                hasWord("next", "skip") -> Parsed(VoiceCommand.NEXT)
            has("pause", "stop music", "stop the music", "stop song", "band karo", "ruko", "music off", "stop", "top the music", "top music", "stop the song", "top the song") ||
                hasWord("pause") -> Parsed(VoiceCommand.PAUSE)
            has("play", "resume", "play music", "start music", "music on", "chalao", "bajao", "gaana bajao", "continue music") ||
                hasWord("play", "resume") -> Parsed(VoiceCommand.PLAY)
            has("dismiss", "clear", "close", "hide", "got it", "okay thanks", "ok thanks") -> Parsed(VoiceCommand.DISMISS)
            else -> Parsed(VoiceCommand.UNKNOWN)
        }
        if (parsed.command == VoiceCommand.UNKNOWN && hasWord("help")) return Parsed(VoiceCommand.SOS)
        if (parsed.command == VoiceCommand.UNKNOWN) soundsLikeCommand(words)?.let { return Parsed(it) }
        return parsed
    }

    /** Whole commands by sound, for when the recogniser garbles a word ("was the music", "and the right"). */
    private val BY_SOUND: List<Pair<String, VoiceCommand>> = listOf(
        "pause the music" to VoiceCommand.PAUSE, "stop the music" to VoiceCommand.PAUSE,
        "play music" to VoiceCommand.PLAY, "resume the song" to VoiceCommand.PLAY,
        "next song" to VoiceCommand.NEXT, "skip this song" to VoiceCommand.NEXT, "play the next song" to VoiceCommand.NEXT,
        "previous song" to VoiceCommand.PREVIOUS, "go back to the last song" to VoiceCommand.PREVIOUS,
        "start the ride" to VoiceCommand.START_RIDE, "end the ride" to VoiceCommand.END_RIDE,
        "volume up" to VoiceCommand.VOLUME_UP, "make it louder" to VoiceCommand.VOLUME_UP,
        "volume down" to VoiceCommand.VOLUME_DOWN, "make it quieter" to VoiceCommand.VOLUME_DOWN,
        "stop navigation" to VoiceCommand.STOP_NAVIGATION, "take me home" to VoiceCommand.NAVIGATE_HOME,
        "find my bike" to VoiceCommand.FIND_BIKE, "what is my speed" to VoiceCommand.SPEED,
        "how far is it" to VoiceCommand.ETA, "what time is it" to VoiceCommand.TIME,
    )
    private val BY_SOUND_KEYS = BY_SOUND.map { (p, c) -> Triple(Phonetic.phraseKey(Phonetic.words(p)), Phonetic.words(p).size, c) }

    private fun soundsLikeCommand(words: List<String>): VoiceCommand? {
        var best: VoiceCommand? = null
        var bestScore = 0.0
        for ((key, n, cmd) in BY_SOUND_KEYS) {
            for (len in maxOf(1, n - 1)..n + 1) for (i in 0..words.size - len) {
                val s = Phonetic.similarity(Phonetic.phraseKey(words.subList(i, i + len)), key)
                if (s > bestScore) { bestScore = s; best = cmd }
            }
        }
        return best.takeIf { bestScore >= 0.72 }
    }

    /** A strong SOS word (after the wake word, or during a countdown); safe to act on mid-sentence. */
    fun isSos(words: List<String>): Boolean =
        words.any { Phonetic.soundsLike(it, SOS_WORDS, 0.86) } || " ${words.joinToString(" ")} ".contains(" s o s ")

    /**
     * The no-wake-word lifeline: the same SOS word twice in one breath ("emergency emergency").
     * "Help help" is left out because it turns up in songs.
     */
    fun isRepeatedSos(words: List<String>): Boolean {
        val strong = listOf("emergency", "sos", "ambulance", "bachao", "mayday")
        var count = 0
        var last = -10
        for ((i, w) in words.withIndex()) {
            if (Phonetic.soundsLike(w, strong, 0.86)) {
                count = if (i - last <= 3) count + 1 else 1
                last = i
                if (count >= 2) return true
            }
        }
        return false
    }

    private val DEST_LEADS = listOf(
        listOf("navigate", "to"), listOf("take", "me", "to"), listOf("directions", "to"), listOf("route", "to"),
        listOf("go", "to"), listOf("drive", "to"), listOf("ride", "to"), listOf("navigate"), listOf("navigation", "to"),
    )

    private fun destination(words: List<String>): Parsed? {
        for (lead in DEST_LEADS) {
            for (i in 0..words.size - lead.size) {
                if (words.subList(i, i + lead.size) == lead) {
                    val rest = words.drop(i + lead.size).filterNot { it in setOf("the", "please") }
                    if (rest.isEmpty()) return null
                    if (rest == listOf("home") || rest == listOf("my", "home") || rest == listOf("ghar")) return Parsed(VoiceCommand.NAVIGATE_HOME)
                    return Parsed(VoiceCommand.NAVIGATE_TO, rest.joinToString(" "))
                }
            }
        }
        val t = " " + words.joinToString(" ") + " "
        if (" take me home " in t || " go home " in t || " navigate home " in t || " ghar chalo " in t || " home " == t) {
            return Parsed(VoiceCommand.NAVIGATE_HOME)
        }
        return null
    }
}
