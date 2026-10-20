package app.morsecode.transport.lan.security.jpake

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroup
import org.bouncycastle.crypto.agreement.jpake.JPAKEPrimeOrderGroups
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.util.BigIntegers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * API 23 measurement of the **finite-field** J-PAKE variant, run because the EC variant cannot be
 * reconstructed from received bytes.
 *
 * WHY THIS EXISTS
 *   `ECSchnorrZKP` has a package-private constructor and no decoder, so a received MPS3 frame
 *   cannot be turned back into an `ECJPAKERound1Payload` or `ECJPAKERound2Payload` using the
 *   library's public API. See `doc/security/bc-ecjpake-reconstruction-audit-2026-10-20.md`.
 *   The MODP variant has no nested proof type: a proof is a plain `BigInteger[]`, so every round
 *   can be rebuilt from bytes with public constructors alone.
 *
 * WHAT THIS MEASURES
 *   1. That reconstruction actually works — payloads are encoded to fixed-width bytes, rebuilt
 *      through public constructors, and then validated by a real participant. This is the exact
 *      operation the EC path cannot perform, so a green result here is the point, not a detail.
 *   2. The wire sizes, so `MAX_FRAME_BYTES` can be chosen from measurement rather than guessed.
 *   3. Phase timings on API 23, reported with the same phase names and the same tag as
 *      `EcJpakePerformanceApi23Test`, so the two variants can be compared directly.
 *
 * WHAT THIS IS NOT
 *   Not a benchmark, not a conformance test, and not evidence of security. The ceilings are
 *   catastrophic-regression detectors on a possibly loaded emulator. No claim is made about
 *   RFC 8236 conformance; the library cites its own references and this project claims nothing.
 *
 * Every value logged is a fixed phase name plus a bounded integer. No code, keying material,
 * MAC tag or group element is ever written to a log.
 */
@RunWith(AndroidJUnit4::class)
class ModpJpakeMeasurementApi23Test {

    /**
     * The decisive test: every round survives an encode/reconstruct/validate cycle.
     *
     * The payloads here are rebuilt from bytes rather than passed by reference. If this passes,
     * an MPS3 frame carrying these fields can drive a real exchange; if it fails, the MODP
     * variant is not viable either and the remaining option is the package bridge.
     */
    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun everyRoundIsRebuiltFromBytesAndStillValidates() {
        val alice = newParticipant(ID_A)
        val bob = newParticipant(ID_B)

        // Round one: both sides create, then each validates the other's *reconstructed* payload.
        val aliceRound1 = alice.createRound1PayloadToSend()
        val bobRound1 = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(reconstructRound1(bobRound1))
        bob.validateRound1PayloadReceived(reconstructRound1(aliceRound1))
        assertEquals(JPAKEParticipant.STATE_ROUND_1_VALIDATED, alice.state)
        assertEquals(JPAKEParticipant.STATE_ROUND_1_VALIDATED, bob.state)

        // Round two.
        val aliceRound2 = alice.createRound2PayloadToSend()
        val bobRound2 = bob.createRound2PayloadToSend()
        alice.validateRound2PayloadReceived(reconstructRound2(bobRound2))
        bob.validateRound2PayloadReceived(reconstructRound2(aliceRound2))

        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        assertEquals("reconstruction must not disturb the shared secret", keyA, keyB)

        // Round three: the tag is a single BigInteger and round-trips the same way.
        val aliceRound3 = alice.createRound3PayloadToSend(keyA)
        val bobRound3 = bob.createRound3PayloadToSend(keyB)
        alice.validateRound3PayloadReceived(reconstructRound3(bobRound3), keyA)
        bob.validateRound3PayloadReceived(reconstructRound3(aliceRound3), keyB)

        assertEquals(JPAKEParticipant.STATE_ROUND_3_VALIDATED, alice.state)
        assertEquals(JPAKEParticipant.STATE_ROUND_3_VALIDATED, bob.state)
        Log.i(PHASE_TAG, "phase=reconstruct_round_trip result=ok rounds=3")
    }

