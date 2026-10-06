# ADR-0003 — Android transfer storage adapters and durable persistence

- **Status:** accepted (2026-10-02), amended for SAF destination closure (2026-10-05), Room v2 persistence (2026-10-06), and explicitly invoked SAF process restoration (2026-10-06; verification pending)
- **Applies to:** `:core-storage` (Android source/destination adapters and SAF journal),
  `:core-data` (Room entities, migration, snapshot adapter, clock), and `:core-transfer`
  (pure persistence contracts, codec, digest value, and typed errors)
- **Historical closure amendment:** the 2026-10-05 SAF destination closure was limited to
  commit/checkpoint/recovery/cleanup and intentionally left Room v1 unchanged. The first
  2026-10-06 amendment below authorized Room v2; the subsequent restoration amendment
  separately authorizes only callable, explicit SAF commit restoration.
- **Supersedes:** nothing. **Amends:** the pre-implementation report for this group,
  which contained one contradiction (foreign keys) and several unstated assumptions
  (durability, SAF visibility, descriptor ownership, zero-progress policy) that are
  resolved here.
- **Delivery:** Milestone 3. `FeatureReadiness.TRANSFER_ENGINE` stays pinned to
  milestone 5 and `CURRENT_MILESTONE` stays 2. Nothing in this group turns the engine on.

## Amendment 2026-10-06 — Room v2 persistence group

This amendment records the user's separate, explicit authorization after acceptance of
SAF destination commit `43e575b1c9e2aea6b70bd03f0ac76ac642e14210`. It supersedes only the
2026-10-05 closure-pass prohibition on Room v2; it does not authorize a restoration
coordinator, cleanup worker, service, transfer UI, networking, or any later feature.

The v2 design is additive and keeps Room entities out of provider logic:

- `transfer_snapshots` stores the reducer snapshot as explicit typed columns (never as
  a serialized object), including durable `confirmed_bytes` and an independent monotonic
  Room `row_revision`.
- `transfer_partials` stores one bounded SAF checkpoint parent, exact grant/tree/document
  identity fields, the checkpoint-format version and phase, expected/copied byte counts,
  canonical lowercase 64-character SHA-256 hex, rename intent/result metadata, typed
  failure/reconciliation fields, and child-count integrity markers.
- `saf_rename_history` and `saf_pending_cleanup` are bounded, ordered child tables. Their
  foreign keys cascade only to the exact `transfer_partials.commit_id` owner; there is no
  recovery-data foreign key to the UI-owned `transfer_sessions` table.

`MORSE_MIGRATION_1_2` creates only the four new tables and their indices/ownership
constraints. Production registers it explicitly. The accepted nine-table `1.json` is
pinned by SHA-256 and byte count and remains immutable; CI generates `2.json` with Room
KSP, then compares it byte-for-byte with the committed authentic export. Destructive
fallbacks and destructive DDL are forbidden.

`RoomTransferSnapshotStore.saveTransition` transactionally checks the prior full snapshot
and exact next reducer version, then atomically replaces the complete typed-column row
while incrementing `row_revision`. Exact retries are idempotent; stale, gapped, regressing,
or inconsistent writes return typed conflicts. Per-chunk checkpoint updates change only
the dedicated confirmed-offset column and cannot regress or exceed the declared size.
Session retention does not remove a session that still has a transfer snapshot or SAF
journal parent.

`RoomSafCommitJournal` uses a transaction for both complete reads and complete replacements.
It validates a checkpoint before returning it, maps all bounded children, compares the
caller-held `journal_revision` inside the write transaction, and rejects stale writers.
The revision is not the checkpoint format version or the Room database version. Child
counts and ordered sequence fields detect missing/reordered rows; malformed identity,
digest, scope, ownership, phase, and token data yield typed refusals before any provider
call. No checkpoint/Room identity, private URI, or raw digest is emitted by entity, digest,
checkpoint, or persistence-error `toString()` output.

