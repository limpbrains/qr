package qr

/**
 * Galois Field GF(2^8) operations for Reed-Solomon error correction.
 * Uses the primitive polynomial 0x11d (x^8 + x^4 + x^3 + x^2 + 1).
 */
object GaloisField {
    private const val PRIMITIVE_POLY = 0x11d

    // Lookup tables for exp and log operations
    private val expTable = IntArray(256)
    private val logTable = IntArray(256)

    init {
        // Initialize lookup tables
        var x = 1
        for (i in 0 until 256) {
            expTable[i] = x
            logTable[x] = i
            x = x shl 1
            if (x and 0x100 != 0) {
                x = x xor PRIMITIVE_POLY
            }
        }
    }

    /**
     * Get the exponential value at index i.
     */
    fun exp(i: Int): Int = expTable[i]

    /**
     * Get the logarithm of x in GF(2^8).
     */
    fun log(x: Int): Int {
        require(x != 0) { "GF.log: invalid arg=0" }
        return logTable[x] % 255
    }

    /**
     * Multiply two values in GF(2^8).
     */
    fun mul(x: Int, y: Int): Int {
        if (x == 0 || y == 0) return 0
        return expTable[(logTable[x] + logTable[y]) % 255]
    }

    /**
     * Add two values in GF(2^8) (XOR operation).
     */
    fun add(x: Int, y: Int): Int = x xor y

    /**
     * Raise x to the power e in GF(2^8).
     */
    fun pow(x: Int, e: Int): Int = expTable[(logTable[x] * e) % 255]

    /**
     * Get the multiplicative inverse of x in GF(2^8).
     */
    fun inv(x: Int): Int {
        require(x != 0) { "GF.inverse: invalid arg=0" }
        return expTable[255 - logTable[x]]
    }

    /**
     * Strip leading zeros from a polynomial.
     */
    fun polynomial(poly: IntArray): IntArray {
        if (poly.isEmpty()) throw IllegalArgumentException("GF.polynomial: invalid length")
        if (poly[0] != 0) return poly
        var i = 0
        while (i < poly.size - 1 && poly[i] == 0) i++
        return poly.copyOfRange(i, poly.size)
    }

    /**
     * Create a monomial of the given degree with the given coefficient.
     */
    fun monomial(degree: Int, coefficient: Int): IntArray {
        require(degree >= 0) { "GF.monomial: invalid degree=$degree" }
        if (coefficient == 0) return intArrayOf(0)
        val coefficients = IntArray(degree + 1)
        coefficients[0] = coefficient
        return polynomial(coefficients)
    }

    /**
     * Get the degree of a polynomial.
     */
    fun degree(a: IntArray): Int = a.size - 1

    /**
     * Get the coefficient at the given degree.
     */
    fun coefficient(a: IntArray, degree: Int): Int = a[degree(a) - degree]

    /**
     * Multiply two polynomials.
     */
    fun mulPoly(a: IntArray, b: IntArray): IntArray {
        if (a[0] == 0 || b[0] == 0) return intArrayOf(0)
        val res = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            for (j in b.indices) {
                res[i + j] = add(res[i + j], mul(a[i], b[j]))
            }
        }
        return polynomial(res)
    }

    /**
     * Multiply a polynomial by a scalar.
     */
    fun mulPolyScalar(a: IntArray, scalar: Int): IntArray {
        if (scalar == 0) return intArrayOf(0)
        if (scalar == 1) return a
        val res = IntArray(a.size)
        for (i in a.indices) {
            res[i] = mul(a[i], scalar)
        }
        return polynomial(res)
    }

    /**
     * Multiply a polynomial by a monomial.
     */
    fun mulPolyMonomial(a: IntArray, degree: Int, coefficient: Int): IntArray {
        require(degree >= 0) { "GF.mulPolyMonomial: invalid degree" }
        if (coefficient == 0) return intArrayOf(0)
        val res = IntArray(a.size + degree)
        for (i in a.indices) {
            res[i] = mul(a[i], coefficient)
        }
        return polynomial(res)
    }

    /**
     * Add two polynomials.
     */
    fun addPoly(a: IntArray, b: IntArray): IntArray {
        if (a[0] == 0) return b
        if (b[0] == 0) return a
        var smaller = a
        var larger = b
        if (smaller.size > larger.size) {
            val tmp = smaller
            smaller = larger
            larger = tmp
        }
        val sumDiff = IntArray(larger.size)
        val lengthDiff = larger.size - smaller.size
        for (i in 0 until lengthDiff) {
            sumDiff[i] = larger[i]
        }
        for (i in lengthDiff until larger.size) {
            sumDiff[i] = add(smaller[i - lengthDiff], larger[i])
        }
        return polynomial(sumDiff)
    }

    /**
     * Calculate the remainder of dividing data by divisor.
     */
    fun remainderPoly(data: IntArray, divisor: IntArray): IntArray {
        val out = data.copyOf()
        for (i in 0 until data.size - divisor.size + 1) {
            val elm = out[i]
            if (elm == 0) continue
            for (j in 1 until divisor.size) {
                if (divisor[j] != 0) {
                    out[i + j] = add(out[i + j], mul(divisor[j], elm))
                }
            }
        }
        return out.copyOfRange(data.size - divisor.size + 1, out.size)
    }

    /**
     * Generate the divisor polynomial for Reed-Solomon with given degree.
     */
    fun divisorPoly(degree: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until degree) {
            g = mulPoly(g, intArrayOf(1, pow(2, i)))
        }
        return g
    }

    /**
     * Evaluate a polynomial at a given value.
     */
    fun evalPoly(poly: IntArray, a: Int): Int {
        if (a == 0) return coefficient(poly, 0)
        var res = poly[0]
        for (i in 1 until poly.size) {
            res = add(mul(a, res), poly[i])
        }
        return res
    }

    /**
     * Extended Euclidean algorithm for Reed-Solomon decoding.
     * Returns [sigma, omega] polynomials.
     */
    fun euclidian(a: IntArray, b: IntArray, R: Int): Pair<IntArray, IntArray> {
        var aVar = a
        var bVar = b
        if (degree(aVar) < degree(bVar)) {
            val tmp = aVar
            aVar = bVar
            bVar = tmp
        }
        var rLast = aVar
        var r = bVar
        var tLast = intArrayOf(0)
        var t = intArrayOf(1)

        while (2 * degree(r) >= R) {
            val rLastLast = rLast
            val tLastLast = tLast
            rLast = r
            tLast = t

            if (rLast[0] == 0) throw QRDecodingException("rLast[0] == 0")
            r = rLastLast

            var q = intArrayOf(0)
            val dltInverse = inv(rLast[0])
            while (degree(r) >= degree(rLast) && r[0] != 0) {
                val degreeDiff = degree(r) - degree(rLast)
                val scale = mul(r[0], dltInverse)
                q = addPoly(q, monomial(degreeDiff, scale))
                r = addPoly(r, mulPolyMonomial(rLast, degreeDiff, scale))
            }
            q = mulPoly(q, tLast)
            t = addPoly(q, tLastLast)

            if (degree(r) >= degree(rLast)) {
                throw QRDecodingException("Division failed r: ${r.toList()}, rLast: ${rLast.toList()}")
            }
        }

        val sigmaTildeAtZero = coefficient(t, 0)
        if (sigmaTildeAtZero == 0) throw QRDecodingException("sigmaTilde(0) was zero")
        val inverse = inv(sigmaTildeAtZero)
        return Pair(mulPolyScalar(t, inverse), mulPolyScalar(r, inverse))
    }
}
