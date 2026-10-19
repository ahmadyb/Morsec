# J-PAKE external review — errata and open review requirements

**Date:** 2026-10-19
**Repository commit this errata applies to:** `35cd6ccb00196f0174c1a2dac0bef0c77a463cd6`
**Status:** **documentation only. Not an external cryptographic review. Not an approval.**
Milestone 4 Part B remains externally blocked and no production implementation is authorized.

---

## 0. Provenance of this document, and what it is not

This document records technical observations produced by an **AI assistant** reading the repository
at the commit above. It exists because a non-authoritative analysis identified a documentation
contradiction and five open requirements that must be settled before a qualified reviewer can assess
the design.

It is **not**:

- an external cryptographic review;
- an independent assessment;
- an approval, conditional approval, or acceptance of any candidate construction;
- evidence that any security property holds;
- authorization to implement J-PAKE, MPS3, channel binding, key derivation, pairing UI, or file
  payload transfer.

The project's acceptance rule is that an AI-generated assessment does not satisfy the external-review
gate. That rule stands unchanged, and this document does not weaken it. The companion analysis
supplied alongside this commit (`Morsec-jpake-review-2026-10-19.md`, held outside the repository and
deliberately not committed) must be read under the same restriction.

**Current verdict, unchanged:**

```text
J-PAKE FEASIBILITY ESTABLISHED — EXTERNAL CRYPTOGRAPHIC REVIEW REQUIRED
PART B INCOMPLETE — EXTERNALLY BLOCKED
```

---

## 1. Correction: the production pairing format is MPS2/MST2

Three documents disagreed about which pairing format production actually speaks. The code settles it,
and the code is the source of truth.

### What production does

`transport-lan`'s `SecureLanPairingCoordinator` imports and uses
`SecurePairingHelloV2Codec`, `SecurePairingTranscriptV2` and `SecurePairingSelectionV2` from
`:core-transfer`, across encoding, decoding, selection and transcript construction. The V1
constants class `SecurePairingTranscript` is still imported by
`ConscryptSecureSessionEngine`, but only for its TLS protocol and cipher-suite name constants — not
for its transcript or hello format. No production path emits or hashes an `MPS1`/`MST1` frame.

An `MPS1` frame presented to the V2 decoder is rejected with the dedicated
`PairingWireV2Reject.LEGACY_MPS1_UNSUPPORTED` result. It is not downgraded, and it is not retried in
plaintext or at a lower format.

`tools/verify/secure-authority-surface.mjs` group 12 pins this and is mutation-tested: reverting the
hello encoder to MPS1 fails three checks, reverting the transcript to MST1 fails three, and
advertising a chunk size instead of explicit zero fails one.

### What was wrong, and where

| Document | Wrong statement | Disposition |
| --- | --- | --- |
| `doc/security/milestone-4-part-b.md` §"Pairing transcript" | "The bounded `MPS1` hello…" describing the V1 field set | Corrected in place |
| `doc/security/milestone-4-part-b.md` remediation table | "M3 — canonical transcript v2 · **Not implemented.**" | Corrected in place |
| `doc/AI_HANDOFF.md` "What was NOT verified" | "The pairing path still negotiates MPS1/MST1… MPS2/MST2 exist alongside them, not in place of them." | Marked superseded in place; original text retained as history |

The `AI_HANDOFF` passage was accurate when written and became stale when M3 landed. It is retained
rather than deleted so the record of what was true at that date survives; the supersession marker is
what prevents it from being read as current.

### Why this matters for the review

A reviewer cannot assess "the canonical MPS/MST transcript" without knowing which transcript is live.
The candidate channel-binding constructions in the dossier bind that transcript; a review conducted
against the wrong one would be worthless. This correction is a prerequisite for the review, not a
finding about cryptographic strength.

---

## 2. Open review requirements

Five requirements are recorded here as **unresolved**. Each states what was observed, why it matters,
and what a qualified reviewer must decide. None is resolved by this document, and none may be treated
as settled by a future implementation choosing an answer unilaterally.

### R-1 — Authentication-suite negotiation must be explicit and fail closed

**Observed.** `PairingAuthenticationCapability` (`SecurePairingWireV2.kt`) defines exactly two values,
`NONE(0)` and `SAS_25BIT_COMPARISON(1)`. `SecurePairingSelectionV2.select` resolves the suite with
`minOf(authA, authB)` and rejects only when the result is `NONE`. `SecureLanPairingCoordinator`
currently offers `SAS_25BIT_COMPARISON`.

**Why it matters.** `minOf` is an ordinal operation over wire ids. Adding a stronger suite as a larger
id means a peer that offers the weaker one wins the negotiation, so offering a stronger suite does not
strengthen the outcome. Under an active two-leg MITM both hellos are attacker-controlled, so the
attacker can force the weaker ceremony on both legs. A PAKE whose code never runs contributes nothing,
and the downgraded ceremony carries only its own entropy.

**Required decision.** The reviewer must specify one of: a mandatory authenticated suite with a
fail-closed refusal when either peer does not offer it; an explicit ranking function that cannot prefer
a weaker suite; or a different rule. Whatever is chosen must refuse, not degrade, and must be testable
as a refusal.

**Not to be resolved by:** adding a new enum value and leaving `minOf` in place.

### R-2 — Round-one replay protection must be specified concretely

**Observed.** In the pinned Bouncy Castle source, `validateRound1PayloadReceived` checks only that the
received participant id differs from the local one, and `validateZeroKnowledgeProof` hashes the
participant id carried *inside* the payload. A replayed round-one therefore verifies against its own
id. The dossier states this correctly and assigns the defence to framing; the framing section does not
state the binding that would reject it. Channel-bound participant ids do not close this gap, because a
replayed payload carries its own older id and its own older binding.

