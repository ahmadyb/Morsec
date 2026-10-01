package app.morsecode.core.transfer.identity

import app.morsecode.core.transfer.ProtocolLimits

/*
 * Positions inside a file, and the chunk size that turns one into the other.
 *
 * File sizes and offsets are `Long` everywhere: a 3 GiB video does not fit in an
 * Int, and a hostile length field must not be able to wrap one. Converting a
 * `Long` length into an allocation size always goes through
 * [ProtocolLimits.checkedToInt].
 */

/**
 * Ordinal of a chunk inside one transfer, starting at 0.
 *
 * The sequence is derived from the offset (`offset / chunkSize`) rather than
 * counted independently, so a peer cannot desynchronise the two and the receiver
 * can reject a chunk whose sequence and offset disagree.
 */
public data class SequenceNumber(public val value: Long) {
    init {
        require(value >= 0L) { "sequence number must not be negative, was $value" }
        require(value <= MAX_VALUE) { "sequence number $value exceeds the maximum $MAX_VALUE" }
    }

    override fun toString(): String = value.toString()

    public companion object {
        /** Ceiling derived from the largest file over the smallest chunk. */
        public const val MAX_VALUE: Long = ProtocolLimits.MAX_FILE_SIZE_BYTES /
            ProtocolLimits.MIN_CHUNK_SIZE_BYTES

        public val ZERO: SequenceNumber = SequenceNumber(0L)

        public fun isValid(value: Long): Boolean = value >= 0L && value <= MAX_VALUE
        public fun orNull(value: Long): SequenceNumber? =
            if (isValid(value)) SequenceNumber(value) else null

        /** The sequence number a chunk starting at [offset] must carry. */
        public fun forOffset(offset: Long, chunkSize: ChunkSize): SequenceNumber =
            SequenceNumber(offset / chunkSize.value.toLong())
    }
}

/**
 * A byte count the receiving side has acknowledged as valid.
 *
 * [ConfirmedOffset] is the only progress number resume is allowed to trust. The
 * sender's own write position is deliberately a different type
 * (see `OptimisticBytes` in the snapshot) so the two can never be mixed up.
 */
public data class ConfirmedOffset(public val value: Long) {
    init {
        require(value >= 0L) { "confirmed offset must not be negative, was $value" }
        require(value <= ProtocolLimits.MAX_FILE_SIZE_BYTES) {
            "confirmed offset $value exceeds the maximum file size ${ProtocolLimits.MAX_FILE_SIZE_BYTES}"
        }
    }

    override fun toString(): String = value.toString()

    public companion object {
        public val ZERO: ConfirmedOffset = ConfirmedOffset(0L)

        public fun isValid(value: Long): Boolean =
            value >= 0L && value <= ProtocolLimits.MAX_FILE_SIZE_BYTES

        public fun orNull(value: Long): ConfirmedOffset? =
            if (isValid(value)) ConfirmedOffset(value) else null
    }
}

/**
 * How many bytes one DATA_CHUNK frame carries.
 *
 * The bounds are allocation limits, not tuning knobs: [MIN][ProtocolLimits.MIN_CHUNK_SIZE_BYTES]
 * keeps framing overhead sane, [MAX][ProtocolLimits.MAX_CHUNK_SIZE_BYTES] keeps a
 * single allocation inside what an API 23 device can comfortably serve.
 */
public data class ChunkSize(public val value: Int) {
    init {
        require(value >= ProtocolLimits.MIN_CHUNK_SIZE_BYTES) {
            "chunk size $value is below the minimum ${ProtocolLimits.MIN_CHUNK_SIZE_BYTES}"
        }
        require(value <= ProtocolLimits.MAX_CHUNK_SIZE_BYTES) {
            "chunk size $value exceeds the maximum ${ProtocolLimits.MAX_CHUNK_SIZE_BYTES}"
        }
    }

    /** Number of chunks needed to move [totalBytes]; 0 when the file is empty. */
    public fun chunkCountFor(totalBytes: Long): Long {
        require(totalBytes >= 0L) { "total size must not be negative, was $totalBytes" }
        if (totalBytes == 0L) return 0L
        return ((totalBytes - 1L) / value.toLong()) + 1L
    }

    /** Length of the chunk starting at [offset], clamped to the file end. */
    public fun lengthOfChunkAt(offset: Long, totalBytes: Long): Int {
        require(offset >= 0L) { "offset must not be negative, was $offset" }
        require(totalBytes >= 0L) { "total size must not be negative, was $totalBytes" }
        if (offset >= totalBytes) return 0
        val remaining = totalBytes - offset
        return if (remaining >= value.toLong()) value else remaining.toInt()
    }

    override fun toString(): String = value.toString()

    public companion object {
        public val DEFAULT: ChunkSize = ChunkSize(ProtocolLimits.DEFAULT_CHUNK_SIZE_BYTES)
        public val MIN: ChunkSize = ChunkSize(ProtocolLimits.MIN_CHUNK_SIZE_BYTES)
        public val MAX: ChunkSize = ChunkSize(ProtocolLimits.MAX_CHUNK_SIZE_BYTES)

        public fun isValid(value: Int): Boolean =
            value >= ProtocolLimits.MIN_CHUNK_SIZE_BYTES &&
                value <= ProtocolLimits.MAX_CHUNK_SIZE_BYTES

        public fun orNull(value: Int): ChunkSize? = if (isValid(value)) ChunkSize(value) else null
    }
}
