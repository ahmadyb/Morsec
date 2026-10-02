package app.morsecode.core.transfer.protocol

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Crc32
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.model.TransferFileDescriptor
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/*
 * The version-1 binary codec.
 *
 * Two rules are enforced without exception, and they are the reason this file is
 * longer than "write an Int, read an Int":
 *
 *   1. No allocation whose size came off the wire until that size has been
 *      bounded against ProtocolLimits. Every length is read as a Long, range
 *      checked, and only then converted with ProtocolLimits.checkedToInt.
 *   2. Every decode failure is a typed TransferError, never an exception that
 *      escapes, and never a partially populated object. A caller that receives
 *      Success knows every field was validated.
 *
 * Byte order is explicit big-endian throughout and text is explicit UTF-8 with
 * malformed input reported rather than replaced, so a decoder on any platform
 * agrees with a decoder on any other.
 */

/** Thrown internally while parsing; always caught and converted to a typed error. */
private class FrameFormat(val error: TransferError) :
    RuntimeException(error.describe(), null, false, false)

/** Growable big-endian output buffer used to build payloads. */
private class ByteWriter(initialCapacity: Int = 64) {
    private var data = ByteArray(initialCapacity.coerceAtLeast(16))
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra <= data.size) return
        var capacity = data.size
        while (capacity < size + extra) capacity *= 2
        data = data.copyOf(capacity)
    }

    fun u8(value: Int): ByteWriter {
        ensure(1)
        data[size++] = value.toByte()
        return this
    }

    fun u16(value: Int): ByteWriter {
        ensure(2)
        data[size++] = (value ushr 8).toByte()
        data[size++] = value.toByte()
        return this
    }

    fun u32(value: Long): ByteWriter {
        ensure(4)
        for (shift in 24 downTo 0 step 8) data[size++] = (value ushr shift).toByte()
        return this
    }

    fun i64(value: Long): ByteWriter {
        ensure(8)
        for (shift in 56 downTo 0 step 8) data[size++] = (value ushr shift).toByte()
        return this
    }

    fun raw(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): ByteWriter {
        ensure(length)
        System.arraycopy(bytes, offset, data, size, length)
        size += length
        return this
    }

    /** Length-prefixed UTF-8; the length is validated before it is written. */
    fun text(value: String, maxBytes: Int, field: String): ByteWriter {
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size > maxBytes) {
            throw FrameFormat(
                TransferError.InvalidFieldLength(field, encoded.size.toLong(), maxBytes),
            )
        }
        u16(encoded.size)
        return raw(encoded)
    }

    fun digest(value: Sha256Digest?): ByteWriter {
        if (value == null) return u8(0)
        u8(1)
        return raw(value.bytes)
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}

/** Bounds-checked big-endian reader over one payload. */
private class Cursor(private val src: ByteArray, private val start: Int, private val end: Int) {
    private var pos = start

    fun remaining(): Int = end - pos

    fun atEnd(): Boolean = pos == end

    /** Consumes whatever is left; used when the payload *is* the raw content. */
    fun skipRemaining() {
        pos = end
    }

    private fun need(count: Int) {
        if (remaining() < count) {
            throw FrameFormat(
                TransferError.MalformedFrame(
                    "truncated payload: needed $count more bytes, ${remaining()} available",
                ),
            )
        }
    }

    fun u8(): Int {
        need(1)
        return src[pos++].toInt() and 0xFF
    }

    fun u16(): Int {
        need(2)
        val value = ((src[pos].toInt() and 0xFF) shl 8) or (src[pos + 1].toInt() and 0xFF)
        pos += 2
        return value
    }

    fun u32(): Long {
        need(4)
        var value = 0L
        repeat(4) { value = (value shl 8) or (src[pos++].toLong() and 0xFF) }
        return value
    }

    fun i64(): Long {
        need(8)
        var value = 0L
        repeat(8) { value = (value shl 8) or (src[pos++].toLong() and 0xFF) }
        return value
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0) {
            throw FrameFormat(TransferError.MalformedFrame("negative length $count"))
        }
        need(count)
        val out = ByteArray(count)
        System.arraycopy(src, pos, out, 0, count)
        pos += count
        return out
    }

    /** Length-prefixed strict UTF-8; malformed bytes are rejected, not replaced. */
    fun text(maxBytes: Int, field: String): String {
        val length = u16()
        if (length > maxBytes) {
            throw FrameFormat(TransferError.InvalidFieldLength(field, length.toLong(), maxBytes))
        }
        val raw = bytes(length)
        return strictUtf8(raw, 0, length)
            ?: throw FrameFormat(TransferError.MalformedFrame("field $field is not valid UTF-8"))
    }

    fun digest(): Sha256Digest? {
        val present = u8()
        if (present == 0) return null
        if (present != 1) {
            throw FrameFormat(TransferError.MalformedFrame("invalid digest presence flag $present"))
        }
        val raw = bytes(ProtocolLimits.SHA256_DIGEST_BYTES)
        return Sha256Digest.of(raw)
            ?: throw FrameFormat(TransferError.MalformedFrame("digest field is not 32 bytes"))
    }

    fun chunkSize(): ChunkSize {
        val raw = u32()
        return ChunkSize.orNull(ProtocolLimits.checkedToIntOrNull(raw) ?: -1)
            ?: throw FrameFormat(
                TransferError.UnsupportedChunkSize(
                    raw,
                    ProtocolLimits.MIN_CHUNK_SIZE_BYTES,
                    ProtocolLimits.MAX_CHUNK_SIZE_BYTES,
                ),
            )
    }

    fun fileId(): FileId = FileId.orNull(text(ProtocolLimits.MAX_ID_LENGTH_BYTES, FileId.FIELD))
        ?: throw FrameFormat(TransferError.InvalidIdentifier(FileId.FIELD))

    fun relativePath(): RelativeTransferPath =
        RelativeTransferPath.orNull(text(ProtocolLimits.MAX_RELATIVE_PATH_BYTES, "relativePath"))
            ?: throw FrameFormat(TransferError.InvalidPath("relativePath failed validation"))
}

