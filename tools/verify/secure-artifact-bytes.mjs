#!/usr/bin/env node
/*
 * Verify the bytes of the security-critical Part B artifacts that Gradle actually resolved.
 *
 * The dependency-governance verifier reads the resolved *coordinate* graph as text. That proves
 * the right group:name:version was selected. It says nothing about the bytes behind those
 * coordinates: a corrupted download, a poisoned module cache, or a mirror serving different
 * content for the same coordinate all pass a coordinate check.
 *
 * This script closes that gap for the artifacts that perform cryptography. It locates each entry
 * in gradle/secure-artifact-checksums.txt inside the Gradle module cache, recomputes its SHA-256,
 * and compares. A mismatch or a missing artifact fails.
 *
 * SCOPE AND LIMITS -- both matter:
 *   - Only the reviewed crypto artifacts are covered, not the whole graph. This is not a lockfile.
 *   - The expected digests were taken from Maven Central, which is also where Gradle downloads
 *     from. A match therefore proves cache integrity and pins the bytes so any substitution shows
 *     up as a reviewable diff; it does not prove the vendor signed them. That needs signature
 *     verification, which this repository does not perform.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const MANIFEST = join(ROOT, 'gradle', 'secure-artifact-checksums.txt');

const ALLOWED_ARGS = new Set(['--quiet', '--cache', '--help']);
const args = process.argv.slice(2);
const unknown = args.filter((a, i) => !ALLOWED_ARGS.has(a) && args[i - 1] !== '--cache');
if (unknown.length) {
  console.error(`unknown argument(s): ${unknown.join(', ')}`);
  process.exit(2);
}

/** Honours an explicit --cache, then GRADLE_USER_HOME, then the default ~/.gradle. */
function cacheRoot() {
  const index = args.indexOf('--cache');
  if (index !== -1 && args[index + 1]) return args[index + 1];
  const userHome = process.env.GRADLE_USER_HOME;
  const base = userHome && userHome.length > 0 ? userHome : join(homedir(), '.gradle');
  return join(base, 'caches', 'modules-2', 'files-2.1');
}

const checks = [];
let failures = 0;
function check(name, ok, detail) {
  checks.push(`${ok ? '  ok  ' : '  FAIL'} ${name}${detail && !ok ? ` -- ${detail}` : ''}`);
  if (!ok) failures += 1;
}

function sha256(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function parseManifest() {
  if (!existsSync(MANIFEST)) return null;
  return readFileSync(MANIFEST, 'utf8')
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '' && !line.startsWith('#'))
    .map((line) => {
      const [coordinate, extension, digest] = line.split(/\s+/);
      return { coordinate, extension, digest, line };
    });
}

/**
 * Gradle stores each artifact under <group-as-path>/<name>/<version>/<hash-dir>/<filename>.
 *
 * The group is stored as a directory tree, not a dotted string: org.conscrypt becomes
 * org/conscrypt. Joining the dotted group directly looks plausible and silently finds nothing,
 * which reports every artifact as unresolved. Both forms are tried so a future layout change
 * fails loudly rather than quietly.
 */
function locate(root, group, name, version, extension) {
  const target = `${name}-${version}.${extension}`;
  const bases = [join(root, group.split('.').join('/'), name, version), join(root, group, name, version)];
  const found = [];
  for (const base of bases) {
    if (!existsSync(base)) continue;
    const walk = (dir) => {
      for (const entry of readdirSync(dir)) {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) walk(full);
        else if (entry === target) found.push(full);
      }
    };
    walk(base);
    if (found.length > 0) return found;
  }
  return found.length === 0 ? null : found;
}

const entries = parseManifest();
check('checksum manifest exists', entries !== null, MANIFEST);

if (entries) {
  check('manifest is not empty', entries.length > 0);
  for (const entry of entries) {
    const parts = (entry.coordinate ?? '').split(':');
    const wellFormed =
      parts.length === 3 &&
      ['jar', 'aar', 'pom'].includes(entry.extension) &&
      /^[0-9a-f]{64}$/.test(entry.digest ?? '');
    check(`manifest entry is well formed: ${entry.coordinate} ${entry.extension}`, wellFormed, entry.line);
  }

  const root = cacheRoot();
  const cachePresent = existsSync(root);
  check(`module cache present at ${relative(ROOT, root) || root}`, cachePresent);

  for (const entry of entries) {
    const [group, name, version] = (entry.coordinate ?? '').split(':');
    const label = `${entry.coordinate} ${entry.extension}`;
    if (!cachePresent) continue;
    const paths = locate(root, group, name, version, entry.extension);
    if (paths === null) {
      check(
        `${label} was resolved into the cache`,
        false,
        `not found under ${join(root, group.split('.').join('/'), name, version)}; ` +
        `cache root is ${root}`,
      );
      continue;
    }
    const digests = [...new Set(paths.map(sha256))];
    check(
      `${label} bytes match the reviewed digest`,
      digests.length === 1 && digests[0] === entry.digest,
      `found ${digests.join(', ')}, expected ${entry.digest} (${paths.length} copy/copies)`,
    );
  }
}

if (!args.includes('--quiet')) {
  console.log('\nSecure artifact byte verification');
  console.log(checks.join('\n'));
}
console.log(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed`);
if (failures) {
  // Job logs are not always retrievable after a run finishes, so surface the reason where the
  // check-run annotations API can still read it.
  if (process.env.GITHUB_ACTIONS === 'true') {
    const reasons = checks.filter((c) => c.includes('FAIL')).map((c) => c.replace(/^\s*FAIL\s*/, ''));
    console.log(`::error title=secure artifact bytes::${reasons.join(' | ')}`.slice(0, 900));
  }
  process.exit(1);
}
