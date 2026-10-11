#!/usr/bin/env node
// Writes the encodeQR render references for RendererTest (src/test/resources/encoder-renders.txt).
// Usage (node >= 22): node --experimental-strip-types tools/js-dump/encoder-renders.mjs <qr-paulmillr-dir> > out.txt
// One JSON object per line: {"text", "opts", "output", "value"}; raw values are "0/1" row strings,
// gif is covered through data-url. JSON.stringify escapes lone surrogates as \udXXX.
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

if (process.argv.length < 3) {
  console.error('usage: encoder-renders.mjs <qr-paulmillr-dir>');
  process.exit(1);
}
const { encodeQR } = await import(pathToFileURL(join(resolve(process.argv[2]), 'src', 'index.ts')).href);
const V10 = 'vita nuova — 🌒🌓'.repeat(8); // 184 UTF-8 bytes: v10-M, multi-digit coords and negative dx
const cases = [
  ['HELLO WORLD', {}, 'svg'],
  ['HELLO WORLD', {}, 'term'],
  ['HELLO WORLD', {}, 'data-url'],
  ['HELLO WORLD', {}, 'ascii'],
  ['HELLO WORLD', { optimize: false }, 'svg'],
  ['HELLO WORLD', { scale: 3, border: 1 }, 'svg'],
  [V10, {}, 'svg'],
  ['HELLO WORLD', { scale: 2, border: 3 }, 'ascii'],
  ['HELLO WORLD', { border: 21, scale: 2 }, 'data-url'], // W = 126: W*W % 126 == 0 (extra clear code)
  ['a\uD800b', {}, 'raw'],
];
const out = [];
for (const [text, opts, output] of cases) {
  let value = encodeQR(text, output, opts);
  if (output === 'raw') value = value.map((row) => row.map((b) => (b ? '1' : '0')).join(''));
  out.push(JSON.stringify({ text, opts, output, value }));
}
console.log(out.join('\n'));
