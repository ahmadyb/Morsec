package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
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
 * Resume behaviour, pinned against the components that already implement it.
 *
 * `TransferSource.openAt`, `ProviderSourceOpener`, `FileBackedTransferSource` and
 * `NonSeekableRepositioner` implement positioning. These tests pin their low-level behavior and the
 * restart decisions; `TypedSourceReaderTest` separately covers the runtime-facing read boundary.
 *
 * The seekable cases run over a real file through the shipped `FileBackedTransferSource`. The
 * non-seekable ones run over a stream, because the discard loop is the part that must stay bounded and
 * it is the same loop the provider opener calls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceResumePolicyTest {

    private val relative = RelativeTransferPath("Camera/clip.mp4")

    private lateinit var root: File
    private var permission = true

    private fun newRoot(): File = Files.createTempDirectory("resume-root-").toFile()

    private fun source(seekable: Boolean = true): FileBackedTransferSource {
        val dir = File(root, "Camera").apply { mkdirs() }
        val file = File(dir, "clip.mp4")
        return FileBackedTransferSource(
            key = TransferSourceKey("private:camera:clip"),
            uri = android.net.Uri.parse("content://private/Camera/clip.mp4"),
            displayName = "clip.mp4",
            relativePath = relative,
            mimeType = "video/mp4",
            file = file,
            permissionGate = { permission },
            seekable = seekable,
        )
    }

    private fun seed(size: Int): ByteArray {
        val dir = File(root, "Camera").apply { mkdirs() }
        val content = ByteArray(size) { (it * 11).toByte() }
        File(dir, "clip.mp4").writeBytes(content)
        return content
    }

    // --- seekable resume -------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun aSeekableSourceResumesExactlyAtTheRequestedOffset() {
        root = newRoot()
        val content = seed(200_000)
        val transfer = source()

        val opened = transfer.openAt(120_000L)
        assertTrue("expected an open handle, got $opened", opened is SourceOpenResult.Opened)
        val handle = (opened as SourceOpenResult.Opened).handle
        try {
            assertTrue(handle.isSeekable)
            assertEquals(120_000L, handle.openedAtOffset)
            assertEquals("no bytes are consumed to reach a seekable offset", 0L, handle.bytesRead)
            assertEquals(120_000L, handle.position)

            val buffer = ByteArray(1_024)
            val count = handle.read(buffer, 0, buffer.size)
            assertEquals(1_024, count)
            // The first byte read is the byte at the offset, not the file's first byte.
            assertEquals(content[120_000], buffer[0])
            assertEquals(121_024L, handle.position)
        } finally {
            handle.close()
        }
    }

    @Test(timeout = 30_000L)
    fun aSeekableOffsetPastTheEndIsRefusedBeforeAnyByteIsRead() {
        root = newRoot()
        seed(1_000)
        val opened = source().openAt(1_001L)
        assertTrue("an offset past the end must be refused, got $opened", opened is SourceOpenResult.Failed)
        val error = (opened as SourceOpenResult.Failed).error
        assertEquals(TransferStorageErrorCategory.STATE, error.category)
        assertEquals("offset_past_end", (error as TransferStorageError.StateConflict).reason)
    }

    @Test(timeout = 30_000L)
    fun anOffsetExactlyAtTheEndIsAValidResumePosition() {
        root = newRoot()
        seed(1_000)
        val opened = source().openAt(1_000L)
        assertTrue(opened is SourceOpenResult.Opened)
        val handle = (opened as SourceOpenResult.Opened).handle
        try {
            assertEquals(-1, handle.read(ByteArray(16), 0, 16))
        } finally {
            handle.close()
        }
    }

    @Test(timeout = 30_000L)
    fun aNegativeResumeOffsetIsRefused() {
        root = newRoot()
        seed(64)
        val opened = source().openAt(-1L)
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            "negative_offset",
            ((opened as SourceOpenResult.Failed).error as TransferStorageError.StateConflict).reason,
        )
    }

    // --- non-seekable resume ---------------------------------------------------------------------

    /**
     * The discard loop is bounded in memory and decides progress by bytes, not by allocation.
     *
     * Eight MiB is discarded through a 64 KiB buffer at a four MiB offset, so the loop must run about
     * 64 times and the working set must not grow with the offset.
     */
    @Test(timeout = 30_000L)
    fun aNonSeekableSourceDiscardsToTheOffsetThroughAConstantBuffer() {
        val total = 8 * 1024 * 1024
        val target = 4 * 1024 * 1024
        val stream = CountingStream(total)
        val buffer = ByteArray(NonSeekableRepositioner.SKIP_BUFFER_BYTES)
        val readsBefore = stream.readCalls

        val result = NonSeekableRepositioner.reposition(
            stream = SourceStream { data, offset, length -> stream.read(data, offset, length) },
            targetOffset = target.toLong(),
            buffer = buffer,
        )

        assertTrue("expected a successful reposition, got $result", result is RepositionResult.Positioned)
        assertEquals(target.toLong(), (result as RepositionResult.Positioned).discarded)
        assertEquals("the working buffer is fixed, not proportional to the offset", 65_536, buffer.size)
        assertEquals(
            "the loop is bounded by offset/buffer, not by offset in bytes",
            target / 65_536,
            stream.readCalls - readsBefore,
        )
        assertEquals("and the stream is now at the offset", target, stream.position)
    }

    @Test(timeout = 30_000L)
    fun eofBeforeTheOffsetIsReportedRatherThanSilentlyShortened() {
        val stream = CountingStream(1_000)
        val result = NonSeekableRepositioner.reposition(
            stream = SourceStream { data, offset, length -> stream.read(data, offset, length) },
            targetOffset = 5_000L,
        )
        assertTrue("expected an EOF result, got $result", result is RepositionResult.EndOfFile)
        assertEquals(1_000L, (result as RepositionResult.EndOfFile).discarded)
    }

    @Test(timeout = 30_000L)
    fun aStreamThatStopsProgressingFailsRatherThanSpinning() {
        val stalled = SourceStream { _, _, _ -> 0 }
        val result = NonSeekableRepositioner.reposition(stalled, 1_000L)
        assertTrue("a stalled stream must be bounded, got $result", result is RepositionResult.Failed)
        val error = (result as RepositionResult.Failed).error
        assertEquals(
            "a provider that stops advancing is a provider fault, not an I/O fault",
            TransferStorageErrorCategory.PROVIDER,
            error.category,
        )
        assertTrue(error is TransferStorageError.ZeroProgress)
        assertEquals(
            NonSeekableRepositioner.MAX_ZERO_PROGRESS_STEPS,
            (error as TransferStorageError.ZeroProgress).steps,
        )
    }

    @Test(timeout = 30_000L)
    fun cancellationAfterOneDiscardReadStopsWithoutAnotherRead() {
        var calls = 0
        val stream = SourceStream { _, _, _ ->
            calls++
            if (calls == 1) 1 else throw CancellationException("private provider detail")
        }
        val result = NonSeekableRepositioner.reposition(stream, 10L)
        assertTrue(result is RepositionResult.Failed)
        assertEquals(TransferStorageError.Cancelled, (result as RepositionResult.Failed).error)
        assertEquals(2, calls)
    }

    @Test(timeout = 30_000L)
    fun discardFailureAfterProgressIsClassifiedWithoutProviderText() {
        val secret = "content://private/secret/photo.jpg"
        var calls = 0
        val stream = SourceStream { _, _, _ ->
            calls++
            if (calls == 1) 1 else throw IOException(secret)
        }
        val result = NonSeekableRepositioner.reposition(stream, 10L) as RepositionResult.Failed
        assertEquals(TransferStorageErrorCategory.IO, result.error.category)
        assertNull(result.error.diagnostic)
        assertFalse(result.toString().contains(secret))
        assertEquals(2, calls)
    }

    @Test(timeout = 30_000L)
    fun interruptionStopsTheDiscardLoopAndPreservesTheThreadFlag() {
        var calls = 0
        val stream = SourceStream { _, _, _ ->
            calls++
            Thread.currentThread().interrupt()
            1
        }
        try {
            val result = NonSeekableRepositioner.reposition(stream, 10L)
            assertTrue(result is RepositionResult.Failed)
            assertEquals(TransferStorageError.Cancelled, (result as RepositionResult.Failed).error)
            assertTrue(Thread.currentThread().isInterrupted)
            assertEquals(1, calls)
        } finally {
            Thread.interrupted()
        }
    }

    @Test(timeout = 30_000L)
    fun impossibleDiscardCountsCannotProduceAPositionedSource() {
        for (count in listOf(-2, 17)) {
            val result = NonSeekableRepositioner.reposition(SourceStream { _, _, _ -> count }, 16L)
            assertTrue(result is RepositionResult.Failed)
            assertEquals(TransferStorageErrorCategory.PROVIDER, (result as RepositionResult.Failed).error.category)
        }
    }

    // --- fingerprint comparison ------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun aStableSourceResumes() {
        root = newRoot()
        seed(4_096)
        val transfer = source()
        val stored = (transfer.fingerprint() as SourceFingerprintResult.Available).fingerprint

        val current = transfer.fingerprint()
        val unchanged = SourceChangePolicy.evaluate(stored, current)
        assertEquals(FingerprintOutcome.STRONG_MATCH, unchanged)
        val expected = Sha256Digest(ByteArray(32) { 3 })
        assertTrue(SourceResumePolicy.authorize(stored, current, expected) is SourceResumeDecision.Ready)
        assertTrue(SourceResumePolicy.authorize(stored, current, null) is SourceResumeDecision.Refused)
        assertFalse(unchanged.requiresRestart)
    }

    /** A stable app-private key does not permit resuming an edited, longer source. */
    @Test(timeout = 30_000L)
    fun stableIdentityCannotOverrideAChangedSize() {
        root = newRoot()
        seed(4_096)
        val stored = (source().fingerprint() as SourceFingerprintResult.Available).fingerprint

        File(File(root, "Camera"), "clip.mp4").appendBytes(byteArrayOf(0x7F))
        val current = source().fingerprint()
        val afterGrowth = SourceChangePolicy.evaluate(stored, current)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, afterGrowth)
        assertTrue(afterGrowth.requiresRestart)
        val refused = SourceResumePolicy.authorize(stored, current, Sha256Digest(ByteArray(32)))
        assertTrue(refused is SourceResumeDecision.Refused)
        assertEquals(
            "source_changed_restart_required",
            ((refused as SourceResumeDecision.Refused).error as TransferStorageError.StateConflict).reason,
        )
    }

    /** A size mismatch is independently decisive whether an identity exists or not. */
    @Test(timeout = 30_000L)
    fun aChangedSizeWithNoIdentityRequiresRestart() {
        val stored = SourceFingerprint(sizeBytes = 4_096L, lastModifiedEpochMillis = 1_000L, contentId = null)
        val grown = SourceFingerprint(sizeBytes = 4_097L, lastModifiedEpochMillis = 1_000L, contentId = null)
        val outcome = SourceChangePolicy.compare(stored, grown)
        assertEquals(FingerprintOutcome.DEFINITE_CHANGE, outcome)
        assertTrue("a mismatch must force a restart", outcome.requiresRestart)
        assertTrue(decisionFor(stored, grown) is SourceResumeDecision.Refused)
    }

    @Test(timeout = 30_000L)
    fun aTouchedTimestampWithNoIdentityAlsoRequiresRestart() {
        val stored = SourceFingerprint(sizeBytes = 4_096L, lastModifiedEpochMillis = 1_000L, contentId = null)
        val touched = SourceFingerprint(sizeBytes = 4_096L, lastModifiedEpochMillis = 2_000L, contentId = null)
        val outcome = SourceChangePolicy.compare(stored, touched)
        assertEquals(
            "re-downloading a metadata-touched file is the cheaper mistake",
            FingerprintOutcome.DEFINITE_CHANGE,
            outcome,
        )
    }

    @Test(timeout = 30_000L)
    fun aReplacedFileWithTheSameSizeIsStillDetectedByItsIdentity() {
        root = newRoot()
        seed(128)
        val stored = (source().fingerprint() as SourceFingerprintResult.Available).fingerprint
        // Same length, different stable identity: a comparable identity mismatch forces restart.
        val replaced = SourceFingerprint(sizeBytes = 128L, lastModifiedEpochMillis = null, contentId = "other")
        assertEquals(
            FingerprintOutcome.DEFINITE_CHANGE,
            SourceChangePolicy.compare(stored, replaced),
        )
    }

    @Test(timeout = 30_000L)
    fun anUnreadableFingerprintIsNotEvidenceOfChangeButStillDoesNotResume() {
        val stored = SourceFingerprint(sizeBytes = 100L, lastModifiedEpochMillis = null, contentId = "id")
        val unavailable = SourceFingerprintResult.Unavailable(
            TransferStorageError.ProviderFailure("documents"),
        )
        val outcome = SourceChangePolicy.evaluate(stored, unavailable)
        assertEquals(FingerprintOutcome.UNAVAILABLE, outcome)
        assertTrue(SourceResumePolicy.authorize(stored, unavailable, Sha256Digest(ByteArray(32))) is SourceResumeDecision.Refused)
        assertFalse("an unavailable query is not the same as changed", outcome.requiresRestart)

        val revoked = SourceFingerprintResult.Unavailable(
            TransferStorageError.PermissionRevoked("read"),
        )
        assertEquals(
            FingerprintOutcome.PERMISSION_REVOKED,
            SourceChangePolicy.evaluate(stored, revoked),
        )
    }

    @Test(timeout = 30_000L)
    fun aSourceWithNoUsableMetadataIsReportedAsMissingNotAsAMatch() {
        val stored = SourceFingerprint(sizeBytes = 100L, lastModifiedEpochMillis = null, contentId = null)
        val current = SourceFingerprint(sizeBytes = null, lastModifiedEpochMillis = null, contentId = null)
        val outcome = SourceChangePolicy.compare(stored, current)
        assertEquals(FingerprintOutcome.MISSING_METADATA, outcome)
        assertTrue(SourceResumePolicy.authorize(stored, SourceFingerprintResult.Available(current), null) is SourceResumeDecision.Refused)
        assertTrue(decisionFor(stored, current) is SourceResumeDecision.Ready)
    }

    @Test(timeout = 30_000L)
    fun aRevokedPermissionIsRefusedOnFingerprintAndOnOpen() {
        root = newRoot()
        seed(64)
        val transfer = source()
        permission = false
        try {
            val fingerprint = transfer.fingerprint()
            assertTrue(fingerprint is SourceFingerprintResult.Unavailable)
            assertEquals(
                TransferStorageErrorCategory.PERMISSION_REVOKED,
                (fingerprint as SourceFingerprintResult.Unavailable).error.category,
            )
            val opened = transfer.openAt(0L)
            assertTrue(opened is SourceOpenResult.Failed)
            assertEquals(
                TransferStorageErrorCategory.PERMISSION_REVOKED,
                (opened as SourceOpenResult.Failed).error.category,
            )
        } finally {
            permission = true
        }
    }

    private fun decisionFor(stored: SourceFingerprint, current: SourceFingerprint): SourceResumeDecision =
        SourceResumePolicy.authorize(stored, SourceFingerprintResult.Available(current), Sha256Digest(ByteArray(32)))

    // --- ownership -------------------------------------------------------------------------------

    @Test(timeout = 30_000L)
    fun everyHandleClosesExactlyOnceAndRefusalsLeaveNothingOpen() {
        root = newRoot()
        seed(256)
        val transfer = source()
        val handle = (transfer.openAt(64L) as SourceOpenResult.Opened).handle
        handle.close()
        handle.close()
        // A second close is a no-op at the handle contract; the descriptor is owned by the resource
        // underneath, which is asserted exactly-once by its own test.
        assertEquals(64L, handle.openedAtOffset)

        // A refused open never yields a handle, so there is nothing for a caller to leak.
        val refused = transfer.openAt(1_000L)
        assertTrue(refused is SourceOpenResult.Failed)
        assertNotNull((refused as SourceOpenResult.Failed).error)
    }

    // --- fakes -----------------------------------------------------------------------------------

    /** A stream over zeros that counts reads, so boundedness is measurable. */
    private class CountingStream(private val total: Int) {
        var readCalls: Int = 0
            private set
        var position: Int = 0
            private set
        private val delegate: InputStream = ByteArrayInputStream(ByteArray(total))

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            val allowed = minOf(length, total - position)
            if (allowed <= 0) return -1
            val count = delegate.read(buffer, offset, allowed)
            if (count > 0) position += count
            return count
        }
    }
}
