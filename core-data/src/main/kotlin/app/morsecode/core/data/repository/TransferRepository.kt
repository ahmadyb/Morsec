package app.morsecode.core.data.repository

import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.toDomain
import app.morsecode.core.data.db.toEntity
import app.morsecode.core.model.HistoryEntry
import app.morsecode.core.model.Peer
import app.morsecode.core.model.RecentDevice
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.SessionPhase
import app.morsecode.core.model.SessionSummary
import app.morsecode.core.model.TransferItem
import app.morsecode.core.model.TransferSession
import app.morsecode.core.model.TransferState
import app.morsecode.core.model.canTransitionTo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence port for the transfer engine and the transfer screens.
 *
 * Rules enforced here rather than in callers:
 *  - state changes go through the model's transition table, so an illegal jump
 *    is refused instead of corrupting a resumable offset;
 *  - confirmed bytes never move backwards;
 *  - a terminal item produces exactly one row in the durable record;
 *  - "Clear completed" only ever touches COMPLETED rows of one section.
 */
public interface TransferRepository {

    public fun observeOpenSessions(): Flow<List<TransferSession>>

    public fun observeSession(sessionId: String): Flow<TransferSession?>

    public fun observeBroadcast(broadcastId: String): Flow<List<TransferSession>>

    public fun observeItems(sessionId: String): Flow<List<TransferItem>>

    public fun observeItems(sessionId: String, direction: SessionDirection): Flow<List<TransferItem>>

    public fun observeBatch(batchId: String): Flow<List<TransferItem>>

    /** Aggregate counts and byte totals derived from real rows. */
    public fun observeSummary(sessionId: String): Flow<SessionSummary>

    public suspend fun session(sessionId: String): TransferSession?

    public suspend fun putSession(session: TransferSession)

    public suspend fun setPhase(sessionId: String, phase: SessionPhase)

    public suspend fun setPauseAll(sessionId: String, pauseAll: Boolean)

    public suspend fun closeSession(sessionId: String, phase: SessionPhase, failureReason: String?)

    /**
     * Adds files to a session's queue, assigning queue positions after whatever
     * is already queued so an added batch never jumps ahead of in-flight work.
     *
     * @param sourceUris per-transfer content uri to read from; required outbound.
     * @param targetDirectory folder inbound files are committed into.
     */
    public suspend fun enqueue(
        items: List<TransferItem>,
        sourceUris: Map<String, String> = emptyMap(),
        targetDirectory: String? = null,
    )

    public suspend fun item(transferId: String): TransferItem?

    public suspend fun itemsForSession(sessionId: String): List<TransferItem>

    public suspend fun nextQueued(sessionId: String): TransferItem?

    /**
     * Applies a state change when the model's transition table allows it.
     *
     * @return the resulting state, or null when the transition was refused.
     */
    public suspend fun transition(transferId: String, next: TransferState): TransferState?

    public suspend fun updateProgress(
        transferId: String,
        confirmedBytes: Long,
        bytesPerSecond: Long,
        state: TransferState,
    )

    public suspend fun setChecksum(transferId: String, sha256Hex: String)

    /** Marks an item completed, records it durably and returns that record. */
    public suspend fun complete(
        transferId: String,
        sha256Hex: String?,
        resultUriString: String?,
    ): HistoryEntry?

    /**
     * Marks an item failed. A retryable failure stays in the session list; a
     * final failure also produces a durable record.
     */
    public suspend fun fail(transferId: String, state: TransferState, reason: String?): HistoryEntry?

    /**
     * Per-file retry: only this item re-enters the queue, at its confirmed
     * offset, and no other item in the session is touched.
     */
    public suspend fun retry(transferId: String): Boolean

    public suspend fun cancel(transferId: String): Boolean

    /** Removes COMPLETED rows of one section; returns how many were removed. */
    public suspend fun clearCompleted(sessionId: String, direction: SessionDirection?): Int

    public suspend fun deleteItem(transferId: String)

