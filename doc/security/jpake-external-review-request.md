# Morsecode EC J-PAKE / TLS channel-binding security review

Repository: <https://github.com/ahmadyb/Morsec>

**Review commit:** the latest exact-SHA green documentation commit containing
`doc/security/jpake-review-dossier.md`. The exact SHA and its CI run are stated in the
transmittal accompanying this request.

Production implementation is intentionally absent. The only J-PAKE code in the repository is an
androidTest feasibility harness, guarded by `tools/verify/jpake-production-boundary.mjs`.

## Scope

Review the proposed use of Bouncy Castle 1.86 EC J-PAKE over NIST P-256 inside an existing
mutual-ephemeral TLS 1.3 channel on Android API 23+.

The interaction is:

1. Initiator generates and displays a random eight-symbol, 40-bit Crockford-style code.
2. Responder enters that code.
3. Both peers run EC J-PAKE.
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
- Curve: `ECJPAKECurves.NIST_P256`
- Digest: SHA-256
- Round-three confirmation is sent and validated in both directions.

## Required review decisions

### 1. EC J-PAKE profile

Determine whether the pinned BC EC J-PAKE implementation and P-256 profile are suitable for this
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
