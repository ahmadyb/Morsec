package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.current
import app.morsecode.core.transfer.effects
import app.morsecode.core.transfer.isAccepted
import app.morsecode.core.transfer.hasEffectOf
import app.morsecode.core.transfer.plus
import app.morsecode.core.transfer.rejection
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.protocol.ResumeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every legal transition, and a representative illegal one for each state.
 *
 * The companion `TransferTransitionTableTest` proves the table is total; this
 * class proves the reducer actually honours it and produces the effects later
 * milestones will execute.
 */
class TransferReducerTransitionTest {

    // --- enqueue and negotiation ---------------------------------------------

    @Test fun `enqueue creates a queued snapshot and asks for it to be persisted`() {
        val result = TransferReducer.enqueue(Tf.enqueueCommand())
        val snapshot = current(result)
        assertEquals(TransferState.QUEUED, snapshot.state)
        assertEquals(1L, snapshot.snapshotVersion)
        assertEquals(0L, snapshot.confirmedBytes)
        assertEquals(0L, snapshot.optimisticBytes)
        assertNull(snapshot.failure)
        assertTrue(effects(result).hasEffectOf(TransferEffect.PersistSnapshot::class))
        assertTrue(effects(result).hasEffectOf(TransferEffect.NotifyUi::class))
    }

    @Test fun `each delivery gets its own identity and queue position`() {
        val first = current(TransferReducer.enqueue(Tf.enqueueCommand(queueOrder = 3L)))
        val second = current(
            TransferReducer.enqueue(
                Tf.enqueueCommand(transferId = Tf.otherTransferId, queueOrder = 7L),
            ),
        )
        assertEquals(3L, first.queueOrder)
        assertEquals(7L, second.queueOrder)
        assertTrue(first.transferId != second.transferId)
    }

    @Test fun `enqueue refuses a negative queue position`() {
        val result = TransferReducer.enqueue(Tf.enqueueCommand(queueOrder = -1L))
        assertEquals("invalid_command", rejection(result).code)
    }

