package qr

import qr.Finder.Triple

// Projection and sampling (qr v0.7.2 `_QRScanner` private methods, decode.ts L1474-1517,
// L1635-1712, L2017-2081, L2155-2175, L2487-2793). Every expression keeps the JS operand order
// (bit-exact doubles). Stages return the decoded text or null with [QRScanner.fail] set.
// `report` (L2084-2152) builds `pointsOnDetect` geometry only when that callback is set.

// JS number truthiness: neither 0 nor NaN.
private fun truthy(v: Double): Boolean = v != 0.0 && !v.isNaN()

// decode.ts L1474-1479: compose the square-to-output and input-to-square homographies from
// `from` and `to` (both are clobbered).
internal fun QRScanner.mapQuad(out: DoubleArray) {
    Homography.squareToQuad(out, to)
    Homography.squareToQuad(to, from)
    Homography.adjugate(from, to)
    Homography.ptMul(out, out, from)
}

// decode.ts L1482-1493: module-to-plane map from the finder-center square (BR = alignment inset).
internal fun QRScanner.mapFinderQuad(out: DoubleArray, size: Int, t: Triple, brX: Double, brY: Double) {
    val c = 3.5
    Homography.packQuad(from, c, c, size - c, c, size - 6.5, size - 6.5, c, size - c)
    Homography.packQuad(to, t.tl.x, t.tl.y, t.tr.x, t.tr.y, brX, brY, t.bl.x, t.bl.y)
    mapQuad(out)
}

private val EMPTY_ALIGNERS = DoubleArray(0)

/**
 * decode.ts L2084-2152: map detected geometry into caller coordinates and cache the one terminal
 * callback payload in [QRScanner.points] (last report wins). [aligners] holds [count] records of
 * (located x, located y, module x, module y) in layer coordinates.
 *
 * Parity hazard (as JS): this rewrites the scanner's real `map`, `from` and `to` through
 * [mapFinderQuad]; on the v7+ tile path the fallback projections then read the report-written
 * matrices, so outcomes may differ when `pointsOnDetect` is set.
 */
private fun QRScanner.report(
    layer: Layer,
    t: Triple,
    brX: Double,
    brY: Double,
    aligners: DoubleArray,
    count: Int,
    ms: Double,
    size: Int,
) {
    if (options.pointsOnDetect == null) return
    val tl = t.tl
    val tr = t.tr
    val bl = t.bl
    val scale = layer.scale.toDouble()
    val ox = layer.ox
    val oy = layer.oy
    fun pt(x: Double, y: Double): Point = Point(x * scale + ox, y * scale + oy)
    // The located alignment center is inset from the fourth finder position.
    // Keep it in `aligners`, while BR completes the logical finder-center
    // square through the same homography used to sample the symbol.
    val map = this.map
    mapFinderQuad(map, size, t, brX, brY)
    fun project(x: Double, y: Double): Point = pt(Homography.mapX(map, x, y), Homography.mapY(map, x, y))
    fun quad(left: Double, top: Double, right: Double, bottom: Double): Quad = listOf(
        project(left, top),
        project(right, top),
        project(right, bottom),
        project(left, bottom),
    )
    fun marker(px: Double, py: Double, moduleSize: Double, x: Double, y: Double, radius: Double): FinderPoint {
        val center = pt(px, py)
        val centerMs = moduleSize * scale
        val expected = project(x, y)
        var corners = quad(x - radius, y - radius, x + radius, y + radius)
        // Tiled high-version alignment searches can move a center away from the
        // global homography; retain that local correction for its marker too.
        val dx = center.x - expected.x
        val dy = center.y - expected.y
        if (truthy(dx) || truthy(dy)) corners = corners.map { Point(it.x + dx, it.y + dy) }
        return FinderPoint(center.x, center.y, centerMs, corners)
    }
    val c = 3.5
    val pad = 1.0 // Keep overlays one module outside the sampled symbol.
    val bounds = quad(0.0, 0.0, size.toDouble(), size.toDouble())
    // JS Math.min(...xs) / Math.max(...xs): NaN-propagating, as Kotlin Math.min/max.
    val minX = Math.min(Math.min(Math.min(bounds[0].x, bounds[1].x), bounds[2].x), bounds[3].x)
    val minY = Math.min(Math.min(Math.min(bounds[0].y, bounds[1].y), bounds[2].y), bounds[3].y)
    val maxX = Math.max(Math.max(Math.max(bounds[0].x, bounds[1].x), bounds[2].x), bounds[3].x)
    val maxY = Math.max(Math.max(Math.max(bounds[0].y, bounds[1].y), bounds[2].y), bounds[3].y)
    points = FinderPoints(
        tl = marker(tl.x, tl.y, tl.ms, c, c, c),
        tr = marker(tr.x, tr.y, tr.ms, size - c, c, c),
        br = project(size - c, size - c),
        bl = marker(bl.x, bl.y, bl.ms, c, size - c, c),
        aligners = List(count) { i ->
            val pos = i * 4
            marker(aligners[pos], aligners[pos + 1], ms, aligners[pos + 2], aligners[pos + 3], 5.0 / 2)
        },
        bounds = bounds,
        outline = quad(-pad, -pad, size + pad, size + pad),
        boundingBox = BoundingBox(minX, minY, maxX - minX, maxY - minY),
    )
}

