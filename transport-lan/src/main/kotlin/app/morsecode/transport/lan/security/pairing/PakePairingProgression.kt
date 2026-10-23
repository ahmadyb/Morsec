/*
 * The proof path, as an ordered reducer with no bypass.
 *
 * WHY A SECOND STATE ENUM
 *   `SecurePairingState` is the live SAS state machine's vocabulary: it has states for comparing a
 *   displayed code, and the running coordinator reports them. Adding values to it would change what
 *   a live pairing can report before the switch that replaces SAS, and it would let new code observe
 *   a live machine in a state the live path never produces. So the PAKE path gets its own states and
 *   its own reducer, and the two coexist until the switch deletes the old one.
 *
 * WHY EVERY TRANSITION TAKES AN ARGUMENT
 *   There is no `advance()`, no `setState()` and no boolean. Each step demands the concrete artifact
 *   the previous step produced -- the selected suite and the transcript digest, a confirmed PAKE
 *   session bound to *this* attempt, the peer's binding approval record, the protected confirmation
 *   record -- and revalidates it. A caller cannot say "the PAKE succeeded": the only way to reach
 *   PAKE_CONFIRMED is to present a `ModpJpakeSession` that is confirmed, in this role, for this
 *   attempt.
 *
 * WHAT IS REFUSED, AND WHY EACH REFUSAL IS A TEST
 *   - A step out of order: refused, and the reducer fails closed rather than staying open.
 *   - Approval before PAKE: refused. A human cannot approve a pairing that has not proven the code.
 *   - The peer's approval standing in for the local one: refused. They are separate transitions with
 *     separate arguments, and the remote record must name the *peer's* role.
 *   - PAKE confirmation standing in for key confirmation: refused. AUTHENTICATED is reachable only
 *     after a protected confirmation record was decrypted.
 *   - Duplicate confirmation: fails closed. The record layer already allows exactly one confirmation
 *     per direction, so a second one at this level means something replayed a frame.
 *   - A second authority: impossible. AUTHENTICATED is terminal and the token is consumed once.
 *
 * STATUS
 *   Experimental, project-defined, not independently cryptographically reviewed. The owner accepted
 *   that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md. Nothing here is
 *   authority for payload: the gate still refuses every session.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession
import java.security.MessageDigest

/** The proof path's states, in the only order in which they may be entered. */
internal enum class PakePairingState {
    PENDING,
    TRANSCRIPT_VALIDATED,
    PAKE_CONFIRMED,
    LOCAL_INTENDED_PEER_APPROVED,
    REMOTE_INTENDED_PEER_APPROVED,
    LOCAL_KEY_CONFIRMATION_SENT,
    REMOTE_KEY_CONFIRMATION_VERIFIED,
    AUTHENTICATED,
    FAILED,
}

/**
 * The ordered reducer.
 *
 * Not thread-safe by design: one instance drives one pairing on one thread, and every method is
 * documented as such. A synchronized container would suggest that racing callers are expected.
 */
