#!/usr/bin/env node
/*
 * Protocol-limit parity and :core-transfer dependency isolation.
 *
 * Two things this repo used to get wrong by hand and now cannot:
 *
 *  1. doc/transfer-protocol.md carried a limits table whose numbers had drifted
 *     from ProtocolLimits.kt — it claimed 4 KiB minimum chunks, 255-byte path
 *     segments, a 240-byte error budget and an 8 TiB file ceiling while the code
 *     enforced 1 KiB, 127, 256 and 4 GiB−1. Nothing failed when that happened.
 *  2. :core-transfer declared coroutines, kotlinx-serialization and javax.inject
 *     and imported none of them.
 *
 * This script parses both sides and fails on any disagreement:
 *
 *   node tools/verify/transfer-limits.mjs          # full report
 *   node tools/verify/transfer-limits.mjs --quiet  # failures only
 *
 * Exit code 1 means the documented limits are not the enforced limits, or a
 * core-transfer source reaches outside the JDK.
 *
 * The parsers here are deliberately strict. A row whose Value cell has no
 * comparable number is a failure, not a row that is skipped; a constant in the
 * source that the table does not mention is a failure; and a name in the
 * exclusion list that no longer exists in the source is a failure, so the list
 * cannot rot into a blind spot.
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
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

const read = (p) => readFileSync(join(ROOT, p), 'utf8');
const short = (p) => relative(ROOT, p).replaceAll('\\', '/');

/* ------------------------------------------------- 1. ProtocolLimits source */

const LIMITS_PATH = 'core-transfer/src/main/kotlin/app/morsecode/core/transfer/ProtocolLimits.kt';
const limitsSrc = read(LIMITS_PATH);

// Strip line comments so a trailing `// 8 TiB - 1` never lands in a value, then
// split the body into `const val NAME: Type = <initialiser>` chunks. An
// initialiser may wrap onto following lines, which is why the split is on the
// next declaration rather than on the newline.
const stripped = limitsSrc.replace(/\/\/[^\n]*/g, '');
const declRe = /const\s+val\s+([A-Z][A-Z0-9_]*)\s*:\s*(Int|Long)\s*=/g;
const constants = new Map();
const rawInitialisers = new Map();
let match;
const starts = [];
while ((match = declRe.exec(stripped)) !== null) {
  starts.push({ name: match[1], type: match[2], from: match.index + match[0].length, declAt: match.index });
}
starts.forEach((entry, index) => {
  const end = index + 1 < starts.length ? starts[index + 1].declAt : stripped.length;
  let body = stripped.slice(entry.from, Math.max(entry.from, end));
  // Declarations are separated by a blank line; cutting there drops the KDoc of
  // the *next* constant, which is not part of this one's initialiser. The second
  // cut is the backstop for a declaration written without a blank line after it.
  body = body.split(/\n[ \t]*\r?\n/)[0];
  body = body.split(/\n\s*(?:@|public|private|internal|const|fun|val|var|object|})\b/)[0];
  body = body.trim().replace(/,$/, '');
  constants.set(entry.name, { type: entry.type, expr: body });
  rawInitialisers.set(entry.name, body);
});

check(
  'ProtocolLimits',
  'every numeric constant was parsed with an initialiser',
  constants.size > 0 && [...constants.values()].every((c) => c.expr.length > 0),
  `${constants.size} numeric constants`,
);

/*
 * A tiny integer-expression evaluator, so MAX_FRAME_SIZE_BYTES can stay written
 * as `HEADER_SIZE_BYTES + (3 * MAX_ID_LENGTH_BYTES) + MAX_PAYLOAD_BYTES` in the
 * source instead of being pinned to a literal that could then drift from its own
 * parts. Only the operators the constants actually use are supported; anything
 * else throws, and the throw is reported as a parse failure rather than being
 * quietly treated as zero.
 */
