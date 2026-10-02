package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.TransferId

/*
 * The order in which a received chunk becomes an acknowledged byte.
 *
 *   validate  →  frontier check  →  write  →  flush  →  atomic persist  →  ack
 *
 * Every arrow is load-bearing and the sequence is not a preference. Doing the
 * persist before the flush records bytes that were never committed, so a process
 * death leaves a frontier pointing past the data. Releasing the acknowledgment
 * before the persist tells the sender "received" and then forgets it, so the
 * sender stops resending bytes nobody has. Writing before the frontier check
 * appends a duplicate to a file that already contains those bytes.
 *
 * The consequence of getting this wrong is not a corrupted file — verification
 * would catch that. It is a *silently short* one that everybody believes is
 * complete, which is the specific failure this group exists to make impossible.
 *
 * The coordinator never touches Android and never touches a database directly.
 * It is handed two seams and it calls them in this order.
 */

/** What one write wants. */
public data class ChunkWrite(
    public val transferId: TransferId,
    /** Absolute offset the bytes belong at. */
    public val writeOffset: Long,
    public val buffer: ByteArray,
    public val dataOffset: Int,
    public val length: Int,
    /** Total size of the file, used only for bounds validation. */
    public val totalBytes: Long,
)

/** What came of it. */
public sealed interface ChunkOutcome {

    /**
     * The bytes are durable enough to acknowledge, and the frontier has moved.
     *
     * [durability] is carried through so the caller can record honestly what the
     * acknowledgement rests on.
     */
    public data class Acknowledged(
        public val offset: Long,
        public val durability: FlushDurability,
    ) : ChunkOutcome

    /** The write was refused before anything was touched. */
    public data class Rejected(
        public val reason: RejectionReason,
        public val frontier: Long,
    ) : ChunkOutcome

    /** Something failed. No acknowledgement may be released. */
    public data class Failed(
        public val error: TransferStorageError,
        /** Frontier as it stands after the failure: unchanged. */
        public val frontier: Long,
        /** How far the sequence got before it failed. */
        public val failedAt: CheckpointStep,
    ) : ChunkOutcome
}

/**
 * The steps, in order.
 *
 * Exposed so a failure can say where it stopped and so a test can assert the
 * order without inventing its own vocabulary.
 */
public enum class CheckpointStep(public val id: String) {
    VALIDATE("validate"),
    FRONTIER("frontier"),
    WRITE("write"),
    FLUSH("flush"),
    PERSIST("persist"),
    ;

    public companion object {
        /** The required order, as data rather than as prose. */
        public val ORDER: List<CheckpointStep> = entries.toList()
    }
}

/** Why a write was refused before any state changed. */
public enum class RejectionReason(public val id: String) {
    /** The chunk would land ahead of the frontier, leaving a hole. */
    GAP("gap"),

    /** The chunk is behind the frontier: already acknowledged, do not re-apply. */
    STALE("stale"),

    /** Zero bytes. */
    EMPTY("empty"),

    /** The bytes do not fit inside the buffer. */
    BUFFER_BOUNDS("buffer_bounds"),

    /** The bytes would run past the end of the file. */
    PAST_END("past_end"),

    /** Bigger than one frame can carry. */
    TOO_LARGE("too_large"),

    /** Negative numbers. */
    NEGATIVE("negative"),
    ;

    public companion object {
        public fun fromId(id: String?): RejectionReason =
            entries.firstOrNull { it.id == id } ?: NEGATIVE
    }
}

/** Observes every step in order. Null by default; tests supply a recorder. */
public fun interface CheckpointListener {
    public fun onStep(step: CheckpointStep, transferId: TransferId, offset: Long)
}

