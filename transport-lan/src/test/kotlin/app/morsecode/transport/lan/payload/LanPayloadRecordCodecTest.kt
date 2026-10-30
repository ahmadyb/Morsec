package app.morsecode.transport.lan.payload

import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.session.SecureRecordDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The MPL1 codec's behaviour, one refusal at a time.
 *
 * Each test names the thing a hostile peer would try and the reason it must be refused, so a future
 * change that turns a refusal into an acceptance fails on the assertion rather than on a fuzzer in
 * production. The decoder's contract is the interesting half: no input makes it throw, every input
 * gets a typed answer, and nothing is allocated from a length that has not been bounded first.
 *
 * What is *not* tested here, because it does not exist yet: key derivation, encryption, tag
 * verification, sockets, files and authority. The sixteen trailing bytes are carried, never checked.
 */
class LanPayloadRecordCodecTest {

    // --- the fixed header ------------------------------------------------------------------------

    @Test fun `the header is thirty four bytes, field by field`() {
        // magic 4 + version 1 + session id 16 + direction 1 + sequence 8 + declared length 4
        assertEquals(34, 4 + 1 + 16 + 1 + 8 + 4)
        assertEquals(34, LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES)
        assertEquals(16, LanPayloadRecordFormat.SESSION_ID_BYTES)
        assertEquals(4, LanPayloadRecordFormat.DECLARED_LENGTH_BYTES)
        assertEquals(16, LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES)
    }

    @Test fun `the offsets tile the header without a gap or an overlap`() {
        assertEquals(0, LanPayloadRecordFormat.OFF_MAGIC)
        assertEquals(LanPayloadRecordFormat.OFF_MAGIC + 4, LanPayloadRecordFormat.OFF_VERSION)
        assertEquals(LanPayloadRecordFormat.OFF_VERSION + 1, LanPayloadRecordFormat.OFF_SESSION_ID)
        assertEquals(
            LanPayloadRecordFormat.OFF_SESSION_ID + LanPayloadRecordFormat.SESSION_ID_BYTES,
            LanPayloadRecordFormat.OFF_DIRECTION,
        )
        assertEquals(
            LanPayloadRecordFormat.OFF_DIRECTION + 1,
            LanPayloadRecordFormat.OFF_SEQUENCE,
        )
        assertEquals(
            LanPayloadRecordFormat.OFF_SEQUENCE + Long.SIZE_BYTES,
            LanPayloadRecordFormat.OFF_DECLARED_LENGTH,
        )
        assertEquals(
            LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES,
            LanPayloadRecordFormat.OFF_DECLARED_LENGTH + LanPayloadRecordFormat.DECLARED_LENGTH_BYTES,
        )
    }

    @Test fun `the encoded header is exactly the documented size`() {
        assertEquals(34, header().size)
    }

    // --- round trips ------------------------------------------------------------------------------

    @Test fun `a zero length body is a structurally valid envelope`() {
        val encoded = LanPayloadRecordCodec.encode(record(body = ByteArray(0)))
        assertEquals(34 + 16, encoded.size)
        val decoded = decode(encoded)
        assertTrue(decoded.bodyBytes().isEmpty())
        assertEquals(16, decoded.tagBytes().size)
    }

    @Test fun `a record round trips in both directions`() {
        for (direction in SecureRecordDirection.entries) {
            val encoded = LanPayloadRecordCodec.encode(
                record(direction = direction, body = ByteArray(12) { it.toByte() }),
            )
            val decoded = decode(encoded, direction = direction)
            assertEquals(SESSION_ID, decoded.sessionId)
            assertEquals(direction, decoded.direction)
            assertEquals(0L, decoded.sequence)
            assertArrayEquals(ByteArray(12) { it.toByte() }, decoded.bodyBytes())
            assertArrayEquals(TAG, decoded.tagBytes())
        }
    }

    @Test fun `the largest signed sequence survives the round trip`() {
        val encoded = LanPayloadRecordCodec.encode(record(sequence = Long.MAX_VALUE, body = ByteArray(1)))
        val decoded = decode(encoded, sequence = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, decoded.sequence)
    }

    @Test fun `a maximal body round trips at exactly the wire maximum`() {
        val body = ByteArray(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES) { (it and 0xFF).toByte() }
        val encoded = LanPayloadRecordCodec.encode(record(body = body))
        assertEquals(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES, encoded.size)
        val decoded = decode(encoded)
        assertEquals(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES, decoded.bodyBytes().size)
        assertArrayEquals(body, decoded.bodyBytes())
    }

