package app.morsecode.transport.lan.security

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.HumanVerificationCode
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecureKeyConfirmationCodec
import app.morsecode.core.transfer.session.SecurePairingApprovalRequest
import app.morsecode.core.transfer.session.SecurePairingHello
import app.morsecode.core.transfer.session.SecurePairingHelloCodec
import app.morsecode.core.transfer.session.SecurePairingHelloDecodeResult
import app.morsecode.core.transfer.session.SecurePairingHelloHeaderResult
import app.morsecode.core.transfer.session.SecurePairingResult
import app.morsecode.core.transfer.session.SecurePairingState
import app.morsecode.core.transfer.session.SecurePairingTranscript
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SecureSessionStateMachine
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
    class Authenticated(
        val secureSocket: SSLSocket,
        val negotiatedSession: NegotiatedSession,
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
        var localHello: SecurePairingHello? = null
        var remoteHello: SecurePairingHello? = null
        var transcript: SecurePairingTranscript? = null
        var transcriptDigest: ByteArray? = null
        var proof: HumanVerificationCode? = null
        try {
            if (interaction.isCancelled()) return failed(SessionFailureCode.OPERATION_CANCELLED)
            rawSocket.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            val established = ConscryptSecureSessionEngine(random).establish(
                rawSocket,
                initiator = role == SecurePeerRole.INITIATOR,
            )
            tls = established
            if (interaction.isCancelled()) return failed(SessionFailureCode.OPERATION_CANCELLED)
            val socket = established.socket
            socket.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            val input = DataInputStream(socket.inputStream)
            val output = DataOutputStream(socket.outputStream)

            val secureId = if (role == SecurePeerRole.INITIATOR) randomSessionId() else null
            val localNonce = randomBytes(SecureSessionLimits.NONCE_BYTES)
            val localFingerprint = established.localFingerprintBytes()
            try {
                if (role == SecurePeerRole.INITIATOR) {
                    val id = requireNotNull(secureId)
                    localHello = createHello(id, localNonce, localFingerprint)
                    writeHello(output, requireNotNull(localHello))
                    remoteHello = readHello(input, socket)
                } else {
                    remoteHello = readHello(input, socket)
                    validateRemoteHello(requireNotNull(remoteHello), null, established.peerFingerprintBytes())
                    localHello = createHello(
                        secureSessionId = requireNotNull(remoteHello).secureSessionId,
                        nonce = localNonce,
                        certificateFingerprint = localFingerprint,
                    )
                    writeHello(output, requireNotNull(localHello))
                }
            } finally {
                localNonce.fill(0)
                localFingerprint.fill(0)
            }

            val remote = requireNotNull(remoteHello)
            val local = requireNotNull(localHello)
            if (role == SecurePeerRole.INITIATOR) {
                validateRemoteHello(remote, secureId, established.peerFingerprintBytes())
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

            val initiatorHello = if (role == SecurePeerRole.INITIATOR) local else remote
            val responderHello = if (role == SecurePeerRole.RESPONDER) local else remote
            transcript = SecurePairingTranscript.create(
                initiatorHello,
                responderHello,
                established.protocol,
                established.cipherSuite,
            ) ?: return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            val canonicalDigest = requireNotNull(transcript).digestBytes()
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
            try {
                output.write(firstFrame)
                output.flush()
            } finally {
                firstFrame.fill(0)
            }
            val localConfirmationResult = pairingMachine.markLocalConfirmationSent()
            if (localConfirmationResult is SecurePairingResult.Failed) {
                return SecurePairingRunResult.Failed(localConfirmationResult)
            }

            val incoming = readRecord(input, requireNotNull(recordLayer), local.secureSessionId, role, socket)
                ?: return failed(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
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
            val completion = pairingMachine.receivePeerConfirmation(peerConfirmation)
            peerConfirmation.clearSensitive()
            val authenticated = completion as? SecurePairingResult.Authenticated
                ?: return failedFromMachine(pairingMachine, SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)

            val now = safeNow() ?: return failed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
            val expiresAt = saturatingAdd(pairStartedAt, SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS)
            if (now >= expiresAt) return failed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
            keptSocket = true
            return SecurePairingRunResult.Authenticated(
                secureSocket = socket,
                negotiatedSession = authenticated.negotiatedSession,
                recordLayer = requireNotNull(recordLayer),
                expiresAtElapsedMillis = expiresAt,
            )
        } catch (_: SocketTimeoutException) {
            return failedFromMachine(machine, SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } catch (_: IOException) {
            return failedFromMachine(machine, SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } catch (_: Exception) {
            return failedFromMachine(machine, SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } finally {
            localHello?.clearSensitive()
            remoteHello?.clearSensitive()
            transcript?.clearSensitive()
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

    private fun createHello(
        secureSessionId: SessionId,
        nonce: ByteArray,
        certificateFingerprint: ByteArray,
    ): SecurePairingHello {
        val localProfile = controlSession.localProfile
        return try {
            SecurePairingHello(
                secureSessionId = secureSessionId,
                controlSessionId = controlSession.attemptId,
                role = role,
                localPeerInstanceId = localProfile.peerInstanceId,
                remotePeerInstanceId = controlSession.remoteProfile.peerInstanceId,
                appVersion = localProfile.appVersion,
                selectedProtocolVersion = controlSession.protocolVersion,
                protocolMinimumVersion = localProfile.protocolRange.minimum,
                protocolMaximumVersion = localProfile.protocolRange.maximum,
                nonce = nonce,
                certificateFingerprint = certificateFingerprint,
            )
        } finally {
            nonce.fill(0)
            certificateFingerprint.fill(0)
        }
    }

    private fun validateRemoteHello(
        hello: SecurePairingHello,
        expectedSecureSessionId: SessionId?,
        peerCertificateFingerprint: ByteArray,
    ) {
        val expectedLocal = controlSession.localProfile.peerInstanceId
        val expectedRemote = controlSession.remoteProfile.peerInstanceId
        val presentedFingerprint = hello.certificateFingerprintBytes()
        try {
            if (hello.role != role.opposite ||
                hello.controlSessionId != controlSession.attemptId ||
                hello.localPeerInstanceId != expectedRemote ||
                hello.remotePeerInstanceId != expectedLocal ||
                hello.appVersion != controlSession.remoteProfile.appVersion ||
                hello.selectedProtocolVersion != controlSession.protocolVersion ||
                hello.protocolMinimumVersion != controlSession.remoteProfile.protocolRange.minimum ||
                hello.protocolMaximumVersion != controlSession.remoteProfile.protocolRange.maximum ||
                (expectedSecureSessionId != null && hello.secureSessionId != expectedSecureSessionId) ||
                !presentedFingerprint.contentEquals(peerCertificateFingerprint)
            ) throw SecureSessionCryptoException()
        } finally {
            presentedFingerprint.fill(0)
            peerCertificateFingerprint.fill(0)
        }
    }

    private fun writeHello(output: DataOutputStream, hello: SecurePairingHello) {
        val frame = SecurePairingHelloCodec.encode(hello)
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

    private fun readHello(input: DataInputStream, socket: Socket): SecurePairingHello {
        val headerBytes = ByteArray(SecurePairingHelloCodec.HEADER_SIZE_BYTES)
        val deadline = readDeadline()
        var frame: ByteArray? = null
        try {
            readExact(input, headerBytes, 0, headerBytes.size, socket, deadline)
            val header = when (val result = SecurePairingHelloCodec.decodeHeader(headerBytes)) {
                is SecurePairingHelloHeaderResult.Valid -> result.header
                is SecurePairingHelloHeaderResult.Invalid -> throw SecureSessionCryptoException()
            }
            frame = ByteArray(SecurePairingHelloCodec.HEADER_SIZE_BYTES + header.payloadLength)
            headerBytes.copyInto(requireNotNull(frame))
            readExact(
                input,
                requireNotNull(frame),
                SecurePairingHelloCodec.HEADER_SIZE_BYTES,
                header.payloadLength,
                socket,
                deadline,
            )
            return when (val result = SecurePairingHelloCodec.decode(requireNotNull(frame))) {
                is SecurePairingHelloDecodeResult.Success -> result.hello
                is SecurePairingHelloDecodeResult.Invalid -> throw SecureSessionCryptoException()
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

    private fun closeSocket(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Close failure never becomes diagnostic detail.
        } catch (_: RuntimeException) {
            // Android may observe concurrent close during cancellation.
        }
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
    }
}
