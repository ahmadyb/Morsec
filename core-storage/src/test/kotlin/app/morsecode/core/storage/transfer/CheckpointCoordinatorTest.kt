package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.TransferId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checkpoint ordering, and what happens at each interruption.
 *
 * This is the file the group's central claim rests on: that an acknowledgement is
 * only ever released after validate, frontier check, write, flush and persist
 * have all succeeded, in that order. Each of the five steps is made to fail in
 * turn and the assertion is always the same two things — the frontier did not
 * move, and no acknowledgement came out.
 *
 * The fakes fail on demand rather than by sleeping, being slow, or pretending.
 */
class CheckpointCoordinatorTest {

    private val transfer = TransferId("transfer-0001")

    private class FakePartial(initialLength: Long = 0L) : PartialSink {
        var currentLength: Long = initialLength
            private set
        var writeCount = 0
            private set
        var flushCount = 0
            private set
        var truncateCount = 0
            private set
        val writeOffsets = mutableListOf<Long>()

        var forcedWrite: WriteOutcome? = null
        var forcedFlush: FlushDurability = FlushDurability.DurableFlushSupported

        override fun length(): Long = currentLength

        override fun writeAt(
            offset: Long,
            buffer: ByteArray,
            dataOffset: Int,
            length: Int,
        ): WriteOutcome {
            writeCount++
            writeOffsets += offset
            val forced = forcedWrite
            if (forced != null) return forced
            currentLength = maxOf(currentLength, offset + length.toLong())
            return WriteOutcome.Written(length, currentLength)
        }

        override fun flush(): FlushDurability {
            flushCount++
            return forcedFlush
        }

        override fun truncateTo(offset: Long): TruncateOutcome {
            truncateCount++
            currentLength = minOf(currentLength, offset)
            return TruncateOutcome.Truncated(currentLength)
        }
    }

    private class FakeSink(initial: Long = 0L) : CheckpointSink {
        var frontier: Long = initial
            private set
        var persistCount = 0
            private set
        var forcedPersist: PersistOutcome = PersistOutcome.Persisted
        val persisted = mutableListOf<Long>()

        override fun confirmedFrontier(transferId: TransferId): Long = frontier

        override fun persist(
            transferId: TransferId,
            offset: Long,
            durability: FlushDurability,
        ): PersistOutcome {
            persistCount++
            val forced = forcedPersist
            if (forced is PersistOutcome.Failed) return forced
            if (offset > frontier) {
                frontier = offset
                persisted += offset
            }
            return PersistOutcome.Persisted
        }
    }

    private fun chunk(
        offset: Long = 0L,
        length: Int = 1_024,
        total: Long = 4_096L,
        bufferSize: Int = length,
    ) = ChunkWrite(
        transferId = transfer,
        writeOffset = offset,
        buffer = ByteArray(bufferSize),
        dataOffset = 0,
        length = length,
        totalBytes = total,
    )

    private fun coordinator(
        sink: CheckpointSink,
        trace: MutableList<CheckpointStep>,
    ) = CheckpointCoordinator(sink) { step, _, _ -> trace += step }

    // --- the happy path ----------------------------------------------------

    @Test
    fun `a successful chunk runs the five steps in order and then acknowledges`() {
        val sink = FakeSink()
        val partial = FakePartial()
        val trace = mutableListOf<CheckpointStep>()

        val outcome = coordinator(sink, trace).onChunk(partial, chunk())

        assertEquals(
            listOf(
                CheckpointStep.VALIDATE,
                CheckpointStep.FRONTIER,
                CheckpointStep.WRITE,
                CheckpointStep.FLUSH,
                CheckpointStep.PERSIST,
            ),
            trace,
        )
        assertTrue(outcome is ChunkOutcome.Acknowledged)
        assertEquals(1_024L, (outcome as ChunkOutcome.Acknowledged).offset)
        assertEquals(1, sink.persistCount)
        assertEquals(listOf(1_024L), sink.persisted)
    }

