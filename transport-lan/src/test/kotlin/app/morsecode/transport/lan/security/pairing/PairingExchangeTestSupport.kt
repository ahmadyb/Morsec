/*
 * The harness the exchange tests are built on: two blocking frame channels, a controllable clock,
 * a controllable human, and a scripted peer that can deviate at exactly one point.
 *
 * Real frames, real crypto, real threads. The exchange under test is never handed a decoded object
 * and never has its state inspected: everything it sees arrives through [PairingExchangeIo] as the
 * same bytes a socket would carry, and everything asserted is either its typed result or what the
 * other end of the channel observed.
 *
 * The scripted peer exists so a required-but-unreachable behaviour can be produced on demand -- a
 * failure record where a round belongs, a silence where a round belongs, a closed connection -- with
 * the rounds before it performed honestly. A test that faked those rounds would prove nothing about
 * which frame the exchange accepted.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.JpakeBinding
import app.morsecode.core.transfer.session.JpakeBindingInputs
import app.morsecode.core.transfer.session.JpakeCurveProfile
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingAuthenticationPolicy
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PakeApproval
import app.morsecode.core.transfer.session.PakeApprovalCodec
import app.morsecode.core.transfer.session.PakeApprovalDecision
import app.morsecode.core.transfer.session.PakeApprovalResult
import app.morsecode.core.transfer.session.PakeAttempt
import app.morsecode.core.transfer.session.PakeAttemptFailure
import app.morsecode.core.transfer.session.PakeAttemptFailureCodec
import app.morsecode.core.transfer.session.PakeAttemptFailureResult
import app.morsecode.core.transfer.session.PakeAttemptId
import app.morsecode.core.transfer.session.PakeAttemptLedger
import app.morsecode.core.transfer.session.PakeAttemptStart
import app.morsecode.core.transfer.session.PakeAttemptStartCodec
import app.morsecode.core.transfer.session.PakeAttemptStartResult
import app.morsecode.core.transfer.session.PakeAttemptVerdict
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.transport.lan.security.jpake.ModpJpakeSession
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A clock the test moves by hand, so no assertion depends on how fast a machine is. */
internal class TestClock(startMillis: Long = 1_000L) : MonotonicClock {
    private val current = AtomicLong(startMillis)

    override fun nowMillis(): Long = current.get()

    fun advanceBy(millis: Long) {
        current.addAndGet(millis)
    }
}

/**
 * Deterministic bytes that differ on every call.
 *
 * Differing per call is not cosmetic: two attempts that received identical entropy would present the
 * same attempt identifier, and the exchange refuses that as a replay before anything else runs.
 */
internal class CountingEntropy(private val base: Int = 0x40) : SecureEntropy {
    private val calls = AtomicInteger(0)

    override fun nextBytes(destination: ByteArray) {
        val call = calls.incrementAndGet()
        for (index in destination.indices) {
            destination[index] = (base + call * 7 + index).toByte()
        }
    }
}

/** What the initiator displayed, shared with the responder the way a human would read it out. */
internal class CodeBoard {
    @Volatile
    private var text: String? = null

    fun publish(value: String) {
        text = value
    }

    fun await(timeoutMillis: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            text?.let { return it }
            Thread.sleep(2L)
        }
        return text
    }
}

/** The displayed code without the grouping dash, which is presentation only. */
internal fun plainCode(text: String): String = text.replace("-", "")

/** A code that is one symbol away from [text], and therefore wrong. */
internal fun wrongCode(text: String): String {
    val plain = plainCode(text)
    val first = if (plain[0] == '0') '1' else '0'
    return first + plain.substring(1)
}

/**
 * A human, scripted.
 *
 * Cancellation, the typed code and the approval are all fields rather than callbacks so a test can
 * change them between attempts -- which is exactly what "the human retyped the code" means.
 */
