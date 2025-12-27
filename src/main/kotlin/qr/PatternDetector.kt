package qr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detects QR code patterns (finder patterns, alignment patterns) in images.
 */
object PatternDetector {

    private const val MAX_BITS_ERROR = 3
    private const val GRAYSCALE_BLOCK_SIZE = 8
    private const val GRAYSCALE_RANGE = 24
    private const val PATTERN_VARIANCE = 2.0
    private const val PATTERN_VARIANCE_DIAGONAL = 1.333
    private const val PATTERN_MIN_CONFIRMATIONS = 2
    private const val DETECT_MIN_ROW_SKIP = 3

    // Finder pattern: light/dark/light/dark/light in 1:1:3:1:1 ratio
    private val FINDER_PATTERN = booleanArrayOf(true, false, true, false, true)
    private val FINDER_SIZE = intArrayOf(1, 1, 3, 1, 1)
    private const val FINDER_TOTAL_SIZE = 7

    // Alignment pattern: dark/light/dark in 1:1:1 ratio
    private val ALIGNMENT_PATTERN = booleanArrayOf(false, true, false)
    private val ALIGNMENT_SIZE = intArrayOf(1, 1, 1)
    private const val ALIGNMENT_TOTAL_SIZE = 3

    private fun cap(value: Int, minVal: Int? = null, maxVal: Int? = null): Int {
        var result = value
        if (minVal != null) result = max(result, minVal)
        if (maxVal != null) result = min(result, maxVal)
        return result
    }

    private fun cap(value: Double, minVal: Double? = null, maxVal: Double? = null): Double {
        var result = value
        if (minVal != null) result = max(result, minVal)
        if (maxVal != null) result = min(result, maxVal)
        return result
    }

    /**
     * Convert an image to a binary bitmap using adaptive thresholding.
     */
    fun toBitmap(img: Image): Bitmap {
        val bytesPerPixel = img.bytesPerPixel
        val brightness = IntArray(img.height * img.width)

        // Calculate brightness for each pixel
        var idx = 0
        for (i in img.data.indices step bytesPerPixel) {
            val r = img.data[i].toInt() and 0xFF
            val g = img.data[i + 1].toInt() and 0xFF
            val b = img.data[i + 2].toInt() and 0xFF
            brightness[idx++] = ((r + 2 * g + b) / 4) and 0xFF
        }

        val block = GRAYSCALE_BLOCK_SIZE
        if (img.width < block * 5 || img.height < block * 5) {
            throw ImageTooSmallException("Image too small: ${img.width}x${img.height}")
        }

        val bWidth = ceil(img.width / block.toDouble()).toInt()
        val bHeight = ceil(img.height / block.toDouble()).toInt()
        val maxY = img.height - block
        val maxX = img.width - block
        val blocks = IntArray(bWidth * bHeight)

        // Calculate average brightness per block
        for (by in 0 until bHeight) {
            val yPos = cap(by * block, 0, maxY)
            for (bx in 0 until bWidth) {
                val xPos = cap(bx * block, 0, maxX)
                var sum = 0
                var minB = 0xFF
                var maxB = 0

                for (yy in 0 until block) {
                    val rowOffset = (yPos + yy) * img.width + xPos
                    for (xx in 0 until block) {
                        val pixel = brightness[rowOffset + xx]
                        sum += pixel
                        minB = min(minB, pixel)
                        maxB = max(maxB, pixel)
                    }
                }

                var average = floor(sum / (block * block).toDouble()).toInt()
                if (maxB - minB <= GRAYSCALE_RANGE) {
                    average = minB / 2
                    if (by > 0 && bx > 0) {
                        val prev = (blocks[(by - 1) * bWidth + bx] +
                                2 * blocks[by * bWidth + bx - 1] +
                                blocks[(by - 1) * bWidth + bx - 1]) / 4
                        if (minB < prev) average = prev
                    }
                }
                blocks[bWidth * by + bx] = average
            }
        }

        // Create bitmap using 5x5 block averaging
        val matrix = Bitmap(img.width, img.height)
        for (by in 0 until bHeight) {
            val yPos = cap(by * block, 0, maxY)
            val top = cap(by, 2, bHeight - 3)
            for (bx in 0 until bWidth) {
                val xPos = cap(bx * block, 0, maxX)
                val left = cap(bx, 2, bWidth - 3)

                // 5x5 blocks average
                var sum = 0
                for (yy in -2..2) {
                    val y2 = bWidth * (top + yy) + left
                    for (xx in -2..2) {
                        sum += blocks[y2 + xx]
                    }
                }
                val average = sum / 25

                for (y in 0 until block) {
                    val rowOffset = (yPos + y) * img.width + xPos
                    for (x in 0 until block) {
                        if (yPos + y < img.height && xPos + x < img.width) {
                            if (brightness[rowOffset + x] <= average) {
                                matrix.set(xPos + x, yPos + y, true)
                            }
                        }
                    }
                }
            }
        }

        return matrix
    }

