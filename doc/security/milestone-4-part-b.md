# Milestone 4 Part B — authenticated LAN control-session foundation

Part B upgrades only an explicitly selected, already established Part A LAN control session.
It provides a bounded TLS 1.3 pairing/authentication seam and encrypted **control records**;
it does not provide file transfer, a user-facing pairing flow, trusted-device storage, or a
product-ready LAN feature. `CURRENT_MILESTONE` stays `2`, `TRANSFER_ENGINE` remains gated,
and payload/resume capabilities are absent after authentication.

**Status of the atomic switch (2026-10-23).** The live pairing path is the entered-code PAKE. The
coordinator offers only `JPAKE_MODP3072_ENTERED_CODE_V1`, selects with `selectRequired`, builds the
MST2 transcript and calls the composed `PakeSessionCoordinator`; the short-authentication-string
comparison is retired and cannot be generated in this build. The construction is **BC J-PAKE with
`JPAKEPrimeOrderGroups.NIST_3072`**.

What that sentence does and does not mean. It means no live code path offers the retired comparison,
generates a proof for it, or approves one: `HumanVerificationCode.fromExporterMaterial` is deleted,
the comparison harness is deleted, and `tools/verify/jpake-production-boundary.mjs` fails if either
returns or if the coordinator or provider names them. The SAS *types* survive only because the
control-session listener record still names them, and nothing in this build can construct one. It
does not mean the composition is reviewed, audited or proven: no independent cryptographic review has
taken place, the owner waived that gate for this personal experimental app on 2026-10-19, and the
waiver permits implementation while establishing no security. The authenticated control authority is
control-only and the payload gate still refuses every session unconditionally.

The sections below were written while the SAS path was still live. They are kept as the record of
that state, and the claims in them that SAS runs in production are historical.

**Status of the J-PAKE replacement (2026-10-21).** Experimental J-PAKE/MPS3 code now exists in
production source: suite negotiation (`PairingAuthenticationPolicy`), the MPS3 codec
(`JpakeMessageCodec`), channel-bound identifiers (`JpakeBinding`) and the entered-code contract in
`:core-transfer`, plus a MODP round adapter (`ModpJpakeSession`, `ModpJpakeFrames`) in
`:transport-lan`. **None of it is wired.** No coordinator, state machine, approval or payload path
can reach it, the SAS production path is still in place, and the payload gate still refuses
unconditionally. (Superseded on 2026-10-23: the coordinator now calls this path and the comparison is
retired. See the switch status at the top of this document.)

The owner waived the external-review gate for this personal experimental application on 2026-10-19
([`owner-risk-waiver-2026-10-19.md`](owner-risk-waiver-2026-10-19.md)). The composition is
experimental, project-defined and **not independently cryptographically reviewed**; the waiver
permits implementation and establishes no security. The open requirements recorded in
[`jpake-review-errata-2026-10-19.md`](jpake-review-errata-2026-10-19.md) remain AI-generated
technical input and **not** an approval. That document's protocol-status correction supersedes the
`MPS1`/`MST1` statements previously carried here. Part B is **incomplete**.

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

The bounded `MPS2` hello is sent only inside the mutually authenticated TLS channel. It is a
length-prefixed, canonical tag-value frame with a closed tag space: each peer sends an *offer*
carrying the secure-session id, existing control-attempt id, sender/target peer-instance ids,
role, protocol range, app version, wire versions, LAN transport, security-suite id, its
offered features, chunk size, resume support, encryption mode and authentication mode,
record/count/byte/lifetime limits, 32-byte fresh nonce, 32-byte TLS certificate fingerprint,
and extension entries, together with an explicit presence mask that distinguishes "absent"
from "present and zero". Duplicate tags, unknown mandatory tags, trailing bytes, bad lengths,
truncation, unknown versions, roles, suites and feature bits are rejected. The initiator's
secure-session id is echoed exactly by the responder.

