package qr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Fixture JSON parsing used by [ImageDecodingTest]; not tagged exhaustive, so `-PskipExhaustive` runs it. */
class FixtureJsonTest {
    /** MiniJson parses doubles correctly rounded; special values map from their string encoding. */
    @Test
    fun fixtureNumberParsing() {
        val v = MiniJson.parse("[0.30000000000000004, 123.45678901234567, 5e-324, \"-0\", \"NaN\", \"-Infinity\"]")
        val d = ImageDecodingTest.fixtureNumbers(v) as List<*>
        assertEquals(0.1 + 0.2, d[0])
        assertEquals(java.lang.Double.doubleToRawLongBits(123.45678901234567), java.lang.Double.doubleToRawLongBits(d[1] as Double))
        assertEquals(Double.MIN_VALUE, d[2])
        assertEquals(0, java.lang.Double.compare(-0.0, d[3] as Double))
        assertEquals(0, java.lang.Double.compare(Double.NaN, d[4] as Double))
        assertEquals(Double.NEGATIVE_INFINITY, d[5])
    }
}
