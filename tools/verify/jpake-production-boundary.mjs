#!/usr/bin/env node
/*
 * Verify that experimental MODP J-PAKE stays inside its authorized blast radius.
 *
 * HISTORY. Until 2026-10-19 this verifier asserted the opposite of what it asserts now: that
 * J-PAKE appeared in *no* production source set, because the protocol was waiting for a qualified
 * external cryptographic review. The owner has since waived that review for this personal,
 * experimental application and authorized implementation. A guard that still said "no J-PAKE in
 * production" would now fail on correct code, which is worse than useless -- it would be worked
 * around.
 *
 * So the guard was inverted, not deleted. What it now pins:
 *
 *   1. Production MAY contain J-PAKE, but only in the three files named below. A fourth file is a
 *      failure, so the surface cannot quietly spread into the app, the UI or the transfer engine.
 *   2. The MPS3 magic may exist only in the framing file.
 *   3. None of those files may persist the code or the key material, log it, reach for Room, or
 *      open a payload path.
 *   4. The androidTest feasibility harness still exists and still cannot be selected by production.
 *   5. Milestone 2, the transfer-engine gate, the payload refusal and the manifest are unchanged.
 *
 * It makes no claim about protocol security. The construction is experimental and unreviewed; see
 * doc/security/owner-risk-waiver-2026-10-19.md.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const SELF_TEST = process.argv.includes('--self-test');

const checks = [];
let failures = 0;

function check(name, condition, detail) {
  checks.push(condition ? `  PASS  ${name}` : `  FAIL  ${name}${detail ? ` -- ${detail}` : ''}`);
  if (!condition) failures += 1;
}

/* ------------------------------------------------------------------ *
 * The authorized surface. Every path is relative to the repository.
 * ------------------------------------------------------------------ */

/**
 * Every file that may implement, describe or drive J-PAKE in production.
 *
 * Membership is NOT a trust statement. It is the opposite: these are the files the checks below
 * scan for persistence, logging, payload access and network use. A file outside this set may not
 * mention the protocol at all, which is what keeps it out of the UI, the discovery provider, the
 * coordinator and every other module by construction.
 *
 * The list grows as the implementation grows. What must never happen is a *new kind* of file
 * appearing here -- a Composable, a Room entity, a service, a discovery path -- because that would
 * mean the boundary moved rather than the implementation.
 */
const AUTHORIZED_JPAKE_FILES = new Set([
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/JpakeBinding.kt',
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/JpakeWireFormat.kt',
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/SecurePairingWireV2.kt',
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/PakeAttempt.kt',
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/PakeRecords.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/AuthenticatedControlAuthority.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/jpake/ModpJpakeFrames.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/jpake/ModpJpakeSession.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/EnteredCodePairingInteraction.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/PairingAttemptExchange.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/PairingExchangeIo.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/PairingExchangeResult.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/PairingChannel.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/PakePairingProgression.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/PakeSessionCoordinator.kt',
]);

/**
 * Files inside the J-PAKE implementation that may name the MPS3 format.
 *
 * Only the codec defines the magic; the adapter names the format because it produces and consumes
 * it. Nothing outside these files may mention it, which is what the check below enforces.
 */
const AUTHORIZED_MPS3_FILES = new Set([
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/JpakeWireFormat.kt',
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/PakeAttempt.kt',
  'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/PakeRecords.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/jpake/ModpJpakeFrames.kt',
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/jpake/ModpJpakeSession.kt',
]);

/* ------------------------------------------------------------------ *
 * Pure predicates. Each is exercised by the self-test, so a mutation
 * proves the guard detects the thing it claims to detect.
 * ------------------------------------------------------------------ */

/**
 * Matches J-PAKE in any casing, with or without the hyphen and with or without an `ec` prefix.
 *
 * The prefix is still matched on purpose. The implemented profile is MODP over
 * `JPAKEPrimeOrderGroups.NIST_3072`, but a file that reintroduced `ECJPAKEParticipant` would be a
 * new cryptographic surface, and this guard must see it rather than let it pass unmentioned.
 */
const JPAKE_TOKEN = /(?:ec)?j-?pake/i;

function filesMentioningJpake(files) {
  return files.filter((f) => JPAKE_TOKEN.test(f.text)).map((f) => f.path);
}

