/*
 * The composed proof path: selection, PAKE, approvals, record layer, key confirmation, authority.
 *
 * WHAT THIS IS
 *   One function that performs the whole authenticated-control chain in the order the protocol
 *   requires, over one connection, and returns either a single-use control authority or a typed
 *   reason. It is inert: nothing in production calls it. `SecureLanPairingCoordinator.run()` -- the
 *   only live pairing -- is untouched, still advertises SAS and still never reaches this file. The
 *   atomic switch is the later commit that wires it in and deletes the comparison path.
 *
 * WHY IT TAKES ITS INPUTS AS OBJECTS AND NOT FLAGS
 *   There is no `pakeSucceeded: Boolean`, no proof token and no caller-supplied key. Every step
 *   consumes the artifact the previous step produced: a selection that names the required suite, a
 *   `ModpJpakeSession` that is confirmed for this attempt and role, the peer's binding approval, a
 *   confirmation record decoded from a frame the AEAD accepted. A caller cannot skip a step because
 *   it cannot manufacture the argument the skipped step would have produced.
 *
 * ORDER, AND WHY IT IS THIS ORDER
 *   1. `selectRequired` over both offers. An SAS-only peer is refused before a byte is written.
 *   2. The entered-code exchange (attempt start, three rounds, failure handshake, approvals).
 *   3. Local approval, recorded by the reducer only after the PAKE confirmed.
 *   4. The peer's approval, bound to this attempt, this code instance and this transcript.
 *   5. The record layer, keyed from the TLS exporter over the transcript digest.
 *   6. This side's confirmation record, written through the record layer.
 *   7. The peer's confirmation, read through it and verified against the transcript.
 *   8. Authority. Issued once, control-only, and refused by the authority itself if any link of the
 *      chain disagrees with the connection.
 *
 * KEYS AND LABELS
 *   Record keys and IVs are eight separate exporter outputs with distinct labels, split by direction,
 *   so the two directions can never share a nonce sequence. They are wiped as soon as the AEADs own
 *   them. Nothing here derives a key from the entered code directly: that happens inside the PAKE
 *   library, and this file never sees it.
 *
 * STATUS
 *   Experimental, project-defined, not independently cryptographically reviewed. The owner accepted
 *   that risk on 2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md. The authority it
 *   returns is not payload authority; the gate still refuses every session.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PairingSelectionResult
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecureKeyConfirmation
import app.morsecode.core.transfer.session.SecureKeyConfirmationCodec
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordLayer
import app.morsecode.core.transfer.session.SecureRecordResult
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.transport.lan.security.AuthenticatedControlAuthority
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The outcome of one composed pairing.
 *
 * [Authenticated] holds the authority the caller may claim exactly once. [Refused] holds a typed
 * reason that never crosses the wire: the peer learned `RETRY` or `ABORT` and nothing more.
 */
internal sealed interface PakeSessionOutcome {
    class Authenticated(
        val authority: AuthenticatedControlAuthority,
        val recordLayer: SecureRecordLayer,
        val state: PakePairingState,
    ) : PakeSessionOutcome

    class Refused(
        val reason: SessionFailureCode,
        val state: PakePairingState,
    ) : PakeSessionOutcome
}

/**
 * Runs the composed chain once.
 *
 * Constructed per pairing. [run] is called exactly once; the object may then be discarded.
 */