`core-data` owns the transfer snapshot store and schema. `core-storage` depends one way on
`core-data` and supplies the production SAF journal adapter/Hilt factory. Both depend on
pure `core-transfer`; no dependency cycle or Room entity enters a provider operation.
Opening the database does not begin recovery. Close/reopen tests demonstrate durable reads;
they do not claim automatic startup recovery. At the time of this amendment, the code was
in progress and test/build/lint/APK/exact-SHA CI evidence was still pending. The later,
separately authorized restoration scope is recorded next.

## Amendment 2026-10-06 — callable SAF process-restoration group

This subsequent amendment records explicit authorization for only the callable SAF
process-restoration and safe-cleanup orchestration group. It does not change the schema or
supersede the Room v2 ownership, migration, CAS, or redaction rules above.

The production entry point is `TransferRestorationCoordinatorFactory.createForProduction`
and the caller explicitly invokes `TransferRestorationCoordinator.restore()`. Construction,
Room opening, and Hilt provisioning remain inert. Production wiring uses the v2 Room
journal and deterministic keyset discovery, exact persisted-grant resolution, the real
`DocumentsContractSafGateway`, and lazy app-private staging. Recovery delegates to the
existing `resumeOrReconcile`; it does not implement a second commit/copy/rename/digest or
delete protocol.

One pass is bounded by checkpoint/page, provider-mutation, cleanup, observation, and
revision-conflict limits, with an optional monotonic elapsed observation. It never sleeps,
schedules, persists retry timestamps, starts a worker/service, or restarts network work.
A process-local lock excludes concurrent same-checkpoint work but makes no cross-process
claim. Stale journal writes trigger bounded reload/revalidation/reclassification. Typed
redacted results return the remaining work and explicit retry guidance. Exact staging,
provider-temporary, and backup cleanup remains authorized by stored identity and commit
state; only observed provider absence settles deletion.

The scope still prohibits Room v3/schema changes, startup recovery, WorkManager/alarms,
services/foreground services, notifications, wake locks, networking, LAN/Nearby/Wi-Fi
Direct, transfer UI, Media3, and WebShare. `CURRENT_MILESTONE` remains 2 and
`TRANSFER_ENGINE` remains gated. The implementation and test coverage are described in
[`../transfer-restoration.md`](../transfer-restoration.md). Close/discard/reopen Room tests
model persisted-state reconstruction and do not claim Android OS process-death testing.
Exact-SHA hosted validation remains the completion gate.

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

### A fact the version-1 export established

The real exported `1.json` shows that **the existing `transfer_items` table already
carries `onDelete = CASCADE` on `session_id → transfer_sessions.sessionId`.** This is
pre-existing behaviour and is *not* being changed by this group: pruning a session
legitimately removes that session's UI rows, and history is expected to disappear when
its session does.

It matters here for three reasons:

1. **The new tables must not inherit it.** `transfer_snapshots` and `transfer_partials`
   stay foreign-key-free precisely so that they are not dragged along by that cascade.
   The cascade is tolerable for rows whose only purpose is display; it is not tolerable
   for rows that name a partial file on disk.
2. **The guard has to reason about the rows the cascade *does* reach.** Deleting a
   session already removes `transfer_items`, so `SessionRetentionGuard` cannot look at
   `transfer_items` for evidence that recovery data exists — those rows may be gone
   while the partial is still on disk. The guard consults the recovery tables and
   storage only.
3. **It is a concrete, in-repo example of the failure mode.** A future change that adds
   a recovery column to `transfer_items`, or a recovery row with a `session_id` foreign
   key, would silently reintroduce cascade deletion of partials. The migration test
   asserts the new tables have **no** foreign keys, so that mistake fails loudly.

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

## Decision 4 — SAF destination: verified temporary plus rename by default

SAF has no pending/hidden mechanism. A document created inside a user-granted tree is
visible to other apps from the moment it exists, whatever it is called. A `.morsec-part`
suffix makes a document *unmistakably incomplete to a human*; it does not make it hidden.
This rule is specific to SAF. MediaStore `IS_PENDING` is a separate API-29+ row protocol;
it is not a SAF capability.

