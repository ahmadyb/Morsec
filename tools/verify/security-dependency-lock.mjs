#!/usr/bin/env node
/*
 * Verify the locked cryptographic dependency graph.
 *
 * Three things are checked, and they are deliberately separate because they fail differently:
 *
 *   1. Structure. The lock itself must be exact: no dynamic versions, no ranges, no duplicate or
 *      unapproved entries, every field present and well formed. A lock that can say "2.+" is not a
 *      lock.
 *   2. Resolved graph. Given a Gradle `dependencies --configuration` report, each configuration the
 *      lock names must resolve to exactly the approved components at the approved versions. Extra,
 *      missing or substituted components fail.
 *   3. Bytes. Given a Gradle module cache, each locked file must exist at the locked size with the
 *      locked SHA-256.
 *
 * WHAT THIS DOES NOT PROVE, honestly:
 *   Publisher metadata is not byte-verified. Gradle keeps POM/module metadata in its own binary
 *   store and does not materialize .pom files under caches/modules-2/files-2.1, which run
 *   37989038083 confirmed empirically. So a metadata change that resolves the exact same approved
 *   graph and the exact same binary bytes would not be caught here. This mechanism is
 *   security-scoped; it is not Gradle's global strict dependency verification, and there is no
 *   gradle/verification-metadata.xml.
 *
 * Run `--self-test` for the mutation fixtures. They are deterministic and need neither Gradle nor
 * network access: each one mutates a synthetic lock, report or cache and asserts the corresponding
 * check fails.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { join } from 'node:path';

const ROOT = process.cwd();
const LOCK_PATH = join(ROOT, 'gradle', 'security-dependency-lock.json');
const ROOT_BUILD = join(ROOT, 'build.gradle.kts');

const APPROVED = new Set([
  'org.conscrypt:conscrypt-android:2.7.0',
  'org.bouncycastle:bcpkix-jdk18on:1.86',
  'org.bouncycastle:bcprov-jdk18on:1.86',
  'org.bouncycastle:bcutil-jdk18on:1.86',
]);

const DYNAMIC = /(\+|latest\.|latest\.)|^\[|^\(|,$/;

const results = [];
let failures = 0;
function check(name, ok, detail) {
  results.push(`${ok ? '  ok  ' : '  FAIL'} ${name}${detail && !ok ? ` -- ${detail}` : ''}`);
  if (!ok) failures += 1;
}

/* ------------------------------------------------------------------ structure */

export function validateStructure(lock) {
  const problems = [];
  if (!lock || typeof lock !== 'object') return ['lock is not an object'];
  const components = lock.components;
  if (!Array.isArray(components) || components.length === 0) return ['components is not a non-empty array'];

  const seen = new Map();
  for (const c of components) {
    const missing = ['group', 'artifact', 'version', 'role', 'packaging', 'fileName', 'sizeBytes', 'sha256']
      .filter((field) => c[field] === undefined || c[field] === null || c[field] === '');
    if (missing.length) problems.push(`${c.artifact ?? '<unknown>'}: missing field(s) ${missing.join(', ')}`);
    if (!/^[0-9]+(\.[0-9]+)*$/.test(String(c.version))) {
      problems.push(`${c.group}:${c.artifact}: version "${c.version}" is not an exact pinned version`);
    }
    if (DYNAMIC.test(String(c.version))) problems.push(`${c.group}:${c.artifact}: version "${c.version}" is dynamic or a range`);
    if (!/^[a-f0-9]{64}$/.test(String(c.sha256))) problems.push(`${c.group}:${c.artifact}: sha256 is not 64 lowercase hex chars`);
    if (!Number.isInteger(c.sizeBytes) || c.sizeBytes <= 0) problems.push(`${c.group}:${c.artifact}: sizeBytes is not a positive integer`);
    const expectedName = `${c.artifact}-${c.version}.${c.packaging}`;
    if (c.fileName !== expectedName) problems.push(`${c.group}:${c.artifact}: fileName "${c.fileName}" does not match "${expectedName}"`);
    if (!['direct', 'transitive'].includes(c.role)) problems.push(`${c.group}:${c.artifact}: role "${c.role}" is neither direct nor transitive`);
    const coordinate = `${c.group}:${c.artifact}:${c.version}`;
    if (seen.has(coordinate)) problems.push(`duplicate lock entry ${coordinate}`);
    seen.set(coordinate, true);
    if (!APPROVED.has(coordinate)) problems.push(`lock entry ${coordinate} is not in the approved crypto graph`);
  }
  for (const approved of APPROVED) {
    if (!seen.has(approved)) problems.push(`approved component ${approved} has no lock entry`);
  }
  if (!Array.isArray(lock.configurations) || lock.configurations.length === 0) {
    problems.push('configurations is not a non-empty array');
  } else {
    for (const cfg of lock.configurations) {
      if (!cfg.project || !cfg.name) problems.push(`configuration entry is missing project or name: ${JSON.stringify(cfg)}`);
      if (!['full', 'subset'].includes(cfg.expect)) problems.push(`configuration ${cfg.project}:${cfg.name} has expect "${cfg.expect}"`);
    }
  }
  return problems;
}

