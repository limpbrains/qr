#!/usr/bin/env node
// Dump the JS (paulmillr/qr v0.7.2) pyramid/binarizer/finder state per layer for Kotlin parity tests.
//
// Usage (node >= 22, needs --experimental-strip-types for the .ts sources):
//   node --experimental-strip-types tools/js-dump/dump-layers.mjs <qr-paulmillr-dir> <image> [maxW maxH]
//     <image>: a .jpg/.jpeg/.png path relative to qr/test/vectors (luma via the JS test readers
//              test/misc/jpg.ts decodeJPEGLuma / png.ts decodePNGLuma, as test/utils.ts readLuma does),
//              or a binary .pgm (P5, maxval 255) path relative to the current directory / absolute.
//     maxW maxH default to the image size.
//   node --experimental-strip-types tools/js-dump/dump-layers.mjs --schedule <qr-paulmillr-dir> <image> [maxW maxH]
//     Retry-schedule mode: after the same coarsest-first prepare + mandatory pick per layer, runs the
//     `schedule:` block (decode.ts L2895-3005, cooperative = false) and then pops every set with the
//     nextSet loop (L3006-3024) until exhaustion. The blocks are inline in the private `scan` generator, so
//     their exact source text is cut out of a temp copy of decode.ts and re-inserted as two methods
//     (`*_schedule(layer, cooperative)`, `_nextSet(layer)`). Output: { image, maxWidth, maxHeight, layers:
//     [{ index, setCount, sets: [[rank, inverted, i0, i1, i2]...] (sorted ascending), pops: [[tlIndex,
//     trIndex, blIndex, inverted]...] }] } (coarsest last, like the main mode).
//   node tools/js-dump/dump-layers.mjs --synthetic <W> <H> <out.pgm>
//     Writes the deterministic synthetic gray image used by LayerDumpTest:
//       x <  W/2: checkerboard of 3x3 cells, (cx + cy) even -> 30 else 220, plus (x*y) % 7
//       x >= W/2: low-contrast ramp 100 + (x + 2*y) % 20   (8x8 range <= 19 -> min/2 + predecessor rule)
//
// Output (stdout): JSON { image, width, height, maxWidth, maxHeight, layers: [{ index, width, height, words, blockWidth,
// blockHeight, lumaCrc, blocksCrc, cutsCrc, bitmapCrc, patternCount, patterns: [[x,y,ms,count,state]...], pick }] }
// Layers are prepared and picked coarsest first, as the round-0 walk of `_QRScanner.scan` does (L2827-2893), so
// pickPolarity sees every upper layer as found. `pick` = { ordinary: [i0,i1,i2,relative] | null, inverted: [...] | null,
// polarity: 0|1|null, triple: { tl:[x,y,ms], tr, bl, tlIndex, trIndex, blIndex, inverted } | null, pickSum, pickLo,
// pickHi } replicates the mandatory branch (L2863-2893) on top of the scanner's exclude/pickPolarity/makeTriple.
// Those are TS `private` members: type stripping erases the modifier, so they are called directly (no source patch);
// tmp64 is read the same way. Non-finite numbers are written as strings ("NaN", "Infinity", "-Infinity").
// CRC32 (zlib.crc32, unsigned, decimal) over: luma width*height bytes; blocks first blockWidth*blockHeight
// bytes; cuts first blockWidth*blockHeight entries as little-endian Int16; bitmap first words*height
// entries as little-endian Int32.
import { compactJson } from './compact-json.mjs';
import { copyFileSync, mkdtempSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { crc32 } from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const vectors = resolve(here, '..', '..', 'test', 'vectors');
const args = process.argv.slice(2);
const scheduleMode = args[0] === '--schedule';
if (scheduleMode) args.shift();

const synthetic = (W, H) => {
  const data = new Uint8Array(W * H);
  for (let y = 0; y < H; y++)
    for (let x = 0; x < W; x++)
      data[y * W + x] =
        x < W >> 1
          ? (((((x / 3) | 0) + ((y / 3) | 0)) & 1) === 0 ? 30 : 220) + ((x * y) % 7)
          : 100 + ((x + 2 * y) % 20);
  return data;
};

if (args[0] === '--synthetic') {
  const W = +args[1];
  const H = +args[2];
  const header = Buffer.from(`P5\n${W} ${H}\n255\n`, 'latin1');
  writeFileSync(args[3], Buffer.concat([header, Buffer.from(synthetic(W, H))]));
  process.exit(0);
}

if (args.length !== 2 && args.length !== 4) {
  console.error('usage: dump-layers.mjs <qr-paulmillr-dir> <image> [maxW maxH]');
  process.exit(1);
}
const dir = resolve(args[0]);
const image = args[1];

// scanRows is module-private (decode.ts L990): export it from a temp copy.
const tmp = mkdtempSync(join(tmpdir(), 'qr-dump-'));
copyFileSync(join(dir, 'src', 'decode.ts'), join(tmp, 'decode.ts'));
copyFileSync(join(dir, 'src', 'index.ts'), join(tmp, 'index.ts'));
appendFileSync(join(tmp, 'decode.ts'), '\nexport const _scanRows = scanRows;\n');
if (scheduleMode) {
  let src = readFileSync(join(tmp, 'decode.ts'), 'utf8');
  const start = src.indexOf('schedule: {');
  const next = src.indexOf('const sets = layer.sets;\n          while (layer.setCursor');
  const end = src.indexOf('\n        }\n        if (!triple) {', next);
  const anchor = src.indexOf('  // Re-run the scan after each success for decode-all');
  if (start < 0 || next < 0 || end < 0 || anchor < 0) throw new Error('decode.ts schedule blocks not found');
  const methods =
    `  *_schedule(layer: any, cooperative: boolean) {\n${src.slice(start, next)}\n  }\n` +
    `  _nextSet(layer: any) {\n    let triple: any;\n${src.slice(next, end)}\n    return triple;\n  }\n`;
  src = src.slice(0, anchor) + methods + src.slice(anchor);
  writeFileSync(join(tmp, 'decode.ts'), src);
}
const { _QRScanner, _scanRows } = await import(pathToFileURL(join(tmp, 'decode.ts')).href);

const readPGM = (path) => {
  const buf = readFileSync(path);
  const tokens = [];
  let i = 0;
  while (tokens.length < 4) {
    while (/\s/.test(String.fromCharCode(buf[i]))) i++;
    let s = '';
    while (!/\s/.test(String.fromCharCode(buf[i]))) s += String.fromCharCode(buf[i++]);
    tokens.push(s);
  }
  i++; // single whitespace after maxval
  if (tokens[0] !== 'P5' || tokens[3] !== '255') throw new Error('expected binary 8-bit PGM');
  const width = +tokens[1];
  const height = +tokens[2];
  return { width, height, data: new Uint8Array(buf.subarray(i, i + width * height)) };
};

let luma;
const lower = image.toLowerCase();
if (lower.endsWith('.pgm')) luma = readPGM(resolve(image));
else if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) {
  const { decodeJPEGLuma } = await import(pathToFileURL(join(dir, 'test', 'misc', 'jpg.ts')).href);
  luma = decodeJPEGLuma(readFileSync(join(vectors, image)));
} else if (lower.endsWith('.png')) {
  const { decodePNGLuma } = await import(pathToFileURL(join(dir, 'test', 'misc', 'png.ts')).href);
  luma = decodePNGLuma(readFileSync(join(vectors, image)));
} else throw new Error(`unsupported image: ${image}`);

