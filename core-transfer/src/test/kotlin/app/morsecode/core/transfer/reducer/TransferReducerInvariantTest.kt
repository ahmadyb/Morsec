package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.command.TransferCommand
import app.morsecode.core.transfer.current
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.event.TransferEvent
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.isAccepted
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.persistence.TransferSnapshotCodec
import app.morsecode.core.transfer.plus
import app.morsecode.core.transfer.protocol.ResumeDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The twenty-eight invariants from `doc/transfer-protocol.md` §4, asserted one by
 * one.
 *
 * The numbering here is the numbering in the document: a change to either breaks
 * this class, which is what keeps specification and implementation from drifting
 * apart. Every test is deterministic — no clock, no socket, no file, no random
 * source — and the ones that need to apply a long run of inputs do it in a loop
 * with a fixed seed.
 */
class TransferReducerInvariantTest {

    // --- ownership ------------------------------------------------------------------------

    @Test fun `invariant 1 - every state change goes through the reducer`() {
        // TransferSnapshot has no public way to move itself: `state` is a
        // constructor value and the only thing that produces a new snapshot is
        // TransferReducer.apply. The compile-time half of this invariant is the
        // absence of a setter; the runtime half is that every move we can make
        // comes back from the reducer.
        val snapshot = Tf.queued()
        val result = TransferReducer.apply(snapshot, Tf.beginNegotiation())
        assertTrue(result is TransitionResult.Accepted)
        assertNotEquals(snapshot.state, current(result).state)
        assertEquals(TransferState.QUEUED, snapshot.state) // the input is untouched
    }

    @Test fun `invariant 2 - the reducer is pure`() {
        val snapshot = Tf.sending()
        val input = Tf.chunkSent(offset = 0L, length = Tf.chunk.value)
        val first = TransferReducer.apply(snapshot, input)
        repeat(20) {
            assertEquals(
                "the same input must give the same result",
                first,
                TransferReducer.apply(snapshot, input),
            )
        }
    }

    @Test fun `invariant 3 - a rejected input leaves the snapshot untouched`() {
        val snapshot = Tf.queued()
        val result = TransferReducer.apply(snapshot, Tf.chunkSent(offset = 0L))
        assertTrue(result is TransitionResult.Rejected)
        val rejected = result as TransitionResult.Rejected
        assertEquals("the snapshot must be returned unchanged", snapshot, rejected.snapshot)
        assertEquals(snapshot.snapshotVersion, rejected.snapshot!!.snapshotVersion)
    }

