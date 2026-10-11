package qr

import qr.Finder.Triple

// Triple selection (qr v0.7.2 `_QRScanner` private methods, decode.ts L1715-2014, the round-0
// branch of `scan` L2863-2893 and the retry schedule L2895-3024). Scratch slots: tmp32[0..15] candidate record
// indices, tmp32[16..] upper-layer match cache, tmp64[0..7] per-polarity picks
// (i0, i1, i2, relative), tmp64[8..23] retain ranks.

// decode.ts L1715-1749: test whether a finder lies inside an affine QR region already decoded on
// any pyramid layer. The region lives in the dead TL record: [tl+2] = trIndex, [tl+3] = blIndex,
// [tr+2] = one-module padding (written by the success bookkeeping in QRScanner.scan).
internal fun QRScanner.excluded(layer: Layer, index: Int): Boolean {
    if (blocked == 0) return false
    val scale = layer.scale.toDouble()
    val offset = (scale - 1) / 2
    val pos = 4 * index
    val x = scale * layer.patterns[pos] + offset
    val y = scale * layer.patterns[pos + 1] + offset
    for (li in layers.indices) {
        val source = layers[li]
        if (!source.found) continue
        val sourceScale = source.scale.toDouble()
        val sourceOffset = (sourceScale - 1) / 2
        val s = source.patterns
        for (i in 0 until source.patternCount) {
            val state = source.state[i].toInt()
            if ((state and 2) == 0) continue
            if ((state and 4) == 0) continue
            val tl = 4 * i
            val tr = 4 * s[tl + 2].toInt()
            val bl = 4 * s[tl + 3].toInt()
            val ux = sourceScale * (s[tr] - s[tl])
            val uy = sourceScale * (s[tr + 1] - s[tl + 1])
            val vx = sourceScale * (s[bl] - s[tl])
            val vy = sourceScale * (s[bl + 1] - s[tl + 1])
            val determinant = ux * vy - uy * vx
            val dx = x - (sourceScale * s[tl] + sourceOffset)
            val dy = y - (sourceScale * s[tl + 1] + sourceOffset)
            val across = (dx * vy - dy * vx) / determinant
            val down = (dy * ux - dx * uy) / determinant
            val padding = s[tr + 2]
            if (across >= -padding && across <= 1 + padding && down >= -padding && down <= 1 + padding)
                return true
        }
    }
    return false
}

// decode.ts L1752-1758: mark this layer's finders that fall inside any already decoded QR region.
internal fun QRScanner.exclude(layer: Layer) {
    for (i in 0 until layer.patternCount) {
        if ((layer.state[i].toInt() and 2) != 0 || !excluded(layer, i)) continue
        layer.state[i] = (layer.state[i].toInt() or 2).toByte()
        blocked++
    }
}

// decode.ts L1762-1775: bounded candidate retention; equal ranks keep the earlier slot.
internal fun QRScanner.retain(index: Int, rank: Double, count: Int, capacity: Int, offset: Int = 0): Int {
    val ranks = tmp64
    if (count < capacity) {
        tmp32[count] = index
        ranks[offset + count] = rank
        return count + 1
    }
    var worst = 0
    for (i in 1 until count) if (ranks[offset + worst] < ranks[offset + i]) worst = i
    if (rank >= ranks[offset + worst]) return count
    tmp32[worst] = index
    ranks[offset + worst] = rank
    return count
}

