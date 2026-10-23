/*
 * The ordered proof path from a validated transcript to an authenticated control session.
 *
 * WHY A SECOND REDUCER
 *   `SecureSessionStateMachine` is the live SAS reducer: it starts at a transcript, waits for a
 *   human to compare a 25-bit short code, and finishes when two key confirmations cross. Its states
 *   are the ones the shipped pairing reports, and the atomic switch will replace it rather than
 *   teach it a second protocol. Until then this file adds the PAKE path's own states *alongside* it:
 *   the live enum is untouched, so nothing the running app reports can change because this exists.
 *
 * THE SEVEN STATES, IN ORDER
 *   TRANSCRIPT_VALIDATED -> PAKE_CONFIRMED -> LOCAL_INTENDED_PEER_APPROVED ->
 *   REMOTE_INTENDED_PEER_APPROVED -> LOCAL_KEY_CONFIRMATION_SENT ->
 *   REMOTE_KEY_CONFIRMATION_VERIFIED -> AUTHENTICATED
 *
 *   No state can be entered out of order, because every transition takes a concrete evidence object
 *   and every evidence object can only be produced by the step it describes. There is no
 *   `advance()` and no boolean: a caller cannot say "the PAKE succeeded", because the only value
 *   that opens PAKE_CONFIRMED is a confirmed library session for *this* attempt and role.
 *
 * WHAT EACH TRANSITION REFUSES, AND WHY IT MATTERS
 *   - Approval before the PAKE is confirmed: refused. Otherwise a human approving a name would be
 *     the only thing standing between an unauthenticated peer and a session.
 *   - Remote approval standing in for the local one: refused. They are separate fields with separate
 *     transitions, and the remote record is checked to name the peer's role, so reflecting the local
 *     record back cannot satisfy it.
 *   - PAKE confirmation standing in for record-layer key confirmation: refused. `AUTHENTICATED`
 *     requires both `LOCAL_KEY_CONFIRMATION_SENT` and `REMOTE_KEY_CONFIRMATION_VERIFIED`, and the
 *     latter is only reachable from a record the AEAD already accepted.
 *   - Duplicate or replayed evidence: refused. Every step is single-use, a repeated local send is a
 *     no-op that returns false, and a second remote confirmation is a failure rather than a
 *     tolerated replay.
 *   - A second authority: impossible. The authority is created once, from `AUTHENTICATED`, and the
 *     reducer refuses everything afterwards.
 *
 * WHAT THIS FILE CANNOT DO
 *   It cannot derive keys, read a socket, or issue anything on its own. It is a deterministic
 *   reducer over evidence handed to it, and the records it inspects are the ones the wire produced.
 *
 * STATUS
 *   Experimental, project-defined and not independently cryptographically reviewed. The owner
 *   accepted that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PairingSelectionV2
import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession
import java.security.MessageDigest

/**
 * The PAKE path's states.
 *
 * Separate from `SecurePairingState` on purpose: that enum is what the shipped pairing reports and
 * the atomic switch is the only commit allowed to change it. This one is internal and unreachable.
 */
internal enum class PakePairingState {
    /** Nothing has been proven. The reducer starts here. */
    TRANSCRIPT_PENDING,
    TRANSCRIPT_VALIDATED,
    PAKE_CONFIRMED,
    LOCAL_INTENDED_PEER_APPROVED,
    REMOTE_INTENDED_PEER_APPROVED,
    LOCAL_KEY_CONFIRMATION_SENT,
    REMOTE_KEY_CONFIRMATION_VERIFIED,
    AUTHENTICATED,
    REJECTED,
    FAILED,
    CANCELLED,
}

/**
 * Evidence that a transcript was validated, for one exact exchange.
 *
 * Private constructor plus an internal factory that validates: a caller can obtain one only by
 * presenting a 32-byte digest and the selection that produced it, and only when that selection names
 * the required suite. There is no public way to construct one, so no caller outside this module can
 * assert a transition it did not perform.
 */
internal class ValidatedTranscriptEvidence private constructor(
    val digest: ByteArray,
    val suite: PairingAuthenticationCapability,
    val profile: JpakeCurveProfile,
    val secureSessionId: SessionId,
) {
    internal companion object {
        fun from(
            digest: ByteArray,
            selection: PairingSelectionV2,
            secureSessionId: SessionId,
        ): ValidatedTranscriptEvidence? {
            if (digest.size != SecureSessionLimits.TRANSCRIPT_HASH_BYTES) return null
            if (!PairingAuthenticationPolicy.isProductionSuite(selection.selectedAuthentication)) return null
            return ValidatedTranscriptEvidence(
                digest = digest.copyOf(),
                suite = selection.selectedAuthentication,
                profile = JpakeCurveProfile.MODP_3072,
                secureSessionId = secureSessionId,
            )
        }
    }
}

