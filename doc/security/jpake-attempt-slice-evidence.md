# Fresh PAKE attempt identity, control records and the authority type — evidence

Status: **implemented, tested, and not wired into the live coordinator**. Experimental,
project-defined and not independently cryptographically reviewed. The owner accepted that risk on
2026-10-19 (`owner-risk-waiver-2026-10-19.md`). Nothing here establishes security, and no part of
this document should be read as an external review, an audit or a conformance claim.

## What this slice is

This is the first half of the atomic SAS replacement: the pure-domain foundations the coordinator
will need, committed on their own so the live pairing path keeps behaving exactly as before until
the second half lands. The rule the owner set for it was that it must not push a state where SAS is
gone and the PAKE path is not yet available, nor a state where the PAKE path is wired with an SAS
fallback. A foundation slice satisfies both halves by changing no coordinator behaviour at all.

Added:

- `PakeAttemptId`, `PakeAttempt`, `PakeAttemptCharge` and `PakeAttemptLedger` — a 16-byte
  identifier per full exchange, a three-exchange budget per generated code, and settlement paths
  that separate "the peer's input was wrong" from "we stopped locally".
- `PakeAttemptStart` / `PakeApproval` with the MPS4 and MPA1 codecs — the bounded attempt-start
  record that tells a responder which fresh attempt it is in, and the approve/reject record that
  names the attempt, ordinal, code instance, transcript digest, both session identifiers, suite,
  profile and the peer role.
- `PakeAttemptFailure` with the MPSF codec — the generic attempt-failure signal retry needs, so
  that one peer cannot wait for a round the other abandoned. It carries exactly one value,
  RETRY or ABORT, which are policy states rather than causes. Wrong code, invalid proof, bad
  round-three tag, context mismatch, replay and malformed input produce identical bytes; a test
  pins the shape (one constructor, one parameter type, one field) so a cause cannot be added
  without failing, and the decoder refuses any tag but the verdict.
- Three new MPS3 fields (attempt id, ordinal, code instance) and schema version 2, so every
  round-one, round-two and round-three frame names the attempt it belongs to.
- `AuthenticatedControlAuthority` and its nested `AuthenticatedControlChannel` — an opaque,
  single-use, control-only type issued only from concrete evidence objects.
- JVM tests for all three: 10 ledger tests, 13 record tests, 10 authority tests. The adapter suites
  grew to 27 JVM and 12 API 23 tests on the same framing.

Not added, deliberately: any coordinator call site, any use of the authority, any change to
`SecurePairingRunResult`, any change to the SAS selection path, any payload capability.

## Verdicts

| Question | Answer |
| --- | --- |
| Does the live coordinator behave differently? | No. No call site was added or changed. |
| Can SAS still be selected? | Yes — unchanged, and that is the remaining work. |
| Can payload move after an authenticated pairing? | No. `PayloadTransferGate` still refuses unconditionally. |
| Is `CURRENT_MILESTONE` still 2 and `TRANSFER_ENGINE` unavailable? | Yes. |
| Is this audited or externally reviewed? | No. |

## Commits

| Commit | Subject |
| --- | --- |
| `0070f17` | feat(lan): add fresh PAKE attempt identity, control records and an authority type |
| `286b0ac` | fix(lan): satisfy the compiler on the attempt-identity slice |
| `28b0dbf` | fix(lan): restore the isolated fixture helper the authority test calls |
| `7afb747` | test(wire): build MPS3 vectors from the attempt-aware schema |
| `6eb9e40` | fix(pairing): size control-record payloads without the length prefix |
| `622ecaa` | fix(pairing): keep the attempt identifier the generator just drew |
| `8d179af` | docs(security): name MODP as the implemented profile and mark EC historical |
| `8adbdf9` | feat(pairing): add an attempt-failure signal that cannot name a cause |
| `4fb62d9` | fix(pairing): refuse a failure frame that carries more than a verdict |
| `5a37339` | feat(pairing): add an inert entered-code exchange with its paired-stream tests |
| `cdff450` | test(pairing): cancel a parked exchange instead of racing a fast one |

