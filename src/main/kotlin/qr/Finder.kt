package qr

/**
 * Finder detection on a binarized [Layer] (qr v0.7.2 decode.ts `ratio`/`run`/`cross` L724-825,
 * refinement and template fit L828-902, pitch helpers L913-936, `scanRows.find` L1184-1269,
 * `copyPattern` L953-958). Every expression keeps the JS operand order (bit-exact doubles).
 *
 * [Pattern] and [Triple] are nested to avoid clashing with `kotlin.Triple`; other files import
 * `qr.Finder.Pattern` / `qr.Finder.Triple`.
 */
internal object Finder {
    /** decode.ts L479 `makePattern`: one finder center and its module size. */
    internal class Pattern(var x: Double = 0.1, var y: Double = 0.1, var ms: Double = 0.1)

    /** decode.ts L472-477 `ScannerTriple`: preallocated once per scanner. */
    internal class Triple(
        val tl: Pattern = Pattern(),
        val tr: Pattern = Pattern(),
        val bl: Pattern = Pattern(),
        var inverted: Boolean = false,
        var tlIndex: Int = 0,
        var trIndex: Int = 0,
        var blIndex: Int = 0,
    )

    // decode.ts L724-744: module pitch of a tolerant 1:1:3:1:1 run, or 0 when it does not fit.
    fun ratio(a: Int, b: Int, c: Int, d: Int, e: Int): Double {
        val total = a + b + c + d + e
        if (total < 7) return 0.0
        val lo = total
        val hi = 3 * total
        return if (lo < 14 * a &&
            14 * a < hi &&
            lo < 14 * b &&
            14 * b < hi &&
            hi < 14 * c &&
            14 * c < 9 * total &&
            lo < 14 * d &&
            14 * d < hi &&
            lo < 14 * e &&
            14 * e < hi
        ) total / 7.0 else 0.0
    }

    /**
     * decode.ts L748-790: consecutive `color` bits from (x,y) inclusive stepping (dx,dy); stops on
     * mismatch, border, or once the count passes [cap] (at most floor(cap)+1 bits count).
     *
     * The horizontal word walk is ported verbatim (clz32 = numberOfLeadingZeros on the same Int32
     * words), so it equals a per-bit walk: it only checks `n <= cap` per word and clamps the
     * result with `min(n, floor(cap) + 1)`, which is exactly the count a per-bit walk with the
     * `n <= cap` guard reaches (cap = Infinity: no clamp). [cap] is finite or +Infinity here.
     */
    fun run(layer: Layer, x0: Int, y0: Int, dx: Int, dy: Int, color: Int, cap: Double): Int {
        var x = x0
        var y = y0
        var n = 0
        if (dy != 0) {
            if (x < 0 || x >= layer.width) return 0
            val bitmap = layer.bitmap
            val words = layer.words
            val height = layer.height
            val mask = 1 shl (x and 31)
            val want = if (color != 0) mask else 0
            var pos = y * words + (x ushr 5)
            while (y >= 0 && y < height && (bitmap[pos] and mask) == want && n <= cap) {
                n++
                y += dy
                pos += dy * words
            }
            return n
        }
        if (y < 0 || y >= layer.height) return 0
        val row = y * layer.words
        while (x >= 0 && x < layer.width && n <= cap) {
            val shift = x and 31
            val stops = if (color != 0) layer.bitmap[row + (x ushr 5)].inv() else layer.bitmap[row + (x ushr 5)]
            val w = if (dx > 0) stops shr shift else stops shl (31 - shift)
            val span = if (dx > 0) minOf(32 - shift, layer.width - x) else shift + 1
            val first = if (w == 0) 32 else if (dx > 0) 31 - Integer.numberOfLeadingZeros(w and -w) else Integer.numberOfLeadingZeros(w)
            val len = minOf(first, span)
            n += len
            x += dx * len
            if (first < span) break
        }
        return minOf(n.toDouble(), Math.floor(cap) + 1).toInt()
    }

    /**
     * decode.ts L793-825: 1:1:3:1:1 cross-check from (cx,cy) along (dx,dy). Returns the refined
     * center coordinate, or the measured pitch when [measure], and -1 on ratio failure.
     */
    fun cross(
        layer: Layer,
        cx: Int,
        cy: Int,
        dx: Int,
        dy: Int,
        maxMs: Double,
        inverted: Boolean,
        measure: Boolean = false,
    ): Double {
        val center = if (inverted) 0 else 1
        val side = if (inverted) 1 else 0
        var back = run(layer, cx, cy, -dx, -dy, center, Double.POSITIVE_INFINITY)
        val forward = run(layer, cx + dx, cy + dy, dx, dy, center, Double.POSITIVE_INFINITY)
        val r2 = back + forward
        if (r2 < 2) return -1.0
        val r1 = run(layer, cx - dx * back, cy - dy * back, -dx, -dy, side, maxMs)
        if (r1 >= r2) return -1.0
        back += r1
        val r0 = run(layer, cx - dx * back, cy - dy * back, -dx, -dy, center, maxMs)
        if (r0 >= r2) return -1.0
        back += r0
        val start = (if (dx != 0) cx else cy) - back
        var ahead = 1 + forward
        val r3 = run(layer, cx + dx * ahead, cy + dy * ahead, dx, dy, side, maxMs)
        if (r3 >= r2) return -1.0
        ahead += r3
        val r4 = run(layer, cx + dx * ahead, cy + dy * ahead, dx, dy, center, maxMs)
        if (ratio(r0, r1, r2, r3, r4) == 0.0) return -1.0
        // Integer prefix sums are exact in doubles, so only the `r2 / 2` term needs float math.
        return if (measure) (r0 + r1 + r2 + r3 + r4) / 7.0 else start + 1 + r0 + r1 + r2 / 2.0
    }

