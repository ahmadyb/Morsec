# Morsecode transfer protocol

The wire protocol and the transfer core that implements it, as delivered by
Milestone 3.

Everything described here lives in `:core-transfer`, which is a **Kotlin/JVM**
module: no Android class, no Room, no Hilt, no coroutine, no socket, no clock and
no `Thread.sleep` appears anywhere in its sources. That is not an aesthetic
choice — it is what lets the 435 tests that cover this document run on the JVM in
milliseconds, and what lets the same engine be driven by a LAN socket, by Nearby
Connections or by a test harness without changing a line of it. Its only declared
dependencies are `:core-model` and JUnit.

Companion documents:

- [`architecture.md`](architecture.md) — the module map and where each piece lives
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

`TransferSnapshotCodec` is a versioned, Room-independent `key=value` line format.

It is hand-rolled rather than generated, for three reasons:

- **Bounded.** Every field is length-checked on the way in, so a corrupt row
  cannot make the decoder allocate something huge.
- **Rejecting.** An unknown key, a duplicated key or a newer version is a typed
  error, not a default. A snapshot written by a future build describes state this
  build does not understand, and guessing is how a resumable offset becomes a
  corrupt file.
- **Uncoupled.** No annotation, no generated code, no Android class.
  `TransferSnapshotStore` is the seam a `:core-data` Room adapter implements;
  nothing in `:core-transfer` can tell the difference.

Values are escaped, so a stored value can never forge a line or a key. A failure
is restored from its code and classification rather than as its original subtype,
so a newer build can read an older build's rows.

The store contract's atomicity rules are part of the interface, not advice:

- `saveTransition` applies the state move *and* the durable parts of its effects
  in one transaction. A crash between writing the new state and writing its
  checkpoint must leave the **old** state on disk, never a new state over an old
  offset.
- `saveCheckpoint` may be called once per acknowledged chunk, so it must be cheap
  and idempotent for a given offset.
- Restoring must never promote optimistic bytes to confirmed bytes.

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

## 11. What this document does not cover

Everything below is out of scope for Milestone 3 and has no source in
`:core-transfer`:

- sockets, LAN/UDP discovery, Nearby Connections, Wi-Fi Direct
- foreground services, notifications, wake locks
- Android stream adapters, the Room implementation, Compose repositories, UI
  integration
- Media3 and the WebShare server and client

`FeatureReadiness.TRANSFER_ENGINE` remains pinned to milestone 5, and
`FeatureReadiness.CURRENT_MILESTONE` remains 2, as a production-readiness gate.
Nothing in this milestone turns the engine on for users; it makes the engine
real, correct and testable so that milestone 5 has something to wire up.
