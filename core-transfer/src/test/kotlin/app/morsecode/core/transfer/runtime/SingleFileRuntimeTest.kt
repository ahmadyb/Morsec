package app.morsecode.core.transfer.runtime

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.protocol.FramePayload
import app.morsecode.core.transfer.protocol.FramePayloads
import app.morsecode.core.transfer.protocol.FrameType
import app.morsecode.core.transfer.protocol.PayloadResult
import app.morsecode.core.transfer.reducer.TransferReducer
import app.morsecode.core.transfer.reducer.TransitionResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFileRuntimeTest {
    private fun content(size: Int): ByteArray = ByteArray(size) { ((it * 31 + 7) and 255).toByte() }

    private fun halted(result: SingleFileResult): SingleFileResult.Halted {
        assertTrue("expected halt, got $result", result is SingleFileResult.Halted)
        return result as SingleFileResult.Halted
    }

    private fun index(trace: List<String>, prefix: String): Int {
        val result = trace.indexOfFirst { it.startsWith(prefix) }
        assertTrue("missing $prefix in ${trace.joinToString()}", result >= 0)
        return result
    }

    private fun assertNoReceiverSideEffectsAfter(trace: List<String>, position: Int) {
        val tail = trace.drop(position + 1)
        assertFalse("unexpected later receiver effects: $tail", tail.any {
            it.startsWith("receiver.send.") || it.startsWith("receiver.notify.") ||
                it == "receiver.schedule" || it.startsWith("receiver.destination.") ||
                it.startsWith("receiver.save.") || it.startsWith("receiver.checkpoint.")
        })
    }

    private fun sendOne(pair: RuntimePair): SingleFileResult {
        assertEquals(SingleFileResult.Advanced, pair.sender.sendNextChunk())
        return pair.deliverToReceiver(FrameType.DATA_CHUNK)
    }

    private fun assertStopped(pair: RuntimePair, result: SingleFileResult.Halted) {
        val before = pair.trace.toList()
        assertEquals(result, pair.receiver.receive(ByteArray(0)))
        assertEquals(result, pair.receiver.start())
        assertEquals(result, pair.receiver.sendNextChunk())
        assertEquals(before, pair.trace)
    }

    @Test
    fun multiChunkTransferUsesDurableCheckpointCasThenReceiverCommitAndMatchingCompletion() {
        val bytes = content(2_555)
        val pair = RuntimePair(bytes, maxRead = 17)
        pair.establish()
        var count = 0
        while (pair.sender.snapshot()?.state == TransferState.SENDING) {
            assertEquals(SingleFileResult.Advanced, sendOne(pair))
            count++
            assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.CHUNK_ACK))
        }
        assertEquals(3, count)
        assertEquals(TransferState.VERIFYING, pair.sender.snapshot()?.state)
        assertEquals(TransferState.COMPLETED, pair.receiver.snapshot()?.state)
        assertArrayEquals(bytes, pair.destination.bytes)
        assertTrue(pair.destination.published)
        assertEquals(1, pair.destination.commitCalls)
        assertEquals(1, pair.source.reader?.closes)
        assertEquals(1, pair.destination.verificationReader?.closes)

        // saveCheckpoint changes the durable row at the same revision; CAS must
        // use that row, not the reducer's older confirmed frontier.
        val promoted = pair.receiverStore.transitions.first { (previous, next) ->
            previous != null && next.state == TransferState.RECEIVING &&
                next.confirmedBytes == 1_024L && next.lastAcknowledgedSequence == 0L
        }
        assertEquals(1_024L, promoted.first?.confirmedBytes)
        assertEquals(1_024L, promoted.first?.optimisticBytes)
        assertNull(promoted.first?.lastAcknowledgedSequence)
        assertEquals(promoted.first!!.snapshotVersion + 1L, promoted.second.snapshotVersion)
        val senderPromoted = pair.senderStore.transitions.first { (previous, next) ->
            previous != null && next.state == TransferState.SENDING && next.confirmedBytes == 1_024L
        }
        assertEquals(1_024L, senderPromoted.first?.confirmedBytes)

        val write = index(pair.trace, "receiver.destination.write.0.")
        val staged = index(pair.trace, "receiver.save.RECEIVING.v4.c0.o1024")
        val checkpoint = index(pair.trace, "receiver.checkpoint.1024")
        val confirmed = index(pair.trace, "receiver.save.RECEIVING.v5.c1024.o1024")
        val ack = index(pair.trace, "receiver.send.CHUNK_ACK")
        val notify = index(pair.trace, "receiver.notify.RECEIVING.v5")
        assertTrue(write < staged && staged < checkpoint && checkpoint < confirmed && confirmed < ack && ack < notify)
        val provisional = pair.trace.indexOfLast { it.startsWith("receiver.save.VERIFYING.") && it.endsWith("verifiedtrue") }
        val commit = index(pair.trace, "receiver.destination.commit")
        val completeSave = index(pair.trace, "receiver.save.COMPLETED.")
        val verification = index(pair.trace, "receiver.send.VERIFICATION_RESULT")
        val complete = index(pair.trace, "receiver.send.FILE_COMPLETE")
        val completeUi = index(pair.trace, "receiver.notify.COMPLETED")
        val schedule = pair.trace.indexOfLast { it == "receiver.schedule" }
        assertTrue(provisional >= 0 && provisional < commit && commit < completeSave && completeSave < verification)
        assertTrue(verification < complete && complete < completeUi && completeUi < schedule)
        assertFalse(pair.trace.any { it == "sender.send.FILE_COMPLETE" })

        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.VERIFICATION_RESULT))
        assertEquals("verification alone does not complete the sender", TransferState.VERIFYING, pair.sender.snapshot()?.state)
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.FILE_COMPLETE))
        assertEquals(TransferState.COMPLETED, pair.sender.snapshot()?.state)
        assertEquals(pair.descriptor.expectedSha256, pair.sender.snapshot()?.verification?.observedDigest)
        assertTrue(pair.senderTransport.frames.isEmpty())
        assertTrue(pair.receiverTransport.frames.isEmpty())
    }

    @Test
    fun emptyFileWaitsForMetadataBeforeImplicitEofVerificationAndCommit() {
        val pair = RuntimePair(ByteArray(0))
        assertEquals(SingleFileResult.Advanced, pair.startThroughAcceptance())
        assertEquals(TransferState.VERIFYING, pair.sender.snapshot()?.state)
        assertEquals(TransferState.RECEIVING, pair.receiver.snapshot()?.state)
        assertEquals(0, pair.destination.commitCalls)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertEquals(1, pair.source.reader?.closes)
        assertEquals(SingleFileResult.Advanced, pair.deliverToReceiver(FrameType.FILE_METADATA))
        assertEquals(TransferState.COMPLETED, pair.receiver.snapshot()?.state)
        assertEquals(1, pair.destination.commitCalls)
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.VERIFICATION_RESULT))
        assertEquals(TransferState.VERIFYING, pair.sender.snapshot()?.state)
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.FILE_COMPLETE))
        assertEquals(TransferState.COMPLETED, pair.sender.snapshot()?.state)
        assertFalse(pair.trace.any { it.contains("DATA_CHUNK") || it.contains("CHUNK_ACK") })
    }

    @Test
    fun emptyFileRejectsMismatchedMetadataWithoutVerifyingOrPublishing() {
        val pair = RuntimePair(ByteArray(0))
        assertEquals(SingleFileResult.Advanced, pair.startThroughAcceptance())
        val mismatched = pair.descriptor.copy(displayName = "different.bin")
        val frame = FrameCodec.fileMetadata(pair.session, pair.transfer, mismatched, pair.recipient)
        val result = halted(pair.receiver.receive(FrameCodec.encode(frame)))
        assertTrue(result.reportedEvent is TransferEvent.ProtocolFailed)
        assertEquals(0, pair.destination.commitCalls)
        assertFalse(pair.destination.published)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertStopped(pair, result)
    }

    @Test
    fun exactChunkAndShortFinalChunkBothComplete() {
        for (size in listOf(1, 1_024, 1_025, 2_048)) {
            val pair = RuntimePair(content(size))
            pair.establish()
            while (pair.sender.snapshot()?.state == TransferState.SENDING) {
                assertEquals(SingleFileResult.Advanced, sendOne(pair))
                assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.CHUNK_ACK))
            }
            assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.VERIFICATION_RESULT))
            assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.FILE_COMPLETE))
            assertEquals(TransferState.COMPLETED, pair.sender.snapshot()?.state)
            assertArrayEquals(content(size), pair.destination.bytes)
        }
    }

    @Test
    fun missingDigestFolderAndMismatchedDirectionAreRefusedBeforeAnyPortIsTouched() {
        val pair = RuntimePair(content(4))
        val unsupported = listOf(
            pair.command(SessionDirection.OUTBOUND, pair.descriptor.copy(expectedSha256 = null)) to SingleFileRole.Sender(pair.source),
            pair.command(SessionDirection.OUTBOUND, pair.descriptor.copy(isFolderArchive = true)) to SingleFileRole.Sender(pair.source),
            pair.command(SessionDirection.OUTBOUND) to SingleFileRole.Receiver(pair.destination),
            pair.command(SessionDirection.INBOUND) to SingleFileRole.Sender(pair.source),
        )
        for ((command, role) in unsupported) {
            val runtime = SingleFileRuntime(command, role, pair.senderStore, pair.senderTransport, pair.senderObserver)
            assertTrue(runtime.start() is SingleFileResult.Refused)
            assertNull(runtime.snapshot())
        }
        assertTrue(pair.trace.isEmpty())
    }

    @Test
    fun anExistingIdenticalQueuedRowIsNotAdoptedAsAFreshTransfer() {
        val pair = RuntimePair(content(12))
        val command = pair.command(SessionDirection.OUTBOUND)
        val queued = (TransferReducer.enqueue(command) as TransitionResult.Accepted).current
        pair.senderStore.row = queued
        val result = halted(pair.sender.start())
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertEquals(queued, pair.senderStore.row)
        assertTrue(pair.senderTransport.frames.isEmpty())
        assertFalse(pair.trace.any { it.startsWith("sender.notify.") || it == "sender.source.open" })
    }

    @Test
    fun initialSaveFailureStopsBeforeNegotiationAndUi() {
        val pair = RuntimePair(content(12))
        pair.senderStore.onSave = { FaultMode.FAIL }
        val result = halted(pair.sender.start())
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertTrue(pair.senderTransport.frames.isEmpty())
        val save = index(pair.trace, "sender.save.QUEUED.")
        assertFalse(pair.trace.drop(save + 1).any { it.startsWith("sender.notify.") || it.startsWith("sender.send.") })
    }

    @Test
    fun unreadableStoreIsNotMistakenForAnAbsentFreshRow() {
        for (mode in listOf(FaultMode.FAIL, FaultMode.UNKNOWN_BEFORE, FaultMode.THROW)) {
            val pair = RuntimePair(content(12))
            pair.senderStore.onLoad = { mode }
            val result = halted(pair.sender.start())
            if (mode == FaultMode.FAIL) assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
            else {
                assertNull(result.reportedEvent)
                assertTrue(result.requiresReconciliation)
            }
            assertNull(pair.senderStore.row)
            assertTrue(pair.senderTransport.frames.isEmpty())
            assertFalse(pair.trace.any { it.startsWith("sender.save.") || it.startsWith("sender.notify.") })
        }
    }

    @Test
    fun failedHandshakeSendPreventsTheLaterSnapshotAndUiEffects() {
        val pair = RuntimePair(content(12))
        pair.senderTransport.onSend = { if (it == FrameType.HANDSHAKE) FaultMode.FAIL else null }
        val result = halted(pair.sender.start())
        assertTrue(result.reportedEvent is TransferEvent.TransportDisconnected)
        val send = index(pair.trace, "sender.send.HANDSHAKE")
        assertFalse(pair.trace.drop(send + 1).any { it.startsWith("sender.save.") || it.startsWith("sender.notify.") })
        assertEquals(TransferState.QUEUED, pair.senderStore.row?.state)
    }

    @Test
    fun destinationOpenFailureStopsAfterPriorHandshakeAcceptAndBeforePersistenceOrUi() {
        val pair = RuntimePair(content(12))
        pair.destination.openMode = FaultMode.FAIL
        assertEquals(SingleFileResult.Advanced, pair.receiver.start())
        assertEquals(SingleFileResult.Advanced, pair.sender.start())
        val result = halted(pair.deliverToReceiver(FrameType.HANDSHAKE))
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertEquals(1, pair.receiverTransport.frames.size) // the earlier accept cannot be undone
        val open = index(pair.trace, "receiver.destination.open")
        assertNoReceiverSideEffectsAfter(pair.trace, open)
        assertEquals(TransferState.NEGOTIATING, pair.receiverStore.row?.state)
        assertEquals(0, pair.destination.commitCalls)
    }

    @Test
    fun advertisedHandshakeOptsOutOfResumeAndDoesNotClaimTheGenericResumeCapability() {
        val pair = RuntimePair(content(1))
        assertEquals(SingleFileResult.Advanced, pair.sender.start())
        val frame = (FrameCodec.decodeSingle(pair.take(pair.senderTransport, FrameType.HANDSHAKE)) as FrameDecodeResult.Success).frame
        val payload = (FramePayloads.parse(frame) as PayloadResult.Success).payload as FramePayload.Handshake
        assertFalse(payload.resumeSupported)
        assertFalse("resume" in payload.capabilities.split(';'))
        assertTrue(payload.capabilities.split(';').containsAll(listOf("v1", "crc32", "sha256")))
    }

    @Test
    fun pendingSnapshotFailureDispatchesCheckpointFailureAndStopsBeforeCheckpointAckOrUi() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.onSave = { if (it.state == TransferState.RECEIVING && it.optimisticBytes > it.confirmedBytes) FaultMode.FAIL else null }
        val result = halted(sendOne(pair))
        assertTrue(result.reportedEvent is TransferEvent.ConfirmedOffsetPersistenceFailed)
        assertTrue(result.eventAccepted)
        assertEquals(0L, pair.receiver.snapshot()?.confirmedBytes)
        assertEquals(0L, pair.receiverStore.row?.confirmedBytes)
        assertTrue(result.requiresReconciliation) // durable bytes are beyond the saved checkpoint
        val failedSave = pair.trace.indexOfLast { it.startsWith("receiver.save.RECEIVING.") && it.contains(".o12.") }
        assertNoReceiverSideEffectsAfter(pair.trace, failedSave)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertStopped(pair, result)
    }

    @Test
    fun definitiveCheckpointFailureReportsCorrectEventAndNeverSendsAckOrUi() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.onCheckpoint = { FaultMode.FAIL }
        val result = halted(sendOne(pair))
        val event = result.reportedEvent as TransferEvent.ConfirmedOffsetPersistenceFailed
        assertEquals(12L, event.candidateOffset)
        assertTrue(event.storageFull)
        assertTrue(result.eventAccepted)
        assertTrue(result.requiresReconciliation)
        assertEquals(0L, pair.receiver.snapshot()?.confirmedBytes)
        assertEquals(0L, pair.receiverStore.row?.confirmedBytes)
        assertEquals(12L, pair.receiverStore.row?.optimisticBytes)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.checkpoint.12"))
        assertStopped(pair, result)
    }

    @Test
    fun checkpointCancellationIsNotReportedAsPersistenceFailureAndCannotAck() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.onCheckpoint = { FaultMode.CANCEL }
        val result = halted(sendOne(pair))
        assertEquals(TransferError.CancelledLocally, result.error)
        assertNull(result.reportedEvent)
        assertTrue(result.requiresReconciliation)
        assertEquals(0L, pair.receiver.snapshot()?.confirmedBytes)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.checkpoint.12"))
    }

    @Test
    fun ambiguousCheckpointNeverGuessesOrAcknowledgesEvenWhenTheStoreActuallyAdvanced() {
        for (mode in listOf(FaultMode.UNKNOWN_BEFORE, FaultMode.UNKNOWN_AFTER, FaultMode.THROW)) {
            val pair = RuntimePair(content(12))
            pair.establish()
            pair.receiverStore.onCheckpoint = { mode }
            val result = halted(sendOne(pair))
            assertNull(result.reportedEvent)
            assertTrue(result.requiresReconciliation)
            assertEquals(0L, pair.receiver.snapshot()?.confirmedBytes)
            assertEquals(12L, pair.receiver.snapshot()?.optimisticBytes)
            assertEquals(if (mode == FaultMode.UNKNOWN_AFTER) 12L else 0L, pair.receiverStore.row?.confirmedBytes)
            assertTrue(pair.receiverTransport.frames.isEmpty())
            assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.checkpoint.12"))
            assertStopped(pair, result)
        }
    }

    @Test
    fun confirmedSnapshotFailureAfterCheckpointCannotSendAckEvenThoughDurableFloorAdvanced() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.onSave = {
            if (it.state == TransferState.RECEIVING && it.confirmedBytes == 12L) FaultMode.FAIL else null
        }
        val result = halted(sendOne(pair))
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertTrue(result.requiresReconciliation)
        assertEquals(12L, pair.receiverStore.row?.confirmedBytes)
        assertNull(pair.receiverStore.row?.lastAcknowledgedSequence)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertEquals(0, pair.destination.commitCalls)
        val failedSave = pair.trace.indexOfLast { it.startsWith("receiver.save.RECEIVING.") && it.contains(".c12.") }
        assertNoReceiverSideEffectsAfter(pair.trace, failedSave)
        assertStopped(pair, result)
    }

    @Test
    fun unexpectedSameRevisionStoreMutationIsNotAdoptedAsACheckpoint() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.afterCheckpoint = { it.copy(lastAcknowledgedSequence = 99L) }
        val result = halted(sendOne(pair))
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertTrue(result.requiresReconciliation)
        assertEquals(12L, pair.receiverStore.row?.confirmedBytes)
        assertEquals(99L, pair.receiverStore.row?.lastAcknowledgedSequence)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertEquals(0, pair.destination.commitCalls)
        val load = pair.trace.indexOfLast { it.startsWith("receiver.load.") }
        assertNoReceiverSideEffectsAfter(pair.trace, load)
    }

    @Test
    fun destinationDurabilityFailureReportsStorageFailureWithoutStagingOrAck() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.writeMode = FaultMode.FAIL
        pair.destination.partialBytesBeforeFailure = 5
        val result = halted(sendOne(pair))
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertEquals("storage_full", (result.reportedEvent as TransferEvent.StorageFailed).error.code)
        assertTrue(result.requiresReconciliation)
        assertEquals(5, pair.destination.bytes.size)
        assertEquals(0L, pair.receiver.snapshot()?.confirmedBytes)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.destination.write.0.12"))
        assertStopped(pair, result)
    }

    @Test
    fun ambiguousDestinationWriteCannotBecomeDurableChunkOrAck() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.writeMode = FaultMode.UNKNOWN_AFTER
        val result = halted(sendOne(pair))
        assertNull(result.reportedEvent)
        assertTrue(result.requiresReconciliation)
        assertEquals(12, pair.destination.bytes.size)
        assertEquals(0L, pair.receiver.snapshot()?.confirmedBytes)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.destination.write.0.12"))
    }

    @Test
    fun precommitPersistenceFailureReportsCommitFailureAndNeverPublishesOrNotifiesAfterward() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.onSave = {
            if (it.state == TransferState.VERIFYING && it.verification?.allowsCommit == true) FaultMode.FAIL else null
        }
        val result = halted(sendOne(pair))
        val event = result.reportedEvent as TransferEvent.DestinationCommitFailed
        assertEquals("persistence_io", event.error.code)
        assertTrue(result.eventAccepted)
        assertEquals(0, pair.destination.commitCalls)
        assertFalse(pair.destination.published)
        assertEquals(TransferState.VERIFYING, pair.receiverStore.row?.state)
        assertFalse(pair.receiverStore.row?.verification?.allowsCommit == true)
        val failedSave = pair.trace.indexOfLast { it.startsWith("receiver.save.VERIFYING.") && it.endsWith("verifiedtrue") }
        assertNoReceiverSideEffectsAfter(pair.trace, failedSave)
        assertFalse(pair.trace.any { it == "receiver.send.FILE_COMPLETE" || it == "receiver.send.VERIFICATION_RESULT" })
        assertStopped(pair, result)
    }

    @Test
    fun indeterminatePrecommitSaveDoesNotGuessOrAttemptPublication() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.onSave = {
            if (it.state == TransferState.VERIFYING && it.verification?.allowsCommit == true) FaultMode.UNKNOWN_AFTER else null
        }
        val result = halted(sendOne(pair))
        assertNull(result.reportedEvent)
        assertTrue(result.requiresReconciliation)
        assertEquals(0, pair.destination.commitCalls)
        assertTrue(pair.receiverStore.row?.verification?.allowsCommit == true)
        val save = pair.trace.indexOfLast { it.startsWith("receiver.save.VERIFYING.") && it.endsWith("verifiedtrue") }
        assertNoReceiverSideEffectsAfter(pair.trace, save)
    }

    @Test
    fun definitiveCommitFailurePreservesDurableProvisionalAndReportsCommitFailureOnly() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.commitMode = FaultMode.FAIL
        val result = halted(sendOne(pair))
        assertTrue(result.reportedEvent is TransferEvent.DestinationCommitFailed)
        assertTrue(result.eventAccepted)
        assertTrue(result.requiresReconciliation)
        assertFalse(pair.destination.published)
        assertEquals(1, pair.destination.commitCalls)
        assertEquals(TransferState.VERIFYING, pair.receiverStore.row?.state)
        assertTrue(pair.receiverStore.row?.verification?.allowsCommit == true)
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.destination.commit"))
        assertFalse(pair.trace.any { it == "receiver.send.FILE_COMPLETE" || it == "receiver.send.VERIFICATION_RESULT" })
        assertStopped(pair, result)
    }

    @Test
    fun commitCancellationDoesNotFabricateACommitOutcomeOrPublish() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.commitMode = FaultMode.CANCEL
        val result = halted(sendOne(pair))
        assertEquals(TransferError.CancelledLocally, result.error)
        assertNull(result.reportedEvent)
        assertTrue(result.requiresReconciliation)
        assertFalse(pair.destination.published)
        assertTrue(pair.receiverStore.row?.verification?.allowsCommit == true)
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.destination.commit"))
    }

    @Test
    fun ambiguousCommitMayHavePublishedButNeverFabricatesAnOutcomeOrDeletesIt() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.commitMode = FaultMode.UNKNOWN_AFTER
        val result = halted(sendOne(pair))
        assertNull(result.reportedEvent)
        assertTrue(result.requiresReconciliation)
        assertTrue(pair.destination.published)
        assertEquals(TransferState.VERIFYING, pair.receiver.snapshot()?.state)
        assertTrue(pair.receiverStore.row?.verification?.allowsCommit == true)
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.destination.commit"))
        assertStopped(pair, result)
        assertTrue(pair.destination.published)
    }

    @Test
    fun completedSnapshotFailureAfterPhysicalCommitLeavesPublicationAndDurableProvisionalForRecovery() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverStore.onSave = { if (it.state == TransferState.COMPLETED) FaultMode.FAIL else null }
        val result = halted(sendOne(pair))
        assertTrue(pair.destination.published)
        assertTrue(result.requiresReconciliation)
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertFalse("terminal reducer correctly refuses a fabricated post-commit failure state", result.eventAccepted)
        assertEquals(TransferState.VERIFYING, pair.receiverStore.row?.state)
        assertTrue(pair.receiverStore.row?.verification?.allowsCommit == true)
        val failedSave = index(pair.trace, "receiver.save.COMPLETED.")
        assertNoReceiverSideEffectsAfter(pair.trace, failedSave)
        assertFalse(pair.trace.any { it == "receiver.send.VERIFICATION_RESULT" || it == "receiver.send.FILE_COMPLETE" })
        assertStopped(pair, result)
        assertTrue(pair.destination.published)
    }

    @Test
    fun ackSendFailureStopsBeforeUiEofVerificationOrCommit() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverTransport.onSend = { if (it == FrameType.CHUNK_ACK) FaultMode.FAIL else null }
        val result = halted(sendOne(pair))
        assertTrue(result.reportedEvent is TransferEvent.TransportDisconnected)
        assertTrue(result.requiresReconciliation)
        assertEquals(12L, pair.receiverStore.row?.confirmedBytes)
        assertEquals(0, pair.destination.commitCalls)
        assertTrue(pair.receiverTransport.frames.isEmpty())
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.send.CHUNK_ACK"))
        assertStopped(pair, result)
    }

    @Test
    fun verificationSendFailureAfterCommitStopsBeforeFileCompleteAndCompletionUi() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverTransport.onSend = { if (it == FrameType.VERIFICATION_RESULT) FaultMode.FAIL else null }
        val result = halted(sendOne(pair))
        assertTrue(result.requiresReconciliation)
        assertTrue(pair.destination.published)
        assertEquals(TransferState.COMPLETED, pair.receiverStore.row?.state)
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.send.VERIFICATION_RESULT"))
        assertFalse(pair.trace.any { it == "receiver.send.FILE_COMPLETE" || it.startsWith("receiver.notify.COMPLETED") })
    }

    @Test
    fun fileCompleteSendFailureLeavesSenderUncompletedAndSuppressesReceiverCompletionUi() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.receiverTransport.onSend = { if (it == FrameType.FILE_COMPLETE) FaultMode.FAIL else null }
        val result = halted(sendOne(pair))
        assertTrue(result.requiresReconciliation)
        assertTrue(pair.destination.published)
        assertNoReceiverSideEffectsAfter(pair.trace, index(pair.trace, "receiver.send.FILE_COMPLETE"))
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.CHUNK_ACK))
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.VERIFICATION_RESULT))
        assertEquals(TransferState.VERIFYING, pair.sender.snapshot()?.state)
        assertTrue(pair.receiverTransport.frames.isEmpty())
    }

    @Test
    fun hashMismatchSendsNegativeVerificationButNeverCommitsOrCompletes() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.corruptOnWrite = true
        assertEquals(SingleFileResult.Advanced, sendOne(pair))
        assertEquals(TransferState.FAILED_RETRYABLE, pair.receiver.snapshot()?.state)
        assertFalse(pair.destination.published)
        assertEquals(0, pair.destination.commitCalls)
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.CHUNK_ACK))
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.VERIFICATION_RESULT))
        assertEquals(TransferState.FAILED_RETRYABLE, pair.sender.snapshot()?.state)
        assertFalse(pair.trace.any { it.contains("FILE_COMPLETE") })
    }

    @Test
    fun shortAndLongSourceAndZeroProgressFailWithoutSendingAnyChunk() {
        val expected = content(12)
        for (source in listOf(expected.copyOf(11), expected + byteArrayOf(5))) {
            val pair = RuntimePair(expected, source)
            pair.establish()
            val result = halted(pair.sender.sendNextChunk())
            assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
            assertTrue(pair.senderTransport.frames.isEmpty())
            assertEquals(1, pair.source.reader?.closes)
            assertFalse(pair.trace.any { it == "sender.send.DATA_CHUNK" })
        }
        val pair = RuntimePair(expected)
        pair.source.overrideRead = { RuntimeReadResult.Data(0) }
        pair.establish()
        val result = halted(pair.sender.sendNextChunk())
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertEquals(1, pair.source.reader?.calls)
        assertEquals(1, pair.source.reader?.closes)
        assertTrue(pair.senderTransport.frames.isEmpty())
    }

    @Test
    fun emptyDescriptorWithNonemptySourceStopsBeforeSenderImplicitEof() {
        val pair = RuntimePair(ByteArray(0), byteArrayOf(1))
        val result = halted(pair.startThroughAcceptance())
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertEquals(1, pair.source.reader?.closes)
        assertFalse(pair.trace.any { it.startsWith("sender.notify.SENDING") || it.startsWith("sender.save.SENDING") })
        assertEquals(0, pair.destination.commitCalls)
    }

    @Test
    fun impossibleTypedReadCountsAndReaderFailuresAreBoundedAndRedacted() {
        for (read in listOf(
            RuntimeReadResult.Data(-1), RuntimeReadResult.Data(0), RuntimeReadResult.Data(13),
            RuntimeReadResult.Failed(TransferError.SourceUnavailable("content://private/secret")),
        )) {
            val pair = RuntimePair(content(12))
            pair.source.overrideRead = { read }
            pair.establish()
            val result = halted(pair.sender.sendNextChunk())
            assertEquals(1, pair.source.reader?.calls)
            assertEquals(1, pair.source.reader?.closes)
            assertFalse(result.toString().contains("content://private"))
            assertFalse(pair.trace.any { it == "sender.send.DATA_CHUNK" })
        }
    }

    @Test
    fun typedReadCancellationIsTerminalAndNeverMislabeledAsIoFailure() {
        for (throwCancellation in listOf(false, true)) {
            val pair = RuntimePair(content(12))
            pair.source.overrideRead = {
                if (throwCancellation) throw kotlin.coroutines.cancellation.CancellationException("private")
                RuntimeReadResult.Cancelled
            }
            pair.establish()
            val result = halted(pair.sender.sendNextChunk())
            assertEquals(TransferError.CancelledLocally, result.error)
            assertNull(result.reportedEvent)
            assertEquals(1, pair.source.reader?.calls)
            assertEquals(1, pair.source.reader?.closes)
            assertFalse(result.toString().contains("private"))
            assertTrue(pair.senderTransport.frames.isEmpty())
        }
    }

    @Test
    fun verificationCancellationClosesReaderWithoutPublishingOrSendingSuccess() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.overrideVerificationRead = { RuntimeReadResult.Cancelled }
        val result = halted(sendOne(pair))
        assertEquals(TransferError.CancelledLocally, result.error)
        assertNull(result.reportedEvent)
        assertEquals(1, pair.destination.verificationReader?.closes)
        assertEquals(0, pair.destination.commitCalls)
        assertFalse(pair.trace.any { it == "receiver.send.VERIFICATION_RESULT" || it == "receiver.send.FILE_COMPLETE" })
    }

    @Test
    fun verificationReadFailureStopsBeforeUiCommitOrSuccessfulResult() {
        val pair = RuntimePair(content(12))
        pair.establish()
        pair.destination.overrideVerificationRead = { RuntimeReadResult.Data(0) }
        val result = halted(sendOne(pair))
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertEquals(1, pair.destination.verificationReader?.calls)
        assertEquals(1, pair.destination.verificationReader?.closes)
        assertEquals(0, pair.destination.commitCalls)
        assertFalse(pair.trace.any { it == "receiver.send.VERIFICATION_RESULT" || it == "receiver.send.FILE_COMPLETE" })
        val read = index(pair.trace, "receiver.destination.verify.read.")
        assertNoReceiverSideEffectsAfter(pair.trace, read + 1) // reader close is the only permitted cleanup
    }

    @Test
    fun senderCheckpointFailureStopsBeforeSenderUiOrImplicitEof() {
        val pair = RuntimePair(content(12))
        pair.establish()
        assertEquals(SingleFileResult.Advanced, sendOne(pair))
        pair.senderStore.onCheckpoint = { FaultMode.FAIL }
        val result = halted(pair.deliverToSender(FrameType.CHUNK_ACK))
        assertTrue(result.reportedEvent is TransferEvent.StorageFailed)
        assertEquals(0L, pair.senderStore.row?.confirmedBytes)
        assertEquals(TransferState.SENDING, pair.senderStore.row?.state)
        val checkpoint = index(pair.trace, "sender.checkpoint.12")
        assertFalse(pair.trace.drop(checkpoint + 1).any { it.startsWith("sender.notify.") || it.startsWith("sender.save.") })
    }

    @Test
    fun observerFailureSuppressesSubsequentImplicitEof() {
        val pair = RuntimePair(ByteArray(0))
        pair.senderObserver.onNotify = { if (it == TransferState.SENDING) FaultMode.FAIL else null }
        val result = halted(pair.startThroughAcceptance())
        assertEquals("notify", result.operation)
        assertNull(result.reportedEvent)
        assertEquals(TransferState.SENDING, pair.sender.snapshot()?.state)
        assertFalse(pair.trace.any { it.startsWith("sender.save.VERIFYING") })
        assertEquals(0, pair.destination.commitCalls)
    }

    @Test
    fun corruptedChunkIsRejectedBeforeDestinationWrite() {
        val pair = RuntimePair(content(12))
        pair.establish()
        assertEquals(SingleFileResult.Advanced, pair.sender.sendNextChunk())
        val frame = pair.take(pair.senderTransport, FrameType.DATA_CHUNK)
        frame[frame.lastIndex] = (frame.last().toInt() xor 1).toByte()
        val result = halted(pair.receiver.receive(frame))
        assertTrue(result.reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(pair.trace.any { it.startsWith("receiver.destination.write.") })
        assertTrue(pair.receiverTransport.frames.isEmpty())
    }

    @Test
    fun chunkBeforeMetadataAndOutOfSequenceChunkAreRejectedBeforeWrite() {
        val beforeMetadata = RuntimePair(content(12))
        assertEquals(SingleFileResult.Advanced, beforeMetadata.startThroughAcceptance())
        val early = FrameCodec.dataChunk(beforeMetadata.session, beforeMetadata.transfer, 0L, 0L, content(12), beforeMetadata.recipient)
        assertTrue(halted(beforeMetadata.receiver.receive(FrameCodec.encode(early))).reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(beforeMetadata.trace.any { it.startsWith("receiver.destination.write.") })

        val wrongSequence = RuntimePair(content(12))
        wrongSequence.establish()
        val bad = FrameCodec.dataChunk(wrongSequence.session, wrongSequence.transfer, 0L, 1L, content(12), wrongSequence.recipient)
        assertTrue(halted(wrongSequence.receiver.receive(FrameCodec.encode(bad))).reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(wrongSequence.trace.any { it.startsWith("receiver.destination.write.") })
    }

    @Test
    fun wrongIdentityAndResumeFrameAreRejectedWithoutStorageOrCompletion() {
        val pair = RuntimePair(content(12))
        pair.establish()
        val other = FrameCodec.dataChunk(SessionId("other"), pair.transfer, 0L, 0L, content(12), pair.recipient)
        assertTrue(halted(pair.receiver.receive(FrameCodec.encode(other))).reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(pair.trace.any { it.startsWith("receiver.destination.write.") })

        val second = RuntimePair(content(12))
        second.establish()
        val resume = FrameCodec.resume(second.session, second.transfer, 0L, recipientId = second.recipient)
        assertTrue(halted(second.receiver.receive(FrameCodec.encode(resume))).reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(second.trace.any { it.startsWith("receiver.destination.write.") })
    }

    @Test
    fun senderCannotCompleteBeforePeerVerificationOrOnMismatchedFileComplete() {
        val early = RuntimePair(content(12))
        early.establish()
        assertEquals(SingleFileResult.Advanced, sendOne(early))
        assertEquals(SingleFileResult.Advanced, early.deliverToSender(FrameType.CHUNK_ACK))
        val complete = FrameCodec.fileComplete(early.session, early.transfer, early.file, 12L, early.descriptor.expectedSha256, early.recipient)
        assertTrue(halted(early.sender.receive(FrameCodec.encode(complete))).reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(early.sender.snapshot()?.state == TransferState.COMPLETED)

        val mismatch = RuntimePair(content(12))
        mismatch.establish()
        assertEquals(SingleFileResult.Advanced, sendOne(mismatch))
        assertEquals(SingleFileResult.Advanced, mismatch.deliverToSender(FrameType.CHUNK_ACK))
        assertEquals(SingleFileResult.Advanced, mismatch.deliverToSender(FrameType.VERIFICATION_RESULT))
        val bad = FrameCodec.fileComplete(mismatch.session, mismatch.transfer, FileId("other"), 12L, mismatch.descriptor.expectedSha256, mismatch.recipient)
        assertTrue(halted(mismatch.sender.receive(FrameCodec.encode(bad))).reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(mismatch.sender.snapshot()?.state == TransferState.COMPLETED)
        assertFalse(mismatch.trace.any { it == "sender.send.FILE_COMPLETE" })
    }

    @Test
    fun falsePositivePeerVerificationDigestAndInvalidAcknowledgementAreRejected() {
        val pair = RuntimePair(content(12))
        pair.establish()
        assertEquals(SingleFileResult.Advanced, sendOne(pair))
        assertEquals(SingleFileResult.Advanced, pair.deliverToSender(FrameType.CHUNK_ACK))
        val bad = FrameCodec.verificationResult(pair.session, pair.transfer, true, 12L, Sha256Accumulator.EMPTY, pair.recipient)
        assertTrue(halted(pair.sender.receive(FrameCodec.encode(bad))).reportedEvent is TransferEvent.ProtocolFailed)
        assertFalse(pair.sender.snapshot()?.state == TransferState.COMPLETED)

        val second = RuntimePair(content(12))
        second.establish()
        assertEquals(SingleFileResult.Advanced, second.sender.sendNextChunk())
        val ack = FrameCodec.chunkAck(second.session, second.transfer, 0L, 11, 11L, recipientId = second.recipient)
        assertTrue(halted(second.sender.receive(FrameCodec.encode(ack))).reportedEvent is TransferEvent.ProtocolFailed)
        assertEquals(0L, second.senderStore.row?.confirmedBytes)
    }

    @Test
    fun stopAndWaitAndReentrantCallbackCannotInterleaveReducerPrograms() {
        val pair = RuntimePair(content(2_000))
        var callbackResult: SingleFileResult? = null
        pair.senderTransport.callback = { callbackResult = pair.sender.sendNextChunk() }
        pair.establish()
        assertTrue(callbackResult is SingleFileResult.Refused)
        assertEquals("reentrant runtime entry", (callbackResult as SingleFileResult.Refused).reason)
        assertEquals(SingleFileResult.Advanced, pair.sender.sendNextChunk())
        assertTrue(pair.sender.sendNextChunk() is SingleFileResult.Refused)
        assertEquals(1, pair.senderTransport.frames.size)
        assertNotNull(pair.sender.snapshot())
    }
}
