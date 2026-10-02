package app.morsecode.core.storage.transfer

/*
 * What a destination can actually promise when you ask it to flush.
 *
 * The distinction this file exists to make is between a *declared capability*
 * and an *observed outcome*, and collapsing the two is how an acknowledgement
 * ends up claiming bytes no device has committed.
 *
 *   * On an app-private regular file, `FileChannel.force(true)` is a real fsync.
 *     When it returns without error, the bytes are on stable storage.
 *   * Through a MediaStore `OutputStream` or a document provider, the same call
 *     means whatever that provider decides it means. Some buffer. Some proxy to
 *     another process. Some ignore it entirely. A successful flush through a
 *     pipe is evidence that the provider accepted the bytes, not that any device
 *     has committed them.
 *
 * So the capability is declared once by the strategy, and every individual flush
 * reports what happened. The checkpoint policy reads the *outcome*, never the
 * declaration, and `FlushFailed` is the only outcome that suppresses both the
 * checkpoint and the acknowledgement (ADR-0003 §3).
 */

/** What a strategy can offer before anything has been attempted. */
public enum class FlushCapability(public val id: String) {
    /** A real fsync is available on this destination. */
    DURABLE("durable"),

    /** Something flush-shaped exists, and it proves nothing. */
    ATTEMPTED_GUARANTEE_UNKNOWN("attempted_guarantee_unknown"),

    /** There is no flush operation to call. */
    UNSUPPORTED("unsupported"),
    ;

    public companion object {
        public fun fromId(id: String?): FlushCapability =
            entries.firstOrNull { it.id == id } ?: UNSUPPORTED
    }
}

/** What one flush operation actually did. */
public sealed interface FlushDurability {
    /**
     * A real fsync completed. The bytes are on stable storage as far as the
     * platform can be asked to arrange that.
     */
    public data object DurableFlushSupported : FlushDurability

    /**
     * The strongest flush the destination offers was attempted and returned
     * without error. Not fsync-grade, and must never be described as though it
     * were: the row records `unknown` so a later reconciliation can interpret a
     * short file honestly instead of mysteriously.
     */
    public data object FlushAttemptedGuaranteeUnknown : FlushDurability

    /** The destination offers no flush; the write itself succeeded. */
    public data object FlushUnsupported : FlushDurability

    /** The flush was attempted and failed. Checkpoint and ack are both withheld. */
    public data class FlushFailed(public val error: TransferStorageError) : FlushDurability

    public companion object {
        /** Whether a checkpoint may advance after this outcome. */
        public fun allowsCheckpoint(outcome: FlushDurability): Boolean = outcome !is FlushFailed

        /** Whether an acknowledgement may be released after this outcome. */
        public fun allowsAcknowledgement(outcome: FlushDurability): Boolean =
            outcome !is FlushFailed

        /** The single-word row value persisted alongside the checkpoint. */
        public fun rowValue(outcome: FlushDurability): String = when (outcome) {
            DurableFlushSupported -> "durable"
            FlushAttemptedGuaranteeUnknown -> "unknown"
            FlushUnsupported -> "unsupported"
            is FlushFailed -> "failed"
        }
    }
}

/**
 * How a destination holds an incomplete file.
 *
 * The strategy is chosen per destination, not per transfer, and it is recorded
 * on the partial row so that restoration after a process death does not have to
 * guess which caveats apply.
 */
public enum class DestinationStrategy(public val id: String) {
    /**
     * MediaStore with `IS_PENDING` set while the file is incomplete.
     *
     * Preferred where it applies: the item is invisible to other apps and to the
     * user's gallery until the pending flag is cleared, which is the one
     * mechanism on this platform that genuinely hides an incomplete file.
     * Available from API 29; below that a pending column does not exist.
     */
    MEDIA_STORE_PENDING("media_store_pending"),

    /**
     * App-private staging followed by a verified bounded copy to the final SAF
     * document.
     *
     * The default for SAF. It costs a second copy and roughly double the free
     * space, and it buys the one thing SAF cannot: a partial that is never
     * visible as a plausible file in the user's chosen folder, and a flush that
     * is a real fsync for the entire transfer.
     */
    SAF_STAGED("saf_staged"),

    /**
     * App-private destination with no copy out. Used for cache and for the
     * temporary home of an inbound file before the user picks a final location.
     */
    APP_PRIVATE("app_private"),

    /**
     * Writing the partial directly into the user's chosen SAF document.
     *
     * Permitted only when the provider's behaviour and the user-visible
     * consequences have been explicitly accepted: SAF has no pending mechanism,
     * so an interrupted transfer leaves a truncated file with the final name in
     * the final folder, visible to every app the user has given that folder to.
     */
    SAF_DIRECT("saf_direct"),
    ;

    /** What this strategy can offer before anything has been attempted. */
    public val declaredFlushCapability: FlushCapability
        get() = when (this) {
            // Staging is app-private *during* the transfer, so the transfer
            // itself gets a real fsync even though the final copy does not.
            SAF_STAGED, APP_PRIVATE -> FlushCapability.DURABLE
            MEDIA_STORE_PENDING, SAF_DIRECT -> FlushCapability.ATTEMPTED_GUARANTEE_UNKNOWN
        }

    /** Whether the incomplete file is hidden from the user and from other apps. */
    public val hidesIncompleteFile: Boolean
        get() = when (this) {
            MEDIA_STORE_PENDING, SAF_STAGED, APP_PRIVATE -> true
            SAF_DIRECT -> false
        }

    /** Whether finishing requires copying the whole file a second time. */
    public val requiresFinalCopy: Boolean
        get() = this == SAF_STAGED

    public companion object {
        public fun fromId(id: String?): DestinationStrategy =
            entries.firstOrNull { it.id == id } ?: APP_PRIVATE
    }
}
