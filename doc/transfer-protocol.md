# Morsecode transfer protocol

The original file-transfer wire protocol and engine were delivered by Milestone 3. The
Milestone 4 Part A discovery/control envelope and Part B authenticated control-session
upgrade are documented separately in §11; neither changes the existing 44-byte
transfer-frame format or adds a payload path. §13 specifies a proposed payload record
layer for authenticated LAN sessions. It is framing only: code exists, nothing in
production emits or accepts one of its records, and payload transfer remains disabled.
§14 records the decisions the payload keys depend on and §15 the payload-key slice that is
implemented and dormant: exporter labels, a key schedule, an in-memory AEAD record layer and
the ownership chain that carries the schedule, with the gate still refusing every session.

Everything described in the transfer-engine sections lives in `:core-transfer`, a
**Kotlin/JVM** module: no Android class, Room, Hilt, coroutine, socket, system clock, or
`Thread.sleep` appears in its sources. It exposes only an injected monotonic-clock interface
for deterministic peer expiry. The original 439 engine tests run on the JVM, and the same
core remains usable from LAN, Nearby or a test harness. Its only declared dependencies are
`:core-model` and JUnit.

Companion documents:

- [`architecture.md`](architecture.md) — the module map and where each piece lives
- [`lan-session-foundation.md`](lan-session-foundation.md) — Milestone 4 Part A discovery and control session
- [`security/milestone-4-part-b.md`](security/milestone-4-part-b.md) — authenticated control-session upgrade, transcript, approval and records
- [`AI_HANDOFF.md`](AI_HANDOFF.md) — session state and the completion gate
- [`decisions/ADR-0001-toolchain.md`](decisions/ADR-0001-toolchain.md) — pinned versions

---

## 1. Design rules

Four rules govern everything below.

**1. Pure core, imperative shell.** The reducer is a function,
`(TransferSnapshot, TransferInput) -> TransitionResult`. It reads nothing and
touches nothing. Every side effect — send a frame, open a stream, write a row —
comes back as a `TransferEffect` value that the caller decides how to perform.
The core never performs one.

**2. Lengths are data, not requests.** Every length that arrives from the wire is
compared against `ProtocolLimits` *before* it is used to allocate, to slice or to
loop. A 32-bit payload length is summed as a `Long` and bounded against
`Int.MAX_VALUE` before it is ever narrowed to an `Int`. There is no code path in
which an untrusted number becomes an allocation.

**3. Confirmed and optimistic bytes are different numbers.** `confirmedBytes` is
what the peer has acknowledged. `optimisticBytes` is what we have written.
Optimistic bytes are a hint for progress bars and nothing else: they are reset on
pause and on disconnect, they are never used as a resume offset, and a process
restart must never turn one into the other.

**4. Errors are values, and they are redacted.** Every failure is a
`TransferError` that answers six questions — what is it, can it be retried, is it
final, who caused it, which subsystem owns the fix, and what can safely be shown.
A detail field is put through `ErrorDetailRedactor` on construction, which strips
absolute paths, Windows drive paths, control characters, multi-line text and long
opaque blobs, and cuts the result to a byte budget without splitting a character.
A SHA-256 digest deliberately survives redaction: an audit trail that cannot name
the mismatching digest is useless.

---

## 2. Framing

Every frame is a fixed 44-byte header followed by a payload. All multi-byte
values are **big-endian**, so a frame written on one device reads identically on
any other.

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | 4 | magic | `MSC1` (`0x4D 0x53 0x43 0x31`) |
| 4 | 1 | version | protocol version; 1 is the only legal value today |
| 5 | 1 | type | frame type id, 1–15 |
| 6 | 2 | flags | reserved; must be zero, non-zero is a skippable error |
| 8 | 4 | payload length | unsigned, bounded by `MAX_PAYLOAD_BYTES` |
| 12 | 8 | sequence | signed; `-1` means "not applicable" |
| 20 | 8 | offset | signed byte offset; `-1` means "not applicable" |
| 28 | 4 | payload CRC32 | CRC-32/ISO-HDLC over the payload |
| 32 | 1 | session id length | bounded by `MAX_ID_LENGTH_BYTES` |
| 33 | 1 | transfer id length | bounded by `MAX_ID_LENGTH_BYTES` |
| 34 | 1 | recipient id length | 0 when the frame is not addressed to a peer |
| 35 | 5 | reserved | must be zero |
| 40 | 4 | header CRC32 | CRC-32/ISO-HDLC over bytes `0..39` |
| 44 | 3 × n | identifiers | session id, transfer id, recipient id, in that order |
| … | n | payload | |

Two checksums, for two different jobs:

- The **header CRC32** covers the length fields themselves. If it fails, no
  length can be trusted, so the frame is *fatal*: the stream has lost sync and
  the caller must close it.
- The **payload CRC32** covers the payload. If it fails, the header is
  trustworthy and the frame length is known, so the frame is *skippable*: the
  decoder reports how many bytes to discard and the stream survives.

That distinction is the whole reason a corrupt stream degrades instead of
desynchronising.

### Limits

Every bound the core enforces is a `const val` on
`app.morsecode.core.transfer.ProtocolLimits`. The table below is the complete
set, and it is checked rather than maintained: `tools/verify/transfer-limits.mjs`
parses this table and `ProtocolLimits.kt` and fails the build if any number here
differs from the constant it names, if a constant is missing from the table, or
if the table names a constant that no longer exists. The numeric values are
therefore always what the code enforces, and the parenthesised notes are only
there to say what the number means.

