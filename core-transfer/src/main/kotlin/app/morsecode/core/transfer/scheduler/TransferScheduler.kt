package app.morsecode.core.transfer.scheduler

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferSnapshot

/*
 * The deterministic queue scheduler.
 *
 * `plan()` is a pure function: it reads a list of snapshots and a set of limits
 * and returns an ordered list of decisions. It does not open a transport, open a
 * stream, read a clock or start a coroutine. Backoff timing is deliberately
 * absent — it belongs to an external clock/policy adapter, and leaving it out is
 * what lets the whole scheduler be tested without waiting.
 *
 * Cost: one pass to group the snapshots plus a sort of the eligible ones, so
 * O(n + e log e) for n snapshots and e eligible ones, with n ≤ the queue size.
 * There is no nested scan over recipients: per-recipient counts are built in the
 * same single pass.
 */

/** How many deliveries may be in flight at once. */
public data class ConcurrencyLimits(
    /** Across every session this device is running. */
    public val global: Int,
    /** Inside one session. */
    public val perSession: Int,
    /** Inside one broadcast recipient's delivery set. */
    public val perRecipient: Int,
) {
    init {
        require(global >= 0) { "global limit must not be negative, was $global" }
        require(perSession >= 0) { "perSession limit must not be negative, was $perSession" }
        require(perRecipient >= 0) { "perRecipient limit must not be negative, was $perRecipient" }
    }

    public companion object {
        /** A single-file-at-a-time queue, the default until settings say more. */
        public val SINGLE: ConcurrencyLimits = ConcurrencyLimits(
            global = 1,
            perSession = 1,
            perRecipient = 1,
        )

        public fun uniform(limit: Int): ConcurrencyLimits =
            ConcurrencyLimits(global = limit, perSession = limit, perRecipient = limit)
    }
}

/** One thing the queue engine should do next. */
public sealed class SchedulerDecision {
    public abstract val transferId: TransferId

    /** Begin negotiation for a queued delivery. */
    public data class Start(
        override val transferId: TransferId,
        public val queueOrder: Long,
    ) : SchedulerDecision()

    /** Resume a locally paused delivery from its confirmed offset. */
    public data class Resume(
        override val transferId: TransferId,
        public val fromOffset: Long,
    ) : SchedulerDecision()

    /** Offer a retryable failure another attempt. */
    public data class Retry(
        override val transferId: TransferId,
        public val retryCount: Int,
    ) : SchedulerDecision()
}

/** What the scheduler concluded about the session as a whole. */
public enum class SchedulerStatus(public val id: String) {
    /** At least one decision was produced. */
    WORK_PLANNED("work_planned"),

    /** Nothing in the queue can be started, paused items included. */
    NO_ELIGIBLE_WORK("no_eligible_work"),

    /** Work exists but every applicable limit is saturated. */
    NO_CAPACITY("no_capacity"),

    /** Work exists but the session cannot run it at all right now. */
    SESSION_BLOCKED("session_blocked"),
    ;

    public companion object {
        public fun fromId(id: String?): SchedulerStatus =
            entries.firstOrNull { it.id == id } ?: NO_ELIGIBLE_WORK
    }
}

/** Why a session produced no decisions. */
public enum class BlockReason(public val id: String) {
    /** The queue holds no snapshot for this session. */
    EMPTY_QUEUE("empty_queue"),

    /** The transport is down, so nothing may be started. */
    TRANSPORT_UNAVAILABLE("transport_unavailable"),

    /** The same transfer id appears twice; the queue is corrupt. */
    DUPLICATE_TRANSFER_ID("duplicate_transfer_id"),
    ;

    public companion object {
        public fun fromId(id: String?): BlockReason =
            entries.firstOrNull { it.id == id } ?: EMPTY_QUEUE
    }
}

/** Everything the scheduler needs, and nothing it could get from the outside world. */
public data class SchedulerInput(
    /** The session being planned; other sessions only count against [limits.global]. */
    public val sessionId: SessionId,
    /** The whole queue, which may span sessions. */
    public val snapshots: List<TransferSnapshot>,
    public val limits: ConcurrencyLimits,
    /** False when the transport is down: nothing may be started. */
    public val transportAvailable: Boolean = true,
    /** True when the user asked to resume locally paused items ("Resume all"). */
    public val resumePausedLocally: Boolean = false,
    /** Retry ceiling applied to FAILED_RETRYABLE items. */
    public val maxRetryCount: Int = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
)

/** What the scheduler decided, and why. */
public data class SchedulerPlan(
    public val decisions: List<SchedulerDecision>,
    public val status: SchedulerStatus,
    public val blockedReason: BlockReason?,
    /** Deliveries currently occupying a slot, across all sessions. */
    public val activeCount: Int,
    /** Slots left after this plan, for the caller's own bookkeeping. */
    public val capacityRemaining: Int,
) {
    public val hasWork: Boolean get() = decisions.isNotEmpty()
}

