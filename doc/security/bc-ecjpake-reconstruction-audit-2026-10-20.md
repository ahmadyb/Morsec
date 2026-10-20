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
| Wire sizes (r1/r2/r3) | 196 / 98 / 32 B (measured) | 2304 / 1152 / 32 B (computed) |
| `MAX_FRAME_BYTES` | stays 512 | must rise to ~3072 |
| Already-merged work | all retained | framing widths and hostility tests rewritten; binding, code and negotiation retained |
| Failure mode on BC change | **compile error** | none — API is supported |
| Split package | Yes (illegal under JPMS; irrelevant on Android today) | No |
| Performance on API 23 | unchanged (~0.9 ms honest run, measured) | unmeasured; 3072-bit modexp, expect tens of ms to a few hundred ms |

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

**Option 2's real risk is different**, and also worth stating: `JPAKEUtil.validateGa` only rejects
the value `1` (line 286). Received group elements therefore need range checking by the MPS3 decoder
— `0 <= value < p`, rejecting `0` and `1` — which is work the EC path got for free from point
validation. That is a hostile-path test obligation, not a blocker. Fixed-width encoding is clean:
`BigIntegers.asUnsignedByteArray(int length, BigInteger value)` (line 57) zero-pads to exactly
`length` bytes and throws on overflow, which is precisely the no-`toByteArray()` rule in errata R-3.

On standards: RFC 8236 describes the finite-field MODP construction. That is a fact about the RFC,
not a claim that this project conforms to it — it does not, and the no-conformance-claim rule stands
whichever option is chosen.

---

## 6. Recommendation

**Option 2, preceded by a measurement.** The MODP variant is the only path that stays inside BC's
supported API without a new dependency. The cost is real but bounded: about 3.4 KB per exchange
instead of 326 bytes, which is nothing on a LAN, and a pairing flow that takes milliseconds rather
than microseconds.

The performance worry is the one thing that cannot be settled from this workspace, so it should be
settled before committing to the rewrite: the existing androidTest harness already exercises the
public API on API 23 inside the real APK, and extending it to time `NIST_3072` is a small, throwaway
change that produces evidence instead of speculation. If it shows an unusable number, the fallback
is Option 1 with the bridge confined to one clearly named file and a verifier that pins the exact
constructor signature.

---

## 7. Status of the implementation

- The coordinator is **not** wired, and will not be until this decision is made and implemented.
- No source file changed in this audit.
- The merged layer from `92ec4fb` remains correct in isolation — framing, binding, code and suite
  negotiation all pass — but it cannot currently be connected to a live exchange. That was true
  before this audit and is now documented rather than latent.
- Part B remains **INCOMPLETE**.

The construction remains experimental and not independently cryptographically reviewed; the owner
waived review on 2026-10-19 (`owner-risk-waiver-2026-10-19.md`). This audit says nothing about the
security of either variant.
