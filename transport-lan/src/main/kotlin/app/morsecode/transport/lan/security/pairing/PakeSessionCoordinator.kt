/*
 * The whole authenticated-control chain, composed but not yet reachable.
 *
 * WHAT IT DOES, IN THE ORDER THE REVIEW REQUIRES
 *   1. Required-suite selection. `PairingAuthenticationPolicy.selectRequired` refuses anything that
 *      is not `JPAKE_MODP3072_ENTERED_CODE_V1`, so a legacy SAS peer and a peer that offered nothing
 *      are both unable to reach step two.
 *   2. The entered-code exchange: MPS4, three PAKE rounds, the retry handshake, MPA1.
 *   3. Local and remote intended-peer approval, through the reducer, which will not accept either
 *      before the PAKE is confirmed.
 *   4. The record layer, derived from the TLS exporter bound to the transcript digest.
 *   5. Bidirectional key confirmation, each direction a protected record the AEAD authenticated.
 *   6. One single-use, control-only authority.
 *
 * Each step hands the next a concrete object: a confirmed `ModpJpakeSession`, a `PakeApproval`, a
 * `SecureRecordLayer`, a decoded `SecureKeyConfirmation`. Nothing in the signature is a boolean or a
 * token, so "the PAKE succeeded" is not expressible, and the authority constructor -- which is where
 * the policy lives -- refuses to run on anything less.
 *
 * WHY IT TAKES SEAMS INSTEAD OF A SOCKET
 *   The transport is [PairingExchangeIo] plus [PairingRecordIo] over one connection, and TLS is
 *   [PairingChannel]. Production passes the Conscrypt-backed adapters; the JVM tests pass an
 *   in-memory channel with real AES-GCM records and a deterministic exporter, and the API 23 test
 *   passes a real Conscrypt handshake over loopback. The seams are the reason the composition can be
 *   tested at all on a machine without an emulator, and none of them can assert a result: each only
 *   supplies bytes or keys.
 *
 * REACHABILITY
 *   Nothing calls this. `SecureLanPairingCoordinator.run()` -- the only live pairing -- is unchanged,
 *   and this file is not imported by the provider. The atomic switch is the commit that will call it.
 *
 * STATUS
 *   Experimental, project-defined and not independently cryptographically reviewed. The owner
 *   accepted that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md. The authority
 *   this can produce is control-only: the payload gate is not consulted and still refuses.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PairingSelectionResult
import app.morsecode.core.transfer.session.PairingSelectionV2
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecureKeyConfirmationCodec
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.security.AuthenticatedControlAuthority
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** What one composed run produced. Internal: nothing outside this module can name it. */
internal sealed interface PakePairingOutcome {

    /**
     * Everything in the chain happened.
     *
     * [authority] is the only thing that permits control, it is single-use, and it is control-only.
     * It is not `SecurePairingRunResult.Authenticated`, which is the live coordinator's type behind
     * the legacy path, and it exposes no payload capability.
     */
    class Authenticated(
        val authority: AuthenticatedControlAuthority,
        val recordLayer: SecureRecordLayer,
        val controlSession: NegotiatedSession,
        val expiresAtElapsedMillis: Long,
        val attemptOrdinal: Int,
    ) : PakePairingOutcome {
        override fun toString(): String =
            "PakePairingOutcome.Authenticated(ordinal=$attemptOrdinal, ids=[redacted])"
    }

    /** No authenticated session. [code] is the local reason; the peer learned only retry or abort. */
    class Failed(val code: SessionFailureCode, val attemptsCharged: Int) : PakePairingOutcome {
        override fun toString(): String =
            "PakePairingOutcome.Failed(code=${code.id}, attemptsCharged=$attemptsCharged)"
    }
}

/**
 * One composed PAKE pairing over an already-negotiated TLS connection.
 *
 * Single use. The same inputs a live coordinator will have after the TLS handshake, expressed as
 * seams rather than objects: the two decoded offers, the transcript digest, the channel, the two
 * transports, the interaction and the clocks.
 */