private fun strictUtf8(src: ByteArray, offset: Int, length: Int): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(src, offset, length)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

/** Encodes and decodes version-1 frames. */
public object FrameCodec {

    // --- header field offsets -------------------------------------------------
    private const val OFF_MAGIC = 0
    private const val OFF_VERSION = 4
    private const val OFF_TYPE = 5
    private const val OFF_FLAGS = 6
    private const val OFF_PAYLOAD_LENGTH = 8
    private const val OFF_SEQUENCE = 12
    private const val OFF_OFFSET = 20
    private const val OFF_PAYLOAD_CRC = 28
    private const val OFF_SESSION_ID_LENGTH = 32
    private const val OFF_TRANSFER_ID_LENGTH = 33
    private const val OFF_RECIPIENT_ID_LENGTH = 34
    private const val OFF_HEADER_CRC = 40
    private const val HEADER_CRC_RANGE = 40

    /** Encodes one envelope; the payload CRC32 is recomputed, not trusted. */
    public fun encode(frame: TransferFrame): ByteArray {
        val session = frame.sessionId?.value?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val transfer = frame.transferId?.value?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val recipient = frame.recipientId?.value?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        require(session.size <= ProtocolLimits.MAX_ID_LENGTH_BYTES) {
            "sessionId is ${session.size} bytes, limit is ${ProtocolLimits.MAX_ID_LENGTH_BYTES}"
        }
        require(transfer.size <= ProtocolLimits.MAX_ID_LENGTH_BYTES) {
            "transferId is ${transfer.size} bytes, limit is ${ProtocolLimits.MAX_ID_LENGTH_BYTES}"
        }
        require(recipient.size <= ProtocolLimits.MAX_ID_LENGTH_BYTES) {
            "recipientId is ${recipient.size} bytes, limit is ${ProtocolLimits.MAX_ID_LENGTH_BYTES}"
        }
        require(frame.payload.size <= ProtocolLimits.MAX_PAYLOAD_BYTES) {
            "payload is ${frame.payload.size} bytes, limit is ${ProtocolLimits.MAX_PAYLOAD_BYTES}"
        }

        val header = ByteArray(ProtocolLimits.HEADER_SIZE_BYTES)
        header[OFF_MAGIC] = ProtocolLimits.MAGIC_BYTE_0.toByte()
        header[OFF_MAGIC + 1] = ProtocolLimits.MAGIC_BYTE_1.toByte()
        header[OFF_MAGIC + 2] = ProtocolLimits.MAGIC_BYTE_2.toByte()
        header[OFF_MAGIC + 3] = ProtocolLimits.MAGIC_BYTE_3.toByte()
        header[OFF_VERSION] = frame.version.value.toByte()
        header[OFF_TYPE] = frame.type.id.toByte()
        putU16(header, OFF_FLAGS, frame.flags)
        putU32(header, OFF_PAYLOAD_LENGTH, frame.payload.size.toLong())
        putI64(header, OFF_SEQUENCE, frame.sequence)
        putI64(header, OFF_OFFSET, frame.offset)
        putU32(header, OFF_PAYLOAD_CRC, Crc32.asUnsigned(frame.payloadCrc32))
        header[OFF_SESSION_ID_LENGTH] = session.size.toByte()
        header[OFF_TRANSFER_ID_LENGTH] = transfer.size.toByte()
        header[OFF_RECIPIENT_ID_LENGTH] = recipient.size.toByte()
        putU32(header, OFF_HEADER_CRC, Crc32.asUnsigned(Crc32.compute(header, 0, HEADER_CRC_RANGE)))

        val out = ByteArray(header.size + session.size + transfer.size + recipient.size + frame.payload.size)
        System.arraycopy(header, 0, out, 0, header.size)
        var cursor = header.size
        System.arraycopy(session, 0, out, cursor, session.size)
        cursor += session.size
        System.arraycopy(transfer, 0, out, cursor, transfer.size)
        cursor += transfer.size
        System.arraycopy(recipient, 0, out, cursor, recipient.size)
        cursor += recipient.size
        System.arraycopy(frame.payload, 0, out, cursor, frame.payload.size)
        return out
    }