const maxW = args.length === 4 ? +args[2] : luma.width;
const maxH = args.length === 4 ? +args[3] : luma.height;
const scanner = new _QRScanner({ maxSize: { width: maxW, height: maxH }, format: 'I420' });
scanner.addImage(luma, 'I420');
const layers = scanner.layers;
for (let i = 1; i < layers.length && layers[i].used; i++) {
  const src = layers[i - 1];
  const dst = layers[i];
  _scanRows.resize(src.luma, dst.luma, src.width, dst.width, 0, dst.height);
}

if (scheduleMode) {
  const out = { image, maxWidth: maxW, maxHeight: maxH, layers: [] };
  let used = 0;
  while (used < layers.length && layers[used].used) used++;
  // Round 0 of the walk (L2827-2893): prepare and pick every layer coarsest first.
  for (let i = used - 1; i >= 0; i--) {
    const layer = layers[i];
    _scanRows.blocks(layer, 0, layer.blockHeight);
    layer.bitmap.fill(0, 0, layer.words * layer.height);
    _scanRows.bitmap(layer, 0, layer.blockHeight);
    layer.patternCount = 0;
    _scanRows.find(layer, 0, layer.height);
    layer.found = true;
    scanner.exclude(layer);
    const state = scanner.tmp64;
    const ordinary = scanner.pickPolarity(layer, 0);
    const inverted = scanner.pickPolarity(layer, 1);
    if (!(ordinary || inverted)) continue;
    const pts = layer.patterns;
    let polarity = 0;
    if (inverted) {
      const ordConfidence = ordinary
        ? pts[state[0] * 4 + 3] + pts[state[1] * 4 + 3] + pts[state[2] * 4 + 3]
        : 0;
      const invConfidence = pts[state[4] * 4 + 3] + pts[state[5] * 4 + 3] + pts[state[6] * 4 + 3];
      if (!ordinary || (state[7] + 0.1) * ordConfidence < (state[3] + 0.1) * invConfidence) polarity = 1;
    }
    const base = polarity * 4;
    const w0 = state[base] | 0;
    const w1 = state[base + 1] | 0;
    const w2 = state[base + 2] | 0;
    layer.pickSum = w0 + w1 + w2;
    layer.pickLo = Math.min(w0, w1, w2);
    layer.pickHi = Math.max(w0, w1, w2);
  }
  // Round 1+: schedule each layer once, then drain it.
  for (let i = used - 1; i >= 0; i--) {
    const layer = layers[i];
    for (const _ of scanner._schedule(layer, false));
    const sets = [];
    for (let k = 0; k < layer.setCount; k++) sets.push(Array.from(layer.sets.subarray(k * 5, k * 5 + 5)));
    const pops = [];
    for (;;) {
      const t = scanner._nextSet(layer);
      if (!t) break;
      pops.push([t.tlIndex, t.trIndex, t.blIndex, t.inverted]);
    }
    out.layers.unshift({ index: i, setCount: layer.setCount, sets, pops });
  }
  process.stdout.write(compactJson(out) + '\n');
  process.exit(0);
}