| Limit | Value | Why |
| --- | --- | --- |
| `PROTOCOL_VERSION_MIN` | 1 | oldest version this build will decode |
| `PROTOCOL_VERSION_MAX` | 1 | newest version this build will produce |
| `PROTOCOL_VERSION_CURRENT` | 1 | the version every frame this build emits carries |
| `HEADER_SIZE_BYTES` | 44 | fixed header; see the table above |
| `MAX_ID_LENGTH_BYTES` | 64 | identifiers are ASCII and short |
| `MAX_TEXT_LENGTH_BYTES` | 255 | display name, MIME type, error code |
| `MAX_RELATIVE_PATH_BYTES` | 512 | a whole relative transfer path, UTF-8 |
| `MAX_PATH_SEGMENT_BYTES` | 127 | one path component; most filesystems stop at 255 bytes, so this stays inside that with room for the extension |
| `MAX_PATH_SEGMENTS` | 32 | depth of a relative transfer path; the receiver recreates one directory per segment, and depth is a way to hide a file |
| `MAX_ERROR_DETAIL_BYTES` | 256 | a redacted detail that fits a log line and a UI row |
| `MAX_HANDSHAKE_CAPABILITIES_BYTES` | 256 | the capabilities string a peer advertises |
| `MIN_CHUNK_SIZE_BYTES` | 1,024 (1 KiB) | below this the per-chunk framing overhead dominates |
| `DEFAULT_CHUNK_SIZE_BYTES` | 65,536 (64 KiB) | used when the peers do not negotiate one |
| `MAX_CHUNK_SIZE_BYTES` | 262,144 (256 KiB) | one chunk must fit one frame |
| `MAX_PAYLOAD_BYTES` | 262,144 (256 KiB) | equals the maximum chunk, by definition |
| `MAX_FRAME_SIZE_BYTES` | 262,380 (44 + 3 × 64 + 262,144) | absolute ceiling on a frame: header + the three bounded identifiers + the bounded payload |
| `MAX_FILE_SIZE_BYTES` | 8,796,093,022,207 (8 TiB − 1) | `Long`-sized, API 23-safe |
| `SHA256_DIGEST_BYTES` | 32 | a digest in binary |
| `SHA256_HEX_LENGTH` | 64 | a digest as lowercase hexadecimal |
| `SNAPSHOT_VERSION` | 1 | persistence format stamp |
| `SNAPSHOT_VERSION_MIN` | 1 | oldest snapshot version this build will restore |
| `DEFAULT_MAX_RETRY_COUNT` | 5 | retries before a retryable failure becomes final |

`MAX_FRAME_SIZE_BYTES` is derived, not written down: it is
`HEADER_SIZE_BYTES + (3 * MAX_ID_LENGTH_BYTES) + MAX_PAYLOAD_BYTES` in the
source, so moving any of the three moves it. `ProtocolLimitsDocumentationTest`
re-derives the arithmetic from the constants and encodes a maximal frame to
confirm the encoder really emits exactly that many bytes.

`MAX_FILE_SIZE_BYTES` bounds a *file*, never an allocation. The largest single
allocation the core will make for a peer-supplied length is
`MAX_FRAME_SIZE_BYTES`, and it is made only after the declared lengths have been
validated against this table. A 5 GiB file is 20,480 maximum-size chunks plus a
short tail; nothing in the core ever holds more than one of them.

### The fifteen frame types

| Id | Type | Direction | Payload |
| --- | --- | --- | --- |
| 1 | `HANDSHAKE` | either | capabilities, max chunk size, resume supported |
| 2 | `HANDSHAKE_ACCEPT` | either | chosen chunk size, resume supported, resume offset |
| 3 | `HANDSHAKE_REJECT` | either | reason |
| 4 | `FILE_METADATA` | sender → receiver | a full `TransferFileDescriptor` |
| 5 | `RESUME_PROPOSAL` | sender → receiver | file id, size, chunk, digest, path, mtime, advertised bytes |
| 6 | `RESUME_RESPONSE` | receiver → sender | file id, a `ResumeDecision` |
| 7 | `DATA_CHUNK` | sender → receiver | raw bytes; the payload *is* the content |
| 8 | `CHUNK_ACK` | receiver → sender | offset, length, confirmed offset, accepted |
| 9 | `PAUSE` | either | reason |
| 10 | `RESUME` | either | from offset, reason |
| 11 | `CANCEL` | either | reason |
| 12 | `ERROR` | either | code, detail, retryable, origin, category |
| 13 | `VERIFICATION_RESULT` | receiver → sender | success, total bytes, observed digest |
| 14 | `FILE_COMPLETE` | receiver → sender | file id, total bytes, digest |
| 15 | `SESSION_COMPLETE` | sender → receiver | item count, total bytes |

---

## 3. The state machine

Twelve states, reused unchanged from `core-model`'s `TransferState`. The
canonical table lives in `TransferTransitionTable` and is rendered by
`TransferTransitionTable.toMarkdown()`; the test suite asserts the rendered table
matches the enum, so this document cannot drift from the code.

`TransferTransitionTable` is deliberately **stricter** than
`TransferState.allowedTransitions`. Three tightenings, each reviewable through
`divergenceFromCoreModel()`:

1. **A paused transfer resumes through `NEGOTIATING`, never straight back into
   `SENDING` or `RECEIVING`.** The confirmed offset has to be re-agreed with the
   peer before any byte moves.
2. **`FAILED_FINAL` is terminal.** core-model allows `FAILED_FINAL → CANCELLED`
   for the "dismiss this row" gesture; the core treats that as a UI-level removal,
   not a transfer transition.
3. **`PAUSED_LOCAL` and `PAUSED_REMOTE` may move into each other** so a
   both-sides pause is representable, but neither may reach anything except
   `NEGOTIATING`, the other paused state, or `CANCELLED`.

`NEGOTIATING → COMPLETED` exists for exactly one case: a
`ResumeDecision.AlreadyVerified` where the receiver already holds a verified copy
*and we know the expected digest*. No bytes move. It is not a shortcut past
verification — the verification happened on the peer, against a digest we hold.

---

## 4. The twenty-eight invariants

These are the rules the reducer enforces. Each is asserted by a test that names
its number, so a change here breaks a test there.

**Ownership**

1. Every state change goes through `TransferReducer`. No other component writes
   `state`.
2. The reducer is pure: the same `(snapshot, input)` pair always produces the
   same `(state, effects)` pair.
3. A rejected input leaves the snapshot unchanged, including its
   `snapshotVersion`.
4. An accepted input advances `snapshotVersion` by exactly one.

**States**

5. Only the twelve `TransferState` values are used.
6. Only the edges in `TransferTransitionTable` are traversable.
7. `COMPLETED`, `FAILED_FINAL`, `CANCELLED` and `SKIPPED` have no outgoing edges.
8. A terminal snapshot accepts only `SessionEnded` and `RemoteCancelled`, as
   no-ops; everything else is rejected.
9. No bytes move before negotiation completes.
10. `SENDING` and `RECEIVING` are entered only from `NEGOTIATING`.
11. `VERIFYING` is entered only when `confirmedBytes == totalBytes`.
12. `COMPLETED` is entered only from `VERIFYING`, or from `NEGOTIATING` through
    `AlreadyVerified` against a known digest.

**Addressing**

13. A command or event addressed to another session, transfer or recipient is
    rejected rather than applied.

**Byte accounting**

14. `confirmedBytes` never decreases.
15. `confirmedBytes` never exceeds `totalBytes`.
16. `optimisticBytes` is never less than `confirmedBytes`.
17. `optimisticBytes` falls back to `confirmedBytes` on pause and on disconnect.
18. A resume offset is always `confirmedBytes`; `optimisticBytes` is never one.
19. A chunk carries the sequence its offset implies, and never starts past the
    byte frontier; a chunk that skips bytes is refused.
