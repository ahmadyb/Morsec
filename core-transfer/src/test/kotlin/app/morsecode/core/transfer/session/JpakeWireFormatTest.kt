package app.morsecode.core.transfer.session

import app.morsecode.core.transfer.identity.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPS3 framing tests.
 *
 * These are project framing vectors, not library conformance vectors. They pin the bytes this
 * project emits and refuses; they say nothing about whether Bouncy Castle's arithmetic is correct,
 * which no test in this repository can establish.
 */
public class JpakeWireFormatTest {

    private val tagEnd = 0x0000
    private val tagSuite = 0x0001
    private val tagProfile = 0x0002
    private val tagRound = 0x0003
    private val tagRole = 0x0004
    private val tagSession = 0x0005
    private val tagControl = 0x0006
    private val tagParticipant = 0x0007
    private val tagCount = 0x0008
    private val tagGx1 = 0x0010
    private val tagGx2 = 0x0011
    private val tagV1 = 0x0012
    private val tagR1 = 0x0013
    private val tagV2 = 0x0014
    private val tagR2 = 0x0015

    private fun point(prefix: Int): ByteArray = byteArrayOf(prefix.toByte()) + ByteArray(32) { 0x22 }
    private fun scalar(): ByteArray = ByteArray(32) { 0x33 }

    private fun tlv(tag: Int, value: ByteArray): ByteArray = byteArrayOf(
        (tag ushr 8).toByte(),
        tag.toByte(),
        (value.size ushr 8).toByte(),
        value.size.toByte(),
    ) + value

    private fun payload(
        suite: ByteArray = byteArrayOf(0, 2),
        profile: ByteArray = byteArrayOf(1),
        round: ByteArray = byteArrayOf(1),
        role: ByteArray = byteArrayOf(1),
        session: ByteArray = ByteArray(16) { 0x11 },
        control: ByteArray = "ctl-session".toByteArray(Charsets.UTF_8),
        participant: ByteArray = "morsec1-i-0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8),
        count: ByteArray = byteArrayOf(6),
        body: List<Pair<Int, ByteArray>> = listOf(
            tagGx1 to point(0x02),
            tagGx2 to point(0x03),
            tagV1 to point(0x02),
            tagR1 to scalar(),
            tagV2 to point(0x03),
            tagR2 to scalar(),
        ),
        tail: ByteArray = ByteArray(0),
    ): ByteArray {
        var frame = byteArrayOf(0x4D, 0x50, 0x53, 0x33, 0x01)
        frame += tlv(tagSuite, suite)
        frame += tlv(tagProfile, profile)
        frame += tlv(tagRound, round)
        frame += tlv(tagRole, role)
        frame += tlv(tagSession, session)
        frame += tlv(tagControl, control)
        frame += tlv(tagParticipant, participant)
        frame += tlv(tagCount, count)
        for ((tag, value) in body) frame += tlv(tag, value)
        frame += tlv(tagEnd, ByteArray(0))
        return frame + tail
    }

    private fun message(round: JpakeRound = JpakeRound.ROUND_ONE): JpakeMessage {
        val body: JpakeRoundBody = when (round) {
            JpakeRound.ROUND_ONE -> JpakeRoundBody.RoundOne(
                point(0x02), point(0x03), point(0x02), scalar(), point(0x03), scalar(),
            )
            JpakeRound.ROUND_TWO -> JpakeRoundBody.RoundTwo(point(0x02), point(0x03), scalar())
            JpakeRound.ROUND_THREE -> JpakeRoundBody.RoundThree(scalar())
        }
        return JpakeMessage(
            suite = PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
            profile = JpakeCurveProfile.NIST_P256,
            round = round,
            role = SecurePeerRole.INITIATOR,
            secureSessionId = SessionId("0123456789abcdef0123456789abcdef"),
            controlSessionId = "ctl-session",
            participantId = "morsec1-i-0123456789abcdef0123456789abcdef",
            body = body,
        )
    }

    private fun decodePayload(frame: ByteArray): JpakeMessageResult =
        JpakeMessageCodec.decode(frame)

    private fun reason(result: JpakeMessageResult): JpakeWireReject? =
        (result as? JpakeMessageResult.Rejected)?.reason

