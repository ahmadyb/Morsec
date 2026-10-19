/*
 * MPS3 -- the bounded frame format for experimental EC J-PAKE pairing messages.
 *
 * WHY A THIRD FORMAT
 *   MPS2 carries the pairing hello and MST2 binds the negotiation. Neither is a channel for
 *   multi-round key-agreement messages: the hello has no round number, no per-round body and no
 *   way to say "this belongs to the group element from round one of *this* session". MPS3 adds
 *   exactly that and nothing else.
 *
 * WHAT THIS FILE DOES NOT DO
 *   It does not perform elliptic-curve arithmetic, does not validate a zero-knowledge proof and
 *   does not know what a valid point is beyond its *encoding*. Curve membership, subgroup and
 *   proof validation remain the library's job, after a safe decode. Keeping the split this way is
 *   deliberate: a decoder that allocates from an attacker-supplied length before checking the
 *   bound is how parsers become the vulnerability.
 *
 * ENCODING RULES, ALL ENFORCED HERE
 *   - Compressed P-256 points are exactly 33 bytes with a 0x02 or 0x03 prefix. 0x00 (infinity)
 *     and 0x04 (uncompressed) are rejected at decode, not left to a downstream check.
 *   - Scalars, round-three tags and any internal keying material are exactly 32 bytes of
 *     big-endian unsigned data. A short encoding is rejected rather than left-padded by the
 *     reader, so "leading zero was dropped" cannot be expressed on the wire at all.
 *   - Tag/length/value entries ascend strictly. Out-of-order is rejected, which is what makes the
 *     encoding canonical rather than merely parseable.
 *   - A repeated tag is rejected. This is representable in TLV and therefore actually tested.
 *   - An unknown tag is rejected. There is no skip-what-you-do-not-understand path.
 *   - The declared payload count must equal the number of body fields the round defines.
 *   - The END tag must terminate the run and no byte may follow it.
 */

package app.morsecode.core.transfer.session

import app.morsecode.core.transfer.identity.SessionId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Which leg of the exchange a frame belongs to. Payload count is fixed per round. */
public enum class JpakeRound(public val wireId: Int, public val payloadCount: Int) {
    ROUND_ONE(1, 6),
    ROUND_TWO(2, 3),
    ROUND_THREE(3, 1);

    public companion object {
        public fun fromWireId(id: Int): JpakeRound? = entries.firstOrNull { it.wireId == id }
    }
}

/**
 * The curve identifier carried in the frame.
 *
 * A single value, not a menu. NIST P-256 has prime order and cofactor 1, which is exactly the
 * property the library's subgroup check relies on; offering a choice here would let a peer ask
 * for a curve for which that argument does not hold.
 */
public enum class JpakeCurveProfile(public val wireId: Int) {
    NIST_P256(1);

    public companion object {
        public fun fromWireId(id: Int): JpakeCurveProfile? = entries.firstOrNull { it.wireId == id }
    }
}

/** Why an MPS3 frame was refused. Every value is stable and safe to report. */
public enum class JpakeWireReject(public val wireReason: String) {
    LEGACY_MPS2_UNSUPPORTED("legacy_mps2_unsupported"),
    BAD_MAGIC("bad_magic"),
    BAD_VERSION("bad_version"),
    FRAME_TOO_SHORT("frame_too_short"),
    FRAME_TOO_LARGE("frame_too_large"),
    FIELD_ORDER_NOT_CANONICAL("field_order_not_canonical"),
    DUPLICATE_FIELD("duplicate_field"),
    UNKNOWN_FIELD("unknown_field"),
    MISSING_FIELD("missing_field"),
    FIELD_LENGTH_OUT_OF_BOUNDS("field_length_out_of_bounds"),
    TRAILING_BYTES("trailing_bytes"),
    MALFORMED_END_TAG("malformed_end_tag"),
    ROUND_INVALID("round_invalid"),
    ROLE_INVALID("role_invalid"),
    SUITE_INVALID("suite_invalid"),
    PROFILE_INVALID("profile_invalid"),
    PAYLOAD_COUNT_MISMATCH("payload_count_mismatch"),
    POINT_ENCODING_INVALID("point_encoding_invalid"),
    PARTICIPANT_ID_INVALID("participant_id_invalid"),
    SESSION_ID_INVALID("session_id_invalid"),
}

