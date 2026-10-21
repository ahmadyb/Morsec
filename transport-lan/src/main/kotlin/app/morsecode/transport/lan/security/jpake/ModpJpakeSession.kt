/*
 * The MODP J-PAKE round driver: three rounds of MPS3 frames over a library participant.
 *
 * WHY THE FINITE-FIELD VARIANT
 *   The elliptic-curve variant cannot be driven from received bytes. `ECSchnorrZKP` has a
 *   package-private constructor and Bouncy Castle exposes no decoder, so no public API can rebuild
 *   an `ECJPAKERound1Payload` from a frame. The MODP variant has no nested proof type -- a proof is
 *   a plain `BigInteger[]` -- so every round is reconstructable through public constructors.
 *   Measured on API 23 before this file existed:
 *   doc/security/bc-ecjpake-reconstruction-audit-2026-10-20.md.
 *
 * WHAT THIS CLASS IS RESPONSIBLE FOR
 *   1. Turning received frames into library payloads, with the participant identity and the session
 *      checked *before* the library sees anything.
 *   2. Range-checking every received integer before the library sees it.
 *   3. Sequencing the rounds so the library is never asked to do something out of order.
 *   4. Failing closed: any refusal closes the session and yields a coarse, typed reason.
 *
 * WHAT THIS CLASS DELIBERATELY DOES NOT DO
 *   It mints no authority, derives no payload keys and exposes no keying material. It is not wired
 *   to the coordinator, the attempt limiter or the state machine yet. It does not remove the SAS
 *   path. Those are later slices, and none of them can be reached from here.
 *
 * THREADING
 *   One participant per session, and a participant is not thread-safe. Drive one session from one
 *   coroutine. There is no internal locking, by design: a lock would suggest a concurrency contract
 *   this class does not have.
 *
 * ERASURE
 *   The library clears its own password copy when the keying material is calculated, and nulls the
 *   ephemeral private values without zeroing them. The keying material is a `BigInteger` and cannot
 *   be wiped at all; this class drops its reference on close and says so, rather than pretending to
 *   erase it. Callers must zero the code array they pass in.
 *
 * STATUS
 *   Experimental and not independently cryptographically reviewed. The owner accepted that risk on
 *   2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.jpake

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.JpakeHeaderResult
import app.morsecode.core.transfer.session.JpakeMessage
import app.morsecode.core.transfer.session.JpakeMessageCodec
import app.morsecode.core.transfer.session.JpakeMessageResult
import app.morsecode.core.transfer.session.JpakeParticipantIds
import app.morsecode.core.transfer.session.JpakeRound
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.JpakeRoundBody
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.SecurePeerRole
import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.crypto.CryptoException
import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import org.bouncycastle.crypto.digests.SHA256Digest

/**
 * One side of one experimental MODP J-PAKE exchange.
 *
 * The caller supplies the channel-bound participant identifiers derived from the authentication
 * context. Both peers derive the same pair, so each knows its own identifier and its partner's
 * before a single byte moves -- which is what lets every received frame be checked against the
 * expected partner without trusting anything in the frame itself.
 *
 * @param role this side's role. The partner must be [SecurePeerRole.opposite].
 * @param attempt the ledger-issued attempt this session belongs to. Its identifier, ordinal and
 *        code-instance id are carried in every frame and compared before any integer is decoded, so
 *        a frame from a previous attempt against the same code cannot reach the library.
 * @param participantIds both identifiers, derived from the authentication context.
 * @param secureSessionId the 128-bit session identifier bound into every frame.
 * @param controlSessionId the control-session identifier bound into every frame.
 * @param code the human-entered secret. The library copies it; the caller must zero the array.
 * @param random entropy for the library's ephemeral values.
 */