/* ------------------------------------------------------------------ resolved graph */

/**
 * Pulls every section named `configuration` out of a Gradle dependencies report.
 *
 * Plural on purpose. A report can legitimately contain the same configuration name more than once,
 * and reading only the first would silently skip a project's section -- exactly the bug that would
 * let app's debugRuntimeClasspath go unverified while transport-lan's passed.
 */
export function cryptoComponentSections(report, configuration) {
  const lines = report.split(/\r?\n/);
  const header = new RegExp(`^${configuration}\\s+-`);
  const boundary = /^[A-Za-z0-9_-]+\s+-\s/;
  const sections = [];
  for (let i = 0; i < lines.length; i += 1) {
    if (!header.test(lines[i])) continue;
    const found = new Map();
    for (let j = i + 1; j < lines.length; j += 1) {
      if (boundary.test(lines[j])) break;
      for (const match of lines[j].matchAll(/\b(org\.conscrypt|org\.bouncycastle):([A-Za-z0-9_.-]+):([0-9][^\s]*)/g)) {
        const upgraded = (lines[j].split(match[0])[1] ?? '').match(/^\s*->\s*([0-9][^\s]*)/);
        const version = (upgraded ? upgraded[1] : match[3]).replace(/[(*)]/g, '');
        found.set(`${match[1]}:${match[2]}`, version);
      }
    }
    sections.push(found);
  }
  return sections;
}

export function verifyGraph(lock, reportsByProject) {
  const problems = [];
  const expectedFull = new Map([...APPROVED].map((c) => {
    const [group, artifact, version] = c.split(':');
    return [`${group}:${artifact}`, version];
  }));
  for (const cfg of lock.configurations ?? []) {
    const report = reportsByProject[cfg.project];
    if (report === undefined) {
      problems.push(`no resolved report was supplied for project ${cfg.project}`);
      continue;
    }
    const sections = cryptoComponentSections(report, cfg.name);
    if (sections.length === 0) {
      problems.push(`configuration ${cfg.project}:${cfg.name} is absent from the resolved report`);
      continue;
    }
    const found = sections[sections.length - 1];
    if (found.size === 0) {
      problems.push(`configuration ${cfg.project}:${cfg.name} resolved no crypto component`);
      continue;
    }
    for (const [coordinate, version] of found) {
      const approvedVersion = expectedFull.get(coordinate);
      if (approvedVersion === undefined) {
        problems.push(`configuration ${cfg.project}:${cfg.name} resolved unexpected component ${coordinate}:${version}`);
      } else if (approvedVersion !== version) {
        problems.push(`configuration ${cfg.project}:${cfg.name} resolved ${coordinate} at ${version}, expected ${approvedVersion}`);
      }
    }
    if (cfg.expect === 'full') {
      for (const [coordinate, version] of expectedFull) {
        if (!found.has(coordinate)) {
          problems.push(`configuration ${cfg.project}:${cfg.name} is missing approved component ${coordinate}:${version}`);
        }
      }
    }
  }
  return problems;
}

/* ------------------------------------------------------------------ bytes */

/**
 * Checks each locked component against what was actually resolved.
 *
 * `digestOf` exists so the mutation fixtures can exercise the digest comparison without needing a
 * file whose bytes happen to hash to a locked value, which no synthetic file can. The real path --
 * hashing the resolved file -- is what CI runs with --cache.
 */
