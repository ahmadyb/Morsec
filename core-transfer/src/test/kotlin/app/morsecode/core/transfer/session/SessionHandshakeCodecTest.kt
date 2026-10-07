package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

public class SessionHandshakeCodecTest {
    private val local = profile("00000000000000000000000000000001", "Local")
    private val remote = profile("00000000000000000000000000000002", "Remote")
    private val attemptId = SessionId("attempt-1")

    @Test
    public fun helloAcceptAndRejectRoundTripWithinTheFixedFrameBound() {
        val hello = SessionHello(attemptId, local.peerInstanceId, remote.peerInstanceId, local)
        val accepted = SessionHandshakeNegotiator.accept(remote, hello) as SessionHandshakeDecision.Accepted
        val reject = SessionReject(
            attemptId = attemptId,
            senderPeerInstanceId = remote.peerInstanceId,
            targetPeerInstanceId = local.peerInstanceId,
            code = HandshakeRejectCode.POLICY_REFUSED,
        )

        listOf<SessionHandshakeMessage>(hello, accepted.response, reject).forEach { message ->
            val encoded = SessionHandshakeCodec.encode(message)
            assertTrue(encoded.size <= SessionHandshakeCodec.MAX_FRAME_SIZE_BYTES)
            assertEquals(
                SessionHandshakeDecodeResult.Success(message),
                SessionHandshakeCodec.decode(encoded),
            )
        }
    }

    @Test
    public fun selectedPeerIdentityAndAttemptAreVerifiedByTheHandshake() {
        val hello = SessionHello(attemptId, local.peerInstanceId, remote.peerInstanceId, local)
        val accepted = SessionHandshakeNegotiator.accept(remote, hello) as SessionHandshakeDecision.Accepted
        val verified = SessionHandshakeNegotiator.verifyAccepted(
            local = local,
            expectedSelectedPeer = remote.peerInstanceId,
            sentHello = hello,
            accept = accepted.response,
        ) as SessionHandshakeDecision.Accepted

        assertEquals(remote.peerInstanceId, verified.session.remoteProfile.peerInstanceId)
        assertEquals(SessionSecurityState.ControlOnlyUnauthenticated, verified.session.security)

        val substitutedId = id("00000000000000000000000000000003")
        val substituted = accepted.response.copy(
            senderPeerInstanceId = substitutedId,
            profile = accepted.response.profile.copy(peerInstanceId = substitutedId),
        )
        val mismatch = SessionHandshakeNegotiator.verifyAccepted(
            local = local,
            expectedSelectedPeer = remote.peerInstanceId,
            sentHello = hello,
            accept = substituted,
        ) as SessionHandshakeDecision.Rejected
        assertEquals(SessionFailureCode.PEER_IDENTITY_MISMATCH, mismatch.failure.code)
    }

    @Test
    public fun peersWithoutACommonRevisionReceiveATypedRejection() {
        val versionTwoPeer = remote.copy(protocolRange = ProtocolRange(2, 2))
        val hello = SessionHello(attemptId, versionTwoPeer.peerInstanceId, local.peerInstanceId, versionTwoPeer)

        val decision = SessionHandshakeNegotiator.accept(local, hello) as SessionHandshakeDecision.Rejected

        assertEquals(HandshakeRejectCode.NO_COMMON_PROTOCOL, decision.response.code)
        assertEquals(SessionFailureCode.PROTOCOL_VERSION_UNSUPPORTED, decision.failure.code)
    }

    @Test
    public fun payloadAndResumeBitsAreNeverNegotiatedInPartA() {
        val payload = SessionCapabilities(
            supportedTransports = setOf(TransportKind.LAN),
            features = setOf(
                SessionFeature.CONTROL_HANDSHAKE,
                SessionFeature.FILE_PAYLOAD,
                SessionFeature.RESUME,
            ),
            maxChunkSizeBytes = 4_096,
            resumeSupported = true,
            encryption = EncryptionCapability.NONE,
        )
        val payloadPeer = remote.copy(capabilities = payload)
        val hello = SessionHello(attemptId, payloadPeer.peerInstanceId, local.peerInstanceId, payloadPeer)
        val decision = SessionHandshakeNegotiator.accept(local, hello) as SessionHandshakeDecision.Accepted

        assertEquals(setOf(SessionFeature.CONTROL_HANDSHAKE), decision.session.capabilities.features)
        assertFalse(decision.session.capabilities.resumeSupported)
        assertTrue(PayloadTransferGate.evaluate(decision.session) is PayloadTransferDecision.Refused)
    }

    @Test
    public fun decoderRejectsTruncationOversizedFramesUnknownTypesAndUnsupportedVersion() {
        val encoded = SessionHandshakeCodec.encode(
            SessionHello(attemptId, local.peerInstanceId, remote.peerInstanceId, local),
        )
        assertInvalid(SessionHandshakeCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertInvalid(SessionHandshakeCodec.decode(ByteArray(SessionHandshakeCodec.MAX_FRAME_SIZE_BYTES + 1)))

        val unknownType = encoded.copyOf().also { it[5] = 0x7F }
        assertInvalid(SessionHandshakeCodec.decode(unknownType))

        val unsupported = encoded.copyOf().also { it[4] = (SessionHandshakeCodec.WIRE_VERSION + 1).toByte() }
        val headerResult = SessionHandshakeCodec.decodeHeader(unsupported.copyOfRange(0, SessionHandshakeCodec.HEADER_SIZE_BYTES))
        assertTrue(headerResult is SessionHandshakeHeaderResult.Invalid)
        assertEquals(
            SessionFailureCode.HANDSHAKE_VERSION_UNSUPPORTED,
            (headerResult as SessionHandshakeHeaderResult.Invalid).failure.code,
        )

        val oversizedHeader = encoded.copyOfRange(0, SessionHandshakeCodec.HEADER_SIZE_BYTES).also {
            it[6] = 0x02
            it[7] = 0x00
        }
        assertInvalidHeader(SessionHandshakeCodec.decodeHeader(oversizedHeader))
    }

    @Test
    public fun payloadTransferRequiresReviewedSecureSessionEvidence() {
        val hello = SessionHello(attemptId, local.peerInstanceId, remote.peerInstanceId, local)
        val accepted = SessionHandshakeNegotiator.accept(remote, hello) as SessionHandshakeDecision.Accepted

        val decision = PayloadTransferGate.evaluate(accepted.session)
        assertTrue(decision is PayloadTransferDecision.Refused)
        assertEquals(
            SessionFailureCode.SECURE_SESSION_REQUIRED,
            (decision as PayloadTransferDecision.Refused).failure.code,
        )
    }

    private fun assertInvalid(result: SessionHandshakeDecodeResult) {
        assertTrue(result is SessionHandshakeDecodeResult.Invalid)
    }

    private fun assertInvalidHeader(result: SessionHandshakeHeaderResult) {
        assertTrue(result is SessionHandshakeHeaderResult.Invalid)
    }

    private fun profile(value: String, name: String): SessionPeerProfile = SessionPeerProfile(
        peerInstanceId = id(value),
        displayName = name,
        appVersion = "1.0.0",
        protocolRange = ProtocolRange.CURRENT,
        capabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    )

    private fun id(value: String): PeerInstanceId = PeerInstanceId(value)
}