// decode.ts L1497-1502: grow the module grid, function map and codeword scratch to one symbol size.
internal fun QRScanner.reserve(size: Int) {
    if (grid.size >= size * size) return
    grid = IntArray(size * size)
    tmp8 = IntArray(size * size)
    codewords = IntArray(QRInfo.BYTES[(size - 17) / 4 - 1])
}

/**
 * decode.ts L1505-1517: alignment positions for [ver] into `tmp8[0..count]`; returns the count.
 * `tmp8` is also the function map / RS scratch. As in JS the positions are written over the map's
 * first row in `decodeOriented` (after the finder marks): those cells are finder cells already set
 * to 1 and every position is >= 6, so the map's truthiness (`!= 0`) is unchanged.
 */
internal fun QRScanner.setAlignments(ver: Int): Int {
    if (ver == 1) return 0
    val last = 17 + 4 * ver - 7
    val count = Math.ceil((last - 6) / 28.0).toInt()
    var interval = (last - 6) / count // Math.floor of a positive quotient
    if ((interval and 1) != 0) interval++
    else if (((last - 6) % count) * 2 >= count) interval += 2
    val positions = tmp8
    positions[0] = 6
    for (i in 1 until count) positions[i] = last - (count - i) * interval
    positions[count] = last
    return count + 1
}

/**
 * decode.ts L1635-1644: project a module center and classify its source pixel against the owning
 * threshold block. A NaN coordinate passes the bounds test in JS and reads `undefined` (not dark).
 */
internal fun QRScanner.read(s: Plane, map: DoubleArray, mx: Double, my: Double): Int {
    val den = map[6] * mx + map[7] * my + map[8]
    val px = Math.floor((map[0] * mx + map[1] * my + map[2]) / den)
    val py = Math.floor((map[3] * mx + map[4] * my + map[5]) / den)
    if (px < 0 || py < 0 || px >= s.w || py >= s.h) return 0
    val dark = if (px.isNaN() || py.isNaN()) false else {
        val x = px.toInt()
        val y = py.toInt()
        (s.d[y * s.w + x].toInt() and 0xFF) <= s.cut[(y shr s.sh) * s.bw + (x shr s.sh)]
    }
    return if (dark != invertedProjection) 1 else 0
}

// decode.ts L1647-1665: recover the fourth corner of a projective finder square from its three
// local pitches; writes `to` and `map`.
internal fun QRScanner.perspective(t: Triple, p0: Double, p1: Double, p2: Double): Boolean {
    val tl = t.tl
    val tr = t.tr
    val bl = t.bl
    val cx = Homography.cbrt(p0 / p1)
    val cy = Homography.cbrt(p0 / p2)
    val wx = cx * cx
    val wy = cy * cy
    val cornerDen = wx + wy - 1
    if (!truthy(cornerDen)) return false
    val brX = (wx * tr.x + wy * bl.x - tl.x) / cornerDen
    val brY = (wx * tr.y + wy * bl.y - tl.y) / cornerDen
    val dx3 = tl.x - tr.x + brX - bl.x
    val dy3 = tl.y - tr.y + brY - bl.y
    if ((truthy(dx3) || truthy(dy3)) && !truthy((tr.x - brX) * (bl.y - brY) - (bl.x - brX) * (tr.y - brY))) return false
    Homography.packQuad(to, tl.x, tl.y, tr.x, tr.y, brX, brY, bl.x, bl.y)
    Homography.squareToQuad(map, to)
    return true
}

