# ADR-0003 — Android transfer storage adapters and durable persistence

- **Status:** accepted (2026-10-02)
- **Applies to:** `:core-storage` (Android source, destination, restoration, cleanup),
  `:core-data` (Room entities, migration, persistence adapter, clock), `:core-transfer`
  (unchanged contracts, one added decision vocabulary)
- **Supersedes:** nothing. **Amends:** the pre-implementation report for this group,
  which contained one contradiction (foreign keys) and several unstated assumptions
  (durability, SAF visibility, descriptor ownership, zero-progress policy) that are
  resolved here.
- **Delivery:** Milestone 3. `FeatureReadiness.TRANSFER_ENGINE` stays pinned to
  milestone 5 and `CURRENT_MILESTONE` stays 2. Nothing in this group turns the engine on.

## Context

The pure transfer core (Milestone 3, accepted at `0404eb7`, corrected at `1920035`)
describes *what* must happen and never *where the bytes live*. Its contracts already
name the adapter work:

- `TransferEffect.RequestSourceStream(transferId, fromOffset)`
- `TransferEffect.RequestDestinationPartial(transferId, expectedOffset)`
- `TransferEffect.PersistConfirmedOffset(transferId, confirmedOffset)`
- `TransferEffect.BeginVerification` / `CommitVerifiedDestination` /
  `DeletePartialDestination`
- `TransferSnapshotStore` in `core-transfer/persistence`

Nothing implements any of them. Android cannot be guessed at here: a resume offset that
was acknowledged but never flushed, a partial that is published before its SHA-256 is
checked, or a file descriptor closed twice are all failures that look like success until
a phone is restarted mid-transfer. The decisions below are the ones where "reasonable
defaults" produce data loss.

---

## Decision 1 — Room schema bootstrap: CI generates, a human (the agent) commits

**CI is read-only with respect to repository source.** No GitHub Action bot ever
commits, and no schema file is hand-authored.

The exported schema must be the *version-1* schema, produced before any entity or
version changes:

1. Keep `@Database(version = 1)` unchanged.
2. Verify `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` in
   `core-data/build.gradle.kts`.
3. Push a commit that makes CI run KSP (any build does) and upload
   `core-data/schemas/**` as an artifact.
4. Retrieve the generated `1.json`, inspect it, and commit it **explicitly**.
5. Obtain green CI with the committed `1.json` and no other change.

Only then: add the version-2 entities → bump `version = 2` → add `Migration(1, 2)` →
generate `2.json` the same way → commit it → run the migration test against the
committed, real `1.json`.

`fallbackToDestructiveMigration()` is not used anywhere, and no migration drops or
recreates an existing table.

### Retrieval deviation (recorded deliberately)

Actions artifact downloads are unreachable from the agent environment: `gh run download`
returns `EOF` against `productionresultssa*.blob.core.windows.net` for every artifact,
including a 44 KB one, so the download is blocked at the network layer rather than by
size. The workflow therefore **both** uploads `core-data/schemas/**` as an artifact (for
humans and other environments) **and** emits the schema as check-run `::notice`
annotations, base64+zlib compressed, which *are* reachable through the annotations API.
The agent reconstructs, inspects and commits the file. The constraints that matter are
unchanged: the bytes come from CI's KSP run, nothing is hand-authored, and no bot
commits.

---

## Decision 2 — Session ownership: no foreign key, adapter-enforced RESTRICT

The report proposed both an `onDelete = CASCADE` foreign key and no foreign key. That
contradiction is resolved in favour of **no foreign key**.

`transfer_sessions` is a *UI-owned* row. It is pruned by `deleteClosedBefore()` and
deleted outright by `delete(sessionId)` as part of ordinary history retention. A foreign
key from recovery data to it is therefore wrong in both directions:

- `CASCADE` would let routine history pruning silently delete snapshots and — worse —
  the partial-file rows that name the only copy of an incomplete download. The partial
  would be orphaned on disk with no record able to clean it up. Forbidden.
- `RESTRICT` would make legitimate retention pruning fail whenever a finished session
  still has a recovery row, coupling two independent lifecycles.

**Chosen design (option 2):**

