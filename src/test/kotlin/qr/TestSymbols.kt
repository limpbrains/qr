package qr

/** Test helpers over the encoder internals. */
object TestSymbols {
    /**
     * Port of decode.test.ts `symbolImage` (L14-36) minus the final `matrixToImage`: place the given
     * codewords (RS-encoded or deliberately corrupt; bits past the data region are dropped, missing
     * bits stay light) via `drawSymbol(version, ecc, words, mask)` with format/version info drawn, and
     * return the matrix with [border]. Feed the result to `TestImages.matrixToImage(m, scale = 4)`.
     */
    fun symbolMatrix(
        version: Int,
        ecc: ErrorCorrection,
        codewords: IntArray,
        mask: Int = 0,
        border: Int = 4,
    ): Array<BooleanArray> = Symbol.withBorder(Symbol.draw(version, ecc, codewords, mask), border)
}
