package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.HumanVerificationCode
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecureKeyConfirmationCodec
import app.morsecode.core.transfer.session.SecurePairingApprovalRequest
import app.morsecode.core.transfer.session.SecurePairingResult
import app.morsecode.core.transfer.session.SecurePairingState
import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.PAIRING_V2_HEADER_BYTES
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingHelloV2Result
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PairingSelectionResult
import app.morsecode.core.transfer.session.PairingV2HeaderResult
import app.morsecode.core.transfer.session.PairingWireV2Reject
import app.morsecode.core.transfer.session.SecurePairingHelloV2Codec
import app.morsecode.core.transfer.session.SecurePairingSelectionV2
import app.morsecode.core.transfer.session.SecurePairingTranscriptV2
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLSocket

internal sealed interface SecurePairingRunResult {
    /**
     * A completed pairing. [negotiatedControlSession] is the *control* session and still reports
     * `ControlOnlyUnauthenticated`: there is no public authenticated-session marker to hand back.
     * The authority produced here is the pair of opaque, module-internal handles — [secureSocket]
     * and [recordLayer] — which exist only because a protected write and an authenticated peer
     * confirmation actually happened.
     */
    class Authenticated(
        val secureSocket: SSLSocket,
        val negotiatedControlSession: NegotiatedSession,
        val recordLayer: SecureRecordLayer,
        val expiresAtElapsedMillis: Long,
    ) : SecurePairingRunResult

    class Failed(val result: SecurePairingResult.Failed) : SecurePairingRunResult
}

internal interface PairingInteraction {
    fun publishApproval(machine: SecureSessionStateMachine, request: SecurePairingApprovalRequest): Boolean
    fun awaitApproval(machine: SecureSessionStateMachine): Boolean
    fun isCancelled(): Boolean
}