Both sides derive the selection deterministically from the two offers
(`SecurePairingSelectionV2`), then encode the same role-ordered canonical `MST2` transcript —
both offers, both presence masks, the derived selection, both nonces and certificate
fingerprints, versions, capabilities, limits, extensions, and negotiated TLS protocol and
cipher suite — with fields in ascending tag order, and compute SHA-256. Because both offers
and both presence masks are bound, a field either peer silently dropped changes the digest.
An `MPS1` peer is refused with `LEGACY_MPS1_UNSUPPORTED`; there is no downgrade and no
plaintext retry. A role-specific TLS exporter label and that transcript digest produce the
pairing proof and independent client-to-server/server-to-client AES-128-GCM keys and 96-bit
nonce bases. The SAS is five symbols from the 32-symbol alphabet
`23456789ABCDEFGHJKLMNPQRSTUVWXYZ`, drawn from the first 25 exporter bits; it is for human
comparison only, never logged, stored, returned as a diagnostic, or accepted automatically.

The alphabet omits `0`/`O` and `1`/`I` so no two symbols can be confused when read aloud or
transcribed. Five symbols of five bits give exactly **25 bits of comparison entropy**, and
that is the entire security value of the human step: an attacker who completes the TLS
handshake and simply guesses the code succeeds with probability 2^-25 per attempt. The
previous six-digit decimal form carried 19 bits, not 20 — 10^6 < 2^20 — and decimal rendering
wasted roughly 0.8 bits per symbol while forcing the reader to distinguish 0/O and 1/I. The short-lived approval request contains only the two session identifiers,
peer identifiers, the SAS, an opaque in-memory request handle and monotonic expiry. The
handle is object-identity and secret-byte bound to that exact request. A stale handle cannot
approve another request; repeating the same in-flight decision is idempotent; approve and
reject conflict. Cancellation, rejection, timeout, failure, expiry and success clear the
SAS, handle bytes, transcript digest and temporary exporter buffers. Secrets are not put in
Room.

Authentication is a conjunction, not a state label: local explicit approval, a successful
local role/direction-specific encrypted key-confirmation record, and a peer confirmation
whose AEAD tag, role and exact transcript digest all verify. The pure reducer does not parse
or authenticate raw network bytes; the LAN coordinator calls it only after the record layer
has decoded the first AEAD record.

Reaching that conjunction produces **no security marker of any kind**. `ApprovedSecureSession`
has been deleted, `SecurePairingResult.Authenticated` is a status-only object that confers
nothing, and `NegotiatedSession` now refuses to carry a secure-session or TLS capability
claim. Post-pairing authority is the opaque `SecureLanControlChannel`, which is `internal` to
`:transport-lan` and can only come into existence after the protected write and the
authenticated peer confirmation actually happened. `PayloadTransferGate.evaluate` returns
`Refused` unconditionally and `PayloadTransferDecision.Authorized` no longer exists, so no
combination of session state, negotiated features or caller behaviour can enable payload
transfer. The pairing reducer is itself `internal` to `:transport-lan`; no other module can
construct one. `tools/verify/secure-authority-surface.mjs` enforces all of this in CI, and
was mutation-tested so that re-opening the boundary fails the check.

*Correction (2026-10-19).* Earlier revisions of the section above stated that the live hello
and transcript were `MPS1`/`MST1`. That was accurate before the M3 remediation and is not
accurate now; the description above is the MPS2/MST2 pair the code actually implements, as
confirmed by `SecurePairingHelloV2Codec`, `SecurePairingSelectionV2`, `SecurePairingTranscriptV2`
and the group-12 guard. See
[`jpake-review-errata-2026-10-19.md`](jpake-review-errata-2026-10-19.md) §1.

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
per-read inactivity timeout, and malformed/truncated records close the session. A slow-drip peer
that keeps producing bytes inside that window is **not** bounded by it; the pairing phases are
bounded by the total deadlines described above, and long-lived post-authentication record I/O is
not yet.

Bounds are 4,096 plaintext bytes per record, 4,096 records per direction, 16 MiB plaintext
per direction, and five minutes per secure session. Approval expires after one minute;
TLS/control handshake I/O is bounded by five seconds. Sequence values never wrap: a limit
closes and wipes the session before another nonce could be used. Session close,
discovery-lease close, cancellation, socket failure and expiry release the record keys and
TLS socket.

## Remediation status

This section exists because "the class exists" and "the production path uses it" are different
claims, and the difference is the whole point of a security remediation.

