package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.Closeable

/*
 * The sequential-write and lifecycle gate over one destination partial.
 *
 * WHY THIS EXISTS ALONGSIDE `PartialSink`
 *   A sink can write at an arbitrary offset, and `CheckpointCoordinator` already refuses a chunk whose
 *   offset is not the persisted frontier. Neither of them knows the *lifecycle*: whether bytes may
 *   still be written after a verification pass, whether a commit already happened, whether a failed
 *   write left the physical file longer than the bytes anyone acknowledged. This class is that gate and
 *   nothing else. It composes the existing pieces rather than replacing them -- the actual write, flush
 *   and truncate go through [PartialSink], the digest through [DestinationVerifier], and the confirmed
 *   frontier through [CheckpointSink].
 *
 * THE INVARIANT
 *   Payload v1 is sequential, so every accepted write satisfies
 *
 *       writeOffset == physical partial length == expected frontier
 *
 *   before the write, and the frontier advances by exactly the accepted length. A hole, an overlap, a
 *   rewind, an empty write, a write past the declared size and a write whose addition would overflow
 *   are each refused by name. A failed or short write never advances anything: the same rejected
 *   position is reported until the caller either retries at the unchanged frontier or truncates
 *   explicitly through [truncateTo].
 *
 * WHY `truncateTo` IS THE ONLY WAY BACK
 *   Extra bytes on the partial are ambiguous: they may be a short write, a crash during flush, or a
 *   previous process's optimism. Silently overwriting them would make the physical file and the
 *   checkpoint disagree in a way nothing records. The caller must say which offset it wants, and the
 *   confirmed frontier is a floor it may not cut below.
 *
 * WHO PROMOTES BYTES
 *   This adapter never decides that the peer acknowledged anything. [writeAt] advances only the
 *   optimistic frontier; [acknowledge] is the runtime's separate, monotonic promotion, and it is the
 *   only call that reaches the checkpoint.
 *
 * WHAT IS DELIBERATELY ABSENT
 *   No Android type, no `Uri`, no path, no `File`, no provider exception, no logging and no buffer
 *   retention. A caller's chunk buffer is read and dropped; the class holds no reference to it after
 *   the call returns, which a test asserts reflectively.
 *
 * STATUS
 *   Inert. Nothing in production constructs this type. It is not a runtime, it does not interpret a
 *   transfer frame and it does not own a socket. See doc/transfer-storage-bridge.md.
 */

/** The lifecycle of one destination partial, from the first accepted byte to its terminal state. */
public enum class DestinationSessionState(public val id: String) {
    /** Accepts writes, truncation and verification. */
    OPEN("open"),

    /** A digest pass succeeded; writes are refused because they would invalidate it. */
    VERIFIED("verified"),

    /** Publication succeeded. Terminal. */
    COMMITTED("committed"),

    /** The partial was abandoned and its cleanup ran. Terminal. */
    ABORTED("aborted"),

    /** The caller closed the session. Terminal. */
    CLOSED("closed"),
    ;

    public val isTerminal: Boolean get() = this == COMMITTED || this == ABORTED || this == CLOSED

    public val acceptsWrites: Boolean get() = this == OPEN

    public companion object {
        public fun fromId(id: String?): DestinationSessionState =
            entries.firstOrNull { it.id == id } ?: OPEN
    }
}

/**
 * Why one write was refused before it reached the sink.
 *
 * Every value names a distinct physical situation, so a caller can tell "retry at the frontier" from
 * "this partial must be truncated first" from "this session can no longer write at all".
 */
public enum class SequentialRejection(public val id: String) {
    /** A negative offset or length. */
    NEGATIVE("negative"),

    /** `offset + length` would not fit in a `Long`. */
    OFFSET_OVERFLOW("offset_overflow"),

    /** A zero-length write. */
    EMPTY("empty"),

    /** A length larger than the protocol's maximum payload. */
    TOO_LARGE("too_large"),

    /** The source range is outside the caller's buffer. */
    BUFFER_BOUNDS("buffer_bounds"),

    /** The write would end past the declared size. */
    PAST_DECLARED_SIZE("past_declared_size"),