internal class TestInteraction(private val board: CodeBoard) : EnteredCodePairingInteraction {
    @Volatile
    var publishAccepted: Boolean = true

    @Volatile
    var cancelled: Boolean = false

    @Volatile
    var approval: SecureApprovalDecision? = SecureApprovalDecision.APPROVE

    /** Runs on the exchange thread immediately after the code is displayed. */
    @Volatile
    var onPublish: (() -> Unit)? = null

    /** Which code the human types for a given attempt; null means they closed the dialog. */
    @Volatile
    var enteredCodeFor: (ordinal: Int, published: String) -> String? = { _, published -> published }

    val enteredOrdinals: MutableList<Int> = CopyOnWriteArrayList()

    val approvalContexts: MutableList<IntendedPeerContext> = CopyOnWriteArrayList()

    override fun publishGeneratedCode(code: EnteredPairingCode, expiresAtElapsedMillis: Long): Boolean {
        if (!publishAccepted) return false
        board.publish(plainCode(code.displayText()))
        onPublish?.invoke()
        return true
    }

    override fun requestEnteredCode(attemptOrdinal: Int, expiresAtElapsedMillis: Long): EnteredPairingCode? {
        enteredOrdinals += attemptOrdinal
        val published = board.await(20_000L) ?: return null
        val typed = enteredCodeFor.invoke(attemptOrdinal, published) ?: return null
        return EnteredPairingCode.parse(typed.toCharArray())
    }

    override fun requestIntendedPeerApproval(
        context: IntendedPeerContext,
        expiresAtElapsedMillis: Long,
    ): SecureApprovalDecision? {
        approvalContexts += context
        return approval
    }

    override fun isCancelled(): Boolean = cancelled
}

/**
 * One direction of a socket, in memory.
 *
 * Blocking, bounded by the budget it is given, and deliberately strict about the three answers a
 * read can produce. It reports EndOfStream only when the peer has actually closed, because that is
 * the distinction the exchange's failure reasons depend on.
 */
internal class MemoryExchangeIo(
    private val inbox: LinkedBlockingQueue<Any>,
    private val peerInbox: LinkedBlockingQueue<Any>,
) : PairingExchangeIo {
    private val closeCalls = AtomicInteger(0)

    @Volatile
    private var closed: Boolean = false

    val closes: Int get() = closeCalls.get()

    val writtenLabels: MutableList<String> = CopyOnWriteArrayList()

    val readLabels: MutableList<String> = CopyOnWriteArrayList()

    override fun readFrame(label: String, budgetMillis: Long): PairingFrameRead {
        if (closed) return PairingFrameRead.EndOfStream
        if (budgetMillis <= 0L) return PairingFrameRead.TimedOut
        readLabels += label
        val item = inbox.poll(budgetMillis, TimeUnit.MILLISECONDS) ?: return PairingFrameRead.TimedOut
        if (item === END_OF_STREAM) return PairingFrameRead.EndOfStream
        return PairingFrameRead.Frame((item as ByteArray).copyOf())
    }

    override fun writeFrame(label: String, frame: ByteArray, budgetMillis: Long): Boolean {
        if (closed) return false
        writtenLabels += label
        peerInbox.put(frame.copyOf())
        return true
    }

    /**
     * Closes this side and lets the peer see the end of the stream, the way a real close does.
     *
     * Without the end-of-stream marker a closed peer would look like a peer that went quiet, and the
     * exchange would report a deadline where the connection was in fact gone.
     */
    override fun close() {
        closeCalls.incrementAndGet()
        if (closed) return
        closed = true
        peerInbox.put(END_OF_STREAM)
    }

    /** Ends the stream the peer reads, the way a reset connection would. */
    fun closePeerDirection() {
        peerInbox.put(END_OF_STREAM)
    }

    companion object {
        private val END_OF_STREAM: Any = Any()
    }
}

