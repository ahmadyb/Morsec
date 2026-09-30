package app.morsecode.ui.transfer

import app.morsecode.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The per-file matrix, state by state and direction by direction.
 *
 * Every combination the master prompt names is a row of the parameter list below, and the
 * assertions are about the model rather than about a screen: which controls a state permits,
 * and what each control does to a row in that state in that direction. Parameterised because
 * the interesting failure is not "pause is broken" but "pause behaves differently for an
 * incoming file than for an outgoing one", which only shows up when both are asked.
 */
@RunWith(Parameterized::class)
class TransferActionsMatrixTest(
    private val state: TransferState,
    private val direction: TransferDirection,
) {

    @Test
    fun `a row offers exactly what the matrix allows`() {
        val actions = item(state = state, direction = direction).actions

        when (state) {
            // Moving: holdable, and stoppable.
            TransferState.SENDING, TransferState.RECEIVING -> assertTrue(
                "a moving file is paused and cancelled, never resumed or retried",
                actions == TransferActions(pause = true, resume = false, cancel = true, retry = false),
            )

            // Held: resumable, and stoppable.
            TransferState.PAUSED -> assertTrue(
                "a held file is resumed and cancelled",
                actions == TransferActions(pause = false, resume = true, cancel = true, retry = false),
            )

            // Waiting its turn: only the way out.
            TransferState.QUEUED -> assertTrue(
                "a queued file is cancelled, and nothing else",
                actions == TransferActions(pause = false, resume = false, cancel = true, retry = false),
            )

            // Stopped by something other than the user: only the way back.
            TransferState.FAILED -> assertTrue(
                "a failed file is retried, and nothing else",
                actions == TransferActions(pause = false, resume = false, cancel = false, retry = true),
            )

            // Arrived, and the three states that have stopped for good: no controls at all.
            TransferState.DONE,
            TransferState.CANCELLED,
            TransferState.SKIPPED,
            -> assertTrue("$state must carry no controls", actions == TransferActions.NONE)

            // Being verified: the model does not support cancelling a verification, so the
            // row offers nothing rather than a wrong control.
            TransferState.VERIFYING -> assertTrue(
                "verification is not cancellable in this model",
                actions == TransferActions.NONE,
            )
        }
        assertEquals("$state/$direction has the wrong controls", expectedActions(state), actions)
    }

    @Test
    fun `a completed file never offers pause, resume, cancel or retry`() {
        val completed = item(state = TransferState.DONE, direction = direction).actions

        assertFalse(completed.pause)
        assertFalse(completed.resume)
        assertFalse(completed.cancel)
        assertFalse(completed.retry)
        assertFalse(completed.any)
    }

    @Test
    fun `eligibility the engine refuses removes a control the state would allow`() {
        val stuck = item(state = TransferState.SENDING, direction = direction, pauseEligible = false)
        assertFalse("a peer that cannot hold a transfer offers no pause", stuck.actions.pause)
        assertTrue("but it can still be cancelled", stuck.actions.cancel)

        val unfixable = item(state = TransferState.FAILED, direction = direction, retryEligible = false)
        assertFalse(unfixable.actions.retry)
        assertFalse(unfixable.actions.any)

        val unbreakable = item(state = TransferState.PAUSED, direction = direction, cancelEligible = false)
        assertTrue(unbreakable.actions.resume)
        assertFalse(unbreakable.actions.cancel)
    }

    @Test
    fun `pause holds the row where it is and stops the speed`() {
        val moving = item(state = TransferState.SENDING, direction = direction)
        val held = TransferRules.pause(moving)

        if (state.isPausable) {
            assertEquals(TransferState.PAUSED, held.state)
            assertEquals(0L, held.speedBytesPerSecond)
            assertEquals("the bytes already transferred are kept", moving.transferredBytes, held.transferredBytes)
        } else {
            assertSame("a state that cannot be paused is returned unchanged", moving, held)
        }
    }

    @Test
    fun `resume returns the row to the state that matches its direction`() {
        val held = item(state = TransferState.PAUSED, direction = direction)
        val resumed = TransferRules.resume(held)

        if (state.isResumable) {
            assertEquals(direction.activeState, resumed.state)
            assertEquals(held.transferredBytes, resumed.transferredBytes)
        } else {
            assertSame(held, resumed)
        }
    }

    @Test
    fun `cancel stops the row and keeps where it got to`() {
        val during = item(state = state, direction = direction)
        val cancelled = TransferRules.cancel(during)

        if (state.isCancellable) {
            assertEquals(TransferState.CANCELLED, cancelled.state)
            assertEquals(0L, cancelled.speedBytesPerSecond)
            assertEquals(during.transferredBytes, cancelled.transferredBytes)
        } else {
            assertSame(during, cancelled)
        }
    }

    @Test
    fun `retry restarts from the beginning, which resume is not`() {
        val failed = item(state = TransferState.FAILED, direction = direction)
        val retried = TransferRules.retry(failed)

        if (state.isRetryable) {
            assertEquals(
                "an outbound file waits its turn again; an inbound one starts arriving again",
                if (direction == TransferDirection.OUTGOING) TransferState.QUEUED else TransferState.RECEIVING,
                retried.state,
            )
            assertEquals("a retry is a fresh attempt, not a resumption", 0L, retried.transferredBytes)
            assertEquals(VerificationOutcome.PENDING, retried.verification)
            assertEquals(0L, retried.speedBytesPerSecond)
        } else {
            assertSame(failed, retried)
        }
    }

    @Test
    fun `a control the state forbids changes nothing at all`() {
        val row = item(state = state, direction = direction)

        TransferRowAction.entries.forEach { action ->
            val after = TransferRules.apply(row, action)
            val allowed = when (action) {
                TransferRowAction.PAUSE -> row.actions.pause
                TransferRowAction.RESUME -> row.actions.resume
                TransferRowAction.CANCEL -> row.actions.cancel
                TransferRowAction.RETRY -> row.actions.retry
            }
            if (!allowed) {
                assertSame("$action must not touch a $state row", row, after)
            }
        }
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0} {1}")
        public fun cases(): List<Array<Any>> = TransferState.entries.flatMap { state ->
            TransferDirection.entries.map { direction -> arrayOf(state, direction) }
        }

        private fun expectedActions(state: TransferState): TransferActions = when (state) {
            TransferState.SENDING, TransferState.RECEIVING ->
                TransferActions(pause = true, resume = false, cancel = true, retry = false)

            TransferState.PAUSED ->
                TransferActions(pause = false, resume = true, cancel = true, retry = false)

            TransferState.QUEUED ->
                TransferActions(pause = false, resume = false, cancel = true, retry = false)

            TransferState.FAILED ->
                TransferActions(pause = false, resume = false, cancel = false, retry = true)

            TransferState.DONE, TransferState.VERIFYING, TransferState.CANCELLED, TransferState.SKIPPED ->
                TransferActions.NONE
        }

        internal fun item(
            state: TransferState,
            direction: TransferDirection = TransferDirection.OUTGOING,
            pauseEligible: Boolean = true,
            cancelEligible: Boolean = true,
            retryEligible: Boolean = true,
        ) = TransferItem(
            id = "item-${state.id}",
            sessionId = "session-1",
            direction = direction,
            fileName = "holiday_2019.mp4",
            kind = MediaKind.VIDEO,
            totalBytes = 144_000_000L,
            transferredBytes = 48_900_000L,
            speedBytesPerSecond = 6_200_000L,
            state = state,
            pauseEligible = pauseEligible,
            cancelEligible = cancelEligible,
            retryEligible = retryEligible,
        )
    }
}

