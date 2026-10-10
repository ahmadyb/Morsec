package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPS2 / MST2 tests.
 *
 * The transcript vector below was produced by an independent implementation written from the
 * format description, not by running this codec. If the encoder and the expected digest ever
 * disagree, one of them is wrong; regenerating the expectation from the implementation would
 * make this test unable to fail.
 */
public class SecurePairingWireV2Test {

    // Tags, restated as literals so the test does not reach into private codec state.
    private val tagSecureId = 0x0001
    private val tagControlId = 0x0002
    private val tagRole = 0x0003
    private val tagLocalPeer = 0x0004
    private val tagRemotePeer = 0x0005
    private val tagProtoMin = 0x0006
    private val tagProtoMax = 0x0007
    private val tagNonce = 0x0008
    private val tagFingerprint = 0x0009
    private val tagTransport = 0x000A
    private val tagSuite = 0x000B
    private val tagTlsProtocol = 0x000C
    private val tagTlsCipher = 0x000D
    private val tagRecordMax = 0x000E
    private val tagRecords = 0x000F
    private val tagBytes = 0x0010
    private val tagLifetime = 0x0011
    private val tagOfferedFeatures = 0x0101
    private val tagOfferedChunk = 0x0102
    private val tagOfferedResume = 0x0103
    private val tagOfferedEncryption = 0x0104
    private val tagOfferedAuth = 0x0105
    private val tagAppVersion = 0x0106

    private val secureSessionId = SessionId("000102030405060708090a0b0c0d0e0f")
    private val controlSessionId = SessionId("101112131415161718191a1b1c1d1e1f")
    private val initiatorPeer = PeerInstanceId("0".repeat(31) + "1")
    private val responderPeer = PeerInstanceId("0".repeat(31) + "2")

    private fun range(start: Int, count: Int): ByteArray =
        ByteArray(count) { ((start + it) and 0xFF).toByte() }

    private fun initiatorOffer(): PairingOfferV2 = PairingOfferV2(
        secureSessionId = secureSessionId,
        controlSessionId = controlSessionId,
        role = SecurePeerRole.INITIATOR,
        localPeerInstanceId = initiatorPeer,
        remotePeerInstanceId = responderPeer,
        protocolMinimumVersion = 1,
        protocolMaximumVersion = 2,
        nonce = range(0x00, 32),
        certificateFingerprint = range(0x20, 32),
        transport = TransportKind.LAN,
        securitySuiteWireId = 1,
        tlsProtocol = "TLSv1.3",
        tlsCipherSuite = "TLS_AES_128_GCM_SHA256",
        maxRecordPlaintextBytes = 4096L,
        maxRecordsPerDirection = 4096L,
        maxBytesPerDirection = 16_777_216L,
        sessionLifetimeMillis = 300_000L,
        offeredFeaturesMask = 9,
        offeredMaxChunkSizeBytes = 65_536L,
        offeredResumeSupported = true,
        offeredEncryption = EncryptionCapability.TLS_1_3,
        offeredAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        appVersion = "1.0.0",
        extensions = emptyList(),
    )

    private fun responderOffer(): PairingOfferV2 = PairingOfferV2(
        secureSessionId = secureSessionId,
        controlSessionId = controlSessionId,
        role = SecurePeerRole.RESPONDER,
        localPeerInstanceId = responderPeer,
        remotePeerInstanceId = initiatorPeer,
        protocolMinimumVersion = 1,
        protocolMaximumVersion = 2,
        nonce = range(0x40, 32),
        certificateFingerprint = range(0x60, 32),
        transport = TransportKind.LAN,
        securitySuiteWireId = 1,
        tlsProtocol = "TLSv1.3",
        tlsCipherSuite = "TLS_AES_128_GCM_SHA256",
        maxRecordPlaintextBytes = 4096L,
        maxRecordsPerDirection = 4096L,
        maxBytesPerDirection = 16_777_216L,
        sessionLifetimeMillis = 300_000L,
        offeredFeaturesMask = 9,
        offeredMaxChunkSizeBytes = 32_768L,
        offeredResumeSupported = false,
        offeredEncryption = EncryptionCapability.TLS_1_3,
        offeredAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        appVersion = "1.0.0",
        extensions = emptyList(),
    )