The first three are the slice and compiler repairs. The last three exist because the suites had
never executed: this environment has no JDK, so the only compile and test signal is an exact-SHA CI
run. Their subject lines are the honest record of what execution found.

## Exact-SHA evidence

Run `39741492111` on `4fb62d9092afcbb69fee2ee0036c3dbb4fde8d9d` — **success**, and the current
head at the time of writing: 3,066 JVM tests (0 failed, 0 skipped), 57 API 23 instrumentation
tests, 0 lint errors, 48 warnings. It differs from the run below by the documentation correction,
the failure-signal record and its decoder fix, and five additional tests.

Run `39680845579` on `622ecaacc8fe81e243a0af2ed849bfc943ed7dd2` — **success**, the run that first
put the attempt-identity slice green.

- JVM: 3,061 tests, 0 failed, 0 skipped, 191 reports. `core-transfer` 577, `transport-lan` 292;
  the rest unchanged (`app` 588, `core-storage` 1,408, `core-design` 100, `core-data` 56,
  `core-model` 40).
- API 23 instrumentation: 57 tests, 0 failures, exit status 0, `cleanup_completed` reached.
- Lint: 0 errors, 48 warnings in 7 reports.
- Artifacts: `app-debug.apk` 32.29 MiB, `app-debug-androidTest.apk` 1.10 MiB.
- Verifiers, local: `jpake-production-boundary` 18/18, `secure-authority-surface` 211/211,
  `refs` 31/31, `room-schema` 11/11, `token-parity` 195/195, `transfer-limits` 21/21,
  `secure-dependency-governance` 13/13, `security-dependency-lock` 2/2.
  `secure-artifact-bytes` reports 6/7 locally because the Gradle module cache is absent in this
  environment; it is 7/7 in CI.

## Two defects execution found, and why they mattered

Both were in code that had only ever been read, never run. Both are recorded because the pattern
is general, not because the repair is interesting.

**1. MPS4 and MPA1 frames carried four bytes after their END tag.** `PakeRecordFormat.encode`
sized the payload from a constant that already included the 4-byte length prefix. The buffer was
therefore four bytes too large and `ByteBuffer` zero-filled the tail, so every record ended
`… 00 00 00 00 00 00 00 00`. A strict decoder stops at END and reports trailing bytes: every
record, including the honest one, was refused. A lenient decoder would have accepted a frame whose
declared length disagrees with its content. The fix sizes the payload from magic, version, fields
and END, and the prefix stays outside its own count.

**2. `PakeAttemptId` came back as sixteen zero bytes.** The class stored the caller's array rather
than a copy, and `generate` wipes that array in a `finally` block. Every attempt therefore carried
the same all-zero identifier: ids compared equal, the attempt binding was constant, and the fresh
identity that is supposed to make a replayed round-one frame name the wrong participant would have
been identical across attempts. The constructor now copies before the wipe can reach it, and the
ledger test asserts a generated identifier is neither all-zero nor equal to another attempt's.

Three test-side mistakes were also found and fixed: the hand-built hostile frames in
`PakeRecordsTest` repeated the same header arithmetic, its binding matrix compared the responder's
honest approval against an initiator role, and the ledger's stand-in entropy source returned the
same block on every draw — which made "distinct identifiers" compare a value with itself.

## Exact-SHA caveat on the documentation commit

Run `39738376206` for `8d179af4410b737ed008416a967828862fa83284` was **cancelled** by the
branch's concurrency group when the next commit was pushed, and GitHub refuses to re-run a
cancelled run. That commit therefore has no green run of its own. Its content is not unverified:
`8adbdf9` and `4fb62d9` are descendants, their green runs include it, and the compiler and tests
saw it there. The distinction is recorded rather than smoothed over, because "the tree was green
later" is a weaker statement than "this SHA was green" and this record should not conflate them.