    /**
     * Decodes the first frame in [buffer].
     *
     * [consumedBytes] on success lets a caller loop over concatenated frames.
     * A [FrameDecodeResult.Invalid] with `fatal == false` is skippable; one with
     * `fatal == true` means the stream is desynchronised and the connection must
     * be closed.
     */
    public fun decode(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): FrameDecodeResult {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }
        require(offset + length <= buffer.size) {
            "range $offset..${offset + length} is outside a buffer of ${buffer.size} bytes"
        }
        if (length < ProtocolLimits.HEADER_SIZE_BYTES) {
            return FrameDecodeResult.Invalid(
                TransferError.MalformedFrame(
                    "truncated header: need ${ProtocolLimits.HEADER_SIZE_BYTES} bytes, have $length",
                ),
                bytesConsumed = 0,
                fatal = false,
            )
        }
        if (readU8(buffer, offset + OFF_MAGIC) != ProtocolLimits.MAGIC_BYTE_0 ||
            readU8(buffer, offset + OFF_MAGIC + 1) != ProtocolLimits.MAGIC_BYTE_1 ||
            readU8(buffer, offset + OFF_MAGIC + 2) != ProtocolLimits.MAGIC_BYTE_2 ||
            readU8(buffer, offset + OFF_MAGIC + 3) != ProtocolLimits.MAGIC_BYTE_3
        ) {
            return FrameDecodeResult.Invalid(
                TransferError.MalformedFrame("frame magic is not MSC1"),
                bytesConsumed = 0,
                fatal = true,
            )
        }

        val declaredHeaderCrc = readU32(buffer, offset + OFF_HEADER_CRC)
        val actualHeaderCrc = Crc32.asUnsigned(
            Crc32.compute(buffer, offset, HEADER_CRC_RANGE),
        )
        if (declaredHeaderCrc != actualHeaderCrc) {
            return FrameDecodeResult.Invalid(
                TransferError.MalformedFrame("header CRC32 mismatch"),
                bytesConsumed = 0,
                fatal = true,
            )
        }

        val versionValue = readU8(buffer, offset + OFF_VERSION)
        val version = ProtocolVersion.orNull(versionValue)
        if (version == null) {
            return FrameDecodeResult.Invalid(
                TransferError.ProtocolVersionMismatch(
                    expected = ProtocolLimits.PROTOCOL_VERSION_MAX,
                    actual = versionValue,
                ),
                bytesConsumed = 0,
                fatal = true,
            )
        }

        val flags = readU16(buffer, offset + OFF_FLAGS)
        val typeValue = readU8(buffer, offset + OFF_TYPE)
        val payloadLength = readU32(buffer, offset + OFF_PAYLOAD_LENGTH)
        val sessionIdLength = readU8(buffer, offset + OFF_SESSION_ID_LENGTH)
        val transferIdLength = readU8(buffer, offset + OFF_TRANSFER_ID_LENGTH)
        val recipientIdLength = readU8(buffer, offset + OFF_RECIPIENT_ID_LENGTH)

        // Frame length is pure arithmetic, computed as a Long and bounded before
        // it is ever used to allocate or to slice. A 32-bit payload length plus
        // three identifier lengths cannot overflow a Long, so the checks below
        // are the only thing standing between a hostile length field and an
        // allocation.
        val declaredFrameLength = ProtocolLimits.HEADER_SIZE_BYTES.toLong() +
            sessionIdLength.toLong() + transferIdLength.toLong() + recipientIdLength.toLong() +
            payloadLength
        if (declaredFrameLength > Int.MAX_VALUE.toLong()) {
            return FrameDecodeResult.Invalid(
                TransferError.FrameTooLarge(declaredFrameLength, ProtocolLimits.MAX_FRAME_SIZE_BYTES),
                bytesConsumed = 0,
                fatal = true,
            )
        }
        val frameLength = declaredFrameLength.toInt()

        // The payload bound is checked on its own, before the frame-level bound,
        // so an oversized payload is reported as an oversized payload.
        if (payloadLength > ProtocolLimits.MAX_PAYLOAD_BYTES) {
            return if (length >= frameLength) {
                FrameDecodeResult.Invalid(
                    TransferError.PayloadTooLarge(payloadLength, ProtocolLimits.MAX_PAYLOAD_BYTES),
                    bytesConsumed = frameLength,
                    fatal = false,
                )
            } else {
                truncated(length, frameLength)
            }
        }
        // …and then again as part of the whole frame, so a payload that is legal
        // on its own cannot be stacked with identifier fields into something
        // larger than the absolute ceiling.
        if (frameLength > ProtocolLimits.MAX_FRAME_SIZE_BYTES) {
            return FrameDecodeResult.Invalid(
                TransferError.FrameTooLarge(declaredFrameLength, ProtocolLimits.MAX_FRAME_SIZE_BYTES),
                bytesConsumed = 0,
                fatal = true,
            )
        }