    @Test
    public fun `each round survives an encode decode round trip`() {
        for (round in JpakeRound.entries) {
            val original = message(round)
            val frame = JpakeMessageCodec.encode(original)
            assertNotNull("round $round must encode", frame)
            val payload = requireNotNull(frame).copyOfRange(
                JpakeMessageCodec.HEADER_BYTES,
                requireNotNull(frame).size,
            )
            val decoded = decodePayload(payload)
            val success = decoded as? JpakeMessageResult.Success
            assertNotNull("round $round must decode: $decoded", success)
            assertEquals(round, success!!.message.round)
            assertEquals(
                PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
                success.message.suite,
            )
            assertEquals(SecurePeerRole.INITIATOR, success.message.role)
        }
    }

    @Test
    public fun `the length prefix matches the payload and stays inside the bound`() {
        val frame = requireNotNull(JpakeMessageCodec.encode(message()))
        val header = frame.copyOfRange(0, JpakeMessageCodec.HEADER_BYTES)
        val result = JpakeMessageCodec.decodeHeader(header) as? JpakeHeaderResult.Valid
        assertNotNull(result)
        assertEquals(frame.size - JpakeMessageCodec.HEADER_BYTES, result!!.payloadLength)
        assertTrue(result.payloadLength <= JpakeMessageCodec.MAX_FRAME_BYTES)
    }

    @Test
    public fun `a header that declares more than the bound is refused before allocation`() {
        val oversized = byteArrayOf(0x00, 0x00, 0x10, 0x00)
        assertEquals(
            JpakeWireReject.FRAME_TOO_LARGE,
            (JpakeMessageCodec.decodeHeader(oversized) as JpakeHeaderResult.Rejected).reason,
        )
    }

    @Test
    public fun `the previous format is reported as a version problem, not a parse error`() {
        val mps2 = payload().copyOf()
        mps2[3] = 0x32 // "MPS2"
        assertEquals(JpakeWireReject.LEGACY_MPS2_UNSUPPORTED, reason(decodePayload(mps2)))
    }

    @Test
    public fun `a foreign magic and a future version are both refused`() {
        val badMagic = payload()
        badMagic[0] = 0x00
        assertEquals(JpakeWireReject.BAD_MAGIC, reason(decodePayload(badMagic)))
        val badVersion = payload()
        badVersion[4] = 0x02
        assertEquals(JpakeWireReject.BAD_VERSION, reason(decodePayload(badVersion)))
    }

    @Test
    public fun `a repeated field is refused distinctly from an unordered one`() {
        val frame = payload()
        // The suite field occupies bytes 5..10; inserting a second copy immediately after it is
        // the smallest representable duplicate.
        val duplicate = frame.copyOfRange(0, 11) + frame.copyOfRange(5, 11) + frame.copyOfRange(11, frame.size)
        assertEquals(JpakeWireReject.DUPLICATE_FIELD, reason(decodePayload(duplicate)))

        val unordered = frame.copyOf()
        unordered[5] = 0x00
        unordered[6] = 0x09 // becomes tag 0x0009, which precedes nothing and is followed by 0x0002
        assertEquals(JpakeWireReject.FIELD_ORDER_NOT_CANONICAL, reason(decodePayload(unordered)))
    }

    @Test
    public fun `a tag outside the closed set is refused`() {
        val frame = payload()
        frame[5] = 0x00
        frame[6] = 0x40 // 0x0040 is above the highest defined tag
        assertEquals(JpakeWireReject.UNKNOWN_FIELD, reason(decodePayload(frame)))
    }

    @Test
    public fun `truncation, a missing end tag and trailing bytes are distinct refusals`() {
        val frame = payload()
        assertEquals(
            JpakeWireReject.MISSING_FIELD,
            reason(decodePayload(frame.copyOfRange(0, frame.size - 4))),
        )
        assertEquals(
            JpakeWireReject.TRAILING_BYTES,
            reason(decodePayload(frame + byteArrayOf(0x00))),
        )
    }

