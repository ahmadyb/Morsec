# BC 1.86 EC J-PAKE payload reconstruction audit

**Date:** 2026-10-20
**Scope:** `org.bouncycastle:crypto` EC J-PAKE (`org.bouncycastle.crypto.agreement.ecjpake`)
**Question:** does Bouncy Castle 1.86 expose any **public** path to reconstruct an
`ECJPAKERound1Payload` or `ECJPAKERound2Payload` from received bytes?
**Verdict:** **No. The concern is correct and the implementation is blocked on it.**

This audit is a source reading, not an execution. Nothing was compiled or run; there is no JVM
toolchain in this workspace, and no claim is made about BC's arithmetic.

---

## 1. Why the question matters

The androidTest feasibility harness exchanges `ECJPAKERound1Payload` and `ECJPAKERound2Payload`
objects **directly, in memory**. That proves the library computes a shared key on this device. It
does not prove the payloads can be rebuilt after a network round trip, because an in-memory
exchange never crosses the boundary where the problem lives.

The MPS3 work merged in `92ec4fb` encodes and decodes those payloads as bytes. If the decoded bytes
cannot be turned back into payload objects, then the codec terminates in something the participant
will not accept, and the whole layer is unusable regardless of how well its framing tests pass.

---

## 2. The blocking fact

`ECSchnorrZKP` is a `public class` whose only constructor is **package-private**:

```java
// core/src/main/java/org/bouncycastle/crypto/agreement/ecjpake/ECSchnorrZKP.java
public class ECSchnorrZKP                     // line 14
{
    private final ECPoint V;
    private final BigInteger r;

    ECSchnorrZKP(ECPoint V, BigInteger r)     // line 27 -- no modifier
    {
        this.V = V;
        this.r = r;
    }

    public ECPoint getV() { return V; }       // line 33
    public BigInteger getr() { return r; }    // line 38
}
```

The class Javadoc calls it a "Package protected class" while the declaration says `public`; the
comment describes the intent, and the missing modifier on the constructor implements it. BC did not
expose a way to build this value, and no decoder was added.

Both round payloads take it as a constructor parameter, so the public constructors are unreachable
from outside the package:

| Type | Constructor | Reconstructable from bytes? |
|---|---|---|
| `ECJPAKERound1Payload` | `(String, ECPoint, ECPoint, ECSchnorrZKP, ECSchnorrZKP)` — line 49 | **No** |
| `ECJPAKERound2Payload` | `(String, ECPoint, ECSchnorrZKP)` — line 39 | **No** |
| `ECJPAKERound3Payload` | `(String, BigInteger)` — line 34 | Yes |

Round three is fine, and only round three.

---

## 3. Why this search is exhaustive, not merely unlucky

A search for "some other method" can always be wrong. This one is closed by construction:

1. **Package-private means only same-package callers can construct it.** Java access control is
   defined on the package, not on the jar or the module.
2. **That package contains exactly nine files**, all of which were read:
   `ECJPAKECurve`, `ECJPAKECurves`, `ECJPAKEParticipant`, `ECJPAKERound1Payload`,
   `ECJPAKERound2Payload`, `ECJPAKERound3Payload`, `ECJPAKEUtil`, `ECSchnorrZKP`, `package-info`.
   None of them is a codec, parser, factory or serializer.
3. **There is exactly one construction site in the entire package:**
   ```
   ECJPAKEUtil.java:143:  return new ECSchnorrZKP(V, v.subtract(x.multiply(h)).mod(n));
   ```
   inside `calculateZeroKnowledgeProof` (line 126), which derives `V = G*v` from a **fresh random
   `v`** and takes the secret `x` as input. It can only *author* a proof, never *adopt* one.
4. **The package exists in exactly one source root.** The `r1rv86` tree (12,136 entries, complete —
   the API reports `truncated: false`) contains `ecjpake` only under
   `core/src/main/java/org/bouncycastle/crypto/agreement/ecjpake/`. There is no `jdk1.9` or other
   variant that could add a class to the package.

Point 4 is the one that closes the loophole a search cannot: even if a codec existed somewhere
else, it could not construct the proof from outside the package. Nothing else can.

Corroboration: BC's own `misc/src/main/java/org/bouncycastle/crypto/examples/ECJPAKEExample.java`
builds both participants in one process and passes payload objects by reference (lines 81–127). The
published example never serialises a payload either.

---

## 4. Avenues examined and ruled out

