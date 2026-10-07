package app.morsecode.core.transfer.session

import app.morsecode.core.model.TransportKind
import app.morsecode.core.transfer.identity.SessionId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val SECURE_HELLO_HEADER_BYTES: Int = 8
private const val PEER_ID_BYTES: Int = 16
private const val SESSION_ID_BYTES: Int = 16
private const val FIXED_NONCE_BYTES: Int = 32
private const val FEATURE_CONTROL_AND_SECURE: Int = (1 shl 0) or (1 shl 3)
private const val TRANSPORT_LAN_WIRE_ID: Int = 1
private const val PART_B_SUITE_WIRE_ID: Int = 1
private val SECURE_HELLO_MAGIC = byteArrayOf(0x4D, 0x50, 0x53, 0x31) // MPS1
private val TRANSCRIPT_MAGIC = byteArrayOf(0x4D, 0x53, 0x54, 0x31) // MST1
private val KEY_CONFIRMATION_MAGIC = byteArrayOf(0x4D, 0x53, 0x4B, 0x43) // MSKC

private fun copyExact(value: ByteArray, expectedSize: Int, field: String): ByteArray {
    require(value.size == expectedSize) { "$field has the wrong length" }
    return value.copyOf()
}

/** One fixed-schema hello sent only inside the upgraded TLS 1.3 channel. */
public class SecurePairingHello(
    public val secureSessionId: SessionId,
    public val controlSessionId: SessionId,
    public val role: SecurePeerRole,
    public val localPeerInstanceId: PeerInstanceId,
    public val remotePeerInstanceId: PeerInstanceId,
    public val appVersion: String,
    public val selectedProtocolVersion: Int,
    public val protocolMinimumVersion: Int,
    public val protocolMaximumVersion: Int,
    nonce: ByteArray,
    certificateFingerprint: ByteArray,
) {
    private val nonceValue: ByteArray = copyExact(nonce, FIXED_NONCE_BYTES, "pairing nonce")
    private val fingerprintValue = copyExact(
        certificateFingerprint,
        SecureSessionLimits.FINGERPRINT_BYTES,
        "certificate fingerprint",
    )

    public val discoveryVersion: Int = SecureSessionLimits.DISCOVERY_WIRE_VERSION
    public val controlVersion: Int = SecureSessionLimits.CONTROL_WIRE_VERSION
    public val secureVersion: Int = SecureSessionLimits.SECURE_WIRE_VERSION
    public val capabilityVersion: Int = SecureSessionLimits.CAPABILITY_WIRE_VERSION
    public val transport: TransportKind = TransportKind.LAN
    public val selectedFeaturesMask: Int = FEATURE_CONTROL_AND_SECURE
    public val maxRecordPlaintextBytes: Int = SecureSessionLimits.MAX_RECORD_PLAINTEXT_BYTES
    public val maxRecordsPerDirection: Long = SecureSessionLimits.MAX_RECORDS_PER_DIRECTION
    public val maxBytesPerDirection: Long = SecureSessionLimits.MAX_BYTES_PER_DIRECTION
    public val sessionLifetimeMillis: Long = SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS
    public val securitySuiteWireId: Int = PART_B_SUITE_WIRE_ID

    init {
        require(secureSessionId.value.matches(Regex("[0-9a-f]{32}"))) {
            "secure session id must be 128-bit lowercase hexadecimal"
        }
        require(localPeerInstanceId != remotePeerInstanceId) { "pairing peer ids must differ" }
        require(appVersion.length in 1..32 && appVersion.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".+-_"
        }) { "pairing app version is outside its bound" }
        require(protocolMinimumVersion in 1..ProtocolRange.MAX_PROTOCOL_REVISION &&
            protocolMaximumVersion in protocolMinimumVersion..ProtocolRange.MAX_PROTOCOL_REVISION &&
            selectedProtocolVersion in protocolMinimumVersion..protocolMaximumVersion
        ) { "selected pairing protocol is outside its advertised range" }
        require(nonceValue.size == FIXED_NONCE_BYTES) { "pairing nonce has the wrong length" }
        require(fingerprintValue.size == SecureSessionLimits.FINGERPRINT_BYTES) {
            "certificate fingerprint has the wrong length"
        }
    }

    public fun nonceBytes(): ByteArray = nonceValue.copyOf()
    public fun certificateFingerprintBytes(): ByteArray = fingerprintValue.copyOf()

    public fun clearSensitive() {
        nonceValue.fill(0)
        fingerprintValue.fill(0)
    }

    override fun toString(): String = "SecurePairingHello(role=${role.name}, ids=[redacted])"
}

