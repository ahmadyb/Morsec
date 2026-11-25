package app.morsecode.core.transfer.runtime

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.protocol.FrameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal enum class FaultMode { FAIL, CANCEL, UNKNOWN_BEFORE, UNKNOWN_AFTER, THROW }

/** A Room-like in-memory CAS store: checkpointing changes the row but not its version. */
internal class MemoryStore(private val label: String, private val trace: MutableList<String>) : SingleFileSnapshotStore {
    var row: TransferSnapshot? = null
    val saved = mutableListOf<TransferSnapshot>()
    val transitions = mutableListOf<Pair<TransferSnapshot?, TransferSnapshot>>()
    var onSave: (TransferSnapshot) -> FaultMode? = { null }
    var onCheckpoint: (Long) -> FaultMode? = { null }
    var afterCheckpoint: (TransferSnapshot) -> TransferSnapshot = { it }
    var onLoad: () -> FaultMode? = { null }

    override fun loadSnapshot(transferId: TransferId): PortResult<TransferSnapshot?> {
        trace += "$label.load.${row?.snapshotVersion ?: 0}"
        return when (onLoad()) {
            FaultMode.FAIL -> PortResult.Failed(TransferError.PersistenceFailure("loaded", false))
            FaultMode.CANCEL -> PortResult.Cancelled
            FaultMode.UNKNOWN_BEFORE, FaultMode.UNKNOWN_AFTER -> PortResult.Indeterminate
            FaultMode.THROW -> throw IllegalStateException("private store exception")
            null -> PortResult.Success(row?.takeIf { it.transferId == transferId })
        }
    }

    override fun saveTransition(previous: TransferSnapshot?, current: TransferSnapshot): PortResult<Unit> {
        trace += "$label.save.${current.state}.v${current.snapshotVersion}.c${current.confirmedBytes}.o${current.optimisticBytes}.verified${current.verification?.allowsCommit == true}"
        if (previous != row || (previous != null && current.snapshotVersion != previous.snapshotVersion + 1L) ||
            (previous == null && current.snapshotVersion != TransferSnapshot.INITIAL_SNAPSHOT_VERSION) ||
            (previous != null && current.confirmedBytes < previous.confirmedBytes) || !current.isInternallyConsistent()
        ) return PortResult.Failed(TransferError.PersistenceConflict("cas_rejected"))
        val mode = onSave(current)
        if (mode == FaultMode.THROW) throw IllegalStateException("private store exception")
        if (mode == FaultMode.FAIL) return PortResult.Failed(TransferError.PersistenceFailure("saved", false))
        if (mode == FaultMode.CANCEL) return PortResult.Cancelled
        if (mode == FaultMode.UNKNOWN_BEFORE) return PortResult.Indeterminate
        row = current
        saved += current
        transitions += previous to current
        return if (mode == FaultMode.UNKNOWN_AFTER) PortResult.Indeterminate else PortResult.Success(Unit)
    }

    override fun saveCheckpoint(transferId: TransferId, offset: ConfirmedOffset): PortResult<Unit> {
        trace += "$label.checkpoint.${offset.value}"
        val previous = row ?: return PortResult.Failed(TransferError.PersistenceConflict("missing"))
        if (previous.transferId != transferId || offset.value < previous.confirmedBytes ||
            offset.value > previous.optimisticBytes || offset.value > previous.totalBytes
        ) return PortResult.Failed(TransferError.PersistenceConflict("bad_checkpoint"))
        val mode = onCheckpoint(offset.value)
        if (mode == FaultMode.THROW) throw IllegalStateException("private checkpoint exception")
        if (mode == FaultMode.FAIL) return PortResult.Failed(TransferError.PersistenceFailure("checkpointed", true))
        if (mode == FaultMode.CANCEL) return PortResult.Cancelled
        if (mode == FaultMode.UNKNOWN_BEFORE) return PortResult.Indeterminate
        row = afterCheckpoint(previous.copy(confirmedBytes = offset.value))
        return if (mode == FaultMode.UNKNOWN_AFTER) PortResult.Indeterminate else PortResult.Success(Unit)
    }
}

