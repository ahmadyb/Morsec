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

// --------------------------- 9. handle comparison is still identity-based
// SecureApprovalHandle.matches and SecurePairingApprovalRequest's constructor had to become public
// when the reducer moved to :transport-lan. That is only safe because accepting a decision requires
// the candidate to be the very object the reducer registered. If that identity check is ever
// dropped, the public constructor becomes an authority hole, so pin it here.
check(
  'handle matching still requires object identity',
  /public fun matches\(candidate: SecureApprovalHandle\): Boolean =\s*this === candidate &&/.test(secureContracts),
);
check(
  'the reducer still gates decisions on its own registered handle',
  /approvalHandle\?\.matches\(handle\) != true\) return SecureApprovalResult\.Stale/.test(reducer),
);
check(
  'handle secret bytes cannot be supplied by callers',
  /public class SecureApprovalHandle internal constructor\(/.test(secureContracts),
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

// ------------------------------- 10. no visibility modifier inside an interface
// Kotlin rejects `internal`/`private`/`protected` on interface members, so a bulk visibility change
// is a compile error, not a warning. A `private` member of a *class* nested in an interface is
// legal, so the enclosing type has to be tracked rather than approximated.
{
  const offenders = [];
  const count = (text, ch) => text.split(ch).length - 1;
  for (const { path } of mainSources) {
    const stack = [];
    const lines = read(path).split('\n');
    for (let i = 0; i < lines.length; i += 1) {
      const code = lines[i].replace(/\/\/.*$/, '');
      for (let c = 0; c < count(code, '}'); c += 1) stack.pop();
      const decl = code.match(
        /^\s*(?:public |internal |private |protected )?(?:sealed |fun |data |enum |abstract )*(interface|class|object)\s+[A-Za-z_][A-Za-z0-9_]*/,
      );
      const innermostIsInterface = stack[stack.length - 1] === 'interface';
      // The offending line is itself a declaration (`internal data object ...`), so this must not
      // be gated on the line being a non-declaration. `internal` and `protected` are never legal
      // on an interface member; `private` is legal when the member has a body, so it is not
      // checked here rather than risk a false positive.
      if (innermostIsInterface && /^\s*(internal|protected)\s/.test(code)) {
        offenders.push(`${relative(ROOT, path)}:${i + 1}`);
      }
      const opens = count(code, '{');
      for (let o = 0; o < opens; o += 1) stack.push(decl && o === 0 ? decl[1] : 'other');
    }
  }
  check(
    'no interface member carries a non-public visibility modifier',
    offenders.length === 0,
    offenders.join(', '),
  );
}

// --------------------------------- 11. total deadlines are actually armed
// The M4 finding is only remediated if the production path arms these. A green unit test on the
// primitive proves nothing about the coordinator, so pin the call sites.
{
  const coordPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanPairingCoordinator.kt',
  );
  const coordinator = existsSync(coordPath) ? read(coordPath) : '';
  // The key-confirmation phase now lives in the composed path, which bounds it with
  // `RecordBudgets` rather than with this file's phase helper. The check below pins that instead.
  for (const label of ['tls-handshake', 'pairing-hello']) {
    check(
      `coordinator arms a total deadline for ${label}`,
      new RegExp(`label = "${label}"[\\s\\S]{0,200}?budgetMillis = SecureSessionLimits\\.[A-Z_]+_DEADLINE_MILLIS`).test(coordinator),
    );
  }
  check(
    'the composed path bounds key confirmation with its record budgets',
    /recordBudgets = recordBudgets,/.test(coordinator) &&
      /val readMillis: Long,/.test(read(join(ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app',
        'morsecode', 'transport', 'lan', 'security', 'pairing', 'PakeSessionCoordinator.kt'))),
  );
  check(
    'socket close runs under a bounded deadline',
    /private fun closeSocket\(socket: Socket\) \{[\s\S]{0,400}?MonotonicSocketDeadline\([\s\S]{0,300}?BOUNDED_CLOSE_DEADLINE_MILLIS/.test(coordinator),
  );
  check(
    'an expired deadline fails the phase rather than reporting success',
    /if \(deadline\.isExpired\(\)\) throw DeadlineExceededException\(\)/.test(coordinator),
  );

  const providerPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'LanPeerDiscoveryProvider.kt',
  );
  const provider = existsSync(providerPath) ? read(providerPath) : '';
  check(
    'the pairing path admits through a lease-owned scope',
    /private val admissionScope = PairingAdmissionScope/.test(provider) &&
      /attemptLimiter = admissionScope,/.test(provider),
  );
  check(
    'no process-global attempt limiter survives across discovery leases',
    !/^private val pairingAttemptLimiter/m.test(provider),
  );
  check(
    'the lease owns the scope and reads the injected monotonic clock, not the platform one',
    /private class LanDiscoveryLease\([\s\S]{0,4000}?private val admissionScope = PairingAdmissionScope\(\s*monotonicMillis = \{ clock\.nowMillis\(\) \},/.test(provider) &&
      /import app\.morsecode\.transport\.lan\.security\.PairingAdmissionScope/.test(provider),
  );
  check(
    'lease stop closes the admission scope exactly once',
    /admissionScope\.close\(\)/.test(provider) &&
      !/admissionScope\.(releaseAllConcurrency|reset)\(\)/.test(provider),
  );
  check(
    'teardown happens before workers are signalled, not after',
    /admissionScope\.close\(\)[\s\S]{0,200}?signalWorkers\(\)/.test(provider),
  );

  const limiterPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecurePairingAttemptLimiter.kt',
  );
  const limiter = existsSync(limiterPath) ? read(limiterPath) : '';
  const outcomes = [
    'AUTHENTICATED', 'AUTHENTICATION_MISMATCH', 'TRANSCRIPT_MISMATCH', 'KEY_CONFIRMATION_FAILURE',
    'ATTEMPT_EXHAUSTED', 'APPROVAL_EXPIRED', 'USER_REJECTION', 'PEER_REJECTION',
    'HANDSHAKE_TIMEOUT', 'PEER_DISCONNECT', 'LOCAL_CANCELLATION', 'LOCAL_FAILURE',
    'RESOURCE_REFUSED',
  ];
  check(
    'the attempt taxonomy names all thirteen documented outcomes',
    /internal enum class PairingAttemptOutcome \{([\s\S]*?)\}/.test(limiter) &&
      outcomes.every((o) => new RegExp(`^\\s+${o},?$`, 'm').test(limiter)),
  );
  check(
    'a failure code is mapped onto the taxonomy by a total when expression',
    /internal fun pairingOutcomeFor\([\s\S]*?else -> PairingAttemptOutcome\.LOCAL_FAILURE/.test(limiter),
  );
  check(
    'only genuine security failures can escalate to identity invalidation',
    /KEY_CONFIRMATION_FAILURE,\s*PairingAttemptOutcome\.ATTEMPT_EXHAUSTED -> recordFailure\(/.test(limiter),
  );
  check(
    'a declined approval applies cooldown but never failure escalation',
    /USER_REJECTION,\s*PairingAttemptOutcome\.PEER_REJECTION,\s*PairingAttemptOutcome\.APPROVAL_EXPIRED -> recordSoftAttempt\(/.test(limiter) &&
      /internal fun recordSoftAttempt[\s\S]{0,500}?nextAllowedAtMillis/.test(limiter) &&
      !/recordSoftAttempt[\s\S]{0,500}?failures\??\.incrementAndGet/.test(limiter),
  );
  check(
    'a local cancellation or local failure costs the peer nothing',
    /LOCAL_CANCELLATION,\s*PairingAttemptOutcome\.LOCAL_FAILURE,\s*PairingAttemptOutcome\.RESOURCE_REFUSED -> Unit/.test(limiter),
  );
  check(
    'a success resets escalation but not the start budget',
    /PairingAttemptOutcome\.AUTHENTICATED -> recordSuccess\(/.test(limiter) &&
      /internal fun recordSuccess[\s\S]{0,400}?failures\?\.set\(0\)/.test(limiter) &&
      !/internal fun recordSuccess[\s\S]{0,400}?starts\?\.set\(0\)/.test(limiter),
  );
  check(
    'lease teardown drops the soft-attempt counter as well',
    /internal fun reset\(\) \{[\s\S]{0,300}?softAttempts\.set\(0\)/.test(limiter),
  );

  const pairingCoordinatorPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanPairingCoordinator.kt',
  );
  const pairingCoordinator = existsSync(pairingCoordinatorPath) ? read(pairingCoordinatorPath) : '';
  check(
    'a closed scope refuses new attempts rather than admitting from dead state',
    /internal fun tryStart\(peerIdentityKey: String, sourceKey: String\): AdmissionDecision \{\s*if \(closed\.get\(\)\) \{\s*return AdmissionDecision\.Refused\(PairingAdmission\.RefusalReason\.SCOPE_CLOSED, 0L\)/.test(limiter),
  );
  check(
    'scope close is idempotent and releases exactly once',
    /override fun close\(\) \{\s*if \(!closed\.compareAndSet\(false, true\)\) return[\s\S]{0,300}?liveGrants\.clear\(\)\s*liveKeys\.clear\(\)\s*limiter\.releaseAllConcurrency\(\)\s*limiter\.reset\(\)/.test(limiter),
  );
  check(
    'concurrency release stays reachable after close so an in-flight finally can settle',
    /internal fun retireGrant\(handle: AdmissionHandle\) \{\s*liveGrants\.remove\(handle\.token\(\)\)\s*liveKeys\.remove\(handle\.token\(\)\)\s*limiter\.releaseConcurrency\(\)/.test(limiter),
  );
  check(
    'the coordinator receives the scope, not a bare limiter',
    /private val attemptLimiter: PairingAdmissionScope\? = null,/.test(pairingCoordinator),
  );

  check(
    'the coordinator reports a classified outcome rather than an undifferentiated failure',
    /granted\.complete\(/.test(pairingCoordinator) &&
      !/recordFailure\(/.test(pairingCoordinator) &&
      !/recordSuccess\(/.test(pairingCoordinator),
  );
  check(
    'every failure path funnels through one classifier',
    /private fun failed\(code: SessionFailureCode\): SecurePairingRunResult\.Failed =\s*failedWith\(code, pairingOutcomeFor\(code\)\)/.test(pairingCoordinator) &&
      /private fun failedWith\(\s*code: SessionFailureCode,\s*classification: PakeRefusalClass,\s*\)/.test(pairingCoordinator) &&
      /PakeRefusalClass\.ATTEMPTS_EXHAUSTED -> PairingAttemptOutcome\.ATTEMPT_EXHAUSTED/.test(pairingCoordinator),
  );
  check(
    'an authenticated result is the only outcome recorded as a success',
    /granted\.complete\(\s*if \(authenticated\) PairingAttemptOutcome\.AUTHENTICATED else attemptOutcome/.test(pairingCoordinator),
  );
}


/**
 * Group 12 -- the production pairing path must use MPS2/MST2, not MPS1/MST1.
 *
 * A codec that production does not call does not remediate M3. These checks exist so the wiring
 * cannot silently regress: replacing a V2 call with the V1 equivalent has to fail here, which is
 * what makes "the coordinator speaks V2" a maintained property rather than a claim.
 */
function verifyProductionWireVersion() {
  const coordinatorPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanPairingCoordinator.kt',
  );
  const coordinator = existsSync(coordinatorPath) ? read(coordinatorPath) : '';
  const enginePath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'ConscryptSecureSessionEngine.kt',
  );
  const engine = existsSync(enginePath) ? read(enginePath) : '';

  check(
    'the production coordinator encodes its hello through the crypto factory',
    /cryptoFactory\.encodePairingHello\(/.test(coordinator),
  );
  check(
    'the real crypto factory encodes that hello with the V2 codec, never V1',
    /override fun encodePairingHello\(offer: PairingOfferV2\): ByteArray =\s*SecurePairingHelloV2Codec\.encodeFrame\(offer\)/.test(engine),
  );
  check(
    'the production coordinator decodes the peer hello with the V2 codec',
    /SecurePairingHelloV2Codec\.decode\(/.test(coordinator),
  );
  check(
    'the production coordinator does not invoke the MPS1 hello encoder',
    !/SecurePairingHelloCodec\.(encode|decode|decodeHeader)\(/.test(coordinator),
  );
  check(
    'the production coordinator does not build an MST1 transcript',
    !/SecurePairingTranscript\.create\(/.test(coordinator),
  );
  check(
    'the selection is derived with the required-suite policy, never by a general select',
    /PairingAuthenticationPolicy\.selectRequired\(initiatorOffer, responderOffer\)/.test(coordinator) &&
      !/\.select\(initiatorOffer/.test(coordinator),
  );
  check(
    'the transcript is built from the V2 offers and the derived selection',
    /SecurePairingTranscriptV2\.build\(initiatorOffer, responderOffer, selection\)/.test(coordinator),
  );
  check(
    'the transcript digest is what the composed path authenticates',
    /transcriptDigest = canonicalDigest/.test(coordinator) &&
      /PakeSessionCoordinator\(/.test(coordinator) &&
      /transcriptDigest = canonicalDigest,/.test(coordinator) &&
      !/PROOF_LABEL/.test(coordinator),
  );
  check(
    'the coordinator no longer derives a record layer or writes a confirmation itself',
    !/SecureRecordLayer\(/.test(coordinator) &&
      !/SecureKeyConfirmationCodec/.test(coordinator) &&
      !/fun deriveRecordLayer\(/.test(coordinator),
  );
  check(
    'a legacy MPS1 peer gets a typed incompatible-format result rather than a retry',
    /LEGACY_MPS1_UNSUPPORTED/.test(coordinator) &&
      /throw LegacySecurityFormatException\(\)/.test(coordinator) &&
      /catch \(_: LegacySecurityFormatException\) \{\s*return failed\(SessionFailureCode\.PROTOCOL_VERSION_UNSUPPORTED\)/.test(coordinator),
  );
  check(
    'the V2 offer encodes the disabled capabilities as explicit zero and false',
    /offeredMaxChunkSizeBytes = 0L/.test(coordinator) && /offeredResumeSupported = false/.test(coordinator),
  );
  check(
    'no payload feature bit is offered in the V2 hello',
    /FEATURES_CONTROL_AND_SECURE: Int = \(1 shl 0\) or \(1 shl 3\)/.test(coordinator) &&
      !/offeredFeaturesMask\s*=\s*[^,]*FILE/.test(coordinator),
  );

  // MPS1 may survive only where it is needed to prove rejection, not in any production path.
  const allowlisted = new Set([
    join(ROOT, 'core-transfer', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core', 'transfer',
      'session', 'SecurePairingCodec.kt'),
  ]);
  const offenders = [];
  for (const module of ['transport-lan', 'core-transfer']) {
    for (const file of kotlinSources(module, 'main')) {
      if (allowlisted.has(file)) continue;
      const text = read(file);
      if (/SecurePairingHelloCodec\.(encode|decode|decodeHeader)\(|SecurePairingTranscript\.create\(/.test(text)) {
        offenders.push(relative(ROOT, file));
      }
    }
  }
  check(
    'MPS1 encoding and MST1 transcript building appear nowhere outside the allowlisted codec',
    offenders.length === 0,
    offenders.join(', '),
  );
}


/**
 * Group 13 -- established-session control I/O must be bounded by a total deadline.
 *
 * `soTimeout` bounds reads only. The write side had no bound at all, so a peer that stopped
 * reading could park a worker for the life of the process. These checks exist because that is
 * invisible in a passing test suite: nothing fails until a hostile or merely stalled peer appears.
 */
function verifyEstablishedSessionDeadlines() {
  const channelPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanControlChannel.kt',
  );
  const channel = existsSync(channelPath) ? read(channelPath) : '';

  check(
    'an authenticated control-record write runs under a total monotonic deadline',
    /label = "control-record-write"[\s\S]{0,220}?budgetMillis = SecureSessionLimits\.CONTROL_RECORD_WRITE_DEADLINE_MILLIS/.test(channel),
  );
  check(
    'the write deadline is armed before the first byte is written',
    /writeDeadline\.arm\(\)[\s\S]{0,200}?socket\.getOutputStream\(\)/.test(channel),
  );
  check(
    'the write deadline is settled exactly once, in a finally block',
    /finally \{\s*writeDeadline\.complete\(\)/.test(channel),
  );
  check(
    'a write released by deadline expiry is reported as a timeout, not a connect failure',
    /val expired = writeDeadline\.isExpired\(\)\s*return@synchronized SecureControlSendResult\.Refused\(\s*terminate\(\s*if \(expired\) SessionFailureCode\.CONTROL_TIMEOUT else SessionFailureCode\.CONTROL_CONNECT_FAILED,\s*if \(expired\) sendTimeoutContext\(type\) else null,/.test(channel),
  );
  check(
    'a write that finished at the instant of expiry is not reported as sent',
    /if \(writeDeadline\.isExpired\(\)\) \{\s*return@synchronized SecureControlSendResult\.Refused\(\s*terminate\(SessionFailureCode\.CONTROL_TIMEOUT, sendTimeoutContext\(type\)\),/.test(channel),
  );
  check(
    'channel teardown closes the socket under a bounded deadline',
    /label = "control-bounded-close"[\s\S]{0,220}?BOUNDED_CLOSE_DEADLINE_MILLIS[\s\S]{0,700}?deadline\.complete\(\)/.test(channel),
  );
  check(
    'control-record reads share one absolute deadline across header and body',
    /val deadline = saturatingAdd\(initialNow, SecureSessionLimits\.CONTROL_RECORD_IO_TIMEOUT_MILLIS\.toLong\(\)\)/.test(channel) &&
      (channel.match(/readExact\(input,[^)]*deadline\)/g) || []).length >= 2,
  );
}


/**
 * Group 14 -- every control record type must share the one deadline-covered I/O path.
 *
 * PING, PONG and SESSION_CLOSE are not separate operations. If a per-type writer or reader were
 * ever added, it would bypass the write deadline and reintroduce the unbounded write this pass
 * removed, while every test covering the generic path kept passing. Counting the stream accessors
 * is what makes that impossible to do quietly.
 */
function verifyControlRecordTypesShareOnePath() {
  const channelPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanControlChannel.kt',
  );
  const channel = existsSync(channelPath) ? read(channelPath) : '';

  check(
    'PING, PONG and SESSION_CLOSE are the complete control record set',
    /val CONTROL_RECORD_TYPES: Set<SecureRecordType> = setOf\(\s*SecureRecordType\.PING,\s*SecureRecordType\.PONG,\s*SecureRecordType\.SESSION_CLOSE,/.test(channel),
  );
  check(
    'no per-record-type send or receive implementation exists to bypass the deadline',
    !/fun send(Ping|Pong|Close|CloseRecord)|fun (ping|pong)\(/.test(channel) &&
      /fun send\(type: SecureRecordType, payload: ByteArray\)/.test(channel) &&
      (channel.match(/fun receive\(\)/g) || []).length === 1,
  );
  check(
    'exactly one write site and one read site exist, so one deadline covers every record type',
    (channel.match(/socket\.getOutputStream\(\)/g) || []).length === 1 &&
      (channel.match(/socket\.getInputStream\(\)/g) || []).length === 1,
  );
  // Index ordering rather than a fixed window: the encode step legitimately sits between the two,
  // and a character budget would silently start passing or failing as that step grows.
  const gateAt = channel.indexOf('if (type !in CONTROL_RECORD_TYPES || payload.isNotEmpty())');
  const armAt = channel.indexOf('writeDeadline.arm()');
  const writeAt = channel.indexOf('socket.getOutputStream()');
  check(
    'the record-type gate runs before the deadline is armed and before any bytes move',
    gateAt > 0 && armAt > gateAt && writeAt > armAt,
  );
  check(
    'SESSION_CLOSE terminates the channel only after its own write has been settled',
    /if \(writeDeadline\.isExpired\(\)\) \{\s*return@synchronized SecureControlSendResult\.Refused\(\s*terminate\(SessionFailureCode\.CONTROL_TIMEOUT, sendTimeoutContext\(type\)\),\s*\)\s*\}\s*if \(type == SecureRecordType\.SESSION_CLOSE\)/.test(channel),
  );
}

/**
 * Group 18 -- typed control-I/O context, and the establishment-capacity bounds it sits beside.
 *
 * `CONTROL_TIMEOUT` alone could not distinguish a PING send deadline from a SESSION_CLOSE receive
 * deadline, because every timeout funnelled through `terminate(code)` and returned
 * `SessionFailure(code)` with nothing else. The structured context fixes that without weakening the
 * high-level code, and these checks pin the two properties that make it safe: the type is UNKNOWN
 * until a valid header reveals it, and the context can carry no peer-controlled material.
 */
function verifyTypedControlIoContext() {
  const channel = read(join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanControlChannel.kt',
  ));
  const contracts = read(join(
    ROOT, 'core-transfer', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core', 'transfer',
    'session', 'SessionContracts.kt',
  ));
  const provider = read(join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'LanPeerDiscoveryProvider.kt',
  ));

  check(
    'the high-level failure code is unchanged and the context is optional',
    /public val code: SessionFailureCode,\s*\/[\s\S]{0,400}?public val controlIoContext: ControlIoFailureContext\? = null,/.test(contracts),
  );
  check(
    'the context carries three closed enumerations and no free-form detail',
    /public data class ControlIoFailureContext\(\s*public val operation: ControlIoOperation,\s*public val phase: ControlIoPhase,\s*public val recordType: ControlIoRecordType,\s*\)/.test(contracts) &&
      // The parameter list itself must stay three enums -- no free-form detail, bytes or identity.
      !/public data class ControlIoFailureContext\(\s*public val operation: ControlIoOperation,\s*public val phase: ControlIoPhase,\s*public val recordType: ControlIoRecordType,\s*public/.test(contracts),
  );
  check(
    'the diagnostic record type is separate from the wire enum, so UNKNOWN is not wire-representable',
    /public enum class ControlIoRecordType \{/.test(contracts) &&
      !/public enum class SecureRecordType\(public val wireId: Int\) \{[\s\S]{0,300}?UNKNOWN/.test(
        read(join(ROOT, 'core-transfer', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core',
          'transfer', 'session', 'SecureRecordLayer.kt'))),
  );
  check(
    'a receive timeout before the header reports UNKNOWN rather than guessing a type',
    /phase = ControlIoPhase\.HEADER,\s*recordType = ControlIoRecordType\.UNKNOWN,/.test(channel),
  );
  check(
    'a receive timeout after a valid header reports the decoded record type',
    /phase = ControlIoPhase\.BODY,\s*recordType = controlIoRecordTypeOf\(header\.type\),/.test(channel),
  );
  check(
    'a read deadline that escapes as an exception still reports the phase it reached',
    /var readPhase = ControlIoPhase\.HEADER\s*var knownType: SecureRecordType\? = null/.test(channel) &&
      /readPhase = ControlIoPhase\.BODY\s*knownType = header\.type/.test(channel) &&
      /catch \(_: SocketTimeoutException\) \{\s*return@synchronized SecureControlReceiveResult\.Refused\(\s*terminate\(\s*SessionFailureCode\.CONTROL_TIMEOUT,\s*ControlIoFailureContext\(\s*operation = ControlIoOperation\.RECEIVE,\s*phase = readPhase,\s*recordType = controlIoRecordTypeOf\(knownType\),/.test(channel),
  );
  mutation(
    'hard-coding HEADER in the escaping-timeline catch fails this check',
    channel,
    (src) => src.replace('phase = readPhase,', 'phase = ControlIoPhase.HEADER,'),
    (src) => /phase = readPhase,\s*recordType = controlIoRecordTypeOf\(knownType\),/.test(src),
  );
  check(
    'a send timeout is attributed to the flush phase and to the type the caller asked for',
    /private fun sendTimeoutContext\(type: SecureRecordType\): ControlIoFailureContext =\s*ControlIoFailureContext\(\s*operation = ControlIoOperation\.SEND,\s*phase = ControlIoPhase\.FLUSH,\s*recordType = controlIoRecordTypeOf\(type\),/.test(channel),
  );
  check(
    'every control timeout still reports CONTROL_TIMEOUT as its high-level code',
    (channel.match(/SessionFailureCode\.CONTROL_TIMEOUT/g) || []).length >= 5 &&
      // Every structured context sits beside a CONTROL_TIMEOUT code, never in place of one.
      (channel.match(/terminate\(\s*SessionFailureCode\.CONTROL_TIMEOUT/g) || []).length >= 4,
  );
  mutation(
    'attributing a header-phase timeout to PING instead of UNKNOWN fails this check',
    channel,
    (src) => src.replaceAll(
      'recordType = ControlIoRecordType.UNKNOWN,',
      'recordType = ControlIoRecordType.PING,',
    ),
    (src) => /phase = ControlIoPhase\.HEADER,\s*recordType = ControlIoRecordType\.UNKNOWN,/.test(src),
  );

  // Establishment capacity stays bounded, and no second scheduler appears beside it.
  check(
    'control establishment capacity remains four active permits and a four-deep bounded queue',
    /const val MAX_CONTROL_SESSIONS: Int = 4/.test(provider) &&
      /const val MAX_PENDING_CONTROL_TASKS: Int = 4/.test(provider) &&
      /private val controlPermits = Semaphore\(MAX_CONTROL_SESSIONS, true\)/.test(provider) &&
      /threads = MAX_CONTROL_SESSIONS,\s*queueCapacity = MAX_PENDING_CONTROL_TASKS,/.test(provider),
  );
  check(
    'the establishment executor is bounded and rejects rather than growing',
    /ArrayBlockingQueue\(queueCapacity\)/.test(provider) &&
      /ThreadPoolExecutor\.AbortPolicy\(\)/.test(provider),
  );
  check(
    'establishment permit release stays exactly-once at every call site',
    (provider.match(/compareAndSet\(false, true\)\) releaseControlCapacity\(\)/g) || []).length >= 3,
  );
  check(
    'no internal control-record worker pool exists beside the establishment executor',
    !/controlRecordExecutor|controlIoExecutor|recordExecutor|operationExecutor/.test(provider) &&
      !/Executors\s*\.\s*new/.test(provider),
  );
  check(
    'the control channel documents that its I/O is blocking and caller-executed',
    /\*\*blocking\*\*/.test(channel) &&
      /must never be called on the Android main thread/.test(channel) &&
      /no internal worker pool here/.test(channel) &&
      /no detached task is ever created/.test(channel),
  );
}

verifyTypedControlIoContext();

/**
 * Group 19 -- control-establishment capacity bounds, and the seam that observes them.
 *
 * The capacity bound is security-relevant in a way that is easy to lose sight of: a leaked permit
 * permanently reduces how many pairings this process can run, and a double release lets a fifth
 * session start on a four-session bound. The observer exists so those counts are visible, and these
 * checks exist so neither the bound nor the seam's safety can regress quietly.
 */
function verifyEstablishmentCapacityAndObserver() {
  const provider = read(join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'LanPeerDiscoveryProvider.kt',
  ));
  const observer = read(join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'ControlEstablishmentObserver.kt',
  ));

  check(
    'establishment capacity stays four active workers and a four-deep bounded queue',
    /const val MAX_CONTROL_SESSIONS: Int = 4/.test(provider) &&
      /const val MAX_PENDING_CONTROL_TASKS: Int = 4/.test(provider),
  );
  check(
    'the queue is a bounded ArrayBlockingQueue, not an unbounded one',
    /ArrayBlockingQueue\(queueCapacity\)/.test(provider) &&
      !/LinkedBlockingQueue\(\)/.test(provider),
  );
  mutation(
    'swapping in an unbounded queue fails this check',
    provider,
    (src) => src.replace('ArrayBlockingQueue(queueCapacity)', 'LinkedBlockingQueue()'),
    (src) => /ArrayBlockingQueue\(queueCapacity\)/.test(src) && !/LinkedBlockingQueue\(\)/.test(src),
  );
  check(
    'rejection is AbortPolicy, so a full queue surfaces rather than running on the caller',
    /ThreadPoolExecutor\.AbortPolicy\(\)/.test(provider) &&
      !/CallerRunsPolicy/.test(provider) &&
      !/DiscardPolicy/.test(provider),
  );
  mutation(
    'switching to CallerRunsPolicy fails this check',
    provider,
    (src) => src.replace('ThreadPoolExecutor.AbortPolicy()', 'ThreadPoolExecutor.CallerRunsPolicy()'),
    (src) => /ThreadPoolExecutor\.AbortPolicy\(\)/.test(src),
  );
  check(
    'the permit semaphore is fair and sized to the same four-session bound',
    /private val controlPermits = Semaphore\(MAX_CONTROL_SESSIONS, true\)/.test(provider),
  );
  check(
    'every permit release stays behind an exactly-once guard',
    (provider.match(/compareAndSet\(false, true\)\) releaseControlCapacity\(\)/g) || []).length >= 3,
  );
  mutation(
    'dropping one exactly-once release guard fails this check',
    provider,
    (src) => src.replace(
      'if (permitReleased.compareAndSet(false, true)) releaseControlCapacity()',
      'releaseControlCapacity()',
    ),
    (src) => (src.match(/compareAndSet\(false, true\)\) releaseControlCapacity\(\)/g) || []).length >= 3,
  );
  check(
    'no internal control-record executor exists beside the establishment executor',
    !/controlRecordExecutor|controlIoExecutor|recordExecutor|operationExecutor/.test(provider) &&
      !/Executors\s*\.\s*new/.test(provider),
  );
  mutation(
    'adding a hidden second executor fails this check',
    provider,
    (src) => src.replace(
      'private fun reserveControlCapacity(): Boolean {',
      'private val hiddenExecutor = Executors.newCachedThreadPool()\n\n        private fun reserveControlCapacity(): Boolean {',
    ),
    (src) => !/Executors\s*\.\s*new/.test(src),
  );

  // The seam itself.
  check(
    'production selects the shared inert observer by default',
    /internal var establishmentObserver: ControlEstablishmentObserver = ControlEstablishmentObserver\.Inert/.test(provider) &&
      /val Inert: ControlEstablishmentObserver = object : ControlEstablishmentObserver \{ \}/.test(observer),
  );
  mutation(
    'selecting a counting observer in production fails this check',
    provider,
    (src) => src.replace(
      'ControlEstablishmentObserver = ControlEstablishmentObserver.Inert',
      'ControlEstablishmentObserver = CountingEstablishmentObserver()',
    ),
    (src) => /establishmentObserver: ControlEstablishmentObserver = ControlEstablishmentObserver\.Inert/.test(src),
  );
  check(
    'every observer call is wrapped so a defect cannot leak a permit or skip teardown',
    /private inline fun observe\(action: \(ControlEstablishmentObserver\) -> Unit\) \{\s*try \{\s*action\(establishmentObserver\)\s*\} catch \(_: RuntimeException\)/.test(provider) &&
      (provider.match(/observe \{ it\./g) || []).length >= 8 &&
      !/establishmentObserver\.on[A-Za-z]+\(\)/.test(provider),
  );
  check(
    'the observer owns no executor, queue, semaphore, socket or timer',
    !/Executor|Semaphore|BlockingQueue|Socket|Timer|Thread/.test(
      observer.replace(/executor|queue|socket|permit and no\s*\n?\s*\* timer/gi, ''),
    ) || !/(Executor|Semaphore|BlockingQueue|Timer)\s*[<(]/.test(observer),
  );
  check(
    'the seam never appears in the public constructor signature',
    !/public class LanPeerDiscoveryProvider\([\s\S]{0,600}?ControlEstablishmentObserver/.test(provider) &&
      /internal var establishmentObserver: ControlEstablishmentObserver/.test(provider),
  );
  check(
    'the observer is internal, so it cannot become a public application API',
    /internal interface ControlEstablishmentObserver/.test(observer) &&
      !/public interface ControlEstablishmentObserver/.test(observer),
  );
  check(
    'every observer method returns Unit, so it cannot steer control flow',
    (observer.match(/fun on[A-Za-z]+\(\)( \{ \})?/g) || []).length >= 4 &&
      !/fun on[A-Za-z]+\(\): (?!Unit)/.test(observer),
  );
}

verifyEstablishmentCapacityAndObserver();


/**
 * Group 15 -- the pairing ownership model after the discovery lease stops.
 *
 * Production uses model A: the lease stays the owner for the whole life of a pairing it admitted.
 * That model is load-bearing in exactly three places, and none of them is obvious from the code
 * around it. If any one regresses, an admitted pairing can outlive the lease that authorised it and
 * still issue authenticated authority -- which no functional test would notice, because the happy
 * path never closes the lease mid-pairing.
 */
function verifyPairingOwnershipAfterLeaseStop() {
  const providerPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'LanPeerDiscoveryProvider.kt',
  );
  const provider = existsSync(providerPath) ? read(providerPath) : '';
  const coordinatorPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanPairingCoordinator.kt',
  );
  const pairingCoordinator = existsSync(coordinatorPath) ? read(coordinatorPath) : '';

  check(
    'lease stop sets its closed flag before it touches admission or operations',
    /private fun stop\(failureCode: SessionFailureCode\?\) \{\s*if \(!closed\.compareAndSet\(false, true\)\) return/.test(provider),
  );
  check(
    'a control session reports cancelled from the lease flag, not only its own',
    /fun isCancelled\(\): Boolean = cancelled\.get\(\) \|\| closedSession\.get\(\) \|\| closed\.get\(\)/.test(provider),
  );
  check(
    'an authenticated result is re-checked against cancellation before the session is installed',
    /is SecurePairingRunResult\.Authenticated -> \{[\s\S]{0,900}?if \(isCancelled\(\) \|\| !installSecureSession\(/.test(provider),
  );
  check(
    'a refused installation closes the record layer and the socket rather than leaking them',
    /authority\.close\(\)\s*closeSocket\(pairingResult\.secureSocket\)\s*completeFailure\(SessionFailureCode\.OPERATION_CANCELLED\)/.test(provider),
  );
  check(
    'lease stop closes in-flight sockets so blocking pairing work cannot survive it',
    /admissionScope\.close\(\)[\s\S]{0,900}?inFlightSockets\.toList\(\)\.forEach \{ closeSocket\(it\) \}/.test(provider),
  );

  check(
    'the coordinator itself refuses authenticated authority once its owning lease has closed',
    /if \(interaction\.isCancelled\(\)\) \{\s*\w+\.authority\.close\(\)\s*return failed\(SessionFailureCode\.OPERATION_CANCELLED\)\s*\}\s*keptSocket = true\s*authenticated = true/.test(pairingCoordinator),
  );
  check(
    'the coordinator checks cancellation before TLS, after the handshake, and before authority leaves',
    (pairingCoordinator.match(/if \(interaction\.isCancelled\(\)\)/g) || []).length >= 3,
  );
  check(
    'the coordinator hands out the authority, never a socket plus a record layer',
    /class Authenticated\(\s*val secureSocket: SSLSocket,\s*val authority: AuthenticatedControlAuthority,/.test(pairingCoordinator) &&
      /authenticatedOutcome\.authority/.test(pairingCoordinator),
  );
  check(
    'exactly one terminal outcome is delivered per control session',
    /private fun finish\([\s\S]{0,200}?if \(!completed\.compareAndSet\(false, true\)\) return/.test(provider),
  );
}

verifyPairingOwnershipAfterLeaseStop();

/**
 * Applies a textual mutation and asserts that `predicate` flips from true to false.
 *
 * A static ordering check that still passes when the ordering it names is broken is worse than no
 * check at all, so every ordering claim below is paired with the edit that would violate it.
 */
function mutation(name, source, mutate, predicate) {
  const before = predicate(source);
  const mutated = mutate(source);
  const changed = mutated !== source;
  const after = predicate(mutated);
  check(name, before && changed && !after);
}

/**
 * Group 16 -- cryptographic work must follow admission, and be directly countable.
 *
 * Socket-stream counters cannot carry this claim on their own: ephemeral key generation,
 * certificate construction and TLS context creation are CPU, entropy and allocation work that never
 * touches a socket. So the ordering is asserted against the crypto factory itself, in source, and
 * each assertion is mutation-tested against the edit that would move the work before admission.
 */
function verifyCryptoWorkFollowsAdmission() {
  const dir = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan', 'security',
  );
  const coordinator = read(join(dir, 'SecureLanPairingCoordinator.kt'));
  const engine = read(join(dir, 'ConscryptSecureSessionEngine.kt'));

  // The five operations, in the order a granted attempt performs them.
  const operations = [
    'generateEphemeralKeyPair',
    'constructEphemeralCertificate',
    'createTlsContext',
    'startHandshake',
    'encodePairingHello',
  ];
  for (const operation of operations) {
    check(`the crypto factory exposes ${operation} as its own operation`,
      new RegExp(`fun ${operation}\\(`).test(engine));
  }

  check(
    'the real factory creates its provider lazily, so no provider work precedes admission',
    /override val securityProvider: Provider by lazy \{ Conscrypt\.newProvider\(\) \}/.test(engine),
  );
  check(
    'production selects the real Conscrypt factory by default, not an injected one',
    /private val cryptoFactory: PairingCryptoFactory = ConscryptPairingCryptoFactory\(random\)/.test(coordinator) &&
      /private val cryptoFactory: PairingCryptoFactory = ConscryptPairingCryptoFactory\(random\)/.test(engine),
  );
  check(
    'the real factory is the only implementation main sources name',
    (coordinator.match(/PairingCryptoFactory/g) || []).length >= 1 &&
      !/object .*: PairingCryptoFactory/.test(coordinator) &&
      /internal class ConscryptPairingCryptoFactory\(/.test(engine),
  );

  // Ordering: in the coordinator, admission is decided before any crypto factory use.
  const admissionAt = coordinator.indexOf('limiter.tryStart(peerIdentityKey, sourceKey)');
  const engineCallAt = coordinator.indexOf('ConscryptSecureSessionEngine(random, cryptoFactory)');
  const helloCallAt = coordinator.indexOf('cryptoFactory.encodePairingHello(offer)');
  check(
    'admission is decided before the coordinator reaches any cryptographic operation',
    admissionAt > 0 && engineCallAt > admissionAt && helloCallAt > engineCallAt,
  );
  mutation(
    'moving the TLS engine construction before the admission decision fails this check',
    coordinator,
    (src) => src.replace(
      'val limiter = attemptLimiter',
      'ConscryptSecureSessionEngine(random, cryptoFactory)\n        val limiter = attemptLimiter',
    ),
    (src) => {
      const a = src.indexOf('limiter.tryStart(peerIdentityKey, sourceKey)');
      const e = src.indexOf('ConscryptSecureSessionEngine(random, cryptoFactory)');
      return a > 0 && e > a;
    },
  );
  mutation(
    'moving MPS2 hello encoding before the admission decision fails this check',
    coordinator,
    (src) => src.replace(
      'var handle: AdmissionHandle? = null',
      'cryptoFactory.encodePairingHello(requireNotNull(localOffer))\n        var handle: AdmissionHandle? = null',
    ),
    (src) => {
      const a = src.indexOf('limiter.tryStart(peerIdentityKey, sourceKey)');
      const h = src.indexOf('cryptoFactory.encodePairingHello(');
      return a > 0 && h > a;
    },
  );

  // Ordering inside the engine: key generation, certificate, context, handshake -- in that order,
  // and the first three before any socket stream is touched.
  const body = engine.slice(engine.indexOf('fun establish('));
  const at = (needle) => body.indexOf(needle);
  const keyAt = at('cryptoFactory.generateEphemeralKeyPair()');
  const certAt = at('cryptoFactory.constructEphemeralCertificate(');
  const ctxAt = at('cryptoFactory.createTlsContext(');
  const hsAt = at('cryptoFactory.startHandshake(');
  const socketFactoryAt = at('context.socketFactory.createSocket(');
  const streamAt = Math.min(
    ...['inputStream', 'outputStream', 'peerCertificates'].map((n) => (at(n) < 0 ? Infinity : at(n))),
  );
  check(
    'the engine performs key generation, certificate construction and TLS context creation in that order',
    keyAt > 0 && keyAt < certAt && certAt < ctxAt,
  );
  check(
    'the first three cryptographic operations happen before any socket stream is read',
    ctxAt < socketFactoryAt && socketFactoryAt < streamAt,
  );
  check(
    'the handshake starts only after the TLS context exists',
    ctxAt < hsAt,
  );
  mutation(
    'constructing the certificate before generating its key pair fails this check',
    engine,
    (src) => src.replace(
      'val keyPair = cryptoFactory.generateEphemeralKeyPair()\n        val certificate = cryptoFactory.constructEphemeralCertificate(keyPair)',
      'val certificate = cryptoFactory.constructEphemeralCertificate(cryptoFactory.generateEphemeralKeyPair())\n        val keyPair = cryptoFactory.generateEphemeralKeyPair()',
    ),
    (src) => {
      const b = src.slice(src.indexOf('fun establish('));
      const k = b.indexOf('val keyPair = cryptoFactory.generateEphemeralKeyPair()');
      const c = b.indexOf('val certificate = cryptoFactory.constructEphemeralCertificate(keyPair)');
      return k > 0 && k < c;
    },
  );
  check(
    'a refusal path performs no cryptographic operation at all',
    /is AdmissionDecision\.Refused -> \{[\s\S]{0,700}?closeSocket\(rawSocket\)\s*return failed\(SessionFailureCode\.SECURE_SESSION_LIMIT_REACHED\)/.test(coordinator) &&
      !/is AdmissionDecision\.Refused -> \{[\s\S]{0,700}?cryptoFactory\./.test(coordinator) &&
      !/is AdmissionDecision\.Refused -> \{[\s\S]{0,700}?ConscryptSecureSessionEngine/.test(coordinator),
  );
}

/**
 * Group 17 -- an admission outcome must be unreachable without a granted handle.
 *
 * `recordOutcome` used to take two arbitrary strings, so any module caller could charge a budget it
 * had never been admitted against, and an untracked key silently recorded nothing. Both failure
 * modes are now unrepresentable: the lease-facing surface has no string-keyed outcome or release at
 * all, and a handle is honoured only when its scope's live-grant table maps its token to that exact
 * object.
 */
function verifyAdmissionHandleIsTheOnlyOutcomeRoute() {
  const dir = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan', 'security',
  );
  const limiter = read(join(dir, 'SecurePairingAttemptLimiter.kt'));
  const coordinator = read(join(dir, 'SecureLanPairingCoordinator.kt'));

  const scopeBody = limiter.slice(limiter.indexOf('internal class PairingAdmissionScope('));
  check(
    'the lease-facing scope exposes no string-keyed outcome path',
    !/internal fun recordOutcome\(\s*peerIdentityKey: String/.test(scopeBody),
  );
  check(
    'the lease-facing scope exposes no handle-less concurrency release',
    !/internal fun releaseConcurrency\(\)/.test(scopeBody),
  );
  check(
    'a handle is honoured only by reference identity, not by its token alone',
    /internal fun isLiveGrant\(handle: AdmissionHandle\): Boolean =\s*!closed\.get\(\) && liveGrants\[handle\.token\(\)\] === handle/.test(limiter),
  );
  check(
    'an outcome cannot be applied to a handle the scope does not recognise',
    /if \(!isLiveGrant\(handle\)\) \{[\s\S]{0,700}?return if \(closed\.get\(\)\) AdmissionReceipt\.ScopeClosed else AdmissionReceipt\.NotAdmitted\s*\}\s*val key = liveKeys\[handle\.token\(\)\] \?: return AdmissionReceipt\.NotAdmitted/.test(limiter),
  );
  check(
    'the outcome path re-verifies reference identity, because tokens collide across scopes',
    /internal fun applyOutcome[\s\S]{0,900}?if \(!isLiveGrant\(handle\)\) \{/.test(limiter),
  );
  check(
    'a teardown that loses the liveness race is reported as scope closure, not as a forged handle',
    /!owner\.isLiveGrant\(this\) ->\s*if \(owner\.isClosed\) AdmissionReceipt\.ScopeClosed else AdmissionReceipt\.NotAdmitted/.test(limiter),
  );
  check(
    'one terminal operation claims the transition before anything else can',
    /internal fun complete\(outcome: PairingAttemptOutcome\): AdmissionReceipt \{\s*if \(!terminal\.compareAndSet\(false, true\)\) return AdmissionReceipt\.AlreadyCompleted/.test(limiter),
  );
  check(
    'the terminal operation records the outcome and retires the grant in one step',
    /else -> owner\.applyOutcome\(this, outcome\)\s*\}\s*terminalOutcome = outcome\s*owner\.retireGrant\(this\)/.test(limiter),
  );
  check(
    'a permit is returned even when the outcome could not be applied, so capacity cannot leak',
    /owner\.isClosed -> AdmissionReceipt\.ScopeClosed\s*\/\/[\s\S]{0,400}?!owner\.isLiveGrant\(this\) ->\s*if \(owner\.isClosed\) AdmissionReceipt\.ScopeClosed else AdmissionReceipt\.NotAdmitted\s*else -> owner\.applyOutcome\(this, outcome\)/.test(limiter) &&
      /owner\.retireGrant\(this\)/.test(limiter),
  );
  check(
    'permit return is deliberately not gated on close, which is what leaked a permit',
    /internal fun retireGrant\(handle: AdmissionHandle\) \{\s*liveGrants\.remove\(handle\.token\(\)\)\s*liveKeys\.remove\(handle\.token\(\)\)\s*limiter\.releaseConcurrency\(\)\s*\}/.test(limiter) &&
      !/internal fun retireGrant\(handle: AdmissionHandle\) \{[^}]*if \(closed\.get\(\)\)/.test(limiter),
  );
  check(
    'cancellation is a terminal outcome, not a separate racing release',
    !/internal fun release\(\): AdmissionReceipt/.test(limiter) &&
      !/internal fun recordOutcome\(outcome: PairingAttemptOutcome\)/.test(limiter),
  );
  check(
    'a closed scope cannot be mutated through a handle',
    /owner\.isClosed -> AdmissionReceipt\.ScopeClosed/.test(limiter) &&
      /internal fun applyOutcome\(handle: AdmissionHandle, outcome: PairingAttemptOutcome\): AdmissionReceipt \{\s*if \(closed\.get\(\)\) return AdmissionReceipt\.ScopeClosed/.test(limiter),
  );
  check(
    'no public constructor can forge a handle',
    /internal class AdmissionHandle internal constructor\(/.test(limiter) &&
      !/class AdmissionHandle\(/.test(limiter),
  );
  check(
    'the coordinator reaches the limiter only through the handle it was granted',
    /var handle: AdmissionHandle\? = null/.test(coordinator) &&
      /granted\.complete\(/.test(coordinator) &&
      !/granted\.release\(\)/.test(coordinator) &&
      !/granted\.recordOutcome\(/.test(coordinator) &&
      !/limiter\?\.recordOutcome\(/.test(coordinator) &&
      !/limiter\?\.releaseConcurrency\(/.test(coordinator),
  );
}

verifyCryptoWorkFollowsAdmission();

verifyAdmissionHandleIsTheOnlyOutcomeRoute();

verifyControlRecordTypesShareOnePath();

verifyEstablishedSessionDeadlines();

verifyProductionWireVersion();

/**
 * Group 20 -- the two capacity models stay distinct, and each refusal keeps its own meaning.
 *
 * Establishment and post-establishment pairing share one executor but not one gate. Establishment
 * takes a permit from a fair `Semaphore(4)` *before* it submits, so a fifth attempt is refused as
 * `CONTROL_CAPACITY_REACHED` and never reaches the queue at all. Pairing submits *without* a permit,
 * which is what lets the bounded queue fill and `AbortPolicy` produce `OPERATION_QUEUE_FULL`. Blurring
 * those two -- letting pairing take an establishment permit, or reporting a semaphore refusal as a
 * queue rejection -- would make the numbers in the capacity tests mean nothing, so both orderings are
 * pinned here and mutation-tested against the edit that would confuse them.
 */
function verifyEstablishmentAndPairingCapacityStayDistinct() {
  const provider = read(join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'LanPeerDiscoveryProvider.kt',
  ));
  const channel = read(join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecureLanControlChannel.kt',
  ));

  const firstReserve = provider.indexOf('if (!reserveControlCapacity())');
  const secondReserve = provider.indexOf('if (!reserveControlCapacity())', firstReserve + 1);
  const firstExecute = provider.indexOf('controlExecutor).execute(');
  const secondExecute = provider.indexOf('controlExecutor).execute(', firstExecute + 1);

  check(
    'both establishment paths acquire the permit before submitting to the executor',
    firstReserve > 0 && secondReserve > firstReserve &&
      firstExecute > firstReserve && secondExecute > secondReserve,
  );
  mutation(
    'dropping the outbound permit gate before submission fails this check',
    provider,
    (src) => src.replace(
      `            if (!reserveControlCapacity()) {
                postControlFailure(listener, SessionFailureCode.CONTROL_CAPACITY_REACHED)
                return NoopOperation
            }
`,
      '',
    ),
    (src) => {
      const a = src.indexOf('if (!reserveControlCapacity())');
      const b = src.indexOf('if (!reserveControlCapacity())', a + 1);
      const x = src.indexOf('controlExecutor).execute(');
      const y = src.indexOf('controlExecutor).execute(', x + 1);
      return a > 0 && b > a && x > a && y > b;
    },
  );

  check(
    'a fifth establishment is reported as CONTROL_CAPACITY_REACHED, never as a queue rejection',
    provider.includes('postControlFailure(listener, SessionFailureCode.CONTROL_CAPACITY_REACHED)'),
  );
  mutation(
    'reporting a semaphore refusal as OPERATION_QUEUE_FULL fails this check',
    provider,
    (src) => src.replace(
      'postControlFailure(listener, SessionFailureCode.CONTROL_CAPACITY_REACHED)',
      'postControlFailure(listener, SessionFailureCode.OPERATION_QUEUE_FULL)',
    ),
    (src) => src.includes(
      'postControlFailure(listener, SessionFailureCode.CONTROL_CAPACITY_REACHED)',
    ),
  );

  const pairingStart = provider.indexOf('private inner class LanSecureSessionOperation(');
  const pairingSubmit = provider.indexOf(
    'completeFailure(SessionFailureCode.OPERATION_QUEUE_FULL)', pairingStart,
  );
  const pairingRegion = pairingStart > 0 && pairingSubmit > pairingStart
    ? provider.slice(pairingStart, pairingSubmit)
    : '';

  check(
    'the pairing path submits without taking an establishment permit',
    pairingRegion.length > 0 &&
      pairingRegion.includes('controlExecutor).execute(work)') &&
      !pairingRegion.includes('reserveControlCapacity'),
  );
  mutation(
    'giving the pairing path an establishment permit fails this check',
    provider,
    (src) => src.replace(
      '                        requireNotNull(controlExecutor).execute(work)',
      '                        reserveControlCapacity()\n' +
        '                        requireNotNull(controlExecutor).execute(work)',
    ),
    (src) => {
      const start = src.indexOf('private inner class LanSecureSessionOperation(');
      const end = src.indexOf('completeFailure(SessionFailureCode.OPERATION_QUEUE_FULL)', start);
      const region = start > 0 && end > start ? src.slice(start, end) : '';
      return region.length > 0 &&
        region.includes('controlExecutor).execute(work)') &&
        !region.includes('reserveControlCapacity');
    },
  );

  check(
    'a rejected establishment returns its permit exactly once before it is dropped',
    provider.includes(`                observe { it.onControlTaskRejected() }
                inFlightSockets.remove(socket)
                closeSocket(socket)
                releaseIncomingPermit(socket)`),
  );
  mutation(
    'dropping the permit release on establishment rejection fails this check',
    provider,
    (src) => src.replace(
      `                observe { it.onControlTaskRejected() }
                inFlightSockets.remove(socket)
                closeSocket(socket)
                releaseIncomingPermit(socket)`,
      `                observe { it.onControlTaskRejected() }
                inFlightSockets.remove(socket)
                closeSocket(socket)`,
    ),
    (src) => src.includes(`                observe { it.onControlTaskRejected() }
                inFlightSockets.remove(socket)
                closeSocket(socket)
                releaseIncomingPermit(socket)`),
  );

  check(
    'control records are caller-executed: the channel submits nothing to any executor',
    !/execute\(/.test(channel) && !/Executor/.test(channel),
  );
}

verifyEstablishmentAndPairingCapacityStayDistinct();

/**
 * Group 21 -- the A3 phase channel and the timeout bounds cannot be removed silently.
 *
 * Both exist because a run went wrong in a way that produced no usable evidence. Run 38460137506
 * hung and was cancelled with nothing to read; the phase markers and the hard process bounds are the
 * answer, and a check that can be deleted without anything noticing is not a check.
 */
function verifyA3PhaseChannelAndTimeoutBounds() {
  const harness = read(join(
    ROOT, 'transport-lan', 'src', 'androidTest', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'ControlEstablishmentCapacityTest.kt',
  ));
  const wrapper = read(join(ROOT, 'ci', 'run-api23-instrumentation.sh'));
  const diagnostics = read(join(ROOT, 'tools', 'ci', 'api23-diagnostics.py'));
  const workflow = read(join(ROOT, '.github', 'workflows', 'android-ci.yml'));

  check(
    'the harness emits phase markers through the dedicated Android log tag',
    harness.includes('const val PHASE_TAG = "MorsecA3Phase"') &&
      harness.includes('Log.i(PHASE_TAG,'),
  );
  mutation(
    'replacing the Android log call with a plain print fails this check',
    harness,
    (src) => src.replace('Log.i(PHASE_TAG,', 'println('),
    (src) => src.includes('Log.i(PHASE_TAG,'),
  );
  mutation(
    'renaming the phase tag fails this check',
    harness,
    (src) => src.replace('const val PHASE_TAG = "MorsecA3Phase"',
      'const val PHASE_TAG = "MorsecA3Phases"'),
    (src) => harness.includes('const val PHASE_TAG = "MorsecA3Phase"') &&
      src.includes('const val PHASE_TAG = "MorsecA3Phase"'),
  );

  check(
    'the harness emits the terminal cleanup marker that proves the channel is alive',
    harness.includes('mark("cleanup_completed"'),
  );
  mutation(
    'dropping the terminal cleanup marker fails this check',
    harness,
    (src) => src.replace('mark("cleanup_completed",', 'unusedCleanupMarker('),
    (src) => src.includes('mark("cleanup_completed"'),
  );

  // The terminal marker must be the last marker the harness emits. Run 38595962958 passed all
  // five tests with a live phase log and still failed the green-run gate, because port_released
  // was emitted after cleanup_completed. Nothing in the local gate caught it, so pin it here.
  // Scoped to the test method, not the file. Helper functions are declared after the method, so a
  // file-order scan reports the last marker a helper happens to emit rather than the last one the
  // test emits -- which is what actually has to be the terminal marker.
  const markers = markersOf(testMethodBody(
    harness, 'establishmentCapacityShutdownAndObserverDefectsOnOneBoundLease',
  ));
  check(
    'the harness ends on the terminal cleanup marker and on nothing after it',
    markers.length > 0 && markers[markers.length - 1] === 'cleanup_completed',
  );
  mutation(
    'emitting a marker after the terminal one fails this check',
    harness,
    (src) => src.replace(
      '            assertTrue("the lease stopped but the fixed control port is still accepting connections",\n                portFree)\n        }',
      '            assertTrue("the lease stopped but the fixed control port is still accepting connections",\n                portFree)\n            mark("port_released", "late_establishment=refused")\n        }',
    ),
    (src) => {
      const names = markersOf(testMethodBody(
        src, 'establishmentCapacityShutdownAndObserverDefectsOnOneBoundLease',
      ));
      return names.length > 0 && names[names.length - 1] === 'cleanup_completed';
    },
  );

  check(
    'every Android log call in the harness uses the phase tag and nothing else',
    (harness.match(/Log\.[a-z]+\(/g) || []).length > 0 &&
      (harness.match(/Log\.[a-z]+\((?!PHASE_TAG)/g) || []).length === 0,
  );

  // The capture must be an actual adb logcat command that writes the phase log, and it must sit
  // outside the failure-only branch. Pinning merely the filename is not enough: a truncation line
  // alone would satisfy it, and deleting the fallback post-run dump -- which is now secondary to
  // the stream -- would leave the check satisfied while the real capture was gone. That exact
  // weakness is what made an earlier version of this mutation pass for the wrong reason.
  const failureBranch = wrapper.indexOf('if [ "$status" -ne 0 ]');
  const streamingCapture = wrapper.indexOf(
    'adb logcat -v time -s "$A3_TAG:I" "$JPAKE_TAG:I" >> ci-logs/62-api23-phases.log 2>&1 &',
  );
  check(
    'the wrapper streams the phase tag from before Gradle starts, on every outcome',
    streamingCapture >= 0 &&
      streamingCapture < wrapper.indexOf('timeout --kill-after=') &&
      streamingCapture < failureBranch &&
      wrapper.includes('kill "$A3_LOGCAT_PID"') &&
      wrapper.indexOf('kill "$A3_LOGCAT_PID"') > wrapper.indexOf('timeout --kill-after='),
  );
  mutation(
    'dropping the streaming phase capture fails this check',
    wrapper,
    (src) => src.replace(
      'adb logcat -v time -s "$A3_TAG:I" "$JPAKE_TAG:I" >> ci-logs/62-api23-phases.log 2>&1 &',
      '',
    ),
    (src) => {
      const at = src.indexOf('adb logcat -v time -s "$A3_TAG:I" "$JPAKE_TAG:I" >> ci-logs/62-api23-phases.log');
      return at >= 0 && at < src.indexOf('timeout --kill-after=') &&
        at < src.indexOf('if [ "$status" -ne 0 ]');
    },
  );

  check(
    'the diagnostic step reads the captured phase log and fails a green run that lacks the marker',
    diagnostics.includes('PHASE_LOG = "ci-logs/62-api23-phases.log"') &&
      diagnostics.includes('TERMINAL_PHASE = "cleanup_completed"') &&
      /if step_passed and not failures:/.test(diagnostics) &&
      diagnostics.includes('MorsecA3Phase'),
  );

  check(
    'the instrumentation command is bounded outside the JVM with TERM then KILL',
    /timeout --kill-after=/.test(wrapper) &&
      wrapper.includes('TIMEOUT_BOUND_SECONDS') &&
      wrapper.includes('exit "$status"'),
  );
  check(
    'the workflow bounds the whole step and never converts a timeout into success',
    workflow.includes('TIMEOUT_BOUND_SECONDS: "900"') &&
      /timeout-minutes: 25/.test(workflow) &&
      workflow.includes('Diagnose API 23 instrumentation results'),
  );
  check(
    'the harness carries its own per-test bound',
    harness.includes('@Test(timeout = TEST_TIMEOUT_MILLIS)') &&
      harness.includes('const val TEST_TIMEOUT_MILLIS = 120_000L'),
  );
  mutation(
    'removing the per-test bound fails this check',
    harness,
    (src) => src.replace('@Test(timeout = TEST_TIMEOUT_MILLIS)', '@Test'),
    (src) => src.includes('@Test(timeout = TEST_TIMEOUT_MILLIS)'),
  );
}

verifyA3PhaseChannelAndTimeoutBounds();

/** Extracts a function body by brace matching, so checks can be scoped to one method. */
function testMethodBody(source, name) {
  const start = source.indexOf(`fun ${name}(`);
  if (start < 0) return '';
  const open = source.indexOf('{', start);
  if (open < 0) return '';
  let depth = 0;
  for (let i = open; i < source.length; i += 1) {
    if (source[i] === '{') depth += 1;
    else if (source[i] === '}') {
      depth -= 1;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  return source.slice(start);
}

/** The phase names a source emits, in order. */
function markersOf(source) {
  return [...source.matchAll(/mark\("([a-z_0-9]+)"/g)].map((m) => m[1]);
}

/**
 * Group 22 -- the pairing-capacity test seam, and the guarantee that it stays a test seam.
 *
 * LanControlSession is a private inner class of the private lease, so without a factory no test can
 * construct a session and the pairing executor's queue bounds could only ever be asserted from
 * their own declaration. The factory is therefore real production code, which is exactly why it has
 * to be pinned: a test seam that can acquire a production caller is a back door into session
 * construction, and nothing else in the build would notice.
 */
function verifyPairingCapacityTestSeamStaysTestOnly() {
  const SEAM = 'createControlSessionForPairingCapacityTest';
  const providerPath = join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'LanPeerDiscoveryProvider.kt',
  );
  const provider = read(providerPath);
  const harness = read(join(
    ROOT, 'transport-lan', 'src', 'androidTest', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'ControlEstablishmentCapacityTest.kt',
  ));

  const limiter = read(join(
    ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport', 'lan',
    'security', 'SecurePairingAttemptLimiter.kt',
  ));

  const productionSources = readdirSync(ROOT)
    .filter((entry) => existsSync(join(ROOT, entry, 'src', 'main', 'kotlin')))
    .flatMap((entry) => kotlinSources(entry, 'main'));

  check(
    'the pairing-capacity session factory is internal and never public',
    provider.includes(`internal fun ${SEAM}(`) && !provider.includes(`public fun ${SEAM}(`),
  );
  mutation(
    'widening the session factory to public fails this check',
    provider,
    (src) => src.replace(`internal fun ${SEAM}(`, `public fun ${SEAM}(`),
    (src) => src.includes(`internal fun ${SEAM}(`) && !src.includes(`public fun ${SEAM}(`),
  );

  check(
    'the session factory releases no establishment permit, because it never took one',
    /createControlSession\(socket, negotiated, role\) \{ \}/.test(provider),
  );
  mutation(
    'letting the factory release an establishment permit fails this check',
    provider,
    (src) => src.replace(
      'createControlSession(socket, negotiated, role) { }',
      'createControlSession(socket, negotiated, role) { releaseControlCapacity() }',
    ),
    (src) => /createControlSession\(socket, negotiated, role\) \{ \}/.test(src),
  );

  check(
    'the session factory rejects a lease that did not come from this provider',
    provider.includes('lease as? LanDiscoveryLease') &&
      provider.includes('throw IllegalArgumentException("lease was not created by this provider")'),
  );
  mutation(
    'silently accepting a foreign lease fails this check',
    provider,
    (src) => src.replace(
      'throw IllegalArgumentException("lease was not created by this provider")',
      'return NoopControlSession',
    ),
    (src) => src.includes('throw IllegalArgumentException("lease was not created by this provider")'),
  );

  check(
    'the lease stays private and the session constructor stays out of the public surface',
    provider.includes('private class LanDiscoveryLease(') &&
      provider.includes('internal fun createControlSession(') &&
      !provider.includes('public fun createControlSession('),
  );
  mutation(
    'making the lease public fails this check',
    provider,
    (src) => src.replace('private class LanDiscoveryLease(', 'internal class LanDiscoveryLease('),
    (src) => src.includes('private class LanDiscoveryLease('),
  );

  const callers = productionSources.filter(
    (path) => path !== providerPath && read(path).includes(SEAM),
  );
  check('no production source outside the provider calls the session factory', callers.length === 0);
  mutation(
    'adding a production call site of the session factory fails this check',
    read(join(ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app', 'morsecode', 'transport',
      'lan', 'security', 'SecureLanControlChannel.kt')),
    (src) => `${src}\n// provider.${SEAM}(lease, socket, negotiated, role)\n`,
    (src) => !src.includes(SEAM),
  );

  check(
    'the pairing fixtures are built through the factory and never fake a discovered peer',
    harness.includes(`provider.${SEAM}(`) &&
      !harness.includes('receiveBeacon') &&
      !harness.includes('ControlConnectionResult.Connected('),
  );

  // The layered design, pinned as three separate numbers. Run 38634422755 measured that pairing
  // work alone cannot fill the executor, because the attempt limiter admits two concurrent pairings
  // and refuses the rest before any socket work, so refused tasks free their workers immediately.
  // Only establishment work, which never touches the pairing limiter, can hold all four workers.
  check(
    'establishment capacity, executor workers and pending slots are each four',
    provider.includes('const val MAX_CONTROL_SESSIONS: Int = 4') &&
      provider.includes('const val MAX_PENDING_CONTROL_TASKS: Int = 4') &&
      provider.includes('threads = MAX_CONTROL_SESSIONS') &&
      provider.includes('queueCapacity = MAX_PENDING_CONTROL_TASKS'),
  );
  check(
    'pairing security concurrency stays at two and the harness mirrors it',
    limiter.includes('val maxConcurrentPairings: Int = 2') &&
      limiter.includes('inFlight.get() >= limits.maxConcurrentPairings') &&
      harness.includes('const val MAX_CONCURRENT_PAIRINGS = 2'),
  );
  mutation(
    'raising the pairing concurrency limit fails this check',
    limiter,
    (src) => src.replace('val maxConcurrentPairings: Int = 2', 'val maxConcurrentPairings: Int = 4'),
    (src) => src.includes('val maxConcurrentPairings: Int = 2'),
  );

  check(
    'the queue-full case is a fifth pairing behind four blocked establishments',
    harness.includes('const val QUEUED_PAIRINGS = 4') &&
      harness.includes('SessionFailureCode.OPERATION_QUEUE_FULL') &&
      /fifth pairing was refused with/.test(harness) &&
      /connecting blocking establishment/.test(harness),
  );
  check(
    'no assertion still requires four active pairings, which production cannot produce',
    !/four active pairing/i.test(harness) &&
      !harness.includes('const val SESSION_TOTAL = 9'),
  );
  check(
    'the harness pins the concurrency cap separately from executor capacity',
    /more than \$MAX_CONCURRENT_PAIRINGS pairings were doing cryptographic work/.test(harness) &&
      /more than \$MAX_CONCURRENT_PAIRINGS queued pairings reached cryptographic work/.test(harness) &&
      harness.includes('SessionFailureCode.SECURE_SESSION_LIMIT_REACHED'),
  );
  check(
    'the harness proves the pairing path holds no establishment permit',
    /the pairing path acquired/.test(harness),
  );
  check(
    'the harness proves capacity recovers with an honest establishment afterwards',
    harness.includes('honest_establishment_recovered') &&
      /the honest establishment was rejected after recovery/.test(harness),
  );
  check(
    'the harness never lets a refused pairing run late or authenticate',
    harness.includes('so it ran after rejection') &&
      /authenticated against a silent peer/.test(harness),
  );
}

/**
 * Group 23 -- hostile secure-session fixtures stay test-only, and approval stays conditional.
 *
 * Item C adds a hostile peer that mutates one field of a real MPS2 hello. Two properties make that
 * safe, and both are pinned here rather than trusted: nothing in a shipped source set can name the
 * hostile factory, and no test approves a pairing without first comparing the two displayed proofs.
 * The second one is not hypothetical -- the previous harness called decide(handle, APPROVE) without
 * ever reading the proof, and asserted equality only afterwards.
 */
function verifyHostileSessionEvidenceStaysTestOnly() {
  const mainRoot = join(ROOT, 'transport-lan', 'src', 'main');
  const testRoot = join(ROOT, 'transport-lan', 'src', 'androidTest');
  const collect = (dir, out = []) => {
    if (!existsSync(dir)) return out;
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) collect(full, out);
      else if (full.endsWith('.kt')) out.push(full);
    }
    return out;
  };
  const mainSources = collect(mainRoot);
  check('hostile fixture scan: transport-lan main sources were read', mainSources.length > 0);

  for (const forbidden of ['HostilePairingCryptoFactory', 'HostileMutation', 'ProofComparisonController']) {
    const offenders = mainSources.filter((f) => read(f).includes(forbidden)).map((f) => f.replace(ROOT, ''));
    check(`hostile fixture ${forbidden} is not referenced by any shipped source`, offenders.length === 0,
      offenders.join(', '));
  }

  const hostilePath = join(testRoot, 'kotlin', 'app', 'morsecode', 'transport', 'lan', 'security',
    'HostileSecureSessionApi23Test.kt');
  check('the hostile peer fixture exists in androidTest only', existsSync(hostilePath));
  const hostile = existsSync(hostilePath) ? read(hostilePath) : '';

  // The hostile factory must delegate real crypto rather than fake it.
  check('the hostile factory delegates to the production Conscrypt factory',
    hostile.includes('ConscryptPairingCryptoFactory(random)'));
  check('the hostile factory still encodes with the production MPS2 codec',
    hostile.includes('SecurePairingHelloV2Codec.encodeFrame('));
  mutation('mutation: a hostile factory that stops delegating real crypto is rejected', hostile,
    (src) => src.replace('ConscryptPairingCryptoFactory(random)', 'FakeCryptoFactory()'),
    (src) => src.includes('ConscryptPairingCryptoFactory(random)'));
  mutation('mutation: a hostile factory that bypasses the production codec is rejected', hostile,
    (src) => src.replace('SecurePairingHelloV2Codec.encodeFrame(alter(offer))', 'handRolledBytes(offer)'),
    (src) => src.includes('SecurePairingHelloV2Codec.encodeFrame('));

  // Production must keep its default factory, so it can never select a hostile one.
  const coordinator = read(join(mainRoot, 'kotlin', 'app', 'morsecode', 'transport', 'lan', 'security',
    'SecureLanPairingCoordinator.kt'));
  check('the production coordinator still defaults to the Conscrypt factory',
    coordinator.includes('ConscryptPairingCryptoFactory(random)'));
  mutation('mutation: a production default other than the Conscrypt factory is rejected', coordinator,
    (src) => src.replace('= ConscryptPairingCryptoFactory(random),', '= InsecureFactory(),'),
    (src) => src.includes('= ConscryptPairingCryptoFactory(random),'));

  // MPS1 must have no encoder call in a shipped source set.
  const mps1Offenders = mainSources.filter((f) => read(f).includes('SecurePairingHelloCodec'))
    .map((f) => f.replace(ROOT, ''));
  check('no shipped source calls the legacy MPS1 encoder', mps1Offenders.length === 0,
    mps1Offenders.join(', '));
  check('the coordinator still fails closed on a legacy MPS1 hello',
    coordinator.includes('LEGACY_MPS1_UNSUPPORTED') &&
      coordinator.includes('LegacySecurityFormatException'));
  mutation('mutation: dropping the typed legacy-format refusal is rejected', coordinator,
    (src) => src.replace('throw LegacySecurityFormatException()', 'throw SecureSessionCryptoException()'),
    (src) => src.includes('throw LegacySecurityFormatException()'));

  // The comparison harness is deleted, not merely unused. Its whole contract was SAS approval, and
  // a live path that cannot generate a proof has nothing for it to compare.
  const controllerPath = join(testRoot, 'kotlin', 'app', 'morsecode', 'transport', 'lan', 'security',
    'ProofComparisonController.kt');
  check('the proof comparison controller is gone from every source set',
    !existsSync(controllerPath) &&
      !mainSources.some((f) => read(f).includes('ProofComparisonController')) &&
      !collect(join(ROOT, 'transport-lan', 'src')).some((f) => read(f).includes('ProofComparisonController')));

  // A prompt may only be answered through the challenge the bridge issued, and the production
  // prompter must answer nothing at all.
  const bridgePath = join(mainRoot, 'kotlin', 'app', 'morsecode', 'transport', 'lan', 'security',
    'pairing', 'EnteredCodePairingBridge.kt');
  const bridge = read(bridgePath);
  check('a challenge answer is bound to the identity of the prompt that issued it',
    bridge.includes('if (prompt.challenge !== challenge) return false') &&
      bridge.includes('if (!challenge.settle()) return false'));
  check('an unanswered prompt expires rather than approving',
    bridge.includes('if (remaining <= 0L) return false') &&
      /if \(!await\(prompt\)\) return null[\s\S]{0,200}?prompt\.decision/.test(bridge) &&
      !/SecureApprovalDecision\.APPROVE/.test(bridge));
  mutation('mutation: answering a structurally equal challenge is rejected', bridge,
    (src) => src.replace('if (prompt.challenge !== challenge) return false', ''),
    (src) => src.includes('if (prompt.challenge !== challenge) return false'));
  const providerPathForPrompter = join(ROOT, 'transport-lan', 'src', 'main', 'kotlin', 'app',
    'morsecode', 'transport', 'lan', 'LanPeerDiscoveryProvider.kt');
  const productionPrompter = existsSync(providerPathForPrompter) ? read(providerPathForPrompter) : '';
  check('the production prompter answers no prompt',
    /object NoInteractionSurfacePrompter : EnteredCodePairingPrompter/.test(productionPrompter) &&
      !/NoInteractionSurfacePrompter[\s\S]{0,2000}?SecureApprovalDecision\.APPROVE/.test(productionPrompter) &&
      !/NoInteractionSurfacePrompter[\s\S]{0,2000}?submitDecision/.test(productionPrompter));

  // The old unconditional harness must stay deleted.
  const allTestSources = collect(join(ROOT, 'transport-lan', 'src'));
  const stale = allTestSources.filter((f) => read(f).includes('TestApprovalInteraction'))
    .map((f) => f.replace(ROOT, ''));
  check('the unconditional TestApprovalInteraction harness is gone', stale.length === 0, stale.join(', '));

  // TLS 1.3 and the Conscrypt requirement are untouched by the hostile work.
  const engine = read(join(mainRoot, 'kotlin', 'app', 'morsecode', 'transport', 'lan', 'security',
    'ConscryptSecureSessionEngine.kt'));
  check('Conscrypt is still required and TLS 1.3 still pinned',
    engine.includes('Conscrypt.newProvider()') && engine.includes('SecurePairingTranscript.TLS_1_3'));
  check('the hostile fixture never installs a provider globally',
    !hostile.includes('Security.insertProviderAt') && !hostile.includes('Security.addProvider'));
  check('the hostile fixture never trusts all certificates',
    !hostile.includes('X509TrustManager') && !hostile.includes('checkServerTrusted'));
}

verifyHostileSessionEvidenceStaysTestOnly();

verifyPairingCapacityTestSeamStaysTestOnly();

console.log('\nPart B capability trust boundary verification');
console.log(checks.join('\n'));
console.log(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed`);
if (failures) process.exit(1);