    @Test fun `a decoded record hands out copies, not its own buffers`() {
        val decoded = decode(LanPayloadRecordCodec.encode(record(body = ByteArray(8) { 7.toByte() })))
        val first = decoded.bodyBytes()
        first.fill(0)
        assertArrayEquals(ByteArray(8) { 7.toByte() }, decoded.bodyBytes())
        val tag = decoded.tagBytes()
        tag.fill(0)
        assertArrayEquals(TAG, decoded.tagBytes())
    }

    @Test fun `clearing a record wipes the body and leaves the tag`() {
        val decoded = decode(LanPayloadRecordCodec.encode(record(body = ByteArray(4) { 1.toByte() })))
        decoded.clearSensitive()
        assertArrayEquals(ByteArray(4), decoded.bodyBytes())
        assertArrayEquals(TAG, decoded.tagBytes())
    }

    @Test fun `the record does not print the session id`() {
        val text = decode(LanPayloadRecordCodec.encode(record())).toString()
        assertFalse(text, text.contains(SESSION_HEX))
        assertTrue(text, text.contains("[redacted]"))
    }

    // --- truncation ------------------------------------------------------------------------------

    @Test fun `every truncation of the header is refused`() {
        val full = header(declaredLength = 0)
        for (size in 0 until LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES) {
            val result = LanPayloadRecordCodec.decodeHeader(
                full.copyOf(size),
                SESSION_ID,
                SecureRecordDirection.INITIATOR_TO_RESPONDER,
                0L,
            )
            assertHeaderRefused(LanPayloadRefusal.TRUNCATED, result)
        }
        // The whole header is accepted, so the loop above is testing truncation and not a broken
        // fixture.
        assertEquals(
            LanPayloadHeader(SESSION_ID, SecureRecordDirection.INITIATOR_TO_RESPONDER, 0L, 0),
            (LanPayloadRecordCodec.decodeHeader(
                full,
                SESSION_ID,
                SecureRecordDirection.INITIATOR_TO_RESPONDER,
                0L,
            ) as LanPayloadHeaderResult.Decoded).header,
        )
    }

    @Test fun `a frame smaller than a header and a tag is refused`() {
        val encoded = LanPayloadRecordCodec.encode(record())
        for (size in 0 until 34 + 16) {
            assertDecodeRefused(LanPayloadRefusal.TRUNCATED, encoded.copyOf(size))
        }
    }

    @Test fun `a missing tag byte is refused`() {
        val encoded = LanPayloadRecordCodec.encode(record(body = ByteArray(4)))
        assertDecodeRefused(LanPayloadRefusal.LENGTH_INCONSISTENT, encoded.copyOf(encoded.size - 1))
    }

    @Test fun `a declared body the frame does not contain is refused`() {
        val header = header(declaredLength = 16)
        val frame = header + ByteArray(8) + TAG
        assertDecodeRefused(LanPayloadRefusal.LENGTH_INCONSISTENT, frame)
    }

    @Test fun `a trailing byte is refused`() {
        val encoded = LanPayloadRecordCodec.encode(record(body = ByteArray(4)))
        assertDecodeRefused(LanPayloadRefusal.TRAILING_BYTES, encoded + ByteArray(1))
    }

    @Test fun `a frame above the wire maximum is refused before anything is parsed`() {
        val oversized = ByteArray(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES + 1)
        assertDecodeRefused(LanPayloadRefusal.WIRE_SIZE_ABOVE_MAXIMUM, oversized)
    }

    // --- format identity -------------------------------------------------------------------------

    @Test fun `each magic byte is load bearing`() {
        for (index in 0 until 4) {
            val mutated = header()
            mutated[index] = (mutated[index].toInt() xor 0x01).toByte()
            assertHeaderRefused(LanPayloadRefusal.WRONG_MAGIC, decodeHeader(mutated))
        }
    }

    @Test fun `an unsupported version is refused`() {
        for (version in intArrayOf(0, 2, 255)) {
            val mutated = header()
            mutated[LanPayloadRecordFormat.OFF_VERSION] = version.toByte()
            assertHeaderRefused(LanPayloadRefusal.UNSUPPORTED_VERSION, decodeHeader(mutated))
        }
    }

    @Test fun `an unknown direction is refused`() {
        for (direction in intArrayOf(0, 3, 255)) {
            val mutated = header()
            mutated[LanPayloadRecordFormat.OFF_DIRECTION] = direction.toByte()
            assertHeaderRefused(LanPayloadRefusal.UNKNOWN_DIRECTION, decodeHeader(mutated))
        }
    }

