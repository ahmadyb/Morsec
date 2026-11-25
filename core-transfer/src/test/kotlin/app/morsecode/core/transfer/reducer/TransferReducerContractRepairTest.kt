package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.current
import app.morsecode.core.transfer.effects
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.persistence.SnapshotDecodeResult
import app.morsecode.core.transfer.persistence.TransferSnapshotCodec
import app.morsecode.core.transfer.plus
import app.morsecode.core.transfer.protocol.ResumeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure reducer/effect-contract tests: no executor or production transport is installed. */
class TransferReducerContractRepairTest {
    private val descriptor = Tf.descriptor(totalBytes = 8_192L, digest = Tf.knownDigest)

    private fun receiving(): TransferSnapshot = Tf.receiving(descriptor = descriptor)

    private fun receiverVerifying(): TransferSnapshot = Tf.receiveEverything(receiving()) + Tf.allBytesConfirmed()

    private fun receiverVerified(): TransferSnapshot = receiverVerifying() +
        TransferEvent.VerificationSucceeded(Tf.sessionId, Tf.transferId, null, Tf.knownDigest)

    private fun senderVerifying(): TransferSnapshot = Tf.sendEverything(Tf.sending(descriptor = descriptor)) +
        Tf.allBytesConfirmed()

    private fun senderVerified(): TransferSnapshot = senderVerifying() +
        TransferEvent.VerificationSucceeded(
            Tf.sessionId, Tf.transferId, null, Tf.knownDigest, reportedTotalBytes = descriptor.totalBytes,
        )

    private fun fileComplete(
        sessionId: app.morsecode.core.transfer.identity.SessionId = Tf.sessionId,
        transferId: app.morsecode.core.transfer.identity.TransferId = Tf.transferId,
        recipientId: app.morsecode.core.transfer.identity.RecipientId? = null,
        fileId: app.morsecode.core.transfer.identity.FileId = Tf.fileId,
        totalBytes: Long = descriptor.totalBytes,
        digest: app.morsecode.core.transfer.integrity.Sha256Digest? = Tf.knownDigest,
    ): TransferEvent.FileCompleteReceived =
        TransferEvent.FileCompleteReceived(sessionId, transferId, recipientId, fileId, totalBytes, digest)

    private fun persisted(candidate: Long) =
        TransferEvent.ConfirmedOffsetPersisted(Tf.sessionId, Tf.transferId, null, candidate)

    private fun persistenceFailed(candidate: Long, storageFull: Boolean = false) =
        TransferEvent.ConfirmedOffsetPersistenceFailed(Tf.sessionId, Tf.transferId, null, candidate, storageFull)

    private fun assertRejected(result: TransitionResult) {
        assertTrue("expected typed refusal, got $result", result is TransitionResult.Rejected)
    }

    @Test fun `receiver verification remains provisional and emits commit only`() {
        val result = TransferReducer.apply(
            receiverVerifying(),
            TransferEvent.VerificationSucceeded(Tf.sessionId, Tf.transferId, null, Tf.knownDigest),
        )
        val provisional = current(result)
        assertEquals(TransferState.VERIFYING, provisional.state)
        assertFalse(provisional.isTerminal)
        assertEquals(Tf.knownDigest, provisional.verification?.observedDigest)
        assertEquals(
            listOf(TransferEffect.CommitVerifiedDestination(Tf.transferId, provisional.snapshotVersion)),
            effects(result),
        )
        assertRejected(TransferReducer.apply(provisional, Tf.verificationSucceeded(Tf.knownDigest)))
        assertFalse(effects(result).any { it is TransferEffect.SendVerificationResult || it is TransferEffect.SendFileComplete })

        val restored = TransferSnapshotCodec.deserialize(TransferSnapshotCodec.serialize(provisional))
            as SnapshotDecodeResult.Success
        assertEquals(provisional, restored.snapshot)
    }

