/*
 * The loopback harness every API 23 live test shares: two real coordinators, one real TLS session.
 *
 * Both endpoints run the production `SecureLanPairingCoordinator.run()`. The only test-only objects
 * are the human (an `EnteredCodePairingInteraction` script) and, in the hostile tests, a
 * `PairingCryptoFactory` that alters exactly one field of the MPS2 offer it encodes. Everything
 * else -- Conscrypt, TLS 1.3, the framing, the PAKE rounds, the record layer, key confirmation and
 * the authority -- is the shipped code path.
 *
 * WHY THE CODE IS CARRIED THROUGH A BOARD
 *   The initiator displays the generated code; the responder types it. In a test both halves run in
 *   one process, so the board stands in for the human carrying the code across. It is deliberately
 *   a plain single-value handoff: the test cannot pass a code the initiator never displayed unless a
 *   scenario says so explicitly, and those scenarios are the wrong-code and exhaustion cases.
 */

package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import app.morsecode.transport.lan.security.pairing.EnteredCodePairingInteraction
import app.morsecode.transport.lan.security.pairing.IntendedPeerContext
import app.morsecode.transport.lan.security.pairing.PairingExchangeBudgets
import app.morsecode.transport.lan.security.pairing.RecordBudgets
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Monotonic time on the emulator: real, advancing, and never wall-clock. */
internal fun api23Clock(): MonotonicClock = MonotonicClock { System.nanoTime() / 1_000_000L }

/**
 * Deliberately small budgets for tests that must not wait out a human-scale deadline.
 *
 * The values are not zero: the exchange type refuses those, and a zero budget would make every
 * refusal a tautology rather than a demonstration that a real deadline ended the attempt.
 */
internal fun api23Budgets(): PairingExchangeBudgets = PairingExchangeBudgets(
    peerResponseMillis = 10_000L,
    roundMillis = 10_000L,
    // Long enough that three real PAKE attempts against one code fit inside it on an emulator. A
    // code that expired mid-suite would turn "the budget is spent" into "the clock ran out", and
    // those two refusals mean different things.
    codeLifetimeMillis = 60_000L,
    approvalMillis = 6_000L,
)

internal fun api23RecordBudgets(): RecordBudgets = RecordBudgets(
    writeMillis = 8_000L,
    readMillis = 8_000L,
)

/** The displayed code, carried across the connection by a human who is a test. */
internal class Api23CodeBoard {
    @Volatile
    private var text: String? = null

    @Volatile
    var shownCount: Int = 0
        private set

    fun publish(value: String) {
        text = value
        shownCount += 1
    }

    fun peek(): String? = text

    fun await(timeoutMillis: Long): String? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            text?.let { return it }
            Thread.sleep(2L)
        }
        return text
    }
}

/**
 * The scripted human.
 *
 * [enteredCodes] is consulted once per attempt, in order; a null entry means the human cancelled
 * rather than typed. [approval] is the answer to the intended-peer question, and null means the
 * prompt was never answered. Nothing here answers on its own: every value comes from the scenario.
 */
internal class Api23Human(
    private val board: Api23CodeBoard,
    private val initiator: Boolean,
    private val enteredCodes: List<() -> CharArray?>,
    private val approval: SecureApprovalDecision?,
    private val cancelSignal: () -> Boolean = { false },
) : EnteredCodePairingInteraction {
    @Volatile
    var promptsShown: Int = 0
        private set

    @Volatile
    var enteredCodeRequests: Int = 0
        private set

    @Volatile
    var approvalPrompts: Int = 0
        private set

    override fun publishGeneratedCode(code: EnteredPairingCode, expiresAtElapsedMillis: Long): Boolean {
        promptsShown += 1
        // The separator is a display convenience. The board carries what a human would read and
        // retype, which is the symbols alone.
        board.publish(code.displayText().replace("-", ""))
        return !cancelSignal()
    }

    override fun requestEnteredCode(attemptOrdinal: Int, expiresAtElapsedMillis: Long): EnteredPairingCode? {
        promptsShown += 1
        enteredCodeRequests += 1
        // The script is a list of suppliers, not values: the entry for the second attempt is
        // evaluated when that attempt happens, which is the only way it can be the code the
        // initiator displayed *after* the first one was refused.
        val index = attemptOrdinal - 1
        val supplier = enteredCodes.getOrNull(index) ?: enteredCodes.lastOrNull() ?: return null
        val symbols = supplier() ?: return null
        return EnteredPairingCode.parse(symbols)
    }

    override fun requestIntendedPeerApproval(
        context: IntendedPeerContext,
        expiresAtElapsedMillis: Long,
    ): SecureApprovalDecision? {
        promptsShown += 1
        approvalPrompts += 1
        return approval
    }

    override fun isCancelled(): Boolean = cancelSignal()
}

/** One live pairing: both results, both human scripts, and the sockets the test owns. */
internal class Api23Pairing(
    val initiatorResult: SecurePairingRunResult,
    val responderResult: SecurePairingRunResult,
    val initiatorSocket: Socket,
    val responderSocket: Socket?,
    val initiatorHuman: Api23Human,
    val responderHuman: Api23Human,
    val board: Api23CodeBoard,
    val executor: ExecutorService,
    val responderFuture: Future<SecurePairingRunResult>,
    /** True when the responder had not finished inside the harness's own wait. */
    val responderTimedOut: Boolean = false,
) {
    fun initiatorFailureCode(): app.morsecode.core.transfer.session.SessionFailureCode? =
        (initiatorResult as? SecurePairingRunResult.Failed)?.result?.failure?.code

    fun responderFailureCode(): app.morsecode.core.transfer.session.SessionFailureCode? =
        (responderResult as? SecurePairingRunResult.Failed)?.result?.failure?.code

    fun close() {
        (initiatorResult as? SecurePairingRunResult.Authenticated)?.authority?.close()
        (responderResult as? SecurePairingRunResult.Authenticated)?.authority?.close()
        runCatching { initiatorSocket.close() }
        runCatching { responderSocket?.close() }
        executor.shutdownNow()
    }
}

