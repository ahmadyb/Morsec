/*
 * One entered-code pairing: attempt-start, three PAKE rounds, retry synchronisation, approvals.
 *
 * WHAT THIS OWNS, IN ORDER, PER ATTEMPT
 *   1. The initiator draws a fresh attempt from the ledger and sends the attempt-start record; the
 *      responder reads it, validates the context and adopts the attempt.
 *   2. Both create their round-one frame, send it, then validate what arrived. Send-then-receive in
 *      every round, because the two sides are symmetric and any other order makes one of them wait
 *      for a frame the other is waiting to send.
 *   3. The same for round two, which derives the keying material inside the adapter, and round
 *      three, which validates the peer's confirmation tag.
 *   4. Both request local intended-peer approval, exchange decision records, and validate that the
 *      peer's record names this exact attempt, session, transcript, suite, profile and opposite
 *      role.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *   No record layer, no key confirmation, no authority, no payload path, and no call site in the
 *   live coordinator. A successful exchange ends at [PairingExchangeResult.Confirmed], which is a
 *   statement that the PAKE and the approvals agreed and nothing more.
 *
 * RETRY SYNCHRONISATION
 *   A peer that abandons a round must not leave the other side blocked until its deadline, so a
 *   failure is announced with one bounded record whose only content is the retry policy. Both peers
 *   must say retry for the same failed attempt or the connection ends. The ways that handshake can
 *   be abused are closed explicitly rather than by timing: a second failure record inside one
 *   attempt is a refusal, a failure record where a round belongs after it has already been consumed
 *   is a refusal, a normal round frame after a local failure is a refusal, and an answer that never
 *   arrives is a refusal.
 *
 * ATTEMPT ACCOUNTING
 *   Both sides charge exactly one attempt per failed attempt, and neither charges a local
 *   cancellation or an internal failure. That symmetry is not cosmetic: each ledger's next ordinal
 *   is `charged + 1`, and the responder refuses an attempt-start whose ordinal does not match, so a
 *   side that silently stopped charging would desynchronise the two and refuse the honest retry.
 *
 * STATUS
 *   Experimental and not independently cryptographically reviewed. The owner accepted that risk on
 *   2026-10-19; see doc/security/owner-risk-waiver-2026-10-19.md. Nothing here is authority.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.JpakeBinding
import app.morsecode.core.transfer.session.JpakeBindingInputs
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.JpakeMessageCodec
import app.morsecode.core.transfer.session.JpakeMessageResult
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalCodec
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeApprovalResult
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.PakeAttemptCharge
import app.morsecode.core.transfer.session.PakeAttemptFailure
import app.morsecode.core.transfer.session.PakeAttemptFailureCodec
import app.morsecode.core.transfer.session.PakeAttemptFailureResult
import app.morsecode.core.transfer.session.PakeAttemptId
import app.morsecode.core.transfer.session.PakeAttemptLedger
import app.morsecode.core.transfer.session.PakeAttemptStart
import app.morsecode.core.transfer.session.PakeAttemptStartCodec
import app.morsecode.core.transfer.session.PakeAttemptStartResult
import app.morsecode.core.transfer.session.PakeAttemptVerdict
import app.morsecode.core.transfer.session.PakeRecordFraming
import app.morsecode.core.transfer.session.PakeRecordKind
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession
import java.security.SecureRandom

/**
 * Both sides of one pairing connection, run to confirmation or refusal.
 *
 * Single use and single threaded: one instance drives one connection from [run] to its result. The
 * object holds no static state, opens no thread and reads no clock other than the injected one.
 *
 * @param role this side's role. The peer must present the opposite role in every frame.
 * @param initiatorOffer the initiator's decoded offer, always with role INITIATOR, on both peers.
 * @param responderOffer the responder's decoded offer, always with role RESPONDER, on both peers.
 *        Both are needed because the authentication context binds the whole negotiated transcript,
 *        and both peers must derive byte-identical context to produce matching participant ids.
 * @param transcriptDigest the MST2 transcript digest both peers already agree on.
 * @param channelBinding the RFC 9266 `tls-exporter` value for this connection.
 */
