package app.morsecode.core.storage.saf

import app.morsecode.core.model.MediaItem
import app.morsecode.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SafTreeWalkerTest {

    @Test
    fun `descendants include nested folders and files without flattening the browser source`() {
        val root = "content://documents/tree/root"
        val camera = "content://documents/tree/root/document/root%3ACamera"
        val photo = item("saf:primary:Camera/photo.jpg", "photo.jpg", MediaKind.IMAGE)
        val song = item("saf:primary:Music/song.mp3", "song.mp3", MediaKind.AUDIO)
        val tree = mapOf(
            root to listOf(
                item("saf:primary:Camera", "Camera", MediaKind.FOLDER, camera, isFolder = true),
                photo,
            ),
            camera to listOf(song),
        )

        val descendants = SafTreeWalker.descendants(root) { tree[it].orEmpty() }

        assertEquals(listOf("saf:primary:Camera", photo.id, song.id), descendants.map { it.id })
        // The existing folder browser still calls children(root) and sees one level only.
        assertEquals(listOf("saf:primary:Camera", photo.id), tree.getValue(root).map { it.id })
    }

    @Test
    fun `a cyclic document provider folder is visited once`() {
        val root = "content://documents/tree/root"
        val child = "content://documents/tree/root/document/root%3AChild"
        val rootLink = item("saf:primary:Root", "Root", MediaKind.FOLDER, root, isFolder = true)
        val childFolder = item("saf:primary:Child", "Child", MediaKind.FOLDER, child, isFolder = true)
        val leaf = item("saf:primary:Child/photo.jpg", "photo.jpg", MediaKind.IMAGE)
        val tree = mapOf(
            root to listOf(childFolder),
            child to listOf(leaf, rootLink),
        )

        val descendants = SafTreeWalker.descendants(root) { tree[it].orEmpty() }

        assertEquals(listOf(childFolder.id, leaf.id, rootLink.id), descendants.map { it.id })
    }

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
        sizeBytes = if (isFolder) 0L else 1L,
        dateModifiedEpochMillis = 1L,
        uriString = uri,
        isFolder = isFolder,
    )
}