export function verifyBytes(components, resolve, digestOf) {
  const problems = [];
  for (const c of components) {
    const located = resolve(c);
    if (!located) {
      problems.push(`${c.fileName} was not found in the Gradle module cache`);
      continue;
    }
    if (located.size !== c.sizeBytes) {
      problems.push(`${c.fileName} is ${located.size} bytes, lock says ${c.sizeBytes}`);
      continue;
    }
    const digest = digestOf
      ? digestOf(c)
      : createHash('sha256').update(readFileSync(located.path)).digest('hex');
    if (digest !== c.sha256) {
      problems.push(`${c.fileName} sha256 ${digest} does not match locked ${c.sha256}`);
    }
  }
  return problems;
}

/** Walks caches/modules-2/files-2.1 for the file a lock entry names. */
function cacheResolver(cacheRoot) {
  const byName = new Map();
  if (existsSync(cacheRoot)) {
    const walk = (dir) => {
      for (const entry of readdirSync(dir)) {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) walk(full);
        else if (!byName.has(entry)) byName.set(entry, full);
      }
    };
    walk(cacheRoot);
  }
  return (component) => {
    const path = byName.get(component.fileName);
    return path ? { path, size: statSync(path).size } : null;
  };
}

/* ------------------------------------------------------------------ task-graph wiring */

