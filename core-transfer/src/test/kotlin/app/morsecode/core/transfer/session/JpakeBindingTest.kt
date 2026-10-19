package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel-binding derivation tests.
 *
 * These pin that both peers derive the same identifiers from the same inputs, and that changing
 * any bound input changes them. They do not and cannot show that the construction is secure; see
 * the waiver.
 */
public class JpakeBindingTest {

    private val secureId = SessionId("0123456789abcdef0123456789abcdef")
    private val controlId = SessionId("control-0000000000000000000000001")
    private val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
    private val responderPeer = PeerInstanceId("00000000000000000000000000000002")

    private fun offer(
        role: SecurePeerRole,
        local: PeerInstanceId,
        remote: PeerInstanceId,
        suite: PairingAuthenticationCapability = PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
    ): PairingOfferV2 = PairingOfferV2(
        secureSessionId = secureId,
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
        offeredAuthentication = suite,
        appVersion = "1.0.0",
        extensions = emptyList(),
    )

    private fun inputs(
        channelBinding: ByteArray = ByteArray(32) { 0x55 },
        transcriptDigest: ByteArray = ByteArray(32) { 0x66 },
        codeInstance: ByteArray = ByteArray(16) { 0x77 },
        suite: PairingAuthenticationCapability = PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
    ): JpakeBindingInputs = JpakeBindingInputs(
        channelBinding = channelBinding,
        transcriptDigest = transcriptDigest,
        codeInstanceId = codeInstance,
        formatVersion = 1,
        suite = suite,
        initiatorOffer = offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, suite),
        responderOffer = offer(SecurePeerRole.RESPONDER, responderPeer, initiatorPeer, suite),
    )

    @Test
    public fun `both peers derive the same context and the same identifiers`() {
        val first = inputs()
        val second = inputs()
        val contextA = JpakeBinding.authenticationContext(first)
        val contextB = JpakeBinding.authenticationContext(second)
        assertEquals(contextA.toList(), contextB.toList())
        assertEquals(32, contextA.size)

        val idsA = JpakeBinding.participantIds(contextA)
        val idsB = JpakeBinding.participantIds(contextB)
        assertEquals(idsA.initiator, idsB.initiator)
        assertEquals(idsA.responder, idsB.responder)
    }

    @Test
    public fun `the two identifiers differ and keep to their prefixes`() {
        val ids = JpakeBinding.participantIds(JpakeBinding.authenticationContext(inputs()))
        assertNotEquals(ids.initiator, ids.responder)
        assertTrue(ids.initiator.startsWith(JpakeBinding.INITIATOR_ID_PREFIX))
        assertTrue(ids.responder.startsWith(JpakeBinding.RESPONDER_ID_PREFIX))
    }

    @Test
    public fun `each identifier fits the wire bound`() {
        val ids = JpakeBinding.participantIds(JpakeBinding.authenticationContext(inputs()))
        assertTrue(
            "initiator id must fit the 64-byte bound",
            ids.initiator.toByteArray(Charsets.UTF_8).size <= JpakeMessageCodec.MAX_PARTICIPANT_ID_BYTES,
        )
        assertTrue(
            "responder id must fit the 64-byte bound",
            ids.responder.toByteArray(Charsets.UTF_8).size <= JpakeMessageCodec.MAX_PARTICIPANT_ID_BYTES,
        )
        assertTrue(
            "identifiers must be printable ASCII so the wire accepts them",
            JpakeMessageCodec.isBoundedText(ids.initiator, JpakeMessageCodec.MAX_PARTICIPANT_ID_BYTES),
        )
    }

    @Test
    public fun `a different TLS channel binding yields different identifiers`() {
        val a = JpakeBinding.participantIds(
            JpakeBinding.authenticationContext(inputs(channelBinding = ByteArray(32) { 0x55 })),
        )
        val b = JpakeBinding.participantIds(
            JpakeBinding.authenticationContext(inputs(channelBinding = ByteArray(32) { 0x56 })),
        )
        assertNotEquals(a.initiator, b.initiator)
        assertNotEquals(a.responder, b.responder)
    }

    @Test
    public fun `a different transcript or code instance yields different identifiers`() {
        val base = JpakeBinding.participantIds(JpakeBinding.authenticationContext(inputs()))
        val otherTranscript = JpakeBinding.participantIds(
            JpakeBinding.authenticationContext(inputs(transcriptDigest = ByteArray(32) { 0x67 })),
        )
        val otherCode = JpakeBinding.participantIds(
            JpakeBinding.authenticationContext(inputs(codeInstance = ByteArray(16) { 0x78 })),
        )
        assertNotEquals(base.initiator, otherTranscript.initiator)
        assertNotEquals(base.initiator, otherCode.initiator)
    }

    @Test
    public fun `the role lookup and its partner are symmetric on both sides`() {
        val ids = JpakeBinding.participantIds(JpakeBinding.authenticationContext(inputs()))
        assertEquals(ids.initiator, ids.forRole(SecurePeerRole.INITIATOR))
        assertEquals(ids.responder, ids.forRole(SecurePeerRole.RESPONDER))
        assertEquals(ids.responder, ids.partnerOf(SecurePeerRole.INITIATOR))
        assertEquals(ids.initiator, ids.partnerOf(SecurePeerRole.RESPONDER))
    }

    @Test
    public fun `the context refuses a channel binding of the wrong width`() {
        var threw = false
        try {
            JpakeBindingInputs(
                channelBinding = ByteArray(31),
                transcriptDigest = ByteArray(32),
                codeInstanceId = ByteArray(16),
                formatVersion = 1,
                suite = PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
                initiatorOffer = offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer),
                responderOffer = offer(SecurePeerRole.RESPONDER, responderPeer, initiatorPeer),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a 31-byte channel binding must be refused", threw)
    }

    @Test
    public fun `clearing the inputs wipes the bound values`() {
        val bound = inputs()
        bound.clearSensitive()
        // Re-deriving after a wipe must not reproduce the original context: the values are gone.
        val after = JpakeBinding.authenticationContext(bound)
        val fresh = JpakeBinding.authenticationContext(inputs())
        assertNotEquals(fresh.toList(), after.toList())
    }
}
