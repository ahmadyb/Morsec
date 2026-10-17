#!/usr/bin/env node
/*
 * Verify that EC J-PAKE stays inside the androidTest feasibility harness.
 *
 * The project has chosen an entered-shared-code interaction backed by J-PAKE, but
 * the protocol is NOT approved for production: the PAKE-to-TLS channel binding and
 * the mutual-confirmation construction are project-specific and have not had
 * qualified external cryptographic review. Until that review approves one exact
 * construction, J-PAKE must remain a measurement harness and nothing more.
 *
 * This verifier is what fails if that boundary erodes. It is mutation-tested with
 * --self-test, because a guard that has never been seen to fail is not evidence.
 *
 * It makes no claim about protocol security. See
 * doc/security/jpake-review-dossier.md for the open questions.
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
 * Pure predicates. Each takes text (or a list of {path, text}) and is
 * exercised directly by the self-test, so a mutation proves the guard
 * actually detects the thing it claims to detect.
 * ------------------------------------------------------------------ */

/** Matches J-PAKE and EC J-PAKE in any casing, with or without the hyphen. */
const JPAKE_TOKEN = /(?:ec)?j-?pake/i;

function filesMentioningJpake(files) {
  return files.filter((f) => JPAKE_TOKEN.test(f.text)).map((f) => f.path);
}

function filesMentioningToken(files, token) {
  return files.filter((f) => f.text.includes(token)).map((f) => f.path);
}

/** The harness must not become selectable through the production crypto seam. */
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
  // A code or keying material reaching a log would appear as an interpolation of a
  // secret-bearing identifier rather than a bounded integer.
  return !/\$(CODE|WRONG_CODE|code|keyA|keyB|keyingMaterial|macTag)\b/.test(harnessText);
}

/* ------------------------------------------------------------------ *
 * Self-test: mutate, then assert the guard caught it.
 * ------------------------------------------------------------------ */

function selfTest() {
  const cleanProduction = [{ path: 'transport-lan/src/main/kotlin/A.kt', text: 'class A\n' }];
  const leakedProduction = [
    { path: 'transport-lan/src/main/kotlin/A.kt', text: 'import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKEParticipant\n' },
  ];
  const docLeak = [{ path: 'app/src/main/kotlin/B.kt', text: '// TODO: switch to J-PAKE\n' }];

  check('self-test: clean production passes the J-PAKE scan',
    filesMentioningJpake(cleanProduction).length === 0);
  check('self-test: an ECJPAKE import in production is caught',
    filesMentioningJpake(leakedProduction).length === 1);
  check('self-test: a J-PAKE design note in production is caught',
    filesMentioningJpake(docLeak).length === 1);

  check('self-test: MPS3 token scan is clean by default',
    filesMentioningToken(cleanProduction, 'MPS3').length === 0);
  check('self-test: an MPS3 production token is caught',
    filesMentioningToken([{ path: 'x.kt', text: 'val MAGIC_MPS3 = 1' }], 'MPS3').length === 1);

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

  // 1. No production source in any module may name J-PAKE at all -- not as an import,
  //    not as a type, not even as a design note. The protocol is unreviewed, so it must
  //    not be reachable from, or implied by, shipped code.
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

  const productionLeaks = filesMentioningJpake(production);
  check('no production source references J-PAKE', productionLeaks.length === 0,
    productionLeaks.join(', '));

  const gradleLeaks = filesMentioningJpake(gradleFiles);
  check('no Gradle file introduces a J-PAKE-specific dependency', gradleLeaks.length === 0,
    gradleLeaks.join(', '));

  // 2. No MPS3/MST3 production token. The framing is a candidate for review, not an
  //    agreed wire format, so it must not acquire a magic constant in shipped code.
  const mps3 = filesMentioningToken(production, 'MPS3')
    .concat(filesMentioningToken(production, 'MST3'));
  check('no MPS3 or MST3 production token exists', mps3.length === 0, mps3.join(', '));

  // 3. The harness exists, and only under androidTest.
  const harnessDir = join(ROOT, 'transport-lan', 'src', 'androidTest', 'kotlin',
    'app', 'morsecode', 'transport', 'lan', 'security', 'jpake');
  const harnessFiles = collect(harnessDir).filter((f) => f.endsWith('.kt'));
  check('the J-PAKE feasibility harness exists', harnessFiles.length >= 2,
    `found ${harnessFiles.length}`);

  const outsideHarness = [];
  for (const module of modules) {
    for (const sourceSet of ['main', 'test', 'debug', 'release']) {
      for (const f of loadSources(collect(join(ROOT, module, 'src', sourceSet)))) {
        if (JPAKE_TOKEN.test(f.text)) outsideHarness.push(f.path);
      }
    }
  }
  check('J-PAKE appears in no source set other than androidTest', outsideHarness.length === 0,
    outsideHarness.join(', '));

  // 4. The harness cannot be selected by production, cannot mint authority, cannot
  //    persist anything, and cannot widen the shipped surface.
  const harness = harnessFiles.map((f) => readFileSync(f, 'utf8')).join('\n');
  check('the harness is not selectable through the production crypto factory seam',
    harnessIsNotSelectableByProduction(harness));
  check('the harness mints no authenticated authority and opens no payload path',
    harnessIssuesNoAuthority(harness));
  check('the harness persists no code and no key', harnessPersistsNothing(harness));
  check('the harness logs only fixed phase names and bounded integers',
    harnessLogsOnlyBoundedValues(harness));

  // 5. The shipped manifest is unchanged by this work: no new permission.
  const manifests = loadSources(collect(join(ROOT, 'app', 'src', 'main')))
    .filter((f) => f.path.endsWith('AndroidManifest.xml'));
  const widened = manifests.filter((m) => /J-?PAKE/i.test(m.text)).map((m) => m.path);
  check('the shipped manifest is untouched by the feasibility work', widened.length === 0,
    widened.join(', '));

  // 6. Payload transfer stays disabled and the milestone stays at 2. The feasibility
  //    harness must not become a back door into Part C.
  const allProduction = production.map((f) => f.text).join('\n');
  check('the current milestone is still 2',
    /CURRENT_MILESTONE[^=\n]*=\s*2\b/.test(allProduction));
  check('the transfer engine stays gated', /TRANSFER_ENGINE/.test(allProduction));

  // 7. The review dossier exists, so the open questions travel with the code.
  check('the external review dossier is present',
    existsSync(join(ROOT, 'doc', 'security', 'jpake-review-dossier.md')));

  // 8. The phase timings are worthless if the tag stops being captured, so pin the
  //    capture as well as the logging.
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