/**
 * Both sides of one connection plus everything they must agree on before the first byte.
 *
 * The offers and digests are fixed; the exchange's own inputs are injected so a test can vary the
 * clock, the entropy and the budgets without rebuilding a peer by hand.
 */
internal class ExchangeFixture {
    val clock = TestClock()
    val secureSessionId: SessionId = SessionId("0123456789abcdef0123456789abcdef")
    val controlText: String = "ctl-session"
    val controlSessionId: SessionId = SessionId("control-00000000000000000000001")
    val transcriptDigest: ByteArray = ByteArray(32) { 0x66 }
    val channelBinding: ByteArray = ByteArray(32) { 0x55 }
    val board = CodeBoard()

    private val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
    private val responderPeer = PeerInstanceId("00000000000000000000000000000002")
    val initiatorOffer: PairingOfferV2 = offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer)
    val responderOffer: PairingOfferV2 = offer(SecurePeerRole.RESPONDER, responderPeer, initiatorPeer)

    private val toInitiator = LinkedBlockingQueue<Any>()
    private val toResponder = LinkedBlockingQueue<Any>()
    val initiatorIo = MemoryExchangeIo(toInitiator, toResponder)
    val responderIo = MemoryExchangeIo(toResponder, toInitiator)

    fun initiator(
        interaction: TestInteraction,
        budgets: PairingExchangeBudgets = GENEROUS,
        entropy: SecureEntropy = CountingEntropy(0x21),
    ): PairingAttemptExchange = exchange(SecurePeerRole.INITIATOR, interaction, budgets, entropy)

    fun responder(
        interaction: TestInteraction,
        budgets: PairingExchangeBudgets = GENEROUS,
        entropy: SecureEntropy = CountingEntropy(0x31),
    ): PairingAttemptExchange = exchange(SecurePeerRole.RESPONDER, interaction, budgets, entropy)

    private fun exchange(
        role: SecurePeerRole,
        interaction: TestInteraction,
        budgets: PairingExchangeBudgets,
        entropy: SecureEntropy,
    ): PairingAttemptExchange = PairingAttemptExchange(
        role = role,
        io = if (role == SecurePeerRole.INITIATOR) initiatorIo else responderIo,
        interaction = interaction,
        clock = clock,
        entropy = entropy,
        secureSessionId = secureSessionId,
        controlSessionId = controlText,
        transcriptDigestInput = transcriptDigest,
        channelBindingInput = channelBinding,
        initiatorOffer = initiatorOffer,
        responderOffer = responderOffer,
        budgets = budgets,
    )

    /**
     * The binding context both peers must derive identically.
     *
     * The scripted peer builds its session from the attempt-start record the same way the exchange
     * does, rather than being handed a prepared one: if the two derivations disagreed, the honest
     * cases would fail and this harness would be worthless.
     */
    fun bindingInputs(record: PakeAttemptStart): JpakeBindingInputs = JpakeBindingInputs(
        channelBinding = channelBinding,
        transcriptDigest = transcriptDigest,
        codeInstanceId = record.codeInstanceBytes(),
        attemptId = record.attemptId,
        attemptOrdinal = record.attemptOrdinal,
        attemptStartDigest = record.digest(),
        formatVersion = 1,
        suite = PairingAuthenticationPolicy.REQUIRED_SUITE,
        initiatorOffer = initiatorOffer,
        responderOffer = responderOffer,
    )

    private fun offer(
        role: SecurePeerRole,
        local: PeerInstanceId,
        remote: PeerInstanceId,
    ): PairingOfferV2 = PairingOfferV2(
        secureSessionId = secureSessionId,
        controlSessionId = controlSessionId,
        role = role,
        localPeerInstanceId = local,
        remotePeerInstanceId = remote,
        protocolMinimumVersion = 1,
        protocolMaximumVersion = 2,
        nonce = if (role == SecurePeerRole.INITIATOR) ByteArray(32) { 0x10 } else ByteArray(32) { 0x20 },
        certificateFingerprint =
        if (role == SecurePeerRole.INITIATOR) ByteArray(32) { 0x30 } else ByteArray(32) { 0x40 },
        transport = TransportKind.LAN,
        securitySuiteWireId = 1,
        tlsProtocol = "TLSv1.3",
        tlsCipherSuite = "TLS_AES_128_GCM_SHA256",
        maxRecordPlaintextBytes = 4_096L,
        maxRecordsPerDirection = 4_096L,
        maxBytesPerDirection = 16_777_216L,
        sessionLifetimeMillis = 300_000L,
        offeredFeaturesMask = 9,
        offeredMaxChunkSizeBytes = 0L,
        offeredResumeSupported = false,
        offeredEncryption = EncryptionCapability.TLS_1_3,
        offeredAuthentication = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
        appVersion = "1.0.0",
        extensions = emptyList(),
    )

    companion object {
        /**
         * Budgets for tests whose frames arrive immediately.
         *
         * Large enough that a loaded CI machine never turns a slow group exponentiation into a
         * timeout, and irrelevant to how long the test takes: a read returns as soon as the frame
         * is there.
         */
        val GENEROUS: PairingExchangeBudgets = PairingExchangeBudgets(
            peerResponseMillis = 20_000L,
            roundMillis = 20_000L,
            codeLifetimeMillis = 120_000L,
            approvalMillis = 20_000L,
        )

        /** Budgets for tests that deliberately wait for something that never arrives. */
        val SHORT: PairingExchangeBudgets = PairingExchangeBudgets(
            peerResponseMillis = 1_500L,
            roundMillis = 800L,
            codeLifetimeMillis = 120_000L,
            approvalMillis = 1_000L,
        )
    }
}