/**
 * decode.ts L1670-1712: best 5x5-template placement on a 7x7 offset lattice around (bx,by);
 * offsets are `sx * mul`. With [tie] the cap starts at Infinity and equal errors prefer the
 * smaller offset; otherwise the first best (at most 5 errors) wins. Writes [QRScanner.alignX]/Y.
 */
internal fun QRScanner.searchAlign(
    s: Plane,
    map: DoubleArray,
    scale: Double,
    side: Double,
    bx: Double,
    by: Double,
    mul: Double,
    tie: Boolean,
): Boolean {
    var bestX = 0.0
    var bestY = 0.0
    var bestErrors = if (tie) Double.POSITIVE_INFINITY else 5.0 + 1
    for (sy in -3..3)
        for (sx in -3..3) {
            val dx = sx * mul
            val dy = sy * mul
            val cap = bestErrors + (if (tie) 1 else 0)
            var errors = 0
            var y = -2
            while (y <= 2 && errors < cap) {
                for (x in -2..2) {
                    val dark = read(s, map, scale + (bx + dx + x) / side, scale + (by + dy + y) / side) != 0
                    val edge = Math.abs(x) == 2 || Math.abs(y) == 2
                    if (dark != (edge || (x == 0 && y == 0)) && ++errors >= cap) break
                }
                y++
            }
            if (errors > bestErrors || (!tie && errors.toDouble() == bestErrors)) continue
            if (errors.toDouble() == bestErrors && dx * dx + dy * dy >= bestX * bestX + bestY * bestY) continue
            bestX = dx
            bestY = dy
            bestErrors = errors.toDouble()
        }
    if (bestErrors > 5) return false
    val mx = scale + (bx + bestX) / side
    val my = scale + (by + bestY) / side
    alignX = Homography.mapX(map, mx, my)
    alignY = Homography.mapY(map, mx, my)
    return true
}

// decode.ts L2017-2035: 75% agreement across the alternating timing tracks, with early rejection.
internal fun QRScanner.timing(s: Plane, map: DoubleArray, size: Int): Boolean {
    var gh = 0
    var gv = 0
    var n = 0
    val bigN = size - 2 * 8
    val track = 6 + 0.5
    for (i in 8 until size - 8) {
        val c = i + 0.5
        val e = 1 - (i and 1)
        if (read(s, map, c, track) == e) gh++
        if (read(s, map, track, c) == e) gv++
        n++
        if (100 * (gh + gv + 2 * (bigN - n)) < 150 * bigN && gh != n && gv != n) return false
    }
    return 100 * (gh + gv) >= 150 * bigN || gh == bigN || gv == bigN
}

/**
 * decode.ts L2041-2081: search a window around the expected bottom-right alignment center for a
 * dark run of ~1 module flanked by light, cross-checked vertically, on the layer's own bitmap.
 * Writes the closest hit to [QRScanner.alignX]/Y.
 */
