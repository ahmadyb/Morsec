#!/usr/bin/env node
/*
 * Verify the Milestone 4 Part B capability trust boundary.
 *
 * The M2 finding was that an "authenticated session" was represented by a public
 * type carrying a session object, so anything that could name the type could
 * present the appearance of authority. The remediation removes the marker and
 * makes payload refusal the only constructible outcome.
 *
 * Those are source-level guarantees, so they are checked here as source-level
 * invariants. A compiled test can show that a gate refuses for the inputs it was
 * given; it cannot show that no other module can mint the type. This verifier is
 * what fails when the boundary is re-opened.
 *
 * It deliberately does not claim to be a proof of protocol security. See
 * doc/security/milestone-4-part-b.md for what is and is not assured.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();

const checks = [];
let failures = 0;

function check(name, condition, detail) {
  checks.push(condition ? `  PASS  ${name}` : `  FAIL  ${name}${detail ? ` -- ${detail}` : ''}`);
  if (!condition) failures += 1;
}

/** Recursively collects Kotlin sources under a module's given source sets. */
function kotlinSources(module, ...sourceSets) {
  const out = [];
  for (const sourceSet of sourceSets) {
    const base = join(ROOT, module, 'src', sourceSet, 'kotlin');
    if (!existsSync(base)) continue;
    const walk = (dir) => {
      for (const entry of readdirSync(dir)) {
        const full = join(dir, entry);
        if (statSync(full).isDirectory()) walk(full);
        else if (entry.endsWith('.kt')) out.push(full);
      }
    };
    walk(base);
  }
  return out;
}

function read(path) {
  return readFileSync(path, 'utf8');
}

const PRODUCTION_MODULES = ['app', 'core-transfer', 'core-data', 'core-model', 'core-storage', 'core-design', 'transport-lan', 'transport-nearby'];
const mainSources = [];
for (const module of PRODUCTION_MODULES) {
  for (const path of kotlinSources(module, 'main')) mainSources.push({ module, path });
}

// ---------------------------------------------------------------- 1. no marker
const markerHits = mainSources
  .filter(({ path }) => /ApprovedSecureSession/.test(read(path)))
  .map(({ path }) => relative(ROOT, path));
check(
  'no ApprovedSecureSession authority marker in any production source',
  markerHits.length === 0,
  markerHits.join(', '),
);