- `transfer_snapshots.session_id` and `transfer_partials.session_id` are **opaque
  strings with an index and no `ForeignKey` annotation.** The database is not asked to
  enforce a relationship whose lifetimes are deliberately independent.
- **Referential validity is enforced in the persistence adapter.** A
  `SessionRetentionGuard` refuses to delete or prune a session while any recovery row
  still depends on it, where "depends on" means: a snapshot in a non-terminal state, or
  a partial whose `commit_state` is anything other than `committed`/`not_ready`, or a
  partial that still exists on storage. The guard answers with a typed
  `SessionRetentionRefused(reason, blockingTransferIds)` rather than silently
  skipping or silently cascading.
- **Session cleanup is a two-phase, idempotent operation:** explicit cleanup of the
  recovery rows and their partials first, then session deletion. Re-running either phase
  is safe, and a failure leaves the row visible (`cleanup_state = failed`) for a later
  retry instead of hiding it.
- **Deleting UI/history records never touches the new tables.** `clearCompleted`,
  `HistoryDao.clear`, `HistoryDao.clearAll`, `TransferItemDao.delete*` and
  `deleteClosedBefore` only address `transfer_items`, `history_entries` and
  `transfer_sessions`. Recovery state is a separate retention policy with a separate
  clock.

Tests required by this decision: history clearing does not delete recovery state;
session cleanup cannot silently delete a retryable partial (it is refused, and the
refusal names the blocking transfer); cleanup is idempotent; a failed cleanup stays
visible.

---

## Decision 3 — Durability is a capability, not an assumption

`FileChannel.force(true)` is a real fsync on an app-private regular file and is
meaningless to a `ContentResolver` pipe or a document provider that may buffer, proxy or
defer. Treating them as equivalent would let an acknowledgement claim bytes that no
device has committed.

```kotlin
public sealed interface FlushDurability {
    public data object DurableFlushSupported          // app-private file: force() is real
    public data object FlushAttemptedGuaranteeUnknown // provider accepted the flush, proves nothing
    public data object FlushUnsupported               // provider offers no flush at all
    public data class FlushFailed(val error: TransferStorageError)
}
```

Each destination strategy **declares** its capability and **returns** the outcome of
each flush. The checkpoint policy follows from the returned value:

| Outcome | Checkpoint? | Acknowledgement? | Row records |
| --- | --- | --- | --- |
| `DurableFlushSupported` | yes, after `force()` returns without error | yes | `durability = durable` |
| `FlushAttemptedGuaranteeUnknown` | yes — the strongest available operation was attempted and returned successfully | yes | `durability = unknown` |
| `FlushUnsupported` | yes — write succeeded, no flush exists to attempt | yes | `durability = unsupported` |
| `FlushFailed` | **no** | **no** | unchanged; typed failure returned |

The first three rows are honest about what they prove and are *reconciled on
restoration* by comparing the persisted confirmed offset against the partial's actual
length (§12 of the group brief). The last row is the only one that must stop the
pipeline: a failed flush never advances the checkpoint and never releases an
acknowledgement, because that is the case where the bytes demonstrably did not land.

Tests: for each of the four outcomes, assert the checkpoint/ack behaviour; specifically
assert that `FlushFailed` leaves the persisted offset unchanged and emits no
acknowledgement.

---

## Decision 4 — SAF destination: app-private staging is the default

SAF has no pending/hidden mechanism. A document created inside a user-granted tree is
visible to every other app the moment it exists, whatever it is called. A `.part`
suffix makes a file *unmistakably incomplete to a human*; it does not make it hidden,
and this document will not describe it as such.

**Default strategy: app-private staging, then a verified bounded copy to the final SAF
document.**

- Bytes land in `filesDir/transfer-partials/<opaque-id>` — not user-visible, not
  indexed, not scannable.
- SHA-256 is computed over the staged file. Nothing is copied anywhere before it
  matches.
- The final copy runs through a bounded buffer and is followed by a re-verification
  policy documented below.
- MediaStore `IS_PENDING` (API 29+) remains the **preferred** strategy where the
  destination is a media collection: the row is inserted pending, stays hidden from the
  gallery and from other apps, and is published only after verification succeeds.

