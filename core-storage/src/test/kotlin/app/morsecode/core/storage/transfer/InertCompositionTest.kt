package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.File
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
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
 * The whole storage bridge, end to end, on real files with a real fsync.
 *
 * This is the §9 shape:
 *
 *   source adapter -> bounded chunk buffer -> destination adapter -> flush
 *                 -> digest verification -> commit
 *
 * It is a test fixture, not a runtime. Nothing in `src/main` composes these objects, no app entry
 * point reaches this loop, and there is no socket anywhere in it. What the test proves is that the
 * pieces *fit together*: that the offsets the source hands out are the offsets the sink accepts,
 * that the flush the sink reports is backed by `force(true)` on a real file descriptor, and that the
 * digest taken over the destination equals the digest the source produced.
 *
 * The source is the shipped file adapter, exercised under Robolectric; the sink is a small file-backed
 * test adapter. What is on trial here is the typed-read and destination seam between the two ports.
 *
 * The transfer is deliberately interrupted and resumed once. A composition that only works for a
 * transfer that never breaks would not be evidence of much.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InertCompositionTest {

    private val transferId = TransferId("transfer-compose")
    private val identity = PartialIdentity.of(transferId, DestinationStrategy.APP_PRIVATE)
    private val chunkBytes = 64 * 1024

    private lateinit var root: File
    private lateinit var staging: File
    private lateinit var sourceFile: File
    private lateinit var partialFile: File

    private fun seed(size: Int): ByteArray {
        root = Files.createTempDirectory("compose-src-").toFile()
        staging = Files.createTempDirectory("compose-part-").toFile()
        sourceFile = File(root, "payload.bin")
        partialFile = File(staging, "payload.part")
        val content = ByteArray(size) { (it * 31 + 7).toByte() }
        sourceFile.writeBytes(content)
        return content
    }

    private fun source(): FileBackedTransferSource = FileBackedTransferSource(
        key = TransferSourceKey("private:payload"),
        uri = android.net.Uri.parse("content://private/payload.bin"),
        displayName = "payload.bin",
        relativePath = RelativeTransferPath("payload.bin"),
        mimeType = "application/octet-stream",
        file = sourceFile,
        permissionGate = { true },
    )

    private fun sink(): FileSink = FileSink(partialFile)

    /** Copies [length] bytes from the source into the session, reusing one buffer. */
    private fun pump(
        session: DestinationSession,
        source: TypedSourceReader,
        buffer: ByteArray,
        fromOffset: Long,
        length: Long,
        onChunk: () -> Unit = {},
    ): Long {
        var offset = fromOffset
        val end = fromOffset + length
        while (offset < end) {
            val want = minOf(buffer.size.toLong(), end - offset).toInt()
            val read = when (val outcome = source.read(buffer, 0, want)) {
                is SourceReadResult.Data -> outcome.count
                SourceReadResult.EndOfSource -> error("source ended at $offset, expected $end")
                is SourceReadResult.Failure -> error("typed source failure: ${outcome.error}")
            }
            val written = session.writeAt(offset, buffer, 0, read)
            assertTrue("write at $offset failed: $written", written is DestinationWrite.Written)
            offset = (written as DestinationWrite.Written).frontier
            onChunk()
        }
        return offset
    }

    private fun openSession(sink: FileSink, size: Int, publisher: RecordingPublisher? = null): DestinationSession {
        val opened = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            declaredSizeBytes = size.toLong(),
            verificationSource = sink.verificationSource(),
            publisher = publisher,
            resources = listOf(sink),
        )
        assertTrue("expected an open session, got $opened", opened is DestinationOpen.Opened)
        return (opened as DestinationOpen.Opened).session
    }

    @Test(timeout = 120_000L)
    fun sourceBufferSinkFlushVerifyCommitProducesTheOriginalBytes() {
        val size = 300_000
        val expected = seed(size)
        val buffer = ByteArray(chunkBytes)
        val publisher = RecordingPublisher()
        val sink = sink()
        val session = openSession(sink, size, publisher)

        val handle = (TypedSourceReader.openAt(source(), 0L) as TypedSourceOpen.Opened).reader
        var chunks = 0
        try {
            val end = pump(session, handle, buffer, 0L, size.toLong()) { chunks++ }
            assertEquals(size.toLong(), end)
            // One buffer, reused: five chunks over a 300 KiB file, and the caller keeps ownership.
            assertEquals(5, chunks)
            assertEquals("nothing is confirmed before the runtime says so", 0L, session.confirmedFrontier)
            assertNull(session.acknowledge(end))
            assertEquals(end, session.confirmedFrontier)
        } finally {
            handle.close()
        }

        val digester = Sha256Digester()
        digester.update(expected, 0, expected.size)
        val expectedDigest = digester.digest()

        val verification = session.verify(expectedDigest)
        assertTrue("verification must match: $verification", verification is SessionVerification.Verified)
        assertTrue((verification as SessionVerification.Verified).matchedExpected)

        assertEquals(CommitResult.Committed, session.commit())
        assertEquals(DestinationSessionState.COMMITTED, session.currentState)
        assertEquals("the publisher is called exactly once", 1, publisher.calls)
        assertEquals("and only with the verified digest", expectedDigest, publisher.publishedDigest)
        assertTrue("a duplicate commit does not republish", session.commit() is CommitResult.Duplicate)
        assertEquals(1, publisher.calls)

        session.close()
        assertArrayEquals(expected, partialFile.readBytes())
    }

    @Test(timeout = 120_000L)
    fun anInterruptedTransferResumesAtTheConfirmedFrontierAndStillVerifies() {
        val size = 250_000
        val expected = seed(size)
        val buffer = ByteArray(chunkBytes)
        val originalFingerprint = (source().fingerprint() as SourceFingerprintResult.Available)
            .fingerprint.copy(lastModifiedEpochMillis = null)
        val expectedDigester = Sha256Digester()
        expectedDigester.update(expected, 0, expected.size)
        val expectedDigest = expectedDigester.digest()

        // First life: write 100 KiB, acknowledge it, then vanish without a flush or a close.
        val firstSink = sink()
        val firstSession = openSession(firstSink, size)
        val firstHandle = (TypedSourceReader.openAt(source(), 0L) as TypedSourceOpen.Opened).reader
        try {
            pump(firstSession, firstHandle, buffer, 0L, 100_000L)
            assertNull(firstSession.acknowledge(100_000L))
        } finally {
            firstHandle.close()
            // The descriptor is dropped without a flush: the checkpoint is what survives, not the file.
            firstSink.closeWithoutFlush()
        }

        // Second life: a fresh sink over the same bytes, and a session resumed at the confirmed
        // frontier rather than at zero.
        val resumedSink = sink()
        assertEquals(100_000L, resumedSink.length())
        val opened = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = resumedSink,
            declaredSizeBytes = size.toLong(),
            verificationSource = resumedSink.verificationSource(),
            initialConfirmedBytes = 100_000L,
            retainedExpectedSha256 = expectedDigest,
            publisher = RecordingPublisher(),
            resources = listOf(resumedSink),
        )
        assertTrue(opened is DestinationOpen.Opened)
        val session = (opened as DestinationOpen.Opened).session
        assertFalse("a crash after an acknowledged write needs no truncation", opened.requiresTruncation)
        assertEquals(100_000L, session.expectedFrontier)
        assertEquals(100_000L, session.confirmedFrontier)

        // With unavailable metadata, the original digest must be retained across restoration.
        val liveFingerprint = (source().fingerprint() as SourceFingerprintResult.Available)
            .fingerprint.copy(lastModifiedEpochMillis = null)
        val current = SourceFingerprintResult.Available(liveFingerprint)
        assertTrue(SourceResumePolicy.authorize(originalFingerprint, current, null) is SourceResumeDecision.Refused)
        val authorization = SourceResumePolicy.authorize(originalFingerprint, current, expectedDigest)
            as SourceResumeDecision.Ready
        assertEquals(FingerprintOutcome.MISSING_METADATA, authorization.fingerprintOutcome)

        // The source is reopened at the frontier too, rather than re-read from zero.
        val resumedSource = TypedSourceReader.openAt(source(), 100_000L, authorization) as TypedSourceOpen.Opened
        val handle = resumedSource.reader
        try {
            assertEquals(100_000L, resumedSource.offset)
            pump(session, handle, buffer, 100_000L, size - 100_000L)
            assertNull(session.acknowledge(size.toLong()))
        } finally {
            handle.close()
        }

        val verified = authorization.verify(session)
        assertTrue(verified is SessionVerification.Verified)
        assertTrue((verified as SessionVerification.Verified).matchedExpected)
        assertEquals(CommitResult.Committed, session.commit())
        session.close()

        assertArrayEquals(expected, partialFile.readBytes())
    }

    @Test(timeout = 120_000L)
    fun aCorruptedChunkFailsVerificationAndCannotCommit() {
        val size = 70_000
        val expected = seed(size)
        val buffer = ByteArray(chunkBytes)
        val publisher = RecordingPublisher()

        val sink = sink()
        val session = openSession(sink, size, publisher)

        val handle = (TypedSourceReader.openAt(source(), 0L) as TypedSourceOpen.Opened).reader
        try {
            pump(session, handle, buffer, 0L, 40_000L)
            // A flipped bit in transit: the destination keeps it, verification must refuse it.
            buffer[0] = (buffer[0] + 1).toByte()
            val written = session.writeAt(40_000L, buffer, 0, 1_000)
            assertTrue(written is DestinationWrite.Written)
            pump(session, handle, buffer, 41_000L, size - 41_000L)
        } finally {
            handle.close()
        }

        val digester = Sha256Digester()
        digester.update(expected, 0, expected.size)
        assertTrue(session.verify(digester.digest()) is SessionVerification.Mismatched)
        assertTrue(session.commit() is CommitResult.Refused)
        assertEquals("nothing was published", 0, publisher.calls)
        assertEquals(DestinationSessionState.OPEN, session.currentState)
        session.close()
    }

    @Test(timeout = 120_000L)
    fun sameSizeAndTimestampMutationIsCaughtByTheRetainedDigestBeforeCommit() {
        val size = 20_000
        val split = 8_192
        val original = seed(size)
        val stored = (source().fingerprint() as SourceFingerprintResult.Available).fingerprint
        val timestamp = sourceFile.lastModified()
        val expected = Sha256Digester().apply { update(original, 0, original.size) }.digest()

        val changed = original.copyOf()
        changed[15_000] = (changed[15_000].toInt() xor 1).toByte()
        sourceFile.writeBytes(changed)
        assertTrue("restore the original timestamp to model an undetectable metadata match", sourceFile.setLastModified(timestamp))
        val current = source().fingerprint()
        assertEquals(FingerprintOutcome.STRONG_MATCH, SourceChangePolicy.evaluate(stored, current))
        val authorization = SourceResumePolicy.authorize(stored, current, expected) as SourceResumeDecision.Ready

        // A persisted partial still contains the old prefix; the resumed tail is from the edit.
        partialFile.writeBytes(original.copyOfRange(0, split))
        val sink = sink()
        val publisher = RecordingPublisher()
        val opened = DestinationSession.open(
            transferId = transferId,
            identity = identity,
            sink = sink,
            declaredSizeBytes = size.toLong(),
            verificationSource = sink.verificationSource(),
            initialConfirmedBytes = split.toLong(),
            retainedExpectedSha256 = expected,
            publisher = publisher,
            resources = listOf(sink),
        ) as DestinationOpen.Opened
        val session = opened.session
        val reader = (TypedSourceReader.openAt(source(), split.toLong(), authorization) as TypedSourceOpen.Opened).reader
        try {
            pump(session, reader, ByteArray(chunkBytes), split.toLong(), (size - split).toLong())
            assertNull(session.acknowledge(size.toLong()))
        } finally {
            reader.close()
        }
        assertTrue(authorization.verify(session) is SessionVerification.Mismatched)
        assertTrue(session.commit() is CommitResult.Refused)
        // Neither a missing digest nor the edited file's digest can replace the value retained
        // when the resumed session opened.
        val editedDigest = Sha256Digester().apply { update(changed, 0, changed.size) }.digest()
        assertTrue(session.verify(null) is SessionVerification.Refused)
        assertTrue(session.verify(editedDigest) is SessionVerification.Refused)
        assertTrue(session.commit() is CommitResult.Refused)
        assertEquals(0, publisher.calls)
        session.close()
    }

    @Test(timeout = 120_000L)
    fun theCompositionHoldsNoTransportReference() {
        seed(16)
        val sink = sink()
        val session = openSession(sink, 16)
        try {
            val forbidden = setOf(
                "java.net.Socket",
                "java.net.ServerSocket",
                "java.nio.channels.SocketChannel",
                "java.nio.channels.ServerSocketChannel",
                "javax.net.ssl.SSLSocket",
            )
            for (subject in listOf(session, sink)) {
                var type: Class<*>? = subject.javaClass
                while (type != null && type != Any::class.java) {
                    for (field in type.declaredFields) {
                        field.isAccessible = true
                        val value = runCatching { field.get(subject) }.getOrNull() ?: continue
                        assertFalse(
                            "${subject.javaClass.simpleName}.${field.name} holds a ${value.javaClass.name}",
                            value.javaClass.name in forbidden,
                        )
                    }
                    type = type.superclass
                }
            }
        } finally {
            session.close()
        }
    }

    // --- adapters --------------------------------------------------------------------------------

    /** A positional file sink that really fsyncs. */
    private class FileSink(private val file: File) : PartialSink, Closeable {
        private val random = RandomAccessFile(file, "rw")
        private val channel: FileChannel = random.channel
        private var closed = false

        override fun length(): Long = channel.size()

        override fun writeAt(offset: Long, buffer: ByteArray, dataOffset: Int, length: Int): WriteOutcome {
            if (closed) return WriteOutcome.Failed(TransferStorageError.StateConflict("after_close"))
            return try {
                val source = ByteBuffer.wrap(buffer, dataOffset, length)
                var written = 0
                while (source.hasRemaining()) {
                    val count = channel.write(source, offset + written.toLong())
                    if (count <= 0) break
                    written += count
                }
                if (written == length) {
                    WriteOutcome.Written(written, channel.size())
                } else {
                    WriteOutcome.Failed(TransferStorageError.Io("write"))
                }
            } catch (e: java.io.IOException) {
                WriteOutcome.Failed(TransferStorageError.Io("write", e.message))
            }
        }

        override fun flush(): FlushDurability = try {
            channel.force(true)
            FlushDurability.DurableFlushSupported
        } catch (e: java.io.IOException) {
            FlushDurability.FlushFailed(TransferStorageError.Io("flush", e.message))
        }

        override fun truncateTo(offset: Long): TruncateOutcome = try {
            channel.truncate(offset)
            TruncateOutcome.Truncated(offset)
        } catch (e: java.io.IOException) {
            TruncateOutcome.Failed(TransferStorageError.Io("truncate", e.message))
        }

        fun verificationSource(): VerificationSource =
            VerificationSource { offset, buffer, dataOffset, length ->
                RandomAccessFile(file, "r").use { reader ->
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

        fun closeWithoutFlush() {
            if (closed) return
            closed = true
            runCatching { channel.close() }
            runCatching { random.close() }
        }

        override fun close() {
            if (closed) return
            flush()
            closeWithoutFlush()
        }
    }

    private class RecordingPublisher : DestinationPublisher {
        var calls: Int = 0
            private set
        var publishedDigest: Sha256Digest? = null
            private set

        override fun publish(verifiedDigest: Sha256Digest): PublicationOutcome {
            calls++
            publishedDigest = verifiedDigest
            return PublicationOutcome.Published
        }
    }
}
