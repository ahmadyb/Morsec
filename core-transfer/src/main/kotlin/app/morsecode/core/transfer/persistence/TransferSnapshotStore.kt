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
 * The atomicity requirements below are part of the contract, not advice:
 *
 *  * [saveTransition] must apply the previous-to-current move and the durable
 *    parts of the accompanying effects in one transaction. A crash between
 *    writing the new state and writing its checkpoint must leave the *old* state
 *    on disk, never a new state over an old offset.
 *  * [saveCheckpoint] may be called once per acknowledged chunk, so it must be
 *    cheap and idempotent for a given offset.
 *  * Restoring a snapshot must never promote optimistic bytes to confirmed bytes.
 *    A conforming adapter persists and validates the two positions separately
 *    rather than inferring one from the other.
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
     * [effects] is passed so the adapter can fold the durable ones
     * (`PersistConfirmedOffset`, verification results) into the same transaction
     * as the state change.
     */
    public fun saveTransition(
        previous: TransferSnapshot?,
        current: TransferSnapshot,
        effects: List<TransferEffect>,
    ): StoreResult

    /** Persists just the confirmed checkpoint; idempotent for a given offset. */
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
