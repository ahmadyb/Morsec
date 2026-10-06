package app.morsecode.core.transfer.integrity

import app.morsecode.core.transfer.ProtocolLimits
import java.security.MessageDigest

/*
 * Full-file SHA-256 as an incremental, streaming abstraction.
 *
 * The contract that matters: nothing in here ever holds a whole file. A sender
 * feeds each chunk as it is written to the wire, a receiver feeds each chunk as
 * it is written to the partial file, and a verifier re-reads the partial file in
 * the same bounded chunks. Memory use is one digest context plus one chunk
 * buffer, whatever the file size.
 *
 * Resumable hashing: `MessageDigest` keeps internal state that this module will
 * not serialise — persisting it would be inventing an unsafe, non-portable
 * format. The documented recovery strategy is therefore to rebuild the digest
 * during verification by re-reading the confirmed partial file through the
 * storage adapter (`TransferEffect.RequestDestinationPartial` followed by
 * `TransferEffect.BeginVerification`), starting from offset zero up to the
 * confirmed offset. See doc/transfer-protocol.md, "SHA-256 completion flow".
 */

private val HEX = charArrayOf(
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
)

/**
 * A 32-byte SHA-256 digest.
 *
 * Value-equal by content (unlike a bare `ByteArray`) so it can be compared,
 * stored in a snapshot and used as a map key without surprises.
 */
public class Sha256Digest(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(value.size == ProtocolLimits.SHA256_DIGEST_BYTES) {
            "a SHA-256 digest must be ${ProtocolLimits.SHA256_DIGEST_BYTES} bytes, was ${value.size}"
        }
    }

    /** A defensive copy of the canonical 32-byte digest representation. */
    public val bytes: ByteArray get() = value.copyOf()

    private val lazyHex: String by lazy { toHexString(value) }

    /** Lowercase 64-character hexadecimal. */
    public val hex: String get() = lazyHex

    public fun contentEquals(other: Sha256Digest?): Boolean =
        other != null && value.contentEquals(other.value)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Sha256Digest && value.contentEquals(other.value))

    override fun hashCode(): Int = value.contentHashCode()

    /** Digest bytes are never included in default diagnostics or generated parent toStrings. */
    override fun toString(): String = "Sha256Digest([redacted])"

    public companion object {
        /** Non-throwing factory for untrusted 32-byte input. */
        public fun of(bytes: ByteArray): Sha256Digest? =
            if (bytes.size == ProtocolLimits.SHA256_DIGEST_BYTES) Sha256Digest(bytes.copyOf()) else null

        /**
         * Parses exactly 64 hexadecimal characters. Any other length — including
         * an odd one or one with a `0x` prefix — is rejected rather than padded.
         */
        public fun fromHex(hex: String): Sha256Digest? {
            if (hex.length != ProtocolLimits.SHA256_HEX_LENGTH) return null
            val out = ByteArray(ProtocolLimits.SHA256_DIGEST_BYTES)
            for (index in 0 until ProtocolLimits.SHA256_DIGEST_BYTES) {
                val high = hex[index * 2].digitToIntOrNull(16) ?: return null
                val low = hex[index * 2 + 1].digitToIntOrNull(16) ?: return null
                out[index] = ((high shl 4) or low).toByte()
            }
            return Sha256Digest(out)
        }

        private fun toHexString(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            var cursor = 0
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                out[cursor++] = HEX[value ushr 4]
                out[cursor++] = HEX[value and 0x0F]
            }
            return String(out)
        }
    }
}

/**
 * Incremental SHA-256 over a byte stream of any length.
 *
 * Not thread-safe: one accumulator per transfer, driven from one byte pump.
 */
public class Sha256Accumulator {
    private val digest: MessageDigest = MessageDigest.getInstance(ALGORITHM)

    /** How many bytes have been fed in; useful for progress assertions. */
    public var bytesConsumed: Long = 0L
        private set

    /** Feeds [length] bytes of [buffer] starting at [offset]. */
    public fun update(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Sha256Accumulator {
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(length >= 0) { "length must not be negative, was $length" }
        require(offset + length <= buffer.size) {
            "range $offset..${offset + length} is outside a buffer of ${buffer.size} bytes"
        }
        if (length == 0) return this
        digest.update(buffer, offset, length)
        bytesConsumed += length.toLong()
        return this
    }

    /** Feeds exactly one byte, for streaming adapters that read byte-wise. */
    public fun updateByte(byte: Int): Sha256Accumulator {
        digest.update((byte and 0xFF).toByte())
        bytesConsumed += 1L
        return this
    }

    /**
     * Finalises the digest.
     *
     * The accumulator stays usable: the digest is reset first, so a caller can
     * hash a second stream with the same object without allocating another
     * context.
     */
    public fun digest(): Sha256Digest {
        val value = Sha256Digest(digest.digest())
        digest.reset()
        bytesConsumed = 0L
        return value
    }

    /** Discards everything fed so far and starts a fresh digest. */
    public fun reset(): Sha256Accumulator {
        digest.reset()
        bytesConsumed = 0L
        return this
    }

    public companion object {
        public const val ALGORITHM: String = "SHA-256"

        /** SHA-256 of the empty input; the digest every zero-byte file produces. */
        public val EMPTY: Sha256Digest by lazy { Sha256Accumulator().digest() }
    }
}