    private fun checkFinderSize(runs: IntArray, moduleSize: Double, v: Double = PATTERN_VARIANCE): Boolean {
        val variance = moduleSize / v
        for (i in runs.indices) {
            if (abs(FINDER_SIZE[i] * moduleSize - runs[i]) >= FINDER_SIZE[i] * variance) {
                return false
            }
        }
        return true
    }

    private fun checkAlignmentSize(runs: IntArray, moduleSize: Double): Boolean {
        val variance = moduleSize / PATTERN_VARIANCE
        for (i in runs.indices) {
            if (abs(ALIGNMENT_SIZE[i] * moduleSize - runs[i]) >= ALIGNMENT_SIZE[i] * variance) {
                return false
            }
        }
        return true
    }

    private fun finderToCenter(runs: IntArray, end: Int): Double {
        var result = end.toDouble()
        for (i in FINDER_PATTERN.size - 1 downTo 3) {
            result -= runs[i]
        }
        result -= runs[2] / 2.0
        return result
    }

    private fun alignmentToCenter(runs: IntArray, end: Int): Double {
        var result = end.toDouble()
        result -= runs[2]
        result -= runs[1] / 2.0
        return result
    }

    private fun checkFinderLine(
        b: Bitmap,
        center: Point,
        maxCount: Int,
        total: Int,
        incr: Point
    ): Double? {
        val runs = IntArray(5)
        var j = 0
        var p = center

        // Check in negative direction
        for (idx in 2 downTo 0) {
            val neg = Point(-incr.x, -incr.y)
            while (b.isInside(p) && (b.point(p) == true) == FINDER_PATTERN[idx]) {
                runs[idx]++
                j++
                p = p + neg
            }
            if (runs[idx] == 0) return null
            if (idx != 2 && maxCount > 0 && runs[idx] > FINDER_SIZE[idx] * maxCount) return null
        }

        // Check in positive direction
        p = center + incr
        j = 1
        for (idx in 2 until 5) {
            while (b.isInside(p) && (b.point(p) == true) == FINDER_PATTERN[idx]) {
                runs[idx]++
                j++
                p = p + incr
            }
            if (runs[idx] == 0) return null
            if (idx != 2 && maxCount > 0 && runs[idx] > FINDER_SIZE[idx] * maxCount) return null
        }

        val runsTotal = runs.sum()
        if (5 * abs(runsTotal - total) >= 2 * total) return null
        if (checkFinderSize(runs, runsTotal / FINDER_TOTAL_SIZE.toDouble())) {
            return finderToCenter(runs, j)
        }
        return null
    }

