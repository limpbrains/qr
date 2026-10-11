package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class FinderTest {
    @Test
    fun ratio() {
        assertEquals(2.0, Finder.ratio(2, 2, 6, 2, 2))
        assertEquals(1.0, Finder.ratio(1, 1, 3, 1, 1)) // total 7 is the minimum
        assertEquals(0.0, Finder.ratio(3, 1, 3, 1, 1)) // side run above 1.5 modules
        assertEquals(0.0, Finder.ratio(1, 1, 1, 1, 1)) // total under 7
        assertEquals(0.0, Finder.ratio(2, 2, 2, 2, 2)) // center not above 1.5 modules
        assertEquals(15 / 7.0, Finder.ratio(2, 2, 7, 2, 2))
    }

    /** A bare layer with [dark] pixels set (bitmap bit 1 = dark); no binarization. */
    private fun layer(width: Int, height: Int, dark: (Int, Int) -> Boolean): Layer {
        val layer = Layer(0, width, height, ByteArray(width * height))
        layer.width = width
        layer.height = height
        layer.words = (width + 31) / 32
        for (y in 0 until height) for (x in 0 until width) {
            if (dark(x, y)) layer.bitmap[y * layer.words + (x ushr 5)] = layer.bitmap[y * layer.words + (x ushr 5)] or (1 shl (x and 31))
        }
        return layer
    }

    @Test
    fun runAllDirectionsAndCap() {
        // Row 1: dark x in 20..45 (crosses the word boundary at 32); column 40: dark y in 2..9.
        val l = layer(70, 12) { x, y -> (y == 1 && x in 20..45) || (x == 40 && y in 2..9) }
        val inf = Double.POSITIVE_INFINITY
        assertEquals(26, Finder.run(l, 20, 1, 1, 0, 1, inf))
        assertEquals(26, Finder.run(l, 45, 1, -1, 0, 1, inf))
        assertEquals(14, Finder.run(l, 33, 1, -1, 0, 1, inf))
        assertEquals(20, Finder.run(l, 19, 1, -1, 0, 0, inf)) // light run to the left border
        assertEquals(24, Finder.run(l, 46, 1, 1, 0, 0, inf)) // light run to the right border
        assertEquals(0, Finder.run(l, 19, 1, 1, 0, 1, inf)) // mismatch at start
        assertEquals(9, Finder.run(l, 40, 1, 0, 1, 1, inf)) // down: y 1..9
        assertEquals(8, Finder.run(l, 40, 8, 0, -1, 1, inf)) // up: y 8..1
        assertEquals(2, Finder.run(l, 40, 10, 0, 1, 0, inf)) // light to the bottom border
        // At most floor(cap) + 1 bits count, in both walk implementations.
        assertEquals(3, Finder.run(l, 20, 1, 1, 0, 1, 2.5))
        assertEquals(1, Finder.run(l, 20, 1, 1, 0, 1, 0.0))
        assertEquals(3, Finder.run(l, 45, 1, -1, 0, 1, 2.0))
        assertEquals(3, Finder.run(l, 40, 1, 0, 1, 1, 2.5))
        assertEquals(1, Finder.run(l, 40, 8, 0, -1, 1, 0.0))
        // Out-of-range starts.
        assertEquals(0, Finder.run(l, 70, 1, -1, 0, 1, inf))
        assertEquals(0, Finder.run(l, 5, -1, 1, 0, 0, inf))
        assertEquals(0, Finder.run(l, -1, 3, 0, 1, 0, inf))
    }

    @Test
    fun crossOnSyntheticRuns() {
        // 1:1:3:1:1 at 2 px/module along row 5 from x = 10 (dark 10-11, light 12-13, dark 14-19,
        // light 20-21, dark 22-23) and the same along column 30 from y = 4.
        fun mod(p: Int, start: Int): Int = if (p < start || p >= start + 14) -1 else (p - start) / 2
        fun darkModule(m: Int) = m == 0 || m in 2..4 || m == 6
        val l = layer(40, 24) { x, y ->
            (y == 5 && mod(x, 10) >= 0 && darkModule(mod(x, 10))) || (x == 30 && mod(y, 4) >= 0 && darkModule(mod(y, 4)))
        }
        assertEquals(17.0, Finder.cross(l, 16, 5, 1, 0, 6.0, false))
        assertEquals(2.0, Finder.cross(l, 16, 5, 1, 0, 6.0, false, measure = true))
        assertEquals(11.0, Finder.cross(l, 30, 9, 0, 1, 6.0, false))
        assertEquals(-1.0, Finder.cross(l, 16, 5, 1, 0, 6.0, true)) // wrong polarity
        assertEquals(-1.0, Finder.cross(l, 16, 6, 1, 0, 6.0, false)) // no center run off the pattern
        assertEquals(-1.0, Finder.cross(l, 11, 5, 1, 0, 6.0, false)) // center run of 2 = side run
    }

    // Quiet zone of 4 modules around one 7x7 finder, 4 px per module: 60x60, a single layer.
    private fun finderMatrix(invert: Boolean) = Array(15) { y ->
        BooleanArray(15) { x ->
            val mx = x - 4
            val my = y - 4
            val inside = mx in 0..6 && my in 0..6
            val ring = maxOf(abs(mx - 3), abs(my - 3))
            (inside && ring != 2) != invert
        }
    }

    private fun scan(matrix: Array<BooleanArray>): Layer {
        val img = TestImages.matrixToGrayImage(matrix, scale = 4)
        val scanner = QRScanner(img.width, img.height)
        scanner.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
        scanner.ensureResized()
        val layer = scanner.layers[0]
        scanner.prepareLayer(layer)
        return layer
    }

    @Test
    fun findSyntheticFinder() {
        for (invert in listOf(false, true)) {
            val layer = scan(finderMatrix(invert))
            assertEquals(1, layer.patternCount, "invert=$invert")
            assertEquals(if (invert) 1 else 0, layer.state[0].toInt())
            // Pixels 16..43; the 3-module center spans rows 24..35: even rows 24..34 hit it.
            assertEquals(6.0, layer.patterns[3])
            assertEquals(4.0, layer.patterns[2])
            assertEquals(30.0, layer.patterns[0])
            assertEquals(30.0, layer.patterns[1])
        }
    }

    @Test
    fun pickMandatoryOnSymbol() {
        val words = Segments.encodeData(1, ErrorCorrection.LOW, "HELLO", Encoding.ALPHANUMERIC, null)
        val img = TestImages.matrixToGrayImage(TestSymbols.symbolMatrix(1, ErrorCorrection.LOW, words), scale = 4)
        val scanner = QRScanner(img.width, img.height)
        scanner.addImage(img.width, img.height, img.data, PixelFormat.GRAY)
        scanner.ensureResized()
        val layer = scanner.layers[0]
        scanner.prepareLayer(layer)
        val triple = scanner.pickMandatory(layer)
        assertNotNull(triple)
        triple!!
        assertEquals(false, triple.inverted)
        // Finders at modules 4..10 (TL), 18..24 (TR on x, BL on y) with 4 px modules.
        fun near(want: Double, got: Double) = assertTrue(abs(want - got) <= 1, "want $want got $got")
        near(30.0, triple.tl.x); near(30.0, triple.tl.y)
        near(86.0, triple.tr.x); near(30.0, triple.tr.y)
        near(30.0, triple.bl.x); near(86.0, triple.bl.y)
        assertEquals(layer.pickSum, (triple.tlIndex + triple.trIndex + triple.blIndex).toDouble())
    }
}
