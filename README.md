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
qr/
├── src/main/kotlin/qr/
│   ├── QRDecoder.kt        # Main public API
│   ├── Image.kt            # Image data class
│   ├── Types.kt            # Point, Pattern, enums, exceptions
│   ├── Bitmap.kt           # 2D bitmap class
│   ├── PatternDetector.kt  # Finder/alignment pattern detection
│   ├── Transform.kt        # Perspective transformation
│   ├── BitDecoder.kt       # Data extraction
│   ├── QRInfo.kt           # QR constants/tables
│   ├── GaloisField.kt      # GF(256) math
│   ├── ReedSolomon.kt      # Error correction
│   └── Interleave.kt       # Block interleaving
├── src/test/kotlin/qr/
│   └── *.kt                # Unit and integration tests
└── test/vectors/           # Test vectors (git submodule)
```

## License

MIT License - see [LICENSE](LICENSE)

## Acknowledgments

This is a Kotlin port of Paul Miller's [paulmillr/qr](https://github.com/paulmillr/qr) JavaScript QR code library.

Test vectors from [paulmillr/qr-code-vectors](https://github.com/paulmillr/qr-code-vectors).