**Default SAF strategy: app-private staging, then a verified provider temporary and
rename.** The ordered pipeline is:

1. Check the staged length and hash staged bytes in a bounded fresh pass through exact EOF before creating a provider document. If a caller supplied an expected digest, it must match; otherwise the observed staged digest is retained as `verifiedDigest` in the version-2 SAF checkpoint so recovery can revalidate the final copy.
2. Resolve the destination through the persisted grant and apply the selected duplicate
   policy. Direct-child lookup comes from the provider's child listing and compares exact
   display names; provider document ids remain opaque.
3. Create a uniquely named `.morsec-part` temporary document and store the exact
   provider-returned URI and document id together.
4. Copy with a bounded buffer; journal copy and flush intent/result boundaries; flush
   while the writer is open; close that owner; then open a fresh reader and verify length
   and SHA-256 against the staged-pass digest. Require exact EOF even when provider size
   metadata matches: a bounded one-byte probe rejects trailing data rather than accepting
   a matching prefix, and a missing size column does not bypass the probe. The version-2
   checkpoint stores the verified staged-content digest (separately from a caller-supplied
   expected digest) but never handles, streams, bytes or accumulator state. SAF flush is
   `FlushAttemptedGuaranteeUnknown`, never a claim of durable flush.
5. Request rename only after verification. Query the before and returned identities;
   do not infer that one is obsolete merely because both resolve. Establish one
   authoritative final only when the exact URI/id is a direct child with the expected
   final name and size, its bytes match `verifiedDigest`, grant/parent checks pass, and
   rename history matches the commit context. Otherwise retain both identities for
   reconciliation. A null return, failed query or identity mismatch is never success.
6. Save cleanup intent before every provider deletion and result/observation after it.
   A failed result save recovers by querying the same exact URI/id; it does not blindly
   issue a second delete. Post-publication provider-temporary/backup cleanup is authorized
   only from a version-2 checkpoint carrying the established final identity, digest,
   expected size, direct-parent/grant evidence and strategy-appropriate rename context.
   Staging is released only after delivery is known; a failed staging deletion remains
   pending.

Every SAF filename uses the 127-byte UTF-8 limit from
`ProtocolLimits.MAX_PATH_SEGMENT_BYTES`, which is the existing per-segment protocol
storage bound. `SafFilenamePolicy` rejects malformed UTF-16, control/NUL characters,
separators and traversal markers. It preserves the exact Unicode spelling used by the
existing transfer-path policy, never counts Kotlin UTF-16 code units as bytes, and
reserves extension plus required duplicate/temporary/backup suffix before trimming only
whole code points from a non-empty basename. Duplicate candidates remain bounded.

A locally calculated staging digest proves only that the provider copy matches the staged
bytes. Without a sender-provided expected digest or equivalent trusted transfer
verification, it does not independently prove sender authenticity.

A visible-final-copy strategy exists only as a separate, explicit policy. With policy
false, lack of rename is a typed refusal; it is never an implicit downgrade. When
explicitly selected, the final-name document is still copied, flushed, closed, and
verified before it is reported delivered, but it is visible while incomplete and is
reported as such. That strategy cannot perform overwrite.

**Overwrite is recoverable and never delete-first.** The verified replacement is built
under a temporary identity before the existing final is touched. The coordinator
re-queries and checks the existing stored identity, renames that document to a backup,
settles the rename, promotes and verifies the replacement, then requests deletion of the
backup by its stored URI/id pair. Absence is established by a follow-up query, not by the
delete request's boolean. An interruption or unknown answer retains the identities and
requires reconciliation; it cannot destroy the old file to make room for a new one.

Cleanup is modeled separately from delivery. `pendingCleanup` can contain `STAGING`,
`PROVIDER_TEMPORARY`, and/or `BACKUP`. The commit record binds the stored grant id,
tree/root, transfer id, partial/commit id, final identity, verified staged digest, pending
item and state. Before post-publication provider cleanup, the coordinator requires a
final-verification/publication checkpoint with strategy-appropriate rename evidence, then
rechecks the final exact URI/id, expected child name, parent, size and digest. Each delete
has a saved intent and an observed result; exact-URI absence after a failed result save
settles the prior request without another delete. `stagingReleased` records the app-private
delete observation independently. Permission revocation or a still-present document
remains pending; an unknown query is not success.

