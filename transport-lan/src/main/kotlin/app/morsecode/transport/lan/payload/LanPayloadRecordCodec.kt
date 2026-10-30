package app.morsecode.transport.lan.payload

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.SecureRecordDirection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The MPL1 record codec.
 *
 * SEQUENCE POLICY, SPELLED OUT BECAUSE A WRAPPED VALUE IS A REPLAY
 *   The sequence is a signed 64-bit field restricted to non-negative values. It is written with
 *   `putLong` and read with `long`, so a peer that sends 0x8000000000000000 produces a negative
 *   Kotlin Long, and that is refused rather than reinterpreted as a large unsigned number. There is
 *   no wraparound: values at or above 2^63 are not representable, and the decoder additionally
 *   requires the exact value it expected, so a record can be neither replayed nor reordered. The
 *   upper bound on how many records one direction may carry belongs to the payload session that
 *   will own the keys, not to this frame layer; what this layer guarantees is that the number never
 *   becomes negative and never silently differs from the expected one.
 *
 * WHAT A REFUSAL IS
 *   Every rejection is a typed value carrying a reason. Nothing throws on wire input: a caller can
 *   hand this codec any bytes at all and receive a decision. The only throwing entry points are the
 *   encoders, where the argument is a local value the caller assembled and a failure is a
 *   programming error rather than a hostile peer.
 *
 * ALLOCATION DISCIPLINE
 *   A declared length is read as an unsigned 32-bit value into a Long, compared against the fixed
 *   maximum, and only then narrowed. The body and tag are copied out of the received frame, so the
 *   largest allocation this codec makes is bounded by the frame the caller already holds, and that
 *   frame is itself refused above MAX_PAYLOAD_RECORD_WIRE_BYTES before any of this runs.
 *
 * STATUS
 *   Inert framing. No keys, no encryption, no socket, no authority, no production caller. The
 *   sixteen trailing bytes are carried, not verified; authentication begins only when a later slice
 *   supplies the key schedule and the payload authority that gates it.
 */

/**
 * One payload record, as a value.
 *
 * [body] is the opaque plaintext this layer carries. It is one MSC1 frame in the protocol that will
 * eventually use this envelope, but this type does not parse it, does not validate it and does not
 * care: a zero-length body is a structurally valid envelope even though no meaningful MSC1 frame is
 * empty, and refusing that belongs to the frame decoder rather than to the envelope.
 */
internal class LanPayloadRecord internal constructor(
    val sessionId: SessionId,
    val direction: SecureRecordDirection,
    val sequence: Long,
    body: ByteArray,
    authenticationTag: ByteArray,
) {
    private val bodyValue: ByteArray = body.copyOf()
    private val tagValue: ByteArray = authenticationTag.copyOf()

    init {
        require(sequence >= 0L) { "payload record sequence must not be negative, was $sequence" }
        require(bodyValue.size <= LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES) {
            "payload record body of ${bodyValue.size} bytes exceeds " +
                LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES
        }
        require(tagValue.size == LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES) {
            "payload record tag must be ${LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES} bytes, " +
                "was ${tagValue.size}"
        }
    }

    /** The body bytes, as a copy: the record keeps the only live reference. */
    fun bodyBytes(): ByteArray = bodyValue.copyOf()

    /** The trailing tag bytes, as a copy. They are carried, not checked, at this layer. */
    fun tagBytes(): ByteArray = tagValue.copyOf()

    /** Wipes the body. The tag is not secret and is left alone. */
    fun clearSensitive() {
        bodyValue.fill(0)
    }

    override fun toString(): String =
        "LanPayloadRecord(direction=$direction, sequence=$sequence, " +
            "bodyBytes=${bodyValue.size}, ids=[redacted])"
}

/** A validated MPL1 header, with the declared body length already narrowed. */
internal data class LanPayloadHeader(
    val sessionId: SessionId,
    val direction: SecureRecordDirection,
    val sequence: Long,
    val declaredLength: Int,
) {
    override fun toString(): String =
        "LanPayloadHeader(direction=$direction, sequence=$sequence, " +
            "declaredLength=$declaredLength, ids=[redacted])"
}

/** Why a header or a whole record was refused. */
internal enum class LanPayloadRefusal {
    /** Fewer bytes arrived than a fixed header and a tag, so no record can be formed at all. */
    TRUNCATED,

    /** The first four bytes are not 'MPL1'. A control record also lands here. */
    WRONG_MAGIC,

    /** The version byte is not the one version this build speaks. */
    UNSUPPORTED_VERSION,

    /** The direction byte is neither 1 nor 2. */
    UNKNOWN_DIRECTION,

    /** The session id is not the id this session is bound to. */
    SESSION_MISMATCH,

    /** The record travels the other way: a reflected record is refused here. */
    DIRECTION_MISMATCH,

    /** The sequence is negative, which on this wire means a value at or above 2^63. */
    SEQUENCE_OUT_OF_RANGE,

    /** The sequence does not equal the next one expected, so it is a replay or a reordering. */
    UNEXPECTED_SEQUENCE,

