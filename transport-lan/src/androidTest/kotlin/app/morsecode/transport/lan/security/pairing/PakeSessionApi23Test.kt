/*
 * The composed PAKE path on API 23 over a real TLS 1.3 connection.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE JVM TEST
 *   The JVM test proves the composition over an in-memory channel with a deterministic exporter.
 *   That is a wiring proof, not a TLS proof: nothing on the JVM exercises Conscrypt, the ephemeral
 *   certificate path, or an exporter tied to a real handshake. This test runs the same coordinator
 *   over two sockets that completed a TLS 1.3 handshake through the bundled provider on an API 23
 *   emulator, so the exporter material both sides derive is the TLS one, and both key confirmations
 *   cross a real encrypted stream.
 *
 * WHAT IT DOES NOT SHOW
 *   It is not a review, not a proof and not a security claim. See
 *   doc/security/owner-risk-waiver-2026-10-19.md. The authority it produces is control-only and the
 *   payload gate still refuses; the last assertion checks that on this device too.
 */

package app.morsecode.transport.lan.security.pairing

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.EnteredPairingCode
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PairingAuthenticationCapability
import app.morsecode.core.transfer.session.PairingOfferV2
import app.morsecode.core.transfer.session.PayloadTransferDecision
import app.morsecode.core.transfer.session.PayloadTransferGate
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureEntropy
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import app.morsecode.transport.lan.security.ConscryptSecureSessionEngine
import app.morsecode.transport.lan.security.EstablishedTlsSession
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the whole chain over a genuine TLS 1.3 connection on API 23. */
@RunWith(AndroidJUnit4::class)
public class PakeSessionApi23Test {

    @Test
    public fun theComposedPakePathAuthenticatesOverRealConscryptTls() {
        val server = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val clock = MonotonicClock { System.nanoTime() / 1_000_000L }
        val executor = Executors.newFixedThreadPool(2)
        val board = CodeBoard()
        try {
            val secureSessionId = SessionId("0123456789abcdef0123456789abcdef")
            val offers = offers(secureSessionId)
            val transcript = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 9).toByte() }

            // One side of the connection, driven on its own thread once the handshake completes.
            fun side(initiator: Boolean, socket: Socket): PakePairingOutcome {
                val tls = ConscryptSecureSessionEngine(SecureRandom()).establish(socket, initiator)
                return try {
                    assertEquals("TLSv1.3", tls.protocol)
                    val role = if (initiator) SecurePeerRole.INITIATOR else SecurePeerRole.RESPONDER
                    val coordinator = PakeSessionCoordinator(
                        channel = TlsPairingChannel(tls),
                        controlSession = controlSession(secureSessionId),
                        role = role,
                        clock = clock,
                        entropy = SecureEntropy { destination -> SecureRandom().nextBytes(destination) },
                        random = SecureRandom(),
                        interaction = SocketInteraction(board, initiator),
                        exchangeIo = SocketPairingExchangeIo(tls.socket, clock),
                        recordIo = SocketPairingRecordIo(tls.socket, secureSessionId, role, clock),
                        initiatorOffer = offers.first,
                        responderOffer = offers.second,
                        transcriptDigestInput = transcript,
                        budgets = PairingExchangeBudgets(
                            peerResponseMillis = 60_000L,
                            roundMillis = 60_000L,
                            codeLifetimeMillis = 120_000L,
                            approvalMillis = 60_000L,
                        ),
                    )
                    coordinator.run()
                } finally {
                    tls.clearFingerprints()
                }
            }

            val serverHandshake = executor.submit(Callable {
                val accepted = server.accept()
                accepted.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
                side(initiator = false, socket = accepted)
            })
            val rawClient = Socket()
            rawClient.connect(server.localSocketAddress, SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS)
            rawClient.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            val clientOutcome = side(initiator = true, socket = rawClient)
            val serverOutcome = serverHandshake.get(300, TimeUnit.SECONDS)

            val authenticatedClient = clientOutcome as? PakePairingOutcome.Authenticated
                ?: throw AssertionError("the initiator did not authenticate: $clientOutcome")
            val authenticatedServer = serverOutcome as? PakePairingOutcome.Authenticated
                ?: throw AssertionError("the responder did not authenticate: $serverOutcome")
            assertEquals(1, authenticatedClient.attemptOrdinal)
            assertEquals(1, authenticatedServer.attemptOrdinal)
            assertNotNull(authenticatedClient.authority.claim())
            assertNull("a control authority is single use", authenticatedClient.authority.claim())

            // Control authentication does not unlock payload, on this device either.
            val decision = PayloadTransferGate.evaluate(authenticatedServer.controlSession)
            assertTrue(decision is PayloadTransferDecision.Refused)
        } finally {
            executor.shutdownNow()
            runCatching { server.close() }
        }
    }

    private fun offers(secureSessionId: SessionId): Pair<PairingOfferV2, PairingOfferV2> {
        val controlId = SessionId("control-00000000000000000000001")
        val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
        val responderPeer = PeerInstanceId("00000000000000000000000000000002")
        return offer(SecurePeerRole.INITIATOR, secureSessionId, controlId, initiatorPeer, responderPeer) to
            offer(SecurePeerRole.RESPONDER, secureSessionId, controlId, responderPeer, initiatorPeer)
    }

    private fun offer(
        role: SecurePeerRole,
        secureSessionId: SessionId,
        controlId: SessionId,
        local: PeerInstanceId,
        remote: PeerInstanceId,
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
        offeredAuthentication = PairingAuthenticationCapability.JPAKE_MODP3072_ENTERED_CODE_V1,
        appVersion = "1.0.0",
        extensions = emptyList(),
    )

    private fun controlSession(secureSessionId: SessionId): NegotiatedSession = NegotiatedSession(
        attemptId = secureSessionId,
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

    /** What the initiator displayed, shared in-process the way a human would read it across. */
    private class CodeBoard {
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

    /** The human, scripted: the initiator shows the code and this process reads it back. */
    private class SocketInteraction(private val board: CodeBoard, private val initiator: Boolean) :
        EnteredCodePairingInteraction {

        override fun publishGeneratedCode(code: EnteredPairingCode, expiresAtElapsedMillis: Long): Boolean {
            // The initiator is the only side that shows a code; a responder that somehow reached
            // here has been wired backwards and must not publish anything.
            if (!initiator) return false
            board.publish(code.displayText().replace("-", ""))
            return true
        }

        override fun requestEnteredCode(attemptOrdinal: Int, expiresAtElapsedMillis: Long): EnteredPairingCode? {
            val text = board.await(60_000L) ?: return null
            return EnteredPairingCode.parse(text.toCharArray())
        }

        override fun requestIntendedPeerApproval(
            context: IntendedPeerContext,
            expiresAtElapsedMillis: Long,
        ): SecureApprovalDecision = SecureApprovalDecision.APPROVE

        override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted
    }
}