| Item | State |
| --- | --- |
| M1 — 25-bit base-32 SAS | Implemented. Pinned by `HumanVerificationCodeTest` hand-derived vectors and by the governance verifier. |
| M1 — attempt limiter | Implemented and wired: `LanPeerDiscoveryProvider` owns one `SecurePairingAttemptLimiter` per provider and passes it to every pairing, keyed on the peer instance id and the peer address. |
| M2 — capability trust boundary | Implemented and enforced in CI by `tools/verify/secure-authority-surface.mjs`. |
| M3 — canonical transcript v2 | **Implemented.** The coordinator encodes with `SecurePairingHelloV2Codec`, decodes the V2 header and body, derives the selection with `SecurePairingSelectionV2`, and hashes the `MST2` transcript with `SecurePairingTranscriptV2`. `tools/verify/secure-authority-surface.mjs` group 12 pins it and is mutation-tested; an `MPS1` frame is refused as `LEGACY_MPS1_UNSUPPORTED`. Test breadth is still incomplete: cross-session substitution, certificate substitution, extension ordering and downgrade are asserted at codec level, and the pre-M3 claim in `doc/AI_HANDOFF.md` is marked superseded there. |
| M4 — total monotonic deadlines | Implemented and wired into `SecureLanPairingCoordinator`: TLS handshake, pairing hello exchange, mutual key-confirmation exchange and bounded close each run under one total deadline. |
| L1 — dependency artifact-byte verification | Implemented for the crypto artifacts: reviewed SHA-256 manifest in `gradle/secure-artifact-checksums.txt`, verified in CI over the bytes Gradle resolved. Not a whole-graph lockfile. |
| Formal external protocol review | **Absent. This is an acceptance blocker.** |

Three consequences deserve to be stated plainly rather than left to the table.

**What the total deadlines do and do not cover.** `SecureLanPairingCoordinator` now arms one
`MonotonicSocketDeadline` per blocking phase: `tls-handshake` (15 s), `pairing-hello` (10 s),
`key-confirmation` (15 s) and `bounded-close` (2 s). Expiry abortively closes the socket that
phase owns, which is what releases a worker parked in `read`/`write`; a phase that appeared to
succeed at the instant its deadline fired is reported as a failure, because the session it
produced is already dead. The approval wait is bounded separately, by the approval request's own
monotonic expiry with `APPROVAL_DEADLINE_MILLIS` as the outer bound, because it blocks on a latch
rather than a socket. The per-read `soTimeout` and `CONTROL_RECORD_IO_TIMEOUT_MILLIS` remain as
additional defence, not as the bound.

**What is still not covered by a total deadline.** Post-authentication secure record I/O on an
established session still relies on the per-read inactivity timeout. The slow-drip exposure
described in the audit is therefore closed for pairing, and **not yet closed for a long-lived
authenticated session**.

**The attempt limiter is a throttle, not authentication.** All of its state is process-local, so
it resets when the process dies or the discovery lease ends, and a peer that changes source
address resets the per-source counters. Its limits must never be described as a substitute for
the human SAS comparison or for reviewed protocol design.

**M1 is not resolved by hardening the SAS.** The pairing protocol remains a project-defined
composition. Raising the comparison entropy from 19 to 25 bits and adding a throttle does not
make it a reviewed standard, and no formal external cryptographic or protocol review has taken
place. Assessment of maintained alternatives (UKEY2, Noise implementations) did not establish a
suitable complete replacement: UKEY2's own README calls it "not an officially supported Google
product", its protocol documentation was last updated around November 2021, and neither a public
handshake conformance-vector corpus nor a complete application-level approval/attempt/fallback
policy could be established from inspection. That assessment is preliminary and is not a
protocol review either way. **Acceptance of Part B requires independent review.**

## Dependency bytes

**Status: partial, and not strict Gradle dependency verification.** A post-resolution cache scan
of four binary artifacts is a useful check. It is not what `verification-metadata.xml` does, and
calling it dependency verification would overstate it. Each audit question, answered from what the
build actually does:

**Is verification applied before artifacts are used by compilation?** It was not. The byte check
ran after `JVM unit tests`, so Conscrypt and Bouncy Castle were compiled and linked into the test
run before their bytes were inspected — a substitution would already have happened by the time it
was reported. There is now an early step that runs
`:transport-lan:downloadDebugRuntimeArtifacts` to materialize the artifacts without compiling,
then verifies them, before the unit tests run. The post-test step is kept as a re-check.

