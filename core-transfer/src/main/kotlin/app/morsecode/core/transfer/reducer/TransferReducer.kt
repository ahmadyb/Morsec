package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.command.TransferTarget
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.SequenceNumber
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.model.VerificationInfo
import app.morsecode.core.transfer.model.VerificationOutcome
import app.morsecode.core.transfer.protocol.ResumeDecision

/*
 * The deterministic transfer reducer.
 *
 *     (snapshot, command|event) -> Accepted(newSnapshot, ordered effects)
 *                               -> Rejected(unchangedSnapshot, typed reason)
 *
 * The rules it is not allowed to break, and which are enforced here rather than
 * left to callers:
 *
 *  * It never reads a file, a socket, a clock or a random source. Identifiers
 *    arrive in the command; "when" is expressed as a snapshot version, not a
 *    timestamp.
 *  * It never mutates anything: every transition returns a new snapshot, so an
 *    unrelated delivery cannot be changed by accident and a rejected transition
 *    leaves the input untouched.
 *  * External work is only ever *described*, as a TransferEffect.
 *  * Confirmed progress is monotonic and bounded by the file size. A receiver
 *    promotes its one durable candidate only after checkpoint success; a sender
 *    advances only from the receiver's acknowledgement. Optimistic progress is
 *    discarded when the delivery pauses, disconnects or is resumed.
 */

public object TransferReducer {

    /** Capability string this build advertises in a handshake. */
    public const val CAPABILITIES: String = "v1;crc32;sha256;resume"

    /**
     * Creates the initial snapshot for one delivery.
     *
     * The only entry point that produces a snapshot from nothing, which is also
     * why the caller supplies every identifier: the reducer will not invent one.
     */
    public fun enqueue(command: TransferCommand.Enqueue): TransitionResult {
        if (command.queueOrder < 0L) {
            return TransitionResult.rejected(
                null,
                Rejection.InvalidCommand("queueOrder must not be negative, was ${command.queueOrder}"),
            )
        }
        /*
         * There is deliberately no protocol-version check here. `ProtocolVersion`
         * cannot hold a value outside PROTOCOL_VERSION_MIN..PROTOCOL_VERSION_MAX,
         * so a descriptor cannot carry one, and a check that cannot fire is not a
         * check — it is dead code that reads like protection. The refusal lives
         * where an untrusted `Int` first appears: FrameCodec and
         * TransferSnapshotCodec both answer with
         * `TransferError.ProtocolVersionMismatch`.
         */
        val snapshot = TransferSnapshot(
            transferId = command.transferId,
            sessionId = command.sessionId,
            batchId = command.batchId,
            recipientId = command.recipientId,
            direction = command.direction,
            descriptor = command.descriptor,
            state = TransferState.QUEUED,
            confirmedBytes = 0L,
            optimisticBytes = 0L,
            lastAcknowledgedSequence = null,
            retryCount = 0,
            failure = null,
            verification = null,
            remotePaused = false,
            snapshotVersion = TransferSnapshot.INITIAL_SNAPSHOT_VERSION,
            queueOrder = command.queueOrder,
        )
        return TransitionResult.accepted(null, snapshot, persist(snapshot))
    }

    /** Applies a command to a snapshot. */
    public fun apply(snapshot: TransferSnapshot, command: TransferCommand): TransitionResult {
        val guard = guard(snapshot, command)
        if (guard != null) return handleGuard(snapshot, command, guard)

        return when (command) {
            is TransferCommand.Enqueue -> TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, command.commandName),
            )

            is TransferCommand.BeginNegotiation -> onBeginNegotiation(snapshot, command)

            is TransferCommand.Accept -> onAccept(snapshot, command)

            is TransferCommand.Reject -> onReject(snapshot, command)

            is TransferCommand.StartSending -> onStartSending(snapshot, command)

            is TransferCommand.StartReceiving -> onStartReceiving(snapshot, command)

            is TransferCommand.PauseLocally -> onPauseLocally(snapshot, command)

            is TransferCommand.ResumeLocally -> onResumeLocally(snapshot, command)

            is TransferCommand.CancelLocally -> onCancelLocally(
                snapshot,
                command.commandName,
                command.reason,
                cancelledRemotely = false,
            )

            is TransferCommand.Retry -> onRetry(snapshot, command)

            is TransferCommand.Skip -> onSkip(snapshot, command)

