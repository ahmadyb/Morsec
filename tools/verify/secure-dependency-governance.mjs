#!/usr/bin/env node
/*
 * Part B's pinned cryptographic stack, attribution files, API-23 test gate, and
 * no-global-provider policy. Gradle separately verifies the resolved runtime
 * graph; this static verifier runs before the Android toolchain is invoked.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const checks = [];
let failures = 0;
const check = (name, ok, detail = '') => {
  checks.push({ name, ok, detail });
  if (!ok) failures++;
};
const read = (path) => readFileSync(join(ROOT, path), 'utf8');

const catalog = read('gradle/libs.versions.toml');
const transportBuild = read('transport-lan/build.gradle.kts');
const workflow = read('.github/workflows/android-ci.yml');
const engine = read('transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/ConscryptSecureSessionEngine.kt');
const gradleTaskExpected = {
  conscrypt: transportBuild.includes('val expectedConscryptVersion = "2.7.0"'),
  bouncyCastle: transportBuild.includes('val expectedBouncyCastleVersion = "1.86"'),
};

function pinnedVersion(key) {
  return catalog.match(new RegExp(`^${key}\\s*=\\s*"([0-9]+(?:\\.[0-9]+)+)"\\s*$`, 'm'))?.[1] ?? null;
}

const conscryptVersion = pinnedVersion('conscrypt');
const bouncyCastleVersion = pinnedVersion('bouncyCastle');
check('Conscrypt is pinned to reviewed 2.7.0', conscryptVersion === '2.7.0', conscryptVersion ?? 'missing');
check('Bouncy Castle is pinned to reviewed 1.86', bouncyCastleVersion === '1.86', bouncyCastleVersion ?? 'missing');
check('runtime governance task matches pinned version literals', gradleTaskExpected.conscrypt && gradleTaskExpected.bouncyCastle);
check('catalog aliases name the reviewed artifacts',
  /conscrypt-android\s*=\s*\{\s*group\s*=\s*"org\.conscrypt",\s*name\s*=\s*"conscrypt-android",\s*version\.ref\s*=\s*"conscrypt"\s*\}/.test(catalog) &&
  /bouncycastle-pkix\s*=\s*\{\s*group\s*=\s*"org\.bouncycastle",\s*name\s*=\s*"bcpkix-jdk18on",\s*version\.ref\s*=\s*"bouncyCastle"\s*\}/.test(catalog));
check('transport-lan consumes the reviewed crypto dependencies',
  transportBuild.includes('implementation(libs.conscrypt.android)') &&
  transportBuild.includes('implementation(libs.bouncycastle.pkix)'));
check('transport-lan remains minSdk 23', /minSdk\s*=\s*23/.test(transportBuild));
check('API 23 emulator instrumentation is a CI gate',
  workflow.includes('Run bundled Conscrypt TLS 1.3 compatibility test on API 23') &&
  /api-level:\s*23/.test(workflow) && workflow.includes(':transport-lan:connectedDebugAndroidTest'));
check('production TLS 1.3/exporter/AES-GCM operations select Conscrypt explicitly',
  engine.includes('Conscrypt.newProvider()') &&
  engine.includes('SSLContext.getInstance(SecurePairingTranscript.TLS_1_3, provider)') &&
  engine.includes('Conscrypt.exportKeyingMaterial') &&
  engine.includes('Cipher.getInstance("AES/GCM/NoPadding", provider)'));

const securitySources = [];
function walk(path) {
  for (const entry of readdirSync(join(ROOT, path), { withFileTypes: true })) {
    const child = join(path, entry.name);
    if (entry.isDirectory()) walk(child);
    else if (entry.isFile() && /\.(kt|java)$/.test(entry.name)) securitySources.push(child);
  }
}
walk('transport-lan/src/main');
const globalMutation = securitySources.filter((path) =>
  /\bSecurity\s*\.\s*(?:addProvider|insertProviderAt|removeProvider)\s*\(/.test(read(path)));
check('production source never mutates the global JCA provider registry', globalMutation.length === 0,
  globalMutation.map((path) => relative(ROOT, path)).join(', '));

const licenses = 'app/src/main/assets/third_party_licenses';
let apache = '';
let netty = '';
let harmony = '';
let bouncy = '';
let notice = '';
try {
  apache = read(`${licenses}/Apache-2.0.txt`);
  netty = read(`${licenses}/licenses/LICENSE.netty.txt`);
  harmony = read(`${licenses}/licenses/LICENSE.harmony.txt`);
  bouncy = read(`${licenses}/BouncyCastle-LICENSE.txt`);
  notice = read(`${licenses}/Conscrypt-NOTICE.txt`);
} catch (error) {
  check('third-party license and notice files exist', false, error.message);
}
if (apache) {
  check('Apache-2.0 file contains the complete standard terms',
    apache.includes('Apache License, Version 2.0') && apache.includes('END OF TERMS AND CONDITIONS') && apache.length > 10_000,
    `${apache.length} characters`);
}
if (apache && netty && harmony) {
  check('Conscrypt Netty and Harmony license references resolve to the complete Apache terms',
    netty === apache && harmony === apache,
    `Apache ${apache.length}, Netty ${netty.length}, Harmony ${harmony.length} characters`);
}
if (bouncy) {
  check('Bouncy Castle license preserves copyright, grant, notice and disclaimer',
    bouncy.includes('The Legion of the Bouncy Castle Inc.') &&
    bouncy.includes('Permission is hereby granted') &&
    bouncy.includes('THE SOFTWARE IS PROVIDED "AS IS"'));
}
if (notice) {
  check('Conscrypt NOTICE preserves Netty and Harmony attribution',
    notice.includes('modified portion of `Netty`') &&
    notice.includes('modified portion of `Apache Harmony`') &&
    notice.includes('licenses/LICENSE.netty.txt') &&
    notice.includes('licenses/LICENSE.harmony.txt') &&
    notice.includes('Apache-2.0.txt'));
}

for (const result of checks) {
  process.stdout.write(`  ${result.ok ? 'ok  ' : 'FAIL'} ${result.name}${result.detail ? ` — ${result.detail}` : ''}\n`);
}
process.stdout.write(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed\n`);
process.exitCode = failures === 0 ? 0 : 1;
