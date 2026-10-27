package com.rodeo.hud.voice

/**
 * Sound-alike keys for words, so "hey doku" still matches when the recogniser writes "hey do
 * coo" or "a doku". Voiced and unvoiced pairs (d/t, b/p, g/k, v/f/w, z/s) and vowel colours are
 * merged because those are exactly what speech engines and Indian accents swap.
 */
object Phonetic {
    fun words(text: String): List<String> =
        text.lowercase().replace("'", "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    fun key(word: String): String {
        var w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        if (w.startsWith("kn") || w.startsWith("gn")) w = w.drop(1)
        if (w.startsWith("wr")) w = w.drop(1)
        if (w.startsWith("wh")) w = "w" + w.drop(2)
        w = w.replace("x", "ks").replace("tch", "C").replace("ch", "C").replace("sh", "S").replace("ph", "f")
            .replace("th", "t").replace("ck", "k").replace("qu", "kw").replace("gh", "")
        val out = StringBuilder()
        for ((i, ch) in w.withIndex()) {
            val next = w.getOrNull(i + 1)
            val c = when (ch) {
                'a', 'e', 'i', 'o', 'u', 'y' -> 'a'
                'b' -> 'p'
                'd' -> 't'
                'g' -> if (next == 'e' || next == 'i' || next == 'y') 'c' else 'k'
                'j' -> 'c'
                'c' -> if (next == 'e' || next == 'i' || next == 'y') 's' else 'k'
                'q' -> 'k'
                'v', 'w' -> 'f'
                'z' -> 's'
                'S' -> 'x'
                'C' -> 'c'
                'h' -> if (i == 0) 'h' else continue
                else -> ch
            }
            if (out.isEmpty() || out.last() != c) out.append(c)
        }
        return out.toString()
    }

    fun phraseKey(words: List<String>): String = words.joinToString("") { key(it) }.let { collapse(it) }

    private fun collapse(s: String): String {
        val b = StringBuilder()
        for (c in s) if (b.isEmpty() || b.last() != c) b.append(c)
        return b.toString()
    }

    /** 1.0 for the same sound, falling towards 0 with each edit. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val d = levenshtein(a, b)
        return 1.0 - d.toDouble() / maxOf(a.length, b.length)
    }

    fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Does [word] sound like any of [targets]? Short words must match exactly. */
    fun soundsLike(word: String, targets: Collection<String>, min: Double = 0.8): Boolean {
        val k = key(word)
        return targets.any { t ->
            if (t == word) return@any true
            val tk = key(t)
            if (tk.length <= 3 || k.length <= 3) tk == k else similarity(k, tk) >= min
        }
    }
}