/**
 * The arithmetic and the batch rules: what a row's bar draws, and what a whole-session action
 * does and does not reach.
 */
class TransferRulesTest {

    private val outbound = listOf(
        item("a", TransferDirection.OUTGOING, TransferState.SENDING, 144_000_000L, 48_900_000L),
        item("b", TransferDirection.OUTGOING, TransferState.QUEUED, 4_100_000L, 0L),
        item("c", TransferDirection.OUTGOING, TransferState.PAUSED, 64_000_000L, 39_700_000L),
        item("d", TransferDirection.OUTGOING, TransferState.DONE, 18_200_000L, 18_200_000L),
        item("e", TransferDirection.OUTGOING, TransferState.FAILED, 412_000L, 190_000L),
    )

    private val inbound = listOf(
        item("f", TransferDirection.INCOMING, TransferState.RECEIVING, 18_200_000L, 13_100_000L),
        item("g", TransferDirection.INCOMING, TransferState.DONE, 4_100_000L, 4_100_000L),
    )

    private val session = TransferSession(
        id = "session-1",
        peer = TransferPeer("r", "Ravi's Redmi", "R", "LAN", "192.168.1.42"),
        outbound = outbound,
        inbound = inbound,
    )

    @Test
    fun `a bar never passes its end and never divides by zero`() {
        assertEquals(0f, TransferProgress.fraction(0L, 100L, TransferState.SENDING), 0f)
        assertEquals(0.5f, TransferProgress.fraction(50L, 100L, TransferState.SENDING), 0f)
        assertEquals(1f, TransferProgress.fraction(100L, 100L, TransferState.SENDING), 0f)
        assertEquals(1f, TransferProgress.fraction(150L, 100L, TransferState.SENDING), 0f)
        assertEquals(0f, TransferProgress.fraction(-20L, 100L, TransferState.SENDING), 0f)
    }

