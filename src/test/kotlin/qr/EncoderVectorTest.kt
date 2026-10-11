package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * qr.test.ts small vectors (L855-869): `encodeQR(text, 'ascii', { ecc })` must equal `out` exactly
 * (default border 2, scale 1, auto version and mask).
 */
class EncoderVectorTest {
    private fun run(limit: Int): Int {
        val vectors = VectorTest.loadSmallVectorsStreaming(limit)
        val mismatches = mutableListOf<String>()
        for ((i, v) in vectors.withIndex()) {
            val ecc = ErrorCorrection.valueOf(v.ecc.uppercase())
            val got = runCatching { QREncoder.encodeAscii(v.text, EncodeOptions(ecc = ecc)) }
            if (got.getOrNull() != v.out) mismatches.add("[$i] ${v.ecc} ${v.text.take(60)}: ${got.exceptionOrNull() ?: "output differs"}")
        }
        println("Encoder small vectors: ${vectors.size - mismatches.size} / ${vectors.size} identical")
        assertEquals(emptyList<String>(), mismatches, "first: ${mismatches.take(20)}")
        return vectors.size
    }

    @Test
    fun `first 200 small vectors encode identically`() {
        assertEquals(200, run(200))
    }

    @Test
    @Tag("exhaustive")
    fun `all small vectors encode identically`() {
        val start = System.nanoTime()
        val n = run(Int.MAX_VALUE)
        println("EncoderVectorTest: $n vectors in ${(System.nanoTime() - start) / 1_000_000} ms")
        assertEquals(9281, n)
    }
}
