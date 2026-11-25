package app.morsecode.core.storage.transfer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.protocol.FramePayload
import app.morsecode.core.transfer.protocol.FramePayloads
import app.morsecode.core.transfer.protocol.FrameType
import app.morsecode.core.transfer.protocol.PayloadResult
import app.morsecode.core.transfer.runtime.PortResult
import app.morsecode.core.transfer.runtime.RuntimeReadResult
import app.morsecode.core.transfer.runtime.RuntimeTypedReader
import app.morsecode.core.transfer.runtime.SingleFileObserver
import app.morsecode.core.transfer.runtime.SingleFileResult
import app.morsecode.core.transfer.runtime.SingleFileRole
import app.morsecode.core.transfer.runtime.SingleFileRuntime
import app.morsecode.core.transfer.runtime.SingleFileSnapshotStore
import app.morsecode.core.transfer.runtime.SingleFileSource
import app.morsecode.core.transfer.runtime.SingleFileTransport
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test-only bridge: no Android provider, production adapter, or runtime call site is installed. */
class SingleFileTypedReaderRuntimeTest {
    private class ControlledStream(bytes: ByteArray, private val failure: String? = null) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var calls = 0
        var closes = 0
        override fun read(): Int = error("unbounded read is forbidden")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            calls++
            return when (failure) {
                "zero" -> 0
                "io" -> throw IOException("content://private/secret")
                "cancel" -> throw CancellationException("content://private/secret")
                else -> delegate.read(buffer, offset, minOf(length, 3))
            }
        }
        override fun close() { closes++; delegate.close() }
    }

    /** The runtime gets a typed-reader facade only. It never gets the raw handle. */
    private class TestTypedSource(private val stream: ControlledStream) : SingleFileSource {
        override fun openFreshReader(): PortResult<RuntimeTypedReader> {
            val typed = TypedSourceReader(StreamSourceHandle(stream, stream, 0L))
            return PortResult.Success(object : RuntimeTypedReader {
                override fun read(buffer: ByteArray, offset: Int, length: Int): RuntimeReadResult =
                    when (val result = typed.read(buffer, offset, length)) {
                        is SourceReadResult.Data -> RuntimeReadResult.Data(result.count)
                        SourceReadResult.EndOfSource -> RuntimeReadResult.EndOfSource
                        is SourceReadResult.Failure -> if (result.error.category == TransferStorageErrorCategory.CANCELLED) {
                            RuntimeReadResult.Cancelled
                        } else {
                            RuntimeReadResult.Failed(TransferError.SourceUnavailable("typed source read failed"))
                        }
                    }
                override fun close() = typed.close()
            })
        }
    }

    private class TestStore : SingleFileSnapshotStore {
        var row: TransferSnapshot? = null
        override fun loadSnapshot(transferId: TransferId): PortResult<TransferSnapshot?> = PortResult.Success(row)
        override fun saveTransition(previous: TransferSnapshot?, current: TransferSnapshot): PortResult<Unit> {
            if (row != previous || (previous != null && current.snapshotVersion != previous.snapshotVersion + 1L)) {
                return PortResult.Failed(TransferError.PersistenceConflict("test_cas"))
            }
            row = current
            return PortResult.Success(Unit)
        }
        override fun saveCheckpoint(transferId: TransferId, offset: ConfirmedOffset): PortResult<Unit> {
            val previous = row ?: return PortResult.Failed(TransferError.PersistenceConflict("missing"))
            if (offset.value > previous.optimisticBytes) return PortResult.Failed(TransferError.PersistenceConflict("frontier"))
            row = previous.copy(confirmedBytes = offset.value)
            return PortResult.Success(Unit)
        }
    }

    private class TestTransport : SingleFileTransport {
        val frames = mutableListOf<ByteArray>()
        override fun send(frame: ByteArray): PortResult<Unit> {
            frames += frame.copyOf()
            return PortResult.Success(Unit)
        }
    }

    private object TestObserver : SingleFileObserver {
        override fun notify(state: TransferState, snapshotVersion: Long): PortResult<Unit> = PortResult.Success(Unit)
        override fun schedule(): PortResult<Unit> = PortResult.Success(Unit)
    }

    private fun sender(content: ByteArray, stream: ControlledStream): Pair<SingleFileRuntime, TestTransport> {
        val session = SessionId("typed-session")
        val transfer = TransferId("typed-transfer")
        val descriptor = TransferFileDescriptor(
            fileId = FileId("typed-file"),
            displayName = "typed.bin",
            relativePath = RelativeTransferPath("typed.bin"),
            mimeType = "application/octet-stream",
            totalBytes = content.size.toLong(),
            lastModifiedEpochMillis = null,
            isFolderArchive = false,
            expectedSha256 = Sha256Accumulator().update(content).digest(),
            chunkSize = ChunkSize.MIN,
            protocolVersion = ProtocolVersion.CURRENT,
        )
        val transport = TestTransport()
        val runtime = SingleFileRuntime(
            TransferCommand.Enqueue(session, transfer, null, BatchId("typed-batch"), SessionDirection.OUTBOUND, descriptor),
            SingleFileRole.Sender(TestTypedSource(stream)), TestStore(), transport, TestObserver,
        )
        assertEquals(SingleFileResult.Advanced, runtime.start())
        val accept = FrameCodec.handshakeAccept(session, transfer, chunkSize = ChunkSize.MIN, resumeSupported = false)
        assertEquals(SingleFileResult.Advanced, runtime.receive(FrameCodec.encode(accept)))
        return runtime to transport
    }

    @Test
    fun runtimeConsumesTheActualTypedSourceReaderAndClosesOnceAtDeclaredEnd() {
        val content = ByteArray(12) { it.toByte() }
        val stream = ControlledStream(content)
        val (runtime, transport) = sender(content, stream)
        assertEquals(SingleFileResult.Advanced, runtime.sendNextChunk())
        val frames = transport.frames.map { (FrameCodec.decodeSingle(it) as FrameDecodeResult.Success).frame }
        assertEquals(listOf(FrameType.HANDSHAKE, FrameType.FILE_METADATA, FrameType.DATA_CHUNK), frames.map { it.type })
        val payload = (FramePayloads.parse(frames.last()) as PayloadResult.Success).payload as FramePayload.DataChunk
        assertArrayEquals(content, payload.bytes)
        assertEquals(5, stream.calls) // four partial reads and one typed EOF probe
        assertEquals(1, stream.closes)
    }

    @Test
    fun actualTypedReaderCancellationRemainsCancellationRatherThanIoFailure() {
        val content = ByteArray(12) { it.toByte() }
        val stream = ControlledStream(content, "cancel")
        val (runtime, transport) = sender(content, stream)
        val result = runtime.sendNextChunk() as SingleFileResult.Halted
        assertEquals(TransferError.CancelledLocally, result.error)
        assertEquals(null, result.reportedEvent)
        assertEquals(1, stream.calls)
        assertEquals(1, stream.closes)
        assertFalse(result.toString().contains("content://private"))
        val types = transport.frames.map { (FrameCodec.decodeSingle(it) as FrameDecodeResult.Success).frame.type }
        assertFalse(types.contains(FrameType.DATA_CHUNK))
    }

    @Test
    fun typedZeroProgressAndProviderFailureAreTerminalAndDoNotSendBytesOrLeakDiagnostics() {
        for (failure in listOf("zero", "io")) {
            val content = ByteArray(12) { it.toByte() }
            val stream = ControlledStream(content, failure)
            val (runtime, transport) = sender(content, stream)
            val result = runtime.sendNextChunk() as SingleFileResult.Halted
            assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
            assertEquals(1, stream.calls)
            assertEquals(1, stream.closes)
            assertFalse(result.toString().contains("content://private"))
            val types = transport.frames.map { (FrameCodec.decodeSingle(it) as FrameDecodeResult.Success).frame.type }
            assertFalse(types.contains(FrameType.DATA_CHUNK))
        }
    }
}