// decode.ts L1778-1819: assign TL/TR/BL by longest side, then enforce clockwise orientation.
internal fun QRScanner.makeTriple(layer: Layer, i0: Int, i1: Int, i2: Int): Triple {
    val pts = layer.patterns
    val p0 = i0 * 4
    val p1 = i1 * 4
    val p2 = i2 * 4
    val d01 = Homography.dist2(pts, p0, p1)
    val d12 = Homography.dist2(pts, p1, p2)
    val d02 = Homography.dist2(pts, p0, p2)
    var tl = i2
    var bl = i0
    var tr = i1
    if (d12 >= d01 && d12 >= d02) {
        tl = i0
        bl = i1
        tr = i2
    } else if (d02 >= d12 && d02 >= d01) {
        tl = i1
        bl = i0
        tr = i2
    }
    Finder.copyPattern(layer, tl, triple.tl)
    Finder.copyPattern(layer, bl, triple.bl)
    Finder.copyPattern(layer, tr, triple.tr)
    val topLeft = triple.tl
    val topRight = triple.tr
    val bottomLeft = triple.bl
    if ((topRight.x - topLeft.x) * (bottomLeft.y - topLeft.y) -
        (topRight.y - topLeft.y) * (bottomLeft.x - topLeft.x) < 0
    ) {
        val index = tr
        tr = bl
        bl = index
        Finder.copyPattern(layer, tr, topRight)
        Finder.copyPattern(layer, bl, bottomLeft)
    }
    triple.tlIndex = tl
    triple.trIndex = tr
    triple.blIndex = bl
    return triple
}

/**
 * decode.ts L1824-2014: best triple of one polarity for the mandatory attempt, written into
 * tmp64[polarity * 4..] as (i0, i1, i2, relative error).
 */
