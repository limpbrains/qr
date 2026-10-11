# QR

A pure Kotlin library for QR code reading/decoding. Port of [Paul Miller's paulmillr/qr](https://github.com/paulmillr/qr) library.

No external dependencies.

## Features

- Decode QR codes from raw pixel data (Grayscale, RGB, or RGBA)
- Optimized for camera frames - reusable `QRScanner`, pass the Y plane directly (with row stride)
- Supports QR versions 1-40
- All error correction levels (L, M, Q, H)
- Numeric, Alphanumeric, and Byte encoding modes
- ECI (Extended Channel Interpretation) - Supports 20+ character encodings (UTF-8, ISO-8859-*, Shift-JIS, Big5, GBK, etc.)
- Inverted QR code detection - Automatically detects and decodes white-on-black QR codes
- Perspective correction, mirrored codes, multiple QR codes per image
- Reed-Solomon error correction

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

Alternatively write tight luma rows straight into `scanner.luma` and call
`scanner.processLuma(width, height)` before `decode()` (zero copy).

Options (constructor):

- `effort` (default 1): retry budget; higher values try more finder triples per frame,
  `Int.MAX_VALUE` = unlimited.
- `timeLimitMs` (default `1000.0 / 60`): wall-time budget for retries, `Double.POSITIVE_INFINITY` = none.
- `nativeLimit` (default `Int.MAX_VALUE`): native-resolution search gate, settable between frames.
- `textDecoder`: custom `(bytes, eci) -> String` for byte segments.

`decode(all = true)` returns every QR code in the frame. After a failed decode,
`scanner.lastFailure` holds the reason (`Fail.FINDER`, `Fail.RS`, ...). `clean()` zeroes all buffers.

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
deviations. Kotlin additions: gray (Y-plane) input with offset/stride and the synchronous `QRScanner`
API. Kanji mode and the JS callbacks (`pointsOnDetect`, `imageOnResult`) are not supported.

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
