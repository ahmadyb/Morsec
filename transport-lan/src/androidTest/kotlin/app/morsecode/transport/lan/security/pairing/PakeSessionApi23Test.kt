/*
 * The composed path over a real Conscrypt TLS 1.3 connection, on API 23.
 *
 * This is the only place the exporter is real: both peers derive their record keys from the TLS
 * session's own keying material, over the transcript digest, and the key-confirmation records cross
 * an actual encrypted stream. The exchange's frames and the record frames share one socket, in
 * order, exactly as production will drive them.
 *
 * The payload gate is asserted here too, on the device, after a successful control authentication:
 * authority is control-only and payload is still refused.
 *
 * STATUS: experimental, project-defined, not independently cryptographically reviewed. See
 * doc/security/owner-risk-waiver-2026-10-19.md.
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

/** Runs the whole chain over genuine TLS 1.3 on API 23. */
@RunWith(AndroidJUnit4::class)
public class PakeSessionApi23Test {

    @Test(timeout = 240_000L)
    public fun theComposedPathAuthenticatesOverRealConscryptTls() {
        val server = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val clock = MonotonicClock { System.nanoTime() / 1_000_000L }
        val executor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "pake-api23").apply { isDaemon = true }
        }
        val board = ApiCodeBoard()
        try {
            val secureSessionId = SessionId("0123456789abcdef0123456789abcdef")
            val offers = offers(secureSessionId)
            val transcript = ByteArray(SecureSessionLimits.TRANSCRIPT_HASH_BYTES) { (it + 9).toByte() }

            val serverHandshake = executor.submit(Callable {
                val accepted = server.accept()
                accepted.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
                side(accepted, initiator = false, offers, transcript, clock, board)
            })
            val rawClient = Socket()
            rawClient.connect(server.localSocketAddress, SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS)
            rawClient.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            val clientOutcome = side(rawClient, initiator = true, offers, transcript, clock, board)
            val serverOutcome = serverHandshake.get(240, TimeUnit.SECONDS)

            val initiator = clientOutcome as? PakeSessionOutcome.Authenticated
                ?: throw AssertionError("the initiator did not authenticate: $clientOutcome")
            val responder = serverOutcome as? PakeSessionOutcome.Authenticated
                ?: throw AssertionError("the responder did not authenticate: $serverOutcome")
            assertEquals(PakePairingState.AUTHENTICATED, initiator.state)
            assertEquals(PakePairingState.AUTHENTICATED, responder.state)
            assertNotNull(initiator.authority.claim())
            assertNull("a control authority is single use", initiator.authority.claim())

            val decision = PayloadTransferGate.evaluate(offer(secureSessionId, SecurePeerRole.INITIATOR))
            assertTrue(decision is PayloadTransferDecision.Refused)
        } finally {
            executor.shutdownNow()
            runCatching { server.close() }
        }
    }

    private fun side(
        socket: Socket,
        initiator: Boolean,
        offers: Pair<PairingOfferV2, PairingOfferV2>,
        transcript: ByteArray,
        clock: MonotonicClock,
        board: ApiCodeBoard,
    ): PakeSessionOutcome {
        val tls: EstablishedTlsSession =
            ConscryptSecureSessionEngine(SecureRandom()).establish(socket, initiator)
        val role = if (initiator) SecurePeerRole.INITIATOR else SecurePeerRole.RESPONDER
        return try {
            assertEquals("TLSv1.3", tls.protocol)
            val channel = SocketPairingChannel(tls, clock, role)
            PakeSessionCoordinator(
                channel = channel,
                controlSession = controlSession(),
                role = role,
                clock = clock,
                entropy = SecureEntropy { destination -> SecureRandom().nextBytes(destination) },
                random = SecureRandom(),
                interaction = ApiInteraction(board, initiator),
                initiatorOffer = offers.first,
                responderOffer = offers.second,
                transcriptDigest = transcript,
            ).run()
        } finally {
            tls.clearFingerprints()
        }
    }

    private fun offers(secureSessionId: SessionId): Pair<PairingOfferV2, PairingOfferV2> =
        offer(secureSessionId, SecurePeerRole.INITIATOR) to offer(secureSessionId, SecurePeerRole.RESPONDER)

    private fun offer(secureSessionId: SessionId, role: SecurePeerRole): PairingOfferV2 = PairingOfferV2(
        secureSessionId = secureSessionId,
        controlSessionId = SessionId("control-00000000000000000000001"),
        role = role,
        localPeerInstanceId = PeerInstanceId(
            if (role == SecurePeerRole.INITIATOR) "00000000000000000000000000000001" else
                "00000000000000000000000000000002",
        ),
        remotePeerInstanceId = PeerInstanceId(
            if (role == SecurePeerRole.INITIATOR) "00000000000000000000000000000002" else
                "00000000000000000000000000000001",
        ),
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

    private fun controlSession(): NegotiatedSession = NegotiatedSession(
        attemptId = SessionId("control-00000000000000000000001"),
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

    /** What the initiator displayed, read back in-process the way a human would read it out. */
    private class ApiCodeBoard {
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

    /** The human, scripted: the initiator shows a code and this process types it back. */
    private class ApiInteraction(private val board: ApiCodeBoard, private val initiator: Boolean) :
        EnteredCodePairingInteraction {

        override fun publishGeneratedCode(code: EnteredPairingCode, expiresAtElapsedMillis: Long): Boolean {
            if (!initiator) return false
            board.publish(code.displayText().replace("-", ""))
            return true
        }

        override fun requestEnteredCode(attemptOrdinal: Int, expiresAtElapsedMillis: Long): EnteredPairingCode? {
            if (initiator) return null
            val text = board.await(120_000L) ?: return null
            return EnteredPairingCode.parse(text.toCharArray())
        }

        override fun requestIntendedPeerApproval(
            context: IntendedPeerContext,
            expiresAtElapsedMillis: Long,
        ): SecureApprovalDecision = SecureApprovalDecision.APPROVE

        override fun isCancelled(): Boolean = Thread.currentThread().isInterrupted
    }
}