| Avenue | Result |
|---|---|
| Alternate constructor or `static` factory on the payloads | None exist. One constructor each, read in full. |
| `ECJPAKEUtil.validateZeroKnowledgeProof(...)` | Public (line 196), but its third parameter **is** an `ECSchnorrZKP` (line 199). It consumes the type; it does not produce one. |
| `ECJPAKEUtil.calculateZeroKnowledgeProof(...)` | Public and returns the type, but binds `V` to a fresh random `v`. To make it emit a *chosen* `V` you would need that `V`'s discrete logarithm. |
| Subclassing `ECSchnorrZKP` | Impossible: a subclass constructor must invoke an accessible super constructor, and the only one is package-private. |
| Feeding a controlled `SecureRandom` to `calculateZeroKnowledgeProof` | Also fails. It lets you pick `v`, which fixes `V = G*v`; it cannot invert a received `V`. |
| Java serialisation / `Externalizable` | Not implemented by `ECSchnorrZKP` or the payloads. |
| JCA / `Provider` wrapper for J-PAKE | None in 1.86. J-PAKE is a lightweight-API-only mechanism. |
| A codec elsewhere in `bcprov` | Impossible per §3: it could not call the constructor. |
| A later BC release | The `master` branch of `bcgit/bc-java` still declares `ECSchnorrZKP(ECPoint V, BigInteger r)` with no modifier. There is no upstream fix to wait for. |

Reflection (`getDeclaredConstructor(...).setAccessible(true)`) would work at runtime on both the JVM
and Android, because the class ships inside the app rather than in the platform. It is not listed as
an approach of its own because it is **strictly worse than the package bridge**: identical coupling
to a non-API constructor, but the coupling is invisible to the compiler, so a BC change fails at
runtime on the pairing path instead of at build time.

---

## 5. Options assessed against this codebase

Message sizes below: the EC figures are **measured** by the API 23 harness on CI
(`round1_bytes=196 round2_bytes=98 round3_bytes=32`). The MODP figures are **arithmetic**, not
measurement: a 3072-bit element is 384 bytes, round one carries six, round two carries three, and
the round-three MAC is 32 bytes whatever the group.

| | 1. Package bridge | 2. MODP J-PAKE (`NIST_3072`) |
|---|---|---|
| Public API only | No | **Yes** |
| New dependency | No | No |
| Wire sizes (r1/r2/r3) | 196 / 98 / 32 B (measured) | 1600 / 800 / 32 B (measured) |
| `MAX_FRAME_BYTES` | stays 512 | must rise to at least 2048 |
| Already-merged work | all retained | framing widths and hostility tests rewritten; binding, code and negotiation retained |
| Failure mode on BC change | **compile error** | none — API is supported |
| Split package | Yes (illegal under JPMS; irrelevant on Android today) | No |
| Performance on API 23 | 682 ms honest run, emulator, measured | measured; see §5a |

Option 3 — a different maintained PAKE — needs its own lock entry, digest, provenance record and
feasibility phase, and it contradicts the current "no new dependency" constraint. It is the only
option that cannot be started without you lifting that constraint.

**Option 1's real risk is narrower than it sounds**, and worth stating fairly: the constructor is
package-private, so a same-package Java file in this repository compiles and links against it, and
any change to that constructor in a future BC **breaks the build** rather than shipping a runtime
failure. The dependency lock pins `bcprov-jdk18on-1.86.jar` byte-for-byte, so the version cannot
drift unnoticed. The objections that remain are principled rather than mechanical: we would be
building on something BC deliberately withheld, and a split package is a smell a reviewer would
raise.

**Option 2's group validation is better than this audit first claimed.** An earlier draft of this
document said the MPS3 decoder would have to range-check every received group element because
`JPAKEUtil.validateGa` (line 286) only rejects the value `1`. That was wrong. `validateGa` is applied
to the *locally computed* `gA`/`gB`, and the *received* elements get a full check inside
`validateZeroKnowledgeProof` (line 302): `gx > 0`, `gx < p`, and `gx^q mod p == 1`. Round one checks
both `gx3` and `gx4` this way; round two checks the received `b` the same way. The EC path's
advantage here is smaller than stated.

The framing layer's remaining obligation is the one that applies either way: a strict fixed width.
`BigIntegers.asUnsignedByteArray(int, BigInteger)` (line 57) throws on overflow but does **not**
reject a negative value — it silently encodes `-1` as `255`. See §5b. So the encoder must range-check
before it calls that helper, which is a stronger statement than errata R-3 makes.

### 5a. What the measurement actually established

Run on the API 23 emulator by `ModpJpakeMeasurementApi23Test`, with the EC harness measured in the
same job so the two are comparable. All eight MODP tests pass.

**Reconstruction works.** Every round was encoded to fixed-width bytes, rebuilt through public
constructors, and validated by a real participant: round one, round two, keying material agreement,
and round three confirmation. This is the operation the EC path cannot perform at all.

**Wire sizes, measured:** round one 1600 B, round two 800 B, round three 32 B — a 326-byte EC
exchange becomes 2432 bytes, 7.5× larger. On a LAN this is irrelevant; the MPS3 frame bound would
need to rise from 512 to at least 2048 bytes.

**Cost, measured on the emulator:**

| | EC P-256 | MODP 3072 |
|---|---|---|
| Honest exchange, median | 682 / 889 / 989 ms across three runs | not captured per-phase; see below |
| Round-one create | ~255 ms | 699 ms |
| Round-one validate | ~213 ms | 1028 ms |
| Four concurrent exchanges | 3.69 s | 27.9 s |

The concurrency ratio is the most robust figure available, because both numbers come from the same
job: **27.9 s / 3.69 s = 7.6×**. Applied to the EC medians, a MODP exchange costs roughly 6.7–7.5 s
on this emulator.