    // decode.ts L828-831: vertical cross at a pattern center, capped by its module size.
    fun vertical(layer: Layer, pattern: Pattern, inverted: Boolean, measure: Boolean = false): Double {
        val limit = 3 * pattern.ms
        return cross(layer, Math.round(pattern.x).toInt(), Math.round(pattern.y).toInt(), 0, 1, limit, inverted, measure)
    }

    // decode.ts L833-843: recenter vertically then horizontally; total movement or -1.
    fun refinePattern(layer: Layer, pattern: Pattern, inverted: Boolean): Double {
        val y = vertical(layer, pattern, inverted)
        if (y < 0) return -1.0
        val limit = 3 * pattern.ms
        val x = cross(layer, Math.round(pattern.x).toInt(), Math.round(y).toInt(), 1, 0, limit, inverted)
        if (x < 0) return -1.0
        val movement = Math.abs(x - pattern.x) + Math.abs(y - pattern.y)
        pattern.x = x
        pattern.y = y
        return movement
    }

    // decode.ts L845-852: refine all finder centers in place and require at least one to move.
    fun refineTriple(layer: Layer, triple: Triple): Boolean {
        val tl = refinePattern(layer, triple.tl, triple.inverted)
        if (tl < 0) return false
        val tr = refinePattern(layer, triple.tr, triple.inverted)
        if (tr < 0) return false
        val bl = refinePattern(layer, triple.bl, triple.inverted)
        return bl >= 0 && tl + tr + bl > 0
    }

    /**
     * decode.ts L854-902: fit a grayscale finder template independently on each axis. The JS
     * iterates integer `scale` 5,7,..,15 (pitch = ms * scale / 10, no float accumulation) and
     * double `candidate`/`along`/`across` loops over integral values; strict `>` keeps the first
     * best (lowest candidate, then lowest scale).
     */
    fun fitPattern(layer: Layer, pattern: Pattern, inverted: Boolean) {
        pattern.x = fit(layer, pattern, inverted, 0)
        pattern.y = fit(layer, pattern, inverted, 1)
    }

    private fun fit(layer: Layer, pattern: Pattern, inverted: Boolean, axis: Int): Double {
        val luma = layer.luma
        val center = if (axis != 0) pattern.y else pattern.x
        val other = if (axis != 0) pattern.x else pattern.y
        val radius = Math.ceil(pattern.ms)
        val lo = Homography.jsRound(center) - radius
        val hi = Homography.jsRound(center) + radius
        var best = center
        var bestScore = Double.NEGATIVE_INFINITY
        var candidate = lo
        while (candidate <= hi) {
            var scale = 5
            while (scale <= 15) {
                val pitch = (pattern.ms * scale) / 10
                val crossRadius = Homography.jsRound(pitch / 2)
                val side = Math.ceil(3.5 * pitch)
                var dark = 0.0
                var darkCount = 0.0
                var light = 0.0
                var lightCount = 0.0
                var along = -side
                while (along <= side) {
                    val module = Math.abs(along / pitch)
                    val expectedDark = module < 1.5 || module >= 2.5
                    var across = -crossRadius
                    while (across <= crossRadius) {
                        val x = Homography.jsRound(if (axis != 0) other + across else candidate + along)
                        val y = Homography.jsRound(if (axis != 0) candidate + along else other + across)
                        if (x < 0 || y < 0 || x >= layer.width || y >= layer.height) {
                            across++
                            continue
                        }
                        val value = luma[y.toInt() * layer.width + x.toInt()].toInt() and 0xFF
                        if (expectedDark) {
                            dark += value
                            darkCount++
                        } else {
                            light += value
                            lightCount++
                        }
                        across++
                    }
                    along++
                }
                var score = light / lightCount - dark / darkCount
                if (inverted) score = -score
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
                scale += 2
            }
            candidate++
        }
        return best
    }

    // decode.ts L913-916: vertical run pitch at a finder center, or 0 on ratio failure.
    fun crossPitch(layer: Layer, pattern: Pattern, inverted: Boolean): Double {
        val pitch = vertical(layer, pattern, inverted, true)
        return if (pitch < 0) 0.0 else pitch
    }

    // decode.ts L918-921: combine horizontal and vertical finder runs into one pitch.
    fun finderPitch(layer: Layer, pattern: Pattern): Double {
        val vertical = crossPitch(layer, pattern, false)
        return if (truthy(vertical)) Math.sqrt(pattern.ms * vertical) else 0.0
    }

