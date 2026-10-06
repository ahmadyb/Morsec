# Room v2 transfer and SAF persistence

**Base Room v2 status:** verified by green Android CI run [37469198527](https://github.com/ahmadyb/Morsec/actions/runs/37469198527) on accepted starting SHA `b758135a2b820a11c78e3df46967d146aacae2f0`; the authentic committed `2.json` is present. The separately authorized restoration orchestration is still being implemented on `arena/268e777f-morsec`; this base run does not validate its new tests or changes. See [transfer-restoration.md](transfer-restoration.md) and the current handoff for exact-SHA completion evidence.

This document records the base Room v2 persistence group. Its original exclusion of a restoration coordinator was superseded by the separately authorized, callable SAF process-restoration amendment in [transfer-restoration.md](transfer-restoration.md) and ADR-0003. That amendment adds no Room schema or migration changes. It still does not enable `TRANSFER_ENGINE`, start automatic recovery, invoke a provider on database open, or add a cleanup worker/service, transfer UI, network transport, foreground service, notification, Media3, or WebShare.

## Schema history and migration

- Database: `MorseDatabase`, Room version 2.
- Accepted v1 export: `core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json`, SHA-256 `b0bca4243d2f0ba4631e3338e611d3bcaff8ba456de83b79ea0106ae187ac488`, 29,767 bytes. `tools/verify/room-schema.mjs` pins both values and the nine expected v1 tables.
- Migration: `MORSE_MIGRATION_1_2`, explicitly registered in the production `DataModule`. It creates only the v2 tables and their declared indexes/constraints; no v1 table is dropped, recreated, or rewritten. No destructive migration fallback is configured.
- Schema export: Room/KSP writes `2.json`. CI saves the committed v1/v2 inputs before tests, runs KSP with the Gradle build cache disabled, checks that v1 is byte-identical, verifies the generated v2 structure, and byte-compares it with committed v2 when that file is present. CI never commits schema files. During the first bootstrap run only, the missing v2 is published from CI so the KSP-produced file can be committed; subsequent runs require and compare it.

## Tables and ownership

| Table | Primary key | Purpose and ownership |
| --- | --- | --- |
| `transfer_snapshots` | `transfer_id` | One reducer snapshot stored in typed columns, including separately durable `confirmed_bytes`; `row_revision` is the Room adapter's CAS token. No serialized snapshot blob or foreign key to UI-owned sessions. |
| `transfer_partials` | `commit_id` | One complete SAF checkpoint parent, exact staging/grant/tree/parent/document identities, copy and digest state, typed failure/reconciliation state, and expected child counts. `staging_identity` is unique and currently must equal `commit_id`. |
| `saf_rename_history` | (`commit_id`, `sequence`) | Ordered rename evidence, bounded to the two supported rename phases. A unique (`commit_id`, `phase`) index prevents duplicate phase evidence. |
| `saf_pending_cleanup` | (`commit_id`, `sequence`) | Ordered, exact-identity cleanup intents, bounded to three cleanup types. Unique indexes prevent duplicate cleanup types and duplicate provider identities within a checkpoint. |

Both SAF child tables reference `transfer_partials.commit_id` with `ON DELETE CASCADE`. This cascade is limited to rows wholly owned by that single checkpoint; it cannot delete transfer-session, history, or other application data. The existing transfer-item/session relationship and all v1 tables remain unchanged. There is deliberately no foreign key from recovery rows to `transfer_sessions`: pruning UI history must not delete recovery authority.

All SQL values are Room parameters. The schema contains no secret defaults, streams or OS file-descriptor handles, file contents, serialized snapshot objects, raw exceptions, stack traces, or private filesystem paths. Transfer descriptor metadata is represented only by bounded, typed columns.

## SHA-256 and identity representation

`Sha256Digest` is the domain value: exactly 32 bytes, copied on input and on every byte-array read. The one persisted encoding used by transfer snapshots and SAF checkpoints is canonical lowercase 64-character hexadecimal. The Room mappers reject any other length, case, or character sequence before returning a value. `toString()` for digest, Room rows, checkpoints, and persistence errors redacts digest bytes and provider identities.

SAF rows retain the exact stored URI strings and separately retain authority, tree URI, tree/root/parent document ids, grant id, and exact document ids. Validation is provider-free and checks the URI scheme/authority/tree scope, canonical path segments, identity pair completeness, checkpoint/staging scope, cleanup ownership, enum tokens, bounded collections, and byte counts. Filename/display-name values never grant deletion authority; provider work remains outside the DAO/Room mapping layer.

## Transaction and revision rules

### Transfer snapshots

`RoomTransferSnapshotStore` is the `:core-data` adapter for the pure-JVM `TransferSnapshotStore` contract in `:core-transfer`.

- A transition checks the full persisted prior snapshot, requires exactly the next reducer `snapshotVersion`, and writes every snapshot field as a typed Room column with an incremented adapter `row_revision` in one Room transaction. No serialized snapshot object is stored.
- A first insert uses `row_revision = 1`. A replay of the identical current snapshot is idempotent. A stale previous snapshot, a gapped reducer version, scope change, confirmed-offset regression, or row-revision exhaustion is a typed conflict; it is never last-writer-wins.
- Per-ack checkpoint writes are transactional and idempotent at the current offset. They update the dedicated `confirmed_bytes` column and row revision, reject negative/out-of-range/regressing offsets, and never promote optimistic bytes.
- Reads reconstruct a domain snapshot from typed columns and validate row metadata, identity/session/state/version, enum tokens, digest encoding, error-field completeness, boolean values, descriptor bounds, and cross-field invariants before returning it. Unknown or malformed persisted fields produce typed failures, not `Missing`.
- The existing session-retention query refuses to prune a session that still owns any transfer snapshot or SAF journal parent.

### SAF journal

`SafCommitJournal` is a typed `read(commitId)` / `write(checkpoint, expectedRevision)` contract. The production `RoomSafCommitJournal` lives in `:core-storage` and maps to the public Room DAOs/entities owned by `:core-data`; the direction does not form a dependency cycle, and provider code never receives an entity.

- `read` uses one Room transaction for parent and child queries, so the returned value is one consistent revision. A parentless checkpoint with children is rejected rather than reported missing.
- `write` validates the domain checkpoint before database work, then in one Room transaction loads and validates the prior parent/children, permits an exact idempotent retry, checks `expectedRevision`, replaces the parent and the complete owned child sets, and commits the new revision. The first revision is 1; every changed valid write increments by one. Stale writers and revision exhaustion return typed conflicts.
- Expected child counts plus stable zero-based sequence values detect missing, extra, or reordered rows. Foreign keys, unique indexes, domain validation, and transaction rollback prevent mixed parent/child snapshots.
- `SafCommitJournalCursor` keeps the revision read for a coordinator operation and passes it into the next CAS. A stale cursor cannot silently overwrite another writer.
- Storage-full, ordinary I/O, malformed/unsupported data, and state conflicts stay distinct. Sensitive provider strings and raw exception text are never put into these errors.

## Tests and evidence required

The base Room v2 suite includes Robolectric/Room tests for migration of the authentic committed v1 schema, preservation of representative existing rows, fresh v2 creation and Room validation, indices/foreign keys, snapshot CAS/rollback/reopen/malformed-row behavior, journal enum/phase/strategy/policy and child mapping, exact cleanup/rename evidence, unsupported versions, malformed identity/digest/ownership, stale and parallel writers, idempotence, child-only cascade, rollback after injected child failure, and database reopen. Those base tests ran in the green exact-SHA Android CI run recorded above. Restoration-specific Room close/discard/reopen tests are added separately; they still need hosted execution at the final restoration SHA. Test source is not evidence that those new tests pass.

Exact completion evidence must include the authentic KSP-produced and committed `2.json`, unchanged v1 hash/size, byte-comparison in CI, passing migration/fresh-install/reopen tests, all JVM tests, zero lint errors, debug and instrumentation APKs, and exact-SHA green CI. `CURRENT_MILESTONE` must remain 2 and `TRANSFER_ENGINE` must remain gated.