20. A chunk length never exceeds the chunk size and never overruns `totalBytes`.
21. A zero-length chunk is rejected.
22. An acknowledgement never confirms more bytes than were sent.
23. `retryCount` only increases and never passes its ceiling; at the ceiling a
    retry is refused rather than performed.
24. A `FAILED_RETRYABLE` delivery returns to `QUEUED` before it can be retried.
25. Optimistic bytes are never promoted to confirmed bytes without an
    acknowledgement.
26. A restored snapshot keeps optimistic and confirmed bytes separate.
27. Verification is required before completion; a digest mismatch is a conflict,
    not a success.
28. Every rejection carries a typed error whose detail is bounded and redacted.

---

## 5. Resume

Resume is `ResumeNegotiator.negotiate(proposal, receiver) -> ResumeDecision`, a
pure function of two values.

The rule that governs all the others: **resume trusts confirmed bytes, and
nothing else.** The sender's own write position is carried in
`ResumeProposal.senderAdvertisedBytes` and deliberately never read. After an
interruption the sender's optimistic position is a guess, and resuming from a
guess produces a file that is silently truncated or silently duplicated.

Ten rules, in order:

1. Nothing stored → start at zero.
2. A different file occupies this transfer id → restart from zero.
3. The receiver holds more bytes than the file contains → reject.
4. A stored size that disagrees with the proposal → restart.
5. A stored descriptor fingerprint that disagrees → restart. The fingerprint
   covers the path, the name, the MIME type, the modification time, the chunk
   size and the expected digest in one comparison.
6. A stored chunk size that disagrees → restart, because partial chunks would be
   written at different boundaries.
7. Both sides know the expected digest and they disagree → restart.
8. The stored partial already failed its own verification → restart.
9. A complete partial is *not* a completed file: it still has to be verified.
   `VERIFIED` gives `AlreadyVerified`; anything else gives restart-from-zero.
10. An offset that is not on a chunk boundary cannot have been produced by this
    protocol, so the partial is suspect → restart.

A version check does not appear in that list on purpose: `ProtocolVersion`
refuses to be constructed outside the supported range, so an undecodable version
cannot reach the negotiator. The guard lives in the constructor instead of in
every consumer.

---

## 6. Integrity

Two checksums, at two different scopes, because they catch different failures.

- **CRC-32 per chunk** catches a corrupted chunk in transit. It is the
  retransmission signal: a chunk that fails its CRC is sent again, immediately,
  while the stream is still open.
- **SHA-256 over the whole file** catches everything CRC-32 cannot: a
  truncation, a duplication, a mixed file, a peer that lied. It is the
  end-to-end signal, and it is computed incrementally — `Sha256Accumulator`
  updates from a bounded buffer as chunks arrive and never holds the file in
  memory. A 3 GB file costs one 64 KiB buffer, not 3 GB of RAM.

Neither is optional. A file that passes CRC-32 chunk by chunk can still be the
wrong file.

---

## 7. Scheduling

`TransferScheduler.plan(input) -> SchedulerPlan` is a pure function from a queue
and a set of limits to an ordered list of decisions. It opens nothing and waits
for nothing.

- **Ordering is total and stable**: eligible items are sorted by `queueOrder`,
  then by transfer id, so two planners given the same queue always agree — and
  reordering the input list cannot change the output.
- **Three limits**: global, per session and per recipient. A busy delivery in
  another session consumes only the global limit.
- **A saturated recipient is skipped, not fatal**: one stalled peer never blocks
  the others.
- **Backoff timing is absent by design.** It belongs to an external clock/policy
  adapter. Leaving it out is what lets the scheduler be tested without waiting.

---

## 8. Broadcast (1 → N)

A broadcast is one *batch* of files delivered to N recipients, and the product
rule the UI shows is enforced in `BroadcastAggregator` rather than assumed:

```
expected deliveries = distinct files × distinct recipients
```

Distinctness is enforced, not trusted: the same file id arriving twice (a retried
delivery, a duplicated row) must not inflate a total, and a duplicated recipient
must not look like two receivers.

Isolation is structural. Every number for a recipient is computed from that
recipient's own snapshots against the whole batch. There is no shared counter a
failing peer can poison, which is what `BroadcastIsolationTest` asserts.

---

## 9. Persistence

`TransferSnapshotCodec` remains a bounded, versioned, Room-independent
`key=value` codec in `:core-transfer`. It is exercised by pure-core codec tests;
**the durable Room adapter does not store its output or any serialized snapshot
object.** Room stores the snapshot as typed columns and reconstructs a domain
value through an explicit mapper.

The codec's stand-alone guarantees are:

- **Bounded.** Every field is length-checked on decode, so malformed input cannot
  cause an unbounded allocation.
- **Rejecting.** Unknown keys, duplicate keys and unsupported versions are typed
  failures, not defaults.
- **Uncoupled.** No Room annotation or Android class appears in `:core-transfer`.
  `TransferSnapshotStore` is the seam implemented by `:core-data`.

The Room mapper applies the same strictness to persisted columns: enum tokens,
protocol versions, field bounds, digest representation, failure-field
completeness, booleans, identity ownership and cross-field snapshot invariants
are validated before a domain value is returned. Failure rows retain only a
bounded redacted code/detail and classification, never an exception or stack.

The store contract's atomicity rules are part of the interface, not advice:

- `saveTransition` applies the state move *and* durable effects in one
  transaction. A crash must leave either the complete old row or the complete
  replacement, never a mixed state/checkpoint.
- `saveCheckpoint` may be called once per acknowledged chunk, so it must be cheap
  and idempotent for a given offset.
- Restoring must never promote optimistic bytes to confirmed bytes.

The v2 Room adapter is `:core-data`'s `RoomTransferSnapshotStore`. It persists
descriptor metadata, reducer state, verification/failure fields, optimistic and
confirmed progress, and an adapter-owned monotonic `row_revision` as typed
columns—no serialized blob. Snapshot transitions compare the full previous
domain value, require the next reducer version, and atomically replace the row;
exact retries are idempotent, while stale/gapped replacements are typed
conflicts. Per-ack checkpoints update only `confirmed_bytes` and the CAS revision,
rejecting regression or an offset beyond the descriptor size.

The Room schema uses one persisted SHA-256 representation: exactly 64 lowercase
hexadecimal characters, parsed to a 32-byte `Sha256Digest` with defensive
copies. Default digest/error/entity `toString()` output is redacted; raw digest
bytes are never emitted to logs. The v1 schema remains immutable and v2 is
installed through explicit `Migration(1, 2)`. `transfer_sessions` has no
recovery-data foreign key: history retention is blocked while a snapshot or SAF
checkpoint still owns recovery data. Rename-history and pending-cleanup rows,
in contrast, are
checkpoint-owned children with foreign keys that cascade only when that exact
checkpoint parent is deleted. Opening the database does not start provider
recovery, a service, or a cleanup worker.