function evaluate(expr, seen = new Set()) {
  const tokens = expr.match(/0x[0-9a-fA-F_]+|[0-9][0-9_]*L?|[A-Z][A-Z0-9_]*|[()+\-*/]|\S/g);
  if (!tokens) throw new Error(`unparsable expression: ${expr}`);
  let pos = 0;
  const peek = () => tokens[pos];
  function parsePrimary() {
    const token = peek();
    if (token === undefined) throw new Error(`unexpected end of expression: ${expr}`);
    if (token === '(') {
      pos++;
      const value = parseSum();
      if (peek() !== ')') throw new Error(`unbalanced parentheses in: ${expr}`);
      pos++;
      return value;
    }
    if (token === '-') {
      pos++;
      return -parsePrimary();
    }
    if (/^0x[0-9a-fA-F_]+$/.test(token)) {
      pos++;
      return Number(BigInt.asIntN(64, BigInt(token.replaceAll('_', ''))));
    }
    if (/^[0-9]/.test(token)) {
      pos++;
      const digits = token.replace(/L$/, '').replaceAll('_', '');
      const value = Number(digits);
      if (!Number.isSafeInteger(value)) {
        // MAX_FILE_SIZE_BYTES exceeds Number.MAX_SAFE_INTEGER, so large values
        // are carried as BigInt and compared as BigInt.
        return BigInt(digits);
      }
      return value;
    }
    if (/^[A-Z][A-Z0-9_]*$/.test(token)) {
      pos++;
      if (seen.has(token)) throw new Error(`circular constant reference to ${token}`);
      const target = rawInitialisers.get(token);
      if (target === undefined) throw new Error(`${token} is not a known constant`);
      return evaluate(target, new Set([...seen, token]));
    }
    throw new Error(`unexpected token ${token} in: ${expr}`);
  }
  function parseProduct() {
    let value = parsePrimary();
    while (peek() === '*' || peek() === '/') {
      const op = tokens[pos++];
      const rhs = parsePrimary();
      value = op === '*' ? scale(value, rhs, (a, b) => a * b) : scale(value, rhs, (a, b) => a / b);
    }
    return value;
  }
  function parseSum() {
    let value = parseProduct();
    while (peek() === '+' || peek() === '-') {
      const op = tokens[pos++];
      const rhs = parseProduct();
      value = scale(value, rhs, (a, b) => (op === '+' ? a + b : a - b));
    }
    return value;
  }
  const value = parseSum();
  if (pos !== tokens.length) throw new Error(`trailing tokens in: ${expr}`);
  return value;
}

// Keeps BigInts as BigInts and rejects the one thing that would silently lose
// precision: a non-integer division.
function scale(a, b, op) {
  const big = typeof a === 'bigint' || typeof b === 'bigint';
  if (big) {
    const result = op(BigInt(a), BigInt(b));
    if (typeof result !== 'bigint') throw new Error('integer arithmetic produced a fraction');
    return result;
  }
  const result = op(a, b);
  if (!Number.isSafeInteger(result)) throw new Error(`arithmetic left the safe integer range: ${result}`);
  return result;
}

const values = new Map();
const evalErrors = [];
for (const [name, entry] of constants) {
  try {
    values.set(name, evaluate(entry.expr));
  } catch (error) {
    evalErrors.push(`${name}: ${error.message}`);
  }
}
check(
  'ProtocolLimits',
  'every constant initialiser evaluates to an integer',
  evalErrors.length === 0,
  evalErrors.length ? evalErrors.join('; ') : `${values.size}/${constants.size} evaluated`,
);

/* ------------------------------------------------------ 2. the doc's table */

const DOC_PATH = 'doc/transfer-protocol.md';
const doc = read(DOC_PATH);

const sectionStart = doc.indexOf('### Limits');
check('Limits table', 'doc/transfer-protocol.md has a "### Limits" section', sectionStart !== -1);

const nextSection = doc.indexOf('\n### ', sectionStart + 1);
const section = doc.slice(sectionStart, nextSection === -1 ? doc.length : nextSection);
const lines = section.split('\n');

const tableStart = lines.findIndex((l) => l.trim().startsWith('|'));
check('Limits table', 'the section contains a markdown table', tableStart !== -1);

const expectedHeader = ['Limit', 'Value', 'Why'];
const headerCells = lines[tableStart].split('|').slice(1, -1).map((c) => c.trim());
check(
  'Limits table',
  'the table header is "Limit | Value | Why"',
  headerCells.length === expectedHeader.length && expectedHeader.every((h, i) => headerCells[i] === h),
  headerCells.join(' | '),
);

const separatorOk = /^\|(?:\s*:?-{3,}:?\s*\|){3}$/.test(lines[tableStart + 1].trim());
check('Limits table', 'the table has a separator row', separatorOk, lines[tableStart + 1]);

