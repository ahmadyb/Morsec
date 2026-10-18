package app.morsecode.transport.lan.security.jpake

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKECurves
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKEParticipant
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKERound1Payload
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKERound2Payload
import org.bouncycastle.crypto.agreement.ecjpake.ECJPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Isolated EC J-PAKE feasibility harness for API 23.
 *
 * This test touches Bouncy Castle's `org.bouncycastle.crypto.agreement.ecjpake` classes and
 * nothing else. It deliberately does NOT construct a project pairing coordinator, does not
 * obtain an authenticated session, does not open a payload path, and does not define any
 * framing or channel binding. Its only purpose is to establish whether the pinned Bouncy
 * Castle 1.86 EC J-PAKE implementation runs on API 23 and behaves as its documentation
 * claims, so that an external cryptographic review can be given measured facts.
 *
 * Every value logged is a fixed phase name plus a bounded integer. No entered code, keying
 * material, MAC tag, point encoding or participant identity is ever written to a log.
 */
@RunWith(AndroidJUnit4::class)
class EcJpakeFeasibilityApi23Test {

    @Test
    fun theHarnessUsesLibraryClassesAndNotAProjectReimplementation() {
        // If this ever fails, a project class has been substituted for the audited library
        // type and every other result in this file is meaningless.
        assertTrue(
            "expected the library participant type",
            ECJPAKEParticipant::class.java.name.startsWith("org.bouncycastle."),
        )
        assertTrue(
            "expected the library curve catalogue type",
            ECJPAKECurves::class.java.name.startsWith("org.bouncycastle."),
        )
    }

