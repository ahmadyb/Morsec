# Owner risk waiver — experimental EC J-PAKE pairing

**Date:** 2026-10-19
**Applies to:** the experimental Part B pairing implementation on `arena/4d4c3195-morsec`
**Supersedes:** the external-review acceptance gate for this feature only

---

## The decision

The owner of this repository waived the requirement for a qualified independent cryptographic
review of the EC J-PAKE pairing design, for this personal experimental application. Implementation
is authorized on that basis.

This waiver permits the code to exist. It does **not** establish that the code is secure, and it
does not convert the design into a reviewed protocol.

## The permanent statement

```text
The EC J-PAKE/TLS composition is experimental, project-defined, and not
independently cryptographically reviewed. The owner explicitly accepted
this risk on 2026-10-19.
```

This sentence is the accurate description of the LAN security feature and should be repeated
wherever the feature is described to a user.

## What is being accepted

1. **No qualified external cryptographic review has taken place.** A review dossier and a review
   request exist (`jpake-review-dossier.md`, `jpake-external-review-request.md`), and an
   AI-generated technical analysis was produced on 2026-10-19. None of those is an external review.
   AI analysis is not review; a test suite is not review; a green CI run is not review.
2. **The composition may contain undiscovered authentication or channel-binding defects.** The
   binding between the PAKE output and the TLS channel is project-defined (Candidate B:
   channel-bound participant identifiers). No published analysis covers it. It could be wrong in a
   way that no test in this repository would detect, because the repository can only test what its
   author thought to test.
3. **A same-network attacker remains a residual risk.** Anyone able to act as a man in the middle on
   the local network, to race discovery, or to observe and replay traffic is inside the threat
   model. The design resists a relay that does not know the code, but that claim rests on an
   unreviewed construction.
4. **There is no security warranty.** The software is provided as-is. The owner accepts the
   consequences of running it, including the possibility that a peer on the same network obtains
   files or session authority it should not have.
5. **No claim of formal conformance to RFC 8236.** The pinned Bouncy Castle implementation cites
   Hao (eprint 2010/190) and NIST SP 800-56A Rev 3 §5.9.1. It does not claim RFC 8236, and this
   project does not claim it either. Its behaviour is what it is; the name of a standard is not
   being borrowed for it.
6. **File payload remains disabled.** Pairing establishes a control session. It does not enable
   file transfer, chunks, resume, a transfer UI, a service, or any background execution. Those stay
   gated behind the existing milestone and feature flags, and enabling them requires their own
   implemented and tested code — not a constant change.

## What the waiver does not change

- `CURRENT_MILESTONE` remains `2`.
- `TRANSFER_ENGINE` remains unavailable.
- `PayloadTransferGate` continues to refuse unconditionally.
- Room schema v1/v2 remain byte-identical; no v3.
- Conscrypt TLS 1.3 enforcement, the dependency lock, the artifact digests, the attempt limiter, the
  MPS2 fail-closed behaviour, diagnostic redaction and the API 23 gates are unchanged.
- The prior findings and the errata are not deleted. An accepted risk is not a resolved finding.

## Safety properties that are deliberately retained

Personal software can still expose files to attackers on the same network, so the warning and the
fail-closed behaviour matter more, not less, now that review is not gating:

- The feature refuses rather than degrades. There is no plaintext fallback, no weaker suite, and no
  automatic retry on another mechanism.
- The entered code is never logged, persisted, placed in a diagnostic, written to Room, or copied to
  a clipboard by the implementation.
- Failures are typed and redacted, and they close the session.
- Payload transfer stays refused even after a successful pairing.
- The implementation is confined to an explicit, verifier-enforced set of files.

## Revocation

This waiver can be revoked. Revoking it does not require deleting working code: it means the
feature returns to gated-and-unreachable until a qualified review exists. The review dossier and
the review request remain in the tree for exactly that purpose.

## Scope

This waiver covers the LAN pairing mechanism only. It does not authorize file transfer, a transfer
UI, a foreground service, notifications, Nearby, Media3, WebShare, Room changes, or Part C. Those
require their own implementations and tests, and their own authorization.
