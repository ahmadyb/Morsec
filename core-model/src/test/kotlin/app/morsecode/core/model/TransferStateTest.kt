package app.morsecode.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine and the per-file action matrix are product invariants, so
 * they are pinned by tests rather than by convention.
 */
class TransferStateTest {

    @Test
    fun `every state appears in the transition table`() {
        assertEquals(
            "every TransferState must declare its legal successors",
            TransferState.entries.toSet(),
            TransferState.allowedTransitions.keys,
        )
    }

    @Test
    fun `terminal states have no successors`() {
        for (state in listOf(
            TransferState.COMPLETED,
            TransferState.CANCELLED,
            TransferState.SKIPPED,
        )) {
            assertTrue("$state must be terminal", state.isTerminal)
            assertTrue("$state must not transition anywhere", TransferState.allowedTransitions.getValue(state).isEmpty())
        }
    }

    @Test
    fun `failed final cannot be retried but can be cancelled`() {
        assertFalse(TransferState.FAILED_FINAL.canTransitionTo(TransferState.QUEUED))
        assertTrue(TransferState.FAILED_FINAL.canTransitionTo(TransferState.CANCELLED))
        assertTrue(TransferState.FAILED_RETRYABLE.canTransitionTo(TransferState.QUEUED))
        assertTrue(TransferState.FAILED_RETRYABLE.canTransitionTo(TransferState.NEGOTIATING))
    }

    @Test
    fun `paused items resume through negotiation or straight back to transfer`() {
        assertTrue(TransferState.PAUSED_LOCAL.canTransitionTo(TransferState.NEGOTIATING))
        assertTrue(TransferState.PAUSED_LOCAL.canTransitionTo(TransferState.SENDING))
        assertTrue(TransferState.PAUSED_LOCAL.canTransitionTo(TransferState.RECEIVING))
        assertTrue(TransferState.PAUSED_LOCAL.canTransitionTo(TransferState.CANCELLED))
        assertFalse("pause must not jump straight to COMPLETED", TransferState.PAUSED_LOCAL.canTransitionTo(TransferState.COMPLETED))
    }

    @Test
    fun `verification is mandatory before completion`() {
        assertFalse(TransferState.SENDING.canTransitionTo(TransferState.COMPLETED))
        assertFalse(TransferState.RECEIVING.canTransitionTo(TransferState.COMPLETED))
        assertTrue(TransferState.VERIFYING.canTransitionTo(TransferState.COMPLETED))
    }

    @Test
    fun `requireTransition applies legal moves and rejects illegal ones`() {
        assertSame(TransferState.VERIFYING, TransferState.SENDING.requireTransition(TransferState.VERIFYING))
        val failure = runCatching { TransferState.COMPLETED.requireTransition(TransferState.SENDING) }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun `sending and receiving offer pause and cancel only`() {
        for (state in listOf(TransferState.SENDING, TransferState.RECEIVING)) {
            val actions = state.itemActions()
            assertTrue("$state pause", actions.pause)
            assertTrue("$state cancel", actions.cancel)
            assertFalse("$state resume", actions.resume)
            assertFalse("$state retry", actions.retry)
        }
    }

    @Test
    fun `paused offers resume and cancel`() {
        for (state in listOf(TransferState.PAUSED_LOCAL, TransferState.PAUSED_REMOTE)) {
            val actions = state.itemActions()
            assertTrue("$state resume", actions.resume)
            assertTrue("$state cancel", actions.cancel)
            assertFalse("$state pause", actions.pause)
            assertFalse("$state retry", actions.retry)
        }
    }

    @Test
    fun `queued offers cancel only`() {
        val actions = TransferState.QUEUED.itemActions()
        assertTrue(actions.cancel)
        assertFalse(actions.pause)
        assertFalse(actions.resume)
        assertFalse(actions.retry)
    }

    @Test
    fun `failed retryable offers retry only`() {
        val actions = TransferState.FAILED_RETRYABLE.itemActions()
        assertTrue(actions.retry)
        assertFalse(actions.pause)
        assertFalse(actions.resume)
        assertFalse("retry is per file; cancel is not offered on a failed row", actions.cancel)
    }

    @Test
    fun `completed cancelled and skipped expose no controls`() {
        for (state in listOf(
            TransferState.COMPLETED,
            TransferState.CANCELLED,
            TransferState.SKIPPED,
            TransferState.FAILED_FINAL,
        )) {
            assertTrue("$state must show no transfer controls", state.itemActions().isEmpty)
        }
    }

    @Test
    fun `busy and pending classifications drive queue admission`() {
        assertTrue(TransferState.SENDING.isBusy)
        assertTrue(TransferState.RECEIVING.isBusy)
        assertTrue(TransferState.VERIFYING.isBusy)
        assertFalse(TransferState.PAUSED_LOCAL.isBusy)
        assertTrue(TransferState.QUEUED.isPending)
        assertTrue(TransferState.NEGOTIATING.isPending)
        assertFalse(TransferState.COMPLETED.isPending)
    }

    @Test
    fun `session summary counts come from real items`() {
        val items = listOf(
            item("a", TransferState.SENDING, SessionDirection.OUTBOUND, total = 100, confirmed = 40, rate = 10),
            item("b", TransferState.QUEUED, SessionDirection.OUTBOUND, total = 100),
            item("c", TransferState.RECEIVING, SessionDirection.INBOUND, total = 200, confirmed = 50, rate = 20),
            item("d", TransferState.COMPLETED, SessionDirection.OUTBOUND, total = 50, confirmed = 50),
            item("e", TransferState.PAUSED_LOCAL, SessionDirection.OUTBOUND, total = 80, confirmed = 20),
        )
        val summary = SessionSummary.of(items)
        assertEquals(1, summary.sending)
        assertEquals(1, summary.receiving)
        assertEquals(1, summary.queued)
        assertEquals(1, summary.paused)
        assertEquals(1, summary.completed)
        assertEquals(0, summary.failed)
        assertEquals(160L, summary.bytesTransferred)
        assertEquals(530L, summary.bytesTotal)
        assertEquals(30L, summary.bytesPerSecond)
        assertFalse(summary.isComplete)
    }

    @Test
    fun `summary reports completion only when nothing is left to move`() {
        val done = listOf(
            item("a", TransferState.COMPLETED, SessionDirection.OUTBOUND, total = 100, confirmed = 100),
            item("b", TransferState.SKIPPED, SessionDirection.OUTBOUND, total = 10),
        )
        assertTrue(SessionSummary.of(done).isComplete)
    }

    private fun item(
        id: String,
        state: TransferState,
        direction: SessionDirection,
        total: Long,
        confirmed: Long = 0L,
        rate: Long = 0L,
    ) = TransferItem(
        transferId = id,
        sessionId = "s1",
        batchId = "b1",
        direction = direction,
        displayName = "$id.bin",
        totalBytes = total,
        confirmedBytes = confirmed,
        bytesPerSecond = rate,
        state = state,
    )
}
