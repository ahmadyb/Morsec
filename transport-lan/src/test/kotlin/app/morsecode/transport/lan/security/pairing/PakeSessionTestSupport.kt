/*
 * Harness for the composed path on the JVM.
 *
 * WHAT IS REAL AND WHAT IS SUBSTITUTED
 *   Real: the PAKE arithmetic and its frames, the exchange and its budgets, the record layer and
 *   AES-GCM, the key-confirmation codec, the reducer and the authority.
 *   Substituted: the TLS exporter. There is no Conscrypt on a JVM, so [DeterministicPairingChannel]
 *   derives exporter outputs with HMAC-SHA256 over the label and context. That is a stand-in for the
 *   *transport*, clearly labelled as one: it cannot assert a result, and the real exporter is
 *   exercised on a device by `PakeSessionApi23Test`.
 *
 * Both sides of one link share a secret and two crossed queues, which is what a completed handshake
 * gives two peers: the same keying material and a byte stream in each direction.
 */

package app.morsecode.transport.lan.security.pairing

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.JpakeBinding
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

/** AES-GCM through the JDK provider. Same primitive, different provider from production. */
internal class JcaAesGcmAead(key: ByteArray) : SecureRecordAead {
    private val delegate = Cipher.getInstance("AES/GCM/NoPadding")
    private val spec = SecretKeySpec(key.copyOf(), "AES")

    override fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(plaintext)
    }

    override fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }

    override fun close() = Unit
}

/**
 * A TLS stand-in: deterministic exporter, real AEADs, in-memory framings.
 *
 * [secret] plays the role of the handshake's exporter master. Two channels built over the same link
 * share it, so both derive identical keys -- which is what makes an honest run work -- and a channel
 * built over a different secret derives different ones, which is how a key mismatch is produced.
 */
internal class DeterministicPairingChannel(
    private val secret: ByteArray,
    private val exchange: PairingExchangeIo,
    private val records: PairingRecordIo,
    override val protocol: String = "TLSv1.3",
    override val cipherSuite: String = "TLS_AES_128_GCM_SHA256",
    /**
     * Mixed into the record-key labels only, never into the exchange's channel-binding label.
     *
     * That is what a key mismatch looks like without also breaking the PAKE: the transcript binds the
     * same way on both sides, so the refusal comes from the record layer and not from a PAKE tag.
     */
    private val recordKeySalt: ByteArray = ByteArray(0),
) : PairingChannel {

    @Volatile
    var closed: Boolean = false
        private set

    override fun export(label: String, context: ByteArray, length: Int): ByteArray {
        require(length in 1..64) { "exporter output is bounded" }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(secret)
        digest.update(0)
        digest.update(label.toByteArray(Charsets.US_ASCII))
        digest.update(0)
        digest.update(context)
        if (recordKeySalt.isNotEmpty() && label.startsWith("EXPORTER-MORSEC-C2S")) {
            digest.update(recordKeySalt)
        }
        if (recordKeySalt.isNotEmpty() && label.startsWith("EXPORTER-MORSEC-S2C")) {
            digest.update(recordKeySalt)
        }
        return digest.digest().copyOf(length)
    }

    override fun createAead(exportedKey: ByteArray): SecureRecordAead = JcaAesGcmAead(exportedKey)

    override fun exchangeIo(): PairingExchangeIo = exchange

    override fun recordIo(): PairingRecordIo = records

    override fun close() {
        closed = true
    }
}

/**
 * The record framing over an in-memory byte stream.
 *
 * Frames are handed over whole, exactly as the record layer produced them, so the peer's
 * `unprotect` sees the same bytes a socket would have carried. [corruptNextFrame] flips one bit,
 * which is what a receiver sees when the two sides derived different keys.
 */
