package app.morsecode.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data access objects. Every read the UI needs is a [Flow], so screens observe
 * state instead of polling it, and every write is a `suspend` function called
 * from a coroutine.
 */

@Dao
public interface TransferSessionDao {

    @Query(
        """
        SELECT * FROM transfer_sessions
        WHERE phase NOT IN ('ended', 'failed')
        ORDER BY started_at DESC
        """,
    )
    public fun observeOpen(): Flow<List<TransferSessionEntity>>

    @Query("SELECT * FROM transfer_sessions ORDER BY started_at DESC LIMIT :limit")
    public fun observeRecent(limit: Int): Flow<List<TransferSessionEntity>>

    @Query("SELECT * FROM transfer_sessions WHERE sessionId = :sessionId")
    public fun observeSession(sessionId: String): Flow<TransferSessionEntity?>

    @Query("SELECT * FROM transfer_sessions WHERE broadcast_id = :broadcastId ORDER BY started_at ASC")
    public fun observeBroadcast(broadcastId: String): Flow<List<TransferSessionEntity>>

    @Query("SELECT * FROM transfer_sessions WHERE sessionId = :sessionId")
    public suspend fun find(sessionId: String): TransferSessionEntity?

    @Upsert
    public suspend fun upsert(session: TransferSessionEntity)

    @Upsert
    public suspend fun upsertAll(sessions: List<TransferSessionEntity>)

    @Query("UPDATE transfer_sessions SET phase = :phase WHERE sessionId = :sessionId")
    public suspend fun setPhase(sessionId: String, phase: String)

    @Query("UPDATE transfer_sessions SET pause_all = :pauseAll WHERE sessionId = :sessionId")
    public suspend fun setPauseAll(sessionId: String, pauseAll: Boolean)

    @Query(
        """
        UPDATE transfer_sessions
        SET phase = :phase, ended_at = :endedAt, failure_reason = :failureReason
        WHERE sessionId = :sessionId
        """,
    )
    public suspend fun close(sessionId: String, phase: String, endedAt: Long, failureReason: String?)

    @Query("DELETE FROM transfer_sessions WHERE sessionId = :sessionId")
    public suspend fun delete(sessionId: String)

    /**
     * Existing session-retention path. A session remains intact while any v2
     * snapshot or SAF checkpoint still owns recovery data; pruning must never
     * discard bytes needed to resume/reconcile it.
     */
    @Query(
        """
        DELETE FROM transfer_sessions
        WHERE phase IN ('ended', 'failed') AND ended_at < :olderThan
          AND NOT EXISTS (
              SELECT 1 FROM transfer_snapshots
              WHERE transfer_snapshots.session_id = transfer_sessions.sessionId
          )
          AND NOT EXISTS (
              SELECT 1 FROM transfer_partials
              WHERE transfer_partials.session_id = transfer_sessions.sessionId
          )
        """,
    )
    public suspend fun deleteClosedBefore(olderThan: Long)
}

@Dao
public interface TransferItemDao {

    @Query("SELECT * FROM transfer_items WHERE session_id = :sessionId ORDER BY queue_position ASC, queued_at ASC")
    public fun observeForSession(sessionId: String): Flow<List<TransferItemEntity>>

    @Query(
        """
        SELECT * FROM transfer_items WHERE session_id = :sessionId AND direction = :direction
        ORDER BY queue_position ASC, queued_at ASC
        """,
    )
    public fun observeForSession(sessionId: String, direction: String): Flow<List<TransferItemEntity>>

    @Query("SELECT * FROM transfer_items WHERE batch_id = :batchId ORDER BY queue_position ASC")
    public fun observeForBatch(batchId: String): Flow<List<TransferItemEntity>>

    @Query("SELECT * FROM transfer_items WHERE recipient_peer_id = :peerId ORDER BY queue_position ASC")
    public fun observeForRecipient(peerId: String): Flow<List<TransferItemEntity>>

    @Query("SELECT * FROM transfer_items WHERE state = 'queued' AND session_id = :sessionId ORDER BY queue_position ASC LIMIT 1")
    public suspend fun nextQueued(sessionId: String): TransferItemEntity?