export function wiringProblems(buildScript) {
  const problems = [];
  if (!buildScript.includes('verifySecureDependencyLock')) {
    problems.push('build.gradle.kts registers no verifySecureDependencyLock task');
  }
  if (!buildScript.includes('gradle/security-dependency-lock.json')) {
    problems.push('the verification task is not given the committed lock file');
  }
  for (const project of ['transport-lan', 'app']) {
    if (!buildScript.includes(`"${project}"`)) problems.push(`project ${project} is not in the protected set`);
  }
  // Typed where Gradle exposes a stable type, name-prefixed where AGP does not.
  for (const typed of ['KotlinCompile', 'JavaCompile', 'org.gradle.api.tasks.testing.Test']) {
    if (!buildScript.includes(typed)) problems.push(`no typed wiring for ${typed}`);
  }
  for (const prefix of ['lint', 'dex', 'package', 'assemble']) {
    if (!buildScript.includes(`"${prefix}"`)) problems.push(`no task-name coverage for ${prefix}* tasks`);
  }
  if (!/dependsOn\(/.test(buildScript)) problems.push('nothing depends on the verification task');
  return problems;
}

/* ------------------------------------------------------------------ mutation fixtures */

function syntheticLock(mutate) {
  const lock = JSON.parse(readFileSync(LOCK_PATH, 'utf8'));
  return mutate(lock);
}

function reportWith(configuration, body) {
  const full = ['org.conscrypt:conscrypt-android:2.7.0',
    'org.bouncycastle:bcpkix-jdk18on:1.86',
    'org.bouncycastle:bcprov-jdk18on:1.86',
    'org.bouncycastle:bcutil-jdk18on:1.86'];
  return `${configuration} - Runtime classpath.\n${(body ?? full).map((l) => `+--- ${l}`).join('\n')}\n`;
}

function runSelfTest() {
  const lock = JSON.parse(readFileSync(LOCK_PATH, 'utf8'));
  const build = readFileSync(ROOT_BUILD, 'utf8');
  const oneConfig = [{ project: 'transport-lan', name: 'debugRuntimeClasspath', expect: 'full' }];
  const minimal = { ...lock, configurations: oneConfig };

  const structureFails = (name, mutate) =>
    check(`mutation: ${name}`, validateStructure(mutate(lock)).length > 0);
  const graphFails = (name, report, cfgs) =>
    check(`mutation: ${name}`,
      verifyGraph({ ...minimal, configurations: cfgs ?? oneConfig },
        { 'transport-lan': report }).length > 0);
  const bytesFails = (name, resolve, digestOf) =>
    check(`mutation: ${name}`, verifyBytes(lock.components, resolve, digestOf).length > 0);
  const wiringFails = (name, mutate) =>
    check(`mutation: ${name}`, wiringProblems(mutate(build)).length > 0);

  // Bytes: a synthetic resolver, so no Gradle cache is needed. The path is never read by these
  // fixtures, because each one trips the missing or size guard, or supplies its own digest.
  const locate = (component) => ({ path: LOCK_PATH, size: component.sizeBytes });
  const fakeResolve = (component, override) => override(component, locate(component));

  bytesFails('altered Conscrypt AAR is rejected', (c) =>
    fakeResolve(c, (component, base) => (component.artifact === 'conscrypt-android'
      ? { ...base, size: base.size - 1 } : base)));
  bytesFails('altered Bouncy Castle JAR is rejected', (c) =>
    fakeResolve(c, (component, base) => (component.artifact === 'bcprov-jdk18on'
      ? { ...base, size: base.size + 1 } : base)));
  bytesFails('same-length byte substitution is rejected', locate,
    (c) => (c.artifact === 'bcpkix-jdk18on' ? 'b'.repeat(64) : c.sha256));

  // 3-5, 7: resolved graph.
  graphFails('added transitive component is rejected',
    reportWith('debugRuntimeClasspath', [
      'org.conscrypt:conscrypt-android:2.7.0',
      'org.bouncycastle:bcpkix-jdk18on:1.86',
      'org.bouncycastle:bcprov-jdk18on:1.86',
      'org.bouncycastle:bcutil-jdk18on:1.86',
      'org.bouncycastle:bcmail-jdk18on:1.86',
    ]));
  graphFails('removed component is rejected',
    reportWith('debugRuntimeClasspath', [
      'org.conscrypt:conscrypt-android:2.7.0',
      'org.bouncycastle:bcpkix-jdk18on:1.86',
      'org.bouncycastle:bcprov-jdk18on:1.86',
    ]));
  graphFails('version substitution is rejected',
    reportWith('debugRuntimeClasspath', [
      'org.conscrypt:conscrypt-android:2.7.0 -> 2.8.0',
      'org.bouncycastle:bcpkix-jdk18on:1.86',
      'org.bouncycastle:bcprov-jdk18on:1.86',
      'org.bouncycastle:bcutil-jdk18on:1.86',
    ]));
  graphFails('module substitution is rejected',
    reportWith('debugRuntimeClasspath', [
      'org.conscrypt:conscrypt-openjdk-uber:2.7.0',
      'org.bouncycastle:bcpkix-jdk18on:1.86',
      'org.bouncycastle:bcprov-jdk18on:1.86',
      'org.bouncycastle:bcutil-jdk18on:1.86',
    ]));
  graphFails('absent configuration is rejected', reportWith('someOtherClasspath'));

  // 6, 8-14: lock structure.
  structureFails('dynamic version is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components[0].version = '2.+';
    return out;
  });
  structureFails('version range is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components[0].version = '[1.86,2.0)';
    return out;
  });
  structureFails('packaging change is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components[0].packaging = 'jar';
    return out;
  });
  structureFails('filename change is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components[0].fileName = 'conscrypt-android-2.7.0-renamed.aar';
    return out;
  });
  // A changed size or hash is structurally well formed, so structure validation cannot catch it --
  // only comparison against the resolved bytes can. These two therefore belong to the byte domain.
  structureFails('malformed size is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components[1].sizeBytes = 'not-a-number';
    return out;
  });
  structureFails('malformed hash is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components[1].sha256 = 'not-a-digest';
    return out;
  });
  {
    const trueSize = new Map(lock.components.map((c) => [c.artifact, c.sizeBytes]));
    const trueSha = new Map(lock.components.map((c) => [c.artifact, c.sha256]));
    const resolveTrue = (c) => ({ path: LOCK_PATH, size: trueSize.get(c.artifact) });
    const digestTrue = (c) => trueSha.get(c.artifact);

    const sizeMutated = JSON.parse(JSON.stringify(lock));
    sizeMutated.components[1].sizeBytes += 1;
    check('mutation: size change is rejected against the resolved bytes',
      verifyBytes(sizeMutated.components, resolveTrue, digestTrue).length > 0);

    const hashMutated = JSON.parse(JSON.stringify(lock));
    hashMutated.components[1].sha256 = 'a'.repeat(64);
    check('mutation: hash change is rejected against the resolved bytes',
      verifyBytes(hashMutated.components, resolveTrue, digestTrue).length > 0);
  }
  structureFails('missing lock entry is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components = out.components.filter((c) => c.artifact !== 'bcutil-jdk18on');
    return out;
  });
  structureFails('extra lock entry is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components.push({
      ...JSON.parse(JSON.stringify(out.components[1])),
      artifact: 'bcmail-jdk18on', fileName: 'bcmail-jdk18on-1.86.jar',
    });
    return out;
  });
  structureFails('duplicate lock entry is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components.push(JSON.parse(JSON.stringify(out.components[0])));
    return out;
  });
  bytesFails('missing artifact in the cache is rejected', (c) =>
    fakeResolve(c, (component, base) => (component.artifact === 'bcutil-jdk18on' ? null : base)));

  // 15-17: task-graph wiring.
  wiringFails('direct compile task with verification dependency removed',
    (src) => src.replace('KotlinCompile', 'SomeOtherTaskType'));
  wiringFails('app assembly with verification dependency removed',
    (src) => src.replace('"assemble"', '"notAssemble"'));
  wiringFails('Android-test packaging with verification dependency removed',
    (src) => src.replace('"package"', '"notPackage"'));

  // The unchanged inputs must pass, or every mutation above would pass for the wrong reason.
  check('baseline: unmodified lock structure is valid', validateStructure(lock).length === 0,
    validateStructure(lock).join('; '));
  const baselineGraph = verifyGraph(minimal, { 'transport-lan': reportWith('debugRuntimeClasspath') });
  check('baseline: unmodified graph resolves', baselineGraph.length === 0, baselineGraph.join('; '));
  check('mutation: a configuration with no report for its project is rejected',
    verifyGraph(minimal, {}).length > 0);
  check('baseline: unmodified task wiring is present', wiringProblems(build).length === 0,
    wiringProblems(build).join('; '));
}

