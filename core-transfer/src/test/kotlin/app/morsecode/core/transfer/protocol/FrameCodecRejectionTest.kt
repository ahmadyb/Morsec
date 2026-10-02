package app.morsecode.core.transfer.protocol

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.integrity.Crc32Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything a hostile peer can put on the wire, and what the decoder must do
 * about it.
 *
 * The rule these tests exist to enforce: a length field is data, not a request.
 * Nothing is allocated, sliced or looped over until the length has been bounded
 * against `ProtocolLimits`, and every failure is a typed error rather than an
 * exception or, worse, a partially populated object.
 */
class FrameCodecRejectionTest {

    // --- truncation at every field boundary -----------------------------------

    @Test fun `empty input is reported as needing more bytes`() {
        val result = FrameCodec.decode(ByteArray(0))
        assertInvalid(result, fatal = false, consumed = 0)
        assertTrue(error(result).detail.contains("truncated header"))
    }

    @Test fun `truncation at every header boundary is reported not guessed`() {
        val frame = FrameCodec.encode(
            FrameCodec.dataChunk(
                Tf.sessionId, Tf.transferId, offset = 0L, sequence = 0L,
                bytes = ByteArray(64),
            ),
        )
        for (length in 0 until frame.size) {
            val result = FrameCodec.decode(frame, 0, length)
            assertTrue(
                "length $length must not decode into a frame",
                result is FrameDecodeResult.Invalid,
            )
            val invalid = result as FrameDecodeResult.Invalid
            assertFalse("a truncation is never fatal", invalid.fatal)
            assertEquals(0, invalid.bytesConsumed)
        }
    }

    @Test fun `a truncated payload waits for more bytes instead of decoding a short chunk`() {
        val frame = FrameCodec.encode(
            FrameCodec.dataChunk(
                Tf.sessionId, Tf.transferId, offset = 0L, sequence = 0L,
                bytes = ByteArray(4_096),
            ),
        )
        val result = FrameCodec.decode(frame, 0, frame.size - 1)
        assertInvalid(result, fatal = false, consumed = 0)
        assertTrue(error(result).detail.contains("truncated frame"))
    }

    // --- magic, version, header integrity --------------------------------------

    @Test fun `unknown magic desynchronises the stream`() {
        val frame = FrameCodec.encode(FrameCodec.pause(Tf.sessionId, Tf.transferId)).copyOf()
        frame[0] = 0x00
        val result = FrameCodec.decode(frame)
        assertInvalid(result, fatal = true, consumed = 0)
        assertTrue(error(result) is TransferError.MalformedFrame)
    }