internal class PakeSessionCoordinator(
    private val channel: PairingChannel,
    private val controlSession: NegotiatedSession,
    private val role: SecurePeerRole,
    private val clock: MonotonicClock,
    private val entropy: SecureEntropy,
    private val random: SecureRandom,
    private val interaction: EnteredCodePairingInteraction,
    private val exchangeIo: PairingExchangeIo,
    private val recordIo: PairingRecordIo,
    private val initiatorOffer: PairingOfferV2,
    private val responderOffer: PairingOfferV2,
    private val transcriptDigestInput: ByteArray,
    private val budgets: PairingExchangeBudgets = PairingExchangeBudgets.DEFAULT,
) {
    private val transcriptDigest: ByteArray = transcriptDigestInput.copyOf()
    private val secureSessionId: SessionId = initiatorOffer.secureSessionId
    private val controlSessionText: String = initiatorOffer.controlSessionId.value
    private val progression: PakePairingProgression =
        PakePairingProgression(secureSessionId, role, controlSessionText)
    private var recordLayer: SecureRecordLayer? = null
    private val closed: AtomicBoolean = AtomicBoolean(false)

    /**
     * The exchange's view of the frame transport.
     *
     * The exchange closes the transport it was handed when it returns, which is right when it owns
     * the connection and wrong here: the same socket still has to carry the key-confirmation
     * records. This wrapper keeps the exchange's contract intact and gives the single close to the
     * coordinator, which performs it after the last record has crossed.
     */
    private val exchangeTransport: PairingExchangeIo = NoCloseExchangeIo(exchangeIo)

    /** The state this run has reached. Read-only, for tests and for the future live wiring. */
    fun state(): PakePairingState = progression.state()

    /** Runs the chain to a typed outcome. Never throws for anything a peer can cause. */
    fun run(): PakePairingOutcome {
        if (closed.get()) return failed(SessionFailureCode.OPERATION_CANCELLED, 0)
        val startedAt = nowOrNull()
            ?: return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED, 0)
        try {
            if (interaction.isCancelled()) return cancelled()
            // 1. Required-suite selection, before a single frame is written. The selection is
            //    derived from both offers, so a peer cannot claim a suite the other never offered.
            if (channel.protocol != REQUIRED_TLS_PROTOCOL) {
                return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED, 0)
            }
            val selection = requireSelection()
                ?: return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID, 0)
            val evidence = ValidatedTranscriptEvidence.from(transcriptDigest, selection, secureSessionId)
                ?: return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID, 0)
            if (!progression.transcriptValidated(evidence)) {
                return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID, 0)
            }
            if (transcriptDigest.size != SecureSessionLimits.TRANSCRIPT_HASH_BYTES) {
                return failed(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID, 0)
            }

            // 2. The entered-code exchange. It owns its own retry budget and reports a typed result;
            //    its internal failures stay internal, and what crosses to the peer is one of two
            //    policy values.
            val exchangeResult = runExchange()
            val confirmed = when (exchangeResult) {
                is PairingExchangeResult.Confirmed -> exchangeResult
                is PairingExchangeResult.Failed -> {
                    progression.fail(codeFor(exchangeResult.reason))
                    return failed(
                        codeFor(exchangeResult.reason),
                        exchangeResult.attemptsCharged,
                    )
                }
            }

            // 3. The PAKE must be confirmed for this attempt and role before either approval counts.
            val pakeEvidence = ConfirmedPakeEvidence.from(transcriptDigest, role, confirmed.session)
                ?: return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
            if (!progression.pakeConfirmed(pakeEvidence)) {
                return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
            }
            return afterPake(startedAt, confirmed)
        } finally {
            closeOnce()
        }
    }

    /** Releases everything exactly once. The record layer is handed over only on success. */
    fun close() = closeOnce()

    override fun toString(): String =
        "PakeSessionCoordinator(role=${role.name}, state=${progression.state().name}, ids=[redacted])"

    // --- the chain after the PAKE -------------------------------------------------------------

    private fun afterPake(
        startedAt: Long,
        confirmed: PairingExchangeResult.Confirmed,
    ): PakePairingOutcome {
        // The exchange already asked both peers and carried the answer out with the peer's record,
        // so recording it here asks nobody twice. What it buys is ordering: the reducer refuses a
        // local approval unless the PAKE is already confirmed, which is what makes "approval before
        // PAKE" impossible rather than merely unusual.
        if (!progression.localApproved(confirmed.localDecision)) {
            return failed(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, 1)
        }
        if (confirmed.localDecision == SecureApprovalDecision.REJECT) {
            return failed(SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED, 1)
        }
        if (!progression.remoteApproved(confirmed.peerApproval)) {
            val code = progression.failureCode() ?: SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED
            return failed(code, 1)
        }

        // 4. The record layer, derived from the exporter and bound to the transcript digest.
        val layer = deriveRecordLayer() ?: return failed(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED, 1)
        recordLayer = layer

        // 5. Bidirectional key confirmation. Send first, then read: both sides do the same, so
        //    neither waits for a record the other is waiting to send.
        val confirmationFrame = keyConfirmationFrame()
            ?: return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        val wrote = try {
            recordIo.writeRecordFrame(confirmationFrame, SecureSessionLimits.CONFIRMATION_DEADLINE_MILLIS)
        } finally {
            confirmationFrame.fill(0)
        }
        if (!wrote) {
            return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        }
        // Only after the protected write left this process may the local half count.
        if (!progression.localKeyConfirmationSent()) {
            return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        }
        val incoming = readProtectedRecord(
            io = recordIo,
            layer = layer,
            budgetMillis = SecureSessionLimits.CONFIRMATION_DEADLINE_MILLIS,
        ) ?: return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        val record = (incoming as? SecureRecordResult.Decoded)?.record
            ?: return failed(SessionFailureCode.SECURE_SESSION_RECORD_INVALID, 1)
        if (record.type != SecureRecordType.KEY_CONFIRMATION) {
            record.clearSensitive()
            return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        }
        val payload = record.payloadBytes()
        record.clearSensitive()
        val peerConfirmation = try {
            SecureKeyConfirmationCodec.decode(payload)
        } finally {
            payload.fill(0)
        } ?: return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        val verified = progression.remoteKeyConfirmationVerified(peerConfirmation)
        peerConfirmation.clearSensitive()
        if (!verified) {
            return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        }

        // 6. Authority. The constructor re-checks every link: TLS 1.3, the transcript, a confirmed
        //    session for this attempt and role, both approvals, the record layer's session id and
        //    the session lifetime. A null here is a refusal, never a downgrade.
        val now = nowOrNull() ?: return failed(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED, 1)
        val authority = AuthenticatedControlAuthority.issue(
            channel = ConnectionRelease(),
            recordLayer = layer,
            controlSession = controlSession,
            tlsProtocol = channel.protocol,
            transcriptDigest = transcriptDigest,
            attempt = confirmed.session.attempt,
            pakeSession = confirmed.session,
            localDecision = progression.localDecision(),
            remoteApproval = progression.peerApproval(),
            role = role,
            pairStartedElapsedMillis = startedAt,
            nowElapsedMillis = now,
        ) ?: return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        if (!progression.authenticated(true)) {
            authority.close()
            return failed(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED, 1)
        }
        // Settled only now: the whole chain has completed, so the attempt is spent and the code
        // instance behind it is wiped.
        confirmed.session.attempt.succeed()
        return PakePairingOutcome.Authenticated(
            authority = authority,
            recordLayer = layer,
            controlSession = controlSession,
            expiresAtElapsedMillis = authority.expiryElapsedMillis,
            attemptOrdinal = confirmed.attemptOrdinal,
        )
    }

    private fun runExchange(): PairingExchangeResult {
        val attempt = PairingAttemptExchange(
            role = role,
            io = exchangeTransport,
            interaction = interaction,
            clock = clock,
            entropy = entropy,
            secureSessionId = secureSessionId,
            controlSessionId = controlSessionText,
            transcriptDigestInput = transcriptDigest,
            channelBindingInput = channelBinding()
                ?: return PairingExchangeResult.Failed(PairingExchangeFailure.PROVIDER_FAILURE, 0),
            initiatorOffer = initiatorOffer,
            responderOffer = responderOffer,
            random = random,
            budgets = budgets,
        )
        return attempt.run()
    }

    /**
     * The channel binding the PAKE context binds.
     *
     * It is the RFC 9266 exporter of the TLS channel, labelled distinctly from every record-layer
     * label so the two cannot collide. A TLS channel with no exporter -- not Conscrypt, not 1.3 --
     * makes `export` fail, and the answer is a refusal. Substituting zeros would let a channel that
     * cannot export derive a binding both sides agree on, which is the downgrade this refuses.
     */
    private fun channelBinding(): ByteArray? = try {
        channel.export(CHANNEL_BINDING_LABEL, transcriptDigest, SecureSessionLimits.TRANSCRIPT_HASH_BYTES)
            .takeIf { it.size == SecureSessionLimits.TRANSCRIPT_HASH_BYTES }
    } catch (_: RuntimeException) {
        null
    }

    private fun requireSelection(): PairingSelectionV2? {
        val required = try {
            PairingAuthenticationPolicy.selectRequired(initiatorOffer, responderOffer)
        } catch (_: RuntimeException) {
            null
        }
        val selection = (required as? PairingSelectionResult.Selected)?.selection ?: return null
        if (selection.selectedEncryption != EncryptionCapability.TLS_1_3) return null
        return selection
    }

    private fun keyConfirmationFrame(): ByteArray? {
        val layer = recordLayer ?: return null
        return try {
            val confirmation = SecureKeyConfirmation(role, transcriptDigest)
            val bytes = try {
                SecureKeyConfirmationCodec.encode(confirmation)
            } finally {
                confirmation.clearSensitive()
            }
            val encoded = try {
                layer.protect(SecureRecordType.KEY_CONFIRMATION, bytes)
            } finally {
                bytes.fill(0)
            }
            (encoded as? SecureRecordResult.Encoded)?.frameBytes()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun deriveRecordLayer(): SecureRecordLayer? {
        var c2sKey: ByteArray? = null
        var c2sIv: ByteArray? = null
        var s2cKey: ByteArray? = null
        var s2cIv: ByteArray? = null
        var clientWrite: SecureRecordAead? = null
        var serverWrite: SecureRecordAead? = null
        var handedOff = false
        try {
            c2sKey = channel.export(C2S_KEY_LABEL, transcriptDigest, SecureSessionLimits.AES_KEY_BYTES)
            c2sIv = channel.export(C2S_IV_LABEL, transcriptDigest, SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
            s2cKey = channel.export(S2C_KEY_LABEL, transcriptDigest, SecureSessionLimits.AES_KEY_BYTES)
            s2cIv = channel.export(S2C_IV_LABEL, transcriptDigest, SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
            clientWrite = channel.createAead(requireNotNull(c2sKey))
            serverWrite = channel.createAead(requireNotNull(s2cKey))
            val client = requireNotNull(clientWrite)
            val server = requireNotNull(serverWrite)
            val sender = if (role == SecurePeerRole.INITIATOR) client else server
            val receiver = if (role == SecurePeerRole.INITIATOR) server else client
            val sendIv = requireNotNull(if (role == SecurePeerRole.INITIATOR) c2sIv else s2cIv)
            val receiveIv = requireNotNull(if (role == SecurePeerRole.INITIATOR) s2cIv else c2sIv)
            val layer = SecureRecordLayer(
                sessionId = secureSessionId,
                localRole = role,
                sendAead = sender,
                receiveAead = receiver,
                sendNonceBase = sendIv,
                receiveNonceBase = receiveIv,
                clock = clock,
            )
            handedOff = true
            return layer
        } catch (_: RuntimeException) {
            return null
        } finally {
            c2sKey?.fill(0)
            c2sIv?.fill(0)
            s2cKey?.fill(0)
            s2cIv?.fill(0)
            if (!handedOff) {
                try {
                    clientWrite?.close()
                } catch (_: Exception) {
                    // Already unusable.
                }
                try {
                    serverWrite?.close()
                } catch (_: Exception) {
                    // Already unusable.
                }
            }
        }
    }

    private fun codeFor(reason: PairingExchangeFailure): SessionFailureCode = when (reason) {
        PairingExchangeFailure.CANCELLED -> SessionFailureCode.OPERATION_CANCELLED
        PairingExchangeFailure.CODE_EXPIRED,
        PairingExchangeFailure.ATTEMPTS_EXHAUSTED,
        -> SessionFailureCode.SECURE_SESSION_LIMIT_REACHED
        PairingExchangeFailure.TIMEOUT -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
        PairingExchangeFailure.CONNECTION_LOST -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
        PairingExchangeFailure.PROTOCOL -> SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID
        PairingExchangeFailure.CRYPTO_FAILED -> SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED
        PairingExchangeFailure.ABORTED_BY_PEER -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
        PairingExchangeFailure.APPROVAL_REJECTED -> SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED
        PairingExchangeFailure.APPROVAL_TIMEOUT -> SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED
        PairingExchangeFailure.PROVIDER_FAILURE -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
        PairingExchangeFailure.INTERNAL -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
    }

    private fun cancelled(): PakePairingOutcome.Failed {
        progression.cancel()
        return failed(SessionFailureCode.OPERATION_CANCELLED, 0)
    }

    private fun failed(code: SessionFailureCode, charged: Int): PakePairingOutcome.Failed {
        if (!progression.isTerminal()) progression.fail(code)
        return PakePairingOutcome.Failed(code = code, attemptsCharged = charged)
    }

    private fun closeOnce() {
        if (!closed.compareAndSet(false, true)) return
        val layer = recordLayer
        recordLayer = null
        // The layer is handed over only on success, and the success path returns before this runs
        // for the caller: `run()`'s finally closes what it still owns, which on the success path is
        // nothing but the two transports.
        if (progression.state() != PakePairingState.AUTHENTICATED) {
            try {
                layer?.close()
            } catch (_: Exception) {
                // Closing always stays idempotent.
            }
        }
        // On success the caller keeps the layer and the transports: closing them here would hand
        // back a session whose socket is already gone.
        if (progression.state() != PakePairingState.AUTHENTICATED) {
            recordIo.close()
            exchangeIo.close()
        }
        // A handed-off attempt that never reached authority is spent: it is abandoned uncharged, so
        // the peer pays nothing for this side's stop and the attempt cannot be re-entered.
        if (progression.state() != PakePairingState.AUTHENTICATED) {
            val attempt = progression.attempt()
            if (attempt != null && !attempt.isSettled()) attempt.abandon()
        }
        transcriptDigest.fill(0)
    }

    private fun nowOrNull(): Long? = try {
        clock.nowMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * What the authority holds, and what it releases when it is closed.
     *
     * On this path the connection is shared between the exchange's frames and the record layer, so
     * neither transport may be closed while the other still has work. Handing both to the authority
     * makes one close release the whole connection, exactly once, at the moment the authenticated
     * session actually ends.
     */
    private inner class ConnectionRelease : java.io.Closeable {
        override fun close() {
            recordIo.close()
            exchangeIo.close()
        }
    }

    private companion object {
        const val REQUIRED_TLS_PROTOCOL: String = "TLSv1.3"
        const val CHANNEL_BINDING_LABEL: String = "EXPORTER-MORSEC-PAKE-BINDING-V1"
        const val C2S_KEY_LABEL: String = "EXPORTER-MORSEC-C2S-KEY-V1"
        const val C2S_IV_LABEL: String = "EXPORTER-MORSEC-C2S-IV-V1"
        const val S2C_KEY_LABEL: String = "EXPORTER-MORSEC-S2C-KEY-V1"
        const val S2C_IV_LABEL: String = "EXPORTER-MORSEC-S2C-IV-V1"
    }
}

/**
 * A frame transport that leaves closing to its owner.
 *
 * Used only for the exchange's view of the connection the coordinator shares with the record phase.
 * Every read and write is passed straight through.
 */
private class NoCloseExchangeIo(private val delegate: PairingExchangeIo) : PairingExchangeIo {
    override fun readFrame(label: String, budgetMillis: Long): PairingFrameRead =
        delegate.readFrame(label, budgetMillis)

    override fun writeFrame(label: String, frame: ByteArray, budgetMillis: Long): Boolean =
        delegate.writeFrame(label, frame, budgetMillis)

    /** Deliberately does nothing. The coordinator closes [delegate] once, at the end of the chain. */
    override fun close() = Unit

    override fun toString(): String = "NoCloseExchangeIo(ids=[redacted])"
}

/** The two offers this milestone may select, checked once so the coordinator reads as prose. */
internal object RequiredSuite {
    fun offered(offer: PairingOfferV2): Boolean =
        offer.transport == TransportKind.LAN &&
            offer.offeredEncryption == EncryptionCapability.TLS_1_3 &&
            PairingAuthenticationPolicy.isProductionSuite(
                offer.offeredAuthentication ?: return false,
            )
}
