/*
 * Harness for the composed PAKE path: a JVM stand-in for the TLS channel, real AES-GCM records, and
 * a pair of coordinators wired to the same in-memory transports the exchange tests use.
 *
 * WHAT IS REAL AND WHAT IS NOT
 *   Real: the PAKE library and its arithmetic, the serialized MPS4/MPS3/MPSF/MPA1 frames, the record
 *   codec, the AES-GCM record layer, the key-confirmation records, the reducer and the authority.
 *   Not real: the TLS exporter. Conscrypt only exists on a device, so [DeterministicPairingChannel]
 *   derives its exporter output from HMAC-SHA256 over the label and context instead. That is enough
 *   to prove the composition is wired correctly -- two sides with the same inputs agree, and two
 *   sides with different inputs do not -- and it proves nothing about TLS. The real Conscrypt path is
 *   covered by `PakeSessionApi23Test` in androidTest.
 *
 *   The double cannot express "the PAKE succeeded": it only turns a label into bytes.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordAead
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A TLS channel stand-in with a deterministic exporter.
 *
 * The exporter output is `HMAC-SHA256(secret, label || context)`, truncated to the requested length.
 * [secret] is per-side in production terms, but both peers here share it, which is exactly what a
 * completed TLS handshake gives them; a test that wants a key mismatch gives one side a different
 * secret.
 */
internal class DeterministicPairingChannel(
    private val secret: ByteArray,
    override val protocol: String = "TLSv1.3",
    override val cipherSuite: String = "TLS_AES_128_GCM_SHA256",
) : PairingChannel {
    private val exported = AtomicInteger(0)

    fun exportCount(): Int = exported.get()

    override fun export(label: String, context: ByteArray, length: Int): ByteArray {
        require(length in 1..64)
        exported.incrementAndGet()
        val mac = MessageDigest.getInstance("SHA-256")
        mac.update(secret)
        mac.update(label.toByteArray(Charsets.US_ASCII))
        mac.update(context)
        return mac.digest().copyOf(length)
    }

    override fun createAead(exportedKey: ByteArray): SecureRecordAead = JcaAesGcm(exportedKey)

    override fun close() = Unit

    override fun toString(): String = "DeterministicPairingChannel(ids=[redacted])"
}

/** AES-GCM through the JDK provider. The same primitive the production adapter uses. */
internal class JcaAesGcm(key: ByteArray) : SecureRecordAead {
    private val keyBytes = key.copyOf()

    override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    override fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }

    override fun close() {
        keyBytes.fill(0)
    }
}

/**
 * One direction of a record stream, in memory.
 *
 * Bounded by the budget it is given and strict about the three answers a read can produce, for the
 * same reasons [MemoryExchangeIo] is: a timeout and a closed connection lead to different failures,
 * and conflating them would hide a protocol violation behind a deadline.
 */