public object TransferScheduler {

    /**
     * Plans the next batch of work.
     *
     * Ordering is total and stable: eligible items are sorted by their
     * [TransferSnapshot.queueOrder] and then by transfer id, so two planners
     * given the same queue always produce the same list in the same order.
     */
    public fun plan(input: SchedulerInput): SchedulerPlan {
        // A duplicated transfer id means the queue itself is inconsistent; refuse
        // to guess which row is authoritative.
        val distinctIds = input.snapshots.map { it.transferId }.toSet()
        if (distinctIds.size != input.snapshots.size) {
            return SchedulerPlan(
                decisions = emptyList(),
                status = SchedulerStatus.SESSION_BLOCKED,
                blockedReason = BlockReason.DUPLICATE_TRANSFER_ID,
                activeCount = input.snapshots.count { it.state.isBusy },
                capacityRemaining = 0,
            )
        }

        val active = input.snapshots.filter { it.state.isBusy }
        var globalRemaining = input.limits.global - active.size

        val inSession = input.snapshots.filter { it.sessionId == input.sessionId }
        val activeInSession = inSession.count { it.state.isBusy }
        var sessionRemaining = input.limits.perSession - activeInSession

        // Per-recipient counts are built from *distinct* recipient ids, so a
        // duplicate recipient cannot inflate a limit by appearing twice.
        val activePerRecipient: Map<RecipientId, Int> = active
            .mapNotNull { it.recipientId }
            .groupingBy { it }
            .eachCount()
        val recipientRemaining = activePerRecipient.keys.associateWith { recipient ->
            input.limits.perRecipient - (activePerRecipient[recipient] ?: 0)
        }.toMutableMap()

        val eligible = inSession.filter { isEligible(it, input) }
            .sortedWith(compareBy<TransferSnapshot> { it.queueOrder }.thenBy { it.transferId.value })

        if (eligible.isEmpty()) {
            return SchedulerPlan(
                decisions = emptyList(),
                status = SchedulerStatus.NO_ELIGIBLE_WORK,
                blockedReason = BlockReason.EMPTY_QUEUE,
                activeCount = active.size,
                capacityRemaining = globalRemaining.coerceAtLeast(0),
            )
        }

        if (!input.transportAvailable) {
            return SchedulerPlan(
                decisions = emptyList(),
                status = SchedulerStatus.SESSION_BLOCKED,
                blockedReason = BlockReason.TRANSPORT_UNAVAILABLE,
                activeCount = active.size,
                capacityRemaining = 0,
            )
        }

        val decisions = mutableListOf<SchedulerDecision>()
        for (snapshot in eligible) {
            if (globalRemaining <= 0 || sessionRemaining <= 0) break
            val recipient = snapshot.recipientId
            val recipientCapacity = if (recipient == null) {
                Int.MAX_VALUE
            } else {
                recipientRemaining[recipient] ?: input.limits.perRecipient
            }
            // A saturated recipient skips just that item: one stalled or busy
            // recipient never blocks the others.
            if (recipientCapacity <= 0) continue

            decisions += when (snapshot.state) {
                TransferState.QUEUED -> SchedulerDecision.Start(
                    transferId = snapshot.transferId,
                    queueOrder = snapshot.queueOrder,
                )

                TransferState.PAUSED_LOCAL -> SchedulerDecision.Resume(
                    transferId = snapshot.transferId,
                    fromOffset = snapshot.confirmedBytes,
                )

                else -> SchedulerDecision.Retry(
                    transferId = snapshot.transferId,
                    retryCount = snapshot.retryCount,
                )
            }
            globalRemaining--
            sessionRemaining--
            if (recipient != null) {
                recipientRemaining[recipient] = recipientCapacity - 1
            }
        }

        return SchedulerPlan(
            decisions = decisions,
            status = if (decisions.isEmpty()) SchedulerStatus.NO_CAPACITY else SchedulerStatus.WORK_PLANNED,
            blockedReason = if (decisions.isEmpty()) null else null,
            activeCount = active.size,
            capacityRemaining = globalRemaining.coerceAtLeast(0),
        )
    }

    /** True when the scheduler should consider starting or resuming this item. */
    public fun isEligible(snapshot: TransferSnapshot, input: SchedulerInput): Boolean =
        when (snapshot.state) {
        TransferState.QUEUED -> true
        // A paused item only moves when the user asked for it, and it never
        // occupies a slot while it waits.
        TransferState.PAUSED_LOCAL -> input.resumePausedLocally
            TransferState.FAILED_RETRYABLE -> snapshot.retryCount < input.maxRetryCount
            else -> false
        }
}
