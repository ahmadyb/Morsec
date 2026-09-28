#!/usr/bin/env node
/*
 * Token parity harness.
 *
 * The approved mockup (doc/morsecode_material3_mockup.html) is the single visual
 * source of truth, and core-design transcribes it into Kotlin. This script is the
 * automated comparison: it parses the reference document's CSS (last declaration
 * wins, because the document layers a Material 3 pass over the prototype pass)
 * and checks it against the three marked regions of MockupTokens.kt:
 *
 *   MOCKUP-RAW      colour custom properties, per scope
 *   MOCKUP-DERIVED  color-mix() formulas
 *   MOCKUP-METRICS  dimensions, type sizes and motion values
 *
 * It also checks the generated icon set and the feature gate. It runs on plain
 * Node with no dependencies, so it can be executed anywhere the repo is checked
 * out — including environments that cannot build the Android app.
 *
 *   node tools/verify/token-parity.mjs            # full report
 *   node tools/verify/token-parity.mjs --quiet    # failures only
 *
 * Exit code 1 means the app and the reference have drifted.
 */

import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const QUIET = process.argv.includes('--quiet');

const MOCKUP = join(ROOT, 'doc', 'morsecode_material3_mockup.html');
const TOKENS_KT = join(ROOT, 'core-design', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core', 'design', 'tokens', 'MockupTokens.kt');
const ICONS_KT = join(ROOT, 'core-design', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core', 'design', 'icon', 'MorseIcons.kt');
const ICONS_JSON = join(ROOT, 'tools', 'gen', 'icons.json');
const DRAWABLES = join(ROOT, 'core-design', 'src', 'main', 'res', 'drawable');
const READINESS_KT = join(ROOT, 'core-model', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core', 'model', 'FeatureReadiness.kt');

/* ------------------------------------------------------------------ results */

const results = [];
let failures = 0;

function check(group, name, ok, detail) {
  if (!ok) failures++;
  results.push({ group, name, ok, detail });
}

function report() {
  let lastGroup = null;
  for (const r of results) {
    if (!QUIET && r.group !== lastGroup) {
      process.stdout.write(`\n${r.group}\n`);
      lastGroup = r.group;
    }
    if (r.ok && QUIET) continue;
    process.stdout.write(`  ${r.ok ? 'ok  ' : 'FAIL'} ${r.name}${r.detail ? ` — ${r.detail}` : ''}\n`);
  }
  const passed = results.filter((r) => r.ok).length;
  process.stdout.write(`\n${passed}/${results.length} checks passed, ${failures} failed\n`);
}

/* ------------------------------------------------------------------- inputs */

function read(path) {
  if (!existsSync(path)) throw new Error(`missing file: ${path}`);
  return readFileSync(path, 'utf8');
}

const html = read(MOCKUP);
const styleBlock = (html.match(/<style[^>]*>([\s\S]*?)<\/style>/) || [])[1] || '';
// Comments are stripped up front: the reference uses them as section banners and
// they would otherwise leak into the selector text of the rule that follows.
const css = styleBlock.replace(/\/\*[\s\S]*?\*\//g, '');
const kotlin = read(TOKENS_KT);

/* -------------------------------------------------------------- CSS parsing */

/** Every rule in document order: { selector, declarations: Map } */
const rules = [];
for (const match of css.matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
  const selector = match[1].replace(/\s+/g, ' ').trim();
  const declarations = new Map();
  for (const decl of match[2].matchAll(/([-A-Za-z0-9_]+)\s*:\s*([^;]+)/g)) {
    declarations.set(decl[1], decl[2].replace(/\s+/g, ' ').replace(/\s*!important\s*$/i, '').trim());
  }
  rules.push({ selector, declarations, body: match[2].replace(/\s+/g, ' ') });
}

const SCOPE_PATTERNS = [
  [/html\[data-theme="dark"\]/, 'dark'],
  [/html\[data-theme="light"\]/, 'light'],
  [/html\[data-accent="([a-z]+)"\]/, null],
  [/:root/, 'root'],
];

function scopeOf(selector) {
  for (const [pattern, scope] of SCOPE_PATTERNS) {
    const match = pattern.exec(selector);
    if (match) return scope ?? match[1];
  }
  return null;
}

/** name|scope -> value, last declaration wins (the M3 pass overrides the prototype). */
const cssVars = new Map();
for (const rule of rules) {
  const scope = scopeOf(rule.selector);
  if (!scope) continue;
  for (const [name, value] of rule.declarations) {
    if (name.startsWith('--')) cssVars.set(`${name.slice(2)}|${scope}`, value);
  }
}

const ACCENT_SCOPES = ['sunflower', 'leaf', 'ember', 'violet', 'sky'];

/** Resolves a variable the way the cascade does: scope first, then :root. */
function cssVar(name, scope) {
  const direct = cssVars.get(`${name}|${scope}`);
  if (direct !== undefined) return direct;
  return cssVars.get(`${name}|root`);
}

function normalizeHex(value) {
  if (!value) return null;
  const hex = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.exec(value.trim());
  if (!hex) return null;
  let digits = hex[1];
  if (digits.length === 3) digits = digits.split('').map((c) => c + c).join('');
  if (digits.length === 8) digits = digits.slice(0, 6); // #RRGGBBAA -> #RRGGBB
  return `#${digits.toUpperCase()}`;
}

/** `rgba(20,19,16,.38)` -> { hex, alpha } */
function normalizeColor(value) {
  const hex = normalizeHex(value);
  if (hex) return { hex, alpha: null };
  const rgba = /rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)/.exec(value || '');
  if (rgba) {
    const to = (n) => Math.round(Number(n)).toString(16).padStart(2, '0').toUpperCase();
    return { hex: `#${to(rgba[1])}${to(rgba[2])}${to(rgba[3])}`, alpha: rgba[4] === undefined ? null : Number(rgba[4]) };
  }
  if ((value || '').trim().toLowerCase() === '#fff') return { hex: '#FFFFFF', alpha: null };
  return { hex: null, alpha: null };
}

/** Last rule matching `selector` that declares `property`. */
function declarationFor(selector, property) {
  for (let i = rules.length - 1; i >= 0; i--) {
    const rule = rules[i];
    const selectors = rule.selector.split(',').map((s) => s.trim());
    const matches = selectors.includes(selector) || rule.selector.trim() === selector;
    if (matches && rule.declarations.has(property)) {
      return rule.declarations.get(property);
    }
  }
  return undefined;
}

/** Resolves `var(--m3-r-md)` (and the first of several values) to its px number. */
function resolveVarPx(value) {
  const text = (value || '').trim();
  const varName = /var\(\s*--([\w-]+)\s*\)/.exec(text);
  if (varName) return resolveVarPx(cssVar(varName[1], 'root') || '');
  const px = /(-?[\d.]+)px/.exec(text);
  return px ? Number(px[1]) : null;
}

const pxNumber = (value) => {
  const px = /(-?[\d.]+)px/.exec(value || '');
  return px ? Number(px[1]) : null;
};

/* ------------------------------------------------------- Kotlin region parse */

function region(marker) {
  const begin = kotlin.indexOf(`// ${marker}-BEGIN`);
  const end = kotlin.indexOf(`// ${marker}-END`);
  if (begin < 0 || end < 0) throw new Error(`region ${marker} not found in MockupTokens.kt`);
  return kotlin.slice(begin, end);
}

const rawRegion = region('MOCKUP-RAW');
const derivedRegion = region('MOCKUP-DERIVED');
const metricsRegion = region('MOCKUP-METRICS');

const kotlinRaw = new Map();
for (const m of rawRegion.matchAll(/"([\w-]+)\|(\w+)"\s+to\s+"(#[0-9A-Fa-f]{3,8})"/g)) {
  kotlinRaw.set(`${m[1]}|${m[2]}`, m[3]);
}

const kotlinDerived = new Map();
for (const m of derivedRegion.matchAll(
  /DerivedMix\("(\w+)",\s*MixKind\.(\w+),\s*"(\w+)",\s*([\d.]+)f,\s*(?:"(\w+)"|null),\s*"([^"]+)"\)/g,
)) {
  kotlinDerived.set(m[1], {
    name: m[1],
    kind: m[2],
    base: m[3],
    fraction: Number(m[4]),
    partner: m[5] || null,
    cssSource: m[6],
  });
}

const kotlinMetrics = new Map();
for (const m of metricsRegion.matchAll(/"([\w.]+)"\s+to\s+([\d.]+)f/g)) {
  kotlinMetrics.set(m[1], Number(m[2]));
}

/* ------------------------------------------------------------- 1. raw colors */

const RAW_SKIP = new Set([
  // Font stacks, shadows and simulation-only chrome: not colour tokens.
  'mono', 'sans', 'shadow', 'm3-e1', 'm3-e2', 'm3-e3', 'm3-motion', 'ph1', 'ph2', 'phS',
  // Declared inline in the document's screen templates / JS, checked further down.
  'viewerBackdrop', 'viewerIconBackground', 'viewerIconContent', 'viewerMetaText', 'videoControlBar',
  'onb1a', 'onb1b', 'onb2a', 'onb2b', 'onb3a', 'onb3b', 'onb4a', 'onb4b',
]);

for (const [key, value] of kotlinRaw) {
  const [name, scope] = key.split('|');
  if (RAW_SKIP.has(name)) continue; // verified against the templates / JS instead
  const cssValue = cssVar(name, scope);
  if (cssValue === undefined) {
    check('Raw colours', `${key}`, false, 'not declared in the reference document');
    continue;
  }
  const expected = normalizeColor(cssValue);
  const actual = normalizeHex(value);
  check('Raw colours', `${key}`, expected.hex === actual, `kotlin ${value} vs css ${cssValue}`);
}

// Reverse direction: a colour the reference declares but the app omits is drift too.
const missingCoverage = [];
let coverageCandidates = 0;
for (const [key, value] of cssVars) {
  const [name, scope] = key.split('|');
  if (RAW_SKIP.has(name)) continue;
  if (!ACCENT_SCOPES.includes(scope) && !['root', 'dark', 'light'].includes(scope)) continue;
  if (normalizeColor(value).hex === null) continue; // var() aliases are covered by the mix checks
  coverageCandidates++;
  if (!kotlinRaw.has(key)) missingCoverage.push(`${key} (${value})`);
}
check(
  'Raw colour coverage',
  'every reference colour custom property is transcribed',
  missingCoverage.length === 0,
  missingCoverage.length
    ? `not transcribed: ${missingCoverage.slice(0, 8).join(', ')}`
    : `${coverageCandidates}/${coverageCandidates} colour declarations across root/dark/light and the five accents`,
);

/* --------------------------------------------------------- 2. derived mixes */

/**
 * Three-way check: the formula in the reference document, the formula recorded
 * in Kotlin, and the kind of mix (solid interpolation vs alpha wash).
 */
const MIXES = [
  // name,                            where to look,                        property
  ['primaryContainer', '--m3-primary-container', null],
  ['surfaceContainerLow', '--m3-surface-container-low', null],
  ['outline', '--m3-outline', null],
  ['chipAccentBackground', '.chip.acc', 'background'],
  ['chipOkBackground', '.chip.ok', 'background'],
  ['chipWarnBackground', '.chip.warn', 'background'],
  ['chipErrorBackground', '.chip.err', 'background'],
  ['chipOutlineBorder', '.chip.line', 'border'],
  ['actionBarPrimaryPill', '.ab button.pri .pill', 'background'],
  ['fileTabSelected', '.files-tabs button.on', 'background'],
  ['radarRing', '.radar::before', 'border-color'],
  ['radarSweep', '.radar::after', 'background'],
  ['focusRing', 'button:focus-visible,a:focus-visible,[tabindex]:focus-visible', 'outline'],
  ['stickyHeaderScrim', '.files-hd', 'background'],
  ['scrollThumb', '.phone .scr::-webkit-scrollbar-thumb', 'background'],
];

/** Extracts the first complete `color-mix(...)` call, honouring nested parens. */
function extractColorMixCall(text) {
  const source = text || '';
  const start = source.indexOf('color-mix(');
  if (start < 0) return null;
  let depth = 0;
  for (let i = start + 'color-mix'.length; i < source.length; i++) {
    if (source[i] === '(') depth++;
    else if (source[i] === ')') {
      depth--;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  return null;
}

function parseColorMix(text) {
  const call = extractColorMixCall(text);
  if (!call) return null;
  const args = call.slice('color-mix('.length, -1).split(/,(?![^(]*\))/).map((a) => a.trim());
  const baseArg = /^var\(\s*--([\w-]+)\s*\)\s+([\d.]+)%$/.exec(args[1] || '');
  if (!baseArg) return null;
  const partnerRaw = (args[2] || '').trim();
  const mix = [null, null, baseArg[1], baseArg[2], partnerRaw];
  const partner = /^var\(\s*--([\w-]+)\s*\)$/.exec(partnerRaw);
  return {
    base: mix[2],
    fraction: Number(mix[3]) / 100,
    partner: partner ? partner[1] : null,
    kind: partnerRaw === 'transparent' ? 'ALPHA' : 'SOLID',
    raw: call,
  };
}

/** Radial/conic gradients in the reference nest the mix inside the value. */
const findMixIn = (value) => parseColorMix(value);

for (const [name, source, property] of MIXES) {
  const kotlinMix = kotlinDerived.get(name);
  if (!kotlinMix) {
    check('Derived mixes', name, false, 'no DerivedMix entry in Kotlin');
    continue;
  }
  const cssText = source.startsWith('--')
    ? cssVar(source.slice(2), 'root')
    : declarationFor(source, property);
  const cssMix = findMixIn(cssText);
  if (!cssMix) {
    check('Derived mixes', name, false, `no color-mix found at ${source}${property ? ` ${property}` : ''} (value: ${cssText})`);
    continue;
  }
  const sameBase = cssMix.base === kotlinMix.base || `m3-${cssMix.base}` === kotlinMix.base;
  const sameFraction = Math.abs(cssMix.fraction - kotlinMix.fraction) < 1e-6;
  const samePartner = (cssMix.partner || null) === (kotlinMix.partner || null);
  const sameKind = cssMix.kind === kotlinMix.kind;
  check(
    'Derived mixes',
    name,
    sameBase && sameFraction && samePartner && sameKind,
    `css ${cssMix.raw} vs kotlin ${kotlinMix.kind} ${kotlinMix.base} ${kotlinMix.fraction} ${kotlinMix.partner ?? 'transparent'}`,
  );
}

// Mixes whose base is a literal colour rather than a custom property.
const LITERAL_MIXES = [
  ['scrimDark', '.scrim', 'background', 'scrimDarkBase'],
  ['listenHalo', '.receive-listen', 'box-shadow', 'acc'],
  ['playButtonGlow', '.music-play-glow', 'box-shadow', 'acc'],
];
for (const [name, source, property, expectedBase] of LITERAL_MIXES) {
  const kotlinMix = kotlinDerived.get(name);
  if (!kotlinMix) {
    check('Derived mixes', name, false, 'no DerivedMix entry in Kotlin');
    continue;
  }
  const cssText = declarationFor(source, property);
  const cssMix = findMixIn(cssText);
  const baseOk = kotlinMix.base === expectedBase;
  const fractionOk = cssMix ? Math.abs(cssMix.fraction - kotlinMix.fraction) < 1e-6 : true;
  check(
    'Derived mixes',
    name,
    baseOk && fractionOk,
    cssMix ? `css ${cssMix.raw} vs kotlin ${kotlinMix.base} ${kotlinMix.fraction}` : `kotlin ${kotlinMix.base} ${kotlinMix.fraction} (no css mix at ${source})`,
  );
}

// The final `.radar` layer paints its centre from an existing token rather than
// a new mix, so it is checked as a direct reference instead.
{
  const radarBackground = declarationFor('.radar', 'background') || '';
  const usesPrimaryContainer = /radial-gradient\(\s*circle\s*,\s*var\(\s*--m3-primary-container\s*\)/.test(radarBackground);
  check('Derived mixes', 'radar centre', usesPrimaryContainer, `css ${radarBackground}`);
  check('Derived mixes', 'radar centre has no stale Kotlin mix', !kotlinDerived.has('radarInner'),
    kotlinDerived.has('radarInner') ? 'radarInner still declared in MockupTokens.kt' : 'RadarIndicator uses colors.primaryContainer');
}

/* --------------------------------------------------------------- 3. metrics */

/**
 * [metric key, selector, property, transform]
 *
 * The transform turns a CSS value into the number the Kotlin token must hold.
 */
const px = (v) => pxNumber(v);
const lastPx = (v) => {
  const all = [...(v || '').matchAll(/(-?[\d.]+)px/g)].map((m) => Number(m[1]));
  return all.length ? all[all.length - 1] : null;
};
const firstPx = (v) => {
  const all = [...(v || '').matchAll(/(-?[\d.]+)px/g)].map((m) => Number(m[1]));
  return all.length ? all[0] : null;
};
const pct = (v) => {
  const p = /([\d.]+)%/.exec(v || '');
  return p ? Number(p[1]) / 100 : null;
};
const em = (v) => {
  const e = /([\d.]+)em/.exec(v || '');
  return e ? Number(e[1]) : null;
};
const num = (v) => {
  const n = /^([\d.]+)$/.exec((v || '').trim());
  return n ? Number(n[1]) : null;
};
const radiusVar = (v) => resolveVarPx(v);
const padTop = (v) => firstPx(v);
const padBottom = (v) => lastPx(v);
const padHorizontal = (v) => {
  const all = [...(v || '').matchAll(/([\d.]+)px/g)].map((m) => Number(m[1]));
  if (all.length === 1) return all[0];
  if (all.length === 2) return all[1];
  if (all.length >= 3) return all[1];
  return null;
};
const padVerticalFromTwo = (v) => {
  const all = [...(v || '').matchAll(/([\d.]+)px/g)].map((m) => Number(m[1]));
  return all.length >= 2 ? all[0] : null;
};
const animMillis = (v) => {
  const s = /([\d.]+)s/.exec(v || '');
  return s ? Number(s[1]) * 1000 : null;
};
const borderPx = (v) => firstPx(v);
const fontSizePx = (v) => pxNumber(v);

const METRICS = [
  ['radius.xs', ':root', '--m3-r-xs', radiusVar],
  ['radius.sm', ':root', '--m3-r-sm', radiusVar],
  ['radius.md', ':root', '--m3-r-md', radiusVar],
  ['radius.lg', ':root', '--m3-r-lg', radiusVar],
  ['radius.xl', ':root', '--m3-r-xl', radiusVar],
  ['radius.pill', ':root', '--m3-r-full', radiusVar],

  ['bottomNav.height', '.bn', 'height', px],
  ['bottomNav.paddingTop', '.bn', 'padding', padTop],
  ['bottomNav.paddingBottom', '.bn', 'padding', padBottom],
  ['bottomNav.paddingHorizontal', '.bn', 'padding', padHorizontal],
  ['bottomNav.labelSize', '.bn button', 'font-size', fontSizePx],
  ['bottomNav.itemMinWidth', '.bn button', 'min-width', px],
  ['bottomNav.pillMinWidth', '.bn .pill', 'min-width', px],
  ['bottomNav.pillPaddingHorizontal', '.bn .pill', 'padding', padHorizontal],
  ['bottomNav.pillPaddingVertical', '.bn .pill', 'padding', padVerticalFromTwo],

  ['actionBar.minHeight', '.ab', 'min-height', px],
  ['actionBar.paddingHorizontal', '.ab', 'padding', padHorizontal],
  ['actionBar.buttonMinHeight', '.ab button', 'min-height', px],
  ['actionBar.labelSize', '.ab button', 'font-size', fontSizePx],

  ['listItem.minHeight', '.li', 'min-height', px],
  ['listItem.gap', '.li', 'gap', px],
  ['listItem.paddingVertical', '.li', 'padding', padVerticalFromTwo],
  ['listItem.paddingHorizontal', '.li', 'padding', padHorizontal],

  ['iconButton.size', '.iconbtn', 'width', px],
  ['iconButton.radius', '.iconbtn', 'border-radius', px],
  ['iconButton.sizeSmall', '.tx-tools .iconbtn', 'width', px],

  ['button.minHeight', '.btn', 'min-height', px],
  ['button.paddingVertical', '.btn', 'padding', padVerticalFromTwo],
  ['button.paddingHorizontal', '.btn', 'padding', padHorizontal],
  ['button.weight', '.btn', 'font-weight', num],
  ['button.smallMinHeight', '.btn.sm', 'min-height', px],
  ['button.smallPaddingVertical', '.btn.sm', 'padding', padVerticalFromTwo],
  ['button.smallPaddingHorizontal', '.btn.sm', 'padding', padHorizontal],

  ['switch.width', '.sw', 'width', px],
  ['switch.height', '.sw', 'height', px],
  ['switch.borderWidth', '.sw', 'border', borderPx],
  ['switch.thumbOff', '.sw i', 'width', px],

  ['radar.size', '.radar', 'width', px],
  ['radar.coreSize', '.radar .core', 'width', px],
  ['radar.sweepDurationMillis', '.radar::after', 'animation', animMillis],

  ['header.minHeight', '.hd', 'min-height', px],
  ['header.titleSize', '.hd h1', 'font-size', fontSizePx],
  ['header.titleLineHeight', '.hd h1', 'line-height', px],
  ['header.titleWeight', '.hd h1', 'font-weight', num],

  ['grid.gapMedia', '.phone .grid3', 'gap', px],
  ['grid.gapApps', '.grid4', 'gap', px],

  ['section.size', '.sec', 'font-size', fontSizePx],
  ['section.weight', '.sec', 'font-weight', num],
  ['section.letterSpacingEm', '.sec', 'letter-spacing', em],
  ['section.marginTop', '.sec', 'margin', padTop],
  ['section.marginBottom', '.sec', 'margin', padBottom],

  ['meta.size', '.meta', 'font-size', fontSizePx],
  ['meta.lineHeight', '.meta', 'line-height', num],
  ['muted.size', '.mut', 'font-size', fontSizePx],

  ['chip.minHeight', '.chip', 'min-height', px],
  ['chip.radius', '.chip', 'border-radius', radiusVar],
  ['chip.paddingVertical', '.chip', 'padding', padVerticalFromTwo],
  ['chip.paddingHorizontal', '.chip', 'padding', padHorizontal],
  ['chip.weight', '.chip', 'font-weight', num],
  ['chip.size', '.chip', 'font-size', fontSizePx],
  ['chip.letterSpacingEm', '.chip', 'letter-spacing', em],

  ['card.radius', '.card', 'border-radius', radiusVar],
  ['wash.radius', '.wash', 'border-radius', radiusVar],
  ['fileIcon.size', '.ico', 'width', px],
  ['fileIcon.radius', '.ico', 'border-radius', px],
  ['avatar.size', '.av', 'width', px],
  ['avatar.textSize', '.av', 'font-size', fontSizePx],
  ['avatar.sizeLarge', '.av.lg', 'width', px],
  ['avatar.textSizeLarge', '.av.lg', 'font-size', fontSizePx],

  ['tab.paddingVertical', '.files-tabs button', 'padding', padTop],
  ['tab.paddingBottom', '.files-tabs button', 'padding', padBottom],
  ['tab.paddingHorizontal', '.files-tabs button', 'padding', padHorizontal],
  ['tab.selectedWeight', '.files-tabs button.on', 'font-weight', num],
  ['tab.unselectedWeight', '.files-tabs button', 'font-weight', num],

  ['dialog.radius', '.dlgcard', 'border-radius', radiusVar],
  ['dialog.paddingTop', '.dlgcard', 'padding', padTop],
  ['dialog.paddingHorizontal', '.dlgcard', 'padding', padHorizontal],
  ['sheet.radius', '.sheet', 'border-radius', radiusVar],
  ['sheet.maxHeightFraction', '.sheet', 'max-height', pct],
  ['sheet.paddingHorizontal', '.sheet', 'padding', padHorizontal],
  ['sheet.paddingBottom', '.sheet', 'padding', padBottom],

  ['statCard.radius', '.statcard', 'border-radius', radiusVar],
  ['statCard.padding', '.statcard', 'padding', px],
  ['statCard.valueSize', '.statcard b', 'font-size', fontSizePx],
  ['statCard.labelSize', '.statcard span', 'font-size', fontSizePx],
  ['tile.valueSize', '.tile b', 'font-size', fontSizePx],
  ['tile.labelSize', '.tile span', 'font-size', fontSizePx],
  ['tile.radius', '.tile', 'border-radius', radiusVar],
  ['tile.padding', '.tile', 'padding', padVerticalFromTwo],
  ['tile.paddingHorizontal', '.tile', 'padding', padHorizontal],

  ['progress.height', '.bar', 'height', px],
  ['progress.scrubberHeight', '.scrub .bar', 'height', px],
  ['photoTick.size', '.ph .tick', 'width', px],
  ['scrollbar.minThumbLength', '.phone .scr::-webkit-scrollbar-thumb', 'min-height', px],

  ['toast.bottomOffset', '.toast', 'bottom', px],
  ['toast.radius', '.toast', 'border-radius', radiusVar],
  ['toast.paddingVertical', '.toast', 'padding', padVerticalFromTwo],
  ['toast.paddingHorizontal', '.toast', 'padding', padHorizontal],
  ['toast.size', '.toast', 'font-size', fontSizePx],

  ['motion.stateDurationMillis', 'button,a,.ph,.li,.card,.statcard,.ffold,.drop', 'transition', animMillis],
  ['motion.sheetDurationMillis', '.sheet', 'animation', animMillis],
];

for (const [key, selector, property, transform] of METRICS) {
  const cssValue = declarationFor(selector, property);
  if (cssValue === undefined) {
    check('Metrics', key, false, `no ${property} on "${selector}" in the reference`);
    continue;
  }
  const expected = transform(cssValue);
  const actual = kotlinMetrics.get(key);
  if (expected === null) {
    check('Metrics', key, false, `could not parse "${cssValue}"`);
    continue;
  }
  if (actual === undefined) {
    check('Metrics', key, false, `reference says ${expected} but Kotlin has no "${key}" entry`);
    continue;
  }
  check('Metrics', key, Math.abs(expected - actual) < 1e-6, `kotlin ${actual} vs css ${cssValue} (${expected})`);
}

/* ------------------------------------------------- 4. pressed scale + motion */

const pressedScale = /button:active[^{]*\{[^}]*transform:scale\(([\d.]+)\)/.exec(css);
const kotlinPressed = kotlinMetrics.get('motion.pressedScale');
check(
  'Motion',
  'motion.pressedScale',
  pressedScale && kotlinPressed !== undefined && Math.abs(Number(pressedScale[1]) - kotlinPressed) < 1e-6,
  pressedScale ? `css scale(${pressedScale[1]}) vs kotlin ${kotlinPressed}` : 'no :active scale rule found',
);

const bezier = /--m3-motion:cubic-bezier\(([\d.,\s-]+)\)/.exec(css);
const bezierValues = bezier ? bezier[1].split(',').map((v) => Number(v.trim())) : [];
const kotlinBezier = [
  'motion.emphasizedControlX1',
  'motion.emphasizedControlY1',
  'motion.emphasizedControlX2',
  'motion.emphasizedControlY2',
].map((k) => kotlinMetrics.get(k));
check(
  'Motion',
  'emphasized cubic-bezier',
  bezierValues.length === 4 && bezierValues.every((v, i) => Math.abs(v - kotlinBezier[i]) < 1e-6),
  `css cubic-bezier(${bezierValues.join(',')}) vs kotlin ${kotlinBezier.join(',')}`,
);

/* ------------------------------------------------------- 5. onboarding slides */

const slideBlock = /const slides=\[([\s\S]*?)\];/.exec(html);
const slideColors = slideBlock
  ? [...slideBlock[1].matchAll(/c:'(#[0-9A-Fa-f]{6})',c2:'(#[0-9A-Fa-f]{6})'/g)].map((m) => [m[1], m[2]])
  : [];
check('Onboarding', 'slide gradients parsed', slideColors.length === 4, `found ${slideColors.length} slides`);
slideColors.forEach(([start, end], index) => {
  const i = index + 1;
  const kotlinStart = kotlinRaw.get(`onb${i}a|root`);
  const kotlinEnd = kotlinRaw.get(`onb${i}b|root`);
  check(
    'Onboarding',
    `slide ${i} gradient`,
    normalizeHex(start) === normalizeHex(kotlinStart) && normalizeHex(end) === normalizeHex(kotlinEnd),
    `css ${start}->${end} vs kotlin ${kotlinStart}->${kotlinEnd}`,
  );
});

/* --------------------------------------------- 5b. inline surface constants */

/**
 * The photo viewer and video player paint themselves with inline styles in the
 * reference document's templates, so their constants are verified there.
 */
const INLINE_CONSTANTS = [
  ['viewerBackdrop|root', /class="screen" style="background:(#[0-9A-Fa-f]{3,6})"/],
  ['viewerIconBackground|root', /width:44px;height:44px;border-radius:50%;background:(#[0-9A-Fa-f]{3,6})/],
  ['viewerIconContent|root', /width:44px;height:44px;border-radius:50%;background:#[0-9A-Fa-f]{3,6};color:(#[0-9A-Fa-f]{3,6})/],
  ['viewerMetaText|root', /class="meta" style="color:(#[0-9A-Fa-f]{3,6})"/],
  ['videoControlBar|root', /padding:14px 18px 22px;background:(#[0-9A-Fa-f]{3,6})/],
];
for (const [key, pattern] of INLINE_CONSTANTS) {
  const found = pattern.exec(html);
  const expected = found ? normalizeHex(found[1]) : null;
  const actual = normalizeHex(kotlinRaw.get(key));
  check('Inline surface constants', key, expected !== null && expected === actual,
    `kotlin ${kotlinRaw.get(key)} vs template ${found ? found[1] : 'not found'}`);
}

/* ------------------------------------------------------------------ 6. icons */

const iconsJson = JSON.parse(read(ICONS_JSON));
const iconIds = Array.isArray(iconsJson) ? iconsJson.map((i) => i.id || i.name) : Object.keys(iconsJson.icons || iconsJson);
const iconsKotlin = read(ICONS_KT);
const kotlinIconNames = new Set([...iconsKotlin.matchAll(/public val (\w+):/g)].map((m) => m[1]));
const drawableFiles = existsSync(DRAWABLES) ? readdirSync(DRAWABLES).filter((f) => f.endsWith('.xml')) : [];

check('Icons', 'generated icon count', iconIds.length === drawableFiles.length,
  `${iconIds.length} ids in icons.json vs ${drawableFiles.length} drawables`);

let missingDrawables = 0;
for (const m of iconsKotlin.matchAll(/R\.drawable\.(ic_morse_\w+)/g)) {
  if (!drawableFiles.includes(`${m[1]}.xml`)) missingDrawables++;
}
check('Icons', 'every MorseIcons entry resolves', missingDrawables === 0, `${missingDrawables} dangling drawable references`);
check('Icons', 'MorseIcons entries match icons.json', kotlinIconNames.size === iconIds.length,
  `${kotlinIconNames.size} Kotlin entries vs ${iconIds.length} generated icons`);

/* --------------------------------------------------------- 7. feature gates */

const readiness = read(READINESS_KT);
const currentMilestone = /CURRENT_MILESTONE:\s*Int\s*=\s*(\d+)/.exec(readiness);
const finalMilestone = /FINAL_MILESTONE:\s*Int\s*=\s*(\d+)/.exec(readiness);
const areas = [...readiness.matchAll(/\w+\("[\w_]+",\s*(\d+)\)/g)].map((m) => Number(m[1]));
const current = currentMilestone ? Number(currentMilestone[1]) : -1;
check('Feature gate', 'CURRENT_MILESTONE parsed', current > 0, `value ${current}`);
check(
  'Feature gate',
  'milestone 1 areas are live',
  areas.filter((a) => a <= 1).length >= 7,
  `${areas.filter((a) => a <= 1).length} areas delivered in milestone 1`,
);
// The gate's own promise, checked against its implementation rather than a restatement.
const availabilityRule = /fun isAvailable\(area: FeatureArea\): Boolean =\s*area\.deliveredInMilestone <= CURRENT_MILESTONE/.test(readiness);
const gatedRule = /gated[\s\S]{0,120}filterNot \{ isAvailable\(it\) \}/.test(readiness);
const nothingGatedAtTheEnd = current < Number(finalMilestone?.[1] ?? 0) || areas.every((a) => a <= current);
check(
  'Feature gate',
  'availability and gated lists are derived from CURRENT_MILESTONE',
  availabilityRule && gatedRule,
  `${availabilityRule ? 'isAvailable' : 'isAvailable MISSING'}, ${gatedRule ? 'gated' : 'gated MISSING'}`,
);
check(
  'Feature gate',
  'no gated area survives the final milestone',
  nothingGatedAtTheEnd,
  `${areas.filter((a) => a > current).length} areas gated at milestone ${current} of ${finalMilestone ? finalMilestone[1] : '?'}`,
);
check(
  'Feature gate',
  'later milestones still gated (no premature claims)',
  areas.some((a) => a > current) && Number(finalMilestone?.[1] ?? 0) > current,
  `current ${current}, final ${finalMilestone ? finalMilestone[1] : '?'}`,
);

/* ------------------------------------------------------------------- report */

report();
process.exit(failures === 0 ? 0 : 1);