**Is Gradle dependency verification in strict mode enabled?** No. There is no
`gradle/verification-metadata.xml` and `dependencyLocking` is not configured. Enabling strict
verification is not a partial measure: Gradle verifies per component and fails on any component
that is not listed, so turning it on requires reviewed digests for the entire resolved graph —
every AndroidX, Kotlin and Gradle transitive — not just the four crypto artifacts. Those digests
would have to be produced by `--write-verification-metadata` and then reviewed, and generating
them wholesale and committing the output is exactly the "do not blindly trust metadata
generation" failure mode. This is a real remaining gap, not a stylistic preference.

**Are POM/module metadata checksums verified?** No, and a cache scan cannot verify them. Gradle
keeps dependency metadata in its own binary store; it resolves AARs and JARs into
`caches/modules-2/files-2.1/<group path>/<name>/<version>/<hash>/` but does not materialize the
`.pom` files there. That was measured, not assumed — CI reported all four POM entries as
unresolved while every binary matched (run 37989038083).

**Are dependency versions locked?** No. Zero `*.lockfile` files. Versions are pinned by
coordinate in `gradle/libs.versions.toml` and the selected graph is checked after resolution.

**Are all debug, release, JVM and instrumentation graphs covered?** No. Only
`:transport-lan`'s `debugRuntimeClasspath`. Release, unit-test runtime and androidTest runtime
are not covered by either the coordinate check or the byte check.

**Are the native Conscrypt contents covered by the AAR digest?** The digest covers the AAR as
delivered, including its `jni/` entries, so a substituted native library inside the published AAR
changes the digest. The `.so` files extracted into the build directory afterwards are not
re-verified.

**Can a changed metadata file alter dependency selection while all four binary checks still
pass?** Yes. This is the sharpest remaining hole. The byte check sees only files that were
resolved; a metadata change that selects a *different* artifact changes which files exist rather
than corrupting the ones that were expected, and the manifest would simply report the expected
artifact as unresolved — which it does fail on, but for the wrong reason and only for the four
listed coordinates. Nothing verifies metadata integrity.

**Can an additional unapproved transitive dependency enter the graph?** For the crypto
coordinates, no: `secure-dependency-governance.mjs` asserts the resolved Conscrypt runtime is
*exactly* `conscrypt-android:2.7.0` and the Bouncy Castle graph is *exactly* the three 1.86
artifacts. For everything else in the graph, yes — there is no whole-graph constraint.

### What the byte check does establish

`gradle/secure-artifact-checksums.txt` holds reviewed SHA-256 digests for the Conscrypt 2.7.0 AAR
and the `bcpkix`/`bcprov`/`bcutil` 1.86 JARs. `tools/verify/secure-artifact-bytes.mjs` locates
each one in the module cache, recomputes SHA-256 and compares. A substituted, truncated or
corrupted binary fails the build rather than being compiled into an APK, and any change to a
digest is a one-line reviewable diff. The digests were re-fetched from
`repo1.maven.org/maven2/.../*.sha256` and matched the manifest exactly.

Two limits. *Scope:* four binaries, not the graph. *Provenance:* the expected digests come from
Maven Central, which is also where Gradle downloads from, so a match proves cache integrity and
pins the bytes for review. It does not prove Conscrypt or Bouncy Castle signed them; that needs
signature verification against the vendors' keys, which this repository does not perform.

### Coordinate pinning, byte verification, locking, licensing

These are four different claims and only the first two are true here. **Coordinate pinning:** yes,
`libs.versions.toml` pins both libraries and the governance verifier fails on drift.
**Artifact-byte verification:** yes, scoped to four reviewed crypto binaries. **Dependency
locking:** no. **License attribution:** yes — the third-party assets carry the Bouncy Castle
MIT-style licence and Conscrypt's Apache attribution with the complete referenced Apache 2.0
terms, and the governance verifier checks the NOTICE attributions. Attribution is a licence
obligation and says nothing about integrity.

## Verification requirements