    @Test
    fun `a zero-byte file is handled rather than divided by`() {
        assertEquals("nothing to carry, nothing to show", 0f, TransferProgress.fraction(0L, 0L, TransferState.QUEUED), 0f)
        assertEquals(0f, TransferProgress.fraction(0L, -1L, TransferState.SENDING), 0f)
        // A zero-byte file that arrived is complete: there was nothing to carry and all of it came.
        assertEquals(1f, TransferProgress.fraction(0L, 0L, TransferState.DONE), 0f)
    }

    @Test
    fun `bytes beyond the file are clamped wherever they are read`() {
        val lying = item("x", TransferDirection.OUTGOING, TransferState.SENDING, 100L, 175L)

        assertEquals(100L, lying.transferred)
        assertEquals(1f, lying.fraction, 0f)
        assertEquals("100%", percentOf(lying))
    }

    @Test
    fun `negative bytes read as none rather than as a backwards bar`() {
        val odd = item("x", TransferDirection.OUTGOING, TransferState.SENDING, 100L, -50L)

        assertEquals(0L, odd.transferred)
        assertEquals(0f, odd.fraction, 0f)
    }

    @Test
    fun `pause all holds the moving rows and touches nothing else`() {
        val held = TransferRules.pauseAll(session)

        assertEquals(TransferState.PAUSED, held.item("a")!!.state)
        assertEquals("a queued file is already not moving", TransferState.QUEUED, held.item("b")!!.state)
        assertEquals(TransferState.PAUSED, held.item("c")!!.state)
        assertEquals("a delivered file is left alone", TransferState.DONE, held.item("d")!!.state)
        assertEquals("a failed file is not a running one", TransferState.FAILED, held.item("e")!!.state)
        assertEquals(
            "the other direction pauses with the same action",
            TransferState.PAUSED,
            held.item("f")!!.state,
        )
        assertEquals(TransferState.DONE, held.item("g")!!.state)
    }

    @Test
    fun `pause all skips rows the engine says cannot be held`() {
        val stuck = session.withItem(
            item("a", TransferDirection.OUTGOING, TransferState.SENDING, pauseEligible = false),
        )

        val held = TransferRules.pauseAll(stuck)

        assertEquals("the engine refused this one, so it keeps running", TransferState.SENDING, held.item("a")!!.state)
        assertEquals(TransferState.PAUSED, held.item("f")!!.state)
    }

    @Test
    fun `resume all sets each row going in its own direction`() {
        val held = TransferRules.pauseAll(session)
        val resumed = TransferRules.resumeAll(held)

        assertEquals(TransferState.SENDING, resumed.item("a")!!.state)
        assertEquals(
            "an inbound file resumes receiving, not sending",
            TransferState.RECEIVING,
            resumed.item("f")!!.state,
        )
        assertEquals("a queued file was never held", TransferState.QUEUED, resumed.item("b")!!.state)
        assertEquals(TransferState.DONE, resumed.item("d")!!.state)
    }

