#!/usr/bin/env node
/*
 * Payload Slice 4R: static contract and inertness guard. This is supplementary
 * to compiled reducer/store tests; it does not execute effects or prove device
 * durability. --self-test applies independent in-memory source mutations to the
 * real checkout and requires the corresponding guard to fail.
 */
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const SELF_TEST = process.argv.includes('--self-test');
const prefix = 'core-transfer/src/main/kotlin/app/morsecode/core/transfer/';
const paths = {
  reducer: `${prefix}reducer/TransferReducer.kt`,
  events: `${prefix}event/TransferEvent.kt`,
  effects: `${prefix}effect/TransferEffect.kt`,
  snapshot: `${prefix}model/TransferSnapshot.kt`,
  table: `${prefix}reducer/TransferTransitionTable.kt`,
  resume: `${prefix}resume/ResumeNegotiator.kt`,
  persistence: `${prefix}persistence/TransferSnapshotStore.kt`,
  transition: `${prefix}reducer/TransitionResult.kt`,
  frame: `${prefix}protocol/FrameType.kt`,
  payload: `${prefix}protocol/FramePayload.kt`,
  gate: `${prefix}session/SessionContracts.kt`,
  readiness: 'core-model/src/main/kotlin/app/morsecode/core/model/FeatureReadiness.kt',
  doc: 'doc/transfer-protocol.md',
  workflow: '.github/workflows/android-ci.yml',
  schema1: 'core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json',
  schema2: 'core-data/schemas/app.morsecode.core.data.db.MorseDatabase/2.json',
};
const editableCore = new Set([
  `${prefix}command/TransferCommand.kt`, paths.effects, paths.events, paths.snapshot,
  paths.persistence, paths.frame, paths.payload, paths.resume,
  `${prefix}protocol/ResumeDecision.kt`, paths.reducer, paths.table, paths.transition,
]);
const pinned = {
  schema1: 'b0bca4243d2f0ba4631e3338e611d3bcaff8ba456de83b79ea0106ae187ac488',
  schema2: '7e6acfd9c03b0214dcaddc2f5d1ceb175588f5cfcf93cca5437617be24b2b074',
  readiness: '1c4eff9bdc0c77a4a7f51367d6fd4934bffe40f9d40386201350e83bac87c142',
  gate: '5757bdd3fca9732a03102577bcdbd040f2360d92f1ecedaeee257d1525cf0ea8',
  production: '43552a979e7c719ff833ca6ecae595b918d872c649821883c469cbbeb7da0f51',
  enums: '3bc58162858ab2f1882da52eba7dd1acdae6fdffaec4a4825e889d4c71db117f',
};
const skipDirs = new Set([
  '.git', '.gradle', '.cache', '.agent-backups', 'node_modules', 'build', 'dist',
  'out', 'target', '.next', '.venv', 'ci-logs', 'Unselected files',
]);
function walk(dir, output = []) {
  if (!existsSync(dir)) return output;
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    if (ent.isDirectory()) {
      if (!skipDirs.has(ent.name)) walk(join(dir, ent.name), output);
    } else if (ent.isFile()) {
      output.push(relative(ROOT, join(dir, ent.name)).replaceAll('\\', '/'));
    }
  }
  return output;
}
function sha(value) { return createHash('sha256').update(value).digest('hex'); }
function collect() {
  const result = new Map();
  for (const file of walk(ROOT)) {
    if (file.includes('/src/main/') || /(^|\/)build\.gradle\.kts$/.test(file) ||
        file === 'settings.gradle.kts' || file === 'gradle/libs.versions.toml' ||
        file === paths.doc || file === paths.workflow || file === paths.schema1 || file === paths.schema2) {
      result.set(file, readFileSync(join(ROOT, file)));
    }
  }
  return result;
}
const source = collect();
function text(files, path) { return files.get(path)?.toString('utf8') ?? ''; }
function fingerprint(files, select) {
  // Do not depend on the host's locale/ICU sorting rules (CI uses a fresh OS).
  const entries = [...files.entries()].filter(([path]) => select(path)).sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0);
  return sha(entries.map(([path, bytes]) => `${path}\0${sha(bytes)}\n`).join(''));
}
function protectedProduction(files) {
  return fingerprint(files, path =>
    (path.includes('/src/main/') && !editableCore.has(path)) ||
    /(^|\/)build\.gradle\.kts$/.test(path) || path === 'settings.gradle.kts' ||
    path === 'gradle/libs.versions.toml');
}

