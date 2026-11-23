package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Controlled streams exercise faults that a real API 23 descriptor cannot reliably reproduce. */
class TypedSourceReaderTest {
    private val secret = "content://private/secret/photo.jpg: provider detail"

    private class ControlledStream(
        private val steps: List<(ByteArray, Int, Int) -> Int>,
        private val failClose: Boolean = false,
    ) : InputStream() {
        var calls = 0
        val closes = AtomicInteger()

        override fun read(): Int = error("the adapter must use bounded reads")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val step = steps.getOrElse(calls) { { _, _, _ -> -1 } }
            calls++
            return step(buffer, offset, length)
        }

        override fun close() {
            closes.incrementAndGet()
            if (failClose) throw IOException("secret close failure")
        }
    }

    private fun reader(stream: ControlledStream): TypedSourceReader =
        TypedSourceReader(StreamSourceHandle(stream, stream, openedAtOffset = 0L))

    private fun failure(result: SourceReadResult): TransferStorageError {
        assertTrue("expected a typed failure, got $result", result is SourceReadResult.Failure)
        return (result as SourceReadResult.Failure).error
    }

    private fun assertNoSecret(error: TransferStorageError) {
        assertNull(error.diagnostic)
        assertFalse(error.safeMessage().contains(secret))
        assertFalse(error.toString().contains(secret))
        assertFalse(SourceReadResult.Failure(error).toString().contains(secret))
    }

    @Test
    fun successfulReadAndEofAreTypedAndCloseExactlyOnce() {
        val stream = ControlledStream(listOf(
            { buffer, offset, _ -> buffer[offset] = 7; buffer[offset + 1] = 8; 2 },
            { _, _, _ -> -1 },
        ))
        val reader = reader(stream)
        val buffer = ByteArray(8)
        assertEquals(SourceReadResult.Data(2), reader.read(buffer, 3, 4))
        assertEquals(7, buffer[3].toInt())
        assertEquals(8, buffer[4].toInt())
        assertEquals(SourceReadResult.EndOfSource, reader.read(buffer, 0, 8))
        assertEquals(SourceReadResult.EndOfSource, reader.read(buffer, 0, 8))
        reader.close()
        reader.close()
        assertEquals(2, stream.calls)
        assertEquals(1, stream.closes.get())
        assertEquals("TypedSourceReader([redacted])", reader.toString())
    }

    @Test
    fun providerIoFailureAfterSuccessfulReadIsTerminalClassifiedAndRedacted() {
        val stream = ControlledStream(listOf(
            { buffer, offset, _ -> buffer[offset] = 42; 1 },
            { _, _, _ -> throw IOException(secret) },
        ))
        val reader = reader(stream)
        val buffer = ByteArray(4)
        assertEquals(SourceReadResult.Data(1), reader.read(buffer, 0, 4))
        val failed = reader.read(buffer, 0, 4)
        val error = failure(failed)
        assertEquals(TransferStorageErrorCategory.IO, error.category)
        assertNoSecret(error)
        assertEquals(failed, reader.read(buffer, 0, 4))
        reader.close()
        assertEquals("no retry on a potentially advanced descriptor", 2, stream.calls)
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun providerRuntimeFailureAfterSuccessfulReadIsRedacted() {
        val stream = ControlledStream(listOf(
            { buffer, offset, _ -> buffer[offset] = 9; 1 },
            { _, _, _ -> throw IllegalStateException(secret) },
        ))
        val reader = reader(stream)
        assertEquals(SourceReadResult.Data(1), reader.read(ByteArray(4), 0, 4))
        val error = failure(reader.read(ByteArray(4), 0, 4))
        assertEquals(TransferStorageErrorCategory.PROVIDER, error.category)
        assertNoSecret(error)
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun revocationAndStorageFullUseTheSharedClassifierWithoutLeakage() {
        val cases = listOf(
            SecurityException(secret) to TransferStorageErrorCategory.PERMISSION_REVOKED,
            IOException("ENOSPC $secret") to TransferStorageErrorCategory.INSUFFICIENT_SPACE,
            IllegalStateException(secret) to TransferStorageErrorCategory.PROVIDER,
            IOException(secret) to TransferStorageErrorCategory.IO,
        )
        for ((thrown, category) in cases) {
            val stream = ControlledStream(listOf({ _, _, _ -> throw thrown }))
            val error = failure(reader(stream).read(ByteArray(4), 0, 4))
            assertEquals(category, error.category)
            assertNoSecret(error)
            assertEquals(1, stream.closes.get())
        }
    }

    @Test
    fun cancellationExceptionRemainsCancellationAndClosesTheHandle() {
        val stream = ControlledStream(listOf(
            { buffer, offset, _ -> buffer[offset] = 1; 1 },
            { _, _, _ -> throw CancellationException(secret) },
        ))
        val reader = reader(stream)
        assertEquals(SourceReadResult.Data(1), reader.read(ByteArray(4), 0, 4))
        assertEquals(TransferStorageError.Cancelled, failure(reader.read(ByteArray(4), 0, 4)))
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun wrappedCancellationAndInterruptedExceptionAreNotIoFailures() {
        val wrapped = ControlledStream(listOf({ _, _, _ ->
            throw IllegalStateException(secret, CancellationException(secret))
        }))
        assertEquals(TransferStorageError.Cancelled, failure(reader(wrapped).read(ByteArray(2), 0, 2)))
        assertEquals(1, wrapped.closes.get())

        val interrupted = ControlledStream(listOf({ _, _, _ -> throw InterruptedException(secret) }))
        try {
            assertEquals(TransferStorageError.Cancelled, failure(reader(interrupted).read(ByteArray(2), 0, 2)))
            assertTrue("the interruption flag is restored", Thread.currentThread().isInterrupted)
            assertEquals(1, interrupted.closes.get())
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun interruptedIoRemainsCancellationEvenWhenTheThreadFlagWasCleared() {
        val stream = ControlledStream(listOf({ _, _, _ -> throw InterruptedIOException(secret) }))
        val error = failure(reader(stream).read(ByteArray(2), 0, 2))
        assertEquals(TransferStorageError.Cancelled, error)
        assertNoSecret(error)
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun invalidRangesAreRefusedBeforeTheProviderIsCalled() {
        val stream = ControlledStream(listOf({ buffer, offset, _ -> buffer[offset] = 5; 1 }))
        val reader = reader(stream)
        val buffer = ByteArray(4)
        val invalid = listOf(-1 to 1, 0 to -1, 0 to 0, 5 to 1, 3 to 2, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)
        for ((offset, length) in invalid) {
            val error = failure(reader.read(buffer, offset, length))
            assertEquals("invalid_read_range", (error as TransferStorageError.StateConflict).reason)
        }
        assertEquals(0, stream.calls)
        assertEquals(0, stream.closes.get())
        assertEquals(SourceReadResult.Data(1), reader.read(buffer, 0, 4))
        reader.close()
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun impossibleCountsAndZeroProgressFailWithoutRetrying() {
        for (count in listOf(-2, Int.MIN_VALUE, 0, 5, Int.MAX_VALUE)) {
            val stream = ControlledStream(listOf({ _, _, _ -> count }))
            val reader = reader(stream)
            val first = reader.read(ByteArray(4), 0, 4)
            val error = failure(first)
            assertEquals(TransferStorageErrorCategory.PROVIDER, error.category)
            if (count == 0) assertTrue(error is TransferStorageError.ZeroProgress)
            assertEquals(first, reader.read(ByteArray(4), 0, 4))
            assertEquals("no provider retry after an impossible result", 1, stream.calls)
            assertEquals(1, stream.closes.get())
        }
    }

    @Test
    fun closeIsIdempotentEvenIfTheUnderlyingCloseThrows() {
        val stream = ControlledStream(emptyList(), failClose = true)
        val reader = reader(stream)
        reader.close()
        reader.close()
        assertEquals(1, stream.closes.get())
        val error = failure(reader.read(ByteArray(2), 0, 2)) as TransferStorageError.StateConflict
        assertEquals("source_closed", error.reason)
        assertFalse(error.toString().contains("secret"))
    }

    @Test(timeout = 10_000L)
    fun cancellingAnInFlightReadClosesOnceAndDoesNotReportGenericIo() {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val stream = ControlledStream(listOf({ _, _, _ ->
            entered.countDown()
            resume.await()
            throw IOException(secret)
        }))
        val reader = reader(stream)
        val result = AtomicReference<SourceReadResult>()
        val worker = Thread { result.set(reader.read(ByteArray(8), 0, 8)) }
        worker.start()
        entered.await()
        reader.close()
        resume.countDown()
        worker.join()
        assertEquals(TransferStorageError.Cancelled, failure(result.get()))
        reader.close()
        assertEquals(1, stream.closes.get())
    }

    @Test(timeout = 10_000L)
    fun concurrentCloseStillClosesTheUnderlyingHandleOnce() {
        val stream = ControlledStream(emptyList())
        val reader = reader(stream)
        val start = CountDownLatch(1)
        val threads = List(8) { Thread { start.await(); reader.close() } }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun callerBufferIsNotRetainedByTheReaderOrItsResults() {
        val stream = ControlledStream(listOf({ buffer, offset, _ -> buffer[offset] = 33; 1 }))
        val reader = reader(stream)
        val buffer = ByteArray(128)
        val result = reader.read(buffer, 4, 10)
        assertEquals(SourceReadResult.Data(1), result)
        for (field in reader.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            assertFalse("field ${field.name} retained the caller buffer", field.get(reader) === buffer)
            assertFalse("the adapter must not own a byte-array field", field.type == ByteArray::class.java)
        }
        assertFalse(result.toString().contains(buffer.toString()))
        reader.close()
    }

    @Test
    fun typedOpenRedactsLegacyOpenDiagnosticsAndExceptionMessages() {
        val source = TestSource { SourceOpenResult.Failed(TransferStorageError.Io(secret, secret)) }
        val opened = TypedSourceReader.openAt(source, 0L) as TypedSourceOpen.Failed
        assertEquals(TransferStorageErrorCategory.IO, opened.error.category)
        assertNoSecret(opened.error)
        assertFalse(opened.toString().contains(secret))

        val throws = TestSource { throw SecurityException(secret) }
        val revoked = TypedSourceReader.openAt(throws, 0L) as TypedSourceOpen.Failed
        assertEquals(TransferStorageErrorCategory.PERMISSION_REVOKED, revoked.error.category)
        assertNoSecret(revoked.error)

        val cancelled = TestSource { throw CancellationException(secret) }
        assertEquals(TransferStorageError.Cancelled, (TypedSourceReader.openAt(cancelled, 0L) as TypedSourceOpen.Failed).error)
    }

    @Test
    fun typedOpenRejectsWrongOffsetAndClosesTheUnusableHandle() {
        val stream = ControlledStream(emptyList())
        val source = TestSource { SourceOpenResult.Opened(StreamSourceHandle(stream, stream, 0L), 1L) }
        val result = TypedSourceReader.openAt(source, 0L) as TypedSourceOpen.Failed
        assertEquals("source_offset_mismatch", (result.error as TransferStorageError.StateConflict).reason)
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun nonzeroOpenRequiresAnAuthorizationCarryingTheExpectedDigest() {
        val stream = ControlledStream(emptyList())
        val source = TestSource { SourceOpenResult.Opened(StreamSourceHandle(stream, stream, it), it) }
        val refused = TypedSourceReader.openAt(source, 2L) as TypedSourceOpen.Failed
        assertEquals("resume_authorization_required", (refused.error as TransferStorageError.StateConflict).reason)
        assertEquals(0, source.openCalls)
        assertEquals(0, source.fingerprintCalls)

        val unknown = SourceFingerprint.UNKNOWN
        assertTrue(
            SourceResumePolicy.authorize(unknown, SourceFingerprintResult.Available(unknown), null) is SourceResumeDecision.Refused,
        )
        val authorization = SourceResumePolicy.authorize(
            unknown,
            SourceFingerprintResult.Available(unknown),
            Sha256Digest(ByteArray(32)),
        ) as SourceResumeDecision.Ready
        val opened = TypedSourceReader.openAt(source, 2L, authorization) as TypedSourceOpen.Opened
        assertEquals(2L, opened.offset)
        assertEquals(1, source.fingerprintCalls)
        assertEquals(1, source.openCalls)
        opened.reader.close()
        assertEquals(1, stream.closes.get())
    }

    @Test
    fun aSourceChangedAfterAuthorizationIsRefusedBeforeOpening() {
        val stored = SourceFingerprint(sizeBytes = 10L, lastModifiedEpochMillis = 100L, contentId = "id")
        val authorization = SourceResumePolicy.authorize(
            stored,
            SourceFingerprintResult.Available(stored),
            Sha256Digest(ByteArray(32)),
        ) as SourceResumeDecision.Ready
        val changed = SourceFingerprintResult.Available(stored.copy(sizeBytes = 11L))
        val source = TestSource(changed) { error("a changed source must not be opened") }
        val refused = TypedSourceReader.openAt(source, 2L, authorization) as TypedSourceOpen.Failed
        assertEquals("source_changed_restart_required", (refused.error as TransferStorageError.StateConflict).reason)
        assertEquals(1, source.fingerprintCalls)
        assertEquals(0, source.openCalls)
    }

    @Test
    fun cancellationReportedByFingerprintStopsBeforeOpening() {
        val stored = SourceFingerprint.UNKNOWN
        val authorization = SourceResumePolicy.authorize(
            stored, SourceFingerprintResult.Available(stored), Sha256Digest(ByteArray(32)),
        ) as SourceResumeDecision.Ready
        val source = TestSource(SourceFingerprintResult.Unavailable(TransferStorageError.Cancelled)) {
            error("a cancelled fingerprint must not be opened")
        }
        val refused = TypedSourceReader.openAt(source, 2L, authorization) as TypedSourceOpen.Failed
        assertEquals(TransferStorageError.Cancelled, refused.error)
        assertEquals(1, source.fingerprintCalls)
        assertEquals(0, source.openCalls)
    }

    private class TestSource(
        private val fingerprintValue: SourceFingerprintResult = SourceFingerprintResult.Available(SourceFingerprint.UNKNOWN),
        private val open: (Long) -> SourceOpenResult,
    ) : TransferSource {
        var openCalls: Int = 0
            private set
        var fingerprintCalls: Int = 0
            private set
        override val key = TransferSourceKey("source")
        override val uri: Uri get() = error("unused")
        override val displayName: String get() = error("unused")
        override val relativePath = RelativeTransferPath("payload.bin")
        override val mimeType: String get() = error("unused")
        override val sizeBytes: Long get() = -1L
        override val lastModifiedEpochMillis: Long? get() = null
        override val isSeekable: Boolean get() = false
        override fun fingerprint(): SourceFingerprintResult {
            fingerprintCalls++
            return fingerprintValue
        }
        override fun openAt(offset: Long): SourceOpenResult {
            openCalls++
            return open(offset)
        }
    }
}
