package app.morsecode.core.transfer.scheduler

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.Tf
import app.morsecode.core.transfer.plus
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduler is a pure function from (queue, limits) to an ordered list of
 * decisions. These tests are the proof: no clock is read, nothing sleeps, and
 * running the planner a hundred times on the same queue produces the same plan a
 * hundred times.
 */
class TransferSchedulerTest {

    private val scheduler = TransferScheduler

    // --- an empty or blocked queue -----------------------------------------------------

    @Test fun `an empty queue plans nothing`() {
        val plan = planFor(snapshots = emptyList())
        assertEquals(SchedulerStatus.NO_ELIGIBLE_WORK, plan.status)
        assertEquals(BlockReason.EMPTY_QUEUE, plan.blockedReason)
        assertFalse(plan.hasWork)
        assertEquals(0, plan.activeCount)
    }

    @Test fun `a queue with nothing eligible plans nothing`() {
        val plan = planFor(snapshots = listOf(Tf.completed()))
        assertEquals(SchedulerStatus.NO_ELIGIBLE_WORK, plan.status)
        assertTrue(plan.decisions.isEmpty())
    }

    @Test fun `a duplicated transfer id blocks the session rather than guessing`() {
        val plan = planFor(
            snapshots = listOf(
                Tf.queued(transferId = Tf.transferId, queueOrder = 0L),
                Tf.queued(transferId = Tf.transferId, queueOrder = 1L),
            ),
        )
        assertEquals(SchedulerStatus.SESSION_BLOCKED, plan.status)
        assertEquals(BlockReason.DUPLICATE_TRANSFER_ID, plan.blockedReason)
        assertTrue(plan.decisions.isEmpty())
    }

    @Test fun `a down transport blocks the session without losing the queue`() {
        val plan = planFor(
            snapshots = listOf(Tf.queued()),
            transportAvailable = false,
        )
        assertEquals(SchedulerStatus.SESSION_BLOCKED, plan.status)
        assertEquals(BlockReason.TRANSPORT_UNAVAILABLE, plan.blockedReason)
        assertTrue(plan.decisions.isEmpty())
        assertEquals(0, plan.capacityRemaining)
    }

    @Test fun `a down transport with no eligible work reports no eligible work`() {
        val plan = planFor(snapshots = listOf(Tf.completed()), transportAvailable = false)
        assertEquals(SchedulerStatus.NO_ELIGIBLE_WORK, plan.status)
    }

    // --- ordering ---------------------------------------------------------------------------

    @Test fun `a single queued delivery is started`() {
        val plan = planFor(snapshots = listOf(Tf.queued()))
        assertEquals(SchedulerStatus.WORK_PLANNED, plan.status)
        assertEquals(
            listOf(SchedulerDecision.Start(Tf.transferId, queueOrder = 0L)),
            plan.decisions,
        )
        assertTrue(plan.hasWork)
    }

    @Test fun `queued deliveries are started in queue order`() {
        val snapshots = listOf(
            Tf.queued(transferId = TransferId("tr-c"), queueOrder = 2L),
            Tf.queued(transferId = TransferId("tr-a"), queueOrder = 0L),
            Tf.queued(transferId = TransferId("tr-b"), queueOrder = 1L),
        )
        val plan = planFor(snapshots = snapshots, limits = ConcurrencyLimits.uniform(3))
        assertEquals(
            listOf(TransferId("tr-a"), TransferId("tr-b"), TransferId("tr-c")),
            plan.decisions.map { it.transferId },
        )
    }

    @Test fun `equal queue orders are broken by transfer id so the order is total`() {
        val snapshots = listOf(
            Tf.queued(transferId = TransferId("tr-b"), queueOrder = 0L),
            Tf.queued(transferId = TransferId("tr-a"), queueOrder = 0L),
        )
        val plan = planFor(snapshots = snapshots, limits = ConcurrencyLimits.uniform(2))
        assertEquals(
            listOf(TransferId("tr-a"), TransferId("tr-b")),
            plan.decisions.map { it.transferId },
        )
    }

    @Test fun `the order does not depend on the order the snapshots arrived in`() {
        val first = listOf(
            Tf.queued(transferId = TransferId("tr-b"), queueOrder = 1L),
            Tf.queued(transferId = TransferId("tr-a"), queueOrder = 0L),
        )
        val second = first.reversed()
        assertEquals(
            planFor(snapshots = first, limits = ConcurrencyLimits.uniform(2)).decisions,
            planFor(snapshots = second, limits = ConcurrencyLimits.uniform(2)).decisions,
        )
    }