    @Query("SELECT * FROM transfer_items WHERE transfer_id = :transferId")
    public suspend fun find(transferId: String): TransferItemEntity?

    @Upsert
    public suspend fun upsert(item: TransferItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(items: List<TransferItemEntity>)

    @Query("UPDATE transfer_items SET state = :state WHERE transfer_id = :transferId")
    public suspend fun setState(transferId: String, state: String)

    @Query("UPDATE transfer_items SET state = :state WHERE session_id = :sessionId AND state IN ('sending', 'receiving')")
    public suspend fun pauseActive(sessionId: String, state: String)

    @Query("UPDATE transfer_items SET state = :state WHERE session_id = :sessionId AND state IN ('paused_local')")
    public suspend fun resumeLocallyPaused(sessionId: String, state: String)

    /**
     * Progress write. Confirmed bytes and throughput are updated together so the
     * UI can never render a percentage ahead of the acknowledged offset. The
     * engine throttles how often it calls this (§13).
     */
    @Query(
        """
        UPDATE transfer_items
        SET confirmed_bytes = :confirmedBytes, bytes_per_second = :bytesPerSecond, state = :state
        WHERE transfer_id = :transferId
        """,
    )
    public suspend fun updateProgress(
        transferId: String,
        confirmedBytes: Long,
        bytesPerSecond: Long,
        state: String,
    )

    @Query("UPDATE transfer_items SET sha256_hex = :sha256 WHERE transfer_id = :transferId")
    public suspend fun setChecksum(transferId: String, sha256: String)

    @Query(
        """
        UPDATE transfer_items
        SET state = :state, failure_reason = :reason, bytes_per_second = 0, finished_at = :finishedAt
        WHERE transfer_id = :transferId
        """,
    )
    public suspend fun fail(transferId: String, state: String, reason: String?, finishedAt: Long)

    @Query(
        """
        UPDATE transfer_items
        SET state = 'completed', confirmed_bytes = total_bytes, bytes_per_second = 0,
            sha256_hex = :sha256, result_uri = :resultUri, finished_at = :finishedAt
        WHERE transfer_id = :transferId
        """,
    )
    public suspend fun complete(transferId: String, sha256: String?, resultUri: String?, finishedAt: Long)

    /** "Clear completed" removes only completed rows in one section. */
    @Query("DELETE FROM transfer_items WHERE session_id = :sessionId AND direction = :direction AND state = 'completed'")
    public suspend fun clearCompleted(sessionId: String, direction: String): Int

    @Query("DELETE FROM transfer_items WHERE session_id = :sessionId AND state = 'completed'")
    public suspend fun clearCompleted(sessionId: String): Int

    @Query("DELETE FROM transfer_items WHERE transfer_id = :transferId")
    public suspend fun delete(transferId: String)

    @Query("DELETE FROM transfer_items WHERE session_id = :sessionId")
    public suspend fun deleteForSession(sessionId: String)

    @Query("SELECT COUNT(*) FROM transfer_items WHERE session_id = :sessionId AND state NOT IN ('completed','cancelled','skipped','failed_final')")
    public suspend fun countUnfinished(sessionId: String): Int

    /** Next free position in the session queue; 0 for an empty queue. */
    @Query("SELECT COALESCE(MAX(queue_position), -1) + 1 FROM transfer_items WHERE session_id = :sessionId")
    public suspend fun nextQueuePosition(sessionId: String): Int

    @Query("SELECT * FROM transfer_items WHERE session_id = :sessionId ORDER BY queue_position ASC, queued_at ASC")
    public suspend fun itemsForSession(sessionId: String): List<TransferItemEntity>
}

@Dao
public interface HistoryDao {

    @Query("SELECT * FROM history_entries WHERE direction = :direction ORDER BY finished_at DESC")
    public fun observe(direction: String): Flow<List<HistoryEntryEntity>>

    @Query(
        """
        SELECT * FROM history_entries WHERE direction = :direction
        AND display_name LIKE '%' || :query || '%'
        ORDER BY finished_at DESC
        """,
    )
    public fun search(direction: String, query: String): Flow<List<HistoryEntryEntity>>

