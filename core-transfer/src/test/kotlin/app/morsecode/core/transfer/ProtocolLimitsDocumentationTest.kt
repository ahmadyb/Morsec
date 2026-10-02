package app.morsecode.core.transfer

import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.protocol.FramePayloads
import app.morsecode.core.transfer.protocol.FrameType
import app.morsecode.core.transfer.protocol.PayloadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The numbers doc/transfer-protocol.md publishes, proved against the constants
 * rather than copied into a comment.
 *
 * tools/verify/transfer-limits.mjs already fails the build when the document's
 * table disagrees with ProtocolLimits.kt. These tests are the other half: they
 * pin the *derived* numbers — the frame ceiling above all — to real behaviour,
 * so a change to the header layout, to the identifier budget or to the payload
 * ceiling cannot leave a document that still reads as correct.
 *
 * Nothing here reads a file or a clock, and no test allocates above
 * MAX_FRAME_SIZE_BYTES, which is itself the property under test.
 */
class ProtocolLimitsDocumentationTest {

    // --- the frame ceiling ------------------------------------------------------------------------

    @Test fun `the header is forty four bytes, field by field`() {
        // magic 4 + version 1 + type 1 + flags 2 + payloadLength 4 + sequence 8
        // + offset 8 + payloadCrc 4 + three id lengths 3 + reserved 5 + headerCrc 4
        assertEquals(44, 4 + 1 + 1 + 2 + 4 + 8 + 8 + 4 + 3 + 5 + 4)
        assertEquals(44, ProtocolLimits.HEADER_SIZE_BYTES)
    }

    @Test fun `the largest frame is the header plus three identifiers plus the payload`() {
        val expected = ProtocolLimits.HEADER_SIZE_BYTES.toLong() +
            (3L * ProtocolLimits.MAX_ID_LENGTH_BYTES) +
            ProtocolLimits.MAX_PAYLOAD_BYTES
        assertEquals(262_380L, expected)
        assertEquals(expected.toInt(), ProtocolLimits.MAX_FRAME_SIZE_BYTES)
    }

    /** The claim above, checked against the encoder rather than the arithmetic. */
    @Test fun `a maximal frame encodes to exactly the documented ceiling`() {
        val encoded = FrameCodec.encode(maximalFrame())
        assertEquals(
            "three 64-byte identifiers plus a ${ProtocolLimits.MAX_PAYLOAD_BYTES}-byte payload",
            ProtocolLimits.MAX_FRAME_SIZE_BYTES,
            encoded.size,
        )
    }

    @Test fun `a maximal frame decodes back to exactly what was encoded`() {
        val encoded = FrameCodec.encode(maximalFrame())
        val decoded = FrameCodec.decodeSingle(encoded) as FrameDecodeResult.Success
        assertEquals(
            "the whole buffer is one frame, with nothing left over",
            ProtocolLimits.MAX_FRAME_SIZE_BYTES,
            decoded.consumedBytes,
        )
        assertEquals(FrameType.DATA_CHUNK, decoded.frame.type)
        assertEquals(ProtocolLimits.MAX_PAYLOAD_BYTES, decoded.frame.payload.size)
        assertEquals(MAX_SESSION_ID, decoded.frame.sessionId)
        assertEquals(MAX_TRANSFER_ID, decoded.frame.transferId)
        assertEquals(MAX_RECIPIENT_ID, decoded.frame.recipientId)
        assertTrue(decoded.frame.payloadIsIntact())
    }

    @Test fun `the identifier and payload budgets are what the layout assumes`() {
        assertEquals(64, ProtocolLimits.MAX_ID_LENGTH_BYTES)
        assertEquals(262_144, ProtocolLimits.MAX_PAYLOAD_BYTES)
        assertEquals(ProtocolLimits.MAX_CHUNK_SIZE_BYTES, ProtocolLimits.MAX_PAYLOAD_BYTES)
    }