    /**
     * Find the three finder patterns in a bitmap.
     */
    fun findFinder(b: Bitmap): Triple<Pattern, Pattern, Pattern> {
        val found = mutableListOf<Pattern>()

        fun checkRuns(runs: IntArray, v: Double = PATTERN_VARIANCE): Boolean {
            val total = runs.sum()
            if (total < FINDER_TOTAL_SIZE) return false
            val moduleSize = total / FINDER_TOTAL_SIZE.toDouble()
            return checkFinderSize(runs, moduleSize, v)
        }

        fun check(runs: IntArray, rowY: Int, endX: Int): Boolean {
            if (!checkRuns(runs)) return false
            val total = runs.sum()
            var x = finderToCenter(runs, endX)

            // Vertical check
            val vy = checkFinderLine(
                b,
                Point(x.toInt().toDouble(), rowY.toDouble()),
                runs[2],
                total,
                Point(0.0, 1.0)
            ) ?: return false
            val y = vy + rowY

            // Horizontal check
            val hx = checkFinderLine(
                b,
                Point(x.toInt().toDouble(), y.toInt().toDouble()),
                runs[2],
                total,
                Point(1.0, 0.0)
            ) ?: return false
            x = hx + x.toInt()

            // Diagonal check
            val dRuns = IntArray(5)
            val diagCenter = Point(x.toInt().toDouble(), y.toInt().toDouble())
            var dp = diagCenter
            val diagIncr = Point(1.0, 1.0)
            val diagNeg = Point(-1.0, -1.0)

            // Negative diagonal
            for (idx in 2 downTo 0) {
                while (b.isInside(dp) && (b.point(dp) == true) == FINDER_PATTERN[idx]) {
                    dRuns[idx]++
                    dp = dp + diagNeg
                }
                if (dRuns[idx] == 0) return false
            }

            // Positive diagonal
            dp = diagCenter + diagIncr
            for (idx in 2 until 5) {
                while (b.isInside(dp) && (b.point(dp) == true) == FINDER_PATTERN[idx]) {
                    dRuns[idx]++
                    dp = dp + diagIncr
                }
                if (dRuns[idx] == 0) return false
            }

            if (!checkRuns(dRuns, PATTERN_VARIANCE_DIAGONAL)) return false

            // Add pattern to found list
            val moduleSize = total / FINDER_TOTAL_SIZE.toDouble()
            val cur = Pattern(x, y, moduleSize, 1)
            for (i in found.indices) {
                if (found[i].equals(cur)) {
                    found[i] = found[i].merge(cur)
                    return true
                }
            }
            found.add(cur)
            return true
        }

        var skipped = false
        var ySkip = cap((3 * b.height) / (4 * 97), DETECT_MIN_ROW_SKIP)
        var done = false

        var y = ySkip - 1
        while (y < b.height && !done) {
            val runs = IntArray(5)
            var pos = 0
            var x = 0

            // Skip initial run if starting mid-image
            while (x < b.width && (b.get(x, y) == true) == FINDER_PATTERN[0]) x++

            while (x < b.width) {
                val pixelValue = b.get(x, y) == true
                if (pixelValue == FINDER_PATTERN[pos]) {
                    runs[pos]++
                    if (x != b.width - 1) {
                        x++
                        continue
                    }
                    x++
                }

                if (pos != 4) {
                    runs[++pos]++
                    x++
                    continue
                }

                val foundPattern = check(runs, y, x)
                var shouldReset = false
                var shouldBreak = false

                if (foundPattern) {
                    ySkip = 2
                    if (skipped) {
                        var count = 0
                        var total = 0.0
                        for (p in found) {
                            if (p.count < PATTERN_MIN_CONFIRMATIONS) continue
                            count++
                            total += p.moduleSize
                        }
                        if (count >= 3) {
                            val average = total / found.size
                            var deviation = 0.0
                            for (p in found) {
                                deviation += abs(p.moduleSize - average)
                            }
                            if (deviation <= 0.05 * total) {
                                done = true
                                shouldBreak = true
                            }
                        }
                        // else: shift (default)
                    } else if (found.size > 1) {
                        val confirmed = found.filter { it.count >= PATTERN_MIN_CONFIRMATIONS }
                        if (confirmed.size < 2) {
                            // Not enough confirmed patterns - reset and continue
                            shouldReset = true
                        } else {
                            skipped = true
                            val d = ((abs(confirmed[0].x - confirmed[1].x) -
                                    abs(confirmed[0].y - confirmed[1].y)) / 2).toInt()
                            if (d <= runs[2] + ySkip) {
                                shouldReset = true
                            } else {
                                y += d - runs[2] - ySkip
                                shouldBreak = true
                            }
                        }
                    }
                    // else (found.size <= 1): shift (default)
                }

                if (shouldBreak) {
                    break
                } else if (shouldReset) {
                    runs.fill(0)
                    pos = 0
                } else {
                    // Shift runs by 2
                    runs[0] = runs[2]
                    runs[1] = runs[3]
                    runs[2] = runs[4]
                    runs[3] = 0
                    runs[4] = 0
                    pos = 2
                    runs[pos]++
                }
                x++
            }
            y += ySkip
        }

        if (found.size < 3) {
            throw FinderNotFoundException("Finder: len(found) = ${found.size}")
        }

        // Sort by module size
        found.sortBy { it.moduleSize }

        // Find the best triple (closest to forming a right triangle)
        var bestScore = Double.MAX_VALUE
        var bestTriple: Triple<Pattern, Pattern, Pattern>? = null

        for (i in 0 until found.size - 2) {
            val fi = found[i]
            for (j in i + 1 until found.size - 1) {
                val fj = found[j]
                val square0 = Point.distance2(fi.toPoint(), fj.toPoint())
                for (k in j + 1 until found.size) {
                    val fk = found[k]
                    if (fk.moduleSize > fi.moduleSize * 1.4) continue

                    val distances = listOf(
                        square0,
                        Point.distance2(fj.toPoint(), fk.toPoint()),
                        Point.distance2(fi.toPoint(), fk.toPoint())
                    ).sorted()

                    val a = distances[0]
                    val bDist = distances[1]
                    val c = distances[2]
                    val score = abs(c - 2 * bDist) + abs(c - 2 * a)

                    if (score < bestScore) {
                        bestScore = score
                        bestTriple = Triple(fi, fj, fk)
                    }
                }
            }
        }

        if (bestTriple == null) {
            throw FinderNotFoundException("Cannot find finder patterns")
        }

        val (p0, p1, p2) = bestTriple
        val d01 = Point.distance(p0.toPoint(), p1.toPoint())
        val d12 = Point.distance(p1.toPoint(), p2.toPoint())
        val d02 = Point.distance(p0.toPoint(), p2.toPoint())

        var tl = p2
        var bl = p0
        var tr = p1

        if (d12 >= d01 && d12 >= d02) {
            tl = p0
            bl = p1
            tr = p2
        } else if (d02 >= d12 && d02 >= d01) {
            tl = p1
            bl = p0
            tr = p2
        }

        // If cross product is negative, flip points
        if ((tr.x - tl.x) * (bl.y - tl.y) - (tr.y - tl.y) * (bl.x - tl.x) < 0.0) {
            val temp = bl
            bl = tr
            tr = temp
        }

        return Triple(bl, tl, tr)
    }