        if (sessionIdLength > ProtocolLimits.MAX_ID_LENGTH_BYTES) {
            return if (length >= frameLength) {
                FrameDecodeResult.Invalid(
                    TransferError.InvalidFieldLength(
                        SessionId.FIELD,
                        sessionIdLength.toLong(),
                        ProtocolLimits.MAX_ID_LENGTH_BYTES,
                    ),
                    bytesConsumed = frameLength,
                    fatal = false,
                )
            } else {
                truncated(length, frameLength)
            }
        }
        if (transferIdLength > ProtocolLimits.MAX_ID_LENGTH_BYTES) {
            return if (length >= frameLength) {
                FrameDecodeResult.Invalid(
                    TransferError.InvalidFieldLength(
                        TransferId.FIELD,
                        transferIdLength.toLong(),
                        ProtocolLimits.MAX_ID_LENGTH_BYTES,
                    ),
                    bytesConsumed = frameLength,
                    fatal = false,
                )
            } else {
                truncated(length, frameLength)
            }
        }
        if (recipientIdLength > ProtocolLimits.MAX_ID_LENGTH_BYTES) {
            return if (length >= frameLength) {
                FrameDecodeResult.Invalid(
                    TransferError.InvalidFieldLength(
                        RecipientId.FIELD,
                        recipientIdLength.toLong(),
                        ProtocolLimits.MAX_ID_LENGTH_BYTES,
                    ),
                    bytesConsumed = frameLength,
                    fatal = false,
                )
            } else {
                truncated(length, frameLength)
            }
        }

        if (length < frameLength) return truncated(length, frameLength)

        val frameType = FrameType.fromId(typeValue)
        if (frameType == null) {
            return FrameDecodeResult.Invalid(
                TransferError.MalformedFrame("unknown frame type $typeValue"),
                bytesConsumed = frameLength,
                fatal = false,
            )
        }
        if (flags != 0) {
            return FrameDecodeResult.Invalid(
                TransferError.MalformedFrame("reserved flags field is not zero"),
                bytesConsumed = frameLength,
                fatal = false,
            )
        }

        val sequence = readI64(buffer, offset + OFF_SEQUENCE)
        val frameOffset = readI64(buffer, offset + OFF_OFFSET)
        if (sequence < NOT_APPLICABLE || frameOffset < NOT_APPLICABLE) {
            return FrameDecodeResult.Invalid(
                TransferError.MalformedFrame("sequence or offset is below the reserved minimum"),
                bytesConsumed = frameLength,
                fatal = false,
            )
        }

        var cursor = offset + ProtocolLimits.HEADER_SIZE_BYTES
        val sessionText = readIdText(buffer, cursor, sessionIdLength)
        cursor += sessionIdLength
        val transferText = readIdText(buffer, cursor, transferIdLength)
        cursor += transferIdLength
        val recipientText = readIdText(buffer, cursor, recipientIdLength)
        cursor += recipientIdLength
        if (sessionIdLength > 0 && sessionText == null) return badId(SessionId.FIELD, frameLength)
        if (transferIdLength > 0 && transferText == null) return badId(TransferId.FIELD, frameLength)
        if (recipientIdLength > 0 && recipientText == null) return badId(RecipientId.FIELD, frameLength)

        val sessionId = if (sessionText == null) null else SessionId.orNull(sessionText)
        val transferId = if (transferText == null) null else TransferId.orNull(transferText)
        val recipientId = if (recipientText == null) null else RecipientId.orNull(recipientText)
        if (sessionText != null && sessionId == null) return badId(SessionId.FIELD, frameLength)
        if (transferText != null && transferId == null) return badId(TransferId.FIELD, frameLength)
        if (recipientText != null && recipientId == null) return badId(RecipientId.FIELD, frameLength)

        val payload = ByteArray(ProtocolLimits.checkedToInt(payloadLength, "payload"))
        System.arraycopy(buffer, cursor, payload, 0, payload.size)

        val declaredPayloadCrc = readU32(buffer, offset + OFF_PAYLOAD_CRC).toInt()
        val actualPayloadCrc = Crc32.compute(payload)
        if (declaredPayloadCrc != actualPayloadCrc) {
            return FrameDecodeResult.Invalid(
                TransferError.ChunkChecksumMismatch(
                    offset = if (frameOffset < 0) 0 else frameOffset,
                    expectedCrc = Crc32.asUnsigned(declaredPayloadCrc),
                    actualCrc = Crc32.asUnsigned(actualPayloadCrc),
                ),
                bytesConsumed = frameLength,
                fatal = false,
            )
        }