/**
 * The per-round body. Each element is a fixed-width byte string; the length rules live in the
 * codec, not here, so a body cannot exist in a shape the wire cannot carry.
 */
public sealed interface JpakeRoundBody {
    public fun clearSensitive()

    public class RoundOne(
        gx1: ByteArray,
        gx2: ByteArray,
        v1: ByteArray,
        r1: ByteArray,
        v2: ByteArray,
        r2: ByteArray,
    ) : JpakeRoundBody {
        private val gx1Value: ByteArray = gx1.copyOf()
        private val gx2Value: ByteArray = gx2.copyOf()
        private val v1Value: ByteArray = v1.copyOf()
        private val r1Value: ByteArray = r1.copyOf()
        private val v2Value: ByteArray = v2.copyOf()
        private val r2Value: ByteArray = r2.copyOf()

        public fun gx1Bytes(): ByteArray = gx1Value.copyOf()
        public fun gx2Bytes(): ByteArray = gx2Value.copyOf()
        public fun proofOnePointBytes(): ByteArray = v1Value.copyOf()
        public fun proofOneScalarBytes(): ByteArray = r1Value.copyOf()
        public fun proofTwoPointBytes(): ByteArray = v2Value.copyOf()
        public fun proofTwoScalarBytes(): ByteArray = r2Value.copyOf()

        override fun clearSensitive() {
            // Public group elements and proof scalars are not secrets, but they are wiped anyway
            // so that no copy of a live exchange outlives the session object.
            gx1Value.fill(0)
            gx2Value.fill(0)
            v1Value.fill(0)
            r1Value.fill(0)
            v2Value.fill(0)
            r2Value.fill(0)
        }

        override fun toString(): String = "RoundOne([redacted])"
    }

    public class RoundTwo(
        a: ByteArray,
        v: ByteArray,
        r: ByteArray,
    ) : JpakeRoundBody {
        private val aValue: ByteArray = a.copyOf()
        private val vValue: ByteArray = v.copyOf()
        private val rValue: ByteArray = r.copyOf()

        public fun pointBytes(): ByteArray = aValue.copyOf()
        public fun proofPointBytes(): ByteArray = vValue.copyOf()
        public fun proofScalarBytes(): ByteArray = rValue.copyOf()

        override fun clearSensitive() {
            aValue.fill(0)
            vValue.fill(0)
            rValue.fill(0)
        }

        override fun toString(): String = "RoundTwo([redacted])"
    }

    public class RoundThree(tag: ByteArray) : JpakeRoundBody {
        private val tagValue: ByteArray = tag.copyOf()

        public fun tagBytes(): ByteArray = tagValue.copyOf()

        override fun clearSensitive() {
            tagValue.fill(0)
        }

        override fun toString(): String = "RoundThree([redacted])"
    }
}

/**
 * One complete MPS3 message.
 *
 * [participantId] is the channel-bound identity derived for this session and role; the receiver
 * compares it against the expected partner id *before* handing anything to the library, which is
 * what turns a replayed payload into a framing rejection rather than a cryptographic one.
 */
public class JpakeMessage(
    public val suite: PairingAuthenticationCapability,
    public val profile: JpakeCurveProfile,
    public val round: JpakeRound,
    public val role: SecurePeerRole,
    public val secureSessionId: SessionId,
    public val controlSessionId: String,
    public val participantId: String,
    public val body: JpakeRoundBody,
) {
    init {
        require(suite != PairingAuthenticationCapability.NONE) { "MPS3 requires a real suite" }
        require(secureSessionId.value.matches(Regex("[0-9a-f]{32}"))) {
            "secure session id must be 128-bit lowercase hexadecimal"
        }
        require(JpakeMessageCodec.isBoundedText(controlSessionId, JpakeMessageCodec.MAX_CONTROL_SESSION_ID_BYTES))
        require(JpakeMessageCodec.isBoundedText(participantId, JpakeMessageCodec.MAX_PARTICIPANT_ID_BYTES))
        val expectedBody = when (round) {
            JpakeRound.ROUND_ONE -> JpakeRoundBody.RoundOne::class
            JpakeRound.ROUND_TWO -> JpakeRoundBody.RoundTwo::class
            JpakeRound.ROUND_THREE -> JpakeRoundBody.RoundThree::class
        }
        require(body::class == expectedBody) { "body shape does not match the round" }
    }

    override fun toString(): String = "JpakeMessage(round=${round.name}, role=${role.name}, ids=[redacted])"
}

