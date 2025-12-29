# QR

A pure Kotlin library for QR code reading/decoding. Port of [Paul Miller's paulmillr/qr](https://github.com/paulmillr/qr) library.

No external dependencies.

## Features

- Decode QR codes from raw RGBA/RGB pixel data
- Supports QR versions 1-40
- All error correction levels (L, M, Q, H)
- Numeric, Alphanumeric, and Byte encoding modes
- Automatic perspective correction
- Reed-Solomon error correction

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.limpbrains:qr:0.1.0")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.github.limpbrains:qr:0.1.0'
}
```

## Usage

### Basic Usage

```kotlin
import qr.QRDecoder
import qr.Image

// From raw RGBA bytes
val width = 640
val height = 480
val rgbaData: ByteArray = // ... get image data from your source

val decoded = QRDecoder.decode(width, height, rgbaData)
println(decoded)

// Or using Image class
val image = Image(width, height, rgbaData)
val decoded2 = QRDecoder.decode(image)
```

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

## License

MIT License - see [LICENSE](LICENSE)

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
