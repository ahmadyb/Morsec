# J-PAKE channel binding and mutual confirmation — external review dossier

Status: **awaiting qualified external cryptographic review**. Nothing in this document is
implemented in production. The J-PAKE code in this repository exists only as an androidTest
feasibility harness, guarded by `tools/verify/jpake-production-boundary.mjs`.

Prepared against Bouncy Castle **1.86** (the version pinned in `gradle/libs.versions.toml` and
digest-locked in `gradle/security-dependency-lock.json`), read from the `r1rv86` tag of
`bcgit/bc-java`.

---

## 1. Protocol goals

Two Morsec peers on the same LAN must establish a session that is authenticated by a human
action, not by a pre-shared certificate authority. There is no PKI, no server and no prior
relationship between the devices.

The chosen interaction is:

```text
One peer displays a generated shared code.
The other peer enters that code.
Both peers explicitly confirm the intended peer/session.
```

Requirements:

- An eavesdropper learns nothing usable, and cannot later verify a guessed code offline.
- An active man-in-the-middle who does not know the code cannot obtain an authenticated
  session on either leg, even while terminating two independent TLS connections.
- A wrong code fails, and the failure is observable to both humans.
- Codes are short-lived, single-use, and never persisted or logged.

## 2. Threat model

| Adversary | Assumed capability |
| --- | --- |
| Passive eavesdropper | Reads all LAN traffic; may store it indefinitely. |
| Active MITM | Injects, drops, reorders, replays and delays packets; can terminate TLS legs independently and relay application messages between them. |
| Online guesser | May attempt to complete a pairing with a guessed code, subject to the attempt limiter. |
| Malicious peer | A device that runs a modified client and sends arbitrary protocol messages. |

Out of scope: a compromised device already paired; a coerced human entering a code read aloud
by an attacker; denial of service beyond the bounded attempt policy.

## 3. Existing TLS design (unchanged by this work)

Production pairing already establishes mutual **ephemeral TLS 1.3** with self-signed
certificates generated per attempt, through the bundled Conscrypt provider. The engine
re-checks, after the handshake, that the socket really is Conscrypt, that the negotiated
protocol is TLS 1.3, and that the cipher suite is in the allowed TLS 1.3 set. Session keys are
derived from the TLS exporter with the canonical MPS/MST transcript digest as the exporter
context.

## 4. Why ephemeral TLS certificates alone are insufficient

Each peer generates a fresh self-signed certificate per attempt, so the certificate proves
nothing about *which device the human intended to pair with*. A MITM can present its own
ephemeral certificate to each peer; both handshakes succeed, and each peer sees a structurally
valid certificate. TLS authenticates the channel, not the peer's identity in the human sense.

The human interaction is what closes that gap. It must therefore be bound to the channel: if
the code exchange is not bound to the TLS transcript, a MITM can complete one PAKE exchange
with the real code and relay it, leaving the two humans believing they paired with each other.

## 5. Interaction contract

| Property | Decision |
| --- | --- |
| Who generates the code | The **initiator**. |
| Who displays it | The **initiator**. |
| Who enters it | The **responder**. |
| Role determination | `SecurePeerRole` from the existing MPS2 offer. |
| Simultaneous initiation | Tie-break on the lexicographically lower `peerInstanceId` (both are exactly 32 hex characters, so the order is total). The loser restarts as responder. |
| Explicit final approval | **Retained on both peers.** PAKE proves code possession; it does not prove the human intends this device. |
| Code lifetime | Short, bounded, one code per pairing attempt. |
| Entry attempts | 3 per code, then the code is invalidated and a fresh discovery plus code is required. |
| Persistence | Never. Not in Room, not in preferences, not on disk. |
| Process recreation | The code does not survive it; a new code is required. |
| Logging | Never, in any form. |

### Code format and entropy

**Recommendation:** 8 symbols from a Crockford-style Base32 alphabet excluding I, L, O and U
(32 symbols, 5 bits each), displayed grouped 4-4, entered case-insensitively with `I`/`L`→`1`
and `O`→`0` normalisation. **Effective entropy: 40 bits.**