---

## 10. Error model

Every error answers six questions:

| Question | Field |
| --- | --- |
| What is it? | `code` — a stable snake_case string, safe to persist and compare |
| Can it be retried? | `retryable` |
| Is it final? | `isFinal` — always the negation of `retryable` |
| Who caused it? | `origin` — `LOCAL`, `REMOTE`, `UNKNOWN` |
| Which subsystem owns the fix? | `category` — protocol, storage, transport, integrity, permission, remote, local action, unknown |
| What can be shown? | `detail` — bounded and redacted |

Nothing else crosses a boundary. No absolute path, no secret, no file contents
and no stack trace reaches a log line, a UI row or a peer.

---

## 11. Milestone 4 Part A: discovery and control handshake

The transport-neutral contracts live in `:core-transfer/session`; their Android LAN
implementation lives in `:transport-lan`. The complete packet layout, lifecycle, security
boundary, resource limits and permission matrix are specified in
[`lan-session-foundation.md`](lan-session-foundation.md). In summary, Part A adds:

- bounded `MSD1` multicast beacon discovery on UDP 33457, with a CRC32 damage check;
- an explicitly started, five-minute-bounded discovery lease and monotonic deterministic
  peer registry;
- a selected-peer TCP 33456 control handshake using the separate bounded `MSH1` envelope;
- typed safe failures and aggregate-only diagnostics; and
- a `PayloadTransferGate` that refuses unless a future reviewed secure-session issuer
  supplies evidence. No such issuer exists in Part A.

The `MSH1` handshake is not one of the fifteen `MSC1` file-transfer frames described above.
The Part A negotiator forces payload, resume and secure-session bits off, even if a peer
advertises them. Discovery ids, source addresses, profile fields and CRC32 do not
authenticate peers. Part A alone has no cryptography or pairing and yields only
`ControlOnlyUnauthenticated` control sessions.

### Milestone 4 Part B: authenticated control-session upgrade

Part B adds an explicit `PairableControlSession` seam for upgrading one existing selected-peer
LAN control session. It uses pinned Conscrypt TLS 1.3 plus transcript-bound, human-compared
approval, mutual encrypted key confirmation, and a bounded `MSR1` AEAD control-record layer.
The exact transcript fields, failure policy, limits, key lifecycle, dependency/license
policy, and API 23 evidence are specified in
[`security/milestone-4-part-b.md`](security/milestone-4-part-b.md). Construction remains
inert and there is no automatic approval. This does not authenticate discovery beacons or
promote a peer based on LAN identity alone.

The Part B secure-session marker is issued only after exact-request explicit local approval
and both transcript-bound key confirmations. Payload, resume and file-data capabilities
remain absent; `PayloadTransferGate` continues to return `SECURE_SESSION_REQUIRED`. No
`MSC1` frame changes, file/chunk records, or transfer-engine calls are introduced.

`PermissionMatrix.lanDiscovery()` requests no runtime permission; discovery does not scan
Wi-Fi or read SSIDs. The LAN library declares its install-time network/multicast
permissions. `CURRENT_MILESTONE` remains 2 and `TRANSFER_ENGINE` remains gated; a dormant
provider binding does not activate the feature.

## 12. Boundary between the transfer engine and LAN foundation

This section distinguishes the pure JVM `:core-transfer` module from the Android adapter.
Sockets, Android network callbacks, multicast locks and their resource ownership are in
`:transport-lan`, never in `:core-transfer`. Nearby Connections and Wi-Fi Direct are not
implemented by Part A. Foreground services, notifications, wake locks, WorkManager,
transfer UI, Media3 and WebShare remain outside this scope. Android storage adapters, Room
v2 persistence and explicitly callable SAF restoration remain the separate Milestone 3
work; they add no Android or Room dependency to the pure core.

`FeatureReadiness.CURRENT_MILESTONE` remains intentionally 2 and `TRANSFER_ENGINE` remains
unavailable. The Part A discovery and Part B secure-control foundations are not product
integration and do not provide a usable file-sharing feature.

---

## 13. Payload transfer: the MPL1 record layer (proposed, inert)

**Status: proposed and inert.** This section specifies framing that exists as a bounded codec and
nothing else. No production code constructs, encodes, decodes, sends or accepts an MPL1 record;
there is no key schedule, no payload authority, no socket path, no storage call and no user-facing
transfer. `PayloadTransferGate` still refuses every session with `SECURE_SESSION_REQUIRED`,
`TRANSFER_ENGINE` is still gated, and payload transfer remains disabled in this build.

The protocol this envelope will eventually carry is experimental, project-defined and not
independently cryptographically reviewed. The owner accepted that risk on 2026-10-19 for this
personal application; the waiver permits the code to exist and establishes no security. See
[`security/owner-risk-waiver-2026-10-19.md`](security/owner-risk-waiver-2026-10-19.md). The profile of
record for pairing is **BC J-PAKE with `JPAKEPrimeOrderGroups.NIST_3072`**.

### 13.1 Why a second record layer, and why it lives in `:transport-lan`

The authenticated control layer (`MSR1`, §11) carries at most 4,096 bytes of plaintext per record
and 16 MiB per direction, and its four record types are control types only. Those numbers are a
control budget, not a transfer budget: file bytes do not fit, and reusing the layer would mean the
"control-only" property that the whole Part B verification rests on stops being true.

The alternative considered and rejected was a second authenticated connection. A second connection
would not inherit the pairing transcript binding, so it would either have to re-run pairing for
every transfer or authenticate nothing. Reusing TLS and deriving separate payload keys from the
same session keeps one authenticated channel and one authority chain.

The envelope therefore lives in `:transport-lan`:

| File | Contents |
| --- | --- |
| `transport-lan/.../payload/LanPayloadRecordFormat.kt` | magic, version, header size, offsets, the five pinned limits, session-id encoding |
| `transport-lan/.../payload/LanPayloadRecordCodec.kt` | header encode/decode, whole-record encode/decode, typed refusals |
| `transport-lan/src/test/.../payload/LanPayloadRecordCodecTest.kt` | behaviour, one refusal per test |
| `transport-lan/src/test/.../payload/LanPayloadRecordVectorsTest.kt` | fixed wire vectors, cross-format refusal, the two ceiling derivations |

The MSC1 frame and the transfer-domain limits stay in `:core-transfer`. MPL1 is a LAN byte-stream
concern, so it does not add a rule the transport-neutral core has to enforce.

### 13.2 Layout

All multi-byte values are **big-endian**. The header is fixed at 34 bytes.