    public suspend fun pruneClosedSessions(olderThanEpochMillis: Long)
}

@Singleton
internal class RoomTransferRepository @Inject constructor(
    private val database: MorseDatabase,
) : TransferRepository {

    private val sessionDao get() = database.transferSessionDao()
    private val itemDao get() = database.transferItemDao()
    private val historyDao get() = database.historyDao()
    private val deviceDao get() = database.recentDeviceDao()

    override fun observeOpenSessions(): Flow<List<TransferSession>> =
        sessionDao.observeOpen().map { rows -> rows.map { it.toDomain() } }

    override fun observeSession(sessionId: String): Flow<TransferSession?> =
        sessionDao.observeSession(sessionId).map { row -> row?.toDomain() }

    override fun observeBroadcast(broadcastId: String): Flow<List<TransferSession>> =
        sessionDao.observeBroadcast(broadcastId).map { rows -> rows.map { it.toDomain() } }

    override fun observeItems(sessionId: String): Flow<List<TransferItem>> =
        itemDao.observeForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

    override fun observeItems(sessionId: String, direction: SessionDirection): Flow<List<TransferItem>> =
        itemDao.observeForSession(sessionId, direction.id).map { rows -> rows.map { it.toDomain() } }

    override fun observeBatch(batchId: String): Flow<List<TransferItem>> =
        itemDao.observeForBatch(batchId).map { rows -> rows.map { it.toDomain() } }

    override fun observeSummary(sessionId: String): Flow<SessionSummary> =
        itemDao.observeForSession(sessionId).map { rows -> SessionSummary.of(rows.map { it.toDomain() }) }

    override suspend fun session(sessionId: String): TransferSession? =
        sessionDao.find(sessionId)?.toDomain()

    override suspend fun putSession(session: TransferSession) {
        sessionDao.upsert(session.toEntity())
    }

    override suspend fun setPhase(sessionId: String, phase: SessionPhase) {
        sessionDao.setPhase(sessionId, phase.id)
    }

    override suspend fun setPauseAll(sessionId: String, pauseAll: Boolean) {
        sessionDao.setPauseAll(sessionId, pauseAll)
    }

    override suspend fun closeSession(sessionId: String, phase: SessionPhase, failureReason: String?) {
        sessionDao.close(sessionId, phase.id, System.currentTimeMillis(), failureReason)
    }

    override suspend fun enqueue(
        items: List<TransferItem>,
        sourceUris: Map<String, String>,
        targetDirectory: String?,
    ) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        items.groupBy { it.sessionId }.forEach { (sessionId, group) ->
            var position = itemDao.nextQueuePosition(sessionId)
            val rows = group.map { item ->
                item.toEntity(
                    sourceUriString = sourceUris[item.transferId],
                    targetDirectory = targetDirectory,
                    queuePosition = position++,
                ).copy(
                    queuedEpochMillis = if (item.queuedEpochMillis > 0L) item.queuedEpochMillis else now,
                )
            }
            itemDao.insertAll(rows)
        }
    }

    override suspend fun item(transferId: String): TransferItem? = itemDao.find(transferId)?.toDomain()

    override suspend fun itemsForSession(sessionId: String): List<TransferItem> =
        itemDao.itemsForSession(sessionId).map { it.toDomain() }

    override suspend fun nextQueued(sessionId: String): TransferItem? =
        itemDao.nextQueued(sessionId)?.toDomain()

    override suspend fun transition(transferId: String, next: TransferState): TransferState? {
        val row = itemDao.find(transferId) ?: return null
        val from = TransferState.fromId(row.state)
        if (!from.canTransitionTo(next)) return null
        itemDao.setState(transferId, next.id)
        return next
    }

    override suspend fun updateProgress(
        transferId: String,
        confirmedBytes: Long,
        bytesPerSecond: Long,
        state: TransferState,
    ) {
        val row = itemDao.find(transferId) ?: return
        val current = TransferState.fromId(row.state)
        // Never move confirmed bytes backwards: a stale sample from a retried
        // attempt must not shrink the offset a resume would start from.
        val confirmed = maxOf(confirmedBytes, row.confirmedBytes)
        val applied = when {
            current == state -> state
            current.canTransitionTo(state) -> state
            else -> current
        }
        itemDao.updateProgress(transferId, confirmed, bytesPerSecond.coerceAtLeast(0L), applied.id)
    }

    override suspend fun setChecksum(transferId: String, sha256Hex: String) {
        itemDao.setChecksum(transferId, sha256Hex)
    }

    override suspend fun complete(
        transferId: String,
        sha256Hex: String?,
        resultUriString: String?,
    ): HistoryEntry? {
        itemDao.complete(transferId, sha256Hex, resultUriString, System.currentTimeMillis())
        return recordTerminalItem(transferId)
    }

    override suspend fun fail(
        transferId: String,
        state: TransferState,
        reason: String?,
    ): HistoryEntry? {
        val terminal = when (state) {
            TransferState.FAILED_RETRYABLE, TransferState.FAILED_FINAL -> state
            else -> TransferState.FAILED_RETRYABLE
        }
        itemDao.fail(transferId, terminal.id, reason, System.currentTimeMillis())
        return if (terminal == TransferState.FAILED_FINAL) recordTerminalItem(transferId) else null
    }

    override suspend fun retry(transferId: String): Boolean {
        val row = itemDao.find(transferId) ?: return false
        if (TransferState.fromId(row.state) != TransferState.FAILED_RETRYABLE) return false
        // Back to QUEUED with the confirmed offset preserved, throughput reset
        // and the retry count bumped so the UI can show "retry 2".
        itemDao.upsert(
            row.copy(
                state = TransferState.QUEUED.id,
                failureReason = null,
                bytesPerSecond = 0L,
                retryCount = row.retryCount + 1,
                finishedEpochMillis = 0L,
            ),
        )
        return true
    }

    override suspend fun cancel(transferId: String): Boolean {
        val row = itemDao.find(transferId) ?: return false
        val current = TransferState.fromId(row.state)
        if (current.isTerminal || !current.canTransitionTo(TransferState.CANCELLED)) return false
        itemDao.fail(transferId, TransferState.CANCELLED.id, null, System.currentTimeMillis())
        recordTerminalItem(transferId)
        return true
    }

    override suspend fun clearCompleted(sessionId: String, direction: SessionDirection?): Int =
        if (direction == null) {
            itemDao.clearCompleted(sessionId)
        } else {
            itemDao.clearCompleted(sessionId, direction.id)
        }

    override suspend fun deleteItem(transferId: String) {
        itemDao.delete(transferId)
    }

    override suspend fun pruneClosedSessions(olderThanEpochMillis: Long) {
        sessionDao.deleteClosedBefore(olderThanEpochMillis)
    }

    /**
     * Writes the durable record for a finished item and remembers the peer.
     *
     * The record id is derived from the transfer id, direction and peer, so
     * calling this twice for the same item overwrites instead of duplicating.
     */
    private suspend fun recordTerminalItem(transferId: String): HistoryEntry? {
        val row = itemDao.find(transferId) ?: return null
        val session = sessionDao.find(row.sessionId)?.toDomain()
        val item = row.toDomain()
        val entry = HistoryEntry.of(
            item = item,
            peer = session?.peer,
            sessionId = row.sessionId,
            broadcastId = session?.broadcastId,
        )
        historyDao.insert(entry.toEntity())
        session?.peer?.let { peer -> rememberDevice(peer, item.displayName) }
        return entry
    }

    private suspend fun rememberDevice(peer: Peer, summary: String) {
        val existing = deviceDao.find(peer.peerId)
        val device = RecentDevice.of(
            peer = peer.copy(lastSeenEpochMillis = System.currentTimeMillis()),
            summary = summary,
            previousCount = existing?.interactionCount ?: 0,
        )
        deviceDao.upsert(device.toEntity())
    }
}