    @Test
    fun `the acknowledgement carries the durability the checkpoint rests on`() {
        val sink = FakeSink()
        val partial = FakePartial().apply {
            forcedFlush = FlushDurability.FlushAttemptedGuaranteeUnknown
        }

        val outcome = CheckpointCoordinator(sink).onChunk(partial, chunk())

        assertTrue(outcome is ChunkOutcome.Acknowledged)
        assertEquals(
            FlushDurability.FlushAttemptedGuaranteeUnknown,
            (outcome as ChunkOutcome.Acknowledged).durability,
        )
    }

    @Test
    fun `successive chunks advance the frontier by exactly their length`() {
        val sink = FakeSink()
        val partial = FakePartial()
        val coordinator = CheckpointCoordinator(sink)

        var offset = 0L
        repeat(4) {
            val outcome = coordinator.onChunk(partial, chunk(offset = offset))
            assertTrue(outcome is ChunkOutcome.Acknowledged)
            offset = (outcome as ChunkOutcome.Acknowledged).offset
        }

        assertEquals(4_096L, offset)
        assertEquals(listOf(0L, 1_024L, 2_048L, 3_072L), partial.writeOffsets)
        assertEquals(4_096L, partial.length())
    }

    // --- flush failure ------------------------------------------------------

    @Test
    fun `a failed flush blocks the checkpoint and withholds the acknowledgement`() {
        val sink = FakeSink(initial = 2_048L)
        val partial = FakePartial(initialLength = 2_048L).apply {
            forcedFlush = FlushDurability.FlushFailed(TransferStorageError.Io("sync"))
        }
        val trace = mutableListOf<CheckpointStep>()

        val outcome = coordinator(sink, trace).onChunk(partial, chunk(offset = 2_048L))

        assertTrue("no acknowledgement may be released", outcome !is ChunkOutcome.Acknowledged)
        assertTrue(outcome is ChunkOutcome.Failed)
        val failure = outcome as ChunkOutcome.Failed
        assertEquals(CheckpointStep.FLUSH, failure.failedAt)
        assertEquals(2_048L, failure.frontier)
        assertEquals("the frontier must not move", 2_048L, sink.frontier)
        assertEquals("the checkpoint must not be persisted", 0, sink.persistCount)
        // PERSIST is never reached, so it is never recorded.
        assertEquals(
            listOf(
                CheckpointStep.VALIDATE,
                CheckpointStep.FRONTIER,
                CheckpointStep.WRITE,
            ),
            trace,
        )
    }

    @Test
    fun `a failed flush is the only flush outcome that blocks progress`() {
        val outcomes: List<FlushDurability> = listOf(
            FlushDurability.DurableFlushSupported,
            FlushDurability.FlushAttemptedGuaranteeUnknown,
            FlushDurability.FlushUnsupported,
            FlushDurability.FlushFailed(TransferStorageError.Io("sync")),
        )
        val results = outcomes.map { durability ->
            val sink = FakeSink()
            val partial = FakePartial().apply { forcedFlush = durability }
            CheckpointCoordinator(sink).onChunk(partial, chunk()) is ChunkOutcome.Acknowledged
        }
        assertEquals(listOf(true, true, true, false), results)
    }

    @Test
    fun `bytes written before a failed flush are left for reconciliation to discard`() {
        // Not rolled back: they are unacknowledged, which is exactly the LONGER
        // case PartialReconciliation truncates on the next recovery.
        val sink = FakeSink()
        val partial = FakePartial().apply {
            forcedFlush = FlushDurability.FlushFailed(TransferStorageError.Io("sync"))
        }

        CheckpointCoordinator(sink).onChunk(partial, chunk())

        assertEquals(1_024L, partial.length())
        assertEquals(0L, sink.frontier)
        assertEquals(
            ReconciliationAction.TRUNCATE_TO_CHECKPOINT,
            PartialReconciliation.reconcile(
                persistedOffset = sink.frontier,
                actualLength = partial.length(),
                totalBytes = 4_096L,
            ).action,
        )
    }

    // --- persist failure ----------------------------------------------------

