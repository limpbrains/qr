package qr

/**
 * Reed-Solomon encoder/decoder for QR code error correction.
 */
class ReedSolomon(private val eccWords: Int) {

    /**
     * Encode data by computing and appending error correction bytes.
     */
    fun encode(data: ByteArray): ByteArray {
        val d = GaloisField.divisorPoly(eccWords)
        val poly = IntArray(data.size + d.size - 1)
        for (i in data.indices) {
            poly[i] = data[i].toInt() and 0xFF
        }
        val remainder = GaloisField.remainderPoly(poly, d)
        return ByteArray(remainder.size) { remainder[it].toByte() }
    }

    /**
     * Decode data by detecting and correcting errors.
     */
    fun decode(data: ByteArray): ByteArray {
        val res = data.copyOf()
        val poly = GaloisField.polynomial(IntArray(data.size) { data[it].toInt() and 0xFF })

        // Find errors
        val syndrome = IntArray(eccWords)
        var hasError = false
        for (i in 0 until eccWords) {
            val evl = GaloisField.evalPoly(poly, GaloisField.exp(i))
            syndrome[syndrome.size - 1 - i] = evl
            if (evl != 0) hasError = true
        }

        if (!hasError) return res

        val syndromePoly = GaloisField.polynomial(syndrome)
        val monomial = GaloisField.monomial(eccWords, 1)
        val (errorLocator, errorEvaluator) = GaloisField.euclidian(monomial, syndromePoly, eccWords)

        // Error locations
        val locations = IntArray(GaloisField.degree(errorLocator))
        var e = 0
        for (i in 1 until 256) {
            if (e >= locations.size) break
            if (GaloisField.evalPoly(errorLocator, i) == 0) {
                locations[e++] = GaloisField.inv(i)
            }
        }

        if (e != locations.size) {
            throw QRDecodingException("RS.decode: invalid errors number")
        }

        for (i in locations.indices) {
            val pos = res.size - 1 - GaloisField.log(locations[i])
            if (pos < 0) throw QRDecodingException("RS.decode: invalid error location")

            val xiInverse = GaloisField.inv(locations[i])
            var denominator = 1
            for (j in locations.indices) {
                if (i == j) continue
                denominator = GaloisField.mul(denominator, GaloisField.add(1, GaloisField.mul(locations[j], xiInverse)))
            }

            val correction = GaloisField.mul(
                GaloisField.evalPoly(errorEvaluator, xiInverse),
                GaloisField.inv(denominator)
            )
            res[pos] = GaloisField.add(res[pos].toInt() and 0xFF, correction).toByte()
        }

        return res
    }
}