/** Evidence that one attempt's PAKE reached CONFIRMED, in one role, for one transcript. */
internal class ConfirmedPakeEvidence private constructor(
    val attempt: PakeAttempt,
    val session: ModpJpakeSession,
    val role: SecurePeerRole,
    val transcriptDigest: ByteArray,
) {
    internal companion object {
        fun from(
            transcriptDigest: ByteArray,
            role: SecurePeerRole,
            session: ModpJpakeSession,
        ): ConfirmedPakeEvidence? {
            if (!session.isConfirmed()) return null
            if (session.role != role) return null
            // The session carries its own attempt. Binding the evidence to it here is what stops a
            // confirmed session from a different attempt being presented as this one's proof.
            val attempt = session.attempt
            // A settled attempt is spent: it was charged, abandoned or succeeded already, and
            // `AuthenticatedControlAuthority.issue` refuses it. Rejecting it here rather than there
            // keeps the failure attributable to the evidence instead of to the last link.
            if (attempt.isSettled()) return null
            return ConfirmedPakeEvidence(
                attempt = attempt,
                session = session,
                role = role,
                transcriptDigest = transcriptDigest.copyOf(),
            )
        }
    }
}

/**
 * The ordered reducer.
 *
 * Single-threaded by contract: one instance drives one pairing on the thread that owns it, and the
 * `@Synchronized` methods exist so a cancellation from the owner's thread is visible without a data
 * race rather than to make the sequence concurrent.
 */
