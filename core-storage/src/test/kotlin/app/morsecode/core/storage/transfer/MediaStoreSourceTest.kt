package app.morsecode.core.storage.transfer

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.transfer.identity.RelativeTransferPath
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The MediaStore source against a real ContentProvider.
 *
 * A registered provider rather than a mock: the interesting failures are the ones
 * the platform produces through the resolver — an empty cursor for a row that was
 * deleted, a SecurityException from a grant that was revoked, a descriptor that
 * will not seek — and a mocked resolver cannot produce any of them.
 *
 * No test asks for a filesystem path. That is the rule the adapter is built
 * around, so these tests assert its absence as well as its behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreSourceTest {

    internal class FakeMediaProvider : ContentProvider() {

        internal data class Row(
            val id: Long,
            val displayName: String?,
            val sizeBytes: Long?,
            val mimeType: String?,
            val modifiedSeconds: Long?,
        )

        var rows: MutableMap<Long, Row> = LinkedHashMap()
        var files: MutableMap<Long, File> = LinkedHashMap()

        /** When set, `openFile` refuses as a revoked grant would. */
        var throwSecurityOnOpen = false

        /** When set, `query` behaves as a provider that will not answer. */
        var queryReturnsNull = false

        /** Every descriptor handed out, so closure can be proven afterwards. */
        val handedOut = mutableListOf<ParcelFileDescriptor>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            if (queryReturnsNull) return null
            val columns = projection ?: COLUMNS
            val id = uri.lastPathSegment?.toLongOrNull() ?: return null
            val row = rows[id] ?: return MatrixCursor(columns)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> valueFor(row, column) }.toTypedArray())
            }
        }

        override fun getType(uri: Uri): String? =
            rows[uri.lastPathSegment?.toLongOrNull()]?.mimeType

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            openFile(uri, mode, null)

        override fun openFile(
            uri: Uri,
            mode: String,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor? {
            if (throwSecurityOnOpen) throw SecurityException("grant revoked")
            val id = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("gone")
            val file = files[id] ?: throw FileNotFoundException("gone")
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            handedOut += descriptor
            return descriptor
        }

        private fun valueFor(row: Row, column: String): Any? = when (column) {
            "_id" -> row.id
            "_display_name" -> row.displayName
            "_size" -> row.sizeBytes
            "mime_type" -> row.mimeType
            "date_modified" -> row.modifiedSeconds
            else -> null
        }

        internal companion object {
            val COLUMNS = arrayOf(
                "_id",
                "_display_name",
                "_size",
                "mime_type",
                "date_modified",
            )
        }
    }

    private lateinit var resolver: ContentResolver
    private lateinit var provider: FakeMediaProvider

    private val relative = RelativeTransferPath("Camera/clip.mp4")

    private fun uriFor(id: Long): Uri = Uri.parse("content://$AUTHORITY/media/$id")

    private fun tempFile(name: String, size: Int): File {
        val dir = Files.createTempDirectory("media-").toFile()
        return File(dir, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
    }

    @Before
    fun setUp() {
        resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        provider = Robolectric.buildContentProvider(FakeMediaProvider::class.java)
            .create(ProviderInfo().apply {
                name = FakeMediaProvider::class.java.name
                authority = AUTHORITY
                grantUriPermissions = true
            })
            .get()
    }

    private fun seed(
        id: Long = 42L,
        name: String = "clip.mp4",
        size: Int = 4_096,
        /** What the row reports. Null means the provider will not say, which is
         *  a different fact from zero and must stay null all the way through. */
        reportedSize: Long? = size.toLong(),
        mime: String? = "video/mp4",
        modifiedSeconds: Long? = 1_700_000_000L,
        file: File = tempFile(name, size),
    ): File {
        provider.rows[id] = FakeMediaProvider.Row(id, name, reportedSize, mime, modifiedSeconds)
        provider.files[id] = file
        return file
    }

    // --- metadata ---------------------------------------------------------------

    @Test
    fun `metadata comes from the resolver, not from a path`() {
        seed()
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        assertEquals("clip.mp4", source.displayName)
        assertEquals(4_096L, source.sizeBytes)
        assertEquals("video/mp4", source.mimeType)
        assertEquals(1_700_000_000_000L, source.lastModifiedEpochMillis)
        assertTrue("a real file descriptor is seekable", source.isSeekable)
    }

    @Test
    fun `the identity is the media id and never a location`() {
        seed()
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)
        val fingerprint = source.fingerprint()

        assertTrue(fingerprint is SourceFingerprintResult.Available)
        val available = fingerprint as SourceFingerprintResult.Available
        assertEquals("media:42", available.fingerprint.contentId)
        assertEquals(FingerprintStrength.STRONG, available.fingerprint.strength)
        assertEquals(4_096L, available.fingerprint.sizeBytes)
    }

    @Test
    fun `a deleted row is reported as an empty cursor by a real provider`() {
        // No row at all: the resolver hands back a cursor with nothing in it,
        // which is what a deleted media item looks like rather than an error.
        val source = MediaStoreTransferSources.create(resolver, uriFor(99L), relative)

        assertEquals(-1L, source.sizeBytes)
        val fingerprint = source.fingerprint()
        assertTrue(fingerprint is SourceFingerprintResult.Unavailable)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (fingerprint as SourceFingerprintResult.Unavailable).error.category,
        )
    }

    @Test
    fun `a provider that refuses the query yields an unavailable fingerprint rather than a throw`() {
        seed()
        provider.queryReturnsNull = true
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        assertTrue(source.fingerprint() is SourceFingerprintResult.Unavailable)
    }

    @Test
    fun `a provider that will not state a length says so instead of claiming zero`() {
        seed(reportedSize = null)
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        // -1 is "the provider would not say"; 0 would be a real, transferable
        // length and would take the zero-byte path.
        assertEquals(-1L, source.sizeBytes)
        assertNull((source.fingerprint() as SourceFingerprintResult.Available).fingerprint.sizeBytes)
    }

    // --- opening -------------------------------------------------------------------

    @Test
    fun `opening reads the bytes the provider's descriptor yields`() {
        val file = seed()
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Opened)
        val handle = (opened as SourceOpenResult.Opened).handle
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(file.readBytes().take(16).toByteArray().contentEquals(buffer))
        handle.close()
    }

    @Test
    fun `opening at a confirmed offset lands exactly there`() {
        val file = seed(size = 8_192)
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        val handle = (source.openAt(8_000L) as SourceOpenResult.Opened).handle
        assertEquals(8_000L, handle.position)
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(file.readBytes().sliceArray(8_000 until 8_016).contentEquals(buffer))
        handle.close()
    }

    @Test
    fun `an offset above the 32-bit range is honoured`() {
        // A sparse file: the length is real, the disk cost is not, and CI never
        // materialises 5 GiB.
        val offset = 5_368_709_120L
        val payload = ByteArray(1_024) { 0x7E.toByte() }
        val dir = Files.createTempDirectory("sparse-").toFile()
        val file = File(dir, "sparse.bin")
        RandomAccessFile(file, "rw").use {
            it.channel.write(ByteBuffer.wrap(payload), offset)
        }
        seed(name = "sparse.bin", reportedSize = offset + payload.size, file = file)

        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)
        val handle = (source.openAt(offset) as SourceOpenResult.Opened).handle

        assertEquals(offset, handle.position)
        val buffer = ByteArray(1_024)
        assertEquals(1_024, handle.read(buffer, 0, 1_024))
        assertTrue(payload.contentEquals(buffer))
        handle.close()
        file.delete()
    }

    @Test
    fun `an offset past the end is refused rather than opened at a wrong place`() {
        seed(size = 1_024)
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        val opened = source.openAt(4_096L)
        assertTrue(opened is SourceOpenResult.Failed)
        val error = (opened as SourceOpenResult.Failed).error
        assertTrue(error is TransferStorageError.StateConflict)
        assertEquals("offset_past_end", (error as TransferStorageError.StateConflict).reason)
    }

    @Test
    fun `an unknown length disables offset validation but the read still reports the truth`() {
        // The metadata column is null *and* the descriptor will not state a
        // length. Setting only the column is not enough: the probe measures the
        // descriptor, which happily answers for a real file, and then the opener
        // would validate after all. Both have to be unknown to reach the case.
        seed(size = 16, reportedSize = null)
        val source = MediaStoreTransferSources.create(
            resolver = resolver,
            uri = uriFor(42L),
            relativePath = relative,
            probe = SeekabilityProbe {
                ProbeResult(Seekability.SEEKABLE, null, ProbeEvidence.SIZE_UNAVAILABLE)
            },
        )

        // With no length to check against, the open proceeds: refusing it would
        // be a guess, and the short read below is the fact.
        val handle = (source.openAt(1_024L) as SourceOpenResult.Opened).handle
        assertEquals(1_024L, handle.position)
        assertEquals(-1, handle.read(ByteArray(16), 0, 16))
        handle.close()
    }

    @Test
    fun `a revoked grant during the open is its own outcome, not a missing file`() {
        seed()
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)
        provider.throwSecurityOnOpen = true

        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (opened as SourceOpenResult.Failed).error.category,
        )
    }

    @Test
    fun `a non-seekable descriptor still reaches its offset, by discarding bytes`() {
        val file = seed(size = 8_192)
        val source = MediaStoreTransferSources.create(
            resolver = resolver,
            uri = uriFor(42L),
            relativePath = relative,
            // Forces the fallback on a real seekable file, which is the only way
            // to exercise it deterministically: whether a particular provider
            // hands back a seekable descriptor is the provider's choice.
            probe = SeekabilityProbe {
                ProbeResult(Seekability.NON_SEEKABLE, null, ProbeEvidence.POSITION_REJECTED)
            },
        )

        assertFalse(source.isSeekable)
        val handle = (source.openAt(8_000L) as SourceOpenResult.Opened).handle
        assertFalse(handle.isSeekable)
        assertEquals(8_000L, handle.position)
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(file.readBytes().sliceArray(8_000 until 8_016).contentEquals(buffer))
        handle.close()
    }

    // --- change and mutation ---------------------------------------------------------

    @Test
    fun `a media item edited in place is still the same item`() {
        seed(size = 4_096)
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)
        val stored = (source.fingerprint() as SourceFingerprintResult.Available).fingerprint

        provider.rows[42L] = FakeMediaProvider.Row(42L, "clip.mp4", 9_999L, "video/mp4", 1_700_000_099L)

        // The id outranks the size, so a resumed transfer is not thrown away
        // because the file was edited while it waited.
        assertEquals(FingerprintOutcome.STRONG_MATCH, SourceChangePolicy.evaluate(stored, source.fingerprint()))
    }

    // --- closure ------------------------------------------------------------------------

    @Test
    fun `every descriptor handed out is closed after a successful read`() {
        seed()
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        val handle = (source.openAtZero() as SourceOpenResult.Opened).handle
        handle.read(ByteArray(16), 0, 16)
        handle.close()

        assertNotNull("the provider must have handed out a descriptor", provider.handedOut.firstOrNull())
        provider.handedOut.forEach { descriptor ->
            assertFalse(
                "a descriptor left open after a successful read is a leak",
                descriptor.fileDescriptor.valid(),
            )
        }
    }

    @Test
    fun `every descriptor handed out is closed after a failed open`() {
        seed(size = 1_024)
        val source = MediaStoreTransferSources.create(resolver, uriFor(42L), relative)

        // Fails after the descriptor exists: the adapter is the only thing that
        // can close it, because no handle is returned for the caller to own.
        assertTrue(source.openAt(4_096L) is SourceOpenResult.Failed)

        provider.handedOut.forEach { descriptor ->
            assertFalse(
                "a descriptor left open after a failed open is a leak",
                descriptor.fileDescriptor.valid(),
            )
        }
    }

    private companion object {
        const val AUTHORITY = "app.morsecode.test.mediastore"
    }
}
