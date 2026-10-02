package app.morsecode.core.transfer

/*
 * Every bound the transfer core enforces lives here so the codec, the reducer
 * and the tests agree on one set of numbers.
 *
 * The values are deliberately small enough for the oldest supported device
 * (minSdk 23, see doc/decisions/ADR-0001-toolchain.md): the largest allocation
 * the codec will ever make for a single frame is MAX_FRAME_SIZE_BYTES, and it
 * is only made *after* the declared lengths have been validated against these
 * constants. Nothing here is a performance target; each constant is an
 * allocation safety limit.
 */

public object ProtocolLimits {
    /** Frame magic 'MSC1' — Morsecode Stream, revision 1 of the container. */
    public const val MAGIC_BYTE_0: Int = 0x4D // 'M'
    public const val MAGIC_BYTE_1: Int = 0x53 // 'S'
    public const val MAGIC_BYTE_2: Int = 0x43 // 'C'
    public const val MAGIC_BYTE_3: Int = 0x31 // '1'

    /** Oldest protocol version this build can decode. */
    public const val PROTOCOL_VERSION_MIN: Int = 1

    /** Newest protocol version this build can produce. */
    public const val PROTOCOL_VERSION_MAX: Int = 1

    /** The version every frame this build emits carries. */
    public const val PROTOCOL_VERSION_CURRENT: Int = PROTOCOL_VERSION_MAX

    /** Fixed size of the binary frame header, in bytes. */
    public const val HEADER_SIZE_BYTES: Int = 44

    /** Maximum encoded length of any identifier field, in UTF-8 bytes. */
    public const val MAX_ID_LENGTH_BYTES: Int = 64

    /** Maximum encoded length of a display name, MIME type or error code. */
    public const val MAX_TEXT_LENGTH_BYTES: Int = 255

    /** Maximum encoded length of a safe relative transfer path. */
    public const val MAX_RELATIVE_PATH_BYTES: Int = 512

    /** One path segment (file or directory name) may not be longer than this. */
    public const val MAX_PATH_SEGMENT_BYTES: Int = 127

    /**
     * A relative transfer path may not be deeper than this many segments.
     *
     * This bound is about the *shape* of a path rather than its length: a path is
     * rebuilt one directory at a time on the receiving side, so an unbounded
     * depth is an unbounded number of directory creations, and a deeply nested
     * path is a way to hide a file where a user will not look for it.
     */
    public const val MAX_PATH_SEGMENTS: Int = 32

    /** Maximum encoded length of an error-detail string. */
    public const val MAX_ERROR_DETAIL_BYTES: Int = 256

    /** Maximum encoded length of the handshake capabilities string. */
    public const val MAX_HANDSHAKE_CAPABILITIES_BYTES: Int = 256

    /** A chunk smaller than this wastes more on framing than it carries. */
    public const val MIN_CHUNK_SIZE_BYTES: Int = 1_024

    /** Chunk size used when the peers do not negotiate one explicitly. */
    public const val DEFAULT_CHUNK_SIZE_BYTES: Int = 65_536

    /** Largest chunk either peer may propose; also the payload ceiling. */
    public const val MAX_CHUNK_SIZE_BYTES: Int = 262_144

    /** Largest payload a single frame may carry, in bytes. */
    public const val MAX_PAYLOAD_BYTES: Int = MAX_CHUNK_SIZE_BYTES

    /**
     * Largest frame this build will ever allocate: the fixed header plus the
     * three bounded identifier fields plus the bounded payload.
     */
    public const val MAX_FRAME_SIZE_BYTES: Int =
        HEADER_SIZE_BYTES + (3 * MAX_ID_LENGTH_BYTES) + MAX_PAYLOAD_BYTES

    /** Length of a SHA-256 digest in bytes. */
    public const val SHA256_DIGEST_BYTES: Int = 32

    /** Length of a SHA-256 digest as lowercase hexadecimal. */
    public const val SHA256_HEX_LENGTH: Int = SHA256_DIGEST_BYTES * 2