The test suite pins a canonical transcript SHA-256 vector, SAS/exporter bit vector, NIST
AES-128-GCM known-answer vector, malformed/downgrade transcript cases, explicit-approval
state transitions, mutual confirmation, and replay/reflection/gap/tamper/expiry/sequence
limits. API 23 Android instrumentation includes a loopback, end-to-end pairing test using the
real bundled TLS exporter, the test-only explicit-approval seam, mutual key confirmation and
control-only PING/PONG/CLOSE records; a separate provider test checks exporter agreement and
AEAD tamper rejection. Hosted CI must pass on the exact pushed SHA, including JVM tests,
dependency/license governance, Room schema immutability, lint with zero errors, debug and
instrumentation APK packaging, and the API-23 emulator tests. Local Java/Android toolchain
absence is not test evidence.

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


## Dependency locking and task-graph enforcement (item D)

Four distinct guarantees are in play here, and they are worth separating because each has a
different strength and a different failure mode.

| Layer | File | What it proves |
| --- | --- | --- |
| Coordinate pinning | `gradle/libs.versions.toml` | The declared versions are exact. No dynamic versions, no ranges. |
| Exact graph locking | `gradle/security-dependency-lock.json` | The resolved crypto graph is exactly these four components at these versions, per configuration, with role, packaging, file name, byte size and SHA-256. |
| Binary byte verification | `gradle/secure-artifact-checksums.txt` and the lock's `sha256` fields | The bytes Gradle actually resolved are the bytes that were reviewed. |
| Task-graph enforcement | `verifySecureDependencyLock` in `build.gradle.kts` | Verification happens before any compiler, test runner, lint analysis, dexer or packager consumes the artifacts, on any invocation, not just in CI step order. |
| License attribution | third-party license and notice files | Attribution for the pinned versions. |

### Locked graph

| Component | Version | Role | Packaging | Bytes |
| --- | --- | --- | --- | --- |
| `org.conscrypt:conscrypt-android` | 2.7.0 | direct | aar | 4,927,898 |
| `org.bouncycastle:bcpkix-jdk18on` | 1.86 | direct | jar | 1,341,621 |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 | transitive | jar | 7,224,011 |
| `org.bouncycastle:bcutil-jdk18on` | 1.86 | transitive | jar | 789,287 |

### Configurations covered

`transport-lan`: `debugCompileClasspath`, `debugRuntimeClasspath`, `releaseCompileClasspath`,
`releaseRuntimeClasspath`, `debugUnitTestRuntimeClasspath`,
`releaseUnitTestRuntimeClasspath`, `debugAndroidTestRuntimeClasspath`.
`app`: `debugRuntimeClasspath`, `debugAndroidTestRuntimeClasspath`.

Reports are resolved per project and compared per project, so a configuration is checked
against its own project's graph.

### Tasks protected

`verifySecureDependencyLock` is a prerequisite of Kotlin compilation, Java compilation, unit
tests and Android tests by typed task matching, and of lint, dex, packaging, assembly,
bundling and connected-test tasks by name prefix, because AGP exposes no stable public type
for those. It is registered in `transport-lan` and `app`, the projects that consume and
package the crypto artifacts.

### Residual provenance limitation

Publisher metadata is not byte-verified, and this is unchanged by item D. Gradle keeps POM
and module metadata in its own binary store and does not materialize `.pom` files under
`caches/modules-2/files-2.1`; run 37989038083 confirmed that empirically, with every POM
lookup reporting "not found" while all four binaries matched.

So a metadata change that resolves this exact approved graph and these exact binary bytes
would not be caught. What cannot happen unnoticed is anything that changes the graph or the
bytes: an added, removed, re-versioned, substituted, re-packaged or re-named component
changes the resolved graph or the file identity, and a substituted file changes size or
SHA-256. `tools/verify/security-dependency-lock.mjs --self-test` carries a deterministic
mutation fixture for each of those cases.

This mechanism is security-scoped. It is not Gradle's global strict dependency verification,
and there is no `gradle/verification-metadata.xml`: generating a whole-repository
verification file and calling it reviewed would be worse than the scoped lock, because the
review that gives the lock its value would not have happened.


### A fifth crypto artifact, found by the enforcement itself

Run `38702916423` compiled the build script, ran the wired verification task, and failed with:

