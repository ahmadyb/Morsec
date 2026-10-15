package app.morsecode.transport.lan.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.ControlIoFailureContext
import app.morsecode.core.transfer.session.ControlIoOperation
import app.morsecode.core.transfer.session.ControlIoPhase
import app.morsecode.core.transfer.session.ControlIoRecordType
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.MonotonicClock
import app.morsecode.core.transfer.session.NegotiatedCapabilities
import app.morsecode.core.transfer.session.NegotiatedSession
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SecureApprovalDecision
import app.morsecode.core.transfer.session.SecureControlReceiveResult
import app.morsecode.core.transfer.session.SecureControlSendResult
import app.morsecode.core.transfer.session.SecurePeerRole
import app.morsecode.core.transfer.session.SecureRecordCodec
import app.morsecode.core.transfer.session.SecureRecordDirection
import app.morsecode.core.transfer.session.SecureRecordType
import app.morsecode.core.transfer.session.SecureSessionLimits
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFailureCode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class SecureLanPairingCoordinatorApi23Test {
    @Test
    public fun realTlsExporterApprovalAndMutualConfirmationProduceControlOnlySecureSession() {
        val listener = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val executor = Executors.newSingleThreadExecutor()
        val clock = MonotonicClock { 100L }
        val rendezvous = ProofRendezvous()
        val initiatorInteraction = ProofComparisonController("initiator", rendezvous)
        val responderInteraction = ProofComparisonController("responder", rendezvous)
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
            // M2 authority boundary, asserted on a real API 23 handshake: a completed pairing hands
            // back no public authenticated-session marker. The negotiated session still reports the
            // unauthenticated control state, and the payload gate still refuses. Authority is the
            // module-internal SecureLanControlChannel built below, not anything a caller can name.
            assertEquals(
                app.morsecode.core.transfer.session.SessionSecurityState.ControlOnlyUnauthenticated,
                client.negotiatedControlSession.security,
            )
            assertEquals(
                app.morsecode.core.transfer.session.SessionSecurityState.ControlOnlyUnauthenticated,
                peer.negotiatedControlSession.security,
            )
            assertEquals(client.negotiatedControlSession.capabilities, peer.negotiatedControlSession.capabilities)
            assertEquals(
                setOf(SessionFeature.CONTROL_HANDSHAKE),
                client.negotiatedControlSession.capabilities.features,
            )
            assertEquals(EncryptionCapability.NONE, client.negotiatedControlSession.capabilities.encryption)
            assertTrue(
                app.morsecode.core.transfer.session.PayloadTransferGate.evaluate(
                    client.negotiatedControlSession,
                ) is app.morsecode.core.transfer.session.PayloadTransferDecision.Refused,
            )
            // Approval was already gated on this comparison inside ProofComparisonController, so
            // reaching here means the two proofs matched before either side approved. The assertion
            // is retained because it also pins the proof's shape.
            assertEquals(initiatorInteraction.proofAtDisplay, responderInteraction.proofAtDisplay)
            assertEquals(SecureApprovalDecision.APPROVE, initiatorInteraction.decisionTaken)
            assertEquals(SecureApprovalDecision.APPROVE, responderInteraction.decisionTaken)
            assertTrue(
                requireNotNull(initiatorInteraction.proofAtDisplay)
                    .matches(Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{5}")),
            )

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

    /**
     * Deadline expiry on the real production channel over a real API 23 Conscrypt handshake.
     *
     * The first test above proves the honest path: pairing authenticates, PING/PONG/SESSION_CLOSE
     * all round-trip, and no timeout context is emitted. This one proves the other half -- that a
     * peer which supplies too little is ended by the operation's one absolute deadline, and that the
     * failure says which phase and which record type, which `CONTROL_TIMEOUT` alone could not.
     *
     * EVIDENCE SPLIT, STATED PLAINLY. This covers the *receive* side on real API 23 sockets, because
     * a peer that withholds bytes is trivially deterministic. It does not cover the *write* side:
     * control frames are tens of bytes against an emulator socket buffer of tens of kilobytes, so
     * forcing a genuine blocked write here would mean shrinking buffers until the test measured the
     * shim rather than the channel, and calling that real-world backpressure would be false.
     * OS-level blocking-write evidence lives in the JVM `BlockingWriteReleaseTest`, which blocks a
     * real production write on a real kernel socket and proves the deadline closes the owning socket
     * and the write cannot report success afterwards. The `SEND`/`FLUSH` classification itself is
     * pinned statically by verifier group 18.
     */
    @Test
    public fun receiveDeadlineOnRealApi23ChannelClassifiesHeaderAndBodyPhases() {
        val listener = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val executor = Executors.newSingleThreadExecutor()
        // Advancing monotonic time. A fixed clock would leave `deadline - now` constant across every
        // read, so a slow peer would never be ended and the test would prove nothing.
        val clock = MonotonicClock { System.nanoTime() / 1_000_000L }
        val rendezvous = ProofRendezvous()
        val initiatorInteraction = ProofComparisonController("initiator", rendezvous)
        val responderInteraction = ProofComparisonController("responder", rendezvous)
        var clientResult: SecurePairingRunResult? = null
        var serverResult: SecurePairingRunResult? = null
        var clientChannel: SecureLanControlChannel? = null
        try {
            val initiator = secureSession(
                localId = "00000000000000000000000000000001",
                remoteId = "00000000000000000000000000000002",
            )
            val responder = secureSession(
                localId = "00000000000000000000000000000002",
                remoteId = "00000000000000000000000000000001",
            )
            val server = executor.submit<SecurePairingRunResult> {
                val socket = listener.accept().apply {
                    soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
                }
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
            serverResult = server.get(30, TimeUnit.SECONDS)

            val client = clientResult as SecurePairingRunResult.Authenticated
            val peer = serverResult as SecurePairingRunResult.Authenticated
            var terminalRuns = 0
            val channel = SecureLanControlChannel(
                socket = client.secureSocket,
                recordLayer = client.recordLayer,
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                expiresAtElapsedMillis = client.expiresAtElapsedMillis,
                onTerminal = { terminalRuns++ },
            )
            clientChannel = channel

            // --- header phase: a header that never completes, then silence -------------------
            // Written straight to the peer's TLS socket, so the bytes are genuinely protected and
            // genuinely short. Nothing about the record is known yet, so the type must be UNKNOWN.
            val header = SecureRecordCodec.encodeHeader(
                sessionId = client.recordLayer.sessionId,
                direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
                sequenceNumber = 0L,
                type = SecureRecordType.PING,
                plaintextLength = 0,
            )
            val peerOut = peer.secureSocket.getOutputStream()
            peerOut.write(header.copyOfRange(0, SecureRecordCodec.HEADER_SIZE_BYTES - 1))
            peerOut.flush()

            val headerTimeout = channel.receive()
            assertTrue(
                "an incomplete header must be refused, got $headerTimeout",
                headerTimeout is SecureControlReceiveResult.Refused,
            )
            val headerFailure = (headerTimeout as SecureControlReceiveResult.Refused).failure
            assertEquals(SessionFailureCode.CONTROL_TIMEOUT, headerFailure.code)
            assertEquals(
                "no type may be fabricated before the header decodes",
                ControlIoFailureContext(
                    operation = ControlIoOperation.RECEIVE,
                    phase = ControlIoPhase.HEADER,
                    recordType = ControlIoRecordType.UNKNOWN,
                ),
                headerFailure.controlIoContext,
            )
            assertTrue("the deadline must close the owning socket", client.secureSocket.isClosed)
            assertEquals("terminal teardown runs exactly once", 1, terminalRuns)

            // The channel is terminal: no later operation revives it or advances a sequence.
            val afterTimeout = channel.receive()
            assertTrue(afterTimeout is SecureControlReceiveResult.Refused)
            assertEquals(
                SessionFailureCode.OPERATION_CANCELLED,
                (afterTimeout as SecureControlReceiveResult.Refused).failure.code,
            )
            val afterSend = channel.send(SecureRecordType.PING, byteArrayOf())
            assertTrue(afterSend is SecureControlSendResult.Refused)
            assertEquals("teardown must not run twice", 1, terminalRuns)
            // Repeated close is harmless on an already terminal channel.
            channel.close()
            channel.close()
            assertEquals(1, terminalRuns)
        } finally {
            clientChannel?.close()
            if (clientChannel == null) {
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

    /**
     * Body-phase expiry on the real production channel: a complete, valid header arrives so the type
     * is known, then the AEAD tag stalls. The failure must name the record type, which is the whole
     * reason the structured context exists -- and the distinction a JVM regression once collapsed
     * back into `HEADER`/`UNKNOWN` because a read deadline escapes as an exception rather than
     * returning false.
     */
    @Test
    public fun receiveBodyDeadlineOnRealApi23ChannelNamesTheDecodedRecordType() {
        val listener = ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
        }
        val executor = Executors.newSingleThreadExecutor()
        val clock = MonotonicClock { System.nanoTime() / 1_000_000L }
        val rendezvous = ProofRendezvous()
        val initiatorInteraction = ProofComparisonController("initiator", rendezvous)
        val responderInteraction = ProofComparisonController("responder", rendezvous)
        var clientResult: SecurePairingRunResult? = null
        var serverResult: SecurePairingRunResult? = null
        var clientChannel: SecureLanControlChannel? = null
        try {
            val initiator = secureSession(
                localId = "00000000000000000000000000000001",
                remoteId = "00000000000000000000000000000002",
            )
            val responder = secureSession(
                localId = "00000000000000000000000000000002",
                remoteId = "00000000000000000000000000000001",
            )
            val server = executor.submit<SecurePairingRunResult> {
                val socket = listener.accept().apply {
                    soTimeout = SecureSessionLimits.TLS_HANDSHAKE_TIMEOUT_MILLIS
                }
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
            serverResult = server.get(30, TimeUnit.SECONDS)

            val client = clientResult as SecurePairingRunResult.Authenticated
            val peer = serverResult as SecurePairingRunResult.Authenticated
            val channel = SecureLanControlChannel(
                socket = client.secureSocket,
                recordLayer = client.recordLayer,
                role = SecurePeerRole.INITIATOR,
                clock = clock,
                expiresAtElapsedMillis = client.expiresAtElapsedMillis,
                onTerminal = {},
            )
            clientChannel = channel

            val header = SecureRecordCodec.encodeHeader(
                sessionId = client.recordLayer.sessionId,
                direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
                sequenceNumber = 0L,
                type = SecureRecordType.PING,
                plaintextLength = 0,
            )
            val peerOut = peer.secureSocket.getOutputStream()
            peerOut.write(header)
            peerOut.flush()
            // PING carries no payload, so the whole outstanding body is the AEAD tag. Drip it slowly
            // enough that it cannot arrive inside the absolute deadline, while each byte still
            // arrives well inside the per-read window -- the shape that defeats an inactivity timer.
            Thread {
                try {
                    repeat(SecureSessionLimits.AEAD_TAG_BYTES) {
                        peerOut.write(byteArrayOf(0x42))
                        peerOut.flush()
                        Thread.sleep(400L)
                    }
                } catch (_: Exception) {
                    // The deadline closes the socket; that is the point.
                }
            }.apply { isDaemon = true }.start()

            val bodyTimeout = channel.receive()
            assertTrue("an incomplete body must be refused, got $bodyTimeout",
                bodyTimeout is SecureControlReceiveResult.Refused)
            val bodyFailure = (bodyTimeout as SecureControlReceiveResult.Refused).failure
            assertEquals(SessionFailureCode.CONTROL_TIMEOUT, bodyFailure.code)
            assertEquals(
                "the header decoded, so the type must be reported rather than left UNKNOWN",
                ControlIoFailureContext(
                    operation = ControlIoOperation.RECEIVE,
                    phase = ControlIoPhase.BODY,
                    recordType = ControlIoRecordType.PING,
                ),
                bodyFailure.controlIoContext,
            )
            assertTrue("the deadline must close the owning socket", client.secureSocket.isClosed)
            // The sequence never advanced: nothing was authenticated, so nothing was consumed.
            val after = channel.receive()
            assertTrue(after is SecureControlReceiveResult.Refused)
            assertEquals(
                SessionFailureCode.OPERATION_CANCELLED,
                (after as SecureControlReceiveResult.Refused).failure.code,
            )
        } finally {
            clientChannel?.close()
            if (clientChannel == null) {
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

}