This deliberately does not reuse the retired 25-bit derived proof. That value was *derived and
compared*; this one is *typed by a human*, so it must survive transcription, and it is the
PAKE password rather than a confirmation value.

With a hard bound of 3 online attempts per code and a fresh code per pairing, 40 bits leaves
roughly 2⁻³⁸ success probability per code. The binding constraint is the attempt limiter, not
the alphabet. Decimal digits would need 13 characters for the same entropy; a word list costs
localisation and UI work for no security gain.

Accessibility: readable by screen readers, no colour-only encoding, no keyboard-layout
assumption. Treated as screenshot- and shoulder-surf-sensitive, hence short lifetime and
single use.

## 6. Bouncy Castle 1.86 implementation details (read from source)

Two independent J-PAKE families ship in bc-java's `core` module, i.e. in **bcprov**, which is
already locked and packaged. **No new artifact is required.**

| | MODP | **EC (recommended)** |
| --- | --- | --- |
| Package | `org.bouncycastle.crypto.agreement.jpake` | `org.bouncycastle.crypto.agreement.ecjpake` |
| Classes | `JPAKEParticipant`, `JPAKEPrimeOrderGroup(s)`, `JPAKERound1/2/3Payload`, `JPAKEUtil` | `ECJPAKEParticipant`, `ECJPAKECurve`, `ECJPAKECurves`, `ECJPAKERound1/2/3Payload`, `ECSchnorrZKP`, `ECJPAKEUtil` |
| Groups | `SUN_JCE_1024` (80-bit), `NIST_2048`, `NIST_3072` (default) | `NIST_P256`, `NIST_P384`, `NIST_P521` |
| Group source | Sun JDK JavaDoc / NIST DSA examples | NIST SP 800-186 |
| Keying material range | `[0, p-1]` | affine X coordinate, documented as `[0, n-1]` |
| Confirmation reference | NIST SP 800-56A **Rev 1** §8.2 | NIST SP 800-56A **Rev 3** §5.9.1 |
| Protocol reference | Hao & Ryan 2008 | Hao, *"J-PAKE: Authenticated Key Exchange Without PKI"*, eprint 2010/190 |

Neither class cites RFC 8236. **Bouncy Castle does not claim RFC 8236 conformance.**

### API

Constructor: `ECJPAKEParticipant(String participantId, char[] password, ECJPAKECurve curve,
Digest digest, SecureRandom random)`. Convenience overloads default to `NIST_P256`, SHA-256 and
`CryptoServicesRegistrar.getSecureRandom()`.

Seven calls, once each, in order:

```text
createRound1PayloadToSend      validateRound1PayloadReceived
createRound2PayloadToSend      validateRound2PayloadReceived
calculateKeyingMaterial
createRound3PayloadToSend(km)  validateRound3PayloadReceived(p3, km)
```

States `STATE_INITIALIZED` (0), `ROUND_1_CREATED` (10), `ROUND_1_VALIDATED` (20),
`ROUND_2_CREATED` (30), `ROUND_2_VALIDATED` (40), `KEY_CALCULATED` (50), `ROUND_3_CREATED`
(60), `ROUND_3_VALIDATED` (70). Out-of-order or duplicate calls throw `IllegalStateException`.

### Validation performed by the library

`ECJPAKEUtil.validateZeroKnowledgeProof` implements the public-key validation of Antipa, Brown,
Menezes, Struik and Vanstone, *"Validation of elliptic curve public keys"*, PKC 2002, §3:

1. `X != infinity` → `CryptoException`.
2. Affine x and y in `[0, q-1]`.
3. X lies on the curve (re-decode).
4. `nX = infinity`, checked as `coFactor·X != infinity`.
5. `V = G·r + X·h`.

Participant IDs: `validateParticipantIdsDiffer` in every round, plus
`validateParticipantIdsEqual` against the partner ID captured in round 1 for rounds 2 and 3. A
substituted sender is therefore refused.

