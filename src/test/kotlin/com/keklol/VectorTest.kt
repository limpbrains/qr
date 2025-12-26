package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests using test vectors from paulmillr/qr-code-vectors
 *
 * The test vectors use Unicode block characters:
 * - █ (U+2588, full block) - white (quiet zone)
 * - ▄ (U+2584, lower half block) - black on top, white on bottom
 * - ▀ (U+2580, upper half block) - white on top, black on bottom
 * - (space) - black module
 *
 * Each line represents 2 rows of QR modules.
 */
class VectorTest {

    companion object {
        private const val FULL_BLOCK = '\u2588'  // █ - white
        private const val LOWER_HALF = '\u2584'  // ▄ - top black, bottom white
        private const val UPPER_HALF = '\u2580'  // ▀ - top white, bottom black

        /**
         * Parse Unicode art QR code into a 2D boolean array.
         * true = black module, false = white module
         */
        fun parseUnicodeQR(out: String): Array<BooleanArray> {
            val lines = out.split('\n').filter { it.isNotEmpty() }
            if (lines.isEmpty()) return emptyArray()

            val width = lines.maxOf { it.length }
            val height = lines.size * 2  // Each line represents 2 rows

            val result = Array(height) { BooleanArray(width) }

            for ((lineIdx, line) in lines.withIndex()) {
                val topRow = lineIdx * 2
                val bottomRow = lineIdx * 2 + 1

                for ((charIdx, char) in line.withIndex()) {
                    when (char) {
                        FULL_BLOCK -> {
                            // Full block = white on both rows
                            result[topRow][charIdx] = false
                            if (bottomRow < height) result[bottomRow][charIdx] = false
                        }
                        LOWER_HALF -> {
                            // Lower half = black on top, white on bottom
                            result[topRow][charIdx] = true
                            if (bottomRow < height) result[bottomRow][charIdx] = false
                        }
                        UPPER_HALF -> {
                            // Upper half = white on top, black on bottom
                            result[topRow][charIdx] = false
                            if (bottomRow < height) result[bottomRow][charIdx] = true
                        }
                        ' ' -> {
                            // Space = black on both rows
                            result[topRow][charIdx] = true
                            if (bottomRow < height) result[bottomRow][charIdx] = true
                        }
                        else -> {
                            // Unknown char, treat as white
                            result[topRow][charIdx] = false
                            if (bottomRow < height) result[bottomRow][charIdx] = false
                        }
                    }
                }
            }

            return result
        }

        /**
         * Convert boolean bitmap to RGBA image bytes.
         * Black modules become black pixels (0,0,0,255)
         * White modules become white pixels (255,255,255,255)
         * Each module is scaled to modulePixels x modulePixels pixels for better detection.
         */
        fun bitmapToImage(bitmap: Array<BooleanArray>, modulePixels: Int = 10): Image {
            if (bitmap.isEmpty()) throw IllegalArgumentException("Empty bitmap")

            val height = bitmap.size
            val width = bitmap[0].size

            val imgWidth = width * modulePixels
            val imgHeight = height * modulePixels
            val data = ByteArray(imgWidth * imgHeight * 4)

            for (y in 0 until imgHeight) {
                for (x in 0 until imgWidth) {
                    val moduleX = x / modulePixels
                    val moduleY = y / modulePixels
                    val isBlack = bitmap[moduleY][moduleX]

                    val pixelIdx = (y * imgWidth + x) * 4
                    val value = if (isBlack) 0.toByte() else 255.toByte()

                    data[pixelIdx] = value      // R
                    data[pixelIdx + 1] = value  // G
                    data[pixelIdx + 2] = value  // B
                    data[pixelIdx + 3] = 255.toByte()  // A
                }
            }

            return Image(imgWidth, imgHeight, data)
        }

        // Hardcoded test vectors from qr-code-vectors
        val TEST_VECTORS = listOf(
            TestVector(
                text = "0",
                ecc = "low",
                out = "█████████████████████████\n██ ▄▄▄▄▄ ██ ▀ ▄█ ▄▄▄▄▄ ██\n██ █   █ █▄ █ ▄█ █   █ ██\n██ █▄▄▄█ ███▄█ █ █▄▄▄█ ██\n██▄▄▄▄▄▄▄█ ▀▄▀ █▄▄▄▄▄▄▄██\n██▄ ▄▀ ▄▄▄   ▄█▄ ███ ▀███\n█████ ▄▀▄▀▄▀▄█▄█▀█▄█▀▀▄██\n█████▄█▄▄▄▀ █▀▄▀▄▀▄▀▄▀▄██\n██ ▄▄▄▄▄ █ █▄ ▀ ▄ ▀ ▄ ▄██\n██ █   █ █▄▄█▄█▄ ▄█▄ ▀▄██\n██ █▄▄▄█ █ █▄█▄█▀█▄█▀█▄██\n██▄▄▄▄▄▄▄█▄▄▄█▄█▄█▄█▄█▄██\n▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n"
            ),
            TestVector(
                text = "01",
                ecc = "low",
                out = "█████████████████████████\n██ ▄▄▄▄▄ █▄ ██ █ ▄▄▄▄▄ ██\n██ █   █ █ █▄▀▄█ █   █ ██\n██ █▄▄▄█ █▄▄▄███ █▄▄▄█ ██\n██▄▄▄▄▄▄▄█▄▀ █▄█▄▄▄▄▄▄▄██\n██▄   █▀▄▀ ▀▄█▀▄█▀▄▄ █▄██\n██▄█  ▄▄▄   ██  ▄▄▄▄█▀███\n████▄█▄▄▄█ ▄ ▄█ █▀▄█ █▀██\n██ ▄▄▄▄▄ ███▀▀▀  █▄▀▄▄▄██\n██ █   █ █▀▄█▄█▀▄█▀▄█▀▄██\n██ █▄▄▄█ █ ▄▄ ▀▄▀█  ▄▀▀██\n██▄▄▄▄▄▄▄█▄██▄▄██▄█▄█▄███\n▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n"
            ),
            TestVector(
                text = "012",
                ecc = "low",
                out = "█████████████████████████\n██ ▄▄▄▄▄ ███▄█ █ ▄▄▄▄▄ ██\n██ █   █ █▄█▄█▀█ █   █ ██\n██ █▄▄▄█ ██ ▀ ▄█ █▄▄▄█ ██\n██▄▄▄▄▄▄▄█ █ █▄█▄▄▄▄▄▄▄██\n██▄  ▀▄▄▄▄ █ ▀▄ ▄▀█▀▄▀███\n██▀█▀▄ ▀▄▀ █  ▀ ▄ ▀▀▄ ███\n██▄█████▄█ ▀ ▄█▄ ▄██ ▀▄██\n██ ▄▄▄▄▄ █ ▄▀█▄█▀█▄█▀ ███\n██ █   █ █▄  ▀▄▀▄▀▄▀▄▀▄██\n██ █▄▄▄█ █ ▄█ ▀ ▄ ▀ ▄█▄██\n██▄▄▄▄▄▄▄█▄▄█▄█▄▄▄█▄▄█▄██\n▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n"
            ),
            TestVector(
                text = "0123",
                ecc = "low",
                out = "█████████████████████████\n██ ▄▄▄▄▄ █▀█ █ █ ▄▄▄▄▄ ██\n██ █   █ █▄█▄▄▀█ █   █ ██\n██ █▄▄▄█ █   ▄▄█ █▄▄▄█ ██\n██▄▄▄▄▄▄▄█ █▄█▄█▄▄▄▄▄▄▄██\n██▄▄█ ▀█▄▄▀▀▄ ▄▀▄ ▄▀▄ ███\n███▀ ██▀▄▄▀▄█▄▀▀   ▄█▀▀██\n███▄█▄▄█▄█   ▄█  ▄▀█ █▄██\n██ ▄▄▄▄▄ █▄██▄▄█ █▄▄▀████\n██ █   █ █▀▀ █▄  ▀██▄  ██\n██ █▄▄▄█ █▀▄▄ ▀▄▄ █ ▄▀▄██\n██▄▄▄▄▄▄▄█▄████▄█▄██▄████\n▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n"
            ),
            TestVector(
                text = "01234",
                ecc = "low",
                out = "█████████████████████████\n██ ▄▄▄▄▄ ███▀█ █ ▄▄▄▄▄ ██\n██ █   █ █▀█ ▄ █ █   █ ██\n██ █▄▄▄█ █▄ █▀▄█ █▄▄▄█ ██\n██▄▄▄▄▄▄▄█▄█▄█▄█▄▄▄▄▄▄▄██\n██  ▀▀▀ ▄▄█▀█ ▀▀█▀  ▀█▀██\n████ ▀▀▄▄▄▀▄▄▀ ▀  █▀ ▄▄██\n██▄▄█▄▄█▄█  ▀▄ ▄▄▄▄▄ ▄▄██\n██ ▄▄▄▄▄ █ ▄▄▄█▄▀█▄█  ▀██\n██ █   █ ██▄  ▀ ▀▀▄▀▄████\n██ █▄▄▄█ █▀█▄   ▀ ▄ █ ▄██\n██▄▄▄▄▄▄▄█▄█▄█▄██▄██▄████\n▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀\n"
            )
        )
    }