// ------------------------------------------------------- 2. payload gate is one-way
const contractsPath = join(
  ROOT,
  'core-transfer',
  'src',
  'main',
  'kotlin',
  'app',
  'morsecode',
  'core',
  'transfer',
  'session',
  'SessionContracts.kt',
);
const contracts = existsSync(contractsPath) ? read(contractsPath) : '';
check('SessionContracts.kt exists', contracts.length > 0, contractsPath);
check(
  'PayloadTransferDecision declares only Refused',
  /sealed interface PayloadTransferDecision\s*\{[^}]*\}/s.test(contracts) &&
    !/Authorized/.test(contracts.match(/sealed interface PayloadTransferDecision\s*\{[^}]*\}/s)?.[0] ?? ''),
);
// The body legitimately contains braces (a `require` lambda), so this matches up to the
// single return rather than to the first closing brace.
check(
  'PayloadTransferGate.evaluate always returns Refused',
  /public fun evaluate\(session: NegotiatedSession\): PayloadTransferDecision \{[\s\S]*?return PayloadTransferDecision\.Refused\(/.test(contracts),
);
check(
  'no authorization outcome is constructible anywhere in SessionContracts.kt',
  !/Authorized\(/.test(contracts),
);

// --------------------------------------------- 3. Authenticated carries no payload
const secureContractsPath = join(
  ROOT,
  'core-transfer',
  'src',
  'main',
  'kotlin',
  'app',
  'morsecode',
  'core',
  'transfer',
  'session',
  'SecureSessionContracts.kt',
);
const secureContracts = existsSync(secureContractsPath) ? read(secureContractsPath) : '';
check(
  'SecurePairingResult.Authenticated is a status-only data object',
  /public data object Authenticated : SecurePairingResult/.test(secureContracts),
);
check(
  'SecurePairingResult.Authenticated exposes no negotiated session',
  !/Authenticated\(\s*public val negotiatedSession/.test(secureContracts),
);

// --------------------------------------- 4. the reducer is internal to transport-lan
const reducerMainPaths = mainSources
  .filter(({ path }) => path.endsWith('SecureSessionStateMachine.kt'))
  .map(({ path }) => relative(ROOT, path));
check(
  'SecureSessionStateMachine lives only in transport-lan main sources',
  reducerMainPaths.length === 1 &&
    reducerMainPaths[0] ===
      'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/SecureSessionStateMachine.kt',
  reducerMainPaths.join(', ') || 'not found',
);

const reducerPath = join(ROOT, reducerMainPaths[0] ?? '');
const reducer = existsSync(reducerPath) ? read(reducerPath) : '';
check('reducer is declared internal', /^internal class SecureSessionStateMachine\(/m.test(reducer));
check(
  'reducer exposes no public API',
  !/^ {4}public /m.test(reducer),
);

const outsideReducerUses = mainSources
  .filter(({ module }) => module !== 'transport-lan')
  .filter(({ path }) => /SecureSessionStateMachine/.test(read(path)))
  .map(({ path }) => relative(ROOT, path));
check(
  'no module outside transport-lan references the reducer',
  outsideReducerUses.length === 0,
  outsideReducerUses.join(', '),
);

// ------------------------------- 5. trust preconditions are carried in the API names
check(
  'local confirmation is named for its protected-write precondition',
  /internal fun markLocalConfirmationSentAfterProtectedWrite\(\)/.test(reducer),
);
check(
  'peer confirmation is named for its authenticated-record precondition',
  /internal fun receivePeerConfirmationFromAuthenticatedRecord\(/.test(reducer),
);
check(
  'the old precondition-free confirmation names are gone',
  !/fun markLocalConfirmationSent\(\)/.test(reducer) && !/fun receivePeerConfirmation\(/.test(reducer),
);
check(
  'reducer completion no longer constructs a NegotiatedSession',
  !/NegotiatedCapabilities\(/.test(reducer) && !/controlSession\.copy\(/.test(reducer),
);

// --------------------------------- 6. NegotiatedSession fails closed on its marker
const handshakePath = join(
  ROOT,
  'core-transfer',
  'src',
  'main',
  'kotlin',
  'app',
  'morsecode',
  'core',
  'transfer',
  'session',
  'SessionHandshake.kt',
);
const handshake = existsSync(handshakePath) ? read(handshakePath) : '';
check(
  'NegotiatedSession requires the unauthenticated control marker',
  /require\(security === SessionSecurityState\.ControlOnlyUnauthenticated\)/.test(handshake),
);
check(
  'NegotiatedSession rejects secure-session capability claims',
  /SessionFeature\.SECURE_SESSION !in capabilities\.features/.test(handshake),
);
check(
  'SessionSecurityState has exactly one implementation',
  (handshake.match(/: SessionSecurityState \{/g) ?? []).length === 1,
  String((handshake.match(/: SessionSecurityState \{/g) ?? []).length),
);

// -------------------------------------------------- 7. payload stays disabled overall
const payloadEnabling = mainSources
  .filter(({ path }) => /FILE_PAYLOAD/.test(read(path)) && /features = setOf\([^)]*FILE_PAYLOAD/.test(read(path)))
  .map(({ path }) => relative(ROOT, path));
check(
  'no production source negotiates FILE_PAYLOAD into a capability set',
  payloadEnabling.length === 0,
  payloadEnabling.join(', '),
);

// ------------------------------------------------- 8. SAS entropy budget is pinned
// The human comparison is the only authentication step, so its bit budget must not be able to
// shrink quietly. These constants are the documented 25-bit budget.
check(
  'SAS alphabet is the pinned 32-symbol unambiguous set',
  /public const val SAS_ALPHABET: String = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"/.test(secureContracts),
);
check(
  'SAS is five symbols of five bits each (25 bits total)',
  /public const val SAS_SYMBOL_COUNT: Int = 5/.test(secureContracts) &&
    /public const val SAS_ENTROPY_BITS: Int = SAS_SYMBOL_COUNT \* 5/.test(secureContracts),
);
check(
  'SAS alphabet excludes the ambiguous pairs 0/O and 1/I',
  (() => {
    const match = secureContracts.match(/SAS_ALPHABET: String = "([^"]+)"/);
    if (!match) return false;
    const alphabet = match[1];
    return alphabet.length === 32 && new Set(alphabet).size === 32 && !/[0O1I]/.test(alphabet);
  })(),
);

console.log('\nPart B capability trust boundary verification');
console.log(checks.join('\n'));
console.log(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed`);
if (failures) process.exit(1);
