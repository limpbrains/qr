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
import { compactJson } from './compact-json.mjs';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const vectors = resolve(here, '..', '..', 'test', 'vectors');
const args = process.argv.slice(2);
const allMode = args[0] === '--all';
if (allMode) args.shift();
if (args.length < 1) {
  console.error('usage: dump-decode.mjs <qr-paulmillr-dir> [image...]');
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

const out = {};
for (const image of images.sort()) {
  const bytes = readFileSync(join(vectors, image));
  const luma = /\.png$/i.test(image) ? decodePNGLuma(bytes) : decodeJPEGLuma(bytes);
  const scanner = new _QRScanner({
    maxSize: { width: luma.width, height: luma.height },
    format: 'I420',
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
    continue;
  }
  const first = scanner.decode()[0];
  out[image] =
    first instanceof Error ? { result: null, fail: first.message } : { result: first, fail: null };
}
const sorted = {};
for (const key of Object.keys(out).sort()) sorted[key] = out[key];
process.stdout.write(compactJson(sorted) + '\n');
