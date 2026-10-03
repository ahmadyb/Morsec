package app.morsecode.core.storage.transfer

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pending MediaStore destination, against a provider that really stores rows.
 *
 * The behaviour under test is the ordering around `IS_PENDING`, because that flag
 * is the commit: while it is set the file is invisible, and clearing it is the
 * moment the user's gallery can see it. Publishing before verifying would put a
 * corrupt file under its final name where every app the user has granted can read
 * it, so the tests check that a blocked verification leaves the flag set.
 *
 * The other thing pinned here is durability. `force(true)` succeeds on most
 * devices and it would be easy — and wrong — to report that as fsync-grade. These
 * tests assert the opposite directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStorePendingDestinationTest {

    internal class FakeMediaStoreProvider : ContentProvider() {

        val rows = LinkedHashMap<Long, ContentValues>()
        val files = LinkedHashMap<Long, File>()
        val handedOut = mutableListOf<ParcelFileDescriptor>()
        var nextId = 1L
        var throwSecurityOnOpen = false

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            val id = nextId++
            val stored = ContentValues(values ?: ContentValues())
            stored.put(MediaStore.MediaColumns._ID, id)
            rows[id] = stored
            val dir = Files.createTempDirectory("pending-").toFile()
            files[id] = File(dir, stored.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) ?: "file")
                .apply { createNewFile() }
            return ContentUris.withAppendedId(uri, id)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val row = rows[ContentUris.parseId(uri)] ?: return null
            val columns = projection ?: COLUMNS
            return MatrixCursor(columns).apply {
                addRow(columns.map { column -> row.get(column) }.toTypedArray())
            }
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            val row = rows[ContentUris.parseId(uri)] ?: return 0
            if (values != null) row.putAll(values)
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = ContentUris.parseId(uri)
            // The bytes go with the row, as they do in MediaStore: deleting a
            // pending item that kept its file would leave an orphan nobody owns.
            files.remove(id)
            return if (rows.remove(id) != null) 1 else 0
        }

        override fun getType(uri: Uri): String? =
            rows[ContentUris.parseId(uri)]?.getAsString(MediaStore.MediaColumns.MIME_TYPE)

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            openFile(uri, mode, null)

        override fun openFile(
            uri: Uri,
            mode: String,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor? {
            if (throwSecurityOnOpen) throw SecurityException("grant revoked")
            val file = files[ContentUris.parseId(uri)] ?: throw FileNotFoundException("gone")
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            handedOut += descriptor
            return descriptor
        }

        internal companion object {
            val COLUMNS = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.IS_PENDING,
            )
        }
    }

    private lateinit var resolver: ContentResolver
    private lateinit var provider: FakeMediaStoreProvider

    private val identity = PartialIdentity.of(
        TransferId("transfer-1"),
        DestinationStrategy.MEDIA_STORE_PENDING,
    )

    private val collection: Uri get() = Uri.parse("content://$AUTHORITY/videos")

    @Before
    fun setUp() {
        resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        provider = Robolectric.buildContentProvider(FakeMediaStoreProvider::class.java)
            .create(ProviderInfo().apply {
                name = FakeMediaStoreProvider::class.java.name
                authority = AUTHORITY
                grantUriPermissions = true
            })
            .get()
    }

    private fun createPending(): Uri {
        val created = MediaStorePendingDestination.create(
            resolver = resolver,
            collection = collection,
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            relativePath = "Movies/Morsec",
        )
        assertTrue("the pending row must be created: $created", created is PendingCreation.Created)
        return (created as PendingCreation.Created).uri
    }

    private fun opened(uri: Uri): MediaStorePendingPartial {
        val opened = MediaStorePendingDestination.open(resolver, uri, identity)
        assertTrue("the pending row must reopen: $opened", opened is PendingOpen.Opened)
        return (opened as PendingOpen.Opened).partial
    }

    private fun digestOf(bytes: ByteArray): Sha256Digest {
        val digester = Sha256Digester()
        digester.update(bytes, 0, bytes.size)
        return digester.digest()
    }

    // --- creation -------------------------------------------------------------------------

    @Test
    fun `a created row is pending from the moment it exists`() {
        val uri = createPending()
        // Not "created and then flagged": there is no window in which a
        // zero-byte file is visible under its final name.
        assertTrue(MediaStorePendingDestination.isPending(resolver, uri))
    }

    @Test
    fun `the pending flag survives writing`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, ByteArray(1_024) { 0x11.toByte() }, 0, 1_024)
        partial.close()

        assertTrue(MediaStorePendingDestination.isPending(resolver, uri))
    }

    // --- reopening --------------------------------------------------------------------------

    @Test
    fun `the recorded uri reopens the same bytes after the holder is gone`() {
        // The recovery path after a process death: the only thing carried across
        // is the Uri, so it has to be enough to find the bytes again.
        val uri = createPending()
        val payload = ByteArray(4_096) { (it % 251).toByte() }

        val first = opened(uri)
        first.writeAt(0L, payload, 0, payload.size)
        first.close()

        val second = opened(uri)
        assertEquals(payload.size.toLong(), second.length())
        val buffer = ByteArray(16)
        val source = second.verificationSource()
        assertEquals(16, source.read(2_000L, buffer, 0, 16))
        assertTrue(payload.sliceArray(2_000 until 2_016).contentEquals(buffer))
        second.close()
    }

    @Test
    fun `a deleted row reopens as not found rather than as empty`() {
        val uri = createPending()
        provider.rows.clear()
        provider.files.clear()

        val reopened = MediaStorePendingDestination.open(resolver, uri, identity)
        assertTrue(reopened is PendingOpen.Refused)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (reopened as PendingOpen.Refused).error.category,
        )
    }

    @Test
    fun `a revoked grant reopens as a permission failure rather than as missing`() {
        val uri = createPending()
        provider.throwSecurityOnOpen = true

        val reopened = MediaStorePendingDestination.open(resolver, uri, identity)
        assertTrue(reopened is PendingOpen.Refused)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (reopened as PendingOpen.Refused).error.category,
        )
    }

    // --- writing ---------------------------------------------------------------------------------

    @Test
    fun `a write after a resume lands at the offset it names`() {
        val uri = createPending()
        val partial = opened(uri)
        val payload = ByteArray(8_192) { (it % 251).toByte() }

        // Two writes out of order, which is what a resumed transfer does.
        partial.writeAt(4_096L, payload, 4_096, 4_096)
        partial.writeAt(0L, payload, 0, 4_096)

        assertEquals(8_192L, partial.length())
        val buffer = ByteArray(16)
        assertEquals(16, partial.verificationSource().read(4_100L, buffer, 0, 16))
        assertTrue(payload.sliceArray(4_100 until 4_116).contentEquals(buffer))
        partial.close()
    }

    @Test
    fun `truncate discards everything past the frontier`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, ByteArray(4_096), 0, 4_096)

        val truncated = partial.truncateTo(1_024L)

        assertTrue(truncated is TruncateOutcome.Truncated)
        assertEquals(1_024L, (truncated as TruncateOutcome.Truncated).length)
        partial.close()
    }

    // --- durability ----------------------------------------------------------------------------

    @Test
    fun `a successful flush through a provider is never reported durable`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, ByteArray(1_024), 0, 1_024)

        val outcome = partial.flush()

        // The single most important assertion in this file. The call succeeded,
        // and that proves only that the provider accepted the bytes.
        assertEquals(FlushDurability.FlushAttemptedGuaranteeUnknown, outcome)
        assertTrue(FlushDurability.allowsCheckpoint(outcome))
        assertEquals("unknown", FlushDurability.rowValue(outcome))
        partial.close()
    }

    @Test
    fun `a failed flush withholds both the checkpoint and the acknowledgement`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, ByteArray(1_024), 0, 1_024)
        partial.close()

        // The channel is closed, so the flush cannot succeed and must say so
        // rather than returning the optimistic outcome.
        val outcome = partial.flush()

        assertTrue("a flush that failed must not be reported as durable: $outcome",
            outcome is FlushDurability.FlushFailed)
        assertFalse(FlushDurability.allowsCheckpoint(outcome))
        assertFalse(FlushDurability.allowsAcknowledgement(outcome))
        assertEquals("failed", FlushDurability.rowValue(outcome))
    }

    // --- publication ---------------------------------------------------------------------------

    @Test
    fun `publication verifies first and only then clears the pending flag`() {
        val uri = createPending()
        val payload = ByteArray(2_048) { (it % 251).toByte() }
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, payload.size)
        partial.close()

        val published = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertEquals(Publication.Published, published)
        assertFalse("publishing must clear the flag", MediaStorePendingDestination.isPending(resolver, uri))
    }

    @Test
    fun `publication is blocked when the bytes do not match and the item stays hidden`() {
        val uri = createPending()
        val payload = ByteArray(1_024) { 0x22.toByte() }
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, payload.size)
        partial.close()

        // Declared longer than what is there: verification reads past the end.
        val published = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = 2_048L,
            expected = null,
        )

        assertTrue("a mismatched file must not be published: $published",
            published is Publication.Blocked)
        assertTrue(
            "a blocked publication must leave the file hidden",
            MediaStorePendingDestination.isPending(resolver, uri),
        )
    }

    @Test
    fun `publication is idempotent when a restart interrupts it`() {
        val uri = createPending()
        val payload = ByteArray(512) { 0x33.toByte() }
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, payload.size)
        partial.close()

        assertEquals(
            Publication.Published,
            MediaStorePendingDestination.publish(
                resolver = resolver,
                uri = uri,
                identity = identity,
                expectedBytes = payload.size.toLong(),
                expected = null,
            ),
        )

        // The second call is what a process that died between clearing the flag
        // and recording COMMITTED looks like. It must not republish, and it must
        // not be mistaken for a failure to retry.
        assertEquals(
            Publication.AlreadyPublished,
            MediaStorePendingDestination.publish(
                resolver = resolver,
                uri = uri,
                identity = identity,
                expectedBytes = payload.size.toLong(),
                expected = null,
            ),
        )
    }

    @Test
    fun `publication of a deleted row is refused rather than silently succeeding`() {
        val uri = createPending()
        provider.rows.clear()
        provider.files.clear()

        val published = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = 0L,
            expected = null,
        )
        assertTrue(published is Publication.Refused)
    }

    // --- the API boundary -------------------------------------------------------------------------

    @Test
    fun `below API 29 the pending destination refuses rather than falling back`() {
        assertFalse(MediaStorePendingDestination.isAvailable(28))

        val created = MediaStorePendingDestination.create(
            resolver = resolver,
            collection = collection,
            displayName = "clip.mp4",
            mimeType = "video/mp4",
            sdk = 28,
        )
        assertTrue(created is PendingCreation.Refused)
        assertEquals(
            TransferStorageErrorCategory.UNSUPPORTED,
            (created as PendingCreation.Refused).error.category,
        )
    }

    // --- abandonment -------------------------------------------------------------------------------

    @Test
    fun `abandon deletes the row and nothing else does`() {
        val uri = createPending()
        assertTrue(MediaStorePendingDestination.abandon(resolver, uri))

        assertEquals(PendingState.MISSING, MediaStorePendingDestination.stateOf(resolver, uri))
        assertFalse(MediaStorePendingDestination.isPending(resolver, uri))
        assertTrue(MediaStorePendingDestination.open(resolver, uri, identity) is PendingOpen.Refused)
    }

    @Test
    fun `abandon is idempotent`() {
        val uri = createPending()
        assertTrue(MediaStorePendingDestination.abandon(resolver, uri))
        assertFalse(MediaStorePendingDestination.abandon(resolver, uri))
    }

    // --- closure -----------------------------------------------------------------------------------

    @Test
    fun `every descriptor handed out is closed`() {
        val uri = createPending()

        val partial = opened(uri)
        partial.writeAt(0L, ByteArray(64), 0, 64)
        partial.close()

        val republished = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = 64L,
            expected = null,
        )
        assertTrue(republished is Publication.Published)

        provider.handedOut.forEach { descriptor ->
            assertFalse(
                "a descriptor left open after publication is a leak",
                descriptor.fileDescriptor.valid(),
            )
        }
    }

    private companion object {
        const val AUTHORITY = "app.morsecode.test.pending"
    }
}