internal class PakePairingProgression(
    private val secureSessionId: SessionId,
    private val localRole: SecurePeerRole,
    private val controlSessionId: String,
) {
    private var state: PakePairingState = PakePairingState.TRANSCRIPT_PENDING
    private var failure: SessionFailureCode? = null
    private var transcriptDigest: ByteArray? = null
    private var attempt: PakeAttempt? = null
    private var localDecision: SecureApprovalDecision? = null
    private var remoteApproval: PakeApproval? = null
    private var localSent: Boolean = false
    private var remoteVerified: Boolean = false
    private var authorityIssued: Boolean = false

    @Synchronized
    fun state(): PakePairingState = state

    @Synchronized
    fun failureCode(): SessionFailureCode? = failure

    @Synchronized
    fun localDecision(): SecureApprovalDecision? = localDecision

    @Synchronized
    fun peerApproval(): PakeApproval? = remoteApproval

    @Synchronized
    fun attempt(): PakeAttempt? = attempt

    @Synchronized
    fun transcript(): ByteArray? = transcriptDigest?.copyOf()

    /** True only before [authenticated] records the issuance. */
    @Synchronized
    fun mayIssueAuthority(): Boolean =
        state == PakePairingState.REMOTE_KEY_CONFIRMATION_VERIFIED && !authorityIssued

    /**
     * Step one. The only entry into TRANSCRIPT_VALIDATED.
     *
     * The evidence must describe this reducer's exact session; a transcript for another session is
     * refused here rather than being bound later by accident.
     */
    @Synchronized
    fun transcriptValidated(evidence: ValidatedTranscriptEvidence): Boolean {
        if (state != PakePairingState.TRANSCRIPT_PENDING) return false
        if (evidence.secureSessionId != secureSessionId) return false
        transcriptDigest = evidence.digest.copyOf()
        state = PakePairingState.TRANSCRIPT_VALIDATED
        return true
    }

    /**
     * Step two, and the only way to leave TRANSCRIPT_VALIDATED.
     *
     * Record-layer key confirmation cannot substitute for this, and neither can the human approval:
     * both are checked against a PAKE-CONFIRMED state that this call is the only source of.
     */
    @Synchronized
    fun pakeConfirmed(evidence: ConfirmedPakeEvidence): Boolean {
        if (state != PakePairingState.TRANSCRIPT_VALIDATED) return false
        if (evidence.role != localRole) return false
        if (!digestMatches(evidence.transcriptDigest)) return false
        attempt = evidence.attempt
        state = PakePairingState.PAKE_CONFIRMED
        return true
    }

    /**
     * Step three. A rejection is a terminal state, not a failure of the exchange.
     *
     * Before PAKE_CONFIRMED this is refused: a human cannot approve or reject a pairing that has not
     * proven the code yet, and letting them would make the prompt the only gate.
     */
    @Synchronized
    fun localApproved(decision: SecureApprovalDecision): Boolean {
        if (state != PakePairingState.PAKE_CONFIRMED) return false
        if (localDecision != null) return false
        localDecision = decision
        if (decision == SecureApprovalDecision.REJECT) {
            state = PakePairingState.REJECTED
            failure = SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED
            return true
        }
        state = PakePairingState.LOCAL_INTENDED_PEER_APPROVED
        return true
    }

    /**
     * Step four. The peer's record must bind this exact exchange and name the peer's role.
     *
     * Repeated evidence is refused: the second identical record returns false and changes nothing,
     * so an idempotent retry by a transport cannot turn one approval into two transitions.
     */
    @Synchronized
    fun remoteApproved(approval: PakeApproval): Boolean {
        if (state != PakePairingState.LOCAL_INTENDED_PEER_APPROVED) return false
        if (remoteApproval != null) return false
        val bound = attempt ?: return false
        val expectedInstance = bound.codeInstanceBytes()
        val presentedInstance = approval.codeInstanceBytes()
        val presentedTranscript = approval.transcriptDigestBytes()
        val binds = try {
            approval.bindsTo(
                expectedAttemptId = bound.id,
                expectedOrdinal = bound.ordinal,
                expectedCodeInstance = expectedInstance,
                expectedTranscriptDigest = requireNotNull(transcriptDigest),
                expectedSecureSessionId = secureSessionId,
                expectedControlSessionId = controlSessionId,
                expectedSuite = PairingAuthenticationPolicy.REQUIRED_SUITE,
                expectedProfile = JpakeCurveProfile.MODP_3072,
                expectedPeerRole = localRole.opposite,
            )
        } finally {
            expectedInstance.fill(0)
            presentedInstance.fill(0)
            presentedTranscript.fill(0)
        }
        if (!binds) {
            fail(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            return false
        }
        if (approval.decision != PakeApprovalDecision.APPROVE) {
            state = PakePairingState.REJECTED
            failure = SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED
            return false
        }
        remoteApproval = approval
        state = PakePairingState.REMOTE_INTENDED_PEER_APPROVED
        return true
    }

    /**
     * Step five, called only after this side's confirmation record was written and flushed through
     * the record layer.
     *
     * The name says what it requires. This reducer cannot see a socket, so a caller that invokes it
     * after a failed write is forging local confirmation -- the same precondition, for the same
     * reason, as the live reducer's `markLocalConfirmationSentAfterProtectedWrite`.
     */
    @Synchronized
    fun localKeyConfirmationSent(): Boolean {
        if (state != PakePairingState.REMOTE_INTENDED_PEER_APPROVED) return false
        if (localSent) return false
        localSent = true
        state = PakePairingState.LOCAL_KEY_CONFIRMATION_SENT
        return true
    }

    /**
     * Step six. [confirmation] must have been decoded from a record the AEAD accepted.
     *
     * A second confirmation fails closed: the peer only has one to send, so a second is a replay or
     * a confused peer, and silently ignoring it would leave the connection usable after evidence the
     * protocol says cannot exist.
     */
    @Synchronized
    fun remoteKeyConfirmationVerified(confirmation: SecureKeyConfirmation): Boolean {
        // Checked before the state, so a second confirmation is a failure even once this side has
        // moved past the state that admitted the first one.
        if (remoteVerified) {
            fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            return false
        }
        if (state != PakePairingState.LOCAL_KEY_CONFIRMATION_SENT) return false
        val expected = transcriptDigest
        val peerDigest = confirmation.transcriptDigestBytes()
        val valid = try {
            expected != null &&
                confirmation.role == localRole.opposite &&
                MessageDigest.isEqual(expected, peerDigest)
        } finally {
            peerDigest.fill(0)
        }
        if (!valid) {
            fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            return false
        }
        remoteVerified = true
        state = PakePairingState.REMOTE_KEY_CONFIRMATION_VERIFIED
        return true
    }

    /**
     * Step seven. The only transition into AUTHENTICATED, and it happens once.
     *
     * The caller supplies the authority it just built; a null authority means the evidence chain did
     * not hold, and that is a failure rather than a state that stays open.
     */
    @Synchronized
    fun authenticated(issuedAuthorityPresent: Boolean): Boolean {
        if (!mayIssueAuthority()) return false
        if (!issuedAuthorityPresent) {
            fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            return false
        }
        authorityIssued = true
        state = PakePairingState.AUTHENTICATED
        return true
    }

    /** Terminal for any reason the caller already classified. Idempotent. */
    @Synchronized
    fun fail(code: SessionFailureCode) {
        if (isTerminal()) return
        failInternal(code)
    }

    /** Terminal cancellation. Never charged to the peer, never an approval. */
    @Synchronized
    fun cancel() {
        if (isTerminal()) return
        state = PakePairingState.CANCELLED
        failure = SessionFailureCode.OPERATION_CANCELLED
        wipe()
    }

    /** True when this pairing can no longer change state. */
    @Synchronized
    fun isTerminal(): Boolean = state in TERMINAL

    override fun toString(): String = "PakePairingProgression(state=${state.name}, ids=[redacted])"

    private fun digestMatches(candidate: ByteArray): Boolean {
        val expected = transcriptDigest ?: return false
        return MessageDigest.isEqual(expected, candidate)
    }

    private fun failInternal(code: SessionFailureCode) {
        state = PakePairingState.FAILED
        failure = code
        wipe()
    }

    /**
     * Wipes what must not outlive the pairing.
     *
     * The remote approval and the attempt are dropped rather than wiped here: the approval's owner
     * clears it, and the attempt's code instance is cleared by settlement. What this owns is the
     * transcript copy and the decision, both of which are zeroed so a cancelled pairing leaves no
     * derivable material in a field a later reader could reach.
     */
    private fun wipe() {
        transcriptDigest?.fill(0)
        transcriptDigest = null
        localDecision = null
        remoteApproval = null
        attempt = null
    }

    private companion object {
        val TERMINAL = setOf(
            PakePairingState.AUTHENTICATED,
            PakePairingState.REJECTED,
            PakePairingState.FAILED,
            PakePairingState.CANCELLED,
        )
    }
}