public data class SecurePairingHelloHeader(public val payloadLength: Int)

public sealed interface SecurePairingHelloHeaderResult {
    public data class Valid(public val header: SecurePairingHelloHeader) : SecurePairingHelloHeaderResult
    public data class Invalid(public val failure: SessionFailure) : SecurePairingHelloHeaderResult
}

public sealed interface SecurePairingHelloDecodeResult {
    public data class Success(public val hello: SecurePairingHello) : SecurePairingHelloDecodeResult
    public data class Invalid(public val failure: SessionFailure) : SecurePairingHelloDecodeResult
}

/** Fixed field order; duplicate and unknown mandatory fields cannot be represented. */
public object SecurePairingHelloCodec {
    public const val HEADER_SIZE_BYTES: Int = SECURE_HELLO_HEADER_BYTES
    public const val MAX_FRAME_SIZE_BYTES: Int = SecureSessionLimits.MAX_HELLO_FRAME_BYTES
    public const val WIRE_VERSION: Int = SecureSessionLimits.SECURE_WIRE_VERSION

    private const val TYPE_HELLO: Int = 1

    public fun encode(hello: SecurePairingHello): ByteArray {
        val controlId = hello.controlSessionId.value.toByteArray(Charsets.US_ASCII)
        val appVersion = hello.appVersion.toByteArray(Charsets.US_ASCII)
        require(controlId.size in 1..64 && controlId.all { (it.toInt() and 0xFF) in 0x21..0x7E }) {
            "control session id is outside its bound"
        }
        val payloadSize =
            SESSION_ID_BYTES + 1 + controlId.size + PEER_ID_BYTES + PEER_ID_BYTES +
                1 + 2 + 2 + 2 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + appVersion.size + 2 + 2 + 4 + 4 + 4 +
                FIXED_NONCE_BYTES + SecureSessionLimits.FINGERPRINT_BYTES
        require(payloadSize + HEADER_SIZE_BYTES <= MAX_FRAME_SIZE_BYTES) {
            "secure pairing hello exceeds its frame bound"
        }
        val frame = ByteArray(HEADER_SIZE_BYTES + payloadSize)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        buffer.put(SECURE_HELLO_MAGIC)
        buffer.put(WIRE_VERSION.toByte())
        buffer.put(TYPE_HELLO.toByte())
        buffer.putShort(payloadSize.toShort())
        buffer.put(hexToBytes(hello.secureSessionId.value, SESSION_ID_BYTES))
        buffer.put(controlId.size.toByte())
        buffer.put(controlId)
        putPeerId(buffer, hello.localPeerInstanceId)
        putPeerId(buffer, hello.remotePeerInstanceId)
        buffer.put(hello.role.wireId.toByte())
        buffer.putShort(hello.selectedProtocolVersion.toShort())
        buffer.putShort(hello.protocolMinimumVersion.toShort())
        buffer.putShort(hello.protocolMaximumVersion.toShort())
        buffer.put(hello.discoveryVersion.toByte())
        buffer.put(hello.controlVersion.toByte())
        buffer.put(hello.secureVersion.toByte())
        buffer.put(hello.capabilityVersion.toByte())
        buffer.put(TRANSPORT_LAN_WIRE_ID.toByte())
        buffer.put(hello.securitySuiteWireId.toByte())
        buffer.put(appVersion.size.toByte())
        buffer.put(appVersion)
        buffer.putShort(hello.selectedFeaturesMask.toShort())
        buffer.putShort(hello.maxRecordPlaintextBytes.toShort())
        buffer.putInt(hello.maxRecordsPerDirection.toInt())
        buffer.putInt(hello.maxBytesPerDirection.toInt())
        buffer.putInt(hello.sessionLifetimeMillis.toInt())
        val nonce = hello.nonceBytes()
        val fingerprint = hello.certificateFingerprintBytes()
        try {
            buffer.put(nonce)
            buffer.put(fingerprint)
        } finally {
            nonce.fill(0)
            fingerprint.fill(0)
        }
        return frame
    }

