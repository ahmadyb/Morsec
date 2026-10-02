package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.TransferId

/*
 * The two seams the checkpoint coordinator talks to.
 *
 * Both are interfaces rather than concrete Android classes so that the ordering
 * rules — which are the part that must not be wrong — can be exercised on the
 * JVM with fakes that fail on demand. A test that has to make a real
 * ContentProvider return an error halfway through a flush is a test that will
 * not be written; one that can make a fake return `FlushFailed` is a test that
 * gets run.
 */

/** Outcome of a write to a partial. */
public sealed interface WriteOutcome {
    /** [bytes] were written; the partial is now [length] bytes long. */
    public data class Written(public val bytes: Int, public val length: Long) : WriteOutcome

    public data class Failed(public val error: TransferStorageError) : WriteOutcome
}

/** Outcome of truncating a partial back to a frontier. */
public sealed interface TruncateOutcome {
    public data class Truncated(public val length: Long) : TruncateOutcome
    public data class Failed(public val error: TransferStorageError) : TruncateOutcome
}

/**
 * The write side of an incomplete file.
 *
 * Offsets are absolute and `Long`: the sink is told where the bytes go rather
 * than maintaining a cursor, because after a resume the first write is not at
 * zero and a cursor that assumed otherwise would silently append to the wrong
 * place.
 */
public interface PartialSink {
    /** Current length in bytes. */
    public fun length(): Long

    /** Writes [length] bytes from [buffer] at absolute offset [offset]. */
    public fun writeAt(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): WriteOutcome

    /**
     * Attempts the strongest durability operation this sink offers and reports
     * what actually happened.
     */
    public fun flush(): FlushDurability

    /** Discards everything past [offset]. Used by the LONGER reconciliation. */
    public fun truncateTo(offset: Long): TruncateOutcome
}

/** Outcome of persisting a checkpoint. */
public sealed interface PersistOutcome {
    public data object Persisted : PersistOutcome
    public data class Failed(public val error: TransferStorageError) : PersistOutcome
}

/**
 * Where a confirmed frontier is read from and written to.
 *
 * The Room adapter implements this. `persist` must be atomic: a crash
 * immediately after it returns must find the new offset, and a crash immediately
 * before must find the old one. It must also be idempotent for a given offset,
 * because a retry after an ambiguous failure will call it again with the same
 * value.
 */
public interface CheckpointSink {
    /** The last confirmed frontier, or 0 when nothing is recorded. */
    public fun confirmedFrontier(transferId: TransferId): Long

    /**
     * Moves the frontier to [offset] and records the durability that produced it.
     *
     * Never moves backwards: a call with an offset below the stored one is
     * answered as persisted without changing anything.
     */
    public fun persist(
        transferId: TransferId,
        offset: Long,
        durability: FlushDurability,
    ): PersistOutcome
}
