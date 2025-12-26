package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ReedSolomonTest {

    @Test
    fun `encode should add error correction bytes`() {
        val rs = ReedSolomon(10)
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = rs.encode(data)
        assertEquals(10, encoded.size)
    }

    @Test
    fun `decode should correct no errors`() {
        val rs = ReedSolomon(10)
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
        val decoded = rs.decode(data)
        assertArrayEquals(data, decoded)
    }

    @Test
    fun `decode should handle valid codeword`() {
        val rs = ReedSolomon(4)

        // Create a simple data block
        val data = byteArrayOf(0x10, 0x20, 0x30)

        // Encode it
        val ecc = rs.encode(data)

        // Combine data + ecc
        val codeword = ByteArray(data.size + ecc.size)
        data.copyInto(codeword)
        ecc.copyInto(codeword, data.size)

        // Decode should not throw
        val decoded = rs.decode(codeword)
        assertEquals(codeword.size, decoded.size)
    }
}