    @Test
    fun `a failed persist withholds the acknowledgement even though the write landed`() {
        val sink = FakeSink(initial = 1_024L).apply {
            forcedPersist = PersistOutcome.Failed(TransferStorageError.Io("persist"))
        }
        val partial = FakePartial(initialLength = 1_024L)
        val trace = mutableListOf<CheckpointStep>()

        val outcome = coordinator(sink, trace).onChunk(partial, chunk(offset = 1_024L))

        assertTrue(outcome is ChunkOutcome.Failed)
        val failure = outcome as ChunkOutcome.Failed
        assertEquals(CheckpointStep.PERSIST, failure.failedAt)
        assertEquals(1_024L, failure.frontier)
        assertEquals(1_024L, sink.frontier)
        // Flush happened, persist did not complete, so PERSIST is not recorded.
        assertFalse(trace.contains(CheckpointStep.PERSIST))
        assertTrue(trace.contains(CheckpointStep.FLUSH))
    }

    // --- write failure ------------------------------------------------------

    @Test
    fun `a failed write never reaches the flush or the persist`() {
        val sink = FakeSink()
        val partial = FakePartial().apply {
            forcedWrite = WriteOutcome.Failed(TransferStorageError.Io("write"))
        }
        val trace = mutableListOf<CheckpointStep>()

        val outcome = coordinator(sink, trace).onChunk(partial, chunk())

        assertTrue(outcome is ChunkOutcome.Failed)
        assertEquals(CheckpointStep.WRITE, (outcome as ChunkOutcome.Failed).failedAt)
        assertEquals(0, partial.flushCount)
        assertEquals(0, sink.persistCount)
        assertEquals(0L, sink.frontier)
    }

    @Test
    fun `a short write is a failure rather than partial progress`() {
        val sink = FakeSink()
        val partial = FakePartial().apply {
            forcedWrite = WriteOutcome.Written(bytes = 512, length = 512L)
        }

        val outcome = CheckpointCoordinator(sink).onChunk(partial, chunk(length = 1_024))

        assertTrue(outcome is ChunkOutcome.Failed)
        assertEquals(CheckpointStep.WRITE, (outcome as ChunkOutcome.Failed).failedAt)
        assertEquals(0L, sink.frontier)
    }

    // --- rejections touch nothing -------------------------------------------

    @Test
    fun `a chunk that would leave a gap is refused before anything is written`() {
        val sink = FakeSink(initial = 1_024L)
        val partial = FakePartial(initialLength = 1_024L)

        val outcome = CheckpointCoordinator(sink).onChunk(partial, chunk(offset = 2_048L))

        assertTrue(outcome is ChunkOutcome.Rejected)
        assertEquals(RejectionReason.GAP, (outcome as ChunkOutcome.Rejected).reason)
        assertEquals(0, partial.writeCount)
        assertEquals(0, partial.flushCount)
        assertEquals(0, sink.persistCount)
    }

    @Test
    fun `a chunk behind the frontier is refused as stale rather than re-applied`() {
        val sink = FakeSink(initial = 2_048L)
        val partial = FakePartial(initialLength = 2_048L)

        val outcome = CheckpointCoordinator(sink).onChunk(partial, chunk(offset = 1_024L))

        assertTrue(outcome is ChunkOutcome.Rejected)
        assertEquals(RejectionReason.STALE, (outcome as ChunkOutcome.Rejected).reason)
        assertEquals(0, partial.writeCount)
    }

    @Test
    fun `validation rejects before the frontier is even consulted`() {
        val sink = FakeSink()
        val partial = FakePartial()
        val trace = mutableListOf<CheckpointStep>()

        val outcome = coordinator(sink, trace).onChunk(partial, chunk(length = 0))

        assertTrue(outcome is ChunkOutcome.Rejected)
        assertEquals(RejectionReason.EMPTY, (outcome as ChunkOutcome.Rejected).reason)
        assertTrue(trace.isEmpty())
        assertEquals(0, partial.writeCount)
    }