Historical state at the end of the 2026-10-05 closure pass: the executable protocol had
only the `SafCommitJournal` abstraction and `InMemorySafCommitJournal` test fake; it had no
production journal or Room v2 persistence. The separate 2026-10-06 persistence amendment
above supersedes that status. No cleanup service is added by either group.

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
channel; the derived objects are closed transitively by the descriptor. No nested `use`
over a wrapper whose lifetime the caller does not own.

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

CI must not allocate or hash a physical 5 GiB fixture. It does run bounded virtual
5-GiB accounting through the integrated coordinator; the evidence is split three ways:

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

## Historical Decision 10 — Earlier pure-decision restoration proposal (superseded)

The earlier, unimplemented proposal described a side-effect-free `RestorationDecision`
value for a later runtime layer. It prohibited transmission restart, foreground services,
networking, UI, and timers, and did not authorize an explicit coordinator beyond the
original SAF destination pass. Its `PartialInspector`/`SourceProbe`/`GrantProbe` API was
not implemented.

The 2026-10-06 callable SAF process-restoration amendment at the start of this ADR
supersedes that proposal only for the explicitly authorized SAF commit group: the caller's
`TransferRestorationCoordinator.restore()` performs bounded local Room/SAF reconciliation
and returns a redacted `RestorationRunReport`. It still starts no transport or automation,
never restarts transmission, and does not add UI, workers, services, networking, or timers.

---

## Decision 11 — Deletion is settled by observation, not by the request

A delete request is not proof of absence. `DocumentsContract.deleteDocument` returns a
boolean, and that boolean is not the provider answering "is it gone". Measured against a
real `ContentProvider` driven through a real `ContentResolver`, it reports success
whether or not the provider removed anything.

So `SafDeletion` is decided by a follow-up query on the exact stored identity:

| Outcome | Meaning | Cleanup |
|---|---|---|
| `ConfirmedAbsent` | A query completed and returned no row | complete |
| `StillPresent` | The exact identity still resolves | pending |
| `QueryUnknown` | Presence and absence are both unproven | reconciliation required |
| `PermissionRevoked` | The grant went before absence was proved | pending |
| `IdentityMismatch` | The URI resolves to a different document | nothing deleted, nothing assumed |
| `DeleteRequestFailed` | The request itself threw or could not be issued | not complete |

Only `ConfirmedAbsent` closes cleanup. The request boolean is carried on several
outcomes as `deleteReported` for diagnostics only; it never authorizes deletion, absence,
or a committed state. The outcome records what was observed.

The reason to insist on this is what an unverified delete costs: the file left behind is
a partial under a name the user will see, and the record says it was cleaned up.

## Decision 12 — The SAF API tier is a type, selected from the real SDK

`isChildDocument` is API 29 and `findDocumentPath` is API 26. Below those levels the
symbols do not exist, and the difference between "the provider said no" and "this device
cannot ask" is the whole of it: the first is containment evidence, the second is none.

The first shape of this was one class holding an injected `sdkInt` integer with an `if`
in front of each versioned call. That is unsafe in a way that is easy to miss. The
integer is not the device, so anything able to construct the object — a test, a future
caller, a refactor threading the wrong value — could direct production code at a symbol
the running device does not have. The failure is a linkage error at the call site, which
no typed error mapping downstream can catch. It also forced two `SuppressLint("NewApi")`
annotations, because lint can only analyse a guard on `Build.VERSION.SDK_INT`.

The tier is now a type:

- `SafPlatformOperations` — the interface, with `SafApi23Operations` (grant-scoped
  containment only), `SafApi26Operations` (adds `findDocumentPath`) and
  `SafApi29Operations` (adds `isChildDocument`).
- Each implementation contains only calls its own minimum API supports and is annotated
  `@RequiresApi(26)` / `@RequiresApi(29)`.
