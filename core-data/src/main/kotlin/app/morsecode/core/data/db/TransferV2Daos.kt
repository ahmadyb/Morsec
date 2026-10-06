package app.morsecode.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
public interface TransferSnapshotDao {
    @Query("SELECT * FROM transfer_snapshots WHERE transfer_id = :transferId")
    public suspend fun find(transferId: String): TransferSnapshotEntity?

    @Query("SELECT * FROM transfer_snapshots WHERE session_id = :sessionId ORDER BY transfer_id")
    public suspend fun forSession(sessionId: String): List<TransferSnapshotEntity>

    @Upsert
    public suspend fun upsert(entity: TransferSnapshotEntity)

    @Query("DELETE FROM transfer_snapshots WHERE transfer_id = :transferId")
    public suspend fun delete(transferId: String): Int

    @Query("SELECT COUNT(*) FROM transfer_snapshots WHERE session_id = :sessionId AND snapshot_state NOT IN ('completed','cancelled','skipped','failed_final')")
    public suspend fun countNonTerminalForSession(sessionId: String): Int
}

@Dao
public interface TransferPartialDao {
    @Query("SELECT * FROM transfer_partials WHERE commit_id = :commitId")
    public suspend fun find(commitId: String): TransferPartialEntity?

    /**
     * Stable keyset page of nonterminal SAF checkpoints and any checkpoint with
     * cleanup counters/children indicating unsettled work, including terminal
     * parents that claim unresolved cleanup. A committed row that still claims
     * to own staging is also surfaced for journal validation. One sentinel row
     * is requested by the storage adapter to determine whether another page exists.
     */
    @Query(
        """
        SELECT candidate.commit_id
        FROM transfer_partials AS candidate
        WHERE (:afterCommitId IS NULL OR candidate.commit_id > :afterCommitId)
          AND (
            candidate.checkpoint_phase NOT IN ('committed', 'cancelled', 'cancelled_temporary_delete_observed')
            OR candidate.pending_cleanup_count > 0
            OR EXISTS (
              SELECT 1 FROM saf_pending_cleanup AS cleanup
              WHERE cleanup.commit_id = candidate.commit_id
            )
            OR (candidate.checkpoint_phase = 'committed' AND candidate.staging_released = 0)
          )
        ORDER BY candidate.commit_id COLLATE BINARY ASC
        LIMIT :limit
        """,
    )
    public suspend fun restorationCandidateCommitIds(
        afterCommitId: String?,
        limit: Int,
    ): List<String>

    @Query("SELECT * FROM transfer_partials WHERE session_id = :sessionId ORDER BY transfer_id")
    public suspend fun forSession(sessionId: String): List<TransferPartialEntity>

    @Query("SELECT * FROM transfer_partials WHERE transfer_id = :transferId ORDER BY commit_id")
    public suspend fun forTransfer(transferId: String): List<TransferPartialEntity>

    @Upsert
    public suspend fun upsert(entity: TransferPartialEntity)

    @Query("DELETE FROM transfer_partials WHERE commit_id = :commitId")
    public suspend fun delete(commitId: String): Int
}

@Dao
public interface SafRenameHistoryDao {
    @Query("SELECT * FROM saf_rename_history WHERE commit_id = :commitId ORDER BY sequence LIMIT 3")
    public suspend fun forCommit(commitId: String): List<SafRenameHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(entities: List<SafRenameHistoryEntity>)

    @Query("DELETE FROM saf_rename_history WHERE commit_id = :commitId")
    public suspend fun deleteForCommit(commitId: String): Int
}

@Dao
public interface SafPendingCleanupDao {
    @Query("SELECT * FROM saf_pending_cleanup WHERE commit_id = :commitId ORDER BY sequence LIMIT 4")
    public suspend fun forCommit(commitId: String): List<SafPendingCleanupEntity>

    @Query("SELECT COUNT(*) FROM saf_pending_cleanup WHERE commit_id = :commitId")
    public suspend fun countForCommit(commitId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    public suspend fun insertAll(entities: List<SafPendingCleanupEntity>)

    @Query("DELETE FROM saf_pending_cleanup WHERE commit_id = :commitId")
    public suspend fun deleteForCommit(commitId: String): Int
}