ZKP: Schnorr, `{V = G·v, r = v − x·h}`. The hash input is the **length-prefixed** (4-byte
big-endian) encoding of `g`, `V`, `X` and `participantId`, so there is no concatenation
ambiguity. Points are encoded **compressed** (`getEncoded(true)`), 33 bytes for P-256.

Secret scalars are multiplied with `ECAlgorithms.multiplySecret` (constant-time), explicitly
commented as such for `x1`, `x2`, `v` and `x2·s`.

### Password handling

The code is supplied as `char[]`. A defensive copy is taken at construction, so the caller can
and should zero its own array immediately. The copy is zeroed and the field nulled inside
`calculateKeyingMaterial()`. An empty code throws `IllegalArgumentException` at construction.
`calculateS` maps the UTF-8 bytes to an unsigned `BigInteger` mod `n` and rejects `s ≡ 0`.

**What remains GC-managed:** `x1`, `x2` and `b` are nulled but not zeroed — the source states
plainly that it relies on the garbage collector and warns that leaking the ephemeral private
keys permits password brute force. `gx1..gx4` are deliberately retained until round 3 is
validated, then nulled.

### Round three: mutual confirmation and reflection

`calculateMacTag` implements NIST SP 800-56A Rev 3 §5.9.1:

```text
MacKey = H(K || "ECJPAKE_KC")
MacData = "KC_1_U" || participantId || partnerParticipantId || gx1 || gx2 || gx3 || gx4
MacTag = HMAC(MacKey, MacData)
```

The label is the fixed string `"KC_1_U"` for both peers — the source notes this is because the
round-3 sender is nominally always the initiator. However `validateMacTag` recomputes the
expected tag with `participantId ↔ partnerParticipantId` and `gx1 ↔ gx3`, `gx2 ↔ gx4`
**reversed**. Consequently the two peers produce *different* tags, each verifiable only by the
correct partner.

**Two consequences, both material to this design:**

1. Running round three in **both** directions does yield **mutual** confirmation.
2. **Reflection fails**: handing a peer its own tag back causes it to recompute the reversed
   tag, which differs.

What round three does **not** bind: the TLS channel, the negotiated protocol or cipher, the
control and session identifiers, the MPS offers, or the selected MST profile. It binds only the
J-PAKE transcript and the two participant IDs. **This is the gap the channel binding must
close.**

### Encoding hazards found in the library

- `calculateMacTag` returns `new BigInteger(macOutput)`, which **discards leading zero bytes**.
  A tag whose first byte is `0x00` is represented in fewer bytes. Any wire format must use a
  fixed width.
- `validateMacTag` compares via `Arrays.constantTimeAreEqual(a.toByteArray(), b.toByteArray())`.
  `BigInteger.toByteArray()` is variable length and may carry a sign byte, so the comparison
  operates on non-canonical encodings. It is still constant-time within a fixed length, but the
  project should compare fixed-width byte arrays itself.
- `calculateKeyingMaterial` returns an affine X coordinate. The Javadoc says `[0, n-1]`; an
  x-coordinate actually lies in `[0, q-1]`, and for P-256 `q ≠ n`. Canonical encoding must not
  assume the documented range.

### Round scheduling is constrained by the state machine

`validateRound1PayloadReceived` moves the state to `STATE_ROUND_1_VALIDATED` (20), while
`createRound1PayloadToSend` throws once the state has reached `STATE_ROUND_1_CREATED` (10).
A participant therefore **cannot validate an incoming round one before generating its own**;
round two is constrained identically, and so is round three: validating a round-three tag
moves the state to `STATE_ROUND_3_VALIDATED` (70) while creating one throws once the state
has reached `STATE_ROUND_3_CREATED` (60). In every round, both peers must create before
either validates.

This is a scheduling requirement, not a detail. Any framing layer must be able to produce its
own round payload before it has seen the peer's, so the two round-one messages must be
exchangeable concurrently rather than strictly request-then-response. It was found by
measurement: the first feasibility run failed with `IllegalStateException: Round1 payload
already created` on every test that validated first.

### Concurrency

