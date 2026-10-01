package app.morsecode.core.transfer.protocol

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Crc32

/*
 * The wire envelope.
 *
 * Layout (network / big-endian, 44-byte fixed header followed by three bounded
 * identifier fields and the payload):
 *
 *   0  .. 3   magic 'MSC1'
 *   4         protocol version
 *   5         frame type
 *   6  .. 7   flags (reserved, must be zero)
 *   8  .. 11  payload length (unsigned 32-bit)
 *   12 .. 19  sequence (signed 64-bit, -1 when not applicable)
 *   20 .. 27  byte offset (signed 64-bit, -1 when not applicable)
 *   28 .. 31  payload CRC32
 *   32        session id length
 *   33        transfer id length
 *   34        recipient id length
 *   35        reserved (must be zero)
 *   36 .. 39  reserved (must be zero)
 *   40 .. 43  header CRC32 over bytes 0..39
 *   44 ..     session id (UTF-8), transfer id (UTF-8), recipient id (UTF-8),
 *             payload
 *
 * The header CRC32 is checked before any length field is trusted, because a
 * corrupt length is the cheapest way to make a decoder allocate something
 * enormous. The payload CRC32 is checked for every frame type, not just
 * DATA_CHUNK: a flipped byte in a descriptor is just as dangerous as a flipped
 * byte in a chunk.
 */

/** Value used for [sequence] and [offset] when a frame does not carry one. */
public const val NOT_APPLICABLE: Long = -1L

/** A decoded, validated frame envelope. */
public data class TransferFrame(
    public val version: ProtocolVersion,
    public val type: FrameType,
    public val flags: Int,
    public val sessionId: SessionId?,
    public val transferId: TransferId?,
    public val recipientId: RecipientId?,
    /** Chunk sequence, or [NOT_APPLICABLE]. */
    public val sequence: Long,
    /** Byte offset the frame refers to, or [NOT_APPLICABLE]. */
    public val offset: Long,
    /** Raw bytes; may be empty, never larger than the negotiated maximum. */
    public val payload: ByteArray,
    /** CRC32 of [payload] as it was computed by the sender. */
    public val payloadCrc32: Int,
) {
    init {
        require(flags == 0) { "flags must be zero in protocol version 1, was $flags" }
        require(payload.size <= ProtocolLimits.MAX_PAYLOAD_BYTES) {
            "payload of ${payload.size} bytes exceeds ${ProtocolLimits.MAX_PAYLOAD_BYTES}"
        }
        require(sequence >= NOT_APPLICABLE) { "sequence must be -1 or greater, was $sequence" }
        require(offset >= NOT_APPLICABLE) { "offset must be -1 or greater, was $offset" }
    }

    /** True when the payload still carries the CRC32 the header claims. */
    public fun payloadIsIntact(): Boolean =
        Crc32.compute(payload) == payloadCrc32

    /** Byte-array aware comparison; `data class` equality on a ByteArray is identity. */
    public fun payloadEquals(other: TransferFrame): Boolean =
        payload.contentEquals(other.payload)

    /** Total encoded size, header plus identifiers plus payload. */
    public fun encodedSize(): Int = ProtocolLimits.HEADER_SIZE_BYTES +
        utf8Length(sessionId?.value) +
        utf8Length(transferId?.value) +
        utf8Length(recipientId?.value) +
        payload.size

    public companion object {
        private fun utf8Length(text: String?): Int =
            if (text == null) 0 else text.toByteArray(Charsets.UTF_8).size
    }
}

/** Result of decoding one frame off a stream. */
public sealed class FrameDecodeResult {
    /** A complete, validated frame; [consumedBytes] tells the caller where to continue. */
    public data class Success(
        public val frame: TransferFrame,
        public val consumedBytes: Int,
    ) : FrameDecodeResult()

    /**
     * A frame that could not be accepted.
     *
     * [fatal] means the stream is desynchronised and the connection must be
     * closed: the byte count could not be established, so there is no safe place
     * to resume reading. When [fatal] is false the caller can skip
     * [bytesConsumed] bytes and keep going.
     */
    public data class Invalid(
        public val error: app.morsecode.core.transfer.error.TransferError,
        public val bytesConsumed: Int,
        public val fatal: Boolean,
    ) : FrameDecodeResult()
}
