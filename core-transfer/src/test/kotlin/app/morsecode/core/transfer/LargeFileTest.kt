package app.morsecode.core.transfer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.transfer.broadcast.BroadcastAggregator
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SequenceNumber
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.persistence.SnapshotDecodeResult
import app.morsecode.core.transfer.persistence.TransferSnapshotCodec
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.protocol.FramePayloads
import app.morsecode.core.transfer.protocol.PayloadResult
import app.morsecode.core.transfer.protocol.FramePayload
import app.morsecode.core.transfer.protocol.ResumeDecision
import app.morsecode.core.transfer.reducer.TransferReducer
import app.morsecode.core.transfer.reducer.TransitionResult
import app.morsecode.core.transfer.resume.ReceiverResumeState
import app.morsecode.core.transfer.resume.ResumeProposal as ResumeProposalModel
import app.morsecode.core.transfer.resume.ReceiverVerificationState
import app.morsecode.core.transfer.resume.ResumeNegotiator
import app.morsecode.core.transfer.scheduler.ConcurrencyLimits
import app.morsecode.core.transfer.scheduler.SchedulerInput
import app.morsecode.core.transfer.scheduler.SchedulerStatus
import app.morsecode.core.transfer.scheduler.TransferScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Files larger than 4 GiB.
 *
 * An earlier revision tied the file-size ceiling to a 32-bit payload length,
 * which silently refused anything over 4 GiB — phone videos, archives, backups
 * and disk images all exceed that. Every size and offset in the core is a `Long`
 * and always has been; the ceiling is the only thing that was wrong.
 *
 * The tests below exercise one 5 GiB file end to end through every representation
 * of size in the core: descriptor, confirmed and optimistic offsets, frame
 * offset, resume offset, scheduler and broadcast totals, and snapshots. None of
 * them allocates the file — a 5 GiB test would not fit in memory, and the point
 * is the arithmetic and the encoding, not the bytes.
 */
class LargeFileTest {

    private companion object {
        /** 5 GiB: comfortably above the 4 GiB limit this suite exists to remove. */
        const val FIVE_GIB: Long = 5L * 1_024L * 1_024L * 1_024L

        /** One byte short of 4 GiB, the old ceiling's last representable size. */
        const val FOUR_GIB_MINUS_ONE: Long = 4L * 1_024L * 1_024L * 1_024L - 1L

        const val CHUNK: Int = 262_144 // 256 KiB, the largest chunk
    }

    private val chunk = ChunkSize(CHUNK)

    private fun descriptor(totalBytes: Long = FIVE_GIB): TransferFileDescriptor =
        Tf.descriptor(
            totalBytes = totalBytes,
            digest = Tf.knownDigest,
            chunkSize = chunk,
        )

    // --- the ceiling itself ----------------------------------------------------------------

    @Test fun `the maximum file size is greater than four gibibytes`() {
        assertTrue(
            "MAX_FILE_SIZE_BYTES ${ProtocolLimits.MAX_FILE_SIZE_BYTES} must exceed 4 GiB",
            ProtocolLimits.MAX_FILE_SIZE_BYTES > 4L * 1_024L * 1_024L * 1_024L,
        )
        assertEquals(8_796_093_022_207L, ProtocolLimits.MAX_FILE_SIZE_BYTES)
    }

    @Test fun `the ceiling leaves room for a trailing chunk without overflowing`() {
        val sum = ProtocolLimits.MAX_FILE_SIZE_BYTES + ProtocolLimits.MAX_CHUNK_SIZE_BYTES
        assertTrue(
            "$sum must stay below Long.MAX_VALUE",
            sum < Long.MAX_VALUE,
        )
        // Eleven orders of magnitude of headroom, so no realistic sum can reach it.
        assertTrue(Long.MAX_VALUE - sum > 1_000_000L * sum)
    }

    // --- descriptor -----------------------------------------------------------------------------

    @Test fun `a five gibibyte descriptor is valid`() {
        val descriptor = descriptor(FIVE_GIB)
        assertEquals(FIVE_GIB, descriptor.totalBytes)
        assertTrue(descriptor.totalBytes > FOUR_GIB_MINUS_ONE)
    }

    @Test fun `a file at the maximum size is valid`() {
        val descriptor = descriptor(ProtocolLimits.MAX_FILE_SIZE_BYTES)
        assertEquals(ProtocolLimits.MAX_FILE_SIZE_BYTES, descriptor.totalBytes)
    }

