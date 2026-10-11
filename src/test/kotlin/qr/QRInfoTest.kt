package qr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class QRInfoTest {

    @Test
    fun `capacity should return valid values for all versions and ECCs`() {
        for (version in 1..40) {
            for (ecc in ErrorCorrection.entries) {
                val cap = TestEncoder.capacity(version, ecc)
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
    fun `maskBits bit k equals mask predicate k`() {
        val predicates: List<(Int, Int) -> Boolean> = listOf(
            { x, y -> (x + y) % 2 == 0 },
            { _, y -> y % 2 == 0 },
            { x, _ -> x % 3 == 0 },
            { x, y -> (x + y) % 3 == 0 },
            { x, y -> (y / 2 + x / 3) % 2 == 0 },
            { x, y -> (x * y) % 2 + (x * y) % 3 == 0 },
            { x, y -> ((x * y) % 2 + (x * y) % 3) % 2 == 0 },
            { x, y -> ((x + y) % 2 + (x * y) % 3) % 2 == 0 },
        )
        // 0..11 x 0..11 covers both table periods (6 columns, 12 rows) and wrap-around;
        // larger coordinates exercise the modulo indexing.
        val coords = (0 until 12) + listOf(12, 23, 100, 176)
        for (y in coords) {
            for (x in coords) {
                val bits = QRInfo.maskBits(x, y)
                assertTrue(bits in 0..255)
                for (k in 0 until 8) {
                    assertEquals(predicates[k](x, y), (bits shr k) and 1 == 1, "mask=$k x=$x y=$y")
                }
            }
        }
    }

    @Test
    fun `popcnt counts set bits`() {
        assertEquals(0, QRInfo.popcnt(0))
        assertEquals(8, QRInfo.popcnt(0xff))
        assertEquals(3, QRInfo.popcnt(0b1010100))
    }

    @Test
    fun `BYTES matches the JS closed form`() {
        // index.ts L164-176
        for (ver in 1..40) {
            var bits = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val align = ver / 7 + 2
                bits -= (25 * align - 10) * align - 55
                if (ver >= 7) bits -= 36
            }
            assertEquals(bits ushr 3, QRInfo.BYTES[ver - 1], "ver=$ver")
        }
    }

    @Test
    fun `spec tables are indexed by ECC ordinal in JS ECC_LEVELS order`() {
        assertEquals(listOf("LOW", "MEDIUM", "QUARTILE", "HIGH"), ErrorCorrection.entries.map { it.name })
        assertEquals(7, QRInfo.WORDS_PER_BLOCK[ErrorCorrection.LOW.ordinal][0])
        assertEquals(17, QRInfo.WORDS_PER_BLOCK[ErrorCorrection.HIGH.ordinal][0])
        assertEquals(81, QRInfo.ECC_BLOCKS[ErrorCorrection.HIGH.ordinal][39])
        // Every version/level partitions BYTES into whole blocks of the stated ECC size.
        for (ecc in ErrorCorrection.entries) for (ver in 1..40) {
            val c = TestEncoder.capacity(ver, ecc)
            assertEquals(QRInfo.BYTES[ver - 1], c.total, "ver=$ver ecc=$ecc")
        }
    }
}
