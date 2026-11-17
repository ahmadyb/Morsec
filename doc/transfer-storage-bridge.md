# Transfer storage bridge

Slice 3 builds the storage side of a transfer — a source that can be re-opened at an offset, and a
destination that can be written sequentially, verified and published — without connecting either of
them to a transport. Nothing in this slice sends or receives a transfer frame. `TRANSFER_ENGINE`
stays gated, `CURRENT_MILESTONE` stays 2, and no runtime in the app composes these pieces.

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
- A filename is metadata about a document, not a label for a progress line. `toString()` on every
  storage type in this module is redacted; the identifier it holds is replaced with `[redacted]`.
- `TransferStorageError` subclasses carry a `diagnostic` field that may name a location. It is
  deliberately excluded from `toString()`, and it is not written to the database.
- Digests are compared, stored where the schema says so, and never logged. They are also redacted in
  `toString()`, because a digest of a file the user did not intend to publish is still information
  about that file.

`TransferStorageError.InsufficientSpace` deserves a note because it is easy to get wrong: it reports
`requiredBytes` and `availableBytes` — both already-bounded numbers computed by the caller — and no
location, no name and no provider message.

## The port boundary

Two Android-free ports live in `core-storage`, in the `transfer` package. They are the only types a
caller has to know about; the Android adapters sit behind them.

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
`CLOSED` is reachable from every state, is idempotent, and closes owned resources exactly once.

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
length. The frontier stays where it was, which turns the next write at that offset into an `OVERLAP`.
The caller's only way forward is an explicit `truncateTo(...)` to a legal offset, and then a retry.
That is intentional: the session refuses to build a story about bytes it did not see accepted.

**The runtime, not the adapter, promotes optimistic bytes.** `writeAt` advances the *optimistic*
frontier — the high-water mark this process believes it has written. The *confirmed* frontier moves
only when `acknowledge(offset)` is called, which is the caller's statement that the peer has
acknowledged those bytes. `acknowledge` is monotonic, bounded by the frontier, and persists the
checkpoint before it moves the floor. If the checkpoint fails, the confirmed frontier does not move
and the error is returned. This is the one place where the storage layer's idea of "done" is
deliberately weaker than the runtime's.

## Resume

The §4 sequence, implemented by `TransferSource.openAt` and by the provider opener beneath it:

1. Re-open the source at the persisted offset.
2. Take a fingerprint and compare it with the persisted descriptor.
3. Validate the offset against the length, when a length is known.
4. Seek when the descriptor is seekable; otherwise discard forward through a fixed buffer.
5. Stop if the stream ends before the offset.
6. Never allocate anything proportional to the offset.

Steps 4 to 6 are the ones that need care, and `NonSeekableRepositioner` is where they live:
`SKIP_BUFFER_BYTES` is a constant 64 KiB, the loop counts *bytes*, and a stream that returns zero
bytes `MAX_ZERO_PROGRESS_STEPS` times in a row is a typed `ZeroProgress` failure instead of an
infinite loop. An offset past the known end is refused at open time (`offset_past_end`); a stream that
ends early is `source_shorter_than_offset`. Both are `StateConflict`s, so they cannot be mistaken for
an I/O fault that might go away on retry.

### What a resumed read is *not* protected by

`SourceChangePolicy` ranks evidence. A provider identity outranks everything: if both sides carry one
and they match, the file is the same file, and a change of size or timestamp is an edit to it, not a
different file. The policy therefore reports `STRONG_MATCH` for an app-private file that grew after
the fingerprint was taken, because its identity — derived from its stable key — did not change.
Resuming is allowed on that evidence.

The consequence is recorded rather than hidden: **the guarantee against splicing two versions of a
file is whole-file digest verification before commit, not the fingerprint.** A resume that lands in
the middle of an edited file produces bytes that fail verification, and a session that fails
verification cannot commit. The fingerprint is an optimisation that saves re-reading a file that has
not changed; it is not the integrity check.

Without an identity — the SAF path, where a document id is not stable across a replace — a size
change is `DEFINITE_CHANGE`, a last-modified change with no identity is also `DEFINITE_CHANGE`, and
the transfer restarts. That occasionally re-reads a file whose metadata was merely touched. That is
the cheaper mistake.

### The cost of a non-seekable resume

A `content://` descriptor that cannot seek is resumed by reading and discarding offset bytes. For a
multi-gigabyte file at a large offset that is genuinely slow: the bytes cross the provider boundary
and are thrown away. It is bounded in memory (64 KiB), it is cancellable at every step, and it does
not require the file to fit anywhere. It is still I/O the user waits for, and it is the reason
seekability is probed once and recorded rather than re-guessed.

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
  caller may still truncate and retry. A missing expected digest is recorded as
  `matchedExpected = false` — the bytes were read and hashed, but nothing was compared.
- `commit()` is refused unless the session is `VERIFIED`, the frontier equals the verified byte
  count, and the sink still reports that length. Publication happens through
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

- Every storage type's `toString()` is redacted: no URI, filename, path, descriptor number, digest or
  file byte appears in it.
- `TransferStorageError.toString()` is `category:safeMessage` and nothing else. The free-form
  `diagnostic` field is not part of it.
- Provider exceptions are converted at the adapter edge into typed domain failures. A raw provider
  message never crosses into the domain port; where one is kept for a developer, it is in
  `diagnostic`, which is not logged, not persisted and not printed.
- No file bytes are retained after an operation completes, and no caller chunk buffer is cached or
  stored. Ownership of the chunk array stays with the caller; the sink copies what it stores, which
  the retention test asserts by mutating the caller's buffer afterwards and re-reading the sink.

### What cannot be erased

Java and Android give no guarantee that a byte array written by a caller, or a buffer used inside a
`Cipher`, is ever zeroed after use. A best-effort wipe on a local array does not reach the JVM's
copies, a moving garbage collector, or the kernel's page cache, and an immutable provider buffer
cannot be wiped at all. This document therefore claims **no** erasure property. The requirement the
code does meet is narrower and checkable: no reference is retained, nothing is written to a log, and
every buffer is bounded.

## Inert composition

The shape a real transfer would use exists, and is exercised by tests, but no production caller
composes it:

```
source adapter → bounded chunk buffer → destination adapter → flush
              → digest verification → commit
```

There is no socket in this path, no payload framing record, and no interpretation of transfer
frames. Those belong to Slice 4. The composition is a test fixture, not a runtime, and no app entry point references
it. Storage adapters reference no payload framing, no socket, no TLS, no PAKE and no payload
authority; the multiplexer references no storage type; and no production code composes storage with
transport. Those are asserted as absence checks by `tools/verify/jpake-production-boundary.mjs`,
each with a mutation test proving the predicate can fail.

## Status

Slice 3 is complete when the tests pass and CI is green, and its status line is:

`PAYLOAD SLICE 3 COMPLETE — STORAGE BRIDGE INERT, PAYLOAD DISABLED`

`CURRENT_MILESTONE` remains 2, `TRANSFER_ENGINE` remains unavailable, the payload gate is
unconditional, and this slice adds no service, worker, notification, UI or Room change.