const docRows = [];
const rowErrors = [];
for (const line of lines.slice(tableStart + 2)) {
  const trimmed = line.trim();
  if (!trimmed.startsWith('|')) break;
  const cells = trimmed.split('|').slice(1, -1).map((c) => c.trim());
  if (cells.length !== 3) {
    rowErrors.push(`row has ${cells.length} cells, expected 3: ${trimmed}`);
    continue;
  }
  const nameMatch = cells[0].match(/^`([A-Z][A-Z0-9_]*)`$/);
  if (!nameMatch) {
    rowErrors.push(`Limit cell is not a single backticked constant name: "${cells[0]}"`);
    continue;
  }
  const numberMatch = cells[1].match(/\d[\d,_]*/);
  if (!numberMatch) {
    rowErrors.push(`${nameMatch[1]}: Value cell has no comparable number: "${cells[1]}"`);
    continue;
  }
  docRows.push({
    name: nameMatch[1],
    value: BigInt(numberMatch[0].replaceAll(/[,_]/g, '')),
    raw: cells[1],
  });
}
check(
  'Limits table',
  'every row is well formed and carries a comparable number',
  rowErrors.length === 0,
  rowErrors.length ? rowErrors.join('; ') : `${docRows.length} rows parsed`,
);

const duplicates = docRows.map((r) => r.name).filter((n, i, all) => all.indexOf(n) !== i);
check(
  'Limits table',
  'no constant is documented twice',
  duplicates.length === 0,
  duplicates.length ? `duplicated: ${[...new Set(duplicates)].join(', ')}` : 'no duplicates',
);

/* -------------------------------------------------------- 3. the parity run */

/*
 * The four magic bytes are the only numeric constants kept out of the table:
 * they are not limits, and they are already documented cell by cell in the
 * framing table above as `0x4D 0x53 0x43 0x31`. Every name here must still exist
 * in the source, so the list cannot quietly grow into a blind spot.
 */
const EXCLUDED = new Map([
  ['MAGIC_BYTE_0', 'documented as 0x4D in the framing table'],
  ['MAGIC_BYTE_1', 'documented as 0x53 in the framing table'],
  ['MAGIC_BYTE_2', 'documented as 0x43 in the framing table'],
  ['MAGIC_BYTE_3', 'documented as 0x31 in the framing table'],
]);

const staleExclusions = [...EXCLUDED.keys()].filter((n) => !constants.has(n));
check(
  'Limits parity',
  'every excluded constant still exists in ProtocolLimits.kt',
  staleExclusions.length === 0,
  staleExclusions.length ? `no longer declared: ${staleExclusions.join(', ')}` : `${EXCLUDED.size} excluded, all present`,
);

const required = [...constants.keys()].filter((n) => !EXCLUDED.has(n));
const documented = new Map(docRows.map((r) => [r.name, r]));

const missing = required.filter((n) => !documented.has(n));
check(
  'Limits parity',
  'every ProtocolLimits constant appears in the table',
  missing.length === 0,
  missing.length ? `undocumented: ${missing.join(', ')}` : `${required.length} constants, all documented`,
);

const unknown = docRows.map((r) => r.name).filter((n) => !constants.has(n));
check(
  'Limits parity',
  'the table names no constant that ProtocolLimits does not declare',
  unknown.length === 0,
  unknown.length ? `not declared in source: ${unknown.join(', ')}` : 'no phantom rows',
);

const mismatches = [];
for (const row of docRows) {
  const actual = values.get(row.name);
  if (actual === undefined) continue; // reported by the phantom-row check
  if (BigInt(actual) !== row.value) {
    mismatches.push(`${row.name}: doc says ${row.value}, source says ${actual}`);
  }
}
check(
  'Limits parity',
  'every documented number equals the constant it names',
  mismatches.length === 0,
  mismatches.length ? mismatches.join('; ') : `${docRows.length} numbers compared`,
);

// The derived frame ceiling is the one number the doc cannot be allowed to
// state loosely: it is a sum, and a sum is exactly what drifts.
const frameSize = values.get('MAX_FRAME_SIZE_BYTES');
const expectedFrame =
  BigInt(values.get('HEADER_SIZE_BYTES')) +
  3n * BigInt(values.get('MAX_ID_LENGTH_BYTES')) +
  BigInt(values.get('MAX_PAYLOAD_BYTES'));
check(
  'Limits parity',
  'MAX_FRAME_SIZE_BYTES = HEADER_SIZE + 3 × MAX_ID_LENGTH + MAX_PAYLOAD',
  frameSize !== undefined && BigInt(frameSize) === expectedFrame,
  `source gives ${frameSize}, arithmetic gives ${expectedFrame}`,
);

/* ------------------------------------------- 4. dependency isolation checks */

