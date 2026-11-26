#!/usr/bin/env node
/*
 * Payload Slice 4S: test-only Android provider, fault controls and evidence.
 * These static checks complement the APK inspection and actual device tests;
 * none supplies a production payload composition or proves power-loss safety.
 * --self-test mutates independent inputs in memory and requires each guard to fail.
 */
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const SELF_TEST = process.argv.includes('--self-test');
const P = {
  manifest: 'core-storage/src/androidTest/AndroidManifest.xml',
  provider: 'core-storage/src/androidTest/kotlin/app/morsecode/core/storage/transfer/Slice4sTestProvider.kt',
  source: 'core-storage/src/androidTest/kotlin/app/morsecode/core/storage/transfer/StorageSourceApi23Test.kt',
  destination: 'core-storage/src/androidTest/kotlin/app/morsecode/core/storage/transfer/StorageDestinationApi23Test.kt',
  room: 'core-data/src/androidTest/kotlin/app/morsecode/core/data/db/RoomStorageApi23Test.kt',
  runtime: 'core-transfer/src/main/kotlin/app/morsecode/core/transfer/runtime/SingleFileRuntime.kt',
  ports: 'core-transfer/src/main/kotlin/app/morsecode/core/transfer/runtime/SingleFilePorts.kt',
  gate: 'core-transfer/src/main/kotlin/app/morsecode/core/transfer/session/SessionContracts.kt',
  readiness: 'core-model/src/main/kotlin/app/morsecode/core/model/FeatureReadiness.kt',
  schema1: 'core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json',
  schema2: 'core-data/schemas/app.morsecode.core.data.db.MorseDatabase/2.json',
  workflow: '.github/workflows/android-ci.yml',
  script: 'ci/run-api23-instrumentation.sh',
  evidence: 'tools/ci/test_evidence.py',
  fixtures: 'tools/ci/test_test_evidence.py',
  apk: 'tools/ci/verify_storage_apk_boundary.py',
  apkFixtures: 'tools/ci/test_storage_apk_boundary.py',
};
const PIN = {
  // All 327 production source/build files as independently hashed from 4A SHA f1f532c.
  production: 'de2a40154c3a948593a2a498ef4e157e19399b8bfb4569a91a41aeecdfd27588',
  gate: '5757bdd3fca9732a03102577bcdbd040f2360d92f1ecedaeee257d1525cf0ea8',
  readiness: '1c4eff9bdc0c77a4a7f51367d6fd4934bffe40f9d40386201350e83bac87c142',
  schema1: 'b0bca4243d2f0ba4631e3338e611d3bcaff8ba456de83b79ea0106ae187ac488',
  schema2: '7e6acfd9c03b0214dcaddc2f5d1ceb175588f5cfcf93cca5437617be24b2b074',
};
const AUTHORITY = 'app.morsecode.core.storage.test.slice4s.provider';
const PROVIDER = 'app.morsecode.core.storage.transfer.Slice4sTestProvider';
const skip = new Set(['.git', '.gradle', '.cache', '.agent-backups', 'node_modules', 'build', 'dist', 'out', 'target', '.next', '.venv', 'ci-logs', '__pycache__', 'Unselected files']);
function walk(dir, result = []) {
  if (!existsSync(dir)) return result;
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    if (ent.isDirectory()) {
      if (!skip.has(ent.name)) walk(join(dir, ent.name), result);
    } else if (ent.isFile()) result.push(relative(ROOT, join(dir, ent.name)).replaceAll('\\', '/'));
  }
  return result;
}
function collect() {
  const files = new Map();
  for (const path of walk(ROOT)) files.set(path, readFileSync(join(ROOT, path)));
  return files;
}
function text(files, path) { return files.get(path)?.toString('utf8') ?? ''; }
function sha(value) { return createHash('sha256').update(value).digest('hex'); }
function productionPath(path) {
  // Only these two exact source sets are test-only. A newly added variant,
  // shared test fixture, debug source set or build script is protected too.
  const source = /(?:^|\/)src\/([^/]+)\//.exec(path);
  if (source && !['test', 'androidTest'].includes(source[1])) return true;
  return /(?:^|\/)(?:build|settings)\.gradle(?:\.kts)?$/.test(path) ||
    /^gradle\/.*\.(?:toml|gradle|gradle\.kts)$/.test(path) || path.startsWith('buildSrc/');
}
function productionFingerprint(files) {
  const entries = [...files.entries()].filter(([path]) => productionPath(path)).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0);
  return sha(entries.map(([path, bytes]) => `${path}\0${sha(bytes)}\n`).join(''));
}
function attr(block, name, value) {
  return new RegExp(`\\bandroid:${name}\\s*=\\s*["']${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}["']`).test(block);
}
function privateTestManifest(files) {
  const xml = text(files, P.manifest).replace(/<!--[\s\S]*?-->/g, '');
  const providers = [...xml.matchAll(/<provider\b[^>]*\/?\s*>/g)].map(match => match[0]);
  const test = providers.filter(provider => attr(provider, 'name', PROVIDER) || attr(provider, 'authorities', AUTHORITY));
  const manifestRefs = [...files].filter(([path, data]) => path !== P.manifest && /AndroidManifest\.xml$/.test(path) &&
    (data.includes(Buffer.from(PROVIDER)) || data.includes(Buffer.from(AUTHORITY))));
  return test.length === 1 && providers.length === 1 && attr(test[0], 'name', PROVIDER) &&
    attr(test[0], 'authorities', AUTHORITY) && attr(test[0], 'exported', 'false') &&
    attr(test[0], 'grantUriPermissions', 'false') && attr(test[0], 'multiprocess', 'false') && manifestRefs.length === 0;
}
function providerAndFaultIsolation(files) {
  const forbidden = /Slice4sTestProvider|Slice4sProviderControls|app\.morsecode\.core\.storage\.test\.slice4s\.provider|SingleFileTypedReaderRuntimeTest|TestTypedSource|TestApprovalInteraction|EchoApproval|\bMemoryReader\b/;
  const cleanProduction = [...files].filter(([path]) => productionPath(path)).every(([, data]) => !forbidden.test(data.toString('utf8')));
  const provider = text(files, P.provider), tests = text(files, P.source) + text(files, P.destination);
  return cleanProduction && /class\s+Slice4sTestProvider\s*:\s*ContentProvider/.test(provider) &&
    /internal\s+object\s+Slice4sProviderControls/.test(provider) &&
    provider.includes('ParcelFileDescriptor.createReliablePipe()') && provider.includes('writer.closeWithError(') &&
    provider.includes('denyReads') && provider.includes('denyWrites') && provider.includes('failAfter') &&
    provider.includes('ownedDescriptors.forEach') && provider.includes('ownedThreads.forEach') &&
    provider.includes('directory.deleteRecursively()') && tests.includes('Slice4sProviderControls.cleanup(context)');
}
function unchangedAndInert(files) {
  const runtime = text(files, P.runtime) + '\n' + text(files, P.ports);
  const outsideRuntime = [...files].filter(([path]) => productionPath(path) && path !== P.runtime && path !== P.ports)
    .map(([, data]) => data.toString('utf8')).join('\n');
  return productionFingerprint(files) === PIN.production &&
    !/\b(?:LanMultiplexedRecordChannel|LanMultiplexedReceive|LanMultiplexedSend)\b|app\.morsecode\.transport\.|\bimport\s+java\.net\./.test(runtime) &&
    !/\b(?:SingleFileRuntime|RuntimeTypedReader|SingleFileRole)\b|app\.morsecode\.core\.transfer\.runtime\b/.test(outsideRuntime);
}
function closedGateAndSchemas(files) {
  const schemas = [...files.keys()].filter(path => path.startsWith('core-data/schemas/app.morsecode.core.data.db.MorseDatabase/')).sort();
  return sha(files.get(P.gate) ?? '') === PIN.gate && sha(files.get(P.readiness) ?? '') === PIN.readiness &&
    text(files, P.readiness).includes('CURRENT_MILESTONE: Int = 2') &&
    text(files, P.gate).includes('return PayloadTransferDecision.Refused(SessionFailure(SessionFailureCode.SECURE_SESSION_REQUIRED))') &&
    schemas.join('\n') === [P.schema1, P.schema2].join('\n') &&
    sha(files.get(P.schema1) ?? '') === PIN.schema1 && sha(files.get(P.schema2) ?? '') === PIN.schema2;
}
function frameworkEvidenceSources(files) {
  const source = text(files, P.source), destination = text(files, P.destination), room = text(files, P.room);
  return [source, destination, room].every(code => code.includes('AndroidJUnit4::class') &&
      code.includes('assertEquals(23, Build.VERSION.SDK_INT)') && !code.includes('RobolectricTestRunner')) &&
    source.includes('resolveContentProvider(') && source.includes('provider.exported') &&
    source.includes('Process.myUid()') && source.includes('descriptor.checkError()') &&
    source.includes('ProviderSourceOpener.open(') && source.includes('DestinationVerifier.verify(') &&
    destination.includes('AppPrivatePartialStore(') && destination.includes('descriptor.fd.sync()') &&
    destination.includes('DocumentsContractSafGateway(') && destination.includes('real.flush()') &&
    destination.includes('TruncateOutcome.Truncated(1_024L)') &&
    room.includes('Room.databaseBuilder(') && room.includes('PRAGMA user_version') &&
    room.includes('CREATE TRIGGER slice4s_reject_completion') && room.includes('TransferState.COMPLETED');
}
function deviceCi(files) {
  const workflow = text(files, P.workflow), script = text(files, P.script), evidence = text(files, P.evidence);
  const branch = /elif \[ "\$script" = tools\/verify\/slice4s-test-boundary\.mjs \]; then([\s\S]*?)\n\s*else/.exec(workflow)?.[1] ?? '';
  return /for script in [^\n]*tools\/verify\/slice4s-test-boundary\.mjs/.test(workflow) &&
    branch.includes('node "$script" --self-test || echo "VERIFIER FAILED: $script"') &&
    branch.includes('node "$script" || echo "VERIFIER FAILED: $script"') &&
    workflow.includes('grep -q "VERIFIER FAILED" ci-logs/15-verifiers.log && exit 1') &&
    workflow.includes("python3 -m unittest discover -s tools/ci -p 'test_storage_apk_boundary.py' -v") &&
    workflow.includes('python3 tools/ci/verify_storage_apk_boundary.py --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt"') &&
    workflow.includes(':core-storage:assembleDebugAndroidTest :core-data:assembleDebugAndroidTest') &&
    workflow.includes('script: bash ci/run-api23-instrumentation.sh') &&
    workflow.includes('run: python3 tools/ci/test_evidence.py api23') &&
    script.includes('--no-parallel') && script.includes(':transport-lan:connectedDebugAndroidTest') &&
    script.includes(':core-storage:connectedDebugAndroidTest') && script.includes(':core-data:connectedDebugAndroidTest') &&
    script.includes('core-storage/build/outputs/androidTest-results/connected') && script.includes('exit "$status"') &&
    script.includes('adb logcat -v time -s "$STORAGE_TAG:I" >> ci-logs/63-api23-storage-observations.log') &&
    evidence.includes('API23_REQUIRED_MODULES = frozenset({"transport-lan", "core-storage", "core-data"})') &&
    evidence.includes('for marker in API23_STORAGE_MARKERS:') && evidence.includes('API-23 storage observation missing:') &&
    evidence.includes('source_dirs = [root / module / "src/androidTest" for module in sorted(required)]') &&
    evidence.includes('for module in required') && evidence.includes('require_source_coverage(results, expected, required)') &&
    evidence.includes('StorageSourceApi23Test"): 9') && evidence.includes('StorageDestinationApi23Test"): 8') &&
    evidence.includes('RoomStorageApi23Test"): 6') &&
    text(files, P.fixtures).includes('test_all_three_api23_modules_and_their_reported_methods_are_required') &&
    text(files, P.apk).includes('validate_manifests(') && text(files, P.apk).includes('validate_dex(') &&
    text(files, P.apkFixtures).includes('test_production_manifest_and_every_test_only_dex_marker_are_rejected');
}
const rules = [
  ['one private, non-granting test provider with a unique authority', privateTestManifest],
  ['provider, controls, test reader and approval fixtures are isolated from production', providerAndFaultIsolation],
  ['all production sources/builds unchanged; runtime has no real multiplexer or callers', unchangedAndInert],
  ['payload gate/readiness and exactly Room v1/v2 remain unchanged', closedGateAndSchemas],
  ['real API-23 framework, descriptor, Room and reopened-file tests are present', frameworkEvidenceSources],
  ['CI runs APK boundary fixtures, all three device tasks and per-method evidence', deviceCi],
];
function mutate(files, path, oldText, replacement) {
  const old = text(files, path);
  if (!old.includes(oldText)) throw new Error(`mutation target missing in ${path}: ${oldText}`);
  const copy = new Map(files); copy.set(path, Buffer.from(old.replace(oldText, replacement))); return copy;
}
function add(files, path, content) { const copy = new Map(files); copy.set(path, Buffer.from(content)); return copy; }
function selfTest(files) {
  const cases = [
    ['provider exported', privateTestManifest, () => mutate(files, P.manifest, 'android:exported="false"', 'android:exported="true"')],
    ['URI grants enabled', privateTestManifest, () => mutate(files, P.manifest, 'android:grantUriPermissions="false"', 'android:grantUriPermissions="true"')],
    ['test authority altered', privateTestManifest, () => mutate(files, P.manifest, AUTHORITY, 'other.authority')],
    ['provider copied to app manifest', privateTestManifest, () => mutate(files, 'app/src/main/AndroidManifest.xml', '<application', `<provider android:name="${PROVIDER}"/><application`)],
    ['test fault control imported by production', providerAndFaultIsolation, () => add(files, 'app/src/main/kotlin/Injected.kt', 'val x = Slice4sProviderControls.denyReads')],
    ['test provider installed in a debug source set', providerAndFaultIsolation, () => add(files, 'app/src/debug/AndroidManifest.xml', `<provider android:name="${PROVIDER}"/>`)],
    ['test reader bridge becomes production-reachable', providerAndFaultIsolation, () => add(files, 'app/src/main/kotlin/Injected.kt', 'val x = TestTypedSource()')],
    ['test approval path becomes production-reachable', providerAndFaultIsolation, () => add(files, 'app/src/main/kotlin/Injected.kt', 'val x = TestApprovalInteraction()')],
    ['test controls omit cleanup', providerAndFaultIsolation, () => mutate(files, P.provider, 'ownedDescriptors.forEach', 'unusedDescriptors.forEach')],
    ['runtime references a real LAN multiplexer', unchangedAndInert, () => mutate(files, P.runtime, 'package app.morsecode.core.transfer.runtime', 'package app.morsecode.core.transfer.runtime\nimport app.morsecode.transport.lan.security.LanMultiplexedRecordChannel')],
    ['production source set imports androidTest', unchangedAndInert, () => mutate(files, 'core-storage/build.gradle.kts', 'android {', 'android { sourceSets.getByName("main").java.srcDir("src/androidTest/kotlin")')],
    ['production constructs runtime', unchangedAndInert, () => add(files, 'app/src/main/kotlin/Injected.kt', 'fun x() = SingleFileRuntime()')],
    ['readiness changed', closedGateAndSchemas, () => mutate(files, P.readiness, 'CURRENT_MILESTONE: Int = 2', 'CURRENT_MILESTONE: Int = 5')],
    ['payload gate bypassed', closedGateAndSchemas, () => mutate(files, P.gate, 'return PayloadTransferDecision.Refused(SessionFailure(SessionFailureCode.SECURE_SESSION_REQUIRED))', 'return PayloadTransferDecision.Allowed')],
    ['Room v1 changed', closedGateAndSchemas, () => mutate(files, P.schema1, '"version": 1', '"version": 9')],
    ['Room v2 changed', closedGateAndSchemas, () => mutate(files, P.schema2, '"version": 2', '"version": 9')],
    ['Room v3 export added', closedGateAndSchemas, () => add(files, 'core-data/schemas/app.morsecode.core.data.db.MorseDatabase/3.json', '{}')],
    ['framework descriptor sync removed', frameworkEvidenceSources, () => mutate(files, P.destination, 'descriptor.fd.sync()', 'Unit')],
    ['real Room builder removed', frameworkEvidenceSources, () => mutate(files, P.room, 'Room.databaseBuilder(', 'fakeDatabase(')],
    ['test provider exported at runtime', frameworkEvidenceSources, () => mutate(files, P.source, 'provider.exported', 'false')],
    ['transport device suite omitted', deviceCi, () => mutate(files, P.script, ':transport-lan:connectedDebugAndroidTest', ':transport-lan:test')],
    ['storage device suite omitted', deviceCi, () => mutate(files, P.script, ':core-storage:connectedDebugAndroidTest', ':core-storage:test')],
    ['Room device suite omitted', deviceCi, () => mutate(files, P.script, ':core-data:connectedDebugAndroidTest', ':core-data:test')],
    ['storage module omitted from evidence', deviceCi, () => mutate(files, P.evidence, '"transport-lan", "core-storage", "core-data"', '"transport-lan", "core-data"')],
    ['source method evidence removed', deviceCi, () => mutate(files, P.evidence, 'require_source_coverage(results, expected, required)', 'pass')],
    ['device observation markers ignored', deviceCi, () => mutate(files, P.evidence, 'for marker in API23_STORAGE_MARKERS:', 'for marker in ():')],
    ['storage log stream omitted', deviceCi, () => mutate(files, P.script, 'adb logcat -v time -s "$STORAGE_TAG:I" >> ci-logs/63-api23-storage-observations.log', 'true')],
    ['APK inspection omitted', deviceCi, () => mutate(files, P.workflow, 'python3 tools/ci/verify_storage_apk_boundary.py --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt"', 'true')],
    ['APK boundary fixtures omitted', deviceCi, () => mutate(files, P.workflow, "python3 -m unittest discover -s tools/ci -p 'test_storage_apk_boundary.py' -v", 'true')],
    ['boundary mutations omitted from CI', deviceCi, () => mutate(files, P.workflow, 'elif [ "$script" = tools/verify/slice4s-test-boundary.mjs ]; then', 'elif [ "$script" = tools/verify/other.mjs ]; then')],
  ];
  let failures = 0;
  for (const [name, rule, change] of cases) {
    let caught = false;
    try { caught = rule(files) && !rule(change()); } catch (error) { console.error(`  ERROR ${name}: ${error.message}`); }
    console.log(`  ${caught ? 'PASS' : 'FAIL'}  mutation: ${name}`);
    if (!caught) failures++;
  }
  console.log(`\n${cases.length - failures}/${cases.length} mutations caught, ${failures} failed`);
  return failures;
}
const files = collect();
let failures = 0;
console.log('Slice 4S test-only storage boundary');
for (const [name, rule] of rules) {
  let ok = false;
  try { ok = rule(files); } catch (error) { console.error(`  ERROR ${name}: ${error.message}`); }
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}`);
  if (!ok) failures++;
}
console.log(`\n${rules.length - failures}/${rules.length} checks passed, ${failures} failed`);
if (process.env.PRINT_SLICE4S_PRODUCTION_HASH === '1') console.log(`production=${productionFingerprint(files)}`);
if (SELF_TEST) failures += selfTest(files);
if (failures) process.exit(1);