| Offset | Size | Field | Notes |
| --- | --- | --- | --- |
| 0 | 4 | magic | `MPL1` (`0x4D 0x50 0x4C 0x31`) |
| 4 | 1 | version | 1 is the only value this build writes or reads |
| 5 | 16 | session id | the secure session id, as raw bytes |
| 21 | 1 | direction | 1 initiator → responder, 2 responder → initiator |
| 22 | 8 | sequence | signed 64-bit, non-negative |
| 30 | 4 | declared body length | **unsigned 32-bit** |
| 34 | *n* | body | exactly one MSC1 frame |
| 34 + *n* | 16 | authentication tag | always present, even for an empty body |

`4 + 1 + 16 + 1 + 8 + 4 = 34`.

There is deliberately **no outer record type byte**. The body is one MSC1 frame and that frame
already carries its own type; a second type field could disagree with it, and the disagreement would
be settled by whichever layer read last.

### 13.3 Why the length field is four bytes

A 64 KiB chunk with three maximum-length identifiers is a 65,772-byte MSC1 frame, and 65,772 does
not fit an unsigned 16-bit field. The control layer next door uses two bytes because its plaintext
ceiling is 4,096; copying that width here would have capped the body just under 65,536 — and a
decoder that narrowed a declared 65,772 into sixteen bits would have accepted a length it could not
reassemble.

The reader parses the four bytes into a `Long`, compares it against the fixed maximum, and only then
narrows it for arithmetic or allocation. A declared `0xFFFFFFFF` is a refusal, never `-1` and never
an allocation.

### 13.4 Limits

These constants are declared in `LanPayloadRecordFormat`. `tools/verify/jpake-production-boundary.mjs`
fails the build if either the source or this table stops saying the numbers below, and a JVM test
proves the derivation by encoding a real maximal MSC1 frame at the LAN chunk size.

| Constant | Value | Why |
| --- | --- | --- |
| `LAN_PAYLOAD_CHUNK_BYTES` | 65,536 (64 KiB) | the chunk the LAN payload profile offers; inside `MIN_CHUNK_SIZE_BYTES`…`MAX_CHUNK_SIZE_BYTES` and equal to the core's default |
| `MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES` | 65,772 | `44 + (3 × 64) + 65,536`: the largest MSC1 frame at that chunk size, with maximum-length identifiers |
| `PAYLOAD_RECORD_HEADER_BYTES` | 34 | `4 + 1 + 16 + 1 + 8 + 4` |
| `PAYLOAD_RECORD_TAG_BYTES` | 16 | the tag field that follows the body |
| `MAX_PAYLOAD_RECORD_WIRE_BYTES` | 65,822 | `34 + 65,772 + 16` |

`MAX_CHUNK_SIZE_BYTES` remains 262,144 in `:core-transfer`: the LAN profile chooses 64 KiB, it does
not change what the engine is allowed to carry on another transport.

### 13.5 One record, one frame, one allocation

The body is exactly one MSC1 frame, and the envelope does not parse it. A zero-length body is
structurally valid at this layer even though no meaningful MSC1 frame is empty; refusing that is the
frame decoder's job, and the two responsibilities are kept apart on purpose. A test asserts both
halves: an empty envelope decodes, and `FrameCodec.decode` refuses an empty buffer.

The largest allocation the codec makes is bounded by a frame the caller already holds. A record
whose received size exceeds `MAX_PAYLOAD_RECORD_WIRE_BYTES` is refused before any field is read; a declared body is
bounded before any array is sized from it.

### 13.6 Refusals

The decoder returns a typed reason and never throws on wire input. It refuses:

- fewer bytes than a header and a tag (`TRUNCATED`), and every truncation of the header itself;
- wrong magic, wrong version, unknown direction;
- a session id that is not this session's (`SESSION_MISMATCH`);
- a record travelling the other way (`DIRECTION_MISMATCH`) — reflection is not a feature;
- a sequence at or above 2^63, which arrives as a negative `Long` (`SEQUENCE_OUT_OF_RANGE`), and any
  sequence other than the one expected (`UNEXPECTED_SEQUENCE`);
- a declared length above 65,772 (`LENGTH_ABOVE_MAXIMUM`);
- a declared body or tag the frame does not fully contain (`LENGTH_INCONSISTENT`);
- a byte the header did not account for (`TRAILING_BYTES`);
- a frame larger than any this layer produces (`WIRE_SIZE_ABOVE_MAXIMUM`).

Sequence policy: non-negative signed 64-bit, checked for exact equality with the expected value. It
never wraps, because values at or above 2^63 are not representable and are refused rather than
masked. How many records one direction may carry is a question for the payload session that owns the
keys; this layer guarantees only that the number is never negative and never silently differs from
the expected one.

The two formats cannot be confused. `MPL1` and `MSR1` differ in all four magic bytes and in header
size, and tests drive each decoder with the other's bytes: a control header presented to the payload
decoder is refused as `WRONG_MAGIC`, and a payload record presented to `SecureRecordCodec` returns
null.

### 13.7 The tag and the associated data

The 16 tag bytes are carried, not verified. Authentication begins only when a later slice supplies
the key schedule and the payload authority that gates access to it.

When that slice exists, the **whole 34-byte header** will be the associated data for the AEAD, for
the same reason `MSR1` uses its header that way: the session id, direction, sequence and declared
length all have to be covered, or an attacker can move a valid body to a different sequence, a
different direction or a different session and the ciphertext will still decrypt. Keys and IVs will
be derived per direction from the TLS session through new exporter labels, so control and payload
never share a key stream and never share a sequence space.

### 13.8 What does not exist yet

- No key derivation, no AEAD, no tag verification.
- No payload authority: nothing can be claimed, consumed or serialized.
- No socket, no stream reader or writer, no framing on the wire.
- No production call site. The package is `internal`, and a verifier fails the build if a referenced
  file outside it mentions the codec or the format.
- No file, chunk, resume, scheduler, storage or UI work.
- No Room change: the schema stays v1/v2, byte-identical.
- `PayloadTransferGate` unchanged; `CURRENT_MILESTONE` still 2; `TRANSFER_ENGINE` still gated.

The next slice must settle these before any record is encrypted: who owns and constructs the payload
authority, how a single reader dispatches between `MSR1` and `MPL1` on one stream, how writes are
serialized with control messages taking priority, whether the control session's 300-second lifetime
also bounds an active transfer, and the exact exporter labels and key separation. §14 works through
all five as recommendations, none of them adopted.

---

## 14. Decisions the payload keys depend on (proposed; unresolved)

Section 13 ends with five questions. They are answered here as **recommendations for the owner to
accept, correct or reject** — not as settled design, and not as work in progress. Nothing in this
section changed a line of code: it was written from the state of the tree at
`35ac93974963140b9ecb3b71bcf2c1c78fe99db4`, and every claim below names the file and line it was read
from so a later reader can check it rather than trust it.