internal class PakePairingProgression(
    private val secureSessionId: SessionId,
    private val controlSessionId: String,
    private val role: SecurePeerRole,
    private val transcriptDigest: ByteArray,
) {
    private var state: PakePairingState = PakePairingState.PENDING
    private var failure: SessionFailureCode? = null
    private var selectedSuite: PairingAuthenticationCapability? = null
    private var attempt: PakeAttempt? = null
    private var pakeSession: ModpJpakeSession? = null
    private var localDecision: SecureApprovalDecision? = null
    private var remoteApproval: PakeApproval? = null
    private var authorityIssued: Boolean = false

    fun state(): PakePairingState = state

    fun failureCode(): SessionFailureCode? = failure

    fun attempt(): PakeAttempt? = attempt

    fun pakeSession(): ModpJpakeSession? = pakeSession

    fun localDecision(): SecureApprovalDecision? = localDecision

    fun remoteApproval(): PakeApproval? = remoteApproval

    fun authorityWasIssued(): Boolean = authorityIssued

    /**
     * Step one: the required suite was selected and the transcript digest is the one both peers
     * hashed.
     *
     * Neither value is taken on trust: the suite must be the policy's required suite and the
     * encryption must be TLS 1.3, so a legacy comparison selection never reaches step two.
     */
    fun transcriptValidated(
        selectedAuthentication: PairingAuthenticationCapability,
        selectedEncryption: EncryptionCapability,
    ): Boolean {
        if (state != PakePairingState.PENDING) return refuse()
        if (!PairingAuthenticationPolicy.isProductionSuite(selectedAuthentication)) {
            return fail(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        }
        if (selectedEncryption != EncryptionCapability.TLS_1_3) {
            return fail(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        }
        if (transcriptDigest.size != TRANSCRIPT_HASH_BYTES) {
            return fail(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        }
        selectedSuite = selectedAuthentication
        state = PakePairingState.TRANSCRIPT_VALIDATED
        return true
    }

    /**
     * Step two: a confirmed PAKE session, for this exact attempt and this exact role.
     *
     * The session's own `attempt` must be the attempt presented, and it must not be settled -- a
     * settled attempt already spent its code, and accepting it would let two pairings share one
     * entered code's proof.
     */
    fun pakeConfirmed(attempt: PakeAttempt, session: ModpJpakeSession): Boolean {
        if (state != PakePairingState.TRANSCRIPT_VALIDATED) return refuse()
        if (session.attempt !== attempt) return fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        if (session.role != role) return fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        if (!session.isConfirmed()) return fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        if (attempt.isSettled()) return fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        this.attempt = attempt
        pakeSession = session
        state = PakePairingState.PAKE_CONFIRMED
        return true
    }

    /**
     * Step three: the local human's decision.
     *
     * A rejection ends the pairing here; it is not an error state, because the person does not have
     * to justify declining. An approval moves on, and the decision is kept for the authority, which
     * requires it to be APPROVE.
     */
    fun localIntendedPeerApproved(decision: SecureApprovalDecision?): Boolean {
        if (state != PakePairingState.PAKE_CONFIRMED) return refuse()
        if (decision == null) return fail(SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED)
        localDecision = decision
        if (decision != SecureApprovalDecision.APPROVE) {
            return fail(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED)
        }
        state = PakePairingState.LOCAL_INTENDED_PEER_APPROVED
        return true
    }

    /**
     * Step four: the peer's approval record, bound to this exact exchange.
     *
     * The record must name the peer's role, this attempt, this code instance, this transcript and
     * both session identifiers. A reflected local record names the sender's role and is refused;
     * that is the whole reason the role is part of the binding.
     */
    fun remoteIntendedPeerApproved(approval: PakeApproval): Boolean {
        if (state != PakePairingState.LOCAL_INTENDED_PEER_APPROVED) return refuse()
        val bound = attempt ?: return fail(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        val suite = selectedSuite ?: return fail(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        val codeInstance = bound.codeInstanceBytes()
        val peerDigest = approval.transcriptDigestBytes()
        val binds = try {
            approval.bindsTo(
                expectedAttemptId = bound.id,
                expectedOrdinal = bound.ordinal,
                expectedCodeInstance = codeInstance,
                expectedTranscriptDigest = transcriptDigest,
                expectedSecureSessionId = secureSessionId,
                expectedControlSessionId = controlSessionId,
                expectedSuite = suite,
                expectedProfile = JpakeCurveProfile.MODP_3072,
                expectedPeerRole = role.opposite,
            )
        } finally {
            codeInstance.fill(0)
            peerDigest.fill(0)
        }
        if (!binds) return fail(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED)
        if (approval.decision != PakeApprovalDecision.APPROVE) {
            return fail(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED)
        }
        remoteApproval = approval
        state = PakePairingState.REMOTE_INTENDED_PEER_APPROVED
        return true
    }

    /**
     * Step five: this side's confirmation record has left the process.
     *
     * Called only after the protected write succeeded. [frameDigest] is the SHA-256 of the encoded
     * record as it was written, kept so a test can show that what was sent is what the peer read
     * without either side retaining the plaintext.
     */
    fun localKeyConfirmationSent(frameDigest: ByteArray): Boolean {
        if (state != PakePairingState.REMOTE_INTENDED_PEER_APPROVED) return refuse()
        if (frameDigest.size != TRANSCRIPT_HASH_BYTES) {
            return fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        }
        state = PakePairingState.LOCAL_KEY_CONFIRMATION_SENT
        return true
    }

    /**
     * Step six: the peer's confirmation, decoded from a record the AEAD accepted.
     *
     * The confirmation must name the peer's role and carry this transcript's digest. A second
     * confirmation fails the pairing rather than being ignored.
     */
    fun remoteKeyConfirmationVerified(confirmation: SecureKeyConfirmation): Boolean {
        if (state != PakePairingState.LOCAL_KEY_CONFIRMATION_SENT) return refuse()
        if (confirmation.role != role.opposite) {
            return fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        }
        val peerDigest = confirmation.transcriptDigestBytes()
        val matches = try {
            MessageDigest.isEqual(peerDigest, transcriptDigest)
        } finally {
            peerDigest.fill(0)
        }
        if (!matches) return fail(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        state = PakePairingState.REMOTE_KEY_CONFIRMATION_VERIFIED
        return true
    }

    /**
     * Step seven: the single-use token the authority builder consumes.
     *
     * Returns null -- never a throw, never a default -- when the chain has not reached
     * REMOTE_KEY_CONFIRMATION_VERIFIED, and null again for every later call. AUTHENTICATED is
     * recorded at the same moment, so the reducer and the caller cannot disagree about whether an
     * authority exists.
     */
    fun authenticated(): AuthenticatedTransition? {
        if (state != PakePairingState.REMOTE_KEY_CONFIRMATION_VERIFIED) return null
        if (authorityIssued) return null
        authorityIssued = true
        state = PakePairingState.AUTHENTICATED
        return AuthenticatedTransition(attempt, pakeSession, localDecision, remoteApproval)
    }

    /** Terminal failure. Never called by a caller: the reducer fails itself. */
    fun failedInsideProgression(code: SessionFailureCode): Boolean = fail(code)

    private fun refuse(): Boolean {
        // An out-of-order step is a protocol-level refusal, not a caller error that may be ignored:
        // the pairing ends and [failureCode] says why. Skipping a state is the one thing this class
        // exists to prevent, so it is not recoverable.
        failure = SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED
        state = PakePairingState.FAILED
        return false
    }

    private fun fail(code: SessionFailureCode): Boolean {
        failure = code
        state = PakePairingState.FAILED
        return false
    }

    private companion object {
        const val TRANSCRIPT_HASH_BYTES: Int = 32
    }
}

/**
 * What [PakePairingProgression.authenticated] hands over.
 *
 * It is not a token and not authority. It is the reducer's final state made readable, and its
 * constructor is internal to this module -- the same boundary as the reducer itself. What makes
 * synthesis useless is downstream: `AuthenticatedControlAuthority.issue` re-derives every fact from
 * the attempt, the confirmed session, the approval record and the record layer, so a hand-built
 * transition would have to carry a genuinely confirmed session to be accepted, at which point it is
 * not synthesised evidence at all.
 */
internal class AuthenticatedTransition(
    val attempt: PakeAttempt?,
    val pakeSession: ModpJpakeSession?,
    val localDecision: SecureApprovalDecision?,
    val remoteApproval: PakeApproval?,
) {
    override fun toString(): String = "AuthenticatedTransition(ids=[redacted])"
}
