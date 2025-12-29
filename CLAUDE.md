# QR - Kotlin QR Code Reader Library

**Repository**: https://github.com/limpbrains/qr

## Overview

A pure Kotlin library for reading/decoding QR codes from raw image data. This is a Kotlin port of Paul Miller's [paulmillr/qr](https://github.com/paulmillr/qr).

## Build & Test

```bash
./gradlew build      # Build the library
./gradlew test       # Run tests
./gradlew publishToMavenLocal  # Publish to local Maven repo
```

## Project Structure

```
src/main/kotlin/qr/
├── QRDecoder.kt      # Main public API (entry point)
├── Image.kt          # Image data class
├── Types.kt          # Point, Pattern, ErrorCorrection, EncodingType, exceptions
├── Bitmap.kt         # 2D bitmap representation
├── PatternDetector.kt # Finder & alignment pattern detection
├── Transform.kt      # Perspective transformation
├── BitDecoder.kt     # Data extraction from QR bitmap
├── QRInfo.kt         # QR code constants, capacity tables, templates
├── GaloisField.kt    # GF(256) finite field arithmetic
├── ReedSolomon.kt    # Reed-Solomon error correction
└── Interleave.kt     # Block interleaving for RS codes

src/test/kotlin/qr/
├── VectorTest.kt       # Integration tests using qr-code-vectors
├── ImageDecodingTest.kt # JPEG image decoding tests
├── QRDecoderTest.kt    # Decoder API tests
├── QRInfoTest.kt       # QR constants/tables tests
├── BitmapTest.kt       # Bitmap operations tests
├── GaloisFieldTest.kt  # GF math tests
└── ReedSolomonTest.kt  # RS codec tests
```

## Key Components

### Public API (`QRDecoder.kt`)
- `QRDecoder.decode(image: Image): String` - Main entry point
- `QRDecoder.decode(width, height, data): String` - Convenience method

### Supported Image Formats
- **Grayscale** (1 byte/pixel): Fastest, ideal for camera Y plane
- **RGB** (3 bytes/pixel): Standard format
- **RGBA** (4 bytes/pixel): With alpha channel

Format is auto-detected from `data.size / (width * height)`.

### Decoding Pipeline
1. **Image → Bitmap**: Adaptive grayscale thresholding (8x8 blocks)
   - Grayscale input: direct copy (no conversion)
   - RGB/RGBA input: weighted average `(r + 2*g + b) / 4`
2. **Pattern Detection**: Find 3 finder patterns (1:1:3:1:1 ratio)
3. **Alignment**: Locate alignment patterns for perspective correction
4. **Transform**: Apply perspective transformation
5. **Bit Extraction**: Read data in zigzag pattern
6. **RS Decode**: Apply Reed-Solomon error correction
7. **Parse**: Decode numeric/alphanumeric/byte segments

### Error Correction
- Uses GF(256) with primitive polynomial 0x11d
- Reed-Solomon encoding/decoding
- Supports all QR ECC levels (L/M/Q/H)

## Test Vectors

Test vectors are loaded from `test/vectors/` (git submodule from paulmillr/qr-code-vectors):
- `small-vectors.json.gz`: ASCII-art QR codes for decoding tests
- `boofcv-v3/`: JPEG images for real-world decoding tests

The `VectorTest.kt` uses a streaming JSON parser to avoid memory issues with the large vector file.

## Dependencies

- Kotlin stdlib only (no external image libraries)
- JDK 17+

## Known Limitations

- No support for Kanji/ECI encoding modes
- Higher version QR codes (Version 10+) may have lower success rates

## Test Results

| Test Suite | Pass Rate |
|------------|-----------|
| Small vectors (synthetic) | 9134/9281 (98.42%) |
| JPEG images (boofcv) | 113/118 (95.76%) |

## Differences from JavaScript Implementation

### 1. Grayscale Input Support (Kotlin-only)

Kotlin accepts grayscale (1 byte/pixel) input, allowing direct Y-plane pass-through from camera frames. This skips YUV→RGB→grayscale conversion for 3-5x faster processing.

**Location**: `Image.kt:24-30`, `PatternDetector.kt:56-68`

### 2. Triangle Validation (Kotlin-only)

Before early termination in finder detection, Kotlin validates that 3 patterns form a valid right isosceles triangle (ratio > 0.8). This prevents false positives when QR data contains 1:1:3:1:1-like patterns.

**Location**: `PatternDetector.kt:384-401`

### 3. Threshold Retry

To compensate for JPEG decoder differences (jpeg-js vs ImageIO), `QRDecoder` retries with offsets `[0, -5, 5]`.

**Location**: `QRDecoder.kt:21`

### 4. Relaxed Finder Variance

Finder detection retries with progressively relaxed variance parameters (2.0 → 2.5 → 3.0) to handle degraded images.

**Location**: `PatternDetector.kt:229-246`

## Key Files for Modifications

- `QRDecoder.kt` - Entry point, threshold retry logic
- `PatternDetector.kt` - Pattern detection, triangle validation, thresholding
- `Transform.kt` - Perspective transformation
- `BitDecoder.kt` - Data extraction and parsing
- `ReedSolomon.kt` - Error correction
