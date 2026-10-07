# Milestone 4 Part B — authenticated LAN control-session foundation

Part B upgrades only an explicitly selected, already established Part A LAN control session.
It provides a bounded TLS 1.3 pairing/authentication seam and encrypted **control records**;
it does not provide file transfer, a user-facing pairing flow, trusted-device storage, or a
product-ready LAN feature. `CURRENT_MILESTONE` stays `2`, `TRANSFER_ENGINE` remains gated,
and payload/resume capabilities are absent after authentication.

## Scope and module placement

- `:core-transfer` contains the transport-neutral state reducer, strict fixed-schema hello
  and confirmation codecs, canonical transcript/SAS logic, resource bounds, and the bounded
  record framing/state layer. It imports no Android framework, network socket, TLS provider,
  or cipher implementation.
- `:transport-lan` contains the real Conscrypt TLS engine, ephemeral certificate construction
  and validation, TLS exporter/key setup, socket coordination, exact-session approval seam,
  and lifecycle ownership. Construction is inert; secure pairing runs only after an explicit
  `PairableControlSession.beginSecurePairing` call on an existing selected-peer session.
- No UI, automatic approval, persistent device trust, permanent key, Room write, service,
  worker, notification, wake lock, background discovery, Nearby, or payload API is added.

## Reviewed cryptographic implementation

The implementation uses pinned `org.conscrypt:conscrypt-android:2.7.0` for TLS 1.3,
exporter material, and AES/GCM, and `org.bouncycastle:bcpkix-jdk18on:1.86` for certificate
construction and API-23-safe signature verification. The provider is selected on each
pairing `SSLContext`/`Cipher`; the process-wide JCA provider registry is not mutated. If the
provider, protocol, exporter, cipher suite, certificate validation, or AEAD operation is
unavailable, the operation fails closed. There is no plaintext or weaker-protocol fallback.

Each endpoint creates a short-lived P-256 ECDSA self-signed certificate and requires mutual
TLS client authentication. The trust manager accepts only the expected single-certificate,
self-signed, P-256 ECDSA form with digital-signature usage, valid dates and a verified
signature. The peer certificate is not treated as a durable identity: both hello messages
must bind its SHA-256 fingerprint to the observed TLS certificate, and the peers compare a
short authentication string before approval. TLS 1.3 is the only enabled protocol; only
TLS 1.3 suites from the explicit supported list are accepted. Session tickets are disabled.

Dependency/license governance checks the locked runtime artifacts and versions, transitive
Bouncy Castle modules, API-23 emulator gate, license texts and Conscrypt NOTICE. The
third-party license assets contain the Bouncy Castle MIT-style license and Conscrypt's
Apache attribution and complete referenced Apache 2.0 terms.

## Pairing transcript, proof and approval

The bounded `MPS1` hello is sent only inside the mutually authenticated TLS channel. It has a
fixed field order and no extensible/optional fields. It binds the secure-session id, existing
control-attempt id, sender/target peer-instance ids, role, selected protocol and both
advertised protocol ranges, app version, discovery/control/secure/capability wire versions,
LAN transport, security-suite id, selected feature mask, record/count/byte/lifetime limits,
32-byte fresh nonce, and 32-byte TLS certificate fingerprint. Unknown versions, roles,
message types, suites, feature bits, changed limits, bad lengths, truncation, and trailing
bytes are rejected. The initiator's secure-session id is echoed exactly by the responder.

Both sides encode the same role-ordered canonical transcript (including both hellos, both
nonces/fingerprints, versions/ranges/capabilities/limits and negotiated TLS protocol/cipher
suite) and compute SHA-256. A role-specific TLS exporter label and that transcript digest
produce the pairing proof and independent client-to-server/server-to-client AES-128-GCM
keys and 96-bit nonce bases. The six-digit, zero-padded SAS is the first 19 exporter bits;
it is for human comparison only, never logged, stored, returned as a diagnostic, or accepted
automatically. The short-lived approval request contains only the two session identifiers,
peer identifiers, the SAS, an opaque in-memory request handle and monotonic expiry. The
handle is object-identity and secret-byte bound to that exact request. A stale handle cannot
approve another request; repeating the same in-flight decision is idempotent; approve and
reject conflict. Cancellation, rejection, timeout, failure, expiry and success clear the
SAS, handle bytes, transcript digest and temporary exporter buffers. Secrets are not put in
Room.

Authentication is a conjunction, not a state label: local explicit approval, a successful
local role/direction-specific encrypted key-confirmation record, and a peer confirmation
whose AEAD tag, role and exact transcript digest all verify. Only then can the reducer issue
`ApprovedSecureSession`. The pure reducer does not parse or authenticate raw network bytes;
the LAN coordinator calls it only after the record layer has decoded the first AEAD record.

