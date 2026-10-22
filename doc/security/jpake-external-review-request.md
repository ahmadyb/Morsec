# Morsecode J-PAKE / TLS channel-binding security review — EC-variant request (historical)

> **Historical, superseded 2026-10-20.** This request was written when the proposed group family
> was EC P-256. The implemented profile is **BC J-PAKE with `JPAKEPrimeOrderGroups.NIST_3072`**.
> The EC payloads cannot be rebuilt from received bytes through Bouncy Castle's public API, which
> is why the variant changed; see `bc-ecjpake-reconstruction-audit-2026-10-20.md`. The questions
> below remain useful reading, and none of them is answered or waived by this note.

Repository: <https://github.com/ahmadyb/Morsec>

**Review commit:** the latest exact-SHA green documentation commit containing
`doc/security/jpake-review-dossier.md`. The exact SHA and its CI run are stated in the
transmittal accompanying this request.

Production implementation is intentionally absent. The only J-PAKE code in the repository is an
androidTest feasibility harness, guarded by `tools/verify/jpake-production-boundary.mjs`.

Open requirements raised by a non-authoritative AI analysis on 2026-10-19 are listed in
[`jpake-review-errata-2026-10-19.md`](jpake-review-errata-2026-10-19.md). They extend §17 below and
must be answered in the report. That errata is AI-generated technical input, not a review and not an
approval.

## Scope

Review the proposed use of Bouncy Castle 1.86 J-PAKE inside an existing mutual-ephemeral TLS 1.3
channel on Android API 23+. This request as written specifies the EC P-256 variant; the
implemented profile is MODP over `JPAKEPrimeOrderGroups.NIST_3072`, and the difference in group
arithmetic does not change the interaction, the channel binding or any question below.

The interaction is:

1. Initiator generates and displays a random eight-symbol, 40-bit Crockford-style code.
2. Responder enters that code.
3. Both peers run the J-PAKE exchange (EC P-256 in this request; MODP NIST-3072 in the code).
4. Both peers perform round-three confirmation.
5. Both users explicitly confirm the intended peer/session.
6. File payload remains unavailable until authentication completes.

Three online attempts are permitted per code. The code is never transmitted directly, persisted,
or logged.

## Existing TLS model

- Conscrypt TLS 1.3
- Mutual short-lived self-signed P-256 certificates
- Structurally validated certificates
- No PKI trust
- No hostname trust
- Current peer authentication relies on a custom SAS and is not accepted
- MPS2/MST2 bind negotiated capabilities and certificate fingerprints
- Payload remains disabled

## BC implementation

Pinned dependency: `org.bouncycastle:bcprov-jdk18on:1.86`

- API: `org.bouncycastle.crypto.agreement.ecjpake`
- Group: `ECJPAKECurves.NIST_P256` in this request; `JPAKEPrimeOrderGroups.NIST_3072` in the code.
- Digest: SHA-256
- Round-three confirmation is sent and validated in both directions.

## Required review decisions

### 1. J-PAKE profile (EC P-256 in this request; MODP NIST-3072 in the code)

Determine whether the pinned BC J-PAKE implementation and the selected group are suitable for this
use. Review:

- Point validation
- ZKP validation
- Participant-ID handling
- Round ordering
- Wrong-password behavior
- Round-three confirmation
- Secret lifecycle
- Lack of independent fixed conformance vectors

### 2. Code policy

Determine whether:

- 40 bits of generated code entropy
- Three online attempts
- Short expiration
- Single use
- Process-local attempt limits

are sufficient. State whether restart-persistent throttling is mandatory before release.

### 3. Channel binding

Review the proposed candidates.

#### Candidate A — explicit PAKE confirmation over channel-bound transcript

Review the proposed HKDF/HMAC construction, including:

- Canonical PAKE keying-material encoding
- TLS exporter/channel-binding input
- Transcript digest
- HKDF salt and input key material
- Domain-separated labels
- Direction-specific confirmation keys
- Confirmation message format
- Replay prevention
- Key erasure

Identify every required correction.

#### Candidate B — channel-bound participant IDs plus BC round three

Review whether participant IDs derived from:

- RFC 9266 `tls-exporter` channel binding
- Canonical MPS/MST transcript
- Roles
- Session/control IDs
- Certificate fingerprints
- Protocol/cipher
- Nonces