`ECJPAKEParticipant` is documented as **stateful and NOT threadsafe**, one instance per
exchange. No method is safe for repeated use: every create/validate call advances state and
throws on repetition.

### Test vectors

Bouncy Castle ships `JPAKEParticipantTest`, `ECJPAKEParticipantTest`, `JPAKEUtilTest`,
`ECJPAKEUtilTest` and `ECJPAKECurveTest`. These are **round-trip and negative tests**, not
published vectors: the honest-exchange test asserts `aliceKeyingMaterial == bobKeyingMaterial`,
not a fixed constant. **No independent conformance vector source has been identified.** Since
the implementation does not claim RFC 8236 conformance, RFC 8236's vectors are not applicable
to it either.

## 7. Proposed framing (candidate, not agreed)

Recommend **MPS3** as a new security format rather than a sub-protocol, reusing the proven MPS2
header discipline: fixed length prefix, magic-identified payload, canonical TLV, and rejection
of duplicates, unknown mandatory fields and trailing data. The existing legacy-rejection
machinery generalises directly to "MPS2 where MPS3 is required".

Fields: magic `"MPS3"` and version · control session ID · secure session ID · role · both
participant IDs · round number · group identifier (`nist_p256`) · payload count · bounded
point and scalar bodies · end tag.

Encoding rules: canonical **unsigned fixed-width** — 33 bytes per compressed P-256 point, 32
bytes per scalar and per MAC tag. No Java object serialisation. No `BigInteger` reconstructed
from an attacker-supplied length before the bound is checked. No negative-integer ambiguity. No
redundant leading zeroes.

Rejected: duplicate fields, unknown mandatory fields, trailing data, a payload from the wrong
round, a payload from the wrong session, a reflected payload, a swapped role, a participant-ID
mismatch.

**Invalid points must be handled at decode, not assumed away.** Measured on the API 23 harness:
a structurally invalid encoding (bad prefix byte, truncated body) is rejected deterministically
by `ECCurve.decodePoint`. A *well-formed* encoding whose x is simply not a curve x-coordinate is
also rejected, but roughly half of all 32-byte field elements are valid curve x-coordinates, so
no single fixed corruption demonstrates it -- a one-bit flip decodes successfully about half the
time. The framing layer must therefore reject on decode for every point it reads, and must not
rely on a downstream check having been reached.

Measured encoded sizes for P-256 (points and scalars only, excluding framing and IDs):

| Round | Contents | Bytes |
| --- | --- | --- |
| 1 | 2 points + 2 ZKPs (point + scalar each) | **196** |
| 2 | 1 point + 1 ZKP | **98** |
| 3 | 1 MAC tag | **32** |

## 8. Full transcript the confirmation must bind

Both participant IDs · the complete J-PAKE round transcript · the group identifier · both
control and session IDs · initiator and responder roles · both MPS offers · the selected MST
profile · **both TLS ephemeral certificate fingerprints** · negotiated TLS protocol ·
negotiated TLS cipher · the relevant capability offers and selections · both nonces · the
security-format version.

## 9. Candidate channel bindings

### Candidate A — explicit mutual confirmation (application-constructed)

| Element | Specification |
| --- | --- |
| Keying-material encoding | Fixed-width 32-byte unsigned big-endian. **Not** `BigInteger.toByteArray()`. |
| Channel-binding value | `CB = TLS-Exporter("morsec jpake channel binding v1", transcript_digest, 32)` |
| KDF | HKDF-SHA256 (RFC 5869) |
| Extract salt | `transcript_digest` (32 bytes) |
| Extract IKM | `encode(K_pake) \|\| CB` |
| Expand labels | `"morsec m1 confirm i2r v1"`, `"morsec m1 confirm r2i v1"`, `"morsec m1 chanbind v1"` |
| Confirmation | HMAC-SHA256, direction-specific keys, over the full bound transcript of §8 |
| Message | 32-byte fixed-width tag in an MPS3 CONFIRM record |
| Replay protection | Bound session IDs and nonces, plus monotonic record sequence |
| Erasure | Zero `K_pake` and both confirmation keys immediately after use |
| Failure | Typed, redacted, channel closed, session unusable, no authority issued |