    // --- capacity -----------------------------------------------------------------------------

    @Test fun `the global limit caps how many deliveries start`() {
        val snapshots = (0..3).map { Tf.queued(transferId = TransferId("tr-$it"), queueOrder = it.toLong()) }
        val plan = planFor(snapshots = snapshots, limits = ConcurrencyLimits.uniform(2))
        assertEquals(2, plan.decisions.size)
        assertEquals(0, plan.capacityRemaining)
    }

    @Test fun `busy deliveries in this session consume the session limit`() {
        val snapshots = listOf(
            Tf.sending(transferId = TransferId("tr-busy")),
            Tf.queued(transferId = TransferId("tr-waiting"), queueOrder = 0L),
        )
        val plan = planFor(snapshots = snapshots, limits = ConcurrencyLimits(global = 4, perSession = 1, perRecipient = 4))
        assertTrue(plan.decisions.isEmpty())
        assertEquals(SchedulerStatus.NO_CAPACITY, plan.status)
        assertEquals(1, plan.activeCount)
    }

    @Test fun `busy deliveries in another session consume only the global limit`() {
        val otherSession = SessionId("sess-other")
        val snapshots = listOf(
            Tf.sending(transferId = TransferId("tr-other"), sessionId = otherSession),
            Tf.queued(transferId = TransferId("tr-mine"), queueOrder = 0L),
        )
        val plan = planFor(
            snapshots = snapshots,
            limits = ConcurrencyLimits(global = 2, perSession = 1, perRecipient = 1),
        )
        assertEquals(
            listOf(SchedulerDecision.Start(TransferId("tr-mine"), queueOrder = 0L)),
            plan.decisions,
        )
    }

    @Test fun `a saturated global limit leaves nothing to plan`() {
        val snapshots = listOf(
            Tf.sending(transferId = TransferId("tr-1")),
            Tf.sending(transferId = TransferId("tr-2")),
            Tf.queued(transferId = TransferId("tr-3"), queueOrder = 0L),
        )
        val plan = planFor(snapshots = snapshots, limits = ConcurrencyLimits.uniform(2))
        assertEquals(SchedulerStatus.NO_CAPACITY, plan.status)
        assertEquals(2, plan.activeCount)
        assertEquals(0, plan.capacityRemaining)
    }

    @Test fun `a zero limit plans nothing at all`() {
        val plan = planFor(snapshots = listOf(Tf.queued()), limits = ConcurrencyLimits.uniform(0))
        assertEquals(SchedulerStatus.NO_CAPACITY, plan.status)
        assertTrue(plan.decisions.isEmpty())
    }

    // --- per-recipient isolation ---------------------------------------------------------------

    @Test fun `a busy recipient does not block a different recipient`() {
        val snapshots = listOf(
            Tf.sending(transferId = TransferId("tr-a1"), recipientId = Tf.recipientA),
            Tf.queued(transferId = TransferId("tr-b1"), recipientId = Tf.recipientB, queueOrder = 0L),
        )
        val plan = planFor(
            snapshots = snapshots,
            limits = ConcurrencyLimits(global = 4, perSession = 4, perRecipient = 1),
        )
        assertEquals(
            listOf(SchedulerDecision.Start(TransferId("tr-b1"), queueOrder = 0L)),
            plan.decisions,
        )
    }

    @Test fun `a recipient at its own limit is skipped but the others continue`() {
        val snapshots = listOf(
            Tf.sending(transferId = TransferId("tr-a1"), recipientId = Tf.recipientA),
            Tf.queued(transferId = TransferId("tr-a2"), recipientId = Tf.recipientA, queueOrder = 0L),
            Tf.queued(transferId = TransferId("tr-b1"), recipientId = Tf.recipientB, queueOrder = 1L),
            Tf.queued(transferId = TransferId("tr-c1"), recipientId = RecipientId("peer-c"), queueOrder = 2L),
        )
        val plan = planFor(
            snapshots = snapshots,
            limits = ConcurrencyLimits(global = 4, perSession = 4, perRecipient = 1),
        )
        assertEquals(
            listOf(TransferId("tr-b1"), TransferId("tr-c1")),
            plan.decisions.map { it.transferId },
        )
    }

    @Test fun `a delivery with no recipient never counts against a recipient limit`() {
        val snapshots = listOf(
            Tf.queued(transferId = TransferId("tr-1"), queueOrder = 0L),
            Tf.queued(transferId = TransferId("tr-2"), queueOrder = 1L),
        )
        val plan = planFor(
            snapshots = snapshots,
            limits = ConcurrencyLimits(global = 4, perSession = 4, perRecipient = 1),
        )
        assertEquals(2, plan.decisions.size)
    }

