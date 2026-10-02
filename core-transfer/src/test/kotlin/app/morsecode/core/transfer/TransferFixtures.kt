package app.morsecode.core.transfer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.protocol.ResumeDecision
import app.morsecode.core.transfer.reducer.Rejection
import app.morsecode.core.transfer.reducer.TransferReducer
import app.morsecode.core.transfer.reducer.TransitionResult

/*
 * Shared fixtures for the transfer-core tests.
 *
 * Everything here is deterministic and allocation-light: files are described,
 * never created, and the chunk size is the 4 KiB minimum-scale value so a
 * multi-chunk transfer needs only a handful of events to drive end to end. No
 * test in this module touches a socket, a file, a clock or a random source.
 */

internal object Tf {
    val sessionId: SessionId = SessionId("sess-1")
    val otherSessionId: SessionId = SessionId("sess-2")
    val batchId: BatchId = BatchId("batch-1")
    val transferId: TransferId = TransferId("tr-1")
    val otherTransferId: TransferId = TransferId("tr-2")
    val fileId: FileId = FileId("file-1")
    val otherFileId: FileId = FileId("file-2")
    val recipientA: RecipientId = RecipientId("peer-a")
    val recipientB: RecipientId = RecipientId("peer-b")

    /** 4 KiB chunks: above the minimum, small enough to keep tests short. */
    val chunk: ChunkSize = ChunkSize(4_096)

    /** Three whole chunks, so resume has a genuine middle offset to find. */
    const val THREE_CHUNKS: Long = 4_096L * 3L

    val path: RelativeTransferPath = RelativeTransferPath("photos/holiday.jpg")

    /** A known 32-byte digest, used wherever a descriptor needs one. */
    val knownDigest: Sha256Digest = Sha256Digest.fromHex(
        "a".repeat(64),
    )!!

    /** A second digest, so a disagreement can be constructed. */
    val otherDigest: Sha256Digest = Sha256Digest.fromHex(
        "b".repeat(64),
    )!!

    /** A fixed modification time; every fixture that needs one uses this. */
    const val LAST_MODIFIED: Long = 1_700_000_000_000L

    fun descriptor(
        fileId: FileId = this.fileId,
        name: String = "holiday.jpg",
        path: RelativeTransferPath = this.path,
        mimeType: String = "image/jpeg",
        totalBytes: Long = THREE_CHUNKS,
        lastModified: Long? = LAST_MODIFIED,
        isFolderArchive: Boolean = false,
        digest: Sha256Digest? = null,
        chunkSize: ChunkSize = chunk,
        version: ProtocolVersion = ProtocolVersion.CURRENT,
    ): TransferFileDescriptor = TransferFileDescriptor(
        fileId = fileId,
        displayName = name,
        relativePath = path,
        mimeType = mimeType,
        totalBytes = totalBytes,
        lastModifiedEpochMillis = lastModified,
        isFolderArchive = isFolderArchive,
        expectedSha256 = digest,
        chunkSize = chunkSize,
        protocolVersion = version,
    )

    fun enqueueCommand(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        direction: SessionDirection = SessionDirection.OUTBOUND,
        descriptor: TransferFileDescriptor = descriptor(),
        queueOrder: Long = 0L,
    ): TransferCommand.Enqueue = TransferCommand.Enqueue(
        sessionId = sessionId,
        transferId = transferId,
        recipientId = recipientId,
        batchId = batchId,
        direction = direction,
        descriptor = descriptor,
        queueOrder = queueOrder,
    )

    /** A freshly queued delivery, taken through the reducer like production code does. */
    fun queued(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        direction: SessionDirection = SessionDirection.OUTBOUND,
        descriptor: TransferFileDescriptor = descriptor(),
        queueOrder: Long = 0L,
    ): TransferSnapshot = current(
        TransferReducer.enqueue(
            enqueueCommand(
                transferId = transferId,
                sessionId = sessionId,
                recipientId = recipientId,
                direction = direction,
                descriptor = descriptor,
                queueOrder = queueOrder,
            ),
        ),
    )