    @Test fun `commit success persists both revisions then sends success completion and UI in order`() {
        val provisional = receiverVerified()
        val result = TransferReducer.apply(
            provisional,
            TransferEvent.DestinationCommitSucceeded(Tf.sessionId, Tf.transferId, null),
        )
        val completed = current(result)
        assertEquals(TransferState.COMPLETED, completed.state)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, provisional.snapshotVersion),
                TransferEffect.PersistSnapshot(Tf.transferId, completed.snapshotVersion),
                TransferEffect.SendVerificationResult(Tf.transferId, true, descriptor.totalBytes, Tf.knownDigest),
                TransferEffect.SendFileComplete(Tf.transferId, Tf.fileId, descriptor.totalBytes, Tf.knownDigest),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.COMPLETED),
                TransferEffect.ScheduleNextEligibleItem(Tf.sessionId),
            ),
            effects(result),
        )
        // A post-commit storage or protocol failure cannot undo the published file.
        val postCommitFailure = TransferReducer.apply(
            completed,
            TransferEvent.StorageFailed(Tf.sessionId, Tf.transferId, null, TransferError.StorageFull(1L)),
        )
        assertRejected(postCommitFailure)
        assertEquals(completed, (postCommitFailure as TransitionResult.Rejected).snapshot)
        assertTrue(effects(TransferReducer.apply(completed, Tf.remoteCancelled())).isEmpty())
    }

    @Test fun `commit failure remains legal and sends failure but never completion`() {
        val provisional = receiverVerified()
        val result = TransferReducer.apply(
            provisional,
            TransferEvent.DestinationCommitFailed(Tf.sessionId, Tf.transferId, null),
        )
        val failed = current(result)
        assertEquals(TransferState.FAILED_RETRYABLE, failed.state)
        assertEquals("destination_unavailable", failed.failure?.code)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, provisional.snapshotVersion),
                TransferEffect.PersistSnapshot(Tf.transferId, failed.snapshotVersion),
                TransferEffect.SendVerificationResult(Tf.transferId, false, descriptor.totalBytes, null),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.FAILED_RETRYABLE),
                TransferEffect.ScheduleNextEligibleItem(Tf.sessionId),
            ),
            effects(result),
        )
        assertFalse(effects(result).any { it is TransferEffect.SendFileComplete || it is TransferEffect.DeletePartialDestination })
        // No speculative deletion or retry of a verified destination whose commit must be reconciled.
        assertRejected(TransferReducer.apply(failed, Tf.retry()))
        assertRejected(TransferReducer.apply(failed, Tf.cancelLocally()))
        assertRejected(TransferReducer.apply(failed, Tf.skip()))
    }

    @Test fun `commit failure retains typed category with bounded diagnostics`() {
        val provisional = receiverVerified()
        val privateDetail = "content://private.example/path/secret.bin"
        val permissionEvent = TransferEvent.DestinationCommitFailed(
            Tf.sessionId, Tf.transferId, null, TransferError.PermissionRevoked(privateDetail),
        )
        val permission = TransferReducer.apply(provisional, permissionEvent)
        assertEquals(TransferState.FAILED_FINAL, current(permission).state)
        assertEquals("permission_revoked", current(permission).failure?.code)
        assertFalse(current(permission).failure.toString().contains(privateDetail))
        assertFalse(permissionEvent.toString().contains(privateDetail))
        assertFalse(effects(permission).any { it is TransferEffect.SendFileComplete })

        val full = TransferReducer.apply(
            provisional,
            TransferEvent.DestinationCommitFailed(Tf.sessionId, Tf.transferId, null, TransferError.StorageFull(42L)),
        )
        assertEquals("storage_full", current(full).failure?.code)
        assertEquals(TransferState.FAILED_RETRYABLE, current(full).state)
        val storeFull = TransferReducer.apply(
            provisional,
            TransferEvent.DestinationCommitFailed(
                Tf.sessionId, Tf.transferId, null, TransferError.PersistenceFailure("write", true),
            ),
        )
        assertEquals("persistence_storage_full", current(storeFull).failure?.code)
        assertEquals(TransferState.FAILED_FINAL, current(storeFull).state)
        assertRejected(TransferReducer.apply(
            provisional,
            TransferEvent.DestinationCommitFailed(
                Tf.sessionId, Tf.transferId, null, TransferError.TransportDisconnected(privateDetail),
            ),
        ))
    }

    @Test fun `a storage failure after provisional verification is legal and does not delete`() {
        val result = TransferReducer.apply(
            receiverVerified(),
            TransferEvent.StorageFailed(Tf.sessionId, Tf.transferId, null, TransferError.StorageFull(1L)),
        )
        assertEquals(TransferState.FAILED_RETRYABLE, current(result).state)
        assertFalse(effects(result).any { it is TransferEffect.DeletePartialDestination || it is TransferEffect.SendFileComplete })
        assertRejected(TransferReducer.apply(receiverVerified(), Tf.cancelLocally()))
    }

    @Test fun `commit outcomes require a verified receiver and never apply to a sender`() {
        val success = TransferEvent.DestinationCommitSucceeded(Tf.sessionId, Tf.transferId, null)
        val failure = TransferEvent.DestinationCommitFailed(Tf.sessionId, Tf.transferId, null)
        assertRejected(TransferReducer.apply(receiverVerifying(), success))
        assertRejected(TransferReducer.apply(receiverVerifying(), failure))
        assertRejected(TransferReducer.apply(senderVerified(), success))
        assertRejected(TransferReducer.apply(senderVerified(), failure))
    }

    @Test fun `zero byte receiver requests local EOF after acceptance without a chunk`() {
        val negotiating = Tf.queued(
            direction = SessionDirection.INBOUND,
            descriptor = Tf.descriptor(totalBytes = 0L, digest = Sha256Accumulator.EMPTY),
        ) + Tf.beginNegotiation()
        val accepted = TransferReducer.apply(negotiating, Tf.accept())
        val empty = current(accepted)
        assertEquals(TransferState.RECEIVING, empty.state)
        assertEquals(
            listOf(
                TransferEffect.SendHandshakeAccept(Tf.transferId, Tf.chunk, 0L),
                TransferEffect.RequestDestinationPartial(Tf.transferId, 0L),
                TransferEffect.PersistSnapshot(Tf.transferId, empty.snapshotVersion),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.RECEIVING),
                TransferEffect.DispatchAllBytesConfirmed(Tf.transferId, empty.snapshotVersion),
            ),
            effects(accepted),
        )
        // A future executor reports that local effect only after its predecessors succeed.
        val result = TransferReducer.apply(empty, Tf.allBytesConfirmed())
        val verifying = current(result)
        assertEquals(TransferState.VERIFYING, verifying.state)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, verifying.snapshotVersion),
                TransferEffect.BeginVerification(Tf.transferId, Sha256Accumulator.EMPTY),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.VERIFYING),
            ),
            effects(result),
        )
        assertRejected(TransferReducer.apply(empty, Tf.chunkDurablyStored(0L, 1)))
        val verified = verifying + TransferEvent.VerificationSucceeded(
            Tf.sessionId, Tf.transferId, null, Sha256Accumulator.EMPTY,
        )
        val complete = verified + TransferEvent.DestinationCommitSucceeded(Tf.sessionId, Tf.transferId, null)
        assertEquals(TransferState.COMPLETED, complete.state)
    }

    @Test fun `extra or duplicate chunks after the declared frontier are refused`() {
        val whole = Tf.receiveEverything(receiving())
        assertEquals(descriptor.totalBytes, whole.confirmedBytes)
        assertRejected(TransferReducer.apply(whole, Tf.chunkDurablyStored(0L)))
        assertRejected(TransferReducer.apply(whole, Tf.chunkDurablyStored(descriptor.totalBytes, 1)))
    }

    @Test fun `sender reports local EOF after final acknowledgement and after empty acceptance`() {
        val sending = Tf.sending(descriptor = Tf.descriptor(totalBytes = 4_096L, digest = Tf.knownDigest)) +
            Tf.chunkSent(0L)
        val acknowledged = TransferReducer.apply(sending, Tf.chunkAck(0L, 4_096, 4_096L))
        val confirmed = current(acknowledged)
        assertEquals(
            listOf(
                TransferEffect.PersistConfirmedOffset(Tf.transferId, 4_096L),
                TransferEffect.PersistSnapshot(Tf.transferId, confirmed.snapshotVersion),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.SENDING),
                TransferEffect.DispatchAllBytesConfirmed(Tf.transferId, confirmed.snapshotVersion),
            ),
            effects(acknowledged),
        )
        val verifying = TransferReducer.apply(confirmed, Tf.allBytesConfirmed())
        assertEquals(TransferState.VERIFYING, current(verifying).state)
        assertFalse(effects(verifying).any { it is TransferEffect.BeginVerification })

        val emptyDescriptor = Tf.descriptor(totalBytes = 0L, digest = Sha256Accumulator.EMPTY)
        val emptyOffer = Tf.queued(descriptor = emptyDescriptor) + Tf.beginNegotiation()
        val accepted = TransferReducer.apply(emptyOffer, Tf.peerAccepted())
        val empty = current(accepted)
        assertEquals(
            listOf(
                TransferEffect.SendMetadata(Tf.transferId, emptyDescriptor),
                TransferEffect.RequestSourceStream(Tf.transferId, 0L),
                TransferEffect.PersistSnapshot(Tf.transferId, empty.snapshotVersion),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.SENDING),
                TransferEffect.DispatchAllBytesConfirmed(Tf.transferId, empty.snapshotVersion),
            ),
            effects(accepted),
        )
        assertEquals(TransferState.VERIFYING, current(TransferReducer.apply(empty, Tf.allBytesConfirmed())).state)
    }

    @Test fun `sender verification remains nonterminal and sender never requests a commit`() {
        val verifying = senderVerifying()
        assertRejected(TransferReducer.apply(verifying, fileComplete()))
        val result = TransferReducer.apply(
            verifying,
            TransferEvent.VerificationSucceeded(
                Tf.sessionId, Tf.transferId, null, Tf.knownDigest, reportedTotalBytes = descriptor.totalBytes,
            ),
        )
        val verified = current(result)
        assertEquals(TransferState.VERIFYING, verified.state)
        assertFalse(verified.isTerminal)
        assertEquals(Tf.knownDigest, verified.verification?.observedDigest)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, verified.snapshotVersion),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.VERIFYING),
            ),
            effects(result),
        )
        assertFalse(effects(result).any { it is TransferEffect.CommitVerifiedDestination || it is TransferEffect.SendFileComplete })
        assertRejected(TransferReducer.apply(verifying, TransferEvent.VerificationSucceeded(
            Tf.sessionId, Tf.transferId, null, Tf.knownDigest, reportedTotalBytes = descriptor.totalBytes + 1,
        )))
    }

    @Test fun `matching file completion alone completes the verified sender`() {
        val verified = senderVerified()
        assertRejected(TransferReducer.apply(verified, fileComplete(sessionId = Tf.otherSessionId)))
        assertRejected(TransferReducer.apply(verified, fileComplete(transferId = Tf.otherTransferId)))
        assertRejected(TransferReducer.apply(verified, fileComplete(recipientId = Tf.recipientA)))
        assertRejected(TransferReducer.apply(verified, fileComplete(fileId = Tf.otherFileId)))
        assertRejected(TransferReducer.apply(verified, fileComplete(totalBytes = descriptor.totalBytes - 1)))
        assertRejected(TransferReducer.apply(verified, fileComplete(digest = Tf.otherDigest)))
        val result = TransferReducer.apply(verified, fileComplete())
        val completed = current(result)
        assertEquals(TransferState.COMPLETED, completed.state)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, completed.snapshotVersion),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.COMPLETED),
                TransferEffect.ScheduleNextEligibleItem(Tf.sessionId),
            ),
            effects(result),
        )
        assertRejected(TransferReducer.apply(completed, fileComplete()))
        assertRejected(TransferReducer.apply(receiverVerified(), fileComplete()))
    }

    @Test fun `receiver and sender verification failures have direction aware ordered effects`() {
        val failure = TransferEvent.VerificationFailed(
            Tf.sessionId, Tf.transferId, null, Tf.knownDigest, Tf.otherDigest,
        )
        val inbound = TransferReducer.apply(receiverVerifying(), failure)
        assertEquals(TransferState.FAILED_RETRYABLE, current(inbound).state)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, current(inbound).snapshotVersion),
                TransferEffect.SendVerificationResult(Tf.transferId, false, descriptor.totalBytes, null),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.FAILED_RETRYABLE),
                TransferEffect.ScheduleNextEligibleItem(Tf.sessionId),
            ),
            effects(inbound),
        )
        val outbound = TransferReducer.apply(senderVerifying(), failure)
        assertEquals(TransferState.FAILED_RETRYABLE, current(outbound).state)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, current(outbound).snapshotVersion),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.FAILED_RETRYABLE),
                TransferEffect.ScheduleNextEligibleItem(Tf.sessionId),
            ),
            effects(outbound),
        )
        assertFalse(effects(outbound).any { it is TransferEffect.CommitVerifiedDestination || it is TransferEffect.SendVerificationResult })
    }

    @Test fun `chunk durability then checkpoint success produce one ordered acknowledgement`() {
        val before = receiving()
        val stage = TransferReducer.apply(before, Tf.chunkDurablyStored(0L))
        val pending = current(stage)
        assertEquals(0L, pending.confirmedBytes)
        assertEquals(4_096L, pending.optimisticBytes)
        assertNull(pending.lastAcknowledgedSequence)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, pending.snapshotVersion),
                TransferEffect.PersistConfirmedOffset(Tf.transferId, 4_096L),
            ),
            effects(stage),
        )
        val restored = TransferSnapshotCodec.deserialize(TransferSnapshotCodec.serialize(pending))
            as SnapshotDecodeResult.Success
        assertEquals(pending, restored.snapshot)
        val success = TransferReducer.apply(pending, persisted(4_096L))
        val confirmed = current(success)
        assertEquals(4_096L, confirmed.confirmedBytes)
        assertEquals(4_096L, confirmed.optimisticBytes)
        assertEquals(0L, confirmed.lastAcknowledgedSequence)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, confirmed.snapshotVersion),
                TransferEffect.SendAcknowledgement(Tf.transferId, 0L, 4_096, 4_096L),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.RECEIVING),
            ),
            effects(success),
        )
        assertRejected(TransferReducer.apply(confirmed, persisted(4_096L)))

        val secondPending = confirmed + Tf.chunkDurablyStored(4_096L)
        val second = TransferReducer.apply(secondPending, persisted(8_192L))
        val final = current(second)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, final.snapshotVersion),
                TransferEffect.SendAcknowledgement(Tf.transferId, 4_096L, 4_096, 8_192L),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.RECEIVING),
                TransferEffect.DispatchAllBytesConfirmed(Tf.transferId, final.snapshotVersion),
            ),
            effects(second),
        )
        val ack = effects(second).filterIsInstance<TransferEffect.SendAcknowledgement>().single()
        assertEquals(8_192L, ack.confirmedOffset)
        assertTrue(ack.confirmedOffset > confirmed.confirmedBytes)
        val verifying = TransferReducer.apply(final, Tf.allBytesConfirmed())
        assertEquals(TransferState.VERIFYING, current(verifying).state)
        assertTrue(effects(verifying).any { it is TransferEffect.BeginVerification })
    }

    @Test fun `checkpoint failure preserves confirmed frontier and has no acknowledgement`() {
        val pending = receiving() + Tf.chunkDurablyStored(0L)
        val result = TransferReducer.apply(pending, persistenceFailed(4_096L))
        val failed = current(result)
        assertEquals(TransferState.FAILED_RETRYABLE, failed.state)
        assertEquals(0L, failed.confirmedBytes)
        assertEquals(0L, failed.optimisticBytes)
        assertEquals("persistence_io", failed.failure?.code)
        assertEquals(
            listOf(
                TransferEffect.PersistSnapshot(Tf.transferId, pending.snapshotVersion),
                TransferEffect.PersistSnapshot(Tf.transferId, failed.snapshotVersion),
                TransferEffect.NotifyUi(Tf.transferId, TransferState.FAILED_RETRYABLE),
                TransferEffect.ScheduleNextEligibleItem(Tf.sessionId),
            ),
            effects(result),
        )
        assertFalse(effects(result).any { it is TransferEffect.SendAcknowledgement })
        val full = current(TransferReducer.apply(pending, persistenceFailed(4_096L, storageFull = true)))
        assertEquals(TransferState.FAILED_FINAL, full.state)
        assertEquals(0L, full.confirmedBytes)
    }

    @Test fun `checkpoint candidates must be the one current durable frontier`() {
        val before = receiving()
        val pending = before + Tf.chunkDurablyStored(0L)
        assertRejected(TransferReducer.apply(before, persisted(4_096L)))
        assertRejected(TransferReducer.apply(pending, persisted(0L)))
        assertRejected(TransferReducer.apply(pending, persisted(2_048L)))
        assertRejected(TransferReducer.apply(pending, persisted(8_192L)))
        assertRejected(TransferReducer.apply(pending, persisted(8_193L)))
        assertRejected(TransferReducer.apply(pending, persistenceFailed(2_048L)))
        assertRejected(TransferReducer.apply(pending, Tf.chunkDurablyStored(4_096L)))
        val impossible = pending.copy(optimisticBytes = 8_192L)
        assertRejected(TransferReducer.apply(impossible, persisted(8_192L)))
    }

    @Test fun `a lost ACK can advance sender resume without guessing a previous sequence`() {
        val negotiating = Tf.queued(descriptor = descriptor) + Tf.beginNegotiation()
        for (offset in listOf(1L, 2_048L, 4_096L, descriptor.totalBytes)) {
            val result = TransferReducer.apply(negotiating, Tf.applyResume(ResumeDecision.ResumeAt(offset)))
            val resumed = current(result)
            assertEquals(offset, resumed.confirmedBytes)
            assertEquals(offset, resumed.optimisticBytes)
            assertNull(resumed.lastAcknowledgedSequence)
            assertTrue(resumed.isInternallyConsistent())
            assertEquals(
                listOf(
                    TransferEffect.PersistSnapshot(Tf.transferId, resumed.snapshotVersion),
                    TransferEffect.SendResume(Tf.transferId, offset),
                    TransferEffect.RequestSourceStream(Tf.transferId, offset),
                    TransferEffect.NotifyUi(Tf.transferId, TransferState.NEGOTIATING),
                ),
                effects(result),
            )
        }
    }

    @Test fun `retry queues a confirmed checkpoint without inventing a fresh offset`() {
        val received = Tf.receiveChunk(receiving(), 0L)
        val failed = received + Tf.transportDisconnected()
        val retried = current(TransferReducer.apply(failed, Tf.retry()))
        assertEquals(TransferState.QUEUED, retried.state)
        assertEquals(1, retried.retryCount)
        assertEquals(4_096L, retried.confirmedBytes)
        assertTrue(retried.isInternallyConsistent())
        val restored = TransferSnapshotCodec.deserialize(TransferSnapshotCodec.serialize(retried))
            as SnapshotDecodeResult.Success
        assertEquals(retried, restored.snapshot)
    }

    @Test fun `direction is taken from the immutable snapshot and guards directional effects`() {
        val falseReceiver = receiving().copy(direction = SessionDirection.OUTBOUND)
        assertRejected(TransferReducer.apply(falseReceiver, Tf.chunkDurablyStored(0L)))
        assertRejected(TransferReducer.apply(falseReceiver, persisted(4_096L)))
        val falseSender = Tf.sending(descriptor = descriptor).copy(direction = SessionDirection.INBOUND)
        assertRejected(TransferReducer.apply(falseSender, Tf.chunkSent(0L)))
        assertRejected(TransferReducer.apply(falseSender, Tf.chunkAck(0L, 4_096, 4_096L)))
        val outboundOffer = Tf.queued() + Tf.beginNegotiation()
        assertRejected(TransferReducer.apply(outboundOffer, Tf.reject()))
        val outbound = senderVerifying()
        assertFalse(effects(TransferReducer.apply(outbound, Tf.allBytesConfirmed())).any { it is TransferEffect.BeginVerification })
        assertRejected(TransferReducer.apply(outbound, TransferEvent.DestinationCommitSucceeded(Tf.sessionId, Tf.transferId, null)))
    }

    @Test fun `new verification and completion effects do not reveal digest bytes in diagnostics`() {
        val verified = receiverVerified()
        val result = TransferReducer.apply(
            verified, TransferEvent.DestinationCommitSucceeded(Tf.sessionId, Tf.transferId, null),
        )
        assertFalse(effects(result).toString().contains(Tf.knownDigest.hex))
        assertFalse(fileComplete().toString().contains(Tf.knownDigest.hex))
        assertFalse(TransferEffect.BeginVerification(Tf.transferId, Tf.knownDigest).toString().contains(Tf.knownDigest.hex))
    }
}