    @Query("SELECT * FROM history_entries ORDER BY finished_at DESC LIMIT :limit")
    public suspend fun recent(limit: Int): List<HistoryEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(entry: HistoryEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(entries: List<HistoryEntryEntity>)

    @Query("DELETE FROM history_entries WHERE history_id = :historyId")
    public suspend fun delete(historyId: String)

    @Query("DELETE FROM history_entries WHERE direction = :direction")
    public suspend fun clear(direction: String): Int

    @Query("DELETE FROM history_entries")
    public suspend fun clearAll(): Int

    @Query("SELECT COUNT(*) FROM history_entries WHERE direction = :direction")
    public fun countFor(direction: String): Flow<Int>
}

@Dao
public interface RecentDeviceDao {

    @Query("SELECT * FROM recent_devices ORDER BY last_seen DESC LIMIT :limit")
    public fun observe(limit: Int): Flow<List<RecentDeviceEntity>>

    @Query("SELECT * FROM recent_devices WHERE peer_id = :peerId")
    public suspend fun find(peerId: String): RecentDeviceEntity?

    @Upsert
    public suspend fun upsert(device: RecentDeviceEntity)

    @Query("DELETE FROM recent_devices")
    public suspend fun clear(): Int

    @Query("DELETE FROM recent_devices WHERE peer_id = :peerId")
    public suspend fun delete(peerId: String)
}

@Dao
public interface LogDao {

    @Query("SELECT * FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT :limit")
    public fun observe(limit: Int): Flow<List<LogEntryEntity>>

    @Query(
        """
        SELECT * FROM log_entries WHERE level IN ('WARN','ERROR')
        ORDER BY timestamp DESC, id DESC LIMIT :limit
        """,
    )
    public fun observeProblems(limit: Int): Flow<List<LogEntryEntity>>

    @Insert
    public suspend fun insert(entry: LogEntryEntity): Long

    @Query("SELECT COUNT(*) FROM log_entries")
    public suspend fun count(): Int

    @Query("SELECT * FROM log_entries ORDER BY timestamp ASC, id ASC")
    public suspend fun all(): List<LogEntryEntity>

    @Query("DELETE FROM log_entries")
    public suspend fun clear(): Int

    /** Keeps the newest [keep] rows so the log table cannot grow without bound. */
    @Query("DELETE FROM log_entries WHERE id NOT IN (SELECT id FROM log_entries ORDER BY timestamp DESC, id DESC LIMIT :keep)")
    public suspend fun trim(keep: Int)
}

@Dao
public interface CrashReportDao {

    @Query("SELECT * FROM crash_reports ORDER BY occurred_at DESC")
    public fun observe(): Flow<List<CrashReportEntity>>

    @Query("SELECT COUNT(*) FROM crash_reports")
    public fun observeCount(): Flow<Int>

    @Query("SELECT * FROM crash_reports WHERE id = :id")
    public suspend fun find(id: Long): CrashReportEntity?

    @Query("SELECT * FROM crash_reports ORDER BY occurred_at DESC")
    public suspend fun all(): List<CrashReportEntity>

    @Query("SELECT COUNT(*) FROM crash_reports")
    public suspend fun count(): Int

    @Insert
    public suspend fun insert(report: CrashReportEntity): Long

    @Query("DELETE FROM crash_reports WHERE id = :id")
    public suspend fun delete(id: Long)

    @Query("DELETE FROM crash_reports")
    public suspend fun clear(): Int

    @Query("DELETE FROM crash_reports WHERE id NOT IN (SELECT id FROM crash_reports ORDER BY occurred_at DESC LIMIT :keep)")
    public suspend fun trim(keep: Int)
}

@Dao
public interface BrowserSessionDao {

    @Query("SELECT * FROM browser_sessions ORDER BY requested_at DESC")
    public fun observe(): Flow<List<BrowserSessionEntity>>

    @Query("SELECT * FROM browser_sessions WHERE state = 'pending_approval' ORDER BY requested_at ASC")
    public fun observePending(): Flow<List<BrowserSessionEntity>>