    /**
     * Wire sizes, measured rather than assumed.
     *
     * A 3072-bit group element is 384 bytes and the subgroup order is 256 bits, so a proof scalar
     * is 32 bytes. Round one is six elements' worth of material, round two three, round three one.
     */
    @Test
    fun encodedPayloadSizesAreMeasuredSoTheFrameBoundCanBeChosen() {
        val group = GROUP
        val elementBytes = (group.p.bitLength() + BITS_PER_BYTE - 1) / BITS_PER_BYTE
        val scalarBytes = (group.q.bitLength() + BITS_PER_BYTE - 1) / BITS_PER_BYTE

        val alice = newParticipant(ID_A)
        val bob = newParticipant(ID_B)
        val aliceRound1 = alice.createRound1PayloadToSend()
        val bobRound1 = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(bobRound1)
        bob.validateRound1PayloadReceived(aliceRound1)
        val aliceRound2 = alice.createRound2PayloadToSend()
        val bobRound2 = bob.createRound2PayloadToSend()
        alice.validateRound2PayloadReceived(bobRound2)
        bob.validateRound2PayloadReceived(aliceRound2)
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        val bobRound3 = bob.createRound3PayloadToSend(keyB)

        // g^x1, g^x2, {g^v, r} for each of them.
        val round1Bytes = 2 * elementBytes + 2 * (elementBytes + scalarBytes)
        // A, and {g^v, r} for x2*s.
        val round2Bytes = elementBytes + elementBytes + scalarBytes
        val round3Bytes = TAG_BYTES // HMAC-SHA256 output, carried as opaque signed bytes

        Log.i(
            PHASE_TAG,
            "phase=modp_payload_sizes round1_bytes=$round1Bytes round2_bytes=$round2Bytes " +
                "round3_bytes=$round3Bytes element_bytes=$elementBytes scalar_bytes=$scalarBytes " +
                "p_bits=${group.p.bitLength()} q_bits=${group.q.bitLength()}",
        )

        assertEquals("a NIST 3072-bit element is 384 bytes", 384, elementBytes)
        assertEquals("a 256-bit subgroup scalar is 32 bytes", 32, scalarBytes)
        assertEquals("round one carries six elements of material", 1_600, round1Bytes)
        assertEquals("round two carries three", 800, round2Bytes)
        assertEquals("the round-three tag is a 32-byte HMAC output", 32, round3Bytes)
        assertTrue("keying material must agree", keyA == keyB)
    }