internal class MemoryRecordIo(
    private val inbox: LinkedBlockingQueue<Any>,
    private val peerInbox: LinkedBlockingQueue<Any>,
) : PairingRecordIo {

    val framesWritten: MutableList<ByteArray> = java.util.concurrent.CopyOnWriteArrayList()

    /** Every frame this side actually received, in order, for replay assertions. */
    val framesDelivered: MutableList<ByteArray> = java.util.concurrent.CopyOnWriteArrayList()

    @Volatile
    var corruptNextFrame: Boolean = false

    @Volatile
    var silent: Boolean = false

    private val closeCount = AtomicInteger(0)

    @Volatile
    private var closed: Boolean = false

    override fun readFrame(sessionId: SessionId, budgetMillis: Long): PairingRecordRead {
        if (closed) return PairingRecordRead.EndOfStream
        if (budgetMillis <= 0L) return PairingRecordRead.TimedOut
        val item = inbox.poll(budgetMillis, TimeUnit.MILLISECONDS) ?: return PairingRecordRead.TimedOut
        if (item === END_OF_STREAM) return PairingRecordRead.EndOfStream
        val frame = (item as ByteArray).copyOf()
        framesDelivered += frame
        return PairingRecordRead.Frame(frame.copyOf())
    }

    override fun writeFrame(frame: ByteArray, budgetMillis: Long): Boolean {
        if (closed || silent) return !closed
        framesWritten += frame.copyOf()
        val sent = frame.copyOf()
        if (corruptNextFrame) {
            corruptNextFrame = false
            sent[sent.size - 1] = (sent[sent.size - 1].toInt() xor 0x01).toByte()
        }
        peerInbox.put(sent)
        return true
    }

    override fun close() {
        closeCount.incrementAndGet()
        if (closed) return
        closed = true
        peerInbox.put(END_OF_STREAM)
    }

    fun closeCount(): Int = closeCount.get()

    private companion object {
        private val END_OF_STREAM: Any = Any()
    }
}

/**
 * One connection: crossed exchange queues, crossed record queues, and the facts both peers agree on.
 */
