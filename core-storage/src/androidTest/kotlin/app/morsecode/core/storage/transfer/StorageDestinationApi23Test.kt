package app.morsecode.core.storage.transfer

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** On-device app-private files and ContentResolver descriptors; no production runtime composition. */
@RunWith(AndroidJUnit4::class)
class StorageDestinationApi23Test {
    private lateinit var context: Context
    private lateinit var directory: File
    private val resources = mutableListOf<AppPrivatePartial>()

    @Before
    fun setUp() {
        assertEquals(23, Build.VERSION.SDK_INT)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "slice4s-destination")
        check(!directory.exists() || directory.deleteRecursively())
        Slice4sProviderControls.cleanup(context)
    }

    @After
    fun tearDown() {
        resources.forEach { it.close() }
        Slice4sProviderControls.cleanup(context)
        check(!directory.exists() || directory.deleteRecursively())
    }

    private fun bytes(size: Int): ByteArray = ByteArray(size) { ((it * 19 + 3) and 255).toByte() }
    private fun digest(data: ByteArray): Sha256Digest = Sha256Accumulator().update(data).digest()
    private fun identity(name: String): PartialIdentity = PartialIdentity("app_private:slice4s-$name")

    private fun partial(store: AppPrivatePartialStore, identity: PartialIdentity): AppPrivatePartial =
        store.open(identity).also(resources::add)

    private class RecordingCheckpoint : CheckpointSink {
        var confirmed = 0L
        var writes = 0
        override fun confirmedFrontier(transferId: TransferId): Long = confirmed
        override fun persist(transferId: TransferId, offset: Long, durability: FlushDurability): PersistOutcome {
            writes++
            confirmed = offset
            return PersistOutcome.Persisted
        }
    }

    /** Injects a bounded short write or ENOSPC after real bytes reached the private file. */
    private class FaultingPartial(private val real: AppPrivatePartial) : PartialSink {
        var fault: String? = null
        override fun length(): Long = real.length()
        override fun writeAt(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): WriteOutcome {
            val selected = fault ?: return real.writeAt(offset, buffer, dataOffset, length)
            val count = minOf(length, 5)
            val physical = real.writeAt(offset, buffer, dataOffset, count)
            check(physical is WriteOutcome.Written && physical.bytes == count)
            return if (selected == "short") physical else throw IOException("ENOSPC slice4s private detail")
        }
        override fun flush(): FlushDurability = real.flush()
        override fun truncateTo(offset: Long): TruncateOutcome = real.truncateTo(offset)
    }

    @Test(timeout = 15000)
    fun appPrivateSequentialWritesForceThenFileDescriptorSyncCloseAndReopen() {
        val data = bytes(3_217)
        val store = AppPrivatePartialStore(directory)
        val id = identity("sync")
        val real = partial(store, id)
        val checkpoint = RecordingCheckpoint()
        val session = (DestinationSession.open(
            transferId = TransferId("slice4s-sync"), identity = id, sink = real,
            declaredSizeBytes = data.size.toLong(), checkpointSink = checkpoint,
            verificationSource = real.verificationSource(), resources = listOf(real),
        ) as DestinationOpen.Opened).session
        try {
            var offset = 0
            for (length in listOf(1_024, 1_024, 1_169)) {
                val written = session.writeAt(offset.toLong(), data, offset, length)
                assertTrue(written is DestinationWrite.Written)
                assertEquals(FlushDurability.DurableFlushSupported, (written as DestinationWrite.Written).durability)
                offset += length
                assertNull(session.acknowledge(offset.toLong()))
            }
            assertEquals(data.size.toLong(), session.confirmedFrontier)
            assertEquals(3, checkpoint.writes)
        } finally {
            session.close()
            session.close()
        }
        assertEquals(1, real.closeCount)
        val file = store.fileFor(id)
        // The production partial above called FileChannel.force(true). This
        // separate real descriptor writes and explicitly calls FileDescriptor.sync.
        RandomAccessFile(file, "rw").use { descriptor ->
            descriptor.seek((data.size - 1).toLong())
            descriptor.write(data.last().toInt() xor 0xff)
            descriptor.seek((data.size - 1).toLong())
            descriptor.write(data.last().toInt())
            descriptor.fd.sync()
        }
        assertEquals(data.size.toLong(), file.length())
        assertArrayEquals(data, FileInputStream(file).use { it.readBytes() })
        val reopened = partial(store, id)
        assertEquals(data.size.toLong(), reopened.length())
        assertTrue(DestinationVerifier.verify(reopened.verificationSource(), data.size.toLong(), digest(data)) is VerifyResult.Matched)
        reopened.close()
        assertEquals(1, reopened.closeCount)
        Log.i("Morsec4S", "private_partial force=ok explicit_fd_sync=ok close_reopen_bytes_length=ok")
    }

    @Test(timeout = 15000)
    fun holesAndRewindsAreRefusedBeforeTouchingTheRealPartial() {
        val store = AppPrivatePartialStore(directory)
        val id = identity("sequence")
        val real = partial(store, id)
        val session = (DestinationSession.open(
            TransferId("slice4s-sequence"), id, real, declaredSizeBytes = 32L, resources = listOf(real),
        ) as DestinationOpen.Opened).session
        try {
            val data = bytes(8)
            assertEquals(SequentialRejection.HOLE, (session.writeAt(1L, data) as DestinationWrite.Rejected).reason)
            assertEquals(0L, real.length())
            assertTrue(session.writeAt(0L, data) is DestinationWrite.Written)
            assertEquals(SequentialRejection.REWIND, (session.writeAt(0L, data) as DestinationWrite.Rejected).reason)
            assertEquals(8L, real.length())
            assertEquals(0L, session.confirmedFrontier)
        } finally { session.close() }
    }

    @Test(timeout = 15000)
    fun shortAndSimulatedStorageFullWritesKeepConfirmedFrontierAndRequireTruncation() {
        for (mode in listOf("short", "full")) {
            val store = AppPrivatePartialStore(directory)
            val id = identity(mode)
            val real = partial(store, id)
            val faulting = FaultingPartial(real)
            val checkpoint = RecordingCheckpoint()
            val session = (DestinationSession.open(
                transferId = TransferId("slice4s-$mode"), identity = id, sink = faulting,
                declaredSizeBytes = 4_096L, checkpointSink = checkpoint, resources = listOf(real),
            ) as DestinationOpen.Opened).session
            try {
                val first = bytes(1_024)
                assertTrue(session.writeAt(0L, first) is DestinationWrite.Written)
                assertNull(session.acknowledge(1_024L))
                faulting.fault = mode
                val result = session.writeAt(1_024L, bytes(512))
                assertTrue(result is DestinationWrite.Failed)
                val failure = result as DestinationWrite.Failed
                if (mode == "full") {
                    assertEquals(TransferStorageErrorCategory.INSUFFICIENT_SPACE, failure.error.category)
                    assertFalse(failure.error.safeMessage().contains("private detail"))
                } else assertEquals(TransferStorageErrorCategory.IO, failure.error.category)
                assertEquals(1_024L, session.expectedFrontier)
                assertEquals(1_024L, session.confirmedFrontier)
                assertEquals(1_024L, checkpoint.confirmed)
                assertEquals(1, checkpoint.writes)
                assertEquals(1_029L, real.length())
                faulting.fault = null
                assertEquals(SequentialRejection.OVERLAP, (session.writeAt(1_024L, bytes(8)) as DestinationWrite.Rejected).reason)
                assertEquals(TruncateOutcome.Truncated(1_024L), session.truncateTo(1_024L))
                assertEquals(FlushDurability.DurableFlushSupported, real.flush())
            } finally { session.close() }
            val reopened = partial(store, id)
            assertEquals(1_024L, reopened.length())
            assertArrayEquals(bytes(1_024), FileInputStream(store.fileFor(id)).use { it.readBytes() })
            reopened.close()
        }
    }

    @Test(timeout = 15000)
    fun verificationAfterReopenIsRequiredBeforeTestPublicationAndDuplicateCommitIsRefused() {
        val data = bytes(2_123)
        val expected = digest(data)
        val store = AppPrivatePartialStore(directory)
        val id = identity("publish")
        val original = partial(store, id)
        assertTrue(original.writeAt(0L, data, 0, data.size) is WriteOutcome.Written)
        assertEquals(FlushDurability.DurableFlushSupported, original.flush())
        original.close()
        val reopened = partial(store, id)
        val published = File(directory, "published.bin")
        var calls = 0
        val session = (DestinationSession.open(
            transferId = TransferId("slice4s-publish"), identity = id, sink = reopened,
            declaredSizeBytes = data.size.toLong(), verificationSource = reopened.verificationSource(),
            initialConfirmedBytes = data.size.toLong(), retainedExpectedSha256 = expected,
            publisher = DestinationPublisher { observed ->
                assertEquals(expected, observed)
                calls++
                store.fileFor(id).copyTo(published, overwrite = false)
                PublicationOutcome.Published
            },
            resources = listOf(reopened),
        ) as DestinationOpen.Opened).session
        try {
            assertTrue(session.commit() is CommitResult.Refused)
            assertEquals(0, calls)
            assertFalse(published.exists())
            val verification = session.verify(expected)
            assertTrue(verification is SessionVerification.Verified)
            assertTrue((verification as SessionVerification.Verified).matchedExpected)
            assertEquals(data.size.toLong(), verification.bytes)
            assertEquals(CommitResult.Committed, session.commit())
            assertArrayEquals(data, FileInputStream(published).use { it.readBytes() })
            assertEquals(CommitResult.Duplicate, session.commit())
            assertEquals(1, calls)
            assertTrue(session.abort() is AbortResult.Refused)
        } finally { session.close() }
    }

    @Test(timeout = 15000)
    fun mismatchedDigestAfterReopenNeverPublishes() {
        val data = bytes(777)
        val damaged = data.copyOf().apply { this[10] = (this[10].toInt() xor 1).toByte() }
        val store = AppPrivatePartialStore(directory)
        val id = identity("mismatch")
        val first = partial(store, id)
        assertTrue(first.writeAt(0L, damaged, 0, damaged.size) is WriteOutcome.Written)
        assertEquals(FlushDurability.DurableFlushSupported, first.flush())
        first.close()
        val reopened = partial(store, id)
        var calls = 0
        val session = (DestinationSession.open(
            TransferId("slice4s-mismatch"), id, reopened, declaredSizeBytes = data.size.toLong(),
            verificationSource = reopened.verificationSource(), initialConfirmedBytes = data.size.toLong(),
            retainedExpectedSha256 = digest(data),
            publisher = DestinationPublisher { calls++; PublicationOutcome.Published }, resources = listOf(reopened),
        ) as DestinationOpen.Opened).session
        try {
            assertTrue(session.verify(digest(data)) is SessionVerification.Mismatched)
            assertTrue(session.commit() is CommitResult.Refused)
            assertEquals(0, calls)
        } finally { session.close() }
    }

    @Test(timeout = 15000)
    fun abortClosesAndDeletesThePrivatePartialExactlyOnce() {
        val store = AppPrivatePartialStore(directory)
        val id = identity("abort")
        val real = partial(store, id)
        var cleanups = 0
        val session = (DestinationSession.open(
            TransferId("slice4s-abort"), id, real, declaredSizeBytes = 64L,
            abortAction = {
                cleanups++
                real.close()
                if (store.delete(id)) null else TransferStorageError.Io("delete")
            }, resources = listOf(real),
        ) as DestinationOpen.Opened).session
        assertTrue(session.writeAt(0L, bytes(12)) is DestinationWrite.Written)
        assertTrue(store.exists(id))
        assertEquals(AbortResult.Aborted, session.abort())
        assertEquals(AbortResult.AlreadyAborted, session.abort())
        session.close()
        session.close()
        assertEquals(1, cleanups)
        assertEquals(1, real.closeCount)
        assertEquals(1, session.closeCount)
        assertFalse(store.exists(id))
    }

    @Test(timeout = 15000)
    fun providerWriteHandleSyncClosesOnceAndReopensThroughTheRealResolver() {
        val data = bytes(3_059)
        val uri = Slice4sProviderControls.seed(context, ByteArray(0), pipe = false, reportedSize = 0L)
        val gateway = DocumentsContractSafGateway(context.contentResolver)
        val opened = gateway.openWrite(uri.toString()) as SafOpen.Opened
        val handle = opened.handle as SafWriteHandle
        try {
            handle.write(data, 0, 1_000)
            handle.write(data, 1_000, data.size - 1_000)
            // The production handle invokes the real FileDescriptor.sync(), but
            // reports the provider guarantee as unknown rather than fsync-grade.
            assertEquals(FlushDurability.FlushAttemptedGuaranteeUnknown, handle.flush())
        } finally {
            handle.close()
            handle.close()
        }
        assertEquals(1, handle.closeCount)
        assertArrayEquals(data, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(data.size.toLong(), Slice4sProviderControls.row!!.file.length())
        Log.i("Morsec4S", "provider_fd_sync=ok guarantee=unknown close_reopen_bytes_length=ok")
    }

    @Test(timeout = 15000)
    fun providerWritePermissionFailureIsTypedAndRedacted() {
        val uri = Slice4sProviderControls.seed(context, ByteArray(0), pipe = false, reportedSize = 0L)
        Slice4sProviderControls.denyWrites = true
        val result = DocumentsContractSafGateway(context.contentResolver).openWrite(uri.toString())
        assertTrue(result is SafOpen.Refused)
        val error = (result as SafOpen.Refused).error
        assertEquals(TransferStorageErrorCategory.PERMISSION_REVOKED, error.category)
        assertNull(error.diagnostic)
        assertFalse(error.safeMessage().contains("private provider detail"))
        assertEquals(1, Slice4sProviderControls.writeAttempts.get())
    }
}