    private fun transcriptOf(
        initiator: PairingOfferV2 = initiatorOffer(),
        responder: PairingOfferV2 = responderOffer(),
    ): Pair<ByteArray, ByteArray>? {
        val selection = SecurePairingSelectionV2.select(initiator, responder)
            as? PairingSelectionResult.Selected ?: return null
        return SecurePairingTranscriptV2.build(initiator, responder, selection.selection)
    }

    private fun digestOf(
        initiator: PairingOfferV2 = initiatorOffer(),
        responder: PairingOfferV2 = responderOffer(),
    ): String? = transcriptOf(initiator, responder)?.second?.joinToString("") {
        "%02x".format(it)
    }

    /** Assembles a frame by hand so malformed input is expressible, which encode() cannot do. */
    private fun frame(fields: List<Pair<Int, ByteArray>>, mask: Int, magic: ByteArray = mps2): ByteArray {
        var size = 7
        for ((_, value) in fields) size += 4 + value.size
        size += 4
        val out = ByteArray(size)
        magic.copyInto(out, 0)
        out[4] = 2
        out[5] = ((mask ushr 8) and 0xFF).toByte()
        out[6] = (mask and 0xFF).toByte()
        var offset = 7
        for ((tag, value) in fields) {
            out[offset] = ((tag ushr 8) and 0xFF).toByte()
            out[offset + 1] = (tag and 0xFF).toByte()
            out[offset + 2] = ((value.size ushr 8) and 0xFF).toByte()
            out[offset + 3] = (value.size and 0xFF).toByte()
            value.copyInto(out, offset + 4)
            offset += 4 + value.size
        }
        // END tag, zero length.
        out[offset + 2] = 0
        out[offset + 3] = 0
        return out
    }

    private val mps2 = byteArrayOf(0x4D, 0x50, 0x53, 0x32)
    private val mps1 = byteArrayOf(0x4D, 0x50, 0x53, 0x31)

    private fun validFields(): List<Pair<Int, ByteArray>> = listOf(
        tagSecureId to range(0x00, 16),
        tagControlId to range(0x10, 16),
        tagRole to byteArrayOf(1),
        tagLocalPeer to initiatorPeer.value.toByteArray(Charsets.UTF_8),
        tagRemotePeer to responderPeer.value.toByteArray(Charsets.UTF_8),
        tagProtoMin to byteArrayOf(0, 1),
        tagProtoMax to byteArrayOf(0, 2),
        tagNonce to range(0x00, 32),
        tagFingerprint to range(0x20, 32),
        tagTransport to "lan".toByteArray(Charsets.UTF_8),
        tagSuite to byteArrayOf(0, 1),
        tagTlsProtocol to "TLSv1.3".toByteArray(Charsets.UTF_8),
        tagTlsCipher to "TLS_AES_128_GCM_SHA256".toByteArray(Charsets.UTF_8),
        tagRecordMax to byteArrayOf(0, 0, 0x10, 0x00),
        tagRecords to byteArrayOf(0, 0, 0x10, 0x00),
        tagBytes to byteArrayOf(0x01, 0x00, 0x00, 0x00),
        tagLifetime to byteArrayOf(0x00, 0x04, 0x93.toByte(), 0xE0.toByte()),
        tagOfferedFeatures to byteArrayOf(0, 9),
        tagOfferedChunk to byteArrayOf(0, 1, 0, 0),
        tagOfferedResume to byteArrayOf(1),
        tagOfferedEncryption to byteArrayOf(1),
        tagOfferedAuth to byteArrayOf(1),
        tagAppVersion to "1.0.0".toByteArray(Charsets.UTF_8),
    )

    /** All six defined optional bits set; the extension bit is clear because none are present. */
    private val fullMask = 0b0000_0000_0011_1111

    // --- the independent vector -------------------------------------------------------