    @Test
    fun `resume all reaches only rows that are actually held`() {
        val resumed = TransferRules.resumeAll(session)

        assertEquals("nothing was paused in the outbound direction", TransferState.SENDING, resumed.item("a")!!.state)
        assertEquals(TransferState.PAUSED, "the held row cannot be held again", resumed.item("c")!!.state)
        assertEquals(TransferState.RECEIVING, resumed.item("f")!!.state)
    }

    @Test
    fun `clear completed empties one section and leaves the other exactly as it was`() {
        val cleared = TransferRules.clearCompleted(session, TransferDirection.OUTGOING)

        assertEquals(listOf("a", "b", "c", "e"), cleared.outbound.map { it.id })
        assertEquals("the other direction is not this action's business", inbound, cleared.inbound)
    }

    @Test
    fun `clear completed removes nothing that is not completed`() {
        val cleared = TransferRules.clearCompleted(session, TransferDirection.INCOMING)

        assertEquals(
            "a delivered file goes; a receiving one stays",
            listOf("f"),
            cleared.inbound.map { it.id },
        )
        assertTrue(
            "a cancelled, failed, skipped, queued or held file is something the user may still want",
            TransferRules.clearCompleted(session, TransferDirection.OUTGOING)
                .outbound
                .any { it.state == TransferState.PAUSED },
        )
    }

    @Test
    fun `ending a session stops what is unfinished and keeps what arrived`() {
        val ended = TransferRules.end(session)

        assertEquals(TransferState.CANCELLED, ended.item("a")!!.state)
        assertEquals("a queued file is unfinished too", TransferState.CANCELLED, ended.item("b")!!.state)
        assertEquals("a held file is still unfinished", TransferState.CANCELLED, ended.item("c")!!.state)
        assertEquals(TransferState.CANCELLED, ended.item("f")!!.state)
        assertEquals("a delivered file is not unfinished", TransferState.DONE, ended.item("d")!!.state)
        assertEquals(
            "a failure that happened before the user ended anything is not their cancellation",
            TransferState.FAILED,
            ended.item("e")!!.state,
        )
        assertEquals(
            "the bytes a cancelled file reached are kept",
            48_900_000L,
            ended.item("a")!!.transferredBytes,
        )
    }

    @Test
    fun `a verified file counts as delivered, and a mismatched one still counts as delivered`() {
        val verified = item("v", TransferDirection.OUTGOING, TransferState.DONE, 100L, 100L)
            .copy(verification = VerificationOutcome.VERIFIED)
        val mismatched = verified.copy(verification = VerificationOutcome.MISMATCH)

        assertTrue(verified.state.isComplete)
        assertTrue(
            "the file arrived; what the checksum said is a separate fact",
            mismatched.state.isComplete,
        )
        assertEquals(
            VerificationOutcome.MISMATCH,
            (mismatched.meta as TransferMeta.Delivered).verification,
        )
    }

    @Test
    fun `the summary counts each state once and calls a batch complete only when nothing is left running`() {
        val running = TransferSummary.of(TransferDirection.OUTGOING, outbound)

        assertEquals(5, running.total)
        assertEquals(1, running.active)
        assertEquals(1, running.queued)
        assertEquals(1, running.paused)
        assertEquals(1, running.done)
        assertEquals(1, running.failed)
        assertFalse("a failed file is still unfinished business", running.complete)

        val settled = TransferSummary.of(
            TransferDirection.OUTGOING,
            listOf(
                item("a", TransferDirection.OUTGOING, TransferState.DONE, 100L, 100L),
                item("b", TransferDirection.OUTGOING, TransferState.FAILED, 100L, 40L),
            ),
        )
        assertTrue(
            "the reference would call this in progress for ever; nothing is running, so it is finished",
            settled.complete,
        )
        assertEquals(1, settled.failed)
    }

    @Test
    fun `an empty batch is not a complete one`() {
        val empty = TransferSummary.of(TransferDirection.INCOMING, emptyList())

        assertEquals(0, empty.total)
        assertFalse(empty.complete)
        assertFalse(empty.hasAverageSpeed)
    }