    public fun decodeHeader(header: ByteArray): SecurePairingHelloHeaderResult {
        if (header.size != HEADER_SIZE_BYTES || !SECURE_HELLO_MAGIC.indices.all { header[it] == SECURE_HELLO_MAGIC[it] }) {
            return invalidHeader()
        }
        if ((header[4].toInt() and 0xFF) != WIRE_VERSION) return invalidHeader()
        if ((header[5].toInt() and 0xFF) != TYPE_HELLO) return invalidHeader()
        val payloadLength = readU16(header, 6)
        if (payloadLength > MAX_FRAME_SIZE_BYTES - HEADER_SIZE_BYTES) return invalidHeader()
        return SecurePairingHelloHeaderResult.Valid(SecurePairingHelloHeader(payloadLength))
    }

    public fun decode(frame: ByteArray): SecurePairingHelloDecodeResult {
        if (frame.size !in HEADER_SIZE_BYTES..MAX_FRAME_SIZE_BYTES) return invalid()
        val header = when (val result = decodeHeader(frame.copyOfRange(0, HEADER_SIZE_BYTES))) {
            is SecurePairingHelloHeaderResult.Valid -> result.header
            is SecurePairingHelloHeaderResult.Invalid -> return SecurePairingHelloDecodeResult.Invalid(result.failure)
        }
        if (header.payloadLength != frame.size - HEADER_SIZE_BYTES) return invalid()
        val cursor = Cursor(frame, HEADER_SIZE_BYTES, frame.size)
        val secureId = cursor.hexSessionId() ?: return invalid()
        val controlId = cursor.sessionId() ?: return invalid()
        val localPeer = cursor.peerId() ?: return invalid()
        val remotePeer = cursor.peerId() ?: return invalid()
        val role = SecurePeerRole.fromWireId(cursor.u8() ?: return invalid()) ?: return invalid()
        val selectedProtocolVersion = cursor.u16() ?: return invalid()
        val protocolMinimumVersion = cursor.u16() ?: return invalid()
        val protocolMaximumVersion = cursor.u16() ?: return invalid()
        val discoveryVersion = cursor.u8() ?: return invalid()
        val controlVersion = cursor.u8() ?: return invalid()
        val secureVersion = cursor.u8() ?: return invalid()
        val capabilityVersion = cursor.u8() ?: return invalid()
        val transport = cursor.u8() ?: return invalid()
        val suite = cursor.u8() ?: return invalid()
        val appVersion = cursor.asciiText(32) ?: return invalid()
        val features = cursor.u16() ?: return invalid()
        val recordLimit = cursor.u16() ?: return invalid()
        val recordCountLimit = cursor.u32() ?: return invalid()
        val byteLimit = cursor.u32() ?: return invalid()
        val lifetime = cursor.u32() ?: return invalid()
        val nonce = cursor.raw(FIXED_NONCE_BYTES) ?: return invalid()
        val fingerprint = cursor.raw(SecureSessionLimits.FINGERPRINT_BYTES) ?: run {
            nonce.fill(0)
            return invalid()
        }
        return try {
            if (!cursor.atEnd ||
                discoveryVersion != SecureSessionLimits.DISCOVERY_WIRE_VERSION ||
                controlVersion != SecureSessionLimits.CONTROL_WIRE_VERSION ||
                secureVersion != SecureSessionLimits.SECURE_WIRE_VERSION ||
                capabilityVersion != SecureSessionLimits.CAPABILITY_WIRE_VERSION ||
                transport != TRANSPORT_LAN_WIRE_ID || suite != PART_B_SUITE_WIRE_ID ||
                features != FEATURE_CONTROL_AND_SECURE ||
                recordLimit != SecureSessionLimits.MAX_RECORD_PLAINTEXT_BYTES ||
                recordCountLimit != SecureSessionLimits.MAX_RECORDS_PER_DIRECTION.toInt() ||
                byteLimit != SecureSessionLimits.MAX_BYTES_PER_DIRECTION.toInt() ||
                lifetime != SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS.toInt()
            ) return invalid()
            SecurePairingHelloDecodeResult.Success(
                SecurePairingHello(
                    secureSessionId = secureId,
                    controlSessionId = controlId,
                    role = role,
                    localPeerInstanceId = localPeer,
                    remotePeerInstanceId = remotePeer,
                    appVersion = appVersion,
                    selectedProtocolVersion = selectedProtocolVersion,
                    protocolMinimumVersion = protocolMinimumVersion,
                    protocolMaximumVersion = protocolMaximumVersion,
                    nonce = nonce,
                    certificateFingerprint = fingerprint,
                ),
            )
        } catch (_: IllegalArgumentException) {
            invalid()
        } finally {
            nonce.fill(0)
            fingerprint.fill(0)
        }
    }

