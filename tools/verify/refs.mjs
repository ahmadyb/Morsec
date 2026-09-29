#!/usr/bin/env node
/*
 * Reference resolution harness.
 *
 * The Android toolchain is not available in every environment this repo is
 * checked out into, so resource and token references cannot always be validated
 * by a compiler. This script does the equivalent check statically: every symbol
 * the Kotlin and XML sources refer to must actually be declared somewhere.
 *
 *   node tools/verify/refs.mjs          # full report
 *   node tools/verify/refs.mjs --quiet  # failures only
 *
 * Exit code 1 means something references a token, string, drawable or colour
 * that does not exist — i.e. the project would not compile.
 */

import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const QUIET = process.argv.includes('--quiet');

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

/* ------------------------------------------------------------------ walking */

const SKIP_DIRS = new Set(['.git', 'build', '.gradle', '.idea', 'node_modules', '.kotlin']);

function* walk(dir) {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const path = join(dir, entry);
    const info = statSync(path);
    if (info.isDirectory()) yield* walk(path);
    else yield path;
  }
}

const files = [...walk(ROOT)];
const kotlinFiles = files.filter((f) => f.endsWith('.kt'));
const xmlFiles = files.filter((f) => f.endsWith('.xml'));
const read = (p) => readFileSync(p, 'utf8');
const short = (p) => relative(ROOT, p);

/* ------------------------------------------------------- declaration indexes */

/** Locates a declaration file by its content, not by guessing its path. */
function fileDeclaring(pattern) {
  return kotlinFiles.find((f) => pattern.test(read(f)));
}

const metricsFile = fileDeclaring(/class MorseMetrics|data class MorseMetrics/);
const colorsFile = fileDeclaring(/class MorseColorTokens|data class MorseColorTokens/);
const iconsFile = fileDeclaring(/object MorseIcons/);
const textStylesFile = fileDeclaring(/object MorseTextStyles/);
const motionFile = fileDeclaring(/class MorseMotion/);

