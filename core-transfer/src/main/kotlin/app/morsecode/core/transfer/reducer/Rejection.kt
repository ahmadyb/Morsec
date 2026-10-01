package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.identity.TransferId
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

    final override fun toString(): String = "$code: $message"

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
    }

    /** The event or command belongs to a different transfer. */
    public data class WrongTransfer(
        public val expected: String,
        public val actual: String,
    ) : Rejection() {
        override val code: String = "wrong_transfer"
        override val message: String = "targets transfer $actual but this is transfer $expected"
    }

    /** The event or command belongs to a different broadcast recipient. */
    public data class WrongRecipient(
        public val expected: String?,
        public val actual: String?,
    ) : Rejection() {
        override val code: String = "wrong_recipient"
        override val message: String =
            "targets recipient ${actual ?: "none"} but this delivery belongs to ${expected ?: "none"}"
    }

    /** A progress report would move the confirmed watermark backwards. */
    public data class OffsetRegression(
        public val current: Long,
        public val proposed: Long,
    ) : Rejection() {
        override val code: String = "offset_regression"
        override val message: String =
            "confirmed offset $proposed is behind the recorded $current; progress never decreases"
    }

    /** A progress report claims more bytes than the file contains. */
    public data class OffsetBeyondTotal(
        public val offset: Long,
        public val total: Long,
    ) : Rejection() {
        override val code: String = "offset_beyond_total"
        override val message: String = "offset $offset is past the file size $total"
    }

    /** A chunk carried a sequence number that does not match its offset. */
    public data class UnexpectedSequence(
        public val expected: Long,
        public val actual: Long,
    ) : Rejection() {
        override val code: String = "unexpected_sequence"
        override val message: String = "expected sequence $expected but received $actual"
    }

    /** A chunk arrived at an offset the receiver cannot append. */
    public data class UnexpectedOffset(
        public val expected: Long,
        public val actual: Long,
    ) : Rejection() {
        override val code: String = "unexpected_offset"
        override val message: String = "expected a chunk at offset $expected but received one at $actual"
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
    }

    /** A chunk declared a length outside the negotiated chunk size. */
    public data class InvalidChunkLength(
        public val length: Int,
        public val max: Int,
    ) : Rejection() {
        override val code: String = "invalid_chunk_length"
        override val message: String = "chunk length $length is outside 1..$max"
    }

    /** An Enqueue named a transfer the queue already holds. */
    public data class DuplicateDescriptor(
        public val fileId: String,
        public val transferId: TransferId,
    ) : Rejection() {
        override val code: String = "duplicate_descriptor"
        override val message: String = "file $fileId is already queued as ${transferId.value}"
    }

    /** The peer's protocol version is outside the supported window. */
    public data class UnsupportedProtocol(
        public val version: Int,
        public val min: Int,
        public val max: Int,
    ) : Rejection() {
        override val code: String = "unsupported_protocol"
        override val message: String = "protocol version $version is outside $min..$max"
    }

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
    }

    /** The transfer is in a terminal state; it will not move again. */
    public data class TerminalTransfer(
        public val state: TransferState,
        public val attempted: String,
    ) : Rejection() {
        override val code: String = "terminal_transfer"
        override val message: String = "$attempted was refused: the transfer is already $state"
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
        /** Every rejection code this build can produce, for tests and docs. */
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
            "duplicate_descriptor",
            "unsupported_protocol",
            "invalid_resume_proposal",
            "verification_too_early",
            "verification_conflict",
            "terminal_transfer",
            "retry_not_allowed",
            "invalid_command",
        )
    }
}
