/*
 * The live Part B pairing: TLS, MPS2, MST2, then the proven entered-code path, then authority.
 *
 * WHAT CHANGED AND WHY IT IS ONE COMMIT
 *   The short-authentication-string comparison is gone. Not deprecated, not fallback-disabled: the
 *   coordinator no longer generates a proof, no longer offers the retired comparison capability,
 *   and no longer calls the general authentication selector that could have negotiated one. It
 *   offers exactly `JPAKE_MODP3072_ENTERED_CODE_V1` and refuses anything else with
 *   `selectRequired`.
 *
 *   SAS and the entered-code PAKE cannot both be live. Two live human checks on one connection would
 *   be two chances to get approval, and the weaker one would be the one an attacker aims at. So the
 *   switch removes the comparison in the same change that wires in the PAKE; there is no interval
 *   where the coordinator offers neither, and none where it offers both.
 *
 * WHO DOES THE CRYPTOGRAPHIC WORK
 *   Not this file. Steps one to four are the connection: TLS 1.3, the MPS2 offers, the required-suite
 *   selection and the MST2 transcript. Everything after that -- the attempt, three J-PAKE rounds,
 *   both approvals, the record layer, key confirmation in both directions and the single-use
 *   authority -- is `PakeSessionCoordinator`, which was built and tested inert before this switch.
 *   Duplicating any of it here would create a second implementation to keep correct, and the point
 *   of the inert period was that the live path would call the tested one.
 *
 * WHAT THIS FILE STILL OWNS
 *   The socket. The deadline that bounds each phase. The admission permit, and therefore the budget
 *   charged for an attempt: this is the only place that knows both what the peer did and which
 *   permit it did it under. And the offer validation that binds the peer's MPS2 fields to the
 *   already-negotiated control session.
 *
 * WHAT IT NO LONGER OWNS
 *   The record layer. It is derived inside the composed path from the TLS exporter over the
 *   transcript digest, and it leaves inside the authority. A caller cannot obtain one from this
 *   coordinator, which is what stops a future caller from pairing a socket with a layer it built
 *   itself and calling the result authenticated.
 *
 * STATUS
 *   Experimental, project-defined, not independently cryptographically reviewed. The owner waived
 *   external review for this personal experimental app on 2026-10-19; that waiver permits
 *   implementation and establishes no security. The authenticated control authority is control-only:
 *   the payload gate still refuses every session unconditionally.
 */

package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PAIRING_V2_HEADER_BYTES
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PairingHelloV2Result
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PairingSelectionResult
import app.morsecode.core.transfer.session.PairingV2HeaderResult
import app.morsecode.core.transfer.session.PairingWireV2Reject
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecurePairingHelloV2Codec
import app.morsecode.core.transfer.session.SecurePairingResult
import app.morsecode.core.transfer.session.SecurePairingTranscriptV2
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.security.pairing.EnteredCodePairingInteraction
import app.morsecode.transport.lan.security.pairing.PairingExchangeBudgets
import app.morsecode.transport.lan.security.pairing.PakeRefusalClass
import app.morsecode.transport.lan.security.pairing.PakeSessionCoordinator
import app.morsecode.transport.lan.security.pairing.PakeSessionOutcome
import app.morsecode.transport.lan.security.pairing.RecordBudgets
import app.morsecode.transport.lan.security.pairing.SocketPairingChannel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLSocket

internal sealed interface SecurePairingRunResult {
    /**
     * A completed pairing.
     *
     * [authority] is the primitive: it exists only because every step of the chain produced its
     * evidence, and [AuthenticatedControlAuthority.claim] hands out the control channel exactly
     * once. This result deliberately carries no record layer: a socket plus a layer is no longer
     * something a caller can assemble into an authenticated session.
     */
    class Authenticated(
        val secureSocket: SSLSocket,
        val authority: AuthenticatedControlAuthority,
    ) : SecurePairingRunResult

    class Failed(val result: SecurePairingResult.Failed) : SecurePairingRunResult
}

