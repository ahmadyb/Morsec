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
const PAYLOAD_PACKAGE = 'transport-lan/src/main/kotlin/app/morsecode/transport/lan/payload/';
const PAYLOAD_FORMAT_PATH = `${PAYLOAD_PACKAGE}LanPayloadRecordFormat.kt`;
const PAYLOAD_CODEC_PATH = `${PAYLOAD_PACKAGE}LanPayloadRecordCodec.kt`;
const PAYLOAD_LABELS_PATH = `${PAYLOAD_PACKAGE}LanPayloadExporterLabels.kt`;
const PAYLOAD_SCHEDULE_PATH = `${PAYLOAD_PACKAGE}LanPayloadKeySchedule.kt`;
const PAYLOAD_LAYER_PATH = `${PAYLOAD_PACKAGE}LanPayloadRecordLayer.kt`;

/** Every file the payload package may contain. A sixth is a review, not a refactor. */
const PAYLOAD_PACKAGE_FILES = new Set([
  PAYLOAD_FORMAT_PATH,
  PAYLOAD_CODEC_PATH,
  PAYLOAD_LABELS_PATH,
  PAYLOAD_SCHEDULE_PATH,
  PAYLOAD_LAYER_PATH,
]);

/**
 * The only production files outside the package that may name payload state.
 *
 * Four, and each for one reason. The coordinator derives the schedule; the authority owns and binds
 * it; the control channel holds it dormant; the multiplexer frames it, and is the only file allowed
 * to name both wire formats at once. The discovery provider passes `claimed.payload` without naming
 * the type, so it does not appear here -- which is the shape wanted: the fewer files that can name a
 * capability, the fewer that can claim one.
 */
const PAYLOAD_COORDINATOR_PATH =
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/pairing/PakeSessionCoordinator.kt';
const PAYLOAD_AUTHORITY_PATH =
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/AuthenticatedControlAuthority.kt';
const PAYLOAD_CONTROL_CHANNEL_PATH =
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/SecureLanControlChannel.kt';
const MULTIPLEXER_PATH =
  'transport-lan/src/main/kotlin/app/morsecode/transport/lan/security/LanMultiplexedRecordChannel.kt';
const MULTIPLEXER_TEST_PATH =
  'transport-lan/src/test/kotlin/app/morsecode/transport/lan/security/LanMultiplexedRecordChannelTest.kt';
const MULTIPLEXER_API23_TEST_PATH =
  'transport-lan/src/androidTest/kotlin/app/morsecode/transport/lan/security/LanMultiplexedRecordChannelApi23Test.kt';

const STORAGE_PACKAGE = 'core-storage/src/main/kotlin/app/morsecode/core/storage/transfer/';
const STORAGE_SESSION_PATH = `${STORAGE_PACKAGE}DestinationSession.kt`;
const STORAGE_VERIFIER_PATH = `${STORAGE_PACKAGE}DestinationVerifier.kt`;
const STORAGE_PRIVATE_PARTIAL_PATH = `${STORAGE_PACKAGE}AppPrivatePartial.kt`;
const STORAGE_PENDING_PARTIAL_PATH = `${STORAGE_PACKAGE}MediaStorePendingDestination.kt`;
const STORAGE_CAPACITY_PATH = `${STORAGE_PACKAGE}DestinationCapacity.kt`;
const STORAGE_TYPED_READ_PATH = `${STORAGE_PACKAGE}TypedSourceReader.kt`;
const STORAGE_FINGERPRINT_PATH = `${STORAGE_PACKAGE}SourceFingerprint.kt`;
const STORAGE_RESUME_PATH = `${STORAGE_PACKAGE}SourceResumePolicy.kt`;
const STORAGE_CLASSIFIER_PATH = `${STORAGE_PACKAGE}StorageFailureClassifier.kt`;
const STORAGE_DISCARD_PATH = `${STORAGE_PACKAGE}NonSeekableRepositioner.kt`;
const STORAGE_SOURCE_OPENER_PATH = `${STORAGE_PACKAGE}ProviderSourceOpener.kt`;
const STORAGE_SOURCE_ADAPTER_PATHS = [
  STORAGE_SOURCE_OPENER_PATH,
  `${STORAGE_PACKAGE}FileBackedTransferSource.kt`,
  `${STORAGE_PACKAGE}MediaStoreTransferSource.kt`,
  `${STORAGE_PACKAGE}SafDocumentTransferSource.kt`,
];
const LOW_LEVEL_SOURCE_FILES = new Set([
  `${STORAGE_PACKAGE}TransferSource.kt`,
  `${STORAGE_PACKAGE}ProviderSourceOpener.kt`,
  `${STORAGE_PACKAGE}FileBackedTransferSource.kt`,
  `${STORAGE_PACKAGE}MediaStoreTransferSource.kt`,
  `${STORAGE_PACKAGE}SafDocumentTransferSource.kt`,
  STORAGE_TYPED_READ_PATH,
]);
const STORAGE_BRIDGE_DOC_PATH = 'doc/transfer-storage-bridge.md';
const STORAGE_TEST_PATHS = [
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/DestinationSessionTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/DestinationVerifierTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/MediaStorePendingDestinationTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/MediaStoreSourceTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/SafDocumentSourceTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/FileBackedTransferSourceTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/SourceResumePolicyTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/SourceChangePolicyTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/TypedSourceReaderTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/FilenameAndPathAuditTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/DestinationCapacityTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/InertCompositionTest.kt',
  'core-storage/src/test/kotlin/app/morsecode/core/storage/transfer/StorageBridgeApi23Test.kt',
];
const VERBATIM_URI_POLICY =
  'Content URIs and required file metadata may be persisted only in the\n' +
  'dedicated transfer-state database fields required for restoration.\n' +
  '\n' +
  'They must not appear in logs, crash reports, exception messages,\n' +
  'diagnostic summaries, analytics, or generic key/value metadata.\n' +
  '\n' +
  'File bytes may exist only in the selected source, bounded in-memory\n' +
  'buffers, and controlled partial/final destinations. They must never be\n' +
  'written to logs, crash reports, or generic diagnostic storage.';
const PAYLOAD_CHAIN_FILES = new Set([
  PAYLOAD_COORDINATOR_PATH,
  PAYLOAD_AUTHORITY_PATH,
  PAYLOAD_CONTROL_CHANNEL_PATH,
  MULTIPLEXER_PATH,
]);

/** Every production file that may mention payload state at all. */
const AUTHORIZED_PAYLOAD_PRODUCTION_FILES = new Set([
  ...PAYLOAD_PACKAGE_FILES,
  ...PAYLOAD_CHAIN_FILES,
]);

/** The one document allowed to describe the format. */
const AUTHORIZED_MPL1_DOCS = new Set(['doc/transfer-protocol.md']);

/**
 * The payload exporter labels, as exact strings.
 *
 * Written here rather than parsed for the same reason the limits are: a check that reads whatever
 * the source says can only prove the source agrees with itself. The control labels are here too,
 * because the interesting failure is not a typo in a payload label -- it is a payload label that
 * equals a control one, which would key two layers from one output.
 */
const PAYLOAD_LABELS = [
  'EXPORTER-MORSEC-PAYLOAD-C2S-KEY-V1',
  'EXPORTER-MORSEC-PAYLOAD-C2S-IV-V1',
  'EXPORTER-MORSEC-PAYLOAD-S2C-KEY-V1',
  'EXPORTER-MORSEC-PAYLOAD-S2C-IV-V1',
];
const CONTROL_LABELS = [
  'EXPORTER-MORSEC-C2S-KEY-V1',
  'EXPORTER-MORSEC-C2S-IV-V1',
  'EXPORTER-MORSEC-S2C-KEY-V1',
  'EXPORTER-MORSEC-S2C-IV-V1',
  'EXPORTER-MORSEC-PAIRING-PROOF-V1',
];

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
 * Payload framing and payload state.
 *
 * Since the payload-key slice, the package holds more than framing: a label table, a key schedule
 * and an AEAD record layer. The guards therefore split into three questions rather than one.
 *
 *   1. Where may payload state be named at all? Only in the package and three chain files.
 *   2. Is the framing still inert? The format and codec must stay free of sockets, ciphers, keys,
 *      storage, logs and authority references. The layer is where a cipher legitimately lives, so
 *      it is guarded by its own predicate instead.
 *   3. Can anything use it? Only the coordinator derives material, only the coordinator constructs
 *      a schedule, and nothing in production claims one.
 * ------------------------------------------------------------------ */

/** Matches any payload state by wire magic or by type name. */
const MPL1_TOKEN = /MPL1|LanPayload/;

/** Matches only the wire format, not the capability or the layer type. */
const PAYLOAD_WIRE_TOKEN = /MPL1|LanPayloadRecordCodec|LanPayloadRecordFormat/;

function filesMentioningMpl1(files) {
  return files.filter((f) => MPL1_TOKEN.test(f.text)).map((f) => f.path);
}

/** Every file that names payload state must be explicitly authorized. */
function unauthorizedMpl1Files(files, allowed) {
  return filesMentioningMpl1(files).filter((path) => !allowed.has(path));
}

/**
 * The AEAD layer is dormant: no socket, no persistence, no logging, no gate, no authority.
 *
 * It is allowed to name its own AEAD interface, and nothing else that could carry a byte or a key
 * somewhere this slice did not approve. `\bFile\b` is deliberately absent: `LanPayloadRecordFormat`
 * is a type name, not a file handle, and a pattern that fired on it would be turned off rather than
 * fixed.
 */