    /** The declared length is above the fixed body maximum. */
    LENGTH_ABOVE_MAXIMUM,

    /** The header declared a body and tag the frame does not fully contain. */
    LENGTH_INCONSISTENT,

    /** More bytes arrived than the header declared. */
    TRAILING_BYTES,

    /** The offered frame is larger than any record this layer can produce. */
    WIRE_SIZE_ABOVE_MAXIMUM,
}

/** The outcome of decoding a fixed header. */
internal sealed interface LanPayloadHeaderResult {
    class Decoded(val header: LanPayloadHeader) : LanPayloadHeaderResult
    class Refused(val reason: LanPayloadRefusal) : LanPayloadHeaderResult {
        override fun toString(): String = "LanPayloadHeaderResult.Refused($reason)"
    }
}

/** The outcome of decoding a whole record. */
internal sealed interface LanPayloadDecodeResult {
    class Decoded(val record: LanPayloadRecord) : LanPayloadDecodeResult
    class Refused(val reason: LanPayloadRefusal) : LanPayloadDecodeResult {
        override fun toString(): String = "LanPayloadDecodeResult.Refused($reason)"
    }
}

/** MPL1 framing. The whole 34-byte header is the associated data a later slice will cover. */
internal object LanPayloadRecordCodec {

    /**
     * Builds a fixed header.
     *
     * @throws IllegalArgumentException for a negative sequence, a body length outside
     *   `0..MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES`, or a session id that is not the thirty-two-character form.
     */
    fun encodeHeader(
        sessionId: SessionId,
        direction: SecureRecordDirection,
        sequence: Long,
        declaredLength: Int,
    ): ByteArray {
        require(sequence >= 0L) { "payload record sequence must not be negative, was $sequence" }
        require(declaredLength in 0..LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES) {
            "payload record body of $declaredLength bytes is outside " +
                "0..${LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES}"
        }
        val sessionBytes = LanPayloadRecordFormat.requireSessionIdBytes(sessionId)
        try {
            val header = ByteArray(LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES)
            LanPayloadRecordFormat.magicBytes().copyInto(header, LanPayloadRecordFormat.OFF_MAGIC)
            header[LanPayloadRecordFormat.OFF_VERSION] = LanPayloadRecordFormat.WIRE_VERSION.toByte()
            sessionBytes.copyInto(header, LanPayloadRecordFormat.OFF_SESSION_ID)
            header[LanPayloadRecordFormat.OFF_DIRECTION] = direction.wireId.toByte()
            // Big-endian is the buffer default; it is stated rather than assumed because the
            // arithmetic in the layout comment is only true in one byte order.
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            buffer.position(LanPayloadRecordFormat.OFF_SEQUENCE)
            buffer.putLong(sequence)
            buffer.position(LanPayloadRecordFormat.OFF_DECLARED_LENGTH)
            buffer.putInt(declaredLength)
            return header
        } finally {
            sessionBytes.fill(0)
        }
    }

    /** Builds a whole wire record: header, body, tag. */
    fun encode(record: LanPayloadRecord): ByteArray {
        val body = record.bodyBytes()
        val tag = record.tagBytes()
        try {
            val header = encodeHeader(record.sessionId, record.direction, record.sequence, body.size)
            val wire = ByteArray(header.size + body.size + tag.size)
            header.copyInto(wire)
            body.copyInto(wire, header.size)
            tag.copyInto(wire, header.size + body.size)
            return wire
        } finally {
            body.fill(0)
            tag.fill(0)
        }
    }

