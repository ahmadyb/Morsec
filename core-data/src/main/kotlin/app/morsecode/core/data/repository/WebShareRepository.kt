package app.morsecode.core.data.repository

import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.toDomain
import app.morsecode.core.data.db.toEntity
import app.morsecode.core.model.BrowserSession
import app.morsecode.core.model.BrowserSessionState
import app.morsecode.core.model.WebTransferRecord
import app.morsecode.core.model.WebTransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the embedded WebShare server (master prompt §8, §12).
 *
 * The server itself is transport code and lives in :webshare-server; this
 * repository is what makes browser sessions and partial uploads survive a
 * process restart, and it is the only place a token digest is stored. Raw bearer
 * tokens are never written here.
 */
public interface WebShareRepository {

    public fun observeSessions(): Flow<List<BrowserSession>>

    /** Sessions waiting on the phone's Approve/Deny prompt. */
    public fun observePending(): Flow<List<BrowserSession>>

    public fun observeActive(): Flow<List<BrowserSession>>

    public suspend fun session(sessionId: String): BrowserSession?

    /** Resolves a request's bearer token to a session; null when unknown. */
    public suspend fun sessionForTokenDigest(digest: String): BrowserSession?

    public suspend fun put(session: BrowserSession)

    public suspend fun accept(sessionId: String)

    public suspend fun revoke(sessionId: String)

    public suspend fun end(sessionId: String)

    public suspend fun delete(sessionId: String)

    public suspend fun recordActivity(
        sessionId: String,
        bytesDownloaded: Long = 0L,
        bytesUploaded: Long = 0L,
    )

    /** Revokes sessions that have been idle longer than [maxIdleMillis]. */
    public suspend fun expireIdle(nowEpochMillis: Long, maxIdleMillis: Long): Int

    public suspend fun pruneEnded(olderThanEpochMillis: Long)

    public fun observeWebTransfers(): Flow<List<WebTransferRecord>>

    public suspend fun webTransfer(uploadId: String): WebTransferRecord?

    public suspend fun putWebTransfer(record: WebTransferRecord)

    public suspend fun updateWebTransferProgress(
        uploadId: String,
        receivedBytes: Long,
        state: WebTransferState,
    )

    public suspend fun deleteWebTransfer(uploadId: String)

    public suspend fun pruneFinishedWebTransfers(olderThanEpochMillis: Long)
}

@Singleton
internal class RoomWebShareRepository @Inject constructor(
    private val database: MorseDatabase,
) : WebShareRepository {

    private val sessionDao get() = database.browserSessionDao()
    private val transferDao get() = database.webTransferDao()

    override fun observeSessions(): Flow<List<BrowserSession>> =
        sessionDao.observe().map { rows -> rows.map { it.toDomain() } }

    override fun observePending(): Flow<List<BrowserSession>> =
        sessionDao.observePending().map { rows -> rows.map { it.toDomain() } }

    override fun observeActive(): Flow<List<BrowserSession>> =
        sessionDao.observeActive().map { rows -> rows.map { it.toDomain() } }

    override suspend fun session(sessionId: String): BrowserSession? =
        sessionDao.findById(sessionId)?.toDomain()

    override suspend fun sessionForTokenDigest(digest: String): BrowserSession? =
        sessionDao.findByDigest(digest)?.toDomain()

    override suspend fun put(session: BrowserSession) {
        sessionDao.upsert(session.toEntity())
    }

    override suspend fun accept(sessionId: String) {
        sessionDao.accept(sessionId, BrowserSessionState.ACTIVE.id, System.currentTimeMillis())
    }

    override suspend fun revoke(sessionId: String) {
        sessionDao.revoke(sessionId, BrowserSessionState.REVOKED.id, System.currentTimeMillis())
    }

    override suspend fun end(sessionId: String) {
        sessionDao.revoke(sessionId, BrowserSessionState.ENDED.id, System.currentTimeMillis())
    }

    override suspend fun delete(sessionId: String) {
        sessionDao.delete(sessionId)
    }

    override suspend fun recordActivity(
        sessionId: String,
        bytesDownloaded: Long,
        bytesUploaded: Long,
    ) {
        sessionDao.recordActivity(
            sessionId = sessionId,
            at = System.currentTimeMillis(),
            downloaded = bytesDownloaded.coerceAtLeast(0L),
            uploaded = bytesUploaded.coerceAtLeast(0L),
        )
    }

    override suspend fun expireIdle(nowEpochMillis: Long, maxIdleMillis: Long): Int {
        val active = sessionDao.activeSnapshot()
        var expired = 0
        active.forEach { row ->
            val idleSince = maxOf(row.lastActivityEpochMillis, row.acceptedEpochMillis)
            if (idleSince > 0L && nowEpochMillis - idleSince > maxIdleMillis) {
                sessionDao.revoke(row.sessionId, BrowserSessionState.ENDED.id, nowEpochMillis)
                expired++
            }
        }
        return expired
    }

    override suspend fun pruneEnded(olderThanEpochMillis: Long) {
        sessionDao.deleteEndedBefore(olderThanEpochMillis)
    }

    override fun observeWebTransfers(): Flow<List<WebTransferRecord>> =
        transferDao.observe().map { rows -> rows.map { it.toDomain() } }

    override suspend fun webTransfer(uploadId: String): WebTransferRecord? =
        transferDao.find(uploadId)?.toDomain()

    override suspend fun putWebTransfer(record: WebTransferRecord) {
        transferDao.upsert(record.toEntity())
    }

    override suspend fun updateWebTransferProgress(
        uploadId: String,
        receivedBytes: Long,
        state: WebTransferState,
    ) {
        transferDao.updateProgress(uploadId, receivedBytes.coerceAtLeast(0L), state.id, System.currentTimeMillis())
    }

    override suspend fun deleteWebTransfer(uploadId: String) {
        transferDao.delete(uploadId)
    }

    override suspend fun pruneFinishedWebTransfers(olderThanEpochMillis: Long) {
        transferDao.deleteFinishedBefore(olderThanEpochMillis)
    }
}
