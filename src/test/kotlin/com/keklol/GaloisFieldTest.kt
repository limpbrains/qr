package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class GaloisFieldTest {

    @Test
    fun `exp should return correct values`() {
        assertEquals(1, GaloisField.exp(0))
        assertEquals(2, GaloisField.exp(1))
        assertEquals(4, GaloisField.exp(2))
        assertEquals(8, GaloisField.exp(3))
    }

    @Test
    fun `log should return correct values`() {
        assertEquals(0, GaloisField.log(1))
        assertEquals(1, GaloisField.log(2))
        assertEquals(2, GaloisField.log(4))
        assertEquals(3, GaloisField.log(8))
    }

    @Test
    fun `log of zero should throw exception`() {
        assertThrows<IllegalArgumentException> {
            GaloisField.log(0)
        }
    }

    @Test
    fun `mul should return correct products`() {
        assertEquals(0, GaloisField.mul(0, 5))
        assertEquals(0, GaloisField.mul(5, 0))
        assertEquals(6, GaloisField.mul(2, 3))
    }

    @Test
    fun `add should XOR values`() {
        assertEquals(0, GaloisField.add(5, 5))
        assertEquals(7, GaloisField.add(5, 2))
        assertEquals(6, GaloisField.add(5, 3))
    }

    @Test
    fun `inv should return multiplicative inverse`() {
        // x * inv(x) = 1 in GF(256)
        for (x in 1..255) {
            val invX = GaloisField.inv(x)
            assertEquals(1, GaloisField.mul(x, invX), "Failed for x=$x")
        }
    }

    @Test
    fun `inv of zero should throw exception`() {
        assertThrows<IllegalArgumentException> {
            GaloisField.inv(0)
        }
    }

    @Test
    fun `polynomial should strip leading zeros`() {
        assertArrayEquals(intArrayOf(1, 2, 3), GaloisField.polynomial(intArrayOf(0, 0, 1, 2, 3)))
        assertArrayEquals(intArrayOf(1, 2, 3), GaloisField.polynomial(intArrayOf(1, 2, 3)))
        assertArrayEquals(intArrayOf(0), GaloisField.polynomial(intArrayOf(0, 0, 0)))
    }

    @Test
    fun `monomial should create correct polynomial`() {
        assertArrayEquals(intArrayOf(5, 0, 0), GaloisField.monomial(2, 5))
        assertArrayEquals(intArrayOf(1), GaloisField.monomial(0, 1))
        assertArrayEquals(intArrayOf(0), GaloisField.monomial(5, 0))
    }

    @Test
    fun `divisorPoly should generate correct generator polynomial`() {
        // For RS(10), the generator polynomial should have degree 10
        val g = GaloisField.divisorPoly(10)
        assertEquals(11, g.size)
    }

    @Test
    fun `mulPoly should multiply polynomials correctly`() {
        val a = intArrayOf(1, 2)  // x + 2
        val b = intArrayOf(1, 3)  // x + 3
        // (x + 2)(x + 3) = x^2 + 5x + 6 = x^2 + (2 XOR 3)x + (2 * 3)
        val result = GaloisField.mulPoly(a, b)
        assertEquals(3, result.size)
        assertEquals(1, result[0])  // x^2 coefficient
    }

    @Test
    fun `addPoly should add polynomials correctly`() {
        val a = intArrayOf(1, 2, 3)
        val b = intArrayOf(4, 5, 6)
        val result = GaloisField.addPoly(a, b)
        assertEquals(3, result.size)
        assertEquals(5, result[0])  // 1 XOR 4
        assertEquals(7, result[1])  // 2 XOR 5
        assertEquals(5, result[2])  // 3 XOR 6
    }
}
