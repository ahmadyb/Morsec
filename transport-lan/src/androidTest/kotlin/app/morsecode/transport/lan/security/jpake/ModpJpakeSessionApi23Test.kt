package app.morsecode.transport.lan.security.jpake

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.JpakeBinding
import app.morsecode.core.transfer.session.JpakeMessage
import app.morsecode.core.transfer.session.JpakeMessageCodec
import app.morsecode.core.transfer.session.JpakeMessageResult
import app.morsecode.core.transfer.session.JpakeParticipantIds
import app.morsecode.core.transfer.session.JpakeRound
import app.morsecode.core.transfer.session.SecurePeerRole
import java.math.BigInteger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The MODP round adapter on API 23, inside the real APK.
 *
 * The JVM tests prove the logic; these prove the same logic on the oldest supported platform, with
 * the pinned Bouncy Castle build and the platform's own `SecureRandom`. Both matter: a library that
 * behaves on a desktop JVM and not on API 23 would be discovered by a user rather than by CI.
 *
 * Every exchange is serialized. No payload object crosses from one session to the other.
 *
 * Experimental and not independently cryptographically reviewed. Owner review waiver, 2026-10-19.
 */
@RunWith(AndroidJUnit4::class)
public class ModpJpakeSessionApi23Test {

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun aSerializedExchangeConfirmsOnApi23() {
        val (initiator, responder) = newPair()
        exchange(initiator, responder)
        assertTrue(initiator.isConfirmed())
        assertTrue(responder.isConfirmed())
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun aWrongCodeFailsAtTheLastRound() {
        val ids = idsFor(0x31)
        val initiator = session(SecurePeerRole.INITIATOR, ids, "7KQF2XPD")
        val responder = session(SecurePeerRole.RESPONDER, ids, "9MTR4WBN")
        advanceToConfirmation(initiator, responder)
        val initiatorTag = initiator.createConfirmation()
        val responderTag = responder.createConfirmation()

        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CRYPTO_FAILED),
            initiator.acceptConfirmation(responderTag),
        )
        assertFalse(initiator.isConfirmed())
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CRYPTO_FAILED),
            responder.acceptConfirmation(initiatorTag),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun aReflectedFrameIsRefused() {
        val (initiator, _) = newPair()
        val own = initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(own),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun aFrameFromAnotherSessionIsRefused() {
        val (initiator, _) = newPair()
        val (_, foreignResponder) = newPair(0x32)
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(foreignResponder.createRoundOne()),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun aSubstitutedParticipantIdIsRefusedBeforeTheLibrary() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        val substituted = rebuild(genuine, participantId = "morsec1-r-ffffffffffffffffffffffffffffffff")
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CONTEXT_MISMATCH),
            initiator.acceptRoundOne(substituted),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun outOfRangeElementsAreRefusedBeforeTheLibrary() {
        for (value in listOf(
            BigInteger.ZERO,
            BigInteger.ONE,
            ModpJpakeFrames.MODULUS_P,
            ModpJpakeFrames.MODULUS_P.add(BigInteger.ONE),
        )) {
            val (initiator, responder) = newPair()
            val genuine = responder.createRoundOne()
            initiator.createRoundOne()
            assertEquals(
                ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.FRAME_INVALID),
                initiator.acceptRoundOne(rebuildNumbers(genuine, element = value)),
            )
        }
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun anOutOfRangeProofScalarIsRefusedBeforeTheLibrary() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.FRAME_INVALID),
            initiator.acceptRoundOne(rebuildNumbers(genuine, scalar = ModpJpakeFrames.SUBGROUP_ORDER_Q)),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun anInRangeSubstitutedElementIsRefusedByTheLibrary() {
        val (initiator, responder) = newPair()
        val genuine = responder.createRoundOne()
        initiator.createRoundOne()
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.CRYPTO_FAILED),
            initiator.acceptRoundOne(rebuildNumbers(genuine, element = BigInteger.valueOf(2))),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun truncatedAndOversizedFramesAreRefused() {
        val (_, responder) = newPair()
        val genuine = responder.createRoundOne()
        val cases = listOf(
            ByteArray(0),
            genuine.copyOfRange(0, 4),
            genuine.copyOfRange(0, genuine.size - 1),
            genuine + byteArrayOf(0x00),
            byteArrayOf(0x00, 0x00, 0x10, 0x00) + genuine.copyOfRange(4, genuine.size),
        )
        for (frame in cases) {
            val fresh = newPair().first
            fresh.createRoundOne()
            assertEquals(
                ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.FRAME_INVALID),
                fresh.acceptRoundOne(frame),
            )
        }
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun aReplayedRoundOneIsRefused() {
        val (initiator, responder) = newPair()
        initiator.createRoundOne()
        val genuine = responder.createRoundOne()
        assertEquals(ModpJpakeSession.Step.Accepted, initiator.acceptRoundOne(genuine))
        assertEquals(
            ModpJpakeSession.Step.Rejected(ModpJpakeSession.Reject.STATE_INVALID),
            initiator.acceptRoundOne(genuine),
        )
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun theTagConversionIsBitExactOnApi23() {
        val samples = listOf(
            ByteArray(32) { 0x00 },
            ByteArray(32) { 0xFF.toByte() },
            ByteArray(32) { 0x80.toByte() },
            ByteArray(32) { index -> (index * 11 + 5).toByte() },
        )
        for (bytes in samples) {
            val decoded = requireNotNull(ModpJpakeFrames.decodeTag(bytes))
            assertTrue(requireNotNull(ModpJpakeFrames.encodeTag(decoded)).contentEquals(bytes))
        }
        assertNull(ModpJpakeFrames.decodeTag(ByteArray(31)))
    }

    @Test(timeout = TEST_TIMEOUT_MILLIS)
    public fun fourConcurrentExchangesAllConfirm() {
        val executor = Executors.newFixedThreadPool(CONCURRENCY)
        try {
            val futures = executor.invokeAll(
                (0 until CONCURRENCY).map { index ->
                    Callable {
                        val (initiator, responder) = newPair(0x40 + index)
                        exchange(initiator, responder)
                        initiator.isConfirmed() && responder.isConfirmed()
                    }
                },
                CONCURRENT_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            assertTrue("every concurrent exchange must complete", futures.all { it.isDone })
            assertTrue("every concurrent exchange must confirm", futures.all { it.get() })
        } finally {
            executor.shutdownNow()
        }
    }

    // --- helpers ------------------------------------------------------------------------

    private val secureSessionId = SessionId("0123456789abcdef0123456789abcdef")
    private val controlSessionId = "ctl-session"

    private fun idsFor(seed: Int): JpakeParticipantIds =
        JpakeBinding.participantIds(ByteArray(32) { seed.toByte() })

    private fun session(
        role: SecurePeerRole,
        ids: JpakeParticipantIds,
        code: String = "7KQF2XPD",
    ): ModpJpakeSession {
        val chars = code.toCharArray()
        try {
            return ModpJpakeSession(role, ids, secureSessionId, controlSessionId, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun newPair(seed: Int = 0x30): Pair<ModpJpakeSession, ModpJpakeSession> {
        val ids = idsFor(seed)
        return session(SecurePeerRole.INITIATOR, ids) to session(SecurePeerRole.RESPONDER, ids)
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

    private fun exchange(initiator: ModpJpakeSession, responder: ModpJpakeSession) {
        advanceToConfirmation(initiator, responder)
        val initiatorTag = initiator.createConfirmation()
        val responderTag = responder.createConfirmation()
        initiator.acceptConfirmation(responderTag)
        responder.acceptConfirmation(initiatorTag)
    }

    private fun decode(frame: ByteArray): JpakeMessage {
        val result = JpakeMessageCodec.decode(
            frame.copyOfRange(JpakeMessageCodec.HEADER_BYTES, frame.size),
        )
        return (result as JpakeMessageResult.Success).message
    }

    private fun rebuild(frame: ByteArray, participantId: String? = null): ByteArray {
        val original = decode(frame)
        val message = JpakeMessage(
            suite = original.suite,
            profile = original.profile,
            round = original.round,
            role = original.role,
            secureSessionId = original.secureSessionId,
            controlSessionId = original.controlSessionId,
            participantId = participantId ?: original.participantId,
            body = original.body,
        )
        return requireNotNull(JpakeMessageCodec.encode(message))
    }

    /**
     * Patches an out-of-range element or scalar into an otherwise honest frame.
     *
     * Byte patching, not re-encoding: the codec refuses to write an all-zero element, so a frame
     * like this can only arrive from a peer. Length-preserving, so the frame stays well formed and
     * the only difference under test is the integer.
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

    /** Overwrites one TLV value in place, locating it by walking the frame. */
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

    private companion object {
        const val TAG_ROUND1_GX1 = 0x0010
        const val TAG_ROUND1_R1 = 0x0013
        const val TAG_ROUND2_A = 0x0020
        const val TAG_ROUND2_R = 0x0022
        const val CONCURRENCY = 4
        const val CONCURRENT_TIMEOUT_SECONDS = 180L
        const val TEST_TIMEOUT_MILLIS = 300_000L
    }
}
