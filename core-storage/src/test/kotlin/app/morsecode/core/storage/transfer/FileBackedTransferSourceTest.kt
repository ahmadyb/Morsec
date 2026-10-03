package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.RelativeTransferPath
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file-backed source: containment, metadata, positioning and closure.
 *
 * The containment cases matter most. A relative path from a peer is untrusted
 * input, and both ways of escaping a root — a traversal and a symlink — have to
 * be refused before a descriptor is ever opened. Canonical paths are compared
 * because comparing strings catches neither.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileBackedTransferSourceTest {

    private val relative = RelativeTransferPath("Camera/clip.mp4")

    private lateinit var root: File
    private var permission = true

    private fun newRoot(): File = Files.createTempDirectory("source-root-").toFile()

    private fun sources(authority: String = "private"): FileBackedTransferSources =
        FileBackedTransferSources(
            root = root,
            authority = authority,
            permissionGate = { permission },
        )

    private fun seed(content: ByteArray, name: String = "clip.mp4"): File {
        val dir = File(root, "Camera").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(content) }
    }

    // --- containment -------------------------------------------------------

    @Test
    fun `a path inside the root resolves`() {
        root = newRoot()
        seed(ByteArray(64))
        assertTrue(sources().contains(relative))
        assertNotNull(sources().create(relative))
    }

    @Test
    fun `a traversal is refused before any descriptor is opened`() {
        root = newRoot()
        seed(ByteArray(64))
        val escaping = RelativeTransferPath("Camera/../../outside.mp4")
        assertFalse(sources().contains(escaping))
        assertNull(sources().create(escaping))
    }

    @Test
    fun `an absolute path is refused`() {
        root = newRoot()
        val absolute = RelativeTransferPath("/etc/passwd")
        assertNull(sources().create(absolute))
    }

    @Test
    fun `a symlink inside the root that points outside it is refused`() {
        root = newRoot()
        val outsideDir = Files.createTempDirectory("outside-").toFile()
        val outside = File(outsideDir, "secret.bin").apply { writeBytes(ByteArray(32)) }
        val insideDir = File(root, "Camera").apply { mkdirs() }
        val link = File(insideDir, "link.bin")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        val escaping = RelativeTransferPath("Camera/link.bin")
        // The symlink is inside the root by name and outside it by resolution.
        assertTrue(link.exists())
        assertFalse("a symlink escape must be refused", sources().contains(escaping))
        assertNull(sources().create(escaping))
    }

    @Test
    fun `the same relative path yields different keys under different roots`() {
        root = newRoot()
        val first = sources("alpha").keyFor(relative)
        val second = sources("beta").keyFor(relative)
        assertFalse(first == second)
    }

    // --- metadata -----------------------------------------------------------

    @Test
    fun `metadata comes from the real file`() {
        root = newRoot()
        val file = seed(ByteArray(1_024) { 0x5A.toByte() })
        val source = sources().create(relative)!!

        assertEquals("clip.mp4", source.displayName)
        assertEquals(1_024L, source.sizeBytes)
        assertEquals(file.lastModified(), source.lastModifiedEpochMillis)
        assertEquals("video/mp4", source.mimeType)
        assertTrue(source.isSeekable)
    }

    @Test
    fun `the uri carries no absolute path`() {
        root = newRoot()
        seed(ByteArray(64))
        val source = sources().create(relative)!!
        val rendered = source.uri.toString()
        assertFalse("a file source must not expose its path: $rendered", rendered.contains(root.path))
        assertFalse(rendered.contains("/storage"))
        // It is deliberately not resolvable: callers must go through the adapter.
        assertTrue(rendered.startsWith("content://"))
    }

    @Test
    fun `an unknown extension yields an empty mime type rather than a guess`() {
        root = newRoot()
        seed(ByteArray(64), name = "clip.unknownext")
        assertEquals("", sources().create(relative, displayName = "clip.unknownext")!!.mimeType)
    }

    // --- fingerprinting ------------------------------------------------------

    @Test
    fun `the fingerprint is strong because the key is a stable identity`() {
        root = newRoot()
        seed(ByteArray(512))
        val source = sources().create(relative)!!
        val result = source.fingerprint()

        assertTrue(result is SourceFingerprintResult.Available)
        val fingerprint = (result as SourceFingerprintResult.Available).fingerprint
        assertEquals(512L, fingerprint.sizeBytes)
        assertNotNull(fingerprint.contentId)
        assertEquals(FingerprintStrength.STRONG, fingerprint.strength)
    }

    @Test
    fun `a deleted source reports unavailable rather than an empty fingerprint`() {
        root = newRoot()
        val file = seed(ByteArray(512))
        val source = sources().create(relative)!!
        file.delete()

        val result = source.fingerprint()
        assertTrue(result is SourceFingerprintResult.Unavailable)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (result as SourceFingerprintResult.Unavailable).error.category,
        )
    }

    @Test
    fun `a revoked permission is its own outcome and never a source change`() {
        root = newRoot()
        seed(ByteArray(512))
        val source = sources().create(relative)!!
        permission = false

        val outcome = SourceChangePolicy.evaluate(
            stored = SourceFingerprint(512L, 1L, source.key.value),
            current = source.fingerprint(),
        )
        assertEquals(FingerprintOutcome.PERMISSION_REVOKED, outcome)
        assertFalse(outcome.requiresRestart)
    }

    @Test
    fun `a source edited in place is still the same file`() {
        root = newRoot()
        val file = seed(ByteArray(512))
        val source = sources().create(relative)!!
        val stored = (source.fingerprint() as SourceFingerprintResult.Available).fingerprint

        file.appendBytes(ByteArray(512))
        val outcome = SourceChangePolicy.evaluate(stored, source.fingerprint())

        // Identity outranks size, so an edited file resumes from a stored offset
        // rather than being discarded.
        assertEquals(FingerprintOutcome.STRONG_MATCH, outcome)
    }

    // --- opening --------------------------------------------------------------

    @Test
    fun `opening at zero reads from the start`() {
        root = newRoot()
        val content = ByteArray(4_096) { (it % 251).toByte() }
        seed(content)
        val source = sources().create(relative)!!

        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Opened)
        val handle = (opened as SourceOpenResult.Opened).handle
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(content.take(16).toByteArray().contentEquals(buffer))
        assertEquals(16L, handle.position)
        handle.close()
    }

    @Test
    fun `opening at a confirmed offset lands exactly there`() {
        root = newRoot()
        val content = ByteArray(4_096) { (it % 251).toByte() }
        seed(content)
        val source = sources().create(relative)!!

        val opened = source.openAt(4_000L)
        assertTrue(opened is SourceOpenResult.Opened)
        val handle = (opened as SourceOpenResult.Opened).handle
        assertEquals(4_000L, handle.position)
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(content.sliceArray(4_000 until 4_016).contentEquals(buffer))
        handle.close()
    }

    @Test
    fun `an offset past the end is refused rather than opened at a wrong place`() {
        root = newRoot()
        seed(ByteArray(1_024))
        val source = sources().create(relative)!!

        val opened = source.openAt(2_048L)
        assertTrue(opened is SourceOpenResult.Failed)
        val error = (opened as SourceOpenResult.Failed).error
        assertTrue(error is TransferStorageError.StateConflict)
        assertEquals("offset_past_end", error.reason)
    }

    @Test
    fun `a missing file fails to open with a typed error rather than an exception`() {
        root = newRoot()
        val file = seed(ByteArray(64))
        val source = sources().create(relative)!!
        file.delete()

        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (opened as SourceOpenResult.Failed).error.category,
        )
    }

    @Test
    fun `a revoked permission fails to open with a typed error`() {
        root = newRoot()
        seed(ByteArray(64))
        val source = sources().create(relative)!!
        permission = false

        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (opened as SourceOpenResult.Failed).error.category,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative offset is rejected rather than clamped`() {
        root = newRoot()
        seed(ByteArray(64))
        sources().create(relative)!!.openAt(-1L)
    }

    // --- non-seekable fallback ---------------------------------------------------

    @Test
    fun `a non-seekable file source reaches its offset by discarding bytes`() {
        root = newRoot()
        val content = ByteArray(8_192) { (it % 251).toByte() }
        seed(content)
        val source = FileBackedTransferSource(
            key = sources().keyFor(relative),
            uri = android.net.Uri.parse("content://app.morsecode.core.storage.private/x"),
            displayName = "clip.mp4",
            relativePath = relative,
            mimeType = "video/mp4",
            file = File(root, "Camera/clip.mp4"),
            permissionGate = { true },
            seekable = false,
        )

        val opened = source.openAt(8_000L)
        assertTrue(opened is SourceOpenResult.Opened)
        val handle = (opened as SourceOpenResult.Opened).handle
        assertFalse(handle.isSeekable)
        assertEquals(8_000L, handle.position)
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(content.sliceArray(8_000 until 8_016).contentEquals(buffer))
        handle.close()
    }

    // --- beyond 4 GiB --------------------------------------------------------------

    @Test
    fun `an offset beyond the 32-bit range is honoured`() {
        root = newRoot()
        val file = seed(ByteArray(0), name = "sparse.bin")
        val offset = 5_368_709_120L
        val payload = ByteArray(1_024) { 0x7E.toByte() }
        java.io.RandomAccessFile(file, "rw").use { it.channel.write(java.nio.ByteBuffer.wrap(payload), offset) }

        val source = sources().create(RelativeTransferPath("Camera/sparse.bin"))!!
        val opened = source.openAt(offset)
        assertTrue(opened is SourceOpenResult.Opened)
        val handle = (opened as SourceOpenResult.Opened).handle
        assertEquals(offset, handle.position)
        val buffer = ByteArray(1_024)
        assertEquals(1_024, handle.read(buffer, 0, 1_024))
        assertTrue(payload.contentEquals(buffer))
        handle.close()
        file.delete()
    }

    // --- closure -------------------------------------------------------------------

    @Test
    fun `repeated failed opens do not leak descriptors`() {
        root = newRoot()
        seed(ByteArray(64))
        val source = sources().create(relative)!!

        // Each of these opens the file, discovers the offset is past the end and
        // fails. No handle is returned on a failure path, so the adapter is the
        // only thing that can close the descriptor it took. If it did not, this
        // many would exhaust the process's descriptor table.
        repeat(2_000) {
            assertTrue(source.openAt(1_000_000L) is SourceOpenResult.Failed)
        }

        assertTrue(source.openAtZero() is SourceOpenResult.Opened)
    }

    @Test
    fun `a successful open hands ownership to the caller and closes once`() {
        root = newRoot()
        seed(ByteArray(64))
        val source = sources().create(relative)!!

        val handle = (source.openAtZero() as SourceOpenResult.Opened).handle
        handle.close()
        // A second close must be a no-op rather than an exception.
        handle.close()
        assertTrue(runCatching { handle.read(ByteArray(4), 0, 4) }.isFailure)
    }
}