/** Executes only after a caller explicitly upgrades an existing selected-peer Part A session. */
internal class SecureLanPairingCoordinator(
    private val rawSocket: Socket,
    private val controlSession: NegotiatedSession,
    private val role: SecurePeerRole,
    private val clock: MonotonicClock,
    private val random: SecureRandom,
    /**
     * The human interaction: a code to display, a code to type, and an intended-peer decision.
     *
     * Production passes the bridge held by the control session's entered-code slot. This parameter
     * is the whole interaction surface of the live path; there is no second, SAS-shaped one, and no
     * default approval anywhere behind it.
     */
    private val interaction: EnteredCodePairingInteraction,
    /**
     * The discovery lease's admission scope. Optional so a caller with no lease still gets the
     * deadline protection; it is never a substitute for the human check.
     *
     * The coordinator receives the lease's exact scope rather than a shared limiter, so closing the
     * lease also closes admission for every pairing that lease started.
     */
    private val attemptLimiter: PairingAdmissionScope? = null,
    private val peerIdentityKey: String = controlSession.remoteProfile.peerInstanceId.value,
    private val sourceKey: String = UNKNOWN_SOURCE,
    /**
     * The boundary through which this coordinator reaches cryptographic work.
     *
     * Production omits the argument and gets the real Conscrypt-backed factory. The parameter
     * exists so an ordering test can count ephemeral key generation, certificate construction, TLS
     * engine creation, handshake start and MPS2 encoding directly, and prove that a limiter
     * refusal performs none of them. Socket-stream counters alone cannot prove that, because three
     * of those five operations never touch a socket.
     */
    private val cryptoFactory: PairingCryptoFactory = ConscryptPairingCryptoFactory(random),
    /**
     * The entered-code exchange's budgets. Production uses the protocol defaults; a hostile test
     * shortens them, because waiting out a sixty-second approval deadline once per mutation would
     * turn a bounded suite into an hour of emulator time without testing anything more.
     */
    private val exchangeBudgets: PairingExchangeBudgets = PairingExchangeBudgets.DEFAULT,
    private val recordBudgets: RecordBudgets = RecordBudgets.DEFAULT,
) {
    @Volatile
    private var tls: EstablishedTlsSession? = null
    @Volatile
    private var keptSocket: Boolean = false
    private var lastObservedNowElapsedMillis: Long = -1L

    fun run(): SecurePairingRunResult {
        val pairStartedAt = safeNow() ?: run {
            closeSocket(rawSocket)
            return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        }
        val limiter = attemptLimiter
        // The only route to the limiter's outcome policy for this attempt. There is no
        // string-keyed alternative on the lease-facing surface, so an outcome cannot be charged
        // to an identity this attempt was never granted for.
        var handle: AdmissionHandle? = null
        if (limiter != null) {
            when (val admission = limiter.tryStart(peerIdentityKey, sourceKey)) {
                is AdmissionDecision.Granted -> handle = admission.handle
                is AdmissionDecision.Refused -> {
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
                ConscryptSecureSessionEngine(random, cryptoFactory).establish(
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
            // caller-supplied: a peer cannot assert a profile the other side never offered, and a
            // peer that offers only SAS is refused here, before a single exchange frame is written.
            val selection = when (
                val result = PairingAuthenticationPolicy.selectRequired(initiatorOffer, responderOffer)
            ) {
                is PairingSelectionResult.Selected -> result.selection
                is PairingSelectionResult.Rejected ->
                    return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            }
            val built = SecurePairingTranscriptV2.build(initiatorOffer, responderOffer, selection)
                ?: return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
            transcriptBytes = built.first
            val canonicalDigest = built.second.copyOf()
            transcriptDigest = canonicalDigest

            // Everything below is the proven chain. This file chooses nothing cryptographic from
            // here on: it passes the connection, both offers, the digest and the human interaction,
            // and reads back either an authority or a typed refusal.
            val outcome = PakeSessionCoordinator(
                channel = SocketPairingChannel(established, clock, role),
                controlSession = controlSession,
                role = role,
                clock = clock,
                entropy = SecureEntropy { destination -> random.nextBytes(destination) },
                random = random,
                interaction = interaction,
                initiatorOffer = initiatorOffer,
                responderOffer = responderOffer,
                transcriptDigest = canonicalDigest,
                recordBudgets = recordBudgets,
                exchangeBudgets = exchangeBudgets,
            ).run()
            if (outcome is PakeSessionOutcome.Refused) {
                return failedWith(outcome.reason, outcome.classification)
            }
            val authenticatedOutcome = outcome as PakeSessionOutcome.Authenticated
            // The final round-three tag has validated and both approvals are in, so the remaining
            // reasons to withhold authority are local ownership and elapsed lifetime. Both are
            // checked before the authority leaves this frame.
            val now = safeNow()
            val expiresAt = saturatingAdd(pairStartedAt, SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS)
            if (now == null || now >= expiresAt) {
                authenticatedOutcome.authority.close()
                return failed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
            }
            // Ownership model A: the discovery lease owns this pairing for its entire life. A lease
            // that stopped during key confirmation would otherwise hand back live authority; the
            // caller re-checks before installing, and so does this.
            if (interaction.isCancelled()) {
                authenticatedOutcome.authority.close()
                return failed(SessionFailureCode.OPERATION_CANCELLED)
            }
            keptSocket = true
            authenticated = true
            return SecurePairingRunResult.Authenticated(
                secureSocket = socket,
                authority = authenticatedOutcome.authority,
            )
        } catch (_: LegacySecurityFormatException) {
            return failed(SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED)
        } catch (_: SocketTimeoutException) {
            return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } catch (_: IOException) {
            return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } catch (_: Exception) {
            return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        } finally {
            val granted = handle
            if (granted != null) {
                // One outcome, one policy. A local cancellation or local provider failure must not
                // be charged to the peer, and a genuine transcript mismatch must not be hidden
                // among them. One atomic terminal operation: it claims the transition, applies the
                // outcome and returns the permit.
                granted.complete(
                    if (authenticated) PairingAttemptOutcome.AUTHENTICATED else attemptOutcome,
                )
            }
            localOffer?.clearSensitive()
            remoteOffer?.clearSensitive()
            transcriptBytes?.fill(0)
            transcriptDigest?.fill(0)
            val completedTls = tls
            completedTls?.clearFingerprints()
            if (!keptSocket) {
                try {
                    completedTls?.socket?.close()
                } catch (_: IOException) {
                    // The raw socket is owned by the control-session close path.
                }
                closeSocket(rawSocket)
            }
            tls = null
        }
    }

    fun cancel() {
        // The composed path owns nothing that can be cancelled from another thread; the socket is
        // what unblocks it. The bridge resolves its own pending prompt, so the exchange returns
        // rather than sitting in an await for the rest of its budget.
        try {
            tls?.socket?.close()
        } catch (_: IOException) {
            // Cancellation always remains redacted and idempotent.
        }
        closeSocket(rawSocket)
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
                // The only authentication this build can perform. A peer that cannot offer it is
                // refused by `selectRequired` above; it is never offered a comparison instead.
                offeredAuthentication = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
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
        val frame = cryptoFactory.encodePairingHello(offer)
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

    /**
     * Why the admitted attempt ended, for the limiter's budget policy.
     *
     * Written only from the thread running [run] -- every failure path funnels through [failed],
     * [failedWith] or the composed outcome on that thread -- and read in the same thread's
     * `finally`.
     */
    private var attemptOutcome: PairingAttemptOutcome = PairingAttemptOutcome.AUTHENTICATION_MISMATCH

    private fun failed(code: SessionFailureCode): SecurePairingRunResult.Failed =
        failedWith(code, pairingOutcomeFor(code))

    private fun failedWith(
        code: SessionFailureCode,
        outcome: PairingAttemptOutcome,
    ): SecurePairingRunResult.Failed {
        attemptOutcome = outcome
        return SecurePairingRunResult.Failed(SecurePairingResult.Failed(SessionFailure(code)))
    }

    private fun failedWith(
        code: SessionFailureCode,
        classification: PakeRefusalClass,
    ): SecurePairingRunResult.Failed = failedWith(code, attemptOutcomeFor(classification))

    private fun attemptOutcomeFor(classification: PakeRefusalClass): PairingAttemptOutcome =
        when (classification) {
            PakeRefusalClass.ATTEMPTS_EXHAUSTED -> PairingAttemptOutcome.ATTEMPT_EXHAUSTED
            PakeRefusalClass.AUTHENTICATION_FAILURE -> PairingAttemptOutcome.AUTHENTICATION_MISMATCH
            PakeRefusalClass.KEY_CONFIRMATION_FAILURE -> PairingAttemptOutcome.KEY_CONFIRMATION_FAILURE
            PakeRefusalClass.TRANSCRIPT_MISMATCH -> PairingAttemptOutcome.TRANSCRIPT_MISMATCH
            PakeRefusalClass.USER_REJECTION -> PairingAttemptOutcome.USER_REJECTION
            PakeRefusalClass.PEER_REJECTION -> PairingAttemptOutcome.PEER_REJECTION
            PakeRefusalClass.LOCAL_CANCELLATION -> PairingAttemptOutcome.LOCAL_CANCELLATION
            PakeRefusalClass.LOCAL_EXPIRY -> PairingAttemptOutcome.APPROVAL_EXPIRED
            PakeRefusalClass.PEER_UNRESPONSIVE -> PairingAttemptOutcome.HANDSHAKE_TIMEOUT
            PakeRefusalClass.PEER_DISCONNECTED -> PairingAttemptOutcome.PEER_DISCONNECT
            PakeRefusalClass.LOCAL_FAILURE -> PairingAttemptOutcome.LOCAL_FAILURE
        }

    private fun saturatingAdd(value: Long, duration: Long): Long =
        if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration

    private companion object {
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
