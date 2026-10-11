package qr

/** A point in input-image coordinates (JS `Point`, decode.ts L30). */
data class Point(val x: Double, val y: Double)

/** Four points, clockwise from top-left (JS `Quad` tuple, decode.ts L31). */
typealias Quad = List<Point>

/** A located finder or alignment pattern (JS `FinderPoint`, decode.ts L32). */
data class FinderPoint(val x: Double, val y: Double, val moduleSize: Double, val corners: Quad)

/** Axis-aligned bounds of the finder-derived QR square. */
data class BoundingBox(val x: Double, val y: Double, val width: Double, val height: Double)

/**
 * Detected QR geometry (JS `FinderPoints`, decode.ts L33-47).
 *
 * @property br projected virtual fourth finder-center position
 * @property aligners alignment patterns actually located during this sampling attempt
 * @property bounds projected outer QR edges, clockwise from top-left
 * @property outline projected QR edges plus one module of overlay padding
 * @property boundingBox axis-aligned bounds of the finder-derived QR square
 */
data class FinderPoints(
    val tl: FinderPoint,
    val tr: FinderPoint,
    val br: Point,
    val bl: FinderPoint,
    val aligners: List<FinderPoint>,
    val bounds: Quad,
    val outline: Quad,
    val boundingBox: BoundingBox,
)

/** Outcome passed to `pointsOnDetect` (JS `string | Error`). */
sealed interface DecodeResult {
    data class Text(val text: String) : DecodeResult
    data class Failure(val fail: Fail) : DecodeResult
}