// Mask comments but preserve Kotlin code and quoted strings; nested block
// comments and triple-quoted strings are handled so prose cannot satisfy checks.
function withoutComments(input) {
  let out = '', i = 0, mode = 'code', depth = 0;
  while (i < input.length) {
    const two = input.slice(i, i + 2), three = input.slice(i, i + 3), c = input[i];
    if (mode === 'code') {
      if (two === '//') { out += '  '; i += 2; mode = 'line'; }
      else if (two === '/*') { out += '  '; i += 2; mode = 'block'; depth = 1; }
      else if (three === '"""') { out += three; i += 3; mode = 'triple'; }
      else if (c === '"' || c === "'") { out += c; i++; mode = c; }
      else { out += c; i++; }
    } else if (mode === 'line') {
      if (c === '\n') { out += '\n'; mode = 'code'; } else out += ' ';
      i++;
    } else if (mode === 'block') {
      if (two === '/*') { out += '  '; i += 2; depth++; }
      else if (two === '*/') { out += '  '; i += 2; if (--depth === 0) mode = 'code'; }
      else { out += c === '\n' ? '\n' : ' '; i++; }
    } else if (mode === 'triple') {
      if (three === '"""') { out += three; i += 3; mode = 'code'; }
      else { out += c; i++; }
    } else {
      out += c; i++;
      if (c === '\\' && i < input.length) { out += input[i++]; }
      else if (c === mode) mode = 'code';
    }
  }
  return out;
}
function kotlin(files, path) { return withoutComments(text(files, path)); }
function fn(code, name) {
  const match = new RegExp(`\\b(?:private|public|internal)\\s+fun\\s+${name}\\s*\\(`).exec(code);
  if (!match) return '';
  const rest = code.slice(match.index + match[0].length);
  const next = /\n    (?:private|public|internal)\s+(?:fun|val|const\s+val)\b/.exec(rest);
  return code.slice(match.index, next ? match.index + match[0].length + next.index : code.length);
}
function ordered(code, markers) {
  let last = -1;
  for (const marker of markers) { const n = code.indexOf(marker, last + 1); if (n < 0 || n <= last) return false; last = n; }
  return true;
}
function count(code, re) { return [...code.matchAll(re)].length; }
function enumFingerprint(files) {
  const declarations = [];
  for (const [path] of files) {
    if (!path.startsWith('core-transfer/src/main/') || !path.endsWith('.kt')) continue;
    for (const match of kotlin(files, path).matchAll(/\benum\s+class\s+(\w+)/g)) declarations.push(`${path}:${match[1]}`);
  }
  return sha(declarations.sort().join('\n'));
}
function verificationProvisional(files) {
  const body = fn(kotlin(files, paths.reducer), 'onVerificationSucceeded');
  return body.includes('allowedStates = VERIFYING_STATES') && !body.includes('TransferState.COMPLETED') &&
    !body.includes('TransferEffect.SendFileComplete(') && !body.includes('TransferEffect.SendVerificationResult(') &&
    body.includes('snapshot.verification.allowsCommit') && body.includes('event.reportedTotalBytes != snapshot.totalBytes') &&
    body.includes('observedDigest = event.observedDigest') && body.includes('verification = verification');
}
function receiverOnlyCommit(files) {
  const code = kotlin(files, paths.reducer), body = fn(code, 'onVerificationSucceeded');
  const validate = fn(code, 'validateReceiverCommit');
  return count(code, /TransferEffect\.CommitVerifiedDestination\s*\(/g) === 1 &&
    /effects\s*=\s*\{\s*current\s*->\s*if\s*\(current\.direction\s*==\s*SessionDirection\.INBOUND\)\s*\{\s*listOf\(TransferEffect\.CommitVerifiedDestination\(current\.transferId,\s*current\.snapshotVersion\)\)\s*\}\s*else\s*\{\s*persist\(current\)\s*\}\s*\}\s*,\s*\)/.test(body) &&
    /snapshot\.direction\s*!=\s*SessionDirection\.INBOUND/.test(validate) &&
    validate.includes('snapshot.state != TransferState.VERIFYING') &&
    validate.includes('!snapshot.allBytesConfirmed || snapshot.verification?.allowsCommit != true');
}
function precommitContract(files) {
  const effect = text(files, paths.effects), store = text(files, paths.persistence);
  const code = kotlin(files, paths.reducer);
  return effect.includes('executor MUST durably save the accepted provisional verified snapshot') &&
    effect.includes('at [verifiedSnapshotVersion], using the last durable revision for CAS') &&
    effect.includes('If\n     * that prerequisite fails, do not publish') &&
    /class CommitVerifiedDestination\([\s\S]*?val verifiedSnapshotVersion:\s*Long/.test(kotlin(files, paths.effects)) &&
    store.includes('Before physical\n * publication, that effect must first persist the provisional verified revision') &&
    fn(code, 'onCancelLocally').includes('snapshot.verification?.allowsCommit == true') &&
    fn(code, 'onRetry').includes('snapshot.verification?.allowsCommit == true') &&
    fn(code, 'onSkip').includes('snapshot.verification?.allowsCommit == true');
}
function checkpointRequest(files) {
  const body = fn(kotlin(files, paths.reducer), 'onChunkDurablyStored');
  return /snapshot\.direction\s*!=\s*SessionDirection\.INBOUND/.test(body) &&
    /current\.copy\(optimisticBytes\s*=\s*end\)/.test(body) &&
    !/\bconfirmedBytes\s*=(?!=)/.test(body) && !body.includes('TransferEffect.SendAcknowledgement(') &&
    ordered(body, ['TransferEffect.PersistSnapshot(', 'TransferEffect.PersistConfirmedOffset(']) &&
    body.includes('snapshot.optimisticBytes != snapshot.confirmedBytes') && body.includes('checkChunkRange(');
}
function acknowledgementBarrier(files) {
  const code = kotlin(files, paths.reducer), body = fn(code, 'onConfirmedOffsetPersisted');
  const validate = fn(code, 'validatePendingCheckpoint');
  return count(code, /TransferEffect\.SendAcknowledgement\s*\(/g) === 1 &&
    body.includes('validatePendingCheckpoint(snapshot, event.candidateOffset, event.eventName)') &&
    /confirmedBytes\s*=\s*event\.candidateOffset/.test(body) &&
    /lastAcknowledgedSequence\s*=\s*sequence/.test(body) &&
    ordered(body, ['TransferEffect.PersistSnapshot(', 'TransferEffect.SendAcknowledgement(', 'TransferEffect.NotifyUi(']) &&
    !body.includes('TransferEffect.PersistConfirmedOffset(') &&
    /snapshot\.direction\s*!=\s*SessionDirection\.INBOUND/.test(validate) &&
    validate.includes('candidate > snapshot.totalBytes') && validate.includes('candidate <= snapshot.confirmedBytes') &&
    validate.includes('candidate != snapshot.optimisticBytes') && validate.includes('snapshot.chunkSize.value');
}
function checkpointFailure(files) {
  const code = kotlin(files, paths.reducer), fail = fn(code, 'onConfirmedOffsetPersistenceFailed');
  return fail.includes('validatePendingCheckpoint(snapshot, event.candidateOffset, event.eventName)') &&
    fail.includes('TransferError.PersistenceFailure("checkpointed", event.storageFull)') &&
    fail.includes('if (error.retryable) TransferState.FAILED_RETRYABLE else TransferState.FAILED_FINAL') &&
    fail.includes('optimisticBytes = current.confirmedBytes') && !/\bconfirmedBytes\s*=(?!=)/.test(fail) &&
    !fail.includes('TransferEffect.SendAcknowledgement(') &&
    ordered(fail, [
      'TransferEffect.PersistSnapshot(snapshot.transferId, snapshot.snapshotVersion)',
      'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)',
      'TransferEffect.NotifyUi(', 'schedule(current)',
    ]);
}
function commitOrdering(files) {
  const code = kotlin(files, paths.reducer), ok = fn(code, 'onDestinationCommitSucceeded');
  const fail = fn(code, 'onDestinationCommitFailed');
  return ok.includes('validateReceiverCommit(snapshot, event.eventName)') && ok.includes('TransferState.COMPLETED') &&
    ordered(ok, [
      'TransferEffect.PersistSnapshot(snapshot.transferId, snapshot.snapshotVersion)',
      'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)',
      'TransferEffect.SendVerificationResult(', 'TransferEffect.SendFileComplete(',
      'TransferEffect.NotifyUi(', 'schedule(current)',
    ]) && fail.includes('validateReceiverCommit(snapshot, event.eventName)') &&
    ordered(fail, [
      'TransferEffect.PersistSnapshot(snapshot.transferId, snapshot.snapshotVersion)',
      'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)',
      'TransferEffect.SendVerificationResult(', 'TransferEffect.NotifyUi(', 'schedule(current)',
    ]) && /SendVerificationResult\(\s*current\.transferId,\s*true,/.test(ok) &&
    /SendVerificationResult\(current\.transferId,\s*false,/.test(fail) &&
    count(ok, /TransferEffect\.PersistSnapshot\s*\(/g) === 2 &&
    count(ok, /TransferEffect\.SendFileComplete\s*\(/g) === 1 &&
    count(fail, /TransferEffect\.PersistSnapshot\s*\(/g) === 2 &&
    fail.includes('if (error.retryable) TransferState.FAILED_RETRYABLE else TransferState.FAILED_FINAL') &&
    !fail.includes('TransferEffect.SendFileComplete(') && !fail.includes('TransferEffect.DeletePartialDestination(');
}
function verificationFailure(files) {
  const body = fn(kotlin(files, paths.reducer), 'onVerificationFailed');
  return body.includes('snapshot.state != TransferState.VERIFYING') &&
    body.includes('snapshot.verification?.allowsCommit == true') &&
    body.includes('current.direction == SessionDirection.INBOUND') &&
    body.includes('SendVerificationResult(current.transferId, false, current.totalBytes, null)') &&
    /listOf\(TransferEffect\.PersistSnapshot\(current\.transferId,\s*current\.snapshotVersion\)\)\s*\+\s*result\s*\+\s*listOf\(TransferEffect\.NotifyUi\(current\.transferId,\s*current\.state\)\)\s*\+\s*schedule\(current\)/.test(body) &&
    !body.includes('TransferEffect.SendFileComplete(') && !body.includes('TransferEffect.CommitVerifiedDestination(');
}
function senderCompletion(files) {
  const code = kotlin(files, paths.reducer), body = fn(code, 'onFileCompleteReceived');
  const verify = fn(code, 'onVerificationSucceeded');
  const resume = fn(code, 'onApplyResumeDecision');
  return /snapshot\.direction\s*!=\s*SessionDirection\.OUTBOUND/.test(body) &&
    body.includes('snapshot.state != TransferState.VERIFYING') && body.includes('snapshot.verification?.allowsCommit != true') &&
    body.includes('event.fileId != snapshot.fileId') && body.includes('event.totalBytes != snapshot.totalBytes') &&
    body.includes('event.digest != snapshot.verification.observedDigest') && body.includes('TransferState.COMPLETED') &&
    body.includes('persist(current) + schedule(current)') &&
    /PersistSnapshot\(current\.transferId,\s*current\.snapshotVersion\),\s*TransferEffect\.NotifyUi/.test(fn(code, 'persist')) &&
    !verify.includes('TransferState.COMPLETED') && /ResumeDecision\.AlreadyVerified\s*->\s*TransitionResult\.rejected/.test(resume) &&
    count(code, /\bTransferState\.COMPLETED\b/g) === 2;
}
function addressing(files) {
  const code = kotlin(files, paths.reducer), body = fn(code, 'guard');
  return count(code, /val guard = guard\(snapshot,\s*(?:command|event)\)/g) === 2 &&
    count(code, /if \(guard != null\) return handleGuard\(snapshot,\s*(?:command|event),\s*guard\)/g) === 2 &&
    body.includes('snapshot.sessionId != target.sessionId') && body.includes('Rejection.WrongSession(') &&
    body.includes('snapshot.transferId != target.transferId') && body.includes('Rejection.WrongTransfer(') &&
    body.includes('snapshot.recipientId != target.recipientId') && body.includes('Rejection.WrongRecipient(') &&
    body.includes('snapshot.state.isTerminal && !isTerminalIdiom(target)') && body.includes('Rejection.TerminalTransfer(');
}
function directionTrust(files) {
  const snapshot = kotlin(files, paths.snapshot), events = kotlin(files, paths.events), code = kotlin(files, paths.reducer);
  return /public\s+val\s+direction:\s*SessionDirection/.test(snapshot) && !/\bvar\s+direction\b/.test(snapshot) &&
    !/\b(?:val|var)\s+direction\s*:/.test(events) && count(code, /\bdirection\s*=(?!=)/g) === 1 &&
    code.includes('direction = command.direction') && /snapshot\.direction\s*!=\s*SessionDirection\.OUTBOUND/.test(fn(code, 'onChunkSent')) &&
    fn(code, 'beginVerification').includes('current.direction == SessionDirection.INBOUND');
}
function directionalEffects(files) {
  const code = kotlin(files, paths.reducer);
  const guarded = [
    ['onAccept', 'INBOUND'], ['onReject', 'INBOUND'], ['onStartReceiving', 'INBOUND'],
    ['onVerificationStarted', 'INBOUND'], ['onChunkDurablyStored', 'INBOUND'],
    ['validatePendingCheckpoint', 'INBOUND'], ['validateReceiverCommit', 'INBOUND'],
    ['onStartSending', 'OUTBOUND'], ['onPeerAccepted', 'OUTBOUND'],
    ['onChunkSent', 'OUTBOUND'], ['onChunkAcknowledged', 'OUTBOUND'],
    ['onConfirmedOffsetReceived', 'OUTBOUND'], ['onFileCompleteReceived', 'OUTBOUND'],
  ];
  if (!guarded.every(([name, dir]) => fn(code, name).includes(`snapshot.direction != SessionDirection.${dir}`))) return false;
  const expectedCounts = {
    SendHandshake: 1, SendHandshakeAccept: 1, SendHandshakeReject: 1, SendMetadata: 2,
    RequestSourceStream: 3, RequestDestinationPartial: 3, SendChunk: 0,
    SendAcknowledgement: 1, BeginVerification: 1, CommitVerifiedDestination: 1,
    SendVerificationResult: 3, SendFileComplete: 1, DeletePartialDestination: 2,
  };
  for (const [name, expected] of Object.entries(expectedCounts)) {
    if (count(code, new RegExp(`TransferEffect\\.${name}\\s*\\(`, 'g')) !== expected) return false;
  }
  return fn(code, 'onBeginNegotiation').includes('current.direction == SessionDirection.OUTBOUND') &&
    fn(code, 'openStreams').includes('current.direction == SessionDirection.OUTBOUND') &&
    fn(code, 'beginVerification').includes('current.direction == SessionDirection.INBOUND') &&
    fn(code, 'onVerificationFailed').includes('current.direction == SessionDirection.INBOUND') &&
    fn(code, 'onCancelLocally').includes('current.direction == SessionDirection.INBOUND') &&
    fn(code, 'onApplyResumeDecision').includes('current.direction == SessionDirection.INBOUND');
}
function implicitEof(files) {
  const code = kotlin(files, paths.reducer), report = fn(code, 'reportImplicitEof');
  const accepted = ['onAccept', 'onStartReceiving', 'onStartSending', 'onPeerAccepted'];
  return accepted.every(name => fn(code, name).includes('reportImplicitEof(current)')) &&
    report.includes('if (current.allBytesConfirmed)') &&
    report.includes('TransferEffect.DispatchAllBytesConfirmed(current.transferId, current.snapshotVersion)') &&
    ordered(fn(code, 'onConfirmedOffsetPersisted'), ['TransferEffect.PersistSnapshot(', 'TransferEffect.SendAcknowledgement(', 'reportImplicitEof(current)']) &&
    ordered(fn(code, 'applyConfirmedProgress'), ['TransferEffect.PersistConfirmedOffset(', 'persist(current)', 'reportImplicitEof(current)']) &&
    fn(code, 'onAllBytesConfirmed').includes('!snapshot.allBytesConfirmed') &&
    fn(code, 'onAllBytesConfirmed').includes('return beginVerification(snapshot, event.eventName)') &&
    !fn(kotlin(files, paths.effects), 'isWireEffect').includes('DispatchAllBytesConfirmed');
}
function checkpointRecovery(files) {
  const resume = fn(kotlin(files, paths.resume), 'negotiate');
  const code = kotlin(files, paths.reducer), adopt = fn(code, 'applyResumeAt'), decision = fn(code, 'onApplyResumeDecision');
  return resume.includes('val offset = receiver.confirmedBytes') &&
    resume.includes('return ResumeDecision.ResumeAt(offset)') &&
    !/offset\s*%\s*proposal\.chunkSize/.test(resume) &&
    /receiver\.verification\s*==\s*ReceiverVerificationState\.VERIFIED/.test(resume) &&
    adopt.includes('offset < snapshot.confirmedBytes') &&
    adopt.includes('offset > snapshot.confirmedBytes && snapshot.direction == SessionDirection.INBOUND') &&
    adopt.includes('lastAcknowledgedSequence = if (offset == current.confirmedBytes)') &&
    ordered(adopt, ['TransferEffect.PersistSnapshot(', 'TransferEffect.SendResume(', 'openStreams(current)', 'TransferEffect.NotifyUi(']) &&
    !adopt.includes('TransferEffect.PersistConfirmedOffset(') &&
    decision.includes('snapshot.confirmedBytes != 0L || snapshot.verification?.allowsCommit == true') &&
    kotlin(files, paths.snapshot).includes('state == TransferState.QUEUED && retryCount == 0 && confirmedBytes != 0L');
}
function orderedProgramContract(files) {
  const effect = text(files, paths.effects), result = text(files, paths.transition), store = text(files, paths.persistence);
  return effect.includes('one effect at a time in list order, stop on the first failure') &&
    result.includes('one at a time, stop on the first failure, and report the corresponding') &&
    result.includes('Never run later effects after a required failure') &&
    store.includes('an unknown outcome requires a store read') &&
    store.includes('Never skip revisions in stores requiring sequential compare-and-set') &&
    store.includes('must never roll back a published destination');
}
function directionDocumentation(files) {
  const frame = text(files, paths.frame), payload = text(files, paths.payload), doc = text(files, paths.doc);
  return /\/\*\*\s*Receiver\s*→\s*sender:[^*]*\*\/\s*FILE_COMPLETE\(14,/.test(frame) &&
    /\/\*\*\s*Receiver's acknowledgement[^*]*\*\/\s*public data class FileComplete/.test(payload) &&
    /\|\s*14\s*\|\s*`FILE_COMPLETE`\s*\|\s*receiver\s*→\s*sender\s*\|/i.test(doc) &&
    !/^.*\|\s*`FILE_COMPLETE`\s*\|\s*sender\s*→\s*receiver.*$/im.test(doc);
}
function frameIds(files) {
  const ids = [...kotlin(files, paths.frame).matchAll(/^\s*([A-Z_]+)\((\d+),\s*"([a-z_]+)",\s*hasPayload\s*=\s*(true|false)\)/gm)]
    .map(m => `${m[1]}:${m[2]}:${m[3]}:${m[4]}`);
  return ids.join(',') === [
    'HANDSHAKE:1:handshake:true', 'HANDSHAKE_ACCEPT:2:handshake_accept:true',
    'HANDSHAKE_REJECT:3:handshake_reject:true', 'FILE_METADATA:4:file_metadata:true',
    'RESUME_PROPOSAL:5:resume_proposal:true', 'RESUME_RESPONSE:6:resume_response:true',
    'DATA_CHUNK:7:data_chunk:true', 'CHUNK_ACK:8:chunk_ack:true',
    'PAUSE:9:pause:true', 'RESUME:10:resume:true', 'CANCEL:11:cancel:true', 'ERROR:12:error:true',
    'VERIFICATION_RESULT:13:verification_result:true', 'FILE_COMPLETE:14:file_complete:true',
    'SESSION_COMPLETE:15:session_complete:true',
  ].join(',');
}
function singleReducerAndEnums(files) {
  const declarations = [];
  for (const [path] of files) {
    if (!path.startsWith('core-transfer/src/main/') || !path.endsWith('.kt')) continue;
    for (const m of kotlin(files, path).matchAll(/\b(?:object|class|interface)\s+(\w*Reducer\w*)\b/g)) declarations.push(`${path}:${m[1]}`);
  }
  return declarations.length === 1 && declarations[0] === `${paths.reducer}:TransferReducer` &&
    enumFingerprint(files) === pinned.enums;
}
function inertProduction(files) {
  const code = [...editableCore].map(path => kotlin(files, path)).join('\n');
  const callers = [...editableCore].filter(path => path !== paths.reducer).map(path => kotlin(files, path)).join('\n');
  return protectedProduction(files) === pinned.production &&
    !/\b(?:class|object|interface)\s+\w*(?:Runtime|TransferEngine|TransportAdapter|StorageAdapter)\w*/.test(code) &&
    !/\bimport\s+(?:android\.|java\.net\.|javax\.net\.|app\.morsecode\.transport\.)/.test(code) &&
    !/\b(?:GlobalScope|Thread|Executors)\b/.test(code) &&
    !/\bTransferReducer\s*\.\s*(?:apply|enqueue)\s*\(/.test(callers);
}
function gates(files) {
  const readiness = text(files, paths.readiness), gate = text(files, paths.gate);
  return sha(files.get(paths.readiness) ?? '') === pinned.readiness && sha(files.get(paths.gate) ?? '') === pinned.gate &&
    /CURRENT_MILESTONE:\s*Int\s*=\s*2\b/.test(readiness) && /TRANSFER_ENGINE\("transfer_engine",\s*5\)/.test(readiness) &&
    /return\s+PayloadTransferDecision\.Refused\(SessionFailure\(SessionFailureCode\.SECURE_SESSION_REQUIRED\)\)/.test(gate);
}
function schemas(files) {
  return sha(files.get(paths.schema1) ?? '') === pinned.schema1 && sha(files.get(paths.schema2) ?? '') === pinned.schema2;
}
function ciIntegration(files) {
  const workflow = text(files, paths.workflow);
  const branch = /elif \[ "\$script" = tools\/verify\/transfer-reducer-contract\.mjs \]; then([\s\S]*?)\n\s*else/.exec(workflow)?.[1] ?? '';
  const jvm = /- name: JVM unit tests([\s\S]*?)(?=\n      - name:|$)/.exec(workflow)?.[1] ?? '';
  return /for script in [^\n]*tools\/verify\/transfer-reducer-contract\.mjs/.test(workflow) &&
    branch.includes('node "$script" --self-test || echo "VERIFIER FAILED: $script"') &&
    branch.includes('node "$script" || echo "VERIFIER FAILED: $script"') &&
    workflow.includes('grep -q "VERIFIER FAILED" ci-logs/15-verifiers.log && exit 1') &&
    jvm.includes('gradle_status=${PIPESTATUS[0]}') &&
    jvm.includes('if not paths or total == 0 or failed or skipped or malformed:') &&
    jvm.includes('if [ "$gradle_status" -ne 0 ]; then exit "$gradle_status"; fi');
}
const rules = [
  ['verification remains provisional and emits no success completion', verificationProvisional],
  ['destination commit is receiver-only and commit outcomes require verification', receiverOnlyCommit],
  ['provisional verification is a mandatory pre-publication durable prerequisite', precommitContract],
  ['durable chunk stages without advancing confirmation or acknowledging', checkpointRequest],
  ['ACK is emitted only after validated persistence success', acknowledgementBarrier],
  ['checkpoint failure preserves confirmation and never emits an ACK', checkpointFailure],
  ['commit success and failure effect programs are ordered', commitOrdering],
  ['verification failure effects are ordered and direction-aware', verificationFailure],
  ['sender completes only on matching FILE_COMPLETE after peer verification', senderCompletion],
  ['session, transfer, recipient and terminal guards apply before events', addressing],
  ['direction is immutable snapshot authority', directionTrust],
  ['all directional emission sites are guarded', directionalEffects],
  ['implicit EOF is local and triggered after acceptance or final confirmation', implicitEof],
  ['recovery retains the persisted checkpoint without guessing ACK sequence', checkpointRecovery],
  ['effect programs require serial execution and fail-stop ordering', orderedProgramContract],
  ['FILE_COMPLETE is receiver-to-sender in code and documentation', directionDocumentation],
  ['MSC1 frame names, IDs and payload declarations are unchanged', frameIds],
  ['one reducer and no newly introduced state enum', singleReducerAndEnums],
  ['no runtime or production wiring is introduced', inertProduction],
  ['payload gate and milestone remain unchanged and closed', gates],
  ['Room v1 and v2 are byte-identical', schemas],
  ['CI runs verifier mutations and fails on missing or skipped JVM results', ciIntegration],
];
function mutate(files, path, oldText, newText) {
  const old = text(files, path);
  if (!old.includes(oldText)) throw new Error(`mutation target missing in ${path}: ${oldText}`);
  const mutated = new Map(files); mutated.set(path, Buffer.from(old.replace(oldText, newText))); return mutated;
}
function mutateFn(files, name, oldText, newText) {
  const raw = text(files, paths.reducer), clean = kotlin(files, paths.reducer), body = fn(clean, name);
  if (!body.includes(oldText)) throw new Error(`mutation target missing in ${name}: ${oldText}`);
  const start = raw.indexOf(`fun ${name}(`), index = raw.indexOf(oldText, start);
  if (index < 0) throw new Error(`raw mutation target missing in ${name}`);
  const mutated = new Map(files);
  mutated.set(paths.reducer, Buffer.from(raw.slice(0, index) + newText + raw.slice(index + oldText.length)));
  return mutated;
}
function addFile(files, path, content) { const copy = new Map(files); copy.set(path, Buffer.from(content)); return copy; }
function runSelfTests() {
  const cases = [
    ['direct verification completion', verificationProvisional, () => mutateFn(source, 'onVerificationSucceeded', 'allowedStates = VERIFYING_STATES', 'allowedStates = setOf(TransferState.COMPLETED)')],
    ['observed digest not retained', verificationProvisional, () => mutateFn(source, 'onVerificationSucceeded', 'observedDigest = event.observedDigest', 'observedDigest = null')],
    ['premature success result during verification', verificationProvisional, () => mutateFn(source, 'onVerificationSucceeded', 'listOf(TransferEffect.CommitVerifiedDestination(current.transferId, current.snapshotVersion))', 'listOf(TransferEffect.SendVerificationResult(current.transferId, true, 0, null))')],
    ['FILE_COMPLETE during verification', verificationProvisional, () => mutateFn(source, 'onVerificationSucceeded', 'listOf(TransferEffect.CommitVerifiedDestination(current.transferId, current.snapshotVersion))', 'listOf(TransferEffect.SendFileComplete(current.transferId), TransferEffect.CommitVerifiedDestination(current.transferId, current.snapshotVersion))')],
    ['sender emits destination commit', receiverOnlyCommit, () => mutateFn(source, 'onVerificationSucceeded', 'current.direction == SessionDirection.INBOUND', 'current.direction != SessionDirection.INBOUND')],
    ['unverified destination can commit', receiverOnlyCommit, () => mutateFn(source, 'validateReceiverCommit', '!snapshot.allBytesConfirmed || snapshot.verification?.allowsCommit != true', 'false')],
    ['commit revision loses preflight requirement', precommitContract, () => mutate(source, paths.effects, 'executor MUST durably save the accepted provisional verified snapshot', 'executor may skip saving the provisional verified snapshot')],
    ['cancellation may discard a provisional destination', precommitContract, () => mutateFn(source, 'onCancelLocally', 'snapshot.verification?.allowsCommit == true', 'false')],
    ['retry may overwrite a provisional destination', precommitContract, () => mutateFn(source, 'onRetry', 'snapshot.verification?.allowsCommit == true', 'false')],
    ['skip may hide a provisional destination', precommitContract, () => mutateFn(source, 'onSkip', 'snapshot.verification?.allowsCommit == true', 'false')],
    ['ACK emitted from durability stage', checkpointRequest, () => mutateFn(source, 'onChunkDurablyStored', 'TransferEffect.PersistConfirmedOffset(current.transferId, end)', 'TransferEffect.PersistConfirmedOffset(current.transferId, end), TransferEffect.SendAcknowledgement(current.transferId, 0, 1, end)')],
    ['confirmed bytes advance in persistence request', checkpointRequest, () => mutateFn(source, 'onChunkDurablyStored', 'current.copy(optimisticBytes = end)', 'current.copy(confirmedBytes = end, optimisticBytes = end)')],
    ['checkpoint bypasses staged revision', checkpointRequest, () => mutateFn(source, 'onChunkDurablyStored', 'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)', 'TransferEffect.NotifyUi(current.transferId, current.state)')],
    ['multiple pending checkpoint candidates', checkpointRequest, () => mutateFn(source, 'onChunkDurablyStored', 'snapshot.optimisticBytes != snapshot.confirmedBytes', 'false')],
    ['ACK without persistence success', acknowledgementBarrier, () => mutateFn(source, 'onConfirmedOffsetPersisted', 'validatePendingCheckpoint(snapshot, event.candidateOffset, event.eventName)', 'null')],
    ['second ACK emission outside barrier', acknowledgementBarrier, () => mutateFn(source, 'onChunkDurablyStored', 'TransferEffect.PersistConfirmedOffset(current.transferId, end)', 'TransferEffect.PersistConfirmedOffset(current.transferId, end), TransferEffect.SendAcknowledgement(current.transferId, 0, 1, end)')],
    ['ACK overtakes persisted snapshot', acknowledgementBarrier, () => mutateFn(source, 'onConfirmedOffsetPersisted', 'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)', 'TransferEffect.NotifyUi(current.transferId, current.state)')],
    ['checkpoint past total accepted', acknowledgementBarrier, () => mutateFn(source, 'validatePendingCheckpoint', 'candidate > snapshot.totalBytes', 'false')],
    ['stale checkpoint accepted', acknowledgementBarrier, () => mutateFn(source, 'validatePendingCheckpoint', 'candidate <= snapshot.confirmedBytes', 'false')],
    ['nonpending checkpoint accepted', acknowledgementBarrier, () => mutateFn(source, 'validatePendingCheckpoint', 'candidate != snapshot.optimisticBytes', 'false')],
    ['oversized checkpoint accepted', acknowledgementBarrier, () => mutateFn(source, 'validatePendingCheckpoint', 'snapshot.chunkSize.value', 'snapshot.totalBytes')],
    ['checkpoint failure sends an ACK', checkpointFailure, () => mutateFn(source, 'onConfirmedOffsetPersistenceFailed', 'TransferEffect.NotifyUi(current.transferId, current.state)', 'TransferEffect.SendAcknowledgement(current.transferId, 0, 1, 1)')],
    ['checkpoint failure changes confirmed frontier', checkpointFailure, () => mutateFn(source, 'onConfirmedOffsetPersistenceFailed', 'current.copy(optimisticBytes = current.confirmedBytes, failure = error)', 'current.copy(confirmedBytes = event.candidateOffset, optimisticBytes = current.confirmedBytes, failure = error)')],
    ['checkpoint failure loses typed persistence error', checkpointFailure, () => mutateFn(source, 'onConfirmedOffsetPersistenceFailed', 'TransferError.PersistenceFailure("checkpointed", event.storageFull)', 'TransferError.DestinationUnavailable("failure")')],
    ['commit success notifications reversed', commitOrdering, () => mutateFn(source, 'onDestinationCommitSucceeded', 'TransferEffect.SendVerificationResult(', 'TransferEffect.SendFileComplete(')],
    ['commit failure emits completion', commitOrdering, () => mutateFn(source, 'onDestinationCommitFailed', 'TransferEffect.SendVerificationResult(current.transferId, false', 'TransferEffect.SendFileComplete(current.transferId, false')],
    ['completion overtakes completed snapshot persistence', commitOrdering, () => mutateFn(source, 'onDestinationCommitSucceeded', 'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)', 'TransferEffect.NotifyUi(current.transferId, current.state)')],
    ['commit failure emits success', commitOrdering, () => mutateFn(source, 'onDestinationCommitFailed', 'SendVerificationResult(current.transferId, false', 'SendVerificationResult(current.transferId, true')],
    ['sender sends receiver failure result', verificationFailure, () => mutateFn(source, 'onVerificationFailed', 'current.direction == SessionDirection.INBOUND', 'current.direction == SessionDirection.OUTBOUND')],
    ['verification failure skips persistence', verificationFailure, () => mutateFn(source, 'onVerificationFailed', 'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)', 'TransferEffect.NotifyUi(current.transferId, current.state)')],
    ['sender completes on verification alone', senderCompletion, () => mutateFn(source, 'onVerificationSucceeded', 'allowedStates = VERIFYING_STATES', 'allowedStates = setOf(TransferState.COMPLETED)')],
    ['sender completion skips file identity', senderCompletion, () => mutateFn(source, 'onFileCompleteReceived', 'event.fileId != snapshot.fileId', 'false')],
    ['sender completion skips length identity', senderCompletion, () => mutateFn(source, 'onFileCompleteReceived', 'event.totalBytes != snapshot.totalBytes', 'false')],
    ['sender completion skips digest identity', senderCompletion, () => mutateFn(source, 'onFileCompleteReceived', 'event.digest != snapshot.verification.observedDigest', 'false')],
    ['sender completion skips prior verification', senderCompletion, () => mutateFn(source, 'onFileCompleteReceived', 'snapshot.verification?.allowsCommit != true', 'false')],
    ['sender completion accepts inbound direction', senderCompletion, () => mutateFn(source, 'onFileCompleteReceived', 'snapshot.direction != SessionDirection.OUTBOUND', 'snapshot.direction != SessionDirection.INBOUND')],
    ['resume claim completes without FILE_COMPLETE', senderCompletion, () => mutateFn(source, 'onApplyResumeDecision', 'ResumeDecision.AlreadyVerified -> TransitionResult.rejected', 'ResumeDecision.AlreadyVerified -> commit')],
    ['wrong session reaches reducer handler', addressing, () => mutateFn(source, 'guard', 'snapshot.sessionId != target.sessionId', 'false')],
    ['wrong transfer reaches reducer handler', addressing, () => mutateFn(source, 'guard', 'snapshot.transferId != target.transferId', 'false')],
    ['wrong recipient reaches reducer handler', addressing, () => mutateFn(source, 'guard', 'snapshot.recipientId != target.recipientId', 'false')],
    ['duplicate terminal completion reaches handler', addressing, () => mutateFn(source, 'guard', 'snapshot.state.isTerminal && !isTerminalIdiom(target)', 'false')],
    ['untrusted event direction', directionTrust, () => mutate(source, paths.events, 'public sealed interface TransferEvent : TransferTarget {', 'public sealed interface TransferEvent : TransferTarget { val direction: SessionDirection')],
    ['outbound receiver rejection frame', directionalEffects, () => mutateFn(source, 'onReject', 'snapshot.direction != SessionDirection.INBOUND', 'false')],
    ['outbound destination open', directionalEffects, () => mutateFn(source, 'onStartReceiving', 'snapshot.direction != SessionDirection.INBOUND', 'false')],
    ['inbound source open', directionalEffects, () => mutateFn(source, 'onStartSending', 'snapshot.direction != SessionDirection.OUTBOUND', 'false')],
    ['inbound peer acceptance sends metadata', directionalEffects, () => mutateFn(source, 'onPeerAccepted', 'snapshot.direction != SessionDirection.OUTBOUND', 'false')],
    ['sender accepts receiver checkpoint event', directionalEffects, () => mutateFn(source, 'validatePendingCheckpoint', 'snapshot.direction != SessionDirection.INBOUND', 'false')],
    ['sender starts destination verification', directionalEffects, () => mutateFn(source, 'beginVerification', 'current.direction == SessionDirection.INBOUND', 'true')],
    ['zero-byte acceptance forgets local EOF', implicitEof, () => mutateFn(source, 'onAccept', 'reportImplicitEof(current)', 'emptyList()')],
    ['resumed receiver forgets local EOF', implicitEof, () => mutateFn(source, 'onStartReceiving', 'reportImplicitEof(current)', 'emptyList()')],
    ['empty sender acceptance forgets local EOF', implicitEof, () => mutateFn(source, 'onPeerAccepted', 'reportImplicitEof(current)', 'emptyList()')],
    ['last checkpoint forgets local EOF', implicitEof, () => mutateFn(source, 'onConfirmedOffsetPersisted', 'reportImplicitEof(current)', 'emptyList()')],
    ['last peer ACK forgets sender local EOF', implicitEof, () => mutateFn(source, 'applyConfirmedProgress', 'reportImplicitEof(current)', 'emptyList()')],
    ['EOF fires before all bytes confirmed', implicitEof, () => mutateFn(source, 'reportImplicitEof', 'current.allBytesConfirmed', 'true')],
    ['recovery discards persisted offset', checkpointRecovery, () => mutate(source, paths.resume, 'return ResumeDecision.ResumeAt(offset)', 'return ResumeDecision.RestartAtZero("restart")')],
    ['recovery guesses preceding ACK sequence', checkpointRecovery, () => mutateFn(source, 'applyResumeAt', 'lastAcknowledgedSequence = if (offset == current.confirmedBytes)', 'lastAcknowledgedSequence = if (offset > 0L)')],
    ['sender resume announces before persistence', checkpointRecovery, () => mutateFn(source, 'applyResumeAt', 'TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)', 'TransferEffect.NotifyUi(current.transferId, current.state)')],
    ['restart discards a confirmed checkpoint', checkpointRecovery, () => mutateFn(source, 'onApplyResumeDecision', 'snapshot.confirmedBytes != 0L || snapshot.verification?.allowsCommit == true', 'false')],
    ['ordered effect failures continue execution', orderedProgramContract, () => mutate(source, paths.transition, 'Never run later effects after a required failure', 'Continue later effects after a required failure')],
    ['FILE_COMPLETE KDoc reversed', directionDocumentation, () => mutate(source, paths.frame, 'Receiver → sender: the verified file was committed', 'Sender → receiver: the verified file was committed')],
    ['FILE_COMPLETE table reversed', directionDocumentation, () => mutate(source, paths.doc, '| 14 | `FILE_COMPLETE` | receiver → sender |', '| 14 | `FILE_COMPLETE` | sender → receiver |')],
    ['FILE_COMPLETE frame id changed', frameIds, () => mutate(source, paths.frame, 'FILE_COMPLETE(14,', 'FILE_COMPLETE(16,')],
    ['second transfer state enum', singleReducerAndEnums, () => mutate(source, paths.events, 'public sealed interface TransferEvent : TransferTarget {', 'public enum class TransferPhase { X }\npublic sealed interface TransferEvent : TransferTarget {')],
    ['second reducer', singleReducerAndEnums, () => mutate(source, paths.reducer, 'public object TransferReducer {', 'public object AlternateReducer {}\npublic object TransferReducer {')],
    ['new runtime source', inertProduction, () => addFile(source, `${prefix}runtime/SingleFileRuntime.kt`, 'class SingleFileRuntime')],
    ['production transport wiring', inertProduction, () => mutate(source, 'transport-lan/src/main/kotlin/app/morsecode/transport/lan/LanPeerDiscoveryProvider.kt', 'package app.morsecode.transport.lan', 'package app.morsecode.transport.lan\nimport app.morsecode.core.transfer.reducer.TransferReducer')],
    ['new reducer caller in editable production source', inertProduction, () => mutate(source, paths.effects, 'public sealed interface TransferEffect {', 'fun start() = TransferReducer.enqueue(x)\npublic sealed interface TransferEffect {')],
    ['changed destination adapter logic', inertProduction, () => mutate(source, 'core-storage/src/main/kotlin/app/morsecode/core/storage/transfer/DestinationSession.kt', 'private var confirmed: Long = initialConfirmedBytes', 'private var confirmed: Long = 0L')],
    ['milestone gate opened', gates, () => mutate(source, paths.readiness, 'CURRENT_MILESTONE: Int = 2', 'CURRENT_MILESTONE: Int = 5')],
    ['payload gate bypassed', gates, () => mutate(source, paths.gate, 'return PayloadTransferDecision.Refused(SessionFailure(SessionFailureCode.SECURE_SESSION_REQUIRED))', 'return PayloadTransferDecision.Allowed')],
    ['Room v1 changed', schemas, () => mutate(source, paths.schema1, '"version": 1', '"version": 9')],
    ['Room v2 changed', schemas, () => mutate(source, paths.schema2, '"version": 2', '"version": 9')],
    ['CI stops running reducer verifier mutations', ciIntegration, () => mutate(source, paths.workflow, 'elif [ "$script" = tools/verify/transfer-reducer-contract.mjs ]; then', 'elif [ "$script" = tools/verify/other.mjs ]; then')],
    ['CI permits skipped JVM tests', ciIntegration, () => mutate(source, paths.workflow, 'if not paths or total == 0 or failed or skipped or malformed:', 'if not paths or total == 0 or failed or malformed:')],
    ['CI masks Gradle failures', ciIntegration, () => mutate(source, paths.workflow, 'if [ "$gradle_status" -ne 0 ]; then exit "$gradle_status"; fi', 'echo done')],
  ];
  let failures = 0;
  for (const [name, rule, mutation] of cases) {
    let caught = false;
    try { caught = rule(source) && !rule(mutation()); } catch (error) { console.error(`  ERROR ${name}: ${error.message}`); }
    console.log(`  ${caught ? 'PASS' : 'FAIL'}  mutation: ${name}`);
    if (!caught) failures++;
  }
  console.log(`\n${cases.length - failures}/${cases.length} mutation checks passed, ${failures} failed`);
  return failures;
}
let failures = 0;
console.log('Reducer contract and inert payload verification');
for (const [name, rule] of rules) {
  let ok = false;
  try { ok = rule(source); } catch (error) { console.error(`  ERROR ${name}: ${error.message}`); }
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}`);
  if (!ok) failures++;
}
console.log(`\n${rules.length - failures}/${rules.length} checks passed, ${failures} failed`);
if (process.env.PRINT_REDUCER_FINGERPRINTS === '1') {
  console.log(`production=${protectedProduction(source)}`);
  console.log(`enums=${enumFingerprint(source)}`);
}
if (SELF_TEST) failures += runSelfTests();
if (failures) process.exit(1);
