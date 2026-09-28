package app.morsecode.core.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import app.morsecode.core.data.repository.DeviceRepository
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.SafGrant
import app.morsecode.core.storage.apps.InstalledAppsReader
import app.morsecode.core.storage.media.MediaStoreReader
import app.morsecode.core.storage.permissions.PermissionMatrix
import app.morsecode.core.storage.saf.SafTreeReader
import app.morsecode.core.model.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real device content for every Files tab.
 *
 * Queries are re-run when [refresh] is called (after a permission grant, a
 * folder pick or a completed transfer) and every read happens on the IO
 * dispatcher. When a permission is missing the flow emits an empty list and
 * [observeAccess] explains why, so the screen can offer the exact grant instead
 * of showing a silently empty grid.
 */
@Singleton
internal class DefaultMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStore: MediaStoreReader,
    private val installedApps: InstalledAppsReader,
    private val saf: SafTreeReader,
    private val devices: DeviceRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MediaRepository {

    /** Bumped by [refresh]; each value re-runs the queries downstream. */
    private val tick = MutableStateFlow(0)

    override fun observeAccess(): Flow<StorageAccess> =
        combine(devices.observeGrants(), tick) { grants, _ -> access(grants) }.flowOn(io)

    override fun observeImages(): Flow<List<MediaItem>> = mediaFlow { it.images() }

    override fun observeVideos(): Flow<List<MediaItem>> = mediaFlow { it.videos() }

    override fun observeAudio(): Flow<List<MediaItem>> = mediaFlow { it.audio() }

    override fun observeApps(): Flow<List<MediaItem>> =
        tick.map { installedApps.apps() }.flowOn(io)

    override fun observeDocuments(): Flow<List<MediaItem>> = tick.map {
        val granted = devices.observeGrants().first()
        val fromFolders = granted.filter { saf.isValid(it.treeUri) }.flatMap { saf.children(it.treeUri) }
        if (Build.VERSION.SDK_INT >= 33) {
            fromFolders
        } else {
            fromFolders + if (canReadMedia()) mediaStore.documents() else emptyList()
        }
    }.flowOn(io)

    override fun observeFolders(): Flow<List<MediaItem>> = tick.map {
        val granted = devices.observeGrants().first().filter { grant -> saf.isValid(grant.treeUri) }
        val folderItems = granted.map { grant -> saf.folderItem(grant) }
        if (Build.VERSION.SDK_INT >= 33 || !canReadMedia()) {
            folderItems
        } else {
            folderItems + bucketFolders(mediaStore.documents())
        }
    }.flowOn(io)

    override suspend fun childrenOf(treeUri: String): List<MediaItem> = withContext(io) {
        saf.children(treeUri)
    }

    override suspend fun itemById(id: String): MediaItem? = withContext(io) {
        when {
            id.startsWith("app:") -> installedApps.byPackage(id.removePrefix("app:"))
            id.startsWith("saf:") -> findBySafId(id)
            else -> mediaStore.byId(id)
        }
    }

    override suspend fun refresh() {
        tick.value = tick.value + 1
    }

    override suspend fun addFolder(treeUri: String): SafGrant? = withContext(io) {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return@withContext null
        if (!saf.isValid(treeUri)) return@withContext null
        if (!saf.takePersistablePermission(uri)) return@withContext null
        val name = saf.displayName(uri) ?: "Folder"
        devices.addGrant(treeUri = treeUri, displayName = name, readWrite = true)
        refresh()
        devices.observeGrants().first().firstOrNull { it.treeUri == treeUri }
    }

    override suspend fun removeFolder(grantId: Long) = withContext(io) {
        val grant = devices.observeGrants().first().firstOrNull { it.id == grantId }
        grant?.let { saf.releasePersistablePermission(Uri.parse(it.treeUri)) }
        devices.removeGrant(grantId)
        refresh()
    }

    override suspend fun pruneRevokedGrants(): Int = withContext(io) {
        val grants = devices.observeGrants().first()
        val revoked = grants.filterNot { saf.isValid(it.treeUri) }
        revoked.forEach { grant ->
            runCatching { saf.releasePersistablePermission(Uri.parse(grant.treeUri)) }
            devices.removeGrantForUri(grant.treeUri)
        }
        if (revoked.isNotEmpty()) refresh()
        revoked.size
    }

    private fun access(grants: List<SafGrant>): StorageAccess {
        val required = PermissionMatrix.mediaRead() + PermissionMatrix.mediaWrite()
        val validGrants = grants.filter { saf.isValid(it.treeUri) }
        return StorageAccess(
            mediaReadGranted = canReadMedia(),
            missingPermissions = PermissionMatrix.missing(context, required),
            grants = validGrants,
            revokedGrantUris = grants.filterNot { saf.isValid(it.treeUri) }.map { it.treeUri },
        )
    }

    private fun canReadMedia(): Boolean = PermissionMatrix.granted(context, PermissionMatrix.mediaRead())

    private fun mediaFlow(query: (MediaStoreReader) -> List<MediaItem>): Flow<List<MediaItem>> =
        tick.map { if (canReadMedia()) query(mediaStore) else emptyList() }.flowOn(io)

    private suspend fun findBySafId(id: String): MediaItem? {
        val grants = devices.observeGrants().first()
        grants.forEach { grant ->
            if ("saf:${grant.id}" == id) return saf.folderItem(grant)
            val match = saf.children(grant.treeUri).firstOrNull { it.id == id }
            if (match != null) return match
        }
        return null
    }

    /**
     * Groups non-media rows by their parent folder so the Files tab can offer the
     * same folder-first browsing the mockup shows, without SAF.
     */
    private fun bucketFolders(documents: List<MediaItem>): List<MediaItem> = documents
        .mapNotNull { item -> item.bucket?.takeIf { it.isNotBlank() }?.let { it to item } }
        .groupBy({ it.first }, { it.second })
        .map { (bucket, items) ->
            MediaItem(
                id = "bucket:$bucket",
                displayName = bucket,
                kind = MediaKind.FOLDER,
                mimeType = null,
                sizeBytes = items.sumOf { it.sizeBytes },
                dateModifiedEpochMillis = items.maxOf { it.dateModifiedEpochMillis },
                bucket = bucket,
                splitCount = items.size,
                isFolder = true,
            )
        }
}