    private fun invalid(): SecurePairingHelloDecodeResult.Invalid =
        SecurePairingHelloDecodeResult.Invalid(SessionFailure(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID))

    private fun invalidHeader(): SecurePairingHelloHeaderResult.Invalid =
        SecurePairingHelloHeaderResult.Invalid(SessionFailure(SessionFailureCode.SECURE_SESSION_TRANSCRIPT_INVALID))
}

/** Canonical transcript shared byte-for-byte by both TLS peers. */
public class SecurePairingTranscript private constructor(encoded: ByteArray, digest: ByteArray) {
    private val encodedValue: ByteArray = encoded.copyOf()
    private val digestValue: ByteArray = digest.copyOf()
    @Volatile
    private var cleared: Boolean = false

    @Synchronized
    public fun encodedBytes(): ByteArray = if (cleared) ByteArray(0) else encodedValue.copyOf()

    @Synchronized
    public fun digestBytes(): ByteArray = if (cleared) ByteArray(0) else digestValue.copyOf()

    @Synchronized
    public fun clearSensitive() {
        encodedValue.fill(0)
        digestValue.fill(0)
        cleared = true
    }

    override fun toString(): String = "SecurePairingTranscript([redacted])"

    public companion object {
        public fun create(
            initiator: SecurePairingHello,
            responder: SecurePairingHello,
            tlsProtocol: String,
            tlsCipherSuite: String,
        ): SecurePairingTranscript? {
            if (initiator.role != SecurePeerRole.INITIATOR || responder.role != SecurePeerRole.RESPONDER ||
                initiator.secureSessionId != responder.secureSessionId ||
                initiator.controlSessionId != responder.controlSessionId ||
                initiator.localPeerInstanceId != responder.remotePeerInstanceId ||
                initiator.remotePeerInstanceId != responder.localPeerInstanceId ||
                initiator.selectedProtocolVersion != responder.selectedProtocolVersion ||
                initiator.selectedProtocolVersion !in initiator.protocolMinimumVersion..initiator.protocolMaximumVersion ||
                initiator.selectedProtocolVersion !in responder.protocolMinimumVersion..responder.protocolMaximumVersion ||
                initiator.discoveryVersion != responder.discoveryVersion ||
                initiator.controlVersion != responder.controlVersion ||
                initiator.secureVersion != responder.secureVersion ||
                initiator.capabilityVersion != responder.capabilityVersion ||
                initiator.selectedFeaturesMask != responder.selectedFeaturesMask ||
                initiator.maxRecordPlaintextBytes != responder.maxRecordPlaintextBytes ||
                initiator.maxRecordsPerDirection != responder.maxRecordsPerDirection ||
                initiator.maxBytesPerDirection != responder.maxBytesPerDirection ||
                initiator.sessionLifetimeMillis != responder.sessionLifetimeMillis ||
                tlsProtocol != TLS_1_3 || tlsCipherSuite !in TLS_1_3_CIPHER_SUITES
            ) return null

            val bytes = ByteBuffer.allocate(SecureSessionLimits.MAX_TRANSCRIPT_BYTES).order(ByteOrder.BIG_ENDIAN)
            val encoded = try {
                bytes.put(TRANSCRIPT_MAGIC)
                bytes.put(1.toByte())
                bytes.put(initiator.discoveryVersion.toByte())
                bytes.put(initiator.controlVersion.toByte())
                bytes.put(initiator.secureVersion.toByte())
                bytes.put(initiator.capabilityVersion.toByte())
                bytes.put(TRANSPORT_LAN_WIRE_ID.toByte())
                bytes.put(PART_B_SUITE_WIRE_ID.toByte())
                bytes.put(initiator.role.wireId.toByte())
                bytes.put(responder.role.wireId.toByte())
                putText(bytes, initiator.controlSessionId.value, 64)
                bytes.put(hexToBytes(initiator.secureSessionId.value, SESSION_ID_BYTES))
                putPeerId(bytes, initiator.localPeerInstanceId)
                putPeerId(bytes, initiator.remotePeerInstanceId)
                bytes.putShort(initiator.selectedProtocolVersion.toShort())
                bytes.putShort(initiator.protocolMinimumVersion.toShort())
                bytes.putShort(initiator.protocolMaximumVersion.toShort())
                bytes.putShort(responder.protocolMinimumVersion.toShort())
                bytes.putShort(responder.protocolMaximumVersion.toShort())
                bytes.putShort(initiator.selectedFeaturesMask.toShort())
                bytes.putShort(initiator.maxRecordPlaintextBytes.toShort())
                bytes.putInt(initiator.maxRecordsPerDirection.toInt())
                bytes.putInt(initiator.maxBytesPerDirection.toInt())
                bytes.putInt(initiator.sessionLifetimeMillis.toInt())
                putText(bytes, initiator.appVersion, 32)
                putText(bytes, responder.appVersion, 32)
                val initiatorNonce = initiator.nonceBytes()
                val responderNonce = responder.nonceBytes()
                val initiatorFingerprint = initiator.certificateFingerprintBytes()
                val responderFingerprint = responder.certificateFingerprintBytes()
                try {
                    bytes.put(initiatorNonce)
                    bytes.put(responderNonce)
                    bytes.put(initiatorFingerprint)
                    bytes.put(responderFingerprint)
                } finally {
                    initiatorNonce.fill(0)
                    responderNonce.fill(0)
                    initiatorFingerprint.fill(0)
                    responderFingerprint.fill(0)
                }
                putText(bytes, tlsProtocol, 8)
                putText(bytes, tlsCipherSuite, 48)
                bytes.array().copyOf(bytes.position())
            } catch (_: Exception) {
                return null
            } finally {
                bytes.array().fill(0)
            }
            val digest = try {
                MessageDigest.getInstance("SHA-256").digest(encoded)
            } catch (_: Exception) {
                encoded.fill(0)
                return null
            }
            return try {
                SecurePairingTranscript(encoded, digest)
            } finally {
                encoded.fill(0)
                digest.fill(0)
            }
        }

        public const val TLS_1_3: String = "TLSv1.3"
        public val TLS_1_3_CIPHER_SUITES: Set<String> = setOf(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
        )

        private fun putText(target: ByteBuffer, value: String, maxBytes: Int) {
            val encoded = value.toByteArray(Charsets.US_ASCII)
            require(encoded.isNotEmpty() && encoded.size <= maxBytes)
            require(encoded.all { (it.toInt() and 0xFF) in 0x21..0x7E })
            target.put(encoded.size.toByte())
            target.put(encoded)
        }
    }
}