    @Test
    fun `bounds rejections all refuse without writing`() {
        val cases = listOf(
            RejectionReason.EMPTY to chunk(length = 0),
            RejectionReason.NEGATIVE to chunk(offset = -1L),
            RejectionReason.TOO_LARGE to chunk(
                length = ProtocolLimits.MAX_PAYLOAD_BYTES + 1,
                bufferSize = ProtocolLimits.MAX_PAYLOAD_BYTES + 1,
                total = ProtocolLimits.MAX_PAYLOAD_BYTES + 2L,
            ),
            RejectionReason.BUFFER_BOUNDS to chunk(length = 2_048, bufferSize = 1_024),
            RejectionReason.PAST_END to chunk(offset = 3_584L, length = 1_024, total = 4_096L),
        )
        cases.forEach { (expected, write) ->
            val partial = FakePartial()
            val outcome = CheckpointCoordinator(FakeSink()).onChunk(partial, write)
            assertTrue(
                "expected $expected for offset=${write.writeOffset} length=${write.length}, got $outcome",
                outcome is ChunkOutcome.Rejected && outcome.reason == expected,
            )
            assertEquals("nothing may be written for $expected", 0, partial.writeCount)
        }
    }

    // --- large files --------------------------------------------------------

    @Test
    fun `offsets beyond the 32-bit range are handled without overflow`() {
        val start = 5_368_709_120L // 5 GiB
        val sink = FakeSink(initial = start)
        val partial = FakePartial(initialLength = start)

        val outcome = CheckpointCoordinator(sink).onChunk(
            partial,
            chunk(offset = start, length = 65_536, total = 8_796_093_022_207L),
        )

        assertTrue(outcome is ChunkOutcome.Acknowledged)
        assertEquals(5_368_774_656L, (outcome as ChunkOutcome.Acknowledged).offset)
        assertEquals(5_368_774_656L, partial.length())
    }

    @Test
    fun `a chunk ending exactly at the protocol maximum is accepted`() {
        val total = 8_796_093_022_207L
        val start = total - 1_024L
        val sink = FakeSink(initial = start)
        val partial = FakePartial(initialLength = start)

        val outcome = CheckpointCoordinator(sink).onChunk(
            partial,
            chunk(offset = start, length = 1_024, total = total),
        )

        assertTrue(outcome is ChunkOutcome.Acknowledged)
        assertEquals(total, (outcome as ChunkOutcome.Acknowledged).offset)
    }

    @Test
    fun `a chunk past the protocol maximum is refused rather than overflowing`() {
        val total = 8_796_093_022_207L
        val sink = FakeSink(initial = total - 512L)
        val partial = FakePartial(initialLength = total - 512L)

        val outcome = CheckpointCoordinator(sink).onChunk(
            partial,
            chunk(offset = total - 512L, length = 1_024, total = total),
        )

        assertTrue(outcome is ChunkOutcome.Rejected)
        assertEquals(RejectionReason.PAST_END, (outcome as ChunkOutcome.Rejected).reason)
        assertEquals(0, partial.writeCount)
    }

    // --- retry --------------------------------------------------------------

    @Test
    fun `a retry after a failed flush starts from the same frontier`() {
        val sink = FakeSink()
        val partial = FakePartial().apply {
            forcedFlush = FlushDurability.FlushFailed(TransferStorageError.Io("sync"))
        }
        val coordinator = CheckpointCoordinator(sink)

        assertTrue(coordinator.onChunk(partial, chunk()) is ChunkOutcome.Failed)

        partial.forcedFlush = FlushDurability.DurableFlushSupported
        val retry = coordinator.onChunk(partial, chunk())

        assertTrue(retry is ChunkOutcome.Acknowledged)
        assertEquals(1_024L, (retry as ChunkOutcome.Acknowledged).offset)
    }

    @Test
    fun `the step vocabulary has the required order`() {
        assertEquals(
            listOf("validate", "frontier", "write", "flush", "persist"),
            CheckpointStep.ORDER.map { it.id },
        )
    }
}