    /**
     * Check alignment pattern in a given direction using the same algorithm as JS.
     * Returns the scan count (j) on success, or null on failure.
     */
    private fun checkAlignmentLine(
        b: Bitmap,
        runs: IntArray,
        center: Point,
        incr: Point,
        maxCount: Int
    ): Int? {
        var j = 0
        var p = center.copy()
        val neg = Point(-incr.x, -incr.y)

        // Check in negative direction (from center to 0)
        for (idx in 1 downTo 0) {
            while (b.isInside(p) && (b.point(p) == true) == ALIGNMENT_PATTERN[idx]) {
                runs[idx]++
                j++
                p = p + neg
            }
            if (runs[idx] == 0) return null
            // maxCount check for non-center runs (matching JS: runs[p] > res.size[p] * maxCount)
            if (idx != 1 && maxCount > 0 && runs[idx] > ALIGNMENT_SIZE[idx] * maxCount) return null
        }

        // Reset position and j for positive direction (matching JS: j = 1)
        p = center + incr
        j = 1

        // Check in positive direction (from center to length-1)
        for (idx in 1 until 3) {
            while (b.isInside(p) && (b.point(p) == true) == ALIGNMENT_PATTERN[idx]) {
                runs[idx]++
                j++
                p = p + incr
            }
            if (runs[idx] == 0) return null
            // maxCount check for non-center runs
            if (idx != 1 && maxCount > 0 && runs[idx] > ALIGNMENT_SIZE[idx] * maxCount) return null
        }

        return j
    }