    // decode.ts L923-936: average endpoint pitches projected onto their shared edge.
    fun edgePitch(layer: Layer, first: Pattern, second: Pattern, inverted: Boolean): Double {
        val dx = second.x - first.x
        val dy = second.y - first.y
        val length = Homography.distance(first.x, first.y, second.x, second.y)
        if (!truthy(length)) return 0.0
        fun pitch(pattern: Pattern): Double {
            val vertical = crossPitch(layer, pattern, inverted)
            if (!truthy(vertical)) return 0.0
            val a = (dx / length) * pattern.ms
            val b = (dy / length) * vertical
            return Math.sqrt(a * a + b * b)
        }
        val firstPitch = pitch(first)
        val secondPitch = pitch(second)
        return if (truthy(firstPitch) && truthy(secondPitch)) (firstPitch + secondPitch) / 2 else 0.0
    }

    // JS number truthiness: neither 0 nor NaN.
    private fun truthy(v: Double): Boolean = v != 0.0 && !v.isNaN()

    // decode.ts L953-958: copy a record's x, y, ms (confidence stays in the record).
    fun copyPattern(layer: Layer, index: Int, into: Pattern) {
        val pos = index * 4
        into.x = layer.patterns[pos]
        into.y = layer.patterns[pos + 1]
        into.ms = layer.patterns[pos + 2]
    }

    /** `scanRows.find(layer, 0, layer.height)` (decode.ts L1184-1269). */
    fun find(layer: Layer) = find(layer, 0, layer.height)

    /**
     * decode.ts L1184-1269: rolling 5-run window over every second row in [from, to), both
     * polarities; seeds are cross-checked vertically then horizontally and merged into the first
     * same-polarity record within two modules on both axes, else appended.
     */
    fun find(layer: Layer, from: Int, to: Int) {
        val width = layer.width
        val words = layer.words
        val bitmap = layer.bitmap
        var y = from
        while (y < to) {
            var r0 = 0
            var r1 = 0
            var r2 = 0
            var r3 = 0
            var r4 = 0
            var runs = 0
            var at = y * words
            var word = bitmap[at]
            var shift = 0
            var previous = (word and 1) == 1
            var x = 0
            while (x < width) {
                var length = 0
                while (true) {
                    val stops = if (previous) word.inv() else word
                    val w = stops shr shift
                    val span = minOf(32 - shift, width - x)
                    val first = if (w == 0) 32 else 31 - Integer.numberOfLeadingZeros(w and -w)
                    val len = minOf(first, span)
                    length += len
                    x += len
                    shift += len
                    if (first < span || x >= width) break
                    word = bitmap[++at]
                    shift = 0
                }
                r0 = r1
                r1 = r2
                r2 = r3
                r3 = r4
                r4 = length
                runs++
                val black = previous
                previous = !previous
                if (runs < 5 || r2 <= r1 || r2 <= r3) continue
                candidate(layer, x, y, r0, r1, r2, r3, r4, !black)
            }
            y += 2
        }
    }

    // decode.ts L1227-1265: the `candidate:` block for one 5-run window ending at x.
    private fun candidate(layer: Layer, x: Int, y: Int, r0: Int, r1: Int, r2: Int, r3: Int, r4: Int, inverted: Boolean) {
        val ms = ratio(r0, r1, r2, r3, r4)
        if (ms == 0.0) return
        val start = x - r0 - r1 - r2 - r3 - r4
        val cx = Math.round(start + r0 + r1 + r2 / 2.0).toInt()
        val limit = ms * 3
        val cy = cross(layer, cx, y, 0, 1, limit, inverted)
        if (cy < 0) return
        val refinedX = cross(layer, cx, Math.round(cy).toInt(), 1, 0, limit, inverted)
        if (refinedX < 0) return
        var patterns = layer.patterns
        val polarity = if (inverted) 1 else 0
        for (i in 0 until layer.patternCount) {
            val pos = i * 4
            if ((layer.state[i].toInt() and 1) != polarity ||
                Math.abs(patterns[pos] - refinedX) >= 2 * ms ||
                Math.abs(patterns[pos + 1] - cy) >= 2 * ms
            ) continue
            val count = patterns[pos + 3] + 1
            patterns[pos] = (patterns[pos] * patterns[pos + 3] + refinedX) / count
            patterns[pos + 1] = (patterns[pos + 1] * patterns[pos + 3] + cy) / count
            patterns[pos + 2] = (patterns[pos + 2] * patterns[pos + 3] + ms) / count
            patterns[pos + 3] = count
            return
        }
        val index = layer.patternCount++
        val pos = index * 4
        if (pos + 3 >= patterns.size) {
            layer.growFinders()
            patterns = layer.patterns
        }
        patterns[pos] = refinedX
        patterns[pos + 1] = cy
        patterns[pos + 2] = ms
        patterns[pos + 3] = 1.0
        layer.state[index] = polarity.toByte()
    }
}
