package app.morsecode.core.transfer.session

import app.morsecode.core.transfer.identity.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class SecurePairingCodecTest {
    private val secureSessionId = SessionId("000102030405060708090a0b0c0d0e0f")
    private val controlSessionId = SessionId("attempt-1")
    private val initiatorPeer = PeerInstanceId("00000000000000000000000000000001")
    private val responderPeer = PeerInstanceId("00000000000000000000000000000002")

    @Test
    public fun secureHelloHasAStableBoundedRoundTrip() {
        val hello = hello(
            role = SecurePeerRole.INITIATOR,
            local = initiatorPeer,
            remote = responderPeer,
            appVersion = "1.2.3",
            nonceStart = 0,
            fingerprintStart = 32,
        )
        val encoded = SecurePairingHelloCodec.encode(hello)

        assertTrue(encoded.size <= SecurePairingHelloCodec.MAX_FRAME_SIZE_BYTES)
        val decoded = SecurePairingHelloCodec.decode(encoded) as SecurePairingHelloDecodeResult.Success
        assertEquals(hello.secureSessionId, decoded.hello.secureSessionId)
        assertEquals(hello.controlSessionId, decoded.hello.controlSessionId)
        assertEquals(hello.role, decoded.hello.role)
        assertEquals(hello.localPeerInstanceId, decoded.hello.localPeerInstanceId)
        assertEquals(hello.remotePeerInstanceId, decoded.hello.remotePeerInstanceId)
        assertEquals(hello.appVersion, decoded.hello.appVersion)
        assertEquals(hello.selectedProtocolVersion, decoded.hello.selectedProtocolVersion)
        assertEquals(hello.protocolMinimumVersion, decoded.hello.protocolMinimumVersion)
        assertEquals(hello.protocolMaximumVersion, decoded.hello.protocolMaximumVersion)
        val expectedNonce = hello.nonceBytes()
        val actualNonce = decoded.hello.nonceBytes()
        val expectedFingerprint = hello.certificateFingerprintBytes()
        val actualFingerprint = decoded.hello.certificateFingerprintBytes()
        try {
            assertTrue(expectedNonce.contentEquals(actualNonce))
            assertTrue(expectedFingerprint.contentEquals(actualFingerprint))
        } finally {
            expectedNonce.fill(0)
            actualNonce.fill(0)
            expectedFingerprint.fill(0)
            actualFingerprint.fill(0)
            encoded.fill(0)
            decoded.hello.clearSensitive()
            hello.clearSensitive()
        }
    }

    @Test
    public fun unknownVersionsSuitesFeaturesAndTrailingBytesFailClosed() {
        val encoded = SecurePairingHelloCodec.encode(
            hello(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, "1.2.3", 0, 32),
        )
        assertInvalid(encoded.copyOf().also { it[4] = 2 }) // unknown secure-wire version
        assertInvalid(encoded.copyOf().also { it[5] = 2 }) // unknown mandatory message type
        assertInvalid(encoded.copyOf().also { it[66] = 7 }) // unknown role
        assertInvalid(encoded.copyOf().also { it[67] = 0; it[68] = 2 }) // unnegotiated control revision
        listOf(73, 74, 75, 76, 77, 78).forEach { index ->
            assertInvalid(encoded.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() })
        } // discovery/control/secure/capability revisions, transport, suite
        assertInvalid(encoded.copyOf().also { it[85] = 0; it[86] = 1 }) // downgrade feature mask
        assertInvalid(encoded.copyOf().also { it[91] = (it[91].toInt() xor 1).toByte() }) // record-count limit
        assertInvalid(encoded.copyOf().also { it[95] = (it[95].toInt() xor 1).toByte() }) // byte limit
        assertInvalid(encoded.copyOf().also { it[99] = (it[99].toInt() xor 1).toByte() }) // lifetime limit
        assertInvalid(encoded + byteArrayOf(0)) // inconsistent declared length
        val unknownTrailingField = encoded.copyOf(encoded.size + 1).also {
            val payloadLength = it.size - SecurePairingHelloCodec.HEADER_SIZE_BYTES
            it[it.lastIndex] = 0
            it[6] = (payloadLength ushr 8).toByte()
            it[7] = payloadLength.toByte()
        }
        assertInvalid(unknownTrailingField)
        assertInvalid(encoded.copyOfRange(0, encoded.size - 1))

        val oversizedHeader = encoded.copyOfRange(0, SecurePairingHelloCodec.HEADER_SIZE_BYTES).also {
            it[6] = 0x02
            it[7] = 0x00
        }
        assertTrue(SecurePairingHelloCodec.decodeHeader(oversizedHeader) is SecurePairingHelloHeaderResult.Invalid)
        encoded.fill(0)
    }

    @Test
    public fun transcriptVectorBindsRolesProtocolRangesAndEverySessionField() {
        val initiator = hello(
            SecurePeerRole.INITIATOR,
            initiatorPeer,
            responderPeer,
            "1.2.3",
            nonceStart = 0,
            fingerprintStart = 32,
        )
        val responder = hello(
            SecurePeerRole.RESPONDER,
            responderPeer,
            initiatorPeer,
            "2.0",
            nonceStart = 64,
            fingerprintStart = 96,
        )
        val transcript = requireNotNull(
            SecurePairingTranscript.create(
                initiator,
                responder,
                "TLSv1.3",
                "TLS_AES_128_GCM_SHA256",
            ),
        )
        val encoded = transcript.encodedBytes()
        val digest = transcript.digestBytes()
        try {
            assertEquals(266, encoded.size)
            assertEquals(
                "d68934743d5ca100050ee79416c502015faf8ac24752db86d1eaa0c845e3c4a1",
                digest.toHex(),
            )
            assertNull(SecurePairingTranscript.create(initiator, responder, "TLSv1.2", "TLS_AES_128_GCM_SHA256"))
            assertNull(SecurePairingTranscript.create(initiator, responder, "TLSv1.3", "unknown-suite"))
            assertFalse(transcript.toString().contains(digest.toHex()))
        } finally {
            encoded.fill(0)
            digest.fill(0)
            transcript.clearSensitive()
            initiator.clearSensitive()
            responder.clearSensitive()
        }
    }

    @Test
    public fun helloRejectsSelectedVersionOutsideItsAdvertisedRange() {
        assertThrows(IllegalArgumentException::class.java) {
            hello(SecurePeerRole.INITIATOR, initiatorPeer, responderPeer, "1.2.3", 0, 32, selected = 2)
        }
    }

    @Test
    public fun transcriptBindsDifferentAdvertisedRangesAroundTheSharedSelection() {
        val initiator = hello(
            SecurePeerRole.INITIATOR,
            initiatorPeer,
            responderPeer,
            "1.2.3",
            nonceStart = 0,
            fingerprintStart = 32,
            selected = 2,
            minimum = 1,
            maximum = 3,
        )
        val responder = hello(
            SecurePeerRole.RESPONDER,
            responderPeer,
            initiatorPeer,
            "2.0",
            nonceStart = 64,
            fingerprintStart = 96,
            selected = 2,
            minimum = 2,
            maximum = 5,
        )
        val transcript = requireNotNull(
            SecurePairingTranscript.create(initiator, responder, "TLSv1.3", "TLS_AES_128_GCM_SHA256"),
        )
        val otherResponder = hello(
            SecurePeerRole.RESPONDER,
            responderPeer,
            initiatorPeer,
            "2.0",
            nonceStart = 64,
            fingerprintStart = 96,
            selected = 2,
            minimum = 2,
            maximum = 6,
        )
        val otherTranscript = requireNotNull(
            SecurePairingTranscript.create(initiator, otherResponder, "TLSv1.3", "TLS_AES_128_GCM_SHA256"),
        )
        val bytes = transcript.digestBytes()
        val otherBytes = otherTranscript.digestBytes()
        try {
            assertTrue(bytes.any { it != 0.toByte() })
            assertFalse(bytes.contentEquals(otherBytes))
        } finally {
            bytes.fill(0)
            otherBytes.fill(0)
            transcript.clearSensitive()
            otherTranscript.clearSensitive()
            initiator.clearSensitive()
            responder.clearSensitive()
            otherResponder.clearSensitive()
        }
    }

    @Test
    public fun shortAuthenticationCodeUsesExporterBitsAndWipesInput() {
        val exporter = ByteArray(32).also {
            it[0] = 0xAB.toByte()
            it[1] = 0xCD.toByte()
            it[2] = 0xE0.toByte()
        }
        val proof = HumanVerificationCode.fromExporterMaterial(exporter)

        // AB CD E0 00 -> 10101011 11001101 11100000 0 -> 10101 01111 00110 11100 00010
        // indices 21, 15, 6, 28, 2 -> P H 8 Y 2. Derived by hand, not pasted from a run.
        assertEquals("PH8Y2", proof.displayText())
        assertTrue(exporter.all { it == 0.toByte() })
        assertEquals("HumanVerificationCode([redacted])", proof.toString())
        proof.clearSensitive()
        assertEquals("", proof.displayText())

        val zeroProof = HumanVerificationCode.fromExporterMaterial(ByteArray(32))
        // All-zero exporter material maps to value 0, which is alphabet[0] repeated.
        assertEquals("22222", zeroProof.displayText())
        zeroProof.clearSensitive()
    }

    @Test
    public fun keyConfirmationCodecHasFixedRoleAndTranscriptBoundLayout() {
        val digest = ByteArray(32) { it.toByte() }
        val message = SecureKeyConfirmation(SecurePeerRole.INITIATOR, digest)
        val encoded = SecureKeyConfirmationCodec.encode(message)
        digest.fill(0)
        message.clearSensitive()
        try {
            assertEquals(38, encoded.size)
            val decoded = requireNotNull(SecureKeyConfirmationCodec.decode(encoded))
            val decodedDigest = decoded.transcriptDigestBytes()
            try {
                assertEquals(SecurePeerRole.INITIATOR, decoded.role)
                assertTrue(ByteArray(32) { it.toByte() }.contentEquals(decodedDigest))
                assertNull(SecureKeyConfirmationCodec.decode(encoded.copyOf(encoded.size - 1)))
                assertNull(SecureKeyConfirmationCodec.decode(encoded.copyOf().also { it[5] = 7 }))
                assertNull(SecureKeyConfirmationCodec.decode(encoded.copyOf().also { it[4] = 2 }))
            } finally {
                decodedDigest.fill(0)
                decoded.clearSensitive()
            }
        } finally {
            encoded.fill(0)
        }
    }

    private fun hello(
        role: SecurePeerRole,
        local: PeerInstanceId,
        remote: PeerInstanceId,
        appVersion: String,
        nonceStart: Int,
        fingerprintStart: Int,
        selected: Int = 1,
        minimum: Int = 1,
        maximum: Int = 1,
        controlId: SessionId = controlSessionId,
    ): SecurePairingHello = SecurePairingHello(
        secureSessionId = secureSessionId,
        controlSessionId = controlId,
        role = role,
        localPeerInstanceId = local,
        remotePeerInstanceId = remote,
        appVersion = appVersion,
        selectedProtocolVersion = selected,
        protocolMinimumVersion = minimum,
        protocolMaximumVersion = maximum,
        nonce = ByteArray(32) { (nonceStart + it).toByte() },
        certificateFingerprint = ByteArray(32) { (fingerprintStart + it).toByte() },
    )

    private fun assertInvalid(frame: ByteArray) {
        assertTrue(SecurePairingHelloCodec.decode(frame) is SecurePairingHelloDecodeResult.Invalid)
        frame.fill(0)
    }

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        val chars = CharArray(size * 2)
        for (index in indices) {
            val byte = this[index].toInt() and 0xFF
            chars[index * 2] = alphabet[byte ushr 4]
            chars[index * 2 + 1] = alphabet[byte and 0x0F]
        }
        return String(chars)
    }
}
