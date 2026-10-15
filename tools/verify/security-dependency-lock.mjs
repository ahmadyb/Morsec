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

import { existsSync, readFileSync, readdirSync, statSync, writeFileSync, unlinkSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { homedir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { join } from 'node:path';

const ROOT = process.cwd();
let LOCK_PATH = join(ROOT, 'gradle', 'security-dependency-lock.json');
const ROOT_BUILD = join(ROOT, 'build.gradle.kts');

const APPROVED = new Set([
  'org.conscrypt:conscrypt-android:2.7.0',
  'org.bouncycastle:bcpkix-jdk18on:1.86',
  'org.bouncycastle:bcprov-jdk18on:1.86',
  'org.bouncycastle:bcutil-jdk18on:1.86',
  'org.conscrypt:conscrypt-openjdk-uber:2.5.2',
]);

const DYNAMIC = /(\+|latest\.|latest\.)|^\[|^\(|,$/;

const results = [];
let failures = 0;
// The CLI body runs at module top level, so an uncaught throw would otherwise print a stack trace
// with no "FAIL" line and no summary, and CI -- which greps for both -- would report nothing at all.
// Run 38716040277 failed that way. Turn any crash into a reported failure instead.
process.on('uncaughtException', (error) => {
  const message = error && error.message ? error.message : String(error);
  console.log(`  FAIL the verifier crashed instead of reporting: ${message}`);
  console.log(`\n0/${results.length + 1} checks passed, 1 failed`);
  process.exit(1);
});

function check(name, ok, detail) {
  results.push(`${ok ? '  ok  ' : '  FAIL'} ${name}${detail && !ok ? ` -- ${detail}` : ''}`);
  if (!ok) failures += 1;
}

/* ------------------------------------------------------------------ structure */

export function validateStructure(lock) {
  const problems = [];
  if (!lock || typeof lock !== 'object') return ['lock is not an object'];
  if (lock.schemaVersion !== 1) return [`unsupported schemaVersion ${JSON.stringify(lock.schemaVersion)}, expected 1`];
  const components = lock.components;
  if (!Array.isArray(components) || components.length === 0) return ['components is not a non-empty array'];

  const seen = new Map();
  for (const c of components) {
    // Every entry describes executable code, so every entry carries a digest. There is no optional
    // or documented-digest-absent escape hatch: an artifact without a SHA-256 cannot detect
    // substitution, whatever its role.
    const missing = ['group', 'artifact', 'version', 'role', 'packaging', 'fileName', 'sizeBytes', 'sha256']
      .filter((field) => c[field] === undefined || c[field] === null || c[field] === '');
    if (missing.length) problems.push(`${c.artifact ?? '<unknown>'}: missing field(s) ${missing.join(', ')}`);
    if (!/^[0-9]+(\.[0-9]+)*$/.test(String(c.version))) {
      problems.push(`${c.group}:${c.artifact}: version "${c.version}" is not an exact pinned version`);
    }
    if (DYNAMIC.test(String(c.version))) problems.push(`${c.group}:${c.artifact}: version "${c.version}" is dynamic or a range`);
    if (c.sha256 !== undefined && c.sha256 !== null && !/^[a-f0-9]{64}$/.test(String(c.sha256))) {
      problems.push(`${c.group}:${c.artifact}: sha256 is not 64 lowercase hex chars`);
    }
    if (c.digestAvailability !== undefined) {
      problems.push(`${c.group}:${c.artifact}: digestAvailability is no longer a valid escape hatch; record provenance instead`);
    }
    if (!Number.isInteger(c.sizeBytes) || c.sizeBytes <= 0) problems.push(`${c.group}:${c.artifact}: sizeBytes is not a positive integer`);
    const expectedName = `${c.artifact}-${c.version}.${c.packaging}`;
    if (c.fileName !== expectedName) problems.push(`${c.group}:${c.artifact}: fileName "${c.fileName}" does not match "${expectedName}"`);
    if (!['direct', 'transitive', 'test-only'].includes(c.role)) {
      problems.push(`${c.group}:${c.artifact}: role "${c.role}" is not direct, transitive or test-only`);
    }
    if (c.role === 'test-only' && (!Array.isArray(c.allowedIn) || c.allowedIn.length === 0)) {
      problems.push(`${c.group}:${c.artifact}: a test-only component must declare allowedIn`);
    }
    if (c.role === 'test-only' && c.reachesProductionArtifact !== false) {
      problems.push(`${c.group}:${c.artifact}: a test-only component must declare reachesProductionArtifact false`);
    }
    // Every entry is executable code, so every entry carries a provenance record. This is where
    // the digest came from and what it was cross-checked against; it is never a substitute for the
    // digest itself.
    const prov = c.provenance;
    if (!prov || typeof prov !== 'object') {
      problems.push(`${c.group}:${c.artifact}: no provenance record`);
    } else {
      if (!prov.algorithm) problems.push(`${c.group}:${c.artifact}: provenance names no algorithm`);
      if (!Array.isArray(prov.crossCheckedAgainst)) {
        problems.push(`${c.group}:${c.artifact}: provenance lists nothing it was cross-checked against`);
      }
      if (prov.signatureVerified === true && !prov.signingKeyFingerprint) {
        problems.push(`${c.group}:${c.artifact}: claims a verified signature with no key fingerprint`);
      }
    }
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
      if (!['full', 'subset', 'none'].includes(cfg.expect)) {
        problems.push(`configuration ${cfg.project}:${cfg.name} has expect "${cfg.expect}"`);
      }
      if (cfg.expect === 'none' && !cfg.note) {
        problems.push(`configuration ${cfg.project}:${cfg.name} declares expect "none" with no note`);
      }
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
  // Only the production graph is required everywhere. A test-only component is allowed where its
  // allowedIn says and nowhere else, and is never part of the "full" requirement.
  const optionalCoordinates = new Map(
    (lock.components ?? []).filter((c) => c.optional === true)
      .map((c) => [`${c.group}:${c.artifact}`, c.allowedIn ?? []]),
  );
  const expectedFull = new Map((lock.components ?? [])
    .filter((c) => c.optional !== true)
    .map((c) => [`${c.group}:${c.artifact}`, c.version]));
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
    if (cfg.expect === 'none') {
      // Documented as carrying no crypto artifact. Emptiness is the assertion, not an absence of
      // checking: a crypto component appearing here is a failure, not a relaxation.
      if (found.size > 0) {
        problems.push(`configuration ${cfg.project}:${cfg.name} is documented as carrying no crypto ` +
          `component but resolved ${[...found.keys()].join(', ')}`);
      }
      continue;
    }
    if (found.size === 0) {
      problems.push(`configuration ${cfg.project}:${cfg.name} resolved no crypto component`);
      continue;
    }
    for (const [coordinate, version] of found) {
      const allowedIn = optionalCoordinates.get(coordinate);
      const approvedVersion = expectedFull.get(coordinate) ??
        (lock.components ?? []).find((c) => `${c.group}:${c.artifact}` === coordinate)?.version;
      if (approvedVersion === undefined) {
        problems.push(`configuration ${cfg.project}:${cfg.name} resolved unexpected component ${coordinate}:${version}`);
      } else if (allowedIn && !allowedIn.some((needle) => cfg.name.includes(needle))) {
        problems.push(`configuration ${cfg.project}:${cfg.name} resolved ${coordinate}, which the lock restricts to ${allowedIn.join(', ')}`);
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
      if (c.optional !== true) problems.push(`${c.fileName} was not found in the Gradle module cache`);
      continue;
    }
    if (located.size !== c.sizeBytes) {
      problems.push(`${c.fileName} is ${located.size} bytes, lock says ${c.sizeBytes}`);
      continue;
    }
    if (!c.sha256) {
      problems.push(`${c.fileName} has no locked sha256, so substitution cannot be detected`);
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

/**
 * Reads a `--dry-run` task plan and proves the verification task is planned before every task that
 * consumes the locked artifacts.
 *
 * The consumer pattern names Kotlin and Java compilation explicitly. A bare `compile` prefix also
 * matches AGP resource tasks such as `:app:compileDebugNavigationResources`, which is neither
 * KotlinCompile nor JavaCompile, consumes no dependency classpath, and is deliberately not gated by
 * the wiring in build.gradle.kts. Run 38710153534 failed on exactly that task. Matching the gated
 * set keeps this proof aligned with the wiring instead of stricter than it.
 */
export const CONSUMER_PATTERN = '(compile[A-Za-z]*(Kotlin|JavaWithJavac)|test[A-Za-z]*UnitTest|lint|dex|package|assemble|bundle|connected)';

export function planOrderProblems(plan, projects) {
  const problems = [];
  const lines = String(plan).split(/\r?\n/);
  for (const project of projects) {
    const verifyAt = lines.findIndex((l) => new RegExp(`^:${project}:verifySecureDependencyLock( |$)`).test(l));
    if (verifyAt < 0) {
      problems.push(`${project}:verifySecureDependencyLock is not in the task graph at all`);
      continue;
    }
    const consumerAt = lines.findIndex((l) => new RegExp(`^:${project}:${CONSUMER_PATTERN}`).test(l));
    if (consumerAt < 0) continue;
    if (verifyAt > consumerAt) {
      problems.push(`${project} runs ${lines[consumerAt].trim()} at line ${consumerAt + 1} ` +
        `before verification at line ${verifyAt + 1}`);
    }
  }
  return problems;
}

/* ------------------------------------------------------------------ workflow wiring */

/** The Gradle gate is the enforcement; the workflow must not be the only thing invoking it. */
export function workflowProblems(workflow) {
  const problems = [];
  const required = [
    ['security-dependency-lock.mjs', 'the lock verifier is not invoked'],
    ['--dry-run', 'no task-plan proof of ordering'],
    ['verifySecureDependencyLock', 'no reference to the verification task'],
    [':app:assembleDebug', 'app debug assembly is not proven'],
    [':app:assembleDebugAndroidTest', 'androidTest packaging is not proven'],
    [':transport-lan:compileDebugKotlin', 'direct Kotlin compilation is not proven'],
    [':transport-lan:testDebugUnitTest', 'unit test execution is not proven'],
    [':transport-lan:lintDebug', 'lint is not proven'],
  ];
  for (const [needle, message] of required) {
    if (!workflow.includes(needle)) problems.push(message);
  }
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

  // The test-only Robolectric transitive is graph-locked but not digest-locked, so its scope has
  // to be enforced structurally instead.
  structureFails('a test-only component without allowedIn is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    delete out.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').allowedIn;
    return out;
  });
  structureFails('a test-only component claiming to reach production is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').reachesProductionArtifact = true;
    return out;
  });
  structureFails('a null binary digest is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').sha256 = null;
    return out;
  });
  structureFails('a missing binary digest is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    delete out.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').sha256;
    return out;
  });
  structureFails('the retired digestAvailability escape hatch is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').digestAvailability = 'only md5 published';
    return out;
  });
  structureFails('a component with no provenance record is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    delete out.components.find((c) => c.artifact === 'bcprov-jdk18on').provenance;
    return out;
  });
  structureFails('a claimed verified signature with no key fingerprint is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.components[0].provenance.signatureVerified = true;
    return out;
  });
  graphFails('a test-only component leaking into a production configuration is rejected',
    reportWith('debugRuntimeClasspath', [
      'org.conscrypt:conscrypt-android:2.7.0',
      'org.bouncycastle:bcpkix-jdk18on:1.86',
      'org.bouncycastle:bcprov-jdk18on:1.86',
      'org.bouncycastle:bcutil-jdk18on:1.86',
      'org.conscrypt:conscrypt-openjdk-uber:2.5.2',
    ]));
  // digestOf returns each component's own recorded hash so no unrelated digest noise is produced,
  // leaving the size comparison as the only thing that can fail.
  bytesFails('the OpenJDK JAR is size-checked as well as digest-checked',
    (c) => fakeResolve(c, (component, base) =>
      (component.artifact === 'conscrypt-openjdk-uber' ? { ...base, size: base.size - 1 } : base)),
    (c) => c.sha256);

  structureFails('a configuration declaring no crypto with no note is rejected', (l) => {
    const out = JSON.parse(JSON.stringify(l));
    out.configurations[0].expect = 'none';
    delete out.configurations[0].note;
    return out;
  });
  graphFails('a configuration declared free of crypto that resolves one is rejected',
    reportWith('debugRuntimeClasspath'),
    [{ project: 'transport-lan', name: 'debugRuntimeClasspath', expect: 'none', note: 'x' }]);

  // --- digest fixtures ------------------------------------------------------------------------
  {
    const trueSize = new Map(lock.components.map((c) => [c.artifact, c.sizeBytes]));
    const trueSha = new Map(lock.components.map((c) => [c.artifact, c.sha256]));
    const resolveTrue = (c) => ({ path: LOCK_PATH, size: trueSize.get(c.artifact) });
    const digestTrue = (c) => trueSha.get(c.artifact);

    const wrongSha = JSON.parse(JSON.stringify(lock));
    wrongSha.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').sha256 = 'c'.repeat(64);
    check('mutation: a wrong SHA-256 is rejected against the resolved bytes',
      verifyBytes(wrongSha.components, resolveTrue, digestTrue).length > 0);

    const wrongSize = JSON.parse(JSON.stringify(lock));
    wrongSize.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').sizeBytes -= 1;
    check('mutation: a wrong size is rejected against the resolved bytes',
      verifyBytes(wrongSize.components, resolveTrue, digestTrue).length > 0);

    const wrongName = JSON.parse(JSON.stringify(lock));
    wrongName.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').fileName = 'renamed.jar';
    check('mutation: a wrong filename is rejected',
      validateStructure(wrongName).length > 0);

    const alteredUber = JSON.parse(JSON.stringify(lock));
    check('mutation: an altered Conscrypt OpenJDK JAR is rejected',
      verifyBytes(alteredUber.components,
        (c) => (c.artifact === 'conscrypt-openjdk-uber'
          ? { path: LOCK_PATH, size: c.sizeBytes + 1 }
          : { path: LOCK_PATH, size: c.sizeBytes }), digestTrue).length > 0);

    check('baseline: a correct verified SHA-256 is accepted',
      verifyBytes(lock.components, resolveTrue, digestTrue).length === 0,
      verifyBytes(lock.components, resolveTrue, digestTrue).join('; '));
  }

  // --- graph fixtures for the app configurations -------------------------------------------------
  const appReports = {
    app: reportWith('debugRuntimeClasspath', [
      'org.conscrypt:conscrypt-android:2.7.0',
      'org.bouncycastle:bcpkix-jdk18on:1.86',
      'org.bouncycastle:bcprov-jdk18on:1.86',
      'org.bouncycastle:bcutil-jdk18on:1.86',
    ]),
  };
  check('mutation: missing crypto in app:debugRuntimeClasspath is rejected',
    verifyGraph({ ...lock, configurations: [{ project: 'app', name: 'debugRuntimeClasspath', expect: 'full' }] },
      { app: reportWith('debugRuntimeClasspath', ['org.conscrypt:conscrypt-android:2.7.0']) }).length > 0);
  check('baseline: app:debugRuntimeClasspath resolves the approved graph',
    verifyGraph({ ...lock, configurations: [{ project: 'app', name: 'debugRuntimeClasspath', expect: 'full' }] },
      appReports).length === 0,
    verifyGraph({ ...lock, configurations: [{ project: 'app', name: 'debugRuntimeClasspath', expect: 'full' }] },
      appReports).join('; '));

  {
    const withNull = JSON.parse(JSON.stringify(lock));
    withNull.components.find((c) => c.artifact === 'conscrypt-openjdk-uber').sha256 = null;
    check('mutation: a null binary digest fails byte verification too',
      verifyBytes(withNull.components,
        (c) => ({ path: LOCK_PATH, size: c.sizeBytes }), (c) => c.sha256).length > 0);
  }

  // --- task-plan fixtures ------------------------------------------------------------------------
  const planFails = (name, plan) => check(`plan mutation: ${name}`, planOrderProblems(plan, ['transport-lan']).length > 0);
  const verifyFirst = (consumer) =>
    `:transport-lan:verifySecureDependencyLock SKIPPED\n:transport-lan:${consumer} SKIPPED\n`;
  planFails('Kotlin compile before verification fails', `:transport-lan:compileDebugKotlin SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n`);
  planFails('Java compile before verification fails', `:transport-lan:compileDebugJavaWithJavac SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n`);
  planFails('dex before verification fails', `:transport-lan:dexBuilderDebug SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n`);
  planFails('package before verification fails', `:transport-lan:packageDebug SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n`);
  planFails('test before verification fails', `:transport-lan:testDebugUnitTest SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n`);
  planFails('lint before verification fails', `:transport-lan:lintDebug SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n`);
  planFails('instrumentation before verification fails', `:transport-lan:connectedDebugAndroidTest SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n`);
  check('plan baseline: a resource-only compile task before verification is allowed',
    planOrderProblems(`:transport-lan:compileDebugNavigationResources SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n:transport-lan:compileDebugKotlin SKIPPED\n`, ['transport-lan']).length === 0,
    planOrderProblems(`:transport-lan:compileDebugNavigationResources SKIPPED\n:transport-lan:verifySecureDependencyLock SKIPPED\n:transport-lan:compileDebugKotlin SKIPPED\n`, ['transport-lan']).join('; '));
  check('plan baseline: verification before every consumer passes',
    planOrderProblems(verifyFirst('compileDebugKotlin') + ':transport-lan:dexBuilderDebug SKIPPED\n:transport-lan:packageDebug SKIPPED\n:transport-lan:testDebugUnitTest SKIPPED\n:transport-lan:lintDebug SKIPPED\n', ['transport-lan']).length === 0);
  check('plan mutation: a missing verification task fails',
    planOrderProblems(':transport-lan:compileDebugKotlin SKIPPED\n', ['transport-lan']).length > 0);

  // --- workflow wiring ---------------------------------------------------------------------------
  const workflow = readFileSync(join(ROOT, '.github', 'workflows', 'android-ci.yml'), 'utf8');
  check('baseline: the workflow keeps the Gradle gate and its proof', workflowProblems(workflow).length === 0,
    workflowProblems(workflow).join('; '));
  // replaceAll, not replace: both strings occur more than once in the workflow, and replacing only
  // the first leaves the gate intact so the mutation would appear to be caught when it is not.
  check('workflow mutation: bypassing the Gradle gate is rejected',
    workflowProblems(workflow.replaceAll(':app:assembleDebugAndroidTest', ':app:somethingElse')).length > 0);
  check('workflow mutation: dropping the task-plan proof is rejected',
    workflowProblems(workflow.replaceAll('--dry-run', '--offline')).length > 0);

  // --- verifier crashes must be reported, not silent ----------------------------------------------
  const selfPath = process.argv[1];
  const crashFails = (name, argv, lockContent) => {
    let lockArg = null;
    if (lockContent !== undefined) {
      lockArg = join(ROOT, `.self-test-lock-${name.replace(/[^a-z0-9]/gi, '')}.json`);
      writeFileSync(lockArg, lockContent);
    }
    const run = spawnSync(process.execPath, [selfPath, ...(lockArg ? ['--lock', lockArg] : []), ...argv],
      { cwd: ROOT, encoding: 'utf8' });
    if (lockArg) unlinkSync(lockArg);
    const out = `${run.stdout ?? ''}${run.stderr ?? ''}`;
    const ok = run.status !== 0 && /FAIL/.test(out);
    check(`crash: ${name}`, ok, `status=${run.status} output=${out.slice(0, 160).replace(/\s+/g, ' ')}`);
  };
  crashFails('missing resolved report is reported', ['--resolved', 'transport-lan=definitely/absent.log']);
  crashFails('malformed JSON lock is reported', [], '{ this is not json');
  crashFails('missing lock file is reported', ['--lock', 'definitely/absent-lock.json']);
  crashFails('duplicate lock entry is reported', [],
    JSON.stringify({ ...lock, components: [...lock.components, JSON.parse(JSON.stringify(lock.components[0]))] }));
  crashFails('unsupported schema version is reported', [], JSON.stringify({ ...lock, schemaVersion: 99 }));
  crashFails('unreadable lock path is reported', ['--lock', 'gradle']);
  crashFails('malformed digest is reported', [],
    JSON.stringify({ ...lock, components: lock.components.map((c, i) => (i === 0 ? { ...c, sha256: 'nope' } : c)) }));

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
const lockOverride = args.indexOf('--lock');
if (lockOverride >= 0 && args[lockOverride + 1]) LOCK_PATH = join(ROOT, args[lockOverride + 1]);
const cacheArg = args.indexOf('--cache');
const CACHE_ROOT = cacheArg >= 0 && args[cacheArg + 1] && !args[cacheArg + 1].startsWith('--')
  ? args[cacheArg + 1]
  : join(process.env.GRADLE_USER_HOME || join(homedir(), '.gradle'), 'caches', 'modules-2', 'files-2.1');