internal fun QRScanner.pickPolarity(layer: Layer, polarity: Int): Boolean {
    val pts = layer.patterns
    val candidates = tmp32
    var confirmed = 0
    for (i in 0 until layer.patternCount) {
        val state = layer.state[i].toInt()
        if ((state and 1) != polarity || (state and 2) != 0) continue
        if (pts[i * 4 + 3] >= 2) confirmed++
    }
    // Once three centers exist, require two row hits to reject one-row data patterns.
    val minimum = if (confirmed >= 3) 2 else 1
    var count = 0
    for (i in 0 until layer.patternCount) {
        val state = layer.state[i].toInt()
        if ((state and 1) != polarity || (state and 2) != 0) continue
        val confidence = pts[i * 4 + 3]
        if (confidence < minimum) continue
        count = retain(i, -confidence, count, 16, 8)
    }
    if (count < 3) return false
    // Cache up to three nearest scale-consistent upper-layer centers per slot (ids + 1, 0 = none).
    val found = tmp32
    found.fill(0, 16)
    val lowerIndex = layers.indexOf(layer)
    for (upperIndex in lowerIndex + 1 until layers.size) {
        val upper = layers[upperIndex]
        if (!upper.used || !upper.found) continue
        val scale = upper.scale.toDouble() / layer.scale
        val offset = (scale - 1) / 2
        for (slot in 0 until count) {
            val at = 4 * candidates[slot]
            val ms = pts[at + 2]
            var b0 = -1
            var b1 = -1
            var b2 = -1
            var d0 = Double.POSITIVE_INFINITY
            var d1 = Double.POSITIVE_INFINITY
            var d2 = Double.POSITIVE_INFINITY
            for (i in 0 until upper.patternCount) {
                if ((upper.state[i].toInt() and 1) != polarity) continue
                if ((upper.state[i].toInt() and 2) != 0) continue
                val pos = 4 * i
                val mappedMs = scale * upper.patterns[pos + 2]
                val smallest = Math.min(ms, mappedMs)
                val largest = Math.max(ms, mappedMs)
                if (largest > 2 * smallest) continue
                val dx = scale * upper.patterns[pos] + offset - pts[at]
                val dy = scale * upper.patterns[pos + 1] + offset - pts[at + 1]
                val distance = dx * dx + dy * dy
                if (distance >= 4 * (largest * largest) || distance >= d2) continue
                if (distance < d0) {
                    b2 = b1
                    d2 = d1
                    b1 = b0
                    d1 = d0
                    b0 = i
                    d0 = distance
                } else if (distance < d1) {
                    b2 = b1
                    d2 = d1
                    b1 = i
                    d1 = distance
                } else {
                    b2 = i
                    d2 = distance
                }
            }
            val base = 16 + (upperIndex * 16 + slot) * 3
            found[base] = b0 + 1
            found[base + 1] = b1 + 1
            found[base + 2] = b2 + 1
        }
    }
    val moduleRatioMax = if (count == 3) 1.8 else 1.4
    var best0 = -1
    var best1 = -1
    var best2 = -1
    var bestScale = 0.0
    var bestScore = Double.POSITIVE_INFINITY
    var bestConfidence = 0.0
    var rankedScore = Double.POSITIVE_INFINITY
    var rankedConfidence = 0.0
    var ranked0 = -1
    var ranked1 = -1
    var ranked2 = -1
    var rankedRelative = Double.POSITIVE_INFINITY
    for (i in 0 until count - 2) {
        val i0 = candidates[i]
        val p0 = i0 * 4
        for (j in i + 1 until count - 1) {
            val i1 = candidates[j]
            val p1 = i1 * 4
            val d01 = Homography.dist2(pts, p0, p1)
            for (k in j + 1 until count) {
                val i2 = candidates[k]
                val p2 = i2 * 4
                val minMs = minOf(pts[p0 + 2], pts[p1 + 2], pts[p2 + 2])
                val maxMs = maxOf(pts[p0 + 2], pts[p1 + 2], pts[p2 + 2])
                val nativeConfidence = minOf(pts[p0 + 3], pts[p1 + 3], pts[p2 + 3])
                if (maxMs > moduleRatioMax * minMs &&
                    // The measured sparse ambiguity has eight eligible centers.
                    (count != 8 || nativeConfidence < 4)
                ) {
                    var matched = false
                    // Check whether an upper layer confirms all three slots with distinct centers.
                    upper@ for (upperIndex in lowerIndex + 1 until layers.size) {
                        var first = 0
                        var second = 0
                        for (role in 0 until 3) {
                            val slot = if (role != 0) (if (role == 1) j else k) else i
                            val base = 16 + (upperIndex * 16 + slot) * 3
                            var selected = 0
                            for (rank in 0 until 3) {
                                val candidate = found[base + rank]
                                if (candidate == 0 || candidate == first || candidate == second) continue
                                selected = candidate
                                break
                            }
                            if (selected == 0) continue@upper
                            if (role == 0) first = selected
                            else if (role == 1) second = selected
                        }
                        matched = true
                        break
                    }
                    if (!matched) continue
                }
                val d12 = Homography.dist2(pts, p1, p2)
                val d02 = Homography.dist2(pts, p0, p2)
                val a = minOf(d01, d12, d02)
                val c = maxOf(d01, d12, d02)
                val b = d01 + d12 + d02 - a - c
                val geometry = Math.abs(c - 2 * b) + Math.abs(c - 2 * a)
                val confidence = pts[p0 + 3] + pts[p1 + 3] + pts[p2 + 3]
                if (geometry < bestScore) {
                    bestScore = geometry
                    bestScale = c
                    bestConfidence = confidence
                    best0 = i0
                    best1 = i1
                    best2 = i2
                }
                if (count > 8) continue
                // A small row-hit weight breaks near-equal geometry scores toward stronger evidence.
                val score = geometry / c - 0.01 * confidence
                if (score >= rankedScore) continue
                rankedScore = score
                rankedConfidence = confidence
                rankedRelative = geometry / c
                ranked0 = i0
                ranked1 = i1
                ranked2 = i2
            }
        }
    }
    if (best0 < 0) return false
    var relative = bestScore / bestScale
    if ((count <= 7 &&
            relative >= 0.08 &&
            relative <= 0.12) ||
        (count > 7 &&
            count <= 8 &&
            rankedConfidence >= 2 * bestConfidence &&
            rankedConfidence <= 4 * bestConfidence)
    ) {
        best0 = ranked0
        best1 = ranked1
        best2 = ranked2
        relative = rankedRelative
    }
    val base = polarity * 4
    val state = tmp64
    state[base] = best0.toDouble()
    state[base + 1] = best1.toDouble()
    state[base + 2] = best2.toDouble()
    state[base + 3] = relative
    return true
}

/**
 * decode.ts L2863-2893 (round-0 branch of `scan`): rank both polarities, pick one by
 * geometry error x evidence, record the pick's id bag and build [QRScanner.triple].
 * Null when neither polarity yields a triple.
 */