## The inert exchange, and what running it found

The second increment builds the component the coordinator will eventually call, and still does not
call it. `PairingAttemptExchange` owns one connection from attempt-start to the intended-peer
decision — MPS4, three PAKE rounds, the retry handshake, MPA1 — and returns an internal,
non-constructible `PairingExchangeResult`. It is not referenced by `SecureLanPairingCoordinator`,
which is byte-for-byte unchanged, and a confirmed result is not `SecurePairingRunResult.Authenticated`
and carries no record-layer, payload or authority handle. A test asserts that by reflection so the
property cannot be lost silently.

Three things had to change outside the new package, and each is small on purpose:

- `PakeAttemptLedger` now keeps every identifier it has issued, and `adoptAttempt` lets the responder
  adopt the initiator's identifier under the same rules as `beginAttempt`: the ordinal must be the
  next one, the identifier must be unused, and only one attempt may be outstanding.
- `PakeAttemptFailure` gained the attempt identifier. Without it a verdict replayed from an earlier
  attempt was indistinguishable from an answer to the current one, so "wrong failure-record
  context" could not be enforced — only documented. The identifier is already public: it names the
  attempt in MPS4 and in every round frame. The record still cannot express a cause, and the shape
  test was rewritten to assert exactly that (two fields, identity and policy; no string, integer,
  byte array or throwable).
- `JpakeMessageCodec.hasMagic` exposes framing only, so the exchange can tell a round frame from a
  control record without restating the magic bytes in another module.

Two defects were found by writing the tests that drive both peers over real serialized frames, and
both were the kind that reading cannot catch:

**1. The three rounds were built as a list.** `listOf(exchangeStep(...), exchangeStep(...), ...)`
evaluates every argument before the first result is looked at, so all three rounds ran even after one
had failed. Written as three statements, each round starts only when the previous one was accepted.

**2. A legitimate retry always failed.** Each round is write-then-read on both sides, so a peer that
got past the failed step has exactly one frame in flight; the retry then read that residue as its own
first round. The exchange now skips at most one round frame, and only when it decodes and names the
attempt immediately before the current one. The allowance is one because one is the most that can
exist, and a frame naming any other attempt — or a control record — is still refused. A scripted peer
replays the abandoned attempt's round-one frame verbatim before the fresh one, and the retry confirms.

**3. A cancellation test measured the machine.** The first cancellation case cancelled from a timer
while an honest peer ran, and on a fast runner the exchange confirmed before the flag was set. The
peer now goes silent after round one, so the exchange is certainly parked in a read when the
cancellation lands. The assertion did not change; only the race did.

## Evidence for the exchange increment

Run `39811828683` on `5a3733950c36d1e9ad131defb1c4e33a082c1026` — **failure**, and the useful kind:
3,105 JVM tests in 193 reports, of which **2 failed**, both being the same cancellation test in its
debug and release executions. `core-transfer` 583, `transport-lan` 330 with 2 failed; the other
modules unchanged. API 23 instrumentation: 57 tests, 0 failures, exit status 0. Lint: 0 errors, 48
warnings. APKs: 32.31 MiB and 1.10 MiB. That run is the compile signal for code that had never been
compiled: everything else in this increment — the honest exchange, the wrong-code retry, three
exhausted attempts, a failure record where each round belongs, a duplicate verdict, retry-versus-abort
disagreement, the stale-frame discard, wrong ordinal, reused identifier, silence, connection loss,
both rejections, the approval echo, exact-once close and the no-authority property — passed first
time.

Run `39816150179` on `cdff4501ec6a4bfc7d4cf517889d6c7c82fcd090` — **failure, not a code failure**:
3,105 JVM tests, **0 failed**, 0 skipped; lint 0 errors, 48 warnings; the same 32.31 MiB and 1.10 MiB
artifacts. The build failed on `:transport-lan:connectedDebugAndroidTest` with one assertion:

```
ControlEstablishmentCapacityTest#establishmentCapacityShutdownAndObserverDefectsOnOneBoundLease
pairing capacity was not restored after the admitted pairings closed;
observed [null, SecurePairingResult.Failed(secure_session_limit_reached)]
```

That is a live-coordinator instrumentation test, and this increment does not touch the coordinator,
the attempt limiter or the establishment permits. The same test passed on `5a37339` — the run above,
with identical production Kotlin — which makes it a timing race in the test rather than a regression:
it closes three admitted pairings and then admits three more, and a refusal that arrives while the
closed sessions' cleanup is still in flight is terminal, so the `awaitTrue` around it can never
succeed. It is recorded here rather than smoothed over, and it is **not** accepted as evidence that
this increment is green.

Run `39819276806` on `0f66d49fb8a9af6c759bd3b7fa179a4ae2189b0b` — **success**, the green run for
this increment, and the tree that contains it: 3,105 JVM tests, **0 failed**, 0 skipped, 193
reports (`transport-lan` 330, `core-transfer` 583, `app` 588, `core-storage` 1,408, `core-design`
100, `core-data` 56, `core-model` 40). API 23 instrumentation: 57 tests, 0 failures. Lint: 0 errors,
48 warnings in 7 reports. APKs: 32.31 MiB and 1.10 MiB. `0f66d49` is the documentation commit that
first recorded the two runs above; the Kotlin it runs is the Kotlin of `cdff450`, and the
instrumentation suite that raced on `cdff450` passed here.

## What is still missing

- The coordinator still offers SAS (`SecureLanPairingCoordinator` around the suite offer), still
  calls `select` rather than `selectRequired`, and never reaches the authority.
- `SecurePairingRunResult.Authenticated` does not yet carry an authority.
- The exchange itself has no API 23 instrumentation: its 19 cases run on the JVM over in-memory
  channels, which is where a protocol can be exercised deterministically and where a socket would
  add nothing to the assertions. The socket adapter is exercised by its own bounds, not by a device.
- No end-to-end hostile test exists at the coordinator level or as API 23 instrumentation: replay
  across attempts, cross-session substitution, approval before round three, one peer rejecting,
  approval replay, concurrent approve/reject, cancellation in each round, exact-once cleanup,
  limiter recovery.
- Because none of that is wired, the SAS-removal claim is **not** yet true in production, and the
  static verifier still permits the SAS constants that the coordinator uses. The atomic rule means
  the offer must be removed in the same commit that makes the PAKE path mandatory.

## The composed path, still inert

This increment adds the whole authenticated-control chain beside the live one. `SecureLanPairingCoordinator.run()`
is untouched: it still offers SAS, still calls `select`, and still never reaches an authority. What
is new is everything the switch will need, and none of it is called by production.

**What was added.**

- `EnteredCodePairingBridge` and `EnteredCodePairingSlot` — the screen-facing half. One bridge per
  control session, sharing the session's existing pairing gate with the SAS operation, so a session
  cannot be in two pairings at once. Every prompt is a single-use handle bound to one session and one
  role; the slot's shutdown resolves a pending prompt with no answer rather than a default approval.
- `PairingChannel` / `SocketPairingRecordIo` / `TlsPairingChannel` — the two seams the composed path
  needs from one connection: framed control records, then AES-GCM records, over the same socket. The
  socket reader validates every header with `SecureRecordCodec.decodeHeader` before allocating a
  ciphertext buffer, and treats a budget that runs out as a timeout rather than arming a deadline
  that would abortively close the socket underneath the peer.
- `PakePairingProgression` — the seven ordered states beside the live `SecurePairingState` enum,
  with typed evidence objects and no `advance(boolean)`. Approval before the PAKE is refused, a
  remote approval cannot stand in for the local one, key confirmation cannot stand in for the PAKE,
  a duplicate confirmation fails closed, and AUTHENTICATED is reachable once.
- `PakeSessionCoordinator` — required-suite selection, the entered-code exchange, both approvals, the
  exporter-derived record layer, both key confirmations and one single-use control authority. It is
  constructed by nothing in production.

