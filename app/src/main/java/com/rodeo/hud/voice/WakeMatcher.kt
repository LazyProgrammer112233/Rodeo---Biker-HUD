package com.rodeo.hud.voice

/**
 * Finds the rider's wake phrase ("Hey Doku") in what the recogniser heard, by sound rather than
 * spelling, so made-up names work. [aliases] are what the recogniser wrote when the rider trained
 * the phrase in Settings ("hey do coo", "a doctor"); they make it fit that rider's voice.
 *
 * A phrase with a greeting ("hey", "ok") needs a greeting-like word followed by something that
 * sounds like the name. Requiring both keeps everyday speech ("...to take a look") from waking it.
 */
class WakeMatcher(phrase: String, aliases: List<String> = emptyList()) {
    private val targets: List<Target>

    private class Target(val greeting: Boolean, val nameWords: Int, val nameKey: String, val trained: Boolean)

    /** Where the wake phrase ended (index of the first word after it) and how close it was. */
    data class Hit(val end: Int, val score: Double)

    init {
        val all = ArrayList<Target>()
        Phonetic.words(phrase).takeIf { it.isNotEmpty() }?.let { all += target(it, trained = false) }
        aliases.map { Phonetic.words(it) }.filter { it.isNotEmpty() && it.size <= 6 }.distinct().forEach { all += target(it, trained = true) }
        targets = all.filter { it.nameKey.isNotEmpty() }
    }

    private fun target(words: List<String>, trained: Boolean): Target {
        val greeting = words.size > 1 && words.first() in GREETING_SOUNDS
        val name = if (greeting) words.drop(1) else words
        return Target(greeting, name.size, Phonetic.phraseKey(name), trained)
    }

    val isEmpty: Boolean get() = targets.isEmpty()

    fun find(text: String): Hit? = find(Phonetic.words(text))

    fun find(words: List<String>): Hit? {
        if (words.isEmpty()) return null
        var best: Hit? = null
        fun offer(end: Int, score: Double) {
            if (best == null || score > best!!.score) best = Hit(end, score)
        }
        for (t in targets) {
            val lens = (t.nameWords - 1).coerceAtLeast(1)..(t.nameWords + 1)
            // Short names must sound the same ("hey there" is not "hey doku"); longer ones may be
            // a sound or two off.
            val need = (if (t.nameKey.length <= 4) 0.99 else 0.78) - if (t.trained) 0.03 else 0.0
            if (t.greeting) {
                for (i in words.indices) {
                    if (words[i] !in GREETING_SOUNDS) continue
                    for (len in lens) {
                        if (i + 1 + len > words.size) break
                        val s = nameScore(Phonetic.phraseKey(words.subList(i + 1, i + 1 + len)), t)
                        if (s >= need) offer(i + 1 + len, s)
                    }
                }
                // Greeting and name run together: "Herodio", "Hidoko", "Hero dio", "Arodeo", "Perodio".
                for (len in 1..2) for (i in 0..words.size - len) {
                    if (words[i] in GREETING_SOUNDS) continue
                    val k = Phonetic.phraseKey(words.subList(i, i + len))
                    val rest = FUSED_GREETINGS.firstOrNull { k.startsWith(it) && k.length >= it.length + 3 }?.let { k.drop(it.length) } ?: continue
                    val s = nameScore(rest, t)
                    if (s >= maxOf(need, 0.85)) offer(i + len, s - 0.01)
                }
            }
            // The name on its own: always for phrases without a greeting; when the greeting
            // was lost ("doku, emergency") only for long names that are hard to hit by chance.
            if (!t.greeting || t.nameKey.length >= 6) {
                val strict = if (t.greeting) 0.9 else maxOf(need, 0.84)
                if (t.nameKey.length < 4 && !t.greeting) continue
                for (len in lens) for (i in 0..words.size - len) {
                    val s = Phonetic.similarity(Phonetic.phraseKey(words.subList(i, i + len)), t.nameKey)
                    if (s >= strict) offer(i + len, s - 0.02)
                }
            }
        }
        return best
    }

    /** How close a heard name is, forgiving the "-er" recognisers like to add ("doker", "rodier"). */
    private fun nameScore(heard: String, t: Target): Double {
        val s = Phonetic.similarity(heard, t.nameKey)
        if (heard.length > 2 && heard.endsWith('r') && !t.nameKey.endsWith('r')) {
            return maxOf(s, Phonetic.similarity(heard.dropLast(1), t.nameKey) - 0.005)
        }
        return s
    }

    companion object {
        private val GREETINGS = setOf("hey", "hi", "hello", "ok", "okay", "oye", "arre", "hai", "yo")
        /** How recognisers write a spoken greeting. */
        private val GREETING_SOUNDS = GREETINGS + setOf("hay", "he", "hee", "a", "ay", "eh", "hei", "high", "hie", "k", "o", "pay")
        /** Sound keys of a greeting glued to the name ("herodio" = "ha" + "rata"). */
        private val FUSED_GREETINGS = listOf("ha", "pa", "a")
    }
}