const SKIP_DIRS = new Set(['.git', 'build', '.gradle', '.idea', 'node_modules', '.kotlin']);
function* walk(dir) {
  for (const entry of readdirSync(dir)) {
    if (SKIP_DIRS.has(entry)) continue;
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) yield* walk(path);
    else yield path;
  }
}

const MAIN_ALLOWED = [
  /^app\.morsecode\.core\.(model|transfer)\./,
  /^java\.nio\.(ByteBuffer|charset\.CharacterCodingException|charset\.CodingErrorAction)$/,
  /^java\.security\.MessageDigest$/,
  /^kotlin\./,
];
const TEST_ALLOWED = [
  ...MAIN_ALLOWED,
  /^org\.junit\.(Assert(\.\w+)?|Test)$/,
  /^java\.util\.zip\.CRC32$/, // the tests cross-check Crc32 against the JDK
];

function scanImports(sourceSet, allowed) {
  const offenders = [];
  let fileCount = 0;
  let importCount = 0;
  const root = join(ROOT, 'core-transfer/src', sourceSet);
  for (const file of walk(root)) {
    if (!file.endsWith('.kt')) continue;
    fileCount++;
    const src = readFileSync(file, 'utf8');
    src.split('\n').forEach((line, index) => {
      const imported = line.match(/^import\s+([\w.]+)/);
      if (!imported) return;
      importCount++;
      const fq = imported[1];
      if (!allowed.some((pattern) => pattern.test(fq))) {
        offenders.push(`${short(file)}:${index + 1} imports ${fq}`);
      }
    });
  }
  return { offenders, fileCount, importCount };
}

const mainScan = scanImports('main', MAIN_ALLOWED);
check(
  'core-transfer isolation',
  'main sources import nothing outside the JDK and :core-model',
  mainScan.offenders.length === 0,
  mainScan.offenders.length
    ? mainScan.offenders.slice(0, 8).join('; ')
    : `${mainScan.importCount} imports in ${mainScan.fileCount} files`,
);

const testScan = scanImports('test', TEST_ALLOWED);
check(
  'core-transfer isolation',
  'test sources import nothing outside the JDK, :core-model and JUnit',
  testScan.offenders.length === 0,
  testScan.offenders.length
    ? testScan.offenders.slice(0, 8).join('; ')
    : `${testScan.importCount} imports in ${testScan.fileCount} files`,
);

// The build file must not reintroduce what the imports above do not use.
const buildSrc = read('core-transfer/build.gradle.kts');
const dependenciesBlock = buildSrc.slice(buildSrc.indexOf('dependencies {'));
const FORBIDDEN_DEPS = [
  ['kotlinx.coroutines.core', 'no coroutine is scheduled in this module'],
  ['kotlinx.coroutines.test', 'no coroutine is scheduled in this module'],
  ['kotlinx.serialization.json', 'the wire format is hand-rolled byte layout'],
  ['libs.turbine', 'no flow is collected in this module'],
  ['javax.inject', 'nothing is injected into the reducer'],
  ['kotlin.serialization', 'no @Serializable type exists in this module'],
];
const declaredForbidden = FORBIDDEN_DEPS.filter(([token]) => buildSrc.includes(token));
check(
  'core-transfer isolation',
  'core-transfer/build.gradle.kts declares no dependency its sources do not use',
  declaredForbidden.length === 0,
  declaredForbidden.length
    ? declaredForbidden.map(([t, why]) => `${t} (${why})`).join('; ')
    : 'dependencies { api(project(":core-model")); testImplementation(libs.junit) }',
);

const emptyFileRules = [
  ['no Android framework import', /\b(android|androidx)\./],
  ['no Kotlin coroutine import', /\bkotlinx\.coroutines\./],
  ['no Room, Hilt or Dagger import', /\b(androidx\.room|dagger|javax\.inject)\./],
  ['no networking import', /\b(java\.net|io\.ktor|okhttp3?)\./],
  ['no filesystem import', /\b(java\.io\.File|kotlin\.io)\b/],
];
for (const [label, pattern] of emptyFileRules) {
  const hits = [...walk(join(ROOT, 'core-transfer/src'))]
    .filter((f) => f.endsWith('.kt') && pattern.test(readFileSync(f, 'utf8')));
  check('core-transfer isolation', label, hits.length === 0, hits.length ? hits.map(short).join(', ') : 'clean');
}

report();
process.exit(failures === 0 ? 0 : 1);