    /** The offset is beyond the expected frontier: bytes would be missing before it. */
    HOLE("hole"),

    /** The offset is behind the expected frontier: bytes would be overwritten. */
    REWIND("rewind"),

    /**
     * The physical partial is longer than the expected frontier, so any write at the frontier would
     * land inside bytes that already exist. Requires an explicit [DestinationSession.truncateTo].
     */
    OVERLAP("overlap"),

    /** The physical length and the session's frontier disagree for another reason. */
    NOT_AT_PHYSICAL_FRONTIER("not_at_physical_frontier"),

    /** A digest pass succeeded; more bytes would invalidate it. */
    AFTER_VERIFICATION("after_verification"),

    /** Publication already succeeded. */
    AFTER_COMMIT("after_commit"),

    /** The partial was abandoned. */
    AFTER_ABORT("after_abort"),

    /** The session was closed. */
    AFTER_CLOSE("after_close"),
    ;

    public companion object {
        public fun fromId(id: String?): SequentialRejection =
            entries.firstOrNull { it.id == id } ?: NOT_AT_PHYSICAL_FRONTIER
    }
}

/** The outcome of one write. */
public sealed interface DestinationWrite {
    /** The bytes are in the partial and flushed; [frontier] is now the expected position. */
    public data class Written(
        public val frontier: Long,
        public val durability: FlushDurability,
    ) : DestinationWrite

    /** Refused before reaching the sink. Nothing was written and the frontier did not move. */
    public data class Rejected(
        public val reason: SequentialRejection,
        public val frontier: Long,
    ) : DestinationWrite

    /** The sink failed. The frontier did not move, whatever the physical file now contains. */
    public data class Failed(
        public val error: TransferStorageError,
        public val frontier: Long,
    ) : DestinationWrite
}

/** The outcome of one verification pass. */
public sealed interface SessionVerification {
    /** The partial may be committed. [matchedExpected] is false when no digest was supplied. */
    public data class Verified(
        public val digest: Sha256Digest,
        public val bytes: Long,
        public val matchedExpected: Boolean,
    ) : SessionVerification

    /** The partial does not match; it stays open and unverified. */
    public data class Mismatched(
        public val expected: Sha256Digest,
        public val observed: Sha256Digest,
        public val bytes: Long,
    ) : SessionVerification

    /** The session was not in a state that can verify, or has no verification source. */
    public data class Refused(public val error: TransferStorageError) : SessionVerification

    /** The digest pass itself failed. */
    public data class Failed(public val error: TransferStorageError) : SessionVerification
}

/** Publication of a verified partial into its final destination. */
public fun interface DestinationPublisher {
    /** Publishes the verified image. Implementations must be idempotent at the strategy level. */
    public fun publish(verifiedDigest: Sha256Digest): PublicationOutcome
}

/** The result of one publication attempt. */
public sealed interface PublicationOutcome {
    public data object Published : PublicationOutcome
    public data class Failed(public val error: TransferStorageError) : PublicationOutcome
}

/** The result of one commit request. */
public sealed interface CommitResult {
    public data object Committed : CommitResult

    /** The partial was already committed; the duplicate is refused explicitly. */
    public data object Duplicate : CommitResult

    public data class Refused(public val error: TransferStorageError) : CommitResult
}

/** The result of one abort request. */
public sealed interface AbortResult {
    public data object Aborted : AbortResult

    /** Abort is idempotent: a second call reports the state rather than repeating cleanup. */
    public data object AlreadyAborted : AbortResult

    public data class Refused(public val error: TransferStorageError) : AbortResult
}

/** The result of opening a session against an existing partial. */
public sealed interface DestinationOpen {
    /**
     * The partial may be written at [frontier].
     *
     * [requiresTruncation] is true when the physical partial is longer than the confirmed bytes; the
     * session refuses writes until the caller truncates explicitly.
     */
    public data class Opened(
        public val session: DestinationSession,
        public val frontier: Long,
        public val requiresTruncation: Boolean,
    ) : DestinationOpen

    public data class Refused(public val error: TransferStorageError) : DestinationOpen
}