/** The first encrypted record in each direction; it doubles as peer approval confirmation. */
public class SecureKeyConfirmation(role: SecurePeerRole, transcriptDigest: ByteArray) {
    public val role: SecurePeerRole = role
    private val digestValue: ByteArray = copyExact(
        transcriptDigest,
        SecureSessionLimits.TRANSCRIPT_HASH_BYTES,
        "key-confirmation digest",
    )

    @Synchronized
    public fun transcriptDigestBytes(): ByteArray = digestValue.copyOf()

    @Synchronized
    public fun clearSensitive() = digestValue.fill(0)

    override fun toString(): String = "SecureKeyConfirmation(role=${role.name}, digest=[redacted])"
}

public object SecureKeyConfirmationCodec {
    public const val FRAME_SIZE_BYTES: Int = SecureSessionLimits.KEY_CONFIRMATION_BYTES
    private const val WIRE_VERSION: Int = 1

    public fun encode(message: SecureKeyConfirmation): ByteArray {
        val result = ByteArray(FRAME_SIZE_BYTES)
        val buffer = ByteBuffer.wrap(result).order(ByteOrder.BIG_ENDIAN)
        buffer.put(KEY_CONFIRMATION_MAGIC)
        buffer.put(WIRE_VERSION.toByte())
        buffer.put(message.role.wireId.toByte())
        val digest = message.transcriptDigestBytes()
        try {
            buffer.put(digest)
        } finally {
            digest.fill(0)
        }
        return result
    }