public class CheckpointCoordinator(
    private val sink: CheckpointSink,
    private val listener: CheckpointListener? = null,
) {

    /**
     * Applies one write and, only if every step succeeded, returns an
     * acknowledgement.
     *
     * The method is safe to call again after a [ChunkOutcome.Failed]: the
     * frontier has not moved, so the retry starts from the same place. Bytes that
     * were written before a flush failure are left in place rather than rolled
     * back — they are unacknowledged, and `PartialReconciliation` discards them
     * on the next recovery as an ordinary LONGER case.
     */
    public fun onChunk(
        partial: PartialSink,
        write: ChunkWrite,
    ): ChunkOutcome {
        // 1. validate ------------------------------------------------------
        val frontier = sink.confirmedFrontier(write.transferId)
        val rejection = validate(write)
        if (rejection != null) {
            return ChunkOutcome.Rejected(rejection, frontier)
        }
        step(CheckpointStep.VALIDATE, write.transferId, write.writeOffset)

        // 2. frontier check -------------------------------------------------
        if (write.writeOffset != frontier) {
            val reason = if (write.writeOffset < frontier) {
                RejectionReason.STALE
            } else {
                RejectionReason.GAP
            }
            return ChunkOutcome.Rejected(reason, frontier)
        }
        step(CheckpointStep.FRONTIER, write.transferId, frontier)

        // 3. write ----------------------------------------------------------
        when (val written = partial.writeAt(
            offset = write.writeOffset,
            buffer = write.buffer,
            dataOffset = write.dataOffset,
            length = write.length,
        )) {
            is WriteOutcome.Failed ->
                return ChunkOutcome.Failed(written.error, frontier, CheckpointStep.WRITE)

            is WriteOutcome.Written -> {
                if (written.bytes != write.length) {
                    // A short write is a failure, not progress: accepting it
                    // would leave a hole that no frontier could describe.
                    return ChunkOutcome.Failed(
                        TransferStorageError.Io(
                            operation = "write",
                            diagnostic = "wrote ${written.bytes} of ${write.length} bytes",
                        ),
                        frontier,
                        CheckpointStep.WRITE,
                    )
                }
            }
        }
        step(CheckpointStep.WRITE, write.transferId, write.writeOffset)

        // 4. flush ----------------------------------------------------------
        val durability = partial.flush()
        if (durability is FlushDurability.FlushFailed) {
            // The bytes may or may not be on the disk. Either way the frontier
            // stays where it was and no acknowledgement leaves this method.
            return ChunkOutcome.Failed(durability.error, frontier, CheckpointStep.FLUSH)
        }
        step(CheckpointStep.FLUSH, write.transferId, write.writeOffset)

        // 5. atomic persist --------------------------------------------------
        val newOffset = write.writeOffset + write.length.toLong()
        if (newOffset > write.totalBytes) {
            return ChunkOutcome.Failed(
                TransferStorageError.StateConflict(
                    reason = "chunk_past_total_bytes",
                    diagnostic = "offset $newOffset exceeds total ${write.totalBytes}",
                ),
                frontier,
                CheckpointStep.PERSIST,
            )
        }
        when (val persisted = sink.persist(write.transferId, newOffset, durability)) {
            is PersistOutcome.Failed ->
                return ChunkOutcome.Failed(persisted.error, frontier, CheckpointStep.PERSIST)

            PersistOutcome.Persisted -> Unit
        }
        step(CheckpointStep.PERSIST, write.transferId, newOffset)

        // 6. acknowledge ------------------------------------------------------
        return ChunkOutcome.Acknowledged(newOffset, durability)
    }

    private fun validate(write: ChunkWrite): RejectionReason? {
        if (write.length <= 0) return RejectionReason.EMPTY
        if (write.writeOffset < 0L || write.dataOffset < 0) return RejectionReason.NEGATIVE
        if (write.length > ProtocolLimits.MAX_PAYLOAD_BYTES) return RejectionReason.TOO_LARGE
        if (write.dataOffset > write.buffer.size - write.length) {
            return RejectionReason.BUFFER_BOUNDS
        }
        // Rearranged to avoid overflow: this is the same test the protocol uses
        // for frame bounds.
        if (write.writeOffset > write.totalBytes - write.length.toLong()) {
            return RejectionReason.PAST_END
        }
        return null
    }

    private fun step(step: CheckpointStep, transferId: TransferId, offset: Long) {
        listener?.onStep(step, transferId, offset)
    }
}