    @Test
    fun matchingEnteredCodeAgreesOnKeyingMaterialAndConfirmsInBothDirections() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)

        exchangeRoundsOneAndTwo(alice, bob)

        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        assertEquals("both peers must derive the same keying material", keyA, keyB)

        // Round three is unilateral in the library, so mutual confirmation requires both
        // directions. Each side sends its own tag and validates the other's.
        val tagA = alice.createRound3PayloadToSend(keyA)
        val tagB = bob.createRound3PayloadToSend(keyB)
        alice.validateRound3PayloadReceived(tagB, keyA)
        bob.validateRound3PayloadReceived(tagA, keyB)

        assertEquals(ECJPAKEParticipant.STATE_ROUND_3_VALIDATED, alice.state)
        assertEquals(ECJPAKEParticipant.STATE_ROUND_3_VALIDATED, bob.state)
    }

    @Test
    fun wrongEnteredCodeDerivesDifferentMaterialAndFailsConfirmation() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, WRONG_CODE)

        // Rounds one and two carry no password check at all: an attacker who knows the
        // public transcript gets this far. Only the derived material differs.
        exchangeRoundsOneAndTwo(alice, bob)

        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        assertNotEquals("a wrong code must not agree on keying material", keyA, keyB)

        val tagA = alice.createRound3PayloadToSend(keyA)
        val tagB = bob.createRound3PayloadToSend(keyB)

        assertTrue("alice must reject bob's confirmation", rejected { alice.validateRound3PayloadReceived(tagB, keyA) })
        assertTrue("bob must reject alice's confirmation", rejected { bob.validateRound3PayloadReceived(tagA, keyB) })
    }

    @Test
    fun anEmptyCodeIsRejectedAtConstruction() {
        try {
            newParticipant(ID_A, "")
            fail("an empty entered code must be rejected")
        } catch (expected: IllegalArgumentException) {
            // The library refuses an empty password outright rather than hashing it.
        }
    }

    @Test
    fun identicalParticipantIdsAreRejected() {
        val alice = newParticipant(ID_SHARED, CODE)
        val impostor = newParticipant(ID_SHARED, CODE)
        assertTrue(
            "two peers claiming the same participant id must be refused",
            rejected { alice.validateRound1PayloadReceived(impostor.createRound1PayloadToSend()) },
        )
    }

    @Test
    fun aSubstitutedParticipantIdIsRejectedInLaterRounds() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        exchangeRoundOne(alice, bob)

        // Bob's round-two payload, re-attributed to a third identity. The library pins the
        // partner id captured in round one, so a substituted sender is refused.
        val genuine = bob.createRound2PayloadToSend()
        val substituted = ECJPAKERound2Payload(
            ID_C,
            genuine.a,
            genuine.knowledgeProofForX2s,
        )
        assertTrue(
            "a round-two payload from an unexpected participant id must be refused",
            rejected { alice.validateRound2PayloadReceived(substituted) },
        )
    }

    @Test
    fun roundOrderViolationsAreRefused() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        val bobRound1 = bob.createRound1PayloadToSend()

        assertTrue(
            "round two cannot be created before round one is validated",
            illegalState { alice.createRound2PayloadToSend() },
        )
        assertTrue(
            "keying material cannot be computed before round two is validated",
            illegalState { alice.calculateKeyingMaterial() },
        )
        assertTrue(
            "round three cannot be created before the key exists",
            illegalState { alice.createRound3PayloadToSend(BigInteger.ONE) },
        )
        assertTrue(
            "round three cannot be validated before the key exists",
            illegalState { alice.validateRound3PayloadReceived(ECJPAKERound3Payload(ID_B, BigInteger.ONE), BigInteger.ONE) },
        )

        // A genuinely valid round-two payload, offered before round one has been validated.
        // Constructing it legitimately first keeps the refusal attributable to the ordering
        // check rather than to the payload being unusable.
        val aliceRound1 = alice.createRound1PayloadToSend()
        bob.validateRound1PayloadReceived(aliceRound1)
        val bobRound2 = bob.createRound2PayloadToSend()
        assertTrue(
            "round two cannot be validated before round one is validated",
            illegalState { alice.validateRound2PayloadReceived(bobRound2) },
        )

        alice.validateRound1PayloadReceived(bobRound1)
        assertEquals(ECJPAKEParticipant.STATE_ROUND_1_VALIDATED, alice.state)
    }

    @Test
    fun duplicatedRoundsAreRefused() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        val aliceRound1 = alice.createRound1PayloadToSend()
        assertTrue("a second round-one creation must be refused", illegalState { alice.createRound1PayloadToSend() })

        val bobRound1 = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(bobRound1)
        assertTrue("a second round-one validation must be refused", illegalState { alice.validateRound1PayloadReceived(bobRound1) })

        bob.validateRound1PayloadReceived(aliceRound1)
        val aliceRound2 = alice.createRound2PayloadToSend()
        assertTrue("a second round-two creation must be refused", illegalState { alice.createRound2PayloadToSend() })

        val bobRound2 = bob.createRound2PayloadToSend()
        alice.validateRound2PayloadReceived(bobRound2)
        assertTrue("a second round-two validation must be refused", illegalState { alice.validateRound2PayloadReceived(bobRound2) })

        val keyA = alice.calculateKeyingMaterial()
        assertTrue("the key cannot be recomputed", illegalState { alice.calculateKeyingMaterial() })

        val aliceTag = alice.createRound3PayloadToSend(keyA)
        assertTrue("a second round-three creation must be refused", illegalState { alice.createRound3PayloadToSend(keyA) })

        bob.validateRound2PayloadReceived(aliceRound2)
        val keyB = bob.calculateKeyingMaterial()
        alice.validateRound3PayloadReceived(bob.createRound3PayloadToSend(keyB), keyA)
        assertTrue("a second round-three validation must be refused", illegalState { alice.validateRound3PayloadReceived(aliceTag, keyA) })
    }

    @Test
    fun replayingAnEarlierRoundOneIsAcceptedSoTheApplicationMustBindTheSession() {
        // Measured, not assumed: a round-one payload is self-contained, so a captured one
        // validates against a fresh participant. Freshness therefore cannot come from the
        // library; the project framing must bind every round to the session.
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        val captured = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(captured)

        val fresh = newParticipant(ID_A, CODE)
        fresh.validateRound1PayloadReceived(captured)
        assertEquals(
            "a replayed round one is accepted, which is why framing must bind the session",
            ECJPAKEParticipant.STATE_ROUND_1_VALIDATED,
            fresh.state,
        )
    }

    @Test
    fun replayingAnEarlierRoundTwoIsRejectedOutright() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        exchangeRoundOne(alice, bob)
        val staleRound2 = bob.createRound2PayloadToSend()

        // A second session reusing the same participant ids, so the identity checks alone
        // would pass. What stops the replay is the zero-knowledge proof: it was produced
        // against the first session's curve points, so it cannot verify here.
        val alice2 = newParticipant(ID_A, CODE)
        val bob2 = newParticipant(ID_B, CODE)
        exchangeRoundOne(alice2, bob2)

        assertTrue(
            "a round two replayed from another session must be refused",
            rejected { alice2.validateRound2PayloadReceived(staleRound2) },
        )
        assertEquals(
            "a refused round two must leave the state at round one validated",
            ECJPAKEParticipant.STATE_ROUND_1_VALIDATED,
            alice2.state,
        )
    }

    @Test
    fun aPayloadFromAnotherSessionIsRejected() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        exchangeRoundOne(alice, bob)

        // A wholly separate exchange whose round-two payload is offered to the first session.
        val carol = newParticipant(ID_C, CODE)
        val dave = newParticipant(ID_D, CODE)
        exchangeRoundOne(carol, dave)
        val foreign = dave.createRound2PayloadToSend()

        assertTrue(
            "a payload belonging to another session must be refused",
            rejected { alice.validateRound2PayloadReceived(foreign) },
        )
    }

    @Test
    fun aPointAtInfinityIsRejected() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        val genuine = bob.createRound1PayloadToSend()
        val infinity = ecCurve.infinity
        val poisoned = ECJPAKERound1Payload(
            genuine.participantId,
            infinity,
            genuine.gx2,
            genuine.knowledgeProofForX1,
            genuine.knowledgeProofForX2,
        )
        assertTrue("the point at infinity must be refused", rejected { alice.validateRound1PayloadReceived(poisoned) })
    }

    @Test
    fun malformedAndOffCurvePointEncodingsAreRejectedOnDecode() {
        // The public API cannot construct an off-curve ECPoint, so the library's internal
        // on-curve check is unreachable except through decoding. This measures where the
        // project framing layer must intervene.
        val genuine = newParticipant(ID_B, CODE).createRound1PayloadToSend()
        val template = genuine.gx1.getEncoded(true)

        // Structurally invalid encodings fail deterministically.
        val badPrefix = template.copyOf().also { it[0] = INVALID_POINT_PREFIX }
        assertTrue(
            "an invalid point-encoding prefix must not decode",
            rejected { ecCurve.decodePoint(badPrefix) },
        )
        assertTrue(
            "a truncated point encoding must not decode",
            rejected { ecCurve.decodePoint(template.copyOfRange(0, template.size - 1)) },
        )

        // A well-formed encoding whose x simply is not a curve x-coordinate. Roughly half of
        // all field elements are not, so a bounded search always finds one -- which is exactly
        // why a single fixed corruption cannot be used here: it decodes about half the time,
        // making the assertion a coin toss.
        var rejectedCount = 0
        var attempts = 0
        while (attempts < MAX_OFF_CURVE_SEARCH && rejectedCount == 0) {
            val candidate = template.copyOf()
            candidate[candidate.size - 1] =
                (candidate[candidate.size - 1].toInt() + attempts + 1).toByte()
            if (rejected { ecCurve.decodePoint(candidate) }) rejectedCount += 1
            attempts += 1
        }
        assertTrue(
            "a bounded search over $attempts candidate encodings must find one that is off-curve",
            rejectedCount > 0,
        )
    }

    @Test
    fun aZeroKnowledgeProofPresentedForTheWrongPointIsRejected() {
        // ECSchnorrZKP's constructor is package-private, so a structurally malformed proof
        // cannot be built from outside the library at all. Recombining two genuine proofs is
        // the strongest invalid-ZKP input reachable through the public API, and it is still
        // refused: the proof hash binds the point the proof is presented against.
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        val genuine = bob.createRound1PayloadToSend()
        val poisoned = ECJPAKERound1Payload(
            genuine.participantId,
            genuine.gx1,
            genuine.gx2,
            genuine.knowledgeProofForX2,
            genuine.knowledgeProofForX1,
        )
        assertTrue(
            "a zero-knowledge proof presented for the wrong point must be refused",
            rejected { alice.validateRound1PayloadReceived(poisoned) },
        )
    }

    @Test
    fun aModifiedRoundThreeTagIsRejected() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        exchangeRoundsOneAndTwo(alice, bob)
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        val tag = bob.createRound3PayloadToSend(keyB)
        val modified = ECJPAKERound3Payload(tag.participantId, tag.macTag.add(BigInteger.ONE))
        assertTrue("a modified confirmation tag must be refused", rejected { alice.validateRound3PayloadReceived(modified, keyA) })
    }

    @Test
    fun aReflectedRoundThreeTagIsRejected() {
        // Alice's own tag handed straight back to her. The library reverses the id and point
        // ordering when it recomputes the expected tag, so the two directions differ and a
        // reflection cannot pass for a reply.
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        exchangeRoundsOneAndTwo(alice, bob)
        val keyA = alice.calculateKeyingMaterial()
        bob.calculateKeyingMaterial()
        val aliceTag = alice.createRound3PayloadToSend(keyA)
        assertTrue(
            "reflecting a peer's own confirmation tag back at it must fail",
            rejected { alice.validateRound3PayloadReceived(aliceTag, keyA) },
        )
    }

    @Test
    fun cancellingBetweenRoundsLeavesNoKeyingMaterial() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        alice.validateRound1PayloadReceived(bob.createRound1PayloadToSend())
        assertEquals(ECJPAKEParticipant.STATE_ROUND_1_VALIDATED, alice.state)

        // Abandoning the participant is the cancellation path: nothing further is derived,
        // and no key can be recovered from the abandoned instance afterwards.
        assertTrue(
            "an abandoned participant cannot yield keying material",
            illegalState { alice.calculateKeyingMaterial() },
        )
    }

    @Test
    fun fourConcurrentExchangesAllAgree() {
        val executor = Executors.newFixedThreadPool(FOUR)
        try {
            val results = executor.invokeAll(
                (0 until FOUR).map { index ->
                    Callable {
                        val alice = newParticipant("$ID_A-$index", CODE)
                        val bob = newParticipant("$ID_B-$index", CODE)
                        exchangeRoundsOneAndTwo(alice, bob)
                        val keyA = alice.calculateKeyingMaterial()
                        val keyB = bob.calculateKeyingMaterial()
                        exchangeRoundThree(alice, bob, keyA, keyB)
                        keyA == keyB
                    }
                },
                CONCURRENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertEquals("every concurrent exchange must finish", FOUR, results.size)
            results.forEachIndexed { index, future ->
                assertTrue("concurrent exchange $index must complete", future.isDone)
                assertTrue("concurrent exchange $index must agree", future.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun repeatedExchangesWithFreshParticipantsKeepWorking() {
        repeat(REPEATS) { index ->
            val alice = newParticipant("$ID_A-r$index", CODE)
            val bob = newParticipant("$ID_B-r$index", CODE)
            exchangeRoundsOneAndTwo(alice, bob)
            val keyA = alice.calculateKeyingMaterial()
            val keyB = bob.calculateKeyingMaterial()
            assertEquals("repeat $index must agree on keying material", keyA, keyB)
            alice.validateRound3PayloadReceived(bob.createRound3PayloadToSend(keyB), keyA)
        }
    }

    @Test
    fun clearingTheCallerCodeArrayDoesNotBreakTheExchange() {
        // The library copies the code, so the caller can and should zero its own array
        // immediately. This proves that clearing is safe rather than merely assumed.
        val shared = CODE.toCharArray()
        val alice = ECJPAKEParticipant(ID_A, shared, profile, SHA256Digest.newInstance(), SecureRandom())
        val bob = ECJPAKEParticipant(ID_B, shared.copyOf(), profile, SHA256Digest.newInstance(), SecureRandom())
        shared.fill('\u0000')

        exchangeRoundsOneAndTwo(alice, bob)
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        assertEquals("clearing the caller array must not change the result", keyA, keyB)
    }

    @Test
    fun encodedPayloadSizesAreBoundedAndSmall() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, CODE)
        val aliceRound1 = alice.createRound1PayloadToSend()
        val round1 = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(round1)
        bob.validateRound1PayloadReceived(aliceRound1)
        val aliceRound2 = alice.createRound2PayloadToSend()
        val round2 = bob.createRound2PayloadToSend()
        alice.validateRound2PayloadReceived(round2)
        bob.validateRound2PayloadReceived(aliceRound2)
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        val round3 = bob.createRound3PayloadToSend(keyB)

        val pointBytes = round1.gx1.getEncoded(true).size
        // ECSchnorrZKP exposes getr(), and Kotlin only synthesises a property from a getter
        // whose name has an uppercase letter after `get`, so this one must be called directly.
        val scalarBytes = unsignedSize(round1.knowledgeProofForX1.getr())
        val round1Bytes = 2 * pointBytes + 2 * (pointBytes + scalarBytes)
        val round2Bytes = pointBytes + pointBytes + scalarBytes
        val round3Bytes = unsignedSize(round3.macTag)

        Log.i(PHASE_TAG, "phase=payload_sizes round1_bytes=$round1Bytes round2_bytes=$round2Bytes " +
            "round3_bytes=$round3Bytes point_bytes=$pointBytes scalar_bytes=$scalarBytes")

        assertEquals("a compressed P-256 point must encode to 33 bytes", POINT_BYTES, pointBytes)
        assertTrue("round one must stay under 512 bytes", round1Bytes < MAX_ROUND1_BYTES)
        assertTrue("round two must stay under 256 bytes", round2Bytes < MAX_ROUND2_BYTES)
        assertTrue("round three must stay under 64 bytes", round3Bytes < MAX_ROUND3_BYTES)
        assertEquals(ECJPAKEParticipant.STATE_ROUND_3_CREATED, bob.state)
        assertTrue(keyA.signum() >= 0)
    }

    /** The pinned profile, passed to the participant. */
    private val profile get() = ECJPAKECurves.NIST_P256

    /** The underlying curve, needed for point-level operations. */
    private val ecCurve get() = ECJPAKECurves.NIST_P256.curve

    private fun newParticipant(id: String, code: String): ECJPAKEParticipant {
        val secret = code.toCharArray()
        try {
            return ECJPAKEParticipant(id, secret, profile, SHA256Digest.newInstance(), SecureRandom())
        } finally {
            secret.fill('\u0000')
        }
    }

    /**
     * Both rounds are created before either is validated, on both sides.
     *
     * This ordering is forced by the library, not chosen: validating a peer's round one moves
     * the state to STATE_ROUND_1_VALIDATED (20), and createRound1PayloadToSend throws once the
     * state has reached STATE_ROUND_1_CREATED (10). A peer that validates an incoming round
     * before generating its own can never reply. Round two is constrained the same way.
     */
    private fun exchangeRoundOne(alice: ECJPAKEParticipant, bob: ECJPAKEParticipant) {
        val aliceRound1 = alice.createRound1PayloadToSend()
        val bobRound1 = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(bobRound1)
        bob.validateRound1PayloadReceived(aliceRound1)
    }

    /** Round three is constrained like the others: both tags are created before either is checked. */
    private fun exchangeRoundThree(
        alice: ECJPAKEParticipant,
        bob: ECJPAKEParticipant,
        keyA: BigInteger,
        keyB: BigInteger,
    ) {
        val aliceRound3 = alice.createRound3PayloadToSend(keyA)
        val bobRound3 = bob.createRound3PayloadToSend(keyB)
        alice.validateRound3PayloadReceived(bobRound3, keyA)
        bob.validateRound3PayloadReceived(aliceRound3, keyB)
    }

    private fun exchangeRoundsOneAndTwo(alice: ECJPAKEParticipant, bob: ECJPAKEParticipant) {
        exchangeRoundOne(alice, bob)
        val aliceRound2 = alice.createRound2PayloadToSend()
        val bobRound2 = bob.createRound2PayloadToSend()
        alice.validateRound2PayloadReceived(bobRound2)
        bob.validateRound2PayloadReceived(aliceRound2)
    }

    /** The library reports a BigInteger of unknown width; size it as an unsigned value. */
    private fun unsignedSize(value: BigInteger): Int = (value.bitLength() + 7) / 8

    private fun rejected(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (expected: Exception) {
        true
    }

    private fun illegalState(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (expected: IllegalStateException) {
        true
    }

    private companion object {
        const val PHASE_TAG = "MorsecJpakePhase"
        const val ID_A = "morsec-feasibility-a"
        const val ID_B = "morsec-feasibility-b"
        const val ID_C = "morsec-feasibility-c"
        const val ID_D = "morsec-feasibility-d"
        const val ID_SHARED = "morsec-feasibility-shared"

        // Fixed, non-secret harness codes. These are test inputs, not credentials, and no
        // code value is ever logged.
        const val CODE = "7KQF-2XPD"
        const val WRONG_CODE = "9MTR-4WBN"

        const val FOUR = 4
        const val REPEATS = 5
        const val CONCURRENT_TIMEOUT_SECONDS = 120L

        const val POINT_BYTES = 33

        // Compressed P-256 encodings carry a 0x02 or 0x03 prefix; anything else is invalid.
        const val INVALID_POINT_PREFIX: Byte = 0x05

        // About half of all field elements are not curve x-coordinates, so this bound is
        // reached with probability near 2^-64.
        const val MAX_OFF_CURVE_SEARCH = 64
        const val MAX_ROUND1_BYTES = 512
        const val MAX_ROUND2_BYTES = 256
        const val MAX_ROUND3_BYTES = 64
    }
}
