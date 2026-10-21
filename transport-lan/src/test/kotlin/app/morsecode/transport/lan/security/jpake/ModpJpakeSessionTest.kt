package app.morsecode.transport.lan.security.jpake

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.JpakeBinding
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.JpakeMessage
import app.morsecode.core.transfer.session.JpakeMessageCodec
import app.morsecode.core.transfer.session.JpakeMessageResult
import app.morsecode.core.transfer.session.JpakeParticipantIds
import app.morsecode.core.transfer.session.JpakeRound
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.PakeAttemptId
import app.morsecode.core.transfer.session.PakeAttemptLedger
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecurePeerRole
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MODP round adapter, driven entirely through serialized frames.
 *
 * Every exchange here goes `ByteArray -> ByteArray`. No library payload object is passed from one
 * session to the other, which is the whole point: the EC variant could not cross that boundary at
 * all, and an in-memory shortcut would not prove that this one can.
 *
 * These tests use the real library. They do not and cannot show that the construction is secure;
 * see doc/security/owner-risk-waiver-2026-10-19.md.
 */
public class ModpJpakeSessionTest {

    // --- the honest path ---------------------------------------------------------------

    @Test
    public fun `a full exchange over serialized frames confirms on both sides`() {
        val (initiator, responder) = newPair()

        val initiatorRoundOne = initiator.createRoundOne()
        val responderRoundOne = responder.createRoundOne()
        assertRound(initiatorRoundOne, JpakeRound.ROUND_ONE, SecurePeerRole.INITIATOR)
        assertRound(responderRoundOne, JpakeRound.ROUND_ONE, SecurePeerRole.RESPONDER)
        assertNotEquals(
            "the two round-one frames must differ",
            initiatorRoundOne.toList(),
            responderRoundOne.toList(),
        )

        assertEquals(ModpJpakeSession.Step.Accepted, initiator.acceptRoundOne(responderRoundOne))
        assertEquals(ModpJpakeSession.Step.Accepted, responder.acceptRoundOne(initiatorRoundOne))
        assertEquals(ModpJpakeSession.Phase.ROUND_ONE_VALIDATED, initiator.phase)

        val initiatorRoundTwo = initiator.createRoundTwo()
        val responderRoundTwo = responder.createRoundTwo()
        assertRound(initiatorRoundTwo, JpakeRound.ROUND_TWO, SecurePeerRole.INITIATOR)

        assertEquals(ModpJpakeSession.Step.Accepted, initiator.acceptRoundTwo(responderRoundTwo))
        assertEquals(ModpJpakeSession.Step.Accepted, responder.acceptRoundTwo(initiatorRoundTwo))
        assertEquals(
            "the secret is derived when round two validates",
            ModpJpakeSession.Phase.KEY_DERIVED,
            initiator.phase,
        )

        val initiatorTag = initiator.createConfirmation()
        val responderTag = responder.createConfirmation()
        assertRound(initiatorTag, JpakeRound.ROUND_THREE, SecurePeerRole.INITIATOR)

        assertEquals(ModpJpakeSession.Step.Accepted, initiator.acceptConfirmation(responderTag))
        assertEquals(ModpJpakeSession.Step.Accepted, responder.acceptConfirmation(initiatorTag))

        assertTrue("the initiator must be confirmed", initiator.isConfirmed())
        assertTrue("the responder must be confirmed", responder.isConfirmed())
    }

    @Test
    public fun `every emitted frame decodes and fits the bound`() {
        val (initiator, responder) = newPair()
        val i1 = initiator.createRoundOne()
        val r1 = responder.createRoundOne()
        initiator.acceptRoundOne(r1)
        responder.acceptRoundOne(i1)
        val i2 = initiator.createRoundTwo()
        val r2 = responder.createRoundTwo()
        initiator.acceptRoundTwo(r2)
        responder.acceptRoundTwo(i2)
        val i3 = initiator.createConfirmation()
        val r3 = responder.createConfirmation()

        for (frame in listOf(i1, r1, i2, r2, i3, r3)) {
            assertTrue(
                "a frame must not exceed the bound",
                frame.size <= JpakeMessageCodec.HEADER_BYTES + JpakeMessageCodec.MAX_FRAME_BYTES,
            )
            // decode() refuses trailing bytes, so a successful decode proves the frame is exact.
            assertTrue(
                "every emitted frame must decode",
                JpakeMessageCodec.decode(
                    frame.copyOfRange(JpakeMessageCodec.HEADER_BYTES, frame.size),
                ) is JpakeMessageResult.Success,
            )
        }
    }

