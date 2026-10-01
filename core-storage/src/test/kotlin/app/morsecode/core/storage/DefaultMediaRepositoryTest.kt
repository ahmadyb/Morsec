package app.morsecode.core.storage

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.data.repository.DeviceRepository
import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.Peer
import app.morsecode.core.model.RecentDevice
import app.morsecode.core.model.SafGrant
import app.morsecode.core.storage.apps.InstalledAppsReader
import app.morsecode.core.storage.media.MediaStoreDataSource
import app.morsecode.core.storage.permissions.PermissionMatrix
import app.morsecode.core.storage.saf.SafTreeDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DefaultMediaRepositoryTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val rootUri = "content://documents/tree/primary%3ADownload"
    private val nestedUri = "content://documents/tree/primary%3ADownload/document/primary%3ADownload%2FCamera"

    private val safPhoto = item(
        id = "saf:primary:Download/Camera/IMG_2043.jpg",
        name = "IMG_2043.jpg",
        kind = MediaKind.IMAGE,
        uri = "content://documents/tree/primary%3ADownload/document/primary%3ADownload%2FCamera%2FIMG_2043.jpg",
    )
    private val safVideo = item(
        id = "saf:primary:Download/Camera/clip.mp4",
        name = "clip.mp4",
        kind = MediaKind.VIDEO,
        uri = "content://documents/tree/primary%3ADownload/document/primary%3ADownload%2FCamera%2Fclip.mp4",
    )
    private val safTrack = item(
        id = "saf:primary:Download/Music/song.mp3",
        name = "song.mp3",
        kind = MediaKind.AUDIO,
        uri = "content://documents/tree/primary%3ADownload/document/primary%3ADownload%2FMusic%2Fsong.mp3",
    )
    private val safDocument = item(
        id = "saf:primary:Download/notes.pdf",
        name = "notes.pdf",
        kind = MediaKind.DOC,
        uri = "content://documents/tree/primary%3ADownload/document/primary%3ADownload%2Fnotes.pdf",
    )
    private val nestedFolder = item(
        id = "saf:primary:Download/Camera",
        name = "Camera",
        kind = MediaKind.FOLDER,
        uri = nestedUri,
        isFolder = true,
    )

    @Before
    fun denyBroadMediaPermission() {
        Shadows.shadowOf(context).denyPermissions(*PermissionMatrix.mediaRead().toTypedArray())
    }

    @Test
    fun `media categories merge MediaStore with matching files throughout SAF trees`() = runTest {
        Shadows.shadowOf(context).grantPermissions(*PermissionMatrix.mediaRead().toTypedArray())
        val store = FakeMediaStoreDataSource(
            images = listOf(item("image:1", "store.jpg", MediaKind.IMAGE)),
            videos = listOf(item("video:2", "store.mp4", MediaKind.VIDEO)),
            audio = listOf(item("audio:3", "store.mp3", MediaKind.AUDIO)),
        )
        val repository = repository(
            grants = listOf(
                SafGrant(id = 11L, treeUri = rootUri, displayName = "Download"),
                // A second overlapping grant must not duplicate items in a category.
                SafGrant(id = 12L, treeUri = nestedUri, displayName = "Camera"),
            ),
            store = store,
        )

        assertEquals(
            listOf("image:1", safPhoto.id),
            repository.observeImages().first().map { it.id },
        )
        assertEquals(
            listOf("video:2", safVideo.id),
            repository.observeVideos().first().map { it.id },
        )
        assertEquals(
            listOf("audio:3", safTrack.id),
            repository.observeAudio().first().map { it.id },
        )
        assertEquals(1, store.imageQueries)
        assertEquals(1, store.videoQueries)
        assertEquals(1, store.audioQueries)
    }

    @Test
    fun `a valid SAF grant remains readable when broad MediaStore permission is absent`() = runTest {
        val store = FakeMediaStoreDataSource(
            images = listOf(item("image:1", "store.jpg", MediaKind.IMAGE)),
        )
        val repository = repository(
            grants = listOf(SafGrant(id = 11L, treeUri = rootUri, displayName = "Download")),
            store = store,
        )

        assertEquals(listOf(safPhoto.id), repository.observeImages().first().map { it.id })
        assertEquals(0, store.imageQueries)

        val access = repository.observeAccess().first()
        assertFalse(access.mediaReadGranted)
        assertTrue("SAF access still makes the media tabs usable", access.canListMedia)
        assertEquals(listOf(11L), access.grants.map { it.id })
    }

    @Test
    fun `item lookup resolves a SAF media item nested below its granted root`() = runTest {
        val repository = repository(
            grants = listOf(SafGrant(id = 11L, treeUri = rootUri, displayName = "Download")),
        )

        assertEquals(safPhoto, repository.itemById(safPhoto.id))
        assertEquals(safVideo, repository.itemById(safVideo.id))
        assertEquals(safTrack, repository.itemById(safTrack.id))
        assertEquals(safDocument, repository.itemById(safDocument.id))
    }

    @Test
    fun `revoked SAF grants do not contribute media or resolve old ids`() = runTest {
        val saf = FakeSafTreeDataSource(
            childrenByUri = mapOf(rootUri to listOf(safPhoto)),
            validTreeUris = emptySet(),
        )
        val repository = repository(
            grants = listOf(SafGrant(id = 11L, treeUri = rootUri, displayName = "Download")),
            saf = saf,
        )

        assertEquals(emptyList<MediaItem>(), repository.observeImages().first())
        assertEquals(null, repository.itemById(safPhoto.id))
        assertTrue(repository.observeAccess().first().grants.isEmpty())
    }

    private fun repository(
        grants: List<SafGrant>,
        store: FakeMediaStoreDataSource = FakeMediaStoreDataSource(),
        saf: FakeSafTreeDataSource = defaultSafSource(),
    ): DefaultMediaRepository = DefaultMediaRepository(
        context = context,
        mediaStore = store,
        installedApps = InstalledAppsReader(context),
        saf = saf,
        devices = FakeDeviceRepository(grants),
        io = Dispatchers.Unconfined,
    )

    private fun defaultSafSource() = FakeSafTreeDataSource(
        childrenByUri = mapOf(
            rootUri to listOf(nestedFolder, safDocument),
            nestedUri to listOf(safPhoto, safVideo, safTrack, safDocument),
        ),
        validTreeUris = setOf(rootUri, nestedUri),
    )

    private fun item(
        id: String,
        name: String,
        kind: MediaKind,
        uri: String? = null,
        isFolder: Boolean = false,
    ) = MediaItem(
        id = id,
        displayName = name,
        kind = kind,
        mimeType = null,
        sizeBytes = if (isFolder) 0L else 2_048L,
        dateModifiedEpochMillis = 1_760_000_000_000L,
        uriString = uri,
        isFolder = isFolder,
    )

    private class FakeMediaStoreDataSource(
        private val images: List<MediaItem> = emptyList(),
        private val videos: List<MediaItem> = emptyList(),
        private val audio: List<MediaItem> = emptyList(),
    ) : MediaStoreDataSource {
        var imageQueries: Int = 0
            private set
        var videoQueries: Int = 0
            private set
        var audioQueries: Int = 0
            private set

        override fun images(): List<MediaItem> {
            imageQueries += 1
            return images
        }

        override fun videos(): List<MediaItem> {
            videoQueries += 1
            return videos
        }

        override fun audio(): List<MediaItem> {
            audioQueries += 1
            return audio
        }

        override fun documents(): List<MediaItem> = emptyList()

        override fun byId(id: String): MediaItem? =
            (images + videos + audio).firstOrNull { it.id == id }
    }

    private class FakeSafTreeDataSource(
        private val childrenByUri: Map<String, List<MediaItem>>,
        private val validTreeUris: Set<String>,
    ) : SafTreeDataSource {
        override fun isValid(treeUri: String): Boolean = treeUri in validTreeUris

        override fun displayName(uri: Uri): String? = null

        override fun children(treeUri: String): List<MediaItem> = childrenByUri[treeUri].orEmpty()

        override fun folderItem(grant: SafGrant, childCount: Int, sizeBytes: Long): MediaItem =
            MediaItem(
                id = "saf:${grant.id}",
                displayName = grant.displayName,
                kind = MediaKind.FOLDER,
                mimeType = null,
                sizeBytes = sizeBytes,
                dateModifiedEpochMillis = grant.grantedEpochMillis,
                uriString = grant.treeUri,
                splitCount = childCount,
                isFolder = true,
            )

        override fun byUri(documentUri: String): MediaItem? =
            childrenByUri.values.flatten().firstOrNull { it.uriString == documentUri }

        override fun takePersistablePermission(uri: Uri): Boolean = false

        override fun releasePersistablePermission(uri: Uri) = Unit
    }

    private class FakeDeviceRepository(
        private val grants: List<SafGrant>,
    ) : DeviceRepository {
        override fun observeRecentDevices(limit: Int): Flow<List<RecentDevice>> = flowOf(emptyList())

        override suspend fun remember(peer: Peer, summary: String?) = Unit

        override suspend fun forget(peerId: String) = Unit

        override suspend fun clearRecentDevices() = Unit

        override fun observeGrants(): Flow<List<SafGrant>> = flowOf(grants)

        override suspend fun addGrant(treeUri: String, displayName: String, readWrite: Boolean): Long = 0L

        override suspend fun removeGrant(grantId: Long) = Unit

        override suspend fun removeGrantForUri(treeUri: String) = Unit

        override suspend fun grantCount(): Int = grants.size
    }
}