    // --- substitution ----------------------------------------------------------------------------

    @Test fun `a record from another session is refused`() {
        val encoded = LanPayloadRecordCodec.encode(record())
        assertDecodeRefused(
            LanPayloadRefusal.SESSION_MISMATCH,
            encoded,
            sessionId = OTHER_SESSION_ID,
        )
    }

    @Test fun `a reflected record is refused`() {
        val encoded = LanPayloadRecordCodec.encode(
            record(direction = SecureRecordDirection.INITIATOR_TO_RESPONDER),
        )
        assertDecodeRefused(
            LanPayloadRefusal.DIRECTION_MISMATCH,
            encoded,
            direction = SecureRecordDirection.RESPONDER_TO_INITIATOR,
        )
    }

    @Test fun `a replayed or reordered sequence is refused`() {
        val first = LanPayloadRecordCodec.encode(record(sequence = 0L))
        // The same bytes are accepted only as sequence zero, and a re-offer at any other expected
        // position is refused -- which is exactly the replay case.
        assertEquals(0L, decode(first, sequence = 0L).sequence)
        assertDecodeRefused(LanPayloadRefusal.UNEXPECTED_SEQUENCE, first, sequence = 1L)
        assertDecodeRefused(LanPayloadRefusal.UNEXPECTED_SEQUENCE, first, sequence = 5L)
        val second = LanPayloadRecordCodec.encode(record(sequence = 1L))
        assertEquals(1L, decode(second, sequence = 1L).sequence)
    }

    @Test fun `a sequence at or above two to the sixty three is refused, not reinterpreted`() {
        for (highBit in intArrayOf(0x80, 0xFF)) {
            val mutated = header()
            mutated[LanPayloadRecordFormat.OFF_SEQUENCE] = highBit.toByte()
            assertHeaderRefused(LanPayloadRefusal.SEQUENCE_OUT_OF_RANGE, decodeHeader(mutated))
        }
    }

    // --- declared length -------------------------------------------------------------------------

    @Test fun `a declared length above the maximum is refused before it is narrowed`() {
        for (declared in longArrayOf(65_773L, 0x8000_0000L, 0xFFFF_FFFFL)) {
            val mutated = header()
            writeDeclaredLength(mutated, declared)
            assertHeaderRefused(LanPayloadRefusal.LENGTH_ABOVE_MAXIMUM, decodeHeader(mutated))
        }
    }

    @Test fun `the maximal declared length is accepted`() {
        val mutated = header()
        writeDeclaredLength(mutated, 65_772L)
        val decoded = (decodeHeader(mutated) as LanPayloadHeaderResult.Decoded).header
        assertEquals(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES, decoded.declaredLength)
    }

    // --- encoder preconditions -------------------------------------------------------------------

    @Test fun `the encoder refuses a body above the maximum`() {
        assertIllegalArgument {
            record(body = ByteArray(LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES + 1))
        }
    }

    @Test fun `the encoder refuses a negative sequence`() {
        assertIllegalArgument {
            LanPayloadRecordCodec.encodeHeader(
                SESSION_ID,
                SecureRecordDirection.INITIATOR_TO_RESPONDER,
                -1L,
                0,
            )
        }
    }

    @Test fun `the encoder refuses a declared length above the maximum`() {
        assertIllegalArgument {
            LanPayloadRecordCodec.encodeHeader(
                SESSION_ID,
                SecureRecordDirection.INITIATOR_TO_RESPONDER,
                0L,
                LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES + 1,
            )
        }
    }

    @Test fun `the encoder refuses a tag that is not sixteen bytes`() {
        assertIllegalArgument {
            LanPayloadRecord(SESSION_ID, SecureRecordDirection.INITIATOR_TO_RESPONDER, 0L, ByteArray(1), ByteArray(15))
        }
    }

    @Test fun `the encoder refuses a session id that is not the issued form`() {
        assertIllegalArgument {
            LanPayloadRecordCodec.encodeHeader(
                SessionId("not-a-hex-session"),
                SecureRecordDirection.INITIATOR_TO_RESPONDER,
                0L,
                0,
            )
        }
    }

    @Test fun `a negative expected sequence is a local programming error, not a wire refusal`() {
        assertIllegalArgument {
            LanPayloadRecordCodec.decodeHeader(
                header(),
                SESSION_ID,
                SecureRecordDirection.INITIATOR_TO_RESPONDER,
                -1L,
            )
        }
    }