    @Test fun `two deliveries planned for the same recipient consume that recipient's slots`() {
        val snapshots = listOf(
            Tf.queued(transferId = TransferId("tr-a1"), recipientId = Tf.recipientA, queueOrder = 0L),
            Tf.queued(transferId = TransferId("tr-a2"), recipientId = Tf.recipientA, queueOrder = 1L),
            Tf.queued(transferId = TransferId("tr-a3"), recipientId = Tf.recipientA, queueOrder = 2L),
        )
        val plan = planFor(
            snapshots = snapshots,
            limits = ConcurrencyLimits(global = 8, perSession = 8, perRecipient = 2),
        )
        assertEquals(
            listOf(TransferId("tr-a1"), TransferId("tr-a2")),
            plan.decisions.map { it.transferId },
        )
    }

    // --- pausing and retrying -----------------------------------------------------------------------

    @Test fun `a locally paused delivery is left alone unless the user asks to resume`() {
        val snapshots = listOf(Tf.pausedLocally(confirmedBytes = 4_096L))
        val without = planFor(snapshots = snapshots)
        assertEquals(SchedulerStatus.NO_ELIGIBLE_WORK, without.status)
        assertTrue(without.decisions.isEmpty())
    }

    @Test fun `resume all starts a paused delivery from its confirmed offset`() {
        val snapshots = listOf(Tf.pausedLocally(confirmedBytes = 4_096L))
        val plan = planFor(snapshots = snapshots, resumePausedLocally = true)
        assertEquals(
            listOf(SchedulerDecision.Resume(Tf.transferId, fromOffset = 4_096L)),
            plan.decisions,
        )
    }

    @Test fun `a paused delivery with no bytes yet resumes at zero`() {
        val plan = planFor(
            snapshots = listOf(Tf.pausedLocally(confirmedBytes = 0L)),
            resumePausedLocally = true,
        )
        assertEquals(
            listOf(SchedulerDecision.Resume(Tf.transferId, fromOffset = 0L)),
            plan.decisions,
        )
    }

    @Test fun `a retryable failure below the ceiling is offered another attempt`() {
        val plan = planFor(snapshots = listOf(Tf.failedRetryable(retryCount = 1)))
        assertEquals(
            listOf(SchedulerDecision.Retry(Tf.transferId, retryCount = 1)),
            plan.decisions,
        )
    }

    @Test fun `a retryable failure at the ceiling is not offered another attempt`() {
        val plan = planFor(
            snapshots = listOf(Tf.failedRetryable(retryCount = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT)),
        )
        assertEquals(SchedulerStatus.NO_ELIGIBLE_WORK, plan.status)
        assertTrue(plan.decisions.isEmpty())
    }

    @Test fun `the retry ceiling can be raised by the caller`() {
        val plan = planFor(
            snapshots = listOf(Tf.failedRetryable(retryCount = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT)),
            maxRetryCount = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT + 1,
        )
        assertEquals(1, plan.decisions.size)
    }

    @Test fun `a final failure is never scheduled`() {
        val plan = planFor(snapshots = listOf(Tf.failedFinally()))
        assertEquals(SchedulerStatus.NO_ELIGIBLE_WORK, plan.status)
    }

    @Test fun `a remote pause is not a local pause and is never resumed by the user`() {
        val remotelyPaused = Tf.sending() + Tf.remotePaused()
        val snapshots = listOf(remotelyPaused)
        val plan = planFor(snapshots = snapshots, resumePausedLocally = true)
        assertTrue(plan.decisions.isEmpty())
    }

    // --- eligibility ---------------------------------------------------------------------------

    @Test fun `only queued, locally paused and retryable deliveries are eligible`() {
        val input = SchedulerInput(
            sessionId = Tf.sessionId,
            snapshots = emptyList(),
            limits = ConcurrencyLimits.uniform(4),
            resumePausedLocally = true,
        )
        val eligible = setOf(
            TransferState.QUEUED,
            TransferState.PAUSED_LOCAL,
            TransferState.FAILED_RETRYABLE,
        )
        for (state in TransferState.entries) {
            val snapshot = Tf.queued().copy(state = state)
            assertEquals(
                "state $state",
                state in eligible,
                scheduler.isEligible(snapshot, input),
            )
        }
    }