internal class MemoryRecordIo(
    private val inbox: LinkedBlockingQueue<Any>,
    private val peerInbox: LinkedBlockingQueue<Any>,
) : PairingRecordIo {
    private val closeCalls = AtomicInteger(0)

    @Volatile
    private var closed = false

    val closes: Int get() = closeCalls.get()

    val framesWritten: MutableList<ByteArray> = java.util.concurrent.CopyOnWriteArrayList()

    /** When set, the next frame is dropped instead of delivered: a peer that never confirms. */
    @Volatile
    var dropNextFrame: Boolean = false

    /** When set, every frame is delivered twice: a peer that confirms twice. */
    @Volatile
    var duplicateNextFrame: Boolean = false

    /**
     * When set, the next frame is delivered with one bit flipped.
     *
     * This is what a receiver sees when the two sides derived different record keys: the bytes
     * arrive, the header parses, and the AEAD refuses them. Corrupting one frame models the symptom
     * without pretending to model the cause.
     */
    @Volatile
    var corruptNextFrame: Boolean = false

    override fun readRecordFrame(budgetMillis: Long): PairingRecordRead {
        if (budgetMillis <= 0L) return PairingRecordRead.TimedOut
        val item = inbox.poll(budgetMillis, TimeUnit.MILLISECONDS) ?: return PairingRecordRead.TimedOut
        if (item === END) return PairingRecordRead.EndOfStream
        return PairingRecordRead.Frame((item as ByteArray).copyOf())
    }

    override fun writeRecordFrame(frame: ByteArray, budgetMillis: Long): Boolean {
        if (closed) return false
        framesWritten += frame.copyOf()
        if (dropNextFrame) {
            dropNextFrame = false
            return true
        }
        val delivered = frame.copyOf()
        if (corruptNextFrame) {
            corruptNextFrame = false
            delivered[delivered.size - 1] = (delivered.last().toInt() xor 1).toByte()
        }
        peerInbox.put(delivered)
        if (duplicateNextFrame) {
            duplicateNextFrame = false
            peerInbox.put(frame.copyOf())
        }
        return true
    }

    override fun close() {
        closeCalls.incrementAndGet()
        if (closed) return
        closed = true
        peerInbox.put(END)
    }

    companion object {
        private val END: Any = Any()
    }
}

/** Both coordinators of one connection, plus everything they must agree on first. */
internal class PakeSessionFixture(
    val clock: TestClock = TestClock(),
    val secureSessionId: SessionId = SessionId("0123456789abcdef0123456789abcdef"),
    val controlText: String = "ctl-session",
    private val transcript: ByteArray = ByteArray(32) { 0x66 },
) {
    private val controlId = SessionId("control-00000000000000000000001")
    private val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
    private val responderPeer = PeerInstanceId("00000000000000000000000000000002")
    val board = CodeBoard()

    private val exchangeToInitiator = LinkedBlockingQueue<Any>()
    private val exchangeToResponder = LinkedBlockingQueue<Any>()
    /** Alias used by tests that assert on the exchange transport without naming its role. */
    val initiatorIo: MemoryExchangeIo get() = initiatorExchangeIo

    val initiatorExchangeIo = MemoryExchangeIo(exchangeToInitiator, exchangeToResponder)
    val responderExchangeIo = MemoryExchangeIo(exchangeToResponder, exchangeToInitiator)

    private val recordToInitiator = LinkedBlockingQueue<Any>()
    private val recordToResponder = LinkedBlockingQueue<Any>()
    val initiatorRecordIo = MemoryRecordIo(recordToInitiator, recordToResponder)
    val responderRecordIo = MemoryRecordIo(recordToResponder, recordToInitiator)

    val initiatorOffer: PairingOfferV2 = offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer)
    val responderOffer: PairingOfferV2 = offer(SecurePeerRole.RESPONDER, responderPeer, initiatorPeer)

    fun initiatorInteraction(): TestInteraction = TestInteraction(board)

    fun responderInteraction(): TestInteraction = TestInteraction(board)

    fun initiator(
        interaction: TestInteraction,
        channel: PairingChannel = channelFor(0x0A),
        recordIo: PairingRecordIo = initiatorRecordIo,
        exchangeIo: PairingExchangeIo = initiatorExchangeIo,
    ): PakeSessionCoordinator = coordinator(
        role = SecurePeerRole.INITIATOR,
        interaction = interaction,
        channel = channel,
        recordIo = recordIo,
        exchangeIo = exchangeIo,
    )

    fun responder(
        interaction: TestInteraction,
        channel: PairingChannel = channelFor(0x0A),
        recordIo: PairingRecordIo = responderRecordIo,
        exchangeIo: PairingExchangeIo = responderExchangeIo,
    ): PakeSessionCoordinator = coordinator(
        role = SecurePeerRole.RESPONDER,
        interaction = interaction,
        channel = channel,
        recordIo = recordIo,
        exchangeIo = exchangeIo,
    )

    private fun channelFor(seed: Int): PairingChannel = DeterministicPairingChannel(ByteArray(32) { seed.toByte() })

    private fun coordinator(
        role: SecurePeerRole,
        interaction: TestInteraction,
        channel: PairingChannel,
        recordIo: PairingRecordIo,
        exchangeIo: PairingExchangeIo,
    ): PakeSessionCoordinator = PakeSessionCoordinator(
        channel = channel,
        controlSession = controlSession(),
        role = role,
        clock = clock,
        entropy = CountingEntropy(if (role == SecurePeerRole.INITIATOR) 0x21 else 0x31),
        random = java.security.SecureRandom(),
        interaction = interaction,
        exchangeIo = exchangeIo,
        recordIo = recordIo,
        initiatorOffer = initiatorOffer,
        responderOffer = responderOffer,
        transcriptDigestInput = transcript,
        budgets = PairingExchangeBudgets(
            peerResponseMillis = 15_000L,
            roundMillis = 15_000L,
            codeLifetimeMillis = 120_000L,
            approvalMillis = 15_000L,
        ),
    )

    fun controlSession(): NegotiatedSession = NegotiatedSession(
        attemptId = controlId,
        localProfile = profile("00000000000000000000000000000001", "Local"),
        remoteProfile = profile("00000000000000000000000000000002", "Remote"),
        protocolVersion = 1,
        capabilities = NegotiatedCapabilities(
            features = setOf(SessionFeature.CONTROL_HANDSHAKE),
            maxChunkSizeBytes = 4_096,
            resumeSupported = false,
            encryption = EncryptionCapability.NONE,
        ),
    )

    private fun profile(id: String, name: String): SessionPeerProfile = SessionPeerProfile(
        peerInstanceId = PeerInstanceId(id),
        displayName = name,
        appVersion = "1.0.0",
        protocolRange = ProtocolRange(1, 1),
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    private fun offer(
        role: SecurePeerRole,
        local: PeerInstanceId,
        remote: PeerInstanceId,
        authentication: PairingAuthenticationCapability =
            PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
    ): PairingOfferV2 = PairingOfferV2(
        secureSessionId = secureSessionId,
        controlSessionId = controlId,
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
        offeredAuthentication = authentication,
        appVersion = "1.0.0",
        extensions = emptyList(),
    )
}

