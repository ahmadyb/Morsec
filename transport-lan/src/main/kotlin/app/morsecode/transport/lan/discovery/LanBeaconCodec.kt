package app.morsecode.transport.lan.discovery

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.integrity.Crc32
import app.morsecode.core.transfer.session.EncryptionCapability
import app.morsecode.core.transfer.session.PeerInstanceId
import app.morsecode.core.transfer.session.ProtocolRange
import app.morsecode.core.transfer.session.SessionCapabilities
import app.morsecode.core.transfer.session.SessionFailure
import app.morsecode.core.transfer.session.SessionFailureCode
import app.morsecode.core.transfer.session.SessionFeature
import app.morsecode.core.transfer.session.SessionPeerProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A bounded version-1 LAN discovery advertisement. All fields are untrusted. */
public data class LanBeacon(public val profile: SessionPeerProfile) {
    init {
        require(TransportKind.LAN in profile.capabilities.supportedTransports) {
            "LAN beacon profile must include LAN"
        }
    }

    override fun toString(): String = "LanBeacon(peerInstanceId=[redacted])"
}

/** Strict fixed-header codec. CRC32 detects accidental damage; it is not authentication. */
public object LanBeaconCodec {
    public const val HEADER_SIZE_BYTES: Int = 8
    public const val MAX_DATAGRAM_SIZE_BYTES: Int = 192
    public const val WIRE_VERSION: Int = 1

    private const val TYPE_BEACON: Int = 1
    private const val PEER_ID_BYTES: Int = 16
    private const val CRC_SIZE_BYTES: Int = 4
    private const val SUPPORTED_TRANSPORT_MASK: Int = 0x03
    private val magic = byteArrayOf(0x4D, 0x53, 0x44, 0x31) // MSD1

    public fun encode(beacon: LanBeacon): ByteArray {
        val profile = beacon.profile
        val displayName = profile.displayName.toByteArray(Charsets.UTF_8)
        val appVersion = profile.appVersion.toByteArray(Charsets.US_ASCII)
        require(displayName.size in 1..MAX_DISPLAY_NAME_BYTES) { "display name exceeds the beacon bound" }
        require(appVersion.size in 1..MAX_APP_VERSION_BYTES) { "app version exceeds the beacon bound" }

        val frame = ByteArray(MAX_DATAGRAM_SIZE_BYTES)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.put(WIRE_VERSION.toByte())
        buffer.put(TYPE_BEACON.toByte())
        buffer.putShort(0)
        writePeerId(buffer, profile.peerInstanceId)
        writeText(buffer, appVersion)
        writeText(buffer, displayName)
        buffer.putShort(profile.protocolRange.minimum.toShort())
        buffer.putShort(profile.protocolRange.maximum.toShort())
        buffer.put(transportMask(profile.capabilities.supportedTransports).toByte())
        buffer.putShort(SessionFeature.mask(profile.capabilities.features).toShort())
        buffer.putInt(profile.capabilities.maxChunkSizeBytes)
        buffer.put((if (profile.capabilities.resumeSupported) 1 else 0).toByte())
        buffer.put(profile.capabilities.encryption.wireId.toByte())

        val crcOffset = buffer.position()
        val frameLength = crcOffset + CRC_SIZE_BYTES
        require(frameLength <= MAX_DATAGRAM_SIZE_BYTES) { "beacon exceeds its datagram bound" }
        buffer.putShort(6, (frameLength - HEADER_SIZE_BYTES).toShort())
        val bounded = frame.copyOf(frameLength)
        ByteBuffer.wrap(bounded).order(ByteOrder.BIG_ENDIAN)
            .putInt(crcOffset, Crc32.compute(bounded, 0, crcOffset))
        return bounded
    }

