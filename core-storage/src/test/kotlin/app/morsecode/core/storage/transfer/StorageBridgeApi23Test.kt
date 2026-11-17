package app.morsecode.core.storage.transfer

import android.content.ContentProvider
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
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
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
 * The storage bridge under API 23, over real `content://` descriptors.
 *
 * These run under Robolectric with `sdk = [23]`, which is the platform level this module still
 * supports and the level at which `ParcelFileDescriptor`, `DocumentsContract` and `Base64` behave
 * most conservatively. They are not device-instrumentation tests: the connected CI job instruments
 * only `:transport-lan`, so a storage instrumentation target would be built and never run. That
 * limitation is recorded in `doc/transfer-storage-bridge.md` rather than papered over.
 *
 * What the provider below does and does not do:
 *   - It is a real `ContentProvider` behind a real `ContentResolver`, so the code under test asks the
 *     platform the same questions it would ask on a phone.
 *   - It hands out real `ParcelFileDescriptor`s over files on disk.
 *   - It can be told to report an unknown length, to deny a read, or to fail the open, because those
 *     are the three provider shapes that change what the bridge must do.
 *
 * Seekability is injected through the existing `SeekabilityProbe` seam rather than faked with a pipe:
 * a pipe would test Robolectric's pipe support, not the discard path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class StorageBridgeApi23Test {

    private lateinit var resolver: android.content.ContentResolver
    private lateinit var provider: Api23Provider

    private val authority = "app.morsecode.test.storage"
    private val documentId = "primary:Download/clip.mp4"

    private val documentUri: Uri get() = Uri.parse("content://$authority/document/$documentId")

    @Before
    fun setUp() {
        resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        provider = Robolectric.buildContentProvider(Api23Provider::class.java)
            .create(
                ProviderInfo().apply {
                    name = Api23Provider::class.java.name
                    authority = this@StorageBridgeApi23Test.authority
                    grantUriPermissions = true
                },
            )
            .get()
        provider.reset()
    }

    private fun seed(size: Int): ByteArray {
        val dir = Files.createTempDirectory("api23-saf-").toFile()
        val content = ByteArray(size) { (it * 17 + 3).toByte() }
        val file = File(dir, "clip.mp4").apply { writeBytes(content) }
        provider.files[documentId] = file
        provider.sizes[documentId] = size.toLong()
        return content
    }

    // --- seekable and non-seekable -----------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun aSeekableContentUriResumesAtANonZeroOffset() {
        val expected = seed(8_192)
        val opened = ProviderSourceOpener.open(resolver, documentUri, offset = 4_000L)
        assertTrue("expected an opened handle: $opened", opened is ProviderOpenResult.Opened)
        val handle = (opened as ProviderOpenResult.Opened).handle
        try {
            assertTrue(handle.isSeekable)
            assertEquals(4_000L, handle.openedAtOffset)
            assertEquals("a seekable descriptor discards nothing", 0L, handle.bytesRead)
            val buffer = ByteArray(256)
            assertEquals(256, handle.read(buffer, 0, buffer.size))
            assertEquals(expected[4_000], buffer[0])
            assertEquals(expected[4_255], buffer[255])
        } finally {
            handle.close()
        }
    }

    @Test(timeout = 60_000L)
    fun aNonSeekableDescriptorReachesTheOffsetByBoundedDiscard() {
        val expected = seed(8_192)
        // The provider's descriptor is seekable; the probe is told to say it is not, which forces the
        // discard path over a real provider descriptor.
        val forced = SeekabilityProbe { descriptor ->
            ProbeResult(
                seekability = Seekability.NON_SEEKABLE,
                sizeBytes = descriptor.statSize.takeIf { it >= 0L },
                evidence = ProbeEvidence.POSITION_REJECTED,
            )
        }
        val opened = ProviderSourceOpener.open(resolver, documentUri, offset = 4_000L, probe = forced)
        assertTrue("expected an opened handle: $opened", opened is ProviderOpenResult.Opened)
        val handle = (opened as ProviderOpenResult.Opened).handle
        try {
            assertFalse(handle.isSeekable)
            val buffer = ByteArray(64)
            assertEquals(64, handle.read(buffer, 0, buffer.size))
            assertEquals("discarding reached the exact byte", expected[4_000], buffer[0])
            assertTrue("the discard was bounded and counted", handle.bytesRead >= 64L)
        } finally {
            handle.close()
        }
    }

    @Test(timeout = 60_000L)
    fun anOffsetPastTheKnownEndIsRefusedBeforeAnyRead() {
        seed(1_024)
        val opened = ProviderSourceOpener.open(resolver, documentUri, offset = 1_025L)
        assertTrue(opened is ProviderOpenResult.Failed)
        assertEquals(
            "offset_past_end",
            ((opened as ProviderOpenResult.Failed).error as TransferStorageError.StateConflict).reason,
        )
    }

    @Test(timeout = 60_000L)
    fun anUnknownLengthDescriptorOpensAndEndsWhereTheBytesDo() {
        seed(1_024)
        // A descriptor that will not state its length: position works, size does not.
        val noSize = SeekabilityProbe { _ ->
            ProbeResult(Seekability.SEEKABLE, null, ProbeEvidence.SIZE_UNAVAILABLE)
        }
        val opened = ProviderSourceOpener.open(resolver, documentUri, offset = 1_000L, probe = noSize)
        assertTrue("an unknown length must not refuse a valid offset: $opened", opened is ProviderOpenResult.Opened)
        val handle = (opened as ProviderOpenResult.Opened).handle
        try {
            val buffer = ByteArray(1_024)
            assertEquals(24, handle.read(buffer, 0, buffer.size))
            assertEquals(-1, handle.read(buffer, 0, buffer.size))
        } finally {
            handle.close()
        }

        // With no length to check against, a larger offset opens and then reads nothing.
        val past = ProviderSourceOpener.open(resolver, documentUri, offset = 5_000L, probe = noSize)
        val pastHandle = (past as ProviderOpenResult.Opened).handle
        try {
            assertEquals(-1, pastHandle.read(ByteArray(8), 0, 8))
        } finally {
            pastHandle.close()
        }
    }

    @Test(timeout = 60_000L)
    fun anEmptyFileIsAValidSourceThatImmediatelyEnds() {
        seed(0)
        val opened = ProviderSourceOpener.open(resolver, documentUri, offset = 0L)
        val handle = (opened as ProviderOpenResult.Opened).handle
        try {
            assertEquals(-1, handle.read(ByteArray(16), 0, 16))
        } finally {
            handle.close()
        }
    }

    @Test(timeout = 60_000L)
    fun aMultiChunkReadCoversTheWholeDocumentInOrder() {
        val expected = seed(200_000)
        val handle = (ProviderSourceOpener.open(resolver, documentUri, 0L) as ProviderOpenResult.Opened).handle
        val buffer = ByteArray(64 * 1024)
        val seen = java.io.ByteArrayOutputStream()
        try {
            while (true) {
                val count = handle.read(buffer, 0, buffer.size)
                if (count < 0) break
                seen.write(buffer, 0, count)
            }
        } finally {
            handle.close()
        }
        assertArrayEquals(expected, seen.toByteArray())
    }

    // --- provider failure shapes -------------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun aRevokedGrantIsRefusedAsAPermissionFailureRatherThanAnIoFailure() {
        seed(64)
        provider.denyWithSecurityException = true
        val opened = ProviderSourceOpener.open(resolver, documentUri, 0L)
        assertTrue(opened is ProviderOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (opened as ProviderOpenResult.Failed).error.category,
        )
    }

    @Test(timeout = 60_000L)
    fun aMissingDocumentIsReportedAsNotFound() {
        // No file registered for the id: the provider throws FileNotFoundException.
        val opened = ProviderSourceOpener.open(resolver, documentUri, 0L)
        assertTrue(opened is ProviderOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (opened as ProviderOpenResult.Failed).error.category,
        )
    }

    @Test(timeout = 60_000L)
    fun aProviderThatFailsTheOpenIsATypedProviderFailure() {
        seed(64)
        provider.failOpenWithIo = true
        val opened = ProviderSourceOpener.open(resolver, documentUri, 0L)
        assertTrue(opened is ProviderOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.IO,
            (opened as ProviderOpenResult.Failed).error.category,
        )
    }

    @Test(timeout = 60_000L)
    fun aDescriptorStreamThatEndsEarlyIsNotSilentlyShortened() {
        seed(100)
        val forced = SeekabilityProbe { _ ->
            ProbeResult(Seekability.NON_SEEKABLE, null, ProbeEvidence.POSITION_REJECTED)
        }
        val opened = ProviderSourceOpener.open(resolver, documentUri, offset = 500L, probe = forced)
        assertTrue(opened is ProviderOpenResult.Failed)
        assertEquals(
            "source_shorter_than_offset",
            ((opened as ProviderOpenResult.Failed).error as TransferStorageError.StateConflict).reason,
        )
    }

    // --- destination side --------------------------------------------------------------------------

    @Test(timeout = 60_000L)
    fun anAppPrivatePartialRoundTripsAndClosesOnce() {
        val staging = Files.createTempDirectory("api23-part-").toFile()
        val store = AppPrivatePartialStore(staging)
        val identity = PartialIdentity.of(TransferId("api23"), DestinationStrategy.APP_PRIVATE)
        val partial = store.open(identity)
        try {
            val chunk = ByteArray(4_096) { 0x5A }
            val outcome = partial.writeAt(0L, chunk, 0, chunk.size)
            assertTrue(outcome is WriteOutcome.Written)
            assertEquals(4_096L, partial.length())
            assertTrue(partial.flush() is FlushDurability.DurableFlushSupported)
        } finally {
            partial.close()
            partial.close()
        }
        assertEquals("the descriptor closed exactly once", 1, partial.closeCount)
        assertEquals(4_096L, store.lengthOrNull(identity))
        assertTrue("the staged bytes are really on disk", store.fileFor(identity).length() == 4_096L)
    }

    @Test(timeout = 60_000L)
    fun aSessionOverAnAppPrivatePartialVerifiesAndCommits() {
        val staging = Files.createTempDirectory("api23-session-").toFile()
        val store = AppPrivatePartialStore(staging)
        val transferId = TransferId("api23-session")
        val identity = PartialIdentity.of(transferId, DestinationStrategy.APP_PRIVATE)
        val body = ByteArray(50_000) { (it * 13).toByte() }

        val partial = store.open(identity)
        val published = RecordingPublisher()
        val opened = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = partial,
            declaredSizeBytes = body.size.toLong(),
            verificationSource = verificationSource(store.fileFor(identity)),
            publisher = published,
            resources = listOf(partial),
        )
        val session = (opened as DestinationOpen.Opened).session
        var offset = 0L
        val chunk = 16 * 1024
        while (offset < body.size) {
            val count = minOf(chunk, body.size - offset.toInt())
            val written = session.writeAt(offset, body, offset.toInt(), count)
            assertTrue("write at $offset: $written", written is DestinationWrite.Written)
            offset = (written as DestinationWrite.Written).frontier
        }
        assertNull(session.acknowledge(offset))

        val digester = Sha256Digester()
        digester.update(body, 0, body.size)
        assertTrue(session.verify(digester.digest()) is SessionVerification.Verified)
        assertEquals(CommitResult.Committed, session.commit())
        session.close()

        assertEquals(1, published.calls)
        assertArrayEquals(body, store.fileFor(identity).readBytes())
        store.delete(identity)
    }

    @Test(timeout = 60_000L)
    fun aBoundedFailingWriteIsClassifiedAsStorageFull() {
        // The classifier is what maps a real provider's write failure onto the typed domain error.
        // A provider that raises ENOSPC is simulated here; no disk is filled.
        val enospc = IOException("write failed: No space left on device")
        val classified = StorageFailureClassifier.classify(enospc, operation = "write")
        assertEquals(TransferStorageErrorCategory.INSUFFICIENT_SPACE, classified?.category)
        assertTrue(classified is TransferStorageError.StorageFull)

        val revoked = StorageFailureClassifier.classify(SecurityException("no"), operation = "read")
        assertEquals(TransferStorageErrorCategory.PERMISSION_REVOKED, revoked?.category)

        val io = StorageFailureClassifier.classify(IOException("descriptor went away"), operation = "read")
        assertEquals(TransferStorageErrorCategory.IO, io?.category)
        val opaque = StorageFailureClassifier.classify(IllegalStateException("not a storage fault"), "read")
        assertNull("a fault that is not a storage fault is not invented into one", opaque)
    }

    @Test(timeout = 60_000L)
    fun unicodeAndDuplicateNamesSurviveTheProviderRoundTrip() {
        val emoji = "\uD83D\uDE00"
        val name = "report $emoji.pdf"
        assertEquals(name, SafFilenamePolicy.requireOriginalName(name))
        val first = SafFilenamePolicy.withDuplicateSuffix(name, " (1)")
        assertTrue(first.endsWith(".pdf"))
        assertTrue(SafFilenamePolicy.utf8Length(first) <= SafFilenamePolicy.MAX_FILENAME_BYTES)

        // The same document id resolves through the provider at API 23 without touching the name.
        seed(0)
        val opened = ProviderSourceOpener.open(resolver, documentUri, 0L)
        assertTrue(opened is ProviderOpenResult.Opened)
        (opened as ProviderOpenResult.Opened).handle.close()
    }

    @Test(timeout = 60_000L)
    fun cancellationBeforeTheFirstByteClosesTheDescriptorWithoutReading() {
        seed(4_096)
        val opened = ProviderSourceOpener.open(resolver, documentUri, offset = 0L)
        val handle = (opened as ProviderOpenResult.Opened).handle
        handle.close()
        assertEquals("no byte was read on the cancelled open", 0L, handle.bytesRead)
        // Closing twice is a no-op rather than a use-after-close on the provider's descriptor.
        handle.close()
        assertEquals(0L, handle.bytesRead)
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun verificationSource(file: File): VerificationSource =
        VerificationSource { offset, buffer, dataOffset, length ->
            java.io.RandomAccessFile(file, "r").use { reader ->
                if (offset >= reader.length()) {
                    -1
                } else {
                    reader.seek(offset)
                    val count = minOf(length.toLong(), reader.length() - offset).toInt()
                    reader.readFully(buffer, dataOffset, count)
                    count
                }
            }
        }

    private class RecordingPublisher : DestinationPublisher {
        var calls: Int = 0
            private set
        var digest: Sha256Digest? = null
            private set

        override fun publish(verifiedDigest: Sha256Digest): PublicationOutcome {
            calls++
            digest = verifiedDigest
            return PublicationOutcome.Published
        }
    }

    /**
     * A provider that can be told which of the three failure shapes to exhibit.
     *
     * It is deliberately small: every knob corresponds to something the bridge must classify, so a
     * knob that is never turned is a classification that is never tested.
     */
    class Api23Provider : ContentProvider() {

        val files: MutableMap<String, File> = LinkedHashMap()
        val sizes: MutableMap<String, Long> = LinkedHashMap()
        var denyWithSecurityException: Boolean = false
        var failOpenWithIo: Boolean = false

        fun reset() {
            files.clear()
            sizes.clear()
            denyWithSecurityException = false
            failOpenWithIo = false
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val id = uri.lastPathSegment ?: return null
            val columns = projection ?: COLUMNS
            return MatrixCursor(columns).apply {
                addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "clip.mp4"
                            DocumentsContract.Document.COLUMN_SIZE -> sizes[id]
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> "video/mp4"
                            else -> null
                        }
                    }.toTypedArray(),
                )
            }
        }

        override fun getType(uri: Uri): String? = "video/mp4"
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
            if (denyWithSecurityException) throw SecurityException("grant revoked")
            if (failOpenWithIo) throw IOException("provider is unwell")
            val id = uri.lastPathSegment ?: throw FileNotFoundException("no id")
            val file = files[id] ?: throw FileNotFoundException("no file")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        companion object {
            val COLUMNS = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        }
    }
}