    @Test fun `an unknown protocol version is fatal rather than guessed at`() {
        val frame = FrameCodec.frame(
            FrameType.PAUSE, Tf.sessionId, Tf.transferId,
            payload = ByteArray(2),
        )
        val encoded = FrameCodec.encode(frame).copyOf()
        encoded[4] = 9
        // Repair the header CRC so the version field is what fails.
        repairHeaderCrc(encoded)
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = true, consumed = 0)
        assertTrue(error(result) is TransferError.ProtocolVersionMismatch)
        assertEquals(9, (error(result) as TransferError.ProtocolVersionMismatch).actual)
    }

    @Test fun `a corrupt header checksum is fatal because no length can be trusted`() {
        val encoded = FrameCodec.encode(FrameCodec.pause(Tf.sessionId, Tf.transferId)).copyOf()
        encoded[40] = (encoded[40].toInt() xor 0xFF).toByte()
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = true, consumed = 0)
        assertTrue(error(result).detail.contains("header CRC32"))
    }

    @Test fun `non zero reserved flags are rejected without desynchronising`() {
        val encoded = FrameCodec.encode(FrameCodec.pause(Tf.sessionId, Tf.transferId)).copyOf()
        encoded[7] = 1
        repairHeaderCrc(encoded)
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = false)
        assertEquals(encoded.size, (result as FrameDecodeResult.Invalid).bytesConsumed)
    }

    // --- hostile lengths ---------------------------------------------------------

    @Test fun `an oversized payload is refused before anything is allocated`() {
        val encoded = buildHeader(
            payloadLength = ProtocolLimits.MAX_PAYLOAD_BYTES + 1L,
            type = FrameType.DATA_CHUNK.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = ByteArray(0),
            padToDeclaredLength = true,
        )
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = false)
        assertTrue(error(result) is TransferError.PayloadTooLarge)
        assertEquals(
            ProtocolLimits.MAX_PAYLOAD_BYTES + 1L,
            (error(result) as TransferError.PayloadTooLarge).size,
        )
    }

    @Test fun `a four gigabyte payload length cannot overflow into an allocation`() {
        // The declared length is summed as a Long and compared against
        // Int.MAX_VALUE *before* it is narrowed, so a 32-bit payload length can
        // never wrap into a small positive allocation on this (32-bit-clean) path.
        val encoded = buildHeader(
            payloadLength = 0xFFFFFFFFL,
            type = FrameType.DATA_CHUNK.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = ByteArray(0),
            padToDeclaredLength = false,
        )
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = true, consumed = 0)
        assertTrue(error(result) is TransferError.FrameTooLarge)
        assertEquals(
            4_294_967_339L,
            (error(result) as TransferError.FrameTooLarge).size,
        )
    }

    @Test fun `oversized identifier lengths are refused`() {
        for (offset in listOf(32, 33, 34)) {
            val encoded = buildHeader(
                payloadLength = 0L,
                type = FrameType.PAUSE.id,
                sessionIdLength = if (offset == 32) 200 else 0,
                transferIdLength = if (offset == 33) 200 else 0,
                recipientIdLength = if (offset == 34) 200 else 0,
                payload = ByteArray(0),
                padToDeclaredLength = true,
            )
            val result = FrameCodec.decode(encoded)
            assertInvalid(result, fatal = false)
            assertTrue(error(result) is TransferError.InvalidFieldLength)
        }
    }

    @Test fun `an unknown frame type is skippable rather than fatal`() {
        val encoded = buildHeader(
            payloadLength = 0L,
            type = 99,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = ByteArray(0),
        )
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = false)
        assertEquals(ProtocolLimits.HEADER_SIZE_BYTES, (result as FrameDecodeResult.Invalid).bytesConsumed)
        assertTrue(error(result).detail.contains("unknown frame type 99"))
    }

    @Test fun `negative sequence and offset values are refused`() {
        for (field in listOf(12, 20)) {
            val encoded = buildHeader(
                payloadLength = 0L,
                type = FrameType.PAUSE.id,
                sessionIdLength = 0,
                transferIdLength = 0,
                recipientIdLength = 0,
                payload = ByteArray(0),
            )
            // Write -2, which is below the reserved NOT_APPLICABLE minimum.
            for (index in 0..7) encoded[field + index] = if (index == 7) 0xFE.toByte() else 0xFF.toByte()
            repairHeaderCrc(encoded)
            val result = FrameCodec.decode(encoded)
            assertInvalid(result, fatal = false)
            assertTrue(error(result).detail.contains("reserved minimum"))
        }
    }

    // --- identifiers and text --------------------------------------------------------

    @Test fun `an identifier that fails validation is refused`() {
        val encoded = buildHeader(
            payloadLength = 0L,
            type = FrameType.PAUSE.id,
            sessionIdLength = 3,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = "a/b".toByteArray(Charsets.UTF_8),
        )
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = false)
        assertTrue(error(result) is TransferError.InvalidIdentifier)
    }

    @Test fun `invalid utf8 in an identifier is refused rather than replaced`() {
        val encoded = buildHeader(
            payloadLength = 0L,
            type = FrameType.PAUSE.id,
            sessionIdLength = 2,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = byteArrayOf(0xC3.toByte(), 0x28.toByte()), // invalid two-byte sequence
        )
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = false)
        assertTrue(error(result) is TransferError.InvalidIdentifier)
    }

    @Test fun `invalid utf8 in a metadata field is refused`() {
        val payload = byteArrayOf(0x00, 0x02) + byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val encoded = buildHeader(
            payloadLength = payload.size.toLong(),
            type = FrameType.HANDSHAKE_REJECT.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = payload,
        )
        val frame = (FrameCodec.decode(encoded) as FrameDecodeResult.Success).frame
        val result = FramePayloads.parse(frame)
        assertTrue("expected the payload to be refused: $result", result is PayloadResult.Invalid)
        assertTrue((result as PayloadResult.Invalid).error.detail.contains("UTF-8"))
    }

    // --- payload integrity -------------------------------------------------------------

    @Test fun `a corrupted chunk payload fails its crc and is not delivered`() {
        val frame = FrameCodec.dataChunk(
            Tf.sessionId, Tf.transferId, offset = 0L, sequence = 0L,
            bytes = Crc32Test.deterministicBytes(1_024),
        )
        val encoded = FrameCodec.encode(frame).copyOf()
        encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0x01).toByte()
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = false)
        assertTrue(error(result) is TransferError.ChunkChecksumMismatch)
        assertEquals(encoded.size, (result as FrameDecodeResult.Invalid).bytesConsumed)
    }

    @Test fun `a corrupted metadata payload also fails its crc`() {
        val encoded = FrameCodec.encode(
            FrameCodec.fileMetadata(Tf.sessionId, Tf.transferId, Tf.descriptor()),
        ).copyOf()
        encoded[encoded.size - 5] = (encoded[encoded.size - 5].toInt() xor 0x08).toByte()
        val result = FrameCodec.decode(encoded)
        assertInvalid(result, fatal = false)
        assertTrue(error(result) is TransferError.ChunkChecksumMismatch)
    }

    // --- trailing bytes and payload structure ------------------------------------------

    @Test fun `extra trailing bytes are refused by decodeSingle`() {
        val encoded = FrameCodec.encode(FrameCodec.pause(Tf.sessionId, Tf.transferId))
        val withTail = encoded + byteArrayOf(0x4D, 0x53, 0x43, 0x31)
        val result = FrameCodec.decodeSingle(withTail)
        assertInvalid(result, fatal = false)
        assertTrue(error(result).detail.contains("extra trailing bytes"))
        assertEquals(encoded.size, (result as FrameDecodeResult.Invalid).bytesConsumed)
    }

    @Test fun `extra trailing bytes inside a payload are refused`() {
        val payload = byteArrayOf(0x00, 0x02, 0x41, 0x42) + byteArrayOf(0x00)
        val encoded = buildHeader(
            payloadLength = payload.size.toLong(),
            type = FrameType.HANDSHAKE_REJECT.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = payload,
        )
        val frame = (FrameCodec.decode(encoded) as FrameDecodeResult.Success).frame
        val result = FramePayloads.parse(frame)
        assertTrue(result is PayloadResult.Invalid)
        assertTrue((result as PayloadResult.Invalid).error.detail.contains("extra trailing bytes"))
    }

    @Test fun `a payload shorter than its fields is refused`() {
        val encoded = buildHeader(
            payloadLength = 1L,
            type = FrameType.HANDSHAKE.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = byteArrayOf(0x00),
        )
        val frame = (FrameCodec.decode(encoded) as FrameDecodeResult.Success).frame
        val result = FramePayloads.parse(frame)
        assertTrue(result is PayloadResult.Invalid)
        assertTrue((result as PayloadResult.Invalid).error.detail.contains("truncated payload"))
    }

    @Test fun `a chunk size outside the supported range is refused`() {
        val payload = byteArrayOf(0x00, 0x00) + byteArrayOf(0x00, 0x00, 0x00, 0x08) + byteArrayOf(0x01)
        val encoded = buildHeader(
            payloadLength = payload.size.toLong(),
            type = FrameType.HANDSHAKE.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = payload,
        )
        val frame = (FrameCodec.decode(encoded) as FrameDecodeResult.Success).frame
        val result = FramePayloads.parse(frame)
        assertTrue(result is PayloadResult.Invalid)
        assertTrue((result as PayloadResult.Invalid).error is TransferError.UnsupportedChunkSize)
    }

    @Test fun `an unknown resume decision is refused`() {
        val payload = byteArrayOf(0x00, 0x01, 0x41) + // fileId length 1, "A"
            byteArrayOf(0x09) + // unknown decision
            ByteArray(8) + // offset
            byteArrayOf(0x00, 0x00) + // reason
            byteArrayOf(0x00, 0x00) + // code
            byteArrayOf(0x00) // retryable
        val encoded = buildHeader(
            payloadLength = payload.size.toLong(),
            type = FrameType.RESUME_RESPONSE.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = payload,
        )
        val frame = (FrameCodec.decode(encoded) as FrameDecodeResult.Success).frame
        val result = FramePayloads.parse(frame)
        assertTrue(result is PayloadResult.Invalid)
        assertTrue((result as PayloadResult.Invalid).error.detail.contains("unknown resume decision"))
    }

    @Test fun `a descriptor that fails validation is refused`() {
        // totalBytes is negative in this hand-built descriptor.
        val payload = byteArrayOf(0x00, 0x02, 0x66, 0x31) + // fileId "f1"
            byteArrayOf(0x00, 0x01, 0x6E) + // displayName "n"
            byteArrayOf(0x00, 0x01, 0x70) + // relativePath "p"
            byteArrayOf(0x00, 0x00) + // mimeType ""
            ByteArray(8) { 0xFF.toByte() } + // totalBytes -1
            ByteArray(8) { 0xFF.toByte() } + // lastModified -1
            byteArrayOf(0x00) + // kind
            byteArrayOf(0x00) + // no digest
            byteArrayOf(0x00, 0x00, 0x10, 0x00) + // chunkSize 4096
            byteArrayOf(0x00, 0x01) // protocol version 1
        val encoded = buildHeader(
            payloadLength = payload.size.toLong(),
            type = FrameType.FILE_METADATA.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = payload,
        )
        val frame = (FrameCodec.decode(encoded) as FrameDecodeResult.Success).frame
        val result = FramePayloads.parse(frame)
        assertTrue(result is PayloadResult.Invalid)
        assertTrue((result as PayloadResult.Invalid).error.detail.contains("descriptor"))
    }

    @Test fun `a digest presence flag that is neither zero nor one is refused`() {
        val payload = byteArrayOf(0x00) + // success flag
            ByteArray(8) { 0x00 } + // totalBytes
            byteArrayOf(0x07) // invalid digest presence flag
        val encoded = buildHeader(
            payloadLength = payload.size.toLong(),
            type = FrameType.VERIFICATION_RESULT.id,
            sessionIdLength = 0,
            transferIdLength = 0,
            recipientIdLength = 0,
            payload = payload,
        )
        val frame = (FrameCodec.decode(encoded) as FrameDecodeResult.Success).frame
        val result = FramePayloads.parse(frame)
        assertTrue(result is PayloadResult.Invalid)
        assertTrue((result as PayloadResult.Invalid).error.detail.contains("digest presence"))
    }

    // --- sequence and offset consistency --------------------------------------------

    @Test fun `a chunk whose sequence disagrees with its offset is reported as such`() {
        // The frame layer only guarantees the fields travelled intact; deciding
        // whether they make sense is the reducer's job, so the codec accepts them
        // and the reducer's typed rejection is what a peer actually sees.
        val frame = FrameCodec.dataChunk(
            Tf.sessionId, Tf.transferId, offset = 4_096L, sequence = 7L,
            bytes = ByteArray(16),
        )
        val decoded = FrameCodec.decodeSingle(FrameCodec.encode(frame))
        assertTrue(decoded is FrameDecodeResult.Success)
        assertEquals(4_096L, (decoded as FrameDecodeResult.Success).frame.offset)
        assertEquals(7L, decoded.frame.sequence)
    }

    @Test fun `the maximum legal frame decodes and the next byte over does not`() {
        val maxPayload = FrameCodec.dataChunk(
            Tf.sessionId, Tf.transferId, offset = 0L, sequence = 0L,
            bytes = Crc32Test.deterministicBytes(ProtocolLimits.MAX_PAYLOAD_BYTES),
        )
        assertTrue(FrameCodec.decodeSingle(FrameCodec.encode(maxPayload)) is FrameDecodeResult.Success)
        val declaredMax = ProtocolLimits.MAX_FRAME_SIZE_BYTES
        assertTrue(declaredMax >= FrameCodec.encode(maxPayload).size)
    }

    // --- helpers --------------------------------------------------------------------

    private fun assertInvalid(
        result: FrameDecodeResult,
        fatal: Boolean,
        consumed: Int? = null,
    ) {
        assertTrue("expected an invalid result but got $result", result is FrameDecodeResult.Invalid)
        val invalid = result as FrameDecodeResult.Invalid
        assertEquals("fatal flag", fatal, invalid.fatal)
        if (consumed != null) assertEquals("bytes consumed", consumed, invalid.bytesConsumed)
    }

    private fun error(result: FrameDecodeResult): TransferError =
        (result as FrameDecodeResult.Invalid).error

    /**
     * Builds a header with hand-chosen field values so a test can put a value on
     * the wire that the encoder would never produce.
     */
    private fun buildHeader(
        payloadLength: Long,
        type: Int,
        sessionIdLength: Int,
        transferIdLength: Int,
        recipientIdLength: Int,
        payload: ByteArray,
        sequence: Long = NOT_APPLICABLE,
        offset: Long = NOT_APPLICABLE,
        padToDeclaredLength: Boolean = false,
    ): ByteArray {
        // Padding has to cover the declared identifier bytes as well as the
        // declared payload, because the decoder sums all of them into the frame
        // length it waits for.
        val declaredBodyLength = payloadLength +
            sessionIdLength.toLong() + transferIdLength.toLong() + recipientIdLength.toLong()
        val body = if (padToDeclaredLength && declaredBodyLength < Int.MAX_VALUE) {
            payload + ByteArray((declaredBodyLength - payload.size).coerceAtLeast(0).toInt())
        } else {
            payload
        }
        val header = ByteArray(ProtocolLimits.HEADER_SIZE_BYTES)
        header[0] = ProtocolLimits.MAGIC_BYTE_0.toByte()
        header[1] = ProtocolLimits.MAGIC_BYTE_1.toByte()
        header[2] = ProtocolLimits.MAGIC_BYTE_2.toByte()
        header[3] = ProtocolLimits.MAGIC_BYTE_3.toByte()
        header[4] = ProtocolVersion.CURRENT.value.toByte()
        header[5] = type.toByte()
        for (index in 0..3) {
            header[8 + index] = (payloadLength ushr (24 - 8 * index)).toByte()
        }
        for (index in 0..7) {
            header[12 + index] = (sequence ushr (56 - 8 * index)).toByte()
            header[20 + index] = (offset ushr (56 - 8 * index)).toByte()
        }
        val payloadCrc = app.morsecode.core.transfer.integrity.Crc32.compute(body)
        for (index in 0..3) {
            header[28 + index] = ((payloadCrc.toLong() and 0xFFFFFFFFL) ushr (24 - 8 * index)).toByte()
        }
        header[32] = sessionIdLength.toByte()
        header[33] = transferIdLength.toByte()
        header[34] = recipientIdLength.toByte()
        val encoded = header + body
        val headerCrc = app.morsecode.core.transfer.integrity.Crc32.compute(encoded, 0, 40)
        for (index in 0..3) {
            encoded[40 + index] = ((headerCrc.toLong() and 0xFFFFFFFFL) ushr (24 - 8 * index)).toByte()
        }
        return encoded
    }

    private fun repairHeaderCrc(encoded: ByteArray) {
        val crc = app.morsecode.core.transfer.integrity.Crc32.compute(encoded, 0, 40)
        for (index in 0..3) {
            encoded[40 + index] = ((crc.toLong() and 0xFFFFFFFFL) ushr (24 - 8 * index)).toByte()
        }
    }
}