    data class TestVector(val text: String, val ecc: String, val out: String)

    @Test
    fun `parseUnicodeQR should correctly parse simple QR pattern`() {
        // Simple 2x2 pattern: top row white-white, bottom row black-white
        val input = "\u2588\u2584\n"  // Full block, lower half block
        val bitmap = parseUnicodeQR(input)

        assertEquals(2, bitmap.size)  // 2 rows (1 line = 2 rows)
        assertEquals(2, bitmap[0].size)  // 2 columns

        // Full block: both rows white
        assertFalse(bitmap[0][0])  // top-left white
        assertFalse(bitmap[1][0])  // bottom-left white

        // Lower half: top black, bottom white
        assertTrue(bitmap[0][1])   // top-right black
        assertFalse(bitmap[1][1])  // bottom-right white
    }

    @Test
    fun `parseUnicodeQR should handle all block types`() {
        // Test all 4 characters
        val input = "\u2588\u2584\u2580 \n"  // full, lower, upper, space
        val bitmap = parseUnicodeQR(input)

        // Full block: white-white
        assertFalse(bitmap[0][0])
        assertFalse(bitmap[1][0])

        // Lower half: black-white
        assertTrue(bitmap[0][1])
        assertFalse(bitmap[1][1])

        // Upper half: white-black
        assertFalse(bitmap[0][2])
        assertTrue(bitmap[1][2])

        // Space: black-black
        assertTrue(bitmap[0][3])
        assertTrue(bitmap[1][3])
    }