```
Execution failed for task ':transport-lan:verifySecureDependencyLock'
> security dependency lock verification failed:
    conscrypt-openjdk-uber-2.5.2.jar resolved from a crypto group but has no lock entry
```

`org.conscrypt:conscrypt-openjdk-uber:2.5.2` is a transitive of `libs.robolectric`, which
`transport-lan` declares `testImplementation`. It reaches the JVM unit-test classpath and no APK.
It was absent from the first version of the lock, and the task graph caught it before any
compilation step ran. That is the mechanism working, not a defect in it, and the same run reported
no size or digest mismatch for the four production artifacts, which confirms those locked values
against real resolved bytes.

The component is now in the lock with `role: "test-only"`, `optional: true` and
`allowedIn: ["UnitTest"]`, so it may appear on a unit-test classpath and nowhere else, and its
absence from `app`'s configurations is not an error.

**It was first recorded with `sha256: null` on the grounds that Maven Central publishes no
`.sha256` for it. That was the wrong conclusion and it has been corrected: the absence of a
published SHA-256 sidecar does not make an artifact unhashable. Every locked binary now carries a
digest, and the verifier rejects a null one. See the provenance section below for how this
digest was established and what it does and does not prove.


### One configuration resolves no crypto at all

Run `38713963400` reported:

```
FAIL every locked configuration resolves to the approved graph --
configuration app:debugAndroidTestRuntimeClasspath resolved no crypto component
```

The resolved report for `:app:debugAndroidTestRuntimeClasspath` contains **zero** `org.conscrypt`
or `org.bouncycastle` modules, even though `app` reaches `transport-lan` through
`implementation` (`app/build.gradle.kts:170`) and `:app:debugRuntimeClasspath` does carry all four
production artifacts. The Gradle verification task resolves this same configuration through its
artifact view and passed, so both mechanisms independently agree the crypto is absent.

That configuration is now declared `expect: "none"` with a note. **Emptiness is the assertion, not
a skipped check**: `verifyGraph` fails if any crypto component ever appears there, so the
configuration is still verified rather than exempted. The eight remaining configurations still
require the full graph.

Why the androidTest report resolves none while the runtime classpath resolves four is **not
established**. The raw report is not retrievable after the run, so the mechanism cannot be
confirmed here. It is recorded as an open question in the lock note rather than explained away.
The practical exposure is nil either way: the API 23 instrumentation suite runs from
`transport-lan`'s androidTest APK, which is covered by
`transport-lan:debugAndroidTestRuntimeClasspath` at `expect: "full"`.

### Diagnostic changes made while finding this

Three CI defects were fixed to make these failures visible at all, each one costing a run to
discover because the job log is not retrievable through the API afterwards:

- The secure lock verifier step emitted no annotation on failure. GitHub Actions runs `run:`
  blocks with `set -eo pipefail`, so the failing `node ... | tee ...` pipeline aborted the script
  before the annotation code. `errexit` is now suspended across the pipeline and the real status
  is captured from `PIPESTATUS`.
- The ordering proof reported only line numbers. It now names the matched task and prints up to
  14 neighbouring plan lines.
- The ordering proof's consumer regex used a bare `compile` prefix, which matched
  `:app:compileDebugNavigationResources` — an AGP resource task that is neither `KotlinCompile`
  nor `JavaCompile`, consumes no dependency classpath, and was never gated by the wiring. The
  regex now names Kotlin and Java compilation explicitly, so it matches the gated set instead of
  being stricter than it. With that fixed, run `38712120643` proved the ordering for both
  projects: `transport-lan` verification planned at line 21 with first consumer at 22, `app` at
  143 and 144, 328 tasks planned.

### A race in the A3 harness, fixed

Run `38705203819` failed API 23 with
`four peers produced 3 submissions, expected 4 expected:<4> but was:<3>`. This was a genuine
defect, not a flake. Acquisition and submission are two separate `CountingObserver`
notifications, but `fourAcquired` is a latch on acquisitions only; it releases as soon as the
fourth permit is held, which can precede the fourth submission notification. Both that site and
the identical shape in the mixed-saturation case now wait on the submission count itself through
the existing bounded `awaitTrue` with `SUBMISSION_TIMEOUT_SECONDS`. No timeout value changed and
no production code changed. Run `38708167687` passed API 23 with `cleanup_completed`.


