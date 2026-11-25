package app.morsecode.core.transfer.event

import app.morsecode.core.transfer.command.TransferTarget
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest

/*
 * Events: facts that already happened outside the reducer.
 *
 * A chunk arrived, the peer paused, the transport dropped, the storage adapter
 * could not open the destination. The reducer cannot refuse that they happened,
 * but it can refuse to let them produce an impossible state — that refusal is a
 * typed `Rejection`, never a silent correction.
 *
 * Like commands, every event names the delivery it belongs to; an event for the
 * wrong session, transfer or recipient is rejected before it can touch anything.
 */

/** A fact about one delivery. */
public sealed interface TransferEvent : TransferTarget {
    /** Short, stable name used in rejections and logs. */
    public val eventName: String

    /** The peer accepted the offer and is ready to receive. */
    public data class PeerAccepted(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        /** Chunk size the peer will use; must equal the descriptor's. */
        public val chunkSize: ChunkSize,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "PeerAccepted"
        }
    }

    /** The peer declined the offer. */
    public data class PeerRejected(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val reason: String = "",
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "PeerRejected"
        }
    }

    /** The peer asked us to stop sending. */
    public data class RemotePaused(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "RemotePaused"
        }
    }

    /** The peer is ready to receive again. */
    public data class RemoteResumed(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "RemoteResumed"
        }
    }

    /**
     * Bytes were written to the wire.
     *
     * This advances *optimistic* progress only. It is never a reason to believe
     * the peer has those bytes.
     */
    public data class ChunkSent(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val offset: Long,
        public val length: Int,
        public val sequence: Long,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "ChunkSent"
        }
    }

    /**
     * A receiver chunk passed CRC, was written sequentially, and the required
     * destination durability operation succeeded. Only a trusted local effect
     * executor may report this fact; receiving a frame is not enough.
     *
     * At most one chunk may be awaiting its confirmed-offset checkpoint. The
     * reducer records its end in optimisticBytes but does not confirm or ACK it.
     */
    public data class ChunkDurablyStored(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val offset: Long,
        public val length: Int,
        public val sequence: Long,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "ChunkDurablyStored"
        }
    }

    /** The requested receiver checkpoint was durably persisted by the local executor. */
    public data class ConfirmedOffsetPersisted(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val candidateOffset: Long,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "ConfirmedOffsetPersisted"
        }
    }

    /**
     * The pending receiver checkpoint definitively did not persist. An unknown
     * persistence outcome must be reconciled with the store before dispatching
     * either this event or ConfirmedOffsetPersisted; it must not be ACKed.
     */
    public data class ConfirmedOffsetPersistenceFailed(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val candidateOffset: Long,
        public val storageFull: Boolean = false,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "ConfirmedOffsetPersistenceFailed"
        }
    }

    /** The peer acknowledged a chunk and reported its confirmed offset. */
    public data class ChunkAcknowledged(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val offset: Long,
        public val length: Int,
        public val confirmedOffset: Long,
        public val sequence: Long,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "ChunkAcknowledged"
        }
    }

    /** The peer reported its confirmed checkpoint without naming a chunk. */
    public data class ConfirmedOffsetReceived(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val confirmedOffset: Long,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "ConfirmedOffsetReceived"
        }
    }

    /** The connection dropped. */
    public data class TransportDisconnected(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val reason: String = "",
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "TransportDisconnected"
        }
    }

    /** The connection is back; retryable work may be reconsidered. */
    public data class TransportRestored(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "TransportRestored"
        }
    }

    /** Every byte of the file has been confirmed by the receiving side. */
    public data class AllBytesConfirmed(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "AllBytesConfirmed"
        }
    }

    /** Verification has begun for this delivery. */
    public data class VerificationStarted(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val expectedDigest: Sha256Digest?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "VerificationStarted"
        }
    }

    /**
     * The receiver verified the file, or the sender received a successful peer
     * verification result. Neither fact by itself completes a delivery.
     */
    public data class VerificationSucceeded(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val observedDigest: Sha256Digest?,
        /** Required for the sender: the total carried by the peer's result. */
        public val reportedTotalBytes: Long? = null,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "VerificationSucceeded"
        }
    }

    /** The full-file SHA-256 did not match; the destination is not committed. */
    public data class VerificationFailed(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val expectedDigest: Sha256Digest?,
        public val observedDigest: Sha256Digest?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "VerificationFailed"
        }
    }

    /** The local receiver's requested destination commit succeeded. */
    public data class DestinationCommitSucceeded(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "DestinationCommitSucceeded"
        }
    }

    /**
     * A receiver commit definitively failed without publishing the destination.
     * An ambiguous outcome requires reconciliation, not this event or deletion.
     */
    public data class DestinationCommitFailed(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        /** A typed local destination or persistence failure, never an exception message. */
        public val error: TransferError = TransferError.DestinationUnavailable("destination commit failed"),
    ) : TransferEvent {
        override val eventName: String get() = NAME

        // TransferError subclasses can carry adapter-owned fields. Never print
        // those fields, even when a caller violates the diagnostic contract.
        override fun toString(): String = "DestinationCommitFailed(error=${error.code})"

        public companion object {
            public const val NAME: String = "DestinationCommitFailed"
        }
    }

    /** A sender received the receiver's FILE_COMPLETE for this file. */
    public data class FileCompleteReceived(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val fileId: FileId,
        public val totalBytes: Long,
        public val digest: Sha256Digest?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "FileCompleteReceived"
        }
    }

    /** Reading the source or writing the destination failed. */
    public data class StorageFailed(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val error: TransferError,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "StorageFailed"
        }
    }

    /** A URI permission the transfer depended on was revoked. */
    public data class PermissionRevoked(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val reason: String = "",
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "PermissionRevoked"
        }
    }

    /** A framing, sequencing or versioning problem that is not a storage problem. */
    public data class ProtocolFailed(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val error: TransferError,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "ProtocolFailed"
        }
    }

    /** The peer cancelled the delivery. */
    public data class RemoteCancelled(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
        public val reason: String = "",
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "RemoteCancelled"
        }
    }

    /** The whole session is over; every non-terminal delivery is closed. */
    public data class SessionEnded(
        override val sessionId: SessionId,
        override val transferId: TransferId,
        override val recipientId: RecipientId?,
    ) : TransferEvent {
        override val eventName: String get() = NAME

        public companion object {
            public const val NAME: String = "SessionEnded"
        }
    }
}