    /** A queued delivery with a recipient, for broadcast tests. */
    fun broadcastQueued(
        transferId: TransferId,
        recipientId: RecipientId,
        fileId: FileId = this.fileId,
        totalBytes: Long = THREE_CHUNKS,
    ): TransferSnapshot = queued(
        transferId = transferId,
        recipientId = recipientId,
        descriptor = descriptor(fileId = fileId, totalBytes = totalBytes),
    )

    /** An outbound delivery that has negotiated and is sending. */
    fun sending(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        descriptor: TransferFileDescriptor = descriptor(),
    ): TransferSnapshot {
        var snapshot = queued(
            transferId = transferId,
            sessionId = sessionId,
            recipientId = recipientId,
            direction = SessionDirection.OUTBOUND,
            descriptor = descriptor,
        )
        snapshot = snapshot + TransferCommand.BeginNegotiation(
            sessionId, transferId, recipientId,
        )
        return snapshot + TransferEvent.PeerAccepted(
            sessionId, transferId, recipientId, descriptor.chunkSize,
        )
    }

    /** An inbound delivery that has been accepted and is receiving. */
    fun receiving(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        descriptor: TransferFileDescriptor = descriptor(),
    ): TransferSnapshot {
        var snapshot = queued(
            transferId = transferId,
            sessionId = sessionId,
            recipientId = recipientId,
            direction = SessionDirection.INBOUND,
            descriptor = descriptor,
        )
        snapshot = snapshot + TransferCommand.BeginNegotiation(
            sessionId, transferId, recipientId,
        )
        return snapshot + TransferCommand.Accept(sessionId, transferId, recipientId)
    }

    // --- event and command helpers ------------------------------------------
    //
    // Sequence numbers are derived from the offset, exactly as the protocol
    // requires, so a test cannot accidentally pass a "valid looking" frame that
    // the wire would have rejected.

