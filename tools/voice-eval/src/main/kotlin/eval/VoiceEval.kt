package eval

import com.rodeo.hud.voice.Commands
import com.rodeo.hud.voice.Parsed
import com.rodeo.hud.voice.Phonetic
import com.rodeo.hud.voice.VoiceBrain
import com.rodeo.hud.voice.VoiceCommand
import com.rodeo.hud.voice.WakeMatcher
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Plays each test clip through the recogniser exactly the way the phone does (100 ms chunks, the
 * open recogniser always, the command recogniser from the moment it wakes) into the app's own
 * VoiceBrain, and scores what happened.
 *
 *     voice-eval MODEL_DIR CLIPS_DIR REPORT.txt RESULTS.json
 */

private const val RATE = 16_000

data class Clip(
    val file: String, val voice: String, val id: String, val phrase: String, val kind: String,
    val expect: String, val text: String, val snr: String, val speechEnd: Double, val wakeEnd: Double?,
)

data class Outcome(
    val clip: Clip, val phrase: String, val trained: Boolean,
    val wakeAt: Double?, val command: String?, val commandArg: String, val commandAt: Double?,
    val nothing: Boolean, val heard: List<String>,
) {
    val sosOk get() = command == "SOS"
    val pass: Boolean
        get() = when (clip.kind) {
            "chatter" -> wakeAt == null && command == null
            "sos", "repeat" -> command == "SOS"
            "cmd" -> command == clip.expect && (clip.expect != "NAVIGATE_TO" || "nandi" in commandArg.lowercase())
            else -> true
        }
    /** Seconds from the end of speech to the action. */
    val latency: Double? get() = commandAt?.let { it - clip.speechEnd }
}

