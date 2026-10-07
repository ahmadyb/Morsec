package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import java.security.MessageDigest

/**
 * Deterministic approval and mutual-key-confirmation reducer for one control-session upgrade.
 * This reducer is not an AEAD verifier: callers must pass a peer confirmation only after the
 * production record layer has authenticated and decoded its first encrypted record.
 */
public class SecureSessionStateMachine(
    private val controlSession: NegotiatedSession,
    public val secureSessionId: SessionId,
    public val localRole: SecurePeerRole,
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
    public fun state(): SecurePairingState = phase

    @Synchronized
    public fun completion(): SecurePairingResult? = completion

    /** Called once, after TLS and both fixed-schema hellos have produced a shared transcript. */
    @Synchronized
    public fun requestApproval(
        transcriptDigest: ByteArray,
        proof: HumanVerificationCode,
    ): SecurePairingApprovalRequest? {
        if (phase != SecurePairingState.TRANSCRIPT_PENDING) {
            proof.clear()
            return null
        }
        val now = activeNow() ?: run {
            proof.clear()
            return null
        }
        if (transcriptDigest.size != SecureSessionLimits.TRANSCRIPT_HASH_BYTES) {
            proof.clear()
            finishFailed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            return null
        }
        val handleBytes = ByteArray(SecureSessionLimits.APPROVAL_HANDLE_BYTES)
        val handle = try {
            entropy.nextBytes(handleBytes)
            SecureApprovalHandle(handleBytes)
        } catch (_: RuntimeException) {
            proof.clear()
            finishFailed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
            return null
        } finally {
            handleBytes.fill(0)
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
    public fun decide(
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
            approvalHandle?.clear()
            return SecureApprovalResult.Applied
        }
        localDecision = decision
        phase = if (peerConfirmationVerified) SecurePairingState.CONFIRMING else SecurePairingState.APPROVED
        return SecureApprovalResult.Applied
    }

    /** The local role-specific AEAD key-confirmation record was written successfully. */
    @Synchronized
    public fun markLocalConfirmationSent(): SecurePairingResult? {
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
    public fun receivePeerConfirmation(confirmation: SecureKeyConfirmation): SecurePairingResult? {
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
    public fun expireIfNeeded(): SecurePairingResult? {
        val now = activeNow() ?: return completion
        val requestExpiry = approvalRequest?.expiresAtElapsedMillis
        if (phase == SecurePairingState.AWAITING_LOCAL_APPROVAL && requestExpiry != null && now >= requestExpiry) {
            finishExpired()
        }
        return completion
    }

    /** Terminal cancellation clears the short code, handle material and transcript digest. */
    @Synchronized
    public fun cancel(): SecurePairingResult? {
        if (completion != null) return completion
        phase = SecurePairingState.CANCELLED
        completion = SecurePairingResult.Failed(SessionFailure(SessionFailureCode.OPERATION_CANCELLED))
        clearProofAndTranscript()
        approvalHandle?.clear()
        return completion
    }

    @Synchronized
    public fun fail(code: SessionFailureCode): SecurePairingResult? {
        if (completion != null) return completion
        finishFailed(code)
        return completion
    }

    private fun completeIfConfirmed(): SecurePairingResult? {
        if (localDecision != SecureApprovalDecision.APPROVE || !localConfirmationSent || !peerConfirmationVerified) {
            return null
        }
        val securedCapabilities = NegotiatedCapabilities(
            features = setOf(SessionFeature.CONTROL_HANDSHAKE, SessionFeature.SECURE_SESSION),
            maxChunkSizeBytes = 0,
            resumeSupported = false,
            encryption = EncryptionCapability.TLS_1_3,
        )
        val securedSession = controlSession.copy(
            capabilities = securedCapabilities,
            security = ApprovedSecureSession(),
        )
        phase = SecurePairingState.AUTHENTICATED
        completion = SecurePairingResult.Authenticated(securedSession)
        clearProofAndTranscript()
        approvalHandle?.clear()
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
        approvalHandle?.clear()
    }

    private fun finishFailed(code: SessionFailureCode) {
        if (completion != null) return
        phase = SecurePairingState.FAILED
        completion = SecurePairingResult.Failed(SessionFailure(code))
        clearProofAndTranscript()
        approvalHandle?.clear()
    }

    private fun clearProofAndTranscript() {
        approvalProof?.clear()
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
