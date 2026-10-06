# Callable SAF commit restoration

This document describes the explicitly invoked Milestone 3 SAF process-restoration
orchestration group. It restores only the final SAF destination-commit protocol already
represented by a version-2 `SafCommitCheckpoint`; it does not restart or schedule network
transfers and does not enable `TRANSFER_ENGINE` for users.

## Entry point and side-effect boundary

Production code may inject `TransferRestorationCoordinator` or explicitly construct it with
`TransferRestorationCoordinatorFactory.createForProduction(context, database, ...)`. Its
`suspend fun restore(continuation: RestorationContinuation? = null)` is the only operation
that discovers or acts on checkpoints. Factory construction, Room database construction,
DAO acquisition, and Hilt provisioning do not scan Room, open staging files, or contact a
document provider. No initializer, service, worker, timer, notification, or startup hook
invokes restoration.

The production factory fixes the implementation path to the existing `MorseDatabase`,
`RoomSafCommitJournal`, `RoomSafCheckpointDiscovery`, `DocumentsContractSafGateway`,
`RoomPersistedSafGrantResolver`, and app-private `incoming` staging store. Tests may inject
fakes through the coordinator constructor. The storage dependency direction remains
`:core-storage` → `:core-data` / `:core-transfer`; neither Room entities nor Android provider
types enter the pure JVM transfer engine.

The journal is in the existing application-private `morsecode.db` opened by
`DataModule.provideDatabase` (`context.getDatabasePath(MorseDatabase.NAME)`). Its checkpoint
parent is `transfer_partials`; the exact cleanup and rename-evidence children are
`saf_pending_cleanup` and `saf_rename_history`. Room remains schema v2, and the committed
v1 and v2 schema exports are frozen. This group adds no table, column, migration, or
schema-version change.

## Discovery, validation, and exact grant resolution

Production discovery calls
`TransferPartialDao.restorationCandidateCommitIds(afterCommitId, limit)`. It uses an
ascending binary-collation keyset, selects nonterminal checkpoints and all rows indicating
unsettled cleanup (including inconsistent terminal rows), and reads one sentinel beyond
the requested page. The adapter caps a page at 128 ids. Clean committed/cancelled rows are
not scanned on every pass. Discovery returns ids only; it does not query a provider.

For each candidate, the coordinator validates the id and loads the complete checkpoint
from the journal before grant resolution or provider access. Unsupported versions,
malformed identities, corrupt child counts, invalid phase/cleanup combinations, and
mismatched parent keys produce typed outcomes without document-provider calls.

Production grant resolution requires all of the following to agree: the checkpoint's
decimal schema-v2 `saf_grants.id`, exact stored tree URI, authority, root document id, the
matching Room grant row, and Android's exact persisted read/write tree permission. A
revoked permission, insufficient write scope, malformed identity, and mismatched row/scope
remain distinct classifications. No broader grant is substituted; restoration never
launches a picker or requests permission.

## Recovery, locks, conflicts, and bounds

After validation and exact grant resolution, the coordinator delegates to the existing
`SafCommitCoordinator.resumeOrReconcile` (or its existing cancelled-temporary cleanup
entry point). It does not implement a second rename, digest, overwrite, final-publication,
provider-copy, or deletion protocol. Visible-final copying is disabled for restoration.
Unknown provider results remain reconciliation work, never success. Recovery never restarts
a network transfer.

A reference-counted process-local mutex excludes concurrent work on the same commit id;
unrelated commits can proceed independently. It makes no cross-process or cross-device
locking claim. Journal writes retain the Room `journal_revision` compare-and-set rule. When
the existing coordinator reports a stale-write conflict, restoration reloads the checkpoint,
revalidates its immutable commit scope, resolves the exact grant again, and reclassifies the
newest phase. Reclassification is bounded; a further conflict is reported for a later
explicit reconciliation rather than retried indefinitely.

Default per-invocation limits are 16 checkpoint slots, discovery pages of 8 (hard page
maximum 128), 128 provider mutations, 24 cleanup attempts, 1,024 reconciliation
observations, one revision-conflict reclassification, and a 30-second monotonic observation
budget. Hard policy ceilings are 256 checkpoints, 2,048 provider mutations, 256 cleanup
attempts, 8,192 reconciliation observations, and four conflict reclassifications per run.
The elapsed budget is checked between synchronous SAF coordinator boundaries; it is not a
hard preemptive deadline for an operation already in progress. Containment-proof calls and
the production create-result observation share the same observation budget. The existing
coordinator retains its bounded buffers and byte validation. Callers receive remaining
work, an opaque keyset continuation, typed classifications, and retry guidance. A later
explicit full rescan is the retry path for unresolved earlier ids. There are no sleeps,
backoff loops, persisted retry timestamps, or automatic scheduling.

## Exact cleanup and cancellation

Cleanup reuses the existing SAF protocol for app-private staging, provider temporaries, and
overwrite backups. Provider cleanup is authorized by the checkpoint's stored URI/document-id
pair plus the exact grant/tree and commit state; a filename never authorizes deletion. The
existing coordinator saves deletion intent, checks the exact target before deletion,
requests deletion, and checks that same identity afterward. Only observed absence settles
provider cleanup. A same-name replacement is not the recorded identity. Cleanup failure
keeps the corresponding typed item pending and does not cause a verified final to be
recopied.

Coroutine cancellation remains joined to synchronous SAF work; restoration does not launch
detached operations. If cancellation or a failed journal result leaves a provider mutation
ambiguous, the existing reconciliation path retains the checkpoint and exact identities.
Unfinished work is not reported as complete. Reports and diagnostic string forms omit raw
commit ids, tree/document URIs, paths, filenames, grant rows, digests, tokens, provider
messages, exception messages, and stack traces.

## Verification boundary

Robolectric tests use Room database files that are closed and reopened before restoration,
with a provider-state double representing state that survived alongside the database.
They cover create/copy/flush/provider-verification and final-rename crash boundaries,
publication/cleanup reconstruction, exact cleanup, and the inert production factory. These
tests model persisted-state reconstruction only; they are not Android OS process-death
tests and do not establish behavior for every OEM/document provider.

`CURRENT_MILESTONE` remains 2 and `TRANSFER_ENGINE` remains gated. LAN, Nearby, Wi-Fi Direct,
foreground services, workers, alarms, startup automation, notifications, wake locks,
transfer UI, Media3, WebShare, and background retry remain out of scope.