fun main(args: Array<String>) {
    val modelDir = args[0]
    val clipsDir = File(args[1])
    val report = File(args[2])
    val json = File(args[3])
    LibVosk.setLogLevel(LogLevel.WARNINGS)
    // One model per worker thread: the desktop library crashed with a shared one.
    val models = ThreadLocal.withInitial { Model(modelDir) }
    val clips = JSONArray(File(clipsDir, "manifest.json").readText()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Clip(
                o.getString("file"), o.getString("voice"), o.getString("id"), o.getString("phrase"), o.getString("kind"),
                o.getString("expect"), o.getString("text"), o.getString("snr"), o.getDouble("speechEnd"),
                if (o.isNull("wakeEnd")) null else o.getDouble("wakeEnd"),
            )
        }
    }
    println("clips: ${clips.size}")
    val threads = System.getenv("EVAL_THREADS")?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    val pool = Executors.newFixedThreadPool(threads)

    // What each voice's training gave, the way Settings › Train does it: the open recogniser's
    // best guess for each of three takes.
    val aliases = HashMap<Pair<String, String>, List<String>>()
    clips.filter { it.kind == "train" }.groupBy { it.voice to it.phrase }.forEach { (key, takes) ->
        val heard = takes.map { c -> pool.submit<String?> { transcribe(models.get(), File(clipsDir, c.file)) } }.mapNotNull { it.get() }
        aliases[key] = heard.map { it.trim().lowercase() }.filter { Phonetic.words(it).size in 1..6 }.distinct()
    }

    val phrases = clips.map { it.phrase }.filter { it.isNotEmpty() }.distinct()
    val jobs = ArrayList<java.util.concurrent.Future<Outcome>>()
    for (c in clips) {
        if (c.kind == "train") continue
        // Chatter and "emergency emergency" are tried against every wake phrase.
        val targets = if (c.phrase.isEmpty()) phrases else listOf(c.phrase)
        for (p in targets) for (trained in listOf(false, true)) {
            val al = if (trained) aliases[c.voice to p].orEmpty() else emptyList()
            jobs += pool.submit<Outcome> { run(models.get(), File(clipsDir, c.file), c, p, trained, al) }
        }
    }
    val results = jobs.mapIndexedNotNull { i, j ->
        if ((i + 1) % 250 == 0) println("ran ${i + 1} of ${jobs.size}")
        runCatching { j.get() }.onFailure { println("run failed: ${it.cause ?: it}") }.getOrNull()
    }
    pool.shutdown()

    val out = StringBuilder()
    out.appendLine("Voice evaluation: ${results.size} runs, ${clips.size} clips")
    out.appendLine("Recogniser: Vosk desktop 0.3.45, model ${File(modelDir).name}")
    out.appendLine()
    out.appendLine("Training (what the recogniser wrote for the wake phrase):")
    aliases.toSortedMap(compareBy({ it.second }, { it.first })).forEach { (k, v) -> out.appendLine("  ${k.second} / ${k.first}: $v") }
    out.appendLine()

    fun rate(rs: List<Outcome>) = if (rs.isEmpty()) "  -  " else "%3d%% (%d/%d)".format(100 * rs.count { it.pass } / rs.size, rs.count { it.pass }, rs.size)
    for (p in phrases) for (trained in listOf(false, true)) {
        out.appendLine("== \"$p\" ${if (trained) "after training" else "untrained"}")
        out.appendLine("   %-8s %-18s %-18s %-18s %-18s %-18s".format("noise", "wake+SOS", "wake+command", "emergency x2", "chatter quiet", "false SOS"))
        for (snr in listOf("clean", "10dB", "5dB", "0dB")) {
            val rs = results.filter { it.phrase == p && it.trained == trained && it.clip.snr == snr }
            val chatter = rs.filter { it.clip.kind == "chatter" }
            out.appendLine(
                "   %-8s %-18s %-18s %-18s %-18s %-18s".format(
                    snr, rate(rs.filter { it.clip.kind == "sos" }), rate(rs.filter { it.clip.kind == "cmd" }),
                    rate(rs.filter { it.clip.kind == "repeat" }), rate(chatter),
                    "${chatter.count { it.sosOk }} of ${chatter.size}",
                ),
            )
        }
        val lat = results.filter { it.phrase == p && it.trained == trained && it.clip.kind == "sos" && it.sosOk }.mapNotNull { it.latency }.sorted()
        if (lat.isNotEmpty()) out.appendLine("   SOS reaction after the last word: median %.2f s, slowest %.2f s".format(lat[lat.size / 2], lat.last()))
        out.appendLine()
    }
    out.appendLine("By voice (trained, all noise levels): wake+SOS / wake+command / chatter quiet")
    for (v in clips.map { it.voice }.distinct()) {
        val rs = results.filter { it.trained && it.clip.voice == v }
        out.appendLine("   %-22s %s  %s  %s".format(v, rate(rs.filter { it.clip.kind == "sos" }), rate(rs.filter { it.clip.kind == "cmd" }), rate(rs.filter { it.clip.kind == "chatter" })))
    }
    out.appendLine()
    out.appendLine("Failures (trained):")
    results.filter { it.trained && !it.pass }.sortedWith(compareBy({ it.phrase }, { it.clip.kind }, { it.clip.id }, { it.clip.snr })).forEach { r ->
        out.appendLine("  [${r.phrase}] ${r.clip.voice} ${r.clip.id} ${r.clip.snr}: said \"${r.clip.text}\" -> " +
            (if (r.wakeAt != null) "woke " else "") + (r.command?.let { "$it ${r.commandArg}".trim() } ?: if (r.nothing) "nothing understood" else "no action") +
            " | heard ${r.heard.joinToString(" / ") { "\"$it\"" }}")
    }
    report.parentFile?.mkdirs()
    report.writeText(out.toString())
    println(out)

    json.writeText(JSONArray().apply {
        results.forEach { r ->
            put(JSONObject().apply {
                put("file", r.clip.file); put("phrase", r.phrase); put("trained", r.trained); put("kind", r.clip.kind)
                put("snr", r.clip.snr); put("pass", r.pass); put("command", r.command ?: JSONObject.NULL)
                put("wakeAt", r.wakeAt ?: JSONObject.NULL); put("heard", JSONArray(r.heard))
            })
        }
    }.toString(1))
}

private fun samples(f: File): ShortArray {
    RandomAccessFile(f, "r").use { raf ->
        val bytes = ByteArray(raf.length().toInt())
        raf.readFully(bytes)
        // Plain 44-byte PCM header from gen.py.
        val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(bb.remaining()).also { bb.get(it) }
    }
}

// The phone build also shortens the end-of-speech timeout; the desktop library has no call for it,
// so finals come a little later here than on the phone. Partial results, which SOS acts on, are
// the same.
private fun newOpen(model: Model): Recognizer = Recognizer(model, RATE.toFloat()).apply { setMaxAlternatives(3) }

private fun alternatives(json: String): List<String> = runCatching {
    val o = JSONObject(json)
    val alts = o.optJSONArray("alternatives")
    if (alts != null) (0 until alts.length()).map { alts.getJSONObject(it).optString("text").trim() }.filter { it.isNotEmpty() && it != "[unk]" }
    else listOf(o.optString("text").trim()).filter { it.isNotEmpty() && it != "[unk]" }
}.getOrDefault(emptyList())

