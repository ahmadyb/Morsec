package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Authentication-suite negotiation tests.
 *
 * The property under test is that the *weaker* mechanism cannot be reached by consent of one peer
 * and cannot be reached by offering something stronger. The earlier rule was `minOf` over wire ids,
 * which made the weaker suite win any disagreement -- that is exactly what these tests pin against.
 */
public class PairingAuthenticationPolicyTest {

    private val secureId = SessionId("0123456789abcdef0123456789abcdef")
    private val controlId = SessionId("control-0000000000000000000000001")
    private val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
    private val responderPeer = PeerInstanceId("00000000000000000000000000000002")

    private fun offer(
        role: SecurePeerRole,
        local: PeerInstanceId,
        remote: PeerInstanceId,
        suite: PairingAuthenticationCapability?,
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

    private fun pair(
        initiatorSuite: PairingAuthenticationCapability?,
        responderSuite: PairingAuthenticationCapability?,
    ): Pair<PairingOfferV2, PairingOfferV2> = Pair(
        offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, initiatorSuite),
        offer(SecurePeerRole.RESPONDER, responderPeer, initiatorPeer, responderSuite),
    )

    private fun selected(result: PairingSelectionResult): PairingAuthenticationCapability? =
        (result as? PairingSelectionResult.Selected)?.selection?.selectedAuthentication

    private fun rejectReason(result: PairingSelectionResult): PairingSelectionReject? =
        (result as? PairingSelectionResult.Rejected)?.reason

    @Test
    public fun `two peers that both offer the required suite select it`() {
        val (initiator, responder) = pair(
            PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
            PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
        )
        val result = PairingAuthenticationPolicy.selectRequired(initiator, responder)
        assertEquals(
            PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
            selected(result),
        )
    }

    @Test
    public fun `offering a stronger suite never wins when the peer offers the weaker one`() {
        // The old rule was minOf(2, 1) = 1, so this combination silently selected the 25-bit
        // comparison. It must now be a refusal, because there is no intersection.
        val (initiator, responder) = pair(
            PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        )
        val result = PairingAuthenticationPolicy.selectRequired(initiator, responder)
        assertEquals(
            PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED,
            rejectReason(result),
        )
    }

    @Test
    public fun `a legacy peer cannot be accommodated by the production policy`() {
        val (initiator, responder) = pair(
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        )
        // The generic selector still resolves the legacy suite when both ask for it, because the
        // codec must remain able to describe an old frame. Production must not accept it.
        assertEquals(
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            selected(SecurePairingSelectionV2.select(initiator, responder)),
        )
        assertEquals(
            PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED,
            rejectReason(PairingAuthenticationPolicy.selectRequired(initiator, responder)),
        )
    }

    @Test
    public fun `nothing and NONE are both refusals`() {
        val (absentInitiator, absentResponder) = pair(null, null)
        assertEquals(
            PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED,
            rejectReason(PairingAuthenticationPolicy.selectRequired(absentInitiator, absentResponder)),
        )

        val (noneInitiator, noneResponder) = pair(
            PairingAuthenticationCapability.NONE,
            PairingAuthenticationCapability.NONE,
        )
        assertEquals(
            PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED,
            rejectReason(PairingAuthenticationPolicy.selectRequired(noneInitiator, noneResponder)),
        )

        val (mixedInitiator, mixedResponder) = pair(
            PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
            null,
        )
        assertEquals(
            PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED,
            rejectReason(PairingAuthenticationPolicy.selectRequired(mixedInitiator, mixedResponder)),
        )
    }

    @Test
    public fun `the policy names exactly one production suite`() {
        assertEquals(
            PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
            PairingAuthenticationPolicy.REQUIRED_SUITE,
        )
        assertTrue(
            PairingAuthenticationPolicy.isProductionSuite(
                PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
            ),
        )
        assertFalse(
            PairingAuthenticationPolicy.isProductionSuite(
                PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            ),
        )
        assertFalse(
            PairingAuthenticationPolicy.isProductionSuite(PairingAuthenticationCapability.NONE),
        )
    }

    @Test
    public fun `wire ids carry no ordering meaning`() {
        // The legacy suite has the smaller id. If any code path still ranked by number, asking for
        // the required suite against a legacy peer would produce the legacy suite rather than a
        // refusal, and this assertion would fail.
        assertTrue(
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON.wireId <
                PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1.wireId,
        )
        val (initiator, responder) = pair(
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
        )
        assertEquals(
            PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED,
            rejectReason(PairingAuthenticationPolicy.selectRequired(initiator, responder)),
        )
    }

    @Test
    public fun `the required suite can be stated explicitly`() {
        val (initiator, responder) = pair(
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        )
        val result = PairingAuthenticationPolicy.selectRequired(
            initiator,
            responder,
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        )
        assertEquals(
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            selected(result),
        )
    }
}