/**
 * One sequential destination partial.
 *
 * Not thread safe by design: the future runtime owns one session per transfer and drives it from one
 * coroutine. Making it internally synchronized would hide ordering bugs the protocol depends on.
 */
public class DestinationSession private constructor(
    public val transferId: TransferId,
    public val identity: PartialIdentity,
    private val sink: PartialSink,
    private val declaredSizeBytes: Long?,
    private val checkpointSink: CheckpointSink?,
    private val verificationSource: VerificationSource?,
    private val publisher: DestinationPublisher?,
    private val abortAction: (() -> TransferStorageError?)?,
    private val resources: List<Closeable>,
    initialConfirmedBytes: Long,
) : Closeable {

    private var state: DestinationSessionState = DestinationSessionState.OPEN

    /** Bytes written and flushed since the last truncation. Never promoted by this class. */
    private var frontier: Long = initialConfirmedBytes

    /** Bytes a runtime acknowledged. The truncation floor. */
    private var confirmed: Long = initialConfirmedBytes

    private var verifiedDigest: Sha256Digest? = null
    private var verifiedBytes: Long = -1L
    private var closeCountValue: Int = 0

    public val currentState: DestinationSessionState get() = state

    /** The optimistic frontier: where the next accepted write must land. */
    public val expectedFrontier: Long get() = frontier

    /** The acknowledged frontier. Monotonic; advanced only by [acknowledge]. */
    public val confirmedFrontier: Long get() = confirmed

    /** How many times [close] ran. Always 0 or 1. */
    public val closeCount: Int get() = closeCountValue

    /** The digest a verification pass accepted, or null. */
    public val verifiedDigestOrNull: Sha256Digest? get() = verifiedDigest

    // --- write -----------------------------------------------------------------------------------

    /**
     * Writes one chunk at [offset].
     *
     * On success the frontier advances by exactly [length]. On any refusal or failure it does not
     * move, even when the sink already wrote part of the buffer.
     */
    public fun writeAt(
        offset: Long,
        buffer: ByteArray,
        dataOffset: Int = 0,
        length: Int = buffer.size,
    ): DestinationWrite {
        lifecycleRejection()?.let { return DestinationWrite.Rejected(it, frontier) }
        rejectionFor(offset, buffer, dataOffset, length)?.let {
            return DestinationWrite.Rejected(it, frontier)
        }
        val outcome = sink.writeAt(offset, buffer, dataOffset, length)
        if (outcome is WriteOutcome.Failed) {
            return DestinationWrite.Failed(outcome.error, frontier)
        }
        val written = outcome as WriteOutcome.Written
        if (written.bytes != length) {
            // A short write is a failure, not progress: the frontier cannot describe a half chunk.
            return DestinationWrite.Failed(
                TransferStorageError.Io(
                    operation = "write",
                    diagnostic = "wrote ${written.bytes} of $length bytes",
                ),
                frontier,
            )
        }
        val durability = sink.flush()
        if (durability is FlushDurability.FlushFailed) {
            // The bytes may or may not be durable. Either way nothing is acknowledged.
            return DestinationWrite.Failed(durability.error, frontier)
        }
        frontier = offset + length.toLong()
        return DestinationWrite.Written(frontier, durability)
    }

    /**
     * Promotes [offset] bytes to confirmed.
     *
     * Called by the runtime after the peer acknowledges the range. Must be between the current
     * confirmed frontier and the optimistic frontier; the checkpoint is written first, so a failure
     * leaves the in-memory floor where it was.
     */
    public fun acknowledge(offset: Long): TransferStorageError? {
        if (offset < confirmed || offset > frontier) {
            return TransferStorageError.StateConflict(
                reason = "acknowledgement_out_of_order",
                diagnostic = "acknowledge $offset with confirmed $confirmed and frontier $frontier",
            )
        }
        val checkpoint = checkpointSink ?: run {
            confirmed = offset
            return null
        }
        val durability = FlushDurability.DurableFlushSupported
        val persisted = checkpoint.persist(transferId, offset, durability)
        if (persisted is PersistOutcome.Failed) return persisted.error
        confirmed = offset
        return null
    }

    // --- truncation ------------------------------------------------------------------------------

    /**
     * Cuts the partial to [offset] at the caller's explicit request.
     *
     * Refused beyond the optimistic frontier and below the confirmed frontier: neither direction is a
     * reconciliation, one invents bytes and the other discards acknowledged ones.
     */
    public fun truncateTo(offset: Long): TruncateOutcome {
        if (state != DestinationSessionState.OPEN) {
            return TruncateOutcome.Failed(lifecycleError())
        }
        if (offset < confirmed) {
            return TruncateOutcome.Failed(
                TransferStorageError.StateConflict(
                    reason = "truncate_below_confirmed",
                    diagnostic = "truncate $offset below confirmed $confirmed",
                ),
            )
        }
        if (offset > frontier) {
            return TruncateOutcome.Failed(
                TransferStorageError.StateConflict(
                    reason = "truncate_beyond_frontier",
                    diagnostic = "truncate $offset beyond frontier $frontier",
                ),
            )
        }
        val outcome = sink.truncateTo(offset)
        if (outcome is TruncateOutcome.Truncated) frontier = offset
        return outcome
    }

    // --- verification ----------------------------------------------------------------------------

    /**
     * Runs the full-file digest over everything written so far.
     *
     * Only [DestinationSessionState.OPEN] sessions verify, and only a successful pass moves the state
     * to [DestinationSessionState.VERIFIED]. A mismatch leaves the session open: nothing was verified,
     * so the caller may truncate and rewrite.
     */
    public fun verify(
        expected: Sha256Digest?,
        buffer: ByteArray = ByteArray(DestinationVerifier.VERIFY_BUFFER_BYTES),
        isCancelled: () -> Boolean = { false },
    ): SessionVerification {
        if (state != DestinationSessionState.OPEN) {
            return SessionVerification.Refused(lifecycleError())
        }
        val source = verificationSource ?: return SessionVerification.Refused(
            TransferStorageError.Unsupported("verification"),
        )
        return when (val result = DestinationVerifier.verify(source, frontier, expected, buffer, isCancelled = isCancelled)) {
            is VerifyResult.Matched -> {
                verifiedDigest = result.digest
                verifiedBytes = result.bytes
                state = DestinationSessionState.VERIFIED
                SessionVerification.Verified(result.digest, result.bytes, matchedExpected = true)
            }

            is VerifyResult.VerifiedWithoutExpected -> {
                verifiedDigest = result.digest
                verifiedBytes = result.bytes
                state = DestinationSessionState.VERIFIED
                SessionVerification.Verified(result.digest, result.bytes, matchedExpected = false)
            }

            is VerifyResult.Mismatched -> SessionVerification.Mismatched(
                expected = result.expected,
                observed = result.observed,
                bytes = result.bytes,
            )

            is VerifyResult.Failed -> SessionVerification.Failed(result.error)
        }
    }

    // --- commit and abort ------------------------------------------------------------------------

    /**
     * Publishes a verified partial.
     *
     * A second commit is refused with [CommitResult.Duplicate] rather than repeating publication, so a
     * caller that lost track cannot copy a file twice.
     */
    public fun commit(): CommitResult {
        when (state) {
            DestinationSessionState.COMMITTED -> return CommitResult.Duplicate
            DestinationSessionState.OPEN -> return CommitResult.Refused(
                TransferStorageError.StateConflict(reason = "not_verified"),
            )
            DestinationSessionState.VERIFIED -> Unit
            DestinationSessionState.ABORTED,
            DestinationSessionState.CLOSED,
            -> return CommitResult.Refused(lifecycleError())
        }
        val digest = verifiedDigest ?: return CommitResult.Refused(
            TransferStorageError.StateConflict(reason = "not_verified"),
        )
        if (frontier != verifiedBytes || sink.length() != verifiedBytes) {
            // Defensive: a write cannot reach here through the public API, but a partial replaced on
            // disk between verification and commit would otherwise be published unverified.
            return CommitResult.Refused(
                TransferStorageError.StateConflict(reason = "partial_changed_after_verification"),
            )
        }
        val target = publisher ?: return CommitResult.Refused(
            TransferStorageError.Unsupported("publication"),
        )
        return when (val outcome = target.publish(digest)) {
            PublicationOutcome.Published -> {
                state = DestinationSessionState.COMMITTED
                CommitResult.Committed
            }
            is PublicationOutcome.Failed -> CommitResult.Refused(outcome.error)
        }
    }

    /**
     * Abandons the partial and runs its cleanup.
     *
     * Idempotent: a second call reports [AbortResult.AlreadyAborted] and does not repeat cleanup. A
     * committed partial cannot be aborted.
     */
    public fun abort(): AbortResult {
        when (state) {
            DestinationSessionState.ABORTED -> return AbortResult.AlreadyAborted
            DestinationSessionState.COMMITTED -> return AbortResult.Refused(
                TransferStorageError.StateConflict(reason = "already_committed"),
            )
            DestinationSessionState.CLOSED -> return AbortResult.Refused(lifecycleError())
            DestinationSessionState.OPEN,
            DestinationSessionState.VERIFIED,
            -> Unit
        }
        val failure = abortAction?.invoke()
        if (failure != null) return AbortResult.Refused(failure)
        state = DestinationSessionState.ABORTED
        return AbortResult.Aborted
    }

    // --- lifecycle -------------------------------------------------------------------------------

    /**
     * Closes the sink and every resource the session was handed, exactly once.
     *
     * Closing does not delete anything: abort is a separate, explicit decision. A closed session
     * accepts no write, truncation, verification, commit or abort.
     */
    override fun close() {
        if (closeCountValue > 0) return
        closeCountValue = 1
        if (state != DestinationSessionState.COMMITTED && state != DestinationSessionState.ABORTED) {
            state = DestinationSessionState.CLOSED
        }
        val candidates = resources.ifEmpty { listOfNotNull(sink as? Closeable) }
        for (resource in candidates) {
            try {
                resource.close()
            } catch (_: RuntimeException) {
                // Teardown is best effort and never rethrown; the caller cannot act on a close failure.
            }
        }
    }

    override fun toString(): String =
        "DestinationSession(state=${state.id}, frontier=$frontier, confirmed=$confirmed, " +
            "closeCount=$closeCountValue, [redacted])"

    // --- internal ----------------------------------------------------------------------------------

    private fun lifecycleRejection(): SequentialRejection? = when (state) {
        DestinationSessionState.OPEN -> null
        DestinationSessionState.VERIFIED -> SequentialRejection.AFTER_VERIFICATION
        DestinationSessionState.COMMITTED -> SequentialRejection.AFTER_COMMIT
        DestinationSessionState.ABORTED -> SequentialRejection.AFTER_ABORT
        DestinationSessionState.CLOSED -> SequentialRejection.AFTER_CLOSE
    }

    private fun lifecycleError(): TransferStorageError = when (state) {
        DestinationSessionState.OPEN -> TransferStorageError.StateConflict(reason = "not_open")
        DestinationSessionState.VERIFIED -> TransferStorageError.StateConflict(reason = "already_verified")
        DestinationSessionState.COMMITTED -> TransferStorageError.StateConflict(reason = "already_committed")
        DestinationSessionState.ABORTED -> TransferStorageError.StateConflict(reason = "aborted")
        DestinationSessionState.CLOSED -> TransferStorageError.StateConflict(reason = "closed")
    }

    private fun rejectionFor(
        offset: Long,
        buffer: ByteArray,
        dataOffset: Int,
        length: Int,
    ): SequentialRejection? {
        if (offset < 0L || dataOffset < 0 || length < 0) return SequentialRejection.NEGATIVE
        if (length == 0) return SequentialRejection.EMPTY
        if (length > ProtocolLimits.MAX_PAYLOAD_BYTES) return SequentialRejection.TOO_LARGE
        if (dataOffset > buffer.size - length) return SequentialRejection.BUFFER_BOUNDS
        if (offset > Long.MAX_VALUE - length.toLong()) return SequentialRejection.OFFSET_OVERFLOW
        declaredSizeBytes?.let { declared ->
            if (offset > declared - length.toLong()) return SequentialRejection.PAST_DECLARED_SIZE
        }

        val physical = try {
            sink.length()
        } catch (_: RuntimeException) {
            // A sink that cannot report its length cannot prove the invariant; treat it as a mismatch.
            return SequentialRejection.NOT_AT_PHYSICAL_FRONTIER
        }
        if (physical < 0L) return SequentialRejection.NOT_AT_PHYSICAL_FRONTIER
        if (physical > frontier) {
            // Bytes exist past the frontier: a short write, a crash during flush or stale optimism.
            return SequentialRejection.OVERLAP
        }
        if (physical != frontier) return SequentialRejection.NOT_AT_PHYSICAL_FRONTIER
        return when {
            offset > frontier -> SequentialRejection.HOLE
            offset < frontier -> SequentialRejection.REWIND
            else -> null
        }
    }

    public companion object {

        /**
         * Opens a session over an existing partial.
         *
         * [initialConfirmedBytes] is the checkpointed frontier the caller recovered; when a
         * [CheckpointSink] is supplied its value wins. The physical partial decides the rest: equal
         * length resumes, longer length requires an explicit truncation, shorter length is refused
         * because acknowledged bytes are gone.
         */
        public fun open(
            transferId: TransferId,
            identity: PartialIdentity,
            sink: PartialSink,
            declaredSizeBytes: Long? = null,
            checkpointSink: CheckpointSink? = null,
            verificationSource: VerificationSource? = null,
            publisher: DestinationPublisher? = null,
            abortAction: (() -> TransferStorageError?)? = null,
            resources: List<Closeable> = emptyList(),
            initialConfirmedBytes: Long = 0L,
        ): DestinationOpen {
            if (declaredSizeBytes != null && declaredSizeBytes < 0L) {
                return DestinationOpen.Refused(
                    TransferStorageError.StateConflict(
                        reason = "declared_size_negative",
                        diagnostic = "declared $declaredSizeBytes",
                    ),
                )
            }
            if (initialConfirmedBytes < 0L) {
                return DestinationOpen.Refused(
                    TransferStorageError.StateConflict(
                        reason = "confirmed_negative",
                        diagnostic = "confirmed $initialConfirmedBytes",
                    ),
                )
            }
            val confirmed = checkpointSink?.confirmedFrontier(transferId) ?: initialConfirmedBytes
            if (confirmed < 0L) {
                return DestinationOpen.Refused(
                    TransferStorageError.StateConflict(reason = "confirmed_negative"),
                )
            }
            if (declaredSizeBytes != null && confirmed > declaredSizeBytes) {
                return DestinationOpen.Refused(
                    TransferStorageError.StateConflict(reason = "confirmed_beyond_declared_size"),
                )
            }
            val physical = try {
                sink.length()
            } catch (_: RuntimeException) {
                return DestinationOpen.Refused(
                    TransferStorageError.Io(operation = "measure the partial file"),
                )
            }
            if (physical < 0L) {
                return DestinationOpen.Refused(
                    TransferStorageError.StateConflict(reason = "physical_length_negative"),
                )
            }
            if (declaredSizeBytes != null && physical > declaredSizeBytes) {
                return DestinationOpen.Refused(
                    TransferStorageError.StateConflict(reason = "partial_beyond_declared_size"),
                )
            }
            if (physical < confirmed) {
                // The bytes that were acknowledged are not on disk. Silently restarting would resume
                // from a position the peer never agreed to.
                return DestinationOpen.Refused(
                    TransferStorageError.StateConflict(
                        reason = "partial_shorter_than_confirmed",
                        diagnostic = "physical $physical below confirmed $confirmed",
                    ),
                )
            }
            return DestinationOpen.Opened(
                session = DestinationSession(
                    transferId = transferId,
                    identity = identity,
                    sink = sink,
                    declaredSizeBytes = declaredSizeBytes,
                    checkpointSink = checkpointSink,
                    verificationSource = verificationSource,
                    publisher = publisher,
                    abortAction = abortAction,
                    resources = resources,
                    initialConfirmedBytes = confirmed,
                ),
                frontier = confirmed,
                requiresTruncation = physical > confirmed,
            )
        }
    }
}