    // --- wire-size arithmetic --------------------------------------------------------------------

    @Test fun `the wire size is the header plus the body plus the tag`() {
        assertEquals(50L, LanPayloadRecordCodec.wireSizeFor(0L))
        assertEquals(66L, LanPayloadRecordCodec.wireSizeFor(16L))
        assertEquals(65_822L, LanPayloadRecordCodec.wireSizeFor(65_772L))
        assertNull(LanPayloadRecordCodec.wireSizeFor(65_773L))
        assertNull(LanPayloadRecordCodec.wireSizeFor(-1L))
    }

    @Test fun `the wire maximum is the sum of its parts`() {
        assertEquals(
            LanPayloadRecordFormat.PAYLOAD_RECORD_HEADER_BYTES +
                LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES +
                LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES,
            LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES,
        )
        assertEquals(65_822, LanPayloadRecordFormat.MAX_PAYLOAD_RECORD_WIRE_BYTES)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private val SESSION_HEX: String = "0123456789abcdef0123456789abcdef"
    private val SESSION_ID: SessionId = SessionId(SESSION_HEX)
    private val OTHER_SESSION_ID: SessionId = SessionId("fedcba9876543210fedcba9876543210")
    private val TAG: ByteArray = ByteArray(LanPayloadRecordFormat.PAYLOAD_RECORD_TAG_BYTES) { 0xA5.toByte() }

    private fun header(
        sequence: Long = 0L,
        direction: SecureRecordDirection = SecureRecordDirection.INITIATOR_TO_RESPONDER,
        declaredLength: Int = 0,
    ): ByteArray = LanPayloadRecordCodec.encodeHeader(SESSION_ID, direction, sequence, declaredLength)

    private fun record(
        sequence: Long = 0L,
        direction: SecureRecordDirection = SecureRecordDirection.INITIATOR_TO_RESPONDER,
        body: ByteArray = ByteArray(0),
    ): LanPayloadRecord = LanPayloadRecord(SESSION_ID, direction, sequence, body, TAG)

    private fun decodeHeader(
        bytes: ByteArray,
        sessionId: SessionId = SESSION_ID,
        direction: SecureRecordDirection = SecureRecordDirection.INITIATOR_TO_RESPONDER,
        sequence: Long = 0L,
    ): LanPayloadHeaderResult =
        LanPayloadRecordCodec.decodeHeader(bytes, sessionId, direction, sequence)

    private fun decode(
        frame: ByteArray,
        sessionId: SessionId = SESSION_ID,
        direction: SecureRecordDirection = SecureRecordDirection.INITIATOR_TO_RESPONDER,
        sequence: Long = 0L,
    ): LanPayloadRecord {
        val result = LanPayloadRecordCodec.decode(frame, sessionId, direction, sequence)
        assertTrue("expected a decoded record, got $result", result is LanPayloadDecodeResult.Decoded)
        return (result as LanPayloadDecodeResult.Decoded).record
    }

    private fun assertDecodeRefused(
        expected: LanPayloadRefusal,
        frame: ByteArray,
        sessionId: SessionId = SESSION_ID,
        direction: SecureRecordDirection = SecureRecordDirection.INITIATOR_TO_RESPONDER,
        sequence: Long = 0L,
    ) {
        val result = LanPayloadRecordCodec.decode(frame, sessionId, direction, sequence)
        assertTrue("expected $expected, got $result", result is LanPayloadDecodeResult.Refused)
        assertEquals(expected, (result as LanPayloadDecodeResult.Refused).reason)
    }

    private fun assertHeaderRefused(
        expected: LanPayloadRefusal,
        result: LanPayloadHeaderResult,
    ) {
        assertTrue("expected $expected, got $result", result is LanPayloadHeaderResult.Refused)
        assertEquals(expected, (result as LanPayloadHeaderResult.Refused).reason)
    }

    private fun writeDeclaredLength(header: ByteArray, declared: Long) {
        val offset = LanPayloadRecordFormat.OFF_DECLARED_LENGTH
        for (index in 0 until 4) {
            val shift = (3 - index) * 8
            header[offset + index] = ((declared ushr shift) and 0xFF).toByte()
        }
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(
            "expected ${expected.toList()} but was ${actual.toList()}",
            expected.contentEquals(actual),
        )
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            assertNotEquals("", expected.message ?: "")
            return
        }
        throw AssertionError("expected an IllegalArgumentException")
    }
}
