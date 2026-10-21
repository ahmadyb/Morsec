/*
 * The authenticated-control authority: one single-use token produced only by a completed exchange.
 *
 * WHY THIS TYPE EXISTS AT ALL
 *   Before this file, "authenticated" was an implicit property of the objects the coordinator
 *   returned: an `SSLSocket` and a `SecureRecordLayer`. That is true as far as it goes -- neither can
 *   exist without a completed handshake -- but nothing stopped a future caller inside the module from
 *   building a record layer from an unauthenticated exporter and treating it as equivalent. The
 *   authority makes the requirement a value: one object, issued once, claimable once, and absent
 *   unless every step of the chain actually produced its evidence.
 *
 * WHAT IT PROVES, IN ORDER
 *   1. TLS 1.3 completed, and the negotiated protocol is exactly `TLSv1.3`.
 *   2. The MST2 transcript digest is the one the exchange used.
 *   3. A MODP J-PAKE session reached CONFIRMED, which happens only after the partner's round-three
 *      tag validated. Both peers check their own; two CONFIRMED sessions are mutual confirmation.
 *   4. That session belongs to this exact attempt, in this exact role.
 *   5. The local decision was APPROVE, and the remote approval record says APPROVE and binds to this
 *      exact attempt, transcript, session identifiers, suite, profile and opposite role.
 *   6. The record layer exists and its session id matches.
 *   7. The attempt has not already been settled, and the session has not expired.
 *
 * WHAT IT DELIBERATELY CANNOT DO
 *   It confers control only. It exposes no payload capability, it is not consulted by the payload
 *   gate, and that gate still refuses unconditionally. There is no method here that returns a
 *   payload sink, a chunk writer or a file channel, and no flag that could be read as permission for
 *   one. The type deliberately does not name those APIs even in comments, so the static verifier can
 *   keep treating any occurrence of them in this file as a defect rather than a documentation
 *   style choice.
 *
 * ACCESSIBILITY
 *   The class is `internal` to `:transport-lan` -- the module that owns the channel, the record layer,
 *   the coordinator and the evidence -- and its constructor is `private`. The only way to obtain one
 *   is [issue], which takes concrete objects rather than booleans. A caller outside this module cannot
 *   name the type at all; a caller inside it cannot fabricate one without the objects a real exchange
 *   produces. `tools/verify/secure-authority-surface.mjs` checks both properties mechanically,
 *   because "it is internal" is a claim the compiler enforces only until someone adds a public
 *   factory.
 *
 * WHY THE CHANNEL IS A `Closeable` AND NOT AN `SSLSocket`
 *   The authority decides *whether* a caller may act, not *what* the caller acts through. Keeping the
 *   socket out of this type means the type has no transport-specific surface to leak, and it makes the
 *   type constructible in a unit test without opening a socket -- which matters, because the tests for
 *   a security boundary should not need a network to exercise a refusal.
 *
 * TEST-ONLY CONSTRUCTION
 *   There is none. Tests obtain an authority the same way production does: by assembling the concrete
 *   evidence a real exchange produces, or by driving one. There is no `@VisibleForTesting`
 *   constructor, no bypass flag and no relaxed overload.
 *
 * STATUS
 *   Experimental, project-defined and not independently cryptographically reviewed. The owner
 *   accepted that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single-use result of a fully authenticated pairing.
 *
 * [claim] hands out the control channel exactly once. After that the authority is spent: a second
 * call returns null. That is what makes "authenticated" a one-shot transition rather than a status
 * flag a caller can read twice and act on twice.
 */