const crc = (u8) => crc32(Buffer.from(u8.buffer, u8.byteOffset, u8.byteLength)) >>> 0;
const int16LE = (arr, n) => {
  const out = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) out.writeInt16LE(arr[i], i * 2);
  return out;
};
const int32LE = (arr, n) => {
  const out = Buffer.alloc(n * 4);
  for (let i = 0; i < n; i++) out.writeInt32LE(arr[i], i * 4);
  return out;
};

const num = (v) => (Number.isFinite(v) ? v : String(v));
const out = { image, width: luma.width, height: luma.height, maxWidth: maxW, maxHeight: maxH, layers: [] };
let used = 0;
while (used < layers.length && layers[used].used) used++;
for (let i = used - 1; i >= 0; i--) {
  const layer = layers[i];
  _scanRows.blocks(layer, 0, layer.blockHeight);
  layer.bitmap.fill(0, 0, layer.words * layer.height);
  _scanRows.bitmap(layer, 0, layer.blockHeight);
  layer.patternCount = 0;
  _scanRows.find(layer, 0, layer.height);
  layer.found = true;
  // Mandatory pick, decode.ts L2863-2893.
  scanner.exclude(layer);
  const state = scanner.tmp64;
  const ordinary = scanner.pickPolarity(layer, 0);
  const ordinaryPick = ordinary ? [state[0], state[1], state[2], num(state[3])] : null;
  const inverted = scanner.pickPolarity(layer, 1);
  const invertedPick = inverted ? [state[4], state[5], state[6], num(state[7])] : null;
  const pick = { ordinary: ordinaryPick, inverted: invertedPick, polarity: null, triple: null };
  if (ordinary || inverted) {
    const pts = layer.patterns;
    let polarity = 0;
    if (inverted) {
      const ordConfidence = ordinary
        ? pts[state[0] * 4 + 3] + pts[state[1] * 4 + 3] + pts[state[2] * 4 + 3]
        : 0;
      const invConfidence = pts[state[4] * 4 + 3] + pts[state[5] * 4 + 3] + pts[state[6] * 4 + 3];
      if (!ordinary || (state[7] + 0.1) * ordConfidence < (state[3] + 0.1) * invConfidence) polarity = 1;
    }
    const base = polarity * 4;
    const w0 = state[base] | 0;
    const w1 = state[base + 1] | 0;
    const w2 = state[base + 2] | 0;
    layer.pickSum = w0 + w1 + w2;
    layer.pickLo = Math.min(w0, w1, w2);
    layer.pickHi = Math.max(w0, w1, w2);
    const triple = scanner.makeTriple(layer, w0, w1, w2);
    triple.inverted = polarity === 1;
    pick.polarity = polarity;
    const p = (q) => [q.x, q.y, q.ms];
    pick.triple = {
      tl: p(triple.tl),
      tr: p(triple.tr),
      bl: p(triple.bl),
      tlIndex: triple.tlIndex,
      trIndex: triple.trIndex,
      blIndex: triple.blIndex,
      inverted: triple.inverted,
    };
  }
  pick.pickSum = layer.pickSum;
  pick.pickLo = layer.pickLo;
  pick.pickHi = layer.pickHi;
  const nb = layer.blockWidth * layer.blockHeight;
  const patterns = [];
  for (let p = 0; p < layer.patternCount; p++) {
    const q = p * 4;
    patterns.push([
      layer.patterns[q],
      layer.patterns[q + 1],
      layer.patterns[q + 2],
      layer.patterns[q + 3],
      layer.inverted[p],
    ]);
  }
  out.layers.unshift({
    index: i,
    width: layer.width,
    height: layer.height,
    words: layer.words,
    blockWidth: layer.blockWidth,
    blockHeight: layer.blockHeight,
    lumaCrc: crc(layer.luma.subarray(0, layer.width * layer.height)),
    blocksCrc: crc(layer.blocks.subarray(0, nb)),
    cutsCrc: crc(int16LE(layer.cuts, nb)),
    bitmapCrc: crc(int32LE(layer.bitmap, layer.words * layer.height)),
    patternCount: layer.patternCount,
    patterns,
    pick,
  });
}
process.stdout.write(compactJson(out) + '\n');