            is TransferCommand.ApplyResumeDecision -> onApplyResumeDecision(snapshot, command)
        }
    }

    /** Applies an external fact to a snapshot. */
    public fun apply(snapshot: TransferSnapshot, event: TransferEvent): TransitionResult {
        val guard = guard(snapshot, event)
        if (guard != null) return handleGuard(snapshot, event, guard)

        return when (event) {
            is TransferEvent.PeerAccepted -> onPeerAccepted(snapshot, event)

            is TransferEvent.PeerRejected -> commit(
                snapshot,
                TransferState.FAILED_FINAL,
                event.eventName,
                transform = { it.copy(failure = TransferError.PeerRejected(event.reason)) },
            )

            is TransferEvent.RemotePaused -> onRemotePaused(snapshot, event)

            is TransferEvent.RemoteResumed -> onRemoteResumed(snapshot, event)

            is TransferEvent.ChunkSent -> onChunkSent(snapshot, event)

            is TransferEvent.ChunkDurablyStored -> onChunkDurablyStored(snapshot, event)

            is TransferEvent.ConfirmedOffsetPersisted -> onConfirmedOffsetPersisted(snapshot, event)

            is TransferEvent.ConfirmedOffsetPersistenceFailed -> onConfirmedOffsetPersistenceFailed(snapshot, event)

            is TransferEvent.ChunkAcknowledged -> onChunkAcknowledged(snapshot, event)

            is TransferEvent.ConfirmedOffsetReceived -> onConfirmedOffsetReceived(snapshot, event)

            is TransferEvent.TransportDisconnected -> onTransportDisconnected(snapshot, event)

            is TransferEvent.TransportRestored -> onTransportRestored(snapshot, event)

            is TransferEvent.AllBytesConfirmed -> onAllBytesConfirmed(snapshot, event)

            is TransferEvent.VerificationStarted -> onVerificationStarted(snapshot, event)

            is TransferEvent.VerificationSucceeded -> onVerificationSucceeded(snapshot, event)

            is TransferEvent.VerificationFailed -> onVerificationFailed(snapshot, event)

            is TransferEvent.DestinationCommitSucceeded -> onDestinationCommitSucceeded(snapshot, event)

            is TransferEvent.DestinationCommitFailed -> onDestinationCommitFailed(snapshot, event)

            is TransferEvent.FileCompleteReceived -> onFileCompleteReceived(snapshot, event)

            is TransferEvent.StorageFailed -> onFailureEvent(
                snapshot,
                event.eventName,
                event.error,
                allowFromQueued = true,
            )

            is TransferEvent.PermissionRevoked -> onFailureEvent(
                snapshot,
                event.eventName,
                TransferError.PermissionRevoked(event.reason),
                allowFromQueued = true,
            )

            is TransferEvent.ProtocolFailed -> onFailureEvent(
                snapshot,
                event.eventName,
                event.error,
                allowFromQueued = true,
            )

            is TransferEvent.RemoteCancelled -> onCancelLocally(
                snapshot,
                event.eventName,
                event.reason,
                cancelledRemotely = true,
            )

            is TransferEvent.SessionEnded -> onCancelLocally(
                snapshot,
                event.eventName,
                "the session ended",
                cancelledRemotely = false,
            )
        }
    }

    // --- commands -------------------------------------------------------------

    private fun onBeginNegotiation(
        snapshot: TransferSnapshot,
        command: TransferCommand.BeginNegotiation,
    ): TransitionResult = commit(
        snapshot,
        TransferState.NEGOTIATING,
        command.commandName,
        effects = { current ->
            val wire = if (current.direction == SessionDirection.OUTBOUND) {
                listOf(
                    TransferEffect.SendHandshake(
                        transferId = current.transferId,
                        chunkSize = current.chunkSize,
                        capabilities = CAPABILITIES,
                    ),
                )
            } else {
                emptyList()
            }
            wire + persist(current)
        },
    )

    private fun onAccept(
        snapshot: TransferSnapshot,
        command: TransferCommand.Accept,
    ): TransitionResult {
        if (snapshot.direction != SessionDirection.INBOUND) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("Accept only applies to an inbound delivery"),
            )
        }
        return commit(
            snapshot,
            TransferState.RECEIVING,
            command.commandName,
            effects = { current ->
                listOf(
                    TransferEffect.SendHandshakeAccept(
                        transferId = current.transferId,
                        chunkSize = current.chunkSize,
                        resumeFromOffset = current.confirmedBytes,
                    ),
                    TransferEffect.RequestDestinationPartial(
                        transferId = current.transferId,
                        expectedOffset = current.confirmedBytes,
                    ),
                ) + persist(current) + reportImplicitEof(current)
            },
        )
    }

    private fun onReject(
        snapshot: TransferSnapshot,
        command: TransferCommand.Reject,
    ): TransitionResult {
        if (snapshot.direction != SessionDirection.INBOUND) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("Reject only applies to an inbound delivery"),
            )
        }
        return commit(
            snapshot,
            TransferState.SKIPPED,
            command.commandName,
            effects = { current ->
                listOf(
                    TransferEffect.SendHandshakeReject(
                        transferId = current.transferId,
                        reason = command.reason,
                    ),
                ) + persist(current)
            },
        )
    }

    private fun onStartSending(
        snapshot: TransferSnapshot,
        command: TransferCommand.StartSending,
    ): TransitionResult {
        if (snapshot.direction != SessionDirection.OUTBOUND) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("StartSending only applies to an outbound delivery"),
            )
        }
        return commit(
            snapshot,
            TransferState.SENDING,
            command.commandName,
            effects = { current ->
                listOf(
                    TransferEffect.SendMetadata(
                        transferId = current.transferId,
                        descriptor = current.descriptor,
                    ),
                    TransferEffect.RequestSourceStream(
                        transferId = current.transferId,
                        fromOffset = current.confirmedBytes,
                    ),
                ) + persist(current) + reportImplicitEof(current)
            },
        )
    }

    private fun onStartReceiving(
        snapshot: TransferSnapshot,
        command: TransferCommand.StartReceiving,
    ): TransitionResult {
        if (snapshot.direction != SessionDirection.INBOUND) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("StartReceiving only applies to an inbound delivery"),
            )
        }
        return commit(
            snapshot,
            TransferState.RECEIVING,
            command.commandName,
            effects = { current ->
                listOf(
                    TransferEffect.RequestDestinationPartial(
                        transferId = current.transferId,
                        expectedOffset = current.confirmedBytes,
                    ),
                ) + persist(current) + reportImplicitEof(current)
            },
        )
    }

    private fun onPauseLocally(
        snapshot: TransferSnapshot,
        command: TransferCommand.PauseLocally,
    ): TransitionResult {
        // Pausing throws away optimistic progress: bytes on the wire that were
        // never acknowledged are not progress and must not be resumed from.
        val reset = { current: TransferSnapshot ->
            current.copy(optimisticBytes = current.confirmedBytes)
        }
        return when (snapshot.state) {
            TransferState.PAUSED_REMOTE ->
                // The peer was already paused; the local pause wins the label but
                // the peer's request is remembered so it survives a local resume.
                commit(
                    snapshot,
                    TransferState.PAUSED_LOCAL,
                    command.commandName,
                    transform = reset,
                )

            else -> commit(
                snapshot,
                TransferState.PAUSED_LOCAL,
                command.commandName,
                transform = reset,
                effects = { current ->
                    listOf(TransferEffect.SendPause(current.transferId)) + persist(current)
                },
            )
        }
    }

    private fun onResumeLocally(
        snapshot: TransferSnapshot,
        command: TransferCommand.ResumeLocally,
    ): TransitionResult = when (snapshot.state) {
        TransferState.PAUSED_LOCAL -> {
            val nextState = if (snapshot.remotePaused) {
                TransferState.PAUSED_REMOTE
            } else {
                TransferState.NEGOTIATING
            }
            commit(
                snapshot,
                nextState,
                command.commandName,
                transform = { current -> current.copy(optimisticBytes = current.confirmedBytes) },
                effects = { current ->
                    listOf(
                        TransferEffect.SendResume(
                            transferId = current.transferId,
                            fromOffset = current.confirmedBytes,
                        ),
                    ) + persist(current) + schedule(current)
                },
            )
        }

        TransferState.PAUSED_REMOTE ->
            // The peer stopped draining the socket: ask it to resume and wait for
            // its RemoteResumed event rather than pushing bytes it cannot take.
            TransitionResult.accepted(
                snapshot,
                snapshot,
                listOf(
                    TransferEffect.SendResume(snapshot.transferId, snapshot.confirmedBytes),
                    TransferEffect.NotifyUi(snapshot.transferId, snapshot.state),
                ),
            )

        else -> TransitionResult.rejected(
            snapshot,
            Rejection.IllegalStateTransition(
                snapshot.state,
                null,
                command.commandName,
            ),
        )
    }

    private fun onCancelLocally(
        snapshot: TransferSnapshot,
        name: String,
        reason: String,
        cancelledRemotely: Boolean,
    ): TransitionResult {
        // A late cancellation of a delivery that already finished is a no-op, so
        // a duplicate terminal event can never resurrect or re-cancel anything.
        if (snapshot.state.isTerminal) return noOp(snapshot)
        if (snapshot.direction == SessionDirection.INBOUND && snapshot.verification?.allowsCommit == true) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("destination commit must be reconciled before cancellation"),
            )
        }
        return commit(
        snapshot,
        TransferState.CANCELLED,
        name,
        transform = { current ->
            current.copy(
                optimisticBytes = current.confirmedBytes,
                failure = if (cancelledRemotely) {
                    TransferError.CancelledRemotely
                } else {
                    TransferError.CancelledLocally
                },
            )
        },
        effects = { current ->
            val wire = if (cancelledRemotely) {
                emptyList()
            } else {
                listOf(
                    TransferEffect.SendCancel(transferId = current.transferId, reason = reason),
                )
            }
            // A verified destination may already have been published if a
            // commit outcome was interrupted. Never delete it speculatively.
            val storage = if (current.direction == SessionDirection.INBOUND &&
                current.verification?.allowsCommit != true
            ) {
                listOf(TransferEffect.DeletePartialDestination(current.transferId))
            } else {
                emptyList()
            }
            wire + storage + persist(current) + schedule(current)
        },
        )
    }

    private fun onRetry(
        snapshot: TransferSnapshot,
        command: TransferCommand.Retry,
    ): TransitionResult {
        if (snapshot.direction == SessionDirection.INBOUND && snapshot.verification?.allowsCommit == true) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("a verified destination requires commit reconciliation before retry"),
            )
        }
        if (snapshot.retryCount >= command.retryLimit) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.RetryNotAllowed(snapshot.retryCount, command.retryLimit),
            )
        }
        return commit(
            snapshot,
            TransferState.QUEUED,
            command.commandName,
            transform = { current ->
                current.copy(
                    retryCount = current.retryCount + 1,
                    optimisticBytes = current.confirmedBytes,
                    failure = null,
                    verification = null,
                )
            },
            effects = { current -> persist(current) + schedule(current) },
        )
    }

    private fun onSkip(
        snapshot: TransferSnapshot,
        command: TransferCommand.Skip,
    ): TransitionResult {
        if (snapshot.direction == SessionDirection.INBOUND && snapshot.verification?.allowsCommit == true) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("a verified destination requires commit reconciliation before skipping"),
            )
        }
        return commit(
            snapshot,
            TransferState.SKIPPED,
            command.commandName,
            transform = { current -> current.copy(optimisticBytes = current.confirmedBytes) },
        )
    }

    private fun onApplyResumeDecision(
        snapshot: TransferSnapshot,
        command: TransferCommand.ApplyResumeDecision,
    ): TransitionResult {
        if (snapshot.state != TransferState.NEGOTIATING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(
                    snapshot.state,
                    TransferState.NEGOTIATING,
                    command.commandName,
                ),
            )
        }
        return when (val decision = command.decision) {
            is ResumeDecision.ResumeAt -> applyResumeAt(snapshot, command, decision.offset)

            is ResumeDecision.RestartAtZero -> {
                // A store with a monotonic checkpoint cannot safely reset an
                // existing delivery, and deleting first would destroy bytes
                // already acknowledged. Starting over needs a new delivery or
                // explicit reconciliation in a later slice.
                if (snapshot.confirmedBytes != 0L || snapshot.verification?.allowsCommit == true) {
                    return TransitionResult.rejected(
                        snapshot,
                        Rejection.InvalidResumeProposal("restart requires a new delivery or reconciliation"),
                    )
                }
                mutate(
                    snapshot,
                    command.commandName,
                    allowedStates = NEGOTIATING_STATES,
                    transform = { current ->
                        current.copy(
                            optimisticBytes = 0L,
                            lastAcknowledgedSequence = null,
                            verification = null,
                        )
                    },
                    effects = { current ->
                        val destination = if (current.direction == SessionDirection.INBOUND) {
                            listOf(TransferEffect.DeletePartialDestination(current.transferId))
                        } else {
                            emptyList()
                        }
                        destination + listOf(
                            TransferEffect.PersistConfirmedOffset(current.transferId, 0L),
                        ) + openStreams(current) + persist(current)
                    },
                )
            }

            // A resume response is not the receiver's FILE_COMPLETE. Do not
            // turn a verified claim into sender completion; a later resume slice
            // must define reconciliation of a previously committed destination.
            ResumeDecision.AlreadyVerified -> TransitionResult.rejected(
                snapshot,
                Rejection.InvalidResumeProposal("already verified requires completion reconciliation"),
            )

            is ResumeDecision.Reject -> commit(
                snapshot,
                TransferState.FAILED_FINAL,
                command.commandName,
                transform = { current -> current.copy(failure = decision.error) },
            )
        }
    }

    private fun applyResumeAt(
        snapshot: TransferSnapshot,
        command: TransferCommand.ApplyResumeDecision,
        offset: Long,
    ): TransitionResult {
        if (offset < 0L || offset > snapshot.totalBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.OffsetBeyondTotal(offset, snapshot.totalBytes),
            )
        }
        when {
            offset > snapshot.confirmedBytes && snapshot.direction == SessionDirection.INBOUND ->
                return TransitionResult.rejected(
                    snapshot,
                    Rejection.InvalidResumeProposal(
                        "an inbound delivery cannot adopt offset $offset beyond its own " +
                            "confirmed ${snapshot.confirmedBytes} bytes",
                    ),
                )

            offset < snapshot.confirmedBytes ->
                return TransitionResult.rejected(
                    snapshot,
                    Rejection.OffsetRegression(snapshot.confirmedBytes, offset),
                )
        }
        return mutate(
            snapshot,
            command.commandName,
            allowedStates = NEGOTIATING_STATES,
            transform = { current ->
                current.copy(
                    confirmedBytes = maxOf(current.confirmedBytes, offset),
                    optimisticBytes = maxOf(current.confirmedBytes, offset),
                    // A recovered offset may represent a checkpoint whose ACK
                    // was lost. It does not reveal the preceding chunk's start,
                    // so never invent an acknowledged sequence for it.
                    lastAcknowledgedSequence = if (offset == current.confirmedBytes) {
                        current.lastAcknowledgedSequence
                    } else {
                        null
                    },
                )
            },
            effects = { current ->
                // A sender may learn a receiver checkpoint beyond its stored
                // optimistic frontier after a lost ACK. Persist the complete
                // revision first; saveCheckpoint alone would reject that offset.
                listOf(
                    TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion),
                    TransferEffect.SendResume(current.transferId, current.confirmedBytes),
                ) + openStreams(current) + listOf(TransferEffect.NotifyUi(current.transferId, current.state))
            },
        )
    }

    // --- events ---------------------------------------------------------------

    private fun onPeerAccepted(
        snapshot: TransferSnapshot,
        event: TransferEvent.PeerAccepted,
    ): TransitionResult {
        if (snapshot.direction != SessionDirection.OUTBOUND) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("PeerAccepted only applies to an outbound delivery"),
            )
        }
        if (event.chunkSize != snapshot.chunkSize) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidResumeProposal(
                    "the peer accepted with a chunk size of ${event.chunkSize.value} but the " +
                        "descriptor says ${snapshot.chunkSize.value}; resume would misalign",
                ),
            )
        }
        return commit(
            snapshot,
            TransferState.SENDING,
            event.eventName,
            effects = { current ->
                listOf(
                    TransferEffect.SendMetadata(current.transferId, current.descriptor),
                    TransferEffect.RequestSourceStream(current.transferId, current.confirmedBytes),
                ) + persist(current) +
                    if (current.allBytesConfirmed) reportImplicitEof(current) else schedule(current)
            },
        )
    }

    private fun onRemotePaused(
        snapshot: TransferSnapshot,
        event: TransferEvent.RemotePaused,
    ): TransitionResult {
        val reset = { current: TransferSnapshot ->
            current.copy(
                optimisticBytes = current.confirmedBytes,
                remotePaused = true,
            )
        }
        return when (snapshot.state) {
            TransferState.PAUSED_LOCAL -> commit(
                snapshot,
                TransferState.PAUSED_LOCAL,
                event.eventName,
                transform = reset,
            )

            TransferState.QUEUED, TransferState.VERIFYING -> TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(
                    snapshot.state,
                    TransferState.PAUSED_REMOTE,
                    event.eventName,
                ),
            )

            else -> commit(
                snapshot,
                TransferState.PAUSED_REMOTE,
                event.eventName,
                transform = reset,
            )
        }
    }

    private fun onRemoteResumed(
        snapshot: TransferSnapshot,
        event: TransferEvent.RemoteResumed,
    ): TransitionResult = when (snapshot.state) {
        TransferState.PAUSED_REMOTE -> commit(
            snapshot,
            TransferState.NEGOTIATING,
            event.eventName,
            transform = { current -> current.copy(remotePaused = false) },
            effects = { current -> persist(current) + schedule(current) },
        )

        TransferState.PAUSED_LOCAL -> commit(
            snapshot,
            TransferState.PAUSED_LOCAL,
            event.eventName,
            transform = { current -> current.copy(remotePaused = false) },
        )

        else -> TransitionResult.rejected(
            snapshot,
            Rejection.IllegalStateTransition(
                snapshot.state,
                TransferState.NEGOTIATING,
                event.eventName,
            ),
        )
    }

    private fun onChunkSent(
        snapshot: TransferSnapshot,
        event: TransferEvent.ChunkSent,
    ): TransitionResult {
        if (snapshot.state != TransferState.SENDING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, event.eventName),
            )
        }
        if (snapshot.direction != SessionDirection.OUTBOUND) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("chunk sending requires outbound direction"))
        }
        // A chunk may not start past the write frontier: bytes 4096..8192 cannot
        // be written before bytes 0..4096 are, so a gap would make optimisticBytes
        // describe bytes that were never sent.
        val range = checkChunkRange(
            snapshot, event.offset, event.length, event.sequence,
            frontier = snapshot.optimisticBytes,
        )
        if (range != null) return range

        val end = event.offset + event.length
        if (end <= snapshot.optimisticBytes) {
            // A resend of bytes already on the wire: idempotent, no new progress.
            return noOp(snapshot)
        }
        return mutate(
            snapshot,
            event.eventName,
            allowedStates = SENDING_STATES,
            transform = { current -> current.copy(optimisticBytes = end) },
        )
    }

    private fun onChunkDurablyStored(
        snapshot: TransferSnapshot,
        event: TransferEvent.ChunkDurablyStored,
    ): TransitionResult {
        if (snapshot.state != TransferState.RECEIVING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, event.eventName),
            )
        }
        if (snapshot.direction != SessionDirection.INBOUND) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("durable chunks require inbound direction"))
        }
        val range = checkChunkRange(
            snapshot, event.offset, event.length, event.sequence,
            frontier = snapshot.confirmedBytes,
        )
        if (range != null) return range
        val end = event.offset + event.length
        if (snapshot.optimisticBytes != snapshot.confirmedBytes) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("a checkpoint is already pending"))
        }
        if (event.offset < snapshot.confirmedBytes && end > snapshot.confirmedBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.OverlappingChunk(event.offset, event.length, snapshot.confirmedBytes),
            )
        }
        if (event.offset != snapshot.confirmedBytes || snapshot.allBytesConfirmed) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.UnexpectedOffset(snapshot.confirmedBytes, event.offset),
            )
        }
        // The event certifies physical durability; it does not certify the
        // checkpoint. Persist the optimistic candidate first so an existing
        // versioned store can then atomically checkpoint it. No ACK is emitted.
        return mutate(
            snapshot,
            event.eventName,
            allowedStates = RECEIVING_STATES,
            transform = { current -> current.copy(optimisticBytes = end) },
            effects = { current ->
                listOf(
                    TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion),
                    TransferEffect.PersistConfirmedOffset(current.transferId, end),
                )
            },
        )
    }

    private fun onConfirmedOffsetPersisted(
        snapshot: TransferSnapshot,
        event: TransferEvent.ConfirmedOffsetPersisted,
    ): TransitionResult {
        val invalid = validatePendingCheckpoint(snapshot, event.candidateOffset, event.eventName)
        if (invalid != null) return invalid
        val offset = snapshot.confirmedBytes
        val length = (event.candidateOffset - offset).toInt()
        val sequence = SequenceNumber.forOffset(offset, snapshot.chunkSize).value
        return mutate(
            snapshot,
            event.eventName,
            allowedStates = RECEIVING_STATES,
            transform = { current ->
                current.copy(confirmedBytes = event.candidateOffset, lastAcknowledgedSequence = sequence)
            },
            effects = { current ->
                listOf(
                    TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion),
                    TransferEffect.SendAcknowledgement(current.transferId, offset, length, current.confirmedBytes),
                    TransferEffect.NotifyUi(current.transferId, current.state),
                ) + reportImplicitEof(current)
            },
        )
    }

    private fun onConfirmedOffsetPersistenceFailed(
        snapshot: TransferSnapshot,
        event: TransferEvent.ConfirmedOffsetPersistenceFailed,
    ): TransitionResult {
        val invalid = validatePendingCheckpoint(snapshot, event.candidateOffset, event.eventName)
        if (invalid != null) return invalid
        val error = TransferError.PersistenceFailure("checkpointed", event.storageFull)
        val next = if (error.retryable) TransferState.FAILED_RETRYABLE else TransferState.FAILED_FINAL
        return commit(
            snapshot,
            next,
            event.eventName,
            transform = { current ->
                current.copy(optimisticBytes = current.confirmedBytes, failure = error)
            },
            effects = { current ->
                // The staging save may itself have failed. Retrying this exact
                // revision is idempotent, and never publishes an ACK.
                listOf(
                    TransferEffect.PersistSnapshot(snapshot.transferId, snapshot.snapshotVersion),
                    TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion),
                    TransferEffect.NotifyUi(current.transferId, current.state),
                ) + schedule(current)
            },
        )
    }

    private fun validatePendingCheckpoint(
        snapshot: TransferSnapshot,
        candidate: Long,
        name: String,
    ): TransitionResult? {
        if (snapshot.state != TransferState.RECEIVING) {
            return TransitionResult.rejected(snapshot, Rejection.IllegalStateTransition(snapshot.state, null, name))
        }
        if (snapshot.direction != SessionDirection.INBOUND) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("checkpoint requires inbound direction"))
        }
        if (candidate > snapshot.totalBytes) {
            return TransitionResult.rejected(snapshot, Rejection.OffsetBeyondTotal(candidate, snapshot.totalBytes))
        }
        if (candidate <= snapshot.confirmedBytes) {
            return TransitionResult.rejected(snapshot, Rejection.OffsetRegression(snapshot.confirmedBytes, candidate))
        }
        if (candidate != snapshot.optimisticBytes ||
            candidate - snapshot.confirmedBytes > snapshot.chunkSize.value.toLong()
        ) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("candidate does not match the durable pending frontier"),
            )
        }
        return null
    }

    private fun onChunkAcknowledged(
        snapshot: TransferSnapshot,
        event: TransferEvent.ChunkAcknowledged,
    ): TransitionResult {
        if (snapshot.state != TransferState.SENDING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, event.eventName),
            )
        }
        if (snapshot.direction != SessionDirection.OUTBOUND) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("peer acknowledgement requires outbound direction"))
        }
        val range = checkChunkRange(
            snapshot, event.offset, event.length, event.sequence,
            frontier = snapshot.optimisticBytes,
        )
        if (range != null) return range
        // The peer cannot have received bytes this side never put on the wire.
        if (event.confirmedOffset > snapshot.optimisticBytes || event.offset + event.length > snapshot.optimisticBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.AcknowledgementBeyondSent(
                    snapshot.optimisticBytes,
                    maxOf(event.confirmedOffset, event.offset + event.length),
                ),
            )
        }
        if (event.confirmedOffset < snapshot.confirmedBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.OffsetRegression(snapshot.confirmedBytes, event.confirmedOffset),
            )
        }
        if (event.confirmedOffset != event.offset + event.length) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("acknowledgement range does not match frontier"))
        }
        if (event.confirmedOffset == snapshot.confirmedBytes) return noOp(snapshot)
        if (event.offset != snapshot.confirmedBytes) {
            return TransitionResult.rejected(snapshot, Rejection.UnexpectedOffset(snapshot.confirmedBytes, event.offset))
        }
        return applyConfirmedProgress(
            snapshot,
            event.eventName,
            event.confirmedOffset,
            event.sequence,
        )
    }

    private fun onConfirmedOffsetReceived(
        snapshot: TransferSnapshot,
        event: TransferEvent.ConfirmedOffsetReceived,
    ): TransitionResult {
        if (snapshot.state != TransferState.SENDING || snapshot.direction != SessionDirection.OUTBOUND) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, event.eventName),
            )
        }
        if (event.confirmedOffset > snapshot.totalBytes) {
            return TransitionResult.rejected(snapshot, Rejection.OffsetBeyondTotal(event.confirmedOffset, snapshot.totalBytes))
        }
        if (event.confirmedOffset > snapshot.confirmedBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("a checkpoint report cannot replace a chunk acknowledgement"),
            )
        }
        return applyConfirmedProgress(snapshot, event.eventName, event.confirmedOffset, sequence = null)
    }

    private fun applyConfirmedProgress(
        snapshot: TransferSnapshot,
        name: String,
        confirmedOffset: Long,
        sequence: Long?,
    ): TransitionResult {
        if (confirmedOffset > snapshot.totalBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.OffsetBeyondTotal(confirmedOffset, snapshot.totalBytes),
            )
        }
        if (confirmedOffset < snapshot.confirmedBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.OffsetRegression(snapshot.confirmedBytes, confirmedOffset),
            )
        }
        if (confirmedOffset == snapshot.confirmedBytes && sequence == null) {
            return noOp(snapshot)
        }
        return mutate(
            snapshot,
            name,
            allowedStates = STREAMING_STATES,
            transform = { current ->
                current.copy(
                    confirmedBytes = confirmedOffset,
                    optimisticBytes = maxOf(current.optimisticBytes, confirmedOffset),
                    lastAcknowledgedSequence = sequence ?: current.lastAcknowledgedSequence,
                )
            },
            effects = { current ->
                listOf(
                    TransferEffect.PersistConfirmedOffset(
                        transferId = current.transferId,
                        confirmedOffset = current.confirmedBytes,
                    ),
                ) + persist(current) + reportImplicitEof(current)
            },
        )
    }

    private fun onTransportDisconnected(
        snapshot: TransferSnapshot,
        event: TransferEvent.TransportDisconnected,
    ): TransitionResult = when (snapshot.state) {
        TransferState.QUEUED ->
            // Nothing was in flight: a queued item simply waits for the transport.
            noOp(snapshot)

        TransferState.PAUSED_LOCAL, TransferState.PAUSED_REMOTE -> noOp(snapshot)

        else -> commit(
            snapshot,
            TransferState.FAILED_RETRYABLE,
            event.eventName,
            transform = { current ->
                current.copy(
                    optimisticBytes = current.confirmedBytes,
                    failure = TransferError.TransportDisconnected(event.reason),
                )
            },
            effects = { current -> persist(current) + schedule(current) },
        )
    }

    private fun onTransportRestored(
        snapshot: TransferSnapshot,
        event: TransferEvent.TransportRestored,
    ): TransitionResult {
        val effects = mutableListOf<TransferEffect>(
            TransferEffect.NotifyUi(snapshot.transferId, snapshot.state),
        )
        if (snapshot.state == TransferState.FAILED_RETRYABLE) {
            effects += TransferEffect.ScheduleNextEligibleItem(snapshot.sessionId)
        }
        return noOp(snapshot, effects)
    }

    private fun onAllBytesConfirmed(
        snapshot: TransferSnapshot,
        event: TransferEvent.AllBytesConfirmed,
    ): TransitionResult {
        if (!snapshot.allBytesConfirmed) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.VerificationTooEarly(snapshot.confirmedBytes, snapshot.totalBytes),
            )
        }
        if (snapshot.state == TransferState.VERIFYING) return noOp(snapshot)
        if ((snapshot.direction == SessionDirection.INBOUND && snapshot.state != TransferState.RECEIVING) ||
            (snapshot.direction == SessionDirection.OUTBOUND && snapshot.state != TransferState.SENDING)
        ) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, TransferState.VERIFYING, event.eventName),
            )
        }
        return beginVerification(snapshot, event.eventName)
    }

    private fun onVerificationStarted(
        snapshot: TransferSnapshot,
        event: TransferEvent.VerificationStarted,
    ): TransitionResult {
        if (snapshot.direction != SessionDirection.INBOUND) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("local verification requires inbound direction"))
        }
        if (!snapshot.allBytesConfirmed) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.VerificationTooEarly(snapshot.confirmedBytes, snapshot.totalBytes),
            )
        }
        if (event.expectedDigest != null && event.expectedDigest != snapshot.descriptor.expectedSha256) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("verification expectation disagrees with descriptor"))
        }
        if (snapshot.state == TransferState.VERIFYING) return noOp(snapshot)
        if (snapshot.state != TransferState.RECEIVING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, TransferState.VERIFYING, event.eventName),
            )
        }
        return beginVerification(snapshot, event.eventName)
    }

    private fun onVerificationSucceeded(
        snapshot: TransferSnapshot,
        event: TransferEvent.VerificationSucceeded,
    ): TransitionResult {
        if (snapshot.state != TransferState.VERIFYING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, TransferState.VERIFYING, event.eventName),
            )
        }
        if (!snapshot.allBytesConfirmed) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.VerificationTooEarly(snapshot.confirmedBytes, snapshot.totalBytes),
            )
        }
        if (snapshot.verification == null) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("verification was not started"))
        }
        if (snapshot.verification.allowsCommit) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("verification was already recorded"))
        }
        if (snapshot.direction == SessionDirection.OUTBOUND && event.reportedTotalBytes != snapshot.totalBytes) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("peer verification length does not match the delivery"))
        }
        val expected = snapshot.descriptor.expectedSha256
        if (snapshot.verification?.expectedDigest != expected) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("verification expectation disagrees with descriptor"))
        }
        val verification = VerificationInfo(
            expectedDigest = expected,
            observedDigest = event.observedDigest,
            startedSnapshotVersion = snapshot.verification?.startedSnapshotVersion ?: snapshot.snapshotVersion,
        )
        if (!verification.allowsCommit) {
            return TransitionResult.rejected(snapshot, Rejection.VerificationConflict(verification.outcome))
        }
        return mutate(
            snapshot,
            event.eventName,
            allowedStates = VERIFYING_STATES,
            transform = { current ->
                current.copy(optimisticBytes = current.confirmedBytes, verification = verification, failure = null)
            },
            effects = { current ->
                if (current.direction == SessionDirection.INBOUND) {
                    listOf(TransferEffect.CommitVerifiedDestination(current.transferId, current.snapshotVersion))
                } else {
                    // This is the peer's result, not a local destination commit.
                    persist(current)
                }
            },
        )
    }

    private fun onDestinationCommitSucceeded(
        snapshot: TransferSnapshot,
        event: TransferEvent.DestinationCommitSucceeded,
    ): TransitionResult {
        val invalid = validateReceiverCommit(snapshot, event.eventName)
        if (invalid != null) return invalid
        return commit(
            snapshot,
            TransferState.COMPLETED,
            event.eventName,
            transform = { current -> current.copy(failure = null) },
            effects = { current ->
                listOf(
                    // VerificationSucceeded was deliberately provisional and
                    // emitted only CommitVerifiedDestination. Persist that
                    // revision before the completed revision in a CAS store.
                    TransferEffect.PersistSnapshot(snapshot.transferId, snapshot.snapshotVersion),
                    TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion),
                    TransferEffect.SendVerificationResult(
                        current.transferId, true, current.totalBytes, current.verification?.observedDigest,
                    ),
                    TransferEffect.SendFileComplete(
                        current.transferId, current.fileId, current.totalBytes, current.verification?.observedDigest,
                    ),
                    TransferEffect.NotifyUi(current.transferId, current.state),
                ) + schedule(current)
            },
        )
    }

    private fun onDestinationCommitFailed(
        snapshot: TransferSnapshot,
        event: TransferEvent.DestinationCommitFailed,
    ): TransitionResult {
        val invalid = validateReceiverCommit(snapshot, event.eventName)
        if (invalid != null) return invalid
        // Preserve the useful failure category without ever retaining a
        // provider's exception text, path, URI or other adapter-owned string.
        val error = when (val reported = event.error) {
            is TransferError.DestinationUnavailable ->
                TransferError.DestinationUnavailable("verified destination commit failed")
            is TransferError.StorageFull -> TransferError.StorageFull(snapshot.totalBytes)
            is TransferError.PermissionRevoked -> TransferError.PermissionRevoked("destination permission revoked")
            is TransferError.PersistenceFailure -> TransferError.PersistenceFailure("committed", reported.storageFull)
            else -> return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidCommand("destination commit requires a local storage failure"),
            )
        }
        val next = if (error.retryable) TransferState.FAILED_RETRYABLE else TransferState.FAILED_FINAL
        return commit(
            snapshot,
            next,
            event.eventName,
            transform = { current -> current.copy(failure = error) },
            effects = { current ->
                listOf(
                    TransferEffect.PersistSnapshot(snapshot.transferId, snapshot.snapshotVersion),
                    TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion),
                    TransferEffect.SendVerificationResult(current.transferId, false, current.totalBytes, null),
                    TransferEffect.NotifyUi(current.transferId, current.state),
                ) + schedule(current)
            },
        )
    }

    private fun validateReceiverCommit(snapshot: TransferSnapshot, name: String): TransitionResult? {
        if (snapshot.direction != SessionDirection.INBOUND) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("destination commit requires inbound direction"))
        }
        if (snapshot.state != TransferState.VERIFYING) {
            return TransitionResult.rejected(snapshot, Rejection.IllegalStateTransition(snapshot.state, null, name))
        }
        if (!snapshot.allBytesConfirmed || snapshot.verification?.allowsCommit != true) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("destination commit requires successful verification"))
        }
        return null
    }

    private fun onFileCompleteReceived(
        snapshot: TransferSnapshot,
        event: TransferEvent.FileCompleteReceived,
    ): TransitionResult {
        if (snapshot.direction != SessionDirection.OUTBOUND) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("FILE_COMPLETE is received only by a sender"))
        }
        if (snapshot.state != TransferState.VERIFYING || !snapshot.allBytesConfirmed ||
            snapshot.verification?.allowsCommit != true
        ) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("FILE_COMPLETE requires peer verification"))
        }
        if (event.fileId != snapshot.fileId || event.totalBytes != snapshot.totalBytes ||
            event.digest != snapshot.verification.observedDigest
        ) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("FILE_COMPLETE does not match the delivery"))
        }
        return commit(
            snapshot,
            TransferState.COMPLETED,
            event.eventName,
            effects = { current -> persist(current) + schedule(current) },
        )
    }

    private fun onVerificationFailed(
        snapshot: TransferSnapshot,
        event: TransferEvent.VerificationFailed,
    ): TransitionResult {
        if (snapshot.state != TransferState.VERIFYING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(
                    snapshot.state,
                    TransferState.FAILED_RETRYABLE,
                    event.eventName,
                ),
            )
        }
        if (snapshot.verification?.allowsCommit == true) {
            return TransitionResult.rejected(snapshot, Rejection.InvalidCommand("verification was already recorded"))
        }
        val error = if (event.expectedDigest != null && event.observedDigest != null) {
            TransferError.FileChecksumMismatch(event.expectedDigest.hex, event.observedDigest.hex)
        } else {
            TransferError.DescriptorMismatch("verification produced no comparable digest")
        }
        return commit(
            snapshot,
            TransferState.FAILED_RETRYABLE,
            event.eventName,
            transform = { current ->
                current.copy(
                    optimisticBytes = current.confirmedBytes,
                    verification = VerificationInfo(
                        expectedDigest = event.expectedDigest,
                        observedDigest = event.observedDigest,
                        startedSnapshotVersion = current.verification?.startedSnapshotVersion
                            ?: current.snapshotVersion,
                    ),
                    failure = error,
                )
            },
            effects = { current ->
                val result = if (current.direction == SessionDirection.INBOUND) {
                    listOf(TransferEffect.SendVerificationResult(current.transferId, false, current.totalBytes, null))
                } else {
                    emptyList()
                }
                listOf(TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)) +
                    result + listOf(TransferEffect.NotifyUi(current.transferId, current.state)) + schedule(current)
            },
        )
    }

    private fun onFailureEvent(
        snapshot: TransferSnapshot,
        name: String,
        error: TransferError,
        allowFromQueued: Boolean,
    ): TransitionResult {
        if (snapshot.state == TransferState.QUEUED) {
            // A queued item owns no slot and no partial file. A final error ends
            // it; a retryable one simply leaves it queued for the scheduler.
            return if (error.isFinal && allowFromQueued) {
                commit(
                    snapshot,
                    TransferState.FAILED_FINAL,
                    name,
                    transform = { current -> current.copy(failure = error) },
                )
            } else {
                noOp(snapshot)
            }
        }
        val next = if (error.retryable) TransferState.FAILED_RETRYABLE else TransferState.FAILED_FINAL
        return commit(
            snapshot,
            next,
            name,
            transform = { current ->
                current.copy(
                    optimisticBytes = current.confirmedBytes,
                    failure = error,
                )
            },
            effects = { current ->
                // The preceding effect may have failed before its snapshot was
                // saved. Re-saving the prior revision is idempotent when it was
                // already stored and preserves strict CAS revision ordering.
                listOf(TransferEffect.PersistSnapshot(snapshot.transferId, snapshot.snapshotVersion)) +
                    persist(current) + schedule(current)
            },
        )
    }

    private fun beginVerification(snapshot: TransferSnapshot, name: String): TransitionResult = commit(
        snapshot,
        TransferState.VERIFYING,
        name,
        transform = { current ->
            current.copy(
                verification = VerificationInfo.started(
                    current.descriptor.expectedSha256,
                    current.snapshotVersion,
                ),
            )
        },
        effects = { current ->
            val verify = if (current.direction == SessionDirection.INBOUND) {
                listOf(
                    TransferEffect.BeginVerification(
                        transferId = current.transferId,
                        expectedDigest = current.verification?.expectedDigest,
                    ),
                )
            } else {
                // A sender waits for the receiver's verification result.
                emptyList()
            }
            listOf(TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion)) +
                verify + listOf(TransferEffect.NotifyUi(current.transferId, current.state))
        },
    )

    // --- shared machinery -----------------------------------------------------

    /**
     * Identity and terminal-state guard.
     *
     * Returns the rejection that applies, or null when the input may proceed.
     */
    private fun guard(snapshot: TransferSnapshot, target: TransferTarget): Rejection? {
        val name = when (target) {
            is TransferCommand -> target.commandName
            is TransferEvent -> target.eventName
            else -> "input"
        }
        if (snapshot.sessionId != target.sessionId) {
            return Rejection.WrongSession(snapshot.sessionId.value, target.sessionId.value)
        }
        if (snapshot.transferId != target.transferId) {
            return Rejection.WrongTransfer(snapshot.transferId.value, target.transferId.value)
        }
        if (snapshot.recipientId != target.recipientId) {
            return Rejection.WrongRecipient(snapshot.recipientId?.value, target.recipientId?.value)
        }
        if (snapshot.state.isTerminal && !isTerminalIdiom(target)) {
            return Rejection.TerminalTransfer(snapshot.state, name)
        }
        return null
    }

    /**
     * The two inputs a finished delivery still accepts, both as no-ops.
     *
     * A late `SessionEnded` or `RemoteCancelled` is exactly the kind of
     * duplicate a reconnect produces; refusing it would be correct but noisy, so
     * it falls through to `onCancelLocally`, which no-ops anything terminal.
     * Everything else is refused outright: a finished delivery is finished.
     */
    private fun isTerminalIdiom(target: TransferTarget): Boolean =
        target is TransferEvent.SessionEnded || target is TransferEvent.RemoteCancelled

    private fun handleGuard(
        snapshot: TransferSnapshot,
        target: TransferTarget,
        rejection: Rejection,
    ): TransitionResult {
        @Suppress("UNUSED_PARAMETER") val ignored = target
        return TransitionResult.rejected(snapshot, rejection)
    }

    /**
     * Validates a chunk's length, sequence, alignment and bounds.
     *
     * Returns a rejection when the chunk cannot possibly be appended, or null
     * when the caller should go on to decide whether it is new, duplicate,
     * overlapping or out-of-order.
     */
    /**
     * Validates a chunk's range and sequence.
     *
     * [frontier] is the byte position this chunk is allowed to start at or before:
     * the write frontier for the sending side (`optimisticBytes`) and the
     * confirmed watermark for the receiving side. A chunk that starts past it
     * describes bytes that were skipped, which would make the progress numbers
     * claim work that never happened.
     */
    private fun checkChunkRange(
        snapshot: TransferSnapshot,
        offset: Long,
        length: Int,
        sequence: Long,
        frontier: Long,
    ): TransitionResult? {
        if (length <= 0 || length > snapshot.chunkSize.value) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.InvalidChunkLength(length, snapshot.chunkSize.value),
            )
        }
        if (offset < 0L) {
            return TransitionResult.rejected(snapshot, Rejection.UnexpectedOffset(0L, offset))
        }
        // A range that cannot be addressed at all is refused before it is
        // compared with the file size, so an untrusted offset plus an untrusted
        // length can never wrap into a small positive number.
        if (!ProtocolLimits.isValidRange(offset, length.toLong())) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.OffsetBeyondTotal(offset, snapshot.totalBytes),
            )
        }
        val endExclusive = ProtocolLimits.checkedEnd(offset, length.toLong())
        if (endExclusive > snapshot.totalBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.OffsetBeyondTotal(endExclusive, snapshot.totalBytes),
            )
        }
        val expectedSequence = SequenceNumber.forOffset(offset, snapshot.chunkSize).value
        if (sequence != expectedSequence) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.UnexpectedSequence(expectedSequence, sequence),
            )
        }
        if (offset > frontier) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.UnexpectedOffset(frontier, offset),
            )
        }
        return null
    }

    private fun commit(
        snapshot: TransferSnapshot,
        next: TransferState,
        name: String,
        transform: (TransferSnapshot) -> TransferSnapshot = { it },
        effects: (TransferSnapshot) -> List<TransferEffect> = { persist(it) },
    ): TransitionResult {
        if (!TransferTransitionTable.canMove(snapshot.state, next)) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, next, name),
            )
        }
        val staged = snapshot.copy(state = next)
        val current = transform(staged).copy(snapshotVersion = snapshot.snapshotVersion + 1)
        return TransitionResult.accepted(snapshot, current, effects(current))
    }

    /**
     * Applies progress within the current state.
     *
     * Chunk events and resume decisions move bytes, not states, so they must not
     * be checked against the transition table — but they are still confined to
     * the states in which they mean anything.
     */
    private fun mutate(
        snapshot: TransferSnapshot,
        name: String,
        allowedStates: Set<TransferState>,
        transform: (TransferSnapshot) -> TransferSnapshot,
        effects: (TransferSnapshot) -> List<TransferEffect> = { persist(it) },
    ): TransitionResult {
        if (snapshot.state !in allowedStates) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, name),
            )
        }
        val current = transform(snapshot).copy(snapshotVersion = snapshot.snapshotVersion + 1)
        return TransitionResult.accepted(snapshot, current, effects(current))
    }

    private fun noOp(
        snapshot: TransferSnapshot,
        effects: List<TransferEffect> = emptyList(),
    ): TransitionResult = TransitionResult.accepted(snapshot, snapshot, effects)

    private fun persist(current: TransferSnapshot): List<TransferEffect> = listOf(
        TransferEffect.PersistSnapshot(current.transferId, current.snapshotVersion),
        TransferEffect.NotifyUi(current.transferId, current.state),
    )

    private fun schedule(current: TransferSnapshot): List<TransferEffect> = listOf(
        TransferEffect.ScheduleNextEligibleItem(current.sessionId),
    )

    /** Local implicit EOF, after earlier persistence and wire effects; never a frame. */
    private fun reportImplicitEof(current: TransferSnapshot): List<TransferEffect> =
        if (current.allBytesConfirmed) {
            listOf(TransferEffect.DispatchAllBytesConfirmed(current.transferId, current.snapshotVersion))
        } else {
            emptyList()
        }

    /** The stream-open effect appropriate for this delivery's direction. */
    private fun openStreams(current: TransferSnapshot): List<TransferEffect> =
        if (current.direction == SessionDirection.OUTBOUND) {
            listOf(
                TransferEffect.RequestSourceStream(current.transferId, current.confirmedBytes),
            )
        } else {
            listOf(
                TransferEffect.RequestDestinationPartial(current.transferId, current.confirmedBytes),
            )
        }

    private val SENDING_STATES: Set<TransferState> = setOf(TransferState.SENDING)
    private val RECEIVING_STATES: Set<TransferState> = setOf(TransferState.RECEIVING)
    private val VERIFYING_STATES: Set<TransferState> = setOf(TransferState.VERIFYING)
    private val STREAMING_STATES: Set<TransferState> =
        setOf(TransferState.SENDING, TransferState.RECEIVING)
    private val NEGOTIATING_STATES: Set<TransferState> = setOf(TransferState.NEGOTIATING)

    /** Verification outcomes that permit a commit. */
    public val COMMITTABLE_OUTCOMES: Set<VerificationOutcome> = setOf(
        VerificationOutcome.MATCHED,
        VerificationOutcome.VERIFIED_WITHOUT_EXPECTED,
    )
}