    /**
     * Decodes a fixed header against this session's expectations.
     *
     * The checks run in the order a reader must run them: length first (nothing else can be trusted
     * from a short buffer), then the four bytes that identify the format, then the version, then
     * the identity of the session, then the direction, then the sequence, and only last the
     * declared length. A hostile buffer can therefore never make this read past its own end, and a
     * reflected or replayed record is refused before its length is even considered.
     */
    fun decodeHeader(
        header: ByteArray,
        expectedSessionId: SessionId,
        expectedDirection: SecureRecordDirection,
        expectedSequence: Long,
    ): LanPayloadHeaderResult {
        require(expectedSequence >= 0L) {
            "the expected payload sequence must not be negative, was $expectedSequence"
        }
        if (header.size != LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES) {
            return refuseHeader(LanPayloadRefusal.TRUNCATED)
        }
        if (!startsWith(header, LanPayloadRecordFormat.magicBytes(), LanPayloadRecordFormat.OFF_MAGIC)) {
            return refuseHeader(LanPayloadRefusal.WRONG_MAGIC)
        }
        if ((header[LanPayloadRecordFormat.OFF_VERSION].toInt() and 0xFF) !=
            LanPayloadRecordFormat.WIRE_VERSION
        ) {
            return refuseHeader(LanPayloadRefusal.UNSUPPORTED_VERSION)
        }
        val expectedSession = LanPayloadRecordFormat.requireSessionIdBytes(expectedSessionId)
        val sessionMatches = try {
            startsWith(header, expectedSession, LanPayloadRecordFormat.OFF_SESSION_ID)
        } finally {
            expectedSession.fill(0)
        }
        if (!sessionMatches) return refuseHeader(LanPayloadRefusal.SESSION_MISMATCH)

        val direction = SecureRecordDirection
            .fromWireId(header[LanPayloadRecordFormat.OFF_DIRECTION].toInt() and 0xFF)
            ?: return refuseHeader(LanPayloadRefusal.UNKNOWN_DIRECTION)
        if (direction != expectedDirection) return refuseHeader(LanPayloadRefusal.DIRECTION_MISMATCH)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.position(LanPayloadRecordFormat.OFF_SEQUENCE)
        val sequence = buffer.long
        // A value at or above 2^63 arrives as a negative Long. Refusing it here is what keeps the
        // sequence ordinal; masking it into a large positive number would make the replay check
        // compare two different numbers and pass.
        if (sequence < 0L) return refuseHeader(LanPayloadRefusal.SEQUENCE_OUT_OF_RANGE)
        if (sequence != expectedSequence) return refuseHeader(LanPayloadRefusal.UNEXPECTED_SEQUENCE)

        // Unsigned: `int` would sign-extend 0xFFFFFFFF into -1 before it was ever compared.
        val declaredLength = buffer.int.toLong() and 0xFFFF_FFFFL
        if (declaredLength > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES.toLong()) {
            return refuseHeader(LanPayloadRefusal.LENGTH_ABOVE_MAXIMUM)
        }
        return LanPayloadHeaderResult.Decoded(
            LanPayloadHeader(
                sessionId = expectedSessionId,
                direction = direction,
                sequence = sequence,
                declaredLength = declaredLength.toInt(),
            ),
        )
    }

    /**
     * Decodes a whole wire record.
     *
     * The frame must be exactly the declared size: a short frame is [LanPayloadRefusal.TRUNCATED]
     * and a long one is [LanPayloadRefusal.TRAILING_BYTES]. Accepting slack here would mean a
     * stream reader could not tell where the next record begins.
     */
    fun decode(
        frame: ByteArray,
        expectedSessionId: SessionId,
        expectedDirection: SecureRecordDirection,
        expectedSequence: Long,
    ): LanPayloadDecodeResult {
        val minimum = LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES.toLong() + LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES
        if (frame.size > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES) {
            return LanPayloadDecodeResult.Refused(LanPayloadRefusal.WIRE_SIZE_ABOVE_MAXIMUM)
        }
        if (frame.size.toLong() < minimum) {
            return LanPayloadDecodeResult.Refused(LanPayloadRefusal.TRUNCATED)
        }
        val headerBytes = frame.copyOfRange(0, LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES)
        val headerResult = decodeHeader(
            headerBytes,
            expectedSessionId,
            expectedDirection,
            expectedSequence,
        )
        if (headerResult is LanPayloadHeaderResult.Refused) {
            return LanPayloadDecodeResult.Refused(headerResult.reason)
        }
        val header = (headerResult as LanPayloadHeaderResult.Decoded).header
        val expectedWire = wireSizeFor(header.declaredLength.toLong())
        if (expectedWire == null) {
            // Unreachable: decodeHeader already bounded the length, and this keeps the compiler
            // from treating the nullable result as a length.
            return LanPayloadDecodeResult.Refused(LanPayloadRefusal.LENGTH_ABOVE_MAXIMUM)
        }
        if (frame.size.toLong() < expectedWire) {
            return LanPayloadDecodeResult.Refused(LanPayloadRefusal.LENGTH_INCONSISTENT)
        }
        if (frame.size.toLong() > expectedWire) {
            return LanPayloadDecodeResult.Refused(LanPayloadRefusal.TRAILING_BYTES)
        }
        val bodyStart = LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES
        val bodyEnd = bodyStart + header.declaredLength
        return LanPayloadDecodeResult.Decoded(
            LanPayloadRecord(
                sessionId = header.sessionId,
                direction = header.direction,
                sequence = header.sequence,
                body = frame.copyOfRange(bodyStart, bodyEnd),
                authenticationTag = frame.copyOfRange(bodyEnd, frame.size),
            ),
        )
    }

    /**
     * The wire size a declared body length implies, or null when that length is not addressable.
     *
     * Expressed as a Long so a caller can compare it against a frame size without narrowing
     * anything first.
     */
    fun wireSizeFor(declaredLength: Long): Long? {
        if (declaredLength < 0L || declaredLength > LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES) {
            return null
        }
        return LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES.toLong() +
            declaredLength +
            LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES
    }

    private fun refuseHeader(reason: LanPayloadRefusal): LanPayloadHeaderResult =
        LanPayloadHeaderResult.Refused(reason)

    private fun startsWith(bytes: ByteArray, prefix: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset + prefix.size > bytes.size) return false
        for (index in prefix.indices) {
            if (bytes[offset + index] != prefix[index]) return false
        }
        return true
    }
}