    @Test fun `a file one byte above the maximum is rejected`() {
        assertRejected { descriptor(ProtocolLimits.MAX_FILE_SIZE_BYTES + 1L) }
    }

    @Test fun `a negative file size is rejected`() {
        assertRejected { descriptor(-1L) }
    }

    @Test fun `a size that would overflow when read as a signed value is rejected`() {
        assertRejected { descriptor(Long.MIN_VALUE) }
        assertRejected { descriptor(Long.MAX_VALUE) }
    }

    // --- offsets ------------------------------------------------------------------------------------

    @Test fun `a middle offset inside a five gibibyte file is a valid confirmed offset`() {
        val middle = 3L * 1_024L * 1_024L * 1_024L // 3 GiB
        val offset = ConfirmedOffset(middle)
        assertEquals(middle, offset.value)
        assertTrue(offset.value < FIVE_GIB)
    }

    @Test fun `the final offset of a five gibibyte file is a valid confirmed offset`() {
        assertEquals(FIVE_GIB, ConfirmedOffset(FIVE_GIB).value)
        assertEquals(
            ProtocolLimits.MAX_FILE_SIZE_BYTES,
            ConfirmedOffset(ProtocolLimits.MAX_FILE_SIZE_BYTES).value,
        )
    }

    @Test fun `a confirmed offset above the ceiling is rejected`() {
        var threw = false
        try {
            ConfirmedOffset(ProtocolLimits.MAX_FILE_SIZE_BYTES + 1L)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(ConfirmedOffset.isValid(ProtocolLimits.MAX_FILE_SIZE_BYTES + 1L))
    }

    @Test fun `sequence numbers above four gibibytes are valid`() {
        // 5 GiB over a 256 KiB chunk is 20,480 chunks; over the 1 KiB minimum
        // chunk it is over five million. Both fit a Long comfortably.
        val atFiveGib = SequenceNumber.forOffset(FIVE_GIB, chunk)
        assertEquals(FIVE_GIB / CHUNK, atFiveGib.value)
        assertTrue(atFiveGib.value > 4_294_967_295L / CHUNK)
        assertTrue(SequenceNumber.isValid(atFiveGib.value))
        assertTrue(
            "the sequence ceiling must cover the largest file over the smallest chunk",
            SequenceNumber.MAX_VALUE >=
                ProtocolLimits.MAX_FILE_SIZE_BYTES / ProtocolLimits.MIN_CHUNK_SIZE_BYTES,
        )
    }

    // --- chunk arithmetic ---------------------------------------------------------------------------

    @Test fun `the final partial chunk of a five gibibyte file is computed correctly`() {
        // 5 GiB is an exact multiple of 256 KiB, so use a size that is not.
        val total = FIVE_GIB + 123L
        val lastChunkStart = (total / CHUNK) * CHUNK
        assertEquals(CHUNK, chunk.lengthOfChunkAt(0L, total))
        assertEquals(123, chunk.lengthOfChunkAt(lastChunkStart, total))
        assertEquals(0, chunk.lengthOfChunkAt(total, total))
        assertEquals(total / CHUNK + 1L, chunk.chunkCountFor(total))
    }

    @Test fun `chunk counts do not overflow at the maximum file size`() {
        val count = chunk.chunkCountFor(ProtocolLimits.MAX_FILE_SIZE_BYTES)
        assertTrue(count > 0L)
        assertTrue(count < Long.MAX_VALUE)
        assertEquals(
            (ProtocolLimits.MAX_FILE_SIZE_BYTES - 1L) / CHUNK + 1L,
            count,
        )
    }

    // --- checked arithmetic --------------------------------------------------------------------------

    @Test fun `checked addition accepts the largest addressable range`() {
        val max = ProtocolLimits.MAX_FILE_SIZE_BYTES
        val length = ProtocolLimits.MAX_CHUNK_SIZE_BYTES.toLong()
        assertTrue(ProtocolLimits.isValidRange(max - length, length))
        assertEquals(max, ProtocolLimits.checkedEnd(max - length, length))
    }

    @Test fun `checked addition rejects a range that would overflow the ceiling`() {
        val max = ProtocolLimits.MAX_FILE_SIZE_BYTES
        val length = ProtocolLimits.MAX_CHUNK_SIZE_BYTES.toLong()
        assertFalse(ProtocolLimits.isValidRange(max, length))
        assertFalse(ProtocolLimits.isValidRange(max - length + 1L, length))
    }

    @Test fun `checked addition near the ceiling never wraps into a small positive number`() {
        // A wrap would show up as a *valid* range far from the ceiling, so the
        // sweep below looks for exactly that: every rejected offset must be
        // rejected, and none of them may report an end below its own start.
        val max = ProtocolLimits.MAX_FILE_SIZE_BYTES
        val length = ProtocolLimits.MAX_CHUNK_SIZE_BYTES.toLong()
        var accepted = 0
        for (delta in longArrayOf(-1L, 0L, 1L, 2L, 1_000L, length)) {
            val offset = max - length + delta
            if (offset < 0L) continue
            if (ProtocolLimits.isValidRange(offset, length)) {
                accepted += 1
                val end = ProtocolLimits.checkedEnd(offset, length)
                assertTrue("end $end must not be below its start $offset", end >= offset)
                assertTrue("end $end must not exceed the ceiling", end <= max)
            }
        }
        // Only the two offsets that genuinely fit are accepted.
        assertEquals(2, accepted)
    }

    @Test fun `checked addition rejects negative and oversized arguments`() {
        assertFalse(ProtocolLimits.isValidRange(-1L, 0L))
        assertFalse(ProtocolLimits.isValidRange(0L, -1L))
        assertFalse(ProtocolLimits.isValidRange(0L, ProtocolLimits.MAX_CHUNK_SIZE_BYTES + 1L))
    }

    @Test fun `a chunk that would run past the ceiling is refused by the reducer`() {
        val snapshot = sendingAt(FIVE_GIB)
        val result = TransferReducer.apply(
            snapshot,
            Tf.chunkSent(offset = FIVE_GIB, length = CHUNK),
        )
        assertTrue(result is TransitionResult.Rejected)
        assertEquals("offset_beyond_total", (result as TransitionResult.Rejected).reason.code)
    }

    // --- frames -----------------------------------------------------------------------------------------

    @Test fun `encoding metadata above four gibibytes preserves the exact value`() {
        val encoded = FrameCodec.encode(
            FrameCodec.fileMetadata(Tf.sessionId, Tf.transferId, descriptor(FIVE_GIB)),
        )
        val decoded = (FrameCodec.decodeSingle(encoded) as FrameDecodeResult.Success).frame
        val payload = (
            FramePayloads.parse(decoded) as PayloadResult.Success
            ).payload as FramePayload.FileMetadata
        assertEquals(FIVE_GIB, payload.descriptor.totalBytes)
        assertEquals(descriptor(FIVE_GIB), payload.descriptor)
    }

    @Test fun `a data chunk above the four gibibyte offset round trips`() {
        val offset = FIVE_GIB - CHUNK // the last chunk of a 5 GiB file
        val frame = FrameCodec.dataChunk(
            sessionId = Tf.sessionId,
            transferId = Tf.transferId,
            offset = offset,
            sequence = SequenceNumber.forOffset(offset, chunk).value,
            bytes = ByteArray(64) { (it and 0xFF).toByte() },
        )
        val decoded = (FrameCodec.decodeSingle(FrameCodec.encode(frame))
            as FrameDecodeResult.Success).frame
        assertEquals(offset, decoded.offset)
        assertTrue(decoded.offset > FOUR_GIB_MINUS_ONE)
        assertEquals(SequenceNumber.forOffset(offset, chunk).value, decoded.sequence)
    }

    @Test fun `a chunk acknowledgement above four gibibytes round trips`() {
        val confirmed = FIVE_GIB
        val payload = FramePayload.ChunkAck(
            offset = confirmed - CHUNK,
            length = CHUNK,
            confirmedOffset = confirmed,
            accepted = true,
        )
        val decoded = (FrameCodec.decodeSingle(
            FrameCodec.encode(
                FrameCodec.chunkAck(
                    Tf.sessionId, Tf.transferId,
                    offset = payload.offset,
                    length = payload.length,
                    confirmedOffset = payload.confirmedOffset,
                ),
            ),
        ) as FrameDecodeResult.Success).frame
        assertEquals(confirmed - CHUNK, decoded.offset)
        val ack = (FramePayloads.parse(decoded) as PayloadResult.Success).payload as FramePayload.ChunkAck
        assertEquals(confirmed, ack.confirmedOffset)
        assertEquals(CHUNK, ack.length)
        // The value survived the wire: it is not truncated to 32 bits and it is
        // not negative, which is what a signed Int round trip would have produced.
        assertEquals(0L, confirmed % CHUNK)
        assertTrue(ack.confirmedOffset > Int.MAX_VALUE)
    }

    @Test fun `a resume offset above four gibibytes round trips`() {
        val decision = ResumeDecision.ResumeAt(FIVE_GIB - CHUNK)
        val response = FramePayload.ResumeResponse(Tf.fileId, decision)
        val decoded = (FramePayloads.parse(
            (FrameCodec.decodeSingle(
                FrameCodec.encode(
                    FrameCodec.resumeResponse(Tf.sessionId, Tf.transferId, response),
                ),
            ) as FrameDecodeResult.Success).frame,
        ) as PayloadResult.Success).payload
            as FramePayload.ResumeResponse
        assertEquals(FIVE_GIB - CHUNK, decoded.offset)
    }

    // --- resume ---------------------------------------------------------------------------------------

    @Test fun `a five gibibyte middle resume offset is valid`() {
        val middle = 3L * 1_024L * 1_024L * 1_024L // 3 GiB, chunk aligned
        val receiver = receiverState(confirmedBytes = middle, total = FIVE_GIB)
        val decision = ResumeNegotiator.negotiate(proposal(FIVE_GIB), receiver)
        assertEquals(ResumeDecision.ResumeAt(middle), decision)
    }

    @Test fun `a five gibibyte final resume offset is valid`() {
        val receiver = receiverState(confirmedBytes = FIVE_GIB, total = FIVE_GIB)
        val decision = ResumeNegotiator.negotiate(proposal(FIVE_GIB), receiver)
        // Complete but not yet verified, so it is retransmitted rather than
        // trusted — the size is not what makes it unverified.
        assertTrue(decision is ResumeDecision.RestartAtZero)
    }

    @Test fun `a five gibibyte verified file is already verified`() {
        val receiver = receiverState(
            confirmedBytes = FIVE_GIB,
            total = FIVE_GIB,
            verification = ReceiverVerificationState.VERIFIED,
        )
        assertEquals(
            ResumeDecision.AlreadyVerified,
            ResumeNegotiator.negotiate(proposal(FIVE_GIB), receiver),
        )
    }

    @Test fun `a resume proposal above the ceiling is rejected`() {
        var threw = false
        try {
            proposal(ProtocolLimits.MAX_FILE_SIZE_BYTES + 1L)
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // --- snapshots --------------------------------------------------------------------------------------

    @Test fun `a snapshot round trip above four gibibytes preserves the exact value`() {
        val snapshot = snapshotAt(confirmedBytes = FIVE_GIB - CHUNK, total = FIVE_GIB)
        val restored = (
            TransferSnapshotCodec.deserialize(TransferSnapshotCodec.serialize(snapshot))
                as SnapshotDecodeResult.Success
            ).snapshot
        assertEquals(FIVE_GIB, restored.totalBytes)
        assertEquals(FIVE_GIB - CHUNK, restored.confirmedBytes)
        assertEquals(snapshot.copy(failure = null), restored.copy(failure = null))
    }

    @Test fun `a snapshot at the maximum size round trips exactly`() {
        val snapshot = snapshotAt(
            confirmedBytes = ProtocolLimits.MAX_FILE_SIZE_BYTES - CHUNK,
            total = ProtocolLimits.MAX_FILE_SIZE_BYTES,
        )
        val restored = (
            TransferSnapshotCodec.deserialize(TransferSnapshotCodec.serialize(snapshot))
                as SnapshotDecodeResult.Success
            ).snapshot
        assertEquals(ProtocolLimits.MAX_FILE_SIZE_BYTES, restored.totalBytes)
        assertEquals(ProtocolLimits.MAX_FILE_SIZE_BYTES - CHUNK, restored.confirmedBytes)
    }

    @Test fun `a snapshot byte count above the ceiling is rejected on restore`() {
        val encoded = TransferSnapshotCodec.serialize(snapshotAt(0L, FIVE_GIB))
            .lines()
            .map { line ->
                if (line.startsWith("totalBytes=")) {
                    "totalBytes=${ProtocolLimits.MAX_FILE_SIZE_BYTES + 1L}"
                } else {
                    line
                }
            }
            .joinToString("\n") + "\n"
        val result = TransferSnapshotCodec.deserialize(encoded)
        assertTrue(
            "a size above the ceiling must be refused: $result",
            result is app.morsecode.core.transfer.persistence.SnapshotDecodeResult.Invalid,
        )
    }

    // --- scheduler and broadcast totals ---------------------------------------------------------------------

    @Test fun `broadcast totals above four gibibytes remain correct`() {
        val batch = listOf(
            Tf.broadcastQueued(TransferId("tr-a"), Tf.recipientA, totalBytes = FIVE_GIB),
            Tf.broadcastQueued(TransferId("tr-b"), Tf.recipientB, totalBytes = FIVE_GIB),
        )
        val summary = BroadcastAggregator.summarize(batch)
        // One file of 5 GiB to two recipients: 10 GiB expected in total.
        assertEquals(2, summary.expectedDeliveries)
        assertEquals(FIVE_GIB * 2L, summary.expectedBytes)
        assertTrue(summary.expectedBytes > 4L * 1_024L * 1_024L * 1_024L)
    }

    @Test fun `a per recipient confirmed total above four gibibytes is exact`() {
        val snapshot = snapshotAt(confirmedBytes = FIVE_GIB, total = FIVE_GIB)
        val summary = BroadcastAggregator.summarize(listOf(snapshot))
        assertEquals(FIVE_GIB, summary.confirmedBytes)
        assertEquals(FIVE_GIB, summary.recipients.single().confirmedBytes)
    }

    @Test fun `the scheduler plans a five gibibyte delivery`() {
        val snapshot = Tf.queued(descriptor = descriptor(FIVE_GIB))
        val plan = TransferScheduler.plan(
            SchedulerInput(
                sessionId = Tf.sessionId,
                snapshots = listOf(snapshot),
                limits = ConcurrencyLimits.uniform(1),
            ),
        )
        assertEquals(SchedulerStatus.WORK_PLANNED, plan.status)
        assertEquals(1, plan.decisions.size)
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private fun assertRejected(build: () -> TransferFileDescriptor) {
        var threw = false
        try {
            build()
        } catch (expected: IllegalArgumentException) {
            threw = true
        }
        assertTrue("the descriptor must be refused", threw)
    }

    private fun sendingAt(total: Long): TransferSnapshot {
        var snapshot = Tf.queued(descriptor = descriptor(total))
        snapshot = current(TransferReducer.apply(snapshot, Tf.beginNegotiation()))
        return current(
            TransferReducer.apply(snapshot, Tf.peerAccepted(chunkSize = chunk)),
        )
    }

    private fun snapshotAt(confirmedBytes: Long, total: Long): TransferSnapshot {
        val base = Tf.queued(descriptor = descriptor(total))
        return base.copy(
            state = app.morsecode.core.model.TransferState.RECEIVING,
            confirmedBytes = confirmedBytes,
            optimisticBytes = confirmedBytes,
            direction = SessionDirection.INBOUND,
            recipientId = Tf.recipientA,
        )
    }

    private fun receiverState(
        confirmedBytes: Long,
        total: Long,
        verification: ReceiverVerificationState = ReceiverVerificationState.NOT_STARTED,
    ): ReceiverResumeState = ReceiverResumeState(
        knownTransfer = true,
        fileId = Tf.fileId,
        totalBytes = total,
        confirmedBytes = confirmedBytes,
        descriptorFingerprint = descriptor(total).descriptorFingerprint,
        chunkSize = chunk,
        verification = verification,
        expectedSha256 = Tf.knownDigest,
        lastModifiedEpochMillis = Tf.LAST_MODIFIED,
    )

    private fun proposal(total: Long): ResumeProposalModel = ResumeProposalModel(
        protocolVersion = app.morsecode.core.transfer.identity.ProtocolVersion.CURRENT,
        fileId = Tf.fileId,
        totalBytes = total,
        chunkSize = chunk,
        expectedSha256 = Tf.knownDigest,
        relativePath = RelativeTransferPath("photos/holiday.mp4"),
        lastModifiedEpochMillis = Tf.LAST_MODIFIED,
        descriptorFingerprint = descriptor(total).descriptorFingerprint,
    )

    private fun current(result: TransitionResult): TransferSnapshot =
        (result as TransitionResult.Accepted).current
}
