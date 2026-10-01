package app.morsecode.ui

import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.SafGrant
import app.morsecode.core.storage.DeleteOutcome
import app.morsecode.core.storage.MediaRepository
import app.morsecode.core.storage.StorageAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The storage layer, narrowed to what a screen reads, for tests that render a real
 * screen with a real view model and no platform.
 *
 * Nothing here stands in for work the app would do: a level that is not in
 * [children] is a level the platform would not open, and an empty list is a list
 * that is empty.
 */
internal class FakeMediaRepository(
    private val images: List<MediaItem> = emptyList(),
    private val videos: List<MediaItem> = emptyList(),
    private val audio: List<MediaItem> = emptyList(),
    private val children: Map<String, List<MediaItem>> = emptyMap(),
    private val folders: Map<String, MediaItem?> = emptyMap(),
    private val access: StorageAccess = StorageAccess(mediaReadGranted = true),
) : MediaRepository {

    /**
     * What the platform says when asked to delete.
     *
     * A real resolver answers differently per API level and per ownership, and a test
     * that could not choose the answer could only ever assert whichever one this
     * environment happens to produce.
     */
    var deleteOutcome: DeleteOutcome = DeleteOutcome.Refused

    /** The uris the app asked the platform to delete, in the order it asked. */
    val deletedUris: MutableList<String> = mutableListOf()

    override fun observeAccess(): Flow<StorageAccess> = flowOf(access)

    override fun observeImages(): Flow<List<MediaItem>> = flowOf(images)

    override fun observeVideos(): Flow<List<MediaItem>> = flowOf(videos)

    override fun observeAudio(): Flow<List<MediaItem>> = flowOf(audio)

    override fun observeApps(): Flow<List<MediaItem>> = flowOf(emptyList())

    override fun observeDocuments(): Flow<List<MediaItem>> = flowOf(emptyList())

    override fun observeFolders(): Flow<List<MediaItem>> = flowOf(emptyList())

    override suspend fun childrenOf(treeUri: String): List<MediaItem> = children[treeUri].orEmpty()

    override suspend fun folderAt(uriString: String): MediaItem? = folders[uriString]

    override suspend fun itemById(id: String): MediaItem? =
        (images + videos + audio + children.values.flatten()).firstOrNull { it.id == id }

    override suspend fun refresh() = Unit

    override suspend fun addFolder(treeUri: String): SafGrant? = null

    override suspend fun removeFolder(grantId: Long) = Unit

    override suspend fun pruneRevokedGrants(): Int = 0

    override suspend fun delete(uriString: String): DeleteOutcome {
        deletedUris += uriString
        return deleteOutcome
    }

    override suspend fun deleteAfterConsent(uriString: String): DeleteOutcome = delete(uriString)
}