/** Runs both sides on their own threads and fails the test if either throws or stalls. */
internal fun runPair(
    initiator: PairingAttemptExchange,
    responder: PairingAttemptExchange,
    timeoutMillis: Long = 120_000L,
): Pair<PairingExchangeResult, PairingExchangeResult> {
    val pool = Executors.newFixedThreadPool(2)
    try {
        val left = pool.submit(Callable { initiator.run() })
        val right = pool.submit(Callable { responder.run() })
        val first = left.get(timeoutMillis, TimeUnit.MILLISECONDS)
        val second = right.get(timeoutMillis, TimeUnit.MILLISECONDS)
        return first to second
    } finally {
        pool.shutdownNow()
    }
}

/** Runs one scripted peer on its own thread alongside one exchange. */
internal fun runAgainstScripted(
    exchange: PairingAttemptExchange,
    scripted: () -> Unit,
    timeoutMillis: Long = 120_000L,
): PairingExchangeResult {
    val pool = Executors.newFixedThreadPool(2)
    try {
        val script = pool.submit(Callable { scripted() })
        val result = pool.submit(Callable { exchange.run() })
        val value = result.get(timeoutMillis, TimeUnit.MILLISECONDS)
        // The script is allowed to end early; a failure there is a harness bug, not a product one,
        // and the timeout makes it visible rather than silent.
        script.get(timeoutMillis, TimeUnit.MILLISECONDS)
        return value
    } finally {
        pool.shutdownNow()
    }
}

internal fun confirmed(result: PairingExchangeResult): PairingExchangeResult.Confirmed {
    if (result is PairingExchangeResult.Confirmed) return result
    throw AssertionError("expected a confirmed result, got $result")
}

internal fun failed(result: PairingExchangeResult): PairingExchangeResult.Failed {
    if (result is PairingExchangeResult.Failed) return result
    throw AssertionError("expected a failed result, got $result")
}

// --- scripted peers -------------------------------------------------------------------------

/** What the scripted responder does instead of following the protocol at one point. */
internal sealed interface ScriptedBehaviour {
    /** Every round and the approval, exactly as the protocol says. */
    data object Honest : ScriptedBehaviour

