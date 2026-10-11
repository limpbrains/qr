#!/usr/bin/env node
// Dump the JS (paulmillr/qr v0.7.2) default decode result per test image for Kotlin parity tests.
//
// Usage (node >= 22, needs --experimental-strip-types for the .ts sources):
//   node --experimental-strip-types tools/js-dump/dump-decode.mjs <qr-paulmillr-dir> [image...] > out.json
//     [image...]: paths relative to qr/test/vectors. Default: every boofcv-v3/detection/**/*.{jpg,png},
//     boofcv-v3/decoding/*.png, issues/*.jpg and misc/*.png.
// Luma comes from the JS test readers (test/misc/jpg.ts decodeJPEGLuma / png.ts decodePNGLuma, as
// test/utils.ts readLuma does). Each image runs through a fresh
// `new _QRScanner({ maxSize: { width, height }, format: 'I420' })`, `addImage`, `decode()` (defaults:
// effort 1, timeLimit 1000/60 — irrelevant with effort 1, no retries).
// Output (stdout): JSON { "<relpath>": { "result": "<text>" | null, "fail": "<tag>" | null } }, sorted keys,
// where `fail` is the Error message of `decode()[0]` (finder/dimension/alignment/timing/version/format/rs/data).
//
//   node --experimental-strip-types tools/js-dump/dump-decode.mjs --all <qr-paulmillr-dir> [image...] > out.json
//     All mode: `new _QRScanner({ maxSize, format: 'I420', effort: Infinity, timeLimit: Infinity })` and
//     `decode(true)`; output { "<relpath>": { "results": ["<text>"...], "fail": "<tag of the terminal Error>" } }.
//
//   node --experimental-strip-types tools/js-dump/dump-decode.mjs --points [--all] <qr-paulmillr-dir> [image...]
//     Points mode: same scanner plus `pointsOnDetect: (p) => events.push(p)`. Setting the callback makes
//     `report()` rewrite the scanner homography before the v7+ tile fallbacks, so outcomes may differ
//     from the plain dumps (the "hazard" images). Output, one image per line:
//     { "<relpath>": { "result"|"results": ..., "fail": ..., "points": [event...] } } where each event is
//     [tl, tr, [brX, brY], bl, [aligner...], bounds, outline, bbox]: a FinderPoint is the flat
//     [x, y, moduleSize, c0x, c0y, c1x, c1y, c2x, c2y, c3x, c3y] (11), bounds/outline are 4 points
//     flattened (8), bbox is [x, y, width, height]. Numbers are shortest JS doubles; NaN, Infinity,
//     -Infinity and -0 are written as the strings "NaN", "Infinity", "-Infinity", "-0". Default mode
//     asserts at most one event per image; --all keeps the ordered event list. Output is always
//     gzipped (zlib.gzipSync): the points fixtures are large, so they are stored as .json.gz.
import { compactJson } from './compact-json.mjs';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const vectors = resolve(here, '..', '..', 'test', 'vectors');
const args = process.argv.slice(2);
let pointsMode = false;
let allMode = false;
while (args[0] === '--points' || args[0] === '--all') {
  if (args.shift() === '--points') pointsMode = true;
  else allMode = true;
}
if (args.length < 1) {
  console.error('usage: dump-decode.mjs [--points] [--all] <qr-paulmillr-dir> [image...]');
  process.exit(1);
}
const dir = resolve(args[0]);
const { _QRScanner } = await import(pathToFileURL(join(dir, 'src', 'decode.ts')).href);
const { decodeJPEGLuma } = await import(pathToFileURL(join(dir, 'test', 'misc', 'jpg.ts')).href);
const { decodePNGLuma } = await import(pathToFileURL(join(dir, 'test', 'misc', 'png.ts')).href);

const walk = (root, re) => {
  const out = [];
  for (const name of readdirSync(root)) {
    const path = join(root, name);
    if (statSync(path).isDirectory()) out.push(...walk(path, re));
    else if (re.test(name)) out.push(relative(vectors, path).split('\\').join('/'));
  }
  return out;
};
const images =
  args.length > 1
    ? args.slice(1)
    : [
        ...walk(join(vectors, 'boofcv-v3', 'detection'), /\.(?:jpe?g|png)$/i),
        ...walk(join(vectors, 'boofcv-v3', 'decoding'), /\.png$/i),
        ...walk(join(vectors, 'issues'), /\.jpe?g$/i),
        ...walk(join(vectors, 'misc'), /\.png$/i),
      ];

const num = (v) =>
  Number.isNaN(v) ? 'NaN' : v === Infinity ? 'Infinity' : v === -Infinity ? '-Infinity' : Object.is(v, -0) ? '-0' : v;
const flatPoint = (p) => [p.x, p.y].map(num);
const flatQuad = (q) => q.flatMap(flatPoint);
const flatFinder = (f) => [...flatPoint(f), num(f.moduleSize), ...flatQuad(f.corners)];
const flatEvent = (p) => [
  flatFinder(p.tl),
  flatFinder(p.tr),
  flatPoint(p.br),
  flatFinder(p.bl),
  p.aligners.map(flatFinder),
  flatQuad(p.bounds),
  flatQuad(p.outline),
  [p.boundingBox.x, p.boundingBox.y, p.boundingBox.width, p.boundingBox.height].map(num),
];

const out = {};
for (const image of images.sort()) {
  const bytes = readFileSync(join(vectors, image));
  const luma = /\.png$/i.test(image) ? decodePNGLuma(bytes) : decodeJPEGLuma(bytes);
  const events = [];
  const scanner = new _QRScanner({
    maxSize: { width: luma.width, height: luma.height },
    format: 'I420',
    ...(pointsMode ? { pointsOnDetect: (p) => events.push(flatEvent(p)) } : {}),
    ...(allMode ? { effort: Infinity, timeLimit: Infinity } : {}),
  });
  scanner.addImage(luma, 'I420');
  if (allMode) {
    const all = scanner.decode(true);
    const last = all[all.length - 1];
    out[image] = {
      results: all.filter((r) => !(r instanceof Error)),
      fail: last instanceof Error ? last.message : null,
    };
    if (pointsMode) out[image].points = events;
    continue;
  }
  const first = scanner.decode()[0];
  out[image] =
    first instanceof Error ? { result: null, fail: first.message } : { result: first, fail: null };
  if (pointsMode) {
    if (events.length > 1) throw new Error(`${image}: ${events.length} pointsOnDetect events in default mode`);
    out[image].points = events;
  }
}
const sorted = {};
for (const key of Object.keys(out).sort()) sorted[key] = out[key];
if (pointsMode) {
  // One image per line (records hold nested arrays, which compactJson would spread over many lines).
  const keys = Object.keys(sorted);
  const text =
    '{\n' + keys.map((k) => ` ${JSON.stringify(k)}: ${JSON.stringify(sorted[k])}`).join(',\n') + '\n}\n';
  process.stdout.write(gzipSync(text, { level: 9 }));
} else process.stdout.write(compactJson(sorted) + '\n');
