package app.morsecode.transport.lan.discovery

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

public class LanBeaconCodecTest {
    @Test
    public fun boundedBeaconRoundTripsUnicodeDisplayNameAndCapabilities() {
        val expected = beacon("Morse 🌊")
        val encoded = LanBeaconCodec.encode(expected)

        assertTrue(encoded.size <= LanBeaconCodec.MAX_DATAGRAM_SIZE_BYTES)
        assertEquals(LanBeaconDecodeResult.Success(expected), LanBeaconCodec.decode(encoded))
    }

    @Test
    public fun malformedLengthsUnknownTypesAndCrcCorruptionAreRejected() {
        val encoded = LanBeaconCodec.encode(beacon("Peer"))
        val badLength = encoded.copyOf().also { it[6] = 0x7F }
        val badType = encoded.copyOf().also { it[5] = 0x7F }
        val badCrc = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val oversized = ByteArray(LanBeaconCodec.MAX_DATAGRAM_SIZE_BYTES + 1)
        val truncated = encoded.copyOf(encoded.size - 1)

        listOf(badLength, badType, badCrc, oversized, truncated).forEach { bytes ->
            assertTrue(LanBeaconCodec.decode(bytes) is LanBeaconDecodeResult.Invalid)
        }
    }

    @Test
    public fun unsupportedVersionIsTypedWithoutReflectingPacketContents() {
        val encoded = LanBeaconCodec.encode(beacon("Peer"))
        encoded[4] = (LanBeaconCodec.WIRE_VERSION + 1).toByte()

        val result = LanBeaconCodec.decode(encoded) as LanBeaconDecodeResult.Invalid
        assertEquals(SessionFailureCode.BEACON_VERSION_UNSUPPORTED, result.failure.code)
        assertEquals("SessionFailure(code=beacon_version_unsupported)", result.failure.toString())
    }

    @Test
    public fun declaredDatagramLengthIsBoundedBeforeFieldParsing() {
        val encoded = LanBeaconCodec.encode(beacon("Peer"))
        ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putShort(6, 0xFFFF.toShort())

        assertTrue(LanBeaconCodec.decode(encoded) is LanBeaconDecodeResult.Invalid)
    }

    @Test
    public fun payloadAdvertisementCanBeDecodedButTheSessionNegotiatorStillDisablesIt() {
        val capabilities = SessionCapabilities(
            supportedTransports = setOf(TransportKind.LAN),
            features = setOf(
                SessionFeature.CONTROL_HANDSHAKE,
                SessionFeature.FILE_PAYLOAD,
                SessionFeature.RESUME,
            ),
            maxChunkSizeBytes = 2_048,
            resumeSupported = true,
            encryption = EncryptionCapability.NONE,
        )
        val advertised = beacon("Peer", capabilities)
        val encoded = LanBeaconCodec.encode(advertised)

        assertEquals(LanBeaconDecodeResult.Success(advertised), LanBeaconCodec.decode(encoded))
    }

    private fun beacon(
        name: String,
        capabilities: SessionCapabilities = SessionCapabilities.controlOnly(TransportKind.LAN),
    ): LanBeacon = LanBeacon(
        SessionPeerProfile(
            peerInstanceId = PeerInstanceId("0123456789abcdef0123456789abcdef"),
            displayName = name,
            appVersion = "1.0.0",
            protocolRange = ProtocolRange.CURRENT,
            capabilities = capabilities,
        ),
    )
}