/** Executes only after a caller explicitly upgrades an existing selected-peer Part A session. */
internal class SecureLanPairingCoordinator(
    private val rawSocket: Socket,
    private val controlSession: NegotiatedSession,
    private val role: SecurePeerRole,
    private val clock: MonotonicClock,
    private val random: SecureRandom,
    private val interaction: PairingInteraction,
    /**
     * Process-local denial-of-service throttle. Optional so a caller that has no lease-scoped
     * limiter still gets the deadline protection; it is never a substitute for the SAS comparison.
     */
    private val attemptLimiter: SecurePairingAttemptLimiter? = null,
    private val peerIdentityKey: String = controlSession.remoteProfile.peerInstanceId.value,
    private val sourceKey: String = UNKNOWN_SOURCE,
) {
    @Volatile
    private var tls: EstablishedTlsSession? = null
    @Volatile
    private var recordLayer: SecureRecordLayer? = null
    @Volatile
    private var machine: SecureSessionStateMachine? = null
    @Volatile
    private var keptSocket: Boolean = false
    private var lastObservedNowElapsedMillis: Long = -1L

    fun run(): SecurePairingRunResult {
        val pairStartedAt = safeNow() ?: run {
            closeSocket(rawSocket)
            return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        }
        val limiter = attemptLimiter
        var admitted = false
        if (limiter != null) {
            when (val admission = limiter.tryStart(peerIdentityKey, sourceKey)) {
                is PairingAdmission.Granted -> admitted = true
                is PairingAdmission.Refused -> {
                    // An exhausted start budget means this discovery identity is spent: it must be
                    // rediscovered rather than retried. Concurrency and cooldown refusals are not
                    // the peer's fault, so they do not invalidate it.
                    if (admission.reason == PairingAdmission.RefusalReason.IDENTITY_START_BUDGET_EXHAUSTED ||
                        admission.reason == PairingAdmission.RefusalReason.PAIR_START_BUDGET_EXHAUSTED
                    ) {
                        limiter.invalidateIdentity(peerIdentityKey)
                    }
                    closeSocket(rawSocket)
                    return failed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
                }
            }
        }
        var authenticated = false
        var localOffer: PairingOfferV2? = null
        var remoteOffer: PairingOfferV2? = null
        // The V2 transcript is returned as canonical bytes plus their digest rather than as an
        // object, so there is nothing to clear beyond the two arrays.
        var transcriptBytes: ByteArray? = null
        var transcriptDigest: ByteArray? = null
        var proof: HumanVerificationCode? = null
        try {
            if (interaction.isCancelled()) return failed(SessionFailureCode.OPERATION_CANCELLED)
            rawSocket.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            // soTimeout bounds a single read; this bounds the whole handshake, so a peer that
            // dribbles handshake bytes cannot hold the worker past the deadline.
            val established = guarded(
                label = "tls-handshake",
                socket = rawSocket,
                budgetMillis = SecureSessionLimits.TLS_HANDSHAKE_DEADLINE_MILLIS,
            ) {
                ConscryptSecureSessionEngine(random).establish(
                    rawSocket,
                    initiator = role == SecurePeerRole.INITIATOR,
                )
            }
            tls = established
            if (interaction.isCancelled()) return failed(SessionFailureCode.OPERATION_CANCELLED)
            val socket = established.socket
            socket.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)

            val secureId = if (role == SecurePeerRole.INITIATOR) randomSessionId() else null
            val localNonce = randomBytes(SecureSessionLimits.NONCE_BYTES)
            val localFingerprint = established.localFingerprintBytes()
            guarded(
                label = "pairing-hello",
                socket = socket,
                budgetMillis = SecureSessionLimits.PAIRING_HELLO_DEADLINE_MILLIS,
            ) {
                try {
                    if (role == SecurePeerRole.INITIATOR) {
                        val id = requireNotNull(secureId)
                        localOffer = createOffer(id, localNonce, localFingerprint, established)
                        writeOffer(output, requireNotNull(localOffer))
                        remoteOffer = readOffer(input, socket)
                    } else {
                        remoteOffer = readOffer(input, socket)
                        validateRemoteOffer(requireNotNull(remoteOffer), null, established.peerFingerprintBytes())
                        localOffer = createOffer(
                            secureSessionId = requireNotNull(remoteOffer).secureSessionId,
                            nonce = localNonce,
                            certificateFingerprint = localFingerprint,
                            established = established,
                        )
                        writeOffer(output, requireNotNull(localOffer))
                    }
                } finally {
                    localNonce.fill(0)
                    localFingerprint.fill(0)
                }
            }

            val remote = requireNotNull(remoteOffer)
            val local = requireNotNull(localOffer)
            if (role == SecurePeerRole.INITIATOR) {
                validateRemoteOffer(remote, secureId, established.peerFingerprintBytes())
            }
            val localPresentedFingerprint = local.certificateFingerprintBytes()
            val actualLocalFingerprint = established.localFingerprintBytes()
            val localFingerprintMatches = try {
                localPresentedFingerprint.contentEquals(actualLocalFingerprint)
            } finally {
                localPresentedFingerprint.fill(0)
                actualLocalFingerprint.fill(0)
            }
            if (!localFingerprintMatches) return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)

            val initiatorOffer = if (role == SecurePeerRole.INITIATOR) local else remote
            val responderOffer = if (role == SecurePeerRole.RESPONDER) local else remote
            // The selection is derived from the two decoded offers. Nothing here is
            // caller-supplied: a peer cannot assert a profile the other side never offered.
            val selection = when (val result = SecurePairingSelectionV2.select(initiatorOffer, responderOffer)) {
                is PairingSelectionResult.Selected -> result.selection
                is PairingSelectionResult.Rejected ->
                    return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            }
            val built = SecurePairingTranscriptV2.build(initiatorOffer, responderOffer, selection)
                ?: return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            transcriptBytes = built.first
            val canonicalDigest = built.second.copyOf()
            transcriptDigest = canonicalDigest

            val proofMaterial = established.export(PROOF_LABEL, canonicalDigest, EXPORTER_BYTES)
            proof = HumanVerificationCode.fromExporterMaterial(proofMaterial)
            val pairingMachine = SecureSessionStateMachine(
                controlSession = controlSession,
                secureSessionId = local.secureSessionId,
                localRole = role,
                clock = clock,
                entropy = app.morsecode.core.transfer.session.SecureEntropy { destination ->
                    random.nextBytes(destination)
                },
            )
            machine = pairingMachine
            val request = pairingMachine.requestApproval(canonicalDigest, requireNotNull(proof))
                ?: return failedFromMachine(pairingMachine, SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
            if (!interaction.publishApproval(pairingMachine, request)) {
                pairingMachine.cancel()
                return failedFromMachine(pairingMachine, SessionFailureCode.OPERATION_CANCELLED)
            }
            if (!interaction.awaitApproval(pairingMachine)) {
                return failedFromMachine(pairingMachine, SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED)
            }
            if (interaction.isCancelled()) {
                pairingMachine.cancel()
                return failedFromMachine(pairingMachine, SessionFailureCode.OPERATION_CANCELLED)
            }
            if (pairingMachine.state() !in setOf(
                    SecurePairingState.APPROVED,
                    SecurePairingState.CONFIRMING,
                )
            ) {
                return failedFromMachine(pairingMachine, SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED)
            }

            socket.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            recordLayer = deriveRecordLayer(established, local.secureSessionId, canonicalDigest, role)
            val localConfirmation = SecureKeyConfirmation(role, canonicalDigest)
            val confirmationBytes = try {
                SecureKeyConfirmationCodec.encode(localConfirmation)
            } finally {
                localConfirmation.clearSensitive()
            }
            val encodedConfirmation = try {
                recordLayer!!.protect(SecureRecordType.KEY_CONFIRMATION, confirmationBytes)
            } finally {
                confirmationBytes.fill(0)
            }
            val firstFrame = (encodedConfirmation as? SecureRecordResult.Encoded)?.frameBytes()
                ?: return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            val incoming = guarded(
                label = "key-confirmation",
                socket = socket,
                budgetMillis = SecureSessionLimits.CONFIRMATION_DEADLINE_MILLIS,
            ) {
                try {
                    output.write(firstFrame)
                    output.flush()
                } finally {
                    firstFrame.fill(0)
                }
                val localConfirmationResult = pairingMachine.markLocalConfirmationSentAfterProtectedWrite()
                if (localConfirmationResult is SecurePairingResult.Failed) {
                    return SecurePairingRunResult.Failed(localConfirmationResult)
                }
                readRecord(input, requireNotNull(recordLayer), local.secureSessionId, role, socket)
            } ?: return failed(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
            val record = (incoming as? SecureRecordResult.Decoded)?.record
                ?: return failed(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
            if (record.type != SecureRecordType.KEY_CONFIRMATION) {
                record.clearSensitive()
                return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            }
            val peerConfirmationBytes = record.payloadBytes()
            record.clearSensitive()
            val peerConfirmation = try {
                SecureKeyConfirmationCodec.decode(peerConfirmationBytes)
            } finally {
                peerConfirmationBytes.fill(0)
            }
            if (peerConfirmation == null) {
                return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            }
            val completion = pairingMachine.receivePeerConfirmationFromAuthenticatedRecord(peerConfirmation)
            peerConfirmation.clearSensitive()
            if (completion !is SecurePairingResult.Authenticated) {
                return failedFromMachine(pairingMachine, SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            }

            val now = safeNow() ?: return failed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
            val expiresAt = saturatingAdd(pairStartedAt, SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS)
            if (now >= expiresAt) return failed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
            keptSocket = true
            authenticated = true
            return SecurePairingRunResult.Authenticated(
                secureSocket = socket,
                negotiatedControlSession = controlSession,
                recordLayer = requireNotNull(recordLayer),
                expiresAtElapsedMillis = expiresAt,
            )
        } catch (_: LegacySecurityFormatException) {
            return failed(SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED)
        } catch (_: SocketTimeoutException) {
            return failedFromMachine(machine, SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } catch (_: IOException) {
            return failedFromMachine(machine, SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } catch (_: Exception) {
            return failedFromMachine(machine, SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } finally {
            if (admitted) {
                if (authenticated) limiter?.recordSuccess(peerIdentityKey, sourceKey) else limiter?.recordFailure(peerIdentityKey, sourceKey)
                limiter?.releaseConcurrency()
            }
            localHello?.clearSensitive()
            remoteHello?.clearSensitive()
            transcriptBytes?.fill(0)
            transcriptDigest?.fill(0)
            proof?.clearSensitive()
            val completedTls = tls
            completedTls?.clearFingerprints()
            if (!keptSocket) {
                recordLayer?.close()
                try {
                    completedTls?.socket?.close()
                } catch (_: IOException) {
                    // The raw socket is owned by the control-session close path.
                }
                closeSocket(rawSocket)
            }
            recordLayer = null
            tls = null
        }
    }

    fun cancel() {
        machine?.cancel()
        try {
            tls?.socket?.close()
        } catch (_: IOException) {
            // Cancellation always remains redacted and idempotent.
        }
        closeSocket(rawSocket)
    }

    private fun deriveRecordLayer(
        established: EstablishedTlsSession,
        sessionId: SessionId,
        transcriptDigest: ByteArray,
        localRole: SecurePeerRole,
    ): SecureRecordLayer {
        var c2sKey: ByteArray? = null
        var c2sIv: ByteArray? = null
        var s2cKey: ByteArray? = null
        var s2cIv: ByteArray? = null
        var clientWrite: SecureRecordAead? = null
        var serverWrite: SecureRecordAead? = null
        var handedOff = false
        try {
            c2sKey = established.export(C2S_KEY_LABEL, transcriptDigest, SecureSessionLimits.AES_KEY_BYTES)
            c2sIv = established.export(C2S_IV_LABEL, transcriptDigest, SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
            s2cKey = established.export(S2C_KEY_LABEL, transcriptDigest, SecureSessionLimits.AES_KEY_BYTES)
            s2cIv = established.export(S2C_IV_LABEL, transcriptDigest, SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
            clientWrite = established.createAead(requireNotNull(c2sKey))
            serverWrite = established.createAead(requireNotNull(s2cKey))
            val clientCipher = requireNotNull(clientWrite)
            val serverCipher = requireNotNull(serverWrite)
            val sender = if (localRole == SecurePeerRole.INITIATOR) clientCipher else serverCipher
            val receiver = if (localRole == SecurePeerRole.INITIATOR) serverCipher else clientCipher
            val sendIv = requireNotNull(if (localRole == SecurePeerRole.INITIATOR) c2sIv else s2cIv)
            val receiveIv = requireNotNull(if (localRole == SecurePeerRole.INITIATOR) s2cIv else c2sIv)
            val layer = SecureRecordLayer(
                sessionId = sessionId,
                localRole = localRole,
                sendAead = sender,
                receiveAead = receiver,
                sendNonceBase = sendIv,
                receiveNonceBase = receiveIv,
                clock = clock,
            )
            handedOff = true
            return layer
        } finally {
            c2sKey?.fill(0)
            c2sIv?.fill(0)
            s2cKey?.fill(0)
            s2cIv?.fill(0)
            if (!handedOff) {
                try { clientWrite?.close() } catch (_: Exception) { }
                try { serverWrite?.close() } catch (_: Exception) { }
            }
        }
    }

    private fun createOffer(
        secureSessionId: SessionId,
        nonce: ByteArray,
        certificateFingerprint: ByteArray,
        established: EstablishedTlsSession,
    ): PairingOfferV2 {
        val localProfile = controlSession.localProfile
        return try {
            PairingOfferV2(
                secureSessionId = secureSessionId,
                controlSessionId = controlSession.attemptId,
                role = role,
                localPeerInstanceId = localProfile.peerInstanceId,
                remotePeerInstanceId = controlSession.remoteProfile.peerInstanceId,
                protocolMinimumVersion = localProfile.protocolRange.minimum,
                protocolMaximumVersion = localProfile.protocolRange.maximum,
                nonce = nonce,
                certificateFingerprint = certificateFingerprint,
                transport = TransportKind.LAN,
                securitySuiteWireId = SECURITY_SUITE_PART_B,
                // The negotiated values, not a preference: the handshake has already happened.
                tlsProtocol = established.protocol,
                tlsCipherSuite = established.cipherSuite,
                maxRecordPlaintextBytes = SecureSessionLimits.MAX_RECORD_PLAINTEXT_BYTES.toLong(),
                maxRecordsPerDirection = SecureSessionLimits.MAX_RECORDS_PER_DIRECTION,
                maxBytesPerDirection = SecureSessionLimits.MAX_BYTES_PER_DIRECTION,
                sessionLifetimeMillis = SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS,
                offeredFeaturesMask = FEATURES_CONTROL_AND_SECURE,
                // Explicit zero and explicit false, not absent. Chunk transfer and resume are
                // disabled in this milestone, and "we do not offer this" has to be a value the
                // transcript binds rather than an omission the peer can fill in.
                offeredMaxChunkSizeBytes = 0L,
                offeredResumeSupported = false,
                offeredEncryption = EncryptionCapability.TLS_1_3,
                offeredAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
                appVersion = localProfile.appVersion,
                extensions = emptyList(),
            )
        } finally {
            nonce.fill(0)
            certificateFingerprint.fill(0)
        }
    }

    private fun validateRemoteOffer(
        offer: PairingOfferV2,
        expectedSecureSessionId: SessionId?,
        peerCertificateFingerprint: ByteArray,
    ) {
        val expectedLocal = controlSession.localProfile.peerInstanceId
        val expectedRemote = controlSession.remoteProfile.peerInstanceId
        val presentedFingerprint = offer.certificateFingerprintBytes()
        try {
            if (offer.role != role.opposite ||
                offer.controlSessionId != controlSession.attemptId ||
                offer.localPeerInstanceId != expectedRemote ||
                offer.remotePeerInstanceId != expectedLocal ||
                offer.appVersion != controlSession.remoteProfile.appVersion ||
                offer.protocolMinimumVersion != controlSession.remoteProfile.protocolRange.minimum ||
                offer.protocolMaximumVersion != controlSession.remoteProfile.protocolRange.maximum ||
                (expectedSecureSessionId != null && offer.secureSessionId != expectedSecureSessionId) ||
                !presentedFingerprint.contentEquals(peerCertificateFingerprint)
            ) throw SecureSessionCryptoException()
        } finally {
            presentedFingerprint.fill(0)
            peerCertificateFingerprint.fill(0)
        }
    }

    private fun writeOffer(output: DataOutputStream, offer: PairingOfferV2) {
        val frame = SecurePairingHelloV2Codec.encodeFrame(offer)
        try {
            output.write(frame)
            output.flush()
        } finally {
            frame.fill(0)
        }
    }

    private fun readDeadline(): Long {
        val now = safeNow() ?: throw SocketTimeoutException()
        return saturatingAdd(now, SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS.toLong())
    }

    private fun readExact(
        input: DataInputStream,
        destination: ByteArray,
        offset: Int,
        length: Int,
        socket: Socket,
        deadlineElapsedMillis: Long,
    ) {
        var position = offset
        val end = offset + length
        while (position < end) {
            val now = safeNow() ?: throw SocketTimeoutException()
            val remaining = deadlineElapsedMillis - now
            if (remaining <= 0L) throw SocketTimeoutException()
            socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val read = input.read(destination, position, end - position)
            if (read < 0) throw java.io.EOFException()
            if (read > 0) position += read
        }
    }

    private fun readOffer(input: DataInputStream, socket: Socket): PairingOfferV2 {
        val headerBytes = ByteArray(PAIRING_V2_HEADER_BYTES)
        val deadline = readDeadline()
        var frame: ByteArray? = null
        try {
            readExact(input, headerBytes, 0, headerBytes.size, socket, deadline)
            val length = when (val result = SecurePairingHelloV2Codec.decodeHeader(headerBytes)) {
                is PairingV2HeaderResult.Valid -> result.payloadLength
                is PairingV2HeaderResult.Rejected -> throw SecureSessionCryptoException()
            }
            frame = ByteArray(length)
            readExact(input, requireNotNull(frame), 0, length, socket, deadline)
            return when (val result = SecurePairingHelloV2Codec.decode(requireNotNull(frame))) {
                is PairingHelloV2Result.Success -> result.offer
                is PairingHelloV2Result.Rejected ->
                    if (result.reason == PairingWireV2Reject.LEGACY_MPS1_UNSUPPORTED) {
                        // A peer still on MPS1 gets a typed incompatible-format result. There is
                        // no retry over MPS1, no fallback transcript that omits the offer fields,
                        // and no plaintext path.
                        throw LegacySecurityFormatException()
                    } else {
                        throw SecureSessionCryptoException()
                    }
            }
        } finally {
            headerBytes.fill(0)
            frame?.fill(0)
        }
    }

    private fun readRecord(
        input: DataInputStream,
        layer: SecureRecordLayer,
        sessionId: SessionId,
        localRole: SecurePeerRole,
        socket: Socket,
    ): SecureRecordResult? {
        val headerBytes = ByteArray(SecureRecordCodec.HEADER_SIZE_BYTES)
        val deadline = readDeadline()
        var frame: ByteArray? = null
        try {
            readExact(input, headerBytes, 0, headerBytes.size, socket, deadline)
            val expectedDirection = SecureRecordLayer.directionFrom(localRole).opposite
            val header = SecureRecordCodec.decodeHeader(headerBytes, sessionId, expectedDirection) ?: return null
            if (header.frameLength > SecureRecordCodec.MAX_FRAME_SIZE_BYTES) return null
            frame = ByteArray(header.frameLength)
            headerBytes.copyInto(requireNotNull(frame))
            readExact(input, requireNotNull(frame), headerBytes.size, header.frameLength - headerBytes.size, socket, deadline)
            return layer.unprotect(requireNotNull(frame))
        } finally {
            frame?.fill(0)
            headerBytes.fill(0)
        }
    }

    private fun randomSessionId(): SessionId {
        val bytes = randomBytes(16)
        val alphabet = "0123456789abcdef"
        val chars = CharArray(32)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            chars[index * 2] = alphabet[value ushr 4]
            chars[index * 2 + 1] = alphabet[value and 0x0F]
        }
        bytes.fill(0)
        return SessionId(String(chars))
    }

    private fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        return try {
            random.nextBytes(bytes)
            bytes
        } catch (failure: Throwable) {
            bytes.fill(0)
            throw failure
        }
    }

    private fun safeNow(): Long? {
        val now = try {
            clock.nowMillis()
        } catch (_: RuntimeException) {
            return null
        }
        if (now < 0L || (lastObservedNowElapsedMillis >= 0L && now < lastObservedNowElapsedMillis)) {
            return null
        }
        lastObservedNowElapsedMillis = now
        return now
    }

    /**
     * Closes a socket under a total deadline. `close()` can block on a lingering send buffer or a
     * stalled peer; if it does, the deadline abortively closes the same socket and releases the
     * worker rather than leaving a detached blocked task behind.
     */
    private fun closeSocket(socket: Socket) {
        val deadline = MonotonicSocketDeadline(
            label = "bounded-close",
            socket = socket,
            budgetMillis = SecureSessionLimits.BOUNDED_CLOSE_DEADLINE_MILLIS,
            monotonicMillis = clock::nowMillis,
        )
        deadline.arm()
        try {
            socket.close()
        } catch (_: IOException) {
            // Close failure never becomes diagnostic detail.
        } catch (_: RuntimeException) {
            // Android may observe concurrent close during cancellation.
        } finally {
            deadline.complete()
        }
    }

    /**
     * Thrown when a phase's total monotonic deadline elapsed. It is an [IOException] so the
     * existing redacted failure mapping applies, and it carries no peer or socket detail.
     */
    private class DeadlineExceededException : IOException("secure session phase deadline elapsed")

    /**
     * Runs one blocking phase under a single total monotonic deadline.
     *
     * Inline so the phase body can use a non-local `return` for its own failure paths. Expiry
     * closes the socket the phase owns, which is what unblocks a thread parked in `read`/`write`;
     * a phase that appeared to succeed at the instant the deadline fired is then reported as a
     * failure, because the session it produced is already dead.
     */
    private inline fun <T> guarded(
        label: String,
        socket: Socket,
        budgetMillis: Long,
        block: () -> T,
    ): T {
        val deadline = MonotonicSocketDeadline(
            label = label,
            socket = socket,
            budgetMillis = budgetMillis,
            monotonicMillis = clock::nowMillis,
        )
        deadline.arm()
        val result = try {
            block()
        } finally {
            deadline.complete()
        }
        if (deadline.isExpired()) throw DeadlineExceededException()
        return result
    }

    private fun failed(code: SessionFailureCode): SecurePairingRunResult.Failed =
        SecurePairingRunResult.Failed(SecurePairingResult.Failed(SessionFailure(code)))

    private fun failedFromMachine(
        stateMachine: SecureSessionStateMachine?,
        fallback: SessionFailureCode,
    ): SecurePairingRunResult.Failed {
        val result = stateMachine?.completion() as? SecurePairingResult.Failed
            ?: SecurePairingResult.Failed(SessionFailure(fallback))
        return SecurePairingRunResult.Failed(result)
    }

    private fun saturatingAdd(value: Long, duration: Long): Long =
        if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration

    private companion object {
        const val PROOF_LABEL: String = "EXPORTER-MORSEC-PAIRING-PROOF-V1"
        const val C2S_KEY_LABEL: String = "EXPORTER-MORSEC-C2S-KEY-V1"
        const val C2S_IV_LABEL: String = "EXPORTER-MORSEC-C2S-IV-V1"
        const val S2C_KEY_LABEL: String = "EXPORTER-MORSEC-S2C-KEY-V1"
        const val S2C_IV_LABEL: String = "EXPORTER-MORSEC-S2C-IV-V1"
        const val EXPORTER_BYTES: Int = 32

        /** Used when a source address cannot be read; it must never be treated as an identity. */
        const val UNKNOWN_SOURCE: String = "unknown-source"
    }
}

/**
 * Security suite this milestone implements. Mirrors the MPS1 suite identifier; declared here
 * because the V2 offer names the suite it offers rather than inheriting a private constant.
 */
private const val SECURITY_SUITE_PART_B: Int = 1

/** Control plus secure-session features. No payload feature bit is offered. */
private const val FEATURES_CONTROL_AND_SECURE: Int = (1 shl 0) or (1 shl 3)

/**
 * The peer spoke MPS1. Kept distinct from a generic cryptographic failure so the pairing path can
 * report an incompatible security format instead of a vague handshake error.
 */
internal class LegacySecurityFormatException : Exception("Peer used an incompatible security format.")
