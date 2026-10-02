package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.model.VerificationOutcome

/*
 * Typed rejections.
 *
 * An illegal transition is data, not an exception: the reducer returns one, the
 * adapter logs it, and the UI can explain it. Nothing anywhere parses an
 * exception message to decide what to do — the sealed hierarchy below is the
 * whole vocabulary, and every member carries the fields a caller needs to render
 * an honest message.
 */

/**
 * Why a command or event was refused.
 *
 * [message] never contains a private path, a session secret, file contents or a
 * stack trace; where a value is echoed it is an identifier or a byte count.
 */
public sealed class Rejection {
    /** Stable, machine-readable code, safe to persist and to switch on. */
    public abstract val code: String

    /** Human-readable explanation, safe to show in the UI. */
    public abstract val message: String

    /**
     * The classified form of this rejection.
     *
     * A rejection is an in-process value; [error] is the same refusal in the
     * vocabulary that crosses a boundary — so an adapter can log it, persist it
     * or explain it without inventing its own mapping from code to category. The
     * detail is re-redacted on the way through, which is what makes invariant 28
     * ("every rejection carries a typed, bounded, redacted error") true rather
     * than merely intended.
     */
    public open val error: TransferError
        get() = TransferError.restore(
            code = code,
            detail = message,
            retryable = false,
            origin = ErrorOrigin.LOCAL,
            category = ErrorCategory.LOCAL_ACTION,
        )

    final override fun toString(): String = "$code: $message"

    /** Shorthand for the variants that describe a peer's malformed input. */
    protected fun protocolError(retryable: Boolean = false): TransferError =
        TransferError.restore(
            code = code,
            detail = message,
            retryable = retryable,
            origin = ErrorOrigin.REMOTE,
            category = ErrorCategory.PROTOCOL,
        )

    /** The current state cannot produce the requested state. */
    public data class IllegalStateTransition(
        public val from: TransferState,
        public val to: TransferState?,
        public val attempted: String,
    ) : Rejection() {
        override val code: String = "illegal_state_transition"
        override val message: String =
            "$attempted cannot move a transfer from $from${if (to == null) "" else " to $to"}"
    }

    /** The event or command belongs to a different session. */
    public data class WrongSession(
        public val expected: String,
        public val actual: String,
    ) : Rejection() {
        override val code: String = "wrong_session"
        override val message: String = "targets session $actual but this transfer is in session $expected"
        override val error: TransferError = protocolError()
    }

    /** The event or command belongs to a different transfer. */
    public data class WrongTransfer(
        public val expected: String,
        public val actual: String,
    ) : Rejection() {
        override val code: String = "wrong_transfer"
        override val message: String = "targets transfer $actual but this is transfer $expected"
        override val error: TransferError = protocolError()
    }

    /** The event or command belongs to a different broadcast recipient. */
    public data class WrongRecipient(
        public val expected: String?,
        public val actual: String?,
    ) : Rejection() {
        override val code: String = "wrong_recipient"
        override val message: String =
            "targets recipient ${actual ?: "none"} but this delivery belongs to ${expected ?: "none"}"
        override val error: TransferError = protocolError()
    }

    /** A progress report would move the confirmed watermark backwards. */
    public data class OffsetRegression(
        public val current: Long,
        public val proposed: Long,
    ) : Rejection() {
        override val code: String = "offset_regression"
        override val message: String =
            "confirmed offset $proposed is behind the recorded $current; progress never decreases"
        override val error: TransferError = protocolError()
    }

    /** A progress report claims more bytes than the file contains. */
    public data class OffsetBeyondTotal(
        public val offset: Long,
        public val total: Long,
    ) : Rejection() {
        override val code: String = "offset_beyond_total"
        override val message: String = "offset $offset is past the file size $total"
        override val error: TransferError = protocolError()
    }

    /** A chunk carried a sequence number that does not match its offset. */
    public data class UnexpectedSequence(
        public val expected: Long,
        public val actual: Long,
    ) : Rejection() {
        override val code: String = "unexpected_sequence"
        override val message: String = "expected sequence $expected but received $actual"
        override val error: TransferError = protocolError(retryable = true)
    }

    /** A chunk arrived at an offset the receiver cannot append. */
    public data class UnexpectedOffset(
        public val expected: Long,
        public val actual: Long,
    ) : Rejection() {
        override val code: String = "unexpected_offset"
        override val message: String = "expected a chunk at offset $expected but received one at $actual"
        override val error: TransferError = protocolError(retryable = true)
    }

    /** A chunk straddled the confirmed watermark instead of starting at it. */
    public data class OverlappingChunk(
        public val offset: Long,
        public val length: Int,
        public val confirmedBytes: Long,
    ) : Rejection() {
        override val code: String = "overlapping_chunk"
        override val message: String =
            "chunk $offset..${offset + length} overlaps the confirmed $confirmedBytes bytes"
        override val error: TransferError = protocolError(retryable = true)
    }

