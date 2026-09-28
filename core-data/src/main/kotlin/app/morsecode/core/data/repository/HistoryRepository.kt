package app.morsecode.core.data.repository

import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.toDomain
import app.morsecode.core.model.HistoryEntry
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The History destination's data source (master prompt §5).
 *
 * Received and sent are the same table filtered by direction, which is why the
 * mockup's two tabs share one list component. Rows are only ever written by
 * [TransferRepository] when an item reaches a terminal state.
 */
public interface HistoryRepository {

    public fun observe(direction: SessionDirection): Flow<List<HistoryEntry>>

    /** Case-insensitive name filter used by the History search field. */
    public fun search(direction: SessionDirection, query: String): Flow<List<HistoryEntry>>

    public fun countFor(direction: SessionDirection): Flow<Int>

    public suspend fun recent(limit: Int): List<HistoryEntry>

    public suspend fun delete(historyId: String)

    /** "Clear history" on one tab only; returns the number of rows removed. */
    public suspend fun clear(direction: SessionDirection): Int

    public suspend fun clearAll(): Int
}

@Singleton
internal class RoomHistoryRepository @Inject constructor(
    private val database: MorseDatabase,
) : HistoryRepository {

    private val dao get() = database.historyDao()

    override fun observe(direction: SessionDirection): Flow<List<HistoryEntry>> =
        dao.observe(direction.id).map { rows -> rows.map { it.toDomain() } }

    override fun search(direction: SessionDirection, query: String): Flow<List<HistoryEntry>> {
        val trimmed = query.trim()
        val source = if (trimmed.isEmpty()) dao.observe(direction.id) else dao.search(direction.id, trimmed)
        return source.map { rows -> rows.map { it.toDomain() } }
    }

    override fun countFor(direction: SessionDirection): Flow<Int> = dao.countFor(direction.id)

    override suspend fun recent(limit: Int): List<HistoryEntry> =
        dao.recent(limit).map { it.toDomain() }

    override suspend fun delete(historyId: String) {
        dao.delete(historyId)
    }

    override suspend fun clear(direction: SessionDirection): Int = dao.clear(direction.id)

    override suspend fun clearAll(): Int = dao.clearAll()
}

/** True for the rows the History screen renders with the failure treatment. */
public val HistoryEntry.showsFailure: Boolean
    get() = state == TransferState.FAILED_FINAL ||
        state == TransferState.FAILED_RETRYABLE ||
        state == TransferState.CANCELLED ||
        state == TransferState.SKIPPED
