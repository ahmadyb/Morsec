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

/* --------------------------------------- 1c. imports for top-level extensions */

/**
 * A top-level extension function needs an import wherever it is used outside its
 * own package. Missing one is an "unresolved reference" at compile time, and it is
 * invisible to a symbol scan that only looks at declared members.
 */
const packageOf = (text) => (/^package\s+([\w.]+)/m.exec(text) || [null, ''])[1];
const importsOf = (text) => new Set([...text.matchAll(/^import\s+([\w.]+(?:\.\*)?)/gm)].map((m) => m[1]));
const TOP_LEVEL_FUN = /^(?:public |internal )?(?:inline |suspend |operator |infix )*(?:fun)\s+(?:<[^>]*>\s*)?(?:([\w.<>]+)\.)?(\w+)\s*\(/gm;

const declaringPackage = new Map(); // extension name -> Set of packages
const parsed = kotlinFiles.map((f) => {
  const text = read(f);
  const pkg = packageOf(text);
  const imports = importsOf(text);
  const extensions = new Set();
  for (const m of text.matchAll(TOP_LEVEL_FUN)) {
    if (m[1]) extensions.add(m[2]);
  }
  for (const name of extensions) {
    const packages = declaringPackage.get(name) ?? new Set();
    packages.add(pkg);
    declaringPackage.set(name, packages);
  }
  return { file: f, rel: short(f), text, pkg, imports };
});

const uniqueExtensions = new Map(
  [...declaringPackage].filter(([, packages]) => packages.size === 1).map(([name, packages]) => [name, [...packages][0]]),
);

const missingImports = [];
for (const entry of parsed) {
  for (const [name, pkg] of uniqueExtensions) {
    if (!pkg || pkg === entry.pkg) continue;
    if (entry.imports.has(`${pkg}.${name}`) || entry.imports.has(`${pkg}.*`)) continue;
    const use = new RegExp(`\\.\\s*${name}\\s*\\(`).exec(entry.text);
    if (!use) continue;
    const line = entry.text.slice(0, use.index).split('\n').length;
    missingImports.push(`${entry.rel}:${line} uses .${name}() declared in ${pkg} without importing it`);
  }
}
check('Kotlin imports', 'top-level extension functions are imported where used', missingImports.length === 0,
  missingImports.length ? missingImports.slice(0, 8).join('; ') : `${uniqueExtensions.size} unique top-level extensions traced`);

/* ------------------------------------------------- 1d. annotation imports */

/**
 * Annotations that must be imported. A missing `import javax.inject.Singleton`
 * is not a compile error for Kotlin — it is a KSP/Dagger processing failure
 * ("'Singleton' could not be resolved"), which is much harder to diagnose.
 * `kotlin.*` annotations are default-imported and therefore not listed.
 */
const ANNOTATION_IMPORTS = {
  Singleton: 'javax.inject.Singleton',
  Inject: 'javax.inject.Inject',
  Provides: 'dagger.Provides',
  Binds: 'dagger.Binds',
  Module: 'dagger.Module',
  InstallIn: 'dagger.hilt.InstallIn',
  ApplicationContext: 'dagger.hilt.android.qualifiers.ApplicationContext',
  HiltViewModel: 'dagger.hilt.android.lifecycle.HiltViewModel',
  AndroidEntryPoint: 'dagger.hilt.android.AndroidEntryPoint',
  HiltAndroidTest: 'dagger.hilt.android.testing.HiltAndroidTest',
  HiltWorker: 'androidx.hilt.work.HiltWorker',
  Composable: 'androidx.compose.runtime.Composable',
  Immutable: 'androidx.compose.runtime.Immutable',
  Stable: 'androidx.compose.runtime.Stable',
  Qualifier: 'javax.inject.Qualifier',
  Entity: 'androidx.room.Entity',
  Dao: 'androidx.room.Dao',
  Database: 'androidx.room.Database',
  PrimaryKey: 'androidx.room.PrimaryKey',
  ColumnInfo: 'androidx.room.ColumnInfo',
  Query: 'androidx.room.Query',
  Insert: 'androidx.room.Insert',
  Update: 'androidx.room.Update',
  Delete: 'androidx.room.Delete',
  Upsert: 'androidx.room.Upsert',
  Transaction: 'androidx.room.Transaction',
  TypeConverters: 'androidx.room.TypeConverters',
  StringRes: 'androidx.annotation.StringRes',
  DrawableRes: 'androidx.annotation.DrawableRes',
  ColorInt: 'androidx.annotation.ColorInt',
  WorkerInject: 'androidx.work.WorkerParameters',
  Serializable: 'kotlinx.serialization.Serializable',
  SerialName: 'kotlinx.serialization.SerialName',
  VisibleForTesting: 'androidx.annotation.VisibleForTesting',
  RequiresApi: 'androidx.annotation.RequiresApi',
  SuppressLint: 'android.annotation.SuppressLint',
  Keep: 'androidx.annotation.Keep',
  ExperimentalCoroutinesApi: 'kotlinx.coroutines.ExperimentalCoroutinesApi',
  FlowPreview: 'kotlinx.coroutines.FlowPreview',
};

/** Removes comments and string literals so documentation prose is not scanned. */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""')
    .replace(/'''[\s\S]*?'''/g, '""');
}

