package app.morsecode.core.storage.transfer

/*
 * Best-effort free-space preflight.
 *
 * WHY THIS IS A SEPARATE, TINY POLICY
 *   Asking a destination how much room it has is easy to get wrong in three specific ways: treating an
 *   unknown answer as zero, treating a positive answer as a promise, and adding an undocumented slack
 *   factor so that "it fits" quietly means "it fits if nothing else is happening". This file makes each
 *   of those a named decision instead of an implicit one.
 *
 * THE RULES
 *   1. A reliable available figure below the remaining bytes fails early with [TransferStorageError.InsufficientSpace].
 *   2. An unknown capacity is not an error: the probe returns null and the preflight accepts.
 *   3. A successful preflight is not a guarantee. The medium can fill between the query and the write,
 *      so a write-time `ENOSPC` must still map to [TransferStorageError.StorageFull] -- see
 *      `StorageFailureClassifier`, which does exactly that.
 *   4. An unknown-length source cannot require a complete-size preflight; its requirement is null.
 *   5. The only margin is the one the caller passes. The default is zero, and a non-zero value must be
 *      chosen by the caller and documented where it is chosen; there is no hidden constant here.
 *
 * WHAT THIS IS NOT
 *   It does not reserve space, does not query a provider, and does not know what a `StatFs` is. The
 *   Android-side probe lives with the storage adapters; this policy stays testable on the JVM.
 */

/** A destination that may be able to say how many bytes are available. */
public fun interface FreeSpaceProbe {
    /**
     * Usable bytes at the destination, or null when the destination cannot report a reliable figure.
     *
     * Implementations must return null rather than a guess. A provider that does not expose capacity,
     * or a query that failed, is unknown -- not zero.
     */
    public fun availableBytes(): Long?
}

/** What a preflight concluded. */
public sealed interface CapacityDecision {
    /** The write may proceed. [reason] records how strong that conclusion is. */
    public data class Accept(public val reason: CapacityAcceptance) : CapacityDecision

    /** The destination is known to be too small. */
    public data class Refuse(public val error: TransferStorageError) : CapacityDecision
}

/** How a preflight reached its answer, kept so a caller can tell certainty from its absence. */
public enum class CapacityAcceptance(public val id: String) {
    /** Both the requirement and the capacity were known and the requirement fits. */
    KNOWN_CAPACITY("known_capacity"),

    /** The destination did not report capacity. Not an error; not a guarantee. */
    UNKNOWN_CAPACITY("unknown_capacity"),

    /** The source length is unknown, so no complete-size check is possible. */
    UNKNOWN_REQUIREMENT("unknown_requirement"),
    ;

    public companion object {
        public fun fromId(id: String?): CapacityAcceptance =
            entries.firstOrNull { it.id == id } ?: UNKNOWN_CAPACITY
    }
}

public object DestinationCapacity {

    /**
     * The only margin this policy will use unless a caller asks for another one.
     *
     * Zero on purpose: a hidden reserve would make the reported requirement unfalsifiable, and a real
     * reserve belongs to the destination strategy that knows why it needs one.
     */
    public const val DEFAULT_SAFETY_MARGIN_BYTES: Long = 0L

    /**
     * Decides whether [remainingBytes] can plausibly fit.
     *
     * [remainingBytes] is null for an unknown-length source. [safetyMarginBytes] is added to the
     * requirement and saturates rather than overflowing.
     */
    public fun preflight(
        probe: FreeSpaceProbe?,
        remainingBytes: Long?,
        safetyMarginBytes: Long = DEFAULT_SAFETY_MARGIN_BYTES,
    ): CapacityDecision {
        require(safetyMarginBytes >= 0L) { "safetyMarginBytes must not be negative" }
        if (remainingBytes == null) {
            return CapacityDecision.Accept(CapacityAcceptance.UNKNOWN_REQUIREMENT)
        }
        require(remainingBytes >= 0L) { "remainingBytes must not be negative" }
        val required = saturatingAdd(remainingBytes, safetyMarginBytes)
        val available = probe?.availableBytes() ?: return CapacityDecision.Accept(
            CapacityAcceptance.UNKNOWN_CAPACITY,
        )
        if (available < 0L) {
            // A negative figure is not a measurement; treat it the way an absent one is treated.
            return CapacityDecision.Accept(CapacityAcceptance.UNKNOWN_CAPACITY)
        }
        if (available < required) {
            return CapacityDecision.Refuse(
                TransferStorageError.InsufficientSpace(
                    requiredBytes = required,
                    availableBytes = available,
                ),
            )
        }
        return CapacityDecision.Accept(CapacityAcceptance.KNOWN_CAPACITY)
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