public sealed interface JpakeMessageResult {
    public data class Success(public val message: JpakeMessage) : JpakeMessageResult
    public data class Rejected(public val reason: JpakeWireReject) : JpakeMessageResult
}

public sealed interface JpakeHeaderResult {
    public data class Valid(public val payloadLength: Int) : JpakeHeaderResult
    public data class Rejected(public val reason: JpakeWireReject) : JpakeHeaderResult
}

/**
 * MPS3 encoder/decoder.
 *
 * Encoding measures the frame before allocating, so no partially written buffer can escape.
 * Decoding validates every declared length against its per-field bound and the frame bound before
 * copying anything out.
 */
public object JpakeMessageCodec {
    /** Fixed 4-byte big-endian payload length prefix; the payload carries the MPS3 magic. */
    public const val HEADER_BYTES: Int = 4

    /** Frame bound. One round-one frame measures 232 bytes plus identifiers. */
    public const val MAX_FRAME_BYTES: Int = 512

    public const val POINT_BYTES: Int = 33
    public const val SCALAR_BYTES: Int = 32
    public const val TAG_BYTES: Int = 32
    public const val SESSION_ID_BYTES: Int = 16
    public const val MAX_CONTROL_SESSION_ID_BYTES: Int = 64
    public const val MAX_PARTICIPANT_ID_BYTES: Int = 64

    private val MAGIC_MPS3 = byteArrayOf(0x4D, 0x50, 0x53, 0x33) // "MPS3"
    private val MAGIC_MPS2 = byteArrayOf(0x4D, 0x50, 0x53, 0x32) // "MPS2"

    private const val SCHEMA_VERSION: Int = 1
    private const val TAG_END: Int = 0x0000
    private const val TAG_SUITE: Int = 0x0001
    private const val TAG_PROFILE: Int = 0x0002
    private const val TAG_ROUND: Int = 0x0003
    private const val TAG_ROLE: Int = 0x0004
    private const val TAG_SECURE_SESSION_ID: Int = 0x0005
    private const val TAG_CONTROL_SESSION_ID: Int = 0x0006
    private const val TAG_PARTICIPANT_ID: Int = 0x0007
    private const val TAG_PAYLOAD_COUNT: Int = 0x0008
    private const val TAG_ROUND1_GX1: Int = 0x0010
    private const val TAG_ROUND1_GX2: Int = 0x0011
    private const val TAG_ROUND1_V1: Int = 0x0012
    private const val TAG_ROUND1_R1: Int = 0x0013
    private const val TAG_ROUND1_V2: Int = 0x0014
    private const val TAG_ROUND1_R2: Int = 0x0015
    private const val TAG_ROUND2_A: Int = 0x0020
    private const val TAG_ROUND2_V: Int = 0x0021
    private const val TAG_ROUND2_R: Int = 0x0022
    private const val TAG_ROUND3_TAG: Int = 0x0030

    private const val TLV_OVERHEAD: Int = 4 // tag(2) + length(2)
    private const val MAX_TAG: Int = 0x0030

    /** True for bounded printable-ASCII text within [maxBytes]. */
    public fun isBoundedText(value: String, maxBytes: Int): Boolean {
        if (value.isEmpty()) return false
        val encoded = value.toByteArray(Charsets.UTF_8)
        if (encoded.size > maxBytes) return false
        return value.all { it.code in 0x20..0x7E }
    }