internal class MemoryReader(
    private val bytes: ByteArray,
    private val label: String,
    private val trace: MutableList<String>,
    private val maxRead: Int = Int.MAX_VALUE,
    private val overrideRead: ((Int) -> RuntimeReadResult?)? = null,
) : RuntimeTypedReader {
    var position = 0
    var calls = 0
    var closes = 0
    override fun read(buffer: ByteArray, offset: Int, length: Int): RuntimeReadResult {
        calls++
        trace += "$label.read.$length"
        overrideRead?.invoke(calls)?.let { return it }
        if (position == bytes.size) return RuntimeReadResult.EndOfSource
        val count = minOf(length, maxRead, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return RuntimeReadResult.Data(count)
    }
    override fun close() { closes++; trace += "$label.close" }
}

internal class MemorySource(
    private val bytes: ByteArray,
    private val trace: MutableList<String>,
    private val maxRead: Int = Int.MAX_VALUE,
) : SingleFileSource {
    var reader: MemoryReader? = null
    var mode: FaultMode? = null
    var overrideRead: ((Int) -> RuntimeReadResult?)? = null
    override fun openFreshReader(): PortResult<RuntimeTypedReader> {
        trace += "sender.source.open"
        return when (mode) {
            FaultMode.FAIL -> PortResult.Failed(TransferError.SourceUnavailable("private source diagnostic"))
            FaultMode.CANCEL -> PortResult.Cancelled
            FaultMode.UNKNOWN_BEFORE, FaultMode.UNKNOWN_AFTER -> PortResult.Indeterminate
            FaultMode.THROW -> throw IllegalStateException("private source exception")
            null -> PortResult.Success(MemoryReader(bytes, "sender.source", trace, maxRead, overrideRead).also { reader = it })
        }
    }
}

internal class MemoryDestination(private val trace: MutableList<String>) : SingleFileDestination {
    var bytes = ByteArray(0)
    var opened = false
    var published = false
    var commitCalls = 0
    var verificationReader: MemoryReader? = null
    var openMode: FaultMode? = null
    var writeMode: FaultMode? = null
    var verificationMode: FaultMode? = null
    var commitMode: FaultMode? = null
    var overrideVerificationRead: ((Int) -> RuntimeReadResult?)? = null
    var corruptOnWrite = false
    var partialBytesBeforeFailure = 0

    override fun openFresh(): PortResult<Unit> {
        trace += "receiver.destination.open"
        if (openMode == FaultMode.THROW) throw IllegalStateException("private open exception")
        if (openMode == FaultMode.FAIL) return PortResult.Failed(TransferError.DestinationUnavailable("private open"))
        if (openMode == FaultMode.CANCEL) return PortResult.Cancelled
        if (openMode == FaultMode.UNKNOWN_BEFORE) return PortResult.Indeterminate
        opened = true
        return if (openMode == FaultMode.UNKNOWN_AFTER) PortResult.Indeterminate else PortResult.Success(Unit)
    }

    override fun writeDurably(offset: Long, bytes: ByteArray): PortResult<Unit> {
        trace += "receiver.destination.write.$offset.${bytes.size}"
        if (!opened || offset != this.bytes.size.toLong() || published) {
            return PortResult.Failed(TransferError.DestinationUnavailable("sequential write refused"))
        }
        if (writeMode == FaultMode.THROW) throw IllegalStateException("private write exception")
        if (writeMode == FaultMode.FAIL) {
            this.bytes += bytes.copyOfRange(0, minOf(bytes.size, partialBytesBeforeFailure))
            return PortResult.Failed(TransferError.StorageFull(bytes.size.toLong()))
        }
        if (writeMode == FaultMode.CANCEL) return PortResult.Cancelled
        if (writeMode == FaultMode.UNKNOWN_BEFORE) return PortResult.Indeterminate
        val copy = bytes.copyOf()
        if (corruptOnWrite && copy.isNotEmpty()) copy[0] = (copy[0].toInt() xor 1).toByte()
        this.bytes += copy
        return if (writeMode == FaultMode.UNKNOWN_AFTER) PortResult.Indeterminate else PortResult.Success(Unit)
    }

    override fun openVerification(): PortResult<RuntimeTypedReader> {
        trace += "receiver.destination.verify.open"
        return when (verificationMode) {
            FaultMode.FAIL -> PortResult.Failed(TransferError.DestinationUnavailable("private verify"))
            FaultMode.CANCEL -> PortResult.Cancelled
            FaultMode.UNKNOWN_BEFORE, FaultMode.UNKNOWN_AFTER -> PortResult.Indeterminate
            FaultMode.THROW -> throw IllegalStateException("private verification exception")
            null -> PortResult.Success(
                MemoryReader(bytes.copyOf(), "receiver.destination.verify", trace, 37, overrideVerificationRead)
                    .also { verificationReader = it },
            )
        }
    }

    override fun commitVerified(): PortResult<Unit> {
        trace += "receiver.destination.commit"
        commitCalls++
        if (commitMode == FaultMode.THROW) throw IllegalStateException("private commit exception")
        if (commitMode == FaultMode.FAIL) return PortResult.Failed(TransferError.DestinationUnavailable("private commit"))
        if (commitMode == FaultMode.CANCEL) return PortResult.Cancelled
        if (commitMode == FaultMode.UNKNOWN_BEFORE) return PortResult.Indeterminate
        published = true
        return if (commitMode == FaultMode.UNKNOWN_AFTER) PortResult.Indeterminate else PortResult.Success(Unit)
    }
}

internal class MemoryTransport(private val label: String, private val trace: MutableList<String>) : SingleFileTransport {
    val frames = ArrayDeque<ByteArray>()
    var onSend: (FrameType) -> FaultMode? = { null }
    var callback: (() -> Unit)? = null
    override fun send(frame: ByteArray): PortResult<Unit> {
        val decoded = FrameCodec.decodeSingle(frame) as FrameDecodeResult.Success
        val type = decoded.frame.type
        trace += "$label.send.$type"
        callback?.invoke()
        return when (onSend(type)) {
            FaultMode.FAIL -> PortResult.Failed(TransferError.TransportDisconnected("private transport"))
            FaultMode.CANCEL -> PortResult.Cancelled
            FaultMode.UNKNOWN_BEFORE -> PortResult.Indeterminate
            FaultMode.UNKNOWN_AFTER -> { frames.addLast(frame.copyOf()); PortResult.Indeterminate }
            FaultMode.THROW -> throw IllegalStateException("private transport exception")
            null -> { frames.addLast(frame.copyOf()); PortResult.Success(Unit) }
        }
    }
}

internal class MemoryObserver(private val label: String, private val trace: MutableList<String>) : SingleFileObserver {
    var onNotify: (TransferState) -> FaultMode? = { null }
    var onSchedule: () -> FaultMode? = { null }
    override fun notify(state: TransferState, snapshotVersion: Long): PortResult<Unit> {
        trace += "$label.notify.$state.v$snapshotVersion"
        return result(onNotify(state))
    }
    override fun schedule(): PortResult<Unit> {
        trace += "$label.schedule"
        return result(onSchedule())
    }
    private fun result(mode: FaultMode?): PortResult<Unit> = when (mode) {
        null -> PortResult.Success(Unit)
        FaultMode.FAIL -> PortResult.Failed(TransferError.UnexpectedInternal("private observer"))
        FaultMode.CANCEL -> PortResult.Cancelled
        FaultMode.UNKNOWN_BEFORE, FaultMode.UNKNOWN_AFTER -> PortResult.Indeterminate
        FaultMode.THROW -> throw IllegalStateException("private observer exception")
    }
}

internal class RuntimePair(content: ByteArray, sourceBytes: ByteArray = content, maxRead: Int = Int.MAX_VALUE) {
    val trace = mutableListOf<String>()
    val session = SessionId("session")
    val transfer = TransferId("transfer")
    val recipient = RecipientId("recipient")
    val file = FileId("file")
    val descriptor = TransferFileDescriptor(
        fileId = file,
        displayName = "sample.bin",
        relativePath = RelativeTransferPath("sample.bin"),
        mimeType = "application/octet-stream",
        totalBytes = content.size.toLong(),
        lastModifiedEpochMillis = null,
        isFolderArchive = false,
        expectedSha256 = Sha256Accumulator().update(content).digest(),
        chunkSize = ChunkSize.MIN,
        protocolVersion = ProtocolVersion.CURRENT,
    )
    val senderStore = MemoryStore("sender", trace)
    val receiverStore = MemoryStore("receiver", trace)
    val source = MemorySource(sourceBytes, trace, maxRead)
    val destination = MemoryDestination(trace)
    val senderTransport = MemoryTransport("sender", trace)
    val receiverTransport = MemoryTransport("receiver", trace)
    val senderObserver = MemoryObserver("sender", trace)
    val receiverObserver = MemoryObserver("receiver", trace)
    fun command(direction: SessionDirection, descriptor: TransferFileDescriptor = this.descriptor): TransferCommand.Enqueue =
        TransferCommand.Enqueue(session, transfer, recipient, BatchId("batch"), direction, descriptor)
    val sender = SingleFileRuntime(command(SessionDirection.OUTBOUND), SingleFileRole.Sender(source), senderStore, senderTransport, senderObserver)
    val receiver = SingleFileRuntime(command(SessionDirection.INBOUND), SingleFileRole.Receiver(destination), receiverStore, receiverTransport, receiverObserver)

    fun startThroughAcceptance(): SingleFileResult {
        assertEquals(SingleFileResult.Advanced, receiver.start())
        assertEquals(SingleFileResult.Advanced, sender.start())
        val received = deliverToReceiver(FrameType.HANDSHAKE)
        if (descriptor.totalBytes == 0L) assertEquals(SingleFileResult.WaitingForMetadata, received)
        else assertEquals(SingleFileResult.Advanced, received)
        return deliverToSender(FrameType.HANDSHAKE_ACCEPT)
    }

    fun establish() {
        assertEquals(SingleFileResult.Advanced, startThroughAcceptance())
        assertEquals(SingleFileResult.Advanced, deliverToReceiver(FrameType.FILE_METADATA))
    }

    fun deliverToReceiver(expected: FrameType): SingleFileResult = receiver.receive(take(senderTransport, expected))
    fun deliverToSender(expected: FrameType): SingleFileResult = sender.receive(take(receiverTransport, expected))

    fun take(transport: MemoryTransport, expected: FrameType): ByteArray {
        assertTrue("missing $expected in ${trace.joinToString()}", transport.frames.isNotEmpty())
        val frame = transport.frames.removeFirst()
        assertEquals(expected, (FrameCodec.decodeSingle(frame) as FrameDecodeResult.Success).frame.type)
        return frame
    }
}