**Project-defined components, every one of which needs review:** the salt choice; the three
label strings; the decision to feed the TLS exporter into the HKDF IKM alongside the PAKE
keying material; the transcript field ordering; and the choice of an application HMAC over the
library's own round 3.

Note in particular that `CB` above uses the raw RFC 8446 §7.5 exporter with a **project label
and a non-empty context**. It is *not* the registered RFC 9266 `tls-exporter` binding, which
mandates the label `EXPORTER-Channel-Binding` and a zero-length context. Candidate A therefore
proposes a project-defined channel binding built on a standard primitive; see Candidate C1 for
what adopting the registered binding instead would and would not change.

### Candidate B — channel-bound participant IDs plus the library's round three

Derive `CB` as above, then set

```text
participantId(initiator) = "morsec-v1|initiator|" || hex(CB)
participantId(responder) = "morsec-v1|responder|" || hex(CB)
```

Because the library hashes `participantId` into **every ZKP** and into the **round-3 MacData**,
both the proofs and the confirmation transitively bind `CB`, and therefore the TLS channel.
Length-prefixed hashing removes concatenation ambiguity.

`CB` here may be either the project exporter value of Candidate A or the registered RFC 9266
`tls-exporter` binding of Candidate C1. The two are not interchangeable: the registered binding
binds the TLS connection only, so a Candidate B built on it would leave the MPS offers bound
solely through whatever else is placed in the participant ID. The reviewer is asked to state
which `CB` definition Candidate B is being assessed against, because the answer changes what the
construction does and does not cover.

**Mutual confirmation:** yes — see §6; both directions of round three are distinct and
reflection fails.

**Replay and cross-session:** a replayed or cross-session payload carries a participant ID
derived from a different `CB`, so `validateParticipantIdsEqual` refuses it, and the ZKPs will
not verify against the new ID in any case.

**Circular dependency:** none, provided PAKE runs last. `CB` derives from the completed TLS
handshake (and optionally the MPS offers, which are exchanged inside TLS before PAKE). Nothing
in `CB` depends on PAKE output. The dependency would only become circular if a PAKE result were
fed into a value the transcript already contained; it is not.

**Weaknesses, stated for the reviewer rather than glossed:** the binding is *indirect* — it
rests on a string being hashed as a ZKP and MAC input, not on a designed channel-binding
construction, and no published analysis covers this composition. The MPS offers are bound only
if they are included in `CB`; the library's MAC does not bind them directly. Any inconsistency
between the peers' ID construction fails closed, which is safe but must be tested.

**This construction is submitted for review, not asserted to be secure.**

### Candidate C — standard TLS channel-binding mechanisms

**Correction.** An earlier revision of this dossier described RFC 9266 as an "exported
authenticator" and gave it a title it does not have. That was wrong. RFC 9266 and RFC 9261 are
distinct mechanisms published in the same month, and only one of them is a channel binding:

| | **RFC 9266** | **RFC 9261** |
| --- | --- | --- |
| Title | *Channel Bindings for TLS 1.3* | *Exported Authenticators in TLS* |
| Status | Proposed Standard, July 2022 | Proposed Standard, July 2022 |
| What it is | A registered **channel binding type**, `tls-exporter`, per RFC 5056 | Proof of ownership of an **identity (X.509 certificate)**, exported from a TLS connection |
| Construction | EKM from the RFC 5705 / RFC 8446 §7.5 exporter with label `EXPORTER-Channel-Binding`, **zero-length context**, 32 bytes | Concatenation of TLS 1.3 `Certificate`, `CertificateVerify` and `Finished`, MACed with a Finished key derived from the connection |
| Effect on other standards | Updates RFCs 5801, 5802, 5929 and 7677; mandatory to implement if any TLS 1.3 channel binding is implemented | Replaces TLS 1.2 renegotiation for post-handshake authentication |

#### C1 — RFC 9266 `tls-exporter` channel binding