function payloadLayerIsDormant(text) {
  const transport = /\bSocket\b|SSLSocket|getOutputStream|getInputStream|DataInputStream|DataOutputStream|java\.io\.File|FileOutputStream/;
  const persistence = /\bRoom\b|\bDao\b|SharedPreferences|openFileOutput|getExternalFilesDir|SQLite/;
  const logging = /\bLog\.[diwev]\s*\(|println\s*\(|System\.out|System\.err/;
  const authority = /PayloadTransferGate|AuthenticatedControlAuthority|\bTransferEngine\b/;
  return !transport.test(text) && !persistence.test(text) && !logging.test(text) &&
    !authority.test(text);
}

/**
 * A label present as an exact string literal.
 *
 * Deliberately not a parser: the question is whether these exact bytes reach the exporter, and a
 * substring test on the quoted literal answers exactly that. The four-label count below catches the
 * other half -- a fifth label dragged in beside them.
 */
function declaresLabel(source, label) {
  return source.includes(`"${label}"`);
}

/** How many payload exporter labels a source declares, right or wrong. */
function countPayloadLabelLiterals(source) {
  return (source.match(/EXPORTER-MORSEC-PAYLOAD-[A-Z0-9-]+/g) ?? []).length;
}

/** True when two label sets share a member. */
function labelSetsCollide(left, right) {
  return left.some((label) => right.includes(label));
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
  // Int or Long: the record ceiling is a Long because sequences are, and a parser that only knew
  // `: Int` would silently stop checking the one bound that keeps a nonce space from being spent.
  const match = source.match(new RegExp(`\\b${name}\\s*:\\s*(?:Int|Long)\\s*=\\s*([0-9_]+)`));
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
  const code = kotlinWithoutComments(gate[0]);
  if (/PayloadTransferDecision\.Authorized/.test(kotlinWithoutComments(contractsText))) return false;
  if (/\b(?:if|when|else)\b/.test(code) || (code.match(/\breturn\b/g) ?? []).length !== 1) return false;
  return /return PayloadTransferDecision\.Refused\(\s*SessionFailure\(\s*SessionFailureCode\.SECURE_SESSION_REQUIRED/.test(code);
}

/** Pin the milestone, the engine's delivery milestone and the actual readiness comparison. */
function milestonePinsHold(featureReadiness) {
  const code = kotlinWithoutComments(featureReadiness);
  return /CURRENT_MILESTONE\s*:\s*Int\s*=\s*2\b/.test(code) &&
    /TRANSFER_ENGINE\("transfer_engine",\s*5\)/.test(code) &&
    /fun\s+isAvailable\s*\(\s*area\s*:\s*FeatureArea\s*\)\s*:\s*Boolean\s*=\s*area\.deliveredInMilestone\s*<=\s*CURRENT_MILESTONE[ \t]*$/m.test(code);
}

/** One file's bytes against one digest. */
function digestMatches(bytes, expectedHex) {
  return createHash('sha256').update(bytes).digest('hex') === expectedHex;
}

/* ------------------------------------------------------------------ *
 * Multiplexer predicates.
 *
 * Each answers one structural question about the single file allowed to
 * carry both wire formats. They are deliberately narrow: they look for
 * the shape that makes the slice safe, not for style. A rewrite that
 * keeps the property keeps the check; one that drops it does not.
 * ------------------------------------------------------------------ */

/** The file dispatches on both magics, one arm each, with no third path. */
function multiplexerDispatchesOnBothMagics(text) {
  return /matches\(prefix, MSR1_MAGIC\)/.test(text) &&
    /matches\(prefix, LanPayloadRecordFormat\.magicBytes\(\)\)/.test(text);
}

/** One receive entry point, no competing control or payload reader, no forward scan. */
function multiplexerHasSingleReader(text) {
  const entries = (text.match(/fun receiveNext\s*\(/g) ?? []).length;
  const competing = /fun receiveControl\s*\(|fun receivePayload\s*\(/.test(text);
  const scan = /indexOf\(|resync\s*\(|findMagic|skipToMagic/.test(text);
  return entries === 1 && !competing && !scan;
}

/** Exactly four bytes are consumed before the format is chosen. */
function multiplexerReadsFourBytePrefix(text) {
  return /const val PREFIX_BYTES: Int = 4\b/.test(text) &&
    /val prefix = ByteArray\(PREFIX_BYTES\)/.test(text);
}

/** One write call per record, under the one arbiter, released in a finally block. */
function multiplexerWritesWholeRecordsUnderOneLock(text) {
  const writes = (text.match(/output\.write\(/g) ?? []).length;
  return writes === 1 &&
    /output\.flush\(\)/.test(text) &&
    /releaseWriter\(\)/.test(text) &&
    /acquireWriter\(control\)/.test(text) &&
    /writeLock\.lock\(\)/.test(text) &&
    /writeLock\.unlock\(\)/.test(text);
}

/** A sealed record is written once and never re-offered. */
function multiplexerNeverRetriesAfterSeal(text) {
  const writes = (text.match(/output\.write\(/g) ?? []).length;
  return writes === 1 && !/fun retry|\.retry\(|retryWrite/.test(text);
}

/** No detached reader or writer: the class owns the socket, not a thread. */
function multiplexerStartsNoThread(text) {
  return !/\bThread\s*[({]|Executors\.|CoroutineScope|\.launch\s*\(|newSingleThreadExecutor/.test(text);
}

/** Control priority is bounded, so continuous control traffic cannot starve payload. */
function multiplexerHasBoundedControlPriority(text) {
  return /controlWaiting/.test(text) &&
    /consecutiveControlWrites/.test(text) &&
    /const val MAX_CONSECUTIVE_CONTROL_WRITES: Int = 32\b/.test(text);
}

/** Payload crosses the boundary only as a TransferFrame, never as raw bytes. */
function multiplexerAcceptsTypedFramesOnly(text) {
  return /fun sendPayloadFrame\(frame: TransferFrame\)/.test(text) &&
    /class Payload\(val frame: TransferFrame\)/.test(text) &&
    !/fun sendPayload\s*\(\s*\w+\s*:\s*ByteArray/.test(text) &&
    !/sendPayloadBytes/.test(text);
}

/** Exactly one MSC1 frame is decoded out of one plaintext; trailing bytes are refused. */
function multiplexerDecodesExactlyOneMsc1(text) {
  return /FrameCodec\.decodeSingle\(/.test(text) && !/FrameCodec\.decode\(/.test(text);
}

/** The only diagnostic surface is a redacted toString. */
function multiplexerDiagnosticsAreRedacted(text) {
  return /override fun toString\(\): String =/.test(text) && /\[redacted\]/.test(text) &&
    !/toString\(\).*sessionId/.test(text);
}

/** No logging, storage, file or URI API in the multiplexer. */
function multiplexerTouchesNoStorageOrLogging(text) {
  return !/\bLog\.[diwev]\s*\(|println\s*\(|SharedPreferences|\bRoom\b|\bDao\b|java\.io\.File|android\.net\.Uri/.test(text);
}

/** A construction site is any file other than the declaration's own file. */
function constructsMultiplexer(path, text) {
  return path !== MULTIPLEXER_PATH && /LanMultiplexedRecordChannel\s*\(/.test(text);
}

/** Only the transport module may name the multiplexer at all. */
function outsideTransportNamesMultiplexer(path, text) {
  return !path.startsWith('transport-lan/') &&
    /LanMultiplexedRecordChannel|LanMultiplexedReceive|LanMultiplexedSend/.test(text);
}


/* ------------------------------------------------------------------ *
 * Storage bridge predicates (slice 3)
 * ------------------------------------------------------------------ */

/**
 * A storage port must not know that a transport exists.
 *
 * The tokens are specific on purpose. "payload" alone appears in ordinary
 * storage prose; the framing magics, the cipher suite, the multiplexer and the
 * payload authority do not.
 */
const STORAGE_TRANSPORT_TOKENS =
  /MPL1|MSR1|LanMultiplexedRecordChannel|LanPayload|PayloadRecordLayer|java[.]net[.]Socket|javax[.]net[.]ssl|SSLSocket|[EJ]-?PAKE|TransferEngine|PayloadAuthority/i;

/** Comments may explain what the port avoids; only code can violate the rule. */
function stripComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

/* Remove Kotlin comments without mistaking // inside a URI string for a comment. */
function kotlinWithoutComments(text) {
  let result = '';
  let i = 0;
  while (i < text.length) {
    if (text.startsWith('//', i)) {
      const end = text.indexOf('\n', i + 2);
      if (end < 0) break;
      result += '\n';
      i = end + 1;
    } else if (text.startsWith('/*', i)) {
      let depth = 1;
      i += 2;
      while (i < text.length && depth > 0) {
        if (text.startsWith('/*', i)) { depth += 1; i += 2; }
        else if (text.startsWith('*/', i)) { depth -= 1; i += 2; }
        else { if (text[i] === '\n') result += '\n'; i += 1; }
      }
    } else if (text.startsWith('"""', i)) {
      const end = text.indexOf('"""', i + 3);
      const next = end < 0 ? text.length : end + 3;
      result += text.slice(i, next);
      i = next;
    } else if (text[i] === '"' || text[i] === "'") {
      const quote = text[i];
      result += text[i++];
      while (i < text.length) {
        const char = text[i++];
        result += char;
        if (char === '\\' && i < text.length) result += text[i++];
        else if (char === quote) break;
      }
    } else {
      result += text[i++];
    }
  }
  return result;
}

/** A small balanced-block extractor used only on the pinned Kotlin function shapes below. */
function kotlinFunctionBody(text, marker) {
  const code = kotlinWithoutComments(text);
  const start = code.indexOf(marker);
  if (start < 0) return null;
  const open = code.indexOf('{', start + marker.length);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < code.length; i += 1) {
    if (code[i] === '{') depth += 1;
    else if (code[i] === '}' && --depth === 0) return code.slice(open + 1, i);
  }
  return null;
}

/** Extract a function body even when a default argument contains its own lambda braces. */
function kotlinCallableBody(text, marker) {
  const code = kotlinWithoutComments(text);
  const start = code.indexOf(marker);
  if (start < 0) return null;
  const args = code.indexOf('(', start);
  if (args < 0) return null;
  let parentheses = 0;
  let endArgs = -1;
  for (let i = args; i < code.length; i += 1) {
    if (code[i] === '(') parentheses += 1;
    else if (code[i] === ')' && --parentheses === 0) { endArgs = i; break; }
  }
  if (endArgs < 0) return null;
  const open = code.indexOf('{', endArgs);
  if (open < 0) return null;
  let braces = 0;
  for (let i = open; i < code.length; i += 1) {
    if (code[i] === '{') braces += 1;
    else if (code[i] === '}' && --braces === 0) return code.slice(open + 1, i);
  }
  return null;
}

/** The low-level source APIs may be used only by the existing adapters and the typed wrapper. */
function runtimeBypassesTypedSource(path, text) {
  if (LOW_LEVEL_SOURCE_FILES.has(path)) return false;
  const code = kotlinWithoutComments(text);
  const rawTypes = /\b(?:SourceHandle|SourceOpenResult|ProviderOpenResult|ChannelSourceHandle|StreamSourceHandle)\b/;
  const rawOpen = /\b(?!TypedSourceReader\b)[A-Za-z_]\w*\s*\.\s*(?:openAt|openAtZero)\s*\(/;
  const rawHandleRead = /\.\s*handle\s*\.\s*read\s*\(|::\s*(?:openAt|openAtZero)\b/;
  return rawTypes.test(code) || rawOpen.test(code) || rawHandleRead.test(code);
}

/** All comparable mismatches must precede missing-metadata and strong-match returns. */
function sourcePolicyRejectsEveryMismatch(text) {
  const body = kotlinFunctionBody(text, 'public fun compare(');
  if (body === null) return false;
  const required = [
    /if\s*\(\s*storedId\s*!=\s*null\s*&&\s*currentId\s*!=\s*null\s*&&\s*storedId\s*!=\s*currentId\s*\)\s*\{\s*return\s+FingerprintOutcome\.DEFINITE_CHANGE\s*\}/,
    /if\s*\(\s*storedSize\s*!=\s*null\s*&&\s*currentSize\s*!=\s*null\s*&&\s*storedSize\s*!=\s*currentSize\s*\)\s*\{\s*return\s+FingerprintOutcome\.DEFINITE_CHANGE\s*\}/,
    /if\s*\(\s*storedModified\s*!=\s*null\s*&&\s*currentModified\s*!=\s*null\s*&&\s*storedModified\s*!=\s*currentModified\s*\)\s*\{\s*return\s+FingerprintOutcome\.DEFINITE_CHANGE\s*\}/,
  ];
  const positions = required.map((pattern) => body.search(pattern));
  const missing = body.indexOf('FingerprintOutcome.MISSING_METADATA');
  const strong = body.indexOf('FingerprintOutcome.STRONG_MATCH');
  const weak = body.indexOf('FingerprintOutcome.WEAK_MATCH');
  return positions.every((position) => position >= 0) &&
    positions[0] < positions[1] && positions[1] < positions[2] &&
    positions[2] < missing && missing < strong && positions[2] < weak;
}

/** A ready resume must retain the expected digest and use it in the final comparison. */
function resumeRequiresExpectedDigest(text) {
  const code = kotlinWithoutComments(text);
  const body = kotlinFunctionBody(text, 'public fun authorize(');
  if (body === null) return false;
  return /private\s+val\s+expectedSha256\s*:\s*Sha256Digest\b/.test(code) &&
    /private\s+val\s+storedFingerprint\s*:\s*SourceFingerprint\b/.test(code) &&
    /SourceResumePolicy\.authorize\(storedFingerprint,\s*current,\s*expectedSha256\)/.test(code) &&
    /session\.verify\(\s*expected\s*=\s*expectedSha256\b/.test(code) &&
    /FingerprintOutcome\.DEFINITE_CHANGE\s*->\s*SourceResumeDecision\.Refused\([\s\S]*?source_changed_restart_required/.test(body) &&
    /FingerprintOutcome\.MISSING_METADATA/.test(body) &&
    /if\s*\(\s*expectedSha256\s*==\s*null\s*\)\s*\{\s*SourceResumeDecision\.Refused\([\s\S]*?expected_sha256_required[\s\S]*?\}\s*else\s*\{\s*SourceResumeDecision\.Ready\(outcome,\s*stored,\s*expectedSha256\)\s*\}/.test(body) &&
    /current\.error\.category\s*==\s*TransferStorageErrorCategory\.CANCELLED[\s\S]*?TransferStorageError\.Cancelled/.test(body);
}

/** A resumed destination retains one digest from open through verification and publication. */
function resumedDestinationRequiresRetainedDigest(text) {
  const code = kotlinWithoutComments(text);
  const open = kotlinCallableBody(text, 'public fun open(');
  const verify = kotlinCallableBody(text, 'public fun verify(');
  const commit = kotlinCallableBody(text, 'public fun commit(');
  if (open === null || verify === null || commit === null) return false;
  const openGuard = open.search(/if\s*\(\s*confirmed\s*>\s*0L\s*&&\s*retainedExpectedSha256\s*==\s*null\s*\)\s*\{\s*return\s+DestinationOpen\.Refused\([\s\S]*?expected_sha256_required/);
  const opened = open.indexOf('return DestinationOpen.Opened(');
  const nullGuard = verify.search(/if\s*\(\s*retained\s*!=\s*null\s*&&\s*expected\s*==\s*null\s*\)\s*\{\s*return\s+SessionVerification\.Refused\([\s\S]*?resume_digest_required/);
  const changedGuard = verify.search(/if\s*\(\s*retained\s*!=\s*null\s*&&\s*!retained\.contentEquals\(expected\)\s*\)\s*\{\s*return\s+SessionVerification\.Refused\([\s\S]*?resume_digest_changed/);
  const verification = verify.search(/DestinationVerifier\.verify\(source,\s*frontier,\s*retained\s*\?:\s*expected,/);
  const withoutExpected = verify.search(/is\s+VerifyResult\.VerifiedWithoutExpected\s*->\s*\{\s*if\s*\(\s*retained\s*!=\s*null\s*\)\s*\{\s*return\s+SessionVerification\.Refused/);
  const commitGuard = commit.search(/if\s*\(\s*retained\s*!=\s*null\s*&&\s*!retained\.contentEquals\(digest\)\s*\)\s*\{\s*return\s+CommitResult\.Refused\([\s\S]*?resume_digest_not_verified/);
  const publish = commit.indexOf('target.publish(digest)');
  return /private\s+val\s+retainedExpectedSha256\s*:\s*Sha256Digest\?/.test(code) &&
    openGuard >= 0 && opened > openGuard &&
    /retainedExpectedSha256\s*=\s*retainedExpectedSha256/.test(open.slice(opened)) &&
    /val\s+retained\s*=\s*retainedExpectedSha256/.test(verify) &&
    nullGuard >= 0 && changedGuard > nullGuard && verification > changedGuard && withoutExpected > verification &&
    /val\s+retained\s*=\s*retainedExpectedSha256/.test(commit) && commitGuard >= 0 && publish > commitGuard;
}

/** Provider exception text may be consulted by the classifier, but never passed into a failure. */
function typedReadDoesNotLeakProviderText(readerText, classifierText) {
  const reader = kotlinWithoutComments(readerText);
  const classifier = kotlinWithoutComments(classifierText);
  const start = classifier.indexOf('fun classify(');
  const end = classifier.indexOf('private inline fun walk(', start);
  if (start < 0 || end < 0) return false;
  const failureConstruction = reader + classifier.slice(start, end);
  const rawText = /\.\s*(?:message|localizedMessage|stackTrace)\b|\bdiagnostic\s*=|\b(?:error|exception|cause|thrown|e)\s*\.\s*toString\s*\(|\$\{?\s*(?:error|exception|cause|thrown|e)\b/;
  return !rawText.test(failureConstruction) &&
    /StorageFailureClassifier\.classifyOrProviderFailure\(error,\s*"read",\s*"read"\)/.test(reader) &&
    /redactOpenFailure\(opened\.error\)/.test(reader);
}

/** Legacy open and fingerprint adapters must not put raw provider text into domain values either. */
function sourceAdaptersDoNotLeakProviderText(texts) {
  const rawText = /\.\s*(?:message|localizedMessage|stackTrace)\b|\b(?:error|exception|cause|thrown|e)\s*\.\s*toString\s*\(|\$\{?\s*(?:error|exception|cause|thrown|e)\b/;
  return texts.length === STORAGE_SOURCE_ADAPTER_PATHS.length &&
    texts.every((text) => !rawText.test(kotlinWithoutComments(text)));
}

/** Only the classifier may inspect exception text, solely to classify a storage-full signal. */
function storageDomainDoesNotLeakProviderText(files) {
  const rawText = /\.\s*(?:message|localizedMessage|stackTrace)\b|\b(?:error|exception|cause|thrown|e)\s*\.\s*toString\s*\(|\$\{?\s*(?:error|exception|cause|thrown|e)\b/;
  // These pre-existing outcome renderers interpolate a typed TransferStorageError, whose final
  // toString omits diagnostics. Permit only the exact literals paired with those typed declarations;
  // an interpolation of an exception anywhere else, including in the same file, remains forbidden.
  const typedRenderers = [
    {
      path: `${STORAGE_PACKAGE}SafCommitCheckpoint.kt`,
      declaration: /public\s+data\s+class\s+ReconciliationRequired\s*\(\s*public\s+val\s+checkpoint:\s*SafCommitCheckpoint,\s*public\s+val\s+error:\s*TransferStorageError,/,
      literal: '"SafCommitRecoveryOutcome.ReconciliationRequired(phase=${checkpoint.phase.id}, error=$error, "',
    },
    {
      path: `${STORAGE_PACKAGE}SafCommitCoordinator.kt`,
      declaration: /public\s+data\s+class\s+Failed\s*\(\s*public\s+val\s+record:\s*SafCommitRecord,\s*public\s+val\s+error:\s*TransferStorageError,/,
      literal: '"SafCommitOutcome.Failed(state=${record.state.id}, error=$error)"',
    },
    {
      path: `${STORAGE_PACKAGE}SafCommitCoordinator.kt`,
      declaration: /public\s+data\s+class\s+ReconciliationRequired\s*\(\s*public\s+val\s+record:\s*SafCommitRecord,\s*public\s+val\s+error:\s*TransferStorageError,/,
      literal: '"SafCommitOutcome.ReconciliationRequired(state=${record.state.id}, error=$error, "',
    },
  ];
  return files.filter((file) => file.path !== STORAGE_CLASSIFIER_PATH).every((file) => {
    let code = kotlinWithoutComments(file.text);
    for (const renderer of typedRenderers.filter((item) => item.path === file.path)) {
      if (!renderer.declaration.test(code) || !code.includes(renderer.literal)) return false;
      code = code.replace(renderer.literal, '"[typed storage error]"');
    }
    return !rawText.test(code);
  });
}

/** Real destination opening, writes and verification must classify unexpected provider exceptions. */
function destinationFailuresAreClassified(sessionText, verifierText, privateText, pendingText) {
  const write = kotlinCallableBody(sessionText, 'public fun writeAt(');
  const verification = kotlinCallableBody(verifierText, 'public fun verify(');
  const create = kotlinCallableBody(pendingText, 'public fun create(');
  const openDescriptor = kotlinCallableBody(pendingText, 'private fun openDescriptor(');
  const openWrite = kotlinCallableBody(pendingText, 'public fun openForWrite(');
  const openRead = kotlinCallableBody(pendingText, 'public fun openForRead(');
  if ([write, verification, create, openDescriptor, openWrite, openRead].some((body) => body === null)) return false;
  const sharedWrite = /WriteOutcome\.Failed\(StorageFailureClassifier\.classifyOrProviderFailure\(e,\s*"write",\s*"write"\)\)/;
  const createCatch = /catch\s*\(e\s*:\s*Exception\)\s*\{\s*PendingCreation\.Refused\(\s*StorageFailureClassifier\.classify\(e,\s*"create",\s*"write"\)\s*\?:\s*TransferStorageError\.ProviderFailure\("media_store"\)/;
  const descriptorCatch = /catch\s*\(e\s*:\s*Exception\)\s*\{\s*DescriptorOpen\.Refused\(\s*StorageFailureClassifier\.classify\(e,\s*"open",\s*if\s*\(mode\s*==\s*READ_MODE\)\s*"read"\s*else\s*"write"\)\s*\?:\s*TransferStorageError\.ProviderFailure\("media_store"\)/;
  const writeOpenCatch = /catch\s*\(e\s*:\s*Exception\)\s*\{\s*runCatching\s*\{\s*descriptor\.close\(\)\s*\}\s*PendingOpen\.Refused\(StorageFailureClassifier\.classifyOrProviderFailure\(e,\s*"open",\s*"write"\)\)/;
  const readOpenCatch = /catch\s*\(e\s*:\s*Exception\)\s*\{\s*runCatching\s*\{\s*descriptor\.close\(\)\s*\}\s*PendingReadOpen\.Refused\(StorageFailureClassifier\.classifyOrProviderFailure\(e,\s*"read",\s*"read"\)\)/;
  return /catch\s*\(error\s*:\s*Exception\)\s*\{\s*return\s+DestinationWrite\.Failed\(StorageFailureClassifier\.classifyOrProviderFailure\(error,\s*"write",\s*"write"\),\s*frontier\)/.test(write) &&
    /catch\s*\(error\s*:\s*Exception\)\s*\{\s*return\s+DestinationWrite\.Failed\(StorageFailureClassifier\.classifyOrProviderFailure\(error,\s*"sync",\s*"write"\),\s*frontier\)/.test(write) &&
    /catch\s*\(error\s*:\s*Exception\)\s*\{\s*return\s+VerifyResult\.Failed\(StorageFailureClassifier\.classifyOrProviderFailure\(error,\s*"read",\s*"read"\)\)/.test(verification) &&
    /count\s*<\s*-1/.test(verification) && sharedWrite.test(kotlinWithoutComments(privateText)) &&
    sharedWrite.test(kotlinWithoutComments(pendingText)) && createCatch.test(create) &&
    /catch\s*\(_\s*:\s*FileNotFoundException\)\s*\{\s*DescriptorOpen\.Refused\(TransferStorageError\.NotFound\("partial"\)\)/.test(openDescriptor) &&
    descriptorCatch.test(openDescriptor) && writeOpenCatch.test(openWrite) && readOpenCatch.test(openRead);
}

/** Provider fingerprints must use the current row's identity, not a cached construction-time id. */
function sourceFingerprintsUseFreshIdentity(mediaText, safText) {
  const media = kotlinWithoutComments(mediaText);
  const saf = kotlinWithoutComments(safText);
  return /val\s+row\s*=\s*queryRow\(\)/.test(media) &&
    /contentId\s*=\s*row\.id\?\.let\s*\{\s*contentIdFor\(it\)\s*\}/.test(media) &&
    /val\s+row\s*=\s*queryRow\(\)/.test(saf) &&
    /contentId\s*=\s*row\.documentId\?\.let\s*\{\s*"doc:\$it"\s*\}/.test(saf);
}

/** The shared classifier preserves interruption and provider/coroutine cancellation, even wrapped. */
function classifierPreservesCancellation(text) {
  const code = kotlinWithoutComments(text);
  const classification = code.indexOf('isCancellation(error) -> TransferStorageError.Cancelled');
  const revocation = code.indexOf('isRevocation(error) -> TransferStorageError.PermissionRevoked');
  return /fun\s+isCancellation\(error:\s*Throwable\)\s*:\s*Boolean\s*=\s*walk\(error\)/.test(code) &&
    /is\s+InterruptedException\s*->\s*\{\s*Thread\.currentThread\(\)\.interrupt\(\)/.test(code) &&
    /is\s+CancellationException,\s*is\s+InterruptedIOException,\s*is\s+OperationCanceledException\s*->\s*true/.test(code) &&
    /depth\s*<\s*MAX_CAUSE_DEPTH/.test(code) && classification >= 0 && revocation > classification;
}

/** Pin the typed reader's range, progress, cancellation and ownership checks. */
function typedReadHasBoundedContract(text) {
  const code = kotlinWithoutComments(text);
  const body = kotlinFunctionBody(text, 'public fun read(');
  const opener = kotlinFunctionBody(text, 'public fun openAt(');
  if (body === null || opener === null) return false;
  const providerRead = body.search(/handle\.read\(buffer,\s*offset,\s*length\)/);
  const bounds = body.search(/length\s*>\s*buffer\.size\s*-\s*offset/);
  const authorization = opener.search(/resumeAuthorization\s*\?:\s*return\s+TypedSourceOpen\.Failed/);
  const revalidation = opener.search(/authorization\.revalidate\(source\.fingerprint\(\)\)/);
  const providerOpen = opener.search(/source\.openAt\(offset\)/);
  return /offset\s*<\s*0/.test(body) && /length\s*<=\s*0/.test(body) &&
    /offset\s*>\s*buffer\.size/.test(body) && bounds >= 0 && providerRead > bounds &&
    /count\s*<\s*-1\s*\|\|\s*count\s*>\s*length/.test(body) && /count\s*==\s*0/.test(body) &&
    /SourceReadResult\.EndOfSource/.test(body) && /SourceReadResult\.Data\(count\)/.test(body) &&
    /TransferStorageError\.Cancelled/.test(body) && /isCancellation\(error\)/.test(body) &&
    /StorageFailureClassifier\.isCancellation\(error\)/.test(code) &&
    /closed\.compareAndSet\(false,\s*true\)/.test(code) && /handle\.close\(\)/.test(code) &&
    /if\s*\(\s*offset\s*>\s*0L\s*\)/.test(opener) &&
    authorization >= 0 && revalidation > authorization && providerOpen > revalidation &&
    /revalidated\s+is\s+SourceResumeDecision\.Refused/.test(opener);
}

/** Only the handle, close flag and typed terminal outcome may be fields on the reader. */
function typedReaderFieldsAreAllowlisted(text) {
  const code = kotlinWithoutComments(text);
  const marker = 'public class TypedSourceReader(';
  const start = code.indexOf(marker);
  if (start < 0) return false;
  const open = code.indexOf('{', start + marker.length);
  const body = kotlinFunctionBody(text, marker);
  if (open < 0 || body === null) return false;
  const header = code.slice(start, open);
  const constructorFields = [...header.matchAll(/\b(?:val|var)\s+(\w+)\s*:/g)].map((m) => m[1]);
  if (constructorFields.length !== 1 || constructorFields[0] !== 'handle' ||
      !/private\s+val\s+handle\s*:\s*SourceHandle/.test(header)) return false;
  const fields = [];
  let depth = 0;
  let inCompanion = false;
  for (const line of body.split('\n')) {
    if (depth === 0 && /\bcompanion\s+object\b/.test(line)) inCompanion = true;
    if (depth === 0 || (depth === 1 && inCompanion)) {
      const field = line.match(/^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:private|public|internal|protected|lateinit|const|override)\s+)*(?:val|var)\s+(\w+)\b/);
      if (field) fields.push(field[1]);
    }
    for (const char of line) {
      if (char === '{') depth += 1;
      else if (char === '}') depth -= 1;
    }
    if (inCompanion && depth === 0) inCompanion = false;
  }
  return fields.length === 2 && fields.includes('closed') && fields.includes('terminal') &&
    /private\s+val\s+closed\s*=\s*AtomicBoolean\(false\)/.test(body) &&
    /private\s+var\s+terminal\s*:\s*SourceReadResult\?\s*=\s*null/.test(body);
}

/** A non-seekable positioning read cannot turn cancellation or impossible counts into success. */
function discardPreservesCancellationAndCounts(text) {
  const body = kotlinFunctionBody(text, 'public fun reposition(');
  if (body === null) return false;
  return /Thread\.currentThread\(\)\.isInterrupted/.test(body) &&
    /catch\s*\(error\s*:\s*Exception\)\s*\{\s*return\s+RepositionResult\.Failed\(StorageFailureClassifier\.classifyOrProviderFailure\(error,\s*"read",\s*"read"\)\)/.test(body) &&
    /count\s*<\s*-1\s*\|\|\s*count\s*>\s*toRead/.test(body);
}

/** Probe failures occur while the descriptor is owned, and cancellation closes it. */
function sourceProbeClosesOnCancellation(text) {
  const body = kotlinFunctionBody(text, 'public fun open(');
  if (body === null) return false;
  const ownedTry = body.indexOf('return try {');
  const probe = body.indexOf('probe.probe(descriptor)');
  return ownedTry >= 0 && probe > ownedTry &&
    /catch\s*\(_\s*:\s*CancellationException\)\s*\{\s*resource\.close\(\)\s*ProviderOpenResult\.Failed\(TransferStorageError\.Cancelled\)/.test(body) &&
    /catch\s*\(e\s*:\s*Exception\)\s*\{\s*resource\.close\(\)/.test(body);
}

/** The reader cannot hold a caller array, a copy, a collection entry or a capturing closure. */
function typedReadDoesNotRetainBuffer(text) {
  const code = kotlinWithoutComments(text);
  return typedReaderFieldsAreAllowlisted(text) &&
    !/\b(?:val|var)\s+\w+\s*:\s*(?:kotlin\.)?ByteArray\b/.test(code) &&
    !/\b(?:this\s*\.\s*)?\w+\s*=\s*buffer\b/.test(code) &&
    !/\]\s*=\s*buffer\b/.test(code) &&
    !/\bterminal\s*=\s*[^\n;]*\bbuffer\b/.test(code) &&
    !/SourceReadResult\.(?:Data|Failure)\([^)]*\bbuffer\b/.test(code) &&
    !/\b(?:add|put|set|offer)\s*\(\s*buffer\b/.test(code) &&
    !/\b(?:val|var)\s+\w+\s*=\s*\{[^}]*\bbuffer\b/.test(code);
}

/** A failed or short write must return before the sole optimistic frontier assignment. */
function failedWriteCannotAdvanceConfirmed(text) {
  const body = kotlinFunctionBody(text, 'public fun writeAt(');
  if (body === null) return false;
  const advances = [...body.matchAll(/\bfrontier\s*(?:=|\+=|-=|\+\+|--)/g)];
  const finalAdvance = body.indexOf('frontier = offset + length.toLong()');
  return !/\bconfirmed\b|\backnowledge\s*\(|\bcheckpointSink\b/.test(body) &&
    advances.length === 1 && finalAdvance >= 0 &&
    body.indexOf('outcome is WriteOutcome.Failed') >= 0 &&
    body.indexOf('written.bytes != length') >= 0 &&
    body.indexOf('durability is FlushDurability.FlushFailed') >= 0 &&
    body.indexOf('outcome is WriteOutcome.Failed') < finalAdvance &&
    body.indexOf('written.bytes != length') < finalAdvance &&
    body.indexOf('durability is FlushDurability.FlushFailed') < finalAdvance &&
    (body.match(/return\s+DestinationWrite\.Failed\s*\(/g) ?? []).length >= 3;
}

function storagePortMentionsTransport(text) {
  return STORAGE_TRANSPORT_TOKENS.test(stripComments(text));
}

/** The two new port files must stay free of Android, Uri, descriptor and Room types. */
function storagePortUsesPlatformTypes(text) {
  const code = stripComments(text);
  return /^[ \t]*import[ \t]+(android|androidx)[.]/m.test(code) ||
    /import[ \t]+java[.]io[.]File/.test(code) ||
    /ContentResolver/.test(code) ||
    /ParcelFileDescriptor/.test(code) ||
    /(^|[^A-Za-z0-9_.])Uri([^A-Za-z0-9_]|$)/.test(code) ||
    /androidx[.]room/.test(code);
}

/**
 * A storage port that can write to a log is a leak waiting for a provider message.
 *
 * The session is allowed to own no logging surface at all, and its only string
 * form must be redacted.
 */
function storagePortRedactsDiagnostics(text) {
  const code = stripComments(text);
  if (/Log[.][diwev][ \t]*[(]|println[ \t]*[(]|printStackTrace|SharedPreferences/.test(code)) {
    return false;
  }
  // A type with no string form has nothing to leak; a type with one must redact it.
  return /override fun toString[(][)]/.test(code) ? /\[redacted\]/.test(code) : true;
}

/**
 * The sequential-write gate has to be visible in the source, not merely claimed in a document.
 */
function storageSessionEnforcesSequentialWrite(text) {
  return /expectedFrontier/.test(text) &&
    /sink[.]length[(][)]/.test(text) &&
    /SequentialRejection[.]OVERLAP/.test(text) &&
    /SequentialRejection[.]HOLE/.test(text) &&
    /SequentialRejection[.]REWIND/.test(text) &&
    /SequentialRejection[.]AFTER_VERIFICATION/.test(text) &&
    /SequentialRejection[.]AFTER_COMMIT/.test(text) &&
    /SequentialRejection[.]AFTER_ABORT/.test(text);
}

/** Publication happens once, after verification, and a duplicate commit does not republish. */
function storageSessionCommitsOnce(text) {
  return /CommitResult[.]Duplicate/.test(text) &&
    /AbortResult[.]AlreadyAborted/.test(text) &&
    /SessionVerification[.]Mismatched/.test(text);
}

/**
 * The URI policy has to be present verbatim.
 *
 * This is a byte comparison rather than a keyword check because the owner
 * supplied exact wording and a paraphrase is a different policy.
 */
function storageDocCarriesVerbatimPolicy(text) {
  return text.includes(VERBATIM_URI_POLICY);
}

/**
 * Room's URI columns are restoration state and belong to the core-data layer.
 */
function uriColumnOutsideCoreData(path, text) {
  return !path.startsWith('core-data/') &&
    /(^|[^A-Za-z0-9_])(source_uri|target_dir|result_uri)([^A-Za-z0-9_]|$)/.test(text);
}

/** No production file composes the storage module with the transport module. */
function composesStorageWithTransport(text) {
  return /app[.]morsecode[.]core[.]storage/.test(text) &&
    /app[.]morsecode[.]transport[.]lan/.test(text);
}

/** core-transfer is platform-free: no Android storage type may enter it. */
function coreTransferUsesAndroidStorage(text) {
  return /android[.]net[.]Uri|android[.]content[.]ContentResolver|android[.]os[.]ParcelFileDescriptor|java[.]io[.]File|androidx[.]room/.test(text);
}

/** The transport module must not depend on the storage module, not even in tests. */
function transportBuildDependsOnStorage(text) {
  return /project\(":core-storage"\)|project\(':core-storage'\)/.test(text);
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

  check('self-test: a dormant payload layer passes',
    payloadLayerIsDormant('secureRecordAead.encrypt(nonce, header, plaintext)\n'));
  check('self-test: a payload layer holding a socket is caught',
    !payloadLayerIsDormant('val link: Socket = accept()\n'));
  check('self-test: a payload layer writing a stream is caught',
    !payloadLayerIsDormant('socket.getOutputStream().write(record)\n'));
  check('self-test: a payload layer persisting a record is caught',
    !payloadLayerIsDormant('@Dao interface Records { }\n'));
  check('self-test: a payload layer logging a key is caught',
    !payloadLayerIsDormant('Log.d("payload", keyHex)\n'));
  check('self-test: a payload layer naming the gate is caught',
    !payloadLayerIsDormant('val d = PayloadTransferGate.evaluate(s)\n'));
  check('self-test: a payload mention outside the authorized set is caught',
    unauthorizedMpl1Files([{ path: 'app/src/main/kotlin/X.kt', text: 'val s = LanPayloadKeySchedule\n' }],
      new Set(['transport-lan/src/main/kotlin/app/morsecode/transport/lan/payload/LanPayloadKeySchedule.kt']))
      .length === 1);
  check('self-test: a payload mention inside the authorized set is not reported',
    unauthorizedMpl1Files([{ path: 'a.kt', text: 'val s = LanPayloadKeySchedule\n' }], new Set(['a.kt']))
      .length === 0);
  check('self-test: the four approved labels are declared',
    PAYLOAD_LABELS.every((label) => declaresLabel(PAYLOAD_LABELS.map((l) => `"${l}"`).join('\n'), label)));
  check('self-test: a renamed payload label is caught',
    !declaresLabel('"EXPORTER-MORSEC-PAYLOAD-C2S-KEY-V2"', PAYLOAD_LABELS[0]));
  check('self-test: a fifth payload label changes the count',
    countPayloadLabelLiterals(PAYLOAD_LABELS.map((l) => `"${l}"`).join('\n') + '"EXPORTER-MORSEC-PAYLOAD-X-V1"') === 5);
  check('self-test: a label collision is caught',
    labelSetsCollide(['a', 'b'], ['b', 'c']));
  check('self-test: disjoint labels are not a collision',
    !labelSetsCollide(PAYLOAD_LABELS, CONTROL_LABELS));

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

  check('self-test: a Long constant with underscores parses',
    declaresIntConstant(
      'const val MAX_PAYLOAD_RECORDS_PER_DIRECTION: Long = 1_048_576L',
      'MAX_PAYLOAD_RECORDS_PER_DIRECTION',
      1048576,
    ));
  check('self-test: a Long constant with a different value is caught',
    !declaresIntConstant(
      'const val MAX_PAYLOAD_RECORDS_PER_DIRECTION: Long = 2_097_152L',
      'MAX_PAYLOAD_RECORDS_PER_DIRECTION',
      1048576,
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

  const readinessFixture = 'public object FeatureReadiness {\n' +
    '    public const val CURRENT_MILESTONE: Int = 2\n' +
    '    TRANSFER_ENGINE("transfer_engine", 5),\n' +
    '    public fun isAvailable(area: FeatureArea): Boolean = area.deliveredInMilestone <= CURRENT_MILESTONE\n' +
    '}\n';
  check('self-test: milestone 2 and the gated engine pass', milestonePinsHold(readinessFixture));
  check('self-test: a bumped milestone is caught',
    !milestonePinsHold(readinessFixture.replace('Int = 2', 'Int = 3')));
  check('self-test: an enabled transfer engine is caught',
    !milestonePinsHold(readinessFixture.replace('"transfer_engine", 5', '"transfer_engine", 2')));
  check('self-test: bypassing the readiness comparison is caught',
    !milestonePinsHold(readinessFixture.replace('area.deliveredInMilestone <= CURRENT_MILESTONE', 'true')));
  check('self-test: a conditional payload-gate return is caught',
    !payloadGateRefusesOnly(refusingGate.replace('    return', '    if (condition) return denied\n    return')));

  const muxGood = 'matches(prefix, MSR1_MAGIC)\n' +
    'matches(prefix, LanPayloadRecordFormat.magicBytes())\n' +
    'const val PREFIX_BYTES: Int = 4\nval prefix = ByteArray(PREFIX_BYTES)\n' +
    'fun receiveNext(): Int = 0\nsynchronized(readLock) { }\n' +
    'fun sendPayloadFrame(frame: TransferFrame): Int = 0\n' +
    'class Payload(val frame: TransferFrame)\n' +
    'FrameCodec.decodeSingle(body)\n' +
    'output.write(wire)\noutput.flush()\nreleaseWriter()\nacquireWriter(control)\n' +
    'writeLock.lock()\nwriteLock.unlock()\n' +
    'controlWaiting + consecutiveControlWrites\n' +
    'const val MAX_CONSECUTIVE_CONTROL_WRITES: Int = 32\n' +
    'override fun toString(): String = "records=[redacted]"\n';
  check('self-test: the multiplexer shape passes every predicate',
    multiplexerDispatchesOnBothMagics(muxGood) &&
    multiplexerHasSingleReader(muxGood) &&
    multiplexerReadsFourBytePrefix(muxGood) &&
    multiplexerWritesWholeRecordsUnderOneLock(muxGood) &&
    multiplexerNeverRetriesAfterSeal(muxGood) &&
    multiplexerStartsNoThread(muxGood) &&
    multiplexerHasBoundedControlPriority(muxGood) &&
    multiplexerAcceptsTypedFramesOnly(muxGood) &&
    multiplexerDecodesExactlyOneMsc1(muxGood) &&
    multiplexerDiagnosticsAreRedacted(muxGood) &&
    multiplexerTouchesNoStorageOrLogging(muxGood));
  check('self-test: a dispatcher with only the control arm is caught',
    !multiplexerDispatchesOnBothMagics('matches(prefix, MSR1_MAGIC)\n'));
  check('self-test: a second reader is caught',
    !multiplexerHasSingleReader('fun receiveNext(): Int = 0\nfun receivePayload(): Int = 0\n'));
  check('self-test: a forward magic scan is caught',
    !multiplexerHasSingleReader('fun receiveNext(): Int = 0\nval i = prefix.indexOf(0x4D)\n'));
  check('self-test: a two-byte prefix is caught',
    !multiplexerReadsFourBytePrefix('const val PREFIX_BYTES: Int = 2\nval prefix = ByteArray(PREFIX_BYTES)\n'));
  check('self-test: a retried write is caught',
    !multiplexerNeverRetriesAfterSeal('output.write(wire)\noutput.write(wire)\n'));
  check('self-test: a write outside the lock is caught',
    !multiplexerWritesWholeRecordsUnderOneLock('output.write(wire)\noutput.flush()\n'));
  check('self-test: a detached reader thread is caught',
    !multiplexerStartsNoThread('Thread { read() }.start()\n'));
  check('self-test: an executors-based reader is caught',
    !multiplexerStartsNoThread('Executors.newSingleThreadExecutor()\n'));
  check('self-test: unbounded control priority is caught',
    !multiplexerHasBoundedControlPriority('controlWaiting\n'));
  check('self-test: an arbitrary-byte payload send is caught',
    !multiplexerAcceptsTypedFramesOnly('fun sendPayload(frame: TransferFrame) = Unit\n' +
      'fun sendPayloadRaw(bytes: ByteArray) = Unit\n'));
  check('self-test: a byte-array payload API is caught',
    !multiplexerAcceptsTypedFramesOnly('fun sendPayloadFrame(frame: TransferFrame)\n' +
      'class Payload(val frame: TransferFrame)\nfun sendPayloadBytes(b: ByteArray) = Unit\n'));
  check('self-test: a stream-decoding payload path is caught',
    !multiplexerDecodesExactlyOneMsc1('FrameCodec.decode(body)\n'));
  check('self-test: an unredacted toString is caught',
    !multiplexerDiagnosticsAreRedacted('override fun toString(): String = "sid=$sessionId"\n'));
  check('self-test: logging inside the multiplexer is caught',
    !multiplexerTouchesNoStorageOrLogging('Log.i("mux", "x")\n'));
  check('self-test: a production construction site is caught',
    constructsMultiplexer('transport-lan/src/main/kotlin/app/morsecode/transport/lan/LanPeerDiscoveryProvider.kt',
      'val m = LanMultiplexedRecordChannel(s, c, p, r, k, e)\n'));
  check('self-test: the declaration file is not a construction site',
    !constructsMultiplexer(MULTIPLEXER_PATH, 'internal class LanMultiplexedRecordChannel(\n'));
  check('self-test: an app reference to the multiplexer is caught',
    outsideTransportNamesMultiplexer('app/src/main/kotlin/Ui.kt', 'val m: LanMultiplexedReceive\n'));
  check('self-test: a transport reference is allowed',
    !outsideTransportNamesMultiplexer(MULTIPLEXER_PATH, 'LanMultiplexedReceive\n'));

  check('self-test: a digest matches its own bytes',
    digestMatches(Buffer.from('payload'), createHash('sha256').update('payload').digest('hex')));
  check('self-test: a digest mismatch is caught',
    !digestMatches(Buffer.from('payload'), createHash('sha256').update('payload2').digest('hex')));

  // Storage bridge (slice 3).
  check('self-test: an inert storage port passes the transport scan',
    !storagePortMentionsTransport('class DestinationSession(val frontier: Long)\n'));
  check('self-test: a port naming MPL1 is caught',
    storagePortMentionsTransport('val magic = MPL1\n'));
  check('self-test: a comment that explains what the port avoids is not a violation',
    !storagePortMentionsTransport('// this port carries no MPL1 record\nval x = 1\n'));
  check('self-test: a port naming the multiplexer is caught',
    storagePortMentionsTransport('LanMultiplexedRecordChannel()\n'));
  check('self-test: a port opening a socket is caught',
    storagePortMentionsTransport('java.net.Socket()\n'));
  check('self-test: a port naming J-PAKE is caught',
    storagePortMentionsTransport('ECJPAKE\n'));
  check('self-test: a port naming the payload authority is caught',
    storagePortMentionsTransport('PayloadAuthority\n'));

  check('self-test: an Android-free port passes',
    !storagePortUsesPlatformTypes('class DestinationSession\n'));
  check('self-test: an android.net import is caught',
    storagePortUsesPlatformTypes('import android.net.Uri\n'));
  check('self-test: a ContentResolver is caught', storagePortUsesPlatformTypes('val r: ContentResolver\n'));
  check('self-test: a File is caught', storagePortUsesPlatformTypes('import java.io.File\n'));
  check('self-test: a Room annotation is caught',
    storagePortUsesPlatformTypes('import androidx.room.ColumnInfo\n'));

  check('self-test: a redacted port passes',
    storagePortRedactsDiagnostics('override fun toString(): String = "x [redacted]"\n'));
  check('self-test: a type with no string form is not required to redact one',
    storagePortRedactsDiagnostics('object Policy { fun decide() = true }\n'));
  check('self-test: a logging port is caught', !storagePortRedactsDiagnostics('Log.i("t", s)\n'));
  check('self-test: an unredacted toString is caught',
    !storagePortRedactsDiagnostics('override fun toString(): String = "id=$id"\n'));

  check('self-test: a session naming all three frontier values passes',
    storageSessionEnforcesSequentialWrite(
      'expectedFrontier\nsink.length()\nSequentialRejection.OVERLAP\nSequentialRejection.HOLE\n' +
      'SequentialRejection.REWIND\nSequentialRejection.AFTER_VERIFICATION\n' +
      'SequentialRejection.AFTER_COMMIT\nSequentialRejection.AFTER_ABORT\n'));
  check('self-test: a session without the overlap refusal is caught',
    !storageSessionEnforcesSequentialWrite('expectedFrontier\nsink.length()\n'));

  check('self-test: a doc with the exact policy passes',
    storageDocCarriesVerbatimPolicy(`x\n${VERBATIM_URI_POLICY}\ny\n`));
  check('self-test: a paraphrased policy is caught',
    !storageDocCarriesVerbatimPolicy('Content URIs may be stored in transfer state.\n'));

  check('self-test: a URI column inside core-data passes', !uriColumnOutsideCoreData('core-data/src/main/Entities.kt', 'source_uri'));
  check('self-test: a URI column in a storage adapter is caught',
    uriColumnOutsideCoreData('core-storage/src/main/A.kt', 'source_uri'));

  check('self-test: storage without transport passes', !composesStorageWithTransport('import app.morsecode.core.storage.transfer.PartialSink\n'));
  check('self-test: one file importing both is caught',
    composesStorageWithTransport('import app.morsecode.core.storage.x\nimport app.morsecode.transport.lan.y\n'));

  check('self-test: a platform-free core-transfer file passes', !coreTransferUsesAndroidStorage('class Frame\n'));
  check('self-test: a Uri in core-transfer is caught', coreTransferUsesAndroidStorage('import android.net.Uri\n'));

  check('self-test: a transport build without storage passes', !transportBuildDependsOnStorage('api(project(":core-transfer"))\n'));
  check('self-test: a transport build depending on storage is caught',
    transportBuildDependsOnStorage('api(project(":core-storage"))\n'));

  // Slice 3 hardening: mutate the actual guarded files in memory, not just toy snippets.
  const fingerprintSource = readFileSync(join(ROOT, STORAGE_FINGERPRINT_PATH), 'utf8');
  const resumeSource = readFileSync(join(ROOT, STORAGE_RESUME_PATH), 'utf8');
  const readerSource = readFileSync(join(ROOT, STORAGE_TYPED_READ_PATH), 'utf8');
  const classifierSource = readFileSync(join(ROOT, STORAGE_CLASSIFIER_PATH), 'utf8');
  const discardSource = readFileSync(join(ROOT, STORAGE_DISCARD_PATH), 'utf8');
  const openerSource = readFileSync(join(ROOT, STORAGE_SOURCE_OPENER_PATH), 'utf8');
  const sessionSource = readFileSync(join(ROOT, STORAGE_SESSION_PATH), 'utf8');
  const verifierSource = readFileSync(join(ROOT, STORAGE_VERIFIER_PATH), 'utf8');
  const privatePartialSource = readFileSync(join(ROOT, STORAGE_PRIVATE_PARTIAL_PATH), 'utf8');
  const pendingPartialSource = readFileSync(join(ROOT, STORAGE_PENDING_PARTIAL_PATH), 'utf8');
  const safCheckpointSource = readFileSync(join(ROOT, `${STORAGE_PACKAGE}SafCommitCheckpoint.kt`), 'utf8');
  const safCoordinatorSource = readFileSync(join(ROOT, `${STORAGE_PACKAGE}SafCommitCoordinator.kt`), 'utf8');
  const adapterSources = STORAGE_SOURCE_ADAPTER_PATHS.map((path) => readFileSync(join(ROOT, path), 'utf8'));
  const runtimePath = 'app/src/main/kotlin/app/morsecode/Runtime.kt';

  check('self-test: a typed runtime source is accepted',
    !runtimeBypassesTypedSource(runtimePath, 'val reader = TypedSourceReader.openAt(source, 0L)\n'));
  check('self-test: a raw handle read in runtime code is caught',
    runtimeBypassesTypedSource(runtimePath, 'fun copy(handle: SourceHandle) = handle.read(buffer, 0, size)\n'));
  check('self-test: an inferred raw handle obtained by openAt is caught',
    runtimeBypassesTypedSource(runtimePath, 'val raw = source.openAt(0L)\nraw.handle.read(buffer, 0, size)\n'));
  check('self-test: a URI string does not hide a raw open',
    runtimeBypassesTypedSource(runtimePath, 'val uri = "content://private/x"; source.openAt(0L)\n'));

  check('self-test: the real fingerprint policy checks every mismatch', sourcePolicyRejectsEveryMismatch(fingerprintSource));
  const identityAnchor = 'val currentId = current.contentId';
  check('self-test: identity overriding a size mismatch is caught',
    fingerprintSource.includes(identityAnchor) && !sourcePolicyRejectsEveryMismatch(fingerprintSource.replace(
      identityAnchor, `${identityAnchor}\n        if (storedId == currentId) return FingerprintOutcome.STRONG_MATCH`,
    )));
  check('self-test: removing the size mismatch check is caught',
    fingerprintSource.includes('storedSize != currentSize') && !sourcePolicyRejectsEveryMismatch(
      fingerprintSource.replace('storedSize != currentSize', 'storedSize == currentSize'),
    ));
  check('self-test: removing the timestamp mismatch check is caught',
    fingerprintSource.includes('storedModified != currentModified') && !sourcePolicyRejectsEveryMismatch(
      fingerprintSource.replace('storedModified != currentModified', 'storedModified == currentModified'),
    ));
  check('self-test: the real resume decision retains and verifies the expected digest',
    resumeRequiresExpectedDigest(resumeSource));
  check('self-test: allowing a missing expected digest is caught',
    resumeSource.includes('expectedSha256 == null') && !resumeRequiresExpectedDigest(
      resumeSource.replace('expectedSha256 == null', 'false'),
    ));
  check('self-test: dropping the digest from final verification is caught',
    resumeSource.includes('expected = expectedSha256') && !resumeRequiresExpectedDigest(
      resumeSource.replace('expected = expectedSha256', 'expected = null'),
    ));
  check('self-test: losing fingerprint cancellation is caught',
    resumeSource.includes('current.error.category == TransferStorageErrorCategory.CANCELLED') && !resumeRequiresExpectedDigest(
      resumeSource.replace('current.error.category == TransferStorageErrorCategory.CANCELLED', 'false'),
    ));
  check('self-test: a resumed destination binds final verification and commit to its retained digest',
    resumedDestinationRequiresRetainedDigest(sessionSource));
  for (const [label, before, after] of [
    ['opening a resume without a digest', 'confirmed > 0L && retainedExpectedSha256 == null', 'false'],
    ['accepting null on a resumed verification', 'retained != null && expected == null', 'false'],
    ['accepting a replacement expected digest', 'retained != null && !retained.contentEquals(expected)', 'false'],
    ['verifying against the supplied rather than retained digest', 'retained ?: expected', 'expected'],
    ['allowing a resumed verified-without-expected result',
      'if (retained != null) {\n                    return SessionVerification.Refused',
      'if (false) {\n                    return SessionVerification.Refused'],
    ['committing a digest other than the retained digest', 'retained != null && !retained.contentEquals(digest)', 'false'],
    ['dropping the retained digest on session construction',
      'retainedExpectedSha256 = retainedExpectedSha256,', 'retainedExpectedSha256 = null,'],
  ]) {
    check(`self-test: ${label} is caught`, sessionSource.includes(before) &&
      !resumedDestinationRequiresRetainedDigest(sessionSource.replace(before, after)));
  }

  check('self-test: the real typed reader has a bounded contract', typedReadHasBoundedContract(readerSource));
  check('self-test: accepting impossible provider counts is caught',
    readerSource.includes('count < -1 || count > length') && !typedReadHasBoundedContract(
      readerSource.replace('count < -1 || count > length', 'count < -1'),
    ));
  check('self-test: cancellation is classified by the shared bounded classifier',
    classifierPreservesCancellation(classifierSource));
  for (const [label, before, after] of [
    ['interrupted I/O', 'is InterruptedIOException, ', ''],
    ['Android provider cancellation', ', is OperationCanceledException', ''],
    ['coroutine cancellation', 'is CancellationException, ', ''],
    ['cancellation precedence', 'isCancellation(error) -> TransferStorageError.Cancelled',
      'isCancellation(error) -> TransferStorageError.Io("read")'],
  ]) {
    check(`self-test: losing ${label} is caught`, classifierSource.includes(before) &&
      !classifierPreservesCancellation(classifierSource.replace(before, after)));
  }
  check('self-test: bypassing the shared cancellation classifier is caught',
    readerSource.includes('StorageFailureClassifier.isCancellation(error)') && !typedReadHasBoundedContract(
      readerSource.replace('StorageFailureClassifier.isCancellation(error)', 'false'),
    ));
  check('self-test: a nonzero open that skips resume authorization is caught',
    readerSource.includes('if (offset > 0L)') && !typedReadHasBoundedContract(
      readerSource.replace('if (offset > 0L)', 'if (offset > Long.MAX_VALUE)'),
    ));
  check('self-test: skipping the fresh fingerprint comparison is caught',
    readerSource.includes('authorization.revalidate(source.fingerprint())') && !typedReadHasBoundedContract(
      readerSource.replace('authorization.revalidate(source.fingerprint())', 'authorization'),
    ));
  check('self-test: discard preserves cancellation and validates provider counts',
    discardPreservesCancellationAndCounts(discardSource));
  check('self-test: accepting an impossible discard count is caught',
    discardSource.includes('count < -1 || count > toRead') && !discardPreservesCancellationAndCounts(
      discardSource.replace('count < -1 || count > toRead', 'count < -1'),
    ));
  check('self-test: downgrading discard cancellation is caught',
    discardSource.includes('StorageFailureClassifier.classifyOrProviderFailure(error, "read", "read")') &&
      !discardPreservesCancellationAndCounts(discardSource.replace(
        'StorageFailureClassifier.classifyOrProviderFailure(error, "read", "read")',
        'TransferStorageError.ProviderFailure("document_provider")',
      )));
  check('self-test: a probing cancellation closes the owned descriptor', sourceProbeClosesOnCancellation(openerSource));
  check('self-test: probing outside the descriptor-owning try is caught',
    openerSource.includes('val capability = probe.probe(descriptor)') && !sourceProbeClosesOnCancellation(
      openerSource.replace('return try {\n            // Probing also owns the descriptor: a throwing probe must not leak it.\n            val capability = probe.probe(descriptor)',
        'val capability = probe.probe(descriptor)\n        return try {'),
    ));
  check('self-test: the real reader and classifier do not leak exception text',
    typedReadDoesNotLeakProviderText(readerSource, classifierSource));
  check('self-test: forwarding a raw read exception message is caught',
    readerSource.includes('classifyOrProviderFailure(error, "read", "read")') &&
      !typedReadDoesNotLeakProviderText(readerSource.replace(
        'StorageFailureClassifier.classifyOrProviderFailure(error, "read", "read")',
        'TransferStorageError.Io("read", error.message)',
      ), classifierSource));
  check('self-test: leaking an exception from the shared classifier is caught',
    classifierSource.includes('TransferStorageError.Io(operation)') &&
      !typedReadDoesNotLeakProviderText(readerSource, classifierSource.replace(
        'TransferStorageError.Io(operation)', 'TransferStorageError.Io(operation, error.message)',
      )));
  check('self-test: source fingerprints use the current provider row identity',
    sourceFingerprintsUseFreshIdentity(adapterSources[2], adapterSources[3]));
  check('self-test: using a cached media identity is caught',
    adapterSources[2].includes('row.id?.let { contentIdFor(it) }') && !sourceFingerprintsUseFreshIdentity(
      adapterSources[2].replace('row.id?.let { contentIdFor(it) }', 'mediaId?.let { contentIdFor(it) }'), adapterSources[3],
    ));
  check('self-test: using a cached SAF identity is caught',
    adapterSources[3].includes('row.documentId?.let { "doc:$it" }') && !sourceFingerprintsUseFreshIdentity(
      adapterSources[2], adapterSources[3].replace('row.documentId?.let { "doc:$it" }', 'cachedDocumentId'),
    ));
  check('self-test: the source open and fingerprint adapters do not retain exception text',
    sourceAdaptersDoNotLeakProviderText(adapterSources));
  for (let index = 0; index < adapterSources.length; index += 1) {
    const mutated = adapterSources.slice();
    mutated[index] += '\nval unsafeDiagnostic = e.message\n';
    check(`self-test: raw provider text in ${STORAGE_SOURCE_ADAPTER_PATHS[index]} is caught`,
      !sourceAdaptersDoNotLeakProviderText(mutated));
  }
  check('self-test: the real typed reader does not retain caller buffers', typedReadDoesNotRetainBuffer(readerSource));
  check('self-test: a retained buffer field is caught',
    readerSource.includes('private val closed = AtomicBoolean(false)') && !typedReadDoesNotRetainBuffer(
      readerSource.replace('private val closed = AtomicBoolean(false)',
        'private val closed = AtomicBoolean(false)\n    private var cached: ByteArray? = null'),
    ));
  check('self-test: saving the caller buffer under an Any field is caught',
    readerSource.includes('terminal?.let { return it }') && !typedReadDoesNotRetainBuffer(
      readerSource.replace('terminal?.let { return it }', 'cached = buffer\n        terminal?.let { return it }'),
    ));
  check('self-test: retaining a buffer inside a collection is caught',
    readerSource.includes('private val closed = AtomicBoolean(false)') && !typedReadDoesNotRetainBuffer(
      readerSource.replace('private val closed = AtomicBoolean(false)',
        'private val closed = AtomicBoolean(false)\n    private var cached: Any? = null')
        .replace('terminal?.let { return it }', 'cached = listOf(buffer)\n        terminal?.let { return it }'),
    ));
  check('self-test: destination create, open, write and verification exceptions are classified',
    destinationFailuresAreClassified(sessionSource, verifierSource, privatePartialSource, pendingPartialSource));
  for (const [label, before, after] of [
    ['limiting pending creation to I/O exceptions',
      'catch (e: Exception) {\n            // Providers may',
      'catch (e: IOException) {\n            // Providers may'],
    ['bypassing pending creation classification',
      'StorageFailureClassifier.classify(e, "create", "write")', 'TransferStorageError.Io("create")'],
    ['limiting pending descriptor opening to I/O exceptions',
      'catch (e: Exception) {\n            DescriptorOpen.Refused(',
      'catch (e: IOException) {\n            DescriptorOpen.Refused('],
    ['bypassing pending descriptor classification',
      'StorageFailureClassifier.classify(e, "open", if (mode == READ_MODE) "read" else "write")',
      'TransferStorageError.Io("open")'],
    ['losing the missing-row distinction',
      'catch (_: FileNotFoundException)', 'catch (_: IOException)'],
    ['not closing a descriptor after a failed write-phase open',
      'runCatching { descriptor.close() }\n            PendingOpen.Refused(',
      'PendingOpen.Refused('],
    ['not closing a descriptor after a failed read-phase open',
      'runCatching { descriptor.close() }\n            PendingReadOpen.Refused(',
      'PendingReadOpen.Refused('],
  ]) {
    check(`self-test: ${label} is caught`, pendingPartialSource.includes(before) &&
      !destinationFailuresAreClassified(sessionSource, verifierSource, privatePartialSource,
        pendingPartialSource.replace(before, after)));
  }
  check('self-test: a throwing write bypassing classification is caught',
    sessionSource.includes('StorageFailureClassifier.classifyOrProviderFailure(error, "write", "write")') &&
      !destinationFailuresAreClassified(
        sessionSource.replace('StorageFailureClassifier.classifyOrProviderFailure(error, "write", "write")',
          'TransferStorageError.Io("write")'), verifierSource, privatePartialSource, pendingPartialSource,
      ));
  check('self-test: a throwing verification read bypassing classification is caught',
    verifierSource.includes('StorageFailureClassifier.classifyOrProviderFailure(error, "read", "read")') &&
      !destinationFailuresAreClassified(sessionSource, verifierSource.replace(
        'StorageFailureClassifier.classifyOrProviderFailure(error, "read", "read")', 'TransferStorageError.Io("read")',
      ), privatePartialSource, pendingPartialSource));
  check('self-test: a real private-partial write bypassing classification is caught',
    privatePartialSource.includes('StorageFailureClassifier.classifyOrProviderFailure(e, "write", "write")') &&
      !destinationFailuresAreClassified(sessionSource, verifierSource, privatePartialSource.replace(
        'StorageFailureClassifier.classifyOrProviderFailure(e, "write", "write")', 'TransferStorageError.Io("write")',
      ), pendingPartialSource));
  check('self-test: a pending-partial raw exception leak is caught',
    !storageDomainDoesNotLeakProviderText([{ path: STORAGE_PENDING_PARTIAL_PATH,
      text: pendingPartialSource + '\nval unsafe = error.message\n' }]));
  const safeSafRenderers = [
    { path: `${STORAGE_PACKAGE}SafCommitCheckpoint.kt`, text: safCheckpointSource },
    { path: `${STORAGE_PACKAGE}SafCommitCoordinator.kt`, text: safCoordinatorSource },
  ];
  check('self-test: the existing typed storage-error renderers remain accepted',
    storageDomainDoesNotLeakProviderText(safeSafRenderers));
  check('self-test: an exception interpolation in a typed-renderer file is caught',
    !storageDomainDoesNotLeakProviderText([safeSafRenderers[0], {
      ...safeSafRenderers[1], text: safCoordinatorSource + '\nval unsafe = "$error"\n',
    }]));
  const safeRecoveryDeclaration = 'public data class ReconciliationRequired(\n        public val checkpoint: SafCommitCheckpoint,\n        public val error: TransferStorageError,';
  check('self-test: changing an approved typed renderer to an exception is caught',
    safCheckpointSource.includes(safeRecoveryDeclaration) && !storageDomainDoesNotLeakProviderText([{
      ...safeSafRenderers[0], text: safCheckpointSource.replace(safeRecoveryDeclaration,
        safeRecoveryDeclaration.replace('error: TransferStorageError', 'error: Exception')),
    }]));
  check('self-test: the real write path does not promote confirmed bytes', failedWriteCannotAdvanceConfirmed(sessionSource));
  check('self-test: a failed write advancing confirmed bytes is caught',
    sessionSource.includes('if (outcome is WriteOutcome.Failed) {') && !failedWriteCannotAdvanceConfirmed(
      sessionSource.replace('if (outcome is WriteOutcome.Failed) {',
        'if (outcome is WriteOutcome.Failed) {\n            confirmed = offset + length.toLong()'),
    ));
  check('self-test: a failed write advancing the optimistic frontier is caught',
    sessionSource.includes('if (outcome is WriteOutcome.Failed) {') && !failedWriteCannotAdvanceConfirmed(
      sessionSource.replace('if (outcome is WriteOutcome.Failed) {',
        'if (outcome is WriteOutcome.Failed) {\n            frontier = offset + length.toLong()'),
    ));
  for (const [schemaPath, expected] of ROOM_SCHEMA_DIGESTS) {
    const schema = readFileSync(join(ROOT, schemaPath));
    check(`self-test: changing ${schemaPath} is caught`,
      digestMatches(schema, expected) && !digestMatches(Buffer.concat([schema, Buffer.from(' ')]), expected));
  }

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

  // 6b. Payload state is confined, inert, and not yet claimed by anything.
  //
  //     Slice 1 added a codec and a specification; this slice adds labels, a key schedule and an
  //     AEAD layer. The guards below are what keep that a dormant capability rather than a payload
  //     path: the package may hold exactly five files, payload state may be named in exactly three
  //     production files outside it, the framing stays free of sockets and ciphers, the layer stays
  //     free of sockets and persistence, only the coordinator derives material or builds a
  //     schedule, and nothing in production claims one. The Room exports and readiness constants
  //     are pinned here too, so none of this can be reached by moving a gate instead of a byte.
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
  const unexpectedPackageFiles = payloadPaths.filter((path) => !PAYLOAD_PACKAGE_FILES.has(path));
  const missingPackageFiles = [...PAYLOAD_PACKAGE_FILES].filter((path) => !payloadPaths.includes(path));
  check(
    'the payload package holds exactly the five approved files',
    unexpectedPackageFiles.length === 0 && missingPackageFiles.length === 0,
    [...unexpectedPackageFiles, ...missingPackageFiles.map((path) => `${path} (missing)`)].join(', '),
  );

  const payloadLeaks = unauthorizedMpl1Files(production, AUTHORIZED_PAYLOAD_PRODUCTION_FILES);
  check(
    'payload state is named in production only inside the approved files',
    payloadLeaks.length === 0,
    payloadLeaks.join(', '),
  );

  const docs = collect(join(ROOT, 'doc'))
    .filter((f) => f.endsWith('.md'))
    .map((f) => ({ path: relative(ROOT, f).replaceAll('\\', '/'), text: readFileSync(f, 'utf8') }));
  const payloadDocLeaks = unauthorizedMpl1Files(docs, AUTHORIZED_MPL1_DOCS);
  check(
    'payload state is documented only in the transfer protocol document',
    payloadDocLeaks.length === 0,
    payloadDocLeaks.join(', '),
  );

  const framing = [PAYLOAD_FORMAT_PATH, PAYLOAD_CODEC_PATH].map((path) => ({
    path,
    text: readFileSync(join(ROOT, path), 'utf8'),
  }));
  const framingOffenders = framing.filter((f) => !payloadFramingIsInert(f.text)).map((f) => f.path);
  check(
    'the payload framing derives no key, opens no socket, persists nothing and logs nothing',
    framingOffenders.length === 0,
    framingOffenders.join(', '),
  );

  const layerSource = readFileSync(join(ROOT, PAYLOAD_LAYER_PATH), 'utf8');
  check(
    'the payload record layer opens no socket, persists nothing, logs nothing and names no gate',
    payloadLayerIsDormant(layerSource),
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

  check(
    'the payload record ceiling is pinned at 1,048,576 per direction',
    declaresIntConstant(formatSource, 'MAX_PAYLOAD_RECORDS_PER_DIRECTION', 1_048_576) &&
      readFileSync(join(ROOT, 'doc', 'transfer-protocol.md'), 'utf8').includes('1,048,576'),
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

  const importingFiles = production
    .filter((f) => !PAYLOAD_PACKAGE_FILES.has(f.path))
    .filter((f) => /import app\.morsecode\.transport\.lan\.payload\./.test(f.text))
    .map((f) => f.path);
  const unauthorizedImporters = importingFiles.filter((path) => !PAYLOAD_CHAIN_FILES.has(path));
  check(
    'only the coordinator, the authority and the control channel import the payload package',
    unauthorizedImporters.length === 0,
    unauthorizedImporters.join(', ') || importingFiles.map((p) => p.replace(/^.*\//, '')).join(', '),
  );

  // The labels: exactly four, the right four, distinct, and not the control layer's.
  const labelsSource = readFileSync(join(ROOT, PAYLOAD_LABELS_PATH), 'utf8');
  const coordinatorSource = readFileSync(join(ROOT, PAYLOAD_COORDINATOR_PATH), 'utf8');
  const labelMisses = PAYLOAD_LABELS.filter((label) => !declaresLabel(labelsSource, label));
  check(
    'the four payload exporter labels are exactly the approved strings',
    labelMisses.length === 0 && countPayloadLabelLiterals(labelsSource) === PAYLOAD_LABELS.length,
    labelMisses.join(', ') ||
      `${countPayloadLabelLiterals(labelsSource)} label literal(s) in the file`,
  );
  check(
    'the four payload labels are pairwise distinct',
    new Set(PAYLOAD_LABELS).size === PAYLOAD_LABELS.length,
  );
  check(
    'no payload label collides with a control, proof or channel-binding label',
    !labelSetsCollide(PAYLOAD_LABELS, CONTROL_LABELS),
  );
  const controlLabelMisses = CONTROL_LABELS.filter((label) => !declaresLabel(coordinatorSource, label));
  check(
    'the control layer still derives from its own five labels',
    controlLabelMisses.length === 0,
    controlLabelMisses.join(', '),
  );

  // Derivation and construction: one file each, and it is the same file.
  const derivations = production
    .filter((f) => /LanPayloadExporterLabels\.[A-Z0-9_]+/.test(f.text))
    .map((f) => f.path)
    .filter((path) => path !== PAYLOAD_LABELS_PATH);
  check(
    'only the coordinator derives payload exporter material',
    derivations.length === 1 && derivations[0] === PAYLOAD_COORDINATOR_PATH,
    derivations.join(', ') || 'no derivation found',
  );
  const constructions = production
    .filter((f) => f.path !== PAYLOAD_SCHEDULE_PATH)
    .filter((f) => /LanPayloadKeySchedule\.create\(/.test(f.text))
    .map((f) => f.path);
  check(
    'only the coordinator constructs a payload key schedule',
    constructions.length === 1 && constructions[0] === PAYLOAD_COORDINATOR_PATH,
    constructions.join(', ') || 'no construction found',
  );

  // Claims: the seam exists, and no production file uses it.
  // The channel's own seam is the one place allowed to forward a claim, and it must stay a single
  // forwarding call: a second site would mean a second path to the same capability.
  const controlChannelSource = readFileSync(join(ROOT, PAYLOAD_CONTROL_CHANNEL_PATH), 'utf8');
  const claimSites = production
    .filter((f) => f.path !== PAYLOAD_SCHEDULE_PATH && f.path !== PAYLOAD_CONTROL_CHANNEL_PATH)
    .filter((f) => /\.claimRecordLayer\(/.test(f.text))
    .map((f) => f.path);
  const dispatchSites = production
    .filter((f) => f.path !== PAYLOAD_CONTROL_CHANNEL_PATH)
    .filter((f) => /\.claimPayloadLayer\(/.test(f.text))
    .map((f) => f.path);
  const seamForwards = (controlChannelSource.match(/\.claimRecordLayer\(\)/g) ?? []).length;
  check(
    'no production caller claims the payload capability',
    claimSites.length === 0 && dispatchSites.length === 0 && seamForwards === 1 &&
      /internal fun claimPayloadLayer\(\): LanPayloadRecordLayer\?/.test(controlChannelSource),
    [...claimSites, ...dispatchSites].join(', ') || `seam forwards: ${seamForwards}`,
  );

  // The capability cannot leave the module, and cannot be serialized or compared by value.
  const scheduleSource = readFileSync(join(ROOT, PAYLOAD_SCHEDULE_PATH), 'utf8');
  check(
    'the payload capability has a private constructor and no serializer',
    /internal class LanPayloadKeySchedule private constructor\(/.test(scheduleSource) &&
      !/Serializable|Parcelable|writeToParcel|toByteArray\(\): ByteArray/.test(scheduleSource) &&
      !/data class LanPayloadKeySchedule/.test(scheduleSource),
  );

  // No socket-owning or storage-owning file may touch the wire format yet.
  const wireFiles = production
    .filter((f) => !PAYLOAD_PACKAGE_FILES.has(f.path))
    .filter((f) => PAYLOAD_WIRE_TOKEN.test(f.text))
    .map((f) => f.path);
  check(
    'the multiplexer is the only file outside the payload package that names the MPL1 format',
    wireFiles.length === 1 && wireFiles[0] === MULTIPLEXER_PATH,
    wireFiles.join(', ') || 'none',
  );
  const socketFiles = production
    .filter((f) => /\bSocket\b|SSLSocket|getInputStream|getOutputStream/.test(f.text))
    .filter((f) => PAYLOAD_WIRE_TOKEN.test(f.text))
    .map((f) => f.path);
  check(
    'the multiplexer is the only socket-bearing file that names the MPL1 record',
    socketFiles.length === 1 && socketFiles[0] === MULTIPLEXER_PATH,
    socketFiles.join(', ') || 'none',
  );
  const storageWire = production
    .filter((f) => /^(core-storage|core-data|app)\//.test(f.path))
    .filter((f) => PAYLOAD_WIRE_TOKEN.test(f.text))
    .map((f) => f.path);
  check(
    'no storage, database or app source references the MPL1 wire format',
    storageWire.length === 0,
    storageWire.join(', '),
  );

  // The package logs nothing and persists nothing, whatever each file is allowed to do.
  const secretLeaks = [...PAYLOAD_PACKAGE_FILES]
    .map((path) => ({ path, text: readFileSync(join(ROOT, path), 'utf8') }))
    .filter((f) => /\bLog\.[diwev]\s*\(|println\s*\(|SharedPreferences|\bRoom\b|\bDao\b/.test(f.text))
    .map((f) => f.path);
  check(
    'no payload source logs or persists anything',
    secretLeaks.length === 0,
    secretLeaks.join(', '),
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

  // 6d. The multiplexer is one inert file: the only dispatcher of both magics, the only owner of
  //     the shared stream, and reachable from no production caller at all.
  const muxSource = readFileSync(join(ROOT, MULTIPLEXER_PATH), 'utf8');
  const dispatchers = production
    .filter((f) => multiplexerDispatchesOnBothMagics(f.text))
    .map((f) => f.path);
  check(
    'exactly one production file dispatches on both the MSR1 and MPL1 magics',
    dispatchers.length === 1 && dispatchers[0] === MULTIPLEXER_PATH,
    dispatchers.join(', ') || 'none',
  );
  check(
    'the multiplexer is internal and pinned to the control codec magic',
    /^internal class LanMultiplexedRecordChannel\(/m.test(muxSource) &&
      /byteArrayOf\(0x4D, 0x53, 0x52, 0x31\)/.test(muxSource),
  );
  check('the multiplexer has one receive entry point and no forward scan',
    multiplexerHasSingleReader(muxSource));
  check('the multiplexer consumes exactly four bytes before choosing a format',
    multiplexerReadsFourBytePrefix(muxSource));
  check('the multiplexer writes each record whole under the one arbiter',
    multiplexerWritesWholeRecordsUnderOneLock(muxSource));
  check('the multiplexer never retries a record once its nonce is spent',
    multiplexerNeverRetriesAfterSeal(muxSource));
  check('the multiplexer starts no detached reader or writer thread',
    multiplexerStartsNoThread(muxSource));
  check('control priority is bounded by a stated consecutive-write limit',
    multiplexerHasBoundedControlPriority(muxSource));
  check('payload crosses the boundary only as a typed TransferFrame',
    multiplexerAcceptsTypedFramesOnly(muxSource));
  check('exactly one MSC1 frame is decoded from one payload plaintext',
    multiplexerDecodesExactlyOneMsc1(muxSource));
  check('the multiplexer logs and persists nothing and redacts its diagnostics',
    multiplexerTouchesNoStorageOrLogging(muxSource) && multiplexerDiagnosticsAreRedacted(muxSource));

  const multiplexerConstructions = production
    .filter((f) => constructsMultiplexer(f.path, f.text))
    .map((f) => f.path);
  check(
    'no production file constructs the multiplexer',
    multiplexerConstructions.length === 0,
    multiplexerConstructions.join(', ') || 'no construction site',
  );
  const outsideTransport = production
    .filter((f) => outsideTransportNamesMultiplexer(f.path, f.text))
    .map((f) => f.path);
  check(
    'no app, storage, service, worker or UI source names the multiplexer',
    outsideTransport.length === 0,
    outsideTransport.join(', ') || 'transport only',
  );
  const providerSource = readFileSync(join(ROOT, PROVIDER), 'utf8');
  check(
    'the live provider still installs the control-only channel',
    !/LanMultiplexedRecordChannel/.test(providerSource) &&
      /SecureLanControlChannel/.test(providerSource),
  );
  check(
    'the JVM and API 23 multiplexer suites are present at their exact paths',
    existsSync(join(ROOT, MULTIPLEXER_TEST_PATH)) &&
      existsSync(join(ROOT, MULTIPLEXER_API23_TEST_PATH)),
  );
  check(
    'the JVM suite covers the slice-by-slice prefix regression',
    readFileSync(join(ROOT, MULTIPLEXER_TEST_PATH), 'utf8')
      .includes('aPartiallyReadPrefixSurvivesThePollingSlice'),
  );

  // 10. The storage bridge is inert: ports with no transport, no platform types and no leaks.
  const storageSession = production.find((f) => f.path === STORAGE_SESSION_PATH);
  const storageVerifier = production.find((f) => f.path === STORAGE_VERIFIER_PATH);
  const privatePartial = production.find((f) => f.path === STORAGE_PRIVATE_PARTIAL_PATH);
  const pendingPartial = production.find((f) => f.path === STORAGE_PENDING_PARTIAL_PATH);
  const storageCapacity = production.find((f) => f.path === STORAGE_CAPACITY_PATH);
  const typedReader = production.find((f) => f.path === STORAGE_TYPED_READ_PATH);
  const fingerprintPolicy = production.find((f) => f.path === STORAGE_FINGERPRINT_PATH);
  const resumePolicy = production.find((f) => f.path === STORAGE_RESUME_PATH);
  const classifier = production.find((f) => f.path === STORAGE_CLASSIFIER_PATH);
  const discard = production.find((f) => f.path === STORAGE_DISCARD_PATH);
  const sourceOpener = production.find((f) => f.path === STORAGE_SOURCE_OPENER_PATH);
  const sourceAdapters = STORAGE_SOURCE_ADAPTER_PATHS.map((path) => production.find((f) => f.path === path));
  check(
    'the destination, typed reader and resume-policy ports exist at their exact paths',
    [storageSession, storageVerifier, privatePartial, pendingPartial, storageCapacity, typedReader, fingerprintPolicy, resumePolicy, classifier, discard, ...sourceAdapters].every(Boolean),
  );
  const storagePorts = [storageSession, storageCapacity, typedReader, fingerprintPolicy, resumePolicy].filter(Boolean);
  const transportTouchers = storagePorts
    .filter((f) => storagePortMentionsTransport(f.text))
    .map((f) => f.path);
  check(
    'storage ports reference no MPL1, MSR1, socket, TLS, PAKE or payload authority',
    transportTouchers.length === 0,
    transportTouchers.join(', ') || 'inert',
  );
  const platformTouchers = storagePorts
    .filter((f) => storagePortUsesPlatformTypes(f.text))
    .map((f) => f.path);
  check(
    'storage ports import no Android, Uri, descriptor, File or Room type',
    platformTouchers.length === 0,
    platformTouchers.join(', ') || 'platform free',
  );
  const loggers = storagePorts
    .filter((f) => !storagePortRedactsDiagnostics(f.text))
    .map((f) => f.path);
  check(
    'storage ports log nothing and redact their string forms',
    loggers.length === 0,
    loggers.join(', ') || 'redacted',
  );
  check(
    'the session enforces the three-value sequential-write invariant',
    storageSession !== undefined && storageSessionEnforcesSequentialWrite(storageSession.text),
  );
  check(
    'the session commits once, refuses a duplicate and never aborts a commit',
    storageSession !== undefined && storageSessionCommitsOnce(storageSession.text),
  );
  check(
    'known source identity, size and timestamp mismatches force restart before any match',
    fingerprintPolicy !== undefined && sourcePolicyRejectsEveryMismatch(fingerprintPolicy.text),
  );
  check(
    'resume retains the expected SHA-256 and uses it for final verification',
    resumePolicy !== undefined && resumeRequiresExpectedDigest(resumePolicy.text),
  );
  check(
    'a resumed destination requires its retained SHA-256 for final verification and commit',
    storageSession !== undefined && resumedDestinationRequiresRetainedDigest(storageSession.text),
  );
  check(
    'the shared storage classifier preserves wrapped and Android provider cancellation',
    classifier !== undefined && classifierPreservesCancellation(classifier.text),
  );
  check(
    'source open and fingerprint adapters never retain raw provider exception text',
    sourceAdapters.every(Boolean) && sourceAdaptersDoNotLeakProviderText(sourceAdapters.map((f) => f.text)),
  );
  check(
    'source fingerprints use freshly queried media and document identities',
    sourceAdapters.every(Boolean) && sourceFingerprintsUseFreshIdentity(sourceAdapters[2].text, sourceAdapters[3].text),
  );
  check(
    'storage domain adapters do not retain raw provider exception text',
    storageDomainDoesNotLeakProviderText(production.filter((f) => f.path.startsWith(STORAGE_PACKAGE))),
  );
  check(
    'destination creation, open, write and verification failures are centrally classified',
    [storageSession, storageVerifier, privatePartial, pendingPartial].every(Boolean) &&
      destinationFailuresAreClassified(storageSession.text, storageVerifier.text, privatePartial.text, pendingPartial.text),
  );
  check(
    'the typed reader validates ranges, progress, cancellation and exactly-once close',
    typedReader !== undefined && typedReadHasBoundedContract(typedReader.text),
  );
  check(
    'typed read and open failures never carry raw provider exception text',
    typedReader !== undefined && classifier !== undefined &&
      typedReadDoesNotLeakProviderText(typedReader.text, classifier.text),
  );
  check(
    'non-seekable positioning preserves cancellation and rejects impossible read counts',
    discard !== undefined && discardPreservesCancellationAndCounts(discard.text),
  );
  check(
    'a cancellation during source probing closes the owned descriptor',
    sourceOpener !== undefined && sourceProbeClosesOnCancellation(sourceOpener.text),
  );
  check(
    'the typed reader does not retain the caller buffer',
    typedReader !== undefined && typedReadDoesNotRetainBuffer(typedReader.text),
  );
  check(
    'failed and short destination writes cannot advance confirmed or optimistic bytes',
    storageSession !== undefined && failedWriteCannotAdvanceConfirmed(storageSession.text),
  );
  const otherRuntimeSources = ['transport-nearby', 'webshare-server']
    .flatMap((module) => loadSources(collect(join(ROOT, module, 'src', 'main'))));
  const rawSourceReaders = [...production, ...otherRuntimeSources]
    .filter((file) => runtimeBypassesTypedSource(file.path, file.text))
    .map((file) => file.path);
  check(
    'runtime-facing sources cannot read a raw source handle or bypass the typed opener',
    rawSourceReaders.length === 0,
    rawSourceReaders.join(', ') || 'no raw source access',
  );

  const androidInCoreTransfer = production
    .filter((f) => f.path.startsWith('core-transfer/') && coreTransferUsesAndroidStorage(f.text))
    .map((f) => f.path);
  check(
    'core-transfer holds no Android, Uri, descriptor, File or Room type',
    androidInCoreTransfer.length === 0,
    androidInCoreTransfer.join(', ') || 'core-transfer is platform free',
  );

  const uriColumnLeaks = production
    .filter((f) => uriColumnOutsideCoreData(f.path, f.text))
    .map((f) => f.path);
  check(
    'transfer URI columns are named only inside the core-data layer',
    uriColumnLeaks.length === 0,
    uriColumnLeaks.join(', ') || 'core-data only',
  );

  const compositions = production
    .filter((f) => composesStorageWithTransport(f.text))
    .map((f) => f.path);
  check(
    'no production file composes the storage module with the transport module',
    compositions.length === 0,
    compositions.join(', ') || 'no composition site',
  );

  const transportBuild = readFileSync(join(ROOT, 'transport-lan', 'build.gradle.kts'), 'utf8');
  check(
    'the transport module does not depend on the storage module',
    !transportBuildDependsOnStorage(transportBuild),
  );

  const storageDocExists = existsSync(join(ROOT, STORAGE_BRIDGE_DOC_PATH));
  check('the storage bridge document exists', storageDocExists, STORAGE_BRIDGE_DOC_PATH);
  check(
    'the storage bridge document carries the URI and file-byte policy verbatim',
    storageDocExists &&
      storageDocCarriesVerbatimPolicy(readFileSync(join(ROOT, STORAGE_BRIDGE_DOC_PATH), 'utf8')),
  );
  const missingStorageTests = STORAGE_TEST_PATHS.filter((p) => !existsSync(join(ROOT, p)));
  check(
    'the storage bridge test suites are present at their exact paths',
    missingStorageTests.length === 0,
    missingStorageTests.join(', ') || 'all present',
  );

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