    @Test
    fun `bitmapToImage should create correct RGBA data`() {
        val bitmap = arrayOf(
            booleanArrayOf(true, false),   // black, white
            booleanArrayOf(false, true)    // white, black
        )

        val image = bitmapToImage(bitmap, modulePixels = 1)

        assertEquals(2, image.width)
        assertEquals(2, image.height)
        assertEquals(16, image.data.size)  // 2x2 pixels * 4 bytes

        // Pixel (0,0) should be black
        assertEquals(0.toByte(), image.data[0])  // R
        assertEquals(0.toByte(), image.data[1])  // G
        assertEquals(0.toByte(), image.data[2])  // B

        // Pixel (1,0) should be white
        assertEquals(255.toByte(), image.data[4])  // R
    }

    @Test
    fun `parseUnicodeQR should parse version 1 QR code`() {
        val vector = TEST_VECTORS[0]  // "0"
        val bitmap = parseUnicodeQR(vector.out)

        // Version 1 QR is 21x21 modules
        // Unicode has 13 lines (including bottom border), each line is 2 rows = 26 rows
        // But last row is just the border (▀), so effective is ~24-26 rows
        assertTrue(bitmap.size >= 21, "Height should be at least 21, got ${bitmap.size}")
        assertTrue(bitmap[0].size >= 21, "Width should be at least 21, got ${bitmap[0].size}")
    }

    @Test
    fun `decode should work for numeric QR code - 0`() {
        val vector = TEST_VECTORS[0]
        val bitmap = parseUnicodeQR(vector.out)
        val image = bitmapToImage(bitmap, modulePixels = 10)

        val decoded = QRDecoder.decode(image)
        assertEquals(vector.text, decoded)
    }

    @Test
    fun `decode should work for numeric QR code - 01`() {
        val vector = TEST_VECTORS[1]
        val bitmap = parseUnicodeQR(vector.out)
        val image = bitmapToImage(bitmap, modulePixels = 10)

        val decoded = QRDecoder.decode(image)
        assertEquals(vector.text, decoded)
    }

    @Test
    fun `decode should work for numeric QR code - 012`() {
        val vector = TEST_VECTORS[2]
        val bitmap = parseUnicodeQR(vector.out)
        val image = bitmapToImage(bitmap, modulePixels = 10)

        val decoded = QRDecoder.decode(image)
        assertEquals(vector.text, decoded)
    }

    @Test
    fun `decode should work for numeric QR code - 0123`() {
        val vector = TEST_VECTORS[3]
        val bitmap = parseUnicodeQR(vector.out)
        val image = bitmapToImage(bitmap, modulePixels = 10)

        val decoded = QRDecoder.decode(image)
        assertEquals(vector.text, decoded)
    }

    @Test
    fun `decode should work for numeric QR code - 01234`() {
        val vector = TEST_VECTORS[4]
        val bitmap = parseUnicodeQR(vector.out)
        val image = bitmapToImage(bitmap, modulePixels = 10)

        val decoded = QRDecoder.decode(image)
        assertEquals(vector.text, decoded)
    }

    @Test
    fun `batch decode test - all hardcoded vectors`() {
        var successCount = 0
        var failCount = 0
        val failures = mutableListOf<String>()

        for (vector in TEST_VECTORS) {
            try {
                val bitmap = parseUnicodeQR(vector.out)
                val image = bitmapToImage(bitmap, modulePixels = 10)
                val decoded = QRDecoder.decode(image)

                if (decoded == vector.text) {
                    successCount++
                } else {
                    failCount++
                    failures.add("Expected '${vector.text}', got '$decoded'")
                }
            } catch (e: Exception) {
                failCount++
                failures.add("${vector.text}: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        println("Batch test results: $successCount passed, $failCount failed")
        if (failures.isNotEmpty()) {
            println("Failures:")
            failures.forEach { println("  - $it") }
        }

        // All should succeed
        assertEquals(0, failCount, "Some vectors failed to decode: $failures")
    }
}