Each question gets the same shape: what exists today, the constraint that makes it a decision, the
options considered, the recommendation, and the property a test would have to prove.

Status of everything here: experimental, project-defined, not independently cryptographically
reviewed. The owner accepted that risk on 2026-10-19 for this personal application; see
[`security/owner-risk-waiver-2026-10-19.md`](security/owner-risk-waiver-2026-10-19.md). The profile of
record for pairing is **BC J-PAKE with `JPAKEPrimeOrderGroups.NIST_3072`**.

### 14.1 Who owns and constructs the payload authority

**What exists.** `AuthenticatedControlAuthority` is `internal`, has a private constructor, and is
obtainable only through `issue`, which takes concrete evidence — a `CONFIRMED` MODP session that is
identity-equal to the attempt's, an `APPROVE` decision on both sides, a matching transcript, an
unsettled attempt and a live clock (`AuthenticatedControlAuthority.kt:218` for `issue`, returning
at `:287`). `claim()` (`:118`) hands out the channel exactly once through an `AtomicBoolean`; there
is deliberately no test-only constructor. `LanPeerDiscoveryProvider.installSecureSession`
(`:1416`) is the only place a claimed channel is installed, and it takes the claimed channel
type rather than a socket plus a record layer, so "installed" and "authenticated" cannot drift
apart. Separately, `PayloadTransferGate.evaluate` returns `Refused` for every session and
`PayloadTransferDecision`
has no other branch at all (`SessionContracts.kt:451`).

**The constraint.** An authority that can be built from booleans, or copied, or read twice, is not
authority. Part B made "authenticated" a one-shot value rather than a status flag, and the payload
side has to inherit that property or it silently reopens what was closed. The static rule that backs
it — `PayloadTransferDecision` declares only `Refused`, no production source negotiates
`FILE_PAYLOAD` into a capability set — is in `tools/verify/secure-authority-surface.mjs`
(sections 2 and 7).

**Options.**

- **A — a second single-use type.** `AuthenticatedPayloadAuthority`, issued *by*
  `PakeSessionCoordinator` next to the control authority, holding the two payload AEADs and the two
  sequence bases, `internal`, private constructor, one `claim()`.
- **B — reuse the control authority with a capability flag.** Rejected: it is the status flag the
  current design deletes, a flag can be read twice and acted on twice, and it contradicts the
  documented control-only property that the Part B verification rests on.
- **C — derive payload keys at claim time from the exporter.** Rejected on two facts: the exporter
  belongs to `PairingChannel`, and `ConnectionRelease` (`PakeSessionCoordinator.kt:628`) closes the
  channel when the authority closes; and the transcript digest that is the exporter's context is
  wiped in `SecureLanPairingCoordinator` (`:331`–`:332`) when pairing ends. After pairing neither
  the exporter nor its context is alive.

**Recommendation: A**, with the issuing site being the coordinator for the same reason C is
rejected — that is the only place the exporter and the digest are alive at the same instant.
`LanPeerDiscoveryProvider` should never construct one; it should receive a claimed payload channel
the way it receives a claimed control channel. The payload authority must be claimed *separately*
from the control claim, not returned by the same call: two grants, so a payload capability cannot be
acquired by neglecting to refuse something.

**What would prove it.** A verifier section that allows the new type in its own file and its tests
only, asserts the constructor is private, asserts `claim` is guarded by one atomic transition, and
asserts no production file outside the coordinator names the issuing entry point. Plus a unit test
that a second `claim()` returns null and that `close()` before a claim makes it null.

### 14.2 One reader, two formats, one TLS stream

**What exists.** `SecureLanControlChannel.receive()` (`:144`) allocates a 33-byte header buffer, reads
it exactly (`readExact`, `:255`), decodes it with the expected direction, sizes the frame from the
decoded header, and reads the rest under one absolute deadline; a deadline closes the socket. During
pairing a different reader — `SocketPairingRecordIo` (`PairingChannel.kt:80`) — does the reading, and
`ConnectionRelease` closes it. **After pairing, nothing calls `receiveSecureControlRecord`**: the
interface declares it (`SessionContracts.kt:421`), the provider overrides it (`:1450`), and there is
no caller. So today's reader model is "one reader per phase, chosen by phase", not "one reader for
two formats".

**The constraint.** The two formats are both fixed-header and both length-prefixed, but the headers
are 33 and 34 bytes and the magics share no byte: `MSR1` and `MPL1` differ in all four. A reader that
reads 33 bytes first has already consumed 33 of a 34-byte MPL1 header before it can tell what it is
holding, and a reader that "resynchronises" by scanning forward for the next magic hands an attacker
a parse restart at a byte offset of the attacker's choosing.

**Options.**

- **A — read four bytes, decide, continue.** Exactly four bytes; `MPL1` → read the remaining 30 and
  hand to the MPL1 decoder; `MSR1` → read the remaining 29 and hand to `SecureRecordCodec`; anything
  else → fatal, close, no scanning. One stream, one reader, one order.
- **B — a second TLS connection for payload.** Rejected in §13.1: it does not inherit the pairing
  transcript binding, so it re-runs pairing or authenticates nothing.
- **C — an outer envelope that wraps both formats.** Rejected: the outer envelope needs its own
  authentication to be worth anything, at which point the inner tags are doing the same job twice.

**Recommendation: A.** It also settles a size problem for free: an MPL1 body reaches 65,772 bytes
(65,822 on the wire), far above the largest control record at 4,145, so the reader sizes its body
allocation from a header that has already been bounded — which both decoders already do — and four
bytes is the smallest read that can carry the discriminant.

**Consequence to record.** This changes `SecureLanControlChannel.receive()`, which is a Part B
component. That is why this belongs to the slice that owns the reader and not to a key-handling
slice: relaxing a proven reader is exactly the change that needs the full evidence burden the switch
had. It also removes the "no caller" state above, which is itself evidence that no payload path is
live.

**What would prove it.** A loopback test on a real socket pair over the composed TLS path: write an
`MSR1` `PING`, then an `MPL1` record, then an `MSR1` `PONG`, and assert the reader returns three
records in order with the right format and types. Then a test that a stream whose first four bytes are
neither magic is refused fatally and that the reader consumed exactly four bytes, not a window it
searched.

### 14.3 Serialized writes, and control taking priority

**Corrected 2026-10-30.** The first version of this section recommended writing a sealed record in
slices and interleaving a control record at a slice boundary. That was wrong, and the reason is
worth keeping: a socket write is not a protocol boundary. MPL1 and MSR1 are length-prefixed records,
and a peer's reader takes its frame boundary from the header it has already read. Bytes of another
record inserted partway through a record leave the peer holding a header whose body is half one
record and half another -- neither parseable, and no resynchronisation point that is not a guess.
The rules below replace that recommendation and are the ones the writer slice must implement.

