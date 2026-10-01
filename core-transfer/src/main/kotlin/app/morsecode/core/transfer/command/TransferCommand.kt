package app.morsecode.core.transfer.command

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.protocol.ResumeDecision

/*
 * Commands: what a user or the queue engine *wants* to happen.
 *
 * Commands are kept strictly apart from events (see TransferEvent.kt): a command
 * may be refused, an event is a fact that already happened. That split is what
 * lets the reducer answer "no" with a typed rejection instead of half-applying
 * an impossible request.
 *
 * Every command names the session, transfer and (for broadcasts) recipient it
 * targets, because every command is validated against the snapshot before it can
 * change anything.
 */

/** Anything that can be addressed at one delivery. */
public interface TransferTarget {
    public val sessionId: SessionId
    public val transferId: TransferId
    public val recipientId: RecipientId?
}

/** User or queue intent for one transfer. */
public sealed interface TransferCommand : TransferTarget {
    /** Short, stable name used in rejections and logs. */
    public val commandName: String

    /**
     * Creates the initial snapshot for one delivery.
     *
     * [transferId] is supplied by the caller on purpose: the reducer is a pure
     * function and never invents an identifier, so a replayed command sequence
     * reproduces byte-identical state.
     */
    public data class Enqueue(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val batchId: BatchId,
        public val direction: SessionDirection,
        public val descriptor: TransferFileDescriptor,
        /** Stable ordering key: lower values are scheduled first. */
        public val queueOrder: Long = 0L,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "Enqueue"
        }
    }

    /** Starts the handshake: capabilities, chunk size and resume point. */
    public data class BeginNegotiation(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "BeginNegotiation"
        }
    }

    /** Accepts an inbound offer the peer made. */
    public data class Accept(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "Accept"
        }
    }

    /** Declines an inbound offer. */
    public data class Reject(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val reason: String = "",
    ) : TransferCommand {
        init {
            requireValidReason(FIELD, reason)
        }

        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "Reject"
            public const val FIELD: String = "reason"
        }
    }

    /** Begins pushing bytes for an outbound delivery. */
    public data class StartSending(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "StartSending"
        }
    }

    /** Begins pulling bytes for an inbound delivery. */
    public data class StartReceiving(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "StartReceiving"
        }
    }

    /** Pauses this delivery on this device; the confirmed offset is preserved. */
    public data class PauseLocally(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "PauseLocally"
        }
    }

    /**
     * Resumes this delivery on this device.
     *
     * Resuming an item the peer paused sends an explicit resume request rather
     * than pushing bytes into a socket the peer has stopped draining.
     */
    public data class ResumeLocally(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "ResumeLocally"
        }
    }

    /** Cancels this delivery on this device and tells the peer. */
    public data class CancelLocally(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val reason: String = "",
    ) : TransferCommand {
        init {
            requireValidReason(FIELD, reason)
        }

        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "CancelLocally"
            public const val FIELD: String = "reason"
        }
    }

    /**
     * Re-offers a retryable failure.
     *
     * [retryLimit] is supplied by the queue engine from settings so the reducer
     * stays free of global configuration.
     */
    public data class Retry(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val retryLimit: Int = ProtocolLimits.DEFAULT_MAX_RETRY_COUNT,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "Retry"
        }
    }

    /** Removes the delivery from the queue without transferring it. */
    public data class Skip(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val reason: String = "",
    ) : TransferCommand {
        init {
            requireValidReason(FIELD, reason)
        }

        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "Skip"
            public const val FIELD: String = "reason"
        }
    }

    /**
     * Applies the outcome of resume negotiation to this delivery.
     *
     * This is the command form of `ResumeNegotiator`'s decision and the only way
     * `confirmedBytes` may move outside of a receiver acknowledgement. It is
     * listed here as an explicit addition to the master prompt's command list:
     * without it the resume rules would live outside the state machine and could
     * not be tested against the transition table.
     */
    public data class ApplyResumeDecision(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val decision: ResumeDecision,
    ) : TransferCommand {
        override val commandName: String get() = NAME

        public companion object {
            public const val NAME: String = "ApplyResumeDecision"
        }
    }

    public companion object {
        /**
         * Bounds a free-text reason.
         *
         * Reasons cross the wire and land in the log, so they are length-checked
         * here and redacted again when they become a `TransferError`.
         */
        public fun requireValidReason(field: String, reason: String) {
            val encoded = reason.toByteArray(Charsets.UTF_8)
            require(encoded.size <= ProtocolLimits.MAX_ERROR_DETAIL_BYTES) {
                "$field must not exceed ${ProtocolLimits.MAX_ERROR_DETAIL_BYTES} UTF-8 bytes"
            }
            require('\u0000' !in reason) { "$field must not contain a NUL character" }
        }
    }
}