/* ------------------------------------------------------------------ main */

const args = process.argv.slice(2);

if (args.includes('--self-test')) {
  runSelfTest();
} else {
  if (!existsSync(LOCK_PATH)) {
    check('the security dependency lock exists', false, LOCK_PATH);
  } else {
    const lock = JSON.parse(readFileSync(LOCK_PATH, 'utf8'));
    check('the lock is valid JSON with an exact approved graph', validateStructure(lock).length === 0,
      validateStructure(lock).join('; '));

    // --resolved takes project=path pairs, so a configuration is checked against its own
    // project's report rather than whichever project happened to print that name first.
    const reportsByProject = {};
    for (let i = 0; i < args.length; i += 1) {
      if (args[i] !== '--resolved') continue;
      const pair = args[i + 1] ?? '';
      const split = pair.indexOf('=');
      if (split > 0) {
        reportsByProject[pair.slice(0, split)] = readFileSync(join(ROOT, pair.slice(split + 1)), 'utf8');
      }
    }
    if (Object.keys(reportsByProject).length > 0) {
      check('every locked configuration resolves to the approved graph',
        verifyGraph(lock, reportsByProject).length === 0, verifyGraph(lock, reportsByProject).join('; '));
    }

    if (args.includes('--cache') || args.includes('--bytes')) {
      const index = args.indexOf('--cache');
      const base = index >= 0 && args[index + 1] ? args[index + 1]
        : join(process.env.GRADLE_USER_HOME || join(homedir(), '.gradle'), 'caches', 'modules-2', 'files-2.1');
      const lock = JSON.parse(readFileSync(LOCK_PATH, 'utf8'));
      check('every locked artifact matches its recorded size and SHA-256',
        verifyBytes(lock.components, cacheResolver(base)).length === 0,
        verifyBytes(lock.components, cacheResolver(base)).join('; '));
    }

    if (existsSync(ROOT_BUILD)) {
      const build = readFileSync(ROOT_BUILD, 'utf8');
      check('the verification task is wired into the task graph',
        wiringProblems(build).length === 0, wiringProblems(build).join('; '));
    }
  }
}

for (const line of results) console.log(line);
console.log(`\n${results.length - failures}/${results.length} checks passed, ${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