internal fun QRScanner.findBasicAlign(layer: Layer, ex: Double, ey: Double, ms: Double): Boolean {
    val r = Math.max(Homography.jsRound(5 * ms), 8.0)
    var found = false
    var bestD = Double.POSITIVE_INFINITY
    val yLo = Math.max(1.0, Homography.jsRound(ey - r)).toInt()
    val yHi = Math.min((layer.height - 2).toDouble(), Homography.jsRound(ey + r)).toInt()
    val xLo = Math.max(1.0, Homography.jsRound(ex - r)).toInt()
    val xHi = Math.min((layer.width - 2).toDouble(), Homography.jsRound(ex + r)).toInt()
    val dark = if (invertedProjection) 0 else 1
    // Integer cap: run() counts at most cap+1 bits, i.e. ceil(3 * ms) for any pitch.
    val capV = Math.ceil(3 * ms) - 1
    for (y in yLo..yHi) {
        // A dark run continuing from before the window is not a start; skip past it first.
        var x = xLo
        if (layer.bit(x - 1, y) == dark) x += Finder.run(layer, x, y, 1, 0, dark, Double.POSITIVE_INFINITY)
        while (x <= xHi) {
            x += Finder.run(layer, x, y, 1, 0, 1 - dark, Double.POSITIVE_INFINITY)
            if (x > xHi) break
            val w = Finder.run(layer, x, y, 1, 0, dark, (xHi + 1 - x).toDouble())
            val cx = x + w / 2.0
            val cxi = Homography.jsRound(cx).toInt()
            x += w + 1 // rejected runs contain no starts; the pixel after a full run is light
            if (w < 0.4 * ms || w > 2.5 * ms) continue
            val up = Finder.run(layer, cxi, y - 1, 0, -1, dark, capV)
            val down = Finder.run(layer, cxi, y + 1, 0, 1, dark, capV)
            val h = up + down + 1
            if (h < 0.4 * ms || h > 2.5 * ms) continue
            val cy = y - up + h / 2.0
            val dist = (cx - ex) * (cx - ex) + (cy - ey) * (cy - ey)
            if (dist < bestD) {
                bestD = dist
                found = true
                alignX = cx
                alignY = cy
            }
        }
    }
    return found
}

/**
 * decode.ts L2155-2175: retarget a coarse-layer homography (into `to`) and threshold grid (into
 * [QRScanner.finePlane]) to the native luma. Returns the scale 2^r, or 0 on layer 0 (JS
 * `ctx.fine` is undefined there).
 */
internal fun QRScanner.upgrade(p: Plane, map: DoubleArray, layer: Layer): Int {
    if (layer.index == 0) return 0
    val r = layer.index
    val sc = 1 shl r
    val off = (sc - 1) / 2.0
    // A layer pixel x sits at 2^r*x + (2^r-1)/2 in native coordinates.
    val scaled = to
    for (i in 0 until 6) scaled[i] = sc * map[i] + off * map[6 + (i % 3)]
    scaled[6] = map[6]
    scaled[7] = map[7]
    scaled[8] = map[8]
    val plane = finePlane
    val sh = p.sh + r
    plane.d = luma
    plane.cut = p.cut
    plane.w = Math.min(width, p.bw shl sh)
    plane.h = Math.min(height, (p.cut.size / p.bw) shl sh)
    plane.bw = p.bw
    plane.sh = sh
    return sc
}

/**
 * decode.ts L2487-2528: `read()` unrolled over the module rect [left,right) x [top,bottom) into
 * `grid[y * size + x]`. Here a NaN coordinate fails the (positive) bounds test, so it samples 0.
 */
internal fun QRScanner.projectQuad(s: Plane, map: DoubleArray, size: Int, left: Int, right: Int, top: Int, bottom: Int) {
    val w = s.w
    val h = s.h
    val d = s.d
    val cut = s.cut
    val sh = s.sh
    val bw = s.bw
    val grid = grid
    val inverted = invertedProjection
    val m0 = map[0]
    val m1 = map[1]
    val m2 = map[2]
    val m3 = map[3]
    val m4 = map[4]
    val m5 = map[5]
    val m6 = map[6]
    val m7 = map[7]
    val m8 = map[8]
    for (y in top until bottom) {
        val my = y + 0.5
        val rx = m1 * my
        val ry = m4 * my
        val rd = m7 * my
        for (x in left until right) {
            val mx = x + 0.5
            val den = m6 * mx + rd + m8
            val px = Math.floor((m0 * mx + rx + m2) / den)
            val py = Math.floor((m3 * mx + ry + m5) / den)
            var value = 0
            if (px >= 0 && py >= 0 && px < w && py < h) {
                val ix = px.toInt()
                val iy = py.toInt()
                val dark = (d[iy * w + ix].toInt() and 0xFF) <= cut[(iy shr sh) * bw + (ix shr sh)]
                value = if (dark != inverted) 1 else 0
            }
            grid[y * size + x] = value
        }
    }
}

