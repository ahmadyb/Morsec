package app.morsecode.core.data.repository

import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.toDomain
import app.morsecode.core.model.Peer
import app.morsecode.core.model.RecentDevice
import app.morsecode.core.model.SafGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Devices the user has transferred with, and the folder grants the app holds.
 *
 * Recent devices are what lets the Connect screen offer a repeat transfer
 * without re-scanning; SAF grants are re-acquired at start-up so an inbound
 * transfer can still write to the folder the user chose (master prompt §7).
 */
public interface DeviceRepository {

    public fun observeRecentDevices(limit: Int = RECENT_LIMIT): Flow<List<RecentDevice>>

    public suspend fun remember(peer: Peer, summary: String?)

    public suspend fun forget(peerId: String)

    public suspend fun clearRecentDevices()

    public fun observeGrants(): Flow<List<SafGrant>>

    public suspend fun addGrant(treeUri: String, displayName: String, readWrite: Boolean): Long

    public suspend fun removeGrant(grantId: Long)

    public suspend fun removeGrantForUri(treeUri: String)

    public suspend fun grantCount(): Int

    public companion object {
        public const val RECENT_LIMIT: Int = 12
    }
}

@Singleton
internal class RoomDeviceRepository @Inject constructor(
    private val database: MorseDatabase,
) : DeviceRepository {

    private val deviceDao get() = database.recentDeviceDao()
    private val grantDao get() = database.safGrantDao()

    override fun observeRecentDevices(limit: Int): Flow<List<RecentDevice>> =
        deviceDao.observe(limit).map { rows -> rows.map { it.toDomain() } }

    override suspend fun remember(peer: Peer, summary: String?) {
        val existing = deviceDao.find(peer.peerId)
        val device = RecentDevice.of(
            peer = peer.copy(lastSeenEpochMillis = System.currentTimeMillis()),
            summary = summary ?: existing?.lastSummary,
            previousCount = existing?.interactionCount ?: 0,
        )
        deviceDao.upsert(device.toEntity())
    }

    override suspend fun forget(peerId: String) {
        deviceDao.delete(peerId)
    }

    override suspend fun clearRecentDevices() {
        deviceDao.clear()
    }

    override fun observeGrants(): Flow<List<SafGrant>> =
        grantDao.observe().map { rows -> rows.map { it.toDomain() } }

    override suspend fun addGrant(treeUri: String, displayName: String, readWrite: Boolean): Long =
        grantDao.insert(
            SafGrant(
                treeUri = treeUri,
                displayName = displayName,
                grantedEpochMillis = System.currentTimeMillis(),
                readWrite = readWrite,
            ).toEntity(),
        )

    override suspend fun removeGrant(grantId: Long) {
        grantDao.delete(grantId)
    }

    override suspend fun removeGrantForUri(treeUri: String) {
        grantDao.deleteByUri(treeUri)
    }

    override suspend fun grantCount(): Int = grantDao.count()
}