    /** Sends a verdict where round [round] belongs, then reads the answer and closes. */
    data class FailAtRound(
        val round: Int,
        val verdict: PakeAttemptVerdict = PakeAttemptVerdict.RETRY,
    ) : ScriptedBehaviour

    /** Sends the same verdict twice, so the second is a duplicate in the peer's stream. */
    data class DuplicateFailureAtRound(val round: Int) : ScriptedBehaviour

    /** Reaches the approval phase and then reflects the peer's own record back at it. */
    data object EchoApproval : ScriptedBehaviour

    /** Sends nothing where round [round] belongs, so the peer's deadline is the only outcome. */
    data class SilentAtRound(val round: Int) : ScriptedBehaviour

    /** Closes the connection where round [round] belongs. */
    data class CloseAtRound(val round: Int) : ScriptedBehaviour

    /**
     * Fails attempt one at round two, then re-sends attempt one's round-one frame before attempt
     * two's. The peer must discard the stale frame by identity and accept the fresh one.
     */
    data object StaleThenHonestOnRetry : ScriptedBehaviour
}

/**
 * A responder that performs real rounds with real frames and deviates only where told.
 *
 * It exists because the required cases -- a failure record where each round belongs, a stale frame
 * after a retry -- cannot be produced by an honest peer, and hand-built frames could not get past
 * round one at all.
 */