    @Test
    public fun transcriptMatchesTheIndependentlyComputedVector() {
        val transcript = transcriptOf()
        assertNotNull("transcript should build for a valid offer pair", transcript)
        assertEquals(578, transcript!!.first.size)
        assertEquals(
            "digest must equal the value computed by the independent implementation",
            "685b681c96edf8f169c88012a29e25492d39c016cc8987e64107e3aff6a43c64",
            transcript.second.joinToString("") { "%02x".format(it) },
        )
        // The digest really is SHA-256 over the canonical bytes, not a separately kept value.
        assertEquals(
            transcript.second.toList(),
            MessageDigest.getInstance("SHA-256").digest(transcript.first).toList(),
        )
    }

    // --- hello framing ----------------------------------------------------------------

    @Test
    public fun helloRoundTripsInsideItsFrameBound() {
        val encoded = SecurePairingHelloV2Codec.encode(initiatorOffer())
        assertTrue(encoded.size <= MAX_PAIRING_V2_FRAME_BYTES)
        val decoded = SecurePairingHelloV2Codec.decode(encoded) as PairingHelloV2Result.Success
        assertEquals(SecurePeerRole.INITIATOR, decoded.offer.role)
        assertEquals(9, decoded.offer.offeredFeaturesMask)
        assertEquals(65_536L, decoded.offer.offeredMaxChunkSizeBytes)
        assertEquals(true, decoded.offer.offeredResumeSupported)
        assertEquals(EncryptionCapability.TLS_1_3, decoded.offer.offeredEncryption)
        assertEquals("1.0.0", decoded.offer.appVersion)
        assertEquals(initiatorOffer().presenceMask(), decoded.offer.presenceMask())
    }

