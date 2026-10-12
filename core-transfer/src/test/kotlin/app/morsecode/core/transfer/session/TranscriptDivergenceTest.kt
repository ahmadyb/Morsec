package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.PeerInstanceId
import app.morsecode.core.transfer.identity.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The property a two-leg MITM has to defeat, tested where it can actually be tested.
 *
 * A man in the middle runs one TLS leg to each honest endpoint. Each leg can be structurally valid:
 * real handshake, real certificate, real exporter. What saves the protocol is that the two honest
 * endpoints end up holding *different* views of the negotiated parameters, and the transcript is
 * built from both peers' offers. Different transcripts mean different human proofs, so the users
 * cannot both approve, and no authenticated authority is issued on either side.
 *
 * WHAT THIS SUITE PROVES, AND WHAT IT DOES NOT. It proves that every substitution below either
 * fails selection outright or makes the two endpoints' canonical transcript digests differ. It does
 * not run the TLS exporter: the displayed proof is derived from exported keying material over this
 * digest, and the exporter is Conscrypt-only by design -- `ConscryptSecureSessionEngine` calls
 * `Conscrypt.newProvider()` in its constructor and throws on any socket for which
 * `Conscrypt.isConscrypt` is false. Digest divergence is therefore the load-bearing input, and the
 * end-to-end coordinator pairing runs in API 23 instrumentation rather than here. Injecting a
 * provider to move that onto the JVM would mean weakening the Conscrypt requirement, which is a
 * security property and not a test inconvenience.
 */
public class TranscriptDivergenceTest {

    private val secureId = SessionId("00112233445566778899aabbccddeeff")
    private val controlId = SessionId("attempt-1")
    private val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
    private val responderPeer = PeerInstanceId("00000000000000000000000000000002")
    private val attackerPeer = PeerInstanceId("00000000000000000000000000000003")

    private fun range(start: Int, length: Int): ByteArray =
        ByteArray(length) { ((start + it) and 0xFF).toByte() }

    private fun offer(
        role: SecurePeerRole,
        local: PeerInstanceId,
        remote: PeerInstanceId,
        secureSessionId: SessionId = secureId,
        controlSessionId: SessionId = controlId,
        nonce: ByteArray = if (role == SecurePeerRole.INITIATOR) range(0x00, 32) else range(0x40, 32),
        fingerprint: ByteArray = if (role == SecurePeerRole.INITIATOR) range(0x20, 32) else range(0x60, 32),
        securitySuiteWireId: Int = 1,
        tlsProtocol: String = "TLSv1.3",
        tlsCipherSuite: String = "TLS_AES_128_GCM_SHA256",
        offeredFeaturesMask: Long = 9L,
        offeredMaxChunkSizeBytes: Long = 65_536L,
        offeredResumeSupported: Boolean = true,
        offeredEncryption: EncryptionCapability = EncryptionCapability.TLS_1_3,
        offeredAuthentication: PairingAuthenticationCapability = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        extensions: List<PairingExtensionV2> = emptyList(),
    ): PairingOfferV2 = PairingOfferV2(
        secureSessionId = secureSessionId,
        controlSessionId = controlSessionId,
        role = role,
        localPeerInstanceId = local,
        remotePeerInstanceId = remote,
        protocolMinimumVersion = 1,
        protocolMaximumVersion = 2,
        nonce = nonce,
        certificateFingerprint = fingerprint,
        transport = TransportKind.LAN,
        securitySuiteWireId = securitySuiteWireId,
        tlsProtocol = tlsProtocol,
        tlsCipherSuite = tlsCipherSuite,
        maxRecordPlaintextBytes = 4096L,
        maxRecordsPerDirection = 4096L,
        maxBytesPerDirection = 16_777_216L,
        sessionLifetimeMillis = 300_000L,
        offeredFeaturesMask = offeredFeaturesMask,
        offeredMaxChunkSizeBytes = offeredMaxChunkSizeBytes,
        offeredResumeSupported = offeredResumeSupported,
        offeredEncryption = offeredEncryption,
        offeredAuthentication = offeredAuthentication,
        appVersion = "1.0.0",
        extensions = extensions,
    )