internal class ScriptedResponder(
    private val io: PairingExchangeIo,
    private val fixture: ExchangeFixture,
    private val behaviour: ScriptedBehaviour,
) {
    /**
     * One ledger for the whole run, exactly as a peer has one per displayed code.
     *
     * A fresh ledger per attempt would refuse the second attempt's ordinal, because a ledger's next
     * ordinal is its charge count plus one and a never-charged ledger always expects one. The
     * scripted peer therefore charges the attempts it abandons, the same way the exchange does.
     */
    private var ledger: PakeAttemptLedger? = null

    fun run() {
        when (behaviour) {
            ScriptedBehaviour.StaleThenHonestOnRetry -> runStaleThenHonest()
            else -> runOnce(behaviour)
        }
    }

    private fun runOnce(action: ScriptedBehaviour) {
        val record = readAttemptStart() ?: return
        val session = openSession(record) ?: return
        if (!round(session, 1, action)) return
        if (!round(session, 2, action)) return
        if (!round(session, 3, action)) return
        when (action) {
            is ScriptedBehaviour.Honest -> approve(record)
            ScriptedBehaviour.EchoApproval -> echoApproval()
            else -> io.close()
        }
    }

    private fun runStaleThenHonest() {
        // Attempt one: honest round one, remembered, then a retry verdict instead of round two.
        val first = readAttemptStart() ?: return
        val firstSession = openSession(first) ?: return
        val firstRoundOne = firstSession.createRoundOne()
        if (!send(firstRoundOne)) return
        val peerRoundOne = readFrame("round-one") ?: return
        if (firstSession.acceptRoundOne(peerRoundOne) !is ModpJpakeSession.Step.Accepted) return
        val verdict = PakeAttemptFailureCodec.encode(PakeAttemptFailure(first.attemptId, PakeAttemptVerdict.RETRY))
        if (!send(verdict)) return
        // The peer has already sent its own round two, so two frames may arrive before the next
        // attempt-start: that round, then the answer to the verdict. Both are skipped.
        firstSession.attempt.charge()

        // Attempt two: the stale frame first, then a genuine round one for the new attempt.
        val second = readAttemptStart(skip = 3) ?: return
        val secondSession = openSession(second) ?: return
        if (!send(firstRoundOne)) return
        if (!round(secondSession, 1, ScriptedBehaviour.Honest, alreadyCreated = secondSession.createRoundOne())) {
            return
        }
        if (!round(secondSession, 2, ScriptedBehaviour.Honest)) return
        if (!round(secondSession, 3, ScriptedBehaviour.Honest)) return
        approve(second)
    }

    /**
     * One round, or the deviation that replaces it.
     *
     * The frame is created before the deviation is applied because that is the order the exchange
     * uses on both sides: create, then send or fail. A scripted peer that failed before creating
     * would not resemble the state it is modelling.
     */
    private fun round(
        session: ModpJpakeSession,
        number: Int,
        action: ScriptedBehaviour,
        alreadyCreated: ByteArray? = null,
    ): Boolean {
        val created = alreadyCreated ?: when (number) {
            1 -> session.createRoundOne()
            2 -> session.createRoundTwo()
            else -> session.createConfirmation()
        }
        when (action) {
            is ScriptedBehaviour.FailAtRound -> if (action.round == number) {
                return failAndClose(session, action.verdict, duplicate = false)
            }
            is ScriptedBehaviour.DuplicateFailureAtRound -> if (action.round == number) {
                return failAndClose(session, PakeAttemptVerdict.RETRY, duplicate = true)
            }
            is ScriptedBehaviour.SilentAtRound -> if (action.round == number) {
                Thread.sleep(1_500L)
                io.close()
                return false
            }
            is ScriptedBehaviour.CloseAtRound -> if (action.round == number) {
                io.close()
                return false
            }
            else -> Unit
        }
        if (!send(created)) return false
        val incoming = readFrame(roundLabel(number)) ?: return false
        val accepted = when (number) {
            1 -> session.acceptRoundOne(incoming)
            2 -> session.acceptRoundTwo(incoming)
            else -> session.acceptConfirmation(incoming)
        }
        return accepted is ModpJpakeSession.Step.Accepted
    }

    private fun failAndClose(
        session: ModpJpakeSession,
        verdict: PakeAttemptVerdict,
        duplicate: Boolean,
    ): Boolean {
        val frame = PakeAttemptFailureCodec.encode(PakeAttemptFailure(session.attempt.id, verdict))
        if (!send(frame)) return false
        if (duplicate && !send(frame)) return false
        session.attempt.charge()
        // The peer answers with its own verdict; reading it keeps the script honest about where the
        // stream ended rather than leaving bytes for a later attempt to trip over.
        readFrame("failure-answer")
        if (!duplicate) io.close()
        return false
    }

    /**
     * Reflects the peer's own approval record back.
     *
     * The record names the sender's role, so an echo fails the binding check instead of counting as
     * the remote decision: this is the cheapest way to test that the role is part of the binding.
     */
    private fun echoApproval() {
        val incoming = readFrame("approval") ?: return
        send(incoming)
        io.close()
    }

    private fun approve(record: PakeAttemptStart) {
        val decision = PakeApproval(
            decision = PakeApprovalDecision.APPROVE,
            suite = PairingAuthenticationPolicy.REQUIRED_SUITE,
            profile = JpakeCurveProfile.MODP_3072,
            role = SecurePeerRole.RESPONDER,
            secureSessionId = fixture.secureSessionId,
            controlSessionId = fixture.controlText,
            attemptId = record.attemptId,
            attemptOrdinal = record.attemptOrdinal,
            codeInstanceId = record.codeInstanceBytes(),
            transcriptDigest = fixture.transcriptDigest,
        )
        if (!send(PakeApprovalCodec.encode(decision))) return
        val answer = readFrame("approval") ?: return
        val decoded = PakeApprovalCodec.decode(answer)
        if (decoded is PakeApprovalResult.Success) decoded.record.clearSensitive()
        io.close()
    }

    private fun openSession(record: PakeAttemptStart): ModpJpakeSession? {
        val codeText = fixture.board.await(20_000L) ?: return null
        val code = EnteredPairingCode.parse(codeText.toCharArray()) ?: return null
        val instances = ledger ?: PakeAttemptLedger(record.codeInstanceBytes()).also { ledger = it }
        val attempt: PakeAttempt = instances.adoptAttempt(record.attemptId, record.attemptOrdinal) ?: return null
        val inputs = fixture.bindingInputs(record)
        return try {
            val context = JpakeBinding.authenticationContext(inputs)
            val ids = try {
                JpakeBinding.participantIds(context)
            } finally {
                context.fill(0)
            }
            val chars = code.toPasswordChars()
            try {
                ModpJpakeSession(
                    SecurePeerRole.RESPONDER,
                    attempt,
                    ids,
                    fixture.secureSessionId,
                    fixture.controlText,
                    chars,
                )
            } finally {
                chars.fill('\u0000')
            }
        } finally {
            inputs.clearSensitive()
            code.clearSensitive()
        }
    }

    /**
     * Reads the next attempt-start record, skipping up to [skip] frames that are not one.
     *
     * Skipping is a property of this script, not of the exchange: after a mid-round failure a peer's
     * stream legitimately holds the round it had already sent plus the answer to the verdict.
     */
    private fun readAttemptStart(skip: Int = 0): PakeAttemptStart? {
        var remaining = skip
        while (true) {
            val frame = readFrame("attempt-start") ?: return null
            val decoded = PakeAttemptStartCodec.decode(frame)
            if (decoded is PakeAttemptStartResult.Success) return decoded.record
            if (remaining <= 0) return null
            remaining -= 1
        }
    }

    private fun readFrame(label: String): ByteArray? = when (val read = io.readFrame(label, 5_000L)) {
        is PairingFrameRead.Frame -> read.bytes
        PairingFrameRead.TimedOut -> null
        PairingFrameRead.EndOfStream -> null
        PairingFrameRead.Invalid -> null
    }

    private fun send(frame: ByteArray): Boolean = io.writeFrame("scripted", frame, 5_000L)

    private fun roundLabel(number: Int): String = when (number) {
        1 -> "round-one"
        2 -> "round-two"
        else -> "round-three"
    }
}