    @Test
    fun `the average speed is the average of what is actually moving`() {
        val summary = TransferSummary.of(
            TransferDirection.OUTGOING,
            listOf(
                item("a", TransferDirection.OUTGOING, TransferState.SENDING, 100L, 10L, speed = 6_000_000L),
                item("b", TransferDirection.OUTGOING, TransferState.SENDING, 100L, 10L, speed = 4_000_000L),
                item("c", TransferDirection.OUTGOING, TransferState.PAUSED, 100L, 10L, speed = 9_000_000L),
            ),
        )

        assertEquals(5_000_000L, summary.averageSpeedBytesPerSecond)
        assertTrue(summary.hasAverageSpeed)
    }

    @Test
    fun `a batch where nothing is moving reports no average rather than a number`() {
        val summary = TransferSummary.of(
            TransferDirection.OUTGOING,
            listOf(item("a", TransferDirection.OUTGOING, TransferState.DONE, 100L, 100L)),
        )

        assertEquals(0L, summary.averageSpeedBytesPerSecond)
        assertFalse("the reference prints a sample figure here; this prints nothing", summary.hasAverageSpeed)
    }

    @Test
    fun `every state states the facts its row needs`() {
        assertEquals(
            TransferMeta.Transferring(48_900_000L, 144_000_000L, 6_200_000L),
            item("a", TransferDirection.OUTGOING, TransferState.SENDING, 144_000_000L, 48_900_000L).meta,
        )
        assertEquals(
            TransferMeta.Waiting(4_100_000L),
            item("b", TransferDirection.OUTGOING, TransferState.QUEUED, 4_100_000L, 0L).meta,
        )
        assertEquals(
            "a held row states the offset it would resume from",
            TransferMeta.Held(39_700_000L, 64_000_000L),
            item("c", TransferDirection.OUTGOING, TransferState.PAUSED, 64_000_000L, 39_700_000L).meta,
        )
        assertEquals(
            TransferMeta.Verifying(64_000_000L),
            item("d", TransferDirection.OUTGOING, TransferState.VERIFYING, 64_000_000L, 64_000_000L).meta,
        )
        assertEquals(
            TransferMeta.Delivered(18_200_000L, VerificationOutcome.VERIFIED),
            item(
                "e",
                TransferDirection.OUTGOING,
                TransferState.DONE,
                18_200_000L,
                18_200_000L,
            ).copy(verification = VerificationOutcome.VERIFIED).meta,
        )
        assertEquals(
            TransferMeta.Broken(190_000L, 412_000L),
            item("f", TransferDirection.OUTGOING, TransferState.FAILED, 412_000L, 190_000L).meta,
        )
        assertEquals(
            TransferMeta.Abandoned(600_000L, 1_800_000L),
            item("g", TransferDirection.INCOMING, TransferState.CANCELLED, 1_800_000L, 600_000L).meta,
        )
        assertEquals(
            TransferMeta.Passed(4_100_000L),
            item("h", TransferDirection.INCOMING, TransferState.SKIPPED, 4_100_000L, 0L).meta,
        )
    }

    @Test
    fun `one row can be acted on by id, and an id the session does not hold changes nothing`() {
        val paused = TransferRules.apply(session, "a", TransferRowAction.PAUSE)
        val untouched = TransferRules.apply(session, "not-a-row", TransferRowAction.CANCEL)

        assertEquals(TransferState.PAUSED, paused.item("a")!!.state)
        assertEquals(session, untouched)
    }

    private fun percentOf(row: TransferItem): String =
        app.morsecode.core.model.MorseFormatters.forDefaultLocale().percent(row.fraction)

    private fun item(
        id: String,
        direction: TransferDirection,
        state: TransferState,
        totalBytes: Long,
        transferredBytes: Long,
        speed: Long = if (state.isActive) 6_200_000L else 0L,
        pauseEligible: Boolean = true,
    ) = TransferItem(
        id = id,
        sessionId = "session-1",
        direction = direction,
        fileName = "$id.bin",
        kind = MediaKind.OTHER,
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        speedBytesPerSecond = speed,
        state = state,
        pauseEligible = pauseEligible,
    )
}
