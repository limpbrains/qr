# keklol

A Kotlin library providing string utilities and QR code reading capabilities.

## Features

### String Utilities
- `revertx(input: String)`: Reverses the given string

### QR Code Reader
- `QRDecoder.decode(image: Image)`: Decodes a QR code from an image
- `QRDecoder.decode(width: Int, height: Int, data: ByteArray)`: Decodes from raw pixel data

## Requirements

- JDK 17 or higher
- Gradle (if wrapper not present)

## Setup

If the Gradle wrapper is not present, generate it first:

```bash
gradle wrapper --gradle-version 8.5
```

Alternatively, you can use your local Gradle installation instead of `./gradlew` in the commands below.

## Build

To build the project:

```bash
./gradlew build
```

## Run Tests

To run the unit tests:

```bash
./gradlew test
```

To run tests with verbose output:

```bash
./gradlew test --info
```

## Usage

### String Reversal

```kotlin
import com.keklol.revertx

fun main() {
    val original = "hello"
    val reversed = revertx(original)
    println(reversed)  // Output: olleh
}
```

### QR Code Reading

```kotlin
import com.keklol.QRDecoder
import com.keklol.Image

fun main() {
    // From raw RGBA bytes (e.g., from an image library)
    val width = 640
    val height = 480
    val rgbaData: ByteArray = // ... get image data from your source

    val decoded = QRDecoder.decode(width, height, rgbaData)
    println(decoded)

    // Or using Image class
    val image = Image(width, height, rgbaData)
    val decoded2 = QRDecoder.decode(image)
}
```

### Error Handling

```kotlin
import com.keklol.*

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

## Project Structure

```
keklol/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   ├── main/kotlin/com/keklol/
│   │   ├── StringUtils.kt      # String reversal utility
│   │   ├── QRDecoder.kt        # Main QR decoding API
│   │   ├── Image.kt            # Image data class
│   │   ├── Types.kt            # Point, Pattern, enums
│   │   ├── Bitmap.kt           # 2D bitmap class
│   │   ├── GaloisField.kt      # GF(256) math
│   │   ├── ReedSolomon.kt      # Error correction
│   │   ├── QRInfo.kt           # QR constants/tables
│   │   ├── Interleave.kt       # Block interleaving
│   │   ├── PatternDetector.kt  # Pattern detection
│   │   ├── Transform.kt        # Perspective transform
│   │   └── BitDecoder.kt       # Data extraction
│   └── test/kotlin/com/keklol/
│       ├── StringUtilsTest.kt
│       ├── BitmapTest.kt
│       ├── GaloisFieldTest.kt
│       ├── ReedSolomonTest.kt
│       ├── QRInfoTest.kt
│       └── QRDecoderTest.kt
└── README.md
```

## Supported QR Code Features

- QR code versions 1-40
- All error correction levels (L, M, Q, H)
- Numeric, Alphanumeric, and Byte encoding modes
- Automatic perspective correction
- Reed-Solomon error correction

## Acknowledgments

The QR code reading implementation is a Kotlin port of [paulmillr/qr](https://github.com/paulmillr/qr), a JavaScript QR code library.