**This is the only standard channel-binding mechanism identified here, and it is applicable in
principle.** Two properties make it attractive. It is a registered, Standards Track binding
rather than a project invention. And its underlying primitive is the TLS keying-material
exporter, which the bundled Conscrypt already exposes and which production already calls at five
sites in `SecureLanPairingCoordinator.kt` — so **the primitive is demonstrably available on
API 23**, and that part is measured rather than assumed.

The limitation is exact and it matters. RFC 9266 fixes both inputs: the label is the registered
string `EXPORTER-Channel-Binding` and the context is **zero-length**. The resulting value binds
the *TLS connection only*. It does not bind the MPS offers, the MST selection, the capabilities
or the security-format version. Adopting C1 therefore replaces a project-invented exporter label
with a registered one, but the binding to the MPS/MST transcript still has to be composed by the
project. C1 removes one project-defined component and leaves the composition question open.

The reviewer is asked specifically whether that trade is worth making, and whether
`CB_rfc9266 || transcript_digest` is an acceptable composition or an unanalysed one.

RFC 9266 also leaves `tls-exporter` undefined when TLS renegotiation is enabled on the
connection. TLS 1.3 has no renegotiation and this design pins TLS 1.3, so that condition is
satisfied.

#### C2 — RFC 9261 Exported Authenticators: considered, not proposed

RFC 9261 is a different mechanism and **is not proposed here**. It proves ownership of a
certificate using a Handshake Context and Finished MAC key derived from the connection. It binds
that proof to the connection, but what it authenticates is an *identity*, not possession of a
shared code, so it is not a substitute for a PAKE-to-channel binding.

It is also a poor fit on the merits. The peer certificates in this design are ephemeral,
self-signed and carry no trust, so proving ownership of one adds nothing the TLS handshake did
not already establish. And RFC 9261 states that the creation and validation APIs *SHOULD* live
inside the TLS library; whether Conscrypt 2.7.0 exposes them on API 23 is **not established**.

C2 is recorded so the reviewer can see it was considered and on what grounds it was set aside.
It is not a proposal.

## 10. Key lifecycle

```text
entered code (char[])  --copied-->  participant  --zeroed at calculateKeyingMaterial-->
K_pake (BigInteger)    --HKDF-->    K_confirm_i2r, K_confirm_r2i, K_chanbind  --zeroed after use-->
control-record keys    --unchanged: TLS exporter over the transcript digest
```

**Recommendation:** control-record keys stay TLS-exporter-derived after a successful PAKE. The
exporter already binds the transcript digest, the channel is by then authenticated by a
completed PAKE, and re-keying would place a novel construction on the data path for no
measurable gain.

## 11. Replay, reflection and downgrade

| Attack | Defence |
| --- | --- |
| Round-1 replay | **The library accepts it.** A round-1 payload is self-contained, so freshness must come from project framing binding every round to the session. Measured, not assumed. |
| Round-2 replay | Does not complete: keying material disagrees and confirmation fails. |
| Cross-session payload | Refused by `validateParticipantIdsEqual`; under Candidate B also by the channel-bound ID. |
| Reflection | Refused: the two round-3 directions differ by construction. |
| Downgrade to MPS2 authentication | Typed incompatible-authentication result. No plaintext path, no automatic retry. |
| Downgrade to a weaker curve | The group identifier is in the bound transcript; a mismatch fails confirmation. |

## 12. Error behaviour and oracles

`CryptoException` signals a cryptographic validation failure; `IllegalStateException` signals a
state violation. Neither message may reach the peer. The two must not be distinguishable by the
peer through timing or content, or confirmation failure becomes an oracle for code guessing.
All failures are typed, redacted and close the channel.

## 13. Resource limits

Built on the existing lease-scoped limiter: 3 starts per identity, 8 per source, 2 per pair;
3 failures per identity, 2 per pair; cooldown 2 s → 60 s; `maxConcurrentPairings = 2`.

Added for J-PAKE: at most 3 code entries per code, one concurrent PAKE per pair, code
invalidated on exhaustion or expiry, fresh discovery required for a new code. The limiter is
**process-local**; J-PAKE removes offline dictionary verification but not online guessing, so
persistence across restarts would be needed against a determined attacker. That is deferred and
**requires no Room v2 change**.