**Why it matters.** Replay is the one attack the library explicitly does not stop. If the framing does
not stop it, the property is absent.

**Required decision.** The reviewer must state exactly which fields bind a round to a session, and how
a receiver rejects a payload from a different session, a second round-one in the same session, and a
payload whose bound digest differs from the local one. The requirement must be expressible as a
concrete test against a captured frame, not a synthetic identifier mismatch.

### R-3 — Fixed-width unsigned encoding for PAKE keying material, scalars and tags

**Observed.** `ECJPAKEUtil.calculateMacTag` returns `new BigInteger(macOutput)`, which discards leading
zero bytes; `validateMacTag` compares `toByteArray()` results, which are variable length and may carry
a sign byte. `calculateKeyingMaterial` returns an affine x-coordinate whose true range is `[0, q-1]`,
although the class documentation says `[0, n-1]`. Separately, the zero-knowledge-proof hash is
interpreted through `new BigInteger(output)`, so a digest with the top bit set is read as negative
before reduction.

**Why it matters.** Any wire format derived from these values inherits a non-canonical length and a
malleable encoding. Two implementations can agree on the integer and disagree on the bytes.

**Required decision.** The reviewer must fix the encoding: 32-byte fixed-width unsigned big-endian for
keying material, scalars and tags; no reduction modulo `n` of an x-coordinate; constant-time comparison
over fixed-width arrays; rejection of non-canonical input at decode. The reviewer must also state
whether the signed-hash behaviour is acceptable to depend on, because a second implementation would
naturally read the digest as unsigned.

### R-4 — Secret-erasure limitations must be stated honestly

**Observed.** `ECJPAKEParticipant.calculateKeyingMaterial()` overwrites the password buffer with zeros
and nulls it, then nulls `x1`, `x2` and `b` **without zeroing them**; the library's own source comment
says it relies on the garbage collector and warns that leaked ephemeral private keys permit password
brute force. Keying material is returned as a `BigInteger`, which cannot be wiped. There is no destroy
API.

**Why it matters.** A design document that says "zero `K_pake` and both confirmation keys immediately
after use" is describing something the platform cannot do. That is not a documentation nicety: it
misstates the residual risk that a reviewer is being asked to accept.

**Required decision.** The reviewer must state the achievable erasure boundary and the accepted residual
risk (heap dump, core dump, GC timing), and the implementation must not claim more than it delivers.
Best-effort handling — copy into a byte array, use it, zero the array, drop references — is the
ceiling. `K_pake` cannot be zeroed.

### R-5 — Independent conformance evidence, or an explicitly accepted substitute

**Observed.** Bouncy Castle's shipped tests for this family are round-trip and negative tests. The
honest-exchange test asserts that both participants derive the same keying material, not a published
constant. The implementation cites Hao (eprint 2010/190) and NIST SP 800-56A Rev 3 §5.9.1 and does
**not** claim RFC 8236 conformance, so RFC 8236's vectors do not apply to it. The dossier records this
as a known limitation.

**Why it matters.** A symmetric derivation error inside the library would satisfy every test this
project can write while invalidating the security argument. For a PAKE the agreement property is the
mechanism, so agreement tests cannot detect it.

**Required decision.** The reviewer must either identify acceptable independent vectors, or explicitly
accept the risk in writing with its scope and duration stated. Silent acceptance is not acceptable, and
the absence of vectors must not be re-described later as "tested".

---

## 3. Fields a reviewer may need that are not currently specified

Recorded so the reviewer does not have to discover them independently. These are observations, not
requirements this document can impose.

- The bound transcript as described in the dossier does not appear to bind the identity of the PAKE
  profile, the identity of the channel-binding construction, or the exporter label in use.
- The transcript binds the selected authentication suite but not the rule by which it was selected.
- The TLS client/server roles and the PAKE initiator/responder roles are not related to each other
  anywhere in the transcript description.
- The dossier states that control-record keys stay TLS-exporter-derived; it does not state at which
  point they become usable relative to the PAKE confirmations.

Each of these is a specification question for the reviewer, not a defect claim by this document.

---

## 4. Constraints that remain in force

Unchanged by this errata:

```text
CURRENT_MILESTONE = 2
TRANSFER_ENGINE = unavailable
```

- No production J-PAKE, MPS3, PAKE/TLS channel binding, key-derivation change, pairing UI, payload
  transfer, Part C, Nearby, service, notification, worker, or Room schema change is authorized.
- LAN file payload remains disabled. `PayloadTransferGate` continues to refuse unconditionally.
- Room schema v1 and v2 remain byte-identical; there is no v3.
- Conscrypt TLS 1.3 enforcement, dependency locks, artifact digests, attempt limits, MPS2 fail-closed
  behaviour, payload refusal, diagnostic redaction and API 23 gates are not weakened.
- The J-PAKE feasibility harness remains confined to `androidTest` and is enforced by
  `tools/verify/jpake-production-boundary.mjs`.

---

## 5. How this errata relates to the review

The sequence this document supports is:

1. A qualified human reviewer reads `doc/security/jpake-review-dossier.md`,
   `doc/security/jpake-external-review-request.md` and this errata at one exact commit.
2. That reviewer addresses R-1 to R-5 and the open questions in §3, and returns a dated, signed
   report whose verdict is exactly one of the two strings the review request defines.
3. Only then may implementation be considered, and only for the construction the report approves.

The AI-generated analysis held outside the repository may be supplied as technical input to that
reviewer. It must not be attached as, quoted as, or described as the review itself.