    /** A chunk declared a length outside the negotiated chunk size. */
    public data class InvalidChunkLength(
        public val length: Int,
        public val max: Int,
    ) : Rejection() {
        override val code: String = "invalid_chunk_length"
        override val message: String = "chunk length $length is outside 1..$max"
        override val error: TransferError = protocolError()
    }

    /**
     * Two refusals are deliberately absent from this vocabulary, because
     * neither can reach the reducer:
     *
     * - **A duplicate delivery.** The reducer never sees the queue, so it
     *   cannot know a file is already enqueued; that is
     *   `TransferScheduler`'s `BlockReason.DUPLICATE_TRANSFER_ID` (a corrupt
     *   queue) and `BroadcastAggregator`'s per-recipient distinctness.
     * - **An unsupported protocol version.** `ProtocolVersion` refuses to
     *   exist outside `PROTOCOL_VERSION_MIN..PROTOCOL_VERSION_MAX`, so a
     *   `TransferFileDescriptor` cannot carry one; the refusal lives where an
     *   untrusted `Int` first arrives, which is `FrameCodec` and
     *   `TransferSnapshotCodec`, both of which emit
     *   `TransferError.ProtocolVersionMismatch`.
     *
     * Keeping either here would mean carrying a variant no input can produce,
     * which is worse than not having it: it reads as coverage that exists and
     * does not.
     */

    /** Resume negotiation produced a decision this snapshot cannot apply. */
    public data class InvalidResumeProposal(
        public val reason: String,
    ) : Rejection() {
        override val code: String = "invalid_resume_proposal"
        override val message: String = reason
    }

    /** Verification was requested before every byte was confirmed. */
    public data class VerificationTooEarly(
        public val confirmedBytes: Long,
        public val totalBytes: Long,
    ) : Rejection() {
        override val code: String = "verification_too_early"
        override val message: String =
            "verification needs all $totalBytes bytes; only $confirmedBytes are confirmed"
    }

    /** A verification event contradicted the digests it carried. */
    public data class VerificationConflict(
        public val outcome: VerificationOutcome,
    ) : Rejection() {
        override val code: String = "verification_conflict"
        override val message: String =
            "a success event cannot carry verification outcome ${outcome.id}"
        override val error: TransferError = TransferError.restore(
            code = code,
            detail = message,
            retryable = true,
            origin = ErrorOrigin.LOCAL,
            category = ErrorCategory.INTEGRITY,
        )
    }

    /** The transfer is in a terminal state; it will not move again. */
    public data class TerminalTransfer(
        public val state: TransferState,
        public val attempted: String,
    ) : Rejection() {
        override val code: String = "terminal_transfer"
        override val message: String = "$attempted was refused: the transfer is already $state"
    }

    /** An acknowledgement confirmed more bytes than this side ever sent. */
    public data class AcknowledgementBeyondSent(
        public val sentBytes: Long,
        public val confirmedOffset: Long,
    ) : Rejection() {
        override val code: String = "acknowledgement_beyond_sent"
        override val message: String =
            "an acknowledgement confirmed $confirmedOffset bytes but only $sentBytes were sent"
        override val error: TransferError = protocolError()
    }

    /** The retry ceiling has been reached. */
    public data class RetryNotAllowed(
        public val retryCount: Int,
        public val limit: Int,
    ) : Rejection() {
        override val code: String = "retry_not_allowed"
        override val message: String =
            "retry was refused: $retryCount of $limit attempts have been used"
    }

    /** The command's own arguments were inconsistent. */
    public data class InvalidCommand(
        public val detail: String,
    ) : Rejection() {
        override val code: String = "invalid_command"
        override val message: String = detail
    }

    public companion object {
        /**
         * Every rejection code the reducer can produce, for tests and docs.
         *
         * This list is closed and every member is reachable:
         * `RejectionCoverageTest` asserts the exact code for each one through a
         * real reducer call, and fails if the two lists ever disagree — so a new
         * variant cannot be added without a test that proves it can happen.
         */
        public val allCodes: List<String> = listOf(
            "illegal_state_transition",
            "wrong_session",
            "wrong_transfer",
            "wrong_recipient",
            "offset_regression",
            "offset_beyond_total",
            "unexpected_sequence",
            "unexpected_offset",
            "overlapping_chunk",
            "invalid_chunk_length",
            "invalid_resume_proposal",
            "verification_too_early",
            "verification_conflict",
            "terminal_transfer",
            "acknowledgement_beyond_sent",
            "retry_not_allowed",
            "invalid_command",
        )
    }
}