- `SafPlatformOperations.create()` branches on `Build.VERSION.SDK_INT` directly. It takes
  no SDK argument, so the guard is the real one and lint can see it.

The wrong tier is no longer a value that can be passed; it is a type that is not
reachable. Tests inject a fake implementation, which is safe in a way the integer was
not: a fake can decide what to answer, but it cannot make production code reference a
symbol the device lacks.

Both `NewApi` suppressions are gone as a consequence.

## Decision 13 — Preserve provider identity evidence and retry cleanup exactly

The document name is presentation, not identity. Every operation after `create` keeps the
provider-returned URI and document id as one `SafStoredDocumentIdentity`; neither field is
reconstructed from a display name. The commit record keeps the temporary, final, existing,
and backup identities plus a `SafRenameEvidence` entry for each rename attempt. An
unresolved attempt retains both the pre-rename and returned identities, along with its
classification when provider observations completed. If a query failed, the evidence is
marked unclassified rather than guessed.

Checkpoint version 2 limits rename history to two entries: one optional backup rename,
then one final promotion. There is no compaction; a duplicate phase, invalid sequence,
cycle, scope mismatch, malformed URI/id pair, or history over the bound is rejected before
provider access and the coordinator stops for reconciliation. Each durable entry carries
the grant, tree, root, parent, session, transfer and commit scope plus its typed rename
phase and sequence. The identity pair is not ancestry proof: any identity later acted on is
re-queried and must be listed as a direct child of the approved parent. If the provider call
succeeds but its result checkpoint cannot be persisted, recovery retains the unresolved
rename phase and does not blindly repeat that mutation. A failed intent save occurs before
the provider call. Version 2 additionally retains the verified staged-content digest,
separate from the caller's optional expected digest, for final revalidation before cleanup.

### Rename reconciliation table

| Reconciliation result | Evidence | Coordinator decision |
| --- | --- | --- |
| `RESOLVED_TO_RETURNED` | returned identity resolves; before identity is absent | adopt the returned URI/id |
| `RESOLVED_TO_ORIGINAL` | same/original identity resolves | adopt the original URI/id |
| `AMBIGUOUS_BOTH_RESOLVE` | both distinct identities resolve | retain both until the exact final name, size and digest, direct parent, grant, and commit context prove which is authoritative; otherwise reconciliation |
| `RETURNED_UNRESOLVED` | before resolves but returned identity does not | stop; do not assume rename failed |
| `NEITHER_RESOLVES` | neither identity resolves | stop; do not publish or delete |
| `NULL_RETURN` | rename returned no URI | stop; retain the before identity |
| query/authorization failure | presence was not established | stop with typed error and unclassified evidence |

A same-name lookup, URI containment, or a provider's returned URI by itself does not
settle identity. Before an operation the coordinator also checks grant context, authority,
and the URI's encoded id against the stored id. Cleanup never searches by name. Post-
publication provider-temporary and backup cleanup requires the exact final identity plus
verified-digest checkpoint, expected size, expected child name, direct-parent reachability,
grant context, and strategy-appropriate rename history. Unverified interrupted-copy
handling is a distinct reconciliation/disposal path and can never report delivery.

### Cleanup state and retry table

| Pending item | Persisted target | Successful retry | Still present / grant revoked | Unknown query or identity mismatch |
| --- | --- | --- | --- | --- |
| `STAGING` | exact `PartialIdentity` in app-private storage | set `stagingReleased`; remove the item | keep pending | keep pending; do not claim cleanup |
| `PROVIDER_TEMPORARY` | stored temporary URI + id, authorized by a verified final checkpoint | remove only after confirmed absence | keep pending | reconciliation required |
| `BACKUP` | stored backup URI + id, authorized by a verified final checkpoint | remove only after confirmed absence | keep pending | reconciliation required |

The record's `pendingCleanup` set can carry more than one item. `stagingReleased` is a
separate observed fact, because the final document can be delivered while staging or a
backup still needs cleanup. The `retryPendingCleanup` operation repeats only the recorded
deletes and staging deletion; it does not recopy, rename, search by filename, or turn an
unknown result into success. If deletion succeeds but its result checkpoint save fails,
recovery first queries that same URI/id and settles exact absence without blindly repeating
the deletion.

