# tools

Generators for the JS-parity fixtures under `src/test/resources` and `ImageVectors.kt`. All of them
read the upstream sources from a qr-paulmillr checkout (in the monorepo: `../qr-paulmillr`, pinned to
the version the Kotlin port tracks, currently v0.7.2 / `951806d`) and the images from
`test/vectors` (git submodule). Node >= 22 is required; `--experimental-strip-types` lets node import
the `.ts` sources directly. Commands below run from the `qr/` directory.

| Script | Arguments | Output | Used by |
|---|---|---|---|
| `extract_decoded.py` | `<decode.test.ts> <js-commit-sha>` | Kotlin source (stdout) | `ImageVectors.kt` (DECODED table, DECODED_ALL_ONLY, high-density PNG text) |
| `js-dump/dump-decode.mjs` | `[--all] <qr-paulmillr-dir> [image...]` | JSON (stdout), default: all detection/decoding/issues/misc images | `dumps/decode-default.json`, `dumps/decode-all.json` (ImageDecodingTest) |
| `js-dump/dump-layers.mjs` | `<qr-paulmillr-dir> <image> [maxW maxH]` | JSON (stdout): per-layer CRCs, finder records, mandatory pick | `dumps/layers/*.json` (LayerDumpTest, FinderDumpTest) |
| `js-dump/dump-layers.mjs` | `--schedule <qr-paulmillr-dir> <image> [maxW maxH]` | JSON (stdout): retry-schedule sets and pop order | `dumps/schedule/*.json` (ScheduleDumpTest) |
| `js-dump/dump-layers.mjs` | `--synthetic <W> <H> <out.pgm>` | deterministic gray PGM | `dumps/synthetic-*.pgm` |
| `js-dump/luma-crc.mjs` | `<qr-paulmillr-dir> <out.json>` (or `--raw <image> <out.gray>` to dump one luma plane) | JSON | `boofcv-luma-crc32.json` (LumaParityTest) |
| `js-dump/math-fixtures.mjs` | `<out-dir>` | `hypot-fixture.txt`, `cbrt-fixture.txt` (V8 `Math.hypot`/`Math.cbrt`) | HomographyTest |
| `js-dump/encoder-fixtures.mjs` | `<qr-paulmillr-dir>` | text (stdout): `encodeQR(..., 'raw')` grids | `encoder-fixtures.txt` (TestEncoderTest) |

`<image>` paths are relative to `test/vectors` (`.jpg`/`.png`, luma via the JS test readers
`test/misc/jpg.ts` / `png.ts`) or a `.pgm` path relative to the current directory.

## Regenerating everything

```bash
JS=../qr-paulmillr
n() { node --experimental-strip-types "$@"; }
R=src/test/resources

# DECODED capability table (pass the qr-paulmillr commit the port tracks)
python3 tools/extract_decoded.py $JS/test/decode.test.ts $(git -C $JS rev-parse --short HEAD) > src/test/kotlin/qr/ImageVectors.kt

# Whole-pipeline decode results (565 images each)
n tools/js-dump/dump-decode.mjs $JS > $R/dumps/decode-default.json
n tools/js-dump/dump-decode.mjs --all $JS > $R/dumps/decode-all.json

# Luma CRC guard (539 images)
n tools/js-dump/luma-crc.mjs $JS $R/boofcv-luma-crc32.json

# Synthetic PGMs
for s in "6 5" "32 48" "48 24"; do set -- $s; node tools/js-dump/dump-layers.mjs --synthetic $1 $2 $R/dumps/synthetic-${1}x${2}.pgm; done

# Layer and schedule dumps (same names/arguments for both directories)
dump() { # <name> <image> [maxW maxH]
  local name=$1; shift
  n tools/js-dump/dump-layers.mjs $JS "$@" > $R/dumps/layers/$name.json
  n tools/js-dump/dump-layers.mjs --schedule $JS "$@" > $R/dumps/schedule/$name.json
}
dump blurred-image003       boofcv-v3/detection/blurred/image003.jpg
dump brightness-image004    boofcv-v3/detection/brightness/image004.jpg
dump curved-image017        boofcv-v3/detection/curved/image017.jpg
dump high_version-image031  boofcv-v3/detection/high_version/image031.jpg
dump issues-invert          issues/invert.jpg
dump lots-image001          boofcv-v3/detection/lots/image001.jpg
dump nominal-image001       boofcv-v3/detection/nominal/image001.jpg
dump pathological-image005  boofcv-v3/detection/pathological/image005.png
dump perspective-image010   boofcv-v3/detection/perspective/image010.jpg
dump rotations-image021     boofcv-v3/detection/rotations/image021.jpg
dump synthetic-6x5          $R/dumps/synthetic-6x5.pgm
dump synthetic-6x5-max10x9  $R/dumps/synthetic-6x5.pgm 10 9
dump synthetic-32x48        $R/dumps/synthetic-32x48.pgm
dump synthetic-32x48-max44x60 $R/dumps/synthetic-32x48.pgm 44 60
dump synthetic-48x24        $R/dumps/synthetic-48x24.pgm

# V8 math references and encoder grids
node tools/js-dump/math-fixtures.mjs $R
n tools/js-dump/encoder-fixtures.mjs $JS > $R/encoder-fixtures.txt
```

After regenerating, run `./gradlew test`; a changed fixture means upstream behaviour changed and the
corresponding Kotlin code must be re-ported (see the sync-qr skill).