## 14. API 23 measurements

Measured by `EcJpakePerformanceApi23Test` on the API 23 emulator, using monotonic time, with
three discarded warm-up exchanges separated from seven measured ones, reporting median and 90th
percentile per phase under the log tag `MorsecJpakePhase`.

The assertion is a deliberately generous ceiling — 20 s for a single exchange, 40 s for four
concurrent — intended only to catch a catastrophic regression, not to act as a benchmark. Both
held with roughly two orders of magnitude of headroom. The medians above are the meaningful
output.

Measured on the API 23 emulator, run `39083022037` at `6ed9e10ae1998e8f5e4e27528f6a0db4a7e8869e`,
P-256, SHA-256, 3 discarded warm-up exchanges and 7 measured ones. Each phase figure covers
**both** participants' work in that phase, so a single peer's share is roughly half.

| Phase | Median (µs) | p90 (µs) |
| --- | --- | --- |
| Participant construction | 171 | 180 |
| Round 1 create | 167,679 | 173,147 |
| Round 1 validate | 146,454 | 151,561 |
| Round 2 create | 85,767 | 96,640 |
| Round 2 validate | 105,127 | 110,814 |
| Keying-material calculation | 102,203 | 107,894 |
| Round 3 create | 2,778 | 3,027 |
| Round 3 validate | 2,480 | 3,143 |
| **Total honest exchange (both sides)** | **614,873** | 619,634 |
| Total wrong-code exchange (both sides) | 605,759 | 644,217 |
| Four concurrent exchanges | 2,519,165 | 2,519,165 |

Reading, not advocacy:

- A full honest exchange costs about **0.61 s** on an API 23 emulator, or roughly 0.31 s per
  peer. That is comfortable against a human interaction that already requires reading and typing
  an eight-character code.
- Cost is dominated by the elliptic-curve scalar multiplications in rounds 1 and 2. Round 3 is
  cheap because it is an HMAC, not a point multiplication.
- Four concurrent exchanges take about 2.52 s against 0.61 s for one, so concurrency is close to
  linear rather than serialised. With 3 samples the median and p90 are the same element, so no
  percentile claim is made for that row.
- A wrong-code exchange costs essentially the same as an honest one up to the point of failure.
  **This is a timing-oracle consideration:** an attacker cannot distinguish a wrong code from a
  right one by how long the exchange took, but the confirmation failure itself is still an
  online oracle, which is what the attempt limiter bounds.

Encoded payload sizes, asserted by test rather than estimated:

| Round | Bytes |
| --- | --- |
| 1 | **196** |
| 2 | **98** |
| 3 | **32** |
| Compressed P-256 point | 33 |
| Scalar | 32 |

**Allocation is not measured.** The thread-allocation counters available on API 23 attribute
all thread activity and cannot be isolated reliably under a concurrent runner.

Emulator limitations apply: shared CI host, variable CPU allocation, no hardware crypto
acceleration guarantee. A single run supports no hard performance claim.

## 15. Dependency provenance

`bcprov-jdk18on:1.86` is already digest-locked (SHA-256 recorded in
`gradle/security-dependency-lock.json`) and already packaged. J-PAKE adds **no new artifact and
no new transitive dependency**. Licence is the Bouncy Castle Licence (MIT-style), already
attributed. No Room schema change, no new Android permission, no service or UI work.

Residual limitation carried over from the dependency work: `.asc` signatures exist for every
artifact but are **not verified** by this repository, so provenance of the bytes is
established while authorship is not.

## 16. Known limitations

1. No published conformance vectors for the pinned implementation (§6).
2. Bouncy Castle does not claim RFC 8236 conformance; the profile is Hao/eprint 2010/190.
3. Ephemeral private scalars are nulled, not zeroed; the library relies on the GC (§6).
4. Round three is unilateral by specification; mutual confirmation is achieved by running both
   directions, which is a usage pattern rather than a library guarantee.
