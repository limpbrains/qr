# QR - Kotlin QR Code Reader Library

**Repository**: https://github.com/limpbrains/qr

A pure Kotlin library (stdlib only, JDK 17+) for reading/decoding QR codes from raw image data.
This is a Kotlin port of the decoder in Paul Miller's [paulmillr/qr](https://github.com/paulmillr/qr),
kept bit-exact with the JS. Encoding is not ported.

## Build & Test

```bash
./gradlew build
./gradlew test                    # everything (~18 s)
./gradlew test -PskipExhaustive   # skip @Tag("exhaustive"): ImageDecodingTest + full VectorTest (~15 s)
./gradlew test -PonlyExhaustive   # only the exhaustive tests
./gradlew publishToMavenLocal
```

`test/vectors` is a git submodule (paulmillr/qr-code-vectors): `git submodule update --init --recursive`.

## Project Structure

```
src/main/kotlin/qr/
├── QRDecoder.kt    # One-shot facade (JS decodeQR): shared QRScanner, Fail -> exceptions
├── QRScanner.kt    # Reusable scanner (JS _QRScanner): arenas, addImage/processLuma, decode, scan loop
├── Image.kt        # Image(width, height, data); format inferred from data length
├── Types.kt        # ErrorCorrection, Fail tags, PixelFormat, exceptions
├── Layer.kt        # Pyramid layer + Plane: downscale, 8x8 block min/max, cuts, packed bitmap
├── Finder.kt       # 1:1:3:1:1 run scan (both polarities), cross-check, refinement, Pattern/Triple
├── Selection.kt    # Mandatory triple pick, polarity choice, exclusion, retry schedule/nextSet
├── Projection.kt   # Dimension candidates, alignment search, timing gate, fine upgrade, v7+ tiles, sampling
├── Homography.kt   # Projective maps, V8 Math.hypot / Math.cbrt ports
├── GridDecoder.kt  # Version check, format candidates, unmask/zigzag, deinterleave, mirror retry
├── RS.kt           # Reed-Solomon generator cache + Berlekamp-Massey/Chien/Forney corrector
├── GF256.kt        # GF(2^8) EXP/LOG tables (0x11d)
├── Payload.kt      # Segment parser: numeric, alphanumeric, byte, ECI (WHATWG charsets)
└── QRInfo.kt       # Spec tables (BYTES, WORDS_PER_BLOCK, ECC_BLOCKS), mask/format/version bits

tools/              # JS dump scripts that generate the parity fixtures (see tools/README.md)
```

Tests (`src/test/kotlin/qr/`): `SyntheticTest`, `QRDecoderTest`, `QRScannerTest`, `RealImageTest`,
`ImageDecodingTest` (BoofCV parity), `VectorTest` (small-vectors), `LayerDumpTest`/`FinderDumpTest`/
`ScheduleDumpTest` (stage parity), `LumaParityTest`, `HomographyTest`, `RSTest`, `PayloadTest`,
`QRInfoTest`, `TestEncoderTest`, `ImageVectorsTest`; helpers `TestEncoder` (port of JS encodeQR for synthetic symbols),
`JpegLuma`/`PngLuma` (ports of JS test/misc/jpg.ts, png.ts), `TestImages`, `ImageVectors` (generated).

## Pipeline (decode.ts)

1. **Luma**: `addImage` converts gray/RGB/RGBA (`(r + 2g + b) >> 2`, optional offset/stride) into the
   native layer; up to three 2x-downscaled pyramid layers (min side >= 64).
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

## Public API

- `QRDecoder.decode(image: Image): String`, `QRDecoder.decode(width, height, data): String` — JS
  defaults; throws `FinderNotFoundException` / `InvalidFormatException` / `InvalidVersionException` /
  `QRDecodingException(tag)`. Thread-safe (serialized); the cached scanner is rebuilt when the frame size changes.
- `QRScanner(maxWidth, maxHeight, effort = 1, timeLimitMs = 1000/60, nativeLimit = Int.MAX_VALUE,
  textDecoder = null)`: construct once per camera stream, then `addImage(w, h, data, format, offset,
  stride)` (or write into `luma` + `processLuma(w, h)`), `decode(all = false): List<String>`,
  `lastFailure`, `clean()`. Not thread-safe. `effort = Int.MAX_VALUE` = JS Infinity.

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
- **Math**: `hypot-fixture.txt`/`cbrt-fixture.txt` (V8 values); `encoder-fixtures.txt` for TestEncoder.

## Test Results (2026-10-11, v0.7.2)

| Suite | Result |
|---|---|
| Small vectors (`VectorTest`) | 9281/9281 |
| BoofCV + issues + misc, default mode vs JS dump | 565/565 identical (text or fail tag) |
| Same, all mode (`decode(true)`) vs JS dump | 565/565 identical (ordered results + terminal tag) |
| JS DECODED table, default (536 detection images) | 311 decoded, JS 311 |
| JS DECODED table, all mode | 326 decoded, JS 326 |
| `./gradlew test` | 742 tests, 0 failures |

## Accepted Deviations from JS

- ECI 2 (cp437) decodes via Java `IBM437`; JS fails because `TextDecoder` has no ibm437 label.
- `Math.hypot`/`Math.cbrt` are ported from V8 (java.lang/StrictMath differ in the last ulp).
- Non-QR exceptions: besides `QRDecodingException`, `IllegalArgumentException` is thrown for invalid
  input (side > 4096, bad buffer size) and `IllegalStateException` in the (practically unreachable)
  finder-storage-exhaustion case; callers such as camera analyzers should catch `Exception`.
- Gray input: any buffer of at least `w * h` bytes other than exactly 3x/4x is treated as gray
  (Y-plane pass-through); strided input via `addImage(..., offset, stride)`.

## Known Limitations

- Kanji mode is not supported (JS does not decode it either).
- ECI charset availability depends on the runtime (Android ICU may lack e.g. IBM437, x-windows-949,
  ISO-8859-16) → such segments fail with `data`.
- Not ported: `pointsOnDetect` / `imageOnResult` / `imageOnBitmap` callbacks, point reporting, async/cooperative scanning.