    @Test fun `outbound negotiation sends a handshake`() {
        val queued = Tf.queued(direction = SessionDirection.OUTBOUND)
        val result = TransferReducer.apply(queued, Tf.beginNegotiation())
        assertEquals(TransferState.NEGOTIATING, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendHandshake::class))
    }

    @Test fun `inbound negotiation waits for the peer instead of handshaking`() {
        val queued = Tf.queued(direction = SessionDirection.INBOUND)
        val result = TransferReducer.apply(queued, Tf.beginNegotiation())
        assertEquals(TransferState.NEGOTIATING, current(result).state)
        assertFalse(effects(result).hasEffectOf(TransferEffect.SendHandshake::class))
    }

    @Test fun `peer acceptance starts sending and asks for the source stream`() {
        val negotiating = Tf.queued() + Tf.beginNegotiation()
        val result = TransferReducer.apply(negotiating, Tf.peerAccepted())
        assertEquals(TransferState.SENDING, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendMetadata::class))
        assertTrue(effects(result).hasEffectOf(TransferEffect.RequestSourceStream::class))
    }

    @Test fun `a peer that accepts with a different chunk size is refused`() {
        val negotiating = Tf.queued() + Tf.beginNegotiation()
        val result = TransferReducer.apply(
            negotiating,
            TransferEvent.PeerAccepted(
                Tf.sessionId, Tf.transferId, null,
                app.morsecode.core.transfer.identity.ChunkSize.MAX,
            ),
        )
        assertEquals("invalid_resume_proposal", rejection(result).code)
    }

    @Test fun `accepting an inbound offer starts receiving and opens the partial file`() {
        val negotiating = Tf.queued(direction = SessionDirection.INBOUND) + Tf.beginNegotiation()
        val result = TransferReducer.apply(negotiating, Tf.accept())
        assertEquals(TransferState.RECEIVING, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.RequestDestinationPartial::class))
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendHandshakeAccept::class))
    }

    @Test fun `accept is refused on an outbound delivery`() {
        val negotiating = Tf.queued() + Tf.beginNegotiation()
        val result = TransferReducer.apply(negotiating, Tf.accept())
        assertEquals("invalid_command", rejection(result).code)
    }

    @Test fun `rejecting an inbound offer skips the delivery and tells the peer`() {
        val negotiating = Tf.queued(direction = SessionDirection.INBOUND) + Tf.beginNegotiation()
        val result = TransferReducer.apply(negotiating, Tf.reject("not now"))
        assertEquals(TransferState.SKIPPED, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendHandshakeReject::class))
    }

    // --- moving bytes ---------------------------------------------------------

    @Test fun `sending a chunk advances optimistic progress only`() {
        val sending = Tf.sending()
        val result = TransferReducer.apply(sending, Tf.chunkSent(0L))
        val snapshot = current(result)
        assertEquals(4_096L, snapshot.optimisticBytes)
        assertEquals(0L, snapshot.confirmedBytes)
        assertEquals(4_096L, snapshot.inFlightBytes)
    }

    @Test fun `acknowledging a chunk advances confirmed progress and checkpoints it`() {
        var sending = Tf.sending()
        sending = sending + Tf.chunkSent(0L)
        val result = TransferReducer.apply(sending, Tf.chunkAck(0L, 4_096, 4_096L))
        val snapshot = current(result)
        assertEquals(4_096L, snapshot.confirmedBytes)
        assertEquals(4_096L, snapshot.optimisticBytes)
        assertEquals(0L, snapshot.inFlightBytes)
        assertEquals(0L, snapshot.lastAcknowledgedSequence)
        assertTrue(effects(result).hasEffectOf(TransferEffect.PersistConfirmedOffset::class))
    }

    @Test fun `a duplicate acknowledgement is idempotent`() {
        var sending = Tf.sending()
        sending = sending + Tf.chunkSent(0L)
        sending = sending + Tf.chunkAck(0L, 4_096, 4_096L)
        val before = sending.snapshotVersion
        val result = TransferReducer.apply(sending, Tf.chunkAck(0L, 4_096, 4_096L))
        assertTrue(isAccepted(result))
        assertEquals(4_096L, current(result).confirmedBytes)
        assertEquals(before + 1L, current(result).snapshotVersion)
    }

    @Test fun `optimistic bytes are never promoted to confirmed without an acknowledgement`() {
        var sending = Tf.sending()
        sending = sending + Tf.chunkSent(0L)
        sending = sending + Tf.chunkSent(4_096L)
        assertEquals(8_192L, sending.optimisticBytes)
        assertEquals(0L, sending.confirmedBytes)
        // An interruption must discard the optimistic part, not adopt it.
        val disconnected = sending + Tf.transportDisconnected()
        assertEquals(0L, disconnected.optimisticBytes)
        assertEquals(0L, disconnected.confirmedBytes)
        assertEquals(TransferState.FAILED_RETRYABLE, disconnected.state)
    }

    @Test fun `receiving a chunk advances confirmed progress and acknowledges it`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(receiving, Tf.chunkReceived(0L))
        assertEquals(4_096L, current(result).confirmedBytes)
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendAcknowledgement::class))
    }

    @Test fun `a duplicate already confirmed chunk is re acknowledged without advancing`() {
        var receiving = Tf.receiving()
        receiving = receiving + Tf.chunkReceived(0L)
        val result = TransferReducer.apply(receiving, Tf.chunkReceived(0L))
        assertEquals(4_096L, current(result).confirmedBytes)
        assertEquals(receiving.snapshotVersion, current(result).snapshotVersion)
        val ack = effects(result).filterIsInstance<TransferEffect.SendAcknowledgement>().first()
        assertEquals(4_096L, ack.confirmedOffset)
    }

    @Test fun `a stale chunk below the confirmed offset is re acknowledged idempotently`() {
        var receiving = Tf.receiving()
        receiving = receiving + Tf.chunkReceived(0L)
        receiving = receiving + Tf.chunkReceived(4_096L)
        val result = TransferReducer.apply(receiving, Tf.chunkReceived(0L))
        assertTrue(isAccepted(result))
        assertEquals(8_192L, current(result).confirmedBytes)
    }

    @Test fun `a chunk ahead of the confirmed offset is refused`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(receiving, Tf.chunkReceived(4_096L))
        assertEquals("unexpected_offset", rejection(result).code)
    }

    @Test fun `an overlapping chunk is refused`() {
        var receiving = Tf.receiving()
        receiving = receiving + Tf.chunkReceived(0L)
        // Starts inside the confirmed range but ends past it.
        val result = TransferReducer.apply(
            receiving,
            TransferEvent.ChunkReceived(
                Tf.sessionId, Tf.transferId, null,
                offset = 2_048L, length = 4_096, sequence = 0L,
            ),
        )
        assertEquals("overlapping_chunk", rejection(result).code)
    }

    @Test fun `a chunk whose sequence disagrees with its offset is refused`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(
            receiving,
            TransferEvent.ChunkReceived(
                Tf.sessionId, Tf.transferId, null,
                offset = 0L, length = 4_096, sequence = 7L,
            ),
        )
        assertEquals("unexpected_sequence", rejection(result).code)
    }

    @Test fun `a chunk past the end of the file is refused`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(
            receiving,
            TransferEvent.ChunkSent(
                Tf.sessionId, Tf.transferId, null,
                offset = Tf.THREE_CHUNKS, length = 4_096, sequence = 3L,
            ),
        )
        assertEquals("illegal_state_transition", rejection(result).code)
    }

    @Test fun `a zero length chunk is refused`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(receiving, Tf.chunkReceived(0L, 0))
        assertEquals("invalid_chunk_length", rejection(result).code)
    }

    // --- verification and completion ------------------------------------------

    @Test fun `all bytes confirmed moves to verifying and asks for the check`() {
        val receiving = Tf.receiveEverything(Tf.receiving())
        assertEquals(receiving.totalBytes, receiving.confirmedBytes)
        val result = TransferReducer.apply(receiving, Tf.allBytesConfirmed())
        assertEquals(TransferState.VERIFYING, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.BeginVerification::class))
    }

    @Test fun `verification cannot begin before every byte is confirmed`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(receiving, Tf.allBytesConfirmed())
        assertEquals("verification_too_early", rejection(result).code)
    }

    @Test fun `a matching digest completes the delivery and commits the destination`() {
        val verifying = Tf.receiveEverything(Tf.receiving()) + Tf.allBytesConfirmed()
        val result = TransferReducer.apply(
            verifying,
            TransferEvent.VerificationSucceeded(
                Tf.sessionId, Tf.transferId, null, Sha256Accumulator.EMPTY,
            ),
        )
        assertEquals(TransferState.COMPLETED, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.CommitVerifiedDestination::class))
        assertTrue(current(result).isInternallyConsistent())
    }

    @Test fun `a mismatched digest never produces a completed delivery`() {
        val digest = Tf.knownDigest
        val verifying = Tf.receiveEverything(
            Tf.receiving(descriptor = Tf.descriptor(digest = digest)),
        ) + Tf.allBytesConfirmed()
        val succeeded = TransferReducer.apply(
            verifying,
            TransferEvent.VerificationSucceeded(
                Tf.sessionId, Tf.transferId, null, Sha256Accumulator.EMPTY,
            ),
        )
        assertEquals("verification_conflict", rejection(succeeded).code)

        val failed = TransferReducer.apply(
            verifying,
            TransferEvent.VerificationFailed(
                Tf.sessionId, Tf.transferId, null,
                expectedDigest = digest,
                observedDigest = Sha256Accumulator.EMPTY,
            ),
        )
        assertEquals(TransferState.FAILED_RETRYABLE, current(failed).state)
        assertFalse(effects(failed).hasEffectOf(TransferEffect.CommitVerifiedDestination::class))
        assertEquals("file_checksum_mismatch", current(failed).failure?.code)
    }

    @Test fun `cancelling during verification has one deterministic outcome`() {
        val verifying = Tf.receiveEverything(Tf.receiving()) + Tf.allBytesConfirmed()
        val result = TransferReducer.apply(verifying, Tf.cancelLocally())
        val snapshot = current(result)
        assertEquals(TransferState.CANCELLED, snapshot.state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.DeletePartialDestination::class))
        assertFalse(effects(result).hasEffectOf(TransferEffect.CommitVerifiedDestination::class))
        assertTrue(snapshot.failure is TransferError.CancelledLocally)
    }

    @Test fun `a zero byte file follows the empty verification path`() {
        val sending = Tf.sending(descriptor = Tf.descriptor(totalBytes = 0L))
        assertTrue(sending.allBytesConfirmed)
        val verifying = sending + Tf.allBytesConfirmed()
        assertEquals(TransferState.VERIFYING, verifying.state)
        val result = TransferReducer.apply(
            verifying,
            TransferEvent.VerificationSucceeded(
                Tf.sessionId, Tf.transferId, null, Sha256Accumulator.EMPTY,
            ),
        )
        assertEquals(TransferState.COMPLETED, current(result).state)
        assertEquals(0L, current(result).totalBytes)
        assertTrue(current(result).isInternallyConsistent())
    }

    @Test fun `a zero byte file with a known digest must agree with it`() {
        val verifying = Tf.sending(
            descriptor = Tf.descriptor(totalBytes = 0L, digest = Tf.knownDigest),
        ) + Tf.allBytesConfirmed()
        val result = TransferReducer.apply(
            verifying,
            TransferEvent.VerificationSucceeded(
                Tf.sessionId, Tf.transferId, null, Sha256Accumulator.EMPTY,
            ),
        )
        assertEquals("verification_conflict", rejection(result).code)
    }

    // --- pause and resume ------------------------------------------------------

    @Test fun `pausing preserves the confirmed offset and discards optimistic bytes`() {
        var sending = Tf.sending()
        sending = sending + Tf.chunkSent(0L)
        sending = sending + Tf.chunkAck(0L, 4_096, 4_096L)
        sending = sending + Tf.chunkSent(4_096L)
        assertEquals(8_192L, sending.optimisticBytes)
        val result = TransferReducer.apply(sending, Tf.pauseLocally())
        val snapshot = current(result)
        assertEquals(TransferState.PAUSED_LOCAL, snapshot.state)
        assertEquals(4_096L, snapshot.confirmedBytes)
        assertEquals(4_096L, snapshot.optimisticBytes)
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendPause::class))
    }

    @Test fun `resuming starts from the confirmed offset not the optimistic one`() {
        var sending = Tf.sending()
        sending = sending + Tf.chunkSent(0L)
        sending = sending + Tf.chunkAck(0L, 4_096, 4_096L)
        sending = sending + Tf.chunkSent(4_096L)
        val paused = sending + Tf.pauseLocally()
        val result = TransferReducer.apply(paused, Tf.resumeLocally())
        val snapshot = current(result)
        assertEquals(TransferState.NEGOTIATING, snapshot.state)
        assertEquals(4_096L, snapshot.confirmedBytes)
        assertEquals(4_096L, snapshot.optimisticBytes)
        val resume = effects(result).filterIsInstance<TransferEffect.SendResume>().first()
        assertEquals(4_096L, resume.fromOffset)
    }

    @Test fun `local and remote pause stay distinguishable`() {
        val sending = Tf.sending()
        val remote = sending + Tf.remotePaused()
        assertEquals(TransferState.PAUSED_REMOTE, remote.state)
        assertTrue(remote.remotePaused)
        val local = remote + Tf.pauseLocally()
        assertEquals(TransferState.PAUSED_LOCAL, local.state)
        assertTrue(local.remotePaused)
    }

    @Test fun `resuming a locally paused delivery the peer also paused returns to remote pause`() {
        var sending = Tf.sending()
        sending = sending + Tf.remotePaused()
        sending = sending + Tf.pauseLocally()
        val resumed = sending + Tf.resumeLocally()
        assertEquals(TransferState.PAUSED_REMOTE, resumed.state)
        // The peer has not resumed yet, so the delivery is still waiting on it.
        assertTrue(resumed.remotePaused)
        // When the peer finally resumes, negotiation reopens the delivery.
        assertEquals(TransferState.NEGOTIATING, (resumed + Tf.remoteResumed()).state)
    }

    @Test fun `resume on a remote paused delivery asks the peer instead of pushing bytes`() {
        val pausedRemotely = Tf.sending() + Tf.remotePaused()
        val result = TransferReducer.apply(pausedRemotely, Tf.resumeLocally())
        assertEquals(TransferState.PAUSED_REMOTE, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendResume::class))
        assertFalse(effects(result).hasEffectOf(TransferEffect.RequestSourceStream::class))
    }

    @Test fun `the peer resuming reopens negotiation`() {
        val pausedRemotely = Tf.sending() + Tf.remotePaused()
        val result = TransferReducer.apply(pausedRemotely, Tf.remoteResumed())
        assertEquals(TransferState.NEGOTIATING, current(result).state)
        assertFalse(current(result).remotePaused)
    }

    // --- failure, retry, cancel, skip ------------------------------------------

    @Test fun `a transport drop is retryable and keeps the confirmed offset`() {
        var sending = Tf.sending()
        sending = sending + Tf.chunkSent(0L)
        sending = sending + Tf.chunkAck(0L, 4_096, 4_096L)
        val result = TransferReducer.apply(sending, Tf.transportDisconnected())
        val snapshot = current(result)
        assertEquals(TransferState.FAILED_RETRYABLE, snapshot.state)
        assertEquals(4_096L, snapshot.confirmedBytes)
        assertTrue(snapshot.failure?.retryable == true)
        assertTrue(effects(result).hasEffectOf(TransferEffect.ScheduleNextEligibleItem::class))
    }

    @Test fun `a dropped transport on a queued delivery changes nothing`() {
        val queued = Tf.queued()
        val result = TransferReducer.apply(queued, Tf.transportDisconnected())
        assertEquals(TransferState.QUEUED, current(result).state)
    }

    @Test fun `the transport coming back reconsiders retryable work`() {
        val failed = Tf.sending() + Tf.transportDisconnected()
        val result = TransferReducer.apply(failed, Tf.transportRestored())
        assertTrue(isAccepted(result))
        assertEquals(TransferState.FAILED_RETRYABLE, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.ScheduleNextEligibleItem::class))
    }

    @Test fun `a final storage failure ends the delivery`() {
        val sending = Tf.sending()
        val result = TransferReducer.apply(
            sending,
            TransferEvent.StorageFailed(
                Tf.sessionId, Tf.transferId, null,
                TransferError.PermissionRevoked("grant revoked"),
            ),
        )
        assertEquals(TransferState.FAILED_FINAL, current(result).state)
        assertFalse(current(result).failure?.retryable == true)
    }

    @Test fun `a retryable storage failure can be retried`() {
        val sending = Tf.sending()
        val result = TransferReducer.apply(
            sending,
            TransferEvent.StorageFailed(
                Tf.sessionId, Tf.transferId, null,
                TransferError.StorageFull(1_000L),
            ),
        )
        assertEquals(TransferState.FAILED_RETRYABLE, current(result).state)
    }

    @Test fun `permission revocation is final`() {
        val sending = Tf.sending()
        val result = TransferReducer.apply(sending, Tf.permissionRevoked())
        assertEquals(TransferState.FAILED_FINAL, current(result).state)
        assertEquals("permission_revoked", current(result).failure?.code)
    }

    @Test fun `a protocol failure is classified by the error it carries`() {
        val retryable = TransferReducer.apply(
            Tf.sending(),
            TransferEvent.ProtocolFailed(
                Tf.sessionId, Tf.transferId, null,
                TransferError.UnexpectedSequence(1L, 4L),
            ),
        )
        assertEquals(TransferState.FAILED_RETRYABLE, current(retryable).state)
        val final = TransferReducer.apply(
            Tf.sending(),
            TransferEvent.ProtocolFailed(
                Tf.sessionId, Tf.transferId, null,
                TransferError.MalformedFrame("bad magic"),
            ),
        )
        assertEquals(TransferState.FAILED_FINAL, current(final).state)
    }

    @Test fun `retry re-queues the delivery and counts the attempt`() {
        val failed = Tf.sending() + Tf.transportDisconnected()
        val result = TransferReducer.apply(failed, Tf.retry())
        val snapshot = current(result)
        assertEquals(TransferState.QUEUED, snapshot.state)
        assertEquals(1, snapshot.retryCount)
        assertNull(snapshot.failure)
        assertEquals(failed.confirmedBytes, snapshot.confirmedBytes)
    }

    @Test fun `retry is refused once the ceiling is reached`() {
        var snapshot = Tf.sending() + Tf.transportDisconnected()
        for (attempt in 0 until ProtocolLimits.DEFAULT_MAX_RETRY_COUNT) {
            snapshot = snapshot + Tf.retry()
            snapshot = snapshot + Tf.beginNegotiation()
            snapshot = snapshot + TransferEvent.TransportDisconnected(
                Tf.sessionId, Tf.transferId, null, "again",
            )
        }
        assertEquals(ProtocolLimits.DEFAULT_MAX_RETRY_COUNT, snapshot.retryCount)
        val result = TransferReducer.apply(snapshot, Tf.retry())
        assertEquals("retry_not_allowed", rejection(result).code)
    }

    @Test fun `skip moves a queued delivery straight to skipped`() {
        val result = TransferReducer.apply(Tf.queued(), Tf.skip())
        assertEquals(TransferState.SKIPPED, current(result).state)
    }

    @Test fun `local cancellation tells the peer and deletes the partial file`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(receiving, Tf.cancelLocally())
        assertEquals(TransferState.CANCELLED, current(result).state)
        assertTrue(effects(result).hasEffectOf(TransferEffect.SendCancel::class))
        assertTrue(effects(result).hasEffectOf(TransferEffect.DeletePartialDestination::class))
    }

    @Test fun `remote cancellation does not send a cancel back`() {
        val receiving = Tf.receiving()
        val result = TransferReducer.apply(receiving, Tf.remoteCancelled())
        assertEquals(TransferState.CANCELLED, current(result).state)
        assertFalse(effects(result).hasEffectOf(TransferEffect.SendCancel::class))
        assertTrue(current(result).failure is TransferError.CancelledRemotely)
    }

    @Test fun `ending the session cancels what is left`() {
        val sending = Tf.sending()
        val result = TransferReducer.apply(sending, Tf.sessionEnded())
        assertEquals(TransferState.CANCELLED, current(result).state)
    }

    // --- terminal states --------------------------------------------------------

    @Test fun `a completed delivery refuses every command`() {
        val completed = Tf.completed()
        for (command in listOf(
            Tf.pauseLocally(),
            Tf.resumeLocally(),
            Tf.cancelLocally(),
            Tf.retry(),
            Tf.beginNegotiation(),
        )) {
            val result = TransferReducer.apply(completed, command)
            assertEquals(
                "${command.commandName} must be refused",
                "terminal_transfer",
                rejection(result).code,
            )
            assertTrue(result.effectsOrEmpty.isEmpty())
        }
    }

    @Test fun `terminal states expose no pause resume or cancel effect`() {
        val terminal = listOf(
            Tf.completed(),
            Tf.queued() + Tf.cancelLocally(),
            Tf.queued() + Tf.skip(),
            Tf.sending() + Tf.permissionRevoked(),
        )
        for (snapshot in terminal) {
            assertTrue(snapshot.state.isTerminal)
            for (command in listOf(Tf.pauseLocally(), Tf.resumeLocally(), Tf.cancelLocally())) {
                assertTrue(TransferReducer.apply(snapshot, command).effectsOrEmpty.isEmpty())
            }
        }
    }

    @Test fun `a duplicate session end on a completed delivery is a no-op`() {
        val completed = Tf.completed()
        val result = TransferReducer.apply(completed, Tf.sessionEnded())
        assertTrue(isAccepted(result))
        assertEquals(TransferState.COMPLETED, current(result).state)
        assertEquals(completed.snapshotVersion, current(result).snapshotVersion)
        assertTrue(result.effectsOrEmpty.isEmpty())
    }

    @Test fun `a duplicate remote cancellation on a completed delivery is a no-op`() {
        val completed = Tf.completed()
        val result = TransferReducer.apply(completed, Tf.remoteCancelled())
        assertTrue(isAccepted(result))
        assertEquals(TransferState.COMPLETED, current(result).state)
    }

    // --- resume decisions --------------------------------------------------------

    @Test fun `a resume decision moves the confirmed offset forward on the sender`() {
        val negotiating = Tf.queued() + Tf.beginNegotiation()
        val result = TransferReducer.apply(negotiating, Tf.applyResume(ResumeDecision.ResumeAt(8_192L)))
        assertEquals(8_192L, current(result).confirmedBytes)
        assertEquals(8_192L, current(result).optimisticBytes)
        assertEquals(TransferState.NEGOTIATING, current(result).state)
    }

    @Test fun `a receiver cannot adopt an offset beyond its own confirmed bytes`() {
        val negotiating = Tf.queued(direction = SessionDirection.INBOUND) + Tf.beginNegotiation()
        val result = TransferReducer.apply(negotiating, Tf.applyResume(ResumeDecision.ResumeAt(1L)))
        assertEquals("invalid_resume_proposal", rejection(result).code)
    }

    @Test fun `a resume proposal behind the confirmed offset is refused`() {
        var sending = Tf.sending()
        sending = sending + Tf.chunkSent(0L)
        sending = sending + Tf.chunkAck(0L, 4_096, 4_096L)
        val negotiating = sending + Tf.pauseLocally() + Tf.resumeLocally()
        val result = TransferReducer.apply(negotiating, Tf.applyResume(ResumeDecision.ResumeAt(0L)))
        assertEquals("offset_regression", rejection(result).code)
    }

    @Test fun `a resume offset past the end of the file is refused`() {
        val negotiating = Tf.queued() + Tf.beginNegotiation()
        val result = TransferReducer.apply(
            negotiating,
            Tf.applyResume(ResumeDecision.ResumeAt(Tf.THREE_CHUNKS + 1L)),
        )
        assertEquals("offset_beyond_total", rejection(result).code)
    }

    @Test fun `restarting from zero deletes the partial file and reopens the streams`() {
        var receiving = Tf.receiving()
        receiving = receiving + Tf.chunkReceived(0L)
        val negotiating = receiving + Tf.pauseLocally() + Tf.resumeLocally()
        val result = TransferReducer.apply(
            negotiating,
            Tf.applyResume(ResumeDecision.RestartAtZero("descriptor changed")),
        )
        val snapshot = current(result)
        assertEquals(0L, snapshot.confirmedBytes)
        assertEquals(0L, snapshot.optimisticBytes)
        assertTrue(effects(result).hasEffectOf(TransferEffect.DeletePartialDestination::class))
        assertTrue(effects(result).hasEffectOf(TransferEffect.RequestDestinationPartial::class))
    }

    @Test fun `an already verified file completes the outbound delivery`() {
        val negotiating = Tf.queued(descriptor = Tf.descriptor(digest = Tf.knownDigest)) +
            Tf.beginNegotiation()
        val result = TransferReducer.apply(
            negotiating,
            Tf.applyResume(ResumeDecision.AlreadyVerified),
        )
        assertEquals(TransferState.COMPLETED, current(result).state)
        assertEquals(Tf.THREE_CHUNKS, current(result).confirmedBytes)
        assertEquals(
            app.morsecode.core.transfer.model.VerificationOutcome.MATCHED,
            current(result).verification?.outcome,
        )
    }

    @Test fun `a file with no known digest is never treated as already verified`() {
        val negotiating = Tf.queued() + Tf.beginNegotiation()
        val result = TransferReducer.apply(
            negotiating,
            Tf.applyResume(ResumeDecision.AlreadyVerified),
        )
        assertEquals("invalid_resume_proposal", rejection(result).code)
    }

    @Test fun `a rejected resume decision ends the delivery`() {
        val negotiating = Tf.queued() + Tf.beginNegotiation()
        val result = TransferReducer.apply(
            negotiating,
            Tf.applyResume(
                ResumeDecision.Reject(TransferError.DescriptorMismatch("relativePath")),
            ),
        )
        assertEquals(TransferState.FAILED_FINAL, current(result).state)
        assertEquals("descriptor_mismatch", current(result).failure?.code)
    }

    // --- identity guards ----------------------------------------------------------

    @Test fun `an input for another session is refused`() {
        val queued = Tf.queued()
        val result = TransferReducer.apply(
            queued,
            TransferCommand.BeginNegotiation(Tf.otherSessionId, Tf.transferId, null),
        )
        assertEquals("wrong_session", rejection(result).code)
    }

    @Test fun `an input for another transfer is refused`() {
        val queued = Tf.queued()
        val result = TransferReducer.apply(
            queued,
            TransferCommand.BeginNegotiation(Tf.sessionId, Tf.otherTransferId, null),
        )
        assertEquals("wrong_transfer", rejection(result).code)
    }

    @Test fun `an input for another recipient is refused`() {
        val queued = Tf.queued(recipientId = Tf.recipientA)
        val result = TransferReducer.apply(
            queued,
            TransferCommand.BeginNegotiation(Tf.sessionId, Tf.transferId, Tf.recipientB),
        )
        assertEquals("wrong_recipient", rejection(result).code)
    }

    @Test fun `a delivery with no recipient refuses one that names one`() {
        val queued = Tf.queued()
        val result = TransferReducer.apply(
            queued,
            TransferCommand.BeginNegotiation(Tf.sessionId, Tf.transferId, Tf.recipientA),
        )
        assertEquals("wrong_recipient", rejection(result).code)
    }

    // --- bookkeeping ---------------------------------------------------------------

    @Test fun `every accepted transition advances the snapshot version`() {
        var snapshot = Tf.queued()
        var version = snapshot.snapshotVersion
        snapshot = snapshot + Tf.beginNegotiation()
        assertEquals(version + 1L, snapshot.snapshotVersion)
        version = snapshot.snapshotVersion
        snapshot = snapshot + Tf.peerAccepted()
        assertEquals(version + 1L, snapshot.snapshotVersion)
        version = snapshot.snapshotVersion
        snapshot = snapshot + Tf.chunkSent(0L)
        assertEquals(version + 1L, snapshot.snapshotVersion)
    }

    @Test fun `an idempotent no-op does not advance the snapshot version`() {
        val sending = Tf.sending() + Tf.chunkSent(0L)
        val duplicate = sending + Tf.chunkSent(0L)
        assertEquals(sending.snapshotVersion, duplicate.snapshotVersion)
        assertEquals(sending.optimisticBytes, duplicate.optimisticBytes)
    }

    @Test fun `a rejected transition leaves the snapshot byte for byte identical`() {
        val queued = Tf.queued()
        val result = TransferReducer.apply(queued, Tf.allBytesConfirmed())
        assertEquals("verification_too_early", rejection(result).code)
        assertEquals(queued, (result as TransitionResult.Rejected).snapshot)
    }

}