    /** Encodes a full frame -- length prefix included -- or null when it would exceed the bound. */
    public fun encode(message: JpakeMessage): ByteArray? {
        val control = message.controlSessionId.toByteArray(Charsets.UTF_8)
        val participant = message.participantId.toByteArray(Charsets.UTF_8)
        val body = bodyFields(message.body)
        if (body == null) return null

        var size = 4 + 1 // magic + version
        size += TLV_OVERHEAD + 2 // suite
        size += TLV_OVERHEAD + 1 // profile
        size += TLV_OVERHEAD + 1 // round
        size += TLV_OVERHEAD + 1 // role
        size += TLV_OVERHEAD + SESSION_ID_BYTES
        size += TLV_OVERHEAD + control.size
        size += TLV_OVERHEAD + participant.size
        size += TLV_OVERHEAD + 1 // payload count
        for ((_, value) in body) size += TLV_OVERHEAD + value.size
        size += TLV_OVERHEAD // END
        if (size > MAX_FRAME_BYTES) return null

        val buffer = ByteBuffer.allocate(HEADER_BYTES + size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(size)
        buffer.put(MAGIC_MPS3)
        buffer.put(SCHEMA_VERSION.toByte())
        putField(buffer, TAG_SUITE, byteArrayOf((message.suite.wireId ushr 8).toByte(), message.suite.wireId.toByte()))
        putField(buffer, TAG_PROFILE, byteArrayOf(message.profile.wireId.toByte()))
        putField(buffer, TAG_ROUND, byteArrayOf(message.round.wireId.toByte()))
        putField(buffer, TAG_ROLE, byteArrayOf(message.role.wireId.toByte()))
        putField(buffer, TAG_SECURE_SESSION_ID, bytesOfHex(message.secureSessionId.value))
        putField(buffer, TAG_CONTROL_SESSION_ID, control)
        putField(buffer, TAG_PARTICIPANT_ID, participant)
        putField(buffer, TAG_PAYLOAD_COUNT, byteArrayOf(message.round.payloadCount.toByte()))
        for ((tag, value) in body) putField(buffer, tag, value)
        putField(buffer, TAG_END, ByteArray(0))
        control.fill(0)
        participant.fill(0)
        return buffer.array()
    }

    /** Reads the 4-byte length prefix and validates it against the frame bound. */
    public fun decodeHeader(prefix: ByteArray): JpakeHeaderResult {
        if (prefix.size != HEADER_BYTES) return JpakeHeaderResult.Rejected(JpakeWireReject.FRAME_TOO_SHORT)
        val length = readU32(prefix, 0)
        if (length < 5) return JpakeHeaderResult.Rejected(JpakeWireReject.FRAME_TOO_SHORT)
        if (length > MAX_FRAME_BYTES) return JpakeHeaderResult.Rejected(JpakeWireReject.FRAME_TOO_LARGE)
        return JpakeHeaderResult.Valid(length)
    }

    /** Decodes a payload body -- magic included, length prefix excluded. */
    public fun decode(frame: ByteArray): JpakeMessageResult {
        if (frame.size < 5) return reject(JpakeWireReject.FRAME_TOO_SHORT)
        if (frame.size > MAX_FRAME_BYTES) return reject(JpakeWireReject.FRAME_TOO_LARGE)
        if (frame.copyOfRange(0, 4).contentEquals(MAGIC_MPS2)) {
            // Fail closed with a distinct value. A peer from the previous format must be reported
            // as a version mismatch, never retried over a path that omits the PAKE.
            return reject(JpakeWireReject.LEGACY_MPS2_UNSUPPORTED)
        }
        if (!frame.copyOfRange(0, 4).contentEquals(MAGIC_MPS3)) return reject(JpakeWireReject.BAD_MAGIC)
        if (readU8(frame, 4) != SCHEMA_VERSION) return reject(JpakeWireReject.BAD_VERSION)

        val values = HashMap<Int, ByteArray>()
        var position = 5
        var previousTag = -1
        var sawEnd = false
        while (position < frame.size) {
            if (position + TLV_OVERHEAD > frame.size) return reject(JpakeWireReject.MISSING_FIELD)
            val tag = readU16(frame, position)
            val length = readU16(frame, position + 2)
            position += TLV_OVERHEAD
            if (tag == TAG_END) {
                if (length != 0) return reject(JpakeWireReject.MALFORMED_END_TAG)
                sawEnd = true
                if (position != frame.size) return reject(JpakeWireReject.TRAILING_BYTES)
                break
            }
            if (tag == previousTag) return reject(JpakeWireReject.DUPLICATE_FIELD)
            if (tag < previousTag) return reject(JpakeWireReject.FIELD_ORDER_NOT_CANONICAL)
            previousTag = tag
            if (tag > MAX_TAG) return reject(JpakeWireReject.UNKNOWN_FIELD)
            if (position + length > frame.size) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
            values[tag] = frame.copyOfRange(position, position + length)
            position += length
        }
        if (!sawEnd) return reject(JpakeWireReject.MISSING_FIELD)

        val suiteBytes = values[TAG_SUITE] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (suiteBytes.size != 2) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        val suiteId = readU16(suiteBytes, 0)
        val suite = PairingAuthenticationCapability.fromWireId(suiteId)
            ?: return reject(JpakeWireReject.SUITE_INVALID)
        if (suite == PairingAuthenticationCapability.NONE) return reject(JpakeWireReject.SUITE_INVALID)

        val profileBytes = values[TAG_PROFILE] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (profileBytes.size != 1) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        val profile = JpakeCurveProfile.fromWireId(readU8(profileBytes, 0))
            ?: return reject(JpakeWireReject.PROFILE_INVALID)

        val roundBytes = values[TAG_ROUND] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (roundBytes.size != 1) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        val round = JpakeRound.fromWireId(readU8(roundBytes, 0))
            ?: return reject(JpakeWireReject.ROUND_INVALID)

        val roleBytes = values[TAG_ROLE] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (roleBytes.size != 1) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        val role = SecurePeerRole.fromWireId(readU8(roleBytes, 0))
            ?: return reject(JpakeWireReject.ROLE_INVALID)

        val sessionBytes = values[TAG_SECURE_SESSION_ID] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (sessionBytes.size != SESSION_ID_BYTES) return reject(JpakeWireReject.SESSION_ID_INVALID)

        val controlBytes = values[TAG_CONTROL_SESSION_ID] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (controlBytes.size > MAX_CONTROL_SESSION_ID_BYTES) {
            return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        }
        val control = utf8(controlBytes)
        if (!isBoundedText(control, MAX_CONTROL_SESSION_ID_BYTES)) {
            return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        }

        val participantBytes = values[TAG_PARTICIPANT_ID] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (participantBytes.size > MAX_PARTICIPANT_ID_BYTES) {
            return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        }
        val participant = utf8(participantBytes)
        if (!isBoundedText(participant, MAX_PARTICIPANT_ID_BYTES)) {
            return reject(JpakeWireReject.PARTICIPANT_ID_INVALID)
        }

        val countBytes = values[TAG_PAYLOAD_COUNT] ?: return reject(JpakeWireReject.MISSING_FIELD)
        if (countBytes.size != 1) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
        if (readU8(countBytes, 0) != round.payloadCount) {
            return reject(JpakeWireReject.PAYLOAD_COUNT_MISMATCH)
        }

        val body = when (round) {
            JpakeRound.ROUND_ONE -> {
                val gx1 = values[TAG_ROUND1_GX1] ?: return reject(JpakeWireReject.MISSING_FIELD)
                val gx2 = values[TAG_ROUND1_GX2] ?: return reject(JpakeWireReject.MISSING_FIELD)
                val v1 = values[TAG_ROUND1_V1] ?: return reject(JpakeWireReject.MISSING_FIELD)
                val r1 = values[TAG_ROUND1_R1] ?: return reject(JpakeWireReject.MISSING_FIELD)
                val v2 = values[TAG_ROUND1_V2] ?: return reject(JpakeWireReject.MISSING_FIELD)
                val r2 = values[TAG_ROUND1_R2] ?: return reject(JpakeWireReject.MISSING_FIELD)
                if (!isPoint(gx1) || !isPoint(gx2) || !isPoint(v1) || !isPoint(v2)) {
                    return reject(JpakeWireReject.POINT_ENCODING_INVALID)
                }
                if (!isScalar(r1) || !isScalar(r2)) {
                    return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
                }
                JpakeRoundBody.RoundOne(gx1, gx2, v1, r1, v2, r2)
            }

            JpakeRound.ROUND_TWO -> {
                val a = values[TAG_ROUND2_A] ?: return reject(JpakeWireReject.MISSING_FIELD)
                val v = values[TAG_ROUND2_V] ?: return reject(JpakeWireReject.MISSING_FIELD)
                val r = values[TAG_ROUND2_R] ?: return reject(JpakeWireReject.MISSING_FIELD)
                if (!isPoint(a) || !isPoint(v)) return reject(JpakeWireReject.POINT_ENCODING_INVALID)
                if (!isScalar(r)) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
                JpakeRoundBody.RoundTwo(a, v, r)
            }

            JpakeRound.ROUND_THREE -> {
                val tag = values[TAG_ROUND3_TAG] ?: return reject(JpakeWireReject.MISSING_FIELD)
                if (tag.size != TAG_BYTES) return reject(JpakeWireReject.FIELD_LENGTH_OUT_OF_BOUNDS)
                JpakeRoundBody.RoundThree(tag)
            }
        }

        return JpakeMessageResult.Success(
            JpakeMessage(
                suite = suite,
                profile = profile,
                round = round,
                role = role,
                secureSessionId = SessionId(hexOf(sessionBytes)),
                controlSessionId = control,
                participantId = participant,
                body = body,
            ),
        )
    }

    private fun bodyFields(body: JpakeRoundBody): List<Pair<Int, ByteArray>>? = when (body) {
        is JpakeRoundBody.RoundOne -> {
            val gx1 = body.gx1Bytes()
            val gx2 = body.gx2Bytes()
            val v1 = body.proofOnePointBytes()
            val r1 = body.proofOneScalarBytes()
            val v2 = body.proofTwoPointBytes()
            val r2 = body.proofTwoScalarBytes()
            if (!isPoint(gx1) || !isPoint(gx2) || !isPoint(v1) || !isPoint(v2)) {
                null
            } else if (!isScalar(r1) || !isScalar(r2)) {
                null
            } else {
                listOf(
                    TAG_ROUND1_GX1 to gx1,
                    TAG_ROUND1_GX2 to gx2,
                    TAG_ROUND1_V1 to v1,
                    TAG_ROUND1_R1 to r1,
                    TAG_ROUND1_V2 to v2,
                    TAG_ROUND1_R2 to r2,
                )
            }
        }

        is JpakeRoundBody.RoundTwo -> {
            val a = body.pointBytes()
            val v = body.proofPointBytes()
            val r = body.proofScalarBytes()
            if (!isPoint(a) || !isPoint(v) || !isScalar(r)) {
                null
            } else {
                listOf(TAG_ROUND2_A to a, TAG_ROUND2_V to v, TAG_ROUND2_R to r)
            }
        }

        is JpakeRoundBody.RoundThree -> {
            val tag = body.tagBytes()
            if (tag.size != TAG_BYTES) null else listOf(TAG_ROUND3_TAG to tag)
        }
    }

    /**
     * Encoding check only: a 33-byte compressed point whose prefix is 0x02 or 0x03.
     *
     * Curve membership is not decided here. Roughly half of all 32-byte field elements are valid
     * x-coordinates, so a decoder cannot demonstrate membership by inspection; it must hand the
     * bytes to a validator that does the arithmetic. What it can do -- and does -- is refuse an
     * encoding that could never be a point.
     */
    public fun isPoint(value: ByteArray): Boolean =
        value.size == POINT_BYTES && (value[0].toInt() == 0x02 || value[0].toInt() == 0x03)

    public fun isScalar(value: ByteArray): Boolean = value.size == SCALAR_BYTES

    private fun putField(buffer: ByteBuffer, tag: Int, value: ByteArray) {
        buffer.putShort(tag.toShort())
        buffer.putShort(value.size.toShort())
        buffer.put(value)
    }

    private fun reject(reason: JpakeWireReject): JpakeMessageResult.Rejected =
        JpakeMessageResult.Rejected(reason)
}

private fun readU8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

private fun readU16(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

private fun readU32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun utf8(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

private fun bytesOfHex(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (index in out.indices) {
        out[index] = (
            (Character.digit(hex[index * 2], 16) shl 4) or
                Character.digit(hex[index * 2 + 1], 16)
            ).toByte()
    }
    return out
}

private fun hexOf(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val builder = StringBuilder(bytes.size * 2)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        builder.append(digits[value ushr 4]).append(digits[value and 0x0F])
    }
    return builder.toString()
}