    @Test fun `a frame one byte over the payload ceiling cannot be built`() {
        try {
            FrameCodec.frame(
                type = FrameType.DATA_CHUNK,
                sessionId = SessionId("s"),
                transferId = TransferId("t"),
                payload = ByteArray(ProtocolLimits.MAX_PAYLOAD_BYTES + 1),
            )
            throw AssertionError("a payload above MAX_PAYLOAD_BYTES must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "the message must name the ceiling: ${e.message}",
                e.message?.contains("262144") == true,
            )
        }
    }

    // --- chunk sizes ------------------------------------------------------------------------------

    @Test fun `the chunk bounds are ordered and the default sits between them`() {
        assertTrue(
            ProtocolLimits.MIN_CHUNK_SIZE_BYTES <= ProtocolLimits.DEFAULT_CHUNK_SIZE_BYTES,
        )
        assertTrue(
            ProtocolLimits.DEFAULT_CHUNK_SIZE_BYTES <= ProtocolLimits.MAX_CHUNK_SIZE_BYTES,
        )
        assertEquals(1_024, ProtocolLimits.MIN_CHUNK_SIZE_BYTES)
        assertEquals(65_536, ProtocolLimits.DEFAULT_CHUNK_SIZE_BYTES)
        assertEquals(262_144, ProtocolLimits.MAX_CHUNK_SIZE_BYTES)
    }

    // --- the file ceiling -------------------------------------------------------------------------

    @Test fun `the largest file is eight tebibytes minus one`() {
        val eightTebibytes = 8L * 1024L * 1024L * 1024L * 1024L
        assertEquals(eightTebibytes - 1L, ProtocolLimits.MAX_FILE_SIZE_BYTES)
        assertEquals(8_796_093_022_207L, ProtocolLimits.MAX_FILE_SIZE_BYTES)
    }

    /**
     * The regression this ceiling exists to prevent: an earlier build capped
     * every file at 4 GiB - 1, which is a 32-bit length field leaking into a
     * `Long`-sized domain.
     */
    @Test fun `the ceiling is past four gibibytes, and a five gibibyte file is accepted`() {
        val fourGibibytes = 4L * 1024L * 1024L * 1024L
        assertTrue(
            "the ceiling must clear 4 GiB: ${ProtocolLimits.MAX_FILE_SIZE_BYTES}",
            ProtocolLimits.MAX_FILE_SIZE_BYTES > fourGibibytes,
        )
        val fiveGibibytes = 5L * 1024L * 1024L * 1024L
        val descriptor = TransferFileDescriptor(
            fileId = FileId("file-1"),
            displayName = "movie.mkv",
            relativePath = RelativeTransferPath("media/movie.mkv"),
            mimeType = "video/x-matroska",
            totalBytes = fiveGibibytes,
            lastModifiedEpochMillis = 1_700_000_000_000L,
            isFolderArchive = false,
            expectedSha256 = null,
            chunkSize = app.morsecode.core.transfer.identity.ChunkSize(ProtocolLimits.MAX_CHUNK_SIZE_BYTES),
            protocolVersion = ProtocolVersion.CURRENT,
        )
        assertEquals(fiveGibibytes, descriptor.totalBytes)
        assertEquals(
            "5 GiB is 20,480 maximum-size chunks",
            20_480L,
            fiveGibibytes / ProtocolLimits.MAX_CHUNK_SIZE_BYTES.toLong(),
        )
    }

    @Test fun `the largest file plus the largest chunk still fits a Long`() {
        val end = ProtocolLimits.checkedEnd(
            ProtocolLimits.MAX_FILE_SIZE_BYTES - ProtocolLimits.MAX_CHUNK_SIZE_BYTES,
            ProtocolLimits.MAX_CHUNK_SIZE_BYTES.toLong(),
        )
        assertEquals(ProtocolLimits.MAX_FILE_SIZE_BYTES, end)
        assertTrue(
            "no accepted range may come near Long.MAX_VALUE",
            end < Long.MAX_VALUE - ProtocolLimits.MAX_CHUNK_SIZE_BYTES,
        )
    }