const missingAnnotationImports = [];
for (const f of kotlinFiles) {
  const raw = read(f);
  const text = stripComments(raw);
  const imports = new Set([...raw.matchAll(/^import\s+([\w.]+)/gm)].map((m) => m[1]));
  for (const m of text.matchAll(/@(\w+)/g)) {
    const required = ANNOTATION_IMPORTS[m[1]];
    if (!required) continue;
    const pkg = required.slice(0, required.lastIndexOf('.'));
    if (imports.has(required) || imports.has(`${pkg}.*`)) continue;
    if ([...imports].some((i) => i.endsWith(`.${m[1]}`))) continue;
    missingAnnotationImports.push(`${short(f)}:${raw.slice(0, raw.indexOf(m[0]) < 0 ? 0 : raw.indexOf(m[0])).split('\n').length} @${m[1]} needs ${required}`);
  }
}
check('Kotlin imports', 'every framework annotation is imported', missingAnnotationImports.length === 0,
  missingAnnotationImports.length
    ? [...new Set(missingAnnotationImports)].slice(0, 10).join('; ')
    : `${kotlinFiles.length} files scanned against ${Object.keys(ANNOTATION_IMPORTS).length} known annotations`);

/* ------------------------------- 1e. cross-package names and named arguments */

/**
 * Two more things the compiler would catch that a symbol scan will not:
 *
 *  A. a name declared in one package and used from another without an import
 *     ("unresolved reference"), and
 *  B. a named argument the callee does not declare ("cannot find a parameter").
 *
 * Both matter because this repository is written without a local toolchain.
 */

