package app.morsecode.core.storage.transfer

import app.morsecode.core.data.db.TransferPartialDao

/** A bounded, deterministic keyset page; [commitIds] may contain sensitive ids. */
public class SafCheckpointCandidatePage internal constructor(
    commitIds: List<String>,
    public val hasMore: Boolean,
) {
    public val commitIds: List<String> = commitIds.toList()

    override fun toString(): String =
        "SafCheckpointCandidatePage(count=${commitIds.size}, hasMore=$hasMore, [identities redacted])"
}

/**
 * Discovery contains no provider calls. Implementations return only stable
 * checkpoint identifiers, ordered by SQLite's binary collation.
 */
public fun interface SafCheckpointDiscovery {
    public suspend fun page(afterCommitId: String?, limit: Int): SafCheckpointCandidatePage
}

/** Room keyset adapter. Fetches exactly one sentinel beyond the requested page. */
internal class RoomSafCheckpointDiscovery(
    private val partialDao: TransferPartialDao,
) : SafCheckpointDiscovery {

    override suspend fun page(afterCommitId: String?, limit: Int): SafCheckpointCandidatePage {
        require(limit in 1..RestorationExecutionPolicy.MAX_DISCOVERY_PAGE_SIZE) {
            "restoration page limit is outside the supported bound"
        }
        val rows = partialDao.restorationCandidateCommitIds(
            afterCommitId = afterCommitId,
            limit = limit + 1,
        )
        return SafCheckpointCandidatePage(
            commitIds = rows.take(limit).toList(),
            hasMore = rows.size > limit,
        )
    }
}