    /** Version stamp written into every persisted snapshot. */
    public const val SNAPSHOT_VERSION: Int = 1

    /** Oldest snapshot version this build can restore. */
    public const val SNAPSHOT_VERSION_MIN: Int = 1

    /** Default ceiling on automatic retries before a failure becomes final. */
    public const val DEFAULT_MAX_RETRY_COUNT: Int = 5

    /**
     * Largest file size the core will accept: 8 TiB - 1, as a safety rail.
     *
     * The value is deliberately *not* tied to the frame payload. Morsecode moves
     * phone videos, archives, backups and disk images, all of which routinely
     * exceed 4 GiB; an earlier 4 GiB - 1 ceiling was simply a 32-bit length
     * field leaking into a `Long`-sized domain. Every size and offset in the
     * core is a `Long`, and 8 TiB - 1 leaves more than three orders of magnitude
     * of headroom over anything a phone holds today while staying far enough
     * below `Long.MAX_VALUE` that no sum can overflow:
     *
     * ```
     *   MAX_FILE_SIZE_BYTES + MAX_CHUNK_SIZE_BYTES
     *     = 8_796_093_022_207 + 262_144
     *     = 8_796_093_284_351        // Long.MAX_VALUE is 9_223_372_036_854_775_807
     * ```
     *
     * Even so, no code path forms that sum speculatively: [checkedEnd] compares
     * against `MAX_FILE_SIZE_BYTES - length` instead of adding, so an untrusted
     * offset plus an untrusted length can never wrap into a small positive
     * number.
     */
    public const val MAX_FILE_SIZE_BYTES: Long = 8_796_093_022_207L // 8 TiB - 1

    /**
     * Adds an offset and a chunk length without ever forming an overflowing sum.
     *
     * The comparison is rearranged to `offset <= MAX_FILE_SIZE_BYTES - length`,
     * which cannot overflow because `length` is bounded and non-negative, and
     * neither can the addition that follows it. Returns the exclusive end of the
     * range, which is the only end any caller should compute.
     *
     * @throws IllegalArgumentException if either argument is negative, if
     *   [length] exceeds the chunk ceiling, or if the range would run past the
     *   largest file the core accepts.
     */
    public fun checkedEnd(offset: Long, length: Long): Long {
        require(isValidRange(offset, length)) { "range $offset..$length is not addressable" }
        return offset + length
    }

    /**
     * True when `offset` and `length` describe a range inside the largest file
     * the core accepts.
     *
     * The test is `offset <= MAX_FILE_SIZE_BYTES - length` rather than
     * `offset + length <= MAX_FILE_SIZE_BYTES`: the subtraction cannot overflow
     * because `length` is bounded and non-negative, so this answer is correct
     * even for a length and an offset that were both read off the wire. Callers
     * that must not throw use this first; [checkedEnd] is the throwing form for
     * callers where an unaddressable range is a programming error.
     */
    public fun isValidRange(offset: Long, length: Long): Boolean =
        offset >= 0L &&
            length >= 0L &&
            length <= MAX_CHUNK_SIZE_BYTES.toLong() &&
            offset <= MAX_FILE_SIZE_BYTES - length

    /**
     * Converts a length read off the wire into an [Int] allocation size.
     *
     * A hostile peer can put any 64-bit value in a length field, so every
     * conversion goes through here: an out-of-range value is rejected with a
     * typed error instead of being truncated into a negative array size.
     */
    public fun checkedToInt(value: Long, field: String): Int {
        if (value < 0L || value > Int.MAX_VALUE.toLong()) {
            throw IllegalArgumentException(
                "field $field length $value is outside the addressable range",
            )
        }
        return value.toInt()
    }

    /**
     * Same conversion, returning null instead of throwing so callers that must
     * produce a typed [app.morsecode.core.transfer.error.TransferError] can do so.
     */
    public fun checkedToIntOrNull(value: Long): Int? =
        if (value < 0L || value > Int.MAX_VALUE.toLong()) null else value.toInt()
}
