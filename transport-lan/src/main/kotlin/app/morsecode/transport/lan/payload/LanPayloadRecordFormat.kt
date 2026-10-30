package app.morsecode.transport.lan.payload

import app.morsecode.core.transfer.identity.SessionId

/*
 * MPL1 -- the framing for one payload record on an authenticated LAN session.
 *
 * WHY THIS LIVES IN :transport-lan AND NOT IN :core-transfer
 *   The MSC1 frame in :core-transfer is transport-independent. The same bytes mean the same thing
 *   over LAN, over Nearby and inside a test harness, so its numbers are transfer-domain numbers and
 *   belong to the engine. MPL1 is not that: it is the LAN envelope that carries one MSC1 frame
 *   through one authenticated byte stream, and its ceiling is derived from what a LAN chunk needs.
 *   Putting it here keeps a transport decision from becoming a rule the transport-neutral core has
 *   to enforce, and keeps the core's limits table honest about what it is bounded by.
 *
 * WHAT THIS LAYER DOES, STATED SO IT CANNOT BE MISTAKEN FOR MORE
 *   It writes and reads a fixed 34-byte header and treats everything after it as opaque bytes plus
 *   a fixed-size authentication tag. It derives no keys, performs no authenticated encryption,
 *   opens no socket, touches no file and issues no authority. The tag is a sixteen-byte field that
 *   must be present and correctly framed; whether its contents are valid is a question for the
 *   layer that will produce and check it, which does not exist yet. Nothing in production
 *   constructs or decodes one of these records, and the payload gate still refuses every session.
 *
 * LAYOUT (big-endian throughout; 34-byte fixed header, then the body, then the tag)
 *
 *    0  .. 3   magic 'MPL1'                          0x4D 0x50 0x4C 0x31
 *    4         wire version, 1
 *    5  .. 20  secure session id, 16 raw bytes
 *    21        direction: 1 initiator -> responder, 2 responder -> initiator
 *    22 .. 29  sequence, signed 64-bit, non-negative
 *    30 .. 33  declared body length, unsigned 32-bit
 *    34 ..     body: exactly one MSC1 frame
 *    ...       authentication tag, 16 bytes
 *
 *    field sizes: 4 + 1 + 16 + 1 + 8 + 4 = 34
 *
 * WHY THE LENGTH FIELD IS FOUR BYTES
 *   A 64 KiB chunk with three maximum-length identifiers is a 65,772-byte MSC1 frame, and 65,772
 *   does not fit an unsigned 16-bit field. The control layer next door uses two bytes because its
 *   plaintext ceiling is 4,096; copying that width here would have capped the body just under
 *   65,536 and, worse, a decoder that narrowed a declared 65,772 into sixteen bits would then have
 *   accepted a length it could not reassemble. The field is read as a Long, bounded against the
 *   fixed maximum, and only then narrowed to an Int. A declared 0xFFFFFFFF is a refusal, never a
 *   negative Int and never an allocation.
 *
 * WHY THERE IS NO OUTER RECORD TYPE
 *   The body is exactly one MSC1 frame and that frame already carries its own type byte. A second
 *   type field here could disagree with it, and the disagreement would be resolved by whichever
 *   layer read last. One frame, one type, one owner.
 *
 * STATUS
 *   Proposed and inert. The construction this will eventually protect is experimental,
 *   project-defined and not independently cryptographically reviewed; the owner accepted that risk
 *   on 2026-10-19 for this personal application. See doc/security/owner-risk-waiver-2026-10-19.md.
 *   Nothing here establishes security, because nothing here authenticates anything yet.
 */
internal object LanPayloadRecordFormat {

    /** Magic 'M' of MPL1. */
    const val MAGIC_BYTE_0: Int = 0x4D

    /** Magic 'P' of MPL1. */
    const val MAGIC_BYTE_1: Int = 0x50

    /** Magic 'L' of MPL1. */
    const val MAGIC_BYTE_2: Int = 0x4C

    /** Magic '1' of MPL1. */
    const val MAGIC_BYTE_3: Int = 0x31

    /** The only wire version this build writes, and the only one it will read. */
    const val WIRE_VERSION: Int = 1

    /** The secure session id travels as sixteen raw bytes, as it does in the control layer. */
    const val SESSION_ID_BYTES: Int = 16

    /** Fixed header size: magic, version, session id, direction, sequence, declared length. */
    const val PAYLOAD_RECORD_HEADER_BYTES: Int = 34

