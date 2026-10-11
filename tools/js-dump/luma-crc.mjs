#!/usr/bin/env node
// Writes src/test/resources/boofcv-luma-crc32.json: CRC32 of the JS test-reader luma for every
// boofcv-v3/detection/**/*.{jpg,png}, issues/*.jpg and misc/*.png under qr/test/vectors.
// LumaParityTest checks the Kotlin JpegLuma/PngLuma ports against it.
//
// Usage (node >= 22):
//   node --experimental-strip-types tools/js-dump/luma-crc.mjs <qr-paulmillr-dir> <out.json>
//   node --experimental-strip-types tools/js-dump/luma-crc.mjs <qr-paulmillr-dir> --raw <image> <out.gray>
//     Debug mode: writes the raw luma bytes of one image (path relative to qr/test/vectors), prints "w h".
// Luma comes from test/misc/jpg.ts decodeJPEGLuma / png.ts decodePNGLuma, as test/utils.ts readLuma does.
import { readFileSync, writeFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { crc32 } from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..', 'test', 'vectors');
const args = process.argv.slice(2);
if (args.length < 2) {
  console.error('usage: luma-crc.mjs <qr-paulmillr-dir> <out.json> | <qr-paulmillr-dir> --raw <image> <out.gray>');
  process.exit(1);
}
const dir = resolve(args[0]);
const { decodeJPEGLuma } = await import(pathToFileURL(join(dir, 'test', 'misc', 'jpg.ts')).href);
const { decodePNGLuma } = await import(pathToFileURL(join(dir, 'test', 'misc', 'png.ts')).href);

const isImg = (f) => /\.(?:jpe?g|png)$/i.test(f);
const walk = (d) => readdirSync(d).flatMap((n) => {
  const p = join(d, n);
  return statSync(p).isDirectory() ? walk(p) : isImg(n) ? [p] : [];
});
const readLuma = (p) => (/\.png$/i.test(p) ? decodePNGLuma : decodeJPEGLuma)(readFileSync(p));
if (args[1] === '--raw') {
  const img = readLuma(join(root, args[2]));
  writeFileSync(args[3], img.data);
  console.log(img.width, img.height);
  process.exit(0);
}
const files = [
  ...walk(join(root, 'boofcv-v3/detection')),
  ...readdirSync(join(root, 'issues')).filter((f) => /\.jpe?g$/i.test(f)).map((f) => join(root, 'issues', f)),
  ...readdirSync(join(root, 'misc')).filter((f) => /\.png$/i.test(f)).map((f) => join(root, 'misc', f)),
];
const res = {};
for (const p of files) {
  const img = readLuma(p);
  if (img.data.length !== img.width * img.height) throw new Error('bad luma length ' + p);
  res[relative(root, p).split(sep).join("/")] = { w: img.width, h: img.height, crc: (crc32(img.data) >>> 0).toString(16).padStart(8, '0') };
}
const keys = Object.keys(res).sort();
const lines = keys.map((k) => `  ${JSON.stringify(k)}: {"w":${res[k].w},"h":${res[k].h},"crc":"${res[k].crc}"}`);
writeFileSync(args[1], '{\n' + lines.join(',\n') + '\n}\n');
console.log('entries', keys.length);