/** Blanks comments, string literals and backtick identifiers, keeping newlines. */
function blanked(text) {
  const blank = (match) => match.replace(/[^\n]/g, ' ');
  const triple = '"""';
  return text
    .replace(/\/\*[\s\S]*?\*\//g, blank)
    .replace(new RegExp(triple + '[\\s\\S]*?' + triple, 'g'), blank)
    .replace(/"(?:[^"\\]|\\.)*"/g, blank)
    .replace(/`[^`\n]*`/g, blank)
    .replace(/(^|[^:])\/\/[^\n]*/g, (m, prefix) => prefix + ' '.repeat(m.length - prefix.length));
}

function balancedFrom(text, openIndex) {
  let depth = 0;
  for (let i = openIndex; i < text.length; i++) {
    const c = text[i];
    // Angle brackets are not tracked: '->' and generics make them unreliable.
    if (c === '(' || c === '[' || c === '{') depth++;
    else if (c === ')' || c === ']' || c === '}') {
      depth--;
      if (depth === 0) return text.slice(openIndex + 1, i);
    }
  }
  return null;
}

function splitTopLevel(argText) {
  const parts = [];
  let depth = 0;
  let current = '';
  for (const ch of argText) {
    if (ch === '(' || ch === '[' || ch === '{') depth++;
    else if (ch === ')' || ch === ']' || ch === '}') depth--;
    if (ch === ',' && depth === 0) {
      parts.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  if (current.trim()) parts.push(current);
  return parts;
}

function parameterNames(argText) {
  const names = new Set();
  for (const part of splitTopLevel(argText || '')) {
    const m = /^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:public|internal|private|protected|override|val|var|vararg|crossinline|noinline|out|in)\s+)*(\w+)\s*:/.exec(part);
    if (m) names.add(m[1]);
  }
  return names;
}

const typePackages = new Map();     // name -> Set<package>
const declaredPackages = new Map(); // name -> Set<package> (types, functions, properties)
const callableParams = new Map();   // name -> Set<parameter names>
const parsedFiles = kotlinFiles.map((f) => {
  const raw = read(f);
  const code = blanked(raw);
  const pkg = (/^package\s+([\w.]+)/m.exec(raw) || [null, ''])[1];
  const imports = new Set([...raw.matchAll(/^import\s+([\w.]+(?:\.\*)?)/gm)].map((m) => m[1]));
  const local = new Set([...code.matchAll(/\b(?:class|interface|object|fun|val|var)\s+(\w+)/g)].map((m) => m[1]));
  const record = (name, target) => {
    const set = target.get(name) ?? new Set();
    set.add(pkg);
    target.set(name, set);
  };

  const typePattern = /^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private )*(?:abstract |open |sealed |data |value |enum |annotation )*(?:class|interface|object)\s+(\w+)/gm;
  for (const m of code.matchAll(typePattern)) {
    record(m[1], typePackages);
    record(m[1], declaredPackages);
    const from = m.index + m[0].length - 1;
    const open = code.indexOf('(', from);
    const brace = code.indexOf('{', from);
    if (open !== -1 && (brace === -1 || open < brace)) {
      const params = callableParams.get(m[1]) ?? new Set();
      for (const name of parameterNames(balancedFrom(code, open))) params.add(name);
      callableParams.set(m[1], params);
    }
  }
  const functionPattern = /^(?:public |internal |private )*(?:inline |suspend |operator |infix )*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>]+\.)?(\w+)\s*\(/gm;
  for (const m of code.matchAll(functionPattern)) {
    record(m[1], declaredPackages);
    const params = callableParams.get(m[1]) ?? new Set();
    for (const name of parameterNames(balancedFrom(code, m.index + m[0].length - 1))) {
      params.add(name);
      // A parameter shadows a repo-wide declaration for the whole file, so
      // `fun handleGuard(rejection: Rejection)` must not be reported as an
      // unimported use of the top-level `rejection` helper.
      local.add(name);
    }
    callableParams.set(m[1], params);
  }
  for (const m of code.matchAll(/^(?:public |internal |private )*(?:val|var)\s+(\w+)/gm)) {
    record(m[1], declaredPackages);
  }

  /*
   * Every function parameter in the file, including indented ones the
   * declaration pattern above skips, is a local for this check.
   */
  const anyFunctionPattern = /^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |protected |open |override |inline |suspend |operator |infix |tailrec |external |actual |expect )*fun\s+(?:<[^>]*>\s*)?(?:[\w.<>]+[.])?(\w+)\s*\(/gm;
  for (const m of code.matchAll(anyFunctionPattern)) {
    for (const name of parameterNames(balancedFrom(code, m.index + m[0].length - 1))) local.add(name);
  }

  /*
   * Lambda parameters, likewise: `mutate { current -> … }` is not a use of the
   * top-level `current` extension. Only an identifier directly introduced by
   * `{`, `(` or `,` counts, so a `when` branch (`is Foo ->`, `else ->`) cannot
   * register a type name as a local and mask a genuine missing import.
   */
  for (const m of code.matchAll(/[{(,]\s*([A-Za-z_]\w*)(?:\s*:\s*[^->{;]*)?\s*->/g)) local.add(m[1]);
  for (const m of code.matchAll(/\{\s*((?:[A-Za-z_]\w*\s*,\s*)+[A-Za-z_]\w*)\s*->/g)) {
    for (const name of m[1].split(',')) local.add(name.trim());
  }
  // Named arguments, loop variables and catch parameters are declarations in
  // their own right: `ProgressBarRangeInfo(current = …)` and
  // `for (rejection in rejections)` are not unimported uses of the top-level
  // `current` / `rejection` helpers.
  for (const m of code.matchAll(/[({,]\s*([A-Za-z_]\w*)\s*=(?!=)/g)) local.add(m[1]);
  // `for (x in xs)` — the loop variable sits inside the parentheses, so the
  // pattern cannot simply look for the first `)` followed by `in`.
  for (const m of code.matchAll(/\bfor\s*\(([^()]*?)\s+in\s+[^()]*\)/g)) {
    for (const part of m[1].split(',')) {
      const name = part.trim();
      if (/^[A-Za-z_]\w*$/.test(name)) local.add(name);
    }
  }
  for (const m of code.matchAll(/\bcatch\s*\(\s*([A-Za-z_]\w*)/g)) local.add(m[1]);

  return { rel: short(f), raw, code, pkg, imports, local };
});

const unimported = [];
const badArguments = [];
for (const entry of parsedFiles) {
  for (const [name, packages] of declaredPackages) {
    if (packages.size !== 1) continue;
    const pkg = [...packages][0];
    if (!pkg || pkg === entry.pkg || entry.local.has(name)) continue;
    if ([...entry.imports].some((i) => i === `${pkg}.${name}` || i === `${pkg}.*` || i.endsWith(`.${name}`))) continue;
    const use = new RegExp(`(?<![.\\w])${name}\\b`).exec(entry.code);
    if (use) {
      unimported.push(`${entry.rel}:${entry.code.slice(0, use.index).split('\n').length} uses ${name} (declared in ${pkg}) without importing it`);
    }
  }
  for (const m of entry.code.matchAll(/(?<![.\w])(\w+)\s*\(/g)) {
    const params = callableParams.get(m[1]);
    if (!params || params.size === 0) continue;
    const args = balancedFrom(entry.code, m.index + m[0].length - 1);
    if (args === null) continue;
    for (const part of splitTopLevel(args)) {
      const named = /^\s*(\w+)\s*=(?!=)/.exec(part);
      if (!named || params.has(named[1])) continue;
      badArguments.push(`${entry.rel}:${entry.code.slice(0, m.index).split('\n').length} ${m[1]}(${named[1]} = …) — callee declares ${[...params].sort().join(', ')}`);
    }
  }
}

check('Kotlin imports', 'repo names used across packages are imported', unimported.length === 0,
  unimported.length ? [...new Set(unimported)].slice(0, 10).join('; ') : `${declaredPackages.size} top-level declarations traced`);
check('Call sites', 'named arguments match the callee declaration', badArguments.length === 0,
  badArguments.length ? [...new Set(badArguments)].slice(0, 10).join('; ') : `${callableParams.size} callables checked`);

/* ------------------------------------- 1f. internal types in public signatures */

/**
 * `internal` visibility is per Gradle module, and the compiler rejects a public
 * declaration that exposes an internal type — an easy mistake in hand-written DI
 * modules. Test source sets are friends of main, so they are excluded.
 */
const moduleOf = (path) => path.split('/')[1] ?? '.';
const isTestSource = (path) => /\/(?:test|androidTest|testDebug|testRelease)\//.test(path);
const INTERNAL_TYPE = /^internal\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:abstract\s+|open\s+|sealed\s+|data\s+|value\s+|enum\s+|annotation\s+)*(?:class|interface|object)\s+(\w+)/gm;
const PUBLIC_MEMBER = /^[ \t]*public\s+(?:abstract\s+|open\s+|suspend\s+|inline\s+)*(?:fun|val|var)\b/gm;

const internalByModule = new Map();
for (const f of kotlinFiles) {
  if (isTestSource(short(f))) continue;
  const mod = moduleOf(short(f));
  const names = internalByModule.get(mod) ?? new Set();
  for (const m of read(f).matchAll(INTERNAL_TYPE)) names.add(m[1]);
  internalByModule.set(mod, names);
}

const exposures = [];
for (const f of kotlinFiles) {
  const rel = short(f);
  if (isTestSource(rel)) continue;
  const names = internalByModule.get(moduleOf(rel));
  if (!names || names.size === 0) continue;
  const text = read(f);
  for (const m of text.matchAll(PUBLIC_MEMBER)) {
    let end = m.index + m[0].length;
    let depth = 0;
    for (; end < text.length && end < m.index + 800; end++) {
      const c = text[end];
      if (c === '(' || c === '[' || c === '<') depth++;
      else if (c === ')' && depth === 0 && /^\s*\{/.test(text.slice(end + 1))) break;
      else if (c === ')' || c === ']' || (c === '>' && text[end - 1] !== '-')) depth--;
      else if (depth === 0 && (c === '{' || c === '=')) break;
      else if (depth === 0 && c === '\n' && text[end + 1] === '\n') break;
    }
    const signature = text.slice(m.index, end);
    const hit = [...names].filter((n) => new RegExp(`\\b${n}\\b`).test(signature));
    if (hit.length) exposures.push(`${rel}:${text.slice(0, m.index).split('\n').length} exposes ${hit.join(', ')}`);
  }
}
check('Kotlin visibility', 'no public member exposes an internal type', exposures.length === 0,
  exposures.length ? exposures.slice(0, 10).join('; ') : `${[...internalByModule.values()].reduce((n, s) => n + s.size, 0)} internal types kept out of public signatures`);

/* ------------------------------------------------------ 1g. dependency graph */

/**
 * Every constructor dependency must be satisfiable: a concrete class with an
 * @Inject constructor, an @Binds/@Provides target in a Hilt module, or one of
 * the platform types Hilt supplies. A gap is not a Kotlin compile error — it is
 * a KSP/Dagger failure ("cannot be provided without an @Provides-annotated
 * method"), which costs a whole build to discover.
 */
const HILT_SUPPLIED = new Set([
  'Context', 'Application', 'Resources', 'AssetManager', 'ContentResolver',
  'PackageManager', 'SharedPreferences',
  // Hilt's ViewModel factory binds this for every @HiltViewModel from the host's
  // SavedStateRegistry, so it needs no module of its own.
  'SavedStateHandle',
]);
const PRIMITIVES = new Set(['String', 'Int', 'Long', 'Boolean', 'Float', 'Double', 'Byte', 'Short', 'Char']);

const provided = new Set();
const bound = new Set();
const injectable = new Set();
const constructorDeps = [];

for (const entry of parsedFiles) {
  const code = entry.code;
  for (const m of code.matchAll(/@Provides[\s\S]{0,300}?fun\s+(?:[\w.<>]+\.)?(\w+)\s*\([^)]*\)\s*:\s*([\w.<>]+)/g)) {
    provided.add(m[2].split('<')[0].split('.').pop());
  }
  for (const m of code.matchAll(/@Binds[\s\S]{0,300}?fun\s+(\w+)\s*\([^)]*\)\s*:\s*([\w.<>]+)/g)) {
    bound.add(m[2].split('<')[0].split('.').pop());
  }
  for (const m of code.matchAll(/(?:class|object)\s+(\w+)[^\n{]*@Inject\s+constructor/g)) {
    injectable.add(m[1]);
  }
  for (const m of code.matchAll(/@Inject\s+constructor\s*\(/g)) {
    const args = balancedFrom(code, m.index + m[0].length - 1);
    if (args === null) continue;
    const ownerLine = code.slice(0, m.index).trimEnd().split('\n').pop() || '';
    const owner = /(?:class|object)\s+(\w+)/.exec(ownerLine);
    const types = [...args.matchAll(/:\s*([\w.<>]+)/g)].map((x) => x[1].split('<')[0].split('.').pop());
    constructorDeps.push({ rel: entry.rel, owner: owner ? owner[1] : '?', types });
  }
}

const unsatisfied = [];
for (const dep of constructorDeps) {
  for (const type of dep.types) {
    if (HILT_SUPPLIED.has(type) || PRIMITIVES.has(type)) continue;
    if (provided.has(type) || bound.has(type) || injectable.has(type)) continue;
    if (/(Dispatcher|Scope|Dispatcher\w*)$/.test(type) && provided.has('CoroutineDispatcher')) continue;
    unsatisfied.push(`${dep.rel}: ${dep.owner} needs ${type}`);
  }
}
check('Dependency graph', 'every @Inject constructor dependency is satisfiable', unsatisfied.length === 0,
  unsatisfied.length
    ? unsatisfied.slice(0, 10).join('; ')
    : `${constructorDeps.length} injected constructors, ${provided.size} @Provides, ${bound.size} @Binds, ${injectable.size} injectable types`);

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