internal class PairingAttemptExchange(
    private val role: SecurePeerRole,
    private val io: PairingExchangeIo,
    private val interaction: EnteredCodePairingInteraction,
    private val clock: MonotonicClock,
    private val entropy: SecureEntropy,
    private val secureSessionId: SessionId,
    private val controlSessionId: String,
    transcriptDigestInput: ByteArray,
    channelBindingInput: ByteArray,
    private val initiatorOffer: PairingOfferV2,
    private val responderOffer: PairingOfferV2,
    private val random: SecureRandom = SecureRandom(),
    private val budgets: PairingExchangeBudgets = PairingExchangeBudgets.DEFAULT,
) {
    private val transcriptDigest: ByteArray = transcriptDigestInput.copyOf()
    private val channelBinding: ByteArray = channelBindingInput.copyOf()

    private var ledger: PakeAttemptLedger? = null
    private var code: EnteredPairingCode? = null
    private var codeInstance: ByteArray? = null
    private var codeExpiresAtElapsedMillis: Long = 0L
    private var previousAttemptId: PakeAttemptId? = null
    private var session: ModpJpakeSession? = null

    /**
     * The attempt handed to the caller on success.
     *
     * Held so the attempt's own `finally` does not abandon it, and so the ledger is not invalidated
     * under it: the authority is issued against an attempt that is still outstanding, and settling
     * it is the caller's job once the whole chain -- record layer, key confirmation, authority --
     * has actually completed.
     */
    private var handedOffAttempt: PakeAttempt? = null
    private var closed: Boolean = false

    init {
        require(transcriptDigest.size == SecureSessionLimits.TRANSCRIPT_HASH_BYTES) {
            "transcript digest must be ${SecureSessionLimits.TRANSCRIPT_HASH_BYTES} bytes"
        }
        require(channelBinding.size == SecureSessionLimits.TRANSCRIPT_HASH_BYTES) {
            "channel binding must be ${SecureSessionLimits.TRANSCRIPT_HASH_BYTES} bytes"
        }
        require(initiatorOffer.role == SecurePeerRole.INITIATOR) { "first offer must be the initiator" }
        require(responderOffer.role == SecurePeerRole.RESPONDER) { "second offer must be the responder" }
    }

    /** Runs the exchange to a typed outcome. Never throws for anything a peer can cause. */
    fun run(): PairingExchangeResult {
        val startedAt = nowOrNull()
            ?: return failed(PairingExchangeFailure.PROVIDER_FAILURE)
        codeExpiresAtElapsedMillis = saturatingAdd(startedAt, budgets.codeLifetimeMillis)
        var keepSession = false
        try {
            if (role == SecurePeerRole.INITIATOR && !publishGeneratedCode()) {
                return failed(PairingExchangeFailure.CANCELLED)
            }
            var completedAttempts = 0
            while (completedAttempts < PakeAttemptLedger.MAX_PAKE_ATTEMPTS) {
                when (val step = runAttempt()) {
                    is AttemptStep.Confirmed -> {
                        keepSession = true
                        return step.result
                    }
                    AttemptStep.Retry -> completedAttempts++
                    is AttemptStep.Stop -> return failed(step.reason)
                }
            }
            return failed(PairingExchangeFailure.ATTEMPTS_EXHAUSTED)
        } finally {
            closeOnce(keepSession)
        }
    }

    override fun toString(): String =
        "PairingAttemptExchange(role=${role.name}, attempts=${ledger?.attemptsCharged() ?: 0}, ids=[redacted])"

    // --- one attempt -----------------------------------------------------------------------

    private fun runAttempt(): AttemptStep {
        if (cancelled()) return AttemptStep.Stop(PairingExchangeFailure.CANCELLED)
        // The attempt before this one, if any. Frames belonging to it may still be in flight; see
        // [readRoundFrame]. Captured before the identifier below is advanced.
        val stale = previousAttemptId
        val start: PakeAttemptStart
        val attempt: PakeAttempt
        if (role == SecurePeerRole.INITIATOR) {
            val next = ledger?.beginAttempt(entropy)
                ?: return AttemptStep.Stop(PairingExchangeFailure.ATTEMPTS_EXHAUSTED)
            attempt = next
            start = attemptStartRecord(next)
            if (!write(PakeAttemptStartCodec.encode(start), budgets.peerResponseMillis, "attempt-start")) {
                return AttemptStep.Stop(PairingExchangeFailure.CONNECTION_LOST)
            }
        } else {
            val decoded = when (
                val read = readRoundFrame("attempt-start", budgets.peerResponseMillis, stale)
            ) {
                is Read.Frame -> read.bytes
                Read.Cancelled -> return AttemptStep.Stop(PairingExchangeFailure.CANCELLED)
                Read.EndOfStream -> return AttemptStep.Stop(PairingExchangeFailure.CONNECTION_LOST)
                Read.TimedOut -> return AttemptStep.Stop(PairingExchangeFailure.TIMEOUT)
                Read.Invalid -> return AttemptStep.Stop(PairingExchangeFailure.PROTOCOL)
            }
            if (PakeRecordFraming.kindOf(decoded) != PakeRecordKind.ATTEMPT_START) {
                return AttemptStep.Stop(PairingExchangeFailure.PROTOCOL)
            }
            val record = when (val parsed = PakeAttemptStartCodec.decode(decoded)) {
                is PakeAttemptStartResult.Success -> parsed.record
                is PakeAttemptStartResult.Rejected -> return AttemptStep.Stop(PairingExchangeFailure.PROTOCOL)
            }
            startContextFailure(record)?.let { failure ->
                record.clearSensitive()
                return AttemptStep.Stop(failure)
            }
            if (ledger == null) {
                val instance = record.codeInstanceBytes()
                codeInstance = instance
                ledger = PakeAttemptLedger(instance)
            }
            val adopted = ledger?.adoptAttempt(record.attemptId, record.attemptOrdinal)
            if (adopted == null) {
                record.clearSensitive()
                return AttemptStep.Stop(PairingExchangeFailure.PROTOCOL)
            }
            attempt = adopted
            start = record
        }

        try {
            // An attempt identifier is an identity. Two attempts sharing one would be
            // indistinguishable inside the authentication context, so a peer that re-presents the
            // previous attempt's identifier is refused here rather than trusted to the proofs.
            // Updated for every attempt, including failed ones, so a retry cannot quietly reuse the
            // identity of the attempt it is retrying.
            if (attempt.id == previousAttemptId) {
                return AttemptStep.Stop(PairingExchangeFailure.PROTOCOL)
            }
            previousAttemptId = attempt.id
            val entered = obtainCode(attempt.ordinal)
                ?: return AttemptStep.Stop(
                    if (cancelled()) PairingExchangeFailure.CANCELLED else PairingExchangeFailure.CODE_EXPIRED,
                )
            val opened = openSession(attempt, start, entered)
                ?: return AttemptStep.Stop(PairingExchangeFailure.PROVIDER_FAILURE)
            session = opened
            when (val rounds = runRounds(opened, attempt.id, stale)) {
                RoundOutcome.Confirmed -> Unit
                is RoundOutcome.PeerFailed -> return synchronizeRemoteFailure(attempt, rounds.verdict)
                is RoundOutcome.Failed -> return synchronizeLocalFailure(attempt, rounds.reason)
                is RoundOutcome.Stopped -> return AttemptStep.Stop(rounds.reason)
            }
            when (val approval = runApproval(attempt)) {
                is ApprovalOutcome.Confirmed -> {
                    // Deliberately not settled here. A confirmed exchange is three rounds and two
                    // approvals, not an authenticated session: the caller still has to derive the
                    // record layer, confirm both keys and issue authority, and the authority is
                    // refused for an attempt that has already been settled. Handing the attempt
                    // over unsettled is what makes that chain enforceable rather than advisory.
                    handedOffAttempt = attempt
                    return AttemptStep.Confirmed(
                        PairingExchangeResult.Confirmed(
                            attemptId = attempt.id,
                            attemptOrdinal = attempt.ordinal,
                            localDecision = approval.localDecision,
                            peerDecision = approval.peerApproval.decision,
                            peerApproval = approval.peerApproval,
                            session = opened,
                        ),
                    )
                }
                is ApprovalOutcome.Stop -> return AttemptStep.Stop(approval.reason)
            }
        } finally {
            start.clearSensitive()
            // A stop the peer cannot attribute -- a cancellation, a deadline, a protocol refusal --
            // must not leave the ledger believing an attempt is still running. Abandoning is
            // uncharged and idempotent, so the paths that already charged or succeeded keep their
            // settlement and this is a no-op for them.
            if (attempt !== handedOffAttempt && !attempt.isSettled()) attempt.abandon()
        }
    }

    private fun publishGeneratedCode(): Boolean {
        if (cancelled()) return false
        val generated = try {
            EnteredPairingCode.generate(entropy)
        } catch (_: RuntimeException) {
            return false
        }
        code = generated
        val instance = newCodeInstance()
        codeInstance = instance
        ledger = PakeAttemptLedger(instance)
        return interaction.publishGeneratedCode(generated, codeExpiresAtElapsedMillis)
    }

    /**
     * The code this attempt runs with.
     *
     * The initiator keeps the one it generated: a retry is a second attempt against the same
     * displayed code. The responder is asked again, because a human who mistyped the first time
     * needs the chance to type it correctly -- retrying the same wrong symbols would fail
     * identically and merely spend the budget.
     */
    private fun obtainCode(ordinal: Int): EnteredPairingCode? {
        if (role != SecurePeerRole.RESPONDER) return code
        if (cancelled()) return null
        val now = nowOrNull() ?: return null
        val deadline = minOf(codeExpiresAtElapsedMillis, saturatingAdd(now, budgets.peerResponseMillis))
        val entered = try {
            interaction.requestEnteredCode(ordinal, deadline)
        } catch (_: RuntimeException) {
            return null
        }
        if (entered != null) code = entered
        return entered
    }

    private fun attemptStartRecord(attempt: PakeAttempt): PakeAttemptStart = PakeAttemptStart(
        suite = PairingAuthenticationPolicy.REQUIRED_SUITE,
        profile = JpakeCurveProfile.MODP_3072,
        role = role,
        secureSessionId = secureSessionId,
        controlSessionId = controlSessionId,
        attemptId = attempt.id,
        attemptOrdinal = attempt.ordinal,
        codeInstanceId = attempt.codeInstanceBytes(),
        transcriptDigest = transcriptDigest,
    )

    /**
     * Everything an attempt-start record must already agree with before it is adopted.
     *
     * This runs before any group arithmetic, so a substituted session, channel, transcript, suite
     * or role costs a peer nothing but a refusal. The code-instance id is checked against the first
     * one seen on this connection: it is stable for the life of a generated code, so a different
     * value on attempt two means the peer is replaying an exchange from a different code.
     */
    private fun startContextFailure(record: PakeAttemptStart): PairingExchangeFailure? {
        if (record.suite != PairingAuthenticationPolicy.REQUIRED_SUITE) return PairingExchangeFailure.PROTOCOL
        if (record.profile != JpakeCurveProfile.MODP_3072) return PairingExchangeFailure.PROTOCOL
        if (record.role != role.opposite) return PairingExchangeFailure.PROTOCOL
        if (record.secureSessionId != secureSessionId) return PairingExchangeFailure.PROTOCOL
        if (record.controlSessionId != controlSessionId) return PairingExchangeFailure.PROTOCOL
        val presented = record.transcriptDigestBytes()
        val transcriptMatches = try {
            presented.contentEquals(transcriptDigest)
        } finally {
            presented.fill(0)
        }
        if (!transcriptMatches) return PairingExchangeFailure.PROTOCOL
        val boundInstance = codeInstance
        if (boundInstance != null) {
            val instance = record.codeInstanceBytes()
            val matches = try {
                instance.contentEquals(boundInstance)
            } finally {
                instance.fill(0)
            }
            if (!matches) return PairingExchangeFailure.PROTOCOL
        }
        return null
    }

    /**
     * Derives this attempt's participant identifiers and opens the library session.
     *
     * The identifiers are a function of the authentication context, which binds the TLS channel,
     * the transcript, the session ids and this exact attempt. Both peers compute them
     * independently, so neither trusts one the other sent -- which is what makes a replayed frame
     * name the wrong participant and fail before a proof is checked.
     */
    private fun openSession(
        attempt: PakeAttempt,
        start: PakeAttemptStart,
        entered: EnteredPairingCode,
    ): ModpJpakeSession? {
        val instance = attempt.codeInstanceBytes()
        val startDigest = start.digest()
        val inputs = JpakeBindingInputs(
            channelBinding = channelBinding,
            transcriptDigest = transcriptDigest,
            codeInstanceId = instance,
            attemptId = attempt.id,
            attemptOrdinal = attempt.ordinal,
            attemptStartDigest = startDigest,
            formatVersion = 1,
            suite = PairingAuthenticationPolicy.REQUIRED_SUITE,
            initiatorOffer = initiatorOffer,
            responderOffer = responderOffer,
        )
        instance.fill(0)
        startDigest.fill(0)
        return try {
            val context = JpakeBinding.authenticationContext(inputs)
            val ids = try {
                JpakeBinding.participantIds(context)
            } finally {
                context.fill(0)
            }
            // The library copies what it is given and wipes its own copy when the keying material
            // is calculated. The array handed over here is ours, and it is wiped immediately.
            val chars = entered.toPasswordChars()
            try {
                ModpJpakeSession(role, attempt, ids, secureSessionId, controlSessionId, chars, random)
            } finally {
                chars.fill('\u0000')
            }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        } finally {
            inputs.clearSensitive()
        }
    }

    // --- rounds ----------------------------------------------------------------------------

    private sealed interface RoundOutcome {
        data object Confirmed : RoundOutcome
        class Failed(val reason: PairingExchangeFailure) : RoundOutcome
        class PeerFailed(val verdict: PakeAttemptVerdict) : RoundOutcome
        class Stopped(val reason: PairingExchangeFailure) : RoundOutcome
    }

    /**
     * The three rounds, strictly in order.
     *
     * Written as three statements rather than a list because the list would have evaluated all
     * three steps before looking at the first result: every round would run even after one had
     * already failed. Each step is only begun when the one before it was accepted.
     */
    private fun runRounds(
        target: ModpJpakeSession,
        current: PakeAttemptId,
        stale: PakeAttemptId?,
    ): RoundOutcome {
        val first = exchangeStep(
            label = "round-one",
            budgetMillis = budgets.peerResponseMillis,
            current = current,
            discardStaleFor = stale,
            create = { target.createRoundOne() },
            accept = { target.acceptRoundOne(it) },
        )
        if (first !is StepOutcome.Accepted) return roundOutcome(first)
        val second = exchangeStep(
            label = "round-two",
            budgetMillis = budgets.roundMillis,
            current = current,
            create = { target.createRoundTwo() },
            accept = { target.acceptRoundTwo(it) },
        )
        if (second !is StepOutcome.Accepted) return roundOutcome(second)
        val third = exchangeStep(
            label = "round-three",
            budgetMillis = budgets.roundMillis,
            current = current,
            create = { target.createConfirmation() },
            accept = { target.acceptConfirmation(it) },
        )
        if (third !is StepOutcome.Accepted) return roundOutcome(third)
        return if (target.isConfirmed()) {
            RoundOutcome.Confirmed
        } else {
            // Every step was accepted and the adapter still does not report confirmation, which is
            // an invariant violation on this side, not something the peer can cause.
            RoundOutcome.Failed(PairingExchangeFailure.INTERNAL)
        }
    }

    private fun roundOutcome(step: StepOutcome): RoundOutcome = when (step) {
        StepOutcome.Accepted -> RoundOutcome.Failed(PairingExchangeFailure.INTERNAL)
        is StepOutcome.Failed -> RoundOutcome.Failed(step.reason)
        is StepOutcome.PeerFailed -> RoundOutcome.PeerFailed(step.verdict)
        is StepOutcome.Stopped -> RoundOutcome.Stopped(step.reason)
    }

    private sealed interface StepOutcome {
        data object Accepted : StepOutcome
        class Failed(val reason: PairingExchangeFailure) : StepOutcome
        class PeerFailed(val verdict: PakeAttemptVerdict) : StepOutcome
        class Stopped(val reason: PairingExchangeFailure) : StepOutcome
    }

    /**
     * One round: create, send, receive, validate — in that order, on both sides.
     *
     * The receive step accepts exactly two things: the round frame this session is waiting for, or
     * a failure record. Anything else — a control record, a round from another attempt, a frame the
     * adapter refuses — is a failed attempt, not a crash.
     */
    private fun exchangeStep(
        label: String,
        budgetMillis: Long,
        current: PakeAttemptId,
        discardStaleFor: PakeAttemptId? = null,
        create: () -> ByteArray,
        accept: (ByteArray) -> ModpJpakeSession.Step,
    ): StepOutcome {
        if (cancelled()) return StepOutcome.Stopped(PairingExchangeFailure.CANCELLED)
        val local = try {
            create()
        } catch (_: RuntimeException) {
            return StepOutcome.Stopped(PairingExchangeFailure.PROVIDER_FAILURE)
        }
        if (!write(local, budgetMillis, label)) return StepOutcome.Stopped(PairingExchangeFailure.CONNECTION_LOST)
        return when (val read = readRoundFrame(label, budgetMillis, discardStaleFor)) {
            Read.Cancelled -> StepOutcome.Stopped(PairingExchangeFailure.CANCELLED)
            Read.EndOfStream -> StepOutcome.Stopped(PairingExchangeFailure.CONNECTION_LOST)
            Read.TimedOut -> StepOutcome.Stopped(PairingExchangeFailure.TIMEOUT)
            // A frame the transport could not even frame leaves the stream at an unknown record
            // boundary, so this is not a failed attempt that may be retried: it ends the exchange.
            Read.Invalid -> StepOutcome.Stopped(PairingExchangeFailure.PROTOCOL)
            is Read.Frame -> acceptFrame(read.bytes, current, accept)
        }
    }

    private fun acceptFrame(
        frame: ByteArray,
        current: PakeAttemptId,
        accept: (ByteArray) -> ModpJpakeSession.Step,
    ): StepOutcome {
        return when (PakeRecordFraming.kindOf(frame)) {
            // The peer gave up on this attempt and is asking what to do about it.
            PakeRecordKind.FAILURE -> {
                val verdict = failureVerdict(frame, current)
                if (verdict == null) {
                    StepOutcome.Stopped(PairingExchangeFailure.PROTOCOL)
                } else {
                    StepOutcome.PeerFailed(verdict)
                }
            }
            // Any other control record where a round belongs is a protocol error: it names a
            // different exchange phase, and retrying would only spend an attempt on a peer that is
            // confused or replaying. Only "this attempt failed" is an outcome that may be retried.
            PakeRecordKind.ATTEMPT_START,
            PakeRecordKind.APPROVAL,
            -> StepOutcome.Stopped(PairingExchangeFailure.PROTOCOL)
            // Not a control record, so it has to be the round frame this step is waiting for.
            // Whether it is well formed, in the right phase and cryptographically valid is the
            // adapter's call.
            null -> {
                val accepted = try {
                    accept(frame)
                } catch (_: RuntimeException) {
                    // A hostile round frame must cost an attempt, not the process.
                    return StepOutcome.Failed(PairingExchangeFailure.PROTOCOL)
                }
                when (accepted) {
                    is ModpJpakeSession.Step.Accepted -> StepOutcome.Accepted
                    is ModpJpakeSession.Step.Rejected ->
                        StepOutcome.Failed(PairingExchangeFailure.CRYPTO_FAILED)
                }
            }
        }
    }

    /**
     * Reads one frame, skipping round frames that provably belong to [discardStaleFor].
     *
     * WHY THIS EXISTS AT ALL
     *   Each round is write-then-read on both sides, so a peer that got past the failed step has
     *   exactly one frame in flight when a failure is agreed: it wrote the next round before it
     *   learned that its partner had stopped. Without this, the retry would read that residue as if
     *   it were the new attempt's first round, and a legitimate retry would always fail.
     *
     *   Two places need it, with different identities. At a retry boundary the residue names the
     *   *previous* attempt. Inside the failure handshake it names the *current* one, because a peer
     *   that was ahead when the failure was raised still has that round queued ahead of its answer.
     *
     * WHY THIS IS NOT A REPLAY HOLE
     *   A frame is skipped only when it decodes, carries the magic of a round frame, and names the
     *   attempt immediately before this one. A frame naming any other attempt, an undecodable
     *   frame, and a control record are all passed through to their normal handling, which refuses
     *   them. At most [MAX_STALE_FRAMES] is skipped in total, so a peer cannot use this to make the
     *   exchange spin: the frame after the allowance is handled normally.
     */
    private fun readRoundFrame(
        label: String,
        budgetMillis: Long,
        discardStaleFor: PakeAttemptId?,
    ): Read {
        val allowance = ReadAllowance(budgetMillis)
        if (discardStaleFor == null) return allowance.read(label)
        var discarded = 0
        while (discarded < MAX_STALE_FRAMES) {
            val read = allowance.read(label)
            if (read !is Read.Frame) return read
            if (!namesAttempt(read.bytes, discardStaleFor)) return read
            discarded += 1
        }
        return allowance.read(label)
    }

    /**
     * Reads the peer's answer to this side's failure record.
     *
     * A peer that had already written its next round is one frame ahead; that frame names the
     * attempt that just failed, so it is skipped. Anything else -- a second failure record, a
     * stale frame from an earlier attempt, a malformed frame -- is passed through and refused.
     */
    private fun readFailureAnswer(attempt: PakeAttempt): Read =
        readRoundFrame("failure-answer", budgets.peerResponseMillis, attempt.id)

    /** True when the frame is a decodable round frame naming exactly [attemptId]. */
    private fun namesAttempt(frame: ByteArray, attemptId: PakeAttemptId): Boolean {
        if (!JpakeMessageCodec.hasMagic(frame)) return false
        if (frame.size < JpakeMessageCodec.HEADER_BYTES) return false
        val payload = frame.copyOfRange(JpakeMessageCodec.HEADER_BYTES, frame.size)
        return try {
            val decoded = JpakeMessageCodec.decode(payload)
            decoded is JpakeMessageResult.Success && decoded.message.attemptId == attemptId
        } catch (_: RuntimeException) {
            // A decoder that cannot classify the frame has not proven it stale, so the frame is
            // passed through and refused by the code that expects it.
            false
        } finally {
            payload.fill(0)
        }
    }

    /**
     * The peer's verdict, but only when the record names the attempt it was expected in.
     *
     * The record has no reason field, so the only thing it can be checked against is identity. A
     * verdict for another attempt -- a replay from an earlier retry, or an answer to a different
     * exchange -- is refused here rather than being allowed to end a live attempt.
     */
    private fun failureVerdict(frame: ByteArray, expected: PakeAttemptId): PakeAttemptVerdict? =
        when (val decoded = PakeAttemptFailureCodec.decode(frame)) {
            is PakeAttemptFailureResult.Success ->
                if (decoded.record.attemptId == expected) decoded.record.verdict else null
            is PakeAttemptFailureResult.Rejected -> null
        }

    // --- the failure handshake --------------------------------------------------------------

    /**
     * This side failed. Announce it, then learn whether the peer will retry.
     *
     * Both peers charge the attempt, because a failed attempt is a failed attempt on both sides and
     * their ordinals have to stay aligned for the retry to be accepted. The record says only which
     * policy each side will follow, never which check failed.
     */
    private fun synchronizeLocalFailure(
        attempt: PakeAttempt,
        reason: PairingExchangeFailure,
    ): AttemptStep {
        val charge = attempt.charge()
        val verdict = localVerdict(charge)
        if (!writeFailure(attempt, verdict)) return AttemptStep.Stop(PairingExchangeFailure.CONNECTION_LOST)
        if (verdict == PakeAttemptVerdict.ABORT) {
            // The verdict is a policy; the local reason says which policy was reached.
            val stop = when {
                charge == PakeAttemptCharge.EXHAUSTED -> PairingExchangeFailure.ATTEMPTS_EXHAUSTED
                codeExpired() -> PairingExchangeFailure.CODE_EXPIRED
                cancelled() -> PairingExchangeFailure.CANCELLED
                else -> reason
            }
            return AttemptStep.Stop(stop)
        }
        return when (val read = readFailureAnswer(attempt)) {
            Read.Cancelled -> AttemptStep.Stop(PairingExchangeFailure.CANCELLED)
            Read.EndOfStream -> AttemptStep.Stop(PairingExchangeFailure.CONNECTION_LOST)
            Read.TimedOut -> AttemptStep.Stop(PairingExchangeFailure.TIMEOUT)
            // A malformed failure record, a duplicate field, a missing field or a frame the
            // transport could not frame: the peer did not follow the handshake, so it ends.
            Read.Invalid -> AttemptStep.Stop(PairingExchangeFailure.PROTOCOL)
            is Read.Frame -> {
                val answer = failureVerdict(read.bytes, attempt.id)
                    ?: return AttemptStep.Stop(PairingExchangeFailure.PROTOCOL)
                if (answer == PakeAttemptVerdict.RETRY) {
                    AttemptStep.Retry
                } else {
                    AttemptStep.Stop(PairingExchangeFailure.ABORTED_BY_PEER)
                }
            }
        }
    }

    /**
     * The peer failed. Answer it, then either retry or end.
     *
     * The answer is sent even when this side aborts, so the peer is never left waiting for a reply
     * that will not come.
     */
    private fun synchronizeRemoteFailure(
        attempt: PakeAttempt,
        peerVerdict: PakeAttemptVerdict,
    ): AttemptStep {
        val charge = attempt.charge()
        val verdict = localVerdict(charge)
        if (!writeFailure(attempt, verdict)) return AttemptStep.Stop(PairingExchangeFailure.CONNECTION_LOST)
        if (verdict == PakeAttemptVerdict.RETRY && peerVerdict == PakeAttemptVerdict.RETRY) {
            return AttemptStep.Retry
        }
        // The reason reported is this side's, so it is derived in the order this side would have
        // stopped. "Aborted by the peer" is only honest when this side would still have retried.
        val reason = when {
            verdict == PakeAttemptVerdict.ABORT && cancelled() -> PairingExchangeFailure.CANCELLED
            verdict == PakeAttemptVerdict.ABORT && codeExpired() -> PairingExchangeFailure.CODE_EXPIRED
            charge == PakeAttemptCharge.EXHAUSTED -> PairingExchangeFailure.ATTEMPTS_EXHAUSTED
            peerVerdict == PakeAttemptVerdict.ABORT -> PairingExchangeFailure.ABORTED_BY_PEER
            else -> PairingExchangeFailure.INTERNAL
        }
        return AttemptStep.Stop(reason)
    }

    private fun localVerdict(charge: PakeAttemptCharge): PakeAttemptVerdict = when {
        charge != PakeAttemptCharge.CHARGED -> PakeAttemptVerdict.ABORT
        cancelled() -> PakeAttemptVerdict.ABORT
        codeExpired() -> PakeAttemptVerdict.ABORT
        else -> PakeAttemptVerdict.RETRY
    }

    private fun writeFailure(attempt: PakeAttempt, verdict: PakeAttemptVerdict): Boolean =
        write(
            PakeAttemptFailureCodec.encode(PakeAttemptFailure(attempt.id, verdict)),
            budgets.peerResponseMillis,
            "attempt-failure",
        )

    // --- approval ---------------------------------------------------------------------------

    private sealed interface ApprovalOutcome {
        class Confirmed(
            val localDecision: SecureApprovalDecision,
            val peerApproval: PakeApproval,
        ) : ApprovalOutcome
        class Stop(val reason: PairingExchangeFailure) : ApprovalOutcome
    }

    /**
     * The intended-peer decision, after round three and never before it.
     *
     * Both sides write their record before reading the peer's, for the same symmetry reason the
     * rounds do. A local rejection is sent and then the connection ends: the peer should learn it
     * was refused rather than wait for a decision that is not coming.
     */
    private fun runApproval(attempt: PakeAttempt): ApprovalOutcome {
        if (cancelled()) return ApprovalOutcome.Stop(PairingExchangeFailure.CANCELLED)
        val now = nowOrNull() ?: return ApprovalOutcome.Stop(PairingExchangeFailure.PROVIDER_FAILURE)
        val deadline = minOf(codeExpiresAtElapsedMillis, saturatingAdd(now, budgets.approvalMillis))
        val answer = try {
            interaction.requestIntendedPeerApproval(intendedPeerContext(attempt.ordinal), deadline)
        } catch (_: RuntimeException) {
            null
        }
        if (answer == null) {
            return ApprovalOutcome.Stop(
                if (cancelled()) PairingExchangeFailure.CANCELLED else PairingExchangeFailure.APPROVAL_TIMEOUT,
            )
        }
        val decision = if (answer == SecureApprovalDecision.APPROVE) {
            PakeApprovalDecision.APPROVE
        } else {
            PakeApprovalDecision.REJECT
        }
        val local = newApproval(attempt, decision)
        val frame = PakeApprovalCodec.encode(local)
        local.clearSensitive()
        if (!write(frame, budgets.peerResponseMillis, "approval")) {
            return ApprovalOutcome.Stop(PairingExchangeFailure.CONNECTION_LOST)
        }
        if (decision == PakeApprovalDecision.REJECT) {
            return ApprovalOutcome.Stop(PairingExchangeFailure.APPROVAL_REJECTED)
        }
        val received = when (val read = readWithBudget("approval", budgets.peerResponseMillis)) {
            Read.Cancelled -> return ApprovalOutcome.Stop(PairingExchangeFailure.CANCELLED)
            Read.EndOfStream -> return ApprovalOutcome.Stop(PairingExchangeFailure.CONNECTION_LOST)
            Read.TimedOut -> return ApprovalOutcome.Stop(PairingExchangeFailure.APPROVAL_TIMEOUT)
            Read.Invalid -> return ApprovalOutcome.Stop(PairingExchangeFailure.PROTOCOL)
            is Read.Frame -> read.bytes
        }
        val peer = when (val parsed = PakeApprovalCodec.decode(received)) {
            is PakeApprovalResult.Success -> parsed.record
            // A round frame, a failure record or a malformed approval is a refusal, not a decision.
            is PakeApprovalResult.Rejected -> return ApprovalOutcome.Stop(PairingExchangeFailure.PROTOCOL)
        }
        val binds = approvalBinds(attempt, peer)
        if (!binds) {
            peer.clearSensitive()
            return ApprovalOutcome.Stop(PairingExchangeFailure.PROTOCOL)
        }
        if (peer.decision != PakeApprovalDecision.APPROVE) {
            peer.clearSensitive()
            return ApprovalOutcome.Stop(PairingExchangeFailure.APPROVAL_REJECTED)
        }
        // The decision this side actually recorded, carried out with the peer's record so the
        // caller does not have to ask the human a second time to learn what they already said.
        return ApprovalOutcome.Confirmed(
            localDecision = if (decision == PakeApprovalDecision.APPROVE) {
                SecureApprovalDecision.APPROVE
            } else {
                SecureApprovalDecision.REJECT
            },
            peerApproval = peer,
        )
    }

    /**
     * True only when the record names this exchange exactly.
     *
     * The role is part of it: an approval reflected back to its sender names the sender's own role
     * and is refused, which is what stops a peer from satisfying "the remote approved" with a copy
     * of the local record.
     */
    private fun approvalBinds(attempt: PakeAttempt, approval: PakeApproval): Boolean {
        val instance = attempt.codeInstanceBytes()
        return try {
            approval.bindsTo(
                expectedAttemptId = attempt.id,
                expectedOrdinal = attempt.ordinal,
                expectedCodeInstance = instance,
                expectedTranscriptDigest = transcriptDigest,
                expectedSecureSessionId = secureSessionId,
                expectedControlSessionId = controlSessionId,
                expectedSuite = PairingAuthenticationPolicy.REQUIRED_SUITE,
                expectedProfile = JpakeCurveProfile.MODP_3072,
                expectedPeerRole = role.opposite,
            )
        } finally {
            instance.fill(0)
        }
    }

    private fun newApproval(attempt: PakeAttempt, decision: PakeApprovalDecision): PakeApproval {
        val instance = attempt.codeInstanceBytes()
        return try {
            PakeApproval(
                decision = decision,
                suite = PairingAuthenticationPolicy.REQUIRED_SUITE,
                profile = JpakeCurveProfile.MODP_3072,
                role = role,
                secureSessionId = secureSessionId,
                controlSessionId = controlSessionId,
                attemptId = attempt.id,
                attemptOrdinal = attempt.ordinal,
                codeInstanceId = instance,
                transcriptDigest = transcriptDigest,
            )
        } finally {
            instance.fill(0)
        }
    }

    private fun intendedPeerContext(ordinal: Int): IntendedPeerContext {
        val local = offerFor(role)
        return IntendedPeerContext(
            controlSessionId = controlSessionId,
            secureSessionId = secureSessionId,
            attemptOrdinal = ordinal,
            localRole = role,
            localPeerInstanceId = local.localPeerInstanceId.value,
            remotePeerInstanceId = local.remotePeerInstanceId.value,
        )
    }

    private fun offerFor(target: SecurePeerRole): PairingOfferV2 =
        if (target == SecurePeerRole.INITIATOR) initiatorOffer else responderOffer

    // --- transport --------------------------------------------------------------------------

    private sealed interface Read {
        class Frame(val bytes: ByteArray) : Read
        data object EndOfStream : Read
        data object TimedOut : Read
        data object Invalid : Read
        data object Cancelled : Read
    }

    /**
     * A read allowance that several reads may draw on.
     *
     * The budget is counted down rather than compared against the clock, so a transport whose clock
     * does not advance -- a test queue, for instance -- still terminates. A slice that times out
     * costs exactly its own length, and a frame that arrives early costs nothing.
     *
     * It is an object rather than a parameter because discarding a stale frame must not hand the
     * caller a second allowance: one round has one deadline, however many frames arrive inside it.
     */
    private inner class ReadAllowance(private var remaining: Long) {
        fun read(label: String): Read {
            while (true) {
                if (cancelled()) return Read.Cancelled
                if (remaining <= 0L) return Read.TimedOut
                val slice = minOf(CANCELLATION_SLICE_MILLIS, remaining)
                when (val read = io.readFrame(label, slice)) {
                    is PairingFrameRead.Frame -> {
                        if (read.bytes.size > PairingExchangeWire.MAX_FRAME_BYTES) {
                            return Read.Invalid
                        }
                        return Read.Frame(read.bytes)
                    }
                    PairingFrameRead.EndOfStream -> return Read.EndOfStream
                    PairingFrameRead.Invalid -> return Read.Invalid
                    PairingFrameRead.TimedOut -> {
                        remaining -= slice
                    }
                }
            }
        }
    }

    private fun readWithBudget(label: String, budgetMillis: Long): Read =
        ReadAllowance(budgetMillis).read(label)

    private fun write(frame: ByteArray, budgetMillis: Long, label: String): Boolean {
        if (cancelled()) return false
        if (frame.size > PairingExchangeWire.MAX_FRAME_BYTES) return false
        return io.writeFrame(label, frame, budgetMillis)
    }

    // --- lifecycle --------------------------------------------------------------------------

    /**
     * Releases everything exactly once.
     *
     * On success the confirmed session is handed to the caller, which owns it from that point; on
     * every other path it is closed here. The entered code is wiped on both paths, and so is the
     * generated code instance.
     */
    private fun closeOnce(keepSession: Boolean) {
        if (closed) return
        closed = true
        if (!keepSession) {
            session?.close()
        }
        session = null
        code?.clearSensitive()
        code = null
        codeInstance?.fill(0)
        codeInstance = null
        if (!keepSession) {
            // On success the ledger stays valid: the handed-off attempt still has to settle, and
            // invalidating here would wipe the code instance the caller's authority is bound to.
            ledger?.invalidate()
        }
        ledger = null
        io.close()
    }

    private fun cancelled(): Boolean = try {
        interaction.isCancelled()
    } catch (_: RuntimeException) {
        true
    }

    private fun codeExpired(): Boolean {
        val now = nowOrNull() ?: return true
        return now >= codeExpiresAtElapsedMillis
    }

    private fun nowOrNull(): Long? = try {
        clock.nowMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    private fun newCodeInstance(): ByteArray {
        val instance = ByteArray(PakeAttemptLedger.CODE_INSTANCE_BYTES)
        random.nextBytes(instance)
        return instance
    }

    private fun saturatingAdd(value: Long, duration: Long): Long =
        if (value > Long.MAX_VALUE - duration) Long.MAX_VALUE else value + duration

    private fun failed(reason: PairingExchangeFailure): PairingExchangeResult.Failed =
        PairingExchangeResult.Failed(reason = reason, attemptsCharged = ledger?.attemptsCharged() ?: 0)

    private sealed interface AttemptStep {
        class Confirmed(val result: PairingExchangeResult) : AttemptStep
        data object Retry : AttemptStep
        class Stop(val reason: PairingExchangeFailure) : AttemptStep
    }

    private companion object {
        /**
         * How long one poll of a blocking read may take before cancellation is re-checked.
         *
         * Short enough that a cancelled pairing releases its worker promptly, long enough that an
         * idle wait is not a spin. Production cancellation also closes the socket, so this is the
         * bound that holds when it does not.
         */
        const val CANCELLATION_SLICE_MILLIS: Long = 50L

        /**
         * How many frames from the abandoned attempt may be skipped at a retry boundary.
         *
         * One is the most that can exist: each side writes at most one frame per round and only
         * reads afterwards, so a peer that raced ahead of a failure is ahead by exactly one frame.
         * The allowance is not larger because every skipped frame is one the peer chose to send.
         */
        const val MAX_STALE_FRAMES: Int = 1
    }
}
