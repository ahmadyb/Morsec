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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pending MediaStore destination under the two corrections.
 *
 * The first group is about descriptor ownership. Every phase must own one
 * descriptor through one object and close it once, and the write descriptor must
 * be gone before verification opens its own. The provider records, at each open,
 * whether every descriptor it handed out earlier is already closed — which is
 * what makes "closed before the next one opens" observable rather than asserted.
 *
 * The second group is about the pending state. UNKNOWN is the state that must
 * exist and must not be collapsed: failing to read IS_PENDING does not prove
 * publication, and a query that throws does not prove the row is gone. Every
 * UNKNOWN path is asserted to produce a reconciliation result and never a
 * committed one, and never a deletion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStorePendingDestinationTest {

    internal class FakeMediaStoreProvider : ContentProvider() {

        /** One open, and whether everything opened before it was already closed. */
        internal data class OpenRecord(
            val mode: String,
            val descriptor: ParcelFileDescriptor,
            val allPriorClosed: Boolean,
        )

        val rows = LinkedHashMap<Long, ContentValues>()
        val files = LinkedHashMap<Long, File>()
        val handedOut = mutableListOf<OpenRecord>()
        var nextId = 1L

        // --- how the provider misbehaves, one flag per race -------------------
        var throwSecurityOnOpen = false
        var readOnlyOnWriteOpen = false
        var throwOnQuery = false
        var throwSecurityOnQuery = false
        var queryReturnsNull = false
        var omitPendingColumn = false
        var updateReturnsZero = false
        var throwOnUpdate = false
        var deleteRowBeforeUpdate = false
        var pendingAfterUpdate: Int? = null
        /** Query ordinal after which queries start failing. Null never fails. */
        var throwOnQueryAfter: Int? = null
        var queryCount = 0

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
            queryCount++
            if (throwOnQueryAfter != null && queryCount > throwOnQueryAfter!!) {
                throw IllegalStateException("provider failed on query $queryCount")
            }
            if (throwSecurityOnQuery) throw SecurityException("permission revoked")
            if (throwOnQuery) throw IllegalStateException("provider failed")
            if (queryReturnsNull) return null

            val row = rows[ContentUris.parseId(uri)]
                // An empty cursor is a conclusive answer: the row is not there.
                ?: return MatrixCursor(projection ?: COLUMNS)

            val requested = projection ?: COLUMNS
            val columns = requested
                .filterNot { omitPendingColumn && it == MediaStore.MediaColumns.IS_PENDING }
                .toTypedArray()
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
            if (deleteRowBeforeUpdate) {
                val id = ContentUris.parseId(uri)
                rows.remove(id)
                files.remove(id)
            }
            if (throwOnUpdate) throw IllegalStateException("provider failed")
            val id = ContentUris.parseId(uri)
            val row = rows[id] ?: return 0
            if (updateReturnsZero) return 0
            if (values != null) row.putAll(values)
            pendingAfterUpdate?.let { row.put(MediaStore.MediaColumns.IS_PENDING, it) }
            return 1
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val id = ContentUris.parseId(uri)
            // The bytes go with the row, as they do in MediaStore.
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
            val flags = when {
                mode == "r" -> ParcelFileDescriptor.MODE_READ_ONLY
                mode == "rw" && readOnlyOnWriteOpen -> ParcelFileDescriptor.MODE_READ_ONLY
                else -> ParcelFileDescriptor.MODE_READ_WRITE
            }
            val descriptor = ParcelFileDescriptor.open(file, flags)
            handedOut += OpenRecord(
                mode = mode,
                descriptor = descriptor,
                allPriorClosed = handedOut.all { !it.descriptor.fileDescriptor.valid() },
            )
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

    private val payload = ByteArray(2_048) { (it % 251).toByte() }

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
        val opened = MediaStorePendingDestination.openForWrite(resolver, uri, identity)
        assertTrue("the pending row must open for writing: $opened", opened is PendingOpen.Opened)
        return (opened as PendingOpen.Opened).partial
    }

    private fun digestOf(bytes: ByteArray): Sha256Digest {
        val digester = Sha256Digester()
        digester.update(bytes, 0, bytes.size)
        return digester.digest()
    }

    private fun written(uri: Uri, bytes: ByteArray = payload) {
        val partial = opened(uri)
        assertTrue(partial.writeAt(0L, bytes, 0, bytes.size) is WriteOutcome.Written)
        partial.close()
    }

    // =========================================================================
    // 1. Descriptor ownership — one owner per descriptor, one close
    // =========================================================================

    @Test
    fun `the write phase opens exactly one descriptor`() {
        val uri = createPending()
        val partial = opened(uri)

        assertEquals(1, provider.handedOut.size)
        assertEquals("rw", provider.handedOut.single().mode)
        partial.close()
    }

    @Test
    fun `verification reopens the uri rather than reusing the write descriptor`() {
        val uri = createPending()
        written(uri)
        assertEquals("only the write descriptor should exist so far", 1, provider.handedOut.size)

        MediaStorePendingDestination.verify(
            resolver = resolver,
            uri = uri,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertEquals(2, provider.handedOut.size)
        assertEquals("rw", provider.handedOut[0].mode)
        assertEquals("r", provider.handedOut[1].mode)
    }

    @Test
    fun `the write descriptor is closed before verification opens one`() {
        val uri = createPending()
        written(uri)

        MediaStorePendingDestination.verify(
            resolver = resolver,
            uri = uri,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        val verificationOpen = provider.handedOut[1]
        assertTrue(
            "the write descriptor must be fully closed before verification opens, " +
                "otherwise the two phases own the same file at the same time",
            verificationOpen.allPriorClosed,
        )
    }

    @Test
    fun `the two phases do not share a file position`() {
        val uri = createPending()
        val partial = opened(uri)
        // Written out of order and finishing well past zero, so a reader that
        // inherited the writer's position would read from the wrong place.
        partial.writeAt(1_024L, payload, 1_024, 1_024)
        partial.writeAt(0L, payload, 0, 1_024)
        partial.close()

        val result = MediaStorePendingDestination.verify(
            resolver = resolver,
            uri = uri,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(
            "verification must read from offset zero, not from wherever writing left off",
            result is VerificationPhase.Completed && result.result is VerifyResult.Matched,
        )
    }

    @Test
    fun `closing the write owner does not invalidate verification`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, payload.size)
        partial.close()
        assertEquals(1, partial.closeCount)

        val result = MediaStorePendingDestination.verify(
            resolver = resolver,
            uri = uri,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )
        assertTrue(result is VerificationPhase.Completed && result.result is VerifyResult.Matched)
    }

    @Test
    fun `a successful publication closes every descriptor it opened exactly once`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, payload.size)
        partial.close()

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )
        assertTrue(outcome is Publication.Published)

        // One write descriptor from the test, one read descriptor from verify().
        assertEquals(2, provider.handedOut.size)
        provider.handedOut.forEach { record ->
            assertFalse(
                "a descriptor left open after publication is a leak",
                record.descriptor.fileDescriptor.valid(),
            )
        }
        assertEquals(1, partial.closeCount)
    }

    @Test
    fun `a read owner closes its descriptor exactly once`() {
        val uri = createPending()
        written(uri)

        val reader = MediaStorePendingDestination.openForRead(resolver, uri)
        assertTrue(reader is PendingReadOpen.Opened)
        val owned = (reader as PendingReadOpen.Opened).reader
        owned.close()
        owned.close()

        assertEquals("closing twice must not close the descriptor twice", 1, owned.closeCount)
    }

    @Test
    fun `an open failure leaves no descriptor open`() {
        val uri = createPending()
        provider.throwSecurityOnOpen = true

        val opened = MediaStorePendingDestination.openForWrite(resolver, uri, identity)
        assertTrue(opened is PendingOpen.Refused)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (opened as PendingOpen.Refused).error.category,
        )
        assertTrue("nothing may be opened on a refused path", provider.handedOut.isEmpty())
    }

    @Test
    fun `a write failure leaves the write descriptor owned and closable once`() {
        val uri = createPending()
        provider.readOnlyOnWriteOpen = true
        val partial = opened(uri)

        val written = partial.writeAt(0L, payload, 0, payload.size)
        assertTrue(
            "a write that cannot be performed must be reported, not swallowed: $written",
            written is WriteOutcome.Failed,
        )

        // The failure does not leak the descriptor; releasing the owner closes it,
        // and a second release is a no-op rather than a double close.
        partial.close()
        partial.close()
        assertEquals(1, partial.closeCount)
        provider.handedOut.forEach { record ->
            assertFalse(record.descriptor.fileDescriptor.valid())
        }
    }

    @Test
    fun `a flush failure closes the write descriptor when released`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, payload.size)
        partial.close()

        val outcome = partial.flush()
        assertTrue(outcome is FlushDurability.FlushFailed)
        assertFalse(FlushDurability.allowsCheckpoint(outcome))
        assertFalse(FlushDurability.allowsAcknowledgement(outcome))

        provider.handedOut.forEach { record ->
            assertFalse(record.descriptor.fileDescriptor.valid())
        }
    }

    @Test
    fun `a verification failure closes the read descriptor`() {
        val uri = createPending()
        written(uri)

        // Declared longer than the file, so verification fails part way through.
        val result = MediaStorePendingDestination.verify(
            resolver = resolver,
            uri = uri,
            expectedBytes = payload.size.toLong() * 2,
            expected = null,
        )
        assertTrue(result is VerificationPhase.Completed && result.result is VerifyResult.Failed)

        val readDescriptor = provider.handedOut.last()
        assertEquals("r", readDescriptor.mode)
        assertFalse(
            "a verification failure must not leave the read descriptor open",
            readDescriptor.descriptor.fileDescriptor.valid(),
        )
    }

    @Test
    fun `cancellation closes the active owner`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, 512)

        // Cancelling is closing the owner mid-phase, and nothing else may be open.
        partial.close()

        assertEquals(1, partial.closeCount)
        provider.handedOut.forEach { record ->
            assertFalse(record.descriptor.fileDescriptor.valid())
        }
    }

    @Test
    fun `no descriptor remains open after abandonment`() {
        val uri = createPending()
        written(uri)

        assertTrue(MediaStorePendingDestination.abandon(resolver, uri) is AbandonOutcome.Deleted)

        provider.handedOut.forEach { record ->
            assertFalse(record.descriptor.fileDescriptor.valid())
        }
    }

    // =========================================================================
    // 2. The pending state — UNKNOWN is not an answer
    // =========================================================================

    @Test
    fun `a created row is pending`() {
        val uri = createPending()
        assertEquals(PendingState.PENDING, MediaStorePendingDestination.stateOf(resolver, uri))
        assertTrue(MediaStorePendingDestination.isPending(resolver, uri))
    }

    @Test
    fun `a cleared flag reads as published`() {
        val uri = createPending()
        provider.rows[1L]?.put(MediaStore.MediaColumns.IS_PENDING, 0)
        assertEquals(PendingState.PUBLISHED, MediaStorePendingDestination.stateOf(resolver, uri))
    }

    @Test
    fun `a row a completed query cannot find is missing`() {
        val uri = createPending()
        provider.rows.clear()
        provider.files.clear()
        assertEquals(PendingState.MISSING, MediaStorePendingDestination.stateOf(resolver, uri))
    }

    @Test
    fun `a provider that omits IS_PENDING is unknown, not published`() {
        val uri = createPending()
        provider.omitPendingColumn = true

        assertEquals(
            "an unreadable pending value is not evidence of publication",
            PendingState.UNKNOWN,
            MediaStorePendingDestination.stateOf(resolver, uri),
        )
        assertFalse(MediaStorePendingDestination.isPending(resolver, uri))
    }

    @Test
    fun `a query that throws is unknown, not missing`() {
        val uri = createPending()
        provider.throwOnQuery = true

        assertEquals(
            "an exception proves nothing was determined, so it must not become 'gone'",
            PendingState.UNKNOWN,
            MediaStorePendingDestination.stateOf(resolver, uri),
        )
    }

    @Test
    fun `a query that returns no cursor is unknown, not missing`() {
        val uri = createPending()
        provider.queryReturnsNull = true

        assertEquals(PendingState.UNKNOWN, MediaStorePendingDestination.stateOf(resolver, uri))
    }

    @Test
    fun `a revoked read permission during reconciliation is unknown, not missing`() {
        val uri = createPending()
        provider.throwSecurityOnQuery = true

        assertEquals(PendingState.UNKNOWN, MediaStorePendingDestination.stateOf(resolver, uri))
    }

    // =========================================================================
    // 3. Publication races
    // =========================================================================

    @Test
    fun `verification passes and publication is confirmed`() {
        val uri = createPending()
        written(uri)

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertEquals(Publication.Published, outcome)
        assertTrue(Publication.isCommitted(outcome))
        assertEquals(PendingState.PUBLISHED, MediaStorePendingDestination.stateOf(resolver, uri))
    }

    @Test
    fun `a row deleted before publication is refused and never committed`() {
        val uri = createPending()
        written(uri)
        provider.rows.clear()
        provider.files.clear()

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(outcome is Publication.Refused)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (outcome as Publication.Refused).error.category,
        )
        assertFalse("a missing row must never become COMMITTED", Publication.isCommitted(outcome))
    }

    @Test
    fun `a row deleted between the state query and the update is refused`() {
        val uri = createPending()
        written(uri)
        provider.deleteRowBeforeUpdate = true

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(
            "deletion during publication must be detected by re-reading the state: $outcome",
            outcome is Publication.Refused,
        )
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `an update that reports zero rows is reconciled rather than assumed`() {
        val uri = createPending()
        written(uri)
        provider.updateReturnsZero = true

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        // The flag is still set, so the update did not take effect and the row
        // stays hidden rather than being reported as published.
        assertTrue(
            "a zero-row update must not be treated as success: $outcome",
            outcome is Publication.Refused,
        )
        assertFalse(Publication.isCommitted(outcome))
        assertEquals(PendingState.PENDING, MediaStorePendingDestination.stateOf(resolver, uri))
    }

    @Test
    fun `an update that succeeds but leaves the flag set is refused`() {
        val uri = createPending()
        written(uri)
        provider.pendingAfterUpdate = 1

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(outcome is Publication.Refused)
        assertEquals(
            "publication_not_effective",
            ((outcome as Publication.Refused).error as TransferStorageError.StateConflict).reason,
        )
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `a state that becomes unreadable after the update requires reconciliation`() {
        val uri = createPending()
        written(uri)
        // The first state query succeeds and the update works; only the
        // confirmation read is unavailable. That is the exact window in which
        // taking the update's word would report success for an unverifiable row.
        provider.throwOnQueryAfter = 1

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(
            "a successful update without a confirmed state must not be reported as committed",
            outcome is Publication.ReconciliationRequired,
        )
        assertEquals(PendingState.UNKNOWN, (outcome as Publication.ReconciliationRequired).state)
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `a provider that throws during the query requires reconciliation, never commitment`() {
        val uri = createPending()
        written(uri)
        provider.throwOnQuery = true

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(outcome is Publication.ReconciliationRequired)
        assertEquals(PendingState.UNKNOWN, (outcome as Publication.ReconciliationRequired).state)
        assertFalse("UNKNOWN must never become COMMITTED", Publication.isCommitted(outcome))
    }

    @Test
    fun `a provider that omits IS_PENDING never produces a committed outcome`() {
        val uri = createPending()
        written(uri)
        provider.omitPendingColumn = true

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(outcome is Publication.ReconciliationRequired)
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `a permission revoked during reconciliation is refused, not committed`() {
        val uri = createPending()
        written(uri)
        provider.throwSecurityOnQuery = true

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
        )

        assertTrue(outcome is Publication.ReconciliationRequired)
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `an already published row with a committed record is idempotent`() {
        val uri = createPending()
        written(uri)
        assertEquals(
            Publication.Published,
            MediaStorePendingDestination.publish(
                resolver = resolver,
                uri = uri,
                identity = identity,
                expectedBytes = payload.size.toLong(),
                expected = digestOf(payload),
            ),
        )

        // The second call is what a process that died between clearing the flag
        // and recording COMMITTED looks like.
        val again = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
            recorded = CommitState.COMMITTED,
        )
        assertEquals(Publication.AlreadyPublished, again)
        assertTrue(Publication.isCommitted(again))
    }

    @Test
    fun `a published row with no committed record is accepted only after an identity check`() {
        val uri = createPending()
        written(uri)
        provider.rows[1L]?.put(MediaStore.MediaColumns.IS_PENDING, 0)

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
            recorded = CommitState.READY_TO_COMMIT,
        )

        // A digest match is an identity check; a byte count alone would not be.
        assertEquals(Publication.ExternallyCompleted, outcome)
        assertTrue(Publication.isCommitted(outcome))
    }

    @Test
    fun `a published row whose bytes do not match is inconsistent, not committed`() {
        val uri = createPending()
        written(uri)
        provider.rows[1L]?.put(MediaStore.MediaColumns.IS_PENDING, 0)

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(ByteArray(payload.size) { 0x00 }),
            recorded = CommitState.READY_TO_COMMIT,
        )

        assertTrue(outcome is Publication.Inconsistent)
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `a published row with no digest available is inconsistent rather than assumed`() {
        val uri = createPending()
        written(uri)
        provider.rows[1L]?.put(MediaStore.MediaColumns.IS_PENDING, 0)

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = null,
            recorded = CommitState.READY_TO_COMMIT,
        )

        assertTrue(
            "a byte count is not an identity, so this must not be accepted: $outcome",
            outcome is Publication.Inconsistent,
        )
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `a committed record while the provider still says pending is inconsistent`() {
        val uri = createPending()
        written(uri)

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong(),
            expected = digestOf(payload),
            recorded = CommitState.COMMITTED,
        )

        assertTrue(outcome is Publication.Inconsistent)
        assertEquals(PendingState.PENDING, (outcome as Publication.Inconsistent).observed)
        assertFalse(Publication.isCommitted(outcome))
    }

    @Test
    fun `verification failure blocks publication and leaves the file hidden`() {
        val uri = createPending()
        written(uri)

        val outcome = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = uri,
            identity = identity,
            expectedBytes = payload.size.toLong() * 2,
            expected = null,
        )

        assertTrue(outcome is Publication.Blocked)
        assertFalse(Publication.isCommitted(outcome))
        assertEquals(
            "a blocked publication must leave the file hidden",
            PendingState.PENDING,
            MediaStorePendingDestination.stateOf(resolver, uri),
        )
    }

    // =========================================================================
    // 4. Abandonment and the API boundary
    // =========================================================================

    @Test
    fun `an unknown row is never automatically deleted`() {
        val uri = createPending()
        provider.queryReturnsNull = true

        val outcome = MediaStorePendingDestination.abandon(resolver, uri)

        assertTrue(outcome is AbandonOutcome.ReconciliationRequired)
        assertEquals(PendingState.UNKNOWN, (outcome as AbandonOutcome.ReconciliationRequired).state)
        // Still there: an unreadable row must not be reaped.
        assertNotNull(provider.rows[1L])
    }

    @Test
    fun `a missing row is already gone rather than deleted`() {
        val uri = createPending()
        provider.rows.clear()
        provider.files.clear()
        assertEquals(AbandonOutcome.AlreadyGone, MediaStorePendingDestination.abandon(resolver, uri))
    }

    @Test
    fun `abandon deletes a row that is known to be there`() {
        val uri = createPending()
        assertEquals(AbandonOutcome.Deleted, MediaStorePendingDestination.abandon(resolver, uri))
        assertEquals(AbandonOutcome.AlreadyGone, MediaStorePendingDestination.abandon(resolver, uri))
    }

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

        val published = MediaStorePendingDestination.publish(
            resolver = resolver,
            uri = createPending(),
            identity = identity,
            expectedBytes = 0L,
            expected = null,
            sdk = 28,
        )
        assertTrue(published is Publication.Refused)
        assertFalse(Publication.isCommitted(published))
    }

    // =========================================================================
    // 5. Durability retained
    // =========================================================================

    @Test
    fun `a successful flush through a provider is never reported durable`() {
        val uri = createPending()
        val partial = opened(uri)
        partial.writeAt(0L, payload, 0, payload.size)

        val outcome = partial.flush()

        // The call succeeded, and that proves only that the provider accepted
        // the bytes. Never fsync-grade.
        assertEquals(FlushDurability.FlushAttemptedGuaranteeUnknown, outcome)
        assertTrue(FlushDurability.allowsCheckpoint(outcome))
        assertEquals("unknown", FlushDurability.rowValue(outcome))
        partial.close()
    }

    @Test
    fun `a resumed write lands at the offset it names`() {
        val uri = createPending()
        val partial = opened(uri)
        val bytes = ByteArray(8_192) { (it % 251).toByte() }

        // Out of order, which is what resuming does.
        partial.writeAt(4_096L, bytes, 4_096, 4_096)
        partial.writeAt(0L, bytes, 0, 4_096)
        assertEquals(8_192L, partial.length())
        partial.close()

        // The digest over the whole file is what proves both halves landed.
        val verified = MediaStorePendingDestination.verify(
            resolver = resolver,
            uri = uri,
            expectedBytes = bytes.size.toLong(),
            expected = digestOf(bytes),
        )
        assertTrue(verified is VerificationPhase.Completed && verified.result is VerifyResult.Matched)
    }

    private companion object {
        const val AUTHORITY = "app.morsecode.test.pending"
    }
}