/** `val name:` / `val name =` declarations inside a Kotlin file. */
function declaredNames(path) {
  if (!path) return new Set();
  const names = new Set();
  for (const m of read(path).matchAll(/\bval\s+(\w+)\s*[:=]/g)) names.add(m[1]);
  for (const m of read(path).matchAll(/\bfun\s+(\w+)\s*\(/g)) names.add(m[1]);
  return names;
}

const metricsNames = declaredNames(metricsFile);
const colorsNames = declaredNames(colorsFile);
const iconsNames = declaredNames(iconsFile);
const textStylesNames = declaredNames(textStylesFile);
const motionNames = declaredNames(motionFile);

/* Android resources. */
const stringsXml = xmlFiles.filter((f) => f.endsWith('/values/strings.xml'));
const declaredStrings = new Set();
for (const f of stringsXml) for (const m of read(f).matchAll(/<(?:string|plurals|string-array)\s+name="([^"]+)"/g)) declaredStrings.add(m[1]);

const colorsXml = xmlFiles.filter((f) => /\/values\/.*color.*\.xml$/.test(f));
const declaredColors = new Set();
for (const f of colorsXml) for (const m of read(f).matchAll(/<color\s+name="([^"]+)"/g)) declaredColors.add(m[1]);

const drawableDirs = files.filter((f) => f.includes('/res/drawable') && f.endsWith('.xml'));
const declaredDrawables = new Set(drawableDirs.map((f) => f.split('/').pop().replace(/\.xml$/, '')));
const mipmapFiles = files.filter((f) => f.includes('/res/mipmap'));
const declaredMipmaps = new Set(mipmapFiles.map((f) => f.split('/').pop().replace(/\.(png|webp|xml)$/, '')));

/* ------------------------------------------------- 0. version-catalog aliases */

// Every `libs.x.y` accessor in a build script must exist in the catalog: Kotlin
// DSL turns '-' and '_' into '.', so an accessor resolves when some alias
// normalises to it. A typo here is a script-compilation failure in CI.
const catalogFile = join(ROOT, 'gradle', 'libs.versions.toml');
if (existsSync(catalogFile)) {
  const catalog = read(catalogFile);
  const sectionOf = (index) => {
    const before = catalog.slice(0, index);
    const sections = [...before.matchAll(/^\[([\w-]+)\]/gm)];
    return sections.length ? sections[sections.length - 1][1] : null;
  };
  const aliases = { libraries: new Set(), plugins: new Set(), versions: new Set() };
  for (const m of catalog.matchAll(/^([\w.-]+)\s*=\s*(.+)$/gm)) {
    const section = sectionOf(m.index);
    if (section && aliases[section]) aliases[section].add(m[1]);
  }
  const normalize = (alias) => alias.replace(/[-_]/g, '.');
  const libraries = new Set([...aliases.libraries].map(normalize));
  const plugins = new Set([...aliases.plugins].map(normalize));

  const buildFiles = files.filter((f) => f.endsWith('.gradle.kts'));
  const unresolvedLibs = [];
  const unresolvedPlugins = [];
  for (const f of buildFiles) {
    const text = read(f);
    for (const m of text.matchAll(/\blibs\.([\w.]+)/g)) {
      if (m[1].startsWith('plugins.')) continue; // checked against the plugin aliases below
      if (!libraries.has(m[1])) unresolvedLibs.push(`${short(f)}: libs.${m[1]}`);
    }
    for (const m of text.matchAll(/\blibs\.plugins\.([\w.]+)/g)) {
      if (!plugins.has(m[1])) unresolvedPlugins.push(`${short(f)}: libs.plugins.${m[1]}`);
    }
  }
  check('Version catalog', 'every libs.* accessor exists', unresolvedLibs.length === 0,
    unresolvedLibs.length ? unresolvedLibs.slice(0, 8).join(', ') : `${libraries.size} library aliases, ${buildFiles.length} build scripts scanned`);
  check('Version catalog', 'every libs.plugins.* accessor exists', unresolvedPlugins.length === 0,
    unresolvedPlugins.length ? unresolvedPlugins.slice(0, 8).join(', ') : `${plugins.size} plugin aliases resolve`);

  // version.ref / version.refs must point at a declared version.
  const badVersionRefs = [];
  for (const m of catalog.matchAll(/version(?:\.refs)?\s*=\s*\[?"?([\w.,\s-]+?)"?\]?\s*$/gm)) {
    for (const ref of m[1].split(',').map((s) => s.trim().replace(/"/g, '')).filter(Boolean)) {
      if (!aliases.versions.has(ref)) badVersionRefs.push(ref);
    }
  }
  check('Version catalog', 'every version.ref resolves', badVersionRefs.length === 0,
    badVersionRefs.length ? `unknown versions: ${badVersionRefs.join(', ')}` : `${aliases.versions.size} version entries`);
}

/* ------------------------------------------------------------ 1. token refs */

/**
 * Blanks out string literals so that token *keys* (`"motion.sheetDurationMillis"`)
 * are not mistaken for token *references*, and drops the `Map.getValue` accessor
 * used to read those keys.
 */
function codeOnly(text) {
  return text.replace(/"(?:[^"\\]|\\.)*"/g, '""').replace(/\.getValue\b/g, '.');
}

function referenceCheck(group, pattern, declared, label) {
  const missing = new Map();
  let total = 0;
  for (const f of kotlinFiles) {
    const text = codeOnly(read(f));
    for (const m of text.matchAll(pattern)) {
      total++;
      const name = m[1];
      if (!declared.has(name)) {
        if (!missing.has(name)) missing.set(name, []);
        missing.get(name).push(`${short(f)}:${text.slice(0, m.index).split('\n').length}`);
      }
    }
  }
  check(group, label, missing.size === 0,
    missing.size === 0
      ? `${total} references resolve`
      : `${missing.size} unresolved (${total} references): ${[...missing.keys()].slice(0, 6).join(', ')} — ${[...missing.values()][0]}`);
  return total;
}

referenceCheck('Design tokens', /\bmetrics\.(\w+)/g, metricsNames, 'metrics.* resolve to MorseMetrics fields');
referenceCheck('Design tokens', /\bcolors\.(\w+)/g, colorsNames, 'colors.* resolve to MorseColorTokens fields');
referenceCheck('Design tokens', /\bmotion\.(\w+)/g, motionNames, 'motion.* resolve to MorseMotion fields');
referenceCheck('Design tokens', /MorseIcons\.(\w+)/g, iconsNames, 'MorseIcons.* resolve to generated icons');
referenceCheck('Design tokens', /MorseTextStyles\.(\w+)/g, textStylesNames, 'MorseTextStyles.* resolve to text styles');

/* ------------------------------------------------ 1b. Compose API call shapes */

/**
 * Signatures the Kotlin compiler rejects but that are easy to write by hand:
 * `Modifier.padding` has a start/top/end/bottom family and a horizontal/vertical
 * family and the two cannot be mixed, and `initialStartOffset` is a parameter of
 * `infiniteRepeatable`, not of `tween`.
 */
function balancedArgs(text, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < text.length; i++) {
    if (text[i] === '(') depth++;
    else if (text[i] === ')') {
      depth--;
      if (depth === 0) return text.slice(openIndex + 1, i);
    }
  }
  return null;
}

const lineOf = (text, index) => text.slice(0, index).split('\n').length;
const EDGES = new Set(['start', 'top', 'end', 'bottom']);
const SYMMETRIC = new Set(['horizontal', 'vertical']);

const mixedPadding = [];
const misplacedOffsets = [];
for (const f of kotlinFiles) {
  const text = read(f);
  for (const m of text.matchAll(/\.padding\s*\(/g)) {
    const args = balancedArgs(text, m.index + m[0].length - 1);
    if (!args) continue;
    const named = new Set([...args.matchAll(/(\w+)\s*=/g)].map((x) => x[1]));
    const hasEdge = [...named].some((n) => EDGES.has(n));
    const hasSymmetric = [...named].some((n) => SYMMETRIC.has(n));
    if (hasEdge && hasSymmetric) mixedPadding.push(`${short(f)}:${lineOf(text, m.index)} (${[...named].join(', ')})`);
  }
  for (const m of text.matchAll(/\btween\s*\(/g)) {
    const args = balancedArgs(text, m.index + m[0].length - 1);
    if (args && /\binitialStartOffset\s*=/.test(args)) {
      misplacedOffsets.push(`${short(f)}:${lineOf(text, m.index)}`);
    }
  }
}

check('Compose API shapes', 'Modifier.padding never mixes edge and symmetric families',
  mixedPadding.length === 0, mixedPadding.length ? mixedPadding.join(', ') : `${kotlinFiles.length} files scanned`);
check('Compose API shapes', 'initialStartOffset is passed to infiniteRepeatable, not tween',
  misplacedOffsets.length === 0, misplacedOffsets.length ? misplacedOffsets.join(', ') : 'clean');

/* ------------------------------------------------------- 2. resource refs */

referenceCheck('Resources', /R\.string\.(\w+)/g, declaredStrings, 'R.string.* resolve to strings.xml entries');
referenceCheck('Resources', /R\.drawable\.(\w+)/g, declaredDrawables, 'R.drawable.* resolve to drawable files');
referenceCheck('Resources', /R\.color\.(\w+)/g, declaredColors, 'R.color.* resolve to colors.xml entries');
referenceCheck('Resources', /R\.mipmap\.(\w+)/g, declaredMipmaps, 'R.mipmap.* resolve to launcher icons');

function xmlReferenceCheck(label, pattern, declared, scope) {
  const missing = new Set();
  let total = 0;
  for (const f of xmlFiles) {
    if (scope && !scope.test(short(f))) continue;
    const text = read(f);
    for (const m of text.matchAll(pattern)) {
      total++;
      if (!declared.has(m[1])) missing.add(m[1]);
    }
  }
  check('Resources', label, missing.size === 0,
    missing.size === 0 ? `${total} references resolve` : `unresolved: ${[...missing].slice(0, 8).join(', ')}`);
}

xmlReferenceCheck('@string/* in XML resolve', /@string\/(\w+)/g, declaredStrings, null);
xmlReferenceCheck('@color/* in XML resolve', /@color\/(\w+)/g, declaredColors, null);
xmlReferenceCheck('@drawable/* in XML resolve', /@drawable\/(\w+)/g, declaredDrawables, null);
xmlReferenceCheck('@mipmap/* in XML resolve', /@mipmap\/(\w+)/g, declaredMipmaps, null);

/* ---------------------------------------------------- 3. wiring sanity checks */

// Every ViewModel that is navigated to must be Hilt-annotated, and every screen
// composable must be reachable from the destination table.
const viewModels = kotlinFiles.filter((f) => /ViewModel\.kt$/.test(short(f)));
const missingHilt = viewModels.filter((f) => !/@HiltViewModel/.test(read(f)));
check('Wiring', 'every ViewModel is @HiltViewModel', missingHilt.length === 0,
  missingHilt.length ? missingHilt.map(short).join(', ') : `${viewModels.length} view models annotated`);

const screens = kotlinFiles.filter((f) => /Screen\.kt$/.test(short(f)));
const navFile = kotlinFiles.find((f) => /navigation\/MorseApp\.kt$/.test(short(f)));
const navText = navFile ? read(navFile) : '';
const unreachable = screens.filter((f) => {
  const composables = [...read(f).matchAll(/public fun (\w+Screen)\s*\(/g)].map((m) => m[1]);
  return composables.length > 0 && composables.every((c) => !navText.includes(c));
});
check('Wiring', 'every screen composable is reachable from navigation', unreachable.length === 0,
  unreachable.length ? unreachable.map(short).join(', ') : `${screens.length} screens wired into MorseApp`);

const routesFile = kotlinFiles.find((f) => /navigation\/Routes\.kt$/.test(short(f)));
const destinationsFile = kotlinFiles.find((f) => /navigation\/MorseDestination\.kt$/.test(short(f)));
if (routesFile && destinationsFile) {
  const routesText = read(routesFile);
  const routeNames = [...routesText.matchAll(/const val (\w+)\s*(?::\s*\w+\s*)?=/g)].map((m) => m[1]);
  // A route counts as used when something other than its own declaration mentions it.
  const mentions = (name) => kotlinFiles.reduce((count, f) => {
    const text = read(f);
    const hits = [...text.matchAll(new RegExp(`\\b${name}\\b`, 'g'))].length;
    return f === routesFile ? count + Math.max(0, hits - 1) : count + hits;
  }, 0);
  const unusedRoutes = routeNames.filter((r) => mentions(r) === 0);
  check('Wiring', 'every route constant is used', unusedRoutes.length === 0,
    unusedRoutes.length
      ? `declared but unreferenced: ${unusedRoutes.join(', ')}`
      : `${routeNames.length} route constants referenced`);

  // Bottom navigation must expose exactly the reference's four destinations.
  const navRoutes = [...read(destinationsFile).matchAll(/Routes\.(\w+)/g)].map((m) => m[1]);
  const expectedNav = ['CONNECT', 'FILES', 'HISTORY', 'SETTINGS'];
  check('Wiring', 'bottom navigation matches the reference order',
    navRoutes.join(',') === expectedNav.join(','), `found ${navRoutes.join(', ')}`);
}

// The manifest must declare every component the app starts.
const manifest = xmlFiles.find((f) => f.endsWith('/app/src/main/AndroidManifest.xml'));
if (manifest) {
  const manifestText = read(manifest);
  const declaredComponents = [...manifestText.matchAll(/android:name="(\.?[\w.]+)"/g)].map((m) => m[1]);
  const activityFiles = kotlinFiles.filter((f) => /Activity\.kt$|Application\.kt$/.test(short(f)));
  const missing = activityFiles.filter((f) => {
    const className = f.split('/').pop().replace('.kt', '');
    return !declaredComponents.some((c) => c.endsWith(className));
  });
  check('Wiring', 'Application/Activity classes are declared in the manifest', missing.length === 0,
    missing.length ? `not declared: ${missing.map(short).join(', ')}` : `${declaredComponents.length} manifest components`);
}

/* --------------------------------------------------------------- 4. hygiene */

// Mirrors the `checkMilestoneHygiene` Gradle task so the rule can be enforced
// without a JVM: identical marker list and identical source roots.
const FORBIDDEN_MARKERS = ['TODO(', 'TODO:', 'FIXME', 'XXX', 'HACK', 'NotImplementedError', 'coming soon', 'not implemented yet'];
const SOURCE_ROOTS = ['app/src', 'core-model/src', 'core-design/src', 'core-data/src', 'core-storage/src',
  'core-transfer/src', 'transport-lan/src', 'transport-nearby/src', 'webshare-server/src', 'media/src'];
const SOURCE_EXTENSIONS = new Set(['.kt', '.kts', '.xml', '.ts', '.css', '.mjs']);
const offenders = [];
let scanned = 0;
for (const f of files) {
  const rel = short(f);
  if (!SOURCE_ROOTS.some((root) => rel.startsWith(root))) continue;
  if (!SOURCE_EXTENSIONS.has(rel.slice(rel.lastIndexOf('.')))) continue;
  scanned++;
  const lines = read(f).split('\n');
  lines.forEach((line, index) => {
    const marker = FORBIDDEN_MARKERS.find((m) => line.toLowerCase().includes(m.toLowerCase()));
    if (marker) offenders.push(`${rel}:${index + 1} "${marker}"`);
  });
}
check('Hygiene', 'no forbidden milestone markers in delivered sources', offenders.length === 0,
  offenders.length ? offenders.slice(0, 8).join('; ') : `${scanned} source files scanned`);

// "placeholder" is a legitimate Compose parameter name, so only wording that
// admits to unimplemented behaviour is treated as a violation.
const placeholder = /(lorem ipsum|dummy (data|value|text)|fake (progress|transfer|data)|not implemented|unimplemented|stub implementation|coming soon)/i;
const placeholders = kotlinFiles.filter((f) => placeholder.test(read(f)));
check('Hygiene', 'no placeholder/stub language in Kotlin sources', placeholders.length === 0,
  placeholders.length ? `${placeholders.map(short).join(', ')}` : 'clean');

report();
process.exit(failures === 0 ? 0 : 1);