    /** Width of the declared-length field. Four bytes because the body can exceed 65,535. */
    const val DECLARED_LENGTH_BYTES: Int = 4

    /** Width of the authentication tag that follows the body. */
    const val PAYLOAD_RECORD_TAG_BYTES: Int = 16

    /**
     * The chunk size the LAN payload profile offers.
     *
     * This is a profile choice, not a new ceiling: the transfer core still honours
     * `ProtocolLimits.MAX_CHUNK_SIZE_BYTES` (262,144) for every transport. A 64 KiB chunk keeps the
     * per-direction buffers at roughly 66 KB each, which is what an API 23 device can hold for two
     * simultaneous files without the allocator becoming the bottleneck.
     */
    const val LAN_PAYLOAD_CHUNK_BYTES: Int = 65_536

    /**
     * Largest body a payload record may carry.
     *
     * Derived, and kept in step by a test that encodes a real maximal MSC1 frame at the LAN chunk
     * size rather than trusting this comment:
     *
     *     ProtocolLimits.HEADER_SIZE_BYTES          44
     *   + 3 * ProtocolLimits.MAX_ID_LENGTH_BYTES   192
     *   + LAN_PAYLOAD_CHUNK_BYTES                       65,536
     *   =                                       65,772
     */
    const val MAX_PAYLOAD_RECORD_PLAINTEXT_BYTES: Int = 65_772

    /** Largest record this build will ever allocate: header plus maximal body plus tag. */
    const val MAX_PAYLOAD_RECORD_WIRE_BYTES: Int = 65_822

    /** Offset of the magic bytes. */
    const val OFF_MAGIC: Int = 0

    /** Offset of the wire version. */
    const val OFF_VERSION: Int = 4

    /** Offset of the sixteen raw session-id bytes. */
    const val OFF_SESSION_ID: Int = 5

    /** Offset of the direction byte. */
    const val OFF_DIRECTION: Int = 21

    /** Offset of the signed 64-bit sequence. */
    const val OFF_SEQUENCE: Int = 22

    /** Offset of the unsigned 32-bit declared length. */
    const val OFF_DECLARED_LENGTH: Int = 30

    /** The session id this layer carries: exactly the thirty-two lowercase hex characters above. */
    private val SESSION_ID_PATTERN = Regex("[0-9a-f]{32}")

    private const val HEX_DIGITS = "0123456789abcdef"

    /** A fresh copy of the four magic bytes; the constant itself is never handed out. */
    fun magicBytes(): ByteArray = byteArrayOf(
        MAGIC_BYTE_0.toByte(),
        MAGIC_BYTE_1.toByte(),
        MAGIC_BYTE_2.toByte(),
        MAGIC_BYTE_3.toByte(),
    )

    /** True when [sessionId] is the shape the control layer issues. */
    fun isEncodableSessionId(sessionId: SessionId): Boolean =
        SESSION_ID_PATTERN.matches(sessionId.value)

    /**
     * The sixteen bytes of [sessionId].
     *
     * The caller owns the returned array and is expected to zero it: a session id is not a secret,
     * but it is a correlator, and the project wipes identifiers it has finished with rather than
     * leaving them in a buffer that outlives the frame.
     *
     * @throws IllegalArgumentException if the id is not the thirty-two-character lowercase form.
     */
    fun requireSessionIdBytes(sessionId: SessionId): ByteArray {
        require(isEncodableSessionId(sessionId)) {
            "the payload record layer carries a 32-character lowercase hexadecimal session id"
        }
        val bytes = ByteArray(SESSION_ID_BYTES)
        for (index in 0 until SESSION_ID_BYTES) {
            val high = Character.digit(sessionId.value[index * 2], 16)
            val low = Character.digit(sessionId.value[index * 2 + 1], 16)
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    /** The session id sixteen bytes at [offset] describe, or null if that is impossible. */
    fun sessionIdOrNull(bytes: ByteArray, offset: Int): SessionId? {
        if (offset < 0 || offset + SESSION_ID_BYTES > bytes.size) return null
        val characters = CharArray(SESSION_ID_BYTES * 2)
        for (index in 0 until SESSION_ID_BYTES) {
            val value = bytes[offset + index].toInt() and 0xFF
            characters[index * 2] = HEX_DIGITS[value ushr 4]
            characters[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return SessionId.orNull(String(characters))
    }
}