### SAF API compatibility table

| Platform API | Provider containment evidence available to the production tier | Consequence |
| --- | --- | --- |
| 23–25 | grant-scoped canonical evidence only; this is not provider ancestry proof | a selected tree root is usable; an unproven child destination is `ContainmentUnknown` |
| 26–28 | `findDocumentPath`; nullable root id is passed through and a null root id does not become a false mismatch | use provider path evidence when available; missing/failed evidence is not a child assertion |
| 29+ | `isChildDocument`, with `findDocumentPath` as the older path capability | use the provider's answer; false is outside, unavailable/failed is unknown |

The production tier is chosen from `Build.VERSION.SDK_INT`. Tests inject a tier fake, not
an arbitrary SDK number. No repeated decoding or raw-URI `%2F` test proves containment,
and provider document ids are never assumed to be string-prefix descendants.

### SAF destination closure evidence and boundary

`SafCommitDestinationClosureTest` exercises the integrated coordinator with a virtual
5,368,709,120-byte (5 GiB) staging stream and provider row. It covers Long byte accounting,
copy, final verification and cleanup without allocating a 5 GiB fixture or computing a
real 5 GiB SHA-256. The same test suite adds partial-write and flush/sync ENOSPC cases,
ordinary-I/O typing, grant revocation and `SecurityException` cases, cancellation around
create/open/write/flush/verify/rename/reconciliation/delete, rename and cleanup result-save
failures, safe-overwrite interruption, and interrupted visible-final-copy recovery. This
is test-source coverage; results are claimed only from the exact-SHA CI run recorded in
the final handoff. Cancellation after a provider mutation whose result checkpoint is not
saved remains `RECONCILIATION_REQUIRED`; recovery does not downgrade that unresolved state
to terminal `Cancelled`. Once publication is authoritative, cancellation does not withdraw
the final and recovery continues identity-checked cleanup.

The checkpoint remains a Room-independent, versioned model. At the accepted 2026-10-05
closure point, `SafCommitJournal` was only an interface and `InMemorySafCommitJournal`
was the test fake, so production process-death durability had not been delivered. The
separately authorized 2026-10-06 amendment above adds the scoped Room v2 persistence layer;
SAF checkpoint version 2 remains distinct from Room database version 2. No cleanup
service or other later-milestone work is started by this persistence group.

---

## Consequences

- Historical 2026-10-05 closure delta: `core-transfer` gained the `RestorationDecision`
  vocabulary and `core-storage` gained the SAF storage/reconciliation code. The 2026-10-06
  Room amendment added typed persistence errors, defensive SHA-256 values, and the Room
  snapshot/journal adapters. The separate callable-restoration amendment adds bounded
  orchestration over those existing boundaries and does not change the transfer feature gate.
- `core-transfer` remains pure JVM; no Android, `Uri`, Room, stream, or provider type enters
  the module. `tools/verify/transfer-limits.mjs` enforces the dependency boundary.
- `core-storage` depends one-way on `core-data` for the production SAF journal adapter;
  provider logic receives only domain checkpoint values, never Room entities. Its
  restoration coordinator is explicitly invoked, bounded, and wired only to Room v2,
  exact persisted grants, `DocumentsContract`, and app-private staging.
- `TransferRestorationCoordinator.restore()` returns typed redacted guidance after reusing
  `resumeOrReconcile`; it starts no worker/service, schedule, UI, network transfer, or
  startup recovery. Same-checkpoint exclusion is process-local only.
- `core-data` is now Room schema version 2 via additive `Migration(1, 2)`. The old
  nine-table `1.json` remains immutable; no destructive migration, cleanup service, or
  automatic startup recovery is added.
- `core-model` is unchanged.
- `app` is unchanged, `CURRENT_MILESTONE` stays 2, and `TRANSFER_ENGINE` stays gated
  until milestone 5.
