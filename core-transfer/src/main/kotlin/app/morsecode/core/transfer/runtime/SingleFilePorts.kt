package app.morsecode.core.transfer.runtime

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferSnapshot

/**
 * The result of a synchronous operation owned by an inert runtime port.
 *
 * [Failed] means the operation's success fact definitely did not occur: for a
 * checkpoint it did not persist, for a commit it did not publish, and for a
 * send no frame was accepted. A failed write can still leave partial bytes.
 * [Cancelled] means cancellation was definitive and the success fact did not
 * occur. If cancellation may have raced an operation, return [Indeterminate].
 * [Indeterminate] means the success fact may have occurred; the runtime stops
 * without guessing. Unknown checkpoints or commits require reconciliation.
 * These ports have no production implementations in this slice.
 */
public sealed interface PortResult<out T> {
    public data class Success<T>(public val value: T) : PortResult<T>
    public data class Failed(public val error: TransferError) : PortResult<Nothing>
    public data object Cancelled : PortResult<Nothing>
    public data object Indeterminate : PortResult<Nothing>
}

/** A bounded, typed read. The reader does not retain the caller's buffer. */
public sealed interface RuntimeReadResult {
    public data class Data(public val count: Int) : RuntimeReadResult
    public data object EndOfSource : RuntimeReadResult
    public data object Cancelled : RuntimeReadResult
    public data class Failed(public val error: TransferError) : RuntimeReadResult
}

/**
 * One serial read owner. A real source bridge, if added in a later slice, must
 * delegate to core-storage's TypedSourceReader, not to a raw SourceHandle.
 * A positive request must return a positive count, EOF, cancellation, or a typed failure.
 */
public interface RuntimeTypedReader {
    public fun read(buffer: ByteArray, offset: Int, length: Int): RuntimeReadResult
    public fun close()
}

/**
 * A new, zero-offset source; restart and resume are intentionally unsupported.
 * Success transfers one reader to the runtime. If opening fails or its outcome
 * is indeterminate, the port owns cleanup of any reader it did not return.
 */
public interface SingleFileSource {
    public fun openFreshReader(): PortResult<RuntimeTypedReader>
}

/**
 * A new sequential destination. A successful write means both the write and the
 * required durability operation have succeeded. In-memory test implementations
 * model that fact; they do not establish any device/provider durability claim.
 * Verification must read the partial, not a cached digest of incoming bytes.
 */
public interface SingleFileDestination {
    public fun openFresh(): PortResult<Unit>
    public fun writeDurably(offset: Long, bytes: ByteArray): PortResult<Unit>
    public fun openVerification(): PortResult<RuntimeTypedReader>
    public fun commitVerified(): PortResult<Unit>
}

/**
 * Versioned persistence seam with explicit outcome certainty. Its compare-and-set
 * semantics mirror TransferSnapshotStore: [previous] is the actual last durable
 * row, and checkpointing can change that row without changing its revision.
 * A missing read returns Success(null), not a failed or unreadable row.
 */
public interface SingleFileSnapshotStore {
    public fun loadSnapshot(transferId: TransferId): PortResult<TransferSnapshot?>
    public fun saveTransition(previous: TransferSnapshot?, current: TransferSnapshot): PortResult<Unit>
    public fun saveCheckpoint(transferId: TransferId, offset: ConfirmedOffset): PortResult<Unit>
}

/** Encoded MSC1 frames are queued in order. A port must not call back reentrantly. */
public interface SingleFileTransport {
    public fun send(frame: ByteArray): PortResult<Unit>
}

/** An inert observer, not a production UI or queue. It must not call back reentrantly. */
public interface SingleFileObserver {
    public fun notify(state: TransferState, snapshotVersion: Long): PortResult<Unit>
    public fun schedule(): PortResult<Unit>
}

/** Exactly one direction and one local storage role. */
public sealed interface SingleFileRole {
    public data class Sender(public val source: SingleFileSource) : SingleFileRole
    public data class Receiver(public val destination: SingleFileDestination) : SingleFileRole
}