        return FrameDecodeResult.Success(
            TransferFrame(
                version = version,
                type = frameType,
                flags = flags,
                sessionId = sessionId,
                transferId = transferId,
                recipientId = recipientId,
                sequence = sequence,
                offset = frameOffset,
                payload = payload,
                payloadCrc32 = actualPayloadCrc,
            ),
            consumedBytes = frameLength,
        )
    }

    /**
     * Decodes exactly one frame and rejects anything after it.
     *
     * Used where a frame is the whole message (a decoded buffer, a test vector)
     * rather than one element of a stream.
     */
    public fun decodeSingle(
        buffer: ByteArray,
        offset: Int = 0,
        length: Int = buffer.size,
    ): FrameDecodeResult {
        val result = decode(buffer, offset, length)
        if (result is FrameDecodeResult.Success && result.consumedBytes != length) {
            val trailing = length - result.consumedBytes
            return FrameDecodeResult.Invalid(
                TransferError.MalformedFrame("$trailing extra trailing bytes after a complete frame"),
                bytesConsumed = result.consumedBytes,
                fatal = false,
            )
        }
        return result
    }

    private fun truncated(available: Int, needed: Int): FrameDecodeResult.Invalid =
        FrameDecodeResult.Invalid(
            TransferError.MalformedFrame(
                "truncated frame: need $needed bytes, $available available",
            ),
            bytesConsumed = 0,
            fatal = false,
        )

    private fun badId(field: String, frameLength: Int): FrameDecodeResult.Invalid =
        FrameDecodeResult.Invalid(
            TransferError.InvalidIdentifier(field),
            bytesConsumed = frameLength,
            fatal = false,
        )

    /**
     * Reads one identifier field as strict UTF-8.
     *
     * Returns null for "absent" (length 0), for malformed UTF-8 and for text
     * that fails identifier validation; the caller distinguishes them using the
     * declared length.
     */
    private fun readIdText(buffer: ByteArray, offset: Int, length: Int): String? {
        if (length == 0) return null
        return strictUtf8(buffer, offset, length)
    }

    // --- builders -------------------------------------------------------------

    /** Builds an envelope, computing the payload CRC32 itself. */
    public fun frame(
        type: FrameType,
        sessionId: SessionId?,
        transferId: TransferId?,
        recipientId: RecipientId? = null,
        sequence: Long = NOT_APPLICABLE,
        offset: Long = NOT_APPLICABLE,
        payload: ByteArray = ByteArray(0),
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = TransferFrame(
        version = version,
        type = type,
        flags = 0,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        sequence = sequence,
        offset = offset,
        payload = payload,
        payloadCrc32 = Crc32.compute(payload),
    )

    public fun handshake(
        sessionId: SessionId,
        transferId: TransferId,
        recipientId: RecipientId? = null,
        capabilities: String = "",
        maxChunkSize: ChunkSize = ChunkSize.DEFAULT,
        resumeSupported: Boolean = true,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.HANDSHAKE,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(capabilities, ProtocolLimits.MAX_HANDSHAKE_CAPABILITIES_BYTES, "capabilities")
            .u32(maxChunkSize.value.toLong())
            .u8(if (resumeSupported) 1 else 0)
            .toByteArray(),
        version = version,
    )

    public fun handshakeAccept(
        sessionId: SessionId,
        transferId: TransferId,
        recipientId: RecipientId? = null,
        chunkSize: ChunkSize = ChunkSize.DEFAULT,
        resumeSupported: Boolean = true,
        resumeFromOffset: Long = 0L,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.HANDSHAKE_ACCEPT,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .u32(chunkSize.value.toLong())
            .u8(if (resumeSupported) 1 else 0)
            .i64(resumeFromOffset)
            .toByteArray(),
        version = version,
    )

    public fun handshakeReject(
        sessionId: SessionId,
        transferId: TransferId,
        recipientId: RecipientId? = null,
        reason: String = "",
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.HANDSHAKE_REJECT,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(reason, ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
            .toByteArray(),
        version = version,
    )

    public fun fileMetadata(
        sessionId: SessionId,
        transferId: TransferId,
        descriptor: TransferFileDescriptor,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.FILE_METADATA,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(descriptor.fileId.value, ProtocolLimits.MAX_ID_LENGTH_BYTES, FileId.FIELD)
            .text(descriptor.displayName, ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "displayName")
            .text(descriptor.relativePath.value, ProtocolLimits.MAX_RELATIVE_PATH_BYTES, "relativePath")
            .text(descriptor.mimeType, ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "mimeType")
            .i64(descriptor.totalBytes)
            .i64(descriptor.lastModifiedEpochMillis ?: -1L)
            .u8(if (descriptor.isFolderArchive) FramePayload.KIND_FOLDER_ARCHIVE else FramePayload.KIND_FILE)
            .digest(descriptor.expectedSha256)
            .u32(descriptor.chunkSize.value.toLong())
            .u16(descriptor.protocolVersion.value)
            .toByteArray(),
        version = version,
    )

    public fun resumeProposal(
        sessionId: SessionId,
        transferId: TransferId,
        proposal: FramePayload.ResumeProposal,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.RESUME_PROPOSAL,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(proposal.fileId.value, ProtocolLimits.MAX_ID_LENGTH_BYTES, FileId.FIELD)
            .i64(proposal.totalBytes)
            .u32(proposal.chunkSize.value.toLong())
            .digest(proposal.expectedSha256)
            .text(proposal.relativePath.value, ProtocolLimits.MAX_RELATIVE_PATH_BYTES, "relativePath")
            .i64(proposal.lastModifiedEpochMillis ?: -1L)
            .i64(proposal.senderAdvertisedBytes)
            .toByteArray(),
        version = version,
    )

    public fun resumeResponse(
        sessionId: SessionId,
        transferId: TransferId,
        response: FramePayload.ResumeResponse,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.RESUME_RESPONSE,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter().apply {
            text(response.fileId.value, ProtocolLimits.MAX_ID_LENGTH_BYTES, FileId.FIELD)
            when (val decision = response.decision) {
                is ResumeDecision.ResumeAt -> {
                    u8(FramePayload.DECISION_RESUME_AT)
                    i64(response.offset)
                    text("", ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
                    text("", ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "code")
                    u8(0)
                }

                is ResumeDecision.RestartAtZero -> {
                    u8(FramePayload.DECISION_RESTART_AT_ZERO)
                    i64(0L)
                    text(decision.reason, ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
                    text("", ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "code")
                    u8(0)
                }

                ResumeDecision.AlreadyVerified -> {
                    u8(FramePayload.DECISION_ALREADY_VERIFIED)
                    i64(response.offset)
                    text("", ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
                    text("", ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "code")
                    u8(0)
                }

                is ResumeDecision.Reject -> {
                    u8(FramePayload.DECISION_REJECT)
                    i64(0L)
                    text(decision.error.detail, ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
                    text(decision.error.code, ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "code")
                    u8(if (decision.error.retryable) 1 else 0)
                }
            }
        }.toByteArray(),
        version = version,
    )

    public fun dataChunk(
        sessionId: SessionId,
        transferId: TransferId,
        offset: Long,
        sequence: Long,
        bytes: ByteArray,
        recipientId: RecipientId? = null,
        payloadOffset: Int = 0,
        length: Int = bytes.size,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame {
        require(payloadOffset >= 0) { "payloadOffset must not be negative" }
        require(length >= 0) { "length must not be negative" }
        require(payloadOffset + length <= bytes.size) { "chunk range is outside the source buffer" }
        require(length <= ProtocolLimits.MAX_PAYLOAD_BYTES) {
            "chunk of $length bytes exceeds ${ProtocolLimits.MAX_PAYLOAD_BYTES}"
        }
        val payload = ByteArray(length)
        System.arraycopy(bytes, payloadOffset, payload, 0, length)
        return frame(
            type = FrameType.DATA_CHUNK,
            sessionId = sessionId,
            transferId = transferId,
            recipientId = recipientId,
            sequence = sequence,
            offset = offset,
            payload = payload,
            version = version,
        )
    }

    public fun chunkAck(
        sessionId: SessionId,
        transferId: TransferId,
        offset: Long,
        length: Int,
        confirmedOffset: Long,
        accepted: Boolean = true,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.CHUNK_ACK,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        offset = offset,
        payload = ByteWriter()
            .i64(offset)
            .u32(length.toLong())
            .i64(confirmedOffset)
            .u8(if (accepted) 1 else 0)
            .toByteArray(),
        version = version,
    )

    public fun pause(
        sessionId: SessionId,
        transferId: TransferId,
        reason: String = "",
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.PAUSE,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(reason, ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
            .toByteArray(),
        version = version,
    )

    public fun resume(
        sessionId: SessionId,
        transferId: TransferId,
        fromOffset: Long,
        reason: String = "",
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.RESUME,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        offset = fromOffset,
        payload = ByteWriter()
            .i64(fromOffset)
            .text(reason, ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
            .toByteArray(),
        version = version,
    )

    public fun cancel(
        sessionId: SessionId,
        transferId: TransferId,
        reason: String = "",
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.CANCEL,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(reason, ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
            .toByteArray(),
        version = version,
    )

    public fun error(
        sessionId: SessionId,
        transferId: TransferId?,
        error: TransferError,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.ERROR,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(error.code, ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "code")
            .text(error.detail, ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "detail")
            .u8(if (error.retryable) 1 else 0)
            .u8(error.origin.ordinal)
            .u8(error.category.ordinal)
            .toByteArray(),
        version = version,
    )

    public fun verificationResult(
        sessionId: SessionId,
        transferId: TransferId,
        success: Boolean,
        totalBytes: Long,
        observedDigest: Sha256Digest?,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.VERIFICATION_RESULT,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .u8(if (success) 1 else 0)
            .i64(totalBytes)
            .digest(observedDigest)
            .toByteArray(),
        version = version,
    )

    public fun fileComplete(
        sessionId: SessionId,
        transferId: TransferId,
        fileId: FileId,
        totalBytes: Long,
        digest: Sha256Digest?,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.FILE_COMPLETE,
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        payload = ByteWriter()
            .text(fileId.value, ProtocolLimits.MAX_ID_LENGTH_BYTES, FileId.FIELD)
            .i64(totalBytes)
            .digest(digest)
            .toByteArray(),
        version = version,
    )

    public fun sessionComplete(
        sessionId: SessionId,
        itemCount: Int,
        totalBytes: Long,
        recipientId: RecipientId? = null,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFrame = frame(
        type = FrameType.SESSION_COMPLETE,
        sessionId = sessionId,
        transferId = null,
        recipientId = recipientId,
        payload = ByteWriter()
            .u32(itemCount.toLong())
            .i64(totalBytes)
            .toByteArray(),
        version = version,
    )

    // --- header primitives ----------------------------------------------------

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0..3) {
            target[offset + index] = (value ushr (24 - (8 * index))).toByte()
        }
    }

    private fun putI64(target: ByteArray, offset: Int, value: Long) {
        for (index in 0..7) {
            target[offset + index] = (value ushr (56 - (8 * index))).toByte()
        }
    }

    private fun readU8(buffer: ByteArray, offset: Int): Int = buffer[offset].toInt() and 0xFF

    private fun readU16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private fun readU32(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0..3) value = (value shl 8) or (buffer[offset + index].toLong() and 0xFF)
        return value
    }

    private fun readI64(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0..7) value = (value shl 8) or (buffer[offset + index].toLong() and 0xFF)
        return value
    }
}

/** Interprets the payload of a validated envelope. */
public object FramePayloads {

    /** Parses [frame]'s payload into its typed form. */
    public fun parse(frame: TransferFrame): PayloadResult {
        val cursor = Cursor(frame.payload, 0, frame.payload.size)
        return try {
            val payload = when (frame.type) {
                FrameType.HANDSHAKE -> parseHandshake(cursor)
                FrameType.HANDSHAKE_ACCEPT -> parseHandshakeAccept(cursor)
                FrameType.HANDSHAKE_REJECT -> FramePayload.HandshakeReject(
                    cursor.text(ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason"),
                )

                FrameType.FILE_METADATA -> parseFileMetadata(cursor)
                FrameType.RESUME_PROPOSAL -> parseResumeProposal(cursor)
                FrameType.RESUME_RESPONSE -> parseResumeResponse(cursor)
                FrameType.DATA_CHUNK -> FramePayload.DataChunk(
                    offset = frame.offset,
                    sequence = frame.sequence,
                    bytes = frame.payload.copyOf().also { cursor.skipRemaining() },
                    crc32 = frame.payloadCrc32,
                )

                FrameType.CHUNK_ACK -> parseChunkAck(cursor)
                FrameType.PAUSE -> FramePayload.Pause(
                    cursor.text(ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason"),
                )

                FrameType.RESUME -> FramePayload.Resume(
                    fromOffset = cursor.i64(),
                    reason = cursor.text(ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason"),
                )

                FrameType.CANCEL -> FramePayload.Cancel(
                    cursor.text(ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason"),
                )

                FrameType.ERROR -> parseError(cursor)
                FrameType.VERIFICATION_RESULT -> parseVerificationResult(cursor)
                FrameType.FILE_COMPLETE -> parseFileComplete(cursor)
                FrameType.SESSION_COMPLETE -> parseSessionComplete(cursor)
            }
            if (!cursor.atEnd()) {
                throw FrameFormat(
                    TransferError.MalformedFrame(
                        "${cursor.remaining()} extra trailing bytes in the payload",
                    ),
                )
            }
            PayloadResult.Success(payload)
        } catch (e: FrameFormat) {
            PayloadResult.Invalid(e.error)
        }
    }

    private fun parseHandshake(cursor: Cursor): FramePayload.Handshake {
        val capabilities = cursor.text(
            ProtocolLimits.MAX_HANDSHAKE_CAPABILITIES_BYTES,
            "capabilities",
        )
        val maxChunkSize = cursor.chunkSize()
        val resumeSupported = cursor.u8() != 0
        return FramePayload.Handshake(capabilities, maxChunkSize, resumeSupported)
    }

    private fun parseHandshakeAccept(cursor: Cursor): FramePayload.HandshakeAccept {
        val chunkSize = cursor.chunkSize()
        val resumeSupported = cursor.u8() != 0
        val resumeFromOffset = cursor.i64()
        if (resumeFromOffset < 0L) {
            throw FrameFormat(TransferError.MalformedFrame("negative resume offset $resumeFromOffset"))
        }
        return FramePayload.HandshakeAccept(chunkSize, resumeSupported, resumeFromOffset)
    }

    private fun parseFileMetadata(cursor: Cursor): FramePayload.FileMetadata {
        val fileId = cursor.fileId()
        val displayName = cursor.text(ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "displayName")
        val relativePath = cursor.relativePath()
        val mimeType = cursor.text(ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "mimeType")
        val totalBytes = cursor.i64()
        val lastModified = cursor.i64().let { if (it < 0) null else it }
        val kind = cursor.u8()
        if (kind != FramePayload.KIND_FILE && kind != FramePayload.KIND_FOLDER_ARCHIVE) {
            throw FrameFormat(TransferError.MalformedFrame("unknown descriptor kind $kind"))
        }
        val digest = cursor.digest()
        val chunkSize = cursor.chunkSize()
        val version = cursor.u16()
        val protocolVersion = ProtocolVersion.orNull(version)
            ?: throw FrameFormat(
                TransferError.ProtocolVersionMismatch(ProtocolLimits.PROTOCOL_VERSION_MAX, version),
            )
        val descriptor = TransferFileDescriptor.tryCreate(
            fileId = fileId,
            displayName = displayName,
            relativePath = relativePath,
            mimeType = mimeType,
            totalBytes = totalBytes,
            lastModifiedEpochMillis = lastModified,
            isFolderArchive = kind == FramePayload.KIND_FOLDER_ARCHIVE,
            expectedSha256 = digest,
            chunkSize = chunkSize,
            protocolVersion = protocolVersion,
        ) ?: throw FrameFormat(TransferError.MalformedFrame("file descriptor failed validation"))
        return FramePayload.FileMetadata(descriptor)
    }

    private fun parseResumeProposal(cursor: Cursor): FramePayload.ResumeProposal {
        val fileId = cursor.fileId()
        val totalBytes = cursor.i64()
        if (totalBytes < 0L) {
            throw FrameFormat(TransferError.MalformedFrame("negative total size $totalBytes"))
        }
        val chunkSize = cursor.chunkSize()
        val digest = cursor.digest()
        val relativePath = cursor.relativePath()
        val lastModified = cursor.i64().let { if (it < 0) null else it }
        val advertised = cursor.i64()
        if (advertised < 0L) {
            throw FrameFormat(TransferError.MalformedFrame("negative advertised byte count $advertised"))
        }
        return FramePayload.ResumeProposal(
            fileId = fileId,
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            expectedSha256 = digest,
            relativePath = relativePath,
            lastModifiedEpochMillis = lastModified,
            senderAdvertisedBytes = advertised,
        )
    }

    private fun parseResumeResponse(cursor: Cursor): FramePayload.ResumeResponse {
        val fileId = cursor.fileId()
        val decisionCode = cursor.u8()
        val offset = cursor.i64()
        val reason = cursor.text(ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "reason")
        val code = cursor.text(ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "code")
        val retryable = cursor.u8() != 0
        val decision = when (decisionCode) {
            FramePayload.DECISION_RESUME_AT -> {
                if (offset < 0L) {
                    throw FrameFormat(TransferError.MalformedFrame("negative resume offset $offset"))
                }
                ResumeDecision.ResumeAt(offset)
            }

            FramePayload.DECISION_RESTART_AT_ZERO -> ResumeDecision.RestartAtZero(reason)

            FramePayload.DECISION_ALREADY_VERIFIED -> ResumeDecision.AlreadyVerified

            FramePayload.DECISION_REJECT -> ResumeDecision.Reject(
                TransferError.restore(
                    code = code,
                    detail = reason,
                    retryable = retryable,
                    origin = ErrorOrigin.REMOTE,
                    category = ErrorCategory.PROTOCOL,
                ),
            )

            else -> throw FrameFormat(
                TransferError.MalformedFrame("unknown resume decision $decisionCode"),
            )
        }
        return FramePayload.ResumeResponse(fileId, decision)
    }

    private fun parseChunkAck(cursor: Cursor): FramePayload.ChunkAck {
        val offset = cursor.i64()
        val length = cursor.u32()
        val confirmedOffset = cursor.i64()
        val accepted = cursor.u8() != 0
        if (offset < 0L || confirmedOffset < 0L) {
            throw FrameFormat(TransferError.MalformedFrame("negative offset in a chunk acknowledgement"))
        }
        return FramePayload.ChunkAck(
            offset = offset,
            length = ProtocolLimits.checkedToInt(length, "ackLength"),
            confirmedOffset = confirmedOffset,
            accepted = accepted,
        )
    }

    private fun parseError(cursor: Cursor): FramePayload.ErrorFrame {
        val code = cursor.text(ProtocolLimits.MAX_TEXT_LENGTH_BYTES, "code")
        val detail = cursor.text(ProtocolLimits.MAX_ERROR_DETAIL_BYTES, "detail")
        val retryable = cursor.u8() != 0
        val origin = ErrorOrigin.entries.getOrNull(cursor.u8()) ?: ErrorOrigin.UNKNOWN
        val category = ErrorCategory.entries.getOrNull(cursor.u8()) ?: ErrorCategory.UNKNOWN
        return FramePayload.ErrorFrame(code, detail, retryable, origin, category)
    }

    private fun parseVerificationResult(cursor: Cursor): FramePayload.VerificationResult {
        val success = cursor.u8() != 0
        val totalBytes = cursor.i64()
        val digest = cursor.digest()
        if (totalBytes < 0L) {
            throw FrameFormat(TransferError.MalformedFrame("negative total size $totalBytes"))
        }
        return FramePayload.VerificationResult(success, totalBytes, digest)
    }

    private fun parseFileComplete(cursor: Cursor): FramePayload.FileComplete {
        val fileId = cursor.fileId()
        val totalBytes = cursor.i64()
        val digest = cursor.digest()
        if (totalBytes < 0L) {
            throw FrameFormat(TransferError.MalformedFrame("negative total size $totalBytes"))
        }
        return FramePayload.FileComplete(fileId, totalBytes, digest)
    }

    private fun parseSessionComplete(cursor: Cursor): FramePayload.SessionComplete {
        val itemCount = cursor.u32()
        val totalBytes = cursor.i64()
        if (totalBytes < 0L) {
            throw FrameFormat(TransferError.MalformedFrame("negative total size $totalBytes"))
        }
        return FramePayload.SessionComplete(
            itemCount = ProtocolLimits.checkedToInt(itemCount, "itemCount"),
            totalBytes = totalBytes,
        )
    }
}
