package app.morsecode.core.storage.transfer

/**
 * Process-local keyset cursor for the checkpoint table's deterministic commit-id ordering.
 * It is deliberately opaque in diagnostics and is never persisted as retry scheduling state.
 */
internal class SafCommitDiscoveryCursor internal constructor(
    internal val afterCommitId: String,
) {
    override fun toString(): String = "SafCommitDiscoveryCursor([redacted])"

    override fun equals(other: Any?): Boolean =
        other is SafCommitDiscoveryCursor && afterCommitId == other.afterCommitId

    override fun hashCode(): Int = afterCommitId.hashCode()
}

/** Transfer-engine state observed without exposing a Room entity to restoration logic. */
internal enum class SafCommitTransferActivity {
    NOT_RECORDED,
    ACTIVE_OR_RESUMABLE,
    QUIESCENT,
    MALFORMED,
    UNAVAILABLE,
}

/** One bounded discovery result. A rejected checkpoint remains isolated from its page neighbours. */
internal data class SafCommitDiscoveryCandidate(
    internal val commitId: PartialIdentity?,
    internal val checkpoint: SafCommitCheckpoint?,
    internal val revision: Long?,
    internal val error: TransferStorageError?,
    internal val transferActivity: SafCommitTransferActivity,
    internal val cursorAfter: SafCommitDiscoveryCursor,
) {
    init {
        require((checkpoint == null) == (error != null)) {
            "a discovery candidate must be either validated or rejected"
        }
        require(checkpoint == null || commitId == checkpoint.commitId) {
            "candidate key must match its checkpoint"
        }
        require(checkpoint == null || revision != null && revision > 0L) {
            "validated checkpoints require a positive journal revision"
        }
    }

    override fun toString(): String =
        "SafCommitDiscoveryCandidate(phase=${checkpoint?.phase?.id ?: "rejected"}, " +
            "activity=$transferActivity, error=${error?.category?.id ?: "none"}, [identity redacted])"
}

internal data class SafCommitDiscoveryPage(
    val candidates: List<SafCommitDiscoveryCandidate>,
    val hasMore: Boolean,
    val nextCursor: SafCommitDiscoveryCursor?,
) {
    init {
        require(candidates.isNotEmpty() || !hasMore) {
            "an empty page cannot advertise more rows"
        }
        require(candidates.isEmpty() || nextCursor == candidates.last().cursorAfter) {
            "the next cursor must follow the last returned candidate"
        }
    }
}

internal sealed interface SafCommitDiscoveryPageResult {
    data class Page(val value: SafCommitDiscoveryPage) : SafCommitDiscoveryPageResult
    data class Failed(val error: TransferStorageError) : SafCommitDiscoveryPageResult
}

internal sealed interface SafCommitDiscoveryReadResult {
    data object Missing : SafCommitDiscoveryReadResult
    data class Found(val candidate: SafCommitDiscoveryCandidate) : SafCommitDiscoveryReadResult
    data class Failed(val error: TransferStorageError) : SafCommitDiscoveryReadResult
}

/** Provider-free, bounded enumeration/read seam implemented by the Room adapter. */
internal interface SafCommitCheckpointDiscovery {
    fun restorationPage(
        after: SafCommitDiscoveryCursor?,
        limit: Int,
    ): SafCommitDiscoveryPageResult

    fun readForRestoration(commitId: PartialIdentity): SafCommitDiscoveryReadResult
}
