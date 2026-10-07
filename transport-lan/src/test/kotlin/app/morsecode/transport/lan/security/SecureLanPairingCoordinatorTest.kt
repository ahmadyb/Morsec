package app.morsecode.transport.lan.security

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureApprovalResult
import app.morsecode.core.transfer.session.SecureControlReceiveResult
import app.morsecode.core.transfer.session.SecureControlSendResult
import app.morsecode.core.transfer.session.SecurePairingApprovalRequest
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SecureSessionStateMachine
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class SecureLanPairingCoordinatorTest {
    @Test
    public fun realTlsExporterApprovalAndMutualConfirmationProduceControlOnlySecureSession() {
        val listener = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val executor = Executors.newSingleThreadExecutor()
        val clock = MonotonicClock { 100L }
        val initiatorInteraction = TestApprovalInteraction()
        val responderInteraction = TestApprovalInteraction()
        var clientResult: SecurePairingRunResult? = null
        var serverResult: SecurePairingRunResult? = null
        var clientChannel: SecureLanControlChannel? = null
        var serverChannel: SecureLanControlChannel? = null
        try {
            val initiator = secureSession(localId = "00000000000000000000000000000001", remoteId = "00000000000000000000000000000002")
            val responder = secureSession(localId = "00000000000000000000000000000002", remoteId = "00000000000000000000000000000001")
            val server = executor.submit<SecurePairingRunResult> {
                val socket = listener.accept().apply { soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS }
                SecureLanPairingCoordinator(
                    rawSocket = socket,
                    controlSession = responder,
                    role = SecurePeerRole.RESPONDER,
                    clock = clock,
                    random = SecureRandom(),
                    interaction = responderInteraction,
                ).run()
            }
            val rawClient = Socket()
            rawClient.connect(listener.localSocketAddress, SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS)
            rawClient.soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
            clientResult = SecureLanPairingCoordinator(
                rawSocket = rawClient,
                controlSession = initiator,
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                random = SecureRandom(),
                interaction = initiatorInteraction,
            ).run()
            serverResult = server.get(20, TimeUnit.SECONDS)

            val client = clientResult as SecurePairingRunResult.Authenticated
            val peer = serverResult as SecurePairingRunResult.Authenticated
            assertTrue(client.negotiatedSession.security is app.morsecode.core.transfer.session.ApprovedSecureSession)
            assertTrue(peer.negotiatedSession.security is app.morsecode.core.transfer.session.ApprovedSecureSession)
            assertEquals(client.negotiatedSession.capabilities, peer.negotiatedSession.capabilities)
            assertEquals(
                setOf(SessionFeature.CONTROL_HANDSHAKE, SessionFeature.SECURE_SESSION),
                client.negotiatedSession.capabilities.features,
            )
            assertEquals(0, client.negotiatedSession.capabilities.maxChunkSizeBytes)
            assertEquals(EncryptionCapability.TLS_1_3, client.negotiatedSession.capabilities.encryption)
            assertEquals(initiatorInteraction.proofAtDisplay, responderInteraction.proofAtDisplay)
            assertTrue(requireNotNull(initiatorInteraction.proofAtDisplay).matches(Regex("[0-9]{6}")))

            val clientSecureChannel = SecureLanControlChannel(
                socket = client.secureSocket,
                recordLayer = client.recordLayer,
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                expiresAtElapsedMillis = client.expiresAtElapsedMillis,
                onTerminal = {},
            )
            clientChannel = clientSecureChannel
            val serverSecureChannel = SecureLanControlChannel(
                socket = peer.secureSocket,
                recordLayer = peer.recordLayer,
                role = SecurePeerRole.RESPONDER,
                clock = clock,
                expiresAtElapsedMillis = peer.expiresAtElapsedMillis,
                onTerminal = {},
            )
            serverChannel = serverSecureChannel
            assertTrue(
                clientSecureChannel.send(SecureRecordType.KEY_CONFIRMATION, byteArrayOf()) is
                    SecureControlSendResult.Refused,
            )
            assertEquals(SecureControlSendResult.Sent, clientSecureChannel.send(SecureRecordType.PING, byteArrayOf()))
            val ping = serverSecureChannel.receive() as SecureControlReceiveResult.Record
            assertEquals(SecureRecordType.PING, ping.value.type)
            ping.value.clearSensitive()
            assertEquals(SecureControlSendResult.Sent, serverSecureChannel.send(SecureRecordType.PONG, byteArrayOf()))
            val pong = clientSecureChannel.receive() as SecureControlReceiveResult.Record
            assertEquals(SecureRecordType.PONG, pong.value.type)
            pong.value.clearSensitive()
            assertEquals(
                SecureControlSendResult.Sent,
                clientSecureChannel.send(SecureRecordType.SESSION_CLOSE, byteArrayOf()),
            )
            val closeRecord = serverSecureChannel.receive() as SecureControlReceiveResult.Record
            assertEquals(SecureRecordType.SESSION_CLOSE, closeRecord.value.type)
            closeRecord.value.clearSensitive()
        } finally {
            clientChannel?.close()
            serverChannel?.close()
            if (clientChannel == null || serverChannel == null) {
                listOfNotNull(clientResult, serverResult).forEach { result ->
                    if (result is SecurePairingRunResult.Authenticated) {
                        result.recordLayer.close()
                        try { result.secureSocket.close() } catch (_: Exception) { }
                    }
                }
            }
            initiatorInteraction.dispose()
            responderInteraction.dispose()
            try { listener.close() } catch (_: Exception) { }
            executor.shutdownNow()
        }
    }

    private fun secureSession(localId: String, remoteId: String): NegotiatedSession {
        val local = profile(localId, "Local")
        val remote = profile(remoteId, "Remote")
        return NegotiatedSession(
            attemptId = SessionId("attempt-1"),
            localProfile = local,
            remoteProfile = remote,
            protocolVersion = 1,
            capabilities = NegotiatedCapabilities(
                features = setOf(SessionFeature.CONTROL_HANDSHAKE),
                maxChunkSizeBytes = 4_096,
                resumeSupported = false,
                encryption = EncryptionCapability.NONE,
            ),
        )
    }

    private fun profile(id: String, name: String): SessionPeerProfile = SessionPeerProfile(
        peerInstanceId = PeerInstanceId(id),
        displayName = name,
        appVersion = "1.0.0",
        protocolRange = ProtocolRange.CURRENT,
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    /** Test-only explicit approval callback; production has no auto-approval implementation. */
    private class TestApprovalInteraction : PairingInteraction {
        private var request: SecurePairingApprovalRequest? = null
        var proofAtDisplay: String? = null
            private set

        override fun publishApproval(
            machine: SecureSessionStateMachine,
            request: SecurePairingApprovalRequest,
        ): Boolean {
            this.request = request
            proofAtDisplay = request.proof.displayText()
            return machine.state().name == "AWAITING_LOCAL_APPROVAL"
        }

        override fun awaitApproval(machine: SecureSessionStateMachine): Boolean {
            val exactRequest = request ?: return false
            val decision = machine.decide(exactRequest.handle, SecureApprovalDecision.APPROVE)
            return decision in setOf(SecureApprovalResult.Applied, SecureApprovalResult.AlreadyApplied)
        }

        override fun isCancelled(): Boolean = false

        fun dispose() {
            request?.proof?.clearSensitive()
            request = null
            proofAtDisplay = null
        }
    }
}