    /** Same phases, same names, same tag as the EC measurement, so the two can be compared. */
    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun honestExchangePhaseTimings() {
        repeat(WARMUP) { timedExchange() }
        val samples = (0 until ITERATIONS).map { timedExchange() }

        reportPhase("modp_construct", samples.map { it.constructNanos })
        reportPhase("modp_round1_create", samples.map { it.round1CreateNanos })
        reportPhase("modp_round1_validate", samples.map { it.round1ValidateNanos })
        reportPhase("modp_round2_create", samples.map { it.round2CreateNanos })
        reportPhase("modp_round2_validate", samples.map { it.round2ValidateNanos })
        reportPhase("modp_keying_material", samples.map { it.keyingMaterialNanos })
        reportPhase("modp_round3_create", samples.map { it.round3CreateNanos })
        reportPhase("modp_round3_validate", samples.map { it.round3ValidateNanos })
        reportPhase("modp_total_honest", samples.map { it.totalNanos })

        val medianMicros = percentile(samples.map { it.totalNanos }.sorted(), 0.5) / NANOS_PER_MICRO
        Log.i(
            PHASE_TAG,
            "phase=modp_median_total micros=$medianMicros iterations=$ITERATIONS warmup=$WARMUP",
        )
        assertTrue(
            "median MODP exchange took ${medianMicros}us, above the generous ceiling of " +
                "$CEILING_MICROS us",
            medianMicros < CEILING_MICROS,
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun aWrongCodeFailsAtConfirmation() {
        val alice = newParticipant(ID_A, CODE)
        val bob = newParticipant(ID_B, WRONG_CODE)
        exchangeRoundsOneAndTwo(alice, bob)
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        assertNotEquals("a wrong code must not agree on keying material", keyA, keyB)
        val bobTag = bob.createRound3PayloadToSend(keyB)
        assertTrue(
            "a wrong code must fail confirmation",
            rejected { alice.validateRound3PayloadReceived(reconstructRound3(bobTag), keyA) },
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    fun fourConcurrentExchangesAllAgree() {
        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        val start = System.nanoTime()
        try {
            val futures = executor.invokeAll(
                (0 until CONCURRENCY).map { index ->
                    Callable {
                        val alice = newParticipant("$ID_A-c$index")
                        val bob = newParticipant("$ID_B-c$index")
                        exchangeRoundsOneAndTwo(alice, bob)
                        val keyA = alice.calculateKeyingMaterial()
                        val keyB = bob.calculateKeyingMaterial()
                        val aliceRound3 = alice.createRound3PayloadToSend(keyA)
                        val bobRound3 = bob.createRound3PayloadToSend(keyB)
                        alice.validateRound3PayloadReceived(reconstructRound3(bobRound3), keyA)
                        bob.validateRound3PayloadReceived(reconstructRound3(aliceRound3), keyB)
                        keyA == keyB
                    }
                },
                CONCURRENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertTrue("every concurrent exchange must agree", futures.all { it.isDone && it.get() })
        } finally {
            executor.shutdownNow()
        }
        val elapsedMicros = (System.nanoTime() - start) / NANOS_PER_MICRO
        Log.i(
            PHASE_TAG,
            "phase=modp_four_concurrent micros=$elapsedMicros concurrency=$CONCURRENCY",
        )
    }

    /**
     * Out-of-range group elements are refused.
     *
     * The refusal is guaranteed by the library's own check in `JPAKEUtil.validateZeroKnowledgeProof`
     * (gx > 0, gx < p, gx^q mod p == 1), which is why the framing layer does not have to decide
     * curve membership — but the framing layer still must reject a non-canonical width, because
     * a short encoding would otherwise be silently zero-extended by a naive decoder.
     */
    @Test
    fun outOfRangeGroupElementsAreRefused() {
        val group = GROUP
        val alice = newParticipant(ID_A)
        val bob = newParticipant(ID_B)
        val genuine = bob.createRound1PayloadToSend()

        for ((label, poison) in listOf(
            "zero" to BigInteger.ZERO,
            "one" to BigInteger.ONE,
            "p" to group.p,
            "p_plus_one" to group.p.add(BigInteger.ONE),
        )) {
            val poisoned = JPAKERound1Payload(
                genuine.participantId,
                poison,
                genuine.gx2,
                genuine.knowledgeProofForX1,
                genuine.knowledgeProofForX2,
            )
            assertTrue(
                "a round-one group element of $label must be refused",
                rejected { alice.validateRound1PayloadReceived(poisoned) },
            )
        }

        // Fixed-width encoding round-trips a legitimate element exactly. Overflow throws rather
        // than truncating, which is the property the framing layer depends on: a value that does
        // not fit its declared width is an error, never a silently shortened one.
        val encoded = encodeElement(genuine.gx1)
        assertEquals("a group element must encode to exactly $ELEMENT_BYTES bytes", ELEMENT_BYTES, encoded.size)
        assertEquals(genuine.gx1, BigInteger(1, encoded))
        assertTrue(
            "a value wider than its declared field must throw, not truncate",
            rejected { BigIntegers.asUnsignedByteArray(SCALAR_BYTES, group.p) },
        )
    }

    /**
     * The fixed-width helper does not reject a negative value.
     *
     * `BigIntegers.asUnsignedByteArray(384, BigInteger.valueOf(-1))` returns a 384-byte array
     * ending in 0xFF, which reads back as 255 rather than -1 -- the sign is dropped, not refused.
     * Overflow throws, but sign does not. This is why the project's own encoder has to range-check
     * a value before it reaches this helper: `-1` must be a rejection, not a widening.
     */
    @Test
    fun theFixedWidthHelperDropsTheSignRatherThanRefusingIt() {
        val encoded = BigIntegers.asUnsignedByteArray(ELEMENT_BYTES, BigInteger.valueOf(-1))
        assertEquals("the helper pads to the requested width", ELEMENT_BYTES, encoded.size)
        assertEquals(
            "a negative value reads back as 255, without an exception",
            BigInteger.valueOf(255),
            BigInteger(1, encoded),
        )
    }

    /**
     * The round-three tag must round-trip through signed two's-complement encoding, and must NOT
     * round-trip through the unsigned helper.
     *
     * Synthetic values are used so the assertion is deterministic rather than depending on the
     * sign of a generated tag: a tag is negative roughly half the time, so a test built on one
     * real tag would be a coin toss.
     */
    @Test
    fun theRoundThreeTagRoundTripsSignedAndNotUnsigned() {
        for (value in listOf(
            BigInteger.valueOf(-1),
            BigInteger.valueOf(-123_456_789),
            BigInteger.valueOf(0),
            BigInteger.ONE,
            BigInteger.valueOf(123_456_789),
        )) {
            assertEquals(
                "a MacTag of $value must survive the signed encoding",
                value,
                decodeTag(encodeTag(value)),
            )
            assertEquals("a MacTag is always $TAG_BYTES bytes", TAG_BYTES, encodeTag(value).size)
        }

        // The signed encoding of -1 is all 0xFF; the unsigned helper produces all 0x00 but for a
        // trailing 0xFF, which reads back as 255. That difference is the bug this pins.
        assertEquals(
            "the signed encoding sign-extends",
            BigInteger.valueOf(-1),
            decodeTag(encodeTag(BigInteger.valueOf(-1))),
        )
        assertNotEquals(
            "the unsigned helper must not be used for the tag",
            BigInteger.valueOf(-1),
            BigInteger(1, BigIntegers.asUnsignedByteArray(TAG_BYTES, BigInteger.valueOf(-1))),
        )
    }

    // --- reconstruction: the operation the EC path cannot perform ------------------------

    /** Rebuilds a round-one payload from fixed-width bytes, exactly as an MPS3 decoder would. */
    private fun reconstructRound1(payload: JPAKERound1Payload): JPAKERound1Payload =
        JPAKERound1Payload(
            payload.participantId,
            decodeElement(encodeElement(payload.gx1)),
            decodeElement(encodeElement(payload.gx2)),
            decodeProof(encodeProof(payload.knowledgeProofForX1)),
            decodeProof(encodeProof(payload.knowledgeProofForX2)),
        )

    private fun reconstructRound2(payload: JPAKERound2Payload): JPAKERound2Payload =
        JPAKERound2Payload(
            payload.participantId,
            decodeElement(encodeElement(payload.a)),
            decodeProof(encodeProof(payload.knowledgeProofForX2s)),
        )

    private fun reconstructRound3(payload: JPAKERound3Payload): JPAKERound3Payload =
        JPAKERound3Payload(
            payload.participantId,
            decodeTag(encodeTag(payload.macTag)),
        )

    /**
     * The round-three tag, as 32 sign-extended two's-complement bytes.
     *
     * `JPAKEUtil.calculateMacTag` returns `new BigInteger(hmacOutput)` -- the *signed* reading of
     * the 32-byte HMAC -- so a tag is negative about half the time. BC's own Javadoc on
     * `validateMacTag` says so explicitly, and warns that `BigIntegers.asUnsignedByteArray`
     * zero-pads instead of sign-extending and therefore does not round-trip a negative tag.
     *
     * Measured, not assumed: the first version of this harness encoded the tag unsigned and round
     * three failed on the emulator while rounds one and two passed. The tag must be treated as
     * opaque two's-complement bytes on the wire; the adapter must never convert it as an unsigned
     * integer.
     */
    private fun encodeTag(value: BigInteger): ByteArray {
        val minimal = value.toByteArray()
        require(minimal.size <= TAG_BYTES) { "a MacTag cannot be wider than $TAG_BYTES bytes" }
        val out = ByteArray(TAG_BYTES)
        out.fill(if (value.signum() < 0) 0xFF.toByte() else 0x00)
        System.arraycopy(minimal, 0, out, TAG_BYTES - minimal.size, minimal.size)
        return out
    }

    /** Signed, because the library reads the tag with `BigInteger(byte[])`. */
    private fun decodeTag(bytes: ByteArray): BigInteger = BigInteger(bytes)

    /** `{g^v, r}` -- a group element followed by a mod-q scalar, both fixed width. */
    private fun encodeProof(proof: Array<BigInteger>): ByteArray =
        encodeElement(proof[0]) + encodeScalar(proof[1])

    private fun decodeProof(bytes: ByteArray): Array<BigInteger> = arrayOf(
        decodeElement(bytes.copyOfRange(0, ELEMENT_BYTES)),
        decodeScalar(bytes.copyOfRange(ELEMENT_BYTES, ELEMENT_BYTES + SCALAR_BYTES)),
    )

    /**
     * Fixed-width, zero-padded, unsigned. `BigIntegers.asUnsignedByteArray` throws rather than
     * truncating when the value does not fit, which is the behaviour the framing layer needs;
     * `BigInteger.toByteArray()` is never used because it is width-ambiguous and signed.
     */
    private fun encodeElement(value: BigInteger): ByteArray =
        BigIntegers.asUnsignedByteArray(ELEMENT_BYTES, value)

    private fun encodeScalar(value: BigInteger): ByteArray =
        BigIntegers.asUnsignedByteArray(SCALAR_BYTES, value)

    private fun decodeElement(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    private fun decodeScalar(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    // --- timing -------------------------------------------------------------------------

    private fun timedExchange(): PhaseTimings {
        var start = System.nanoTime()
        val alice = newParticipant("$ID_A-perf")
        val bob = newParticipant("$ID_B-perf")
        val construct = System.nanoTime() - start

        start = System.nanoTime()
        val aliceRound1 = alice.createRound1PayloadToSend()
        val bobRound1 = bob.createRound1PayloadToSend()
        val round1Create = System.nanoTime() - start

        start = System.nanoTime()
        alice.validateRound1PayloadReceived(reconstructRound1(bobRound1))
        bob.validateRound1PayloadReceived(reconstructRound1(aliceRound1))
        val round1Validate = System.nanoTime() - start

        start = System.nanoTime()
        val aliceRound2 = alice.createRound2PayloadToSend()
        val bobRound2 = bob.createRound2PayloadToSend()
        val round2Create = System.nanoTime() - start

        start = System.nanoTime()
        alice.validateRound2PayloadReceived(reconstructRound2(bobRound2))
        bob.validateRound2PayloadReceived(reconstructRound2(aliceRound2))
        val round2Validate = System.nanoTime() - start

        start = System.nanoTime()
        val keyA = alice.calculateKeyingMaterial()
        val keyB = bob.calculateKeyingMaterial()
        val keyingMaterial = System.nanoTime() - start

        start = System.nanoTime()
        val aliceRound3 = alice.createRound3PayloadToSend(keyA)
        val bobRound3 = bob.createRound3PayloadToSend(keyB)
        val round3Create = System.nanoTime() - start

        start = System.nanoTime()
        alice.validateRound3PayloadReceived(reconstructRound3(bobRound3), keyA)
        bob.validateRound3PayloadReceived(reconstructRound3(aliceRound3), keyB)
        val round3Validate = System.nanoTime() - start

        return PhaseTimings(
            constructNanos = construct,
            round1CreateNanos = round1Create,
            round1ValidateNanos = round1Validate,
            round2CreateNanos = round2Create,
            round2ValidateNanos = round2Validate,
            keyingMaterialNanos = keyingMaterial,
            round3CreateNanos = round3Create,
            round3ValidateNanos = round3Validate,
            totalNanos = construct + round1Create + round1Validate + round2Create +
                round2Validate + keyingMaterial + round3Create + round3Validate,
        )
    }

    private fun reportPhase(name: String, nanos: List<Long>) {
        val sorted = nanos.sorted()
        val medianMicros = percentile(sorted, 0.5) / NANOS_PER_MICRO
        val highMicros = percentile(sorted, 0.9) / NANOS_PER_MICRO
        Log.i(
            PHASE_TAG,
            "phase=$name median_micros=$medianMicros p90_micros=$highMicros samples=${sorted.size}",
        )
    }

    private fun percentile(sortedNanos: List<Long>, fraction: Double): Long {
        if (sortedNanos.isEmpty()) return 0L
        val index = ((sortedNanos.size - 1) * fraction).toInt().coerceIn(0, sortedNanos.size - 1)
        return sortedNanos[index]
    }

    // --- participants -------------------------------------------------------------------

    private fun newParticipant(id: String, code: String = CODE): JPAKEParticipant {
        val secret = code.toCharArray()
        try {
            return JPAKEParticipant(id, secret, GROUP, SHA256Digest.newInstance(), SecureRandom())
        } finally {
            secret.fill('\u0000')
        }
    }

    /** Both rounds are created before either is validated; the library forbids the other order. */
    private fun exchangeRoundsOneAndTwo(alice: JPAKEParticipant, bob: JPAKEParticipant) {
        val aliceRound1 = alice.createRound1PayloadToSend()
        val bobRound1 = bob.createRound1PayloadToSend()
        alice.validateRound1PayloadReceived(reconstructRound1(bobRound1))
        bob.validateRound1PayloadReceived(reconstructRound1(aliceRound1))
        val aliceRound2 = alice.createRound2PayloadToSend()
        val bobRound2 = bob.createRound2PayloadToSend()
        alice.validateRound2PayloadReceived(reconstructRound2(bobRound2))
        bob.validateRound2PayloadReceived(reconstructRound2(aliceRound2))
    }

    private fun rejected(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (expected: Exception) {
        true
    }

    private class PhaseTimings(
        val constructNanos: Long,
        val round1CreateNanos: Long,
        val round1ValidateNanos: Long,
        val round2CreateNanos: Long,
        val round2ValidateNanos: Long,
        val keyingMaterialNanos: Long,
        val round3CreateNanos: Long,
        val round3ValidateNanos: Long,
        val totalNanos: Long,
    )

    private companion object {
        const val PHASE_TAG = "MorsecJpakePhase"

        /** The pinned group. NIST 3072-bit MODP, subgroup order 256 bits. */
        val GROUP: JPAKEPrimeOrderGroup = JPAKEPrimeOrderGroups.NIST_3072

        val ELEMENT_BYTES: Int = (GROUP.p.bitLength() + 7) / 8
        val SCALAR_BYTES: Int = (GROUP.q.bitLength() + 7) / 8

        const val BITS_PER_BYTE = 8

        /** HMAC-SHA256 output. Two's complement on the wire; see [encodeTag]. */
        const val TAG_BYTES = 32

        const val ID_A = "morsec-modp-a"
        const val ID_B = "morsec-modp-b"

        // Fixed, non-secret harness codes. Test inputs, not credentials; never logged.
        const val CODE = "7KQF-2XPD"
        const val WRONG_CODE = "9MTR-4WBN"

        const val WARMUP = 3
        const val ITERATIONS = 7
        const val CONCURRENCY = 4
        const val CONCURRENT_TIMEOUT_SECONDS = 120L

        const val NANOS_PER_MICRO = 1_000L

        // Generous: a catastrophic-regression detector on a loaded emulator, not a target. The
        // MODP variant is expected to be far slower than EC, so this is wider than the EC ceiling
        // on purpose. The logged medians are the measurement that matters.
        const val CEILING_MICROS = 120_000_000L

        const val TEST_TIMEOUT_MILLIS = 240_000L
    }
}