    @Test
    public fun `a round one frame measures what the frame bound was sized for`() {
        val (initiator, _) = newPair()
        val frame = initiator.createRoundOne()
        // 1600 bytes of body plus header fields and the length prefix; the bound is 2048.
        assertTrue("round one measured ${frame.size} bytes", frame.size in 1600..2048)
    }

    // --- wrong secret ------------------------------------------------------------------

    @Test
    public fun `a wrong code fails confirmation and closes both sessions`() {
        val ids = idsFor(0x11)
        val attempt = attemptFor(0x11)
        val initiator = session(SecurePeerRole.INITIATOR, ids, attempt, code = "7KQF2XPD")
        val responder = session(SecurePeerRole.RESPONDER, ids, attempt, code = "9MTR4WBN")

        advanceToConfirmation(initiator, responder)
        val initiatorTag = initiator.createConfirmation()
        val responderTag = responder.createConfirmation()

        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CRYPTO_FAILED),
            initiator.acceptConfirmation(responderTag),
        )
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CRYPTO_FAILED),
            responder.acceptConfirmation(initiatorTag),
        )
        assertTrue(initiator.isClosed())
        assertTrue(responder.isClosed())
        assertFalse(initiator.isConfirmed())
    }

    // --- identity, session and role binding ---------------------------------------------

    @Test
    public fun `identifiers differ between sessions`() {
        assertNotEquals(idsFor(0x11).responder, idsFor(0x22).responder)
        assertNotEquals(idsFor(0x11).initiator, idsFor(0x11).responder)
    }

    @Test
    public fun `a frame naming a different participant is refused before the library runs`() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()

        val substituted = rebuild(genuine, participantId = "morsec1-r-ffffffffffffffffffffffffffffffff")
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(substituted),
        )
        assertTrue(initiator.isClosed())
    }

    @Test
    public fun `a reflected frame from our own role is refused`() {
        val (initiator, _) = newPair()
        // The initiator's own frame handed straight back: real identifier, real proof, wrong role.
        val own = initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(own),
        )
    }

    @Test
    public fun `a frame from another session is refused`() {
        val (initiator, _) = newPair()
        val (_, foreignResponder) = newPair(contextByte = 0x22)
        val foreign = foreignResponder.createRoundOne()
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(foreign),
        )
    }

    @Test
    public fun `a frame carrying a different secure session id is refused`() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        val moved = rebuild(genuine, secureSessionId = SessionId("fedcba9876543210fedcba9876543210"))
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(moved),
        )
    }

    @Test
    public fun `a frame carrying a different control session id is refused`() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(rebuild(genuine, controlSessionId = "ctl-other")),
        )
    }

    @Test
    public fun `a round two frame offered to round one is refused`() {
        val (initiator, responder) = newPair()
        val i1 = initiator.createRoundOne()
        val r1 = responder.createRoundOne()
        initiator.acceptRoundOne(r1)
        responder.acceptRoundOne(i1)
        val responderRoundTwo = responder.createRoundTwo()

        val (fresh, freshResponder) = newPair()
        fresh.createRoundOne()
        freshResponder.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            fresh.acceptRoundOne(responderRoundTwo),
        )
    }

    @Test
    public fun `a frame offering a legacy suite is refused by the adapter`() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        // The codec can still describe the legacy suite; the policy must not select it.
        val legacy = rebuild(
            genuine,
            suite = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        )
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(legacy),
        )
    }

    @Test
    public fun `a frame naming an unknown profile is refused at the codec`() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.FRAME_INVALID),
            initiator.acceptRoundOne(tamperProfileByte(genuine, 0x02)),
        )
    }

    // --- integer ranges, checked before the library -------------------------------------

    @Test
    public fun `out of range group elements are refused at the adapter`() {
        for ((label, value) in listOf(
            "zero" to BigInteger.ZERO,
            "one" to BigInteger.ONE,
            "p" to ModpJpakeFrames.MODULUS_P,
            "p_plus_one" to ModpJpakeFrames.MODULUS_P.add(BigInteger.ONE),
        )) {
            val (initiator, responder) = newPair()
            val genuine = responder.createRoundOne()
            initiator.createRoundOne()
            assertEquals(
                "a round-one element of $label must be refused",
                ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.FRAME_INVALID),
                initiator.acceptRoundOne(rebuildNumbers(genuine, element = value)),
            )
        }
    }

    @Test
    public fun `an out of range proof scalar is refused at the adapter`() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        val poisoned = rebuildNumbers(genuine, scalar = ModpJpakeFrames.SUBGROUP_ORDER_Q)
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.FRAME_INVALID),
            initiator.acceptRoundOne(poisoned),
        )
    }

    @Test
    public fun `an in range but substituted element reaches the library and is refused there`() {
        // The adapter's range check passes -- 2 is the inclusive lower bound -- and the
        // zero-knowledge proof then fails against the substituted point. This is the boundary
        // between the project's check and the library's check.
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CRYPTO_FAILED),
            initiator.acceptRoundOne(rebuildNumbers(genuine, element = BigInteger.valueOf(2))),
        )
    }

    // --- framing ------------------------------------------------------------------------

    @Test
    public fun `malformed frames are refused as frames`() {
        val (_, responder) = newPair()
        val genuine = responder.createRoundOne()

        val cases = listOf(
            "empty" to ByteArray(0),
            "header only" to genuine.copyOfRange(0, 4),
            "truncated body" to genuine.copyOfRange(0, genuine.size - 1),
            "trailing byte" to genuine + byteArrayOf(0x00),
            "declared length above the bound" to
                byteArrayOf(0x00, 0x00, 0x10, 0x00) + genuine.copyOfRange(4, genuine.size),
            "foreign magic" to genuine.copyOf().also { it[4] = 0x00 },
        )
        for ((label, frame) in cases) {
            val fresh = newPair().first
            fresh.createRoundOne()
            assertEquals(
                "$label must be refused as a frame",
                ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.FRAME_INVALID),
                fresh.acceptRoundOne(frame),
            )
        }
    }

    // --- sequencing ---------------------------------------------------------------------

    @Test
    public fun `steps out of order are refused and do not leave the session open`() {
        val (initiator, responder) = newPair()
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.STATE_INVALID),
            initiator.acceptRoundTwo(responder.createRoundOne()),
        )
        assertTrue("a refused step must close the session", initiator.isClosed())

        val (second, secondResponder) = newPair()
        second.createRoundOne()
        val genuine = secondResponder.createRoundOne()
        assertEquals(ModpJpakeSession.Step.Accepted, second.acceptRoundOne(genuine))
        assertEquals(
            "the same round-one frame again is a replay",
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.STATE_INVALID),
            second.acceptRoundOne(genuine),
        )
        assertTrue(second.isClosed())
    }

    @Test
    public fun `creating out of order is a local error rather than a protocol event`() {
        val (initiator, _) = newPair()
        assertTrue(
            "round two cannot be created before round one validates",
            threwIllegalState { initiator.createRoundTwo() },
        )
        assertTrue(
            "confirmation cannot be created before the key exists",
            threwIllegalState { initiator.createConfirmation() },
        )
    }

    @Test
    public fun `a closed session refuses every further step`() {
        val (initiator, responder) = newPair()
        initiator.createRoundOne()
        val genuine = responder.createRoundOne()
        initiator.close()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CLOSED),
            initiator.acceptRoundOne(genuine),
        )
        assertTrue(threwIllegalState { initiator.createRoundOne() })
    }

    @Test
    public fun `a session confirms nothing until the partner tag validates`() {
        val (initiator, responder) = newPair()
        assertFalse("a new session is not confirmed", initiator.isConfirmed())
        assertEquals(ModpJpakeSession.Phase.NEW, initiator.phase)

        advanceToConfirmation(initiator, responder)
        assertEquals(ModpJpakeSession.Phase.KEY_DERIVED, initiator.phase)
        assertFalse(
            "deriving the key is not confirmation; only the partner tag is",
            initiator.isConfirmed(),
        )

        initiator.createConfirmation()
        assertFalse("creating our own tag proves nothing", initiator.isConfirmed())
        assertTrue(responder.isConfirmed().not())
        initiator.close()
        assertFalse("closing never confirms", initiator.isConfirmed())
    }

    @Test
    public fun `replaying a later round frame is refused`() {
        val (initiator, responder) = newPair()
        val i1 = initiator.createRoundOne()
        val r1 = responder.createRoundOne()
        initiator.acceptRoundOne(r1)
        responder.acceptRoundOne(i1)
        val i2 = initiator.createRoundTwo()
        val r2 = responder.createRoundTwo()
        initiator.acceptRoundTwo(r2)
        responder.acceptRoundTwo(i2)
        assertEquals(
            "a replayed round-two frame is out of phase",
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.STATE_INVALID),
            initiator.acceptRoundTwo(r2),
        )
        assertTrue(initiator.isClosed())

        val (second, secondResponder) = newPair()
        advanceToConfirmation(second, secondResponder)
        val secondTag = second.createConfirmation()
        val partnerTag = secondResponder.createConfirmation()
        assertEquals(ModpJpakeSession.Step.Accepted, second.acceptConfirmation(partnerTag))
        assertEquals(
            "a replayed round-three frame is out of phase",
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.STATE_INVALID),
            second.acceptConfirmation(partnerTag),
        )
        assertTrue(secondTag.isNotEmpty())
        assertTrue(second.isClosed())
        assertFalse("closing after confirmation is not authority", second.isConfirmed())
    }

    @Test
    public fun `no public member hands out keying material`() {
        // The class exposes frames and phases only. If a getter for the secret ever appears, this
        // fails before anything can be built on it.
        val names = ModpJpakeSession::class.java.methods.map { it.name.lowercase() }
        assertTrue(
            "unexpected accessor: ${names.filter { it.contains("key") }}",
            names.none { it.contains("key") },
        )
        val (initiator, _) = newPair()
        assertFalse(initiator.toString().contains("key"))
    }

    @Test
    public fun `close is idempotent and redacts identity in its string form`() {
        val (initiator, _) = newPair()
        initiator.close()
        initiator.close()
        assertTrue(initiator.isClosed())
        assertFalse(initiator.toString().contains("morsec1"))
        assertTrue(initiator.toString().contains("[redacted]"))
    }

    // --- the pure conversions -----------------------------------------------------------

    @Test
    public fun `element conversion is fixed width and range checked in both directions`() {
        assertEquals(384, ModpJpakeFrames.ELEMENT_BYTES)
        val gx = ModpJpakeFrames.GENERATOR.multiply(BigInteger.valueOf(7)).mod(ModpJpakeFrames.MODULUS_P)
        val encoded = requireNotNull(ModpJpakeFrames.encodeElement(gx))
        assertEquals(384, encoded.size)
        assertEquals(gx, ModpJpakeFrames.decodeElement(encoded))

        // A value whose top byte is small still occupies the full width: leading zeros are kept.
        val wide = requireNotNull(ModpJpakeFrames.encodeElement(ModpJpakeFrames.GENERATOR))
        assertEquals(384, wide.size)
        assertEquals(ModpJpakeFrames.GENERATOR, ModpJpakeFrames.decodeElement(wide))

        assertNull(ModpJpakeFrames.encodeElement(BigInteger.ZERO))
        assertNull(ModpJpakeFrames.encodeElement(BigInteger.ONE))
        assertNull(ModpJpakeFrames.encodeElement(BigInteger.valueOf(-1)))
        assertNull(ModpJpakeFrames.encodeElement(ModpJpakeFrames.MODULUS_P))
        assertNull(ModpJpakeFrames.decodeElement(ByteArray(383)))
        assertNull(ModpJpakeFrames.decodeElement(ByteArray(385)))
        assertNull(ModpJpakeFrames.decodeElement(ByteArray(384)))
    }

    @Test
    public fun `scalar conversion refuses negatives and values at or above the order`() {
        val zero = requireNotNull(ModpJpakeFrames.encodeScalar(BigInteger.ZERO))
        assertEquals(32, zero.size)
        assertEquals(BigInteger.ZERO, ModpJpakeFrames.decodeScalar(zero))

        val top = ModpJpakeFrames.SUBGROUP_ORDER_Q.subtract(BigInteger.ONE)
        assertEquals(top, ModpJpakeFrames.decodeScalar(requireNotNull(ModpJpakeFrames.encodeScalar(top))))

        assertNull(ModpJpakeFrames.encodeScalar(BigInteger.valueOf(-1)))
        assertNull(ModpJpakeFrames.encodeScalar(ModpJpakeFrames.SUBGROUP_ORDER_Q))
        assertNull(ModpJpakeFrames.decodeScalar(ByteArray(31)))
        // All 0xFF is 2^256 - 1, which is at or above the order.
        assertNull(ModpJpakeFrames.decodeScalar(ByteArray(32) { 0xFF.toByte() }))
    }

    @Test
    public fun `the tag is bit exact for every 32-byte value, including negative ones`() {
        // Two's complement at a fixed width is a bijection, so the property worth pinning is that
        // a generated tag survives unchanged: any change to it is a failed confirmation.
        val samples = listOf(
            ByteArray(32) { 0x00 },          // zero
            ByteArray(32) { 0xFF.toByte() },  // -1
            ByteArray(32) { 0x80.toByte() },  // negative, high bit set
            ByteArray(32) { 0x7F.toByte() },  // positive
            ByteArray(32) { index -> (index * 7 + 3).toByte() },
        )
        for (bytes in samples) {
            val decoded = requireNotNull(ModpJpakeFrames.decodeTag(bytes))
            val recoded = requireNotNull(ModpJpakeFrames.encodeTag(decoded))
            assertTrue("a 32-byte tag must round-trip exactly", recoded.contentEquals(bytes))
        }

        val minusOne = requireNotNull(ModpJpakeFrames.encodeTag(BigInteger.valueOf(-1)))
        assertTrue("a negative tag is padded with 0xFF", minusOne.all { it == 0xFF.toByte() })

        val plusOne = requireNotNull(ModpJpakeFrames.encodeTag(BigInteger.ONE))
        assertEquals(0x00.toByte(), plusOne[0])
        assertEquals(0x01.toByte(), plusOne[31])

        assertNull(ModpJpakeFrames.decodeTag(ByteArray(31)))
        assertNull(ModpJpakeFrames.decodeTag(ByteArray(33)))
        assertNull(ModpJpakeFrames.encodeTag(BigInteger.ONE.shiftLeft(600)))
    }

    // --- helpers ------------------------------------------------------------------------

    private val secureSessionId = SessionId("0123456789abcdef0123456789abcdef")
    private val controlSessionId = "ctl-session"

    private fun idsFor(seed: Int): JpakeParticipantIds =
        JpakeBinding.participantIds(ByteArray(32) { seed.toByte() })

    private fun session(
        role: SecurePeerRole,
        ids: JpakeParticipantIds,
        attempt: PakeAttempt = attemptFor(0x11),
        code: String = "7KQF2XPD",
    ): ModpJpakeSession {
        val chars = code.toCharArray()
        try {
            return ModpJpakeSession(role, attempt, ids, secureSessionId, controlSessionId, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    /**
     * One ledger attempt. Both sessions of a pair share it, because in production the responder
     * learns the identifier from the initiator's attempt-start record rather than inventing one.
     */
    private fun attemptFor(seed: Int): PakeAttempt {
        val ledger = PakeAttemptLedger(ByteArray(16) { seed.toByte() })
        return requireNotNull(ledger.beginAttempt(SecureEntropy { destination -> destination.fill(seed.toByte()) }))
    }

    private fun newPair(contextByte: Int = 0x11): Pair<ModpJpakeSession, ModpJpakeSession> {
        val ids = idsFor(contextByte)
        val attempt = attemptFor(contextByte)
        return session(SecurePeerRole.INITIATOR, ids, attempt) to session(SecurePeerRole.RESPONDER, ids, attempt)
    }

    private fun advanceToConfirmation(initiator: ModpJpakeSession, responder: ModpJpakeSession) {
        val i1 = initiator.createRoundOne()
        val r1 = responder.createRoundOne()
        initiator.acceptRoundOne(r1)
        responder.acceptRoundOne(i1)
        val i2 = initiator.createRoundTwo()
        val r2 = responder.createRoundTwo()
        initiator.acceptRoundTwo(r2)
        responder.acceptRoundTwo(i2)
    }

    private fun decode(frame: ByteArray): JpakeMessage {
        val result = JpakeMessageCodec.decode(
            frame.copyOfRange(JpakeMessageCodec.HEADER_BYTES, frame.size),
        )
        return (result as JpakeMessageResult.Success).message
    }

    /**
     * Re-emits a frame with header fields changed, through the real codec.
     *
     * Header fields are rebuilt rather than patched, so the hostile frame differs from the honest
     * one only where the test says it does.
     */
    private fun rebuild(
        frame: ByteArray,
        participantId: String? = null,
        secureSessionId: SessionId? = null,
        controlSessionId: String? = null,
        suite: PairingAuthenticationCapability? = null,
        attemptId: PakeAttemptId? = null,
        attemptOrdinal: Int? = null,
        codeInstance: ByteArray? = null,
    ): ByteArray {
        val original = decode(frame)
        val message = JpakeMessage(
            suite = suite ?: original.suite,
            profile = original.profile,
            round = original.round,
            role = original.role,
            secureSessionId = secureSessionId ?: original.secureSessionId,
            controlSessionId = controlSessionId ?: original.controlSessionId,
            participantId = participantId ?: original.participantId,
            attemptId = attemptId ?: original.attemptId,
            attemptOrdinal = attemptOrdinal ?: original.attemptOrdinal,
            codeInstanceId = codeInstance ?: original.codeInstanceBytes(),
            body = original.body,
        )
        return requireNotNull(JpakeMessageCodec.encode(message))
    }

    /**
     * Re-emits a frame whose group element or proof scalar is out of range.
     *
     * The bytes are patched in place rather than rebuilt through the codec, and that is the point:
     * the codec refuses to *encode* an all-zero element, so a hostile frame like this cannot be
     * produced by the project's own writer. It can only arrive from a peer, which is exactly what
     * this test is modelling. Patching is length-preserving, so the frame stays well formed.
     */
    private fun rebuildNumbers(
        frame: ByteArray,
        element: BigInteger? = null,
        scalar: BigInteger? = null,
    ): ByteArray {
        val round = decode(frame).round
        var out = frame
        if (element != null) {
            val tag = if (round == JpakeRound.ROUND_ONE) TAG_ROUND1_GX1 else TAG_ROUND2_A
            out = patchField(out, tag, writeWidth(element, ModpJpakeFrames.ELEMENT_BYTES))
        }
        if (scalar != null) {
            val tag = if (round == JpakeRound.ROUND_ONE) TAG_ROUND1_R1 else TAG_ROUND2_R
            out = patchField(out, tag, writeWidth(scalar, ModpJpakeFrames.SCALAR_BYTES))
        }
        return out
    }

    private fun writeWidth(value: BigInteger, width: Int): ByteArray {
        val out = ByteArray(width)
        var remaining = value
        var index = width - 1
        while (remaining.signum() > 0 && index >= 0) {
            out[index] = remaining.and(BigInteger.valueOf(0xFF)).toByte()
            remaining = remaining.shiftRight(8)
            index--
        }
        return out
    }

    /**
     * Changes the profile byte to a value no profile uses.
     *
     * The profile is not selectable through any public constructor -- the enum has one member --
     * so this is the one hostile header vector that has to be byte-patched.
     */
    private fun tamperProfileByte(frame: ByteArray, newValue: Int): ByteArray =
        patchField(frame, PROFILE_TAG, byteArrayOf(newValue.toByte()))

    /**
     * Overwrites one TLV value in place, requiring the replacement to be the same width.
     *
     * The TLV is located by walking the frame, not by hard-coding an offset, so a layout change
     * fails this loudly instead of silently patching the wrong bytes.
     */
    private fun patchField(frame: ByteArray, wantedTag: Int, value: ByteArray): ByteArray {
        val copy = frame.copyOf()
        var position = JpakeMessageCodec.HEADER_BYTES + 5 // length prefix + magic + version
        while (position + 4 <= copy.size) {
            val tag = ((copy[position].toInt() and 0xFF) shl 8) or (copy[position + 1].toInt() and 0xFF)
            val length = ((copy[position + 2].toInt() and 0xFF) shl 8) or (copy[position + 3].toInt() and 0xFF)
            if (tag == wantedTag) {
                require(length == value.size) {
                    "field 0x${wantedTag.toString(16)} is $length bytes, not ${value.size}"
                }
                System.arraycopy(value, 0, copy, position + 4, value.size)
                return copy
            }
            position += 4 + length
        }
        throw IllegalStateException("field 0x${wantedTag.toString(16)} not found")
    }

    private fun assertRound(frame: ByteArray, round: JpakeRound, role: SecurePeerRole) {
        val message = decode(frame)
        assertEquals(round, message.round)
        assertEquals(role, message.role)
        assertEquals(PairingAuthenticationPolicy.REQUIRED_SUITE, message.suite)
        assertEquals(JpakeCurveProfile.MODP_3072, message.profile)
    }

    private fun threwIllegalState(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (_: IllegalStateException) {
        true
    }

    private companion object {
        const val PROFILE_TAG = 0x0002
        const val TAG_ROUND1_GX1 = 0x0010
        const val TAG_ROUND1_R1 = 0x0013
        const val TAG_ROUND2_A = 0x0020
        const val TAG_ROUND2_R = 0x0022
    }
}
