package com.rodeo.hud.nav.gmaps

import com.rodeo.hud.nav.Maneuver
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What a turn-arrow picture says, with the measurements behind it (kept for tests and the
 * diagnostics screen).
 */
data class ArrowReading(
    val maneuver: Maneuver,
    /** False when the picture had no clear arrow head, or two of them; the HUD then shows Google's own picture. */
    val confident: Boolean,
    /** Where the head points: degrees, 0 = up, positive = clockwise (right). */
    val endAngle: Float = 0f,
    /** Net signed turning from the bottom of the stem to the head, degrees; negative = left. */
    val turn: Float = 0f,
    val roundabout: Boolean = false,
    val note: String = "",
) {
    companion object {
        val NONE = ArrowReading(Maneuver.UNKNOWN, false, note = "no picture")
    }
}

/**
 * Reads the manoeuvre from the arrow picture in Google Maps' navigation notification. Google Maps
 * puts the distance and street in the notification text but the turn itself only in the picture,
 * so this is what tells a left from a U-turn.
 *
 * It works on shape alone, so it does not depend on Google's exact artwork, colours or size:
 * the picture is normalised, thinned to a one-pixel skeleton and turned into a small graph. The
 * arrow head is the junction with two short spurs (the barbs of an open "V" head, or the corners of
 * a filled triangle); the stem is followed back from the head to its far end. The angle the stem
 * enters the head at, and how much it turns on the way, give the manoeuvre. A ring (a hole in
 * the picture, or a stem that loops round) means a roundabout.
 *
 * Plain Kotlin on ARGB pixels so it runs in unit tests without Android.
 */
object ArrowReader {
    /** Working size the glyph is scaled to. */
    private const val WORK = 96
    private const val PAD = 4
    private const val N = WORK + 2 * PAD

    fun read(width: Int, height: Int, argb: IntArray): ArrowReading {
        if (width < 8 || height < 8 || argb.size < width * height) return ArrowReading.NONE
        val ink = inkCoverage(width, height, argb) ?: return ArrowReading(Maneuver.UNKNOWN, false, note = "blank picture")
        val mask = normalise(width, height, ink) ?: return ArrowReading(Maneuver.UNKNOWN, false, note = "blank picture")
        return analyse(mask)
    }

    // ---------------------------------------------------------------- pixels to a clean mask

    /** Ink amount 0..1 per pixel: alpha for a glyph on transparency, else distance from the background colour. */
    private fun inkCoverage(w: Int, h: Int, px: IntArray): FloatArray? {
        val n = w * h
        var clear = 0
        var maxA = 0
        for (i in 0 until n) {
            val a = px[i] ushr 24
            if (a < 32) clear++
            if (a > maxA) maxA = a
        }
        if (maxA < 40) return null
        val out = FloatArray(n)
        if (clear >= n * 0.12f) {
            // Glyph on a transparent background. Secondary (dimmed) branches fall under the cut.
            for (i in 0 until n) out[i] = (px[i] ushr 24) / maxA.toFloat()
            // A badge (arrow drawn inside an opaque disc or tile): read the colour inside it instead.
            if (solidity(w, h, out) > 0.6f) {
                val bg = dominantInk(px, out)
                val inside = FloatArray(n)
                fillDistance(px, bg, inside)
                for (i in 0 until n) inside[i] = if (out[i] > 0.5f) inside[i] else 0f
                val m = inside.maxOrNull() ?: 0f
                if (m <= 0.05f) return null
                for (i in 0 until n) inside[i] /= m
                return inside
            }
            return out
        }
        // Opaque picture: the background is the commonest colour round the edge.
        var bg = borderColour(w, h, px)
        fillDistance(px, bg, out)
        // A badge (arrow inside a filled circle or square): the badge becomes the background.
        val fraction = out.count { it > 0.5f } / n.toFloat()
        if (fraction > 0.5f) {
            bg = dominantInk(px, out)
            val second = FloatArray(n)
            fillDistance(px, bg, second)
            for (i in 0 until n) out[i] = min(out[i], second[i])
            val m = out.maxOrNull() ?: 0f
            if (m <= 0.05f) return null
            for (i in 0 until n) out[i] /= m
        }
        return out
    }