**What exists.** `send()` (`:69`) takes `writeLock`, and inside it: seal the record through
`SecureRecordLayer.protect` (`SecureRecordLayer.kt:214`), arm one absolute write deadline
(`CONTROL_RECORD_WRITE_DEADLINE_MILLIS`, 5 s), write the whole frame, flush, and treat a write that
finishes just as the deadline fires as a failure. `protect` is `@Synchronized` and increments
`sendSequence` (`:253`) as part of sealing. A maximal MPL1 record is 65,822 bytes; the largest
possible control record is 4,145 (33 + 4,096 + 16).

**The rules, pinned now.**

- A complete MSR1 or MPL1 wire record is **contiguous** on the stream.
- One shared write mutex is held from the first byte of a record until `writeFully` and `flush`
  complete.
- Control priority chooses **which complete record is written next**. It cannot interrupt a record
  already being written.
- If any sealed record is only partially written, the connection is **terminal**. The record is never
  retried, because its sequence and nonce have already been consumed.

The 64 KiB payload profile bounds worst-case control delay to at most one complete payload-record
write. That is the trade the profile makes: the largest record a payload session can produce is
65,822 bytes, so a control record can wait for one record's transmission and no longer. It does not
wait for a file, a chunk loop, or a queue.

**Why the alternative was rejected.** Two facts collide. First, sealing consumes a sequence number:
once `protect` returns, the record exists in the sender's nonce space and re-sending it would reuse a
nonce, so an abandoned record must terminate the connection rather than be retried. Second, the
channel documents that it has **no internal worker pool and creates no detached task**
(`SecureLanControlChannel.kt:37`–`:42`), so "add a writer thread with a priority queue" deletes a
stated property of a component the Part B evidence covers. Slicing a record into separately locked
pieces was the third option and is the one that is now ruled out by the contiguity rule.

**What would prove it.** A socket-pair test with a deliberately small peer receive buffer: write a
maximal payload record while a control record is queued, assert the control record arrives after that
one record completes and inside its own deadline, and assert that a peer which stops reading ends in
a terminated connection with a timeout failure rather than in a stream it can still parse. A second
test writes two records and asserts the bytes on the wire are exactly the concatenation of the two
complete records, with no third interleaving.

### 14.4 Does the 300-second control session bound an active transfer

**Corrected 2026-10-30.** The first version of this section recommended that the gate predict
whether a transfer would fit in the remaining lifetime and refuse one that would not. That is
removed: a sender cannot predict throughput, so the prediction would be a guess dressed as a
precondition, and a refused transfer is worse than a transfer that stops cleanly.

**What exists.** `MAX_SESSION_LIFETIME_MILLIS = 300_000` is measured from the **start of pairing**
(`AuthenticatedControlAuthority.kt:239`), carried as `expiresAtElapsedMillis` into the channel
(`SecureLanControlChannel.kt:49`) and now also into the payload key schedule, enforced on every send
and every receive by `unavailableFailure()` (`:309`), and enforced independently by
`LanPeerDiscoveryProvider.expireSecureSessionIfNeeded` (`:1403`), which closes the session at the
same instant.

**The policy, pinned now.**

- The sender does **not** predict whether a transfer will fit within the remaining session lifetime.
- Transfer may continue only until the fixed session deadline.
- At expiry it stops at a confirmed record/chunk boundary, persists the confirmed offset, closes the
  session, and requires re-pairing before resume.

The boundary matters as much as the stop. A record that was sealed and not written is a terminal
connection failure (§14.3), so the last usable position is the last offset a peer has confirmed, not
the last offset this side sealed. Resume then begins from that confirmed offset under a new session,
which is what §5 already specifies -- the session is the unit that expires, not the transfer.

**What would prove it.** A unit test on the stop arithmetic: given a deadline and a confirmed offset,
the layer refuses the next protect at or after the deadline and reports the confirmed offset rather
than an unconfirmed one. On the device, a transfer long enough to cross the deadline: the session
closes at the deadline, the offset survives, and the resumed session starts there.

### 14.5 Exporter labels and key separation

**What exists.** The control layer makes four exporter calls — `EXPORTER-MORSEC-C2S-KEY-V1`,
`…-C2S-IV-V1`, `…-S2C-KEY-V1`, `…-S2C-IV-V1` (`PakeSessionCoordinator.kt:459`–`:462`) — each with the
transcript digest as context (`:531`–`:532`), through `EstablishedTlsSession.export`
(`ConscryptSecureSessionEngine.kt:261`), which requires a context of exactly 32 bytes and a length of
1 to 64. Keys are wiped as soon as the AEADs own them (`:465`–`:466`). One further output, the pairing
proof value, is taken at `:195`.

**A prose correction, with no protocol change.** The four calls are at `:459`–`:462`. Two comments
in the same file used to say *eight* exporter outputs — the header block (`:32`) and the KDoc above
`deriveRecordLayer` (`:453`–`:456`). The count was wrong and the code was right: four calls with
four distinct labels is exactly what the separation argument needs. Both comments now say four. No
key, label, byte or behaviour changed; the correction was made while this section's slice was
already editing that file for its own reasons.

**The constraint.** AAD does not create separation. Two AEADs keyed from the same key, with the same
nonce base and overlapping sequence ranges, are a nonce reuse whether or not their headers differ —
and the two formats do differ in header length (33 against 34), which makes the mistake easier to
miss. Reusing one exporter output split into halves makes the halves share the exporter's internal
chaining, which is why the control layer already spends four labels instead of two.

**Decision.** Four more outputs, labels distinct from the control set and from `PROOF_LABEL`, for
example `EXPORTER-MORSEC-PAYLOAD-C2S-KEY-V1`, `…-PAYLOAD-C2S-IV-V1`, `…-PAYLOAD-S2C-KEY-V1`,
`…-PAYLOAD-S2C-IV-V1`, derived in the same step as the control keys so both layers share one
derivation instant and one digest lifetime. Both are required together: **distinct keys** and
**distinct sequence spaces**. Either alone is insufficient; the recommendation is both.

**What would prove it.** A test that all nine labels are pairwise distinct; a test that the same
plaintext sealed by the two layers produces different ciphertext under the same nonce; a test that
neither layer accepts the other's record even when the plaintext lengths match; and a wipe test that
the payload keys are zeroed once the AEADs hold them.

### 14.6 Ordering, and what a slice may do without these answers

They are not independent. 14.5 is fixed by the code that already exists and can be decided now.
14.1 follows from 14.5 (the keys exist only during pairing, so the authority must be built there).
14.2 and 14.3 are about the live channel and change Part B components; each needs its own evidence
burden. 14.4 is a policy question that can wait until a byte actually moves.

