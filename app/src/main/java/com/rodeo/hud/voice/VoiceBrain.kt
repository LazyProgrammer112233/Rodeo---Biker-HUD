package com.rodeo.hud.voice

/**
 * The assistant's decisions, separate from audio so they can be replayed in tests:
 *
 *  - Idle: listens only for the wake phrase (or "emergency emergency").
 *  - Awake: the next few seconds are a command. A strong SOS word acts at once, mid-sentence.
 *  - Countdown: an SOS is counting down; "cancel" / "I'm okay" stops it and "help" / "send now"
 *    sends it, no wake phrase needed.
 *
 * Three recognisers may feed it: the open one (any words); a keyword spotter limited to the wake
 * phrase and SOS words, which keeps hearing them through engine and wind noise; and, once awake, a
 * command one limited to the command phrases. Whichever understands first wins.
 */
class VoiceBrain(
    private var wake: WakeMatcher,
    private val out: Output,
    var repeatedSosEnabled: Boolean = true,
    /** How sure the keyword spotter must be of a word. */
    var keywordConf: Double = 0.5,
) {
    interface Output {
        fun onWake()
        fun onCommand(p: Parsed, heard: String)
        /** Awake but what was said didn't make sense; [final] means the window has closed. */
        fun onNothing(heard: String, final: Boolean)
        fun onHeard(text: String) {}
        /** Feed the command recogniser again from [fromSec] (keyword-spotter time) to now. */
        fun onReplay(fromSec: Double) {}
    }

    enum class Mode { IDLE, AWAKE, COUNTDOWN }

    var mode = Mode.IDLE
        private set
    private var deadline = 0L
    private var retried = false
    /** Words of the current utterance up to and including the wake phrase. */
    private var skip = 0
    private var utteranceHadWake = false
    /** The current utterance was already acted on from a partial result (per recogniser). */
    private val consumed = BooleanArray(3)
    private var unknownAt = 0L
    private var unknownHeard = ""

    fun setWake(m: WakeMatcher) { wake = m }

    /** Tap on the Voice tile, the helmet button, or a BT remote. */
    fun wakeManually(now: Long) {
        if (mode == Mode.COUNTDOWN) return
        enterAwake(now, 0, fromSpeech = false)
    }

    /** The rider closed the voice panel. */
    fun cancelAwake() {
        if (mode == Mode.AWAKE) {
            mode = Mode.IDLE
            reset()
        }
    }

    fun setCountdown(active: Boolean, now: Long) {
        if (active) {
            mode = Mode.COUNTDOWN
            reset()
        } else if (mode == Mode.COUNTDOWN) {
            mode = Mode.IDLE
            reset()
        }
        deadline = now
    }

    /** Recogniser's running guess for the utterance in progress. */
    fun partial(text: String, now: Long, source: Int = OPEN) {
        if (text.isBlank() || consumed[source]) return
        // The keyword spotter's running guesses flicker between its few phrases; only its
        // finished results (with confidences) are used.
        if (source == KEYWORDS) return
        out.onHeard(text)
        val words = Phonetic.words(text)
        when (mode) {
            Mode.COUNTDOWN -> {
                // Act on clear words straight away; the alarm is loud and every second counts.
                val p = Commands.parse(text, countdown = true)
                if (p.command == VoiceCommand.CANCEL || (p.command == VoiceCommand.SOS && Commands.isSos(words))) fire(p, text, source = source)
            }
            Mode.IDLE -> {
                if (repeatedSosEnabled && Commands.isRepeatedSos(words)) { fire(Parsed(VoiceCommand.SOS), text, source = source); return }
                if (source != OPEN) return
                val hit = wake.find(words) ?: return
                enterAwake(now, hit.end, fromSpeech = true)
                if (Commands.isSos(words.drop(hit.end))) fire(Parsed(VoiceCommand.SOS), text, source = source)
            }
            Mode.AWAKE -> {
                // The command recogniser can force noise into its phrases, so its SOS waits for
                // the end of the utterance.
                if (source == COMMANDS) return
                val rest = if (utteranceHadWake && source == OPEN) words.drop(skip) else words
                if (Commands.isSos(rest)) fire(Parsed(VoiceCommand.SOS), text, source = source)
            }
        }
    }

    /** A word from the keyword spotter, with the recogniser's confidence and its time in the stream. */
    data class Word(val text: String, val conf: Double, val start: Double, val end: Double)

    /**
     * The keyword spotter's result for a finished utterance. It only knows the wake phrase and the
     * SOS words, so it keeps hearing them through engine and wind noise, but it also squeezes other
     * speech into them; words below [keywordConf] count as unknown. It wakes the assistant (and
     * the command recogniser replays what was said after the wake phrase), and sends SOS for the
     * wake phrase plus an SOS word, an SOS word said twice, or an SOS word while awake.
     */
    fun keywords(result: List<Word>, now: Long) {
        if (mode == Mode.COUNTDOWN || result.isEmpty()) return
        val words = result.map { if (it.conf >= keywordConf) Phonetic.words(it.text).joinToString(" ").ifEmpty { "unk" } else "unk" }
            .flatMap { it.split(' ') }
        val heard = result.joinToString(" ") { it.text }
        if (repeatedSosEnabled && Commands.isRepeatedSos(words)) { fire(Parsed(VoiceCommand.SOS), heard, fromPartial = false, source = KEYWORDS); return }
        val hit = wake.find(words)
        if (hit != null) {
            val wasAwake = mode == Mode.AWAKE
            if (!wasAwake) {
                out.onHeard(heard)
                enterAwake(now, 0, fromSpeech = false)
            }
            if (Commands.isSos(words.drop(hit.end))) { fire(Parsed(VoiceCommand.SOS), heard, fromPartial = false, source = KEYWORDS); return }
            // Hand what came after the wake phrase to the command recogniser.
            if (!wasAwake) result.getOrNull(wakeWordIndex(result, hit.end) - 1)?.let { out.onReplay(it.end) }
            return
        }
        if (mode == Mode.AWAKE && Commands.isSos(words)) fire(Parsed(VoiceCommand.SOS), heard, fromPartial = false, source = KEYWORDS)
    }

    /** Maps a position in the split word list back to the recogniser's word list. */
    private fun wakeWordIndex(result: List<Word>, splitIndex: Int): Int {
        var n = 0
        for ((i, w) in result.withIndex()) {
            n += Phonetic.words(w.text).size.coerceAtLeast(1)
            if (n >= splitIndex) return i + 1
        }
        return result.size
    }

    /**
     * End of an utterance. [alternatives] are the recogniser's best guesses, best first.
     */
    fun final(alternatives: List<String>, now: Long, source: Int = OPEN) {
        val texts = alternatives.filter { it.isNotBlank() }
        if (source == KEYWORDS) return
        val wasConsumed = consumed[source]
        consumed[source] = false
        if (texts.isEmpty() || wasConsumed) {
            if (source == OPEN) {
                utteranceHadWake = false
                skip = 0
            }
            return
        }
        out.onHeard(texts.first())
        when (mode) {
            Mode.COUNTDOWN -> {
                val p = texts.map { Commands.parse(it, countdown = true) }
                    .firstOrNull { it.command == VoiceCommand.CANCEL || it.command == VoiceCommand.SOS }
                if (p != null) fire(p, texts.first(), fromPartial = false)
            }
            Mode.IDLE -> {
                for (t in texts) {
                    val words = Phonetic.words(t)
                    if (repeatedSosEnabled && Commands.isRepeatedSos(words)) { fire(Parsed(VoiceCommand.SOS), t, fromPartial = false); return }
                }
                if (source != OPEN) return
                for (t in texts) {
                    val words = Phonetic.words(t)
                    val hit = wake.find(words) ?: continue
                    enterAwake(now, hit.end, fromSpeech = true)
                    command(words.drop(hit.end), t, now)
                    utteranceHadWake = false
                    skip = 0
                    return
                }
            }
            Mode.AWAKE -> {
                val drop = utteranceHadWake && source == OPEN
                for (t in texts) {
                    var rest = Phonetic.words(t).let { if (drop) it.drop(skip) else it }
                    // Saying the wake phrase again ("hey doku ... hey doku, next") is fine.
                    wake.find(rest)?.let { rest = rest.drop(it.end) }
                    val p = Commands.parse(rest.joinToString(" "))
                    if (p.command != VoiceCommand.UNKNOWN) { finish(p, t); return }
                }
                val rest = Phonetic.words(texts.first()).let { if (drop) it.drop(skip) else it }
                command(rest, texts.first(), now)
            }
        }
        if (source == OPEN) {
            utteranceHadWake = false
            skip = 0
        }
    }

    /** Called about every 100 ms; ends the awake window when time runs out. */
    fun tick(now: Long) {
        if (mode != Mode.AWAKE) return
        // Give the other recogniser half a second to make sense of it before saying "sorry".
        if (unknownAt > 0 && now - unknownAt > 500) {
            val heard = unknownHeard
            unknownAt = 0
            if (!retried) {
                retried = true
                deadline = now + RETRY_MS
                out.onNothing(heard, final = false)
            } else {
                mode = Mode.IDLE
                reset()
                out.onNothing(heard, final = true)
            }
            return
        }
        if (now > deadline) {
            mode = Mode.IDLE
            reset()
            out.onNothing("", final = true)
        }
    }

    private fun command(rest: List<String>, heard: String, now: Long) {
        if (rest.isEmpty()) return // just the wake phrase: wait for the command
        val p = Commands.parse(rest.joinToString(" "))
        if (p.command != VoiceCommand.UNKNOWN) { finish(p, heard); return }
        if (unknownAt == 0L) unknownAt = now
        unknownHeard = heard
    }

    private fun enterAwake(now: Long, skipWords: Int, fromSpeech: Boolean) {
        val wasAwake = mode == Mode.AWAKE
        mode = Mode.AWAKE
        deadline = now + AWAKE_MS
        retried = false
        unknownAt = 0
        skip = skipWords
        utteranceHadWake = fromSpeech
        if (!wasAwake) out.onWake()
    }

    private fun finish(p: Parsed, heard: String) {
        mode = Mode.IDLE
        reset()
        out.onCommand(p, heard)
    }

    private fun fire(p: Parsed, heard: String, fromPartial: Boolean = true, source: Int = OPEN) {
        if (mode != Mode.COUNTDOWN) mode = Mode.IDLE
        reset()
        if (fromPartial) consumed[source] = true
        out.onCommand(p, heard)
    }

    private fun reset() {
        retried = false
        skip = 0
        utteranceHadWake = false
        unknownAt = 0
        unknownHeard = ""
    }

    companion object {
        /** The open recogniser (any words), the command one and the keyword spotter (grammar-limited). */
        const val OPEN = 0
        const val COMMANDS = 1
        const val KEYWORDS = 2
        const val AWAKE_MS = 7_000L
        const val RETRY_MS = 6_000L
    }
}
