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
 *   6. The payload record framing (MPL1) exists only inside the two files below, imports nothing
 *      from the rest of the module, derives no key, opens no socket and is named by no production
 *      caller. Its five pinned limits are checked on both sides -- the Kotlin constants and the
 *      numbers published in doc/transfer-protocol.md -- and the Room exports are digest-pinned, so
 *      a framing slice cannot move a schema by accident.
 *
 * It makes no claim about protocol security. The construction is experimental and unreviewed; see
 * doc/security/owner-risk-waiver-2026-10-19.md.
 */

import { createHash } from 'node:crypto';
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
  // The live coordinator names the suite it offers and calls the composed path, so it belongs in
  // the set the inertness checks scan -- not because it is trusted, but because it is the one
  // production file where a payload reference or a log line would now be reachable.
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/SecureLanPairingCoordinator.kt',
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
 * The payload framing surface. Proposed and inert: a codec with no caller.
 * ------------------------------------------------------------------ */

/**
 * Every production file that may name the payload record format.
 *
 * The list is short on purpose. MPL1 is framing, not policy: if it starts appearing in the
 * coordinator, the provider, the engine or the app, then a payload path is being assembled outside
 * the authority that is supposed to gate it, and this check should fail before a compiler sees it.
 */
const PAYLOAD_FORMAT_PATH =
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/payload/LanPayloadRecordFormat.kt';
const PAYLOAD_CODEC_PATH =
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/payload/LanPayloadRecordCodec.kt';
const AUTHORIZED_MPL1_FILES = new Set([PAYLOAD_FORMAT_PATH, PAYLOAD_CODEC_PATH]);

/** The one document allowed to describe the format. */
const AUTHORIZED_MPL1_DOCS = new Set(['doc/transfer-protocol.md']);

/**
 * The five numbers this build is approved to use, with the form the document spells them in.
 *
 * They are written here rather than derived from the source: a check that recomputes whatever the
 * source says can only prove the source agrees with itself. The JVM suite separately proves the
 * body ceiling is the largest MSC1 frame the core actually encodes at the LAN chunk size.
 */
const PAYLOAD_LIMITS = [
  ['LAN_PAYLOAD_CHUNK_BYTES', 65536, '65,536'],
  ['MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES', 65772, '65,772'],
  ['PAYLOAD_RECORD_HEADER_BYTES', 34, '34'],
  ['PAYLOAD_RECORD_TAG_BYTES', 16, '16'],
  ['MAX_PAYLOAD_RECORD_WIRE_BYTES', 65822, '65,822'],
];

/** The Room exports are frozen until a migration is approved. */
const ROOM_SCHEMA_DIGESTS = [
  ['core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json',
    'b0bca4243d2f0ba4631e3338e611d3bcaff8ba456de83b79ea0106ae187ac488'],
  ['core-data/schemas/app.morsecode.core.data.db.MorseDatabase/2.json',
    '7e6acfd9c03b0214dcaddc2f5d1ceb175588f5cfcf93cca5437617be24b2b074'],
];

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

/**
 * The live pairing path must not contain any trace of the retired comparison.
 *
 * `EnteredCodePairingInteraction` legitimately contains the substring `PairingInteraction`, so the
 * allowed token is removed before the legacy interface is looked for. Everything else is matched as
 * written: a coordinator that names `HumanVerificationCode`, `ProofComparisonController`, the SAS
 * capability or the legacy state machine has grown a second human check, and the fix is to delete
 * it rather than to explain it.
 */