## Bounded `MSR1` control records

Every record has a 33-byte fixed header: magic (`MSR1`), wire version, 128-bit secure-session
id, direction, unsigned 64-bit strictly increasing sequence, mandatory record type, and
plaintext length. The entire header is AES-GCM associated data; ciphertext and the 16-byte
tag follow it. Direction-specific exporter keys and nonce bases prevent cross-direction
key/nonce reuse. The first record in each direction must be `KEY_CONFIRMATION`; later
confirmation records are rejected. Replay, reflection, sequence gaps, wrong session or
direction, unknown version/type, inconsistent length, truncation, tag failure and tampering
close the layer and destroy both directional keys.

Part B record types are only `KEY_CONFIRMATION`, `PING`, `PONG`, and `SESSION_CLOSE`—there
is no file, chunk, metadata-transfer, or payload record. After authentication, the explicit
`PairableControlSession` seam can send/receive only the empty `PING`, `PONG`, and
`SESSION_CLOSE` controls through the installed AEAD layer. Calls before authentication are
refused; there is no plaintext fallback or background reader. Record reads have a five-second
monotonic deadline, and malformed/truncated/slow records close the session.

Bounds are 4,096 plaintext bytes per record, 4,096 records per direction, 16 MiB plaintext
per direction, and five minutes per secure session. Approval expires after one minute;
TLS/control handshake I/O is bounded by five seconds. Sequence values never wrap: a limit
closes and wipes the session before another nonce could be used. Session close,
discovery-lease close, cancellation, socket failure and expiry release the record keys and
TLS socket.

## Verification requirements

The test suite pins a canonical transcript SHA-256 vector, SAS/exporter bit vector, NIST
AES-128-GCM known-answer vector, malformed/downgrade transcript cases, explicit-approval
state transitions, mutual confirmation, and replay/reflection/gap/tamper/expiry/sequence
limits. A loopback test exercises the real TLS exporter, explicit test-only approval seam,
key confirmation and control-only negotiated result. Android instrumentation exercises the
real bundled Conscrypt handshake, exporter and AES-GCM implementation on API 23. Hosted CI
must pass on the exact pushed SHA, including JVM tests, dependency/license governance, Room
schema immutability, lint with zero errors, debug and instrumentation APK packaging, and the
API-23 emulator test. Local Java/Android toolchain absence is not test evidence.

## Audit references

- [Conscrypt 2.7.0 signed release](https://github.com/google/conscrypt/releases/tag/2.7.0),
  [versioned capabilities](https://github.com/google/conscrypt/blob/2.7.0/CAPABILITIES.md),
  and [versioned source](https://github.com/google/conscrypt/tree/2.7.0): TLS 1.3,
  supported TLS 1.3 suites and exporter/provider APIs. The dependency is bundled because
  Android's platform Conscrypt does not provide a consistent TLS 1.3/exporter path down to
  the repository's API 23 minimum; CI exercises the bundled implementation on API 23.
- [Bouncy Castle Java 1.86 release](https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/)
  and [Java artifact catalog](https://www.bouncycastle.org/java.html): the exact PKIX
  helper version and published `jdk18on` artifacts. Bouncy Castle is used only for
  certificate construction/signature verification, not for custom key agreement or record
  encryption.
- [TLS 1.3, RFC 8446](https://www.rfc-editor.org/rfc/rfc8446.html), especially §5.3
  (per-record nonces), §5.5 (key-usage limits) and §7.5 (exporters): standard record nonce
  and exporter semantics. The protocol derives distinct application values with separate
  exporter labels and the canonical transcript digest as nonempty context; it closes before
  its stricter record/byte/lifetime ceilings rather than attempting a custom rekey.
- [NIST SP 800-38D](https://doi.org/10.6028/NIST.SP.800-38D): AES-GCM known-answer
  vector source used by the core record-layer test.
- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) and the
  [Bouncy Castle license](https://www.bouncycastle.org/licence.html): compatible license
  terms mirrored in the app's third-party-license assets; Conscrypt's NOTICE and its
  Netty/Harmony attributions are preserved there as well.

The repository audit also confirmed the Part A control session is unauthenticated,
correlation ids and LAN addresses are not trust anchors, and its negotiator removes payload,
resume and secure-session claims. The Part B wiring leaves discovery, Room v1/v2, readiness,
and transfer-engine gates unchanged. The required *ordering* of this audit before the first
cryptography edit was missed; that process deviation is recorded in `doc/AI_HANDOFF.md` and
cannot be retroactively cured by this late audit.

Until exact-SHA CI evidence is green, Part B remains incomplete.