5. The channel binding is project-defined under both candidates and is unreviewed.
6. MAC tags and keying material are returned as `BigInteger` with non-canonical encoding (§6).
7. API 23 timing is measured on an emulator, not on representative hardware.

## 17. Questions for the external reviewer

1. Is the Bouncy Castle EC J-PAKE profile (`ECJPAKEParticipant`, NIST P-256, SHA-256) suitable
   for this use?
2. Are 40 bits of entered-code entropy and 3 online attempts per code sufficient for this threat
   model?
3. Which channel-binding candidate is acceptable — A, B, or neither?
4. Does the chosen binding resist active two-leg TLS termination and message relay?
5. Is mutual confirmation correctly achieved by running the library's round three in both
   directions, or is an application-constructed confirmation mandatory?
6. Are the HKDF and domain-separation choices in Candidate A correct?
7. Are channel-bound participant IDs (Candidate B) constructed safely, given that the library
   hashes them as length-prefixed UTF-8?
8. Is the MPS3 framing canonical and sufficiently bounded?
9. Are the error responses and oracle behaviour acceptable?
10. Are replay, reflection and downgrade protections complete?
11. Are the API 23 and Conscrypt provider assumptions safe?
12. What changes are **mandatory** before implementation may begin?

---

## Appendix: sequence diagrams

### A.1 Honest pairing

```text
 Initiator (displays)                 Responder (enters)
      |                                        |
      |----- TLS 1.3 handshake (mutual, ephemeral) -----|
      |----- MPS2 offers / capability negotiation ----->|
      |                                        |
      | generate code, display it              |
      |                                human reads and enters code
      |                                        |
      |<---- R1 {id, gx, ZKP} -----------------|
      |----- R1 {id, gx, ZKP} ---------------->|
      |<---- R2 {id, A, ZKP} ------------------|
      |----- R2 {id, A, ZKP} ----------------->|
      |                                        |
      | K = calculateKeyingMaterial()          | K = calculateKeyingMaterial()
      |                                        |
      |<---- R3 tag (responder direction) -----|
      |----- R3 tag (initiator direction) ---->|
      |                                        |
      | human confirms intended peer           | human confirms intended peer
      |========== authenticated session =================|
```

### A.2 Wrong code

```text
 Initiator                                  Responder
    |----- R1 / R2 exchanged normally ------------>|   (no password check in rounds 1-2)
    | K_init                                       | K_resp      <-- K_init != K_resp
    |<---- R3 tag ---------------------------------|
    | validate -> FAILS                            |
    |----- typed failure, channel closed --------->|
    |  attempt recorded, cooldown applied          |
```

### A.3 Active two-leg MITM

```text
 Initiator            MITM              Responder
    |== TLS leg 1 ==|== TLS leg 2 ==|
    |-- R1 -------->|               |
    |               |-- R1' ------->|      different transcript per leg
    |-- R2 -------->|-- R2' ------->|
    | K_A           |  K_M1 | K_M2  |        K_R
    |               |               |
    |  channel binding differs per leg, so the bound confirmation cannot be
    |  satisfied on both legs without the code. Neither leg authenticates.
    |<-- fail ------|-- fail ------>|
```

### A.4 Replay

```text
 Attacker                Victim peer
    |--- captured R1 (old session) --->|  accepted by the library (self-contained)
    |                                  |  framing binds the session id -> rejected
    |--- captured R2 (old session) --->|  participant id mismatch -> rejected
    |--- captured R3 tag ------------->|  reversed-direction recompute -> rejected
```

### A.5 Cancellation

```text
 either peer, between any two rounds
    |--- cancel --->  participant abandoned
                      no keying material derivable
                      permit released exactly once
                      socket closed exactly once
                      no authority issued
```

### A.6 Code expiry

```text
 Initiator                                  Responder
    | display code, start bounded timer      |
    |                                        |  ...no entry within the lifetime...
    | code invalidated                       | entry refused: typed expiry
    |                                        |
    | fresh discovery required; new code generated; attempt counter reset only by
    | a new pairing, never by re-displaying the same code
```
