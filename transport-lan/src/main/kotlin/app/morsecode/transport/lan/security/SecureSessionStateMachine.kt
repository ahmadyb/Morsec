package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.HumanVerificationCode
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureApprovalHandle
import app.morsecode.core.transfer.session.SecureApprovalResult
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecurePairingApprovalRequest
import app.morsecode.core.transfer.session.SecurePairingResult
import app.morsecode.core.transfer.session.SecurePairingState
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionSecurityState
import java.security.MessageDigest

/**
 * Deterministic approval and mutual-key-confirmation reducer for one control-session upgrade.
 * This reducer is not an AEAD verifier: callers must pass a peer confirmation only after the
 * production record layer has authenticated and decoded its first encrypted record.
 */
internal class SecureSessionStateMachine(
    private val controlSession: NegotiatedSession,
    internal val secureSessionId: SessionId,
    internal val localRole: SecurePeerRole,
    private val clock: MonotonicClock,
    private val entropy: SecureEntropy,
) {
    private val startedAtElapsedMillis: Long = readInitialTime()
    private val sessionExpiresAtElapsedMillis: Long = saturatingAdd(
        startedAtElapsedMillis,
        SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS,
    )
    private var lastNowElapsedMillis: Long = startedAtElapsedMillis
    private var phase: SecurePairingState = SecurePairingState.TRANSCRIPT_PENDING
    private var transcriptDigest: ByteArray? = null
    private var approvalProof: HumanVerificationCode? = null
    private var approvalRequest: SecurePairingApprovalRequest? = null
    private var approvalHandle: SecureApprovalHandle? = null
    private var localDecision: SecureApprovalDecision? = null
    private var localConfirmationSent: Boolean = false
    private var peerConfirmationVerified: Boolean = false
    private var completion: SecurePairingResult? = null

    init {
        require(secureSessionId.value.matches(Regex("[0-9a-f]{32}"))) {
            "secure session id must be 128-bit lowercase hexadecimal"
        }
        require(controlSession.security == SessionSecurityState.ControlOnlyUnauthenticated)
        require(controlSession.capabilities.encryption == EncryptionCapability.NONE)
        require(SessionFeature.CONTROL_HANDSHAKE in controlSession.capabilities.features)
        require(SessionFeature.FILE_PAYLOAD !in controlSession.capabilities.features)
        require(SessionFeature.RESUME !in controlSession.capabilities.features)
        require(SessionFeature.SECURE_SESSION !in controlSession.capabilities.features)
        require(TransportKind.LAN in controlSession.localProfile.capabilities.supportedTransports)
        require(TransportKind.LAN in controlSession.remoteProfile.capabilities.supportedTransports)
    }

    @Synchronized
    internal fun state(): SecurePairingState = phase

    @Synchronized
    internal fun completion(): SecurePairingResult? = completion

    /** Called once, after TLS and both fixed-schema hellos have produced a shared transcript. */
    @Synchronized
    internal fun requestApproval(
        transcriptDigest: ByteArray,
        proof: HumanVerificationCode,
    ): SecurePairingApprovalRequest? {
        if (phase != SecurePairingState.TRANSCRIPT_PENDING) {
            proof.clearSensitive()
            return null
        }
        val now = activeNow() ?: run {
            proof.clearSensitive()
            return null
        }
        if (transcriptDigest.size != SecureSessionLimits.TRANSCRIPT_HASH_BYTES) {
            proof.clearSensitive()
            finishFailed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            return null
        }
        val handle = try {
            SecureApprovalHandle.generate(entropy)
        } catch (_: RuntimeException) {
            proof.clearSensitive()
            finishFailed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
            return null
        }
        val expiresAt = minOf(
            saturatingAdd(now, SecureSessionLimits.APPROVAL_LIFETIME_MILLIS),
            sessionExpiresAtElapsedMillis,
        )
        this.transcriptDigest = transcriptDigest.copyOf()
        approvalProof = proof
        approvalHandle = handle
        val request = SecurePairingApprovalRequest(
            controlSessionId = controlSession.attemptId,
            secureSessionId = secureSessionId,
            localPeerInstanceId = controlSession.localProfile.peerInstanceId,
            remotePeerInstanceId = controlSession.remoteProfile.peerInstanceId,
            proof = proof,
            expiresAtElapsedMillis = expiresAt,
            handle = handle,
        )
        approvalRequest = request
        phase = SecurePairingState.AWAITING_LOCAL_APPROVAL
        return request
    }

    /** A handle can decide only this exact request; repeats of the same decision are idempotent. */
    @Synchronized
    internal fun decide(
        handle: SecureApprovalHandle,
        decision: SecureApprovalDecision,
    ): SecureApprovalResult {
        if (approvalHandle?.matches(handle) != true) return SecureApprovalResult.Stale
        val previous = localDecision
        if (previous != null) {
            return if (previous == decision) SecureApprovalResult.AlreadyApplied else SecureApprovalResult.Conflict
        }
        if (phase == SecurePairingState.EXPIRED) return SecureApprovalResult.Expired
        if (phase != SecurePairingState.AWAITING_LOCAL_APPROVAL) return SecureApprovalResult.Stale
        val now = activeNow() ?: return SecureApprovalResult.Expired
        val requestExpiry = approvalRequest?.expiresAtElapsedMillis ?: return SecureApprovalResult.Stale
        if (now >= requestExpiry) {
            finishExpired()
            return SecureApprovalResult.Expired
        }
        if (decision == SecureApprovalDecision.REJECT) {
            localDecision = decision
            phase = SecurePairingState.REJECTED
            completion = SecurePairingResult.Failed(SessionFailure(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED))
            clearProofAndTranscript()
            approvalHandle?.clearSensitive()
            return SecureApprovalResult.Applied
        }
        localDecision = decision
        phase = if (peerConfirmationVerified) SecurePairingState.CONFIRMING else SecurePairingState.APPROVED
        return SecureApprovalResult.Applied
    }

    /**
     * Records that the local role-specific AEAD key-confirmation record has been written through the
     * protected record layer and flushed to the socket.
     *
     * The name carries the precondition on purpose. This reducer cannot verify a write: it is a pure
     * state machine. Only the transport adapter knows whether the bytes really left through the
     * encrypted record layer, so this may be called only after that protected write succeeded. A
     * caller that invokes it after a failed or skipped write is forging local confirmation.
     */
    @Synchronized
    internal fun markLocalConfirmationSentAfterProtectedWrite(): SecurePairingResult? {
        if (isTerminal()) return completion
        if (localDecision != SecureApprovalDecision.APPROVE) return null
        if (activeNow() == null) return completion
        if (localConfirmationSent) return completion
        localConfirmationSent = true
        phase = SecurePairingState.CONFIRMING
        return completeIfConfirmed()
    }

    /**
     * Accepts a confirmation decoded from the authenticated first AEAD record only, and checks
     * the opposite role and exact transcript. The transport adapter owns that AEAD-verification
     * precondition; this pure reducer never treats a raw network frame as a confirmation.
     */
    @Synchronized
    internal fun receivePeerConfirmationFromAuthenticatedRecord(confirmation: SecureKeyConfirmation): SecurePairingResult? {
        if (isTerminal()) return completion
        if (activeNow() == null) return completion
        val expectedDigest = transcriptDigest
        val peerDigest = confirmation.transcriptDigestBytes()
        val valid = try {
            expectedDigest != null && confirmation.role == localRole.opposite &&
                MessageDigest.isEqual(expectedDigest, peerDigest) && !peerConfirmationVerified
        } finally {
            peerDigest.fill(0)
        }
        if (!valid) {
            finishFailed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            return completion
        }
        peerConfirmationVerified = true
        if (localDecision == SecureApprovalDecision.APPROVE) phase = SecurePairingState.CONFIRMING
        return completeIfConfirmed()
    }

    /** Polls injected monotonic time while the transport waits for an explicit human decision. */
    @Synchronized
    internal fun expireIfNeeded(): SecurePairingResult? {
        val now = activeNow() ?: return completion
        val requestExpiry = approvalRequest?.expiresAtElapsedMillis
        if (phase == SecurePairingState.AWAITING_LOCAL_APPROVAL && requestExpiry != null && now >= requestExpiry) {
            finishExpired()
        }
        return completion
    }

    /** Terminal cancellation clears the short code, handle material and transcript digest. */
    @Synchronized
    internal fun cancel(): SecurePairingResult? {
        if (completion != null) return completion
        phase = SecurePairingState.CANCELLED
        completion = SecurePairingResult.Failed(SessionFailure(SessionFailureCode.OPERATION_CANCELLED))
        clearProofAndTranscript()
        approvalHandle?.clearSensitive()
        return completion
    }

    @Synchronized
    internal fun fail(code: SessionFailureCode): SecurePairingResult? {
        if (completion != null) return completion
        finishFailed(code)
        return completion
    }

    /**
     * Both halves of mutual key confirmation are now established: the local confirmation was written
     * through the protected record layer, and the peer confirmation was decoded from an
     * authenticated record with the opposite role and the exact transcript digest.
     *
     * This produces a status-only result and no session object. The reducer does not and cannot
     * manufacture post-pairing authority; the transport adapter owns the opaque secure control
     * channel that embodies it.
     */
    private fun completeIfConfirmed(): SecurePairingResult? {
        if (localDecision != SecureApprovalDecision.APPROVE || !localConfirmationSent || !peerConfirmationVerified) {
            return null
        }
        phase = SecurePairingState.AUTHENTICATED
        completion = SecurePairingResult.Authenticated
        clearProofAndTranscript()
        approvalHandle?.clearSensitive()
        return completion
    }

    private fun activeNow(): Long? {
        if (completion != null) return null
        val now = try {
            clock.nowMillis()
        } catch (_: RuntimeException) {
            finishFailed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
            return null
        }
        if (now < lastNowElapsedMillis || now < startedAtElapsedMillis) {
            finishFailed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
            return null
        }
        lastNowElapsedMillis = now
        if (now >= sessionExpiresAtElapsedMillis) {
            finishFailed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
            return null
        }
        return now
    }

    private fun isTerminal(): Boolean = completion != null || phase in setOf(
        SecurePairingState.AUTHENTICATED,
        SecurePairingState.FAILED,
        SecurePairingState.REJECTED,
        SecurePairingState.EXPIRED,
        SecurePairingState.CANCELLED,
    )

    private fun finishExpired() {
        if (completion != null) return
        phase = SecurePairingState.EXPIRED
        completion = SecurePairingResult.Failed(SessionFailure(SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED))
        clearProofAndTranscript()
        approvalHandle?.clearSensitive()
    }

    private fun finishFailed(code: SessionFailureCode) {
        if (completion != null) return
        phase = SecurePairingState.FAILED
        completion = SecurePairingResult.Failed(SessionFailure(code))
        clearProofAndTranscript()
        approvalHandle?.clearSensitive()
    }

    private fun clearProofAndTranscript() {
        approvalProof?.clearSensitive()
        approvalProof = null
        transcriptDigest?.fill(0)
        transcriptDigest = null
    }

    private fun readInitialTime(): Long {
        val now = clock.nowMillis()
        require(now >= 0L) { "monotonic clock returned a negative value" }
        return now
    }

    private fun saturatingAdd(value: Long, duration: Long): Long =
        if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration
}
