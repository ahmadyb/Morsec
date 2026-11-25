package app.morsecode.core.transfer.runtime

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.SequenceNumber
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.protocol.FrameCodec
import app.morsecode.core.transfer.protocol.FrameDecodeResult
import app.morsecode.core.transfer.protocol.FramePayload
import app.morsecode.core.transfer.protocol.FramePayloads
import app.morsecode.core.transfer.protocol.FrameType
import app.morsecode.core.transfer.protocol.NOT_APPLICABLE
import app.morsecode.core.transfer.protocol.PayloadResult
import app.morsecode.core.transfer.protocol.TransferFrame
import app.morsecode.core.transfer.reducer.TransferReducer
import app.morsecode.core.transfer.reducer.TransitionResult
import kotlin.coroutines.cancellation.CancellationException

/** Outcome of one serialized runtime entry. A halted runtime cannot be restarted. */
public sealed interface SingleFileResult {
    public data object Advanced : SingleFileResult
    /** An empty receiver has accepted but is waiting for the matching metadata frame. */
    public data object WaitingForMetadata : SingleFileResult
    public data class Refused(public val reason: String) : SingleFileResult

    /**
     * No further effects will run. [reportedEvent] is the factual failure sent to
     * the reducer, when an appropriate event exists; [eventAccepted] says whether
     * the reducer accepted it. Its resulting effects are deliberately not run.
     * [requiresReconciliation] includes ambiguous outcomes, possible uncheckpointed
     * partial bytes, an unacknowledged checkpoint, and any halt after a provisional
     * verified revision was saved. No restart or reconciliation is implemented here.
     */
    public data class Halted(
        public val operation: String,
        public val error: TransferError,
        public val reportedEvent: TransferEvent?,
        public val eventAccepted: Boolean,
        public val requiresReconciliation: Boolean,
    ) : SingleFileResult
}

/**
 * Inert, synchronous single-file runtime. It is not connected to the app, real
 * transport, Room, or storage adapters. Only a fresh, one-direction delivery
 * with known length and SHA-256 is supported; there is no pause, resume, retry,
 * reconnect, batch, or restart executor.
 *
 * Every snapshot comes directly from TransferReducer. Each accepted effect
 * program is executed in order. Successful facts are dispatched after that
 * program finishes, so an outcome cannot overtake its remaining effects. A
 * failed effect stops the whole runtime: its matching failure event is applied,
 * but neither the abandoned effects nor the failure transition's effects run.
 * In particular, failure cannot leak a later ACK, completion, UI, or schedule.
 * An indeterminate result is never converted into a guessed failure or success.
 *
 * Entry methods serialize calls and reject reentrant callbacks. Ports must be
 * synchronous and non-reentrant; a transport must queue frames instead of
 * delivering a response from inside send. A visible snapshot can be provisional:
 * consult the result as well, especially after a post-commit persistence fault.
 */
