package com.rodeo.hud.logic

import com.rodeo.hud.voice.Commands
import com.rodeo.hud.voice.Parsed
import com.rodeo.hud.voice.Phonetic
import com.rodeo.hud.voice.VoiceBrain
import com.rodeo.hud.voice.VoiceCommand
import com.rodeo.hud.voice.WakeMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLogicTest {

    @Test fun soundAlikeKeys() {
        assertEquals(Phonetic.key("doku"), Phonetic.phraseKey(listOf("do", "coo")))
        assertEquals(Phonetic.key("next"), Phonetic.key("nekst"))
        assertTrue(Phonetic.similarity(Phonetic.key("emergency"), Phonetic.key("emergancy")) > 0.85)
    }

    @Test fun wakePhraseByMeansOfSound() {
        val w = WakeMatcher("Hey Doku")
        for (heard in listOf("hey doku", "hey do coo", "hey doku emergency", "hay doku", "hey dokoo next song", "okay hey doku")) {
            assertNotNull("should wake on '$heard'", w.find(heard))
        }
        for (heard in listOf("take a look", "the doctor is in", "hello how are you", "next song", "turn left onto mg road", "do you know")) {
            assertNull("should not wake on '$heard'", w.find(heard))
        }
    }

    @Test fun trainedAliasesWake() {
        val w = WakeMatcher("Hey Doku", aliases = listOf("hey the coup"))
        assertNotNull(w.find("hey the coup emergency"))
        assertNotNull(w.find("hey the coop"))
    }

    @Test fun wakeEndsBeforeTheCommand() {
        val w = WakeMatcher("Hey Rodeo")
        val words = Phonetic.words("hey rodeo next song")
        val hit = w.find(words)!!
        assertEquals(listOf("next", "song"), words.drop(hit.end))
    }

    @Test fun commands() {
        fun cmd(t: String, countdown: Boolean = false) = Commands.parse(t, countdown).command
        assertEquals(VoiceCommand.SOS, cmd("emergency"))
        assertEquals(VoiceCommand.SOS, cmd("help me"))
        assertEquals(VoiceCommand.SOS, cmd("there has been an accident"))
        assertEquals(VoiceCommand.SOS, cmd("call an ambulance"))
        assertEquals(VoiceCommand.CANCEL, cmd("i am okay"))
        assertEquals(VoiceCommand.CANCEL, cmd("i'm okay"))
        assertEquals(VoiceCommand.CANCEL, cmd("stop", countdown = true))
        assertEquals(VoiceCommand.SOS, cmd("send now", countdown = true))
        assertEquals(VoiceCommand.NEXT, cmd("next song"))
        assertEquals(VoiceCommand.NEXT, cmd("skip"))
        assertEquals(VoiceCommand.PREVIOUS, cmd("previous song"))
        assertEquals(VoiceCommand.PAUSE, cmd("pause the music"))
        assertEquals(VoiceCommand.PAUSE, cmd("stop music"))
        assertEquals(VoiceCommand.PLAY, cmd("play music"))
        assertEquals(VoiceCommand.NAVIGATE_HOME, cmd("take me home"))
        assertEquals(VoiceCommand.NAVIGATE_HOME, cmd("navigate home"))
        assertEquals(Parsed(VoiceCommand.NAVIGATE_TO, "nandi hills"), Commands.parse("navigate to nandi hills"))
        assertEquals(Parsed(VoiceCommand.NAVIGATE_TO, "indian oil petrol pump"), Commands.parse("take me to the indian oil petrol pump"))
        assertEquals(VoiceCommand.FIND_BIKE, cmd("where is my bike"))
        assertEquals(VoiceCommand.STOP_NAVIGATION, cmd("stop navigation"))
        assertEquals(VoiceCommand.END_RIDE, cmd("end ride"))
        assertEquals(VoiceCommand.START_RIDE, cmd("start the ride"))
        assertEquals(VoiceCommand.SPEED, cmd("what is my speed"))
        assertEquals(VoiceCommand.ETA, cmd("how far"))
        assertEquals(VoiceCommand.VOLUME_UP, cmd("volume up"))
        assertEquals(VoiceCommand.UNKNOWN, cmd("banana"))
    }

    @Test fun repeatedSosWithoutWake() {
        assertTrue(Commands.isRepeatedSos(Phonetic.words("emergency emergency")))
        assertTrue(Commands.isRepeatedSos(Phonetic.words("emergency please emergency")))
        assertTrue(!Commands.isRepeatedSos(Phonetic.words("emergency exit")))
        assertTrue(!Commands.isRepeatedSos(Phonetic.words("help help")))
    }

    // --- The state machine, replayed the way the recogniser streams text ---

    private class Rec : VoiceBrain.Output {
        val events = ArrayList<String>()
        override fun onWake() { events += "wake" }
        override fun onCommand(p: Parsed, heard: String) { events += p.command.name + if (p.arg.isNotEmpty()) ":" + p.arg else "" }
        override fun onNothing(heard: String, final: Boolean) { events += if (final) "nothing" else "again" }
        override fun onReplay(fromSec: Double) { events += "replay@%.2f".format(fromSec) }
    }

    private fun brain(phrase: String = "Hey Doku"): Pair<VoiceBrain, Rec> {
        val r = Rec()
        return VoiceBrain(WakeMatcher(phrase), r) to r
    }

    @Test fun lifelineActsMidSentence() {
        val (b, r) = brain()
        b.partial("hey", 0)
        b.partial("hey doku", 300)
        b.partial("hey doku emergency", 900)
        assertEquals(listOf("wake", "SOS"), r.events)
        // The final result of the same sentence must not fire it twice.
        b.final(listOf("hey doku emergency"), 1_500)
        assertEquals(listOf("wake", "SOS"), r.events)
    }

    @Test fun wakeThenCommandInOneBreath() {
        val (b, r) = brain()
        b.partial("hey doku next", 500)
        b.final(listOf("hey doku next song"), 1_200)
        assertEquals(listOf("wake", "NEXT"), r.events)
        assertEquals(VoiceBrain.Mode.IDLE, b.mode)
    }

    @Test fun wakeThenPauseThenCommand() {
        val (b, r) = brain()
        b.final(listOf("hey doku"), 1_000)
        assertEquals(VoiceBrain.Mode.AWAKE, b.mode)
        b.partial("navigate", 2_500)
        b.final(listOf("navigate to nandi hills"), 3_500)
        assertEquals(listOf("wake", "NAVIGATE_TO:nandi hills"), r.events)
    }

    @Test fun commandRecogniserCanWin() {
        val (b, r) = brain()
        b.final(listOf("hey doku"), 1_000)
        // The open recogniser mishears; the command-limited one gets it.
        b.final(listOf("next on"), 2_000, VoiceBrain.OPEN)
        b.tick(2_200)
        b.final(listOf("next song"), 2_300, VoiceBrain.COMMANDS)
        b.tick(3_000)
        assertEquals(listOf("wake", "NEXT"), r.events)
    }

    @Test fun nonsenseAsksAgainThenGivesUp() {
        val (b, r) = brain()
        b.final(listOf("hey doku"), 0)
        b.final(listOf("banana"), 1_000)
        b.tick(1_600)
        b.final(listOf("mango"), 3_000)
        b.tick(3_600)
        assertEquals(listOf("wake", "again", "nothing"), r.events)
    }

    @Test fun windowTimesOut() {
        val (b, r) = brain()
        b.wakeManually(0)
        b.tick(VoiceBrain.AWAKE_MS + 100)
        assertEquals(listOf("wake", "nothing"), r.events)
    }

    @Test fun countdownNeedsNoWakePhrase() {
        val (b, r) = brain()
        b.setCountdown(true, 0)
        b.partial("i am", 500)
        b.partial("i am okay", 900)
        assertEquals(listOf("CANCEL"), r.events)
        b.final(listOf("i am okay"), 1_200)
        assertEquals(listOf("CANCEL"), r.events)
    }

    @Test fun countdownHelpSendsNow() {
        val (b, r) = brain()
        b.setCountdown(true, 0)
        b.final(listOf("help"), 1_000, VoiceBrain.COMMANDS)
        assertEquals(listOf("SOS"), r.events)
    }

    @Test fun emergencyTwiceWithoutWake() {
        val (b, r) = brain()
        b.partial("emergency emergency", 800)
        assertEquals(listOf("SOS"), r.events)
    }

    @Test fun chatterDoesNothing() {
        val (b, r) = brain()
        for (t in listOf("so i told him to take a look", "the doctor said it is fine", "turn left after the signal", "help me carry this")) {
            b.partial(t, 0)
            b.final(listOf(t), 0)
        }
        assertEquals(emptyList<String>(), r.events)
    }

    private fun trained(): Pair<VoiceBrain, Rec> {
        val r = Rec()
        return VoiceBrain(WakeMatcher("Hey Doku", listOf("hey doug", "i do care")), r) to r
    }

    private fun kw(vararg w: Pair<String, Double>): List<VoiceBrain.Word> =
        w.mapIndexed { i, (t, c) -> VoiceBrain.Word(t, c, i * 0.4, i * 0.4 + 0.35) }

    @Test fun keywordSpotterWakeAndSos() {
        val (b, r) = trained()
        b.keywords(kw("hey" to 0.9, "doug" to 0.95, "emergency" to 0.9), 1_400)
        assertEquals(listOf("wake", "SOS"), r.events)
    }

    @Test fun keywordSpotterWakesAndReplaysTheCommand() {
        val r = Rec()
        val b = VoiceBrain(WakeMatcher("Hey Doku", listOf("hey doug", "i do care")), r)
        b.keywords(kw("i" to 0.9, "do" to 0.9, "care" to 0.85, "[unk]" to 0.5, "[unk]" to 0.5), 2_000)
        assertEquals(listOf("wake", "replay@1.15"), r.events)
        b.final(listOf("next song"), 2_300, VoiceBrain.COMMANDS)
        assertEquals(listOf("wake", "replay@1.15", "NEXT"), r.events)
    }

    @Test fun keywordSpotterIgnoresUnsureWords() {
        val (b, r) = trained()
        b.keywords(kw("hey" to 0.9, "doug" to 0.3), 900)
        b.keywords(kw("emergency" to 0.35, "emergency" to 0.45), 1_900)
        assertEquals(emptyList<String>(), r.events)
        b.keywords(kw("emergency" to 0.9, "emergency" to 0.95), 2_900)
        assertEquals(listOf("SOS"), r.events)
    }

    @Test fun keywordSpotterSingleSosWordNeedsWake() {
        val (b, r) = trained()
        b.keywords(kw("emergency" to 0.95), 900)
        b.keywords(kw("help" to 0.95), 1_900)
        assertEquals(emptyList<String>(), r.events)
        // Partials from the spotter are never acted on.
        b.partial("emergency emergency", 2_400, VoiceBrain.KEYWORDS)
        assertEquals(emptyList<String>(), r.events)
    }

    @Test fun commandRecogniserSosWaitsForTheEnd() {
        val (b, r) = brain()
        b.final(listOf("hey doku"), 1_000)
        // A grammar recogniser can force engine noise into "accident" mid-utterance.
        b.partial("accident", 1_400, VoiceBrain.COMMANDS)
        assertEquals(listOf("wake"), r.events)
        b.final(listOf("next song"), 2_000, VoiceBrain.COMMANDS)
        assertEquals(listOf("wake", "NEXT"), r.events)
    }
}
