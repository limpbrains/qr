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
├── VectorTest.kt     # Integration tests using qr-code-vectors
├── QRDecoderTest.kt  # Decoder API tests
├── QRInfoTest.kt     # QR constants/tables tests
├── BitmapTest.kt     # Bitmap operations tests
├── GaloisFieldTest.kt # GF math tests
└── ReedSolomonTest.kt # RS codec tests
```

## Key Components

### Public API (`QRDecoder.kt`)
- `QRDecoder.decode(image: Image): String` - Main entry point
- `QRDecoder.decode(width, height, data): String` - Convenience method

### Decoding Pipeline
1. **Image → Bitmap**: Adaptive grayscale thresholding (8x8 blocks)
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
- `small-vectors.json.gz`: ASCII-art QR codes for encoding tests
- `boofcv-v3/`: JPEG images for real-world decoding tests

The `VectorTest.kt` uses a streaming JSON parser to avoid memory issues with the large vector file.

## Dependencies

- Kotlin stdlib only (no external image libraries)
- JDK 17+

## Known Limitations

- Decoder works best with Version 1-2 QR codes (smaller codes)
- Higher version QR codes may fail pattern detection
- No support for Kanji encoding mode
