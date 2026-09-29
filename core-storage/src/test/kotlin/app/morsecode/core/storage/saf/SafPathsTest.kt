package app.morsecode.core.storage.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder browser's path arithmetic, on the JVM: breadcrumb levels, descent
 * into a subfolder, and the rules that decide whether a typed path may be opened.
 *
 * The uris below are the shape the platform's external-storage provider really
 * hands out — a tree root, and a document inside it, both percent-encoded.
 */
class SafPathsTest {

    private val authority = "com.android.externalstorage.documents"
    private val tree = "content://$authority/tree/primary%3ADownload"
    private val sub = "content://$authority/tree/primary%3ADownload/document/primary%3ADownload%2F2026"
    private val rootLabel = "Internal storage"

    @Test
    fun `a bare tree uri points at the granted folder itself`() {
        assertEquals("primary:Download", SafPaths.documentIdOf(tree))
        assertEquals("primary:Download", SafPaths.rootDocumentIdOf(tree))
        assertTrue(SafPaths.isTreeUri(tree))
    }

    @Test
    fun `a tree document uri points at the level being browsed`() {
        assertEquals("primary:Download/2026", SafPaths.documentIdOf(sub))
        // The grant does not change while browsing inside it.
        assertEquals("primary:Download", SafPaths.rootDocumentIdOf(sub))
        assertTrue(SafPaths.isTreeUri(sub))
    }

    @Test
    fun `uris that are not saf trees are rejected rather than guessed at`() {
        assertFalse(SafPaths.isTreeUri("content://media/external/images/media/12"))
        assertNull(SafPaths.documentIdOf("content://media/external/images/media/12"))
        assertNull(SafPaths.rootDocumentIdOf("file:///storage/emulated/0/Download"))
        assertNull(SafPaths.documentIdOf("not a uri"))
        assertNull(SafPaths.uriFor("content://media/external/images/media/12", "primary:Download"))
    }

    @Test
    fun `building a level uri round trips through the parser`() {
        val built = SafPaths.uriFor(tree, "primary:Download/2026")
        assertEquals(sub, built)
        assertEquals("primary:Download/2026", SafPaths.documentIdOf(built!!))
        // Spaces must stay percent-encoded, not become `+`.
        val spaced = SafPaths.uriFor(tree, "primary:Download/My Files")
        assertEquals(
            "content://$authority/tree/primary%3ADownload/document/primary%3ADownload%2FMy%20Files",
            spaced,
        )
        assertEquals("primary:Download/My Files", SafPaths.documentIdOf(spaced!!))
    }

    @Test
    fun `breadcrumb levels come from the real document id`() {
        val levels = SafPaths.levelsOf("primary:Download/2026")
        assertEquals(
            listOf(
                FolderLevel(name = "primary", documentId = "primary:", isVolume = true),
                FolderLevel(name = "Download", documentId = "primary:Download"),
                FolderLevel(name = "2026", documentId = "primary:Download/2026"),
            ),
            levels,
        )
        assertEquals(rootLabel, SafPaths.displayPath(levels.take(1), rootLabel))
        assertEquals("Internal storage/Download/2026", SafPaths.displayPath(levels, rootLabel))
        assertTrue(SafPaths.levelsOf("").isEmpty())
    }

    @Test
    fun `every breadcrumb level can be turned back into a uri`() {
        SafPaths.levelsOf("primary:Download/2026").forEach { level ->
            val uri = SafPaths.uriFor(tree, level.documentId)
            assertEquals(level.documentId, SafPaths.documentIdOf(uri!!))
        }
    }

    @Test
    fun `the granted level comes back as the tree itself`() {
        // Climbing out of a level has to land on the uri the platform granted, not
        // on an equal-but-different document form of it, or the browser cannot tell
        // that it is standing where it started.
        assertEquals(tree, SafPaths.uriFor(sub, "primary:Download"))
        assertEquals(tree, SafPaths.uriFor(tree, "primary:Download"))
        assertEquals(sub, SafPaths.uriFor(sub, "primary:Download/2026"))
        assertEquals("primary:Download", SafPaths.documentIdOf(SafPaths.uriFor(sub, "primary:Download")!!))
    }

    @Test
    fun `a copied path pastes back to the same level`() {
        val levels = SafPaths.levelsOf("primary:Download/2026")
        val copied = SafPaths.displayPath(levels, rootLabel)
        assertEquals(
            PathResolution.Inside("primary:Download/2026"),
            SafPaths.resolve(copied, tree, rootLabel),
        )
        // And the volume label on its own is above the grant, so it stays closed.
        assertEquals(PathResolution.Outside, SafPaths.resolve(rootLabel, tree, rootLabel))
    }

    @Test
    fun `paths are read as volume absolute or grant relative, whichever fits`() {
        assertEquals(
            PathResolution.Inside("primary:Download/2026"),
            SafPaths.resolve("Download/2026", tree, rootLabel),
        )
        assertEquals(
            PathResolution.Inside("primary:Download/2026"),
            SafPaths.resolve("2026", tree, rootLabel),
        )
        assertEquals(
            PathResolution.Inside("primary:Download/2026"),
            SafPaths.resolve("/storage/emulated/0/Download/2026", tree, rootLabel),
        )
        assertEquals(
            PathResolution.Inside("primary:Download/2026"),
            SafPaths.resolve("primary:Download/2026", tree, rootLabel),
        )
        assertEquals(PathResolution.Inside("primary:Download/2026"), SafPaths.resolve(sub, tree))
    }

    @Test
    fun `a blank path means the level already open`() {
        assertEquals(PathResolution.Inside("primary:Download"), SafPaths.resolve("  ", tree))
        assertEquals(PathResolution.Inside("primary:Download"), SafPaths.resolve(tree, tree))
    }

    @Test
    fun `paths outside the grant are refused`() {
        assertEquals(PathResolution.Outside, SafPaths.resolve("primary:Pictures", tree, rootLabel))
        assertEquals(
            PathResolution.Outside,
            SafPaths.resolve("content://$authority/tree/primary%3APictures", tree, rootLabel),
        )
        assertEquals(
            PathResolution.Outside,
            SafPaths.resolve("/storage/emulated/0/Pictures", tree, rootLabel),
        )
    }

    @Test
    fun `traversal and junk are malformed, never resolved`() {
        assertEquals(PathResolution.Malformed, SafPaths.resolve("../Pictures", tree, rootLabel))
        assertEquals(PathResolution.Malformed, SafPaths.resolve("2026/../../", tree, rootLabel))
        assertEquals(PathResolution.Malformed, SafPaths.resolve("./2026", tree, rootLabel))
        assertEquals(PathResolution.Malformed, SafPaths.resolve("Download/2026", "not a uri"))
        assertEquals(PathResolution.Malformed, SafPaths.resolve("http://example.com/Download", tree))
    }

    @Test
    fun `a grant at the volume root accepts volume paths directly`() {
        val volumeTree = "content://$authority/tree/primary%3A"
        assertEquals("primary:", SafPaths.rootDocumentIdOf(volumeTree))
        assertEquals(
            PathResolution.Inside("primary:Download"),
            SafPaths.resolve("Download", volumeTree, rootLabel),
        )
        assertEquals(PathResolution.Inside("primary:"), SafPaths.resolve(rootLabel, volumeTree, rootLabel))
        assertEquals(
            SafPaths.uriFor(volumeTree, "primary:Download"),
            "content://$authority/tree/primary%3A/document/primary%3ADownload",
        )
    }
}
