#!/usr/bin/env node
/*
 * Morsecode — icon generator
 *
 * The interactive mockup (doc/morsecode_material3_mockup.html) defines every
 * application icon as raw 24x24 SVG path data in its `const I = {...}` map.
 * Those paths are the approved artwork, so they are the source of truth for the
 * Android vector drawables in core-design.
 *
 * This script parses that map and emits one Android <vector> drawable per icon.
 * Converting the artwork instead of substituting Material Icons keeps stroke
 * weight, geometry and optical size identical to the approved reference.
 *
 * Usage:  node tools/gen/icons.mjs [--check]
 *   --check   regenerate into memory and fail if any committed file differs
 */
import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..', '..');
const MOCKUP = join(root, 'doc', 'morsecode_material3_mockup.html');
const OUT_DIR = join(root, 'core-design', 'src', 'main', 'res', 'drawable');
const PREFIX = 'ic_morse_';
const CHECK = process.argv.includes('--check');

/** Fixed geometry taken from the mockup: `svg()` sets stroke-width 1.8. */
const STROKE_WIDTH = 1.8;
const VIEWPORT = 24;

/* ------------------------------------------------------------------ parsing */

function readIconMap(html) {
  const block = html.match(/const I = \{([\s\S]*?)\n\};/);
  if (!block) throw new Error('icon map not found in mockup');
  const icons = new Map();
  // Entries look like:  back:'<path d="M19 12H5"/>',  wifi:'<path .../><circle .../>',
  const entry = /(?:^|[\n,])\s*([A-Za-z0-9]+)\s*:\s*'((?:[^'\\]|\\.)*)'/g;
  let m;
  while ((m = entry.exec(block[1])) !== null) {
    icons.set(m[1], m[2]);
  }
  if (icons.size === 0) throw new Error('no icons parsed');
  return icons;
}

const num = (s) => {
  const v = Number.parseFloat(s);
  if (!Number.isFinite(v)) throw new Error(`bad number: ${s}`);
  return v;
};

/** Round to 3 decimals and drop trailing zeros — keeps drawables readable. */
const r3 = (v) => String(Math.round(v * 1000) / 1000);

/** <circle cx cy r> as path data (two 180 degree arcs). */
function circleToPath(cx, cy, r) {
  return `M${r3(cx - r)},${r3(cy)}a${r3(r)},${r3(r)} 0 1,0 ${r3(r * 2)},0a${r3(r)},${r3(r)} 0 1,0 ${r3(-r * 2)},0`;
}

/** <rect x y width height rx> as path data. */
function rectToPath(x, y, w, h, rx) {
  const k = Math.min(rx, w / 2, h / 2);
  if (k <= 0) {
    return `M${r3(x)},${r3(y)}h${r3(w)}v${r3(h)}h${r3(-w)}z`;
  }
  return [
    `M${r3(x + k)},${r3(y)}`,
    `h${r3(w - 2 * k)}`,
    `a${r3(k)},${r3(k)} 0 0,1 ${r3(k)},${r3(k)}`,
    `v${r3(h - 2 * k)}`,
    `a${r3(k)},${r3(k)} 0 0,1 ${r3(-k)},${r3(k)}`,
    `h${r3(-(w - 2 * k))}`,
    `a${r3(k)},${r3(k)} 0 0,1 ${r3(-k)},${r3(-k)}`,
    `v${r3(-(h - 2 * k))}`,
    `a${r3(k)},${r3(k)} 0 0,1 ${r3(k)},${r3(-k)}`,
    'z',
  ].join('');
}

function attrOf(tag, name) {
  const m = tag.match(new RegExp(`${name}\\s*=\\s*"([^"]*)"`));
  return m ? m[1] : null;
}

/**
 * Turn one mockup SVG body into Android <path> elements.
 * The mockup draws stroked outlines; two icons (play, and the filled halves of
 * a few marks) declare fill="currentColor" stroke="none" and are emitted filled.
 */