    fun beginNegotiation(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.BeginNegotiation =
        TransferCommand.BeginNegotiation(sessionId, transferId, recipientId)

    fun accept(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.Accept = TransferCommand.Accept(sessionId, transferId, recipientId)

    fun reject(
        reason: String = "declined",
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.Reject = TransferCommand.Reject(sessionId, transferId, recipientId, reason)

    fun peerAccepted(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
        chunkSize: ChunkSize = chunk,
    ): TransferEvent.PeerAccepted =
        TransferEvent.PeerAccepted(sessionId, transferId, recipientId, chunkSize)

    fun chunkSent(
        offset: Long,
        length: Int = chunk.value,
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.ChunkSent = TransferEvent.ChunkSent(
        sessionId, transferId, recipientId,
        offset = offset,
        length = length,
        sequence = offset / chunk.value,
    )

    fun chunkReceived(
        offset: Long,
        length: Int = chunk.value,
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.ChunkReceived = TransferEvent.ChunkReceived(
        sessionId, transferId, recipientId,
        offset = offset,
        length = length,
        sequence = offset / chunk.value,
    )

    fun chunkAck(
        offset: Long,
        length: Int,
        confirmedOffset: Long,
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.ChunkAcknowledged = TransferEvent.ChunkAcknowledged(
        sessionId, transferId, recipientId,
        offset = offset,
        length = length,
        confirmedOffset = confirmedOffset,
        sequence = offset / chunk.value,
    )

    fun allBytesConfirmed(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.AllBytesConfirmed =
        TransferEvent.AllBytesConfirmed(sessionId, transferId, recipientId)

    fun verificationSucceeded(
        digest: Sha256Digest? = null,
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.VerificationSucceeded =
        TransferEvent.VerificationSucceeded(sessionId, transferId, recipientId, digest)

    fun pauseLocally(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.PauseLocally =
        TransferCommand.PauseLocally(sessionId, transferId, recipientId)

    fun resumeLocally(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.ResumeLocally =
        TransferCommand.ResumeLocally(sessionId, transferId, recipientId)

    fun remotePaused(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.RemotePaused = TransferEvent.RemotePaused(sessionId, transferId, recipientId)

    fun remoteResumed(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.RemoteResumed = TransferEvent.RemoteResumed(sessionId, transferId, recipientId)

    fun transportDisconnected(
        reason: String = "socket closed",
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.TransportDisconnected =
        TransferEvent.TransportDisconnected(sessionId, transferId, recipientId, reason)

    fun transportRestored(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.TransportRestored =
        TransferEvent.TransportRestored(sessionId, transferId, recipientId)

    fun permissionRevoked(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.PermissionRevoked =
        TransferEvent.PermissionRevoked(sessionId, transferId, recipientId, "grant revoked")

    fun retry(
        limit: Int = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.Retry = TransferCommand.Retry(sessionId, transferId, recipientId, limit)

    fun skip(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.Skip = TransferCommand.Skip(sessionId, transferId, recipientId)

    fun cancelLocally(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.CancelLocally =
        TransferCommand.CancelLocally(sessionId, transferId, recipientId)

    fun remoteCancelled(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.RemoteCancelled =
        TransferEvent.RemoteCancelled(sessionId, transferId, recipientId, "peer stopped")

    fun sessionEnded(
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferEvent.SessionEnded = TransferEvent.SessionEnded(sessionId, transferId, recipientId)

    fun applyResume(
        decision: ResumeDecision,
        sessionId: SessionId = this.sessionId,
        transferId: TransferId = this.transferId,
        recipientId: RecipientId? = null,
    ): TransferCommand.ApplyResumeDecision =
        TransferCommand.ApplyResumeDecision(sessionId, transferId, recipientId, decision)

    /** A delivery that has been received in full, verified and committed. */
    fun completed(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        descriptor: TransferFileDescriptor = descriptor(),
    ): TransferSnapshot {
        val verifying = receiveEverything(
            receiving(transferId, sessionId, recipientId, descriptor),
        ) + allBytesConfirmed(sessionId, transferId, recipientId)
        return verifying + TransferEvent.VerificationSucceeded(
            sessionId, transferId, recipientId,
            descriptor.expectedSha256 ?: Sha256Accumulator.EMPTY,
        )
    }

    /** A delivery the user paused, holding a confirmed offset. */
    fun pausedLocally(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        descriptor: TransferFileDescriptor = descriptor(),
        confirmedBytes: Long = 0L,
    ): TransferSnapshot {
        var snapshot = queued(
            transferId = transferId,
            sessionId = sessionId,
            recipientId = recipientId,
            direction = SessionDirection.OUTBOUND,
            descriptor = descriptor,
        )
        if (confirmedBytes > 0L) {
            // One acknowledged chunk, so the snapshot holds a real confirmed
            // offset to resume from.
            snapshot = snapshot + TransferCommand.BeginNegotiation(sessionId, transferId, recipientId)
            snapshot = snapshot + TransferEvent.PeerAccepted(sessionId, transferId, recipientId, chunk)
            val length = confirmedBytes.toInt()
            snapshot = snapshot + TransferEvent.ChunkSent(
                sessionId, transferId, recipientId,
                offset = 0L, length = length, sequence = 0L,
            )
            snapshot = snapshot + TransferEvent.ChunkAcknowledged(
                sessionId, transferId, recipientId,
                offset = 0L, length = length, confirmedOffset = confirmedBytes, sequence = 0L,
            )
        }
        return snapshot + TransferCommand.PauseLocally(sessionId, transferId, recipientId)
    }

    /** A delivery that failed retryably, with a retry count. */
    fun failedRetryable(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        descriptor: TransferFileDescriptor = descriptor(),
        retryCount: Int = 0,
    ): TransferSnapshot {
        var snapshot = sending(transferId, sessionId, recipientId, descriptor)
        repeat(retryCount) {
            snapshot = snapshot + TransferEvent.TransportDisconnected(
                sessionId, transferId, recipientId, "socket closed",
            )
            snapshot = snapshot + TransferCommand.Retry(
                sessionId, transferId, recipientId, ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
            )
            snapshot = snapshot + TransferCommand.BeginNegotiation(sessionId, transferId, recipientId)
            snapshot = snapshot + TransferEvent.PeerAccepted(sessionId, transferId, recipientId, chunk)
        }
        return snapshot + TransferEvent.TransportDisconnected(
            sessionId, transferId, recipientId, "socket closed",
        )
    }

    /** A delivery that failed finally and cannot be retried. */
    fun failedFinally(
        transferId: TransferId = this.transferId,
        sessionId: SessionId = this.sessionId,
        recipientId: RecipientId? = null,
        descriptor: TransferFileDescriptor = descriptor(),
    ): TransferSnapshot = sending(transferId, sessionId, recipientId, descriptor) +
        TransferEvent.PermissionRevoked(sessionId, transferId, recipientId, "grant revoked")

    /** Drives a whole file through ChunkReceived events and returns the result. */
    fun receiveEverything(snapshot: TransferSnapshot): TransferSnapshot {
        var current = snapshot
        val chunkSize = current.descriptor.chunkSize.value
        var offset = 0L
        var sequence = 0L
        while (offset < current.totalBytes) {
            val length = minOf(chunkSize.toLong(), current.totalBytes - offset).toInt()
            current = current + TransferEvent.ChunkReceived(
                sessionId = current.sessionId,
                transferId = current.transferId,
                recipientId = current.recipientId,
                offset = offset,
                length = length,
                sequence = sequence,
            )
            offset += length.toLong()
            sequence++
        }
        return current
    }

    /** Drives a whole file through ChunkSent then ChunkAcknowledged events. */
    fun sendEverything(snapshot: TransferSnapshot): TransferSnapshot {
        var current = snapshot
        val chunkSize = current.descriptor.chunkSize.value
        var offset = 0L
        var sequence = 0L
        while (offset < current.totalBytes) {
            val length = minOf(chunkSize.toLong(), current.totalBytes - offset).toInt()
            current = current + TransferEvent.ChunkSent(
                sessionId = current.sessionId,
                transferId = current.transferId,
                recipientId = current.recipientId,
                offset = offset,
                length = length,
                sequence = sequence,
            )
            current = current + TransferEvent.ChunkAcknowledged(
                sessionId = current.sessionId,
                transferId = current.transferId,
                recipientId = current.recipientId,
                offset = offset,
                length = length,
                confirmedOffset = offset + length.toLong(),
                sequence = sequence,
            )
            offset += length.toLong()
            sequence++
        }
        return current
    }
}

// --- result helpers ----------------------------------------------------------

internal fun current(result: TransitionResult): TransferSnapshot =
    (result as TransitionResult.Accepted).current

internal fun rejection(result: TransitionResult): Rejection =
    (result as TransitionResult.Rejected).reason

internal fun effects(result: TransitionResult): List<TransferEffect> =
    (result as TransitionResult.Accepted).effects

internal fun isAccepted(result: TransitionResult): Boolean = result is TransitionResult.Accepted

internal fun isRejected(result: TransitionResult): Boolean = result is TransitionResult.Rejected

/** Applies a command and returns the new snapshot; fails the test on rejection. */
internal operator fun TransferSnapshot.plus(command: TransferCommand): TransferSnapshot {
    val result = TransferReducer.apply(this, command)
    if (result is TransitionResult.Rejected) {
        throw AssertionError(
            "expected the command to be accepted but it was rejected: ${result.reason.code} " +
                "- ${result.reason.message}",
        )
    }
    return (result as TransitionResult.Accepted).current
}

/** Applies an event and returns the new snapshot; fails the test on rejection. */
internal operator fun TransferSnapshot.plus(event: TransferEvent): TransferSnapshot {
    val result = TransferReducer.apply(this, event)
    if (result is TransitionResult.Rejected) {
        throw AssertionError(
            "expected the event to be accepted but it was rejected: ${result.reason.code} " +
                "- ${result.reason.message}",
        )
    }
    return (result as TransitionResult.Accepted).current
}

internal fun List<TransferEffect>.hasEffectOf(type: kotlin.reflect.KClass<out TransferEffect>): Boolean =
    any { it::class == type }

internal fun List<TransferEffect>.codes(): List<String> = map { it::class.java.simpleName }

internal fun transferError(): TransferError = TransferError.TransportDisconnected("unit test")