    @Test fun `invariant 4 - an accepted input advances the version by exactly one`() {
        var snapshot = Tf.queued()
        val inputs: List<Any> = listOf(
            Tf.beginNegotiation(),
            Tf.peerAccepted(),
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value),
        )
        for (input in inputs) {
            val before = snapshot.snapshotVersion
            val result = applyAny(snapshot, input)
            assertTrue(result is TransitionResult.Accepted)
            snapshot = current(result)
            assertEquals("version must advance by one", before + 1L, snapshot.snapshotVersion)
        }
    }

    // --- states -------------------------------------------------------------------------------

    @Test fun `invariant 5 - only the twelve core states are used`() {
        val seen = mutableSetOf<TransferState>()
        walk { seen += it.state }
        assertEquals(TransferState.entries.toSet(), seen)
        assertEquals(12, seen.size)
    }

    @Test fun `invariant 6 - only the table's edges are traversable`() {
        walk { before ->
            val input = Tf.beginNegotiation()
            val result = TransferReducer.apply(before, input)
            if (result is TransitionResult.Accepted) {
                assertTrue(
                    "${before.state} -> ${current(result).state} is not in the table",
                    TransferTransitionTable.canMove(before.state, current(result).state),
                )
            }
        }
    }

    @Test fun `invariant 7 - terminal states have no outgoing edges`() {
        for (state in listOf(
            TransferState.COMPLETED,
            TransferState.FAILED_FINAL,
            TransferState.CANCELLED,
            TransferState.SKIPPED,
        )) {
            assertTrue(
                "$state must have no outgoing edges",
                (TransferTransitionTable.allowed[state] ?: emptySet()).isEmpty(),
            )
        }
    }

    @Test fun `invariant 8 - a terminal snapshot accepts only the two ending events`() {
        val terminals = listOf(
            Tf.completed(),
            Tf.failedFinally(),
            Tf.queued() + Tf.cancelLocally(),
            Tf.queued() + Tf.skip(),
        )
        for (snapshot in terminals) {
            assertTrue(snapshot.isTerminal)
            assertTrue(
                "SessionEnded must be an accepted no-op on ${snapshot.state}",
                isAccepted(TransferReducer.apply(snapshot, Tf.sessionEnded())),
            )
            assertTrue(
                "RemoteCancelled must be an accepted no-op on ${snapshot.state}",
                isAccepted(TransferReducer.apply(snapshot, Tf.remoteCancelled())),
            )
            val moved = TransferReducer.apply(snapshot, Tf.chunkSent(offset = 0L))
            assertTrue("anything else must be refused", moved is TransitionResult.Rejected)
            assertEquals("terminal_transfer", (moved as TransitionResult.Rejected).reason.code)
        }
    }

    @Test fun `invariant 9 - no bytes move before negotiation completes`() {
        val queued = Tf.queued()
        assertTrue(
            "a chunk must not be accepted while queued",
            TransferReducer.apply(queued, Tf.chunkSent(offset = 0L)) is TransitionResult.Rejected,
        )
        val negotiating = queued + Tf.beginNegotiation()
        assertTrue(
            "a chunk must not be accepted while negotiating",
            TransferReducer.apply(negotiating, Tf.chunkSent(offset = 0L)) is TransitionResult.Rejected,
        )
        val sending = negotiating + Tf.peerAccepted()
        assertTrue(
            "a chunk must be accepted once sending",
            TransferReducer.apply(sending, Tf.chunkSent(offset = 0L)) is TransitionResult.Accepted,
        )
    }

    @Test fun `invariant 10 - sending and receiving are entered only from negotiating`() {
        for (state in TransferState.entries) {
            val entersSending = TransferTransitionTable.canMove(state, TransferState.SENDING)
            val entersReceiving = TransferTransitionTable.canMove(state, TransferState.RECEIVING)
            assertFalse("$state must not enter SENDING", entersSending && state != TransferState.NEGOTIATING)
            assertFalse("$state must not enter RECEIVING", entersReceiving && state != TransferState.NEGOTIATING)
        }
        assertTrue(TransferTransitionTable.canMove(TransferState.NEGOTIATING, TransferState.SENDING))
        assertTrue(TransferTransitionTable.canMove(TransferState.NEGOTIATING, TransferState.RECEIVING))
    }

    @Test fun `invariant 11 - verifying is entered only when every byte is confirmed`() {
        val receiving = Tf.receiving()
        val partial = receiving + Tf.chunkReceived(offset = 0L, length = Tf.chunk.value)
        assertFalse(partial.allBytesConfirmed)
        assertTrue(
            "AllBytesConfirmed must be refused while bytes are missing",
            TransferReducer.apply(partial, Tf.allBytesConfirmed()) is TransitionResult.Rejected,
        )
        val whole = Tf.receiveEverything(receiving)
        assertTrue(whole.allBytesConfirmed)
        val verifying = current(TransferReducer.apply(whole, Tf.allBytesConfirmed()))
        assertEquals(TransferState.VERIFYING, verifying.state)
    }

    @Test fun `invariant 12 - completion comes only from verifying, or from an already verified resume`() {
        for (state in TransferState.entries) {
            if (!TransferTransitionTable.canMove(state, TransferState.COMPLETED)) continue
            assertTrue(
                "$state must not reach COMPLETED",
                state == TransferState.VERIFYING || state == TransferState.NEGOTIATING,
            )
        }
        // The NEGOTIATING edge is the resume shortcut, and it needs a known digest.
        val descriptor = Tf.descriptor(digest = Tf.knownDigest)
        val negotiating = Tf.queued(descriptor = descriptor) + Tf.beginNegotiation()
        val result = TransferReducer.apply(
            negotiating,
            Tf.applyResume(ResumeDecision.AlreadyVerified),
        )
        assertTrue(result is TransitionResult.Accepted)
        assertEquals(TransferState.COMPLETED, current(result).state)
        // Without a digest the same claim is refused.
        val unknownDigest = Tf.queued(descriptor = Tf.descriptor(digest = null)) + Tf.beginNegotiation()
        assertTrue(
            TransferReducer.apply(
                unknownDigest,
                Tf.applyResume(ResumeDecision.AlreadyVerified),
            ) is TransitionResult.Rejected,
        )
    }

    // --- addressing -------------------------------------------------------------------------------

    @Test fun `invariant 13 - a misaddressed input is rejected rather than applied`() {
        val snapshot = Tf.queued()
        for (input in listOf(
            Tf.beginNegotiation(sessionId = Tf.otherSessionId),
            Tf.beginNegotiation(transferId = Tf.otherTransferId),
        )) {
            val result = TransferReducer.apply(snapshot, input)
            assertTrue("input $input must be refused", result is TransitionResult.Rejected)
            assertEquals(
                snapshot,
                (result as TransitionResult.Rejected).snapshot,
            )
        }
        val broadcast = Tf.broadcastQueued(Tf.transferId, Tf.recipientA)
        val wrongPeer = TransferReducer.apply(broadcast, Tf.beginNegotiation(recipientId = Tf.recipientB))
        assertTrue(wrongPeer is TransitionResult.Rejected)
    }

    // --- byte accounting ----------------------------------------------------------------------------

    @Test fun `invariant 14 - confirmed bytes never decrease`() {
        var previous = 0L
        walkOutbound { snapshot ->
            assertTrue(
                "confirmed bytes went from $previous to ${snapshot.confirmedBytes}",
                snapshot.confirmedBytes >= previous,
            )
            previous = snapshot.confirmedBytes
        }
    }

    @Test fun `invariant 15 - confirmed bytes never exceed the total`() {
        walk { snapshot ->
            assertTrue(
                "${snapshot.confirmedBytes} exceeds ${snapshot.totalBytes}",
                snapshot.confirmedBytes <= snapshot.totalBytes,
            )
        }
    }

    @Test fun `invariant 16 - optimistic bytes are never below confirmed bytes`() {
        walk { snapshot ->
            assertTrue(
                "optimistic ${snapshot.optimisticBytes} below confirmed ${snapshot.confirmedBytes}",
                snapshot.optimisticBytes >= snapshot.confirmedBytes,
            )
            assertTrue(snapshot.violations().joinToString(), snapshot.isInternallyConsistent())
        }
    }

    @Test fun `invariant 17 - optimistic bytes fall back on pause and on disconnect`() {
        val inFlight = Tf.sending() + Tf.chunkSent(offset = 0L, length = Tf.chunk.value)
        assertEquals(0L, inFlight.confirmedBytes)
        assertTrue(inFlight.optimisticBytes > 0L)

        val paused = current(TransferReducer.apply(inFlight, Tf.pauseLocally()))
        assertEquals(TransferState.PAUSED_LOCAL, paused.state)
        assertEquals(paused.confirmedBytes, paused.optimisticBytes)

        val disconnected = current(
            TransferReducer.apply(inFlight, Tf.transportDisconnected()),
        )
        assertEquals(disconnected.confirmedBytes, disconnected.optimisticBytes)
    }

    @Test fun `invariant 18 - a resume offset is the confirmed offset, never the optimistic one`() {
        val inFlight = Tf.sending() +
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value) +
            Tf.chunkAck(
                offset = 0L,
                length = Tf.chunk.value,
                confirmedOffset = Tf.chunk.value.toLong(),
            ) +
            Tf.chunkSent(offset = Tf.chunk.value.toLong(), length = Tf.chunk.value) +
            Tf.chunkSent(offset = 2L * Tf.chunk.value, length = Tf.chunk.value)
        val paused = current(TransferReducer.apply(inFlight, Tf.pauseLocally()))
        assertEquals(TransferState.PAUSED_LOCAL, paused.state)
        assertEquals(Tf.THREE_CHUNKS, paused.totalBytes)

        val resuming = current(TransferReducer.apply(paused, Tf.resumeLocally()))
        assertEquals(TransferState.NEGOTIATING, resuming.state)
        // The optimistic three chunks are discarded; the confirmed one is kept.
        assertEquals(Tf.chunk.value.toLong(), resuming.confirmedBytes)
        assertEquals(Tf.chunk.value.toLong(), resuming.optimisticBytes)
    }

    @Test fun `invariant 19 - a chunk carries the sequence its offset implies and never skips bytes`() {
        val sending = Tf.sending()
        assertTrue(
            "the first chunk must be accepted",
            isAccepted(TransferReducer.apply(sending, Tf.chunkSent(offset = 0L))),
        )
        assertTrue(
            "a sequence that disagrees with the offset must be refused",
            TransferReducer.apply(
                sending,
                TransferEvent.ChunkSent(
                    Tf.sessionId, Tf.transferId, null,
                    offset = 0L, length = Tf.chunk.value, sequence = 7L,
                ),
            ) is TransitionResult.Rejected,
        )
        val afterFirst = sending + Tf.chunkSent(offset = 0L)
        assertTrue(
            "a chunk that skips bytes must be refused",
            TransferReducer.apply(
                afterFirst,
                Tf.chunkSent(offset = 2L * Tf.chunk.value),
            ) is TransitionResult.Rejected,
        )
        val gap = TransferReducer.apply(afterFirst, Tf.chunkSent(offset = 2L * Tf.chunk.value))
        assertEquals("unexpected_offset", (gap as TransitionResult.Rejected).reason.code)
        assertTrue(
            "the next chunk must be accepted",
            isAccepted(
                TransferReducer.apply(afterFirst, Tf.chunkSent(offset = Tf.chunk.value.toLong())),
            ),
        )
        // A resend of bytes already on the wire is idempotent, not an error: the
        // peer may ask for it twice and the answer must be the same either way.
        val resend = TransferReducer.apply(afterFirst, Tf.chunkSent(offset = 0L))
        assertTrue(resend is TransitionResult.Accepted)
        assertEquals(afterFirst, current(resend).copy(snapshotVersion = afterFirst.snapshotVersion))
    }

    @Test fun `invariant 20 - a chunk never exceeds the chunk size or overruns the file`() {
        val sending = Tf.sending()
        assertTrue(
            "an oversized chunk must be refused",
            TransferReducer.apply(
                sending,
                Tf.chunkSent(offset = 0L, length = ProtocolLimits.MAX_CHUNK_SIZE_BYTES + 1),
            ) is TransitionResult.Rejected,
        )
        val lastChunk = Tf.queued(descriptor = Tf.descriptor(totalBytes = 10L))
        val sendingLast = current(
            TransferReducer.apply(lastChunk, Tf.beginNegotiation()),
        ).let { current(TransferReducer.apply(it, Tf.peerAccepted())) }
        assertTrue(
            "a chunk past the end of the file must be refused",
            TransferReducer.apply(sendingLast, Tf.chunkSent(offset = 0L, length = 11))
                is TransitionResult.Rejected,
        )
    }

    @Test fun `invariant 21 - a zero length chunk is rejected`() {
        val sending = Tf.sending()
        val result = TransferReducer.apply(sending, Tf.chunkSent(offset = 0L, length = 0))
        assertTrue(result is TransitionResult.Rejected)
        assertEquals("invalid_chunk_length", (result as TransitionResult.Rejected).reason.code)
    }

    @Test fun `invariant 22 - an acknowledgement never confirms more than was sent`() {
        val sending = Tf.sending()
        assertTrue(
            "acknowledging an unsent chunk must be refused",
            TransferReducer.apply(
                sending,
                Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = Tf.chunk.value.toLong()),
            ) is TransitionResult.Rejected,
        )
        val sent = sending + Tf.chunkSent(offset = 0L, length = Tf.chunk.value)
        assertTrue(
            "acknowledging more than the file holds must be refused",
            TransferReducer.apply(
                sent,
                Tf.chunkAck(
                    offset = 0L,
                    length = Tf.chunk.value,
                    confirmedOffset = Tf.THREE_CHUNKS + 1L,
                ),
            ) is TransitionResult.Rejected,
        )
        assertTrue(
            "a well formed acknowledgement must be accepted",
            isAccepted(
                TransferReducer.apply(
                    sent,
                    Tf.chunkAck(
                        offset = 0L,
                        length = Tf.chunk.value,
                        confirmedOffset = Tf.chunk.value.toLong(),
                    ),
                ),
            ),
        )
    }

    @Test fun `invariant 23 - the retry count only rises, and stops at the ceiling`() {
        var snapshot = Tf.sending()
        var previous = 0
        var attempts = 0
        while (snapshot.state != TransferState.FAILED_FINAL && attempts < 20) {
            attempts += 1
            val disconnected = TransferReducer.apply(snapshot, Tf.transportDisconnected())
            assertTrue("a disconnect must always be accepted", disconnected is TransitionResult.Accepted)
            snapshot = current(disconnected)
            assertTrue(
                "the retry count fell from $previous to ${snapshot.retryCount}",
                snapshot.retryCount >= previous,
            )
            assertTrue(
                "the retry count passed the ceiling: ${snapshot.retryCount}",
                snapshot.retryCount <= ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
            )
            previous = snapshot.retryCount
            val retry = TransferReducer.apply(snapshot, Tf.retry())
            if (snapshot.state == TransferState.FAILED_RETRYABLE &&
                snapshot.retryCount >= ProtocolLimits.DEFAULT_MAX_RETRY_COUNT
            ) {
                assertTrue(
                    "at the ceiling a retry must be refused, not performed",
                    retry is TransitionResult.Rejected,
                )
                assertEquals("retry_not_allowed", (retry as TransitionResult.Rejected).reason.code)
                assertEquals(
                    "a refused retry must leave the delivery where it was",
                    snapshot,
                    retry.snapshot,
                )
                break
            }
            assertTrue("a retry below the ceiling must be accepted", retry is TransitionResult.Accepted)
            snapshot = current(retry)
            for (input in listOf<Any>(Tf.beginNegotiation(), Tf.peerAccepted())) {
                val result = applyAny(snapshot, input)
                assertTrue("input $input must be accepted", result is TransitionResult.Accepted)
                snapshot = current(result)
            }
        }
        assertEquals(
            "the retry count must stop at the ceiling",
            ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
            snapshot.retryCount,
        )
        assertEquals(TransferState.FAILED_RETRYABLE, snapshot.state)
    }

    @Test fun `invariant 24 - a retryable failure returns to queued before it can be retried`() {
        val failed = Tf.failedRetryable()
        assertEquals(TransferState.FAILED_RETRYABLE, failed.state)
        val requeued = current(TransferReducer.apply(failed, Tf.retry()))
        assertEquals(TransferState.QUEUED, requeued.state)
        assertTrue(
            "a queued delivery cannot be retried again before it is renegotiated",
            TransferReducer.apply(requeued, Tf.retry()) is TransitionResult.Rejected,
        )
    }

    @Test fun `invariant 25 - optimistic bytes are never promoted without an acknowledgement`() {
        val sent = Tf.sending() + Tf.chunkSent(offset = 0L, length = Tf.chunk.value)
        assertEquals(0L, sent.confirmedBytes)
        assertEquals(Tf.chunk.value.toLong(), sent.optimisticBytes)
        // Nothing but an acknowledgement moves the confirmed number.
        val paused = current(TransferReducer.apply(sent, Tf.pauseLocally()))
        assertEquals(0L, paused.confirmedBytes)
        val resumed = current(TransferReducer.apply(paused, Tf.resumeLocally()))
        assertEquals(0L, resumed.confirmedBytes)
        val acked = current(
            TransferReducer.apply(
                sent,
                Tf.chunkAck(
                    offset = 0L,
                    length = Tf.chunk.value,
                    confirmedOffset = Tf.chunk.value.toLong(),
                ),
            ),
        )
        assertEquals(Tf.chunk.value.toLong(), acked.confirmedBytes)
    }

    @Test fun `invariant 26 - a restored snapshot keeps the two byte counts separate`() {
        val inFlight = Tf.sending() +
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value) +
            Tf.chunkSent(offset = Tf.chunk.value.toLong(), length = Tf.chunk.value)
        assertEquals(0L, inFlight.confirmedBytes)
        assertEquals(2L * Tf.chunk.value, inFlight.optimisticBytes)
        val restored = (TransferSnapshotCodec.deserialize(
            TransferSnapshotCodec.serialize(inFlight),
        ) as app.morsecode.core.transfer.persistence.SnapshotDecodeResult.Success).snapshot
        assertEquals(0L, restored.confirmedBytes)
        assertEquals(2L * Tf.chunk.value, restored.optimisticBytes)
    }

    // --- verification ---------------------------------------------------------------------------------

    @Test fun `invariant 27 - verification is required, and a digest conflict is not success`() {
        val verifying = Tf.receiveEverything(
            Tf.receiving(descriptor = Tf.descriptor(digest = Tf.knownDigest)),
        ) + Tf.allBytesConfirmed()
        assertEquals(TransferState.VERIFYING, verifying.state)

        val mismatched = TransferReducer.apply(
            verifying,
            Tf.verificationSucceeded(Tf.otherDigest),
        )
        assertTrue(
            "a digest that disagrees with the expectation must be refused",
            mismatched is TransitionResult.Rejected,
        )
        assertEquals(
            "verification_conflict",
            (mismatched as TransitionResult.Rejected).reason.code,
        )

        val verifyingNoDigest = Tf.receiveEverything(
            Tf.receiving(descriptor = Tf.descriptor(digest = null)),
        ) + Tf.allBytesConfirmed()
        val succeeded = current(
            TransferReducer.apply(verifyingNoDigest, Tf.verificationSucceeded(Tf.knownDigest)),
        )
        assertEquals(TransferState.COMPLETED, succeeded.state)
        assertEquals(
            app.morsecode.core.transfer.model.VerificationOutcome.VERIFIED_WITHOUT_EXPECTED,
            succeeded.verification?.outcome,
        )
    }

    @Test fun `invariant 28 - every rejection carries a typed, bounded and redacted error`() {
        val rejections = mutableListOf<Rejection>()
        walk { snapshot ->
            for (input in allInputs()) {
                val result = applyAny(snapshot, input)
                if (result is TransitionResult.Rejected) rejections += result.reason
            }
        }
        assertTrue("expected some rejections", rejections.isNotEmpty())
        for (rejection in rejections) {
            val error: TransferError = rejection.error
            assertTrue("${error.code} needs a code", error.code.isNotBlank())
            assertTrue("${error.code} must be final or retryable", error.isFinal != error.retryable)
            assertTrue("${error.code} needs an origin", error.origin in ErrorOrigin.entries)
            assertTrue("${error.code} needs a category", error.category in ErrorCategory.entries)
            assertTrue(
                "${error.code} detail is too long",
                error.detail.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_ERROR_DETAIL_BYTES,
            )
            assertFalse(
                "${error.code} leaked a path: ${error.detail}",
                error.detail.contains("/storage") || error.detail.contains("/sdcard"),
            )
            error.detail.forEach { character ->
                assertTrue("${error.code} leaked a control character", character.code >= 32)
            }
        }
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private fun applyAny(snapshot: TransferSnapshot, input: Any): TransitionResult =
        when (input) {
            is TransferCommand -> TransferReducer.apply(snapshot, input)
            is TransferEvent -> TransferReducer.apply(snapshot, input)
            else -> throw IllegalArgumentException("not a transfer input: $input")
        }

    /** Every input a test might throw at a snapshot. */
    private fun allInputs(): List<Any> = listOf(
        Tf.beginNegotiation(),
        Tf.accept(),
        Tf.reject(),
        Tf.peerAccepted(),
        Tf.chunkSent(offset = 0L),
        Tf.chunkReceived(offset = 0L),
        Tf.chunkAck(offset = 0L, length = Tf.chunk.value, confirmedOffset = Tf.chunk.value.toLong()),
        Tf.allBytesConfirmed(),
        Tf.verificationSucceeded(),
        Tf.pauseLocally(),
        Tf.resumeLocally(),
        Tf.remotePaused(),
        Tf.remoteResumed(),
        Tf.transportDisconnected(),
        Tf.transportRestored(),
        Tf.permissionRevoked(),
        Tf.retry(),
        Tf.skip(),
        Tf.cancelLocally(),
        Tf.remoteCancelled(),
        Tf.sessionEnded(),
        Tf.applyResume(ResumeDecision.ResumeAt(0L)),
    )

    /**
     * A deterministic walk over a representative set of snapshots.
     *
     * No random source and no clock: the inputs are fixed and listed, so a
     * failure names the state it happened in.
     */
    private fun walk(visit: (TransferSnapshot) -> Unit) {
        val samples = listOf(
            Tf.queued(),
            Tf.queued() + Tf.beginNegotiation(),
            Tf.sending(),
            Tf.receiving(),
            Tf.pausedLocally(confirmedBytes = Tf.chunk.value.toLong()),
            Tf.sending() + Tf.remotePaused(),
            Tf.receiveEverything(Tf.receiving()) + Tf.allBytesConfirmed(),
            Tf.completed(),
            Tf.failedRetryable(retryCount = 1),
            Tf.failedFinally(),
            Tf.queued() + Tf.cancelLocally(),
            Tf.queued() + Tf.skip(),
        )
        for (snapshot in samples) {
            visit(snapshot)
            for (input in allInputs()) {
                val result = applyAny(snapshot, input)
                if (result is TransitionResult.Accepted) visit(current(result))
            }
        }
    }

    /** The same walk, restricted to the outbound snapshots in the order they occur. */
    private fun walkOutbound(visit: (TransferSnapshot) -> Unit) {
        var snapshot = Tf.queued()
        visit(snapshot)
        val inputs: List<Any> = listOf(
            Tf.beginNegotiation(),
            Tf.peerAccepted(),
            Tf.chunkSent(offset = 0L, length = Tf.chunk.value),
            Tf.chunkAck(
                offset = 0L,
                length = Tf.chunk.value,
                confirmedOffset = Tf.chunk.value.toLong(),
            ),
            Tf.chunkSent(offset = Tf.chunk.value.toLong(), length = Tf.chunk.value),
            Tf.chunkAck(
                offset = Tf.chunk.value.toLong(),
                length = Tf.chunk.value,
                confirmedOffset = 2L * Tf.chunk.value,
            ),
            Tf.pauseLocally(),
            Tf.resumeLocally(),
            Tf.peerAccepted(),
            Tf.chunkSent(offset = 2L * Tf.chunk.value, length = Tf.chunk.value),
            Tf.chunkAck(
                offset = 2L * Tf.chunk.value,
                length = Tf.chunk.value,
                confirmedOffset = Tf.THREE_CHUNKS,
            ),
        )
        for (input in inputs) {
            val result = applyAny(snapshot, input)
            if (result is TransitionResult.Accepted) {
                snapshot = current(result)
                visit(snapshot)
            }
        }
    }
}