private fun partial(json: String) = runCatching { JSONObject(json).optString("partial") }.getOrDefault("").trim()

/** Training: the best guess for one take. */
private fun transcribe(model: Model, f: File): String? {
    val pcm = samples(f)
    newOpen(model).use { r ->
        var last: String? = null
        var i = 0
        while (i < pcm.size) {
            val n = minOf(RATE / 10, pcm.size - i)
            if (r.acceptWaveForm(pcm.copyOfRange(i, i + n), n)) alternatives(r.result).firstOrNull()?.let { last = it }
            i += n
        }
        alternatives(r.finalResult).firstOrNull()?.let { last = it }
        return last
    }
}

private val grammar = JSONArray().apply { Commands.GRAMMAR.forEach { put(it) }; put("[unk]") }.toString()

private fun run(model: Model, f: File, c: Clip, phrase: String, trained: Boolean, aliases: List<String>): Outcome {
    val pcm = samples(f)
    var wakeAt: Double? = null
    var command: Parsed? = null
    var commandAt: Double? = null
    var nothing = false
    var commandsOn = false
    var commandsReset = false
    var openReset = false
    val heard = ArrayList<String>()
    var now = 0L
    lateinit var brain: VoiceBrain
    brain = VoiceBrain(WakeMatcher(phrase, aliases), object : VoiceBrain.Output {
        override fun onWake() {
            if (wakeAt == null) wakeAt = now / 1000.0
            commandsOn = true
            commandsReset = true
        }
        override fun onCommand(p: Parsed, heard: String) {
            if (command == null) { command = p; commandAt = now / 1000.0 }
            commandsOn = brain.mode == VoiceBrain.Mode.COUNTDOWN
            openReset = true
        }
        override fun onNothing(heard: String, final: Boolean) { if (final) nothing = true }
    })
    val open = newOpen(model)
    var cmd: Recognizer? = null
    var lastPartial = ""
    var lastCmdPartial = ""
    val chunk = RATE / 10
    var i = 0
    try {
        while (i < pcm.size && command == null) {
            val n = minOf(chunk, pcm.size - i)
            val data = pcm.copyOfRange(i, i + n)
            i += n
            now = i * 1000L / RATE
            if (openReset) { openReset = false; open.reset(); lastPartial = "" }
            if (open.acceptWaveForm(data, n)) {
                val alts = alternatives(open.result)
                if (alts.isNotEmpty()) heard += alts.first()
                brain.final(alts, now, VoiceBrain.OPEN)
                lastPartial = ""
            } else {
                val p = partial(open.partialResult)
                if (p != lastPartial) { lastPartial = p; if (p.isNotBlank()) brain.partial(p, now, VoiceBrain.OPEN) }
            }
            if (commandsOn) {
                if (commandsReset || cmd == null) {
                    commandsReset = false
                    if (cmd == null) cmd = Recognizer(model, RATE.toFloat(), grammar) else cmd.reset()
                    lastCmdPartial = ""
                }
                val r = cmd!!
                if (r.acceptWaveForm(data, n)) {
                    val alts = alternatives(r.result)
                    if (alts.isNotEmpty()) heard += "(cmd) " + alts.first()
                    brain.final(alts, now, VoiceBrain.COMMANDS)
                    lastCmdPartial = ""
                } else {
                    val p = partial(r.partialResult)
                    if (p != lastCmdPartial) { lastCmdPartial = p; if (p.isNotBlank()) brain.partial(p, now, VoiceBrain.COMMANDS) }
                }
            }
            brain.tick(now)
        }
        if (command == null) {
            // The rider stops talking; whatever is left in the recognisers comes out.
            val alts = alternatives(open.finalResult)
            if (alts.isNotEmpty()) heard += alts.first()
            brain.final(alts, now, VoiceBrain.OPEN)
            cmd?.let { r -> alternatives(r.finalResult).also { if (it.isNotEmpty()) heard += "(cmd) " + it.first() }.let { brain.final(it, now, VoiceBrain.COMMANDS) } }
            var t = now
            while (command == null && t < now + 1_500) { t += 100; brain.tick(t) }
        }
        if (heard.isEmpty() && lastPartial.isNotBlank()) heard += "($lastPartial)"
    } finally {
        open.close()
        cmd?.close()
    }
    return Outcome(c, phrase, trained, wakeAt, command?.command?.name, command?.arg.orEmpty(), commandAt, nothing, heard)
}