internal fun QRScanner.pickMandatory(layer: Layer): Triple? {
    exclude(layer)
    val ordinary = pickPolarity(layer, 0)
    val inverted = pickPolarity(layer, 1)
    if (!(ordinary || inverted)) return null
    val state = tmp64
    val pts = layer.patterns
    var polarity = 0
    if (inverted) {
        val ordConfidence = if (ordinary) {
            pts[state[0].toInt() * 4 + 3] + pts[state[1].toInt() * 4 + 3] + pts[state[2].toInt() * 4 + 3]
        } else 0.0
        val invConfidence = pts[state[4].toInt() * 4 + 3] + pts[state[5].toInt() * 4 + 3] + pts[state[6].toInt() * 4 + 3]
        if (!ordinary || (state[7] + 0.1) * ordConfidence < (state[3] + 0.1) * invConfidence) polarity = 1
    }
    val base = polarity * 4
    // `| 0` on non-negative integral record indices.
    val w0 = state[base].toInt()
    val w1 = state[base + 1].toInt()
    val w2 = state[base + 2].toInt()
    layer.pickSum = (w0 + w1 + w2).toDouble()
    layer.pickLo = minOf(w0, w1, w2).toDouble()
    layer.pickHi = maxOf(w0, w1, w2).toDouble()
    val triple = makeTriple(layer, w0, w1, w2)
    triple.inverted = polarity == 1
    return triple
}

// decode.ts L960-969: swap two stride-5 retry records [rank, inverted, finder0, finder1, finder2].
private fun swapSet(layer: Layer, a: Int, b: Int) {
    val sets = layer.sets
    val ap = a * 5
    val bp = b * 5
    for (i in 0 until 5) {
        val value = sets[ap + i]
        sets[ap + i] = sets[bp + i]
        sets[bp + i] = value
    }
}

// decode.ts L971-982: max-heap sift-down of the root over the first `end` set records.
private fun siftDown(layer: Layer, end: Int) {
    val sets = layer.sets
    var index = 0
    while (true) {
        val left = index * 2 + 1
        if (left >= end) return
        val right = left + 1
        val child = if (right < end && sets[right * 5] > sets[left * 5]) right else left
        if (sets[index * 5] >= sets[child * 5]) return
        swapSet(layer, index, child)
        index = child
    }
}

/**
 * decode.ts L2895-3005 (`schedule:` block of `scan`, cooperative = false): once per staged layer,
 * rank same-polarity neighbour triples by compactness / evidence into the 256-entry max-heap
 * [Layer.sets], then heap-sort it ascending. Scratch: tmp32[0..14] neighbours, tmp64[0..14] ranks.
 */
