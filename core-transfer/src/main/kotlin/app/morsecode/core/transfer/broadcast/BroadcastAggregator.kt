package app.morsecode.core.transfer.broadcast

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.model.TransferSnapshot

/*
 * Broadcast (1→N) aggregation.
 *
 * A broadcast is one *batch* of files delivered to N recipients, and the product
 * rule that must always hold is the one the UI shows: expected deliveries =
 * distinct files × distinct recipients. Distinctness is enforced here rather than
 * trusted, because the same file id arriving twice (a retried delivery, a
 * duplicated row) must not inflate a total, and a duplicated recipient must not
 * look like two receivers.
 *
 * Isolation is structural: every number below is computed per recipient from
 * that recipient's own snapshots. There is no shared counter a failing recipient
 * can poison, which is the property the tests assert.
 */

/** One recipient's progress through the batch. */
public data class RecipientProgress(
    public val recipientId: RecipientId,
    public val expectedDeliveries: Int,
    public val completedDeliveries: Int,
    public val failedDeliveries: Int,
    public val cancelledDeliveries: Int,
    public val skippedDeliveries: Int,
    public val pendingDeliveries: Int,
    public val confirmedBytes: Long,
    public val expectedBytes: Long,
) {
    /** Deliveries that have reached a state they will not leave. */
    public val settledDeliveries: Int
        get() = completedDeliveries + cancelledDeliveries + skippedDeliveries + failedDeliveries

    public val isComplete: Boolean get() = settledDeliveries == expectedDeliveries
    public val isFullyVerified: Boolean get() = completedDeliveries == expectedDeliveries
}

/** Overall outcome of a broadcast batch. */
public enum class BroadcastStatus(public val id: String) {
    /** Nothing to deliver. */
    EMPTY("empty"),

    /** At least one delivery is still moving. */
    IN_PROGRESS("in_progress"),

    /** Every expected delivery completed and verified. */
    COMPLETE_ALL_VERIFIED("complete_all_verified"),

    /** Every delivery settled, but at least one did not complete. */
    COMPLETE_PARTIAL("complete_partial"),
    ;

    public companion object {
        public fun fromId(id: String?): BroadcastStatus =
            entries.firstOrNull { it.id == id } ?: EMPTY
    }
}

/** The aggregate view of a broadcast batch. */
public data class BroadcastSummary(
    /** One entry per distinct recipient, sorted by id. */
    public val recipients: List<RecipientProgress>,
    /** The distinct files in the batch, sorted by id. */
    public val fileIds: List<FileId>,
    /** Distinct recipients × distinct files. */
    public val expectedDeliveries: Int,
    public val settledDeliveries: Int,
    public val completedDeliveries: Int,
    public val failedDeliveries: Int,
    public val cancelledDeliveries: Int,
    public val skippedDeliveries: Int,
    public val pendingDeliveries: Int,
    public val confirmedBytes: Long,
    /** Batch size × recipient count: what a perfect run would move. */
    public val expectedBytes: Long,
    public val status: BroadcastStatus,
) {
    /** True only when every expected delivery completed and was verified. */
    public val allVerified: Boolean get() = status == BroadcastStatus.COMPLETE_ALL_VERIFIED

    public companion object {
        public val EMPTY_SUMMARY: BroadcastSummary = BroadcastSummary(
            recipients = emptyList(),
            fileIds = emptyList(),
            expectedDeliveries = 0,
            settledDeliveries = 0,
            completedDeliveries = 0,
            failedDeliveries = 0,
            cancelledDeliveries = 0,
            skippedDeliveries = 0,
            pendingDeliveries = 0,
            confirmedBytes = 0L,
            expectedBytes = 0L,
            status = BroadcastStatus.EMPTY,
        )
    }
}

/** States that count as settled for aggregate purposes. */
private val SETTLED: Set<TransferState> = setOf(
    TransferState.COMPLETED,
    TransferState.CANCELLED,
    TransferState.SKIPPED,
    TransferState.FAILED_FINAL,
)

public object BroadcastAggregator {

    /** Summarises every delivery in a broadcast batch. */
    public fun summarize(snapshots: List<TransferSnapshot>): BroadcastSummary {
        // Distinctness first: a duplicate file id or recipient id must not count
        // twice, whichever row happened to be written last.
        val fileIds: List<FileId> = snapshots.map { it.fileId }.distinct().sortedBy { it.value }
        val batchBytes: Long = snapshots
            .associateBy { it.fileId }
            .values
            .sumOf { it.totalBytes }
        val recipientIds: List<RecipientId> = snapshots
            .mapNotNull { it.recipientId }
            .distinct()
            .sortedBy { it.value }

        if (recipientIds.isEmpty() || fileIds.isEmpty()) return BroadcastSummary.EMPTY_SUMMARY

        val expectedDeliveries = fileIds.size * recipientIds.size
        val perRecipient = recipientIds.map { recipient ->
            val own = snapshots.filter { it.recipientId == recipient }
            RecipientProgress(
                recipientId = recipient,
                // Every recipient is measured against the whole batch: the
                // product rule files × recipients is what the UI shows, and a
                // recipient missing a row simply never reaches `settled`.
                expectedDeliveries = fileIds.size,
                completedDeliveries = own.count { it.state == TransferState.COMPLETED },
                failedDeliveries = own.count { it.state == TransferState.FAILED_FINAL },
                cancelledDeliveries = own.count { it.state == TransferState.CANCELLED },
                skippedDeliveries = own.count { it.state == TransferState.SKIPPED },
                pendingDeliveries = own.count { it.state !in SETTLED },
                confirmedBytes = own.sumOf { it.confirmedBytes },
                expectedBytes = batchBytes,
            )
        }

        val completed = perRecipient.sumOf { it.completedDeliveries }
        val failed = perRecipient.sumOf { it.failedDeliveries }
        val cancelled = perRecipient.sumOf { it.cancelledDeliveries }
        val skipped = perRecipient.sumOf { it.skippedDeliveries }
        val settled = completed + failed + cancelled + skipped

        val status = when {
            settled >= expectedDeliveries && completed == expectedDeliveries ->
                BroadcastStatus.COMPLETE_ALL_VERIFIED

            settled >= expectedDeliveries -> BroadcastStatus.COMPLETE_PARTIAL
            else -> BroadcastStatus.IN_PROGRESS
        }

        return BroadcastSummary(
            recipients = perRecipient,
            fileIds = fileIds,
            expectedDeliveries = expectedDeliveries,
            settledDeliveries = settled,
            completedDeliveries = completed,
            failedDeliveries = failed,
            cancelledDeliveries = cancelled,
            skippedDeliveries = skipped,
            pendingDeliveries = perRecipient.sumOf { it.pendingDeliveries },
            confirmedBytes = perRecipient.sumOf { it.confirmedBytes },
            expectedBytes = batchBytes * recipientIds.size.toLong(),
            status = status,
        )
    }

    /** True when this delivery's own failure left another recipient unaffected. */
    public fun isIsolatedFrom(
        failing: TransferSnapshot,
        other: TransferSnapshot,
    ): Boolean = failing.recipientId != other.recipientId
}