**Documented consequences of the staging default:**

- *Additional free space.* Staging plus the final copy means up to 2× the file size is
  required at the moment of commit. For an 8 TiB − 1 ceiling that is not satisfiable on
  any phone; the copy path is bounded by reality, not by the protocol.
- *Insufficient staging space.* The allocator checks `StorageManager.getAllocatableBytes`
  (API 26+) or `File.usableSpace` (API 23+) before staging and refuses with a typed
  `InsufficientSpace(required, available)` rather than failing mid-write. A refusal is a
  retryable failure and the partial is left clean.
- *Very large files.* The copy is a single bounded-buffer stream. There is no
  whole-file allocation, no memory-mapped shortcut and no second copy in memory. If
  staging space is insufficient for the full size, the transfer fails retryably instead
  of writing a truncated file.
- *Commit-copy interruption recovery.* `commit_state` records
  `final_created → content_copied → published → cleanup_pending → committed`. On
  restoration, an interrupted copy is detected by comparing the final document's length
  against the staged length: shorter means re-copy from zero (the final document is
  deleted first if the provider allows it), equal means proceed to publish. The copy is
  therefore idempotent-by-restart, not atomic — and is documented as such.
- *Final-file visibility.* The final SAF document appears only after verification and a
  complete copy. It is never visible with partial content.
- *Cleanup.* The staged file is removed only after `committed`. A staged file whose
  record is missing is an orphan and is removed by the cleanup planner, never by a name
  match.

---

## Decision 5 — One owner per descriptor

A `ParcelFileDescriptor` wrapped in a `FileInputStream` wrapped in a `FileChannel` is
**one** resource, and closing all three independently is how a descriptor gets closed
twice — the second close silently releasing a descriptor number that the process has
already reused.

**Chosen design: an owned-resource abstraction.** A `SourceHandle` (and
`DestinationHandle`) is the sole owner. It exposes the stream and channel, records a
single `closed` flag, and its `close()` closes the `ParcelFileDescriptor` **once**.
Callers use the handle in a `use { }` block and must **not** close the derived stream or
channel; the derived objects are closed transitively by the descriptor. No nested
`use` over a wrapper whose lifetime the caller does not own.

Tests must assert descriptor closure — exactly once — on: success, seek failure, read
failure, write failure, verification failure, and cancellation.

---

## Decision 6 — Non-seekable skip: bounded zero-progress policy

`InputStream.skip(n)` may return less than `n`, may return `0`, and is not required to
make progress. The advance loop is therefore:

- `read()` returning **`-1`** is EOF: stop immediately and fail with a typed
  `OffsetUnreachable(offset, reachedBytes)` — the target was past the end.
- `read()` returning a **positive** value is progress: add it to `reached`, reset the
  zero-progress counter, continue.
- `read()` returning **`0`** is *no progress*, not an error: increment the counter and
  retry.

**Zero-progress policy (documented, not arbitrary):** the loop permits
`MAX_ZERO_PROGRESS_STEPS = 64` consecutive zero-progress iterations. Reaching the
threshold fails with `OffsetUnreachable`; the counter resets on any progress. 64 was
chosen because a provider that has returned zero sixty-four times in a row while being
asked to advance is not slow, it is broken, and because it bounds the worst case to
sixty-four cheap calls rather than an unbounded spin. **The loop can never spin
forever:** every iteration either advances `reached`, is counted against the threshold,
or terminates.

The discard buffer is a single fixed allocation (`ProtocolLimits.DEFAULT_CHUNK_SIZE_BYTES`
or smaller), allocated once per open, never sized from the offset and never sized from
the file length. The skipped prefix is never materialised.

---

## Decision 7 — Large-file tests: accounting is virtual, hashing is bounded

CI must not hash or iterate 5 GiB. The evidence is split three ways:

1. **Long-range accounting and seek/skip** use *virtual* sources: a stream that advances
   a `Long` cursor and hands back a pre-filled bounded buffer. Nothing is generated per
   byte, so a 5 GiB advance costs the number of iterations (81,920 at 64 KiB), not the
   number of bytes.