    @Test
    public fun legacyMps1FrameFailsClosedAndIsNotMisparsed() {
        val result = SecurePairingHelloV2Codec.decode(frame(validFields(), fullMask, mps1))
        assertEquals(
            "an MPS1 peer must be reported as a version problem, never retried in plaintext",
            PairingWireV2Reject.LEGACY_MPS1_UNSUPPORTED,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun unsupportedPresenceBitIsRejected() {
        val result = SecurePairingHelloV2Codec.decode(frame(validFields(), fullMask or (1 shl 15)))
        assertEquals(
            PairingWireV2Reject.UNSUPPORTED_PRESENCE_BIT,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun presenceMaskThatDisagreesWithTheFrameIsRejected() {
        // Claims the extension bit but carries no extension field.
        val result = SecurePairingHelloV2Codec.decode(frame(validFields(), fullMask or (1 shl 6)))
        assertEquals(
            PairingWireV2Reject.PRESENCE_MASK_MISMATCH,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun duplicateFieldIsRejected() {
        val fields = validFields() + (tagOfferedFeatures to byteArrayOf(0, 3))
        val result = SecurePairingHelloV2Codec.decode(frame(fields.sortedBy { it.first }, fullMask))
        assertEquals(
            PairingWireV2Reject.DUPLICATE_FIELD,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun missingMandatoryFieldIsRejected() {
        val fields = validFields().filterNot { it.first == tagFingerprint }
        val result = SecurePairingHelloV2Codec.decode(frame(fields, fullMask))
        assertEquals(
            PairingWireV2Reject.MISSING_MANDATORY_FIELD,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun fieldsOutOfCanonicalOrderAreRejected() {
        val swapped = validFields().toMutableList()
        // Swap the first two entries so tags descend.
        val first = swapped[0]
        swapped[0] = swapped[1]
        swapped[1] = first
        val result = SecurePairingHelloV2Codec.decode(frame(swapped, fullMask))
        assertEquals(
            PairingWireV2Reject.FIELD_ORDER_NOT_CANONICAL,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun unknownTagIsRejectedRatherThanSkipped() {
        val fields = (validFields() + (0x0108 to byteArrayOf(1))).sortedBy { it.first }
        val result = SecurePairingHelloV2Codec.decode(frame(fields, fullMask))
        assertEquals(
            PairingWireV2Reject.UNKNOWN_FIELD,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun trailingBytesAfterTheEndTagAreRejected() {
        val result = SecurePairingHelloV2Codec.decode(frame(validFields(), fullMask) + byteArrayOf(0))
        assertEquals(
            PairingWireV2Reject.TRAILING_BYTES,
            (result as PairingHelloV2Result.Rejected).reason,
        )
    }

    @Test
    public fun explicitZeroIsDistinctFromAbsent() {
        // Advertise resume support as an explicit false, then omit the field entirely. The two
        // must encode differently and transcribe differently; collapsing them is the bug this
        // distinction exists to prevent.
        val explicitFalse = initiatorOffer()
        val absent = PairingOfferV2(
            secureSessionId = secureSessionId,
            controlSessionId = controlSessionId,
            role = SecurePeerRole.INITIATOR,
            localPeerInstanceId = initiatorPeer,
            remotePeerInstanceId = responderPeer,
            protocolMinimumVersion = 1,
            protocolMaximumVersion = 2,
            nonce = range(0x00, 32),
            certificateFingerprint = range(0x20, 32),
            transport = TransportKind.LAN,
            securitySuiteWireId = 1,
            tlsProtocol = "TLSv1.3",
            tlsCipherSuite = "TLS_AES_128_GCM_SHA256",
            maxRecordPlaintextBytes = 4096L,
            maxRecordsPerDirection = 4096L,
            maxBytesPerDirection = 16_777_216L,
            sessionLifetimeMillis = 300_000L,
            offeredFeaturesMask = 9,
            offeredMaxChunkSizeBytes = 65_536L,
            offeredResumeSupported = null,
            offeredEncryption = EncryptionCapability.TLS_1_3,
            offeredAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            appVersion = "1.0.0",
            extensions = emptyList(),
        )
        assertNotEquals(explicitFalse.presenceMask(), absent.presenceMask())
        assertNotEquals(
            SecurePairingHelloV2Codec.encode(explicitFalse).toList(),
            SecurePairingHelloV2Codec.encode(absent).toList(),
        )
        val withFalse = PairingOfferV2(
            secureSessionId = secureSessionId,
            controlSessionId = controlSessionId,
            role = SecurePeerRole.INITIATOR,
            localPeerInstanceId = initiatorPeer,
            remotePeerInstanceId = responderPeer,
            protocolMinimumVersion = 1,
            protocolMaximumVersion = 2,
            nonce = range(0x00, 32),
            certificateFingerprint = range(0x20, 32),
            transport = TransportKind.LAN,
            securitySuiteWireId = 1,
            tlsProtocol = "TLSv1.3",
            tlsCipherSuite = "TLS_AES_128_GCM_SHA256",
            maxRecordPlaintextBytes = 4096L,
            maxRecordsPerDirection = 4096L,
            maxBytesPerDirection = 16_777_216L,
            sessionLifetimeMillis = 300_000L,
            offeredFeaturesMask = 9,
            offeredMaxChunkSizeBytes = 65_536L,
            offeredResumeSupported = false,
            offeredEncryption = EncryptionCapability.TLS_1_3,
            offeredAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            appVersion = "1.0.0",
            extensions = emptyList(),
        )
        // Same presence mask, different transcript from the absent case: zero is bound as zero.
        assertEquals(absent.presenceMask() or (1 shl 2), withFalse.presenceMask())
        assertNotEquals(digestOf(withFalse), digestOf(absent))
    }

    // --- mutation coverage -------------------------------------------------------------

    @Test
    public fun everySecurityRelevantMutationChangesTheTranscriptOrIsRejected() {
        val baseline = digestOf()
        assertNotNull(baseline)

        val mutations: List<Pair<String, PairingOfferV2>> = listOf(
            "protocol minimum" to mutated { it.copy(protocolMinimumVersion = 2) },
            "protocol maximum" to mutated { it.copy(protocolMaximumVersion = 1) },
            "nonce" to mutated { it.copy(nonce = range(0x01, 32)) },
            "certificate fingerprint" to mutated { it.copy(certificateFingerprint = range(0x21, 32)) },
            "transport" to mutated { it.copy(transport = TransportKind.NEARBY) },
            "security suite" to mutated { it.copy(securitySuiteWireId = 2) },
            "tls protocol" to mutated { it.copy(tlsProtocol = "TLSv1.2") },
            "tls cipher suite" to mutated { it.copy(tlsCipherSuite = "TLS_AES_256_GCM_SHA384") },
            "record plaintext limit" to mutated { it.copy(maxRecordPlaintextBytes = 2048L) },
            "records per direction" to mutated { it.copy(maxRecordsPerDirection = 1L) },
            "bytes per direction" to mutated { it.copy(maxBytesPerDirection = 1L) },
            "session lifetime" to mutated { it.copy(sessionLifetimeMillis = 1000L) },
            "offered features" to mutated { it.copy(offeredFeaturesMask = 8) },
            "offered chunk size" to mutated { it.copy(offeredMaxChunkSizeBytes = 1L) },
            "offered resume" to mutated { it.copy(offeredResumeSupported = false) },
            "offered encryption" to mutated { it.copy(offeredEncryption = EncryptionCapability.NONE) },
            "offered authentication" to mutated {
                it.copy(offeredAuthentication = PairingAuthenticationCapability.NONE)
            },
            "app version" to mutated { it.copy(appVersion = "9.9.9") },
            "local peer id" to mutated { it.copy(localPeerInstanceId = PeerInstanceId("0".repeat(31) + "3")) },
            "remote peer id" to mutated { it.copy(remotePeerInstanceId = PeerInstanceId("0".repeat(31) + "4")) },
            "secure session id" to mutated {
                it.copy(secureSessionId = SessionId("f00102030405060708090a0b0c0d0e0f"))
            },
            "control session id" to mutated {
                it.copy(controlSessionId = SessionId("e01112131415161718191a1b1c1d1e1f"))
            },
        )

        for ((label, mutatedOffer) in mutations) {
            val changed = digestOf(initiator = mutatedOffer)
            assertTrue(
                "$label must either change the transcript or be rejected outright",
                changed == null || changed != baseline,
            )
        }
    }

    /** PairingOfferV2 has no copy(); rebuild it field by field from a template. */
    private fun mutated(transform: (Template) -> Template): PairingOfferV2 {
        val t = transform(Template())
        return PairingOfferV2(
            secureSessionId = t.secureSessionId,
            controlSessionId = t.controlSessionId,
            role = SecurePeerRole.INITIATOR,
            localPeerInstanceId = t.localPeerInstanceId,
            remotePeerInstanceId = t.remotePeerInstanceId,
            protocolMinimumVersion = t.protocolMinimumVersion,
            protocolMaximumVersion = t.protocolMaximumVersion,
            nonce = t.nonce,
            certificateFingerprint = t.certificateFingerprint,
            transport = t.transport,
            securitySuiteWireId = t.securitySuiteWireId,
            tlsProtocol = t.tlsProtocol,
            tlsCipherSuite = t.tlsCipherSuite,
            maxRecordPlaintextBytes = t.maxRecordPlaintextBytes,
            maxRecordsPerDirection = t.maxRecordsPerDirection,
            maxBytesPerDirection = t.maxBytesPerDirection,
            sessionLifetimeMillis = t.sessionLifetimeMillis,
            offeredFeaturesMask = t.offeredFeaturesMask,
            offeredMaxChunkSizeBytes = t.offeredMaxChunkSizeBytes,
            offeredResumeSupported = t.offeredResumeSupported,
            offeredEncryption = t.offeredEncryption,
            offeredAuthentication = t.offeredAuthentication,
            appVersion = t.appVersion,
            extensions = emptyList(),
        )
    }

    private data class Template(
        val secureSessionId: SessionId = SessionId("000102030405060708090a0b0c0d0e0f"),
        val controlSessionId: SessionId = SessionId("101112131415161718191a1b1c1d1e1f"),
        val localPeerInstanceId: PeerInstanceId = PeerInstanceId("0".repeat(31) + "1"),
        val remotePeerInstanceId: PeerInstanceId = PeerInstanceId("0".repeat(31) + "2"),
        val protocolMinimumVersion: Int = 1,
        val protocolMaximumVersion: Int = 2,
        val nonce: ByteArray = ByteArray(32) { (it and 0xFF).toByte() },
        val certificateFingerprint: ByteArray = ByteArray(32) { ((0x20 + it) and 0xFF).toByte() },
        val transport: TransportKind = TransportKind.LAN,
        val securitySuiteWireId: Int = 1,
        val tlsProtocol: String = "TLSv1.3",
        val tlsCipherSuite: String = "TLS_AES_128_GCM_SHA256",
        val maxRecordPlaintextBytes: Long = 4096L,
        val maxRecordsPerDirection: Long = 4096L,
        val maxBytesPerDirection: Long = 16_777_216L,
        val sessionLifetimeMillis: Long = 300_000L,
        val offeredFeaturesMask: Int = 9,
        val offeredMaxChunkSizeBytes: Long = 65_536L,
        val offeredResumeSupported: Boolean = true,
        val offeredEncryption: EncryptionCapability = EncryptionCapability.TLS_1_3,
        val offeredAuthentication: PairingAuthenticationCapability =
            PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
        val appVersion: String = "1.0.0",
    )

    // --- selection is an intersection, never an assertion -------------------------------

    @Test
    public fun disjointProtocolRangesAreRejected() {
        val narrowed = mutated { it.copy(protocolMinimumVersion = 2, protocolMaximumVersion = 2) }
        val responder = PairingOfferV2(
            secureSessionId = secureSessionId,
            controlSessionId = controlSessionId,
            role = SecurePeerRole.RESPONDER,
            localPeerInstanceId = responderPeer,
            remotePeerInstanceId = initiatorPeer,
            protocolMinimumVersion = 1,
            protocolMaximumVersion = 1,
            nonce = range(0x40, 32),
            certificateFingerprint = range(0x60, 32),
            transport = TransportKind.LAN,
            securitySuiteWireId = 1,
            tlsProtocol = "TLSv1.3",
            tlsCipherSuite = "TLS_AES_128_GCM_SHA256",
            maxRecordPlaintextBytes = 4096L,
            maxRecordsPerDirection = 4096L,
            maxBytesPerDirection = 16_777_216L,
            sessionLifetimeMillis = 300_000L,
            offeredFeaturesMask = 9,
            offeredMaxChunkSizeBytes = 32_768L,
            offeredResumeSupported = false,
            offeredEncryption = EncryptionCapability.TLS_1_3,
            offeredAuthentication = PairingAuthenticationCapability.SAS_25BIT_COMPARISON,
            appVersion = "1.0.0",
            extensions = emptyList(),
        )
        val result = SecurePairingSelectionV2.select(narrowed, responder)
        assertEquals(
            PairingSelectionReject.PROTOCOL_RANGES_DISJOINT,
            (result as PairingSelectionResult.Rejected).reason,
        )
    }

    @Test
    public fun swappedRolesAreRejected() {
        // Both peers claiming INITIATOR must not produce a selection.
        val result = SecurePairingSelectionV2.select(initiatorOffer(), initiatorOffer())
        assertEquals(
            PairingSelectionReject.ROLE_NOT_OPPOSED,
            (result as PairingSelectionResult.Rejected).reason,
        )
    }

    @Test
    public fun peerIdsThatAreNotReflectedAreRejected() {
        val stranger = PeerInstanceId("0".repeat(31) + "9")
        val lying = mutated { it.copy(remotePeerInstanceId = stranger) }
        val result = SecurePairingSelectionV2.select(lying, responderOffer())
        assertEquals(
            PairingSelectionReject.PEER_ID_NOT_REFLECTED,
            (result as PairingSelectionResult.Rejected).reason,
        )
    }

    @Test
    public fun mismatchedSessionIdsAreRejected() {
        val other = mutated { it.copy(secureSessionId = SessionId("f00102030405060708090a0b0c0d0e0f")) }
        val result = SecurePairingSelectionV2.select(other, responderOffer())
        assertEquals(
            PairingSelectionReject.SESSION_ID_MISMATCH,
            (result as PairingSelectionResult.Rejected).reason,
        )
    }

    @Test
    public fun emptyFeatureIntersectionIsRejected() {
        val disjoint = mutated { it.copy(offeredFeaturesMask = 0b0010) }
        val responder = mutatedResponder { it.copy(offeredFeaturesMask = 0b0100) }
        val result = SecurePairingSelectionV2.select(disjoint, responder)
        assertEquals(
            PairingSelectionReject.FEATURE_INTERSECTION_EMPTY,
            (result as PairingSelectionResult.Rejected).reason,
        )
    }

    @Test
    public fun nonMutualEncryptionIsRejected() {
        val plaintext = mutated { it.copy(offeredEncryption = EncryptionCapability.NONE) }
        val result = SecurePairingSelectionV2.select(plaintext, responderOffer())
        assertEquals(
            PairingSelectionReject.ENCRYPTION_NOT_MUTUALLY_SUPPORTED,
            (result as PairingSelectionResult.Rejected).reason,
        )
    }

    @Test
    public fun nonMutualAuthenticationIsRejected() {
        val unauthenticated = mutated {
            it.copy(offeredAuthentication = PairingAuthenticationCapability.NONE)
        }
        val result = SecurePairingSelectionV2.select(unauthenticated, responderOffer())
        assertEquals(
            PairingSelectionReject.AUTHENTICATION_NOT_MUTUALLY_SUPPORTED,
            (result as PairingSelectionResult.Rejected).reason,
        )
    }

    @Test
    public fun selectionIsTheIntersectionOfBothOffers() {
        val selected = SecurePairingSelectionV2.select(initiatorOffer(), responderOffer())
            as PairingSelectionResult.Selected
        assertEquals(2, selected.selection.selectedProtocolVersion)
        assertEquals(9, selected.selection.selectedFeaturesMask)
        assertEquals(32_768L, selected.selection.selectedMaxChunkSizeBytes)
        assertEquals(false, selected.selection.selectedResumeSupported)
        assertEquals(EncryptionCapability.TLS_1_3, selected.selection.selectedEncryption)
    }

    private fun mutatedResponder(transform: (Template) -> Template): PairingOfferV2 {
        val t = transform(Template())
        return PairingOfferV2(
            secureSessionId = t.secureSessionId,
            controlSessionId = t.controlSessionId,
            role = SecurePeerRole.RESPONDER,
            localPeerInstanceId = t.remotePeerInstanceId,
            remotePeerInstanceId = t.localPeerInstanceId,
            protocolMinimumVersion = t.protocolMinimumVersion,
            protocolMaximumVersion = t.protocolMaximumVersion,
            nonce = range(0x40, 32),
            certificateFingerprint = range(0x60, 32),
            transport = t.transport,
            securitySuiteWireId = t.securitySuiteWireId,
            tlsProtocol = t.tlsProtocol,
            tlsCipherSuite = t.tlsCipherSuite,
            maxRecordPlaintextBytes = t.maxRecordPlaintextBytes,
            maxRecordsPerDirection = t.maxRecordsPerDirection,
            maxBytesPerDirection = t.maxBytesPerDirection,
            sessionLifetimeMillis = t.sessionLifetimeMillis,
            offeredFeaturesMask = t.offeredFeaturesMask,
            offeredMaxChunkSizeBytes = 32_768L,
            offeredResumeSupported = false,
            offeredEncryption = t.offeredEncryption,
            offeredAuthentication = t.offeredAuthentication,
            appVersion = t.appVersion,
            extensions = emptyList(),
        )
    }

    @Test
    public fun anOverLongFieldIsRefusedAtConstructionRatherThanByBufferOverflow() {
        // The bound is enforced where the value enters, so the encoder can never be handed
        // something it would have to truncate or overflow to represent.
        val thrown = runCatching {
            mutated { it.copy(appVersion = "9".repeat(64)) }
        }.exceptionOrNull()
        assertNotNull("a 64-character app version exceeds its 32-byte bound", thrown)
        assertTrue(thrown is IllegalArgumentException)
    }
}
