# QR - Kotlin QR Code Library

**Repository**: https://github.com/limpbrains/qr

A pure Kotlin library (stdlib only, JDK 17+) for encoding QR codes and decoding them from raw image
data. This is a Kotlin port of the encoder and decoder in Paul Miller's
[paulmillr/qr](https://github.com/paulmillr/qr), kept bit-exact with the JS.

## Build & Test

```bash
./gradlew build
./gradlew test                    # everything
./gradlew test -PskipExhaustive   # skip @Tag("exhaustive"): ImageDecodingTest, full VectorTest, full EncoderVectorTest
./gradlew test -PonlyExhaustive   # only the exhaustive tests
./gradlew publishToMavenLocal
```

`test/vectors` is a git submodule (paulmillr/qr-code-vectors): `git submodule update --init --recursive`.

## Project Structure

```
src/main/kotlin/qr/
├── QRDecoder.kt    # Facade (JS decodeQR/decodeQRBatch): shared QRScanner, decode(image, options), decodeBatch
├── QRScanner.kt    # Reusable scanner (JS _QRScanner): arenas, addImage/processImage, decode, scan loop, callbacks
├── Image.kt        # Image(width, height, data); format inferred from data length
├── Types.kt        # ErrorCorrection, Fail tags, PixelFormat (12 JS formats), DecodeOptions, BatchResult, exceptions
├── Points.kt       # FinderPoints/FinderPoint/BoundingBox/DecodeResult (pointsOnDetect payload)
├── Layer.kt        # Pyramid layer + Plane: downscale, 8x8 block min/max, cuts, packed bitmap
├── Finder.kt       # 1:1:3:1:1 run scan (both polarities), cross-check, refinement, Pattern/Triple
├── Selection.kt    # Mandatory triple pick, polarity choice, exclusion, retry schedule/nextSet
├── Projection.kt   # Dimension candidates, alignment search, timing gate, fine upgrade, v7+ tiles, sampling, report()
├── Homography.kt   # Projective maps, V8 Math.hypot / Math.cbrt ports
├── GridDecoder.kt  # Version check, format candidates, unmask/zigzag, deinterleave, mirror retry
├── RS.kt           # Reed-Solomon generator cache + Berlekamp-Massey/Chien/Forney corrector
├── GF256.kt        # GF(2^8) EXP/LOG tables (0x11d)
├── Payload.kt      # Segment parser: numeric, alphanumeric, byte, ECI (WHATWG charsets)
├── QRInfo.kt       # Spec tables (BYTES, WORDS_PER_BLOCK, ECC_BLOCKS), mask/format/version bits
├── QREncoder.kt    # Encoder facade (JS encodeQR): validation, version pick, encodeRaw/Ascii/Term/Svg/Gif/DataUrl
├── Segments.kt     # Mode detection, capacity/byteCapacity, single-segment bit stream + padding + RS interleave
├── Symbol.kt       # Alignment positions, per-version template/zigzag cache, drawSymbol (mask pick), drawInfo
├── Penalty.kt      # Mask penalty score (N1-N4) on the test-form symbol
├── Renderers.kt    # Raster (border/scale map) + raw/ascii/term/svg/gif/data-url renderers, internal base64
└── Utf8.kt         # WHATWG TextEncoder UTF-8 (lone surrogate -> U+FFFD) and UTF-8 length

tools/              # JS dump scripts that generate the parity fixtures (see tools/README.md)
```

Tests (`src/test/kotlin/qr/`): `SyntheticTest`, `QRDecoderTest`, `QRScannerTest`, `RealImageTest`,
`ImageDecodingTest` (BoofCV parity incl. points), `PointsTest`, `CallbacksTest`, `BatchTest`, `VectorTest` (small-vectors), `LayerDumpTest`/`FinderDumpTest`/
`ScheduleDumpTest` (stage parity), `LumaParityTest`, `HomographyTest`, `RSTest`, `PayloadTest`,
`QRInfoTest`, `ImageVectorsTest`; encoder: `EncoderTest` (raw fixtures, JS drawSymbol/API/encodeData
goldens, spec tables), `EncoderVectorTest` (small vectors via `encodeAscii`), `PenaltyTest`,
`EncodeOptionsTest` (validation and option semantics), `RendererTest` (render fixtures, GIF structure +
ImageIO + decoder round-trip), `Utf8Test`; helpers `TestSymbols` (codewords → symbol matrix for decoder
tests), `JsEncoderGoldens` (verbatim JS test data), `JpegLuma`/`PngLuma` (ports of JS test/misc/jpg.ts,
png.ts), `TestImages`, `ImageVectors` (generated).

## Pipeline (decode.ts)

1. **Luma**: `addImage` (or `processImage` in place over `luma`) converts one of the 12 JS formats
   (RGB/RGBA/RGBX/BGRA/BGRX via `(r + 2g + b) >> 2`; I420/I420A/I422/I444/NV12 leading Y plane;
   I420P10/I420P12 16-bit LE shifted to 8 bits; `GRAY` = `I420` alias; optional offset/stride) into
   the native layer; up to three 2x-downscaled pyramid layers (min side >= 64).
2. **Binarize** per layer: 8x8 block min/max, per-block cuts, packed 32-px-per-word bitmap.
3. **Finders**: row scan for 1:1:3:1:1 runs in both polarities, cross-check, refine, dedupe.
4. **Mandatory triple** per layer (coarsest first): best TL/TR/BL pick, polarity choice.
5. **Projection**: dimension candidates from finder pitch, alignment search, timing gate, fine-plane
   upgrade, tiled sampling for v7+.
6. **Grid decode**: format candidates, unmask, zigzag read, deinterleave, RS (BM), payload parse;
   retry on the transposed (mirrored) grid.
7. **Retries**: scheduled triple sets per layer while `effort` retries and `timeLimit` allow.
8. **Multi-QR**: `decode(all = true)` excludes decoded regions and repeats until a scan fails.

Failures are `Fail` tags (finder/dimension/alignment/timing/version/format/rs/data), not exceptions,
on the hot path; `QRDecoder` maps the terminal tag to an exception.

## Encoder pipeline (index.ts)

`QREncoder.prepare` follows `encodeQR` L1175-1258 in the JS check order:

1. **Validate**: version (1..40), then `detectType` (or `options.encoding`), then mode letters
   (`Unknown letter`), mask (0..7), UTF-8 length pre-check against `byteCapacity` (built-in encoder only).
2. **Segments**: `textEncoder`/`Utf8.encode` for byte mode,
   smallest fitting version (or the explicit one), then `Segments.encodeData`: one segment (mode,
   length, data), terminator, pad bytes, `RS.encode` per block, interleave.
3. **Symbol**: per-version template (finders, timing, alignment, reserved areas) and zigzag order,
   cached in an `AtomicReferenceArray(40)`; data bits placed along the zigzag.
4. **Mask**: with no `mask`, all eight are scored by `Penalty.score` on the test form (no format/version
   info); the first lowest wins.
5. **Info**: format bits, dark module, version bits (v7+).
6. **Raster**: border (> 0) and scale (1..1024) checked after encoding (capacity errors win), output
   side limit per renderer, output-pixel → module map.
7. **Render**: `Renderers.raw/ascii/term/svg/gif`, `dataUrl` = base64 of the GIF.

## Public API

- `QRDecoder.decode(image: Image): String`, `QRDecoder.decode(width, height, data): String` — JS
  defaults; throws `FinderNotFoundException` / `InvalidFormatException` / `InvalidVersionException` /
  `QRDecodingException(tag)`. Thread-safe (serialized); the cached scanner is rebuilt when the frame size changes.
- `QRDecoder.decode(image, options: DecodeOptions): String` — JS `decodeQR(img, opts)`; fresh scanner
  per call (callbacks are bound at construction), same exceptions plus IAE for invalid options.
- `QRDecoder.decodeBatch(images, options = DecodeOptions(), maxWidth = 3840, maxHeight = 3840):
  List<BatchResult>` — JS `decodeQRBatch` (synchronous); `BatchResult(texts, failure, error)`, a
  throwing image yields `error` without affecting its neighbours.
- `QRScanner(maxWidth, maxHeight, options: DecodeOptions, stride = 1)` (full options incl. callbacks;
  `stride` = bytes reserved per max-size pixel in `luma` for `processImage`) or the short
  `QRScanner(maxWidth, maxHeight, effort = 1, timeLimitMs = 1000/60, nativeLimit = Int.MAX_VALUE,
  textDecoder = null)`: construct once per camera stream, then `addImage(w, h, data, format, offset,
  stride)` / `addImage(image)` (or write into `luma` + `processImage(w, h, format, offset, stride)`;
  `processLuma` deprecated), `decode(all = false): List<String>`, `lastFailure`, `clean()`. Not
  thread-safe. `effort = Int.MAX_VALUE` = JS Infinity.
- `DecodeOptions(format, effort, timeLimitMs, nativeLimit, textDecoder, pointsOnDetect, imageOnResult,
  imageOnBitmap)` — JS `DecodeOpts`. `pointsOnDetect(FinderPoints, DecodeResult)` fires once per scan
  (success, or terminal failure after a report); `imageOnResult` gets the masked module grid (RGBA,
  1 px/module); `imageOnBitmap` each binarized layer.
- `QREncoder.encodeRaw(text, options = EncodeOptions()): Array<BooleanArray>` (rows `[y][x]`, true =
  dark), `encodeAscii(...): String`, `encodeTerm(...): String`, `encodeSvg(...): String`,
  `encodeGif(...): ByteArray`, `encodeDataUrl(...): String` — JS `encodeQR(text, output, opts)`.
  Stateless, thread-safe; every validation error is an `IllegalArgumentException` with the JS message.
- `EncodeOptions(ecc = MEDIUM, encoding: Encoding? = null, textEncoder: ((String) -> ByteArray)? = null,
  version: Int? = null, mask: Int? = null, border = 2, scale = 1, optimize = true)` — JS `QrOpts`;
  `Encoding` = NUMERIC, ALPHANUMERIC, BYTE.
- `PixelFormat`: RGB, RGBA, RGBX, BGRA, BGRX, I420, I420A, I422, I444, NV12, I420P10, I420P12;
  `PixelFormat.GRAY` is a `@JvmField` alias of `I420` (`GRAY.name == "I420"`, `valueOf("GRAY")` throws).

## Parity Testing

The port is verified stage by stage against fixtures dumped from the JS sources by `tools/js-dump/*`
(regeneration commands in `tools/README.md`):

- **Luma**: `JpegLuma`/`PngLuma` port the JS test image readers; `boofcv-luma-crc32.json` (539 images)
  guards that Kotlin feeds the decoder the exact JS luma.
- **Stages**: `dumps/layers/*.json` (per-layer luma/blocks/cuts/bitmap CRCs, finder records, mandatory
  pick) and `dumps/schedule/*.json` (retry sets and pop order) for 10 images + 3 synthetic PGMs (5 dumps).
- **Whole decode**: `dumps/decode-default.json` / `decode-all.json` hold the JS result or fail tag for
  all 565 images; `ImageDecodingTest` is strict (any mismatch fails) and also checks the JS DECODED
  table (`ImageVectors.kt`, generated by `tools/extract_decoded.py`).
- **Points**: `dumps/decode-points.json.gz` (default mode) and `dumps/decode-points-all.json.gz` (all mode,
  ordered event list) hold the JS result/tag plus every `pointsOnDetect` payload (flattened, shortest
  doubles; NaN/±Infinity/-0 as strings) with the callback set; `ImageDecodingTest.points*` compares
  every number with `Double.compare` and prints images whose points-mode outcome differs from
  `decode-default.json` (the report() hazard; none on the current corpus).
- **Math**: `hypot-fixture.txt`/`cbrt-fixture.txt` (V8 values).
- **Encoder**: `EncoderVectorTest` compares `encodeAscii` with the `out` string of all 9281 small vectors
  (exact strings). `encoder-fixtures.txt` pins JS `encodeQR(..., 'raw')` grids (`EncoderTest`);
  `encoder-renders.txt` pins JS svg/term/data-url/ascii outputs, incl. multi-digit SVG paths and the
  GIF `W*W % 126 == 0` clear-code case (`RendererTest`). GIF output is decoded with ImageIO and compared
  pixel-exact with `encodeRaw`, and decoded back to the text by `QRDecoder`. The upstream qr.test.ts /
  encode.test.ts goldens (drawSymbol grids, Full API outputs, encodeData codewords, python-qrcode
  penalty grids) are copied verbatim into `JsEncoderGoldens`; option/error cases are in
  `EncodeOptionsTest`, gif.test.ts cases in `RendererTest`.

## Test Results (2026-10-11, v0.7.2)

| Suite | Result |
|---|---|
| Small vectors (`VectorTest`) | 9281/9281 |
| BoofCV + issues + misc, default mode vs JS dump | 565/565 identical (text or fail tag) |
| Same, all mode (`decode(true)`) vs JS dump | 565/565 identical (ordered results + terminal tag) |
| `pointsOnDetect` default / all mode vs JS dump | 565/565 / 565/565 identical (outcome + every reported number) |
| JS DECODED table, default (536 detection images) | 311 decoded, JS 311 |
| JS DECODED table, all mode | 326 decoded, JS 326 |
| Encoder small vectors (`encodeAscii` vs `out`) | 9281/9281 identical |
| Encoder raw / render fixtures vs JS | all identical |
| GIF via ImageIO vs `encodeRaw` | pixel-exact |

## Accepted Deviations from JS

- ECI 2 (cp437) decodes via Java `IBM437`; JS fails because `TextDecoder` has no ibm437 label.
- `Math.hypot`/`Math.cbrt` are ported from V8 (java.lang/StrictMath differ in the last ulp).
- Non-QR exceptions: besides `QRDecodingException`, `IllegalArgumentException` is thrown for invalid
  input (side > 4096, bad buffer size) and `IllegalStateException` in the (practically unreachable)
  finder-storage-exhaustion case; callers such as camera analyzers should catch `Exception`.
- `decodeBatch` is synchronous (JS `decodeQRBatch` awaits `decodeAsync(true)`); results are identical.
- `pointsOnDetect` hazard parity: as in JS, setting the callback makes `report()` rewrite the scanner's
  homography (`map/from/to`) before the v7+ tile fallbacks, so it can change v7+ outcomes exactly as
  in JS. The `decode-points.json.gz` fixture runs with `pointsOnDetect` set and the reported geometry
  matches JS bit-exactly on all 565 images; no image in the current corpus changes outcome when the
  callback is set.
- Encoder errors: every validation error is an `IllegalArgumentException` with the JS message (JS mixes
  `Error`/`RangeError`/`TypeError`). JS type checks that Kotlin types make impossible are dropped.
- Encoder options are `Int`-only: JS fractional/NaN/Infinity `border`/`scale` cases do not apply.
- `data-url` uses an internal base64 encoder (no `java.util.Base64`, which needs Android API 26).
- Gray input: any buffer of at least `w * h` bytes other than exactly 3x/4x is treated as gray
  (Y-plane pass-through); strided input via `addImage(..., offset, stride)`.

## Known Limitations

- Kanji mode is not supported (JS does not decode it either).
- ECI charset availability depends on the runtime (Android ICU may lack e.g. IBM437, x-windows-949,
  ISO-8859-16) → such segments fail with `data`.
- Not ported: async/cooperative scanning (`decodeAsync`), `BarcodeDetector`, `dom.ts`.
