package app.morsecode.core.storage.transfer

/*
 * Reconciling a persisted checkpoint against the partial that is actually there.
 *
 * After a process death these two numbers have been written by different
 * mechanisms at different times, and they are allowed to disagree. The persisted
 * offset was written by the database, under a transaction, after a flush. The
 * file length was whatever the filesystem happened to have when the power went.
 * Neither is authoritative on its own.
 *
 * The rule that makes this safe is that the confirmed frontier can only ever
 * move *backwards* during reconciliation. Bytes beyond it were never
 * acknowledged and cannot be trusted no matter how firmly they are on disk; bytes
 * short of it were acknowledged and are gone, which the sender has to be told
 * about by resuming from the shorter length rather than the stored number.
 *
 * All four shapes — equal, longer, shorter, missing — are named and handled,
 * because three of them are ordinary consequences of an interruption and only
 * pretending they are exceptional leads to code that truncates a good file or
 * resumes past a hole.
 */

/** What storage looked like when compared with the checkpoint. */
public enum class ReconciliationReason(public val id: String) {
    /** The file is exactly as long as the checkpoint claims. */
    EQUAL("equal"),

    /**
     * The file is longer: unacknowledged bytes were written and survived. Those
     * bytes are not evidence of progress — no acknowledgement authorised them.
     */
    LONGER("longer"),

    /**
     * The file is shorter than the checkpoint: a flush was acknowledged without
     * being durable, or the process died mid-write. The checkpoint over-claimed.
     */
    SHORTER("shorter"),

    /** There is no partial file at all. */
    MISSING("missing"),

    /** The numbers are not plausible for this transfer. */
    IMPLAUSIBLE("implausible"),
    ;

    public companion object {
        public fun fromId(id: String?): ReconciliationReason =
            entries.firstOrNull { it.id == id } ?: IMPLAUSIBLE
    }
}

/** What to do about it. */
public enum class ReconciliationAction(public val id: String) {
    /** Carry on from the persisted frontier; the file already agrees with it. */
    RESUME_AT_CHECKPOINT("resume_at_checkpoint"),

    /** Discard the unacknowledged tail, then carry on from the frontier. */
    TRUNCATE_TO_CHECKPOINT("truncate_to_checkpoint"),

    /** Accept that the checkpoint over-claimed and carry on from the real end. */
    RESUME_AT_ACTUAL_LENGTH("resume_at_actual_length"),

    /** No usable bytes survived; begin again at zero. */
    RESTART_FROM_ZERO("restart_from_zero"),

    /** Do not touch anything: the numbers cannot be true, so ask a human. */
    REFUSE("refuse"),
    ;

    public companion object {
        public fun fromId(id: String?): ReconciliationAction =
            entries.firstOrNull { it.id == id } ?: REFUSE
    }
}

/** The whole decision: why, what to do, from where, and any error to report. */
public data class ReconciliationDecision(
    public val reason: ReconciliationReason,
    public val action: ReconciliationAction,
    /** Offset the transfer should resume from. */
    public val resumeOffset: Long,
    /** Bytes to discard from the end of the partial, if any. */
    public val truncateTo: Long?,
    public val error: TransferStorageError? = null,
) {
    /** True when the caller may proceed without asking anyone. */
    public val isActionable: Boolean get() = action != ReconciliationAction.REFUSE

    /** True when the partial has to be modified before writing resumes. */
    public val requiresTruncation: Boolean get() = truncateTo != null
}

public object PartialReconciliation {

    /**
     * Compares [persistedOffset] against [actualLength] for a file of
     * [totalBytes].
     *
     * [actualLength] is null when the partial does not exist.
     */
    public fun reconcile(
        persistedOffset: Long,
        actualLength: Long?,
        totalBytes: Long,
    ): ReconciliationDecision {
        if (totalBytes < 0L) {
            return implausible("totalBytes is negative: $totalBytes")
        }
        if (persistedOffset < 0L) {
            return implausible("persistedOffset is negative: $persistedOffset")
        }
        if (persistedOffset > totalBytes) {
            return implausible(
                "persistedOffset $persistedOffset exceeds totalBytes $totalBytes",
            )
        }

        // No partial at all.
        if (actualLength == null) {
            return if (persistedOffset == 0L) {
                // Nothing had been confirmed, so nothing was lost: this is a
                // normal start, not a recovery.
                ReconciliationDecision(
                    reason = ReconciliationReason.MISSING,
                    action = ReconciliationAction.RESUME_AT_CHECKPOINT,
                    resumeOffset = 0L,
                    truncateTo = null,
                )
            } else {
                // Confirmed bytes were lost with the process. Starting over is
                // the only honest option.
                ReconciliationDecision(
                    reason = ReconciliationReason.MISSING,
                    action = ReconciliationAction.RESTART_FROM_ZERO,
                    resumeOffset = 0L,
                    truncateTo = null,
                )
            }
        }

        if (actualLength < 0L) {
            return implausible("actualLength is negative: $actualLength")
        }
        if (actualLength > totalBytes) {
            // More bytes on disk than the file can hold. This is not a stale
            // checkpoint, it is a different file or a corrupted row.
            return implausible(
                "actualLength $actualLength exceeds totalBytes $totalBytes",
            )
        }

        return when {
            actualLength == persistedOffset -> ReconciliationDecision(
                reason = ReconciliationReason.EQUAL,
                action = ReconciliationAction.RESUME_AT_CHECKPOINT,
                resumeOffset = persistedOffset,
                truncateTo = null,
            )

            actualLength > persistedOffset -> ReconciliationDecision(
                reason = ReconciliationReason.LONGER,
                action = ReconciliationAction.TRUNCATE_TO_CHECKPOINT,
                resumeOffset = persistedOffset,
                truncateTo = persistedOffset,
            )

            else -> ReconciliationDecision(
                reason = ReconciliationReason.SHORTER,
                action = ReconciliationAction.RESUME_AT_ACTUAL_LENGTH,
                resumeOffset = actualLength,
                truncateTo = null,
            )
        }
    }

    private fun implausible(detail: String): ReconciliationDecision =
        ReconciliationDecision(
            reason = ReconciliationReason.IMPLAUSIBLE,
            action = ReconciliationAction.REFUSE,
            resumeOffset = 0L,
            truncateTo = null,
            error = TransferStorageError.StateConflict(
                reason = "implausible_partial_length",
                diagnostic = detail,
            ),
        )
}