**Two changes to the inert exchange, both consequences of composing it.**

1. `PairingExchangeResult.Confirmed` now carries the local decision. The exchange has already asked
   the human; carrying the answer out is what lets the reducer record the same decision afterwards
   without a second prompt, which is what makes "approval before PAKE" unreachable rather than
   merely unusual.
2. A confirmed exchange no longer settles its attempt or invalidates its ledger. `issue` refuses an
   attempt that has already been settled, so the exchange cannot settle it and still hand the caller
   something an authority can be built from; the caller settles it after the authority exists. The
   attempt's abandonment in the exchange's own failure path is skipped only for the attempt it
   handed over, so a stop still leaves no outstanding attempt behind.

3. The composed path settles and releases whatever it still owns on every refusal, and hands both
   to the authority on success. A refusal abandons the handed-off attempt (so a spent budget is
   visible and the next try is not blocked), closes the record stream first (so a peer waiting on a
   confirmation sees a close instead of a deadline) and then the layer, which destroys the key
   material. On success those three are cleared, and the authority's single close performs the
   release exactly once: a test closes it twice and asserts one release, one revocation and no
   second claim.

4. Timing is part of the fixture, not an accident of the runner. Two cases asserted an expiry while
   the suite's clock was frozen at its start value, so the wait they were measuring could never
   elapse and each burned its whole `@Test` timeout on CI; the coordinator suite also used the
   production peer budgets, so a case where one side stops without telling the peer left the other
   blocking for a minute. The waits now run on a clock that advances with wall time, the JVM link
   uses five-second exchange budgets that still dwarf an in-memory exchange, and every worker thread
   is a daemon so a stuck case fails its own annotation instead of starving the run.

**Tests.** JVM: `PakeSessionCoordinatorTest` (17 cases — honest run, single-use authority, payload
still refused after control authentication, wrong code then retry, exhaustion, local and remote
rejection, unanswered approval, record-key mismatch, an AEAD-refused record, a replayed confirmation
frame refused by the record layer, a missing confirmation, cancellation, exactly-once release, two
concurrent sessions, a legacy suite and a non-1.3 channel refused before a frame is written),
`PakePairingProgressionTest` (15 cases over the ordered reducer: the honest sequence and a second
authority refused, approval before PAKE, the remote approval standing in for the local one, a
confirmation standing in for the PAKE, authentication before key confirmation, a replayed approval,
an approval for another transcript or the sender's own role, a second confirmation, a legacy suite, a
confirmation from the wrong role or transcript, a digest of the wrong width, an unconfirmed session
and a settled attempt. The handover type is `internal` and its only source is the reducer, so the
synthesis question is a visibility fact rather than something reflection can assert: `internal`
classes are public in bytecode, and a case that claimed otherwise failed for exactly that reason),
`EnteredCodePairingBridgeTest`
(10) and `EnteredCodePairingSlotTest` (5). API 23: `PakeSessionApi23Test` runs the full chain over a
real Conscrypt TLS 1.3 connection on loopback — the exporter material both sides derive is the TLS
one, and both key confirmations cross a real encrypted stream — then asserts single-use authority and
the payload refusal on the device.

**What these tests cannot show.** The JVM transport is in-memory and its exporter is deterministic,
so the JVM cases prove the composition is wired correctly and prove nothing about TLS. Nothing here
is a review of the construction, and none of it is authority for payload. See the owner-risk waiver
of 2026-10-19.

**Still missing, unchanged by this increment.** The coordinator still offers SAS, still calls
`select` rather than `selectRequired`, and still never reaches an authority. The SAS-removal claim is
not true in production. The switch — advertise only `JPAKE_MODP3072_ENTERED_CODE_V1`, call
`selectRequired`, invoke this path, delete the SAS generation and comparison, drop
`HumanVerificationCode` from live approval, add the SAS-reachability verifier and move the live
coordinator's API 23 matrix — remains a single later commit.