    public fun decode(frame: ByteArray): SecureKeyConfirmation? {
        if (frame.size != FRAME_SIZE_BYTES || !KEY_CONFIRMATION_MAGIC.indices.all { frame[it] == KEY_CONFIRMATION_MAGIC[it] }) {
            return null
        }
        if ((frame[4].toInt() and 0xFF) != WIRE_VERSION) return null
        val role = SecurePeerRole.fromWireId(frame[5].toInt() and 0xFF) ?: return null
        val digest = frame.copyOfRange(6, frame.size)
        return try {
            SecureKeyConfirmation(role, digest)
        } finally {
            digest.fill(0)
        }
    }
}

private class Cursor(private val frame: ByteArray, start: Int, private val end: Int) {
    private var position: Int = start
    val atEnd: Boolean get() = position == end

    fun u8(): Int? = if (position < end) frame[position++].toInt() and 0xFF else null

    fun u16(): Int? {
        val high = u8() ?: return null
        val low = u8() ?: return null
        return (high shl 8) or low
    }

    fun u32(): Int? {
        val value = u16() ?: return null
        val low = u16() ?: return null
        return ((value.toLong() shl 16) or low.toLong()).takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    fun raw(length: Int): ByteArray? {
        if (length < 0 || length > end - position) return null
        val value = frame.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun sessionId(): SessionId? {
        val length = u8() ?: return null
        if (length !in 1..64 || length > end - position) return null
        val raw = raw(length) ?: return null
        if (raw.any { (it.toInt() and 0xFF) !in 0x21..0x7E }) return null
        return SessionId.orNull(String(raw, Charsets.US_ASCII))
    }

    fun hexSessionId(): SessionId? {
        val raw = raw(SESSION_ID_BYTES) ?: return null
        return SessionId.orNull(bytesToHex(raw))
    }

    fun peerId(): PeerInstanceId? {
        val raw = raw(PEER_ID_BYTES) ?: return null
        return PeerInstanceId.orNull(bytesToHex(raw))
    }

    fun asciiText(maxBytes: Int): String? {
        val length = u8() ?: return null
        if (length !in 1..maxBytes || length > end - position) return null
        val raw = raw(length) ?: return null
        if (raw.any { (it.toInt() and 0xFF) > 0x7F }) return null
        return String(raw, Charsets.US_ASCII)
    }
}

private fun putPeerId(buffer: ByteBuffer, peerId: PeerInstanceId) {
    buffer.put(hexToBytes(peerId.value, PEER_ID_BYTES))
}

private fun hexToBytes(value: String, byteLength: Int): ByteArray {
    require(value.length == byteLength * 2 && value.all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(byteLength) { index ->
        val high = value[index * 2].digitToInt(16)
        val low = value[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

private fun bytesToHex(value: ByteArray): String {
    val alphabet = "0123456789abcdef"
    val result = CharArray(value.size * 2)
    for (index in value.indices) {
        val number = value[index].toInt() and 0xFF
        result[index * 2] = alphabet[number ushr 4]
        result[index * 2 + 1] = alphabet[number and 0x0F]
    }
    return String(result)
}

private fun readU16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