That ordering suggests three slices rather than one, and this is a recommendation, not authorized
work:

1. **Payload key schedule and single-record sealing**, in the payload package, with keys supplied by
   the caller. No socket, no coordinator, no authority: the MPL1 codec from Slice 1 plus a
   seal/open step, tested against fixed vectors and against its own refusals.
2. **The payload authority** (14.1, 14.5 wired): issued in the coordinator, claimable once, still
   unreachable from the gate, still refusing.
3. **The reader and writer** (14.2, 14.3): the only slice that changes Part B components, and
   therefore the one that needs socket-backed tests over the composed TLS path before it is pushed.

§15 records which of these the payload-key slice implemented: the exporter schedule, the dormant
record layer and the ownership chain. The reader, the writer and the socket still do not exist.

Nothing in this section authorizes any of it. `PayloadTransferGate` still refuses every session,
`CURRENT_MILESTONE` is still 2 and `TRANSFER_ENGINE` is still gated: readiness is raised by an
implementation that passes its tests, never by a document. No part of the framing in §13 is
authenticated, reviewed or safe to rely on.

---

## 15. Payload keys and the dormant record layer (proposed, inert)

**Status: implemented, dormant, and unreachable from any transfer path.** The payload slice added
four exporter labels, one key schedule, one AEAD record layer and the ownership chain that carries
the schedule from the authenticated pairing to the control channel. Nothing claims the schedule in
production, no payload byte is written to a socket, `PayloadTransferGate` still refuses every
session, `CURRENT_MILESTONE` is 2 and `TRANSFER_ENGINE` is still gated. Everything here is
experimental, project-defined and not independently cryptographically reviewed; the owner accepted
that risk on 2026-10-19, and the waiver establishes no security.

### 15.1 The four payload exporter labels

| Purpose | Label |
| --- | --- |
| payload key, initiator → responder | `EXPORTER-MORSEC-PAYLOAD-C2S-KEY-V1` |
| payload nonce base, initiator → responder | `EXPORTER-MORSEC-PAYLOAD-C2S-IV-V1` |
| payload key, responder → initiator | `EXPORTER-MORSEC-PAYLOAD-S2C-KEY-V1` |
| payload nonce base, responder → initiator | `EXPORTER-MORSEC-PAYLOAD-S2C-IV-V1` |

Four outputs rather than one split in two, for the reason §14.5 gives: a split output makes both
halves share the exporter's internal chaining, and a shared output makes two purposes one key. The
labels are pinned as ASCII bytes by a JVM test and read from the source by the boundary verifier,
which also refuses a build where any of them equals a control, proof or channel-binding label.

### 15.2 Where derivation happens, and what a failure does

Inside `PakeSessionCoordinator`, after the control record layer is derived and before the authority
is issued -- the last moment the exporter and the transcript digest are both alive, since the digest
is wiped when the pairing function returns and the exporter dies with the connection. The context is
the same transcript digest the control keys use, so neither layer can be derived for a different
transcript by mistake.

An export failure closes the pairing and issues no authority: the same refusal path as any other
handshake failure, with no payload material left behind. There is **no on-demand exporter access
after pairing**. A caller cannot ask for payload keys later, because there is nothing left to ask.

### 15.3 The ownership chain

```
PakeSessionCoordinator derives the schedule
        │
        ▼
AuthenticatedControlAuthority owns it, and refuses to issue unless
it is bound to the same session, role, transcript and expiry
        │  claim() — once
        ▼
AuthenticatedControlChannel carries it
        │  installation
        ▼
SecureLanControlChannel owns it, unclaimed, and closes it with the channel
```

One chain, one owner at a time, and no sibling authority. The type has a private constructor, is
`internal` to `:transport-lan`, is not serializable, is not a data class, exposes no key bytes, and
prints only `[redacted]`. It can be claimed once; closing any parent before a claim destroys it;
closing the control channel after a claim closes the layer that was claimed from it; and a failed
installation destroys it explicitly, because the claim has already moved it out of the authority.
`SecurePairingRunResult.Authenticated` still carries only the socket and the authority, so no
independent payload grant appears in the pairing result.

### 15.4 What the record layer guarantees

`LanPayloadRecordLayer` is one MSC1 frame in, one complete MPL1 record out, and no socket anywhere.

- **Cipher.** AES-128-GCM through the `SecureRecordAead` the locked provider supplies. The layer
  takes an interface, so the JVM suite can drive it with the JDK provider and the device suite with
  Conscrypt; it never names a provider itself.
- **Associated data.** The whole 34-byte header: magic, version, session, direction, sequence,
  declared length. Move a body to another sequence, direction or session and the tag fails.
- **Keys and nonces.** The four labels above, per direction, with the payload's own sequence spaces
  starting at zero. Payload and control never share a key, an IV or a counter.
- **Framing before crypto.** Wrong magic, version, session, direction, sequence or length is refused
  before any ciphertext is touched, and the refusal names which of them it was.
- **Terminal failures.** A tag failure, an oversized plaintext, a spent record allowance, a clock
  that went backwards or a passed deadline closes the layer permanently. There is no retry, because
  a sequence that was consumed is consumed.
- **Limits.** Body ceiling 65,772 bytes, wire ceiling 65,822, and `MAX_PAYLOAD_RECORDS_PER_DIRECTION
  = 1,048,576` checked before a nonce is used -- 64 GiB in one direction at the 64 KiB profile.
- **Bounded before allocation.** A record above the wire ceiling is refused before any field is
  read or any array is sized from a declared length.
- **One frame per record, not validated here.** The body is one MSC1 frame by contract. A zero-length
  body is a structurally valid envelope and the frame decoder refuses it, which is where that
  judgement belongs; the layer does not become a second frame parser.

### 15.5 The rules that bind the writer slice

The record-boundary rules are in §14.3 and are normative for the slice that adds socket I/O: one
complete record is contiguous on the stream, one shared write mutex spans a record from first byte to
completed flush, control priority chooses the next record rather than interrupting the current one,
and a partially written record is a terminal connection failure. Nothing in this slice adds a writer
thread, a reader thread, a dispatcher or a socket read/write site.

### 15.6 What still does not exist

- No socket ownership of payload bytes: `SecureLanControlChannel` has the same one read site and one
  write site it had before, both control-only.
- No dispatcher deciding between `MSR1` and `MPL1` on one stream (§14.2).
- No writer serialization (§14.3), no transfer lifetime behaviour beyond the session deadline
  (§14.4), no resume, chunk loop, storage, scheduler or UI.
- No production claim of the payload capability. The seam exists and is uncalled.
- No readiness change: `TRANSFER_ENGINE` is still unavailable and the gate still refuses.