## Provenance and verification layers, described separately

These are distinct guarantees with distinct strengths. They are listed separately because
conflating them is how a null digest survived a review: "the coordinate is pinned" was silently
doing the work of "the bytes are verified".

**1. Coordinate pinning.** `gradle/libs.versions.toml` pins `conscrypt = "2.7.0"` and
`bouncyCastle = "1.86"`; `transport-lan/build.gradle.kts` declares
`org.conscrypt:conscrypt-android` and `org.bouncycastle:bcpkix-jdk18on`. `settings.gradle.kts`
uses `RepositoriesMode.FAIL_ON_PROJECT_REPOS` with `google()` and `mavenCentral()` only. This
stops version drift and repository injection. It does not stop a substituted artifact at the
same coordinate.

**2. Exact graph lock.** `gradle/security-dependency-lock.json` names every component in the
crypto graph with its group, artifact, version, direct/transitive/test-only role, packaging,
filename, size and digest, and lists the nine configurations each must resolve. Extra
components, missing components, version substitution, module substitution, packaging change and
filename change all fail. Group membership alone is never trusted.

**3. Binary-byte lock.** All five binaries are digest-locked. Measured values from run
`38766118011`, which hashed the bytes Gradle actually resolved:

| artifact | bytes | SHA-256 |
| --- | --- | --- |
| `conscrypt-android-2.7.0.aar` | 4,927,898 | `86072ce711795e188e1e82214d023789d2983fafe342bcae83f046125cf64e7f` |
| `bcpkix-jdk18on-1.86.jar` | 1,341,621 | `8d8b41a4b149bdae8d331f059a578870954d1e3ff29f8269a9eb3ee19a7e4ef7` |
| `bcprov-jdk18on-1.86.jar` | 7,224,011 | `2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f` |
| `bcutil-jdk18on-1.86.jar` | 789,287 | `1c268e15f785aafb1e4670d8d6e5f856f69475e96c162dadcd1ff9b350e9139f` |
| `conscrypt-openjdk-uber-2.5.2.jar` | 4,534,256 | `eaf537d98e033d0f0451cd1b8cc74e02d7b55ec882da63c88060d806ba89c348` |

The verifier rejects a null or malformed digest on any entry, including test-only ones, and
`verifySecureDependencyLock` throws if a lock entry has no digest at all. There is no
escape hatch: the former `digestAvailability` field is now itself a validation failure.

**4. Signing and checksum provenance.** The CI provenance step fetches every checksum sidecar
Maven Central publishes for each coordinate and compares it against the resolved bytes.
Measured results:

| artifact | published sidecars | matched |
| --- | --- | --- |
| `conscrypt-android-2.7.0.aar` | sha256, sha1, md5 | all three |
| `bcpkix-jdk18on-1.86.jar` | sha256, sha1, md5 | all three |
| `bcprov-jdk18on-1.86.jar` | sha256, sha1, md5 | all three |
| `bcutil-jdk18on-1.86.jar` | sha256, sha1, md5 | all three |
| `conscrypt-openjdk-uber-2.5.2.jar` | sha1, md5 | both |

A published sidecar that does not match is a hard CI failure. Which algorithms are published is
discovered at run time, not recorded from memory.

The four production artifacts therefore have **independently published** SHA-256 values, and
their lock records `digestDerivation: "independently published"`.

`conscrypt-openjdk-uber` has no published SHA-256. Its recorded digest was **computed** over the
bytes Gradle resolved, after those bytes matched both published SHA-1 and published MD5
sidecars, and its lock record says `digestDerivation: "computed"`. Two independent published
algorithms agreeing is real evidence, but it is weaker than a published SHA-256: SHA-1 and MD5
are both collision-broken, so this establishes that the bytes are the ones Maven Central
published, not that they are the bytes Conscrypt's authors built. It is not described as
independently published anywhere.

**No detached signature is verified.** Maven Central publishes `.asc` signatures for these
coordinates and none of them are checked: there is no keyring, no pinned Conscrypt or Bouncy
Castle release-signing fingerprint in this repository, and no keyserver reachable from the build
environment. `signatureVerified: false` is recorded on every component, and the verifier rejects
a record claiming `signatureVerified: true` without a `signingKeyFingerprint`. Provenance
therefore rests on transport from `repo1.maven.org` plus published checksum agreement, not on
cryptographic authorship proof.