internal class PakeLink(
    val secureSessionId: SessionId = SessionId("0123456789abcdef0123456789abcdef"),
    val controlSessionId: SessionId = SessionId("control-00000000000000000000001"),
    val transcriptDigest: ByteArray = ByteArray(32) { 0x66 },
    secret: ByteArray = ByteArray(32) { 0x0A },
) {
    val clock = TestClock()
    val board = CodeBoard()

    private val exchangeToInitiator = LinkedBlockingQueue<Any>()
    private val exchangeToResponder = LinkedBlockingQueue<Any>()
    private val recordToInitiator = LinkedBlockingQueue<Any>()
    private val recordToResponder = LinkedBlockingQueue<Any>()

    val initiatorExchangeIo = MemoryExchangeIo(exchangeToInitiator, exchangeToResponder)
    val responderExchangeIo = MemoryExchangeIo(exchangeToResponder, exchangeToInitiator)
    val initiatorRecordIo = MemoryRecordIo(recordToInitiator, recordToResponder)
    val responderRecordIo = MemoryRecordIo(recordToResponder, recordToInitiator)

    val initiatorOffer: PairingOfferV2 = offer(SecurePeerRole.INITIATOR)
    val responderOffer: PairingOfferV2 = offer(SecurePeerRole.RESPONDER)

    private val secretBytes = secret.copyOf()

    fun initiatorChannel(recordKeySalt: ByteArray = ByteArray(0)): PairingChannel = DeterministicPairingChannel(
        secret = secretBytes,
        exchange = initiatorExchangeIo,
        records = initiatorRecordIo,
        recordKeySalt = recordKeySalt,
    )

    fun responderChannel(recordKeySalt: ByteArray = ByteArray(0)): PairingChannel = DeterministicPairingChannel(
        secret = secretBytes,
        exchange = responderExchangeIo,
        records = responderRecordIo,
        recordKeySalt = recordKeySalt,
    )

    fun initiatorInteraction(): TestInteraction = TestInteraction(board)

    fun responderInteraction(): TestInteraction = TestInteraction(board)

    fun initiator(interaction: TestInteraction): PakeSessionCoordinator = coordinator(
        SecurePeerRole.INITIATOR, interaction, initiatorChannel(), CountingEntropy(0x21),
    )

    fun responder(interaction: TestInteraction): PakeSessionCoordinator = coordinator(
        SecurePeerRole.RESPONDER, interaction, responderChannel(), CountingEntropy(0x31),
    )

    fun initiatorWith(channel: PairingChannel, interaction: TestInteraction): PakeSessionCoordinator =
        coordinator(SecurePeerRole.INITIATOR, interaction, channel, CountingEntropy(0x21))

    fun responderWith(channel: PairingChannel, interaction: TestInteraction): PakeSessionCoordinator =
        coordinator(SecurePeerRole.RESPONDER, interaction, channel, CountingEntropy(0x31))

    private fun coordinator(
        role: SecurePeerRole,
        interaction: TestInteraction,
        channel: PairingChannel,
        entropy: SecureEntropy,
    ): PakeSessionCoordinator = PakeSessionCoordinator(
        channel = channel,
        controlSession = controlSession(),
        role = role,
        clock = clock,
        entropy = entropy,
        random = java.security.SecureRandom(),
        interaction = interaction,
        initiatorOffer = initiatorOffer,
        responderOffer = responderOffer,
        transcriptDigest = transcriptDigest,
        recordBudgets = RecordBudgets(writeMillis = 5_000L, readMillis = 1_500L),
    )

    fun controlSession(): NegotiatedSession = NegotiatedSession(
        attemptId = controlSessionId,
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
        capabilities = SessionCapabilities.controlOnly(app.morsecode.core.model.TransportKind.LAN),
    )

    private fun offer(role: SecurePeerRole): PairingOfferV2 {
        val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
        val responderPeer = PeerInstanceId("00000000000000000000000000000002")
        return PairingOfferV2(
            secureSessionId = secureSessionId,
            controlSessionId = controlSessionId,
            role = role,
            localPeerInstanceId = if (role == SecurePeerRole.INITIATOR) initiatorPeer else responderPeer,
            remotePeerInstanceId = if (role == SecurePeerRole.INITIATOR) responderPeer else initiatorPeer,
            protocolMinimumVersion = 1,
            protocolMaximumVersion = 2,
            nonce = if (role == SecurePeerRole.INITIATOR) ByteArray(32) { 0x10 } else ByteArray(32) { 0x20 },
            certificateFingerprint =
            if (role == SecurePeerRole.INITIATOR) ByteArray(32) { 0x30 } else ByteArray(32) { 0x40 },
            transport = app.morsecode.core.model.TransportKind.LAN,
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
    }

    companion object {
        /** The participant ids a real binding would derive, so fixtures share one derivation. */
        fun participantIds(): app.morsecode.core.transfer.session.JpakeParticipantIds =
            JpakeBinding.participantIds(ByteArray(32) { 0x11 })
    }
}

/** Runs both coordinators on their own threads and returns their outcomes. */
internal fun runPairing(
    initiator: PakeSessionCoordinator,
    responder: PakeSessionCoordinator,
    timeoutMillis: Long = 240_000L,
): Pair<PakeSessionOutcome, PakeSessionOutcome> {
    // Daemon threads: a pairing that somehow blocks must not keep the test worker alive after the
    // timeout above has already failed the case.
    val pool = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "pake-pairing").apply { isDaemon = true }
    }
    try {
        val left = pool.submit(java.util.concurrent.Callable { initiator.run() })
        val right = pool.submit(java.util.concurrent.Callable { responder.run() })
        return left.get(timeoutMillis, TimeUnit.MILLISECONDS) to
            right.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } finally {
        pool.shutdownNow()
    }
}

internal fun authenticated(outcome: PakeSessionOutcome): PakeSessionOutcome.Authenticated {
    if (outcome is PakeSessionOutcome.Authenticated) return outcome
    throw AssertionError("expected an authenticated outcome, got $outcome")
}

internal fun refused(outcome: PakeSessionOutcome): PakeSessionOutcome.Refused {
    if (outcome is PakeSessionOutcome.Refused) return outcome
    throw AssertionError("expected a refusal, got ${outcome.javaClass.simpleName}")
}

/** An interaction that answers from the shared board, with the answers a test chooses. */
internal fun quietInteraction(
    board: CodeBoard,
    entered: (Int, String) -> String? = { _, published -> published },
    approval: SecureApprovalDecision? = SecureApprovalDecision.APPROVE,
): TestInteraction = TestInteraction(board).apply {
    enteredCodeFor = entered
    this.approval = approval
}

/** A code the human types that is one symbol away from the published one, once. */
internal fun wrongOnFirstAttempt(ordinal: Int, published: String): String =
    if (ordinal == 1) wrongCode(published) else published