/** A responder-side test peer that only knows how to send attempt-start records. */
internal class ScriptedInitiator(
    private val io: PairingExchangeIo,
    private val fixture: ExchangeFixture,
) {
    fun send(record: PakeAttemptStart): Boolean =
        io.writeFrame("attempt-start", PakeAttemptStartCodec.encode(record), 5_000L)

    fun attemptStart(
        attemptId: PakeAttemptId,
        ordinal: Int,
        role: SecurePeerRole = SecurePeerRole.INITIATOR,
    ): PakeAttemptStart = PakeAttemptStart(
        suite = PairingAuthenticationPolicy.REQUIRED_SUITE,
        profile = JpakeCurveProfile.MODP_3072,
        role = role,
        secureSessionId = fixture.secureSessionId,
        controlSessionId = fixture.controlText,
        attemptId = attemptId,
        attemptOrdinal = ordinal,
        codeInstanceId = ByteArray(16) { 0x33 },
        transcriptDigest = fixture.transcriptDigest,
    )

    fun failure(
        attemptId: PakeAttemptId,
        verdict: PakeAttemptVerdict = PakeAttemptVerdict.RETRY,
    ): Boolean = io.writeFrame(
        "attempt-failure",
        PakeAttemptFailureCodec.encode(PakeAttemptFailure(attemptId, verdict)),
        5_000L,
    )

    fun readVerdict(): PakeAttemptVerdict? {
        val frame = when (val read = io.readFrame("failure-answer", 5_000L)) {
            is PairingFrameRead.Frame -> read.bytes
            else -> return null
        }
        return when (val decoded = PakeAttemptFailureCodec.decode(frame)) {
            is PakeAttemptFailureResult.Success -> decoded.record.verdict
            is PakeAttemptFailureResult.Rejected -> null
        }
    }

    fun attemptId(seed: Int): PakeAttemptId = requireNotNull(PakeAttemptId.fromBytes(ByteArray(16) { seed.toByte() }))
}