2. **SHA-256 correctness** is proved on bounded real byte sequences (empty file, one
   byte, one chunk, a multi-chunk deterministic file with a known digest) using
   `Sha256Accumulator` end to end. These are the tests that would catch a broken digest.
3. **Buffer-bound tests** assert that the largest allocation is the fixed buffer and
   that no allocation depends on `totalBytes`, by recording the maximum buffer size
   observed across a virtual 5 GiB verification pass.

The virtual 5 GiB verifier test proves **Long byte accounting** — bytes fed equals
5 GiB, the counter never wraps, the buffer never exceeds its bound — without performing
billions of `update()` operations. No 5 GiB fixture is created, and no 5 GiB digest is
computed, in any test or in CI.

---

## Decision 8 — Clock ownership

Inspected first: the project has **no** clock or time abstraction anywhere. `System
.currentTimeMillis()` is currently called directly at the point of use.

**The clock goes in `:core-data`, not `:core-model`.** `core-model` is shared
vocabulary (states, settings, domain types); a timestamp source is transfer-infrastructure
that happens to be needed by two adapters, and putting it in `core-model` would make
infrastructure look like domain vocabulary. `core-storage` already depends on
`:core-data` (`api`), so a `Clock` declared there is visible to both without widening any
boundary.

- `app.morsecode.core.data.time.Clock` — interface, single method.
- `SystemClock` implementation, provided through Hilt from `DataModule`; tests inject a
  `FakeClock` with an explicit, advanced-by-hand value.
- **`core-transfer` is not touched by this and remains clock-free.** The reducer takes
  no time input; timestamps are attached by the adapter when a row is written.

---

## Decision 9 — Migration tests must execute, not merely compile

An assembled `androidTest` APK is not an executed test; CI only assembles that APK, so a
migration test placed there would never run.

**Order of preference, as recorded:**

1. **Robolectric/JVM Room migration test.** Preferred. `:core-data` already has
   `testImplementation(libs.robolectric)`, `testImplementation(libs.room.testing)` and
   `testOptions.unitTests.isIncludeAndroidResources = true`. If
   `androidx.room.testing.MigrationTestHelper` runs under Robolectric 4.14.1 with Room
   2.8.5, the migration test runs in the ordinary `./gradlew test` invocation and is
   executed by CI like every other unit test.
2. **Host-side SQLite verification** if (1) is not viable: open a database, execute the
   real exported version-1 `setupQueries` read from the committed `1.json`, insert
   representative rows, run `Migration(1, 2)`'s SQL, and validate the resulting schema
   and data. Still an executed JVM test, still against the real exported schema.
3. **A dedicated instrumentation job** only if neither is viable — and then it must
   actually run on an emulator, not just compile.

Whichever path is taken, the test must prove: version 1 opens; existing data inserts;
migration 1→2 executes; history, logs, crash reports and SAF grants survive; the new
tables and indexes exist; foreign-key behaviour matches Decision 2 (i.e. deleting a
session row does not remove recovery rows, because there is no foreign key, and the
adapter guard refuses while recovery data exists); and no destructive migration occurs.

---

## Decision 10 — Restoration is a pure decision

`TransferRestorationCoordinator` may read persistence and inspect storage. It must not
start a transport, discovery, a foreground service, networking, UI or timers, and it
must not resume transmission. It returns `RestorationDecision` — a pure value — for a
later runtime layer to act on.

Storage inspection sits behind interfaces (`PartialInspector`, `SourceProbe`,
`GrantProbe`) so that the overwhelming majority of restoration tests are deterministic
JVM tests with fakes; only the provider implementations need Robolectric.

---

## Consequences

- `core-transfer` gains exactly one thing: the `RestorationDecision` vocabulary in
  `persistence`, alongside `StoreResult` and `RetentionPolicy`. No Android, `Uri`,
  `Room`, `java.io` or stream type enters the module; `tools/verify/transfer-limits.mjs`
  enforces that and CI runs it.
- `core-storage` gains `api(project(":core-transfer"))` and packages for source,
  destination, verification, restoration and cleanup.
- `core-data` gains two entities, one migration, one adapter and one clock.
- `core-model` is unchanged.
- `app` is unchanged, and `TRANSFER_ENGINE` stays gated.