    @Test
    public fun `an infinity or uncompressed point is refused at decode`() {
        val infinity = payload(
            body = listOf(
                tagGx1 to point(0x00),
                tagGx2 to point(0x03),
                tagV1 to point(0x02),
                tagR1 to scalar(),
                tagV2 to point(0x03),
                tagR2 to scalar(),
            ),
        )
        assertEquals(JpakeWireReject.POINT_ENCODING_INVALID, reason(decodePayload(infinity)))

        val uncompressed = payload(
            body = listOf(
                tagGx1 to point(0x04),
                tagGx2 to point(0x03),
                tagV1 to point(0x02),
                tagR1 to scalar(),
                tagV2 to point(0x03),
                tagR2 to scalar(),
            ),
        )
        assertEquals(JpakeWireReject.POINT_ENCODING_INVALID, reason(decodePayload(uncompressed)))
    }

    @Test
    public fun `a short scalar is refused rather than left padded`() {
        val short = payload(
            body = listOf(
                tagGx1 to point(0x02),
                tagGx2 to point(0x03),
                tagV1 to point(0x02),
                tagR1 to ByteArray(31) { 0x33 },
                tagV2 to point(0x03),
                tagR2 to scalar(),
            ),
        )
        assertEquals(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS, reason(decodePayload(short)))
    }

    @Test
    public fun `the declared payload count must match the round`() {
        assertEquals(
            JpakeWireReject.PAYLOAD_COUNT_MISMATCH,
            reason(decodePayload(payload(count = byteArrayOf(3)))),
        )
    }

    @Test
    public fun `an unknown suite or profile is refused`() {
        assertEquals(
            JpakeWireReject.SUITE_INVALID,
            reason(decodePayload(payload(suite = byteArrayOf(0, 9)))),
        )
        assertEquals(
            JpakeWireReject.SUITE_INVALID,
            reason(decodePayload(payload(suite = byteArrayOf(0, 0)))),
        )
        assertEquals(
            JpakeWireReject.PROFILE_INVALID,
            reason(decodePayload(payload(profile = byteArrayOf(2)))),
        )
    }

    @Test
    public fun `an invalid role or round is refused`() {
        assertEquals(
            JpakeWireReject.ROLE_INVALID,
            reason(decodePayload(payload(role = byteArrayOf(9)))),
        )
        assertEquals(
            JpakeWireReject.ROUND_INVALID,
            reason(decodePayload(payload(round = byteArrayOf(4)))),
        )
    }

    @Test
    public fun `an over long participant identifier is refused`() {
        val long = ByteArray(65) { 'a'.code.toByte() }
        assertEquals(
            JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS,
            reason(decodePayload(payload(participant = long))),
        )
    }

    @Test
    public fun `a non printable participant identifier is refused`() {
        val control = byteArrayOf(0x07)
        assertEquals(
            JpakeWireReject.PARTICIPANT_ID_INVALID,
            reason(decodePayload(payload(participant = control))),
        )
    }

    @Test
    public fun `a body shape that disagrees with the round cannot be constructed`() {
        var threw = false
        try {
            JpakeMessage(
                suite = PairingAuthenticationCapability.EC_JPAKE_P256_ENTERED_CODE_V1,
                profile = JpakeCurveProfile.NIST_P256,
                round = JpakeRound.ROUND_THREE,
                role = SecurePeerRole.INITIATOR,
                secureSessionId = SessionId("0123456789abcdef0123456789abcdef"),
                controlSessionId = "ctl",
                participantId = "morsec1-i-0123456789abcdef0123456789abcdef",
                body = JpakeRoundBody.RoundOne(
                    point(0x02), point(0x03), point(0x02), scalar(), point(0x03), scalar(),
                ),
            )
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a round-three message must not carry a round-one body", threw)
    }

    @Test
    public fun `an all zero point is encoding valid and left for the library to reject`() {
        // The decoder must not pretend to decide curve membership. Half of all 32-byte field
        // elements are valid x-coordinates, so only arithmetic can settle it.
        assertTrue(JpakeMessageCodec.isPoint(point(0x02)))
        assertTrue(JpakeMessageCodec.isPoint(point(0x03)))
        assertFalse(JpakeMessageCodec.isPoint(point(0x00)))
        assertFalse(JpakeMessageCodec.isPoint(point(0x04)))
        assertFalse(JpakeMessageCodec.isPoint(ByteArray(32)))
        assertNull((decodePayload(payload()) as? JpakeMessageResult.Rejected)?.reason)
    }
}
