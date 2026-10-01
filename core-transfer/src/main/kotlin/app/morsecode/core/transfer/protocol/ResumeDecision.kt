package app.morsecode.core.transfer.protocol

import app.morsecode.core.transfer.error.TransferError

/*
 * The outcome of resume negotiation.
 *
 * Resume is decided by a pure function (`ResumeNegotiator`) and applied to the
 * state machine by `TransferCommand.ApplyResumeDecision`, so the whole rule set
 * is testable without a socket, a file or a clock.
 *
 * The single rule everything else hangs off: only bytes the *receiver* has
 * confirmed count. The sender's own write position is deliberately not part of
 * the decision, which is what makes "optimistic sent bytes are ignored after an
 * interruption" an executable statement rather than a comment.
 */

public sealed class ResumeDecision {
    /**
     * Continue from [offset] bytes.
     *
     * For an outbound delivery this adopts the receiver's confirmed offset. For
     * an inbound delivery it can only ever rewind to a point this device has
     * already confirmed, never forward.
     */
    public data class ResumeAt(public val offset: Long) : ResumeDecision() {
        init {
            require(offset >= 0L) { "a resume offset must not be negative, was $offset" }
        }
    }

    /**
     * Discard the partial file and start again from zero.
     *
     * Chosen whenever continuing would risk producing a corrupt file: a
     * different file, a different size, a different expected digest, or a
     * different chunk size.
     */
    public data class RestartAtZero(public val reason: String) : ResumeDecision()

    /**
     * The receiver already holds a verified copy of this file; nothing to send.
     */
    public data object AlreadyVerified : ResumeDecision()

    /** Resume is impossible for a reason that is not "start over". */
    public data class Reject(public val error: TransferError) : ResumeDecision()

    public companion object {
        /** The four decision kinds, for documentation and table tests. */
        public val kinds: List<String> = listOf("ResumeAt", "RestartAtZero", "AlreadyVerified", "Reject")
    }
}
