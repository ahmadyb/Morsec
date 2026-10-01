package app.morsecode.core.storage

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import app.morsecode.core.data.repository.DeviceRepository
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.SafGrant
import app.morsecode.core.model.di.IoDispatcher
import app.morsecode.core.storage.apps.InstalledAppsReader
import app.morsecode.core.storage.media.MediaStoreDataSource
import app.morsecode.core.storage.permissions.PermissionMatrix
import app.morsecode.core.storage.saf.SafTreeDataSource
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
 * dispatcher. Missing media permissions suppress MediaStore reads, but never
 * hide files inside a still-valid SAF grant; [observeAccess] tells the screen
 * which source is available so it can explain an empty category honestly.
 */
@Singleton
internal class DefaultMediaRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStore: MediaStoreDataSource,
    private val installedApps: InstalledAppsReader,
    private val saf: SafTreeDataSource,
    private val devices: DeviceRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : MediaRepository {

    /** Bumped by [refresh]; each value re-runs the queries downstream. */
    private val tick = MutableStateFlow(0)

    override fun observeAccess(): Flow<StorageAccess> =
        combine(devices.observeGrants(), tick) { grants, _ -> access(grants) }.flowOn(io)

    override fun observeImages(): Flow<List<MediaItem>> =
        mediaFlow(MediaKind.IMAGE) { images() }

    override fun observeVideos(): Flow<List<MediaItem>> =
        mediaFlow(MediaKind.VIDEO) { videos() }

    override fun observeAudio(): Flow<List<MediaItem>> =
        mediaFlow(MediaKind.AUDIO) { audio() }

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

    override suspend fun folderAt(uriString: String): MediaItem? = withContext(io) {
        saf.byUri(uriString)?.takeIf { it.isFolder }
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
        val uri = runCatching { treeUri.toUri() }.getOrNull() ?: return@withContext null
        if (!saf.isValid(treeUri)) return@withContext null
        if (!saf.takePersistablePermission(uri)) return@withContext null
        val name = saf.displayName(uri) ?: "Folder"
        devices.addGrant(treeUri = treeUri, displayName = name, readWrite = true)
        refresh()
        devices.observeGrants().first().firstOrNull { it.treeUri == treeUri }
    }

    override suspend fun removeFolder(grantId: Long) = withContext(io) {
        val grant = devices.observeGrants().first().firstOrNull { it.id == grantId }
        grant?.let { saf.releasePersistablePermission(it.treeUri.toUri()) }
        devices.removeGrant(grantId)
        refresh()
    }

    override suspend fun delete(uriString: String): DeleteOutcome = withContext(io) {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@withContext DeleteOutcome.Refused
        val attempt = runCatching { context.contentResolver.delete(uri, null, null) }
        if (attempt.isSuccess) {
            return@withContext rowsToOutcome(attempt.getOrDefault(0))
        }

        val cause = attempt.exceptionOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29 carries the consent flow inside the exception it throws, and what
            // comes back is a permission rather than a deletion.
            @Suppress("DEPRECATION")
            val sender = (cause as? android.app.RecoverableSecurityException)
                ?.userAction
                ?.actionIntent
                ?.intentSender
            if (sender != null) {
                return@withContext DeleteOutcome.Consent(sender, deleteAfterGrant = true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30 and above ask the same question and delete the row themselves.
            val sender = runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            }.getOrNull()
            if (sender != null) {
                return@withContext DeleteOutcome.Consent(sender, deleteAfterGrant = false)
            }
        }
        DeleteOutcome.Refused
    }

    override suspend fun deleteAfterConsent(uriString: String): DeleteOutcome = withContext(io) {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return@withContext DeleteOutcome.Refused
        val rows = runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0)
        rowsToOutcome(rows)
    }

    /** Zero rows changed is a refusal: nothing was deleted, and the app must not say otherwise. */
    private fun rowsToOutcome(rows: Int): DeleteOutcome =
        if (rows > 0) DeleteOutcome.Deleted else DeleteOutcome.Refused

    override suspend fun pruneRevokedGrants(): Int = withContext(io) {
        val grants = devices.observeGrants().first()
        val revoked = grants.filterNot { saf.isValid(it.treeUri) }
        revoked.forEach { grant ->
            runCatching { saf.releasePersistablePermission(grant.treeUri.toUri()) }
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

    /** MediaStore plus every matching file below a valid user-granted SAF tree. */
    private fun mediaFlow(
        kind: MediaKind,
        query: MediaStoreDataSource.() -> List<MediaItem>,
    ): Flow<List<MediaItem>> = tick.map {
        val fromMediaStore = if (canReadMedia()) query(mediaStore) else emptyList()
        val fromSaf = safMediaItems(kind)
        (fromMediaStore + fromSaf)
            .filter { !it.isFolder && it.kind == kind }
            .distinctBy { it.id }
    }.flowOn(io)

    private suspend fun safMediaItems(kind: MediaKind): List<MediaItem> =
        devices.observeGrants().first()
            .asSequence()
            .filter { saf.isValid(it.treeUri) }
            .flatMap { grant -> saf.descendants(grant.treeUri).asSequence() }
            .filter { !it.isFolder && it.kind == kind }
            .toList()

    private suspend fun findBySafId(id: String): MediaItem? {
        val grants = devices.observeGrants().first()
        grants.forEach { grant ->
            if (!saf.isValid(grant.treeUri)) return@forEach
            if ("saf:${grant.id}" == id) return saf.folderItem(grant)
            val match = saf.descendants(grant.treeUri).firstOrNull { it.id == id }
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