/** Every file that mentions J-PAKE must be explicitly authorized. */
function unauthorizedJpakeFiles(files, allowed) {
  return filesMentioningJpake(files).filter((path) => !allowed.has(path));
}

function filesMentioningToken(files, token) {
  return files.filter((f) => f.text.includes(token)).map((f) => f.path);
}

/**
 * The PAKE surface must not persist, log, or reach the payload path.
 *
 * Each pattern targets a concrete mistake rather than a style preference: a Room annotation, a
 * preferences write, a file write, a log call, or a transfer-engine reference inside a file that
 * is supposed to do nothing but derive bytes.
 */
function pakeSurfaceIsInert(text) {
  const persistence = /\bRoom\b|\bDao\b|SharedPreferences|putString|openFileOutput|getExternalFilesDir/;
  const logging = /\bLog\.(d|i|w|e|v)\s*\(|println\s*\(/;
  const payload = /\bTransferEngine\b|PayloadTransferGate|authenticatedPayload\s*=/;
  return !persistence.test(text) && !logging.test(text) && !payload.test(text);
}

/** The androidTest harness must not become selectable through the production crypto seam. */
function harnessIsNotSelectableByProduction(harnessText) {
  return !/:\s*PairingCryptoFactory\b/.test(harnessText) &&
    !/override\s+fun\s+encodePairingHello/.test(harnessText);
}

/** A feasibility harness must not mint authority or open a payload path. */
function harnessIssuesNoAuthority(harnessText) {
  return !harnessText.includes('SecurePairingRunResult.Authenticated') &&
    !harnessText.includes('AuthenticatedSecureSession') &&
    !/\bTransferEngine\b/.test(harnessText);
}

/** The harness must not persist the entered code. */
function harnessPersistsNothing(harnessText) {
  return !/\bRoom\b/.test(harnessText) &&
    !/\bDao\b/.test(harnessText) &&
    !/SharedPreferences/.test(harnessText) &&
    !/getExternalFilesDir|openFileOutput/.test(harnessText);
}

/** Only fixed phase names and bounded integers may be logged. */
function harnessLogsOnlyBoundedValues(harnessText) {
  if (!harnessText.includes('MorsecJpakePhase')) return false;
  return !/\$(CODE|WRONG_CODE|code|keyA|keyB|keyingMaterial|macTag)\b/.test(harnessText);
}

/* ------------------------------------------------------------------ *
 * Self-test: mutate, then assert the guard caught it.
 * ------------------------------------------------------------------ */

function selfTest() {
  const allowed = new Set(['core-transfer/src/main/kotlin/A.kt']);
  const clean = [{ path: 'core-transfer/src/main/kotlin/A.kt', text: 'class A\n' }];
  const authorized = [{ path: 'core-transfer/src/main/kotlin/A.kt', text: 'class A // J-PAKE ok here\n' }];
  const elsewhere = [{ path: 'app/src/main/kotlin/B.kt', text: 'import x.ECJPAKEParticipant\n' }];

  check('self-test: a file with no J-PAKE mention is not reported',
    unauthorizedJpakeFiles(clean, allowed).length === 0);
  check('self-test: an authorized file is not reported',
    unauthorizedJpakeFiles(authorized, allowed).length === 0);
  check('self-test: J-PAKE outside the authorized set is caught',
    unauthorizedJpakeFiles(elsewhere, allowed).length === 1);

  check('self-test: MPS3 token scan is clean for an unmentioned file',
    filesMentioningToken(clean, 'MPS3').length === 0);
  check('self-test: an MPS3 token is caught',
    filesMentioningToken([{ path: 'x.kt', text: 'val MAGIC_MPS3 = 1' }], 'MPS3').length === 1);

  check('self-test: inert PAKE code passes', pakeSurfaceIsInert('val digest = sha(bytes)\n'));
  check('self-test: Room inside the PAKE surface is caught',
    !pakeSurfaceIsInert('@Dao interface D {}\n'));
  check('self-test: a preferences write is caught',
    !pakeSurfaceIsInert('prefs.edit().putString("code", c)\n'));
  check('self-test: a log call is caught', !pakeSurfaceIsInert('Log.i("tag", "x")\n'));
  check('self-test: a println is caught', !pakeSurfaceIsInert('println(code)\n'));
  check('self-test: a transfer-engine reference is caught',
    !pakeSurfaceIsInert('val e = TransferEngine\n'));

  check('self-test: a harness that does not implement the factory seam passes',
    harnessIsNotSelectableByProduction('class H {\n  fun go() = Unit\n}\n'));
  check('self-test: a harness implementing PairingCryptoFactory is caught',
    !harnessIsNotSelectableByProduction('class H : PairingCryptoFactory {\n}\n'));
  check('self-test: a harness overriding the hello encoder is caught',
    !harnessIsNotSelectableByProduction('class H {\n  override fun encodePairingHello(o: PairingOfferV2) = b\n}\n'));

  check('self-test: a harness that mints no authority passes', harnessIssuesNoAuthority('x'));
  check('self-test: a harness minting Authenticated is caught',
    !harnessIssuesNoAuthority('SecurePairingRunResult.Authenticated'));
  check('self-test: a harness naming the transfer engine is caught',
    !harnessIssuesNoAuthority('val e = TransferEngine'));

  check('self-test: a harness with no persistence passes', harnessPersistsNothing('x'));
  check('self-test: Room persistence in the harness is caught', !harnessPersistsNothing('@Dao fun save()'));
  check('self-test: SharedPreferences in the harness is caught',
    !harnessPersistsNothing('getSharedPreferences("code", 0)'));

  check('self-test: bounded logging passes',
    harnessLogsOnlyBoundedValues('Log.i("MorsecJpakePhase", "phase=x micros=$m")'));
  check('self-test: logging the entered code is caught',
    !harnessLogsOnlyBoundedValues('Log.i("MorsecJpakePhase", "code=$CODE")'));
  check('self-test: logging keying material is caught',
    !harnessLogsOnlyBoundedValues('Log.i("MorsecJpakePhase", "k=$keyingMaterial")'));
  check('self-test: an untagged log is caught',
    !harnessLogsOnlyBoundedValues('Log.i("Other", "phase=x")'));

  console.log('\nJ-PAKE production boundary self-test');
  console.log(checks.join('\n'));
  console.log(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed`);
  if (failures) process.exit(1);
  return;
}

/* ------------------------------------------------------------------ *
 * Real verification against the working tree.
 * ------------------------------------------------------------------ */

function collect(dir) {
  const out = [];
  if (!existsSync(dir)) return out;
  const walk = (current) => {
    for (const entry of readdirSync(current)) {
      const full = join(current, entry);
      if (statSync(full).isDirectory()) walk(full);
      else out.push(full);
    }
  };
  walk(dir);
  return out;
}

const CODE_EXTENSIONS = new Set(['.kt', '.kts', '.java', '.xml', '.json', '.pro', '.toml']);

function loadSources(files) {
  return files
    .filter((f) => CODE_EXTENSIONS.has(f.slice(f.lastIndexOf('.'))))
    .map((f) => ({ path: relative(ROOT, f), text: readFileSync(f, 'utf8') }));
}

function verify() {
  const modules = ['app', 'core-data', 'core-design', 'core-model', 'core-storage',
    'core-transfer', 'transport-lan'];

  const production = [];
  for (const module of modules) {
    production.push(...loadSources(collect(join(ROOT, module, 'src', 'main'))));
  }
  const gradleFiles = loadSources([
    join(ROOT, 'build.gradle.kts'),
    join(ROOT, 'settings.gradle.kts'),
    join(ROOT, 'gradle', 'libs.versions.toml'),
    ...modules.map((m) => join(ROOT, m, 'build.gradle.kts')),
  ]);

  // 1. Production MAY name J-PAKE -- the owner authorized the implementation -- but only in the
  //    files listed at the top of this script. Anywhere else is a scope breach.
  const leaks = unauthorizedJpakeFiles(production, AUTHORIZED_JPAKE_FILES);
  check('J-PAKE appears in production only inside the authorized files', leaks.length === 0,
    leaks.join(', '));

  const gradleLeaks = filesMentioningJpake(gradleFiles);
  check('no Gradle file introduces a J-PAKE-specific dependency', gradleLeaks.length === 0,
    gradleLeaks.join(', '));

  // 2. The MPS3 magic is confined to the framing file.
  const mps3 = filesMentioningToken(production, 'MPS3')
    .concat(filesMentioningToken(production, 'MST3'))
    .filter((path) => !AUTHORIZED_MPS3_FILES.has(path));
  check('the MPS3 format is named only inside the J-PAKE implementation', mps3.length === 0, mps3.join(', '));

  // 3. The authorized files must stay inert: no persistence, no logging, no payload path.
  const offenders = [];
  for (const file of production) {
    if (!AUTHORIZED_JPAKE_FILES.has(file.path)) continue;
    if (!pakeSurfaceIsInert(file.text)) offenders.push(file.path);
  }
  check('the PAKE surface persists nothing, logs nothing and opens no payload path',
    offenders.length === 0, offenders.join(', '));

  // 4. The androidTest harness still exists and still cannot be reached from production.
  const harnessDir = join(ROOT, 'transport-lan', 'src', 'androidTest', 'kotlin',
    'app', 'morsecode', 'transport', 'lan', 'security', 'jpake');
  const harnessFiles = collect(harnessDir).filter((f) => f.endsWith('.kt'));
  check('the J-PAKE feasibility harness exists', harnessFiles.length >= 2,
    `found ${harnessFiles.length}`);

  const harness = harnessFiles.map((f) => readFileSync(f, 'utf8')).join('\n');
  check('the harness is not selectable through the production crypto factory seam',
    harnessIsNotSelectableByProduction(harness));
  check('the harness mints no authenticated authority and opens no payload path',
    harnessIssuesNoAuthority(harness));
  check('the harness persists no code and no key', harnessPersistsNothing(harness));
  check('the harness logs only fixed phase names and bounded integers',
    harnessLogsOnlyBoundedValues(harness));

  // 5. The shipped manifest is unchanged: no new permission for this work.
  const manifests = loadSources(collect(join(ROOT, 'app', 'src', 'main')))
    .filter((f) => f.path.endsWith('AndroidManifest.xml'));
  const widened = manifests.filter((m) => /J-?PAKE/i.test(m.text)).map((m) => m.path);
  check('the shipped manifest is untouched by this work', widened.length === 0,
    widened.join(', '));

  // 6. Milestone and payload gates are untouched by the implementation.
  const allProduction = production.map((f) => f.text).join('\n');
  check('the current milestone is still 2',
    /CURRENT_MILESTONE[^=\n]*=\s*2\b/.test(allProduction));
  check('the transfer engine stays gated', /TRANSFER_ENGINE/.test(allProduction));
  check('the payload gate still refuses unconditionally',
    /PayloadTransferDecision\.Refused\(\s*SessionFailure\(\s*SessionFailureCode\.SECURE_SESSION_REQUIRED/.test(
      allProduction,
    ));

  // 7. The owner-risk waiver travels with the code, because the code is unreviewed.
  check('the owner-risk waiver is present',
    existsSync(join(ROOT, 'doc', 'security', 'owner-risk-waiver-2026-10-19.md')));
  check('the external review dossier is still present',
    existsSync(join(ROOT, 'doc', 'security', 'jpake-review-dossier.md')));

  // 8. The phase timings are worthless if the tag stops being captured, so pin the capture.
  const wrapper = readFileSync(join(ROOT, 'ci', 'run-api23-instrumentation.sh'), 'utf8');
  check('the J-PAKE phase tag is captured from before Gradle starts',
    wrapper.includes('adb logcat -v time -s "$A3_TAG:I" "$JPAKE_TAG:I"'));
  check('the J-PAKE phase tag is dumped on every outcome',
    wrapper.includes('"${JPAKE_PHASE_TAG:-MorsecJpakePhase}:I"'));

  // 9. This verifier is wired into CI, otherwise the guard is decorative.
  const workflow = readFileSync(join(ROOT, '.github', 'workflows', 'android-ci.yml'), 'utf8');
  check('the boundary verifier runs in CI',
    workflow.includes('jpake-production-boundary.mjs'));
}

process.on('uncaughtException', (error) => {
  console.error('jpake-production-boundary crashed:', error && error.message);
  process.exit(1);
});

if (SELF_TEST) selfTest();
else verify();

if (!SELF_TEST) {
  console.log('\nJ-PAKE production boundary verification');
  console.log(checks.join('\n'));
  console.log(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed`);
  if (failures) process.exit(1);
}
