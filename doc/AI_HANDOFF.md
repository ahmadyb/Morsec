# Morsecode project handoff

You are continuing an existing Android project. Do not restart, redesign,
replace, or regenerate the repository.

## Repository

GitHub repository:
https://github.com/ahmadyb/Morsec

Working branch for this Arena session:
arena/01a10ca1-morsec

Accepted parent SHA:
`43e575b1c9e2aea6b70bd03f0ac76ac642e14210`. This session is fixed to
`arena/01a10ca1-morsec`; do not switch branches, modify the prior Arena branch, or
push anywhere else. Preserve the live worktree and untracked files. Never use
`git reset --hard`, rewrite history, or run `git clean` without explicit path-by-path review.

Older session notes below name prior Arena branches and heads; they are historical
records, not branch instructions. The current authoritative scope and latest status are
recorded first below.

---

## Current authoritative state — 2026-10-06: callable SAF process-restoration group (in progress)

This is the current, narrower authorization and supersedes older handoff statements that
said a restoration coordinator was not approved. Work only on the explicitly invoked
Milestone 3 SAF process-restoration and safe-cleanup orchestration group, then stop.

- Repository: `https://github.com/ahmadyb/Morsec`.
- Fixed Arena branch: `arena/268e777f-morsec`; accepted starting SHA:
  `b758135a2b820a11c78e3df46967d146aacae2f0`. At the initial recheck local HEAD matched
  this SHA and the accepted exact-SHA CI run `37469198527` was green. At that same recheck,
  the preferred remote branch was `540bfaed700aa17476b9394587e3174489e18685` and the
  assigned remote ref was absent. Do not merge/rebase to the preferred branch; publish
  only to the assigned branch.
- Schema v1/v2 remain frozen: database version 2, no v3, no schema/column/table changes,
  no new migration, and no destructive fallback. The essential grant, exact document,
  cleanup, phase, digest, and revision fields are present in the existing v2 schema.
- Scope includes bounded deterministic checkpoint discovery, validation before document
  provider access, typed classifications, exact persisted-grant resolution, reuse of
  `resumeOrReconcile`, exact-identity cleanup, process-local checkpoint locks, bounded CAS
  conflict reload/reclassification, explicit retry guidance, redacted reports, a real
  production factory, Room close/discard/reopen reconstruction tests, and documentation.
  No network transfer is restarted and no retry is scheduled.
- No WorkManager/AlarmManager, services/FGS, startup initializers, notifications, wake locks,
  networking/LAN/Nearby/Wi-Fi Direct, transfer UI, Media3, or WebShare. `CURRENT_MILESTONE`
  stays 2 and `TRANSFER_ENGINE` stays gated.
