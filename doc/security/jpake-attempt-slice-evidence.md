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

The first three are the slice and compiler repairs. The last three exist because the suites had
never executed: this environment has no JDK, so the only compile and test signal is an exact-SHA CI
run. Their subject lines are the honest record of what execution found.

## Exact-SHA evidence

Run `39680845579` on `622ecaacc8fe81e243a0af2ed849bfc943ed7dd2` — **success**.

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

## What is still missing

- The coordinator still offers SAS (`SecureLanPairingCoordinator` around the suite offer), still
  calls `select` rather than `selectRequired`, and never reaches the authority.
- `SecurePairingRunResult.Authenticated` does not yet carry an authority.
- No end-to-end hostile test exists at the coordinator level or as API 23 instrumentation: replay
  across attempts, cross-session substitution, approval before round three, one peer rejecting,
  approval replay, concurrent approve/reject, cancellation in each round, exact-once cleanup,
  limiter recovery.
- Because none of that is wired, the SAS-removal claim is **not** yet true in production, and the
  static verifier still permits the SAS constants that the coordinator uses. The atomic rule means
  the offer must be removed in the same commit that makes the PAKE path mandatory.
