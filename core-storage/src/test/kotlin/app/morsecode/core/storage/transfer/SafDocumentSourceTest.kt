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
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.model.SafGrant
import app.morsecode.core.transfer.identity.RelativeTransferPath
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The SAF source, and above all its refusals.
 *
 * The rule under test: being a `content://` Uri proves nothing. A Uri can arrive
 * from an intent, from a row an older version wrote, or from a peer, and none of
 * those are trusted because of their scheme. So each one is resolved against the
 * persisted grants with SafPaths before a descriptor is asked for, and the
 * escaped and malformed cases are tested as carefully as the working one — a
 * refusal that let a document outside the tree through would be the whole bug.
 *
 * The grant is re-checked on every use rather than once, because it can be
 * revoked in Settings while the transfer is queued.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafDocumentSourceTest {

    internal class FakeDocumentProvider : ContentProvider() {

        internal data class Row(
            val documentId: String,
            val displayName: String?,
            val sizeBytes: Long?,
            val mimeType: String?,
            val modifiedEpochMillis: Long?,
        )

        var rows: MutableMap<String, Row> = LinkedHashMap()
        var files: MutableMap<String, File> = LinkedHashMap()

        val handedOut = mutableListOf<ParcelFileDescriptor>()

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val columns = projection ?: COLUMNS
            val id = uri.lastPathSegment ?: return null
            val row = rows[id] ?: return MatrixCursor(columns)
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> valueFor(row, column) }.toTypedArray())
            }
        }

        override fun getType(uri: Uri): String? =
            rows[uri.lastPathSegment]?.mimeType

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
            val id = uri.lastPathSegment ?: throw FileNotFoundException("gone")
            val file = files[id] ?: throw FileNotFoundException("gone")
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            handedOut += descriptor
            return descriptor
        }

        private fun valueFor(row: Row, column: String): Any? = when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> row.documentId
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> row.displayName
            DocumentsContract.Document.COLUMN_SIZE -> row.sizeBytes
            DocumentsContract.Document.COLUMN_MIME_TYPE -> row.mimeType
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> row.modifiedEpochMillis
            else -> null
        }

        internal companion object {
            val COLUMNS = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
        }
    }

    private lateinit var resolver: ContentResolver
    private lateinit var provider: FakeDocumentProvider

    /** Starts out held; individual tests revoke it. */
    private var grantHeld = true

    private fun treeUri(): Uri =
        Uri.parse("content://$AUTHORITY/tree/primary%3ADownload")

    private val grant = SafGrant(
        id = 1L,
        treeUri = treeUri().toString(),
        displayName = "Download",
        grantedEpochMillis = 1_700_000_000_000L,
        readWrite = false,
    )

    private val checker = SafGrantChecker { grantHeld }

    private val relative = RelativeTransferPath("Camera/clip.mp4")

    private val documentUri: Uri get() = Uri.parse(
        "content://$AUTHORITY/tree/primary%3ADownload/document/" +
            "primary%3ADownload%2FCamera%2Fclip.mp4",
    )

    @Before
    fun setUp() {
        resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        provider = Robolectric.buildContentProvider(FakeDocumentProvider::class.java)
            .create(ProviderInfo().apply {
                name = FakeDocumentProvider::class.java.name
                authority = AUTHORITY
                grantUriPermissions = true
            })
            .get()
        grantHeld = true
    }

    private fun seed(
        documentId: String = "primary:Download/Camera/clip.mp4",
        name: String = "clip.mp4",
        size: Int = 4_096,
    ): File {
        val dir = Files.createTempDirectory("saf-").toFile()
        val file = File(dir, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
        provider.rows[documentId] = FakeDocumentProvider.Row(
            documentId = documentId,
            displayName = name,
            sizeBytes = size.toLong(),
            mimeType = "video/mp4",
            modifiedEpochMillis = 1_700_000_000_000L,
        )
        provider.files[documentId] = file
        return file
    }

    private fun create(uri: Uri = documentUri): SafSourceResult =
        SafDocumentTransferSources.create(
            resolver = resolver,
            grants = listOf(grant),
            documentUri = uri,
            relativePath = relative,
            grantChecker = checker,
        )

    // --- accepting -----------------------------------------------------------------------

    @Test
    fun `a document inside the granted tree is accepted`() {
        seed()
        val result = create()
        assertTrue("a descendant of the granted tree must be accepted: $result", result is SafSourceResult.Ready)
        val source = (result as SafSourceResult.Ready).source
        assertEquals("clip.mp4", source.displayName)
        assertEquals(4_096L, source.sizeBytes)
        assertEquals("video/mp4", source.mimeType)
        assertTrue(source.isSeekable)
    }

    @Test
    fun `metadata is read through the resolver and the identity is the document id`() {
        seed()
        val source = (create() as SafSourceResult.Ready).source
        val fingerprint = source.fingerprint()

        assertTrue(fingerprint is SourceFingerprintResult.Available)
        val available = fingerprint as SourceFingerprintResult.Available
        assertEquals("doc:primary:Download/Camera/clip.mp4", available.fingerprint.contentId)
        assertEquals(FingerprintStrength.STRONG, available.fingerprint.strength)
        assertEquals(4_096L, available.fingerprint.sizeBytes)
    }

    @Test
    fun `a deeper descendant of the same tree is still inside it`() {
        seed(documentId = "primary:Download/Camera/2026/clip.mp4")
        val deeper = Uri.parse(
            "content://$AUTHORITY/tree/primary%3ADownload/document/" +
                "primary%3ADownload%2FCamera%2F2026%2Fclip.mp4",
        )
        assertTrue(
            SafDocumentTransferSources.isInsideGrantedTree(listOf(grant), deeper),
        )
        assertTrue(create(deeper) is SafSourceResult.Ready)
    }

    @Test
    fun `opening reads the bytes the document provider yields`() {
        val file = seed()
        val source = (create() as SafSourceResult.Ready).source

        val handle = (source.openAtZero() as SourceOpenResult.Opened).handle
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(file.readBytes().take(16).toByteArray().contentEquals(buffer))
        handle.close()
    }

    @Test
    fun `opening at a confirmed offset lands exactly there`() {
        val file = seed(size = 8_192)
        val source = (create() as SafSourceResult.Ready).source

        val handle = (source.openAt(8_000L) as SourceOpenResult.Opened).handle
        assertEquals(8_000L, handle.position)
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(file.readBytes().sliceArray(8_000 until 8_016).contentEquals(buffer))
        handle.close()
    }

    // --- refusing ----------------------------------------------------------------------------

    @Test
    fun `a document outside every granted tree is refused before anything is opened`() {
        // A sibling of the grant, not a descendant of it. It is a well-formed
        // content Uri with the same authority, which is exactly why the scheme
        // proves nothing.
        val escaped = Uri.parse(
            "content://$AUTHORITY/tree/primary%3ADocuments/document/" +
                "primary%3ADocuments%2Fsecret.txt",
        )
        assertFalse(
            SafDocumentTransferSources.isInsideGrantedTree(listOf(grant), escaped),
        )
        val result = create(escaped)
        assertTrue("an escaped document must be refused: $result", result is SafSourceResult.Refused)
        val error = (result as SafSourceResult.Refused).error
        assertTrue(error is TransferStorageError.Unsupported)
        assertEquals("saf_outside_tree", (error as TransferStorageError.Unsupported).capability)
        assertTrue("nothing may be opened on the refusal path", provider.handedOut.isEmpty())
    }

    @Test
    fun `a traversal in the document id is refused`() {
        val traversal = Uri.parse(
            "content://$AUTHORITY/tree/primary%3ADownload/document/" +
                "primary%3ADownload%2F..%2F..%2FDocuments%2Fsecret.txt",
        )
        assertFalse(
            SafDocumentTransferSources.isInsideGrantedTree(listOf(grant), traversal),
        )
        assertTrue(create(traversal) is SafSourceResult.Refused)
    }

    @Test
    fun `a uri that is not a document at all is refused`() {
        val notADocument = Uri.parse("content://$AUTHORITY/whatever/12345")
        assertTrue(create(notADocument) is SafSourceResult.Refused)
    }

    @Test
    fun `a revoked grant is refused at creation`() {
        seed()
        grantHeld = false

        val result = create()
        assertTrue(result is SafSourceResult.Refused)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (result as SafSourceResult.Refused).error.category,
        )
        assertTrue("nothing may be opened without a grant", provider.handedOut.isEmpty())
    }

    @Test
    fun `a grant revoked after creation is caught by the next use`() {
        seed()
        val source = (create() as SafSourceResult.Ready).source
        grantHeld = false

        // The source object is still alive; the grant underneath it is not. This
        // is the case a construction-time check alone would miss.
        assertTrue(source.fingerprint() is SourceFingerprintResult.Unavailable)
        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (opened as SourceOpenResult.Failed).error.category,
        )
    }

    // --- missing documents -------------------------------------------------------------------------

    @Test
    fun `a document deleted from under the grant is reported as not found`() {
        seed()
        val source = (create() as SafSourceResult.Ready).source
        provider.rows.clear()
        provider.files.clear()

        assertTrue(source.fingerprint() is SourceFingerprintResult.Unavailable)
        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (opened as SourceOpenResult.Failed).error.category,
        )
    }

    @Test
    fun `a document of unknown length says so rather than claiming zero`() {
        seed()
        provider.rows["primary:Download/Camera/clip.mp4"] = FakeDocumentProvider.Row(
            documentId = "primary:Download/Camera/clip.mp4",
            displayName = "clip.mp4",
            sizeBytes = null,
            mimeType = "video/mp4",
            modifiedEpochMillis = 1_700_000_000_000L,
        )
        val source = (create() as SafSourceResult.Ready).source

        assertEquals(-1L, source.sizeBytes)
        assertNull((source.fingerprint() as SourceFingerprintResult.Available).fingerprint.sizeBytes)
    }

    // --- closure -----------------------------------------------------------------------------------------

    @Test
    fun `every descriptor handed out is closed after a successful read`() {
        seed()
        val source = (create() as SafSourceResult.Ready).source

        val handle = (source.openAtZero() as SourceOpenResult.Opened).handle
        handle.read(ByteArray(16), 0, 16)
        handle.close()

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
        val source = (create() as SafSourceResult.Ready).source

        assertTrue(source.openAt(4_096L) is SourceOpenResult.Failed)

        provider.handedOut.forEach { descriptor ->
            assertFalse(
                "a descriptor left open after a failed open is a leak",
                descriptor.fileDescriptor.valid(),
            )
        }
    }

    private companion object {
        const val AUTHORITY = "app.morsecode.test.documents"
    }
}