- The coordinator suite has 14 test methods and the Room reconstruction suite has 9, plus
  a staging-key collision regression. Local `./gradlew test` cannot start because this
  sandbox has no Java, `JAVA_HOME`, Gradle, or Kotlin compiler. First exact-SHA Android CI
  run [37509054977](https://github.com/ahmadyb/Morsec/actions/runs/37509054977) on
  `5cb815499991253fd2e0d209896fa1f1c92b9d05` compiled, passed milestone hygiene, all
  reference/design/protocol verifiers and KSP/schema comparison, produced 0 lint errors/45
  accepted warnings across 7 reports, and assembled a 19.00 MiB debug APK plus 1.10 MiB
  instrumentation APK. It reported 2,611 JVM tests (app 588, core-data 56, core-design 100,
  core-model 40, core-storage 1,388, core-transfer 439); only the same test-class
  initialization error failed in both Debug and Release:
  `production factory explicitly restores app private staging through real DocumentsContract`
  ended with a Boolean expression, but JUnit requires `void`. The focused correction is
  committed locally as `992048d` (`assertTrue(stagingStore.delete(id))`) and awaits push plus
  exact-SHA rerun. No test pass is claimed until that run is green. Preserve rescue bundles,
  publish only to the assigned branch, and require exact local/remote SHA equality plus green
  exact-SHA CI before completion. No PR or other branch.
- `doc/transfer-restoration.md` records the runtime boundary. The older
  `MORSEC_OFFLINE_PATCH/` and `MORSEC_ROOM_V2_RESCUE_2026-10-06/` paths named below were
  absent in this checkout; do not overwrite or delete any rescue artifacts if they appear.
  Two current rescue snapshots are outside Git at `/home/user/MORSEC_RESTORATION_RESCUE_2026-10-06/`
  and `/home/user/MORSEC_RESTORATION_RESCUE_2026-10-06-v2/`; preserve them until the
  assigned-branch commits are remote and exact-SHA CI is green.

---

## Historical authoritative state — 2026-10-06: Room v2 persistence group

The accepted SAF destination implementation was the starting point at
`43e575b1c9e2aea6b70bd03f0ac76ac642e14210`. The fixed Arena branch is
`arena/01a10ca1-morsec`. The pre-existing local HEAD
`7fab62ffd6387855bd3fb80c03683dd93c496a36` was an ancestor; after verifying the
rescue checksums, the branch was reconciled with a non-destructive `git reset --mixed`,
which preserved the Room v2 worktree changes. The focused migration-test SQL-placeholder
correction was committed and pushed as
`5b2c14fabe27cfb026d16e1267bf4677665d9007` (`Fix Room migration schema fixture
quoting`). Exact-SHA Android CI run
[37468340772](https://github.com/ahmadyb/Morsec/actions/runs/37468340772) is green on
that commit. This handoff is being synchronized after that run; the synchronization is
documentation-only. Do not switch branches, rewrite history, force-push, merge, or create a
PR. Push directly to the fixed branch.

Only the explicitly approved Room v2 persistence group is in scope. The implementation
includes the v2 transfer entities/migration/store, a production Room `SafCommitJournal`,
validation/CAS cursor and tests, the committed authentic schema export, schema verifier/CI
wiring, and documentation. Four hosted runs have been attempted. Run
[37464589550](https://github.com/ahmadyb/Morsec/actions/runs/37464589550) on
`afb3eae95329761b340d10a41723e88ae4f2e108` found compile/test failures. Run
[37465900006](https://github.com/ahmadyb/Morsec/actions/runs/37465900006) on
`cc149dfc4dd697967a9687348a1120e3f0695b80` compiled and assembled both APKs but failed
12 tests, one API-level lint check, and strict schema verification. It generated the
authentic Room/KSP `2.json`; its 50,652 bytes were reconstructed byte-for-byte from CI
check annotations. Run [37467189323](https://github.com/ahmadyb/Morsec/actions/runs/37467189323)
on `420a56e15fd91ca856555da7e357124cf9b5774f` completed red solely because the same
migration test failed in the Debug and Release unit-test variants (2,579 tests total, 2
failures, 0 skipped). The emitted SQL had doubled backticks in an index `ON` clause. The
narrow fix in `5b2c14fabe27cfb026d16e1267bf4677665d9007` uses the bare table name when
substituting Room's already-backticked `${TABLE_NAME}` token.

Exact-SHA run [37468340772](https://github.com/ahmadyb/Morsec/actions/runs/37468340772)
on `5b2c14fabe27cfb026d16e1267bf4677665d9007` is green. It executed `checkMilestoneHygiene`,
`test`, `:core-data:kspDebugKotlin --rerun` (`--no-build-cache`), the seven module
`:lintDebug` tasks in the workflow, `:app:assembleDebug`, and
`:app:assembleDebugAndroidTest` (Gradle 8.13, JDK 17, API 36). All 2,579 JVM tests passed
(0 failed, 0 skipped, 143 reports); modules: app 588, core-data 56, core-design 100,
core-model 40, core-storage 1,356, core-transfer 439. KSP regenerated v2 and the committed
schema comparison passed byte-for-byte. Lint had 0 errors and 45 warnings across 7
reports. Both APKs assembled (debug 18.94 MiB; instrumentation 1.10 MiB), and the
`morsecode-debug-apk` artifact is available on the run page. The sandbox has no `java` or
installed Gradle, so JVM/Android validation is performed by hosted CI.

Before continuing, preserve all of the following:

- `MORSEC_OFFLINE_PATCH/` — prior SAF rescue artifact; do not alter or delete it.
- `git stash list` was empty after reconciliation, and a read-only reflog/object check
  found no stash entry. No stash was applied, created, or dropped. Do not assume one is
  present; the checksummed rescue directory below is the available recovery copy.
- `MORSEC_ROOM_V2_RESCUE_2026-10-06/` — SHA-256 recorded copy of the pre-reconciliation
  tracked patch and untracked sources.
- Any new or unexpected worktree changes. Never use `git reset --hard` or unreviewed
  `git clean`.

The v1 schema remains at
`core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json` (29,767 bytes,
SHA-256 `b0bca4243d2f0ba4631e3338e611d3bcaff8ba456de83b79ea0106ae187ac488`); it is
unchanged. The authentic KSP-generated v2 export is committed at
`core-data/schemas/app.morsecode.core.data.db.MorseDatabase/2.json` (50,652 bytes,
SHA-256 `7e6acfd9c03b0214dcaddc2f5d1ceb175588f5cfcf93cca5437617be24b2b074`). The strict
local verifier passes 11/11, and green run 37468340772 regenerated and compared the
committed export successfully. Never hand-author v2 or regenerate over v1; keep CI's
byte-for-byte KSP comparison.

Current code has explicit `Migration(1,2)`, Room schema version 2, bounded SAF parent/
child tables with child-only ownership cascades, `RoomTransferSnapshotStore`, and
`RoomSafCommitJournal`. Transfer snapshots use validated typed Room columns; no
serialized snapshot object is persisted. Run 1 found compilation errors and seven
`core-transfer` failures. Run 2 fixed compilation but recorded 12 test failures (six
distinct cases duplicated across variants): migration fixture quoting; a journal fixture
that overwrote its deliberately corrupted parent; and four recovery cases, including
invalid identity/scope rejection before provider access and a same-identity final-rename
checkpoint with a null returned URI. Run 3 confirmed those fixes and isolated the final
fixture placeholder bug; the bare-name replacement passed on run 4. Thus the authentic-v1
migration/existing-row preservation test, fresh-v2 validation/DAO/reopen test, typed
validation/conflict tests, and complete JVM suite pass on the green exact SHA.

Local checks after the correction: reference verifier 31/31, token parity 195/195,
protocol limits 21/21, strict Room schema 11/11, Node syntax check and `git diff --check`
pass; v1 hash remains unchanged. Run 4 had 0 lint errors and 45 warnings (27 unused
resources, 11 plural candidates, 4 configuration screen-width checks, 2 selected-photo
access checks, and 1 old target API); both APKs assembled. The workflow also reported a
non-blocking Actions Node.js 20 deprecation notice. No local Kotlin/Android test run is
possible without Java/Gradle; hosted CI is the verified build/test path.

`CURRENT_MILESTONE` must stay 2 and `TRANSFER_ENGINE` must remain gated. Do not start a
restoration coordinator, cleanup worker, LAN/Nearby/Wi-Fi Direct, transfer networking,
foreground service, notification, wake lock, transfer UI, Media3, WebShare, Room v3, or
automatic startup recovery. Stop after the persistence group.

---

## Historical state — 2026-10-05: SAF destination closure accepted

Complete only the SAF destination closure pass on the fixed branch
`arena/01a10ca1-morsec`, based on accepted parent
`7fab62ffd6387855bd3fb80c03683dd93c496a36`. Keep `CURRENT_MILESTONE = 2` and
`TRANSFER_ENGINE` gated until milestone 5. Do not begin Room schema v2 or migration,
a Room transfer adapter, LAN/Nearby/Wi-Fi Direct, foreground service, notifications,
wake locks, transfer-screen integration, Media3, WebShare, or later-milestone work.

The closure implementation and tests were pushed at
`156cfa95b3bf9f5944ff34568fdb5dc95950838f`. Exact-SHA Android CI run
[37376882298](https://github.com/ahmadyb/Morsec/actions/runs/37376882298) is green on
that SHA. It records implementation/test evidence; the final handoff synchronization is
documentation-only, and the final delivery report records its resulting SHA and exact-SHA
run.

### Verification evidence

- **JVM tests:** 2,531 passed, 0 failed, 0 skipped in 137 reports. `core-storage`:
  1,328 passed, 0 failed, 0 skipped in 70 reports.
- **Static checks:** milestone hygiene and reference/design-token/protocol-limit
  verifiers passed.
- **Android lint:** 0 errors, 45 warnings in 7 reports.
- **Artifacts:** debug APK 18.87 MiB; instrumentation APK 1.10 MiB.
- **Room:** CI exported the unchanged version-1 schema: 9 tables, 29,767 bytes. No Room
  v2 entity, migration, or transfer adapter was started.
- **Local toolchain:** Gradle cannot run in this sandbox because Java is not installed;
  hosted CI is the compile, test, lint, and APK evidence.

### SAF closure implementation and acceptance evidence

1. The version-2, Room-independent checkpoint carries the verified staged-content digest
   separately from the optional expected digest, exact provider identities, scoped rename
   history, and pending-cleanup identities. Journal intent/result boundaries surround
   provider create/copy/flush/rename and deletion operations. Failed intents stop before
   mutation; a mutation without a saved result remains unresolved.
2. Provider-temporary and backup cleanup is production-code reachable only after the
   authoritative final identity is established using exact URI/id, expected final name,
   size, digest, direct parent, persisted grant/tree, and commit/rename context. If both
   pre-rename and returned identities resolve, neither is automatically declared
   obsolete. Cleanup intent is saved before deletion. If its result save fails, recovery
   queries that exact URI/id and does not blindly repeat deletion.
3. Executable recovery and result-save-failure tests cover create, rename, flush, provider
   temporary, backup, staging, and interrupted-copy cleanup boundaries. The committed
   recovery test revalidates final identity and provider bytes/digest and asserts no
   recreation, rewrite, deletion, or second rename.
4. The failure matrix covers ENOSPC at writable open after provider creation, after a
   partial stream write, and at flush/sync; ordinary I/O remains distinct from storage
   full. Permission revocation and `SecurityException`, plus cancellation around create,
   open/write, flush, verification, rename, reconciliation and deletion, are exercised.
   Cancellation after mutation but before a result checkpoint remains
   `RECONCILIATION_REQUIRED`.
5. An integrated coordinator test streams, verifies, and accounts for a virtual 5-GiB
   destination with `Long` counters and bounded buffers; it allocates neither a 5-GiB
   fixture nor a real 5-GiB digest.
6. Safe overwrite builds/verifies a replacement before moving the old final to a backup;
   recovery retains both identities until the new final is proven and never uses
   delete-first. Visible-final fallback is explicit, cannot overwrite, remains visible
   while incomplete, and interrupted rows are never committed; recovery uses exact
   identity before cleanup/recreation.
7. ADR-0003 and `doc/architecture.md` describe the checkpoint, ordering, recovery,
   provider limits, failure coverage, and durability boundary. Provider filenames use the
   127-byte UTF-8 budget and preserve Unicode code points while fitting deterministic
   suffixes.

### Durability and provider limits

`SafCommitJournal` is an abstraction; `InMemorySafCommitJournal` exists only as a test
fake. There is **no production journal implementation**, so the versioned checkpoint and
fake-backed executable recovery do not establish process-death durability. Checkpoint
model version 2 is distinct from Room schema version 2. A successful SAF flush is only
`FlushAttemptedGuaranteeUnknown`.

Containment evidence is API-tiered: API 23–25 can use the selected tree root but cannot
prove child ancestry; API 26–28 may use `findDocumentPath`; API 29+ may use
`isChildDocument`. A provider query/rename that is absent, failed, ambiguous, or cannot
re-establish the stored identity yields a typed unknown/refusal or reconciliation, not a
heuristic success. The closure coordinator tests use a recording fake gateway; CI does not
establish compatibility with every OEM or third-party document provider. The visible-final
strategy must be opted into and cannot provide atomic visibility or overwrite.

The refreshed offline rescue artifact is retained at
`MORSEC_OFFLINE_PATCH/saf-destination-closure-2026-10-05.tar.gz`. It contains the full
tracked delta from the accepted parent through the final tracked tree, a path-preserving
copy of the closure test, and checksums. Preserve it and the untracked `MORSEC_OFFLINE_PATCH/`
directory; do not reset, clean, or switch branches. Stop when SAF closure is complete.

---

## Previous verified baseline — 2026-10-04: Part C SAF destination group active

A and B are complete and approved. Work only on Part C, then stop; do not begin Room v2,
a migration/adapter, cleanup service, restoration coordinator beyond SAF reconciliation,
LAN, Nearby, FGS, notifications, UI, Media3 or WebShare. Room schema remains version 1.

The accepted starting SHA was
`93c6d3959a18c184687b1ce35a48d2f815e353c7`; the earlier green CI run was `37218808121`.
The first follow-up commit, `8db2a6e19b09b2b758eae6cb48fcfc37e2438f1c`, was pushed
directly to `arena/01a0f97d-morsec` and matched the remote after push. Exact-SHA Android
CI run `37234748228` found five distinct test defects (reported in both Debug and
Release): two deletion tests invoked `delete()` a second time from an assertion message,
and three redactor tests exposed an opaque-blob regex that consumed field labels and
app stack frames. These were corrected in focused commit
`2963893ef94a4798bda569948d1dc5f5681c8d65`.

CI run `37235364969` for code SHA
`2963893ef94a4798bda569948d1dc5f5681c8d65` is green. GitHub Actions reports 2,343
tests, 0 failed, 0 skipped in 127 reports; `core-storage` reports 1,140 passed, 0
failed in 60 reports. Milestone hygiene and all read-only verifiers passed. Lint had
0 errors and 45 warnings in 7 reports. The debug APK was 18.80 MiB and the
instrumentation APK was 1.10 MiB. The Room schema export was version 1, 9 tables,
29,767 bytes; no v2 schema was added. The workflow's Room export step is read-only,
and its generated-wrapper commit step was skipped. The final delivery report records
the exact pushed SHA/run; any later metadata-only commit still requires exact-SHA CI.

The follow-up patch is preserved at
`MORSEC_OFFLINE_PATCH/unverified-part-c-followup.patch`, against the accepted base,
and was checked by a single application to a temporary clean-base export. Keep all
earlier rescue artifacts. The local sandbox has no Java runtime, so the requested
Gradle commands stop before Gradle starts; CI is the source of compile/test/lint/APK
evidence. Part C remains limited to SAF destination behavior and review; no Room v2,
LAN, Nearby, FGS, notifications, UI, Media3 or WebShare work was started.

The implementation keeps provider URI/id pairs and rename evidence on the commit
record, exposes exact-identity cleanup retry for a delivered record, and separates
`stagingReleased` from delivery. Every commit hashes staging in a bounded fresh pass
through exact EOF, even without a caller-supplied expected digest; a supplied digest is
compared with that pass, and the transient staged SHA-256 is required to match a fresh
provider read through exact EOF before publication. A locally calculated digest proves
the provider copy matches staging; without a sender-provided expected digest or equivalent
trusted verification, it does not prove sender authenticity. A bounded one-byte probe
rejects trailing provider data even when size metadata is absent. Cleanup retry validates
stored grant/tree context and a cleanup-authorizing state; provider deletion queries the
exact stored identity before and after the request. Digest bytes, content URIs, private
paths, control characters, opaque IDs, and peer-visible stack traces are redacted.

The requested Part C SAF follow-up passed exact-SHA CI on code commit
`2963893ef94a4798bda569948d1dc5f5681c8d65` (run `37235364969`, evidence above).
Coverage includes the production gateway with registered provider fakes, bounded copy,
flush/close/fresh-read/verify ordering, temp-plus-rename recovery, explicit visible-final-
copy policy, RENAME/SKIP/ASK/OVERWRITE, recoverable overwrite without delete-first,
rename reconciliation, exact-identity cleanup retries, storage-full and grant-revocation
handling, descriptor closure, and virtual >4-GiB accounting. This does not authorize
starting the next milestone or any excluded subsystem: stop this follow-up for review.
The final response records the exact pushed branch SHA and CI URL for the final commit.

The offline follow-up patch is regenerated from accepted base `93c6d3959a18c184687b1ce35a48d2f815e353c7`,
checked by applying it once to a clean temporary export, and preserved at
`MORSEC_OFFLINE_PATCH/unverified-part-c-followup.patch`. Older bundles remain untouched.
Keep Part C scope limited to SAF destination behavior and tests; do not start Room v2,
LAN, Nearby, FGS, notifications, UI, Media3, WebShare, or unrelated coordinators.

## Historical gate — 2026-10-03: SAF destination gateway, two platform-safety corrections

Branch `arena/01a0f97d-morsec`. Part B of the transfer storage group. `TRANSFER_ENGINE`
is still gated at milestone 5 and `CURRENT_MILESTONE` is still 2.

Two corrections to `DocumentsContractSafGateway`, made before it is wired to
`SafCommitCoordinator`. Both were requested after the gateway itself went green, and
both change what production code is allowed to trust.

### Deletion is settled by observation, not by the request

`DocumentsContract.deleteDocument` returns a boolean that is **not** the provider's
answer to "is it gone". Measured against a real `ContentProvider` driven through a real
`ContentResolver`: a provider that kept the document and answered *false* was reported by
the platform as a success, and a provider that removed the document and answered *false*
was also reported as a success. The boolean carries no information about absence.

`deleteAndReconcile` therefore issues the request, queries the exact stored identity
again, and classifies what it observed:

| Outcome | Meaning | Cleanup |
| --- | --- | --- |
| `ConfirmedAbsent` | a query completed and returned no row | complete |
| `StillPresent` | the exact identity still resolves | pending |
| `QueryUnknown` | presence and absence both unproven | reconciliation required |
| `PermissionRevoked` | the grant went before absence was proved | pending |
| `IdentityMismatch` | the URI resolves to a different document | nothing deleted, nothing assumed |
| `DeleteRequestFailed` | the request threw or could not be issued | not complete |

The request's boolean is retained as `deleteReported` and is never read to decide an
outcome. It is kept so a log can record what the platform claimed while the outcome
records what was observed.

### The API tier is a type, selected from the real SDK

`isChildDocument` is API 29 and `findDocumentPath` is API 26. The first design held an
injected `sdkInt` integer with an `if` in front of each versioned call. That integer is
not the device, so anything able to construct the gateway could point production code at
a symbol the running device does not have — and the failure for that is a linkage error
at the call site, which no typed error mapping downstream can catch.

The tier is now a type: `SafPlatformOperations`, with `SafApi23Operations` (grant-scoped
containment only), `SafApi26Operations` (adds `findDocumentPath`) and
`SafApi29Operations` (adds `isChildDocument`). Each contains only the calls its own
minimum supports and is annotated `@RequiresApi(26)` / `@RequiresApi(29)`.
`SafPlatformOperations.create()` branches on `Build.VERSION.SDK_INT` and takes no SDK
argument. Tests inject a fake, which replaces a tier rather than choosing one by number.

**Both `SuppressLint("NewApi")` annotations are gone.** They existed only because lint
cannot analyse a guard on an arbitrary integer; guarding on `Build.VERSION.SDK_INT` is a
guard lint can see. `grep -rn SuppressLint core-storage/src/main/.../transfer/` now
returns nothing.

### Commits and CI

| Commit | What | CI |
| --- | --- | --- |
| `512bec7` | deletion by observation, tier from the real SDK, provider extracted | — |
| `2c536c0` | deletion and tier tests | red — see below |
| `3fffb21` | fix: import `File`, widen `DeleteBehaviour` | red — see below |
| `b101728` | fix: assert the provider's answer, not the platform's | **green, run 37184344527** |

Both red runs are worth remembering:

- `2c536c0` — extracting `FakeSafProvider` out of the test that owned it lost two
  things that used to come for free: the `java.io.File` import, and the ability for
  `DeleteBehaviour` to stay `internal` (a public class cannot expose an internal type
  from a public property).
- `3fffb21` — a test asserted that `deleteAndReconcile` retained the *false* the provider
  reported. The gateway retained *true*, because the platform does not relay the
  provider's answer at all. The provider said failure, the platform said success, and
  the document was gone. That is the strongest evidence yet for proving absence by
  query, so the test now asserts the provider's own answer and leaves the platform's
  return value alone — pinning it would be pinning the platform's unreliability rather
  than the gateway's behaviour.

### Gate at `b101728`

2187 tests, 0 failed, 0 skipped. core-storage 990 in 52 reports. Lint 0 errors, 45
warnings. `app-debug.apk` 18.78 MiB, `app-debug-androidTest.apk` 1.10 MiB. Room schema
`1.json` unchanged.

### Gate handoff at that historical point (superseded)

At this point the coordinator was not yet wired. That wiring and its first tests were
completed in later Part C commits; the active remaining criteria are listed in the
current authoritative state and the latest Part C acceptance report below. Room schema
version 2 remains gated until the whole SAF destination group is green and explicitly
approved.

## Session state — 2026-10-03: the transfer storage slice is COMPLETE and green

The Android storage and durable-persistence group, part one. Branch
`arena/01a0f97d-morsec`. `TRANSFER_ENGINE` is still gated at milestone 5 and
`CURRENT_MILESTONE` is still 2.

### Commits and CI

| Commit | What | CI |
| --- | --- | --- |
| `9d345f0` | ADR-0003, the mandated pre-implementation design record | green, run 37062747660 |
| `e36bebe` | CI publishes the Room schema export (artifact + check annotations) | green, run 37062989874 |
| `1debd99` | CI generates the export deterministically | green, run 37063511139 |
| `543ef1e` | the version-1 Room schema, exported by CI then committed | green, run 37064149717 |
| `28adb4f` | source abstraction, fingerprint policy, descriptor ownership | green, run 37065608957 |
| `6c5b591` | CI reports per-module unit-test counts | green, run 37066464775 |
| `09f81ec` | durability capability, partial identity, reconciliation | green, run 37066961227 |
| `6c550e1` | the checkpoint coordinator | green, run 37067965803 |
| `6161762` | incremental verification and the app-private partial | red — see below |
| `9f3ad00` | fix: two tests used a digest wrapper as a hash function | green, run 37103757966 |
| `55c48ac` | close four test-coverage gaps | red — missing import |
| `87022a7` | fix: add the missing `java.io.File` import | red — see below |
| `9247e38` | fix: measure sparse-file consumption another way | **green, run 37104618380** |

Two of the three red runs are worth remembering, because both were honest
failures that the design record predicted would be caught:

- `6161762` — `Sha256Digest.of(bytes)` is not a hash function. It wraps exactly
  32 raw digest bytes and returns null for anything else, which is correct for
  its real job in `FrameCodec` (parsing a 32-byte digest out of a frame header).
  Two tests fed it 200,000 bytes of file content and so compared against
  nothing. Both expected digests are now hardcoded from values computed with
  Python's `hashlib`.
- `87022a7` — the sparseness assertion read `unix:blocks`, which the JDK's unix
  attribute view does not expose. Physical consumption is now measured as free
  space before minus free space after.

### The version-1 Room schema, for the record

| | |
| --- | --- |
| Path | `core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json` |
| Size | 29,767 bytes |
| SHA-256 | `b0bca4243d2f0ba4631e3338e611d3bcaff8ba456de83b79ea0106ae187ac488` |
| Generated by | CI run 37063511139 at commit `1debd99`, job 111025560065 |
| `formatVersion` | 1 |
| `database.version` | 1 |
| Tables | 9: browser_sessions, crash_reports, history_entries, log_entries, recent_devices, saf_grants, transfer_items, transfer_sessions, web_transfers |
| `identityHash` | `6f98728aff1c696dffae0399bc8cf47f` |
| Contains v2 tables | no `transfer_snapshots`, no `transfer_partials` |

It was produced by KSP while `core-data` was at `version = 1` with nine entities
and no version-2 entity classes anywhere in the repository, then retrieved from
CI's check-run annotations, inspected, and committed by hand. CI never commits.

**The 3,050-byte figure is the compressed artifact, not the file.** GitHub
reported the `room-schema-export` artifact as 3,050 bytes; that is the zip.
Deflating the committed 29,767-byte JSON and zipping it with its filename
reproduces 3,046 bytes — within 4 bytes of GitHub's number, the difference being
zip metadata. For reference, the same file is 2,806 bytes as raw deflate and
3,744 characters as the base64+zlib annotation payload. Nothing is inconsistent.

### How to regenerate it, and why it is not regenerated casually

The workflow generates it with:

```
./gradlew --no-daemon --stacktrace --no-build-cache :core-data:kspDebugKotlin --rerun
```

Both flags matter. KSP writes the schema as a *side effect*, and it is not a
declared task output, so when `:core-data:kspDebugKotlin` is restored
`FROM-CACHE` the module compiles correctly and no JSON is written at all. That
is exactly what happened on the first attempt: the export step found nothing.

`tools/verify/room-schema.mjs` now runs in CI and refuses a `1.json` that is not
version 1, does not contain exactly the nine accepted tables, contains either
version-2 table, or does not match the structure Room emits. It was tested
against a deliberately tampered v2 schema and fails it.

### What was built

In `core-storage`, package `app.morsecode.core.storage.transfer`: the source
abstraction and its six-outcome fingerprint policy; typed errors with mechanical
redaction; single-owner handles for descriptors and for files; a bounded
non-seekable reposition loop; the four-outcome durability capability;
name-independent partial identity and the six commit states; reconciliation of a
persisted checkpoint against the partial that is actually present; the checkpoint
coordinator that enforces validate → frontier → write → flush → persist → ack;
incremental SHA-256 verification with an injectable digester; and the
app-private partial, the one destination where `force(true)` is a real fsync.

### Still not started

Schema version 2, migration 1→2, the Room `TransferSnapshotStore` adapter, the
restoration coordinator, and cleanup. Also still untouched: LAN, Nearby,
foreground services, notifications, transfer-screen integration, Media3 and
WebShare.

---

## Session state — 2026-10-02: the Milestone 3 corrective pass is COMPLETE

A correction to the transfer core, not a new implementation group. Milestone 3
shipped green at `0404eb7` (run 36970741133) and was then held to eight findings
raised against it. Every finding is resolved; nothing outside the eight was
touched, and no Android, LAN, Nearby, service, Media3 or WebShare work was begun.

**Content-final source SHA:** `f4b8cfd`. **Green run:**
<https://github.com/ahmadyb/Morsec/actions/runs/36999633087>. The commit that
adds this section changes documentation only and touches no source.

| Check | Baseline (`0404eb7`, run 36970741133) | This pass (`f4b8cfd`, run 36999633087) |
| --- | --- | --- |
| Unit tests | 1163, 0 failed, 0 skipped, 70 reports | **1235, 0 failed, 0 skipped, 73 reports** |
| Android lint | 0 errors, 45 warnings, 7 reports | **0 errors, 45 warnings, 7 reports** |
| Debug APK | 18.64 MiB | **18.64 MiB** |
| androidTest APK | 1.10 MiB | **1.10 MiB** |
| `checkMilestoneHygiene` | clean | **clean** |
| Node verifiers | refs 31/31, tokens 195/195, icons 61/61 | **plus `transfer-limits.mjs` 21/21** |

The five Gradle invocations were run by CI in that order:
`checkMilestoneHygiene` → `test` → `:app:lintDebug :core-design:lintDebug
:core-data:lintDebug :core-storage:lintDebug :transport-lan:lintDebug
:transport-nearby:lintDebug :media:lintDebug` → `:app:assembleDebug` →
`:app:assembleDebugAndroidTest`.

### 1. Files above four gibibytes

`MAX_FILE_SIZE_BYTES` was `4,294,967,295` — a 32-bit length field leaking into a
`Long`-sized domain — so every delivery was silently capped at 4 GiB − 1. It is
now `8,796,093,022,207` (8 TiB − 1). `LargeFileTest` (32 tests) drives a 5 GiB
file end to end: descriptor construction, offsets at and past the 4 GiB mark, the
short tail chunk, DATA_CHUNK and CHUNK_ACK frames carrying offsets above 2³¹,
resume, snapshot persistence, broadcast totals and the scheduler.

Two helpers make the wider domain safe rather than merely larger:
`ProtocolLimits.isValidRange(offset, length)` compares `offset <= MAX − length`
so no sum can wrap into a small positive number, and `checkedEnd` only adds after
the range has been accepted. `TransferReducer`'s chunk-range check now routes
through both instead of adding inline.

### 2. The limits table now checks itself

`doc/transfer-protocol.md`'s limits table had drifted from `ProtocolLimits.kt`
in five places: it claimed a 4 KiB minimum chunk (the code enforces 1 KiB), a
255-byte path segment (127), a 240-byte error budget (256), a file ceiling of
8 TiB (the code enforced 4 GiB − 1) and gave the frame ceiling as a formula
instead of a number. The table is now the complete set of **22** numeric
constants, each written as a plain number.

`tools/verify/transfer-limits.mjs` parses both sides and fails on any
disagreement in either direction — a value that differs, a constant the table
omits, a row naming a constant that no longer exists, or a row whose value cell
carries no comparable number. The four magic bytes are the only exclusions, and
each must still exist in the source so the exclusion list cannot rot. CI runs it
(step 9) alongside `refs.mjs` and `token-parity.mjs`.

### 3. Unused dependencies removed

`core-transfer` declared `kotlinx-coroutines-core`, `kotlinx-serialization-json`
and `javax.inject` on `api`/`implementation`, plus `kotlinx-coroutines-test` and
`turbine` for tests, and imported none of them. The complete set of non-Kotlin
types its main sources reference is `java.nio.ByteBuffer`, two
`java.nio.charset` types and `java.security.MessageDigest`. All five are gone;
the module declares `:core-model` and JUnit only. The same verifier enforces an
import allow-list and re-checks the build file, so they cannot creep back.

### 4. Rejection coverage

Three of nineteen variants had no test asserting their exact code, and the
reasons differed:

- **`AcknowledgementBeyondSent`** was reachable but untested beyond invariant 22.
  It now has its own test.
- **`DuplicateDescriptor`** and **`UnsupportedProtocol`** were never produced at
  all. The reducer never sees the queue, so it cannot know a file is already
  enqueued — that is `TransferScheduler`'s `BlockReason.DUPLICATE_TRANSFER_ID`
  and `BroadcastAggregator`'s per-recipient distinctness. And `ProtocolVersion`
  refuses to exist outside the supported window, so no descriptor can carry an
  out-of-window version; the refusal belongs where an untrusted `Int` first
  arrives, which is `FrameCodec` and `TransferSnapshotCodec`, both of which emit
  `TransferError.ProtocolVersionMismatch`. **The validated domain type was not
  weakened to make either reachable.** Both variants and the dead branch in
  `TransferReducer.enqueue` were removed, with the reason recorded in
  `Rejection.kt`.

The vocabulary is now **17** and closed: `RejectionCoverageTest` asserts the
exact code for every one through a real reducer call, and fails if the set of
covered codes and `Rejection.allCodes` ever disagree.

Tracing this found a genuine defect: `TransferSnapshotCodec` built its
descriptor's `ProtocolVersion` with the throwing constructor, so a row written by
a build speaking a newer protocol surfaced as "malformed frame" from the
`IllegalArgumentException` catch rather than as a version refusal. It now returns
`SnapshotDecodeResult.Invalid` carrying `protocol_version_mismatch`.

### 5. The frame ceiling, verified

`MAX_FRAME_SIZE_BYTES` is derived in source —
`HEADER_SIZE_BYTES + (3 * MAX_ID_LENGTH_BYTES) + MAX_PAYLOAD_BYTES` = **262,380**
— and `ProtocolLimitsDocumentationTest` (15 tests) now proves it against the
encoder rather than only the arithmetic: a maximal frame with three 64-byte
identifiers and a 262,144-byte payload encodes to exactly 262,380 bytes and
decodes back whole, and the 44-byte header is proved field by field.

### 6. Counts, for the record

12 states · 38 legal transition edges · 12 commands · 19 events · 18 effects ·
17 rejections · 15 frame types · 28 invariants · 22 documented limits · 435
core-transfer tests · 1235 project tests.

### 7. Still gated

`FeatureReadiness.TRANSFER_ENGINE` is `deliveredInMilestone = 5` and
`CURRENT_MILESTONE` is 2, so `isAvailable(TRANSFER_ENGINE)` is `false`. Unchanged.

### 8. Known limitations

- **Nothing proves integration.** 435 tests prove the core's rules; the engine is
  still wired to no transport, no stream adapter and no Room implementation.
- `:core-transfer` is a JVM module, so it gets no Android lint; its discipline is
  enforced by the import allow-list instead.
- The standalone Kotlin harness is an additional local check only. CI is the
  verification of record.
- Git metadata was reset four times this session. The remote branch is the only
  durable copy; local commits are not.

---

## Session state — 2026-10-01: MILESTONE 2 FINAL AUDIT is COMPLETE

Written by the agent that ran the twelve-part final audit you specified. Every
number comes from GitHub Actions annotations.

**Green evidence.**

- Green run: https://github.com/ahmadyb/Morsec/actions/runs/36894415703
  (`test-and-lint-reports`, `Android CI` on `arena/01a0e98a-morsec`).
- Content-final SHA: `f5065108e7c063e22746d788fea9aaea8fc90d83`
  (`f506510`); the milestone-2 docs commit described below sits on top of it.
  `git ls-remote origin arena/01a0e98a-morsec` is the truth, not this file.
- Audit commit chain on top of the approved group-5 SHA `c5927a9`:
  `9da552f` (route/responsive/accessibility tests + 2 lint fixes) → `c3835b4`
  (window/range-query repairs) → `2c0603a` (library+system screen tests) →
  `afc9896` (sheet scroll, doctor's actions un-Hilted, 5 view models hoisted) →
  `b16e1dd` (looper interval API) → `5201971` (doctor `%d` integer args, compose
  clock settle) → `f506510` (sort-sheet buttons held to what the runner sees).

**Completed M2 groups.** Groups 1–4 approved at `2b7d292`, `82fd7a0`, `a860791`
(group 4 run `36719322299`); group 5 (Broadcast UI) approved at `c5927a9`
(run `36855116083`); the final audit is the run above. M2 = the `2–4` UI-depth
row of the README table, delivered as one milestone.

**Screen inventory (exact).** 19 registered routes — `onboarding`, `connect`,
`files`, `viewer`, `music`, `video`, `session/{layout}`, `broadcast/pick`,
`broadcast/sender`, `broadcast/sender/complete`,
`broadcast/receiver/{recipient}(+/complete)`, `folder/{treeUri}`, `history`,
`settings`, `logs`, `crashes`, `doctor`, `help` — plus one declared-unregistered
route, `webshare`, reserved for milestone 11. The parity table
(`doc/qa/milestone-2-screen-parity.md`) covers all 33 mobile registry states:
**28 implemented and tested, 5 gated with a milestone, 0 missing**. The gated
five: `discover` live peer lists (milestones 6–7), peer consent `consentP`
(5+6/7), browser consent `consentB` and the WebShare control screen `webshare`
(11), and the receive screen's idle listener state (5+8). The WebShare *browser*
pages (`wHome`…`wPlayer`) are the TypeScript client, milestones 11–12, not
Android screens.

**Totals.**

- Unit tests: **786, 0 failed, 0 skipped** (51 reports).
- Lint: **0 errors, 45 warnings** (7 reports) — UnusedResources 27,
  PluralsCandidate 11, ConfigurationScreenWidthHeight 4 (test-only, documented),
  SelectedPhotoAccess 2, OldTargetApi 1. No baseline, no severity lowered, no
  new suppressions; dispositions in `doc/qa/lint-and-warnings.md`.
- APK: `app-debug.apk` **18.48 MiB** and `app-debug-androidTest.apk` **1.10 MiB**
  as published by the green run; `test-and-lint-reports` artifact carries the
  HTML.
- Wrapper: committed and used as-is (no regeneration).
- Verifiers: `refs.mjs` 31/31, `token-parity.mjs` 195/195, `icons.mjs --check`
  61/61 at `CURRENT_MILESTONE = 2`.

**Intentional differences from the reference** are recorded in
`doc/fidelity-notes.md` §4.9 (broadcast) and the new §4.10 (visual fidelity):
TalkBack labels and `Selected` semantics are Android adaptations the HTML
reference cannot express; onboarding draws its progress as dots (the
`onb_progress` sentence is a kept token, unused by the screen); the sheet's
content scrolls so a tall menu cannot clip its last button; one Robolectric
limitation is stated — the sort sheet's button row passes composition and
clickability but not the runner's window-bounds display check.

**Non-reversible decisions (do not relitigate).** Transport copy "LAN or
Nearby"; no Manual IP entry and no QR screen; no separate Queue destination; no
global "Retry failed"; one Pause all/Resume all in the bottom bar only;
completed rows carry no controls; five sticky category tabs; video header =
back + filename + metadata only; crash reports = view/export/clear; WebShare
browser UI stays out of the Android app; minSdk 23 / targetSdk 36 / toolchain
pins (ADR-0001); no lint baseline; the mockup and master prompt are read-only
unless asked; `CURRENT_MILESTONE = 2` was bumped with its three test pins and
is bumped only at a delivered milestone.

**Focused storage/media integration task — complete (2026-10-01).** Source
inspection confirmed the production Files, viewer and player view models already
use `MediaRepository`; no replacement of production fixture rows was needed.
The actual gap was that Photos, Videos and Music did not merge matching media
beneath persisted SAF trees, and nested SAF descendants could not all be
resolved by ID. The implementation adds MediaStore/SAF data-source interfaces
and Hilt bindings, breadth-first SAF descendant traversal, kind-filtered and
deduplicated SAF + MediaStore category feeds, nested SAF item lookup, and Files
access-state behavior that keeps tree-granted media usable without broad media
permission. Existing `FeatureReadiness` gates remain; no transfer behavior was
invented or enabled.

**Verification and evidence.**

- Code commit: `136711e2da47d612a9b898527296a025c5324168` on
  `arena/01a0f8ba-morsec`; the remote ref matched after push. Draft PR #1:
  https://github.com/ahmadyb/Morsec/pull/1.
- Green Android CI: https://github.com/ahmadyb/Morsec/actions/runs/36910240632
  (run `36910240632`, head SHA `136711e2da47d612a9b898527296a025c5324168`).
  The earlier run `36909332385` found a test assertion import and a Robolectric
  permission setup problem; both were fixed before the green run.
- CI completed hygiene, JVM unit tests, Android lint, debug APK assembly and
  Android-test APK assembly: **800 tests, 0 failed, 0 skipped** (56 reports);
  **0 lint errors, 45 warnings** (7 reports, matching the documented warning
  baseline); `app-debug.apk` **18.48 MiB** and
  `app-debug-androidTest.apk` **1.10 MiB**. The run uploaded the
  `morsecode-debug-apk` artifact (18,156,590 bytes), `test-and-lint-reports`,
  and `gradle-wrapper`. The Android-test APK was assembled successfully but is
  not a separate uploaded artifact in this workflow.
- `node tools/verify/refs.mjs`: 31/31; `node
  tools/verify/token-parity.mjs`: 195/195; `git diff --check`: clean.
- The sandbox has no Java/JDK or Android SDK, so local Gradle compilation,
  tests, lint and APK assembly could not run; the green Actions run is the
  actual Android verification. Workflow dispatch returned HTTP 403, so the
  draft PR triggered CI. Artifact metadata is available from GitHub, but
  downloading the artifact blob failed with EOF in this environment.

**Next task and protected scope.** This focused task does not claim Milestone 3
delivery: `CURRENT_MILESTONE` remains 2. The next unstarted work is the master
prompt §6 pure-Kotlin transfer state machine (framed chunks, sequence/offset,
per-chunk CRC32 and full-file SHA-256) with tests. Do not start it without
explicit approval. All previously recorded protected UI/product decisions,
minSdk 23, toolchain pins, lint error checks, honest feature gates, and the
prohibition on fake transfer/discovery/playback behavior remain in force.

---

## Session state — 2026-10-01: the Milestone 3 pure transfer core is implemented

> **Superseded in part by the 2026-10-02 correction pass below.** Nothing here was
> reverted, but three numbers in this section are no longer current: the engine is
> covered by **435** tests, not 363; the largest file is **8 TiB − 1**, not 4 GiB − 1;
> and the rejection vocabulary is **17** variants, not 19. Read the next section for
> why each changed.

This section was written by the agent that implemented the focused group. It
records what exists, what was verified, and — importantly — what was **not**
verified, so the next session does not mistake one for the other.

### What was built

`:core-transfer` went from a module with a build script and no sources to a
complete pure-Kotlin transfer engine:

- validated identity and metadata types (`ProtocolVersion`, `SessionId`, `BatchId`,
  `TransferId`, `FileId`, `RecipientId`, `SequenceNumber`, `ConfirmedOffset`,
  `ChunkSize`, `RelativeTransferPath`, `TransferFileDescriptor`)
- reuse of the existing twelve `TransferState` values, with a stricter transition
  table and a machine-computed list of where it diverges from core-model's
  UI-facing table
- typed commands, events, effects and rejections
- a deterministic reducer — no clock, no socket, no file, no random ID, no thread,
  no `Thread.sleep`, no Room, no global mutable state
- versioned big-endian binary framing: fifteen frame types, magic bytes, two CRC-32
  checksums (one over the header, one over the payload), and every untrusted length
  bounded before it is used to allocate
- per-chunk CRC-32 and incremental streaming SHA-256 that never buffers a whole
  file
- pure resume negotiation (`ResumeAt` / `RestartAtZero` / `AlreadyVerified` /
  `Reject`)
- a deterministic scheduler with no clock, bounded concurrency, and per-recipient
  isolation
- versioned, Room-independent persistence snapshot contracts
- `doc/transfer-protocol.md`, the twenty-eight invariants, and 363 JVM tests

### Verification status

**CI-verified.** The work is on the remote at `685e0f3` and the final run is
green: <https://github.com/ahmadyb/Morsec/actions/runs/36970179041>

| Check | Baseline (last green on `arena/01a0f8ba-morsec`, run 36911164456) | This work (run 36970179041) |
| --- | --- | --- |
| Unit tests | 800 tests, 0 failed, 0 skipped, 56 reports | **1163 tests, 0 failed, 0 skipped, 70 reports** |
| Android lint | 0 errors, 45 warnings | **0 errors, 45 warnings** |
| Debug APK | 18.48 MiB | 18.64 MiB |
| `checkMilestoneHygiene` | clean | clean |

The test delta is **+363**, which is exactly the count the standalone Kotlin
harness reported locally, so the tests that were written locally are the tests
that ran in CI. Lint's 45 warnings are unchanged, so no new warning was
introduced and no baseline was touched.

Two honest caveats:

1. **The Actions log blob cannot be downloaded from this sandbox** (`gh run view
   --log` fails with EOF, as it has in previous sessions). The numbers above come
   from the check-run annotations the workflow republishes, which is exactly why
   that republishing exists. If you want the raw `20-unit-tests.log`, download it
   from the Actions UI.
2. **The 363 tests were first run in a standalone Kotlin harness**, not in Gradle,
   because the sandbox has no JDK, Gradle or Android SDK that can resolve this
   project's dependencies. That harness was an additional check while GitHub
   authentication was down; it is now superseded by the CI run above. Do not
   mistake it for the real verification — but do note that it is what caught all
   six defects listed below, before CI ever ran.

### A Git-metadata incident worth recording

Part-way through this session the sandbox was **re-cloned**: `.git` was reset to
the base commit and every local commit object disappeared, along with
`/home/user/tools` and `/tmp`. The working tree survived intact, and the one
commit that had already been pushed (`c279b90`) survived on the remote. The four
remaining commits were re-created from the working tree on top of `c279b90` and
pushed, so the history is linear and nothing was force-pushed.

This is precisely the failure the "never leave the whole feature only in local
commits" rule exists for. It happened, and it cost nothing but re-committing,
because the last pushed commit was never more than one commit behind the work.

### Protected scope is unchanged

`FeatureReadiness.TRANSFER_ENGINE` remains `deliveredInMilestone = 5` and
`CURRENT_MILESTONE` remains 2. Nothing in this milestone turns the transfer engine
on for users. The status line for this work is:

> Milestone 3 in progress: SAF media integration complete / Pure transfer core
> implemented and verified / Production transfer engine integration still gated.

Not started, and not to be started without approval: sockets, LAN/UDP discovery,
Nearby, Wi-Fi Direct, foreground services, notifications, wake locks, Android stream
adapters, the Room implementation, Compose repositories, UI transfer integration,
Media3, and the WebShare server and client.

### Defects found and fixed while writing the tests

Worth knowing, because they are the reason the tests are worth running:

1. A `ChunkSent` could start past the write frontier, so `optimisticBytes` could
   describe bytes that were never written. `checkChunkRange` now takes the frontier
   it must not pass.
2. An acknowledgement could confirm more bytes than this side ever sent. That is now
   a typed rejection, `AcknowledgementBeyondSent`.
3. Rejections carried no classification, so a caller had to invent its own mapping
   from code to category before it could log or persist one. `Rejection` now carries
   a `TransferError`, re-redacted on the way through.
4. The resume version guard was unreachable: `ProtocolVersion` refuses to be
   constructed outside the supported range, so the check is now a documented
   precondition of the type instead of dead code in a consumer.
5. A `DATA_CHUNK` payload *is* the raw bytes, but the parser reported trailing bytes
   instead of consuming them.
6. `ResumeResponse` carried an offset alongside a decision that already carried one,
   so the two could disagree on the wire.

---

## Session state — 2026-09-29: CI validation of Milestone 1 is COMPLETE

Written by the agent that executed the CI-validation task. Every number here comes
from GitHub Actions output, not from local inspection: the development sandbox has
no JDK, Gradle or Android SDK and cannot download them, so **CI is the only place
this project is actually compiled**. Do not trust a local `./gradlew` attempt there.

### 1. Current branch

`arena/01a0e98a-morsec`, branched from `main` at `4e8cef8`. All work is pushed to
this branch only. History has never been rewritten, rebased over remote work, or
force-pushed.

### 2. Latest pushed commit SHA

`d9cd14965255f4f096195d09a253da3a4f78b1e4` — *"Clear 70 of the 111 lint warnings
and write the docs the build references"* — the newest commit that changes code.
The docs-only commit adding this section follows it.

Confirm before doing anything:

```bash
git ls-remote origin arena/01a0e98a-morsec
gh run list --branch arena/01a0e98a-morsec --limit 3
```

### 3. Latest green CI run

| Run | Commit | Result |
| --- | --- | --- |
| [#18](https://github.com/ahmadyb/Morsec/actions/runs/36577006338) | `d9cd149` | **success**, all 18 steps — newest code run |
| [#17](https://github.com/ahmadyb/Morsec/actions/runs/36574837295) | `18347b9` | **success** — the commit that first added this handoff file |
| [#16](https://github.com/ahmadyb/Morsec/actions/runs/36568731048) | `d29cd4c` | **success** — the first green run |

Workflow: `.github/workflows/android-ci.yml` — `ubuntu-latest`, Temurin JDK 17,
the preinstalled Android SDK plus `platform-tools`, `platforms;android-36` and
`build-tools;36.0.0`, Gradle 8.13 through `gradle/actions/setup-gradle@v4`.
Triggers: push to this branch, pull requests, `workflow_dispatch`.

Exact Gradle invocations, in order (all `--no-daemon --stacktrace`):

1. `checkMilestoneHygiene`
2. `test --continue`
3. `:app:lintDebug :core-design:lintDebug :core-data:lintDebug :core-storage:lintDebug :transport-lan:lintDebug :transport-nearby:lintDebug :media:lintDebug --continue`
4. `:app:assembleDebug`
5. `:app:assembleDebugAndroidTest`

### 4. Completed milestone

**Milestone 1 of 12 — implemented, compiled, tested and CI-verified.**
`core-model/FeatureReadiness.kt` reads `CURRENT_MILESTONE = 1`, `FINAL_MILESTONE = 12`;
it is bumped only when a milestone is genuinely delivered, and
`tools/verify/token-parity.mjs` fails if the final milestone is reached while
anything is still gated.

Milestone 1 acceptance is still **yours to give**: per the completion gate below,
Milestone 2 does not start until you approve explicitly.

### 5. Exact implemented features

**`:app`** (30 Kotlin files) — `MorseApplication` (`@HiltAndroidApp`), single
`MainActivity` (Compose, no fragments), navigation graph over
`Routes`: onboarding, connect, files, history, settings + nested logs, crashes,
doctor, help; bottom navigation exactly as the mockup (CONNECT · FILES · HISTORY ·
SETTINGS); 9 `@HiltViewModel`s; `FeatureGate` routing every not-yet-delivered
action to an honest explanation sheet.

- **Onboarding** — 4 cards, and the only first-run permission prompt: a real
  contextual storage request issued by card 2's primary button.
- **Connect** — transport copy is exactly "LAN or Nearby" (`transport_label`),
  recent devices come from Room, and every control that needs a later milestone is
  routed through `FeatureGate`: send/receive → `TRANSFER_ENGINE` (M5), broadcast →
  `SESSIONS_AND_BROADCAST` (M9), the WebShare card (which shows the real configured
  port and running state) → `WEBSHARE_SERVER` (M11).
- **Files** — five category tabs (Photos, Videos, Music, Apps, Files) via
  `CategoryTabRow`, three-column grid at the reference width
  (`metrics.gridColumnsAtReferenceWidth`), list layout for music/apps/files,
  multi-select with a selection bar (share outside the app, gated send, clear),
  sort sheet, SAF folder grants (persisted, revocation detected), real thumbnails
  (`ThumbnailLoader`), thin straight scrollbar overlays.
- **History** — Received/Sent direction tabs, real search backed by a Room query,
  date grouping (Today/Yesterday), per-entry overflow: open, share, send again
  (gated), remove from history, delete the file through `ContentResolver`.
- **Settings** — device name (rename dialog), accent-family swatches, theme mode,
  granted folders (SAF add/remove), a battery-optimisation shortcut, entries to
  Logs / Crashes / Doctor / Help, and replay-onboarding. The WebShare port is *not*
  a Settings control yet: it surfaces on the Connect card and in the Doctor's port
  check, and `DiagnosticAction.CHANGE_WEBSHARE_PORT` is gated — the settings row for
  it (within `NetworkPorts.USER_PORT_RANGE`) is Milestone 2–4 work.
- **Connection Doctor** — 10 checks that read the real device state: Wi-Fi
  transport, multicast permission, location-for-scan, runtime permissions, storage
  access, revoked grants (only shown when there are any), battery optimisation,
  notifications, Play services presence, and port conflicts. Each failing check
  offers a corrective action that launches a real system screen; the API 26
  notification-settings intent is guarded by `Build.VERSION_CODES.O` and falls back
  to the app details screen on API 23–25. The Nearby-specific checks
  (`DOCTOR_NEARBY`) land with milestone 7.
- **Logs / Crashes** — filter by level, export to a real file through
  `FileProvider`, share, clear; crash reports are viewable, exportable and
  clearable, and are never uploaded.

**`:core-model`** (14 files, pure JVM) — `TransferState` state machine with an
explicit `allowedTransitions` map and `requireTransition()`; `ItemActions`, the
per-file action matrix (Active: pause+cancel, Paused: resume+cancel, Queued:
cancel, Failed: retry only, Completed: none); `Session`/`Peer`; `NetworkPorts`
(WebShare 33455, peer control 33456, discovery beacon 33457, 1.2 s beacon
interval, 4× staleness window, user port range); `WebShareSession` contracts
(`BrowserSession`, `BrowserSessionState`, `WebTransferRecord`); `MediaItem`,
`History`, `Diagnostics`, `Settings` (clamping `require` blocks); `MorseFormatters`
with localised units injected from `:app`; `FeatureReadiness`; DI qualifiers.

**`:core-design`** (11 files + 61 generated vector drawables) — dark and light
palettes, accent families (sunflower, leaf, ember, violet, sky), status colours,
`MockupTokens` (values lifted verbatim from the mockup CSS), `MorseMetrics` (all
dimensions: radii 8/12/16/24/28/999, 180 ms `bezier(0.2,0,0,1)`, pressed scale
0.97), `MorseType` (all type sizes), `MorseTheme`, components (`Atoms`,
`Surfaces` incl. scrollbar overlays for `LazyListState` and `LazyGridState`,
`Navigation`, `Overlays`, `TouchTarget`), `MorseIcons`.

**`:core-data`** (19 files) — Room `MorseDatabase`: **9 entities / 9 DAOs**
(`transfer_sessions`, `transfer_items`, `history`, `recent_devices`,
`log_entries`, `crash_reports`, `browser_sessions`, `saf_grants`,
`web_transfers`), mappers so entities never cross a module boundary, repositories
(Transfer, History, Device, Diagnostics, WebShare, Settings), one Preferences
DataStore file with corrupt-store fallback and clamped writes, `MorseLogger` +
`RoomMorseLogger`, `LogRedactor` (authorization headers, bearer tokens, long
opaque blobs including SHA-256 digest bytes, app-private paths, `/storage/emulated/…`,
`/sdcard/…` → `[redacted]`), `CrashRecorder`, Hilt modules.

**`:core-storage`** (7 files) — `MediaStoreReader`, `SafTreeReader` (tree grants,
persisted-permission release), `InstalledAppsReader` (uses the manifest's
`<queries>` LAUNCHER intent, never `QUERY_ALL_PACKAGES`), `DefaultMediaRepository`,
`PermissionMatrix` (per-API-level permission sets: media read split at 33, write
only ≤ 28, fine location ≤ 32, Bluetooth scan/advertise/connect ≥ 31,
POST_NOTIFICATIONS ≥ 33).

**Manifest** — contextual permission set with the tightest `maxSdkVersion` each
one allows; `FileProvider` over cache `exports/` and files `shares/`;
include-only backup and data-extraction rules (only `files/datastore/` is ever
backed up — the database, `incoming/`, `exports/` and the cache stay on device);
adaptive launcher icon with background, foreground and monochrome layers.

**Modules with a build script and no sources yet** (correct for Milestone 1; CI
reports `NO-SOURCE` for them): `:core-transfer` (M5), `:transport-lan` (M6),
`:transport-nearby` (M7), `:media` (M10), `:webshare-server` (M11). The npm
project `webshare-ui` (M12) does not exist yet.

**Tooling and docs** — `tools/gen/icons.mjs` (+ `icons.json`) generates and checks
the 61 drawables; `tools/verify/token-parity.mjs` (191 assertions, mockup ↔
Compose tokens); `tools/verify/refs.mjs` (31 check groups: catalog consistency,
Compose API shapes, visibility leaks, imports, named-argument ↔ parameter
agreement across 202 callables, Hilt graph satisfiability, resource-XML
well-formedness, milestone hygiene). Docs: `README.md`, `doc/architecture.md`,
`doc/decisions/ADR-0001-toolchain.md`, `doc/decisions/ADR-0002-webshare-server.md`,
`doc/qa/lint-and-warnings.md`, `doc/fidelity-notes.md`, `doc/release.md`.

### 6. Tests and build artifacts

- **JVM unit tests: 69 distinct `@Test` methods in 6 suites, 0 failures, 0
  skipped.** CI reports **98 test executions across 9 report files**, because the
  three Android library modules run their unit tests for both the debug and the
  release variant: `ModelContractTest` 14, `TransferStateTest` 14,
  `MorseFormattersTest` 12 (all `:core-model`, single variant), `ColorTokensTest`
  12 (`:core-design`, ×2), `LogRedactorTest` 10 and `MappersTest` 7 (`:core-data`, ×2).
- **`checkMilestoneHygiene`: passes** — no `TODO(`/`FIXME`/stub markers in the 10
  source roots.
- **Android lint: 0 errors, 41 warnings** across 7 module reports. Every warning
  has a written verdict in `doc/qa/lint-and-warnings.md`.
- **Artifacts uploaded by every run:** `morsecode-debug-apk`
  (`app-debug.apk` **17.90 MiB**, `app-debug-androidTest.apk` **1.10 MiB**),
  `test-and-lint-reports` (JUnit XML, lint XML/HTML/text, the captured Gradle
  logs), `gradle-wrapper` (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`,
  `gradle-wrapper.properties`).
- **Gradle wrapper: committed** (`b2e0664`, created by CI because this sandbox
  cannot download Actions artifacts). Runs #16, #17 and #18 all logged *"the
  committed wrapper was used as-is … nothing was regenerated"*, so CI is verified
  against the committed wrapper.
- **Instrumentation tests are assembled, not executed** — the workflow has no
  emulator. `:app:assembleDebugAndroidTest` proves `src/androidTest` (including
  `MorseTestRunner`) compiles; running it needs `connectedDebugAndroidTest` on a
  device or an emulator job.

### 7. Known issues

1. **Delivered since this list was written:** horizontal swipe between the five
   Files categories (`MorseCategoryPager` — one piece of state drives a tap and a
   swipe), the internal folder browser (`Routes.FOLDER` registered, `FolderScreen`,
   the breadcrumb as the way up and no upward-arrow control), and the image viewer
   (`Routes.VIEWER` registered, `ViewerScreen` — a deck that wraps both ways, no
   overflow button, nothing clickable over the photograph). `doc/fidelity-notes.md`
   §7 records the decisions behind each. `Routes.WEBSHARE` is still unregistered,
   correctly, until milestones 11–12. The milestone 2 handoff itself is written at
   the end of milestone 2, per the plan; this item is corrected here only so that
   nothing in this document claims delivered work is missing.
2. **41 lint warnings**, all with a verdict in `doc/qa/lint-and-warnings.md`:
   27 `UnusedResources` (mockup tokens reserved for later milestones — do not
   delete them, `token-parity` asserts them), 11 `PluralsCandidate` (single-locale
   English copy from the mockup), 2 `SelectedPhotoAccess` (Android 14 partial
   photo access, deferred to the media-depth work), 1 `OldTargetApi` (targetSdk 36
   is deliberate, ADR-0001).
3. **28 Kotlin compiler warnings**: `androidx.hilt.navigation.compose.hiltViewModel`
   is deprecated (its replacement package ships in the `androidx.hilt` line that
   requires compileSdk 37, and this project pins 1.3.0), plus Kotlin 2.2
   annotation-use-site-target notices on resource-id annotations. Neither is
   suppressed in code.
4. **No emulator in CI**, so nothing verifies runtime behaviour — only
   compilation, unit tests, lint and packaging.
5. **Five modules are empty** until milestones 5–12 (see §5).
6. **Sandbox limits:** no local Android build; Actions **logs and artifacts cannot
   be downloaded** (blob hosts unreachable); `gh workflow run` returns 403 with the
   bot token, so runs are triggered by pushing. That is why the workflow
   republishes failure text, lint findings and the headline test/lint/APK numbers
   as check-run annotations — keep that mechanism when editing the workflow.
   Recipe:
   ```bash
   JOB=$(gh run view <run-id> --json jobs --jq '.jobs[0].databaseId')
   gh api repos/ahmadyb/Morsec/check-runs/$JOB/annotations --paginate      --jq '.[] | "[\(.annotation_level)] \(.title) :: \(.message)"'
   ```
7. **The sandbox's local `.git` rolled back five times** (HEAD reverted to
   `4e8cef8`, worktree intact, committed work appearing untracked). Recovery, in
   one shell so it cannot be interrupted: set the fetch refspec, `git fetch origin
   +refs/heads/arena/01a0e98a-morsec:refs/remotes/origin/arena/01a0e98a-morsec`,
   `git reset --mixed origin/arena/01a0e98a-morsec`, `git branch --set-upstream-to=…`,
   then `git status` before committing. **Never `reset --hard`** (it deletes
   uncommitted work) and never force-push. The GitHub token also expires
   mid-session: re-authenticate in Arena, never paste a token into chat.
8. **`README.md`'s build snippet is one line stale** — it still says to run
   `gradle wrapper --gradle-version 8.13`; the wrapper is committed, so `./gradlew`
   works directly.
9. **Release builds are unsigned** unless `keystore.properties` exists
   (`doc/release.md`); CI builds debug only.

### 8. Next unstarted milestone

**Milestone 2** — the first of the README's 2–4 band: *"Depth inside the
milestone-1 areas: full Files browsing/selection and the per-file action matrix,
History search and filters, Settings completeness, Logs/Crashes export."* No new
`FeatureArea` unlocks before milestone 5 (`TRANSFER_ENGINE`), so milestone 2 must
not introduce transfer, transport, service, playback or WebShare behaviour — it
deepens browsing, selection and the screens that already exist, to full mockup
fidelity. Milestones 5–12 stay planned, in order.

### 9. The exact first task for the next AI

**Step 0 — verify, do not assume (no code yet):**

```bash
git fetch origin '+refs/heads/*:refs/remotes/origin/*'
git checkout arena/01a0e98a-morsec && git status
git log --oneline --decorate -10
gh run list --branch arena/01a0e98a-morsec --limit 3
node tools/verify/refs.mjs && node tools/verify/token-parity.mjs && node tools/gen/icons.mjs --check
```

Expect: tip at or after `d9cd149`, a clean tree, the latest run green, and
31/31 · 191/191 · 61 from the harnesses. Then read, in this order:
`doc/AI_HANDOFF.md`, `doc/architecture.md`, `doc/qa/lint-and-warnings.md`,
`README.md`, `doc/fidelity-notes.md`, `doc/decisions/ADR-0001-toolchain.md`, and
the Files/History/Settings sections of `doc/morsecode_master_build_prompt.md`
against the same screens in `doc/morsecode_material3_mockup.html`.

**Then ask for explicit approval to begin Milestone 2 and stop.** Writing
Milestone 2 code before that approval violates the completion gate below.

**Step 1 — the first code task, once approved** (both parts are verified gaps,
listed as known issue 1):

1. **Swipe between Files categories.** Add `rememberPagerState` + `HorizontalPager`
   to `FilesScreen` with exactly five pages, bound to the *same* state the tab strip
   already drives (`FilesViewModel` tab selection), so a swipe and a tab tap are one
   behaviour with one source of truth. `CategoryTabRow` stays pinned above the pager
   and must never scroll away, must not gain a native horizontal scrollbar, and the
   selected tab keeps bold accent text, the subtle selected tint and the straight
   underline. Keep the three-column grid, the thin straight scrollbar overlays and
   the existing per-tab empty/permission states; `LaunchedEffect` must survive a
   page change without re-showing a consumed one-shot message.
2. **The internal folder browser for `Routes.FOLDER`.** Register
   `composable(Routes.FOLDER)` with the `treeUri` argument, add a screen that lists
   children through `MediaRepository`/`SafTreeReader.children(treeUri)`, keeps the
   breadcrumb visible below the sticky header, has **no upward-arrow control**, and
   reuses the Files selection model (same per-item action rules, same selection bar).

**Step 2 — the gate after every change, unchanged from milestone 1:** run the three
Node harnesses, push, watch CI to green (`gh run watch`), read failures through the
annotations recipe above, fix the cause (never baseline, disable an error-severity
check, delete a test or add `continue-on-error`), compare the result against the
mockup, and report what was implemented and what remains. Then, and only then,
continue with the rest of milestone 2.

### 10. Product decisions that must not be reversed

Everything under **Approved UI decisions** below still stands, unchanged. These
were added during CI validation and carry the same weight:

- **The toolchain pins move together or not at all** (ADR-0001): AGP 8.13.2,
  Gradle 8.13, Kotlin 2.2.20, KSP `2.2.20-2.0.4`, compile/target SDK 36, minSdk 23,
  JDK 17, Compose BOM 2026.04.01, Hilt **2.58**, `androidx.hilt` **1.3.0**. Hilt
  2.59+ needs AGP 9; `androidx.hilt` 1.4.0 and Compose 1.12 need compileSdk 37.
  Bumping one row without the rest breaks `checkAarMetadata`.
- **minSdk 23 is real, not nominal.** `NewApi` lint stays enabled in every module;
  API-level differences are handled with `Build.VERSION` branches (as the Doctor's
  notification action now does), never with a suppression that hides a dead button.
- **Backup policy is include-only:** just `files/datastore/`. The Room database,
  `incoming/`, `exports/` and the cache never leave the device. Do not add
  `<exclude>` entries for paths outside an include — that is the
  `FullBackupContent` error this project already paid for.
- **UI strings are resolved in composition** (`stringResource` / hoisted vals),
  never through `LocalContext.current` inside a lambda or an effect.
- **Lint keeps `abortOnError = true` with no baseline.** The only disabled checks
  are four warning-severity ones (`GradleDependency`, `NewerVersionAvailable`,
  `AndroidGradlePluginVersion`, `MissingTranslation`), each justified in
  `doc/qa/lint-and-warnings.md`.
- **Nothing is faked ahead of its milestone.** Gated actions go through
  `FeatureGate` and say which milestone makes them real; `CURRENT_MILESTONE` is
  bumped only on delivery. No simulated progress, no placeholder control that
  appears to succeed, no sample data.
- **The Compose BOM must stay on `implementation`, `testImplementation` *and*
  `androidTestImplementation`** in any module using a BOM-managed artifact — a
  BOM-managed dependency on a configuration without the platform resolves to an
  *empty* version and breaks resolution plus lint's model task.
- **The committed Gradle wrapper is authoritative.** CI verifies it instead of
  regenerating it, and only commits a new one if the tracked copy is missing.
- **Design tokens have exactly one home each:** dimensions in `MorseMetrics`, type
  sizes in `MorseType`, mockup-derived values in `MockupTokens`, colours in
  `MorseColorTokens`. `token-parity.mjs` (191 assertions) enforces agreement with
  the mockup and must stay green.
- **Logs are redacted before they are written**, including private paths and
  SHA-256 digest bytes; auditability comes from typed status, not secret-bearing data.
- **CI must keep republishing its own diagnostics as annotations** (failures, lint
  findings, test/lint/APK totals): in some environments that is the only readable
  output of a run.

---

## Authoritative product files

Read these completely before proceeding:

1. `morsecode_master_build_prompt.md`
2. `morsecode_material3_mockup.html`
3. `README.md`
4. `doc/fidelity-notes.md`
5. `doc/release.md`
6. Existing Gradle configuration
7. Existing source and test modules
8. Any `AI_HANDOFF.md`, milestone report, or CI workflow in the repository

The master prompt defines the application requirements.

The HTML file is the authoritative visual and behavioral reference.
Do not replace its approved UI with a generic Material template.

## Product summary

Morsecode is a local file-sharing Android application supporting:

- Android 6/API 23 through the latest Android version
- LAN or Nearby device discovery
- Android-to-Android transfers
- Bidirectional sessions
- Phone-to-multiple-phone broadcasts
- Background transfers
- Photo, video, music, apps, and file browsing
- Image viewer
- Music player
- Video player
- Embedded WebShare server and browser client
- Logs, crash reports, diagnostics, and history
- No cloud, accounts, advertisements, analytics, or automatic crash uploads

## Approved UI decisions

Do not reverse these decisions:

- Use native Kotlin and Jetpack Compose.
- Minimum Android SDK is API 23.
- The transport label is `LAN or Nearby`.
- There is no Manual IP control.
- There is no QR pairing control.
- Mobile Files categories are:
  - Photos
  - Videos
  - Music
  - Apps
  - Files
- All five category tabs fit the phone width.
- Category tabs remain sticky while scrolling.
- Category tabs have no native horizontal scrollbar.
- The selected tab has bold accent text, a subtle selected tint, and a
  straight underline.
- Horizontal swipe changes mobile file categories.
- Photo and video grids use three columns at the reference phone width.
- Mobile scroll indicators are thin straight lines.
- The redundant address bar above mobile Files/Categories was removed.
- The internal folder browser does not have an upward-arrow control.
- Its breadcrumb stays visible below the sticky header.
- The separate transfer Queue screen was removed.
- Queue controls are integrated into transfer screens.
- Transfer screens are:
  - Sending + receiving
  - Receiving + sending back
- Per-file actions are:
  - Active: Pause and Cancel
  - Paused: Resume and Cancel
  - Queued: Cancel
  - Failed: Retry only
  - Completed: no transfer action icons
- Retry is per file, not global.
- There is only one global Pause all / Resume all action.
- The heading action clears completed items for that section.
- Bottom transfer actions are:
  - Add files
  - Pause all / Resume all
  - Background
  - End
- Image and video viewer overflow buttons were removed.
- WebShare has no `All videos` pseudo-folder.
- WebShare has no duplicate video table below the video grid.
- WebShare Quick Access and folder panels remain pinned while content
  scrolls.
- WebShare breadcrumbs remain visible while file rows scroll.
- Crash reports are viewable, exportable, and clearable.
- Crash reports are never uploaded automatically.

## Milestone state

Milestone 1 was implemented and pushed.

Reported implementation includes:

- 10 Android modules
- Kotlin/Compose application shell
- Hilt
- Room
- DataStore
- Storage adapters
- Navigation foundation
- Design tokens and reusable components
- Onboarding
- Connect
- Files
- History
- Settings
- Logs
- Crash reports
- Connection Doctor
- Help
- Node-based token/reference verification
- 69 JVM unit tests

**Update 2026-09-29:** the environment limitation below was removed by moving the
build into GitHub Actions. Those 69 tests have now really been executed (98 test
executions across 9 reports, 0 failures), the project really compiles, Hilt and
Room really process, lint really runs (0 errors) and both APKs really assemble.
See "Session state" for the runs.

Milestone 1 is therefore **implemented and CI-verified**.

**Update 2026-10-01:** Milestone 2 (the `2–4` UI-depth row) is also delivered and
CI-verified — see "Session state — 2026-10-01" above for the green run, the
totals and the parity summary. Milestone 3 has not started and must not start
without explicit approval.

The previous environment could not compile Android code because it had
no JDK, Gradle, or Android SDK and could not download them. That is still true of
the sandbox — CI is the only build path.

Node verification scripts are useful, but they are not substitutes for:

- Kotlin compilation
- Compose type checking
- Hilt graph processing
- KSP/Room generation
- Android resource processing
- Manifest merging
- Android lint
- JVM tests
- APK assembly

## Exact starting point — COMPLETED 2026-09-29

The first task was CI validation of Milestone 1. It is **done**: the workflow
exists, it is green, the wrapper is committed, and CI has been re-verified against
that committed wrapper. The evidence and the next task are in "Session state"
above (§3, §6, §9).

Do not begin Milestone 2 without explicit approval.

Create or inspect:

`.github/workflows/android-ci.yml`

Use GitHub Actions to provide:

- Ubuntu runner
- JDK 17
- Android SDK Platform 36
- Gradle 8.13
- Generated Gradle wrapper
- Dependency resolution
- Kotlin/Android compilation
- Unit tests
- Android lint
- Debug APK assembly

The CI workflow must run:

- `checkMilestoneHygiene`
- `test`
- `lintDebug`
- `:app:assembleDebug`

It must upload:

- Debug APK
- Test reports
- Lint reports
- Generated Gradle wrapper files, if the repository still lacks them

After pushing the workflow:

1. Trigger it using GitHub CLI.
2. Monitor it.
3. Read complete failure logs.
4. Fix every build, test, lint, resource, dependency, Hilt, KSP, Room,
   manifest, and Kotlin error.
5. Push fixes.
6. Rerun CI.
7. Repeat until green.
8. Download and commit the verified Gradle wrapper if it is absent.
9. Run CI again using the committed wrapper.

All nine steps were carried out. Step 8 needed one adaptation: the sandbox cannot
download Actions artifacts, so the workflow commits the generated wrapper back to
the branch itself (with `GITHUB_TOKEN`, which cannot retrigger a run) — commit
`b2e0664`. Step 9 then happened three times: runs #16, #17 and #18 all built with
the committed wrapper.

Nothing was hidden or bypassed to get there: no lint baseline, no
`abortOnError = false`, no deleted or skipped test, no `continue-on-error` on a
build step. The only checks switched off are four *warning*-severity ones, each
with a written justification in `doc/qa/lint-and-warnings.md`. Every failure was
repaired at its cause across runs #1–#15 (toolchain pins, Gradle DSL, Hilt/KSP
wiring, 13 app-module Kotlin errors, the Compose BOM on test configurations,
backup rules, and six composition-scope resource reads).

Do not hide or bypass compiler/lint/test failures merely to make CI green.

## Completion gate for the current task

Stop before Milestone 2.

The current task is complete only when you provide:

1. A green GitHub Actions run URL.
2. The final pushed commit SHA.
3. Exact Gradle tasks executed.
4. Unit-test count and results.
5. Android lint result.
6. A downloadable debug APK artifact.
7. Confirmation that the Gradle wrapper is committed.
8. A list of warnings or remaining limitations.

**Status: all eight were provided** — §3 (run URLs), §2 (SHA), §3 (Gradle tasks),
§6 (tests), §6 (lint), §6 (APK artifacts), §6 (wrapper committed), §7 (warnings
and limitations).

Only after I explicitly approve Milestone 1 may you begin Milestone 2.

## Anti-drift rules

- Do not create a new project.
- Do not switch to Flutter, React Native, or XML layouts.
- Do not redesign the approved interface.
- Do not remove existing modules because they look complex.
- Do not replace real requirements with simulated behavior.
- Do not skip API 23 compatibility.
- Do not advance to another milestone without permission.
- Do not claim success without a real build and test run.
- Do not reset, force-push, or rewrite working Git history.
- Do not modify the master prompt or mockup unless I explicitly request it.
- Do not discard existing work because it has compile errors; repair it.
- Keep commits focused and push them to the existing working branch.

Begin by verifying the repository and branch. Then report what you found
and execute only the CI-validation task described above.