    @Query("SELECT * FROM browser_sessions WHERE state = 'active'")
    public fun observeActive(): Flow<List<BrowserSessionEntity>>

    @Query("SELECT * FROM browser_sessions WHERE token_digest = :digest LIMIT 1")
    public suspend fun findByDigest(digest: String): BrowserSessionEntity?

    @Query("SELECT * FROM browser_sessions WHERE session_id = :sessionId LIMIT 1")
    public suspend fun findById(sessionId: String): BrowserSessionEntity?

    /** One-shot read for the idle expiry sweep; the UI observes [observeActive]. */
    @Query("SELECT * FROM browser_sessions WHERE state = 'active'")
    public suspend fun activeSnapshot(): List<BrowserSessionEntity>

    @Upsert
    public suspend fun upsert(session: BrowserSessionEntity)

    @Query("UPDATE browser_sessions SET state = :state WHERE session_id = :sessionId")
    public suspend fun setState(sessionId: String, state: String)

    @Query(
        """
        UPDATE browser_sessions
        SET state = :state, accepted_at = :acceptedAt
        WHERE session_id = :sessionId
        """,
    )
    public suspend fun accept(sessionId: String, state: String, acceptedAt: Long)

    @Query(
        """
        UPDATE browser_sessions
        SET state = :state, revoked_at = :revokedAt
        WHERE session_id = :sessionId
        """,
    )
    public suspend fun revoke(sessionId: String, state: String, revokedAt: Long)

    @Query(
        """
        UPDATE browser_sessions
        SET last_activity = :at, request_count = request_count + 1,
            bytes_downloaded = bytes_downloaded + :downloaded,
            bytes_uploaded = bytes_uploaded + :uploaded
        WHERE session_id = :sessionId
        """,
    )
    public suspend fun recordActivity(sessionId: String, at: Long, downloaded: Long, uploaded: Long)

    @Query("DELETE FROM browser_sessions WHERE session_id = :sessionId")
    public suspend fun delete(sessionId: String)

    @Query("DELETE FROM browser_sessions WHERE state IN ('revoked','ended') AND revoked_at < :olderThan")
    public suspend fun deleteEndedBefore(olderThan: Long)
}

@Dao
public interface SafGrantDao {

    @Query("SELECT * FROM saf_grants ORDER BY granted_at DESC")
    public fun observe(): Flow<List<SafGrantEntity>>

    /** Exact persisted row lookup; restoration never scans or substitutes another grant. */
    @Query("SELECT * FROM saf_grants WHERE id = :id LIMIT 1")
    public suspend fun find(id: Long): SafGrantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insert(grant: SafGrantEntity): Long

    @Query("DELETE FROM saf_grants WHERE id = :id")
    public suspend fun delete(id: Long)

    @Query("DELETE FROM saf_grants WHERE tree_uri = :treeUri")
    public suspend fun deleteByUri(treeUri: String)

    @Query("SELECT COUNT(*) FROM saf_grants")
    public suspend fun count(): Int
}

@Dao
public interface WebTransferDao {

    @Query("SELECT * FROM web_transfers ORDER BY updated_at DESC")
    public fun observe(): Flow<List<WebTransferEntity>>

    @Query("SELECT * FROM web_transfers WHERE upload_id = :uploadId")
    public suspend fun find(uploadId: String): WebTransferEntity?

    @Upsert
    public suspend fun upsert(transfer: WebTransferEntity)

    @Query(
        """
        UPDATE web_transfers
        SET received_bytes = :receivedBytes, state = :state, updated_at = :updatedAt
        WHERE upload_id = :uploadId
        """,
    )
    public suspend fun updateProgress(uploadId: String, receivedBytes: Long, state: String, updatedAt: Long)

    @Query("DELETE FROM web_transfers WHERE upload_id = :uploadId")
    public suspend fun delete(uploadId: String)

    @Query("DELETE FROM web_transfers WHERE state IN ('completed','cancelled','failed') AND updated_at < :olderThan")
    public suspend fun deleteFinishedBefore(olderThan: Long)
}
