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

1. Check the staged length and hash staged bytes in a bounded fresh pass through exact EOF before creating a provider document. If a caller supplied an expected digest, it must match; otherwise the observed staged digest is the transient comparison value for the provider copy.
2. Resolve the destination through the persisted grant and apply the selected duplicate
   policy. Direct-child lookup comes from the provider's child listing and compares exact
   display names; provider document ids remain opaque.
3. Create a uniquely named `.morsec-part` temporary document and store the exact
   provider-returned URI and document id together.
4. Copy with a bounded buffer; flush while the writer is open; close that owner; then
   open a fresh reader and verify length and SHA-256 against the digest from the staged
   pass. Require exact EOF even when provider size metadata matches: a bounded one-byte
   probe rejects trailing data rather than accepting a matching prefix, and a missing
   size column does not bypass the probe. The digest itself is transient and is not logged
   or stored in recovery state. SAF flush is `FlushAttemptedGuaranteeUnknown`, never a
   claim of durable flush.
5. Request rename only after verification. Query the before and returned identities;
   publish only when one authoritative identity is proven. Both identities, neither, a
   null return, a failed query, or an identity mismatch remain reconciliation cases.
6. Release app-private staging only after delivery is known. If its deletion does not
   complete, delivery stays delivered and the record retains staging cleanup as pending.

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
tree/root, transfer id, partial/commit id, final identity, pending item and state. Retry
validates grant/tree context and an eligible cleanup state. Provider cleanup uses the
exact recorded URI/id, queries that identity before issuing a delete and again afterwards,
and requires confirmed absence; names and containment alone never authorize a delete.
`stagingReleased` records the app-private delete observation independently. Permission
revocation or a still-present document remains pending; an unknown query is not success.
These are commit-record facts only: this decision does not start Room v2, a Room adapter,
or a cleanup service.

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

### Rename reconciliation table

| Reconciliation result | Evidence | Coordinator decision |
| --- | --- | --- |
| `RESOLVED_TO_RETURNED` | returned identity resolves; before identity is absent | adopt the returned URI/id |
| `RESOLVED_TO_ORIGINAL` | same/original identity resolves | adopt the original URI/id |
| `AMBIGUOUS_BOTH_RESOLVE` | both distinct identities resolve | stop; retain both for a later query |
| `RETURNED_UNRESOLVED` | before resolves but returned identity does not | stop; do not assume rename failed |
| `NEITHER_RESOLVES` | neither identity resolves | stop; do not publish or delete |
| `NULL_RETURN` | rename returned no URI | stop; retain the before identity |
| query/authorization failure | presence was not established | stop with typed error and unclassified evidence |

Only the two resolved rows authorize promotion. A same-name lookup, URI containment, or
a provider's returned URI by itself does not settle identity. Before an operation the
coordinator also checks grant context, authority, and the URI's encoded id against the
stored id. Cleanup never searches by name.

### Cleanup state and retry table

| Pending item | Persisted target | Successful retry | Still present / grant revoked | Unknown query or identity mismatch |
| --- | --- | --- | --- | --- |
| `STAGING` | exact `PartialIdentity` in app-private storage | set `stagingReleased`; remove the item | keep pending | keep pending; do not claim cleanup |
| `PROVIDER_TEMPORARY` | stored temporary URI + id | remove only after confirmed absence | keep pending | reconciliation required |
| `BACKUP` | stored backup URI + id | remove only after confirmed absence | keep pending | reconciliation required |

The record's `pendingCleanup` set can carry more than one item. `stagingReleased` is a
separate observed fact, because the final document can be delivered while staging or a
backup still needs cleanup. The `retryPendingCleanup` operation repeats only the recorded
deletes and staging deletion; it does not recopy, rename, search by name, or turn an
unknown result into success. It requires the stored final identity and a compatible grant
context before returning a delivered outcome.

### SAF API compatibility table

| Platform API | Provider containment evidence available to the production tier | Consequence |
| --- | --- | --- |
| 23–25 | grant-scoped canonical evidence only; this is not provider ancestry proof | a selected tree root is usable; an unproven child destination is `ContainmentUnknown` |
| 26–28 | `findDocumentPath`; nullable root id is passed through and a null root id does not become a false mismatch | use provider path evidence when available; missing/failed evidence is not a child assertion |
| 29+ | `isChildDocument`, with `findDocumentPath` as the older path capability | use the provider's answer; false is outside, unavailable/failed is unknown |

The production tier is chosen from `Build.VERSION.SDK_INT`. Tests inject a tier fake, not
an arbitrary SDK number. No repeated decoding or raw-URI `%2F` test proves containment,
and provider document ids are never assumed to be string-prefix descendants.

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