internal fun QRScanner.buildSchedule(layer: Layer) {
    if (layer.setsReady) return
    layer.setsReady = true
    layer.setCount = 0
    layer.setCursor = 0
    var eligible = 0
    for (i in 0 until layer.patternCount) {
        if ((layer.state[i].toInt() and 2) != 0) continue
        eligible++
    }
    if (eligible < 3) return
    val pts = layer.patterns
    val neighbors = tmp32
    val useFilters = eligible > 5
    for (i in 0 until layer.patternCount - 2) {
        val state = layer.state[i].toInt()
        if ((state and 2) != 0) continue
        val inverted = (state and 1) != 0
        val p0 = i * 4
        // A 1.5 scale allowance retains the largest legal perspective dimension.
        val maxDistance = pts[p0 + 2] * 177 * 1.5
        val maxDistance2 = maxDistance * maxDistance
        var count = 0
        for (index in i + 1 until layer.patternCount) {
            val otherState = layer.state[index].toInt()
            if ((otherState and 2) != 0 || ((otherState and 1) != 0) != inverted) continue
            val pos = index * 4
            val smallest = Math.min(pts[p0 + 2], pts[pos + 2])
            val largest = Math.max(pts[p0 + 2], pts[pos + 2])
            if (useFilters &&
                // The 2.4 ratio preserves upper-layer-confirmed perspective triples.
                largest > 2.4 * smallest + 2.0 / 7
            ) continue
            val dx = pts[p0] - pts[pos]
            val dy = pts[p0 + 1] - pts[pos + 1]
            val distance = dx * dx + dy * dy
            if (distance > maxDistance2) continue
            val rank = distance / pts[pos + 3]
            count = retain(index, rank, count, 15)
        }
        for (u in 0 until count - 1) {
            val i1 = neighbors[u]
            val p1 = i1 * 4
            val d01 = Homography.dist2(pts, p0, p1)
            for (v in u + 1 until count) {
                val i2 = neighbors[v]
                val p2 = i2 * 4
                val d12 = Homography.dist2(pts, p1, p2)
                val d02 = Homography.dist2(pts, p0, p2)
                val a = minOf(d01, d12, d02)
                val c = maxOf(d01, d12, d02)
                val b = d01 + d12 + d02 - a - c
                // JS `!a || !b`: 0 and NaN are falsy.
                if (a == 0.0 || a.isNaN() || b == 0.0 || b.isNaN() || (useFilters && (a > 4 * b || b > 4 * a))) continue
                val da = Math.sqrt(a)
                val db = Math.sqrt(b)
                val moduleCount = (da + db) / (2 * ((pts[p0 + 2] + pts[p1 + 2] + pts[p2 + 2]) / 3)) + 7
                // Center-line pitch can overestimate small finders; allow 0.8..1.5 scaling.
                if (moduleCount < 21 * 0.8 || moduleCount > 177 * 1.5) continue
                val cosine = (a + b - c) / (2 * Math.sqrt(a * b))
                // Accept finder legs spanning 60 through 120 degrees.
                if (useFilters && Math.abs(cosine) > 0.5) continue
                val confidence = pts[p0 + 3] + pts[p1 + 3] + pts[p2 + 3]
                val score = (da + db + Math.abs(da - db)) / confidence
                // `retain:` block L2973-2995.
                val sets = layer.sets
                var index = layer.setCount
                if (index < 256) layer.setCount++
                else {
                    if (score >= sets[0]) continue
                    index = 0
                }
                val pos = index * 5
                sets[pos] = score
                sets[pos + 1] = if (inverted) 1.0 else 0.0
                sets[pos + 2] = i.toDouble()
                sets[pos + 3] = i1.toDouble()
                sets[pos + 4] = i2.toDouble()
                // A completed sift-up satisfies the root comparison (JS `break retain`).
                var settled = false
                while (index != 0) {
                    val parent = (index - 1) shr 1
                    if (sets[parent * 5] >= sets[index * 5]) {
                        settled = true
                        break
                    }
                    swapSet(layer, parent, index)
                    index = parent
                }
                if (!settled) siftDown(layer, layer.setCount)
            }
        }
    }
    var end = layer.setCount - 1
    while (end > 0) {
        swapSet(layer, 0, end)
        siftDown(layer, end)
        end--
    }
}

/**
 * decode.ts L3006-3024: pop the next scheduled set whose finders are all live and which is not
 * the round-0 pick's id bag; null when the schedule is exhausted.
 */
internal fun QRScanner.nextSet(layer: Layer): Triple? {
    val sets = layer.sets
    while (layer.setCursor < layer.setCount) {
        val pos = layer.setCursor++ * 5
        val i0 = sets[pos + 2].toInt()
        val i1 = sets[pos + 3].toInt()
        val i2 = sets[pos + 4].toInt()
        if ((layer.state[i0].toInt() and 2) != 0 || (layer.state[i1].toInt() and 2) != 0 ||
            (layer.state[i2].toInt() and 2) != 0
        ) continue
        // The round-0 pick may sit anywhere in the schedule (or nowhere); skip its id bag.
        if ((i0 + i1 + i2).toDouble() == layer.pickSum &&
            minOf(i0, i1, i2).toDouble() == layer.pickLo &&
            maxOf(i0, i1, i2).toDouble() == layer.pickHi
        ) continue
        val triple = makeTriple(layer, i0, i1, i2)
        triple.inverted = sets[pos + 1] != 0.0
        return triple
    }
    return null
}