    private fun initiator(): PairingOfferV2 = offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer)
    private fun responder(): PairingOfferV2 = offer(SecurePeerRole.RESPONDER, responderPeer, initiatorPeer)

    /**
     * Selects and builds, returning the canonical transcript's SHA-256 digest, or null when
     * selection is rejected or the transcript would exceed its bound. `select` reports rejection as
     * a result rather than throwing, and `build` already returns the digest alongside the canonical
     * bytes, so neither is recomputed here.
     */
    private fun digestOf(initiatorOffer: PairingOfferV2, responderOffer: PairingOfferV2): ByteArray? {
        val selection = (SecurePairingSelectionV2.select(initiatorOffer, responderOffer)
            as? PairingSelectionResult.Selected)?.selection ?: return null
        return SecurePairingTranscriptV2.build(initiatorOffer, responderOffer, selection)?.second
    }

    @Test
    public fun `honest endpoints derive the same transcript and therefore the same proof input`() {
        val digestA = digestOf(initiator(), responder())
        val digestB = digestOf(initiator(), responder())
        assertTrue("an honest pairing must select and build", digestA != null && digestB != null)
        assertEquals(digestA!!.toList(), digestB!!.toList())
    }

    @Test
    public fun `every substitution makes the transcripts diverge or fails selection`() {
        // Each case models an attacker relaying between two honest endpoints and showing one of
        // them a different negotiated value than the other.
        val cases = linkedMapOf<String, () -> Pair<ByteArray?, ByteArray?>>(
            "peer identity substitution" to {
                digestOf(offer(SecurePeerRole.INITIATOR, attackerPeer, responderPeer), responder()) to
                    digestOf(initiator(), responder())
            },
            "control session id substitution" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, controlSessionId = SessionId("attempt-2")), responder()) to
                    digestOf(initiator(), responder())
            },
            "secure session id substitution" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, secureSessionId = SessionId("ffeeddccbbaa99887766554433221100")), responder()) to
                    digestOf(initiator(), responder())
            },
            "role swap" to {
                digestOf(offer(SecurePeerRole.RESPONDER, initiatorPeer, responderPeer), responder()) to
                    digestOf(initiator(), responder())
            },
            "certificate fingerprint substitution" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, fingerprint = range(0xA0, 32)), responder()) to
                    digestOf(initiator(), responder())
            },
            "capability downgrade" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, offeredFeaturesMask = 1L), responder()) to
                    digestOf(initiator(), responder())
            },
            "chunk size mutation" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, offeredMaxChunkSizeBytes = 1_024L), responder()) to
                    digestOf(initiator(), responder())
            },
            "resume mutation" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, offeredResumeSupported = false), responder()) to
                    digestOf(initiator(), responder())
            },
            "encryption capability mutation" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, offeredEncryption = EncryptionCapability.NONE), responder()) to
                    digestOf(initiator(), responder())
            },
            "authentication capability mutation" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, offeredAuthentication = PairingAuthenticationCapability.NONE), responder()) to
                    digestOf(initiator(), responder())
            },
            "security suite mutation" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, securitySuiteWireId = 2), responder()) to
                    digestOf(initiator(), responder())
            },
            "tls protocol mismatch" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, tlsProtocol = "TLSv1.2"), responder()) to
                    digestOf(initiator(), responder())
            },
            "tls cipher mismatch" to {
                digestOf(offer(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, tlsCipherSuite = "TLS_AES_256_GCM_SHA384"), responder()) to
                    digestOf(initiator(), responder())
            },
        )

        var diverged = 0
        var rejected = 0
        for ((label, produce) in cases) {
            val (attackerView, honestView) = produce()
            assertTrue("$label: the honest leg must still build", honestView != null)
            if (attackerView == null) {
                rejected += 1
                continue
            }
            assertNotEquals(
                "$label: a substituted negotiation must not produce the honest transcript",
                honestView!!.toList(),
                attackerView.toList(),
            )
            diverged += 1
        }
        assertEquals("every substitution must be accounted for", cases.size, diverged + rejected)
        assertTrue("at least one substitution must diverge rather than merely being rejected", diverged > 0)
    }

    @Test
    public fun `the extension dimension is closed by the type, not by a runtime check`() {
        // PairingExtensionV2 is an enum with exactly one reserved entry, so a caller cannot
        // construct an offer carrying an unknown or attacker-defined extension at all -- the
        // dimension is closed by the type system rather than by validation. The wire-level case is
        // the one that matters and is covered in SecurePairingWireV2Test, which hand-rolls frames
        // with unknown tags: the codec has no skip-unknown path, so an unrecognised tag is rejected
        // rather than ignored. Recording that split here so the absence of a case above is not
        // mistaken for an omission.
        assertEquals(1, PairingExtensionV2.entries.size)
        assertEquals(0x0000, PairingExtensionV2.RESERVED_ZERO.tag)
        assertTrue(
            "an unrecognised tag must not resolve to a known extension",
            PairingExtensionV2.fromTag(0x7FFF) == null,
        )
    }
}
