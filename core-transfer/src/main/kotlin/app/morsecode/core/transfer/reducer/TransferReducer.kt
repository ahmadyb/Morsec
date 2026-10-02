package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.command.TransferTarget
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.ProtocolVersion
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
 *  * Confirmed progress is monotonic and bounded by the file size. Optimistic
 *    progress may run ahead but is never promoted to confirmed without an
 *    acknowledgement, and is discarded whenever the delivery pauses, disconnects
 *    or is resumed.
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
        if (!ProtocolVersion.isValid(command.descriptor.protocolVersion.value)) {
            return TransitionResult.rejected(
                null,
                Rejection.UnsupportedProtocol(
                    command.descriptor.protocolVersion.value,
                    ProtocolLimits.PROTOCOL_VERSION_MIN,
                    ProtocolLimits.PROTOCOL_VERSION_MAX,
                ),
            )
        }
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

            is TransferEvent.ChunkReceived -> onChunkReceived(snapshot, event)

            is TransferEvent.ChunkAcknowledged -> onChunkAcknowledged(snapshot, event)

            is TransferEvent.ConfirmedOffsetReceived -> onConfirmedOffsetReceived(snapshot, event)

            is TransferEvent.TransportDisconnected -> onTransportDisconnected(snapshot, event)

            is TransferEvent.TransportRestored -> onTransportRestored(snapshot, event)

            is TransferEvent.AllBytesConfirmed -> onAllBytesConfirmed(snapshot, event)

            is TransferEvent.VerificationStarted -> onVerificationStarted(snapshot, event)

            is TransferEvent.VerificationSucceeded -> onVerificationSucceeded(snapshot, event)

            is TransferEvent.VerificationFailed -> onVerificationFailed(snapshot, event)

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
                ) + persist(current)
            },
        )
    }

    private fun onReject(
        snapshot: TransferSnapshot,
        command: TransferCommand.Reject,
    ): TransitionResult = commit(
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
                ) + persist(current)
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
                ) + persist(current)
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
            val storage = if (current.direction == SessionDirection.INBOUND) {
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
    ): TransitionResult = commit(
        snapshot,
        TransferState.SKIPPED,
        command.commandName,
        transform = { current -> current.copy(optimisticBytes = current.confirmedBytes) },
    )

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

            is ResumeDecision.RestartAtZero -> mutate(
                snapshot,
                command.commandName,
                allowedStates = NEGOTIATING_STATES,
                transform = { current ->
                    current.copy(
                        confirmedBytes = 0L,
                        optimisticBytes = 0L,
                        lastAcknowledgedSequence = null,
                        verification = null,
                    )
                },
                effects = { current ->
                    listOf(
                        TransferEffect.DeletePartialDestination(current.transferId),
                        TransferEffect.PersistConfirmedOffset(current.transferId, 0L),
                    ) + openStreams(current) + persist(current)
                },
            )

            ResumeDecision.AlreadyVerified -> {
                if (snapshot.direction != SessionDirection.OUTBOUND) {
                    return TransitionResult.rejected(
                        snapshot,
                        Rejection.InvalidResumeProposal(
                            "AlreadyVerified is a sender-side decision; this delivery is inbound",
                        ),
                    )
                }
                val expected = snapshot.descriptor.expectedSha256
                // "The peer already has this file verified" is only meaningful
                // against a digest we both know. Without one there is nothing to
                // compare the claim to, so the honest answer is to send it.
                if (expected == null) {
                    return TransitionResult.rejected(
                        snapshot,
                        Rejection.InvalidResumeProposal(
                            "a file with no expected digest cannot be treated as already verified",
                        ),
                    )
                }
                commit(
                    snapshot,
                    TransferState.COMPLETED,
                    command.commandName,
                    transform = { current ->
                        current.copy(
                            confirmedBytes = current.totalBytes,
                            optimisticBytes = current.totalBytes,
                            verification = VerificationInfo(
                                expectedDigest = expected,
                                observedDigest = expected,
                                startedSnapshotVersion = current.snapshotVersion,
                            ),
                        )
                    },
                    effects = { current ->
                        persist(current) + schedule(current)
                    },
                )
            }

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
                    lastAcknowledgedSequence = if (offset == 0L) {
                        null
                    } else {
                        SequenceNumber.forOffset(offset, current.chunkSize).value - 1L
                    },
                )
            },
            effects = { current ->
                listOf(
                    TransferEffect.SendResume(current.transferId, current.confirmedBytes),
                    TransferEffect.PersistConfirmedOffset(current.transferId, current.confirmedBytes),
                ) + openStreams(current) + persist(current)
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
                ) + persist(current) + schedule(current)
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

    private fun onChunkReceived(
        snapshot: TransferSnapshot,
        event: TransferEvent.ChunkReceived,
    ): TransitionResult {
        if (snapshot.state != TransferState.RECEIVING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, event.eventName),
            )
        }
        val range = checkChunkRange(
            snapshot, event.offset, event.length, event.sequence,
            frontier = snapshot.confirmedBytes,
        )
        if (range != null) return range

        val end = event.offset + event.length
        return when {
            event.offset == snapshot.confirmedBytes -> mutate(
                snapshot,
                event.eventName,
                allowedStates = RECEIVING_STATES,
                transform = { current ->
                    current.copy(
                        confirmedBytes = end,
                        optimisticBytes = end,
                        lastAcknowledgedSequence = event.sequence,
                    )
                },
                effects = { current ->
                    listOf(
                        TransferEffect.SendAcknowledgement(
                            transferId = current.transferId,
                            offset = event.offset,
                            length = event.length,
                            confirmedOffset = current.confirmedBytes,
                        ),
                        TransferEffect.PersistConfirmedOffset(
                            transferId = current.transferId,
                            confirmedOffset = current.confirmedBytes,
                        ),
                    ) + persist(current)
                },
            )

            end <= snapshot.confirmedBytes ->
                // A duplicate of bytes already confirmed: acknowledge again so the
                // sender can make progress, but do not move the watermark.
                TransitionResult.accepted(
                    snapshot,
                    snapshot,
                    listOf(
                        TransferEffect.SendAcknowledgement(
                            transferId = snapshot.transferId,
                            offset = event.offset,
                            length = event.length,
                            confirmedOffset = snapshot.confirmedBytes,
                        ),
                        TransferEffect.NotifyUi(snapshot.transferId, snapshot.state),
                    ),
                )

            event.offset < snapshot.confirmedBytes -> TransitionResult.rejected(
                snapshot,
                Rejection.OverlappingChunk(event.offset, event.length, snapshot.confirmedBytes),
            )

            else -> TransitionResult.rejected(
                snapshot,
                Rejection.UnexpectedOffset(snapshot.confirmedBytes, event.offset),
            )
        }
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
        val range = checkChunkRange(
            snapshot, event.offset, event.length, event.sequence,
            frontier = snapshot.optimisticBytes,
        )
        if (range != null) return range
        // The peer cannot have received bytes this side never put on the wire.
        if (event.confirmedOffset > snapshot.optimisticBytes) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.AcknowledgementBeyondSent(snapshot.optimisticBytes, event.confirmedOffset),
            )
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
        if (snapshot.state != TransferState.SENDING && snapshot.state != TransferState.RECEIVING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(snapshot.state, null, event.eventName),
            )
        }
        return applyConfirmedProgress(
            snapshot,
            event.eventName,
            event.confirmedOffset,
            sequence = null,
        )
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
                ) + persist(current)
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
        return beginVerification(snapshot, event.eventName)
    }

    private fun onVerificationStarted(
        snapshot: TransferSnapshot,
        event: TransferEvent.VerificationStarted,
    ): TransitionResult {
        if (!snapshot.allBytesConfirmed) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.VerificationTooEarly(snapshot.confirmedBytes, snapshot.totalBytes),
            )
        }
        if (snapshot.state == TransferState.VERIFYING) return noOp(snapshot)
        return commit(
            snapshot,
            TransferState.VERIFYING,
            event.eventName,
            transform = { current ->
                current.copy(
                    verification = VerificationInfo.started(
                        event.expectedDigest ?: current.descriptor.expectedSha256,
                        current.snapshotVersion,
                    ),
                )
            },
            effects = { current ->
                listOf(
                    TransferEffect.BeginVerification(
                        transferId = current.transferId,
                        expectedDigestHex = current.verification?.expectedDigest?.hex,
                    ),
                ) + persist(current)
            },
        )
    }

    private fun onVerificationSucceeded(
        snapshot: TransferSnapshot,
        event: TransferEvent.VerificationSucceeded,
    ): TransitionResult {
        if (snapshot.state != TransferState.VERIFYING) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.IllegalStateTransition(
                    snapshot.state,
                    TransferState.COMPLETED,
                    event.eventName,
                ),
            )
        }
        if (!snapshot.allBytesConfirmed) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.VerificationTooEarly(snapshot.confirmedBytes, snapshot.totalBytes),
            )
        }
        val expected = snapshot.verification?.expectedDigest ?: snapshot.descriptor.expectedSha256
        val verification = VerificationInfo(
            expectedDigest = expected,
            observedDigest = event.observedDigest,
            startedSnapshotVersion = snapshot.verification?.startedSnapshotVersion
                ?: snapshot.snapshotVersion,
        )
        if (!verification.allowsCommit) {
            return TransitionResult.rejected(
                snapshot,
                Rejection.VerificationConflict(verification.outcome),
            )
        }
        return commit(
            snapshot,
            TransferState.COMPLETED,
            event.eventName,
            transform = { current ->
                current.copy(
                    optimisticBytes = current.confirmedBytes,
                    verification = verification,
                    failure = null,
                )
            },
            effects = { current ->
                listOf(
                    TransferEffect.CommitVerifiedDestination(current.transferId),
                ) + persist(current) + schedule(current)
            },
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
            effects = { current -> persist(current) },
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
            effects = { current -> persist(current) + schedule(current) },
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
            listOf(
                TransferEffect.BeginVerification(
                    transferId = current.transferId,
                    expectedDigestHex = current.verification?.expectedDigest?.hex,
                ),
            ) + persist(current)
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
    private val STREAMING_STATES: Set<TransferState> =
        setOf(TransferState.SENDING, TransferState.RECEIVING)
    private val NEGOTIATING_STATES: Set<TransferState> = setOf(TransferState.NEGOTIATING)

    /** Verification outcomes that permit a commit. */
    public val COMMITTABLE_OUTCOMES: Set<VerificationOutcome> = setOf(
        VerificationOutcome.MATCHED,
        VerificationOutcome.VERIFIED_WITHOUT_EXPECTED,
    )
}
