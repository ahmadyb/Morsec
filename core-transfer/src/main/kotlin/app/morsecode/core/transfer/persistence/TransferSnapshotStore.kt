package app.morsecode.core.transfer.persistence

import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.model.VerificationInfo

/*
 * Persistence contracts for the transfer core.
 *
 * These are interfaces, not an implementation, and they carry no Room annotation,
 * no Android class and no coroutine type. The `:core-data` Room adapter implements
 * them; nothing in `:core-transfer` can tell the difference,
 * which is what keeps the reducer testable on the JVM and keeps the engine
 * portable.
 *
 * Atomicity requirements are part of the contract, not advice:
 *
 *  * [saveTransition] atomically replaces one persisted snapshot revision and
 *    any durable effects actually included in that call. Do not fold a pending
 *    receiver checkpoint into the optimistic staging transition: its candidate
 *    intentionally differs from that transition's confirmedBytes.
 *  * After destination durability succeeds, first persist the pending snapshot
 *    (optimisticBytes = candidate, confirmedBytes unchanged), then call
 *    [saveCheckpoint] for that candidate. Dispatch ConfirmedOffsetPersisted
 *    only after checkpoint success. A definitive checkpoint failure dispatches
 *    ConfirmedOffsetPersistenceFailed; an unknown outcome requires a store read
 *    and reconciliation, never an ACK based on a guess.
 *  * [saveCheckpoint] is atomic, monotonic and idempotent. It must not advance
 *    beyond the persisted optimistic frontier or the descriptor length. After it
 *    succeeds, a restart reads the persisted confirmed offset even if no ACK was
 *    sent. Resume/retry reconciliation is outside this contract.
 *  * PersistSnapshot effects can name the previous or current revision in an
 *    accepted transition. Read the last durable snapshot; skip an identical
 *    revision, otherwise use it as `previous` in [saveTransition], in order.
 *    After saveCheckpoint, that durable previous row already contains the new
 *    confirmed offset and may differ from the reducer's in-memory `previous`.
 *    Never skip revisions in stores requiring sequential compare-and-set.
 *  * Restoring a snapshot must never infer confirmed bytes from optimistic
 *    bytes. The two values are persisted and validated separately.
 *
 * A receiver's VerificationSucceeded has only a commit effect. Before physical
 * publication, that effect must first persist the provisional verified revision;
 * if that prerequisite fails, do not attempt the commit. Once the revision is
 * durable, a crash may have occurred before, during or after publication, so
 * recovery must conservatively reconcile and must not delete or republish on a
 * guess. On a commit outcome, the provisional save is idempotent and is followed
 * by the completed or failed revision before any wire/UI effects. A post-commit
 * save or notification failure must never roll back a published destination.
 * These fields do not themselves prove whether physical publication happened.
 */

/** How long a finished delivery's row is kept. */
public enum class RetentionPolicy(public val id: String) {
    /** Keep the row so History can show it. */
    KEEP_FOR_HISTORY("keep_for_history"),

    /** Drop the row as soon as the delivery reaches a terminal state. */
    DISCARD_IMMEDIATELY("discard_immediately"),

    /** Keep the row until the session ends, then drop it. */
    DISCARD_AFTER_SESSION_END("discard_after_session_end"),
    ;

    public companion object {
        public fun fromId(id: String?): RetentionPolicy =
            entries.firstOrNull { it.id == id } ?: KEEP_FOR_HISTORY
    }
}

/** Everything persisted for one session, restored together. */
public data class SessionSnapshot(
    public val sessionId: SessionId,
    public val transfers: List<TransferSnapshot>,
    /** Version of the persistence format this bundle was read as. */
    public val version: Int = TransferSnapshotCodec.VERSION,
)

/** Result of a persistence operation. */
public sealed class StoreResult {
    public data object Ok : StoreResult()

    public data class Failed(public val error: TransferError) : StoreResult()
}

/**
 * A read distinguishes an absent row from one that exists but cannot be trusted.
 *
 * Returning `null` for both cases is unsafe for resumable storage: the caller
 * could mistake a corrupt checkpoint for a new transfer and touch a provider.
 */
public sealed class StoreReadResult<out T> {
    public data object Missing : StoreReadResult<Nothing>()

    public data class Found<T>(public val value: T) : StoreReadResult<T>()

    public data class Failed(public val error: TransferError) : StoreReadResult<Nothing>()
}

/**
 * The seam a durable adapter implements.
 *
 * Every method is synchronous from the caller's point of view: the engine asks
 * for a value and gets one. Making the interface asynchronous is the adapter's
 * business (a Room adapter will wrap it in a dispatcher), and keeping it out of
 * this signature is what lets the reducer's callers be tested without a
 * coroutine context.
 */
public interface TransferSnapshotStore {

    /** Loads a whole session, distinguishing absence from an invalid persisted row. */
    public fun loadSession(sessionId: SessionId): StoreReadResult<SessionSnapshot>

    /** Loads one delivery, distinguishing absence from an invalid persisted row. */
    public fun loadTransfer(transferId: TransferId): StoreReadResult<TransferSnapshot>

    /**
     * Persists one accepted transition atomically.
     *
     * [previous] is the last durably stored row, which can differ from the
     * reducer transition's previous value after [saveCheckpoint]. [effects]
     * contains only work actually committed with this replacement. In
     * particular, do not include a receiver's pending checkpoint effect in the
     * earlier optimistic-staging save.
     */
    public fun saveTransition(
        previous: TransferSnapshot?,
        current: TransferSnapshot,
        effects: List<TransferEffect>,
    ): StoreResult

    /**
     * Atomically persists a monotonic confirmed checkpoint no greater than the
     * stored optimistic frontier and descriptor length; idempotent for an
     * existing offset. A successful result survives restart before any ACK.
     */
    public fun saveCheckpoint(transferId: TransferId, confirmedOffset: ConfirmedOffset): StoreResult

    /** Persists the outcome of the full-file verification pass. */
    public fun saveVerificationResult(
        transferId: TransferId,
        result: VerificationInfo,
    ): StoreResult

    /** Deliveries that can still be resumed after a restart, or a typed refusal. */
    public fun listResumable(sessionId: SessionId): StoreReadResult<List<TransferSnapshot>>

    /** Drops a terminal delivery according to [policy]. */
    public fun removeTerminal(
        transferId: TransferId,
        policy: RetentionPolicy,
    ): StoreResult
}