    @Test fun `a paused delivery is eligible only when the user asked to resume`() {
        val paused = Tf.pausedLocally()
        assertFalse(
            scheduler.isEligible(
                paused,
                SchedulerInput(Tf.sessionId, emptyList(), ConcurrencyLimits.uniform(1)),
            ),
        )
        assertTrue(
            scheduler.isEligible(
                paused,
                SchedulerInput(
                    Tf.sessionId, emptyList(), ConcurrencyLimits.uniform(1),
                    resumePausedLocally = true,
                ),
            ),
        )
    }

    // --- determinism ----------------------------------------------------------------------------

    @Test fun `the same queue produces the same plan every time`() {
        val snapshots = listOf(
            Tf.queued(transferId = TransferId("tr-a"), queueOrder = 0L),
            Tf.pausedLocally(transferId = TransferId("tr-b"), confirmedBytes = 4_096L),
            Tf.failedRetryable(transferId = TransferId("tr-c"), retryCount = 1),
            Tf.sending(transferId = TransferId("tr-d"), recipientId = Tf.recipientA),
            Tf.queued(transferId = TransferId("tr-e"), recipientId = Tf.recipientA, queueOrder = 1L),
        )
        val first = planFor(
            snapshots = snapshots,
            limits = ConcurrencyLimits(global = 4, perSession = 4, perRecipient = 1),
            resumePausedLocally = true,
        )
        repeat(50) {
            assertEquals(
                "the plan must not depend on anything but its input",
                first,
                planFor(
                    snapshots = snapshots,
                    limits = ConcurrencyLimits(global = 4, perSession = 4, perRecipient = 1),
                    resumePausedLocally = true,
                ),
            )
        }
    }

    @Test fun `planning does not mutate the queue it was given`() {
        val snapshots = mutableListOf(
            Tf.queued(transferId = TransferId("tr-a"), queueOrder = 0L),
            Tf.queued(transferId = TransferId("tr-b"), queueOrder = 1L),
        )
        val before = snapshots.toList()
        planFor(snapshots = snapshots, limits = ConcurrencyLimits.uniform(2))
        assertEquals(before, snapshots)
        for (snapshot in snapshots) {
            assertEquals(TransferState.QUEUED, snapshot.state)
        }
    }

    @Test fun `planning twice without applying anything plans the same work twice`() {
        val snapshots = listOf(Tf.queued())
        val first = planFor(snapshots = snapshots)
        val second = planFor(snapshots = snapshots)
        assertEquals(first.decisions, second.decisions)
    }

    // --- limits -----------------------------------------------------------------------------------

    @Test fun `a negative limit is refused`() {
        val builders: List<() -> ConcurrencyLimits> = listOf(
            { ConcurrencyLimits(global = -1, perSession = 1, perRecipient = 1) },
            { ConcurrencyLimits(global = 1, perSession = -1, perRecipient = 1) },
            { ConcurrencyLimits(global = 1, perSession = 1, perRecipient = -1) },
        )
        builders.forEachIndexed { index, build ->
            var threw = false
            try {
                build()
            } catch (expected: IllegalArgumentException) {
                threw = true
            }
            assertTrue("limit $index must be refused", threw)
        }
        assertEquals(4, ConcurrencyLimits.uniform(4).perRecipient)
    }

    @Test fun `every status and block reason round trips through its id`() {
        for (status in SchedulerStatus.entries) {
            assertEquals(status, SchedulerStatus.fromId(status.id))
        }
        for (reason in BlockReason.entries) {
            assertEquals(reason, BlockReason.fromId(reason.id))
        }
        assertEquals(SchedulerStatus.NO_ELIGIBLE_WORK, SchedulerStatus.fromId("nonsense"))
        assertEquals(BlockReason.EMPTY_QUEUE, BlockReason.fromId("nonsense"))
    }

    // --- helpers --------------------------------------------------------------------------------------

    private fun planFor(
        snapshots: List<TransferSnapshot>,
        limits: ConcurrencyLimits = ConcurrencyLimits.SINGLE,
        sessionId: SessionId = Tf.sessionId,
        transportAvailable: Boolean = true,
        resumePausedLocally: Boolean = false,
        maxRetryCount: Int = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
    ): SchedulerPlan = scheduler.plan(
        SchedulerInput(
            sessionId = sessionId,
            snapshots = snapshots,
            limits = limits,
            transportAvailable = transportAvailable,
            resumePausedLocally = resumePausedLocally,
            maxRetryCount = maxRetryCount,
        ),
    )
}