    public fun decode(frame: ByteArray): LanBeaconDecodeResult {
        if (frame.size < HEADER_SIZE_BYTES + CRC_SIZE_BYTES || frame.size > MAX_DATAGRAM_SIZE_BYTES) {
            return invalid()
        }
        if (!magic.indices.all { frame[it] == magic[it] }) return invalid()
        if ((frame[4].toInt() and 0xFF) != WIRE_VERSION) {
            return LanBeaconDecodeResult.Invalid(SessionFailure(SessionFailureCode.BEACON_VERSION_UNSUPPORTED))
        }
        if ((frame[5].toInt() and 0xFF) != TYPE_BEACON) return invalid()
        val payloadLength = readU16(frame, 6)
        if (payloadLength != frame.size - HEADER_SIZE_BYTES) return invalid()

        val crcOffset = frame.size - CRC_SIZE_BYTES
        val expectedCrc = ByteBuffer.wrap(frame, crcOffset, CRC_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).getInt()
        if (Crc32.compute(frame, 0, crcOffset) != expectedCrc) return invalid()

        val cursor = Cursor(frame, HEADER_SIZE_BYTES, crcOffset)
        val peerId = cursor.peerId() ?: return invalid()
        val appVersion = cursor.text(MAX_APP_VERSION_BYTES, asciiOnly = true) ?: return invalid()
        val displayName = cursor.text(MAX_DISPLAY_NAME_BYTES, asciiOnly = false) ?: return invalid()
        val minimum = cursor.u16() ?: return invalid()
        val maximum = cursor.u16() ?: return invalid()
        val transports = transportSet(cursor.u8() ?: return invalid()) ?: return invalid()
        if (TransportKind.LAN !in transports) return invalid()
        val features = SessionFeature.fromMask(cursor.u16() ?: return invalid()) ?: return invalid()
        val maxChunkSize = cursor.u32() ?: return invalid()
        val resumeSupported = cursor.boolean() ?: return invalid()
        val encryption = EncryptionCapability.fromWireId(cursor.u8() ?: return invalid()) ?: return invalid()
        if (!cursor.atEnd) return invalid()

        val profile = try {
            SessionPeerProfile(
                peerInstanceId = peerId,
                displayName = displayName,
                appVersion = appVersion,
                protocolRange = ProtocolRange(minimum, maximum),
                capabilities = SessionCapabilities(
                    supportedTransports = transports,
                    features = features,
                    maxChunkSizeBytes = maxChunkSize,
                    resumeSupported = resumeSupported,
                    encryption = encryption,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return invalid()
        }
        return LanBeaconDecodeResult.Success(LanBeacon(profile))
    }

    private fun writePeerId(buffer: ByteBuffer, peerId: PeerInstanceId) {
        var index = 0
        while (index < peerId.value.length) {
            val high = peerId.value[index].digitToInt(16)
            val low = peerId.value[index + 1].digitToInt(16)
            buffer.put(((high shl 4) or low).toByte())
            index += 2
        }
    }

    private fun writeText(buffer: ByteBuffer, bytes: ByteArray) {
        require(bytes.size in 1..0xFF) { "beacon text length is outside its bound" }
        buffer.put(bytes.size.toByte())
        buffer.put(bytes)
    }

    private fun transportMask(transports: Set<TransportKind>): Int {
        var mask = 0
        if (TransportKind.LAN in transports) mask = mask or 0x01
        if (TransportKind.NEARBY in transports) mask = mask or 0x02
        return mask
    }

    private fun transportSet(mask: Int): Set<TransportKind>? {
        if (mask == 0 || mask and SUPPORTED_TRANSPORT_MASK.inv() != 0) return null
        val transports = linkedSetOf<TransportKind>()
        if (mask and 0x01 != 0) transports += TransportKind.LAN
        if (mask and 0x02 != 0) transports += TransportKind.NEARBY
        return transports
    }

    private fun invalid(): LanBeaconDecodeResult.Invalid =
        LanBeaconDecodeResult.Invalid(SessionFailure(SessionFailureCode.BEACON_INVALID))

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

    private class Cursor(private val bytes: ByteArray, start: Int, private val end: Int) {
        private var position: Int = start
        val atEnd: Boolean get() = position == end

        fun u8(): Int? = if (position < end) bytes[position++].toInt() and 0xFF else null

        fun u16(): Int? {
            val first = u8() ?: return null
            val second = u8() ?: return null
            return (first shl 8) or second
        }

        fun u32(): Int? {
            val first = u8() ?: return null
            val second = u8() ?: return null
            val third = u8() ?: return null
            val fourth = u8() ?: return null
            val value = (first.toLong() shl 24) or
                (second.toLong() shl 16) or
                (third.toLong() shl 8) or fourth.toLong()
            return value.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
        }

        fun peerId(): PeerInstanceId? {
            if (PEER_ID_BYTES > end - position) return null
            val alphabet = "0123456789abcdef"
            val chars = CharArray(PEER_ID_BYTES * 2)
            repeat(PEER_ID_BYTES) { index ->
                val value = u8() ?: return null
                chars[index * 2] = alphabet[value ushr 4]
                chars[index * 2 + 1] = alphabet[value and 0x0F]
            }
            return PeerInstanceId.orNull(chars.concatToString())
        }

        fun text(maximumBytes: Int, asciiOnly: Boolean): String? {
            val length = u8() ?: return null
            if (length !in 1..maximumBytes || length > end - position) return null
            if (asciiOnly && (position until position + length).any { (bytes[it].toInt() and 0x80) != 0 }) return null
            val result = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, position, length))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            position += length
            return result
        }

        fun boolean(): Boolean? = when (u8()) {
            0 -> false
            1 -> true
            else -> null
        }
    }
}

private const val MAX_DISPLAY_NAME_BYTES: Int = 96
private const val MAX_APP_VERSION_BYTES: Int = 32

public sealed interface LanBeaconDecodeResult {
    public data class Success(public val beacon: LanBeacon) : LanBeaconDecodeResult
    public data class Invalid(public val failure: SessionFailure) : LanBeaconDecodeResult
}