safely bind the J-PAKE ZKPs and BC round-three MACs to the active TLS channel.

Determine whether this construction:

- Resists two-leg TLS termination
- Resists PAKE-message relay
- Resists replay
- Resists reflection
- Provides mutual confirmation
- Avoids circular dependencies
- Preserves participant-ID assumptions in the BC security analysis

Do not approve it merely because participant IDs are MAC inputs.

#### Candidate C

Clarify any proposal involving:

- RFC 9266 TLS 1.3 channel binding
- RFC 9261 TLS Exported Authenticators
- TLS exporter values

Specify which standard mechanism is actually applicable and whether Conscrypt 2.7.0 on API 23
exposes the required API.

### 4. Transcript

Determine whether the proposed bound transcript is complete and canonical. It includes:

- J-PAKE participant IDs
- J-PAKE rounds
- Curve/profile
- Control/session IDs
- Initiator/responder roles
- MPS offers
- MST selection
- TLS certificate fingerprints
- TLS protocol/cipher
- Capabilities
- Nonces
- Security format

Identify omitted security-relevant fields.

### 5. Key schedule

Determine whether control-record keys should:

- Remain TLS-exporter-derived after PAKE authentication
- Be derived from PAKE material
- Combine PAKE and TLS material
- Use another reviewed construction

Provide the exact recommended schedule and labels.

### 6. Confirmation

Determine whether BC round three in both directions is sufficient after the recommended channel
binding. If not, provide the exact additional mutual-confirmation construction.

### 7. Framing

Review the proposed MPS3 framing:

- Fixed bounds
- Canonical EC-point encoding
- Round/session binding
- Participant-ID binding
- Duplicate/unknown/trailing rejection
- Invalid-point handling
- Resource limits

### 8. Threat model

Review resistance to:

- Passive eavesdropping
- Active two-leg MITM
- Wrong-code entry
- Online guessing
- Replay
- Reflection
- Cross-session substitution
- Downgrade
- Invalid points/ZKPs
- Resource exhaustion
- Error oracles
- Process restart
- Shoulder surfing/code disclosure

### 9. Open requirements carried from the 2026-10-19 errata

Answer each explicitly. Detail is in
[`jpake-review-errata-2026-10-19.md`](jpake-review-errata-2026-10-19.md) §2; the summary here is not
a substitute for reading it.

- **R-1 Authentication-suite negotiation.** The current selector resolves the suite with ordinal
  `minOf` over wire ids and rejects only `NONE`. State the correct rule, and state that a peer which
  offers only a weaker suite must be refused rather than accommodated.
- **R-2 Round-one replay.** The pinned library accepts a replayed round one because the participant
  id travels inside the payload and is what the ZKP hashes. State exactly which fields bind a round
  to a session and how a replay is rejected at the framing layer.
- **R-3 Encoding.** MAC tags and keying material are returned as `BigInteger` with variable-length
  encodings, and the ZKP hash is read as a signed value. State the required wire encodings and say
  whether depending on the signed-hash behaviour is acceptable.
- **R-4 Erasure.** `x1`, `x2` and `b` are nulled but not zeroed, and `K_pake` is a `BigInteger` that
  cannot be wiped. State the achievable erasure boundary and the residual risk a deployment accepts.
- **R-5 Conformance evidence.** The library ships round-trip tests rather than published vectors and
  does not claim RFC 8236. Identify acceptable independent vectors, or state explicitly that the risk
  is accepted and on what terms.

## Required deliverable

Provide a dated written report containing:

1. Reviewer identity and relevant qualifications
2. Exact commit and dossier reviewed
3. Selected candidate or explicit rejection of all candidates
4. Critical/High/Medium/Low findings
5. Exact required protocol changes
6. Exact approved channel-binding construction, if any
7. Exact approved key schedule and confirmation flow
8. Required attempt-limit policy
9. Required framing changes
10. Required test vectors/tests
11. Residual risks
12. One conclusion:

```text
APPROVED FOR IMPLEMENTATION SUBJECT TO LISTED CHANGES
```

or:

```text
NOT APPROVED FOR IMPLEMENTATION
```

A conditional approval must enumerate every condition.

The reviewer is not being asked to approve file payload transfer, UI, services, or Nearby.
