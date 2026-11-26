# Transfer storage bridge

Slice 3 builds the storage side of a transfer — a source that can be re-opened at an offset, and a
destination that can be written sequentially, verified and published — without connecting either of
them to a transport. Nothing in this slice sends or receives a transfer frame. `TRANSFER_ENGINE`
stays gated, `CURRENT_MILESTONE` stays 2, and no runtime in the app composes these pieces.
The later inert Slice 4A runtime uses fake/in-memory ports only; its test-only typed-reader bridge is
not a production adapter. Slice 4S adds the separate API-23 storage observations below, not a real
runtime/storage composition. See [transfer-protocol.md §18](transfer-protocol.md#18-inert-single-file-runtime-slice-4a-payload-disabled)
and [§19](transfer-protocol.md#19-api-23-storage-evidence-slice-4s-payload-disabled).

This page is the normative description of that bridge. It records the port boundary, the
sequential-write invariant, the resume and reconciliation rules, and the diagnostics policy that the
storage layer is required to obey.

## URI and file-byte policy (normative)

The following is the policy. It applies to every storage adapter, every reducer that persists
transfer state, and every diagnostic path in the module. It is stated here in full so that a later
change cannot quietly weaken it by editing prose elsewhere.

Content URIs and required file metadata may be persisted only in the
dedicated transfer-state database fields required for restoration.

They must not appear in logs, crash reports, exception messages,
diagnostic summaries, analytics, or generic key/value metadata.

File bytes may exist only in the selected source, bounded in-memory
buffers, and controlled partial/final destinations. They must never be
written to logs, crash reports, or generic diagnostic storage.

### What "dedicated transfer-state database fields" means in practice

The Room schema already carries exactly three such fields on `transfer_items` — `source_uri`,
`target_dir` and `result_uri` — and they exist for restoration, not for display. This slice does not
add a column, remove a column, or change a schema version. The v1 and v2 export digests are unchanged
and are asserted unchanged by both the schema verifier and CI.

The policy is a restriction on *where* those values may flow, not a licence to widen the fields that
hold them:

- A URI may be read back to re-open a document. It is not a value a UI prints, a log line
  interpolates, or a crash report carries.
- A filename is metadata about a document, not a label for a progress line. The new runtime-facing
  reader, resume authorization and destination session have redacted string forms. Legacy storage
  objects are not safe to log merely because they have a `toString()` method.
- Legacy `TransferStorageError` subclasses have a local-only `diagnostic` field. Source fingerprint,
  source open, repositioning and typed-read failures never place provider exception text in that field
  or any other domain value. No diagnostic may be logged, persisted, surfaced or sent to crash reports.
- Digests are compared, stored where the schema says so, and never logged. They are also redacted in
  `toString()`, because a digest of a file the user did not intend to publish is still information
  about that file.

`TransferStorageError.InsufficientSpace` deserves a note because it is easy to get wrong: it reports
`requiredBytes` and `availableBytes` — both already-bounded numbers computed by the caller — and no
location, no name and no provider message.

## The port boundary

The runtime-facing decisions live in `core-storage`, in the `transfer` package. Source handles,
destination sinks and platform-specific adapters already existed; the new types wrap or compose
those abstractions rather than creating another storage hierarchy. `core-transfer` remains free of
Android storage types.

### Source read and ownership

`TypedSourceReader` wraps one existing `SourceHandle`. Its `read(buffer, offset, length)` returns
`Data(count)`, `EndOfSource` or `Failure(TransferStorageError)`. It rejects invalid ranges before
calling the provider, treats counts below -1 or above the request as provider faults, and fails on
the first zero-progress return for a positive-length request. It never caches the caller's buffer.
Provider and I/O exceptions are classified at this boundary without retaining exception messages,
causes or stacks. Cancellation is returned as `Cancelled`, not I/O, including Android provider
cancellation and bounded wrapped causes; for these storage descriptors, `InterruptedIOException` and
its subclasses are conservatively treated as cancellation. Coroutine owners must propagate that
outcome as cancellation. End of source, failure and cancellation close the handle; `close()` is
idempotent, including concurrent cancellation. A failure is terminal because a provider may have
consumed an unknown number of bytes. The owner reads serially and closes in `finally`.

`TypedSourceReader.openAt(...)` is an adapter-side factory: it turns the existing source opener's
result into a typed reader, redacts legacy open diagnostics and closes a handle returned at the wrong
offset. A nonzero offset requires a digest-bearing `SourceResumeDecision.Ready`; the factory compares
the stored fingerprint with a fresh source fingerprint again immediately before opening. The future
runtime must consume the typed reader and must not call `SourceHandle.read` directly. The older
low-level handle remains for compatibility with existing storage adapters.

### Destination

`DestinationSession` is the sequential-write gate. It is created by `DestinationSession.open(...)`
from four collaborators:

| Collaborator | Owns |
| --- | --- |
| `PartialSink` | bytes at an offset, flush, truncate, length |
| `CheckpointSink` | the confirmed frontier, persisted before it is trusted |
| `DestinationPublisher` | publication of a verified partial into its final location |
| `VerificationSource` | a bounded read of the partial for whole-file verification |

The session owns the *decisions*: which offsets are legal, when the frontier advances, when
verification may run, whether commit may proceed, and how abort and close behave. The adapter owns the
*mechanism*: a file descriptor, a `RandomAccessFile`, a `ParcelFileDescriptor`, a document provider.
That split is what keeps `File`, `Uri`, `ContentResolver` and provider exceptions out of the port.

A session moves through `OPEN → VERIFIED → COMMITTED`, or `OPEN → ABORTED`, or anything `→ CLOSED`.
`CLOSED` is reachable from every state, is idempotent, and closes owned resources exactly once. After
an interruption, including one in the same process, the future owner must discard the old session and
reopen from the persisted confirmed frontier with the retained digest. An unbound fresh session must
not be reused as a resumed one; the session cannot observe network interruption by itself.

### Capacity

`DestinationCapacity.preflight(...)` is a best-effort gate before the first byte is written. It is
described under [Free-space preflight](#free-space-preflight).

## The sequential-write invariant

One sentence: **a write is accepted only when the requested offset equals the physical length of the
partial, which equals the optimistic frontier.**

The three values are kept in lockstep deliberately. Any pair of them can be true while the third is
wrong — a partial can be the right length at the wrong offset, or the frontier can be optimistic
about bytes that are not there — and each such state would let a resumed transfer splice two
different files together. Keeping all three equal makes every divergence a refusal rather than a
guess.

Refused at the gate, by name, each with its own `SequentialRejection`:

| Rejection | Meaning |
| --- | --- |
| `NEGATIVE` | offset < 0 |
| `OFFSET_OVERFLOW` | `offset + length` would not fit in a `Long` |
| `HOLE` | offset > physical length: bytes in between would be unknown |
| `REWIND` | offset < frontier but not an overlap of the physical file |
| `OVERLAP` | the physical partial is longer than the frontier, so this offset lands inside existing bytes |
| `PAST_DECLARED_SIZE` | the write would exceed the size the sender declared |
| `EMPTY` | zero-length write; not an error in itself, but it is not progress either |
| `TOO_LARGE` | chunk larger than the bounded chunk buffer |
| `BUFFER_BOUNDS` | `dataOffset`/`length` do not describe a valid slice of the caller's array |
| `AFTER_VERIFICATION` | bytes added after the digest was taken would invalidate it |
| `AFTER_COMMIT` | the partial is now a published file |
| `AFTER_ABORT` | the partial was discarded |
| `AFTER_CLOSE` | the session is gone |
| `NOT_OPEN` | the session never opened, or is already in a terminal state |

**A failed or partial write never advances the confirmed frontier.** The sink may have accepted some
bytes — an `ENOSPC` after 4 KiB is the normal case — and those bytes are visible in the physical
length. Both returned failures and thrown write/flush exceptions are classified without provider
text. The frontier stays where it was, which turns the next write at that offset into an `OVERLAP`.
The caller's only way forward is an explicit `truncateTo(...)` to a legal offset, and then a retry.
That is intentional: the session refuses to build a story about bytes it did not see accepted.

**The eventual receiver, not the adapter, decides when to acknowledge.** `writeAt` advances the
*optimistic* frontier — the high-water mark this process believes it has written. The local
`acknowledge(offset)` promotion is monotonic and bounded by that frontier. When a checkpoint sink is
present it persists before moving the floor; a failed checkpoint leaves the floor unchanged. Without
a checkpoint sink it only changes in-memory state and cannot authorize a wire ACK. The receiver must
complete its required durability and checkpoint before it sends `CHUNK_ACK`, not wait for a peer to
acknowledge received bytes. Unsupported or unknown flush outcomes are not fsync-grade guarantees;
the current `acknowledge` call passes a supported-flush marker to its checkpoint sink rather than
retaining the actual earlier flush outcome. No runtime is connected here. The stronger reducer
barrier is specified in [transfer-protocol.md §17](transfer-protocol.md#17-reducer-contract-repair-inert-payload-disabled);
the separate, limited API-23 observations below do not reconcile that contract mismatch.

## Resume

The source-side sequence is: query a fresh fingerprint, compare it with the persisted fingerprint,
authorize the resume using the retained expected whole-file SHA-256, and open at the confirmed offset
through `TypedSourceReader`. `TransferSource.openAt` validates a known length and seeks, or delegates
to the bounded non-seekable discard loop. An offset past a known end is refused; an early EOF while
discarding is `source_shorter_than_offset`. No allocation is proportional to the offset.

`NonSeekableRepositioner` uses a constant 64 KiB buffer. Its bounded zero-progress policy prevents a
stalled stream from spinning indefinitely. A fingerprint query and a subsequent open are not an
atomic snapshot of a provider: the source can change between them or while it is read. The retained
expected digest and the final verification are required despite a metadata match.

### Fingerprint and retained digest policy

`SourceChangePolicy` compares **all** comparable fields before declaring a match or missing metadata:

- A different known identity requires restart.
- Different known sizes require restart **even when a strong identity matches**.
- Different known timestamps require restart even when identity and size match. A timestamp that a
  caller cannot regard as trustworthy must be recorded as unavailable, not invented.
- A matching identity identifies the logical object, not immutable file contents. A comparable
  metadata mismatch remains a restart even if some other field is unavailable.
- Missing size, timestamp or identity is not proof of equality. `SourceResumePolicy.authorize`
  permits such a resume only with the expected whole-file SHA-256 retained; an inaccessible source or
  revoked grant is refused. It requires that digest even when every field matches. The owner retains
  it in the approved transfer-state fields across restoration. A destination reopened with a nonzero
  confirmed frontier refuses to open without that retained digest. It binds the value for its whole
  session: `verify(null)`, a replacement expected digest, or a mismatch cannot lead to commit.
  `SourceResumeDecision.Ready.verify(session)` is a convenience that supplies its own retained value;
  it is not the enforcement mechanism. A hash without an expected value is not a comparison.

**Residual limitation:** a same-size, same-timestamp mutation can have the same logical identity and
escape fingerprint detection. Final verification against the retained expected whole-file SHA-256
detects an edit or splice present in the bytes it reads; no metadata comparison is a content-integrity
guarantee. Verification is not an atomic provider snapshot: an external same-length mutation after
verification may evade the commit-time length check. A future runtime needs provider-appropriate
exclusive ownership or publication guarantees. The inert tests demonstrate the mismatch refusal.

### The cost of a non-seekable resume

A `content://` descriptor that cannot seek is resumed by reading and discarding offset bytes. For a
multi-gigabyte file at a large offset that is genuinely slow: the bytes cross the provider boundary
and are thrown away. It is bounded in memory (64 KiB) and checks interruption between reads; a
cancellation raised by a read is returned as `Cancelled`, and the opener closes its owned descriptor.
A single provider read that blocks indefinitely may not respond to interruption until the provider or
descriptor unblocks. The file never has to fit in memory, but the discard is still I/O the user waits
for; seekability is probed rather than guessed.

## Destination reconciliation

On open, the session compares what the checkpoint claims with what the partial actually contains.

| confirmed vs physical | Result |
| --- | --- |
| equal | resume; writing may continue at the frontier |
| physical > confirmed | `requiresTruncation = true`; writing is refused (`OVERLAP`) until an explicit `truncateTo` |
| physical < confirmed | refused: `partial_shorter_than_confirmed`; nothing may be regained |
| physical > declared size | refused: `partial_beyond_declared_size` |
| confirmed > declared size | refused: `confirmed_beyond_declared_size` |

Truncation is never automatic. The extra bytes are exactly what a crashed optimistic write leaves
behind, and they may also be the only evidence that something went wrong; a session that silently
deleted them would erase the state a reconciliation report needs. `truncateTo(offset)` therefore
requires `confirmed ≤ offset ≤ frontier`, and a truncation below the confirmed frontier or above the
optimistic one is a `StateConflict` naming which bound was violated.

`PartialReconciliation` remains the vocabulary the restoration coordinator uses for the *persisted*
side of this comparison; a `DestinationSession` consumes its conclusion rather than re-deriving it.

### Verification, publication, abort

- `verify(expectedDigest)` reads the whole partial through `VerificationSource` and moves to
  `VERIFIED` on a match. A mismatch returns `Mismatched` and leaves the session `OPEN`, because a
  caller may still truncate and retry. Provider exceptions during verification become redacted typed
  failures and cannot authorize commit. For a fresh session, a missing expected digest is recorded as
  `matchedExpected = false` — the bytes were read and hashed, but nothing was compared. A session
  bound to a retained digest refuses a null or different expected value before reading.
- `commit()` is refused unless the session is `VERIFIED`, the frontier equals the verified byte
  count, the sink still reports that length, and any retained resume digest equals the verified
  digest. Publication happens through
  `DestinationPublisher`; a failed publication leaves the session `VERIFIED` and publishes nothing
  twice.
- A second `commit()` is `CommitResult.Duplicate`. It does not republish.
- `abort()` runs the cleanup action at most once, is idempotent (`AlreadyAborted` on the second
  call), and is refused after a commit — a published file is not a partial.
- `close()` is idempotent, closes every owned resource once, and swallows close failures so that one
  broken resource cannot prevent the rest from closing.

## Free-space preflight

`DestinationCapacity.preflight(probe, remainingBytes, safetyMarginBytes = 0)` is best effort, and the
documents say so in three places because all three matter:

- It only runs where the destination exposes a number. `FreeSpaceProbe` returns `null` for "I cannot
  tell", and **unknown capacity is not an error** — it produces `UNKNOWN_CAPACITY` and the write is
  allowed to proceed.
- A pass is not a guarantee. Another app can fill the disk between the probe and the write, and the
  write-time `ENOSPC` still has to map to `StorageFull`. The classifier does that independently of
  the preflight.
- An unknown-length source cannot require a complete-size preflight. `remainingBytes = null` produces
  `UNKNOWN_REQUIREMENT`, which accepts.

The default margin is **zero**, and it is a parameter rather than a constant precisely because a
hidden margin is a policy change disguised as a safety feature: it would refuse a transfer that would
have fitted. `DestinationCapacity.DEFAULT_SAFETY_MARGIN_BYTES` is `0L` and must stay `0L` unless the
value is documented and configurable at the call site.

## Unicode and filenames

Full audit: `FilenameAndPathAuditTest` in `core-storage/src/test`. Summary of what is pinned:

- **No normalization, anywhere.** NFC and NFD spellings of the same word are different strings, are
  counted differently, and are passed through unchanged. A name is never trimmed, case-folded, or
  Unicode-normalized. The consequence, stated plainly: two peers can each hold what a human reads as
  the same filename and the storage layer will treat them as distinct, and a duplicate-suffix loop
  will not recognise a case-only collision on a case-insensitive provider.
- **Refused because they address something else:** empty, blank-only, `.`, `..`, `/`, `\`, NUL,
  ISO control characters, malformed UTF-16 (a lone surrogate).
- **Accepted verbatim:** leading and trailing whitespace, leading dots, `:`, `*`, `?`, `"`, `<`, `>`,
  `|`, and the Windows device names. A SAF `displayName` is not a path; refusing these would make
  Morsec stricter than the platform and would rewrite names the user chose.
- **Byte accounting is UTF-8, never `String.length`.** The per-segment limit is 127 bytes, the total
  relative-path limit is 512 bytes, and the depth limit is 32 segments. A name or suffix is truncated
  on a code-point boundary — never inside a surrogate pair — and a suffix that cannot fit is an error
  rather than an oversized name.
- **Destination names are never derived from unvalidated sender metadata.** The relative path is
  validated by `RelativeTransferPath` before a provider sees it, and the display name by
  `SafFilenamePolicy`.

## Diagnostics

- The runtime-facing reader, resume authorization and destination session redact their string forms:
  no URI, filename, path, descriptor number, digest or file byte is emitted. Legacy objects are not
  safe to log merely because they have a string form.
- `TransferStorageError.toString()` is `category:safeMessage` and nothing else. Legacy storage
  errors may carry a free-form `diagnostic` field; it is never logged, persisted or put into crash
  reports. The typed source boundary discards that field and never forwards provider exception text.
- MediaStore pending-row creation and descriptor opening classify unexpected provider exceptions,
  including cancellation, without retaining their text. Failed phase setup closes its descriptor
  best-effort. Publication and cleanup reconciliation use fixed details rather than provider text;
  app-private open failures also replace filesystem exception messages with safe text.
- The low-level `SourceHandle.read` remains count-based for compatibility and can throw. Before any
  future runtime use, `TypedSourceReader` wraps it and calls the shared `StorageFailureClassifier`;
  runtime call sites must not classify raw provider exceptions themselves.
- The reader and session do not retain caller chunk buffers. Ownership stays with the caller; tests
  inspect the reader and session and mutate the caller's buffer after writing. This is not a claim
  that an arbitrary external provider or the operating system never copies or retains data.

### What cannot be erased

Java and Android give no guarantee that a byte array written by a caller, or a buffer used inside a
`Cipher`, is ever zeroed after use. A best-effort wipe on a local array does not reach the JVM's
copies, a moving garbage collector, or the kernel's page cache, and an immutable provider buffer
cannot be wiped at all. This document therefore claims **no** erasure property. The requirement the
code does meet is narrower and checkable: no reference is retained, nothing is written to a log, and
every buffer is bounded.

## API 23 coverage and deterministic failure tests (Slice 4S)

`StorageBridgeApi23Test` remains a host-side Robolectric test, **not** device evidence. Slice 4S adds
`StorageSourceApi23Test` and `StorageDestinationApi23Test` in `core-storage/src/androidTest`, and
`RoomStorageApi23Test` in `core-data/src/androidTest`. CI runs those suites and `transport-lan` on the
actual API 23 emulator, inventories every source test method against its XML, and rejects any failure,
skip, missing module or missing method. The test methods assert the device API level. Only a passing
run on the exact committed SHA establishes the following observations; the source code alone does not.

The storage test APK supplies a uniquely named, non-exported provider with URI grants disabled. Its
manifest and controls exist only in `androidTest`. The installed-manifest test checks the provider's
flags and that its UID is the instrumentation process's UID before opening it through the real
`ContentResolver`. The build also inspects the actual test and application APK manifests and dex for
leakage. All documents, pipe ends and writer threads are bounded and cleaned up. The SAF grant check
is deliberately injected; this does **not** demonstrate taking or revoking a persisted platform grant.

| Device case | Assertions and boundaries of the evidence |
| --- | --- |
| source | A seekable `content://` file and non-seekable reliable pipes supply empty, unknown-length and multi-chunk content; nonzero seek or bounded discard reaches the exact offset. EOF before an offset is refused. Changed known-size metadata requires restart. A denied grant prevents open, and a provider `SecurityException` becomes a redacted typed failure. |
| descriptor and stream ownership | A real `ProviderSourceOpener` pipe handle closes its owned descriptor and derived stream once at EOF, even when the reader is closed repeatedly; the early-EOF open failure also leaves its descriptor invalid. The counters measure close attempts, while descriptor validity is separately observed. |
| mid-read provider fault | The test writer delivers 17 of 128 bytes and calls `closeWithError`. The API 23 raw `FileInputStream` path used by the typed opener sees premature EOF, not a thrown provider exception; a separately opened reliable descriptor reports `IOException` when `checkError()` is explicitly called after EOF. The declared-length `DestinationVerifier` fails, the expected full-file digest mismatches, and the test publisher is not called. |
| sequential private file | A real app-private partial receives sequential chunks and calls `FileChannel.force(true)`; the descriptor is closed. Separately, a `RandomAccessFile` descriptor invokes `FileDescriptor.sync()` successfully, is closed, and the same file is reopened with the expected bytes, length and digest. The two operations are not conflated. |
| provider write | A real `SafWriteHandle.flush()` calls `FileDescriptor.sync()` on this test provider's regular-file descriptor, closes once, and reopens with the expected bytes and length. The adapter still returns `FlushAttemptedGuaranteeUnknown`, not a provider durability guarantee. |
| short/full write | A test-only wrapper writes at most five real bytes, then reports a short write or throws a synthetic `ENOSPC`. Confirmed progress remains unchanged, overlap is refused, explicit truncation to the confirmed frontier is forced, and the resulting private file is closed and reopened. No disk is filled; the checkpoint sink here is test-only. |
| verification and lifecycle | Full digest verification after reopen precedes the test publisher; a mismatch cannot publish, duplicate commit is refused and abort closes/deletes the test partial once. The publisher is a test file copy, not the production SAF commit coordinator. |
| Room v2 | A new real v2 Room/SQLite file retains a checkpoint, rejects a stale CAS predecessor, and retains a provisional verified state across close/reopen. An injected SQLite trigger makes a completion save fail after an explicitly simulated publication report; the reopened row remains unresolved. A separate simulated commit event yields a completed row after reopen. No publisher, migration, schema change or process-restart executor is joined. |

The declared-length check above is an explicit caller of `DestinationVerifier`; the destination
session's own `verify` hashes its current frontier and is not, by itself, a declared-length gate.
The digest mismatch independently prevents this particular short source from being committed. A
future real composition must enforce both the confirmed declared length and the expected digest;
these separate tests are not evidence that one is wired today. Android may keep an already-open
descriptor usable after revocation, and behavior of external providers may differ from this controlled
provider. Existing JVM tests additionally exercise cancellation and typed exception classification.

**Durability limit:** the only device claim is successful invocation of the specified force/sync,
closing the descriptor, and reopening to observe the expected bytes and length. Neither this nor the
provider's `sync()` proves physical flash persistence through power loss; filesystem, kernel, emulator,
hardware and provider behavior remain outside the observation. Closing/reopening Room is likewise not
a crash, power-loss or cross-process recovery test.

## Inert composition

The shape a real transfer would use exists, and is exercised by tests, but no production caller
composes it:

```
source adapter → typed reader → bounded chunk buffer → destination adapter → flush
                            → expected digest verification → commit
```

There is no socket in this path, no payload framing record, and no interpretation of transfer
frames. Those belong to Slice 4. The composition is a test fixture, not a runtime, and no app entry point references
it. Storage adapters reference no payload framing, no socket, no TLS, no PAKE and no payload
authority; the multiplexer references no storage type; and no production code composes storage with
transport. Those are asserted as absence checks by `tools/verify/jpake-production-boundary.mjs`,
each with a mutation test proving the predicate can fail.

## Status

Until this hardening revision passes its exact-SHA gate, the status is:

`PAYLOAD SLICE 3 HARDENING REQUIRED — PAYLOAD DISABLED`

The hardening gate requires the exact committed revision to pass the complete CI, verifier, schema and
repository-state checks. A previous green checkpoint does not satisfy that gate for later edits. Only
when the exact revision passes may its verified result be reported as:

`PAYLOAD SLICE 3 COMPLETE — STORAGE BRIDGE HARDENED, PAYLOAD DISABLED`

This conditional statement is not CI evidence. `CURRENT_MILESTONE` remains 2, `TRANSFER_ENGINE` remains
unavailable, the payload gate is unconditional, and this slice adds no service, worker, notification,
UI or Room change.