function svgBodyToPaths(body) {
  const out = [];
  const tags = body.match(/<(path|circle|rect|polygon|line)\b[^>]*\/?>/g) || [];
  if (tags.length === 0) throw new Error(`no shapes in: ${body}`);
  for (const tag of tags) {
    const kind = tag.match(/^<(\w+)/)[1];
    // The mockup fills a handful of solid marks and declares them with
    // fill="currentColor" stroke="none"; everything else is a stroked outline.
    const strokeNone = /stroke\s*=\s*"none"/.test(tag);
    const filled = strokeNone || /fill\s*=\s*"currentColor"/.test(tag);
    let d = null;
    if (kind === 'path') {
      d = attrOf(tag, 'd');
    } else if (kind === 'circle') {
      d = circleToPath(num(attrOf(tag, 'cx')), num(attrOf(tag, 'cy')), num(attrOf(tag, 'r')));
    } else if (kind === 'rect') {
      d = rectToPath(
        num(attrOf(tag, 'x')), num(attrOf(tag, 'y')),
        num(attrOf(tag, 'width')), num(attrOf(tag, 'height')),
        num(attrOf(tag, 'rx') ?? '0'),
      );
    } else if (kind === 'line') {
      d = `M${attrOf(tag, 'x1')},${attrOf(tag, 'y1')}L${attrOf(tag, 'x2')},${attrOf(tag, 'y2')}`;
    } else {
      const pts = (attrOf(tag, 'points') || '').trim().split(/\s+/);
      d = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p}`).join('') + 'z';
    }
    if (!d) throw new Error(`no geometry for ${kind}: ${tag}`);

    // Artwork is emitted in white; the use site tints it with Icon(tint = ...).
    if (filled) {
      out.push(
        `        <path\n            android:fillColor="#FFFFFFFF"\n            android:pathData="${d}" />`,
      );
    } else {
      out.push(
        [
          '        <path',
          '            android:fillColor="#00000000"',
          `            android:pathData="${d}"`,
          `            android:strokeColor="#FFFFFFFF"`,
          `            android:strokeLineCap="round"`,
          `            android:strokeLineJoin="round"`,
          `            android:strokeWidth="${STROKE_WIDTH}" />`,
        ].join('\n'),
      );
    }
  }
  return out.join('\n');
}

function drawableFor(name, body) {
  const snake = name.replace(/([a-z0-9])([A-Z])/g, '$1_$2').toLowerCase();
  const xml = `<?xml version="1.0" encoding="utf-8"?>
<!--
  Generated from doc/morsecode_material3_mockup.html (icon "${name}") by
  tools/gen/icons.mjs. Do not edit by hand: the mockup is the approved artwork.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="${VIEWPORT}"
    android:viewportHeight="${VIEWPORT}">
${svgBodyToPaths(body)}
</vector>
`;
  return { file: `${PREFIX}${snake}.xml`, xml, key: name };
}

/* --------------------------------------------------------------------- main */

const html = readFileSync(MOCKUP, 'utf8');
const icons = readIconMap(html);
const generated = [];
for (const [name, body] of icons) {
  try {
    generated.push(drawableFor(name, body));
  } catch (e) {
    throw new Error(`icon "${name}": ${e.message}`);
  }
}

if (CHECK) {
  const onDisk = new Map(
    readdirSync(OUT_DIR).filter((f) => f.startsWith(PREFIX)).map((f) => [f, readFileSync(join(OUT_DIR, f), 'utf8')]),
  );
  const problems = [];
  for (const g of generated) {
    const current = onDisk.get(g.file);
    if (current === undefined) problems.push(`missing ${g.file}`);
    else if (current !== g.xml) problems.push(`stale ${g.file}`);
    onDisk.delete(g.file);
  }
  for (const extra of onDisk.keys()) problems.push(`orphan ${extra}`);
  if (problems.length) {
    console.error(`icon check FAILED:\n  ${problems.join('\n  ')}`);
    console.error('run: node tools/gen/icons.mjs');
    process.exit(1);
  }
  console.log(`icon check OK — ${generated.length} drawables match the mockup artwork`);
  process.exit(0);
}

mkdirSync(OUT_DIR, { recursive: true });
for (const stale of readdirSync(OUT_DIR).filter((f) => f.startsWith(PREFIX))) {
  if (!generated.some((g) => g.file === stale)) rmSync(join(OUT_DIR, stale));
}
for (const g of generated) writeFileSync(join(OUT_DIR, g.file), g.xml);
console.log(`wrote ${generated.length} vector drawables to core-design/src/main/res/drawable`);
writeFileSync(
  join(here, 'icons.json'),
  JSON.stringify(generated.map((g) => ({ mockup: g.key, drawable: g.file.replace(/\.xml$/, '') })), null, 2) + '\n',
);