    // --- range arithmetic cannot wrap --------------------------------------------------------------

    @Test fun `a range that would wrap is refused instead of becoming negative`() {
        val hostile = longArrayOf(
            Long.MAX_VALUE,
            Long.MAX_VALUE - 1,
            ProtocolLimits.MAX_FILE_SIZE_BYTES + 1,
            ProtocolLimits.MAX_FILE_SIZE_BYTES,
        )
        for (offset in hostile) {
            for (length in intArrayOf(0, 1, ProtocolLimits.MAX_CHUNK_SIZE_BYTES)) {
                if (offset == ProtocolLimits.MAX_FILE_SIZE_BYTES && length == 0) continue // exactly at the end
                assertFalse(
                    "offset $offset + length $length must be refused",
                    ProtocolLimits.isValidRange(offset, length.toLong()),
                )
            }
        }
    }

    @Test fun `checkedEnd only adds after the range has been accepted`() {
        assertEquals(10L, ProtocolLimits.checkedEnd(4L, 6L))
        assertEquals(ProtocolLimits.MAX_FILE_SIZE_BYTES, ProtocolLimits.checkedEnd(ProtocolLimits.MAX_FILE_SIZE_BYTES, 0L))
        for (bad in longArrayOf(-1L, ProtocolLimits.MAX_CHUNK_SIZE_BYTES + 1L)) {
            try {
                ProtocolLimits.checkedEnd(0L, bad)
                throw AssertionError("length $bad must be refused")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("not addressable") == true)
            }
        }
    }

    // --- wire lengths cannot become allocations ------------------------------------------------------

    @Test fun `a length read off the wire is bounded before it becomes an Int`() {
        assertEquals(0, ProtocolLimits.checkedToInt(0L, "payloadLength"))
        assertEquals(ProtocolLimits.MAX_PAYLOAD_BYTES, ProtocolLimits.checkedToInt(262_144L, "payloadLength"))
        assertNull(ProtocolLimits.checkedToIntOrNull(-1L))
        assertNull(ProtocolLimits.checkedToIntOrNull(Int.MAX_VALUE.toLong() + 1L))
        try {
            ProtocolLimits.checkedToInt(Long.MAX_VALUE, "payloadLength")
            throw AssertionError("a hostile length must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("payloadLength") == true)
        }
    }

    @Test fun `the fifteen frame types are numbered one to fifteen`() {
        val ids = FrameType.entries.map { it.id }
        assertEquals(15, ids.size)
        assertEquals((1..15).toList(), ids)
    }

    @Test fun `a maximal chunk payload round trips through the codec`() {
        val frame = maximalFrame()
        val parsed = FramePayloads.parse(frame) as PayloadResult.Success
        val chunk = parsed.payload as app.morsecode.core.transfer.protocol.FramePayload.DataChunk
        assertEquals(ProtocolLimits.MAX_PAYLOAD_BYTES, chunk.bytes.size)
        assertEquals(0L, chunk.offset)
    }

    // --- helpers ------------------------------------------------------------------------------------

    private val MAX_ID_BYTES: Int get() = ProtocolLimits.MAX_ID_LENGTH_BYTES

    private val MAX_SESSION_ID: SessionId get() = SessionId("a".repeat(MAX_ID_BYTES))
    private val MAX_TRANSFER_ID: TransferId get() = TransferId("b".repeat(MAX_ID_BYTES))
    private val MAX_RECIPIENT_ID: RecipientId get() = RecipientId("c".repeat(MAX_ID_BYTES))

    private fun maximalFrame() = FrameCodec.dataChunk(
        sessionId = MAX_SESSION_ID,
        transferId = MAX_TRANSFER_ID,
        recipientId = MAX_RECIPIENT_ID,
        offset = 0L,
        sequence = 0L,
        bytes = ByteArray(ProtocolLimits.MAX_PAYLOAD_BYTES),
    )
}