internal class PakeSessionCoordinator(
    private val channel: PairingChannel,
    private val controlSession: NegotiatedSession,
    private val role: SecurePeerRole,
    private val clock: MonotonicClock,
    private val entropy: SecureEntropy,
    private val random: SecureRandom,
    private val interaction: EnteredCodePairingInteraction,
    private val initiatorOffer: PairingOfferV2,
    private val responderOffer: PairingOfferV2,
    private val transcriptDigest: ByteArray,
    private val recordBudgets: RecordBudgets = RecordBudgets.DEFAULT,
) {
    init {
        require(transcriptDigest.size == SecureSessionLimits.TRANSCRIPT_HASH_BYTES) {
            "the transcript digest is the fixed hash width"
        }
        require(initiatorOffer.role == SecurePeerRole.INITIATOR) { "first offer must be the initiator" }
        require(responderOffer.role == SecurePeerRole.RESPONDER) { "second offer must be the responder" }
    }

    /** The reducer, exposed so a test can assert which state a refusal stopped in. */
    val progression = PakePairingProgression(
        secureSessionId = initiatorOffer.secureSessionId,
        controlSessionId = controlSession.attemptId.value,
        role = role,
        transcriptDigest = transcriptDigest,
    )

    private val startedAtMillis: Long = clock.nowMillis()

    /**
     * Set once the exchange hands the attempt over. From that moment nothing else will settle it, so
     * a refusal has to: an attempt left outstanding would block the next try against the same code
     * and hide the fact that a budget was spent.
     */
    private var handedOffAttempt: PakeAttempt? = null

    private var activeLayer: SecureRecordLayer? = null

    private var activeRecords: PairingRecordIo? = null

    /** One run. Returns a typed outcome; never throws for anything the peer can cause. */
    fun run(): PakeSessionOutcome {
        if (channel.protocol != REQUIRED_TLS_PROTOCOL) {
            return refused(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        }
        // 1. Required-suite selection. The policy re-derives the selection from both offers and
        //    refuses anything that is not the experimental PAKE suite, so an SAS peer cannot be
        //    negotiated "down" into this path.
        val selection = when (val result = selectRequired()) {
            is PairingSelectionResult.Selected -> result.selection
            is PairingSelectionResult.Rejected -> return refused(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        }
        if (!progression.transcriptValidated(
                selection.selectedAuthentication,
                selection.selectedEncryption,
            )
        ) {
            return refused(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID)
        }

        // 2. The entered-code exchange. Its own budgets, its own retry rule, and its own refusals:
        //    this file never decides whether a wrong code may be retried.
        // The delegate is wrapped so the exchange's own close does not shut a socket the record
        // phase still needs; the channel is closed once, by the caller, at the end.
        val exchangeResult = PairingAttemptExchange(
            role = role,
            io = NonClosingExchangeIo(channel.exchangeIo()),
            interaction = interaction,
            clock = clock,
            entropy = entropy,
            secureSessionId = initiatorOffer.secureSessionId,
            controlSessionId = controlSession.attemptId.value,
            transcriptDigestInput = transcriptDigest,
            channelBindingInput = exporter(PROOF_LABEL, SecureSessionLimits.TRANSCRIPT_HASH_BYTES),
            initiatorOffer = initiatorOffer,
            responderOffer = responderOffer,
            random = random,
        ).run()
        val confirmed = when (exchangeResult) {
            is PairingExchangeResult.Confirmed -> exchangeResult
            is PairingExchangeResult.Failed -> return refused(refusalFor(exchangeResult.reason))
        }

        // 3. PAKE confirmed, then the local decision. The exchange already asked the human; it
        //    carries the answer out rather than asking twice.
        val attempt: PakeAttempt = confirmed.session.attempt
        handedOffAttempt = attempt
        if (!progression.pakeConfirmed(attempt, confirmed.session)) {
            return refused(progression.failureCode() ?: SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        }
        if (!progression.localIntendedPeerApproved(confirmed.localDecision)) {
            return refused(progression.failureCode() ?: SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED)
        }
        // 4. The peer's approval, bound to this exact exchange.
        if (!progression.remoteIntendedPeerApproved(confirmed.peerApproval)) {
            return refused(progression.failureCode() ?: SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED)
        }

        // 5-7. Record layer, then key confirmation in both directions.
        val layer = try {
            deriveRecordLayer()
        } catch (_: RuntimeException) {
            null
        } ?: return refused(SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED)
        activeLayer = layer
        val recordIo = channel.recordIo()
        activeRecords = recordIo
        val beforeAuthority = confirmKeys(layer, recordIo)
        if (beforeAuthority != null) return beforeAuthority

        // 8. The reducer's single-use transition, then the authority, which re-derives every link of
        //    the chain from the same objects. It refuses -- returns null -- if any of them disagrees
        //    with the connection, so this is a second opinion, not a formality.
        val transition = progression.authenticated()
            ?: return refused(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        val authority = try {
            AuthenticatedControlAuthority.issue(
                channel = ConnectionRelease(layer, recordIo),
                recordLayer = layer,
                controlSession = controlSession,
                tlsProtocol = channel.protocol,
                transcriptDigest = transcriptDigest,
                attempt = attempt,
                pakeSession = confirmed.session,
                localDecision = transition.localDecision,
                remoteApproval = transition.remoteApproval,
                role = role,
                pairStartedElapsedMillis = startedAtMillis,
                nowElapsedMillis = clock.nowMillis(),
            )
        } catch (_: RuntimeException) {
            null
        }
        if (authority == null) {
            // The exchange handed the attempt over unsettled; nothing was authenticated, so the
            // budget is spent and the attempt settles as a failure rather than staying open.
            runCatching { attempt.abandon() }
            return refused(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        }
        if (!attempt.succeed()) {
            runCatching { authority.close() }
            return refused(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        }
        // Ownership passes to the authority, which closes the record stream and the layer when it is
        // spent. Clearing these keeps a later refusal from releasing them a second time.
        handedOffAttempt = null
        activeLayer = null
        activeRecords = null
        return PakeSessionOutcome.Authenticated(authority, layer, PakePairingState.AUTHENTICATED)
    }

    /**
     * Steps five to seven. Null means "continue"; a non-null outcome is the refusal to return.
     *
     * Split out only to keep [run] readable: the sequence is the protocol, and a reader should be
     * able to see it in one column.
     */
    private fun confirmKeys(layer: SecureRecordLayer, recordIo: PairingRecordIo): PakeSessionOutcome? {
        val written = writeKeyConfirmation(layer, recordIo)
        if (!written) return refused(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        val peerConfirmation = when (val read = readKeyConfirmation(layer, recordIo)) {
            is ConfirmationRead.Refused -> return refused(read.reason)
            is ConfirmationRead.Verified -> read.confirmation
        }
        val verified = try {
            progression.remoteKeyConfirmationVerified(peerConfirmation)
        } finally {
            peerConfirmation.clearSensitive()
        }
        if (!verified) {
            return refused(progression.failureCode() ?: SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        }
        return null
    }

    /** Writes this side's confirmation record and records the transition only if it went out. */
    private fun writeKeyConfirmation(layer: SecureRecordLayer, recordIo: PairingRecordIo): Boolean {
        val confirmation = SecureKeyConfirmation(role, transcriptDigest)
        val plaintext = try {
            SecureKeyConfirmationCodec.encode(confirmation)
        } finally {
            confirmation.clearSensitive()
        }
        val frame = try {
            when (val protected = layer.protect(SecureRecordType.KEY_CONFIRMATION, plaintext)) {
                is SecureRecordResult.Encoded -> protected.frameBytes()
                is SecureRecordResult.Decoded, is SecureRecordResult.Failed -> return false
            }
        } finally {
            plaintext.fill(0)
        }
        // The digest is taken before the frame is wiped: it identifies what actually left this
        // process, which is what a test needs to compare against what the peer read, without either
        // side keeping the frame itself.
        val frameDigest = MessageDigest.getInstance("SHA-256").digest(frame)
        val sent = try {
            recordIo.writeFrame(frame, recordBudgets.writeMillis)
        } finally {
            frame.fill(0)
        }
        if (!sent) {
            frameDigest.fill(0)
            return false
        }
        return try {
            progression.localKeyConfirmationSent(frameDigest)
        } finally {
            frameDigest.fill(0)
        }
    }

    /** One read of the peer's confirmation, or the reason it will not be had. */
    private sealed interface ConfirmationRead {
        class Verified(val confirmation: SecureKeyConfirmation) : ConfirmationRead

        class Refused(val reason: SessionFailureCode) : ConfirmationRead
    }

    /**
     * Reads the peer's confirmation record.
     *
     * The reason the record layer gives is carried out rather than flattened: a frame the AEAD
     * refused and a confirmation that never arrived are different events, and the caller reports
     * which one happened.
     */
    private fun readKeyConfirmation(
        layer: SecureRecordLayer,
        recordIo: PairingRecordIo,
    ): ConfirmationRead {
        // One budget for the whole wait, not a slice per attempt: the framing may hand back a frame
        // split across several reads, and a per-slice timeout would be indistinguishable from a
        // truncated frame. A peer that sends nothing costs the full budget exactly once.
        val deadline = clock.nowMillis() + recordBudgets.readMillis
        var residual = recordBudgets.readMillis
        while (true) {
            if (residual <= 0L) return ConfirmationRead.Refused(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            val now = clock.nowMillis()
            if (now >= deadline) return ConfirmationRead.Refused(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            val budget = minOf(residual, maxOf(1L, deadline - now))
            val read = recordIo.readFrame(layer.sessionId, budget)
            val frame = when (read) {
                is PairingRecordRead.Frame -> read.bytes
                PairingRecordRead.TimedOut -> {
                    // Timed out with nothing consumed: the stream is still aligned, but the budget is
                    // gone, and a peer that did not answer in time is a refusal rather than a retry.
                    residual = 0L
                    continue
                }
                PairingRecordRead.EndOfStream ->
                    return ConfirmationRead.Refused(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
                PairingRecordRead.Invalid ->
                    return ConfirmationRead.Refused(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
            }
            val decoded = try {
                layer.unprotect(frame)
            } finally {
                frame.fill(0)
            }
            val record = when (decoded) {
                is SecureRecordResult.Decoded -> decoded.record
                is SecureRecordResult.Failed -> return ConfirmationRead.Refused(decoded.failure.code)
                is SecureRecordResult.Encoded ->
                    return ConfirmationRead.Refused(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
            }
            if (record.type != SecureRecordType.KEY_CONFIRMATION) {
                record.clearSensitive()
                return ConfirmationRead.Refused(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
            }
            val payload = record.payloadBytes()
            record.clearSensitive()
            val confirmation = try {
                SecureKeyConfirmationCodec.decode(payload)
            } finally {
                payload.fill(0)
            }
            return if (confirmation == null) {
                ConfirmationRead.Refused(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
            } else {
                ConfirmationRead.Verified(confirmation)
            }
        }
    }

    /**
     * Derives the record layer from the TLS exporter.
     *
     * Eight independent exporter calls -- a key and an IV per direction -- rather than one longer
     * output split in two: splitting one output would make the two directions share the exporter's
     * internal chaining, and separate labels cost nothing.
     */
    private fun deriveRecordLayer(): SecureRecordLayer {
        val c2sKey = exporter(C2S_KEY_LABEL, SecureSessionLimits.AES_KEY_BYTES)
        val c2sIv = exporter(C2S_IV_LABEL, SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
        val s2cKey = exporter(S2C_KEY_LABEL, SecureSessionLimits.AES_KEY_BYTES)
        val s2cIv = exporter(S2C_IV_LABEL, SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
        val c2sAead = channel.createAead(c2sKey.copyOf())
        val s2cAead = channel.createAead(s2cKey.copyOf())
        c2sKey.fill(0)
        s2cKey.fill(0)
        val initiator = role == SecurePeerRole.INITIATOR
        return try {
            SecureRecordLayer(
                sessionId = initiatorOffer.secureSessionId,
                localRole = role,
                sendAead = if (initiator) c2sAead else s2cAead,
                receiveAead = if (initiator) s2cAead else c2sAead,
                sendNonceBase = if (initiator) c2sIv else s2cIv,
                receiveNonceBase = if (initiator) s2cIv else c2sIv,
                clock = clock,
            )
        } finally {
            c2sIv.fill(0)
            s2cIv.fill(0)
        }
    }

    /** One exporter output, wiped by nothing here: every caller copies and wipes its own buffer. */
    private fun exporter(label: String, length: Int): ByteArray =
        channel.export(label, transcriptDigest, length)

    private fun selectRequired(): PairingSelectionResult =
        PairingAuthenticationPolicy.selectRequired(initiatorOffer, responderOffer)

    private fun refusalFor(reason: PairingExchangeFailure): SessionFailureCode = when (reason) {
        PairingExchangeFailure.CANCELLED -> SessionFailureCode.OPERATION_CANCELLED
        PairingExchangeFailure.CODE_EXPIRED -> SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED
        PairingExchangeFailure.ATTEMPTS_EXHAUSTED -> SessionFailureCode.SECURE_SESSION_LIMIT_REACHED
        PairingExchangeFailure.TIMEOUT -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
        PairingExchangeFailure.CONNECTION_LOST -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
        PairingExchangeFailure.PROTOCOL -> SessionFailureCode.SECURE_SESSION_RECORD_INVALID
        PairingExchangeFailure.CRYPTO_FAILED -> SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED
        PairingExchangeFailure.ABORTED_BY_PEER -> SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED
        PairingExchangeFailure.APPROVAL_REJECTED -> SessionFailureCode.SECURE_SESSION_APPROVAL_REJECTED
        PairingExchangeFailure.APPROVAL_TIMEOUT -> SessionFailureCode.SECURE_SESSION_APPROVAL_EXPIRED
        PairingExchangeFailure.PROVIDER_FAILURE -> SessionFailureCode.SECURE_SESSION_HANDSHAKE_FAILED
        PairingExchangeFailure.INTERNAL -> SessionFailureCode.INTERNAL_TRANSPORT_FAILURE
    }

    private fun refused(reason: SessionFailureCode): PakeSessionOutcome {
        releaseAfterFailure()
        val state = progression.state()
        val terminal = if (state == PakePairingState.FAILED) {
            state
        } else {
            progression.failedInsideProgression(reason)
            PakePairingState.FAILED
        }
        return PakeSessionOutcome.Refused(reason, terminal)
    }

    /**
     * Settles and releases whatever this run still owns.
     *
     * Order matters: the attempt is settled first so a crash in the stream teardown cannot leave a
     * budget open; then the record stream, so a peer waiting on a confirmation sees the close rather
     * than a timeout; then the layer, which destroys the key material.
     */
    private fun releaseAfterFailure() {
        handedOffAttempt?.let { runCatching { it.abandon() } }
        handedOffAttempt = null
        activeRecords?.let { runCatching { it.close() } }
        activeRecords = null
        activeLayer?.let { runCatching { it.close() } }
        activeLayer = null
    }

    /**
     * What the authority closes when it is spent.
     *
     * Closing releases the framings and then the record layer, in that order: the layer owns key
     * material and the framings own stream state, and releasing the key material first would leave a
     * window where a frame could still be written under a destroyed key. The TLS session itself is
     * the caller's connection, and the caller closes it.
     */
    private inner class ConnectionRelease(
        private val layer: SecureRecordLayer,
        private val records: PairingRecordIo,
    ) : java.io.Closeable {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun close() {
            if (!released.compareAndSet(false, true)) return
            runCatching { records.close() }
            runCatching { layer.close() }
            runCatching { channel.close() }
        }

        override fun toString(): String = "ConnectionRelease"
    }

    private companion object {
        const val REQUIRED_TLS_PROTOCOL: String = "TLSv1.3"
        const val PROOF_LABEL: String = "EXPORTER-MORSEC-PAIRING-PROOF-V1"
        const val C2S_KEY_LABEL: String = "EXPORTER-MORSEC-C2S-KEY-V1"
        const val C2S_IV_LABEL: String = "EXPORTER-MORSEC-C2S-IV-V1"
        const val S2C_KEY_LABEL: String = "EXPORTER-MORSEC-S2C-KEY-V1"
        const val S2C_IV_LABEL: String = "EXPORTER-MORSEC-S2C-IV-V1"
    }
}

/** The record-layer deadlines. Only the record phase uses them; the exchange has its own. */
internal class RecordBudgets(
    val writeMillis: Long,
    val readMillis: Long,
) {
    init {
        require(writeMillis > 0L) { "record write budget must be positive" }
        require(readMillis > 0L) { "record read budget must be positive" }
    }

    internal companion object {
        val DEFAULT: RecordBudgets = RecordBudgets(
            writeMillis = SecureSessionLimits.CONTROL_RECORD_WRITE_DEADLINE_MILLIS,
            readMillis = SecureSessionLimits.CONFIRMATION_DEADLINE_MILLIS,
        )
    }

    override fun toString(): String = "RecordBudgets(write=$writeMillis, read=$readMillis)"
}
