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

## JS vs Kotlin Parity Issue

**Current status**: 97/118 tests pass (82.20%)

### Root Cause: JPEG Decoder Differences

JS (jpeg-js) and Kotlin (ImageIO) use different JPEG decoders that produce slightly different pixel values:
- **JS:** r=125, g=112, b=110, brightness=114
- **Kotlin:** r=126, g=112, b=112, brightness=115

This 1-2 pixel difference propagates through:
1. Brightness calculation → 2. Block averages → 3. Binary bitmap → 4. Finder positions → 5. Perspective transform → 6. Bit extraction → 7. RS.decode errors

### Solutions Implemented

1. **Threshold adjustment for borderline pixels** (AVAILABLE - not enabled by default)
   - Added `thresholdOffset` parameter to `PatternDetector.toBitmap()`
   - Retry logic in `QRDecoder.decode()` can try offsets [0, 1, -1, 2, -2, ...]
   - Testing showed: `[0, ..., 5, -5]` improves pass rate to 107/118 (90.68%)
   - Disabled by default for JS performance parity (uses offset=0 only)
   - To enable: modify `THRESHOLD_OFFSETS` in `QRDecoder.kt`

2. **More robust finder detection** (IMPLEMENTED)
   - Added retry logic in `PatternDetector.findFinder()` with relaxed variance parameters
   - Tries normal variance (2.0), then lenient (2.5), then very lenient (3.0) with lower confirmations
   - Helps detect finder patterns in degraded or blurry images

### Remaining Failures (21 tests)

- 10 RS.decode errors: Perspective transform or bit extraction issues
- 5 FinderNotFound: Images too degraded or only 0-2 patterns found
- 5 InvalidFormat/Version: Format pattern reading errors
- 1 glare/image049.jpg: Various issues

### Possible Further Improvements

1. **Enable threshold retry** - improves to 90.68% but adds overhead

2. **Multiple perspective transform attempts**
   - If RS.decode fails, try slightly adjusted transform points
   - Use alignment pattern candidates with small offsets

3. **Use same JPEG decoder**
   - Port jpeg-js to Kotlin or use a common library for perfect parity

### Key Files for Parity Work
- `PatternDetector.kt:toBitmap()` - Binary thresholding (threshold adjustment here)
- `PatternDetector.kt:findFinder()` - Finder pattern detection (robustness here)
- `QRDecoder.kt:decode()` - Entry point (retry logic here)
- `Transform.kt:transform()` - Perspective transform
