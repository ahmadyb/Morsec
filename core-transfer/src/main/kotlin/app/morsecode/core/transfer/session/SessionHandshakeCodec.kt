package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Bounded, versioned binary envelope for the control-only capability handshake. */
public object SessionHandshakeCodec {
    public const val HEADER_SIZE_BYTES: Int = 8
    public const val MAX_FRAME_SIZE_BYTES: Int = 512
    public const val WIRE_VERSION: Int = 1

    private const val TYPE_HELLO: Int = 1
    private const val TYPE_ACCEPT: Int = 2
    private const val TYPE_REJECT: Int = 3
    private const val PEER_ID_BYTES: Int = 16
    private const val MAX_SESSION_ID_BYTES: Int = 64
    private const val SUPPORTED_TRANSPORT_MASK: Int = 0x03
    private val magic = byteArrayOf(0x4D, 0x53, 0x48, 0x31) // MSH1

    public fun encode(message: SessionHandshakeMessage): ByteArray {
        val payload = Writer(MAX_FRAME_SIZE_BYTES - HEADER_SIZE_BYTES)
        when (message) {
            is SessionHello -> {
                payload.sessionId(message.attemptId)
                payload.peerId(message.senderPeerInstanceId)
                payload.peerId(message.targetPeerInstanceId)
                payload.profile(message.profile)
            }
            is SessionAccept -> {
                payload.sessionId(message.attemptId)
                payload.peerId(message.senderPeerInstanceId)
                payload.peerId(message.targetPeerInstanceId)
                payload.profile(message.profile)
                payload.u16(message.selectedProtocolVersion)
                payload.negotiated(message.negotiated)
            }
            is SessionReject -> {
                payload.sessionId(message.attemptId)
                payload.peerId(message.senderPeerInstanceId)
                payload.peerId(message.targetPeerInstanceId)
                payload.u8(message.code.wireId)
            }
        }
        val payloadBytes = payload.toByteArray()
        require(payloadBytes.size + HEADER_SIZE_BYTES <= MAX_FRAME_SIZE_BYTES) {
            "session handshake exceeds its frame bound"
        }
        val frame = ByteArray(HEADER_SIZE_BYTES + payloadBytes.size)
        magic.copyInto(frame)
        frame[4] = WIRE_VERSION.toByte()
        frame[5] = when (message) {
            is SessionHello -> TYPE_HELLO.toByte()
            is SessionAccept -> TYPE_ACCEPT.toByte()
            is SessionReject -> TYPE_REJECT.toByte()
        }
        putU16(frame, 6, payloadBytes.size)
        payloadBytes.copyInto(frame, destinationOffset = HEADER_SIZE_BYTES)
        return frame
    }

    /** Validates a fixed header before a transport allocates the declared payload. */
    public fun decodeHeader(header: ByteArray): SessionHandshakeHeaderResult {
        if (header.size != HEADER_SIZE_BYTES) return invalidHeader()
        if (!magic.indices.all { header[it] == magic[it] }) return invalidHeader()
        if ((header[4].toInt() and 0xFF) != WIRE_VERSION) {
            return SessionHandshakeHeaderResult.Invalid(
                SessionFailure(SessionFailureCode.HANDSHAKE_VERSION_UNSUPPORTED),
            )
        }
        val messageType = header[5].toInt() and 0xFF
        if (messageType !in TYPE_HELLO..TYPE_REJECT) return invalidHeader()
        val payloadLength = readU16(header, 6)
        if (payloadLength > MAX_FRAME_SIZE_BYTES - HEADER_SIZE_BYTES) return invalidHeader()
        return SessionHandshakeHeaderResult.Valid(SessionHandshakeHeader(messageType, payloadLength))
    }