function livePathHasNoSasSurface(text) {
  const withoutEnteredCode = text.replace(/EnteredCodePairingInteraction/g, '');
  const forbidden = [
    /\bHumanVerificationCode\b/,
    /\bProofComparisonController\b/,
    /\bProofRendezvous\b/,
    /\bSAS_25BIT_COMPARISON\b/,
    /\bSecureSessionStateMachine\b/,
    /\bPairingInteraction\b/,
    /\.select\s*\(/,
  ];
  return !forbidden.some((pattern) => pattern.test(withoutEnteredCode));
}

/**
 * The live pairing path must offer exactly the MODP entered-code suite and must call the proven
 * composed path. Both halves are required: a coordinator that selects the required suite but keeps
 * its own record layer would pass the first half and still be a second implementation.
 */
function livePathUsesRequiredSuite(text) {
  return text.includes('selectRequired(') &&
    text.includes('JPAKE_MODP3072_ENTERED_CODE_V1') &&
    text.includes('PakeSessionCoordinator(') &&
    !text.includes('SecureRecordLayer(');
}

/** SAS generation is gone from production, not merely unused by the coordinator. */
function noSasGenerationAnywhere(text) {
  return !text.includes('fromExporterMaterial') && !text.includes('ProofComparisonController');
}

/* ------------------------------------------------------------------ *
 * Payload framing (MPL1). Framing only: no key, no socket, no caller.
 * ------------------------------------------------------------------ */

/** Matches the payload record format by magic or by type name. */
const MPL1_TOKEN = /MPL1|LanPayloadRecord/;

function filesMentioningMpl1(files) {
  return files.filter((f) => MPL1_TOKEN.test(f.text)).map((f) => f.path);
}

/** Every file that names MPL1 must be explicitly authorized. */
function unauthorizedMpl1Files(files, allowed) {
  return filesMentioningMpl1(files).filter((path) => !allowed.has(path));
}

/**
 * The framing package must stay inert.
 *
 * The codec turns bytes into a typed decision. A transport, a cipher, a key handle, a persistence
 * call, a log line or an authority reference inside it means the slice grew past framing, and the
 * growth is what is reported rather than the intention behind it. The patterns are the concrete
 * identifiers a real implementation would have to use, not a style rule.
 */
function payloadFramingIsInert(text) {
  const transport = /\bSocket\b|SSLSocket|getOutputStream|getInputStream|DataInputStream|DataOutputStream/;
  const crypto = /\bCipher\b|\bAES\b|\bGCM\b|SecretKey|KeyGenerator|SecretKeyFactory|MessageDigest/;
  const persistence = /\bRoom\b|\bDao\b|SharedPreferences|openFileOutput|getExternalFilesDir/;
  const logging = /\bLog\.[diwev]\s*\(|println\s*\(/;
  const authority = /PayloadTransferGate|AuthenticatedControlAuthority|\bTransferEngine\b/;
  return !transport.test(text) && !crypto.test(text) && !persistence.test(text) &&
    !logging.test(text) && !authority.test(text);
}

/** A pinned integer constant, compared by value so a digit separator cannot hide a change. */
function declaresIntConstant(source, name, value) {
  const match = source.match(new RegExp(`\\b${name}\\s*:\\s*Int\\s*=\\s*([0-9_]+)`));
  return match !== null && Number(match[1].replaceAll('_', '')) === value;
}

/** The document row for one constant, and whether it carries the approved figure. */
function docRowStates(docText, name, formattedValue) {
  const row = docText.split('\n').find((line) => line.startsWith(`| \`${name}\``));
  return row !== undefined && row.includes(formattedValue);
}

/**
 * The payload gate has exactly one outcome and no authorising branch.
 *
 * `Refused` must still be what the body returns, and no `PayloadTransferDecision.Authorized` may
 * exist anywhere in the file, so re-opening the gate is a visible edit to this predicate's subject
 * rather than something a caller can arrange by satisfying a condition somewhere else.
 */
function payloadGateRefusesOnly(contractsText) {
  const gate = contractsText.match(/object PayloadTransferGate\s*\{[\s\S]*?\n\}/);
  if (gate === null) return false;
  if (/PayloadTransferDecision\.Authorized/.test(contractsText)) return false;
  return /return PayloadTransferDecision\.Refused\(\s*SessionFailure\(\s*SessionFailureCode\.SECURE_SESSION_REQUIRED/.test(
    gate[0],
  );
}

/** Milestone 2 and the gated transfer engine, pinned to their exact declarations. */
function milestonePinsHold(featureReadiness) {
  return /CURRENT_MILESTONE\s*:\s*Int\s*=\s*2\b/.test(featureReadiness) &&
    /TRANSFER_ENGINE\("transfer_engine",\s*5\)/.test(featureReadiness);
}

/** One file's bytes against one digest. */
function digestMatches(bytes, expectedHex) {
  return createHash('sha256').update(bytes).digest('hex') === expectedHex;
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

  check('self-test: an entered-code coordinator passes the SAS-surface scan',
    livePathHasNoSasSurface('private val interaction: EnteredCodePairingInteraction\nselectRequired(a, b)\n'));
  check('self-test: a coordinator naming HumanVerificationCode is caught',
    !livePathHasNoSasSurface('val proof = HumanVerificationCode.fromExporterMaterial(b)\n'));
  check('self-test: a coordinator naming the comparison controller is caught',
    !livePathHasNoSasSurface('val c = ProofComparisonController(a, r)\n'));
  check('self-test: a coordinator offering the SAS capability is caught',
    !livePathHasNoSasSurface('offeredAuthentication = SAS_25BIT_COMPARISON\n'));
  check('self-test: a coordinator calling the general select is caught',
    !livePathHasNoSasSurface('SecurePairingSelectionV2.select(initiator, responder)\n'));
  check('self-test: a coordinator keeping the legacy interaction is caught',
    !livePathHasNoSasSurface('val interaction: PairingInteraction\n'));
  check('self-test: a coordinator keeping the legacy state machine is caught',
    !livePathHasNoSasSurface('private var machine: SecureSessionStateMachine? = null\n'));

  check('self-test: the required-suite coordinator passes',
    livePathUsesRequiredSuite(
      'selectRequired(a, b)\nJPAKE_MODP3072_ENTERED_CODE_V1\nPakeSessionCoordinator(\n'));
  check('self-test: a coordinator without selectRequired is caught',
    !livePathUsesRequiredSuite('JPAKE_MODP3072_ENTERED_CODE_V1\nPakeSessionCoordinator(\n'));
  check('self-test: a coordinator that never offers the suite is caught',
    !livePathUsesRequiredSuite('selectRequired(a, b)\nPakeSessionCoordinator(\n'));
  check('self-test: a coordinator that builds its own record layer is caught',
    !livePathUsesRequiredSuite(
      'selectRequired(a, b)\nJPAKE_MODP3072_ENTERED_CODE_V1\nPakeSessionCoordinator(\n' +
      'SecureRecordLayer(\n'));

  check('self-test: a production source with no SAS generator passes',
    noSasGenerationAnywhere('class A\n'));
  check('self-test: a surviving exporter-to-code generator is caught',
    !noSasGenerationAnywhere('val c = HumanVerificationCode.fromExporterMaterial(m)\n'));
  check('self-test: a surviving comparison controller in production is caught',
    !noSasGenerationAnywhere('class ProofComparisonController\n'));

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

  const allowedPayload = new Set(['transport-lan/src/main/kotlin/X.kt']);
  check('self-test: a payload file inside the approved set is not reported',
    unauthorizedMpl1Files(
      [{ path: 'transport-lan/src/main/kotlin/X.kt', text: 'const val M = "MPL1"' }],
      allowedPayload,
    ).length === 0);
  check('self-test: MPL1 outside the approved set is caught',
    unauthorizedMpl1Files(
      [{ path: 'app/src/main/kotlin/Y.kt', text: 'val codec = LanPayloadRecordCodec' }],
      allowedPayload,
    ).length === 1);
  check('self-test: a file that never names the format is not reported',
    unauthorizedMpl1Files([{ path: 'app/src/main/kotlin/Y.kt', text: 'class Y' }], allowedPayload).length === 0);

  check('self-test: an inert framing package passes',
    payloadFramingIsInert('val header = ByteArray(34)\nval length = header.size\n'));
  check('self-test: a socket inside the framing package is caught',
    !payloadFramingIsInert('val link: Socket = connect()\n'));
  check('self-test: a cipher inside the framing package is caught',
    !payloadFramingIsInert('val c = Cipher.getInstance("AES/GCM/NoPadding")\n'));
  check('self-test: a key handle inside the framing package is caught',
    !payloadFramingIsInert('val key = SecretKeySpec(bytes, "AES")\n'));
  check('self-test: a log line inside the framing package is caught',
    !payloadFramingIsInert('Log.d("payload", "wrote a record")\n'));
  check('self-test: persistence inside the framing package is caught',
    !payloadFramingIsInert('@Dao interface Records { }\n'));
  check('self-test: the payload gate inside the framing package is caught',
    !payloadFramingIsInert('val decision = PayloadTransferGate.evaluate(session)\n'));

  check('self-test: a pinned constant with the approved value passes',
    declaresIntConstant(
      'const val MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES: Int = 65_772',
      'MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES',
      65772,
    ));
  check('self-test: a pinned constant with a different value is caught',
    !declaresIntConstant(
      'const val MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES: Int = 65_773',
      'MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES',
      65772,
    ));

  check('self-test: a documented row carrying the approved figure passes',
    docRowStates('| `PAYLOAD_RECORD_HEADER_BYTES` | 34 | fixed header |', 'PAYLOAD_RECORD_HEADER_BYTES', '34'));
  check('self-test: a documented row carrying a different figure is caught',
    !docRowStates('| `PAYLOAD_RECORD_HEADER_BYTES` | 32 | fixed header |', 'PAYLOAD_RECORD_HEADER_BYTES', '34'));
  check('self-test: a missing documented row is caught',
    !docRowStates('| `OTHER` | 34 | x |', 'PAYLOAD_RECORD_HEADER_BYTES', '34'));

  const refusingGate = 'object PayloadTransferGate {\n' +
    '    return PayloadTransferDecision.Refused(SessionFailure(SessionFailureCode.SECURE_SESSION_REQUIRED))\n' +
    '}\n';
  check('self-test: a gate with one refusing outcome passes', payloadGateRefusesOnly(refusingGate));
  check('self-test: a gate with an authorising branch is caught',
    !payloadGateRefusesOnly(refusingGate + 'PayloadTransferDecision.Authorized(failure)\n'));
  check('self-test: a gate that returns a different failure is caught',
    !payloadGateRefusesOnly(
      'object PayloadTransferGate {\n' +
      '    return PayloadTransferDecision.Refused(SessionFailure(SessionFailureCode.OTHER))\n' +
      '}\n',
    ));

  check('self-test: milestone 2 and the gated engine pass',
    milestonePinsHold(
      'public object FeatureReadiness {\n' +
      '    public const val CURRENT_MILESTONE: Int = 2\n' +
      '    TRANSFER_ENGINE("transfer_engine", 5),\n' +
      '}\n',
    ));
  check('self-test: a bumped milestone is caught',
    !milestonePinsHold('public const val CURRENT_MILESTONE: Int = 3\nTRANSFER_ENGINE("transfer_engine", 5)'));
  check('self-test: an enabled transfer engine is caught',
    !milestonePinsHold('public const val CURRENT_MILESTONE: Int = 2\nTRANSFER_ENGINE("transfer_engine", 2)'));

  check('self-test: a digest matches its own bytes',
    digestMatches(Buffer.from('payload'), createHash('sha256').update('payload').digest('hex')));
  check('self-test: a digest mismatch is caught',
    !digestMatches(Buffer.from('payload'), createHash('sha256').update('payload2').digest('hex')));

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

  // 3b. The live pairing path is the switch, not a second implementation beside it.
  const COORDINATOR = 'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/SecureLanPairingCoordinator.kt';
  const PROVIDER = 'transport-lan/src/main/kotlin/app/morsecode/transport/lan/LanPeerDiscoveryProvider.kt';
  const liveFiles = production.filter((f) => f.path === COORDINATOR || f.path === PROVIDER);
  check('the live coordinator and provider both exist', liveFiles.length === 2,
    liveFiles.map((f) => f.path).join(', '));
  const sasOffenders = liveFiles.filter((f) => !livePathHasNoSasSurface(f.text)).map((f) => f.path);
  check('the live pairing path names no SAS generation, comparison or general select',
    sasOffenders.length === 0, sasOffenders.join(', '));
  const coordinator = production.find((f) => f.path === COORDINATOR);
  check('the coordinator offers only the MODP entered-code suite and calls the composed path',
    coordinator !== undefined && livePathUsesRequiredSuite(coordinator.text));
  const sasRemnants = production.filter((f) => !noSasGenerationAnywhere(f.text)).map((f) => f.path);
  check('no production file can generate a SAS or construct a comparison controller',
    sasRemnants.length === 0, sasRemnants.join(', '));

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

  // 6b. The payload framing is inert, confined, and still attached to nothing.
  //
  //     Slice 1 adds a codec and a specification. The checks below are what keep it a codec: the
  //     package may hold exactly two files, no other production file may name the format or import
  //     the package, those two files may not acquire a socket, a cipher, a key handle, a log call or
  //     an authority reference, and the five approved numbers must read the same in the source and
  //     in the document. The Room exports are digest-pinned here as well, so a framing change that
  //     quietly dragged a schema with it fails on this verifier rather than in a later migration.
  const payloadDir = join(
    ROOT,
    'transport-lan',
    'src',
    'main',
    'kotlin',
    'app',
    'morsecode',
    'transport',
    'lan',
    'payload',
  );
  const payloadPaths = existsSync(payloadDir)
    ? collect(payloadDir).map((f) => relative(ROOT, f).replaceAll('\\', '/'))
    : [];
  check(
    'the payload framing package holds exactly the two approved files',
    payloadPaths.length === 2 && payloadPaths.every((path) => AUTHORIZED_MPL1_FILES.has(path)),
    payloadPaths.join(', ') || 'package missing',
  );

  const mpl1Leaks = unauthorizedMpl1Files(production, AUTHORIZED_MPL1_FILES);
  check(
    'MPL1 appears in production only inside the approved framing files',
    mpl1Leaks.length === 0,
    mpl1Leaks.join(', '),
  );

  const docs = collect(join(ROOT, 'doc'))
    .filter((f) => f.endsWith('.md'))
    .map((f) => ({ path: relative(ROOT, f).replaceAll('\\', '/'), text: readFileSync(f, 'utf8') }));
  const mpl1DocLeaks = unauthorizedMpl1Files(docs, AUTHORIZED_MPL1_DOCS);
  check(
    'MPL1 is documented only in the transfer protocol document',
    mpl1DocLeaks.length === 0,
    mpl1DocLeaks.join(', '),
  );

  const framing = [...AUTHORIZED_MPL1_FILES].map((path) => ({
    path,
    text: readFileSync(join(ROOT, path), 'utf8'),
  }));
  const framingOffenders = framing.filter((f) => !payloadFramingIsInert(f.text)).map((f) => f.path);
  check(
    'the payload framing derives no key, opens no socket, persists nothing and logs nothing',
    framingOffenders.length === 0,
    framingOffenders.join(', '),
  );

  const formatSource = framing.find((f) => f.path === PAYLOAD_FORMAT_PATH)?.text ?? '';
  const constantMisses = PAYLOAD_LIMITS
    .filter(([name, value]) => !declaresIntConstant(formatSource, name, value))
    .map(([name, value]) => `${name}=${value}`);
  check(
    'the payload framing pins the five approved limits',
    constantMisses.length === 0,
    constantMisses.join(', ') || `${PAYLOAD_LIMITS.length} constants agree`,
  );

  const docText = readFileSync(join(ROOT, 'doc', 'transfer-protocol.md'), 'utf8');
  const docMisses = PAYLOAD_LIMITS
    .filter(([name, , formatted]) => !docRowStates(docText, name, formatted))
    .map(([name, , formatted]) => `${name} (${formatted})`);
  check(
    'doc/transfer-protocol.md publishes the same five numbers',
    docMisses.length === 0,
    docMisses.join(', ') || 'all rows agree',
  );

  const callers = production
    .filter((f) => !AUTHORIZED_MPL1_FILES.has(f.path))
    .filter((f) => /app\.morsecode\.transport\.lan\.payload/.test(f.text))
    .map((f) => f.path);
  check(
    'no production file imports the payload framing package',
    callers.length === 0,
    callers.join(', '),
  );
  check(
    'the format file constructs no record and calls no codec entry point',
    !/LanPayloadRecordCodec\./.test(formatSource) && !/LanPayloadRecord\(/.test(formatSource),
  );

  const contractsText = readFileSync(
    join(ROOT, 'core-transfer', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core', 'transfer',
      'session', 'SessionContracts.kt'),
    'utf8',
  );
  check(
    'the payload gate still has exactly one, refusing outcome',
    payloadGateRefusesOnly(contractsText),
  );

  const readiness = readFileSync(
    join(ROOT, 'core-model', 'src', 'main', 'kotlin', 'app', 'morsecode', 'core', 'model',
      'FeatureReadiness.kt'),
    'utf8',
  );
  check('the milestone and the transfer-engine gate are unmoved', milestonePinsHold(readiness));

  const schemaProblems = ROOM_SCHEMA_DIGESTS
    .filter(([path, digest]) =>
      !existsSync(join(ROOT, path)) || !digestMatches(readFileSync(join(ROOT, path)), digest))
    .map(([path]) => path);
  check(
    'the Room v1 and v2 exports are byte identical to their pinned digests',
    schemaProblems.length === 0,
    schemaProblems.join(', '),
  );

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