Two honest caveats about that table. First, the per-phase MODP breakdown could not be captured in
full: the API 23 logcat ring buffer is small, and which lines survive varies between runs — one run
kept a single MODP line, another kept none, even though every test passed. The round-one figures and
the concurrency ratio are real measurements; the total is a scaling estimate, and is labelled as
one. Second, an emulator is not a phone: the EC baseline here is already ~0.9 s for something that
should take tens of milliseconds on real hardware. The 7.6× ratio should transfer; the absolute
figure should not be quoted as a device number.

**What this does to the decision.** It removes the argument that MODP is too slow. On real hardware
a 7.6× scaling of a tens-of-milliseconds EC exchange is likely a few hundred milliseconds, which is
invisible inside a flow where a human reads a code from one screen and types it into another. The
remaining cost of MODP is 2.4 KB instead of 326 B per exchange, and a larger frame bound.

### 5b. What the measurement found on the first run: the tag is signed

The first MODP harness run failed three tests, all in round three, while rounds one and two passed.
That was not a test bug.

`JPAKEUtil.calculateMacTag` (line 454) returns `new BigInteger(calculateMacTagEncoded(...))` — the
**signed** two's-complement reading of a 32-byte HMAC output. A tag is therefore negative about half
the time. Encoding it with `BigIntegers.asUnsignedByteArray`, which zero-pads, silently turns a
negative tag into a different positive number, and confirmation then fails.

Bouncy Castle documents the trap in its own `validateMacTag` Javadoc:

> Note calculateMacTag reads the MAC output with the *signed* BigInteger(byte[]) constructor, so a
> MacTag is negative about half the time and the supplied value has to be encoded two's-complement
> and sign-extended to match: BigIntegers.asUnsignedByteArray zero-pads instead, which does not
> round-trip a negative tag.

So the MPS3 round-three field must be specified as **32 sign-extended two's-complement bytes**, and
the adapter must read it back with `BigInteger(byte[])`. `JpakeRoundBody.RoundThree` now says so, and
the codec stores the bytes opaquely, which is why no production behaviour had to change.

This is worth recording for a second reason: it is precisely the defect class that the EC in-memory
harness could never have found. Passing payload objects by reference never crosses a byte boundary,
so the signedness of a value that is never serialised cannot be wrong. The blocking issue and this
defect have the same root cause — the harness never proved what it appeared to prove.

On standards: RFC 8236 describes the finite-field MODP construction. That is a fact about the RFC,
not a claim that this project conforms to it — it does not, and the no-conformance-claim rule stands
whichever option is chosen.

---

## 6. Recommendation, after the measurement

**Option 2 — MODP J-PAKE with `NIST_3072`.** The measurement removed the objection that decided
against it. It runs on API 23 inside the real APK, every round reconstructs from bytes through
public constructors, and the cost is 7.5× the bytes and 7.6× the time — which on a LAN and inside a
human-typed flow is not a cost anyone will notice.

What the project buys: no split package, no reliance on a constructor BC deliberately withheld, no
version coupling, and no verifier needed to pin a constructor signature that exists only by
accident. What it pays: MPS3 element widths change from 33/32 to 384/32, `MAX_FRAME_BYTES` rises
from 512 to at least 2048, the round-three field becomes signed two's-complement, and the
BN-adapter layer replaces the EC adapter. The binding derivation, the entered-code contract and the
suite negotiation from `92ec4fb` are unaffected and stay.

**Option 1 remains a legitimate fallback, and it is less reckless than it first appears.** The
expression-compile argument in §5 is real: a package-private constructor failing to compile is a
build break, not a silent runtime defect, and the dependency lock pins the jar byte-for-byte. If
MODP's cost turns out to matter on real hardware, the bridge is defensible — confined to one named
file, with a verifier that pins the exact constructor signature and fails the build if BC changes
it.

**Option 3 is not recommended.** It is the only option that needs a new dependency, a new lock
entry, a new digest and provenance review, and the measured MODP cost does not justify paying that.

This recommendation is engineering judgement about API stability, message size and measured cost. It
is not a security opinion, and it does not make either variant reviewed.

---

## 7. Status of the implementation

- The coordinator is **not** wired, and will not be until the chosen variant is implemented.
- No production source changed for this audit. The only production edit in the whole measurement
  effort is a KDoc paragraph on `JpakeRoundBody.RoundThree` recording the signedness trap; the
  codec's behaviour is unchanged.
- The merged layer from `92ec4fb` remains correct in isolation — framing, binding, code and suite
  negotiation all pass — but it cannot currently be connected to a live EC exchange, and its
  element widths are P-256 widths. Choosing MODP means re-deriving those widths.
- Measured evidence lives in `ModpJpakeMeasurementApi23Test`, which runs in CI and asserts the
  sizes and the reconstruction round trip rather than merely logging them.
- Part B remains **INCOMPLETE**.

The construction remains experimental and not independently cryptographically reviewed; the owner
waived review on 2026-10-19 (`owner-risk-waiver-2026-10-19.md`). This audit says nothing about the
security of either variant.
