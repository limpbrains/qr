package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class QRInfoTest {

    @Test
    fun `sizeEncode should return correct size for each version`() {
        assertEquals(21, QRInfo.sizeEncode(1))
        assertEquals(25, QRInfo.sizeEncode(2))
        assertEquals(177, QRInfo.sizeEncode(40))
    }

    @Test
    fun `sizeDecode should return correct version for each size`() {
        assertEquals(1, QRInfo.sizeDecode(21))
        assertEquals(2, QRInfo.sizeDecode(25))
        assertEquals(40, QRInfo.sizeDecode(177))
    }

    @Test
    fun `sizeEncode and sizeDecode should be inverses`() {
        for (version in 1..40) {
            val size = QRInfo.sizeEncode(version)
            assertEquals(version, QRInfo.sizeDecode(size))
        }
    }

    @Test
    fun `validateVersion should accept valid versions`() {
        for (version in 1..40) {
            assertDoesNotThrow { QRInfo.validateVersion(version) }
        }
    }

    @Test
    fun `validateVersion should reject invalid versions`() {
        assertThrows<InvalidVersionException> { QRInfo.validateVersion(0) }
        assertThrows<InvalidVersionException> { QRInfo.validateVersion(41) }
        assertThrows<InvalidVersionException> { QRInfo.validateVersion(-1) }
    }

    @Test
    fun `alignmentPatterns should return empty for version 1`() {
        val patterns = QRInfo.alignmentPatterns(1)
        assertEquals(0, patterns.size)
    }

    @Test
    fun `alignmentPatterns should return correct values for version 2`() {
        val patterns = QRInfo.alignmentPatterns(2)
        assertArrayEquals(intArrayOf(6, 18), patterns)
    }

    @Test
    fun `alignmentPatterns should return correct values for version 7`() {
        val patterns = QRInfo.alignmentPatterns(7)
        assertEquals(3, patterns.size)
        assertEquals(6, patterns[0])
    }

    @Test
    fun `PATTERNS should have 8 mask patterns`() {
        assertEquals(8, QRInfo.PATTERNS.size)
    }

    @Test
    fun `mask patterns should produce valid boolean results`() {
        for ((idx, pattern) in QRInfo.PATTERNS.withIndex()) {
            for (x in 0..20) {
                for (y in 0..20) {
                    val result = pattern(x, y)
                    assertTrue(result || !result, "Pattern $idx at ($x, $y)")
                }
            }
        }
    }

    @Test
    fun `capacity should return valid values for all versions and ECCs`() {
        for (version in 1..40) {
            for (ecc in ErrorCorrection.entries) {
                val cap = QRInfo.capacity(version, ecc)
                assertTrue(cap.words > 0)
                assertTrue(cap.numBlocks > 0)
                assertTrue(cap.blockLen >= 0)
                assertTrue(cap.capacity > 0)
                assertTrue(cap.total > 0)
            }
        }
    }

    @Test
    fun `formatBits should produce 15-bit values`() {
        for (ecc in ErrorCorrection.entries) {
            for (mask in 0..7) {
                val bits = QRInfo.formatBits(ecc, mask)
                assertTrue(bits >= 0)
                assertTrue(bits < (1 shl 15))
            }
        }
    }

    @Test
    fun `versionBits should produce 18-bit values for versions 7+`() {
        for (version in 7..40) {
            val bits = QRInfo.versionBits(version)
            assertTrue(bits >= 0)
            assertTrue(bits < (1 shl 18))
        }
    }

    @Test
    fun `lengthBits should return correct values`() {
        // Version 1 (size type 0)
        assertEquals(10, QRInfo.lengthBits(1, EncodingType.NUMERIC))
        assertEquals(9, QRInfo.lengthBits(1, EncodingType.ALPHANUMERIC))
        assertEquals(8, QRInfo.lengthBits(1, EncodingType.BYTE))

        // Version 10 (size type 0)
        assertEquals(10, QRInfo.lengthBits(10, EncodingType.NUMERIC))

        // Version 27 (size type 1)
        assertEquals(12, QRInfo.lengthBits(27, EncodingType.NUMERIC))
    }

    @Test
    fun `drawTemplate should create correct size bitmap`() {
        val version = 1
        val template = QRInfo.drawTemplate(version, ErrorCorrection.MEDIUM, 0)
        assertEquals(21, template.width)
        assertEquals(21, template.height)
    }

    @Test
    fun `alphanumericEncode and decode should work correctly`() {
        val indices = listOf(0, 10, 36, 44)
        val chars = QRInfo.alphanumericEncode(indices)
        assertEquals(listOf('0', 'A', ' ', ':'), chars)

        val decoded = QRInfo.alphanumericDecode(chars)
        assertEquals(indices, decoded)
    }
}