/** Runs two coordinators on their own threads. */
internal fun runCoordinators(
    initiator: PakeSessionCoordinator,
    responder: PakeSessionCoordinator,
    timeoutMillis: Long = 180_000L,
): Pair<PakePairingOutcome, PakePairingOutcome> {
    val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
        val left = pool.submit(java.util.concurrent.Callable { initiator.run() })
        val right = pool.submit(java.util.concurrent.Callable { responder.run() })
        return left.get(timeoutMillis, TimeUnit.MILLISECONDS) to
            right.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } finally {
        pool.shutdownNow()
    }
}

internal fun authenticated(outcome: PakePairingOutcome): PakePairingOutcome.Authenticated {
    if (outcome is PakePairingOutcome.Authenticated) return outcome
    throw AssertionError("expected an authenticated outcome, got $outcome")
}

internal fun notAuthenticated(outcome: PakePairingOutcome): PakePairingOutcome.Failed {
    if (outcome is PakePairingOutcome.Failed) return outcome
    throw AssertionError("expected a failed outcome, got $outcome")
}

/** An interaction that answers approvals however the test says, without touching the code board. */
internal fun quietInteraction(
    board: CodeBoard,
    entered: (Int, String) -> String? = { _, published -> published },
    approval: SecureApprovalDecision? = SecureApprovalDecision.APPROVE,
): TestInteraction = TestInteraction(board).apply {
    enteredCodeFor = entered
    this.approval = approval
}

/** A code the human types that is one symbol away from the published one. */
internal fun wrongEntered(ordinal: Int, published: String): String =
    if (ordinal == 1) wrongCode(published) else published

