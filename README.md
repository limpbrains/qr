# QR

A pure Kotlin library for QR code reading/decoding. Port of [Paul Miller's paulmillr/qr](https://github.com/paulmillr/qr) library.

No external dependencies.

## Features

- Decode QR codes from raw pixel data: all 12 JS pixel formats (RGB/RGBA/RGBX/BGRA/BGRX, I420/I420A/
  I422/I444/NV12 luma planes, 10/12-bit I420P10/I420P12) plus `GRAY`
- Optimized for camera frames - reusable `QRScanner`, pass the Y plane directly (with row stride)
- Supports QR versions 1-40
- All error correction levels (L, M, Q, H)
- Numeric, Alphanumeric, and Byte encoding modes
- ECI (Extended Channel Interpretation) - Supports 20+ character encodings (UTF-8, ISO-8859-*, Shift-JIS, Big5, GBK, etc.)
- Inverted QR code detection - Automatically detects and decodes white-on-black QR codes
- Perspective correction, mirrored codes, multiple QR codes per image
- Reed-Solomon error correction
- The full JS decoder option set: `effort`, `timeLimit`, `nativeLimit`, `textDecoder`, and the
  `pointsOnDetect` / `imageOnResult` / `imageOnBitmap` callbacks

## Installation

Add JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}
```

Then add the dependency:

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.github.limpbrains:qr:TAG")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.github.limpbrains:qr:TAG'
}
```

Replace `TAG` with the latest release tag (e.g., `v0.7.2`).

## Usage

### One-shot decode

```kotlin
import qr.QRDecoder

// Format is inferred from data.size: exactly 3 or 4 bytes/pixel -> RGB / RGBA, otherwise gray
// (1 byte/pixel, e.g. a camera Y plane).
val text: String = QRDecoder.decode(width, height, data)
```

Inference only looks at the length, so a padded RGBA buffer (not exactly 4 bytes/pixel) is read as
gray and a 3-plane YUV444 buffer as RGB rather than rejected; pass an explicit `PixelFormat` to
`QRScanner.addImage` when the layout is ambiguous.

`QRDecoder` uses the JS defaults (effort 1, 1/60 s retry budget) and is thread-safe; calls are
serialized on one cached scanner.

### Camera frames: `QRScanner`

For a stream of frames, construct a `QRScanner` once with the largest frame size and reuse it. It
allocates all buffers up front and is not thread-safe (one scanner per analyzer thread).

```kotlin
import qr.PixelFormat
import qr.QRScanner

val scanner = QRScanner(maxWidth = 1920, maxHeight = 1080) // effort = 1, timeLimitMs = 1000.0 / 60

private var bytes = ByteArray(0) // reused across frames

// Android CameraX ImageAnalysis.Analyzer
override fun analyze(image: ImageProxy) {
    try {
        val y = image.planes[0]
        val n = y.buffer.remaining()
        if (bytes.size < n) bytes = ByteArray(n)
        y.buffer.get(bytes, 0, n)
        // The Y plane is already luma: pass it with its row stride, no color conversion.
        scanner.addImage(image.width, image.height, bytes, PixelFormat.GRAY, offset = 0, stride = y.rowStride)
        scanner.decode().firstOrNull()?.let(onQRCodeDetected) // empty list = nothing decoded
    } finally {
        image.close()
    }
}
```

`decode(all = true)` returns every QR code in the frame. After a failed decode,
`scanner.lastFailure` holds the reason (`Fail.FINDER`, `Fail.RS`, ...). `clean()` zeroes all buffers.

The short constructor `QRScanner(maxWidth, maxHeight, effort, timeLimitMs, nativeLimit, textDecoder)`
covers the plain options; `QRScanner(maxWidth, maxHeight, options: DecodeOptions, stride = 1)` takes
the full set, including callbacks.

### `DecodeOptions`

`DecodeOptions` is the JS `DecodeOpts`. Every field is optional:

| Field | Default | Meaning |
|---|---|---|
| `format` | `null` | Input `PixelFormat`. With `null`, the format is inferred from the buffer length (see below). |
| `effort` | `1` | Retry budget, >= 1. Higher values try more finder triples per frame. `Int.MAX_VALUE` is JS `Infinity`. |
| `timeLimitMs` | `1000.0 / 60` | Wall-time budget for retries. Finite and >= 0, or `Double.POSITIVE_INFINITY` for no limit. |
| `nativeLimit` | `Int.MAX_VALUE` | Gate for the native-resolution search layer, >= 0. |
| `textDecoder` | `null` | Custom `(bytes, eci) -> String` for byte segments. |
| `pointsOnDetect` | `null` | `(FinderPoints, DecodeResult) -> Unit`. See [Callbacks](#callbacks). |
| `imageOnResult` | `null` | `(Image) -> Unit`. Receives the sampled module grid. |
| `imageOnBitmap` | `null` | `(Image) -> Unit`. Debug hook that receives each binarized layer. |

Invalid values throw `IllegalArgumentException` when the scanner is constructed.

### Pixel formats

| `PixelFormat` | Bytes/pixel (`step`) | Luma source |
|---|---|---|
| `RGB` | 3 | `(r + 2g + b) >> 2` |
| `RGBA`, `RGBX`, `BGRA`, `BGRX` | 4 | Same formula; the fourth byte is ignored. The formula is symmetric in r/b, so BGR order needs no swap. |
| `I420`, `I420A`, `I422`, `I444`, `NV12` | 1 | The leading Y plane, 8-bit. Chroma and alpha planes are not read. |
| `I420P10`, `I420P12` | 2 | The leading Y plane, 10/12-bit little-endian samples, shifted down to 8 bits. |
| `GRAY` | 1 | Kotlin alias of `I420` (a tight 8-bit luma plane). |

`PixelFormat.GRAY` is no longer its own enum entry: since the API-completion change it is a
`@JvmField` alias of `I420`, so binary access to `PixelFormat.GRAY` keeps working. The change is
source-breaking for an exhaustive `when` over `PixelFormat` and for code that relies on `name`,
`valueOf`, `ordinal` or `entries`: `PixelFormat.GRAY.name == "I420"`, and `PixelFormat.valueOf("GRAY")` throws.

When `format` is `null`, `addImage` infers only RGB or RGBA, and only from a length of exactly
3·px or 4·px bytes, as JS does. `Image.format` (used by `QRDecoder`) also treats any other buffer of
at least px bytes as `GRAY`.

### `stride` and `processImage` (zero-copy input)

`addImage(w, h, data, format, offset, stride)` reads a strided source. Here `offset` and `stride` are
in bytes, and `stride = 0` means tight rows.

Integrations that can write pixels straight into the scanner do this:

1. Construct the scanner with `stride`, the number of bytes reserved per maximum-size pixel. This
   makes `scanner.luma` large enough.
2. Write the raw frame into `scanner.luma`.
3. Call `processImage`. It converts the frame to tight luma in place.

```kotlin
val scanner = QRScanner(1920, 1080, DecodeOptions(), stride = 4) // luma holds 1920*1080*4 bytes
frameBuffer.get(scanner.luma, 0, width * height * 4)               // raw BGRA written in place
scanner.processImage(width, height, PixelFormat.BGRA)              // stride defaults to width * 4
val texts = scanner.decode()
```

`processImage(w, h)` with its defaults (I420) handles tight 8-bit luma. The older
`processLuma(w, h)` is deprecated in its favour.

### `decode(image, options)` and `decodeBatch`

```kotlin
val text = QRDecoder.decode(Image(w, h, rgba), DecodeOptions(format = PixelFormat.RGBA, effort = 4))

val batch: List<BatchResult> = QRDecoder.decodeBatch(images, DecodeOptions())
batch.forEach { r -> println("${r.texts} ${r.failure} ${r.error}") }
```

`QRDecoder.decode(image, options)` builds a fresh scanner, with fresh buffers, on every call, because
options and callbacks are bound to a scanner. Camera loops should keep their own
`QRScanner(w, h, options)` instead.

`decodeBatch` decodes every QR code in each image through one reusable scanner. Its capacity is set
by `maxWidth`/`maxHeight` and defaults to 3840x3840. For each image it returns a `BatchResult(texts,
failure, error)`. An image that throws (invalid, or too large) gets the exception in `error` and
does not affect the images around it. Unlike the JS `decodeQRBatch`, it is synchronous.
The default 3840x3840 arena is about 15 MB, so callers decoding small images should pass their
actual maximum size as `maxWidth`/`maxHeight`.

### Callbacks

- `pointsOnDetect(points: FinderPoints, result: DecodeResult)` fires **once per scan**. It fires when
  the scan succeeds (`DecodeResult.Text`), or when the scan fails terminally
  (`DecodeResult.Failure(fail)`) after finder geometry was projected. It does not fire when nothing
  got that far, for example on a blank image. With
  `decode(all = true)`, there is one call per scan. All coordinates are in input-image pixels.
  `FinderPoints` holds:
  - `tl`, `tr`, `bl`: `FinderPoint(x, y, moduleSize, corners)`, where `corners` is the projected
    7x7 finder square (clockwise from top-left)
  - `br`: the projected virtual fourth finder centre
  - `aligners`: the alignment patterns located in this attempt, as `FinderPoint`s with 5x5 corners
  - `bounds`: the outer edges of the symbol
  - `outline`: `bounds` plus one module of padding, for overlays
  - `boundingBox`: `BoundingBox(x, y, width, height)` of `bounds`

  As in JS, setting this callback makes the scanner re-project the finder quad before the v7+ tile
  fallbacks, so it can change the outcome on high-version codes. The parity fixture runs with
  `pointsOnDetect` set, and the reported geometry matches JS bit-exactly on all 565 images; no image
  in the current corpus changes outcome when the callback is set.
- `imageOnResult(image)` fires on success with the sampled module grid. The image is RGBA, one
  pixel per module, black or white, and still masked.
- `imageOnBitmap(image)` is a debug hook. It fires with each binarized layer, as RGBA, before finder
  detection.

Exceptions thrown from a callback propagate out of `decode`.

### Error handling (`QRDecoder`)

```kotlin
try {
    val result = QRDecoder.decode(image)
} catch (e: FinderNotFoundException) {
    // no QR code found
} catch (e: QRDecodingException) {
    // found but not decodable; e.message is the JS failure tag (timing, version, rs, ...)
}
```

Besides `QRDecodingException`, `IllegalArgumentException` is thrown for invalid input (side > 4096,
bad buffer size) and `IllegalStateException` in the (practically unreachable) finder-storage-exhaustion
case, so callers such as camera analyzers should catch `Exception`.

## Requirements

- JDK 17 or higher

## Building from Source

```bash
git submodule update --init --recursive

# Build
./gradlew build

# Run tests
./gradlew test

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

## Compatibility with paulmillr/qr

This is a port of the decoder from paulmillr/qr. Results are identical to the JS
decoder on every test image (565/565 BoofCV/issue/misc images, default and all-results modes) and on
all 9281 small vectors; see [CLAUDE.md](CLAUDE.md) for the parity tests and the few accepted
deviations. The reported `pointsOnDetect` geometry is also bit-identical, as checked by
`decode-points.json.gz`. Kotlin additions are gray (Y-plane) input with offset/stride and the
synchronous `QRScanner` API. Not ported: async/cooperative scanning (`decodeAsync`), `BarcodeDetector`
and the browser helpers (`dom.ts`).

## Acknowledgments

- Original JavaScript library: [paulmillr/qr](https://github.com/paulmillr/qr) by Paul Miller
- Test vectors: [paulmillr/qr-code-vectors](https://github.com/paulmillr/qr-code-vectors)
- For React Native, use [react-native-camera-kit-no-google](https://github.com/limpbrains/react-native-camera-kit-no-google) for high-performance camera scanning.

## License

Copyright (c) 2025 limpbrains

Copyright (c) 2023 Paul Miller [(paulmillr.com)](https://paulmillr.com)

Copyright (c) 2019 ZXing authors

The library is dual-licensed under the Apache 2.0 OR MIT license.
You can select a license of your choice.

The library is derived from ZXing, which is licensed under Apache 2.0.

QR Code is trademarked by DENSO WAVE INCORPORATED.
The word "QR Code" used in this project is for identification purposes only.

The specification is not covered by any known patents:
QR Code was patented, but Denso Wave chose not to exercise those rights.
