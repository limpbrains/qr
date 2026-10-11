#!/usr/bin/env node
// Writes V8 Math.hypot / Math.cbrt reference values for HomographyTest.
// Usage: node tools/js-dump/math-fixtures.mjs <out-dir>   (writes hypot-fixture.txt, cbrt-fixture.txt)
// Each line: inputs and result as JS shortest round-trip number strings (Kotlin String.toDouble() is exact).
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

// mulberry32, fixed seed: deterministic across runs.
let seed = 0x5eed1234;
const rand = () => {
  seed = (seed + 0x6d2b79f5) | 0;
  let t = seed;
  t = Math.imul(t ^ (t >>> 15), t | 1);
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
// Mix of scales: sub-pixel offsets, image-sized coordinates and wide-range magnitudes.
const value = (i) => {
  const r = rand() * 2 - 1;
  if (i % 4 === 0) return r * 4;
  if (i % 4 === 1) return r * 4096;
  if (i % 4 === 2) return Math.round(r * 2000);
  return r * 10 ** Math.floor(rand() * 40 - 20);
};
const s = (n) => JSON.stringify(n);
const hypot = [];
for (let i = 0; i < 500; i++) {
  const dx = i === 0 ? 0 : value(i);
  const dy = i === 0 ? 0 : i === 1 ? 0 : value(i + 1);
  hypot.push(`${s(dx)} ${s(dy)} ${s(Math.hypot(dx, dy))}`);
}
const cbrt = [];
for (let i = 0; i < 200; i++) {
  const x = value(i) * (i % 2 ? 1 : 37.5);
  cbrt.push(`${s(x)} ${s(Math.cbrt(x))}`);
}
// Exact cubes, subnormals and range ends exercise V8's (FreeBSD msun) cbrt special branches.
for (const x of [0, 1, 8, -27, 1e-310, -5e-324, 2.2250738585072014e-308, 1.7976931348623157e308, -3e300])
  cbrt.push(`${s(x)} ${s(Math.cbrt(x))}`);
writeFileSync(join(process.argv[2], 'hypot-fixture.txt'), hypot.join('\n') + '\n');
writeFileSync(join(process.argv[2], 'cbrt-fixture.txt'), cbrt.join('\n') + '\n');