// decode.ts L2531-2535: timing prefilter + global grid projection against one plane.
internal fun QRScanner.projectMap(s: Plane, map: DoubleArray, size: Int): String? {
    val ok = timing(s, map, size)
    if (ok) projectQuad(s, map, size, 0, size, 0, size)
    if (ok) return decodeGrid(size)
    fail = Fail.TIMING
    return null
}

// decode.ts L2538-2550: sample only timing and the redundant version bits to gate v7+ tiling.
internal fun QRScanner.confirm(s: Plane, map: DoubleArray, size: Int): Boolean {
    var ok = timing(s, map, size)
    if (ok) {
        for (i in 0 until 18) {
            val x = size - 11 + (i % 3)
            val y = i / 3
            grid[y * size + x] = read(s, map, x + 0.5, y + 0.5)
            grid[x * size + y] = read(s, map, y + 0.5, x + 0.5)
        }
        ok = GridDecoder.checkVersion(grid, size)
    }
    return ok
}

/**
 * decode.ts L2554-2793: dimension candidates, bottom-right alignment, global (v1-6, then fine
 * plane) or tiled (v7+) projection and grid decode for one triple. Returns the text, or null with
 * [QRScanner.fail] = the last candidate failure (JS `failed`, initially `dimension`).
 * `invertedProjection` is set for the attempt and cleared on every exit.
 */
internal fun QRScanner.projectWith(layer: Layer, triple: Triple, pitch: Double): String? {
    invertedProjection = triple.inverted
    val result = project(layer, triple, pitch)
    invertedProjection = false
    return result
}