    /** Parses one complete frame; length is bounded before field allocation. */
    public fun decode(frame: ByteArray): SessionHandshakeDecodeResult {
        if (frame.size < HEADER_SIZE_BYTES || frame.size > MAX_FRAME_SIZE_BYTES) return invalid()
        val header = when (val decoded = decodeHeader(frame.copyOfRange(0, HEADER_SIZE_BYTES))) {
            is SessionHandshakeHeaderResult.Valid -> decoded.header
            is SessionHandshakeHeaderResult.Invalid -> return SessionHandshakeDecodeResult.Invalid(decoded.failure)
        }
        if (header.payloadLength != frame.size - HEADER_SIZE_BYTES) return invalid()
        val cursor = Cursor(frame, HEADER_SIZE_BYTES, frame.size)
        val message = when (header.messageType) {
            TYPE_HELLO -> parseHello(cursor)
            TYPE_ACCEPT -> parseAccept(cursor)
            TYPE_REJECT -> parseReject(cursor)
            else -> null
        } ?: return invalid()
        if (!cursor.atEnd) return invalid()
        return SessionHandshakeDecodeResult.Success(message)
    }

    private fun parseHello(cursor: Cursor): SessionHello? {
        val attempt = cursor.sessionId() ?: return null
        val sender = cursor.peerId() ?: return null
        val target = cursor.peerId() ?: return null
        val profile = cursor.profile(sender) ?: return null
        return try {
            SessionHello(attempt, sender, target, profile)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseAccept(cursor: Cursor): SessionAccept? {
        val attempt = cursor.sessionId() ?: return null
        val sender = cursor.peerId() ?: return null
        val target = cursor.peerId() ?: return null
        val profile = cursor.profile(sender) ?: return null
        val selectedVersion = cursor.u16() ?: return null
        val negotiated = cursor.negotiated() ?: return null
        return try {
            SessionAccept(attempt, sender, target, profile, selectedVersion, negotiated)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseReject(cursor: Cursor): SessionReject? {
        val attempt = cursor.sessionId() ?: return null
        val sender = cursor.peerId() ?: return null
        val target = cursor.peerId() ?: return null
        val code = HandshakeRejectCode.fromWireId(cursor.u8() ?: return null) ?: return null
        return SessionReject(attempt, sender, target, code)
    }

    private fun invalid(): SessionHandshakeDecodeResult.Invalid =
        SessionHandshakeDecodeResult.Invalid(SessionFailure(SessionFailureCode.HANDSHAKE_INVALID))

    private fun invalidHeader(): SessionHandshakeHeaderResult.Invalid =
        SessionHandshakeHeaderResult.Invalid(SessionFailure(SessionFailureCode.HANDSHAKE_INVALID))

    private class Writer(maximum: Int) {
        private val bytes = ByteArray(maximum)
        private var position: Int = 0

        fun u8(value: Int) {
            require(value in 0..0xFF) { "handshake byte is outside its bound" }
            bytes[position++] = value.toByte()
        }

        fun u16(value: Int) {
            require(value in 0..0xFFFF) { "handshake short is outside its bound" }
            bytes[position++] = (value ushr 8).toByte()
            bytes[position++] = value.toByte()
        }

        fun u32(value: Int) {
            require(value >= 0) { "handshake integer is negative" }
            bytes[position++] = (value ushr 24).toByte()
            bytes[position++] = (value ushr 16).toByte()
            bytes[position++] = (value ushr 8).toByte()
            bytes[position++] = value.toByte()
        }

        fun sessionId(value: SessionId) {
            val encoded = value.value.toByteArray(Charsets.US_ASCII)
            require(encoded.size in 1..MAX_SESSION_ID_BYTES) { "handshake attempt id is outside its bound" }
            u8(encoded.size)
            raw(encoded)
        }

        fun peerId(value: PeerInstanceId) {
            var index = 0
            while (index < value.value.length) {
                val high = value.value[index].digitToInt(16)
                val low = value.value[index + 1].digitToInt(16)
                u8((high shl 4) or low)
                index += 2
            }
        }

        fun profile(value: SessionPeerProfile) {
            text(value.appVersion, MAX_APP_VERSION_BYTES)
            text(value.displayName, MAX_DISPLAY_NAME_BYTES)
            u16(value.protocolRange.minimum)
            u16(value.protocolRange.maximum)
            u8(transportMask(value.capabilities.supportedTransports))
            u16(SessionFeature.mask(value.capabilities.features))
            u32(value.capabilities.maxChunkSizeBytes)
            u8(if (value.capabilities.resumeSupported) 1 else 0)
            u8(value.capabilities.encryption.wireId)
        }

        fun negotiated(value: NegotiatedCapabilities) {
            u16(SessionFeature.mask(value.features))
            u32(value.maxChunkSizeBytes)
            u8(if (value.resumeSupported) 1 else 0)
            u8(value.encryption.wireId)
        }

        private fun text(value: String, maximumBytes: Int) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            require(encoded.size in 1..maximumBytes) { "handshake text is outside its bound" }
            u8(encoded.size)
            raw(encoded)
        }

        private fun raw(value: ByteArray) {
            require(value.size <= bytes.size - position) { "handshake payload exceeds its bound" }
            value.copyInto(bytes, destinationOffset = position)
            position += value.size
        }

        fun toByteArray(): ByteArray = bytes.copyOf(position)
    }

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

        fun sessionId(): SessionId? {
            val length = u8() ?: return null
            if (length !in 1..MAX_SESSION_ID_BYTES || length > end - position) return null
            val chars = CharArray(length)
            repeat(length) { index ->
                val value = u8() ?: return null
                if (value !in 0x21..0x7E) return null
                chars[index] = value.toChar()
            }
            return SessionId.orNull(chars.concatToString())
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

        fun profile(peerId: PeerInstanceId): SessionPeerProfile? {
            val appVersion = text(MAX_APP_VERSION_BYTES) ?: return null
            val displayName = text(MAX_DISPLAY_NAME_BYTES) ?: return null
            val minimum = u16() ?: return null
            val maximum = u16() ?: return null
            val transportBits = u8() ?: return null
            val featureBits = u16() ?: return null
            val maxChunk = u32() ?: return null
            val resume = boolean() ?: return null
            val encryption = EncryptionCapability.fromWireId(u8() ?: return null) ?: return null
            val transports = transportSet(transportBits) ?: return null
            val features = SessionFeature.fromMask(featureBits) ?: return null
            return try {
                SessionPeerProfile(
                    peerInstanceId = peerId,
                    displayName = displayName,
                    appVersion = appVersion,
                    protocolRange = ProtocolRange(minimum, maximum),
                    capabilities = SessionCapabilities(
                        supportedTransports = transports,
                        features = features,
                        maxChunkSizeBytes = maxChunk,
                        resumeSupported = resume,
                        encryption = encryption,
                    ),
                )
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun negotiated(): NegotiatedCapabilities? {
            val featureBits = u16() ?: return null
            val maxChunk = u32() ?: return null
            val resume = boolean() ?: return null
            val encryption = EncryptionCapability.fromWireId(u8() ?: return null) ?: return null
            val features = SessionFeature.fromMask(featureBits) ?: return null
            return try {
                NegotiatedCapabilities(features, maxChunk, resume, encryption)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        private fun boolean(): Boolean? = when (u8()) {
            0 -> false
            1 -> true
            else -> null
        }

        private fun text(maximumBytes: Int): String? {
            val length = u8() ?: return null
            if (length !in 1..maximumBytes || length > end - position) return null
            val decoded = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, position, length))
                    .toString()
            } catch (_: CharacterCodingException) {
                return null
            }
            position += length
            return decoded
        }
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

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)
}

public data class SessionHandshakeHeader(
    public val messageType: Int,
    public val payloadLength: Int,
)

public sealed interface SessionHandshakeHeaderResult {
    public data class Valid(public val header: SessionHandshakeHeader) : SessionHandshakeHeaderResult
    public data class Invalid(public val failure: SessionFailure) : SessionHandshakeHeaderResult
}

public sealed interface SessionHandshakeDecodeResult {
    public data class Success(public val message: SessionHandshakeMessage) : SessionHandshakeDecodeResult
    public data class Invalid(public val failure: SessionFailure) : SessionHandshakeDecodeResult
}