if (args.includes('--emit-digests')) {
  // Prints what CI needs to establish provenance: the bytes Gradle actually resolved, and every
  // digest of them. Nothing here is trusted from the lock; the lock is compared afterwards.
  const lock = JSON.parse(readFileSync(LOCK_PATH, 'utf8'));
  const resolve = cacheResolver(CACHE_ROOT);
  for (const c of lock.components) {
    const located = resolve(c);
    const gavPath = `${c.group.replace(/\./g, '/')}/${c.artifact}/${c.version}/${c.fileName}`;
    if (!located) {
      console.log(`${gavPath}|absent|-|-|-|-|${c.sizeBytes}|${c.sha256 ?? 'null'}`);
      continue;
    }
    const bytes = readFileSync(located.path);
    const hex = (algo) => createHash(algo).update(bytes).digest('hex');
    console.log(`${gavPath}|present|${bytes.length}|${hex('sha256')}|${hex('sha1')}|${hex('md5')}|${c.sizeBytes}|${c.sha256 ?? 'null'}`);
  }
  process.exit(0);
}

if (args.includes('--plan')) {
  const planPath = args[args.indexOf('--plan') + 1];
  const plan = readFileSync(join(ROOT, planPath), 'utf8');
  const problems = planOrderProblems(plan, ['transport-lan', 'app']);
  for (const problem of problems) console.log(`  FAIL ${problem}`);
  console.log(problems.length === 0
    ? '  ok   the task plan places verification before every consumer task'
    : `  FAIL the task plan does not place verification first`);
  console.log(`\n${problems.length === 0 ? 1 : 0}/1 checks passed, ${problems.length === 0 ? 0 : 1} failed`);
  process.exit(problems.length === 0 ? 0 : 1);
}

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