internal class AuthenticatedControlAuthority private constructor(
    private val channel: Closeable,
    private val recordLayer: SecureRecordLayer,
    private val controlSession: NegotiatedSession,
    private val transcriptDigest: ByteArray,
    val expiryElapsedMillis: Long,
) {
    private val claimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** The control session this authority was issued for. It still reports control-only security. */
    val authenticatedControlSession: NegotiatedSession get() = controlSession

    fun isClaimed(): Boolean = claimed.get()

    fun isClosed(): Boolean = closed.get()

    /**
     * Transfers the channel to the caller, once.
     *
     * Returns null when already claimed or closed. Closing this authority after a claim also closes
     * the channel that was handed out -- deliberate, so an abandoned authority cannot leave a live
     * encrypted session behind it.
     */
    fun claim(): AuthenticatedControlChannel? {
        if (closed.get()) return null
        if (!claimed.compareAndSet(false, true)) return null
        return AuthenticatedControlChannel(
            channel = channel,
            recordLayer = recordLayer,
            controlSession = controlSession,
            expiresAtElapsedMillis = expiryElapsedMillis,
        )
    }

    /** Releases the channel. Idempotent, and safe to call before or after a claim. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            recordLayer.close()
        } catch (_: Exception) {
            // Closing always stays redacted and idempotent.
        }
        try {
            channel.close()
        } catch (_: Exception) {
            // A channel that is already gone is the state we wanted.
        }
        transcriptDigest.fill(0)
    }

    override fun toString(): String =
        "AuthenticatedControlAuthority(claimed=${claimed.get()}, closed=${closed.get()}, ids=[redacted])"

    /**
     * What a caller receives after claiming: the authenticated control channel.
     *
     * Nested inside the authority, with a private constructor, so the only way to hold one is to have
     * claimed an [AuthenticatedControlAuthority]. The type is deliberately narrow -- it carries what
     * the control-record exchange needs and names no payload capability.
     */
    internal class AuthenticatedControlChannel private constructor(
        val channel: Closeable,
        val recordLayer: SecureRecordLayer,
        val controlSession: NegotiatedSession,
        val expiresAtElapsedMillis: Long,
    ) {
        override fun toString(): String = "AuthenticatedControlChannel(ids=[redacted])"
    }

    companion object {
        /** The one protocol this authority accepts. Anything else is a refusal, not a downgrade. */
        private const val REQUIRED_TLS_PROTOCOL = "TLSv1.3"

        private const val TRANSCRIPT_DIGEST_BYTES = 32

        /**
         * Issues an authority, or null if any link in the chain is missing.
         *
         * Every evidence parameter is a concrete object produced by a completed step. There is no
         * boolean anywhere in this signature, which is the point: a caller cannot say "the PAKE
         * succeeded". It can only supply a session in the CONFIRMED phase, and that phase is reachable
         * only by validating the partner's round-three tag.
         */
        fun issue(
            channel: Closeable,
            recordLayer: SecureRecordLayer,
            controlSession: NegotiatedSession,
            tlsProtocol: String,
            transcriptDigest: ByteArray,
            attempt: PakeAttempt,
            pakeSession: ModpJpakeSession,
            localDecision: SecureApprovalDecision?,
            remoteApproval: PakeApproval?,
            role: SecurePeerRole,
            pairStartedElapsedMillis: Long,
            nowElapsedMillis: Long,
        ): AuthenticatedControlAuthority? {
            if (tlsProtocol != REQUIRED_TLS_PROTOCOL) return null
            if (transcriptDigest.size != TRANSCRIPT_DIGEST_BYTES) return null
            if (pairStartedElapsedMillis < 0L || nowElapsedMillis < pairStartedElapsedMillis) return null
            // The lifetime bound is measured from the start of the pairing, not from this call: a
            // caller that reached the end of a very long exchange does not get a fresh full lifetime
            // for having done so.
            val expiry = saturatingAdd(pairStartedElapsedMillis, SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS)
            if (nowElapsedMillis >= expiry) return null
            if (localDecision != SecureApprovalDecision.APPROVE) return null
            val approval = remoteApproval ?: return null
            if (approval.decision != PakeApprovalDecision.APPROVE) return null

            // The PAKE session must be the one this attempt produced, in the confirmed phase, for the
            // same role. A confirmed session belonging to another attempt proves nothing about this
            // one, and a confirmed session in the other role proves only that the peer is present.
            if (pakeSession.attempt !== attempt) return null
            if (pakeSession.role != role) return null
            if (!pakeSession.isConfirmed()) return null
            if (attempt.isSettled()) return null

            // The approval must name exactly this exchange. `bindsTo` compares the attempt id, its
            // ordinal, the code instance, the transcript digest, both session identifiers, the suite,
            // the profile and the peer role, so a record for a different attempt, a different session
            // or the sender's own role cannot satisfy it.
            val codeInstance = attempt.codeInstanceBytes()
            val approvalCodeInstance = approval.codeInstanceBytes()
            val approvalTranscript = approval.transcriptDigestBytes()
            val binds = try {
                approval.bindsTo(
                    expectedAttemptId = attempt.id,
                    expectedOrdinal = attempt.ordinal,
                    expectedCodeInstance = codeInstance,
                    expectedTranscriptDigest = transcriptDigest,
                    expectedSecureSessionId = recordLayer.sessionId,
                    expectedControlSessionId = controlSession.attemptId.value,
                    expectedSuite = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
                    expectedProfile = JpakeCurveProfile.MODP_3072,
                    expectedPeerRole = role.opposite,
                )
            } finally {
                codeInstance.fill(0)
                approvalCodeInstance.fill(0)
                approvalTranscript.fill(0)
            }
            if (!binds) return null

            return AuthenticatedControlAuthority(
                channel = channel,
                recordLayer = recordLayer,
                controlSession = controlSession,
                transcriptDigest = transcriptDigest.copyOf(),
                expiryElapsedMillis = expiry,
            )
        }

        private fun saturatingAdd(value: Long, duration: Long): Long =
            if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration
    }
}