    /** Share of the ink's bounding box that is ink: about 0.8 for a disc, well under 0.5 for an arrow. */
    private fun solidity(w: Int, h: Int, ink: FloatArray): Float {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var count = 0
        for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x] > 0.5f) {
            count++
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return 0f
        return count / ((x1 - x0 + 1) * (y1 - y0 + 1)).toFloat()
    }

    private fun fillDistance(px: IntArray, bg: Int, out: FloatArray) {
        var maxD = 0f
        for (i in px.indices) {
            val d = colourDistance(px[i], bg)
            out[i] = d
            if (d > maxD) maxD = d
        }
        if (maxD < 0.08f) { out.fill(0f); return }
        for (i in out.indices) out[i] = out[i] / maxD
    }

    private fun colourDistance(a: Int, b: Int): Float {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat()) / 441.7f
    }

    private fun borderColour(w: Int, h: Int, px: IntArray): Int {
        val counts = HashMap<Int, Int>()
        fun add(c: Int) { val k = c and 0xF0F0F0; counts[k] = (counts[k] ?: 0) + 1 }
        for (x in 0 until w) { add(px[x]); add(px[(h - 1) * w + x]) }
        for (y in 0 until h) { add(px[y * w]); add(px[y * w + w - 1]) }
        val key = counts.maxByOrNull { it.value }!!.key
        // Average the real colours in that bucket.
        var r = 0L; var g = 0L; var b = 0L; var k = 0
        fun take(c: Int) { if (c and 0xF0F0F0 == key) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; k++ } }
        for (x in 0 until w) { take(px[x]); take(px[(h - 1) * w + x]) }
        for (y in 0 until h) { take(px[y * w]); take(px[y * w + w - 1]) }
        return (0xFF shl 24) or ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
    }

    private fun dominantInk(px: IntArray, ink: FloatArray): Int {
        val counts = HashMap<Int, Int>()
        for (i in px.indices) if (ink[i] > 0.5f) { val k = px[i] and 0xF0F0F0; counts[k] = (counts[k] ?: 0) + 1 }
        val key = counts.maxByOrNull { it.value }!!.key
        var r = 0L; var g = 0L; var b = 0L; var k = 0
        for (i in px.indices) if (ink[i] > 0.5f && px[i] and 0xF0F0F0 == key) { r += (px[i] shr 16) and 0xFF; g += (px[i] shr 8) and 0xFF; b += px[i] and 0xFF; k++ }
        return (0xFF shl 24) or ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
    }

    /** Crops to the ink, scales it to fit [WORK] keeping its shape, and thresholds at half coverage. */
    private fun normalise(w: Int, h: Int, ink: FloatArray): BooleanArray? {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if (ink[y * w + x] > 0.35f) {
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        if (x1 < 0) return null
        // One source pixel of margin so edges interpolate down to nothing.
        val bx0 = (x0 - 1).coerceAtLeast(0); val by0 = (y0 - 1).coerceAtLeast(0)
        val bx1 = (x1 + 1).coerceAtMost(w - 1); val by1 = (y1 + 1).coerceAtMost(h - 1)
        val bw = (bx1 - bx0 + 1).toFloat(); val bh = (by1 - by0 + 1).toFloat()
        val scale = WORK / max(bw, bh)
        val ox = PAD + (WORK - bw * scale) / 2f
        val oy = PAD + (WORK - bh * scale) / 2f
        val mask = BooleanArray(N * N)
        fun sample(sx: Float, sy: Float): Float {
            val fx = (sx - 0.5f).coerceIn(0f, w - 1f); val fy = (sy - 0.5f).coerceIn(0f, h - 1f)
            val ix = fx.toInt(); val iy = fy.toInt()
            val jx = min(ix + 1, w - 1); val jy = min(iy + 1, h - 1)
            val tx = fx - ix; val ty = fy - iy
            val a = ink[iy * w + ix] * (1 - tx) + ink[iy * w + jx] * tx
            val b = ink[jy * w + ix] * (1 - tx) + ink[jy * w + jx] * tx
            return a * (1 - ty) + b * ty
        }
        var any = false
        for (y in 0 until N) for (x in 0 until N) {
            val sx = bx0 + (x + 0.5f - ox) / scale
            val sy = by0 + (y + 0.5f - oy) / scale
            if (sx < bx0 || sy < by0 || sx > bx1 + 1 || sy > by1 + 1) continue
            if (sample(sx, sy) >= 0.5f) { mask[y * N + x] = true; any = true }
        }
        return if (any) mask else null
    }

    // ---------------------------------------------------------------- shape analysis

    private data class P(val x: Int, val y: Int)

    private class Edge(val pts: List<P>, val a: Int, val b: Int) {
        /** Node ids at each end; ids >= 0 are junctions, -1 is a free end (endpoint). */
        val length: Float = pathLength(pts)
    }

    private class Prepared(val mask: BooleanArray, val skel: BooleanArray, val stroke: Float, val graph: Graph, val holes: Int, val dist: FloatArray)

    /** Mask without hairlines, its skeleton and graph. */
    private fun prepare(raw: BooleanArray): Prepared? {
        var mask = raw
        var stroke = strokeOf(mask) ?: return null
        // Opening: lines much thinner than the arrow (outlines of greyed-out roads) go.
        val r = stroke * 0.32f
        if (r >= 1f) {
            val d = distanceTransform(mask)
            val core = BooleanArray(N * N) { d[it] >= r }
            val toCore = distanceTransform(BooleanArray(N * N) { !core[it] })
            val opened = BooleanArray(N * N) { mask[it] && toCore[it] <= r + 0.75f }
            if (opened.count { it } > mask.count { it } * 0.4f) {
                mask = opened
                stroke = strokeOf(mask) ?: stroke
            }
        }
        val skel = thin(mask.copyOf())
        prune(skel, stroke * 0.6f)
        return Prepared(mask, skel, stroke, buildGraph(skel, stroke), countHoles(mask, stroke), distanceTransform(mask))
    }

    /** Typical stroke width: from the skeleton's distance to the edge (upper quartile, so hairlines don't drag it down). */
    private fun strokeOf(mask: BooleanArray): Float? {
        val dist = distanceTransform(mask)
        val skel = thin(mask.copyOf())
        val d = ArrayList<Float>()
        for (i in skel.indices) if (skel[i]) d += dist[i]
        if (d.size < 6) return null
        d.sort()
        return max(2f, 2f * d[(d.size * 0.7f).toInt().coerceAtMost(d.size - 1)])
    }

    /** For tests: the skeleton and graph drawn as text. */
    fun debugDump(width: Int, height: Int, argb: IntArray): String {
        val ink = inkCoverage(width, height, argb) ?: return "no ink"
        val raw = normalise(width, height, ink) ?: return "no mask"
        val p = prepare(raw) ?: return "no shape"
        val g = p.graph
        val sb = StringBuilder("stroke=${p.stroke} nodes=${g.nodes.size} holes=${p.holes}\n")
        val nodePx = HashMap<Int, Int>(); g.nodes.forEachIndexed { k, c -> c.forEach { nodePx[it] = k } }
        for (y in 0 until N) {
            val line = StringBuilder()
            for (x in 0 until N) {
                val i = y * N + x
                line.append(when { nodePx[i] != null -> ('A' + nodePx[i]!! % 26); p.skel[i] -> '#'; p.mask[i] -> '.'; else -> ' ' })
            }
            if (line.isNotBlank()) sb.append(line.trimEnd()).append('\n')
        }
        g.edges.forEach { sb.append("edge ${it.a}->${it.b} len=${"%.1f".format(it.length)} from=${it.pts.first()} to=${it.pts.last()}\n") }
        return sb.toString()
    }

    private class Head(val node: Int, val stem: Edge, val nodes: Set<Int>)

    private fun analyse(raw: BooleanArray): ArrowReading {
        val p = prepare(raw) ?: return ArrowReading(Maneuver.UNKNOWN, false, note = "no shape")
        val graph = p.graph
        val stroke = p.stroke
        val holes = p.holes

        val heads = findHeads(graph, stroke, p.dist)
        if (heads.isEmpty()) {
            return ArrowReading(Maneuver.UNKNOWN, false, roundabout = holes > 0, note = "no arrow head (holes=$holes)")
        }
        // Read each head; with more than one, the one that turns most is the manoeuvre.
        var best: ArrowReading? = null
        for (h in heads) {
            val r = readHead(graph, h.node, listOf(h.stem), stroke, holes, p.dist) ?: continue
            if (best == null || abs(r.turn) > abs(best.turn) + 5f) best = r
        }
        val reading = best ?: return ArrowReading(Maneuver.UNKNOWN, false, roundabout = holes > 0, note = "no stem")
        return if (heads.size > 1) reading.copy(confident = false, note = reading.note + "; ${heads.size} heads") else reading
    }

    /**
     * A head is a node where, cutting off the stem, what is left is a small tree with at least two
     * loose ends: the two barbs of a "V" head, or the corners of a filled triangle.
     */
    private fun findHeads(graph: Graph, stroke: Float, dist: FloatArray): List<Head> {
        val found = ArrayList<Head>()
        for (j in graph.nodes.indices) {
            val c = centre(graph.nodes[j])
            for (stem in graph.edges.filter { it.a == j || it.b == j }) {
                if (stem.a == stem.b) continue
                // Walk the side away from the stem.
                val nodes = HashSet<Int>().apply { add(j) }
                val usedEdges = HashSet<Edge>().apply { add(stem) }
                val queue = ArrayDeque<Int>().apply { add(j) }
                var leaves = 0
                var extent = 0f
                var length = 0f
                var ok = true
                while (queue.isNotEmpty() && ok) {
                    val n = queue.removeFirst()
                    for (e in graph.edges) {
                        if (e in usedEdges || (e.a != n && e.b != n)) continue
                        usedEdges += e
                        length += e.length
                        for (pt in e.pts) extent = max(extent, hypot((pt.x - c.x).toFloat(), (pt.y - c.y).toFloat()))
                        val other = if (e.a == n) e.b else e.a
                        // Inside a head, junctions sit close together; a long link means this is a road, not a head.
                        if (other >= 0 && e.length > stroke * 1.6f) ok = false
                        when {
                            other == -1 -> leaves++
                            other == n || other in nodes -> ok = false // a loop: not a head
                            else -> { nodes += other; queue += other }
                        }
                    }
                }
                if (!ok || leaves < 2 || extent > 4.8f * stroke) continue
                // A real head is wider than the stem (filled triangle) or has long barbs (open "V").
                // The little fork thinning leaves at a square stem end is neither.
                var widest = 0f
                var longestLeaf = 0f
                for (e in usedEdges) {
                    if (e === stem) continue
                    for (pt in e.pts) widest = max(widest, dist[pt.y * N + pt.x])
                    if (e.a == -1 || e.b == -1) longestLeaf = max(longestLeaf, e.length)
                }
                if (widest < stroke * 0.7f && longestLeaf < stroke * 1.35f) continue
                // Barbs (or a triangle's back corners) sweep back along the stem, one each side.
                // Where two roads meet (a fork, a ramp) the loose ends point away from the stem.
                val back = stemDirection(stem, j, stroke) ?: continue
                var leftSide = 0
                var rightSide = 0
                for (e in usedEdges) {
                    if (e === stem || (e.a != -1 && e.b != -1)) continue
                    val end = if (e.a == -1) e.pts.first() else e.pts.last()
                    val dx = (end.x - c.x).toFloat(); val dy = (end.y - c.y).toFloat()
                    val l = hypot(dx, dy)
                    if (l < 1f) continue
                    val cos = (dx * back.first + dy * back.second) / l
                    if (cos < 0.17f) continue // more than ~80 degrees off the stem
                    val cross = back.first * dy - back.second * dx
                    if (cross < 0) leftSide++ else rightSide++
                }
                if (leftSide == 0 || rightSide == 0) continue
                // The stem side must be clearly longer than the head.
                val stemReach = reach(graph, j, stem)
                if (stemReach < max(1.3f * extent, 2.2f * stroke)) continue
                found += Head(j, stem, nodes)
            }
        }
        // A head found twice (an inner junction of the same triangle): keep the outermost.
        return found.filter { h -> found.none { o -> o !== h && h.node in o.nodes && o.nodes.size > h.nodes.size } }
            .distinctBy { it.node }
    }

    /** Unit direction from node [j] out along [stem], over a couple of stroke widths. */
    private fun stemDirection(stem: Edge, j: Int, stroke: Float): Pair<Float, Float>? {
        val pts = if (stem.a == j) stem.pts else stem.pts.asReversed()
        val total = pathLength(pts)
        return segmentDirection(pts, 0f, min(total, stroke * 2.5f))
    }

    /** How far the skeleton runs from node [j] out along [stem] (longest simple walk, bounded). */
    private fun reach(graph: Graph, j: Int, stem: Edge): Float {
        var best = 0f
        fun walk(node: Int, via: Edge, acc: Float, seen: Set<Edge>, depth: Int) {
            val total = acc + via.length
            if (total > best) best = total
            val next = if (via.a == node) via.b else via.a
            if (next < 0 || depth > 12) return
            for (e in graph.edges) if ((e.a == next || e.b == next) && e !in seen) walk(next, e, total, seen + e, depth + 1)
        }
        walk(j, stem, 0f, setOf(stem), 0)
        return best
    }

    private fun readHead(graph: Graph, node: Int, stems: List<Edge>, stroke: Float, holes: Int, dist: FloatArray): ArrowReading? {
        // Follow the stem from the head back to its far end, going straight on at junctions.
        val path = ArrayList<P>()
        var current = node
        var edge = stems.maxByOrNull { it.length } ?: return null
        val used = HashSet<Edge>()
        val visited = HashSet<Int>().apply { add(node) }
        var guard = 0
        while (guard++ < 40) {
            used += edge
            val forward = if (edge.a == current) edge.pts else edge.pts.asReversed()
            if (path.isNotEmpty()) path.addAll(forward.drop(1)) else path.addAll(forward)
            val next = if (edge.a == current) edge.b else edge.a
            if (next < 0 || next in visited) break
            visited += next
            current = next
            val dir = direction(path, path.size - 1, stroke * 2.5f, backwards = true) ?: break
            val options = graph.edges.filter { (it.a == current || it.b == current) && it !in used }
            if (options.isEmpty()) break
            edge = options.minByOrNull { e ->
                val pts = if (e.a == current) e.pts else e.pts.asReversed()
                val d = direction(pts, 0, stroke * 2.5f, backwards = false)
                val straightness = if (d == null) 3f else 1f - (dir.first * d.first + dir.second * d.second)
                // Prefer real roads over little stubs.
                straightness + if (e.length < stroke * 2f) 1f else 0f
            }!!
        }
        if (path.size < 4) return null
        val headPt = path.first()
        val total = pathLength(path)
        if (total < stroke * 2.5f) return null

        // Into the head: the stem just behind it. Skip the head itself (where the shape is wider
        // than the stem), then look a short way along, before any bend further back.
        var exit = 0f
        run {
            var acc = 0f
            for (i in 1 until path.size) {
                acc += hypot((path[i].x - path[i - 1].x).toFloat(), (path[i].y - path[i - 1].y).toFloat())
                if (dist[path[i].y * N + path[i].x] <= stroke * 0.62f) { exit = acc; break }
                if (acc > stroke * 4f) { exit = acc; break }
            }
        }
        val skip = min(max(exit, stroke * 0.5f) + stroke * 0.25f, total * 0.4f)
        val span = min(stroke * 1.8f, total * 0.35f)
        val inDir = segmentDirection(path, skip, skip + span) ?: return null
        val endAngle = heading(-inDir.first, -inDir.second)

        // Base to head: headings every ~stroke along the path, unwrapped.
        val rev = path.asReversed()
        val step = max(2f, stroke * 1.0f)
        val headings = ArrayList<Float>()
        var s = 0f
        val endLimit = total - skip
        while (s + 2 * step <= endLimit) {
            segmentDirection(rev, s, s + 2 * step)?.let { headings += heading(it.first, it.second) }
            s += step
        }
        headings += endAngle
        val unwrapped = FloatArray(headings.size)
        var totalAbs = 0f
        for (i in headings.indices) {
            if (i == 0) { unwrapped[0] = headings[0]; continue }
            var d = headings[i] - headings[i - 1]
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            unwrapped[i] = unwrapped[i - 1] + d
            totalAbs += abs(d)
        }
        val turn = unwrapped.last() - unwrapped.first()
        val base = path.last()
        val roundabout = holes > 0 || totalAbs > 250f
        val note = "end=%.0f turn=%.0f abs=%.0f holes=%d stroke=%.1f len=%.0f base=%s head=%s h=%s".format(endAngle, turn, totalAbs, holes, stroke, total, base, headPt, headings.joinToString(",") { "%.0f".format(it) })
        val maneuver = classify(endAngle, turn, roundabout, headPt, base)
        // Near a class boundary the picture could be either: say so.
        val edgeCase = if (roundabout) false else nearBoundary(abs(endAngle))
        return ArrowReading(maneuver, !edgeCase, endAngle, turn, roundabout, note)
    }

    private fun classify(endAngle: Float, turn: Float, roundabout: Boolean, head: P, base: P): Maneuver {
        if (roundabout) {
            val e = endAngle
            return when {
                abs(e) <= 35f -> Maneuver.ROUNDABOUT_STRAIGHT
                abs(e) >= 155f -> Maneuver.ROUNDABOUT_UTURN
                e < 0 -> Maneuver.ROUNDABOUT_LEFT
                else -> Maneuver.ROUNDABOUT_RIGHT
            }
        }
        // Turn pictures always start with the rider at the bottom heading up, so the way the head
        // points is the turn. Only for a U-turn (head pointing back down) does the side need the
        // path: which way it turned on the way round, or failing that, which side the head is on.
        val a = abs(endAngle)
        val left = endAngle < 0
        return when {
            a < 22f -> {
                // Straight on, but beside where it started: a fork or keep.
                val offset = (head.x - base.x) / WORK.toFloat()
                when {
                    offset < -0.2f -> Maneuver.KEEP_LEFT
                    offset > 0.2f -> Maneuver.KEEP_RIGHT
                    else -> Maneuver.STRAIGHT
                }
            }
            a < 65f -> if (left) Maneuver.SLIGHT_LEFT else Maneuver.SLIGHT_RIGHT
            a < 118f -> if (left) Maneuver.LEFT else Maneuver.RIGHT
            a < 158f -> if (left) Maneuver.SHARP_LEFT else Maneuver.SHARP_RIGHT
            else -> {
                val uLeft = if (abs(turn) >= 120f) turn < 0 else head.x < base.x
                if (uLeft) Maneuver.UTURN_LEFT else Maneuver.UTURN_RIGHT
            }
        }
    }

    private fun nearBoundary(a: Float): Boolean = listOf(22f, 65f, 118f, 158f).any { abs(a - it) < 6f }

    /** 0 = up, clockwise positive, -180..180. */
    private fun heading(dx: Float, dy: Float): Float = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()

    /** Unit direction from the point at arc length [from] to the one at [to] along [pts]. */
    private fun segmentDirection(pts: List<P>, from: Float, to: Float): Pair<Float, Float>? {
        val a = pointAt(pts, from) ?: return null
        val b = pointAt(pts, to) ?: pts.last().let { it.x.toFloat() to it.y.toFloat() }
        val dx = b.first - a.first; val dy = b.second - a.second
        val l = hypot(dx, dy)
        if (l < 1e-3f) return null
        return dx / l to dy / l
    }

    private fun pointAt(pts: List<P>, s: Float): Pair<Float, Float>? {
        if (pts.isEmpty()) return null
        var acc = 0f
        for (i in 1 until pts.size) {
            val d = hypot((pts[i].x - pts[i - 1].x).toFloat(), (pts[i].y - pts[i - 1].y).toFloat())
            if (acc + d >= s) {
                val t = if (d == 0f) 0f else (s - acc) / d
                return (pts[i - 1].x + (pts[i].x - pts[i - 1].x) * t) to (pts[i - 1].y + (pts[i].y - pts[i - 1].y) * t)
            }
            acc += d
        }
        return null
    }

    /** Direction of travel at index [i] of [pts], looking [reach] along it (backwards = towards index 0). */
    private fun direction(pts: List<P>, i: Int, reach: Float, backwards: Boolean): Pair<Float, Float>? {
        if (pts.size < 2) return null
        var j = i
        var acc = 0f
        while (true) {
            val k = if (backwards) j - 1 else j + 1
            if (k < 0 || k >= pts.size) break
            acc += hypot((pts[k].x - pts[j].x).toFloat(), (pts[k].y - pts[j].y).toFloat())
            j = k
            if (acc >= reach) break
        }
        if (j == i) return null
        val from = pts[j]; val to = pts[i]
        val (dx, dy) = if (backwards) (to.x - from.x).toFloat() to (to.y - from.y).toFloat() else (from.x - to.x).toFloat() to (from.y - to.y).toFloat()
        val l = hypot(dx, dy)
        return if (l < 1e-3f) null else dx / l to dy / l
    }

    // ---------------------------------------------------------------- image operations

    /** Chamfer distance (1, √2) from each ink pixel to the nearest background pixel. */
    private fun distanceTransform(mask: BooleanArray): FloatArray {
        val inf = 1e6f
        val d = FloatArray(N * N) { if (mask[it]) inf else 0f }
        val r2 = 1.4142f
        for (y in 0 until N) for (x in 0 until N) {
            val i = y * N + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1)
            if (y > 0) {
                v = min(v, d[i - N] + 1)
                if (x > 0) v = min(v, d[i - N - 1] + r2)
                if (x < N - 1) v = min(v, d[i - N + 1] + r2)
            }
            d[i] = v
        }
        for (y in N - 1 downTo 0) for (x in N - 1 downTo 0) {
            val i = y * N + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x < N - 1) v = min(v, d[i + 1] + 1)
            if (y < N - 1) {
                v = min(v, d[i + N] + 1)
                if (x < N - 1) v = min(v, d[i + N + 1] + r2)
                if (x > 0) v = min(v, d[i + N - 1] + r2)
            }
            d[i] = v
        }
        return d
    }

    /** Zhang-Suen thinning down to a one-pixel skeleton. */
    private fun thin(img: BooleanArray): BooleanArray {
        val remove = ArrayList<Int>()
        var changed = true
        var iter = 0
        while (changed && iter++ < 200) {
            changed = false
            for (pass in 0..1) {
                remove.clear()
                for (y in 1 until N - 1) for (x in 1 until N - 1) {
                    val i = y * N + x
                    if (!img[i]) continue
                    val p2 = img[i - N]; val p3 = img[i - N + 1]; val p4 = img[i + 1]; val p5 = img[i + N + 1]
                    val p6 = img[i + N]; val p7 = img[i + N - 1]; val p8 = img[i - 1]; val p9 = img[i - N - 1]
                    val ring = booleanArrayOf(p2, p3, p4, p5, p6, p7, p8, p9)
                    val b = ring.count { it }
                    if (b < 2 || b > 6) continue
                    var a = 0
                    for (k in 0 until 8) if (!ring[k] && ring[(k + 1) % 8]) a++
                    if (a != 1) continue
                    if (pass == 0) {
                        if (p2 && p4 && p6) continue
                        if (p4 && p6 && p8) continue
                    } else {
                        if (p2 && p4 && p8) continue
                        if (p2 && p6 && p8) continue
                    }
                    remove += i
                }
                if (remove.isNotEmpty()) changed = true
                for (i in remove) img[i] = false
            }
        }
        // Drop redundant pixels (staircase corners): ones whose neighbours already join up without them.
        // Repeated, so two-pixel-thick diagonal runs end up one pixel wide. Real junctions stay.
        var again = true
        var rounds = 0
        while (again && rounds++ < 6) {
            again = false
            for (y in 1 until N - 1) for (x in 1 until N - 1) {
                val i = y * N + x
                if (!img[i]) continue
                val nb = neighbours(img, i)
                if (nb >= 2 && branches(img, i) == 1 && crossings(img, i) < 3) { img[i] = false; again = true }
            }
        }
        return img
    }

    /** How many separate branches leave a skeleton pixel: groups of touching neighbours. */
    private fun branches(img: BooleanArray, i: Int): Int {
        val set = OFFSETS.filter { img[i + it] }
        if (set.size <= 1) return set.size
        val group = IntArray(set.size) { it }
        fun find(k: Int): Int { var r = k; while (group[r] != r) r = group[r]; return r }
        for (a in set.indices) for (b in a + 1 until set.size) {
            val pa = set[a]; val pb = set[b]
            val ax = (pa + N + 1) % N - 1; val ay = (pa - ax) / N
            val bx = (pb + N + 1) % N - 1; val by = (pb - bx) / N
            if (abs(ax - bx) <= 1 && abs(ay - by) <= 1) group[find(a)] = find(b)
        }
        return set.indices.count { find(it) == it }
    }

    private fun neighbours(img: BooleanArray, i: Int): Int {
        var c = 0
        for (o in OFFSETS) if (img[i + o]) c++
        return c
    }

    private val OFFSETS = intArrayOf(-N - 1, -N, -N + 1, -1, 1, N - 1, N, N + 1)

    /** Crossing number: separate runs of set pixels round a skeleton pixel. */
    private fun crossings(img: BooleanArray, i: Int): Int {
        val ring = intArrayOf(-N, -N + 1, 1, N + 1, N, N - 1, -1, -N - 1)
        var c = 0
        for (k in 0 until 8) if (!img[i + ring[k]] && img[i + ring[(k + 1) % 8]]) c++
        return c
    }

    /** Removes tiny spurs left by thinning (repeated, so a forked stub goes too). */
    private fun prune(img: BooleanArray, maxLen: Float) {
        repeat(2) {
            val ends = (0 until N * N).filter { img[it] && neighbours(img, it) == 1 }
            for (e in ends) {
                val chain = ArrayList<Int>()
                var cur = e
                var prev = -1
                var len = 0f
                while (true) {
                    chain += cur
                    val next = OFFSETS.map { cur + it }.filter { img[it] && it != prev && it !in chain }
                    if (next.size != 1 || neighbours(img, next[0]) >= 3) {
                        // Reached a junction (or a dead end).
                        if (next.isNotEmpty() && len < maxLen) chain.forEach { img[it] = false }
                        break
                    }
                    len += if (abs(next[0] - cur) == 1 || abs(next[0] - cur) == N) 1f else 1.4142f
                    prev = cur
                    cur = next[0]
                    if (len > maxLen) break
                }
            }
        }
    }

    private class Graph(val nodes: List<List<Int>>, val edges: List<Edge>)

    /** Junction clusters become nodes; what is left of the skeleton splits into edges between them. */
    private fun buildGraph(skel: BooleanArray, stroke: Float): Graph {
        val isJunction = BooleanArray(N * N)
        for (y in 1 until N - 1) for (x in 1 until N - 1) {
            val i = y * N + x
            if (skel[i] && neighbours(skel, i) >= 3) isJunction[i] = true
        }
        val nodeOf = IntArray(N * N) { -1 }
        val nodes = ArrayList<List<Int>>()
        for (i in skel.indices) if (isJunction[i] && nodeOf[i] < 0) {
            val cluster = ArrayList<Int>()
            val queue = ArrayDeque<Int>().apply { add(i) }
            nodeOf[i] = nodes.size
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst(); cluster += c
                for (o in OFFSETS) { val k = c + o; if (isJunction[k] && nodeOf[k] < 0) { nodeOf[k] = nodes.size; queue += k } }
            }
            nodes += cluster
        }
        val seen = BooleanArray(N * N)
        val edges = ArrayList<Edge>()
        for (i in skel.indices) {
            if (!skel[i] || isJunction[i] || seen[i]) continue
            // One edge = one connected run of non-junction skeleton pixels.
            val comp = ArrayList<Int>()
            val queue = ArrayDeque<Int>().apply { add(i) }
            seen[i] = true
            val touches = LinkedHashSet<Int>()
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst(); comp += c
                for (o in OFFSETS) {
                    val k = c + o
                    if (!skel[k]) continue
                    if (isJunction[k]) touches += nodeOf[k]
                    else if (!seen[k]) { seen[k] = true; queue += k }
                }
            }
            val ordered = order(comp, skel, isJunction)
            // Which node sits at which end.
            fun nodeNear(p: Int): Int = OFFSETS.map { p + it }.firstOrNull { isJunction[it] }?.let { nodeOf[it] } ?: -1
            val a = nodeNear(ordered.first())
            var b = nodeNear(ordered.last())
            if (ordered.size == 1 && touches.size >= 2) b = touches.elementAt(1)
            // A stub that starts and ends on the same junction is a loose end left by thinning.
            if (a >= 0 && a == b && touches.size == 1 && ordered.size <= 8) b = -1
            // Include the junction pixel at each end so lengths and directions start at the node centre.
            val pts = ArrayList<P>()
            if (a >= 0) pts += centre(nodes[a])
            ordered.forEach { pts += P(it % N, it / N) }
            if (b >= 0) pts += centre(nodes[b])
            edges += Edge(pts, a, b)
        }
        return merge(Graph(nodes, edges), stroke)
    }

    /** Junctions joined by a very short link are one junction (the knot at a head, a thick corner). */
    private fun merge(g: Graph, stroke: Float): Graph {
        val parent = IntArray(g.nodes.size) { it }
        fun find(k: Int): Int { var r = k; while (parent[r] != r) r = parent[r]; return r }
        val short = g.edges.filter { it.a >= 0 && it.b >= 0 && it.a != it.b && it.length < stroke * 0.6f }
        if (short.isEmpty()) return g
        for (e in short) parent[find(e.a)] = find(e.b)
        val roots = g.nodes.indices.map { find(it) }.distinct()
        val newId = roots.withIndex().associate { it.value to it.index }
        val nodes = roots.map { r -> g.nodes.indices.filter { find(it) == r }.flatMap { g.nodes[it] } }
        val edges = g.edges.filter { it !in short }.map { e ->
            Edge(e.pts, if (e.a >= 0) newId[find(e.a)]!! else -1, if (e.b >= 0) newId[find(e.b)]!! else -1)
        }.filter { !(it.a >= 0 && it.a == it.b && it.length < stroke) }
        return Graph(nodes, edges)
    }

    private fun centre(cluster: List<Int>): P {
        val x = cluster.sumOf { it % N } / cluster.size.toFloat()
        val y = cluster.sumOf { it / N } / cluster.size.toFloat()
        return P(x.roundToInt(), y.roundToInt())
    }

    /** Orders an edge's pixels from one end to the other (BFS from an end, then by distance). */
    private fun order(comp: List<Int>, skel: BooleanArray, isJunction: BooleanArray): List<Int> {
        if (comp.size <= 1) return comp
        val set = comp.toHashSet()
        fun bfs(from: Int): Map<Int, Int> {
            val d = HashMap<Int, Int>(); d[from] = 0
            val q = ArrayDeque<Int>().apply { add(from) }
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                for (o in OFFSETS) { val k = c + o; if (k in set && k !in d) { d[k] = d[c]!! + 1; q += k } }
            }
            return d
        }
        // An end: next to a junction or with a single neighbour; else (a loop) anywhere.
        val start = comp.firstOrNull { p -> OFFSETS.count { (p + it) in set } <= 1 }
            ?: comp.firstOrNull { p -> OFFSETS.any { isJunction[p + it] } } ?: comp.first()
        val far = bfs(start).maxByOrNull { it.value }!!.key
        val d = bfs(far)
        return comp.sortedBy { d[it] ?: Int.MAX_VALUE }
    }

    /** Background areas enclosed by ink (a roundabout ring), ignoring specks smaller than the stroke. */
    private fun countHoles(mask: BooleanArray, stroke: Float): Int {
        val seen = BooleanArray(N * N)
        val q = ArrayDeque<Int>()
        for (x in 0 until N) for (y in listOf(0, N - 1)) { val i = y * N + x; if (!mask[i] && !seen[i]) { seen[i] = true; q += i } }
        for (y in 0 until N) for (x in listOf(0, N - 1)) { val i = y * N + x; if (!mask[i] && !seen[i]) { seen[i] = true; q += i } }
        fun flood() {
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                val x = c % N; val y = c / N
                for ((dx, dy) in FOUR) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue
                    val k = ny * N + nx
                    if (!mask[k] && !seen[k]) { seen[k] = true; q += k }
                }
            }
        }
        flood()
        var holes = 0
        val minArea = max(6f, stroke * stroke * 0.8f)
        for (i in 0 until N * N) {
            if (mask[i] || seen[i]) continue
            var area = 0
            val qq = ArrayDeque<Int>().apply { add(i) }
            seen[i] = true
            while (qq.isNotEmpty()) {
                val c = qq.removeFirst(); area++
                val x = c % N; val y = c / N
                for ((dx, dy) in FOUR) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= N || ny >= N) continue
                    val k = ny * N + nx
                    if (!mask[k] && !seen[k]) { seen[k] = true; qq += k }
                }
            }
            if (area >= minArea) holes++
        }
        return holes
    }

    private val FOUR = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private fun pathLength(pts: List<P>): Float {
        var l = 0f
        for (i in 1 until pts.size) l += hypot((pts[i].x - pts[i - 1].x).toFloat(), (pts[i].y - pts[i - 1].y).toFloat())
        return l
    }
}