**5. Task-graph enforcement.** `verifySecureDependencyLock` is registered in `transport-lan` and
`app` and is a prerequisite of Kotlin compilation, Java compilation and `Test` tasks by type, and
of `lint*`, `dex*`, `package*`, `assemble*`, `bundle*` and `connected*` tasks by name prefix,
because AGP exposes no stable public type for those. Running
`./gradlew :transport-lan:compileDebugKotlin` or `:app:assembleDebug` directly triggers
verification without relying on workflow step order. Run `38712120643` proved the ordering from
a `--dry-run` plan: `transport-lan` verification planned at line 21 with its first consumer at
22, `app` at 143 and 144, 328 tasks planned. The ordering rule now lives in
`planOrderProblems` with fixtures, because shell regexes cannot be tested and one of them
initially mistook `:app:compileDebugNavigationResources` for a compiler.

**6. Tested APK versus instrumentation APK.** `:app` reaches `transport-lan` through
`implementation` (`app/build.gradle.kts:170`), so `:app:debugRuntimeClasspath` carries all four
production artifacts and is locked at `expect: "full"`. `:app:debugAndroidTestRuntimeClasspath`
resolves no crypto at all, and is locked at `expect: "none"`.

That emptiness is the AGP split-APK model rather than a gap. `debugAndroidTestRuntimeClasspath`
describes only the instrumentation APK, whose test classes are loaded into the tested
application's process; the production dependencies are packaged into the separately built tested
APK. Consequences, all enforced rather than assumed:

- `:app:debugRuntimeClasspath` must resolve the full approved graph, or verification fails.
- `assembleDebugAndroidTest` builds **only** the instrumentation APK. Measured in run
  `38770998120`: `:app:packageDebug` is absent from that plan, while `:app:compileDebugKotlin`,
  four `:app` dex tasks and `:app:verifySecureDependencyLock` are present. The tested APK is built
  by `assembleDebug` and the two are combined at install time.
- The tested APK's packaged native libraries are listed by that step, which is where
  `libconscrypt_jni.so` appears.
- The tested APK's compile, dex and package tasks are gated, since `dex*`, `package*` and
  `assemble*` are all in the protected prefix set.
- Adding a crypto dependency directly to `androidTest` makes the `expect: "none"` lock fail. This
  is a fixture, so the emptiness cannot silently become a place to hide an unapproved artifact.
- The tested debug APK must contain `libconscrypt_jni.so` (measured: 4 entries, one per ABI).
  `:app:assembleDebug` must build `:app:packageDebug`, `:app:compileDebugKotlin`,
  `:app:verifySecureDependencyLock` and at least one `:app` dex task, with verification planned
  before every consumer in both `:transport-lan` and `:app`. `:app:assembleDebugAndroidTest` must
  build `:app:compileDebugKotlin`, `:app:verifySecureDependencyLock` and at least one `:app` dex
  task, likewise ordered. Each of these is
  asserted and fails the run, not merely logged: an earlier version emitted them as notices, and
  GitHub's annotation cap silently discarded them.

**7. Metadata bytes are not verified.** POM and Gradle module metadata are not hashed. Gradle
never materialises a `.pom` under `caches/modules-2/files-2.1` (proved in run `37989038083`), so
there is nothing local to hash. Only `conscrypt-android` publishes Gradle module metadata; the
three Bouncy Castle modules and `conscrypt-openjdk-uber` are POM-only.

**8. Residual provenance limitation.** A substituted POM or module file that resolves the same
graph and the same artifact bytes would not be detected, because the metadata is fetched over the
same transport as the artifacts and is never hashed. Combined with the unverified `.asc`
signatures, the honest statement is: this repository proves the artifact bytes match values
Maven Central publishes and that no unapproved component enters the graph. It does **not** prove
authorship, and it does **not** prove the metadata was untampered.

**9. This is not global Gradle dependency verification.** There is no
`gradle/verification-metadata.xml` and no `dependencyLocking`. The mechanism is scoped to the
five crypto components and nine configurations named in the lock. Everything else in the build
resolves without checksum verification. Describing this as strict Gradle dependency verification
would be false.