public class SingleFileRuntime(
    private val enqueue: TransferCommand.Enqueue,
    private val role: SingleFileRole,
    private val store: SingleFileSnapshotStore,
    private val transport: SingleFileTransport,
    private val observer: SingleFileObserver,
) {
    private var current: TransferSnapshot? = null
    private var started: Boolean = false
    private var busy: Boolean = false
    private var stopped: SingleFileResult.Halted? = null
    private var sourceReader: RuntimeTypedReader? = null
    private var metadataReceived: Boolean = false
    private var pendingEmptyEof: TransferEffect.DispatchAllBytesConfirmed? = null
    private var uncheckpointedPartialMayExist: Boolean = false
    private var checkpointAwaitingAck: Boolean = false
    private var verifiedPreflightSaved: Boolean = false
    private var publicationSucceeded: Boolean = false

    /** The latest reducer-produced snapshot, which may not yet be durable. */
    @Synchronized
    public fun snapshot(): TransferSnapshot? = current

    @Synchronized
    public fun start(): SingleFileResult = entry {
        if (started) return@entry SingleFileResult.Refused("already started")
        if (enqueue.descriptor.expectedSha256 == null || enqueue.descriptor.isFolderArchive ||
            enqueue.descriptor.protocolVersion != ProtocolVersion.CURRENT ||
            (enqueue.direction == SessionDirection.OUTBOUND && role !is SingleFileRole.Sender) ||
            (enqueue.direction == SessionDirection.INBOUND && role !is SingleFileRole.Receiver)
        ) {
            return@entry SingleFileResult.Refused("unsupported single-file profile")
        }
        started = true
        val first = drive(TransferReducer.enqueue(enqueue))
        if (first != SingleFileResult.Advanced) return@entry first
        drive(TransferReducer.apply(requireCurrent(), TransferCommand.BeginNegotiation(
            enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
        )))
    }

    /** Process one complete encoded frame. Invalid or out-of-profile input fails closed. */
    @Synchronized
    public fun receive(encoded: ByteArray): SingleFileResult = entry {
        val snapshot = current ?: return@entry SingleFileResult.Refused("not started")
        if (snapshot.isTerminal) return@entry SingleFileResult.Refused("delivery is terminal")
        val decoded = FrameCodec.decodeSingle(encoded)
        val frame = when (decoded) {
            is FrameDecodeResult.Success -> decoded.frame
            is FrameDecodeResult.Invalid -> return@entry protocolFailure(decoded.error)
        }
        if (frame.sessionId != snapshot.sessionId || frame.transferId != snapshot.transferId ||
            frame.recipientId != snapshot.recipientId || frame.version != snapshot.descriptor.protocolVersion
        ) {
            return@entry protocolFailure(TransferError.MalformedFrame("frame delivery identity mismatch"))
        }
        if (!validEnvelopePosition(frame)) {
            return@entry protocolFailure(TransferError.MalformedFrame("unexpected frame position"))
        }
        val parsed = FramePayloads.parse(frame)
        val payload = when (parsed) {
            is PayloadResult.Success -> parsed.payload
            is PayloadResult.Invalid -> return@entry protocolFailure(parsed.error)
        }
        if (pendingEmptyEof != null && payload !is FramePayload.FileMetadata) {
            return@entry protocolFailure(TransferError.MalformedFrame("metadata required before empty-file verification"))
        }
        when (payload) {
            is FramePayload.Handshake -> receiveHandshake(snapshot, payload)
            is FramePayload.HandshakeAccept -> receiveAcceptance(snapshot, payload)
            is FramePayload.HandshakeReject -> {
                if (role !is SingleFileRole.Sender || snapshot.state != TransferState.NEGOTIATING) {
                    protocolFailure(TransferError.MalformedFrame("unexpected handshake rejection"))
                } else {
                    peerEvent(TransferEvent.PeerRejected(
                        enqueue.sessionId, enqueue.transferId, enqueue.recipientId, "peer declined",
                    ))
                }
            }
            is FramePayload.FileMetadata -> receiveMetadata(snapshot, payload)
            is FramePayload.DataChunk -> receiveChunk(snapshot, payload)
            is FramePayload.ChunkAck -> receiveAcknowledgement(snapshot, frame, payload)
            is FramePayload.VerificationResult -> receiveVerification(snapshot, payload)
            is FramePayload.FileComplete -> {
                if (role !is SingleFileRole.Sender) {
                    protocolFailure(TransferError.MalformedFrame("FILE_COMPLETE is receiver to sender"))
                } else {
                    peerEvent(TransferEvent.FileCompleteReceived(
                        enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
                        payload.fileId, payload.totalBytes, payload.digest,
                    ))
                }
            }
            else -> protocolFailure(TransferError.MalformedFrame("frame outside single-file profile"))
        }
    }

    /** Read and send exactly one chunk; only one chunk may be awaiting its ACK. */
    @Synchronized
    public fun sendNextChunk(): SingleFileResult = entry {
        val snapshot = current ?: return@entry SingleFileResult.Refused("not started")
        if (role !is SingleFileRole.Sender || snapshot.state != TransferState.SENDING) {
            return@entry SingleFileResult.Refused("sender is not streaming")
        }
        if (snapshot.optimisticBytes != snapshot.confirmedBytes) {
            return@entry SingleFileResult.Refused("waiting for acknowledgement")
        }
        val length = snapshot.chunkSize.lengthOfChunkAt(snapshot.optimisticBytes, snapshot.totalBytes)
        if (length == 0) return@entry SingleFileResult.Refused("no chunk remains")
        val reader = sourceReader ?: return@entry internalFailure("source_not_open")
        val bytes = ByteArray(length)
        var used = 0
        while (used < length) {
            when (val read = readSource(reader, bytes, used, length - used)) {
                is RuntimeReadResult.Data -> used += read.count
                RuntimeReadResult.EndOfSource -> return@entry sourceFailure("source shorter than declared")
                RuntimeReadResult.Cancelled -> return@entry cancelled("read_source")
                is RuntimeReadResult.Failed -> return@entry sourceFailure(read.error)
            }
        }
        if (snapshot.optimisticBytes + length == snapshot.totalBytes) {
            when (val end = readSource(reader, ByteArray(1), 0, 1)) {
                RuntimeReadResult.EndOfSource -> closeSource()
                RuntimeReadResult.Cancelled -> return@entry cancelled("read_source")
                is RuntimeReadResult.Data -> return@entry sourceFailure("source longer than declared")
                is RuntimeReadResult.Failed -> return@entry sourceFailure(end.error)
            }
        }
        val offset = snapshot.optimisticBytes
        val sequence = SequenceNumber.forOffset(offset, snapshot.chunkSize).value
        val frame = FrameCodec.dataChunk(
            enqueue.sessionId, enqueue.transferId, offset, sequence, bytes, enqueue.recipientId,
        )
        when (send(frame)) {
            is PortResult.Success -> Unit
            is PortResult.Failed -> return@entry transportFailure()
            PortResult.Cancelled -> return@entry cancelled("send_chunk")
            PortResult.Indeterminate -> return@entry uncertain("send_chunk")
        }
        drive(TransferReducer.apply(snapshot, TransferEvent.ChunkSent(
            enqueue.sessionId, enqueue.transferId, enqueue.recipientId, offset, length, sequence,
        )))
    }

    private inline fun entry(block: () -> SingleFileResult): SingleFileResult {
        if (busy) return SingleFileResult.Refused("reentrant runtime entry")
        stopped?.let { return it }
        busy = true
        return try { block() } finally { busy = false }
    }

    private fun receiveHandshake(snapshot: TransferSnapshot, payload: FramePayload.Handshake): SingleFileResult {
        val capabilities = payload.capabilities.split(';').toSet()
        if (role !is SingleFileRole.Receiver || snapshot.state != TransferState.NEGOTIATING ||
            payload.maxChunkSize != snapshot.chunkSize || !capabilities.containsAll(setOf("v1", "crc32", "sha256"))
        ) {
            return protocolFailure(TransferError.MalformedFrame("unsupported handshake"))
        }
        return drive(TransferReducer.apply(snapshot, TransferCommand.Accept(
            enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
        )))
    }

    private fun receiveAcceptance(snapshot: TransferSnapshot, payload: FramePayload.HandshakeAccept): SingleFileResult {
        if (role !is SingleFileRole.Sender || snapshot.state != TransferState.NEGOTIATING ||
            payload.resumeFromOffset != 0L || payload.chunkSize != snapshot.chunkSize
        ) {
            return protocolFailure(TransferError.MalformedFrame("unsupported handshake acceptance"))
        }
        return peerEvent(TransferEvent.PeerAccepted(
            enqueue.sessionId, enqueue.transferId, enqueue.recipientId, payload.chunkSize,
        ))
    }

    private fun receiveMetadata(snapshot: TransferSnapshot, payload: FramePayload.FileMetadata): SingleFileResult {
        if (role !is SingleFileRole.Receiver || snapshot.state != TransferState.RECEIVING ||
            metadataReceived || payload.descriptor != snapshot.descriptor
        ) {
            return protocolFailure(TransferError.DescriptorMismatch("metadata"))
        }
        metadataReceived = true
        val pending = pendingEmptyEof ?: return SingleFileResult.Advanced
        pendingEmptyEof = null
        return dispatchImplicitEof(pending)
    }

    private fun receiveChunk(snapshot: TransferSnapshot, payload: FramePayload.DataChunk): SingleFileResult {
        val destination = (role as? SingleFileRole.Receiver)?.destination
        val length = payload.bytes.size
        if (destination == null || snapshot.state != TransferState.RECEIVING || !metadataReceived ||
            snapshot.optimisticBytes != snapshot.confirmedBytes || payload.offset != snapshot.confirmedBytes ||
            length <= 0 || length > snapshot.chunkSize.value ||
            payload.offset < 0L || payload.offset > snapshot.totalBytes ||
            length.toLong() > snapshot.totalBytes - payload.offset ||
            payload.sequence != SequenceNumber.forOffset(payload.offset, snapshot.chunkSize).value
        ) {
            return protocolFailure(TransferError.MalformedFrame("invalid sequential chunk"))
        }
        // Even a definite write failure can leave uncheckpointed physical residue.
        uncheckpointedPartialMayExist = true
        when (val write = port { destination.writeDurably(payload.offset, payload.bytes.copyOf()) }) {
            is PortResult.Success -> Unit
            is PortResult.Failed -> return storageFailure("write_destination", destinationError(write.error))
            PortResult.Cancelled -> return cancelled("write_destination")
            PortResult.Indeterminate -> return uncertain("write_destination")
        }
        return drive(TransferReducer.apply(snapshot, TransferEvent.ChunkDurablyStored(
            enqueue.sessionId, enqueue.transferId, enqueue.recipientId, payload.offset, length, payload.sequence,
        )))
    }

    private fun receiveAcknowledgement(
        snapshot: TransferSnapshot,
        frame: TransferFrame,
        payload: FramePayload.ChunkAck,
    ): SingleFileResult {
        if (role !is SingleFileRole.Sender || snapshot.state != TransferState.SENDING || !payload.accepted ||
            frame.offset != payload.offset || payload.offset != snapshot.confirmedBytes ||
            payload.length <= 0 || payload.length.toLong() != snapshot.inFlightBytes ||
            payload.confirmedOffset != snapshot.optimisticBytes || payload.offset > snapshot.totalBytes
        ) {
            return protocolFailure(TransferError.MalformedFrame("invalid acknowledgement"))
        }
        return peerEvent(TransferEvent.ChunkAcknowledged(
            enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
            payload.offset, payload.length, payload.confirmedOffset,
            SequenceNumber.forOffset(payload.offset, snapshot.chunkSize).value,
        ))
    }

    private fun receiveVerification(snapshot: TransferSnapshot, payload: FramePayload.VerificationResult): SingleFileResult {
        if (role !is SingleFileRole.Sender || snapshot.state != TransferState.VERIFYING ||
            payload.totalBytes != snapshot.totalBytes
        ) {
            return protocolFailure(TransferError.MalformedFrame("invalid verification result"))
        }
        if (payload.success && payload.observedDigest != snapshot.descriptor.expectedSha256) {
            return protocolFailure(TransferError.DescriptorMismatch("verification digest"))
        }
        return if (payload.success) {
            peerEvent(TransferEvent.VerificationSucceeded(
                enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
                payload.observedDigest, payload.totalBytes,
            ))
        } else {
            peerEvent(TransferEvent.VerificationFailed(
                enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
                snapshot.descriptor.expectedSha256, payload.observedDigest,
            ))
        }
    }

    private fun validEnvelopePosition(frame: TransferFrame): Boolean = when (frame.type) {
        FrameType.DATA_CHUNK -> frame.offset >= 0L && frame.sequence >= 0L
        FrameType.CHUNK_ACK -> frame.offset >= 0L && frame.sequence == NOT_APPLICABLE
        else -> frame.offset == NOT_APPLICABLE && frame.sequence == NOT_APPLICABLE
    }

    private fun peerEvent(event: TransferEvent): SingleFileResult {
        val transition = TransferReducer.apply(requireCurrent(), event)
        return if (transition is TransitionResult.Rejected) {
            protocolFailure(TransferError.MalformedFrame("peer event rejected"))
        } else {
            drive(transition)
        }
    }

    /** Drive one reducer transition and then its successful outcomes, without interleaving. */
    private fun drive(result: TransitionResult): SingleFileResult {
        if (result !is TransitionResult.Accepted) return internalFailure("reducer_rejected")
        current = result.current
        val outcomes = mutableListOf<TransferEvent>()
        for ((index, effect) in result.effects.withIndex()) {
            when (val step = execute(effect, result, index)) {
                EffectStep.Done -> Unit
                is EffectStep.Event -> outcomes += step.event
                EffectStep.WaitForMetadata -> return SingleFileResult.WaitingForMetadata
                is EffectStep.Fault -> return halt(step.operation, step.error, step.event, step.uncertain)
            }
        }
        for (event in outcomes) {
            val next = drive(TransferReducer.apply(requireCurrent(), event))
            if (next != SingleFileResult.Advanced) return next
        }
        return SingleFileResult.Advanced
    }

    private sealed interface EffectStep {
        data object Done : EffectStep
        data class Event(val event: TransferEvent) : EffectStep
        data object WaitForMetadata : EffectStep
        data class Fault(
            val operation: String,
            val error: TransferError,
            val event: TransferEvent? = null,
            val uncertain: Boolean = false,
        ) : EffectStep
    }

    private fun execute(effect: TransferEffect, transition: TransitionResult.Accepted, index: Int): EffectStep {
        val snapshot = requireCurrent()
        if (effect.transferId != null && effect.transferId != snapshot.transferId) return invariant("effect_identity")
        return when (effect) {
            is TransferEffect.SendHandshake -> {
                if (role !is SingleFileRole.Sender) return invariant("handshake_direction")
                // The generic reducer advertises resume; this profile explicitly removes it.
                val capabilities = effect.capabilities.split(';').filterNot { it == "resume" }.joinToString(";")
                wire(FrameCodec.handshake(
                    enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
                    capabilities, effect.chunkSize, resumeSupported = false,
                ))
            }
            is TransferEffect.SendHandshakeAccept -> {
                if (role !is SingleFileRole.Receiver || effect.resumeFromOffset != 0L) return invariant("accept_direction")
                wire(FrameCodec.handshakeAccept(
                    enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
                    effect.chunkSize, resumeSupported = false, resumeFromOffset = 0L,
                ))
            }
            is TransferEffect.SendMetadata -> {
                if (role !is SingleFileRole.Sender || effect.descriptor != snapshot.descriptor) return invariant("metadata_direction")
                wire(FrameCodec.fileMetadata(enqueue.sessionId, enqueue.transferId, effect.descriptor, enqueue.recipientId))
            }
            is TransferEffect.RequestSourceStream -> openSource(effect)
            is TransferEffect.RequestDestinationPartial -> openDestination(effect)
            is TransferEffect.PersistSnapshot -> {
                val targetsCurrent = effect.snapshotVersion == transition.current.snapshotVersion
                val target = when (effect.snapshotVersion) {
                    transition.current.snapshotVersion -> transition.current
                    transition.previous?.snapshotVersion -> transition.previous
                    else -> null
                } ?: return invariant("unknown_snapshot_revision")
                val pending = transition.effects.drop(index + 1).firstOrNull() as? TransferEffect.PersistConfirmedOffset
                val checkpointCandidate = pending?.confirmedOffset?.takeIf {
                    role is SingleFileRole.Receiver && snapshot.optimisticBytes == it && it > snapshot.confirmedBytes
                }
                persistenceStep(
                    saveSnapshot(
                        target, expectedPrevious = if (targetsCurrent) transition.previous else null,
                        requireNew = transition.previous == null, requireExisting = !targetsCurrent,
                    ),
                    "save_snapshot", checkpointCandidate,
                )
            }
            is TransferEffect.PersistConfirmedOffset -> {
                if (effect.confirmedOffset < 0L || effect.confirmedOffset > snapshot.totalBytes) return invariant("checkpoint_range")
                val receiverCandidate = if (role is SingleFileRole.Receiver) effect.confirmedOffset else null
                if (receiverCandidate != null &&
                    (snapshot.optimisticBytes != receiverCandidate || snapshot.confirmedBytes >= receiverCandidate)
                ) return invariant("checkpoint_candidate")
                if (role is SingleFileRole.Sender && snapshot.confirmedBytes != effect.confirmedOffset) {
                    return invariant("sender_checkpoint")
                }
                val saved = port { store.saveCheckpoint(snapshot.transferId, ConfirmedOffset(effect.confirmedOffset)) }
                val step = persistenceStep(saved, "save_checkpoint", receiverCandidate)
                if (step == EffectStep.Done && receiverCandidate != null) {
                    uncheckpointedPartialMayExist = false
                    checkpointAwaitingAck = true
                    EffectStep.Event(TransferEvent.ConfirmedOffsetPersisted(
                        enqueue.sessionId, enqueue.transferId, enqueue.recipientId, receiverCandidate,
                    ))
                } else step
            }
            is TransferEffect.SendAcknowledgement -> {
                if (role !is SingleFileRole.Receiver || !checkpointAwaitingAck ||
                    snapshot.confirmedBytes != effect.confirmedOffset || effect.offset < 0L ||
                    effect.offset > effect.confirmedOffset || effect.length <= 0 ||
                    effect.length.toLong() != effect.confirmedOffset - effect.offset
                ) return invariant("acknowledgement_direction")
                val sent = wire(FrameCodec.chunkAck(
                    enqueue.sessionId, enqueue.transferId, effect.offset, effect.length,
                    effect.confirmedOffset, recipientId = enqueue.recipientId,
                ))
                if (sent == EffectStep.Done) checkpointAwaitingAck = false
                sent
            }
            is TransferEffect.DispatchAllBytesConfirmed -> {
                if (snapshot.snapshotVersion != effect.snapshotVersion || !snapshot.allBytesConfirmed) {
                    return invariant("stale_implicit_eof")
                }
                if (role is SingleFileRole.Receiver && !metadataReceived) {
                    if (snapshot.totalBytes != 0L || index != transition.effects.lastIndex) return invariant("metadata_barrier")
                    pendingEmptyEof = effect
                    EffectStep.WaitForMetadata
                } else {
                    EffectStep.Event(allBytesConfirmed())
                }
            }
            is TransferEffect.BeginVerification -> beginVerification(effect)
            is TransferEffect.CommitVerifiedDestination -> commitDestination(effect, transition.previous)
            is TransferEffect.SendVerificationResult -> {
                if (role !is SingleFileRole.Receiver || effect.totalBytes != snapshot.totalBytes || (effect.success &&
                        (!publicationSucceeded || snapshot.state != TransferState.COMPLETED ||
                            effect.observedDigest != snapshot.descriptor.expectedSha256))
                ) return invariant("verification_result_direction")
                wire(FrameCodec.verificationResult(
                    enqueue.sessionId, enqueue.transferId, effect.success, effect.totalBytes,
                    effect.observedDigest, enqueue.recipientId,
                ))
            }
            is TransferEffect.SendFileComplete -> {
                val verification = snapshot.verification
                if (role !is SingleFileRole.Receiver || !publicationSucceeded || snapshot.state != TransferState.COMPLETED ||
                    verification?.allowsCommit != true || effect.fileId != snapshot.fileId ||
                    effect.totalBytes != snapshot.totalBytes || effect.digest != snapshot.descriptor.expectedSha256 ||
                    effect.digest != verification.observedDigest
                ) return invariant("completion_direction")
                wire(FrameCodec.fileComplete(
                    enqueue.sessionId, enqueue.transferId, effect.fileId, effect.totalBytes,
                    effect.digest, enqueue.recipientId,
                ))
            }
            is TransferEffect.NotifyUi -> {
                if (effect.state != snapshot.state) return invariant("stale_notification")
                observerStep(port { observer.notify(effect.state, snapshot.snapshotVersion) }, "notify")
            }
            is TransferEffect.ScheduleNextEligibleItem -> {
                if (effect.sessionId != snapshot.sessionId) return invariant("schedule_identity")
                observerStep(port { observer.schedule() }, "schedule")
            }
            // None of these are reachable in the supported profile. Do not invent
            // bytes, resume, delete a partial, or emit other protocol operations.
            is TransferEffect.SendHandshakeReject, is TransferEffect.SendChunk,
            is TransferEffect.SendPause, is TransferEffect.SendResume, is TransferEffect.SendCancel,
            is TransferEffect.DeletePartialDestination -> invariant("unsupported_effect")
        }
    }

    private fun openSource(effect: TransferEffect.RequestSourceStream): EffectStep {
        val source = (role as? SingleFileRole.Sender)?.source ?: return invariant("source_direction")
        if (effect.fromOffset != 0L || sourceReader != null) return invariant("source_offset")
        return when (val opened = port { source.openFreshReader() }) {
            is PortResult.Failed -> storageFault("open_source", sourceError(opened.error))
            PortResult.Cancelled -> cancelledStep("open_source")
            PortResult.Indeterminate -> unknownStep("open_source")
            is PortResult.Success -> {
                sourceReader = opened.value
                if (requireCurrent().totalBytes != 0L) return EffectStep.Done
                when (val end = readSource(opened.value, ByteArray(1), 0, 1)) {
                    RuntimeReadResult.EndOfSource -> { closeSource(); EffectStep.Done }
                    RuntimeReadResult.Cancelled -> cancelledStep("read_source")
                    is RuntimeReadResult.Data -> storageFault("read_source", TransferError.SourceUnavailable("source longer than declared"))
                    is RuntimeReadResult.Failed -> storageFault("read_source", sourceError(end.error))
                }
            }
        }
    }

    private fun openDestination(effect: TransferEffect.RequestDestinationPartial): EffectStep {
        val destination = (role as? SingleFileRole.Receiver)?.destination ?: return invariant("destination_direction")
        if (effect.expectedOffset != 0L) return invariant("destination_offset")
        return when (val opened = port { destination.openFresh() }) {
            is PortResult.Success -> EffectStep.Done
            is PortResult.Failed -> storageFault("open_destination", destinationError(opened.error))
            PortResult.Cancelled -> cancelledStep("open_destination")
            PortResult.Indeterminate -> unknownStep("open_destination")
        }
    }

    private fun beginVerification(effect: TransferEffect.BeginVerification): EffectStep {
        val snapshot = requireCurrent()
        val destination = (role as? SingleFileRole.Receiver)?.destination ?: return invariant("verification_direction")
        if (!metadataReceived || !snapshot.allBytesConfirmed || effect.expectedDigest != snapshot.descriptor.expectedSha256) {
            return invariant("verification_precondition")
        }
        val opened = port { destination.openVerification() }
        val reader = when (opened) {
            is PortResult.Success -> opened.value
            is PortResult.Failed -> return storageFault("open_verification", destinationError(opened.error))
            PortResult.Cancelled -> return cancelledStep("open_verification")
            PortResult.Indeterminate -> return unknownStep("open_verification")
        }
        val hash = Sha256Accumulator()
        val buffer = ByteArray(snapshot.chunkSize.value)
        var remaining = snapshot.totalBytes
        try {
            while (remaining > 0L) {
                val request = minOf(remaining, buffer.size.toLong()).toInt()
                when (val read = readDestination(reader, buffer, 0, request)) {
                    is RuntimeReadResult.Data -> { hash.update(buffer, 0, read.count); remaining -= read.count.toLong() }
                    RuntimeReadResult.EndOfSource -> return storageFault(
                        "verify_destination", TransferError.DestinationUnavailable("partial shorter than declared"),
                    )
                    RuntimeReadResult.Cancelled -> return cancelledStep("verify_destination")
                    is RuntimeReadResult.Failed -> return storageFault("verify_destination", destinationError(read.error))
                }
            }
            when (val end = readDestination(reader, buffer, 0, 1)) {
                RuntimeReadResult.EndOfSource -> Unit
                RuntimeReadResult.Cancelled -> return cancelledStep("verify_destination")
                is RuntimeReadResult.Data -> return storageFault(
                    "verify_destination", TransferError.DestinationUnavailable("partial longer than declared"),
                )
                is RuntimeReadResult.Failed -> return storageFault("verify_destination", destinationError(end.error))
            }
        } finally {
            closeReader(reader)
        }
        val observed = hash.digest()
        return if (observed == effect.expectedDigest) {
            EffectStep.Event(TransferEvent.VerificationSucceeded(
                enqueue.sessionId, enqueue.transferId, enqueue.recipientId, observed,
            ))
        } else {
            EffectStep.Event(TransferEvent.VerificationFailed(
                enqueue.sessionId, enqueue.transferId, enqueue.recipientId, effect.expectedDigest, observed,
            ))
        }
    }

    private fun commitDestination(
        effect: TransferEffect.CommitVerifiedDestination,
        previous: TransferSnapshot?,
    ): EffectStep {
        val snapshot = requireCurrent()
        val destination = (role as? SingleFileRole.Receiver)?.destination ?: return invariant("commit_direction")
        if (previous == null || snapshot.snapshotVersion != effect.verifiedSnapshotVersion ||
            snapshot.state != TransferState.VERIFYING || snapshot.verification?.allowsCommit != true || !metadataReceived
        ) return invariant("commit_precondition")
        // This is part of the commit effect itself: persist the provisional
        // verified revision before attempting any physical publication.
        when (val saved = saveSnapshot(snapshot, expectedPrevious = previous)) {
            is PortResult.Success -> Unit
            is PortResult.Failed -> {
                val error = persistenceError(saved.error, "committed")
                return EffectStep.Fault("precommit_snapshot", error, commitFailure(error))
            }
            PortResult.Cancelled -> return cancelledStep("precommit_snapshot")
            PortResult.Indeterminate -> return unknownStep("precommit_snapshot")
        }
        verifiedPreflightSaved = true
        return when (val committed = port { destination.commitVerified() }) {
            is PortResult.Success -> {
                publicationSucceeded = true
                EffectStep.Event(TransferEvent.DestinationCommitSucceeded(
                    enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
                ))
            }
            is PortResult.Failed -> {
                val error = destinationError(committed.error)
                EffectStep.Fault("commit_destination", error, commitFailure(error))
            }
            PortResult.Cancelled -> cancelledStep("commit_destination")
            PortResult.Indeterminate -> unknownStep("commit_destination")
        }
    }

    /** Load the actual durable row for every CAS, including after checkpointing. */
    private fun saveSnapshot(
        target: TransferSnapshot,
        expectedPrevious: TransferSnapshot?,
        requireNew: Boolean = false,
        requireExisting: Boolean = false,
    ): PortResult<Unit> {
        val loaded = port { store.loadSnapshot(target.transferId) }
        val previous = when (loaded) {
            is PortResult.Success -> loaded.value
            is PortResult.Failed -> return loaded
            PortResult.Cancelled -> return PortResult.Cancelled
            PortResult.Indeterminate -> return PortResult.Indeterminate
        }
        // This runtime never resumes or adopts a row, even an identical queued one.
        if (requireNew && previous != null) {
            return PortResult.Failed(TransferError.PersistenceConflict("snapshot_already_exists"))
        }
        if (previous == target) return PortResult.Success(Unit)
        if (requireExisting) return PortResult.Failed(TransferError.PersistenceConflict("expected_snapshot_missing"))
        if (previous == null) {
            if (!requireNew || expectedPrevious != null || target.snapshotVersion != TransferSnapshot.INITIAL_SNAPSHOT_VERSION) {
                return PortResult.Failed(TransferError.PersistenceConflict("snapshot_previous_missing"))
            }
        } else if (expectedPrevious == null ||
            (previous != expectedPrevious && !isCheckpointAdjusted(previous, expectedPrevious, target)) ||
            previous.transferId != target.transferId || previous.sessionId != target.sessionId ||
            previous.batchId != target.batchId || previous.recipientId != target.recipientId ||
            previous.direction != target.direction || previous.descriptor != target.descriptor ||
            previous.snapshotVersion == Long.MAX_VALUE || target.snapshotVersion != previous.snapshotVersion + 1L ||
            target.confirmedBytes < previous.confirmedBytes
        ) {
            return PortResult.Failed(TransferError.PersistenceConflict("snapshot_revision_conflict"))
        }
        return port { store.saveTransition(previous, target) }
    }

    /** The only permitted same-revision change is this program's checkpoint. */
    private fun isCheckpointAdjusted(
        stored: TransferSnapshot,
        expected: TransferSnapshot,
        target: TransferSnapshot,
    ): Boolean = stored.transferId == expected.transferId && stored.sessionId == expected.sessionId &&
        stored.batchId == expected.batchId && stored.recipientId == expected.recipientId &&
        stored.direction == expected.direction && stored.descriptor == expected.descriptor && stored.state == expected.state &&
        stored.confirmedBytes > expected.confirmedBytes && stored.confirmedBytes == expected.optimisticBytes &&
        stored.confirmedBytes == target.confirmedBytes && stored.optimisticBytes == expected.optimisticBytes &&
        stored.lastAcknowledgedSequence == expected.lastAcknowledgedSequence && stored.retryCount == expected.retryCount &&
        stored.failure == expected.failure && stored.verification == expected.verification &&
        stored.remotePaused == expected.remotePaused && stored.snapshotVersion == expected.snapshotVersion &&
        stored.queueOrder == expected.queueOrder

    private fun persistenceStep(result: PortResult<Unit>, operation: String, candidate: Long?): EffectStep = when (result) {
        is PortResult.Success -> EffectStep.Done
        is PortResult.Failed -> {
            val error = persistenceError(result.error, if (candidate != null) "checkpointed" else "saved")
            val event = if (candidate != null) {
                TransferEvent.ConfirmedOffsetPersistenceFailed(
                    enqueue.sessionId, enqueue.transferId, enqueue.recipientId, candidate, error.storageFull,
                )
            } else storageEvent(error)
            EffectStep.Fault(operation, error, event)
        }
        PortResult.Cancelled -> cancelledStep(operation)
        PortResult.Indeterminate -> unknownStep(operation)
    }

    private fun wire(frame: TransferFrame): EffectStep = when (send(frame)) {
        is PortResult.Success -> EffectStep.Done
        is PortResult.Failed -> {
            val error = TransferError.TransportDisconnected("transport send failed")
            EffectStep.Fault("send_frame", error, transportEvent())
        }
        PortResult.Cancelled -> cancelledStep("send_frame")
        PortResult.Indeterminate -> unknownStep("send_frame")
    }

    private fun send(frame: TransferFrame): PortResult<Unit> = port { transport.send(FrameCodec.encode(frame)) }

    private fun observerStep(result: PortResult<Unit>, operation: String): EffectStep = when (result) {
        is PortResult.Success -> EffectStep.Done
        is PortResult.Failed -> EffectStep.Fault(operation, TransferError.UnexpectedInternal("observer failed"))
        PortResult.Cancelled -> cancelledStep(operation)
        PortResult.Indeterminate -> unknownStep(operation)
    }

    private fun dispatchImplicitEof(effect: TransferEffect.DispatchAllBytesConfirmed): SingleFileResult {
        val snapshot = requireCurrent()
        if (effect.snapshotVersion != snapshot.snapshotVersion || !snapshot.allBytesConfirmed || !metadataReceived) {
            return internalFailure("stale_implicit_eof")
        }
        return drive(TransferReducer.apply(snapshot, allBytesConfirmed()))
    }

    private fun allBytesConfirmed(): TransferEvent.AllBytesConfirmed = TransferEvent.AllBytesConfirmed(
        enqueue.sessionId, enqueue.transferId, enqueue.recipientId,
    )

    private fun readSource(reader: RuntimeTypedReader, buffer: ByteArray, offset: Int, length: Int): RuntimeReadResult =
        checkedRead(reader, buffer, offset, length, TransferError.SourceUnavailable("source read failed"))

    private fun readDestination(reader: RuntimeTypedReader, buffer: ByteArray, offset: Int, length: Int): RuntimeReadResult =
        checkedRead(reader, buffer, offset, length, TransferError.DestinationUnavailable("partial read failed"))

    private fun checkedRead(
        reader: RuntimeTypedReader,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        fallback: TransferError,
    ): RuntimeReadResult {
        val result = try {
            reader.read(buffer, offset, length)
        } catch (_: CancellationException) {
            RuntimeReadResult.Cancelled
        } catch (_: Exception) {
            RuntimeReadResult.Failed(fallback)
        }
        return if (result is RuntimeReadResult.Data && (result.count <= 0 || result.count > length)) {
            RuntimeReadResult.Failed(fallback)
        } else result
    }

    private inline fun <T> port(block: () -> PortResult<T>): PortResult<T> =
        try { block() } catch (_: Exception) { PortResult.Indeterminate }

    private fun closeSource() {
        val reader = sourceReader ?: return
        sourceReader = null
        closeReader(reader)
    }

    private fun closeReader(reader: RuntimeTypedReader) {
        try { reader.close() } catch (_: Exception) { /* close is best effort; never expose provider text */ }
    }

    private fun sourceError(error: TransferError): TransferError = when (error) {
        is TransferError.PermissionRevoked -> TransferError.PermissionRevoked("source permission revoked")
        else -> TransferError.SourceUnavailable("source operation failed")
    }

    private fun destinationError(error: TransferError): TransferError = when (error) {
        is TransferError.PermissionRevoked -> TransferError.PermissionRevoked("destination permission revoked")
        is TransferError.StorageFull -> TransferError.StorageFull(enqueue.descriptor.totalBytes)
        is TransferError.PersistenceFailure -> persistenceError(error, "committed")
        else -> TransferError.DestinationUnavailable("destination operation failed")
    }

    private fun persistenceError(error: TransferError, operation: String): TransferError.PersistenceFailure =
        TransferError.PersistenceFailure(operation, error is TransferError.StorageFull ||
            (error is TransferError.PersistenceFailure && error.storageFull))

    private fun storageEvent(error: TransferError): TransferEvent.StorageFailed = TransferEvent.StorageFailed(
        enqueue.sessionId, enqueue.transferId, enqueue.recipientId, error,
    )

    private fun transportEvent(): TransferEvent.TransportDisconnected = TransferEvent.TransportDisconnected(
        enqueue.sessionId, enqueue.transferId, enqueue.recipientId, "transport send failed",
    )

    private fun commitFailure(error: TransferError): TransferEvent.DestinationCommitFailed =
        TransferEvent.DestinationCommitFailed(enqueue.sessionId, enqueue.transferId, enqueue.recipientId, error)

    private fun storageFault(operation: String, error: TransferError): EffectStep =
        EffectStep.Fault(operation, error, storageEvent(error))

    private fun cancelledStep(operation: String): EffectStep = EffectStep.Fault(
        operation, TransferError.CancelledLocally,
    )

    private fun unknownStep(operation: String): EffectStep = EffectStep.Fault(
        operation, TransferError.UnexpectedInternal("operation outcome is unknown"), uncertain = true,
    )

    private fun invariant(operation: String): EffectStep = EffectStep.Fault(
        operation, TransferError.UnexpectedInternal("runtime invariant failed"), uncertain = publicationSucceeded,
    )

    private fun sourceFailure(reason: String): SingleFileResult =
        storageFailure("read_source", TransferError.SourceUnavailable(reason))

    private fun sourceFailure(error: TransferError): SingleFileResult = storageFailure("read_source", sourceError(error))

    private fun storageFailure(operation: String, error: TransferError): SingleFileResult =
        halt(operation, error, storageEvent(error), uncertain = false)

    private fun transportFailure(): SingleFileResult = halt(
        "send_chunk", TransferError.TransportDisconnected("transport send failed"), transportEvent(), uncertain = false,
    )

    private fun protocolFailure(error: TransferError): SingleFileResult = halt(
        "receive_frame", error,
        TransferEvent.ProtocolFailed(enqueue.sessionId, enqueue.transferId, enqueue.recipientId, error),
        uncertain = false,
    )

    private fun cancelled(operation: String): SingleFileResult = halt(
        operation, TransferError.CancelledLocally, null, uncertain = false,
    )

    private fun uncertain(operation: String): SingleFileResult = halt(
        operation, TransferError.UnexpectedInternal("operation outcome is unknown"), null, uncertain = true,
    )

    private fun internalFailure(operation: String): SingleFileResult = halt(
        operation, TransferError.UnexpectedInternal("runtime invariant failed"), null, uncertain = publicationSucceeded,
    )

    private fun halt(operation: String, error: TransferError, event: TransferEvent?, uncertain: Boolean): SingleFileResult.Halted {
        var accepted = false
        val snapshot = current
        if (event != null && snapshot != null) {
            val transition = TransferReducer.apply(snapshot, event)
            if (transition is TransitionResult.Accepted) {
                current = transition.current
                accepted = true
            }
        }
        pendingEmptyEof = null
        closeSource()
        return SingleFileResult.Halted(
            operation, error, event, accepted,
            uncertain || uncheckpointedPartialMayExist || checkpointAwaitingAck || verifiedPreflightSaved || publicationSucceeded,
        ).also { stopped = it }
    }

    private fun requireCurrent(): TransferSnapshot = checkNotNull(current) { "runtime has no reducer snapshot" }
}