    /**
     * Find the alignment pattern near the estimated position.
     */
    fun findAlignment(b: Bitmap, est: Pattern, allowanceFactor: Int): Pattern {
        val moduleSize = est.moduleSize
        val allowance = (allowanceFactor * moduleSize).toInt()
        val leftX = cap(est.x.toInt() - allowance, 0)
        val rightX = cap(est.x.toInt() + allowance, null, b.width - 1)
        val topY = cap(est.y.toInt() - allowance, 0)
        val bottomY = cap(est.y.toInt() + allowance, null, b.height - 1)

        val xRange = rightX - leftX
        val yRange = bottomY - topY

        if (xRange < moduleSize * 3 || yRange < moduleSize * 3) {
            throw QRDecodingException("Alignment search area too small: x=$xRange, y=$yRange, moduleSize=$moduleSize")
        }

        val found = mutableListOf<Pattern>()
        val middleY = topY + yRange / 2

        for (yGen in 0 until yRange) {
            val diff = (yGen + 1) / 2
            val y = middleY + (if (yGen % 2 == 1) -diff else diff)
            if (y < topY || y > bottomY) continue

            val runs = IntArray(3)
            var pos = 0
            var x = leftX

            // Skip initial run if it matches pattern[0] (only if not starting at edge)
            // Matches JS: if (xStart) while (x < xEnd && !!b.data[y][x] === res.pattern[0]) x++;
            if (leftX > 0) {
                while (x <= rightX && (b.get(x, y) == true) == ALIGNMENT_PATTERN[0]) x++
            }

            while (x <= rightX) {
                val pixelValue = b.get(x, y) == true
                if (pixelValue == ALIGNMENT_PATTERN[pos]) {
                    runs[pos]++
                    if (x != rightX) {
                        x++
                        continue
                    }
                    x++
                }

                if (pos != 2) {
                    runs[++pos]++
                    x++
                    continue
                }

                if (checkAlignmentSize(runs, moduleSize)) {
                    val total = runs.sum()
                    val xx = alignmentToCenter(runs, x)

                    // Vertical check with maxCount = 2 * runs[1] (matching JS)
                    val maxCount = 2 * runs[1]
                    val vRuns = IntArray(3)
                    val vCenter = Point(xx.toInt().toDouble(), y.toDouble())

                    val v = checkAlignmentLine(b, vRuns, vCenter, Point(0.0, 1.0), maxCount)
                    if (v != null) {
                        val vTotal = vRuns.sum()
                        if (5 * abs(vTotal - total) < 2 * total && checkAlignmentSize(vRuns, moduleSize)) {
                            // Calculate yy using same formula as JS: toCenter(rVert, v + y)
                            val yy = alignmentToCenter(vRuns, v + y)

                            // JS uses FINDER.totalSize (7) for moduleSize in add()
                            val pattern = Pattern(xx, yy, total / FINDER_TOTAL_SIZE.toDouble(), 1)
                            for (i in found.indices) {
                                if (found[i].equals(pattern)) {
                                    found[i] = found[i].merge(pattern)
                                    return found[i]  // Return immediately on merge (matching JS)
                                }
                            }
                            found.add(pattern)
                        }
                    }
                }

                // Shift runs by 2 (matching JS: res.shift(runs, 2))
                runs[0] = runs[2]
                runs[1] = 0
                runs[2] = 0
                pos = 0
                runs[pos]++
                x++
            }
        }

        if (found.isNotEmpty()) {
            return found[0]
        }

        throw QRDecodingException("Alignment pattern not found")
    }

    /**
     * Calculate module size using Bresenham's line algorithm.
     */
    private fun singleBWB(b: Bitmap, from: Point, to: Point): Double {
        var steep = false
        var d = Point(abs(to.x - from.x), abs(to.y - from.y))
        var fromVar = from
        var toVar = to

        if (d.y > d.x) {
            steep = true
            fromVar = fromVar.mirror()
            toVar = toVar.mirror()
            d = d.mirror()
        }

        var error = -d.x / 2
        val step = Point(
            if (fromVar.x >= toVar.x) -1.0 else 1.0,
            if (fromVar.y >= toVar.y) -1.0 else 1.0
        )
        var runPos = 0
        val xLimit = (toVar.x + step.x).toInt()

        var x = fromVar.x.toInt()
        var y = fromVar.y.toInt()

        while (x != xLimit) {
            var real = Point(x.toDouble(), y.toDouble())
            if (steep) real = real.mirror()

            val pixelValue = b.point(real) == true
            if ((runPos == 1) == pixelValue) {
                if (runPos == 2) {
                    return Point.distance(Point(x.toDouble(), y.toDouble()), fromVar)
                }
                runPos++
            }

            error += d.y
            if (error > 0) {
                if (y == toVar.y.toInt()) break
                y += step.y.toInt()
                error -= d.x
            }
            x += step.x.toInt()
        }

        if (runPos == 2) {
            return Point.distance(Point(toVar.x + step.x, toVar.y), fromVar)
        }
        return Double.NaN
    }

