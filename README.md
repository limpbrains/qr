# QR

A pure Kotlin library for QR code reading/decoding. Port of [Paul Miller's paulmillr/qr](https://github.com/paulmillr/qr) library.

No external dependencies.

## Features

- Decode QR codes from raw pixel data (Grayscale, RGB, or RGBA)
- Optimized for camera frames - pass Y plane directly, skip color conversion
- Supports QR versions 1-40
- All error correction levels (L, M, Q, H)
- Numeric, Alphanumeric, and Byte encoding modes
- Automatic perspective correction
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
    implementation("com.github.limpbrains:qr:v0.0.1")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'com.github.limpbrains:qr:v0.0.1'
}
```

## Usage

### Basic Usage

```kotlin
import qr.QRDecoder
import qr.Image

// From raw pixel data (auto-detects format based on size)
val width = 640
val height = 480

// Grayscale (1 byte per pixel) - fastest, ideal for camera Y plane
val grayscaleData: ByteArray = // ... width * height bytes
val decoded = QRDecoder.decode(width, height, grayscaleData)

// RGB (3 bytes per pixel)
val rgbData: ByteArray = // ... width * height * 3 bytes
val decoded2 = QRDecoder.decode(width, height, rgbData)

// RGBA (4 bytes per pixel)
val rgbaData: ByteArray = // ... width * height * 4 bytes
val decoded3 = QRDecoder.decode(width, height, rgbaData)
```

### Android CameraX Integration

For optimal performance with Android CameraX, extract the Y (luminance) plane directly. The Y plane is already grayscale - no color conversion needed:

```kotlin
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import qr.QRDecoder
import qr.QRDecodingException

class QRCodeAnalyzer(
    private val onQRCodeDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val grayscaleData = extractYPlane(image)
            val decoded = QRDecoder.decode(image.width, image.height, grayscaleData)
            onQRCodeDetected(decoded)
        } catch (e: QRDecodingException) {
            // No QR code found - expected for most frames
        } finally {
            image.close()
        }
    }

    private fun extractYPlane(image: ImageProxy): ByteArray {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val width = image.width
        val height = image.height
        val yBytes = ByteArray(width * height)

        if (rowStride == width) {
            // Fast path: no padding
            yBuffer.rewind()
            yBuffer.get(yBytes, 0, width * height)
        } else {
            // Handle row stride padding
            yBuffer.rewind()
            for (row in 0 until height) {
                yBuffer.position(row * rowStride)
                yBuffer.get(yBytes, row * width, width)
            }
        }
        return yBytes
    }
}
```

This approach is **3-5x faster** than converting YUV to RGB first, as it skips:
- U/V plane extraction
- YUV to RGB color conversion (6 float multiplications per pixel)
- RGB to grayscale conversion

### Error Handling

```kotlin
import qr.*

try {
    val result = QRDecoder.decode(image)
} catch (e: ImageTooSmallException) {
    println("Image is too small for QR detection")
} catch (e: FinderNotFoundException) {
    println("No QR code finder patterns detected")
} catch (e: InvalidFormatException) {
    println("Invalid QR code format")
} catch (e: QRDecodingException) {
    println("Failed to decode QR code: ${e.message}")
}
```

## Requirements

- JDK 17 or higher

## Building from Source

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Publish to local Maven repository
./gradlew publishToMavenLocal
```

## Project Structure

```
src/main/kotlin/qr/
├── QRDecoder.kt        # Main public API (entry point)
├── Image.kt            # Image data class
├── Types.kt            # Point, Pattern, enums, exceptions
├── Bitmap.kt           # 2D bitmap class
├── PatternDetector.kt  # Finder/alignment pattern detection
├── Transform.kt        # Perspective transformation
├── BitDecoder.kt       # Data extraction
├── QRInfo.kt           # QR constants/tables
├── GaloisField.kt      # GF(256) math
├── ReedSolomon.kt      # Error correction
└── Interleave.kt       # Block interleaving

src/test/kotlin/qr/
└── *.kt                # Unit and integration tests

test/vectors/           # Test vectors (git submodule)
```

## Differences from JavaScript Implementation

This Kotlin library is a port of the JavaScript [paulmillr/qr](https://github.com/paulmillr/qr) library with the following differences:

### 1. Triangle Validation for Finder Patterns (Kotlin-only enhancement)

Before stopping early during finder pattern detection, the Kotlin implementation validates that the 3 found patterns form a valid right isosceles triangle (ratio of two shorter sides > 0.8). This prevents false positives when QR data accidentally contains patterns matching the 1:1:3:1:1 finder ratio.

**Impact:** +83 synthetic QR codes decoded correctly, -2 JPEG images.

### 2. Threshold Retry for JPEG Decoder Differences

JS (jpeg-js) and Kotlin (ImageIO) JPEG decoders produce slightly different pixel values (1-2 pixel brightness difference). To compensate, `QRDecoder` retries with threshold offsets `[0, -5, 5]`.

To adjust, modify `THRESHOLD_OFFSETS` in `QRDecoder.kt`.

### 3. No Kanji/ECI Encoding Support

Same as the JS library - Kanji and ECI encoding modes are not supported.

## Test Results

Tested against [paulmillr/qr-code-vectors](https://github.com/paulmillr/qr-code-vectors):

| Test Suite | Pass Rate |
|------------|-----------|
| Small vectors (synthetic ASCII art QR) | 9134/9281 (98.42%) |
| JPEG images (boofcv-v3) | 113/118 (95.76%) |

## Acknowledgments

- Original JavaScript library: [paulmillr/qr](https://github.com/paulmillr/qr) by Paul Miller
- Test vectors: [paulmillr/qr-code-vectors](https://github.com/paulmillr/qr-code-vectors)

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
