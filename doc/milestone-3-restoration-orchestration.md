# Milestone 3 callable SAF restoration orchestration

**Status:** implementation and tests are complete for the authorized callable restoration-orchestration scope. Exact implementation SHA `540bfaed700aa17476b9394587e3174489e18685` passed hosted Android CI run [37505271565](https://github.com/ahmadyb/Morsec/actions/runs/37505271565). The run reports 2,613 tests, 0 failed, 0 skipped (153 reports); lint reports 0 errors and 46 warnings (7 reports); both debug (18.98 MiB) and instrumentation (1.10 MiB) APKs assembled. The `morsecode-debug-apk` artifact is available on the run. This evidence is for the exact source implementation SHA above; if the handoff is subsequently synchronized in a documentation-only commit, verify and report CI for that final pushed SHA separately.

## Scope and activation boundary

This group adds a callable production restoration domain around the existing SAF commit/recovery state machine. `StoragePersistenceModule` provides `SafCommitRestorationCoordinator` and its persistence, exact-grant, staging, policy, and recovery-factory dependencies. Construction is inert: opening Room or creating these dependencies does not enumerate checkpoints, resolve permissions, or contact a document provider. A caller must explicitly invoke `restore`.

There is no startup initializer, worker, service, receiver, timer, notification, wake lock, self-scheduling, network transport, transfer UI integration, Media3, WebShare, or other automatic invocation. `FeatureReadiness.CURRENT_MILESTONE` remains 2 and `TRANSFER_ENGINE` remains gated.

## Discovery and classification

`RoomSafCommitJournal` implements a storage-domain discovery seam without exposing Room entities. The DAO uses a bounded keyset query ordered by SQLite binary `commit_id`, with one lookahead row to report `hasMore`. A continuation cursor is an opaque, redacted handle. Each returned row is decoded and validated independently so a malformed checkpoint is reported without hiding its neighbours.

Discovery excludes only a `committed` checkpoint with no pending cleanup and observed staging release. Nonterminal phases, interrupted mutation/reconciliation phases, and any checkpoint with cleanup pending remain discoverable. Transfer activity is read as a minimal `(transfer_id, session_id, snapshot_state)` projection; active/resumable, quiescent, malformed, missing, and unavailable states are classified before grant resolution. No provider access happens during discovery. Unknown/malformed checkpoint data is rejected before a grant is resolved or recovery is constructed.

## Grant resolution and recovery

`RoomSafCommitGrantResolver` validates the checkpoint, parses only its canonical persisted grant ID, performs one exact `SafGrantDao.find(id)`, and compares the exact persisted tree URI, authority, and root document identity. It then checks for the exact URI in `ContentResolver.persistedUriPermissions`, requiring both read and write permission. Outcomes distinguish available, revoked, malformed, and unavailable. There is no fallback to another row/tree, no broader-grant substitution, and no permission prompt. Recovery is not called on any grant-validation failure.

A `SafCommitRecoveryCoordinatorFactory` seam makes orchestration testable. Production constructs the existing `SafCommitCoordinator` using `SafCommitCoordinatorFactory.createForRestoration`; it does not implement a second copy/rename/verification/delete state machine. Existing recovery remains responsible for provider revalidation, exact identity checks, intent/result checkpointing, rename reconciliation, final verification, cleanup, and staging release.

The existing recovery path authorizes provider cleanup from exact persisted URI/document-ID pairs and staging cleanup from its exact partial identity. It records cleanup intent before deletion, observes the target before and after delete, and settles cleanup only when absence is confirmed. Name lookup is part of reconciliation, not deletion authority; same-name replacements and verified finals are protected by the underlying recovery checks.

## Concurrency, budgets, and cancellation

`SafCommitProcessLocks` serializes commit, recovery, and cleanup entrypoints for the same checkpoint inside one process. This is not a multi-process lock. Room writes still use the existing typed journal revision compare-and-set. When a stale revision conflict reaches restoration, it reloads and reclassifies the latest row instead of retrying a stale mutation.

Each explicit run uses the injected `SafCommitRestorationPolicy`, bounding checkpoint count, page size, provider mutations, cleanup attempts, reconciliation observations per checkpoint, and retry output. The coordinator does not sleep or schedule itself. Returned retry plans are opaque, in-memory handles with a trigger and checkpoint number; they do not persist timestamps or imply an automatic retry. Continuation and unprocessed-work handles allow a caller to request another bounded explicit run.

Caller cancellation is checked between checkpoints and provider/storage boundaries. The budget adapters refuse new work after cancellation or exhaustion. The existing recovery state machine retains any already-durable progress and mutation ambiguity, closes owned streams/descriptors, and leaves unresolved cleanup pending. A caller-provided cancellation signal produces a structured report; ordinary coroutine cancellation continues to propagate through structured concurrency.

## Redaction

Per-checkpoint reports contain only a disposition, safe phase token, cleanup-type set, retry trigger, and a validated category/code pair. They do not contain commit IDs, session/transfer IDs, URIs, paths, filenames, digests, raw provider messages, or Room entities. Continuation and retry handles redact their identity in diagnostics.

## Persistence compatibility

This work adds DAO queries/projections only. It does not change a Room entity, database version, migration, or schema export. Database version remains 2, schemas `1.json` and `2.json` remain immutable accepted artifacts, and no schema v3 is added. No destructive migration fallback is introduced.

## Tests added for this group

- `RoomSafCommitDiscoveryTest`: ordered bounded keyset pages; exclusion of only fully completed cleanup-free rows; inclusion of reconciliation/cleanup rows; provider-free active and malformed transfer-activity classification.
- `RoomSafCommitGrantResolverTest`: exact persisted grant and exact live URI permission; malformed IDs/rows; broader-tree non-substitution; revocation; missing data; and database/permission-query failures without provider access.
- `SafCommitRestorationCoordinatorTest`: routing through the recovery factory; validation and grant gates; active-transfer deferral and explicit retry; mutation/observation/cleanup budgets; exact stored-identity cleanup with a same-name replacement; revision-conflict reload; cancellation and unprocessed work; and redacted reports.
- `SafCommitProcessLocksTest`: same-key serialization, different-key concurrency, and reference-counted lock-entry release.
- `RoomSafCommitRestorationReconstructionTest`: writes a checkpoint, closes/reopens the Room database, constructs the callable coordinator without provider activity, explicitly restores, then reopens and verifies the committed row.
- Existing `SafCommitRecoveryTest` remains the underlying crash-boundary and exact cleanup/reconciliation suite.

The Room reconstruction test proves only the sequence it executes (database close/reopen and coordinator reconstruction). It is not an OS process-death test. Hosted test results and APK/lint evidence are still required.