public class ModpJpakeSession(
    public val role: SecurePeerRole,
    public val attempt: PakeAttempt,
    participantIds: JpakeParticipantIds,
    private val secureSessionId: SessionId,
    private val controlSessionId: String,
    code: CharArray,
    random: SecureRandom = SecureRandom(),
) {

    /** Where this session is in the exchange. `CONFIRMED` means the library verified the tag. */
    public enum class Phase {
        NEW,
        ROUND_ONE_CREATED,
        ROUND_ONE_VALIDATED,
        ROUND_TWO_CREATED,
        KEY_DERIVED,
        CONFIRMATION_CREATED,
        CONFIRMED,
        CLOSED,
    }

    /**
     * Why a step was refused.
     *
     * Deliberately coarse. The remote side must not learn which check failed: an attacker who
     * can distinguish "wrong identifier" from "bad proof" from "out of range" gets a free oracle
     * for probing the exchange. Local misuse keeps its own value because it is not reachable by
     * an attacker.
     */
    public enum class Reject {
        /** The frame is well formed but does not belong to this session or partner. */
        CONTEXT_MISMATCH,

        /** The frame could not be decoded, or a field was out of its permitted range. */
        FRAME_INVALID,

        /** The library refused the payload: proof, confirmation tag or identity. */
        CRYPTO_FAILED,

        /** This side called the steps out of order. Not reachable by a remote peer. */
        STATE_INVALID,

        /** The session has already closed. */
        CLOSED,
    }

    /** The result of an inbound step. */
    public sealed interface Step {
        public object Accepted : Step

        public data class Rejected(public val reason: Reject) : Step
    }

    /** This side's identifier. Not for diagnostics; log the redacted session, never the id. */
    internal val ownParticipantId: String = participantIds.forRole(role)

    private val partnerParticipantId: String = participantIds.partnerOf(role)

    private var engine: JPAKEParticipant? = JPAKEParticipant(
        ownParticipantId,
        code,
        ModpJpakeFrames.GROUP,
        SHA256Digest(),
        random,
    )

    /**
     * The shared secret, held only to compute and check the round-three tag.
     *
     * Never returned, never logged, never used to derive anything. A `BigInteger` cannot be wiped;
     * dropping the reference is the strongest erasure available on this platform, and claiming
     * more would be false.
     */
    private var keyingMaterial: BigInteger? = null

    public var phase: Phase = Phase.NEW
        private set

    /** True only after the library verified the partner's round-three tag. Evidence, not authority. */
    public fun isConfirmed(): Boolean = phase == Phase.CONFIRMED

    /** True once the session has closed for any reason, including success being followed by a close. */
    public fun isClosed(): Boolean = phase == Phase.CLOSED

    // --- outbound ---------------------------------------------------------------------

    /** Creates this side's round-one frame. */
    public fun createRoundOne(): ByteArray {
        requirePhase(Phase.NEW)
        val payload = requireEngine().createRound1PayloadToSend()
        requireEngineState(JPAKEParticipant.STATE_ROUND_1_CREATED)
        val body = JpakeRoundBody.RoundOne(
            element(payload.gx1),
            element(payload.gx2),
            element(payload.knowledgeProofForX1[0]),
            scalar(payload.knowledgeProofForX1[1]),
            element(payload.knowledgeProofForX2[0]),
            scalar(payload.knowledgeProofForX2[1]),
        )
        phase = Phase.ROUND_ONE_CREATED
        return frameFor(JpakeRound.ROUND_ONE, body)
    }

    /** Creates this side's round-two frame. */
    public fun createRoundTwo(): ByteArray {
        requirePhase(Phase.ROUND_ONE_VALIDATED)
        val payload = requireEngine().createRound2PayloadToSend()
        requireEngineState(JPAKEParticipant.STATE_ROUND_2_CREATED)
        val body = JpakeRoundBody.RoundTwo(
            element(payload.a),
            element(payload.knowledgeProofForX2s[0]),
            scalar(payload.knowledgeProofForX2s[1]),
        )
        phase = Phase.ROUND_TWO_CREATED
        return frameFor(JpakeRound.ROUND_TWO, body)
    }

    /**
     * Derives the shared secret and creates this side's round-three confirmation tag.
     *
     * Derivation happens here rather than in an accessor so that the secret is never handed out:
     * the only thing a caller ever receives is the frame carrying the tag.
     */
    public fun createConfirmation(): ByteArray {
        requirePhase(Phase.KEY_DERIVED)
        val secret = keyingMaterial ?: internalFailure("keying material missing")
        val payload = requireEngine().createRound3PayloadToSend(secret)
        requireEngineState(JPAKEParticipant.STATE_ROUND_3_CREATED)
        val body = JpakeRoundBody.RoundThree(
            ModpJpakeFrames.encodeTag(payload.macTag) ?: internalFailure("tag width"),
        )
        phase = Phase.CONFIRMATION_CREATED
        return frameFor(JpakeRound.ROUND_THREE, body)
    }

    // --- inbound ----------------------------------------------------------------------

    /** Accepts the partner's round-one frame. */
    public fun acceptRoundOne(frame: ByteArray): Step {
        if (phase != Phase.ROUND_ONE_CREATED) {
            return localPhaseRejection()
        }
        return when (val decoded = decodeFor(frame, JpakeRound.ROUND_ONE)) {
            is Decoded.Refused -> fail(decoded.reason)
            is Decoded.Message -> {
                val body = decoded.message.body as JpakeRoundBody.RoundOne
                val gx1 = ModpJpakeFrames.decodeElement(body.gx1Bytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val gx2 = ModpJpakeFrames.decodeElement(body.gx2Bytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val v1 = ModpJpakeFrames.decodeElement(body.proofOneElementBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val r1 = ModpJpakeFrames.decodeScalar(body.proofOneScalarBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val v2 = ModpJpakeFrames.decodeElement(body.proofTwoElementBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val r2 = ModpJpakeFrames.decodeScalar(body.proofTwoScalarBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val accepted = validate {
                    requireEngine().validateRound1PayloadReceived(
                        JPAKERound1Payload(
                            partnerParticipantId,
                            gx1,
                            gx2,
                            arrayOf(v1, r1),
                            arrayOf(v2, r2),
                        ),
                    )
                }
                if (!accepted) return fail(Reject.CRYPTO_FAILED)
                requireEngineState(JPAKEParticipant.STATE_ROUND_1_VALIDATED)
                phase = Phase.ROUND_ONE_VALIDATED
                Step.Accepted
            }
        }
    }

    /** Accepts the partner's round-two frame and derives the shared secret. */
    public fun acceptRoundTwo(frame: ByteArray): Step {
        if (phase != Phase.ROUND_TWO_CREATED) {
            return localPhaseRejection()
        }
        return when (val decoded = decodeFor(frame, JpakeRound.ROUND_TWO)) {
            is Decoded.Refused -> fail(decoded.reason)
            is Decoded.Message -> {
                val body = decoded.message.body as JpakeRoundBody.RoundTwo
                val a = ModpJpakeFrames.decodeElement(body.elementBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val v = ModpJpakeFrames.decodeElement(body.proofElementBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val r = ModpJpakeFrames.decodeScalar(body.proofScalarBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val accepted = validate {
                    requireEngine().validateRound2PayloadReceived(
                        JPAKERound2Payload(partnerParticipantId, a, arrayOf(v, r)),
                    )
                }
                if (!accepted) return fail(Reject.CRYPTO_FAILED)
                requireEngineState(JPAKEParticipant.STATE_ROUND_2_VALIDATED)
                // Derive immediately: the library clears its password copy here, and holding the
                // secret only for the confirmation step keeps its lifetime as short as the API
                // allows.
                keyingMaterial = requireEngine().calculateKeyingMaterial()
                requireEngineState(JPAKEParticipant.STATE_KEY_CALCULATED)
                phase = Phase.KEY_DERIVED
                Step.Accepted
            }
        }
    }

    /** Accepts the partner's round-three confirmation tag. */
    public fun acceptConfirmation(frame: ByteArray): Step {
        if (phase != Phase.CONFIRMATION_CREATED) {
            return localPhaseRejection()
        }
        return when (val decoded = decodeFor(frame, JpakeRound.ROUND_THREE)) {
            is Decoded.Refused -> fail(decoded.reason)
            is Decoded.Message -> {
                val body = decoded.message.body as JpakeRoundBody.RoundThree
                val tag = ModpJpakeFrames.decodeTag(body.tagBytes())
                    ?: return fail(Reject.FRAME_INVALID)
                val secret = keyingMaterial ?: internalFailure("keying material missing")
                val accepted = validate {
                    requireEngine().validateRound3PayloadReceived(
                        JPAKERound3Payload(partnerParticipantId, tag),
                        secret,
                    )
                }
                if (!accepted) return fail(Reject.CRYPTO_FAILED)
                requireEngineState(JPAKEParticipant.STATE_ROUND_3_VALIDATED)
                phase = Phase.CONFIRMED
                Step.Accepted
            }
        }
    }

    /**
     * Closes the session and drops every reference it holds.
     *
     * Idempotent. The library has no destroy API, so its internal state is released by dropping the
     * reference; anything it had already nulled stays nulled and anything it did not cannot be
     * wiped by anyone.
     */
    public fun close() {
        engine = null
        keyingMaterial = null
        phase = Phase.CLOSED
    }

    override fun toString(): String =
        "ModpJpakeSession(role=${role.name}, phase=${phase.name}, ids=[redacted])"

    // --- internals --------------------------------------------------------------------

    private sealed class Decoded {
        class Message(val message: JpakeMessage) : Decoded()
        class Refused(val reason: Reject) : Decoded()
    }

    /**
     * Decodes a frame and proves it belongs to this session.
     *
     * Order is the point. Framing first, then identity and context, and only then is anything
     * turned into an integer the library will see. The participant identifier is compared against
     * the one this side derived from the authentication context -- so a payload replayed from
     * another TLS channel, another session or the other role names the wrong identifier and is
     * refused here, before a single group operation happens.
     */
    private fun decodeFor(frame: ByteArray, round: JpakeRound): Decoded {
        if (frame.size < JpakeMessageCodec.HEADER_BYTES) return Decoded.Refused(Reject.FRAME_INVALID)
        if (frame.size > JpakeMessageCodec.HEADER_BYTES + JpakeMessageCodec.MAX_FRAME_BYTES) {
            return Decoded.Refused(Reject.FRAME_INVALID)
        }
        val header = JpakeMessageCodec.decodeHeader(
            frame.copyOfRange(0, JpakeMessageCodec.HEADER_BYTES),
        )
        if (header !is JpakeHeaderResult.Valid) return Decoded.Refused(Reject.FRAME_INVALID)
        if (frame.size != JpakeMessageCodec.HEADER_BYTES + header.payloadLength) {
            return Decoded.Refused(Reject.FRAME_INVALID)
        }
        val decoded = JpakeMessageCodec.decode(
            frame.copyOfRange(JpakeMessageCodec.HEADER_BYTES, frame.size),
        )
        if (decoded !is JpakeMessageResult.Success) return Decoded.Refused(Reject.FRAME_INVALID)
        val message = decoded.message

        if (message.suite != PairingAuthenticationPolicy.REQUIRED_SUITE) {
            return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        }
        if (message.profile != JpakeCurveProfile.MODP_3072) {
            return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        }
        if (message.round != round) return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        if (message.role != role.opposite) return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        if (message.secureSessionId != secureSessionId) return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        if (message.controlSessionId != controlSessionId) {
            return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        }
        if (message.participantId != partnerParticipantId) {
            return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        }
        // The attempt identity is checked unconditionally, not only when an ordinal happens to
        // differ. A frame from an earlier attempt against the same displayed code carries a stale
        // identifier and is refused here, before an integer is decoded.
        if (message.attemptId != attempt.id) return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        if (message.attemptOrdinal != attempt.ordinal) return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        val receivedCodeInstance = message.codeInstanceBytes()
        val codeInstanceMatches = try {
            receivedCodeInstance.contentEquals(attempt.codeInstanceBytes())
        } finally {
            receivedCodeInstance.fill(0)
        }
        if (!codeInstanceMatches) return Decoded.Refused(Reject.CONTEXT_MISMATCH)
        return Decoded.Message(message)
    }

    private fun frameFor(round: JpakeRound, body: JpakeRoundBody): ByteArray {
        val message = JpakeMessage(
            suite = PairingAuthenticationPolicy.REQUIRED_SUITE,
            profile = JpakeCurveProfile.MODP_3072,
            round = round,
            role = role,
            secureSessionId = secureSessionId,
            controlSessionId = controlSessionId,
            participantId = ownParticipantId,
            attemptId = attempt.id,
            attemptOrdinal = attempt.ordinal,
            codeInstanceId = attempt.codeInstanceBytes(),
            body = body,
        )
        return JpakeMessageCodec.encode(message) ?: internalFailure("frame exceeds the bound")
    }

    /**
     * Runs a library validation, collapsing every refusal into one answer.
     *
     * True when the library accepted. Which of the library's many failure messages was produced is
     * not preserved: it would only describe the failure to an observer, and the caller maps every
     * refusal to the same [Reject.CRYPTO_FAILED].
     */
    private fun validate(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: CryptoException) {
        false
    } catch (_: IllegalArgumentException) {
        // The library's payload constructors validate their arguments.
        false
    }

    private fun element(value: BigInteger): ByteArray =
        ModpJpakeFrames.encodeElement(value) ?: internalFailure("element width")

    private fun scalar(value: BigInteger): ByteArray =
        ModpJpakeFrames.encodeScalar(value) ?: internalFailure("scalar width")

    private fun requireEngine(): JPAKEParticipant =
        engine ?: internalFailure("session is closed")

    /**
     * Asserts that the library advanced exactly as expected.
     *
     * A mismatch means the pinned library no longer behaves as this adapter assumes, which is the
     * failure mode that a byte-pinned dependency lock cannot rule out by itself. Failing closed
     * and loudly is better than continuing on an assumption that is no longer true.
     */
    private fun requireEngineState(expected: Int) {
        val actual = requireEngine().state
        if (actual != expected) {
            internalFailure("library state $actual, expected $expected")
        }
    }

    private fun requirePhase(expected: Phase) {
        if (phase == Phase.CLOSED) throw IllegalStateException("session is closed")
        if (phase != expected) {
            throw IllegalStateException("expected phase $expected but was $phase")
        }
    }

    /**
     * Refuses a step this side called in the wrong phase.
     *
     * A repeated or out-of-order inbound frame is a protocol violation -- a replay is exactly that
     * -- so the session closes rather than staying open for a retry.
     */
    private fun localPhaseRejection(): Step {
        if (phase == Phase.CLOSED) return Step.Rejected(Reject.CLOSED)
        close()
        return Step.Rejected(Reject.STATE_INVALID)
    }

    private fun fail(reason: Reject): Step {
        close()
        return Step.Rejected(reason)
    }

    private fun internalFailure(detail: String): Nothing {
        close()
        throw IllegalStateException("J-PAKE adapter invariant violated: $detail")
    }
}
