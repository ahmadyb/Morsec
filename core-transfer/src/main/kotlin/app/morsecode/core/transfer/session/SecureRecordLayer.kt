package app.morsecode.core.transfer.session

import app.morsecode.core.transfer.identity.SessionId
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val SECURE_RECORD_HEADER_SIZE: Int = 33
private const val RECORD_SESSION_ID_BYTES: Int = 16
private const val RECORD_TAG_SIZE_BYTES: Int = 16
private val RECORD_MAGIC = byteArrayOf(0x4D, 0x53, 0x52, 0x31) // MSR1

private fun copyGcmNonce(value: ByteArray): ByteArray {
    require(value.size == SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
    return value.copyOf()
}

private fun copyBoundedRecordPayload(value: ByteArray): ByteArray {
    require(value.size <= SecureSessionLimits.MAX_RECORD_PLAINTEXT_BYTES)
    return value.copyOf()
}

public enum class SecureRecordDirection(public val wireId: Int) {
    INITIATOR_TO_RESPONDER(1),
    RESPONDER_TO_INITIATOR(2);

    public val opposite: SecureRecordDirection
        get() = if (this == INITIATOR_TO_RESPONDER) RESPONDER_TO_INITIATOR else INITIATOR_TO_RESPONDER

    public companion object {
        public fun fromWireId(value: Int): SecureRecordDirection? = entries.firstOrNull { it.wireId == value }
    }
}

/** Part B deliberately has no file/data record type. */
public enum class SecureRecordType(public val wireId: Int) {
    KEY_CONFIRMATION(1),
    SESSION_CLOSE(2),
    PING(3),
    PONG(4);

    public companion object {
        public fun fromWireId(value: Int): SecureRecordType? = entries.firstOrNull { it.wireId == value }
    }
}

/** JCA-backed AEAD boundary; the transport module supplies explicit Conscrypt AES-GCM. */
public interface SecureRecordAead : AutoCloseable {
    public fun encrypt(nonce: ByteArray, associatedData: ByteArray, plaintext: ByteArray): ByteArray
    public fun decrypt(nonce: ByteArray, associatedData: ByteArray, ciphertext: ByteArray): ByteArray?
    override fun close()
}

public class SecureControlRecord(type: SecureRecordType, payload: ByteArray) {
    public val type: SecureRecordType = type
    private val payloadValue: ByteArray = copyBoundedRecordPayload(payload)

    init {
        when (type) {
            SecureRecordType.KEY_CONFIRMATION -> require(payloadValue.size == SecureKeyConfirmationCodec.FRAME_SIZE_BYTES)
            SecureRecordType.SESSION_CLOSE, SecureRecordType.PING, SecureRecordType.PONG -> require(payloadValue.isEmpty())
        }
    }

    public fun payloadBytes(): ByteArray = payloadValue.copyOf()

    public fun clearSensitive() = payloadValue.fill(0)

    override fun toString(): String = "SecureControlRecord(type=${type.name}, payload=[redacted])"
}

public data class SecureRecordHeader(
    public val sessionId: SessionId,
    public val direction: SecureRecordDirection,
    public val sequenceNumber: Long,
    public val type: SecureRecordType,
    public val plaintextLength: Int,
) {
    public val frameLength: Int get() = SecureRecordCodec.HEADER_SIZE_BYTES + plaintextLength + SecureSessionLimits.AEAD_TAG_BYTES
}

public sealed interface SecureRecordResult {
    public class Encoded(frame: ByteArray) : SecureRecordResult {
        private val frameValue: ByteArray = frame.copyOf()
        public fun frameBytes(): ByteArray = frameValue.copyOf()
        override fun toString(): String = "SecureRecordResult.Encoded([redacted])"
    }

    public class Decoded(public val record: SecureControlRecord) : SecureRecordResult
    public class Failed(public val failure: SessionFailure) : SecureRecordResult
}

/** MSR1 record framing. The exact header bytes are AEAD associated data. */
public object SecureRecordCodec {
    public const val HEADER_SIZE_BYTES: Int = SECURE_RECORD_HEADER_SIZE
    public const val TAG_SIZE_BYTES: Int = RECORD_TAG_SIZE_BYTES
    public const val MAX_RECORD_PLAINTEXT_BYTES: Int = SecureSessionLimits.MAX_RECORD_PLAINTEXT_BYTES
    public const val MAX_FRAME_SIZE_BYTES: Int =
        HEADER_SIZE_BYTES + MAX_RECORD_PLAINTEXT_BYTES + TAG_SIZE_BYTES
    public const val WIRE_VERSION: Int = 1

    public fun encodeHeader(
        sessionId: SessionId,
        direction: SecureRecordDirection,
        sequenceNumber: Long,
        type: SecureRecordType,
        plaintextLength: Int,
    ): ByteArray {
        require(sessionId.value.matches(Regex("[0-9a-f]{32}"))) { "secure session id is invalid" }
        require(sequenceNumber in 0 until SecureSessionLimits.MAX_RECORDS_PER_DIRECTION) {
            "record sequence is outside its lifetime bound"
        }
        require(plaintextLength in 0..MAX_RECORD_PLAINTEXT_BYTES) { "record payload exceeds its bound" }
        validateTypeLength(type, plaintextLength)
        val header = ByteArray(HEADER_SIZE_BYTES)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.put(RECORD_MAGIC)
        buffer.put(WIRE_VERSION.toByte())
        buffer.put(hexToBytes(sessionId.value))
        buffer.put(direction.wireId.toByte())
        buffer.putLong(sequenceNumber)
        buffer.put(type.wireId.toByte())
        buffer.putShort(plaintextLength.toShort())
        return header
    }

    /** Called on the fixed header before a stream reader allocates ciphertext. */
    public fun decodeHeader(
        header: ByteArray,
        expectedSessionId: SessionId,
        expectedDirection: SecureRecordDirection,
    ): SecureRecordHeader? {
        if (header.size != HEADER_SIZE_BYTES || !RECORD_MAGIC.indices.all { header[it] == RECORD_MAGIC[it] }) {
            return null
        }
        if ((header[4].toInt() and 0xFF) != WIRE_VERSION) return null
        if (bytesToHex(header, 5, RECORD_SESSION_ID_BYTES) != expectedSessionId.value) return null
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.position(21)
        val direction = SecureRecordDirection.fromWireId(buffer.get().toInt() and 0xFF) ?: return null
        if (direction != expectedDirection) return null
        val sequence = buffer.long
        if (sequence !in 0 until SecureSessionLimits.MAX_RECORDS_PER_DIRECTION) return null
        val type = SecureRecordType.fromWireId(buffer.get().toInt() and 0xFF) ?: return null
        val length = buffer.short.toInt() and 0xFFFF
        if (length !in 0..MAX_RECORD_PLAINTEXT_BYTES) return null
        if (runCatching { validateTypeLength(type, length) }.isFailure) return null
        return SecureRecordHeader(expectedSessionId, direction, sequence, type, length)
    }

    public fun assemble(header: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
        require(header.size == HEADER_SIZE_BYTES) { "record header has the wrong size" }
        require(ciphertextAndTag.size <= MAX_RECORD_PLAINTEXT_BYTES + TAG_SIZE_BYTES) {
            "record ciphertext exceeds its bound"
        }
        val declaredLength = readU16(header, 31)
        require(ciphertextAndTag.size == declaredLength + TAG_SIZE_BYTES) {
            "record ciphertext length does not match the header"
        }
        val frame = ByteArray(header.size + ciphertextAndTag.size)
        header.copyInto(frame)
        ciphertextAndTag.copyInto(frame, header.size)
        return frame
    }

    private fun validateTypeLength(type: SecureRecordType, length: Int) {
        when (type) {
            SecureRecordType.KEY_CONFIRMATION -> require(length == SecureKeyConfirmationCodec.FRAME_SIZE_BYTES)
            SecureRecordType.SESSION_CLOSE, SecureRecordType.PING, SecureRecordType.PONG -> require(length == 0)
        }
    }
}

/**
 * Strict duplex record state. Sequence numbers never wrap; reaching any bound
 * destroys directional key material and closes the layer before another nonce
 * can be used.
 */
public class SecureRecordLayer(
    public val sessionId: SessionId,
    private val localRole: SecurePeerRole,
    sendAead: SecureRecordAead,
    receiveAead: SecureRecordAead,
    sendNonceBase: ByteArray,
    receiveNonceBase: ByteArray,
    private val clock: MonotonicClock,
) : AutoCloseable {
    private val sender: SecureRecordAead = sendAead
    private val receiver: SecureRecordAead = receiveAead
    private val sendIv: ByteArray = copyGcmNonce(sendNonceBase)
    private val receiveIv: ByteArray = copyGcmNonce(receiveNonceBase)
    private val sendDirection: SecureRecordDirection =
        if (localRole == SecurePeerRole.INITIATOR) {
            SecureRecordDirection.INITIATOR_TO_RESPONDER
        } else {
            SecureRecordDirection.RESPONDER_TO_INITIATOR
        }
    private val receiveDirection: SecureRecordDirection = sendDirection.opposite
    private val createdAtElapsedMillis: Long = clock.nowMillis()
    private var lastNowElapsedMillis: Long = createdAtElapsedMillis
    private var sendSequence: Long = 0L
    private var receiveSequence: Long = 0L
    private var sentBytes: Long = 0L
    private var receivedBytes: Long = 0L
    private var isClosed: Boolean = false

    init {
        require(sessionId.value.matches(Regex("[0-9a-f]{32}"))) { "secure session id is invalid" }
        require(sendIv.size == SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
        require(receiveIv.size == SecureSessionLimits.NONCE_BYTES_FOR_AES_GCM)
        require(createdAtElapsedMillis >= 0L) { "monotonic clock returned a negative value" }
    }

    @Synchronized
    public fun protect(type: SecureRecordType, plaintext: ByteArray): SecureRecordResult {
        if (isClosed) return failed(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        if (validNow() == null) return failAndClose(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        if (sendSequence >= SecureSessionLimits.MAX_RECORDS_PER_DIRECTION ||
            sentBytes > SecureSessionLimits.MAX_BYTES_PER_DIRECTION - plaintext.size.toLong()
        ) return failAndClose(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        if ((sendSequence == 0L && type != SecureRecordType.KEY_CONFIRMATION) ||
            (sendSequence > 0L && type == SecureRecordType.KEY_CONFIRMATION)
        ) return failAndClose(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        if (plaintext.size > SecureRecordCodec.MAX_RECORD_PLAINTEXT_BYTES ||
            runCatching { validateTypeLength(type, plaintext.size) }.isFailure
        ) return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)

        val header = try {
            SecureRecordCodec.encodeHeader(sessionId, sendDirection, sendSequence, type, plaintext.size)
        } catch (_: RuntimeException) {
            return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        }
        val nonce = nonceFor(sendIv, sendSequence)
        val ciphertext = try {
            sender.encrypt(nonce, header, plaintext)
        } catch (_: Exception) {
            return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        } finally {
            nonce.fill(0)
        }
        if (ciphertext.size != plaintext.size + SecureRecordCodec.TAG_SIZE_BYTES) {
            ciphertext.fill(0)
            return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        }
        val frame = try {
            SecureRecordCodec.assemble(header, ciphertext)
        } catch (_: RuntimeException) {
            ciphertext.fill(0)
            return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        }
        ciphertext.fill(0)
        val encoded = SecureRecordResult.Encoded(frame)
        frame.fill(0)
        sendSequence++
        sentBytes += plaintext.size.toLong()
        if (type == SecureRecordType.SESSION_CLOSE) close()
        return encoded
    }

    @Synchronized
    public fun unprotect(frame: ByteArray): SecureRecordResult {
        if (isClosed) return failed(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        if (validNow() == null) return failAndClose(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        if (frame.size < SecureRecordCodec.HEADER_SIZE_BYTES + SecureRecordCodec.TAG_SIZE_BYTES ||
            frame.size > SecureRecordCodec.MAX_FRAME_SIZE_BYTES
        ) return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        val headerBytes = frame.copyOfRange(0, SecureRecordCodec.HEADER_SIZE_BYTES)
        val header = SecureRecordCodec.decodeHeader(headerBytes, sessionId, receiveDirection)
            ?: run {
                headerBytes.fill(0)
                return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
            }
        if (receiveSequence >= SecureSessionLimits.MAX_RECORDS_PER_DIRECTION ||
            receivedBytes > SecureSessionLimits.MAX_BYTES_PER_DIRECTION - header.plaintextLength.toLong()
        ) {
            headerBytes.fill(0)
            return failAndClose(SessionFailureCode.SECURE_SESSION_LIMIT_REACHED)
        }
        if (header.sequenceNumber != receiveSequence || frame.size != header.frameLength) {
            headerBytes.fill(0)
            return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        }
        if ((receiveSequence == 0L && header.type != SecureRecordType.KEY_CONFIRMATION) ||
            (receiveSequence > 0L && header.type == SecureRecordType.KEY_CONFIRMATION)
        ) {
            headerBytes.fill(0)
            return failAndClose(SessionFailureCode.SECURE_SESSION_CONFIRMATION_FAILED)
        }
        val ciphertext = frame.copyOfRange(SecureRecordCodec.HEADER_SIZE_BYTES, frame.size)
        val nonce = nonceFor(receiveIv, receiveSequence)
        val plaintext = try {
            receiver.decrypt(nonce, headerBytes, ciphertext)
        } catch (_: Exception) {
            null
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
            headerBytes.fill(0)
        } ?: return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        if (plaintext.size != header.plaintextLength ||
            runCatching { validateTypeLength(header.type, plaintext.size) }.isFailure
        ) {
            plaintext.fill(0)
            return failAndClose(SessionFailureCode.SECURE_SESSION_RECORD_INVALID)
        }
        receiveSequence++
        receivedBytes += plaintext.size.toLong()
        val record = SecureControlRecord(header.type, plaintext)
        plaintext.fill(0)
        val decoded = SecureRecordResult.Decoded(record)
        if (header.type == SecureRecordType.SESSION_CLOSE) close()
        return decoded
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        try {
            sender.close()
        } catch (_: RuntimeException) {
            // Key wiping remains best-effort; no provider detail is surfaced.
        }
        try {
            receiver.close()
        } catch (_: RuntimeException) {
            // The second direction must still be destroyed if the first close fails.
        }
        sendIv.fill(0)
        receiveIv.fill(0)
    }

    private fun validNow(): Long? {
        if (isClosed) return null
        val now = try {
            clock.nowMillis()
        } catch (_: RuntimeException) {
            return null
        }
        if (now < lastNowElapsedMillis || now < createdAtElapsedMillis) return null
        lastNowElapsedMillis = now
        if (now - createdAtElapsedMillis >= SecureSessionLimits.MAX_SESSION_LIFETIME_MILLIS) return null
        return now
    }

    private fun failAndClose(code: SessionFailureCode): SecureRecordResult.Failed {
        close()
        return failed(code)
    }

    private fun failed(code: SessionFailureCode): SecureRecordResult.Failed =
        SecureRecordResult.Failed(SessionFailure(code))

    private fun nonceFor(base: ByteArray, sequence: Long): ByteArray {
        val nonce = base.copyOf()
        for (index in 0 until Long.SIZE_BYTES) {
            val shift = (Long.SIZE_BYTES - 1 - index) * 8
            val sequenceByte = ((sequence ushr shift) and 0xFF).toByte()
            val nonceOffset = nonce.size - Long.SIZE_BYTES + index
            nonce[nonceOffset] = (nonce[nonceOffset].toInt() xor sequenceByte.toInt()).toByte()
        }
        return nonce
    }

    private fun validateTypeLength(type: SecureRecordType, length: Int) {
        when (type) {
            SecureRecordType.KEY_CONFIRMATION -> require(length == SecureKeyConfirmationCodec.FRAME_SIZE_BYTES)
            SecureRecordType.SESSION_CLOSE, SecureRecordType.PING, SecureRecordType.PONG -> require(length == 0)
        }
    }

    public companion object {
        public fun directionFrom(role: SecurePeerRole): SecureRecordDirection =
            if (role == SecurePeerRole.INITIATOR) {
                SecureRecordDirection.INITIATOR_TO_RESPONDER
            } else {
                SecureRecordDirection.RESPONDER_TO_INITIATOR
            }
    }
}

private fun readU16(value: ByteArray, offset: Int): Int =
    ((value[offset].toInt() and 0xFF) shl 8) or (value[offset + 1].toInt() and 0xFF)

private fun hexToBytes(value: String): ByteArray {
    require(value.matches(Regex("[0-9a-f]{32}")))
    return ByteArray(RECORD_SESSION_ID_BYTES) { index ->
        val high = value[index * 2].digitToInt(16)
        val low = value[index * 2 + 1].digitToInt(16)
        ((high shl 4) or low).toByte()
    }
}

private fun bytesToHex(value: ByteArray, offset: Int, length: Int): String {
    val alphabet = "0123456789abcdef"
    val result = CharArray(length * 2)
    for (index in 0 until length) {
        val number = value[offset + index].toInt() and 0xFF
        result[index * 2] = alphabet[number ushr 4]
        result[index * 2 + 1] = alphabet[number and 0x0F]
    }
    return String(result)
}
