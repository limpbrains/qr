package com.keklol

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class StringUtilsTest {

    @Test
    fun `revertx should reverse a simple string`() {
        val input = "hello"
        val expected = "olleh"
        assertEquals(expected, revertx(input))
    }

    @Test
    fun `revertx should reverse a string with spaces`() {
        val input = "hello world"
        val expected = "dlrow olleh"
        assertEquals(expected, revertx(input))
    }

    @Test
    fun `revertx should handle empty string`() {
        val input = ""
        val expected = ""
        assertEquals(expected, revertx(input))
    }

    @Test
    fun `revertx should handle single character`() {
        val input = "a"
        val expected = "a"
        assertEquals(expected, revertx(input))
    }

    @Test
    fun `revertx should reverse palindrome correctly`() {
        val input = "racecar"
        val expected = "racecar"
        assertEquals(expected, revertx(input))
    }

    @Test
    fun `revertx should handle special characters`() {
        val input = "hello@123!"
        val expected = "!321@olleh"
        assertEquals(expected, revertx(input))
    }
}
