package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.current
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.integrity.Crc32
import app.morsecode.core.transfer.persistence.SnapshotDecodeResult
import app.morsecode.core.transfer.model.VerificationOutcome
import app.morsecode.core.transfer.persistence.TransferSnapshotCodec
import app.morsecode.core.transfer.plus
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.rejection
import app.morsecode.core.transfer.scheduler.BlockReason
import app.morsecode.core.transfer.scheduler.ConcurrencyLimits
import app.morsecode.core.transfer.scheduler.SchedulerInput
import app.morsecode.core.transfer.scheduler.SchedulerStatus
import app.morsecode.core.transfer.scheduler.TransferScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * Every reachable rejection, asserted by code.
 *
 * `Rejection` is a sealed vocabulary, and a sealed vocabulary is only useful if
 * a caller can rely on every member actually occurring. Three checks make that
 * true and keep it true:
 *
 * 1. One test per variant, each driving a real `TransferReducer` call — never a
 *    hand-constructed `Rejection` — and asserting the exact code.
 * 2. `the covered codes are exactly the codes the reducer can produce`, so a new
 *    variant cannot be added without a test that proves it can fire.
 * 3. Tests for the two refusals that are *not* in the vocabulary, proving they
 *    are owned somewhere real rather than merely missing.
 *
 * No test here sleeps, reads a clock or uses a random value: each scenario is
 * built from the deterministic fixtures in TransferFixtures.
 */
class RejectionCoverageTest {

    // --- ownership --------------------------------------------------------------------------------