    private fun BWBRunLength(b: Bitmap, from: Point, to: Point): Double {
        var result = singleBWB(b, from, to)
        var scaleY = 1.0
        val fx = from.x
        val fy = from.y
        var otherToX = fx - (to.x - fx)

        if (otherToX < 0) {
            scaleY = fx / (fx - otherToX)
            otherToX = 0.0
        } else if (otherToX >= b.width) {
            scaleY = (b.width - 1 - fx) / (otherToX - fx)
            otherToX = (b.width - 1).toDouble()
        }

        var otherToY = (fy - (to.y - fy) * scaleY).toInt().toDouble()
        var scaleX = 1.0

        if (otherToY < 0) {
            scaleX = fy / (fy - otherToY)
            otherToY = 0.0
        } else if (otherToY >= b.height) {
            scaleX = (b.height - 1 - fy) / (otherToY - fy)
            otherToY = (b.height - 1).toDouble()
        }

        otherToX = (fx + (otherToX - fx) * scaleX).toInt().toDouble()
        result += singleBWB(b, from, Point(otherToX, otherToY))
        return result - 1.0
    }

    /**
     * Calculate average module size between two points.
     */
    fun moduleSizeAvg(b: Bitmap, p1: Point, p2: Point): Double {
        val est1 = BWBRunLength(b, p1.toInt(), p2.toInt())
        val est2 = BWBRunLength(b, p2.toInt(), p1.toInt())
        return when {
            est1.isNaN() -> est2 / FINDER_TOTAL_SIZE
            est2.isNaN() -> est1 / FINDER_TOTAL_SIZE
            else -> (est1 + est2) / (2 * FINDER_TOTAL_SIZE)
        }
    }

    /**
     * Detect the QR code in a bitmap and return the extracted bits and finder points.
     */
    fun detect(b: Bitmap): Pair<Bitmap, List<Pattern>> {
        val (bl, tl, tr) = findFinder(b)
        val moduleSize = (moduleSizeAvg(b, tl.toPoint(), tr.toPoint()) +
                moduleSizeAvg(b, tl.toPoint(), bl.toPoint())) / 2

        if (moduleSize < 1.0) {
            throw QRDecodingException("Invalid moduleSize = $moduleSize")
        }

        // Estimate size
        val tltr = (Point.distance(tl.toPoint(), tr.toPoint()) / moduleSize + 0.5).toInt()
        val tlbl = (Point.distance(tl.toPoint(), bl.toPoint()) / moduleSize + 0.5).toInt()
        var size = (tltr + tlbl) / 2 + 7

        val rem = size % 4
        size = when (rem) {
            0 -> size + 1
            2 -> size - 1
            3 -> size - 2
            else -> size
        }

        val version = QRInfo.sizeDecode(size)
        QRInfo.validateVersion(version)

        var alignmentPattern: Pattern? = null
        if (QRInfo.alignmentPatterns(version).isNotEmpty()) {
            // Bottom right estimate
            val brEst = Point(tr.x - tl.x + bl.x, tr.y - tl.y + bl.y)
            val c = 1.0 - 3.0 / (QRInfo.sizeEncode(version) - 7)
            // Use average finder moduleSize for alignment detection (more robust than BWB-computed moduleSize)
            val finderModuleSize = (tl.moduleSize + tr.moduleSize + bl.moduleSize) / 3
            val alignmentModuleSize = (moduleSize + finderModuleSize) / 2
            val est = Pattern(
                x = (tl.x + c * (brEst.x - tl.x)).toInt().toDouble(),
                y = (tl.y + c * (brEst.y - tl.y)).toInt().toDouble(),
                moduleSize = alignmentModuleSize,
                count = 1
            )
            for (i in listOf(4, 8, 16)) {
                try {
                    alignmentPattern = findAlignment(b, est, i)
                    break
                } catch (e: Exception) {
                    // Continue trying with larger allowance
                }
            }
        }

        val toTL = Point(3.5, 3.5)
        val toTR = Point(size - 3.5, 3.5)
        val toBL = Point(3.5, size - 3.5)
        val br: Point
        val toBR: Point

        if (alignmentPattern != null) {
            br = alignmentPattern.toPoint()
            toBR = Point(size - 6.5, size - 6.5)
        } else {
            br = Point(tr.x - tl.x + bl.x, tr.y - tl.y + bl.y)
            toBR = Point(size - 3.5, size - 3.5)
        }

        val from = listOf(tl.toPoint(), tr.toPoint(), br, bl.toPoint())
        val to = listOf(toTL, toTR, toBR, toBL)
        val bits = Transform.transform(b, size, from, to)

        val points = if (alignmentPattern != null) {
            listOf(tl, tr, alignmentPattern, bl)
        } else {
            listOf(tl, tr, Pattern(br.x, br.y, moduleSize, 1), bl)
        }

        return Pair(bits, points)
    }
}