// The labelled `project:` block of projectWith (L2561-2789); `break project` on success = return.
private fun QRScanner.project(layer: Layer, t: Triple, pitch: Double): String? {
    val inverted = t.inverted
    val p = layer.plane
    var failed = Fail.DIMENSION
    val tl = t.tl
    val tr = t.tr
    val bl = t.bl
    val ms = (tl.ms + tr.ms + bl.ms) / 3
    val minMs = minOf(tl.ms, tr.ms, bl.ms)
    val maxMs = maxOf(tl.ms, tr.ms, bl.ms)
    // Perspective changes apparent pitch across finders; use the matching edge's endpoint
    // pitches only when that variation is material.
    val ax = tr.x - tl.x
    val ay = tr.y - tl.y
    val bx = bl.x - tl.x
    val by = bl.y - tl.y
    val area = Math.abs(ax * by - ay * bx)
    val span = Math.max(Math.abs(ay), Math.abs(by))
    val medianMs = tl.ms + tr.ms + bl.ms - minMs - maxMs
    val medianEst = if (span > 0 && medianMs > 0) area / (span * medianMs) + 7 else Double.POSITIVE_INFINITY
    val length = Homography.distance(tl.x, tl.y, tr.x, tr.y)
    val est = if (truthy(pitch)) length / pitch + 7
    // 1.105, 10/9, 1.125, and 1.15 were tested; 10/9 is the best gain plateau.
    else if (maxMs > (10.0 / 9) * minMs) (length * 2) / (tl.ms + tr.ms) + 7
    else medianEst
    val snapped = Homography.snapSize(est)
    val meanEst = if (span > 0 && ms > 0) area / (span * ms) + 7 else Double.POSITIVE_INFINITY
    val mean = Homography.snapSize(meanEst)
    // Module-size estimates drift under rotation and blur; try the neighbor dimensions too.
    for (i in 0 until 4) {
        val size = if (i == 0) snapped else if (i == 1) mean else if (i == 2) snapped - 4 else snapped + 4
        val estimate = if (i == 1) meanEst else est
        if ((i > 0 && size == snapped) || (i > 1 && size == mean)) continue
        // Reject estimates more than six modules from their snapped QR size.
        if (size < 21 || size > 177 || Math.abs(size - estimate) > 6) continue
        decodedSize = size
        reserve(size)
        // A located bottom-right alignment pattern upgrades the affine BR estimate to perspective.
        val f = 1 - (3.5 - 0.5) / (size - 7)
        val brEstX = tl.x + (tr.x - tl.x + bl.x - tl.x) * f
        val brEstY = tl.y + (tr.y - tl.y + bl.y - tl.y) * f
        var found = false
        if (size >= 21 + 4) {
            if (inverted || !perspective(t, t.tl.ms, t.tr.ms, t.bl.ms))
                found = findBasicAlign(layer, brEstX, brEstY, ms)
            else {
                val dax = tr.x + bl.x - 2 * tl.x
                val day = tr.y + bl.y - 2 * tl.y
                val scale = (dax * (brEstX - tl.x) + day * (brEstY - tl.y)) / (dax * dax + day * day)
                val map = this.map
                val predictedDen = (map[6] + map[7]) * scale + map[8]
                val dx = ((map[0] + map[1]) * scale + map[2]) / predictedDen - brEstX
                val dy = ((map[3] + map[4]) * scale + map[5]) / predictedDen - brEstY
                // Treat disagreement within three modules as measurement noise.
                if (dx * dx + dy * dy <= 9 * ms * ms)
                    found = findBasicAlign(layer, brEstX, brEstY, ms)
                else {
                    val tlMs = Finder.finderPitch(layer, tl)
                    val trMs = Finder.finderPitch(layer, tr)
                    val blMs = Finder.finderPitch(layer, bl)
                    if (truthy(tlMs) && truthy(trMs) && truthy(blMs) && perspective(t, tlMs, trMs, blMs)) {
                        val side = (3.5 - 0.5) / (1 - scale)
                        found = searchAlign(p, map, scale, side, 0.0, 0.0, 1.0, false)
                    }
                }
            }
        }
        // Data can resemble an aligner; let Reed-Solomon try the affine estimate too.
        failed = Fail.ALIGNMENT
        val attempts = if (found) 2 else 1
        for (attempt in 0 until attempts) {
            val align = attempt == 0 && found
            val brX = if (align) alignX else brEstX
            val brY = if (align) alignY else brEstY
            // L2638-2643.
            if (options.pointsOnDetect != null) {
                val inset = size - 6.5
                report(
                    layer, t, brX, brY,
                    if (align) doubleArrayOf(alignX, alignY, inset, inset) else EMPTY_ALIGNERS,
                    if (align) 1 else 0, ms, size,
                )
            }
            val map = this.map
            mapFinderQuad(map, size, t, brX, brY)
            val version = (size - 17) / 4
            if (version < 7) {
                projectMap(p, map, size)?.let { return it }
                failed = fail
                // Blurred Versions 1-6 still require fine-plane sampling.
                if (upgrade(p, map, layer) != 0) {
                    projectMap(finePlane, to, size)?.let { return it }
                    failed = fail
                }
            } else {
                val fine = upgrade(p, map, layer)
                val sp = if (fine != 0) finePlane else p
                val sm = if (fine != 0) to else map
                // Confirm Version 7+'s redundant BCH word before paying for local alignment searches.
                var confirmed = confirm(sp, sm, size)
                if (!confirmed && fine != 0) confirmed = confirm(p, map, size)
                if (confirmed) {
                    tiles(sp, sm, size, version, layer, t, brX, brY, ms, fine)?.let { return it }
                    failed = fail
                    // A false local alignment can corrupt a valid global projection; rebuild that
                    // grid. As in JS, `sm`/`map` may hold the last tile's maps by now.
                    projectMap(sp, sm, size)?.let { return it }
                    failed = fail
                    if (fine != 0) {
                        projectMap(p, map, size)?.let { return it }
                        failed = fail
                    }
                } else failed = Fail.VERSION
            }
        }
    }
    fail = failed
    return null
}