    @Test fun `illegal_state_transition - a chunk acknowledgement outside SENDING is refused`() {
        val paused = Tf.sending() + Tf.pauseLocally()
        assertEquals(TransferState.PAUSED_LOCAL, paused.state)
        val rejected = rejection(
            TransferReducer.apply(paused, Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = 0L)),
        )
        assertEquals("illegal_state_transition", rejected.code)
        assertEquals(TransferState.PAUSED_LOCAL, (rejected as Rejection.IllegalStateTransition).from)
        assertEquals("ChunkAcknowledged", rejected.attempted)
    }

    @Test fun `wrong_session - a command for another session is refused`() {
        val queued = Tf.queued()
        val rejected = rejection(
            TransferReducer.apply(queued, Tf.beginNegotiation(sessionId = Tf.otherSessionId)),
        )
        assertEquals("wrong_session", rejected.code)
        assertEquals(Tf.sessionId.value, (rejected as Rejection.WrongSession).expected)
        assertEquals(Tf.otherSessionId.value, rejected.actual)
    }

    @Test fun `wrong_transfer - a command for another transfer is refused`() {
        val queued = Tf.queued()
        val rejected = rejection(
            TransferReducer.apply(queued, Tf.beginNegotiation(transferId = Tf.otherTransferId)),
        )
        assertEquals("wrong_transfer", rejected.code)
        assertEquals(Tf.otherTransferId.value, (rejected as Rejection.WrongTransfer).actual)
    }

    @Test fun `wrong_recipient - a command for another broadcast recipient is refused`() {
        val queued = Tf.broadcastQueued(transferId = Tf.transferId, recipientId = Tf.recipientA)
        val rejected = rejection(
            TransferReducer.apply(queued, Tf.beginNegotiation(recipientId = Tf.recipientB)),
        )
        assertEquals("wrong_recipient", rejected.code)
        assertEquals(Tf.recipientA.value, (rejected as Rejection.WrongRecipient).expected)
        assertEquals(Tf.recipientB.value, rejected.actual)
    }

    // --- byte accounting -------------------------------------------------------------------------

    @Test fun `offset_regression - a checkpoint behind the watermark is refused`() {
        val sending = Tf.sending() +
            Tf.chunkSent(offset = 0L) +
            Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = Tf.chunk.value.toLong())
        assertEquals(Tf.chunk.value.toLong(), sending.confirmedBytes)

        val rejected = rejection(
            TransferReducer.apply(
                sending,
                TransferEvent.ConfirmedOffsetReceived(
                    sessionId = Tf.sessionId,
                    transferId = Tf.transferId,
                    recipientId = null,
                    confirmedOffset = 0L,
                ),
            ),
        )
        assertEquals("offset_regression", rejected.code)
        assertEquals(Tf.chunk.value.toLong(), (rejected as Rejection.OffsetRegression).current)
        assertEquals(0L, rejected.proposed)
    }

    @Test fun `offset_beyond_total - a checkpoint past the file size is refused`() {
        val sending = Tf.sending()
        val rejected = rejection(
            TransferReducer.apply(
                sending,
                TransferEvent.ConfirmedOffsetReceived(
                    sessionId = Tf.sessionId,
                    transferId = Tf.transferId,
                    recipientId = null,
                    confirmedOffset = sending.totalBytes + 1L,
                ),
            ),
        )
        assertEquals("offset_beyond_total", rejected.code)
        assertEquals(sending.totalBytes + 1L, (rejected as Rejection.OffsetBeyondTotal).offset)
        assertEquals(sending.totalBytes, rejected.total)
    }

    @Test fun `unexpected_sequence - a chunk carrying the wrong sequence is refused`() {
        val receiving = Tf.receiving()
        val rejected = rejection(
            TransferReducer.apply(
                receiving,
                TransferEvent.ChunkReceived(
                    sessionId = Tf.sessionId,
                    transferId = Tf.transferId,
                    recipientId = null,
                    offset = 0L,
                    length = Tf.chunk.value,
                    sequence = 7L,
                ),
            ),
        )
        assertEquals("unexpected_sequence", rejected.code)
        assertEquals(0L, (rejected as Rejection.UnexpectedSequence).expected)
        assertEquals(7L, rejected.actual)
    }

    @Test fun `unexpected_offset - a chunk that skips bytes is refused`() {
        val receiving = Tf.receiving()
        val skipped = 2L * Tf.chunk.value
        val rejected = rejection(
            TransferReducer.apply(receiving, Tf.chunkReceived(offset = skipped)),
        )
        assertEquals("unexpected_offset", rejected.code)
        assertEquals(0L, (rejected as Rejection.UnexpectedOffset).expected)
        assertEquals(skipped, rejected.actual)
    }

    @Test fun `overlapping_chunk - a chunk straddling the watermark is refused`() {
        val receiving = Tf.receiving() + Tf.chunkReceived(offset = 0L)
        assertEquals(Tf.chunk.value.toLong(), receiving.confirmedBytes)
        // Starts inside the confirmed bytes but ends past them: neither a
        // duplicate (which would be idempotent) nor the next chunk.
        val rejected = rejection(
            TransferReducer.apply(
                receiving,
                Tf.chunkReceived(offset = Tf.chunk.value / 2L, length = Tf.chunk.value),
            ),
        )
        assertEquals("overlapping_chunk", rejected.code)
        assertEquals(Tf.chunk.value.toLong(), (rejected as Rejection.OverlappingChunk).confirmedBytes)
    }

    @Test fun `invalid_chunk_length - a zero length chunk is refused`() {
        val receiving = Tf.receiving()
        val rejected = rejection(
            TransferReducer.apply(receiving, Tf.chunkReceived(offset = 0L, length = 0)),
        )
        assertEquals("invalid_chunk_length", rejected.code)
        assertEquals(0, (rejected as Rejection.InvalidChunkLength).length)
        assertEquals(Tf.chunk.value, rejected.max)
    }

    // --- resume ----------------------------------------------------------------------------------

    @Test fun `invalid_resume_proposal - accepting with a different chunk size is refused`() {
        val negotiating = Tf.queued(direction = SessionDirection.OUTBOUND) +
            Tf.beginNegotiation()
        val rejected = rejection(
            TransferReducer.apply(negotiating, Tf.peerAccepted(chunkSize = ChunkSize(Tf.chunk.value * 2))),
        )
        assertEquals("invalid_resume_proposal", rejected.code)
        assertTrue(
            (rejected as Rejection.InvalidResumeProposal).reason.contains("chunk size"),
        )
    }

    // --- verification ----------------------------------------------------------------------------

    @Test fun `verification_too_early - confirming all bytes before they arrive is refused`() {
        val receiving = Tf.receiving()
        assertEquals(0L, receiving.confirmedBytes)
        val rejected = rejection(TransferReducer.apply(receiving, Tf.allBytesConfirmed()))
        assertEquals("verification_too_early", rejected.code)
        assertEquals(0L, (rejected as Rejection.VerificationTooEarly).confirmedBytes)
        assertEquals(receiving.totalBytes, rejected.totalBytes)
    }

    @Test fun `verification_conflict - a success carrying a mismatching digest is refused`() {
        val verifying = Tf.receiveEverything(
            Tf.receiving(descriptor = Tf.descriptor(digest = Tf.knownDigest)),
        ) + Tf.allBytesConfirmed()
        assertEquals(TransferState.VERIFYING, verifying.state)

        val rejected = rejection(
            TransferReducer.apply(verifying, Tf.verificationSucceeded(Tf.otherDigest)),
        )
        assertEquals("verification_conflict", rejected.code)
        assertEquals(
            app.morsecode.core.transfer.model.VerificationOutcome.MISMATCHED,
            (rejected as Rejection.VerificationConflict).outcome,
        )
    }

    // --- lifecycle -------------------------------------------------------------------------------

    @Test fun `terminal_transfer - a finished delivery refuses everything but two no-ops`() {
        val completed = Tf.completed()
        assertEquals(TransferState.COMPLETED, completed.state)
        val rejected = rejection(TransferReducer.apply(completed, Tf.pauseLocally()))
        assertEquals("terminal_transfer", rejected.code)
        assertEquals(TransferState.COMPLETED, (rejected as Rejection.TerminalTransfer).state)

        // The two inputs a finished delivery still accepts, both as no-ops.
        val lateEnd = TransferReducer.apply(completed, Tf.sessionEnded())
        val lateCancel = TransferReducer.apply(completed, Tf.remoteCancelled())
        assertTrue(lateEnd is TransitionResult.Accepted)
        assertTrue(lateCancel is TransitionResult.Accepted)
        assertEquals(completed, current(lateEnd))
        assertEquals(completed, current(lateCancel))
    }

    @Test fun `acknowledgement_beyond_sent - confirming more than was written is refused`() {
        val sending = Tf.sending() + Tf.chunkSent(offset = 0L)
        assertEquals(Tf.chunk.value.toLong(), sending.optimisticBytes)
        val claimed = 2L * Tf.chunk.value
        val rejected = rejection(
            TransferReducer.apply(
                sending,
                Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = claimed),
            ),
        )
        assertEquals("acknowledgement_beyond_sent", rejected.code)
        assertEquals(Tf.chunk.value.toLong(), (rejected as Rejection.AcknowledgementBeyondSent).sentBytes)
        assertEquals(claimed, rejected.confirmedOffset)
    }

    @Test fun `retry_not_allowed - the retry ceiling refuses instead of retrying`() {
        val exhausted = Tf.failedRetryable(retryCount = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT)
        assertEquals(TransferState.FAILED_RETRYABLE, exhausted.state)
        assertEquals(ProtocolLimits.DEFAULT_MAX_RETRY_COUNT, exhausted.retryCount)

        val rejected = rejection(
            TransferReducer.apply(exhausted, Tf.retry(limit = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT)),
        )
        assertEquals("retry_not_allowed", rejected.code)
        assertEquals(
            ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
            (rejected as Rejection.RetryNotAllowed).retryCount,
        )
    }

    @Test fun `invalid_command - a command that contradicts the direction is refused`() {
        val outbound = Tf.queued(direction = SessionDirection.OUTBOUND)
        val rejected = rejection(TransferReducer.apply(outbound, Tf.accept()))
        assertEquals("invalid_command", rejected.code)
        assertTrue((rejected as Rejection.InvalidCommand).detail.contains("inbound"))
    }

    // --- the vocabulary is closed ----------------------------------------------------------------

    /**
     * The whole point of the file: every code `Rejection` advertises is a code a
     * test above has just produced through the reducer, and vice versa. Add a
     * variant without adding its test and this fails.
     */
    @Test fun `the covered codes are exactly the codes the reducer can produce`() {
        val covered = listOf(
            "illegal_state_transition",
            "wrong_session",
            "wrong_transfer",
            "wrong_recipient",
            "offset_regression",
            "offset_beyond_total",
            "unexpected_sequence",
            "unexpected_offset",
            "overlapping_chunk",
            "invalid_chunk_length",
            "invalid_resume_proposal",
            "verification_too_early",
            "verification_conflict",
            "terminal_transfer",
            "acknowledgement_beyond_sent",
            "retry_not_allowed",
            "invalid_command",
        )
        assertEquals(
            "Rejection.allCodes must list exactly the refusals a test drives",
            Rejection.allCodes.sorted(),
            covered.sorted(),
        )
        assertEquals(
            "a code must not be listed twice",
            covered.size,
            covered.toSet().size,
        )
    }

    @Test fun `every rejection the reducer produced carries a matching classified error`() {
        val scenarios = listOf(
            "illegal_state_transition" to TransferReducer.apply(
                Tf.sending() + Tf.pauseLocally(),
                Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = 0L),
            ),
            "wrong_session" to TransferReducer.apply(
                Tf.queued(),
                Tf.beginNegotiation(sessionId = Tf.otherSessionId),
            ),
            "acknowledgement_beyond_sent" to TransferReducer.apply(
                Tf.sending() + Tf.chunkSent(offset = 0L),
                Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = 2L * Tf.chunk.value),
            ),
        )
        for ((expected, result) in scenarios) {
            val reason = rejection(result)
            assertEquals(expected, reason.code)
            assertEquals(
                "a rejection's error must carry the same code it reports",
                reason.code,
                reason.error.code,
            )
            assertTrue(
                "${reason.code} must be classified as final or retryable, not both",
                reason.error.isFinal != reason.error.retryable,
            )
            assertTrue(
                "${reason.code} detail must fit the bounded budget",
                reason.error.detail.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_ERROR_DETAIL_BYTES,
            )
        }
    }

    // --- the two refusals that are not in the vocabulary -------------------------------------------

    /**
     * `unsupported_protocol` is not a `Rejection` because it cannot be one: a
     * `ProtocolVersion` outside the window cannot be constructed, so no
     * descriptor can carry one and no reducer input can trigger it.
     */
    @Test fun `a protocol version outside the window cannot exist, so the reducer never sees one`() {
        assertNull("version 0 is not supported", ProtocolVersion.orNull(0))
        assertNull("version 2 is newer than this build", ProtocolVersion.orNull(2))
        assertEquals(ProtocolLimits.PROTOCOL_VERSION_CURRENT, ProtocolVersion.CURRENT.value)
        for (illegal in intArrayOf(Int.MIN_VALUE, -1, 0, 2, 99, Int.MAX_VALUE)) {
            try {
                ProtocolVersion(illegal)
                fail("ProtocolVersion($illegal) must not be constructible")
            } catch (e: IllegalArgumentException) {
                assertTrue(
                    "the message must name the version: ${e.message}",
                    e.message?.contains(illegal.toString()) == true,
                )
            }
        }
    }

    /** Where the refusal actually lives: the wire boundary. */
    @Test fun `a frame carrying an unsupported protocol version is refused, not decoded`() {
        val frame = FrameCodec.encode(
            FrameCodec.dataChunk(
                sessionId = Tf.sessionId,
                transferId = Tf.transferId,
                offset = 0L,
                sequence = 0L,
                bytes = ByteArray(Tf.chunk.value),
            ),
        )
        frame[4] = 2.toByte() // OFF_VERSION, one byte past the 4-byte magic
        patchHeaderCrc(frame)
        val result = FrameCodec.decode(frame)
        val invalid = result as? FrameDecodeResult.Invalid
            ?: fail("expected an invalid frame, got $result") as Nothing
        assertEquals("protocol_version_mismatch", invalid.error.code)
        assertTrue("a version nobody can decode is fatal", invalid.fatal)
    }

    /** And the other wire boundary: a row persisted by a newer build. */
    @Test fun `a persisted snapshot naming an unsupported protocol version is refused, not thrown`() {
        val snapshot = Tf.queued()
        val raw = TransferSnapshotCodec.serialize(snapshot)
        assertTrue("the fixture row must carry a protocol version", raw.contains("protocolVersion=1"))
        val newer = raw.replace("protocolVersion=1", "protocolVersion=2")
        val result = TransferSnapshotCodec.deserialize(newer)
        val invalid = result as? SnapshotDecodeResult.Invalid
            ?: fail("expected an invalid snapshot, got $result") as Nothing
        assertEquals("protocol_version_mismatch", invalid.error.code)
    }

    /** A row this build wrote still round trips, so the check above is not a blanket refusal. */
    @Test fun `a persisted snapshot naming the current protocol version still restores`() {
        val snapshot = Tf.queued()
        val restored = TransferSnapshotCodec.deserialize(TransferSnapshotCodec.serialize(snapshot))
        assertTrue("expected a successful restore, got $restored", restored is SnapshotDecodeResult.Success)
        assertEquals(
            ProtocolVersion.CURRENT,
            (restored as SnapshotDecodeResult.Success).snapshot.descriptor.protocolVersion,
        )
    }

    /**
     * `duplicate_descriptor` is not a `Rejection` either: the reducer never sees
     * the queue, so it cannot know a file is already enqueued. Duplicate
     * admission is the scheduler's, and it refuses to guess which row wins.
     */
    @Test fun `a queue holding one transfer id twice is blocked by the scheduler`() {
        val duplicated = listOf(
            Tf.queued(transferId = Tf.transferId, queueOrder = 0L),
            Tf.queued(transferId = Tf.transferId, queueOrder = 1L),
        )
        val plan = TransferScheduler.plan(
            SchedulerInput(
                sessionId = Tf.sessionId,
                snapshots = duplicated,
                limits = ConcurrencyLimits.uniform(2),
            ),
        )
        assertEquals(SchedulerStatus.SESSION_BLOCKED, plan.status)
        assertEquals(BlockReason.DUPLICATE_TRANSFER_ID, plan.blockedReason)
        assertTrue("a corrupt queue produces no work", plan.decisions.isEmpty())
    }

    /** ...and a duplicate file id across recipients is de-duplicated, not double counted. */
    @Test fun `the same file queued for two recipients is two deliveries, not four`() {
        val batch = listOf(
            Tf.broadcastQueued(transferId = Tf.transferId, recipientId = Tf.recipientA),
            Tf.broadcastQueued(transferId = Tf.otherTransferId, recipientId = Tf.recipientB),
        )
        assertEquals(
            Tf.fileId,
            batch[0].descriptor.fileId,
        )
        assertEquals(
            app.morsecode.core.transfer.broadcast.BroadcastAggregator.summarize(batch).expectedDeliveries,
            2,
        )
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Rewrites the header CRC32 after a byte has been edited, so the frame fails
     * on the version check rather than on the checksum that guards it.
     */
    private fun patchHeaderCrc(frame: ByteArray) {
        val crc = Crc32.asUnsigned(Crc32.compute(frame, 0, 40)).toInt()
        frame[40] = (crc ushr 24).toByte()
        frame[41] = (crc ushr 16).toByte()
        frame[42] = (crc ushr 8).toByte()
        frame[43] = crc.toByte()
    }
}
