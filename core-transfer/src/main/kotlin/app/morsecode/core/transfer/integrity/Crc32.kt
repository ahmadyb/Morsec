package app.morsecode.core.transfer.integrity

/*
 * CRC32 for per-chunk integrity.
 *
 * Written out in full rather than delegating to `java.util.zip.CRC32` so the
 * algorithm is visible in review and so the exact same table and the exact same
 * byte-at-a-time path run on every JVM the module is ever compiled against. The
 * implementation is the standard reflected CRC-32 (polynomial 0xEDB88320, init
 * 0xFFFFFFFF, final xor 0xFFFFFFFF); `Crc32Test` pins it against
 * `java.util.zip.CRC32` and against the well-known check value for "123456789".
 *
 * CRC32 is a transport error check, not a security primitive: it is here to
 * catch a corrupted chunk cheaply so the chunk can be retransmitted. The
 * end-to-end integrity check is the full-file SHA-256.
 */

public object Crc32 {
    private const val POLYNOMIAL: Int = -306674912 // 0xEDB88320 as a signed Int
    private const val INIT: Int = -1 // 0xFFFFFFFF
    private const val FINAL_XOR: Int = -1 // 0xFFFFFFFF

    private val TABLE: IntArray = IntArray(256).also { table ->
        for (index in 0 until 256) {
            var value = index
            repeat(8) {
                value = if ((value and 1) != 0) {
                    (value ushr 1) xor POLYNOMIAL
                } else {
                    value ushr 1
                }
            }
            table[index] = value
        }
    }

    /** Feeds one byte into a running CRC; the canonical incremental step. */
    public fun update(crc: Int, byte: Int): Int =
        (crc ushr 8) xor TABLE[(crc xor byte) and 0xFF]

    /**
     * CRC32 of [length] bytes of [payload] starting at [offset].
     *
     * Returns the check value as a signed Int; use [asUnsigned] when it has to
     * be compared against an unsigned field or printed.
     */
    public fun compute(payload: ByteArray, offset: Int = 0, length: Int = payload.size): Int {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }
        require(offset + length <= payload.size) {
            "range $offset..${offset + length} is outside a payload of ${payload.size} bytes"
        }
        var crc = INIT
        var index = offset
        repeat(length) {
            crc = update(crc, payload[index].toInt())
            index++
        }
        return crc xor FINAL_XOR
    }

    /** The same value as a non-negative Long, for display and comparison. */
    public fun asUnsigned(crc: Int): Long = crc.toLong() and 0xFFFFFFFFL

    /** True when [payload] carries the CRC32 value [expected]. */
    public fun matches(payload: ByteArray, expected: Int, offset: Int = 0, length: Int = payload.size): Boolean =
        compute(payload, offset, length) == expected

    /** Lowercase eight-digit hexadecimal, zero padded. */
    public fun toHex(crc: Int): String {
        val value = asUnsigned(crc)
        return value.toString(16).padStart(8, '0')
    }
}