/**
 * decode.ts L2658-2788 (the `tiles:` block): template-search every alignment position (`tie`),
 * use located centres as lattice nodes (others mapped through [sm]), then project each lattice
 * cell through its own homography; outer tiles extend to the symbol edge. tmp64 slots: nodes
 * `(yi * count + xi) * 2` in 0..97, located records `98 + i * 4` (x, y, module x, module y; JS
 * reads them only for `pointsOnDetect` via [report]). Null with [Fail.ALIGNMENT] when no aligner is found.
 * `map`, `from` and `to` are clobbered by the per-tile homographies.
 */
private fun QRScanner.tiles(
    sp: Plane,
    sm: DoubleArray,
    size: Int,
    version: Int,
    layer: Layer,
    t: Triple,
    brX: Double,
    brY: Double,
    ms: Double,
    fine: Int,
): String? {
    val tileFine = sp === finePlane
    val sc = if (tileFine) fine else 1
    val off = (sc - 1) / 2.0
    val count = setAlignments(version)
    val positions = tmp8
    val nodes = tmp64
    val located = tmp64
    val last = positions[count - 1]
    val c = 0.5
    var locatedCount = 0
    var tileBrX = 0.0
    var tileBrY = 0.0
    var hasTileBr = false
    for (yi in 0 until count)
        for (xi in 0 until count) {
            val x = positions[xi]
            val y = positions[yi]
            val overlapsFinder = (x == 6 && (y == 6 || y == last)) || (x == last && y == 6)
            // scale=c, side=1 preserves x + dx + q + 0.5 bit for bit.
            val node = (yi * count + xi) * 2
            if (!overlapsFinder && searchAlign(sp, sm, c, 1.0, x.toDouble(), y.toDouble(), c, true)) {
                val pos = 7 * 7 * 2 + locatedCount * 4
                located[pos] = alignX
                located[pos + 1] = alignY
                located[pos + 2] = x + c
                located[pos + 3] = y + c
                locatedCount++
                if (x == last && y == last) {
                    hasTileBr = true
                    tileBrX = alignX
                    tileBrY = alignY
                }
                nodes[node] = alignX
                nodes[node + 1] = alignY
            } else {
                nodes[node] = Homography.mapX(sm, x + c, y + c)
                nodes[node + 1] = Homography.mapY(sm, x + c, y + c)
            }
        }
    if (locatedCount == 0) {
        fail = Fail.ALIGNMENT
        return null
    }
    for (yi in 0 until count - 1)
        for (xi in 0 until count - 1) {
            val left = positions[xi]
            val right = positions[xi + 1]
            val top = positions[yi]
            val bottom = positions[yi + 1]
            val tlNode = (yi * count + xi) * 2
            val trNode = tlNode + 2
            val blNode = ((yi + 1) * count + xi) * 2
            val brNode = blNode + 2
            val tile = map
            Homography.packQuad(from, left + c, top + c, right + c, top + c, right + c, bottom + c, left + c, bottom + c)
            Homography.packQuad(
                to,
                nodes[tlNode], nodes[tlNode + 1],
                nodes[trNode], nodes[trNode + 1],
                nodes[brNode], nodes[brNode + 1],
                nodes[blNode], nodes[blNode + 1],
            )
            mapQuad(tile)
            projectQuad(
                sp,
                tile,
                size,
                if (xi != 0) left else 0,
                if (xi == count - 2) size else right,
                if (yi != 0) top else 0,
                if (yi == count - 2) size else bottom,
            )
        }
    // L2755-2773: located aligners back to layer coordinates (fine-plane records divide out sc).
    if (options.pointsOnDetect != null) {
        val reportAligners = DoubleArray(locatedCount * 4)
        for (i in 0 until locatedCount) {
            val pos = 7 * 7 * 2 + i * 4
            reportAligners[i * 4] = (located[pos] - off) / sc
            reportAligners[i * 4 + 1] = (located[pos + 1] - off) / sc
            reportAligners[i * 4 + 2] = located[pos + 2]
            reportAligners[i * 4 + 3] = located[pos + 3]
        }
        report(
            layer,
            t,
            if (hasTileBr) (tileBrX - off) / sc else brX,
            if (hasTileBr) (tileBrY - off) / sc else brY,
            reportAligners,
            locatedCount,
            ms,
            size,
        )
    }
    return decodeGrid(size)
}
