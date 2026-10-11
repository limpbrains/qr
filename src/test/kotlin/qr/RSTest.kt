package qr

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RSTest {
    private fun rsEcc(data: IntArray, eccWords: Int): IntArray = TestEncoder.rsEcc(data, eccWords)

    private fun encode(data: IntArray, eccWords: Int): IntArray = data + rsEcc(data, eccWords)

    private fun correct(block: IntArray, offset: Int, length: Int, words: Int): Boolean =
        RS.correct(block, offset, length, words, IntArray(RS.tmpSize(words)), IntArray(8))

    /** Flip [count] distinct random positions of block[offset until offset+length) to new values. */
    private fun corrupt(block: IntArray, offset: Int, length: Int, count: Int, rnd: Random) {
        val positions = (0 until length).shuffled(rnd).take(count)
        for (p in positions) block[offset + p] = block[offset + p] xor (1 + rnd.nextInt(255))
    }

    /** Carry-less multiply reduced by 0x11d, independent of the log tables. */
    private fun slowMul(a: Int, b: Int): Int {
        var r = 0
        var x = a
        var y = b
        while (y != 0) {
            if (y and 1 != 0) r = r xor x
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
            y = y ushr 1
        }
        return r
    }

    /** Classic generator prod_{i<words} (x - a^i), leading coefficient first. */
    private fun divisorPoly(words: Int): IntArray {
        var g = intArrayOf(1)
        var root = 1
        repeat(words) {
            val n = IntArray(g.size + 1)
            for (k in g.indices) {
                n[k] = n[k] xor g[k]
                n[k + 1] = n[k + 1] xor slowMul(g[k], root)
            }
            g = n
            root = slowMul(root, 2)
        }
        return g
    }

    @Test
    fun `GF256 tables match the 0x11d field`() {
        assertTrue(GF256.EXP.size == 510 && GF256.LOG.size == 256)
        for (i in 0 until 255) {
            assertTrue(GF256.EXP[i + 255] == GF256.EXP[i])
            assertTrue(GF256.LOG[GF256.EXP[i]] == i)
        }
        for (a in 1 until 256) {
            assertTrue(GF256.mul(a, GF256.inv(a)) == 1)
            for (b in 0 until 256) assertTrue(GF256.mul(a, b) == slowMul(a, b))
        }
        assertTrue(GF256.mul(0, 7) == 0 && GF256.inv(0) == 1)
    }

    @Test
    fun `generator matches the classic divisor polynomial`() {
        for (words in 1..30) {
            val classic = divisorPoly(words) // leading 1 first
            assertArrayEquals(classic.copyOfRange(1, classic.size), RS.cached(words).gen, "words=$words")
        }
    }

    @Test
    fun `encodes the standard v1-M vector`() {
        val data = intArrayOf(0x40, 0xd2, 0x75, 0x47, 0x76, 0x17, 0x32, 0x06, 0x27, 0x26, 0x96, 0xc6, 0xc6, 0x96, 0x70, 0xec)
        val ecc = intArrayOf(0xbc, 0x2a, 0x90, 0x13, 0x6b, 0xaf, 0xef, 0xfd, 0x4b, 0xe0)
        assertArrayEquals(ecc, rsEcc(data, 10))
        val block = encode(data, 10)
        val damaged = block.copyOf()
        damaged[0] = 0
        damaged[7] = 0xff
        damaged[20] = damaged[20] xor 0x55
        assertTrue(correct(damaged, 0, damaged.size, 10))
        assertArrayEquals(block, damaged)
    }

    @Test
    fun `corrects up to words over 2 errors in place and rejects one more`() {
        val rnd = Random(42)
        val dataLens = listOf(1, 19, 60, 120, 225)
        for (words in listOf(7, 10, 16, 30)) {
            for (dataLen in dataLens) {
                val data = IntArray(dataLen) { rnd.nextInt(256) }
                val block = encode(data, words)
                val length = block.size
                val pad = 5
                for (k in 0..words / 2 + 1) {
                    repeat(4) {
                        // Embed the block at an offset with guard words to check only the slice changes.
                        val arena = IntArray(length + 2 * pad) { 0xA5 }
                        block.copyInto(arena, pad)
                        corrupt(arena, pad, length, k, rnd)
                        val ok = correct(arena, pad, length, words)
                        val msg = "words=$words dataLen=$dataLen errors=$k"
                        if (k <= words / 2) {
                            assertTrue(ok, msg)
                            assertArrayEquals(block, arena.copyOfRange(pad, pad + length), msg)
                            for (i in 0 until pad) {
                                assertTrue(arena[i] == 0xA5 && arena[pad + length + i] == 0xA5, msg)
                            }
                        } else if (words % 2 == 1) {
                            assertFalse(ok, msg)
                        } else if (ok) {
                            // Even words: words/2+1 errors can land within radius words/2 of another
                            // codeword, so a "successful" miscorrection is legitimate; it must differ.
                            assertFalse(block.contentEquals(arena.copyOfRange(pad, pad + length)), msg)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `intact block takes the zero-remainder path unmodified`() {
        val rnd = Random(7)
        for (words in listOf(7, 10, 16, 30)) {
            val block = encode(IntArray(40) { rnd.nextInt(256) }, words)
            val copy = block.copyOf()
            // Poisoned scratch: the zero-remainder path must not depend on it.
            val tmp = IntArray(RS.tmpSize(words)) { 0x77 }
            assertTrue(RS.correct(copy, 0, copy.size, words, tmp, IntArray(8) { -1 }))
            assertArrayEquals(block, copy)
            assertTrue(tmp.all { it == 0x77 })
        }
    }

    @Test
    fun `upstream payload fixtures are RS-valid v1-low blocks`() {
        for (words in PayloadTest.UPSTREAM_V1L_FIXTURES) {
            val copy = words.copyOf()
            assertTrue(correct(copy, 0, 26, 7))
            assertArrayEquals(words, copy)
        }
    }
}