/**
 * A redacted one-line account of both endpoints, for an assertion message.
 *
 * Typed failure codes and counters only: no identifier, address, certificate, proof, key, frame
 * content or raw exception. A failed device run has no other evidence to offer -- only the plain
 * run log survives the emulator -- so the message has to carry the diagnosis itself.
 */
internal fun Api23Pairing.describeOutcome(): String =
    "initiator=${describeSide(initiatorResult, initiatorHuman)} " +
        "responder=" +
        (if (responderTimedOut) "timedOut" else describeSide(responderResult, responderHuman)) +
        " boardShown=${board.shownCount}"

private fun describeSide(result: SecurePairingRunResult, human: Api23Human): String {
    val outcome = when (result) {
        is SecurePairingRunResult.Authenticated -> "authenticated"
        is SecurePairingRunResult.Failed -> "failed(${result.result.failure.code.id})"
    }
    return "$outcome prompts=${human.promptsShown} codes=${human.enteredCodeRequests} " +
        "approvals=${human.approvalPrompts}"
}

internal fun api23Session(localId: String, remoteId: String): NegotiatedSession = NegotiatedSession(
    attemptId = SessionId("attempt-1"),
    localProfile = api23Profile(localId, "Local"),
    remoteProfile = api23Profile(remoteId, "Remote"),
    protocolVersion = 1,
    capabilities = NegotiatedCapabilities(
        features = setOf(SessionFeature.CONTROL_HANDSHAKE),
        maxChunkSizeBytes = 4_096,
        resumeSupported = false,
        encryption = EncryptionCapability.NONE,
    ),
)

internal fun api23Profile(id: String, name: String): SessionPeerProfile = SessionPeerProfile(
    peerInstanceId = PeerInstanceId(id),
    displayName = name,
    appVersion = "1.0.0",
    protocolRange = ProtocolRange.CURRENT,
    capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
)

/**
 * Runs two production coordinators against each other over loopback TLS.
 *
 * The responder runs first, on a worker; the initiator runs on the calling thread, because the
 * initiator is the side that displays the code and the board must be filled before the responder
 * can type it. A scenario that expects the initiator to stop early still gets a bounded responder
 * result: `run()` closes its own socket on every refusal, so the peer's read ends rather than
 * hanging.
 */
internal fun api23RunPairing(
    initiatorHuman: Api23Human,
    responderHuman: Api23Human,
    board: Api23CodeBoard,
    clock: MonotonicClock = api23Clock(),
    responderFactory: PairingCryptoFactory? = null,
    initiatorScope: PairingAdmissionScope? = null,
    initializerBudgetSeconds: Long = 30L,
): Api23Pairing {
    val listener = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
    }
    val executor = Executors.newSingleThreadExecutor()
    val initiatorSession = api23Session("00000000000000000000000000000001", "00000000000000000000000000000002")
    val responderSession = api23Session("00000000000000000000000000000002", "00000000000000000000000000000001")
    var responderSocket: Socket? = null
    val responderFuture = executor.submit<SecurePairingRunResult> {
        val socket = listener.accept().apply {
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        responderSocket = socket
        val coordinator = SecureLanPairingCoordinator(
            rawSocket = socket,
            controlSession = responderSession,
            role = SecurePeerRole.RESPONDER,
            clock = clock,
            random = SecureRandom(),
            interaction = responderHuman,
            exchangeBudgets = api23Budgets(),
            recordBudgets = api23RecordBudgets(),
            cryptoFactory = responderFactory ?: ConscryptPairingCryptoFactory(SecureRandom()),
        )
        coordinator.run()
    }
    val rawClient = Socket()
    rawClient.connect(listener.localSocketAddress, SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS)
    rawClient.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
    val initiatorResult = SecureLanPairingCoordinator(
        rawSocket = rawClient,
        controlSession = initiatorSession,
        role = SecurePeerRole.INITIATOR,
        clock = clock,
        random = SecureRandom(),
        interaction = initiatorHuman,
        attemptLimiter = initiatorScope,
        exchangeBudgets = api23Budgets(),
        recordBudgets = api23RecordBudgets(),
    ).run()
    var responderTimedOut = false
    val responderResult = try {
        responderFuture.get(initializerBudgetSeconds, TimeUnit.SECONDS)
    } catch (_: Exception) {
        // The harness's own wait, not the pairing's: recorded as such so a failing assertion does
        // not read this placeholder as the responder's answer.
        responderTimedOut = true
        SecurePairingRunResult.Failed(
            app.morsecode.core.transfer.session.SecurePairingResult.Failed(
                app.morsecode.core.transfer.session.SessionFailure(
                    app.morsecode.core.transfer.session.SessionFailureCode.INTERNAL_TRANSPORT_FAILURE,
                ),
            ),
        )
    }
    runCatching { listener.close() }
    return Api23Pairing(
        initiatorResult = initiatorResult,
        responderResult = responderResult,
        initiatorSocket = rawClient,
        responderSocket = responderSocket,
        initiatorHuman = initiatorHuman,
        responderHuman = responderHuman,
        board = board,
        executor = executor,
        responderFuture = responderFuture,
        responderTimedOut = responderTimedOut,
    )
}
