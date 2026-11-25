package app.morsecode.core.transfer.effect

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.model.TransferFileDescriptor

/*
 * Effects: work the reducer has decided must happen, described as values.
 *
 * Nothing in this file is executed. The reducer returns an ordered program, not
 * a collection of independent notifications. A future executor must perform
 * one effect at a time in list order, stop on the first failure, and dispatch
 * the corresponding success or failure event before processing further work.
 * No UI or scheduling effect may overtake a preceding persistence or wire
 * effect. Effect execution and all production payload paths remain disabled.
 *
 * In particular, a receiver checkpoint follows destination durability, and an
 * ACK follows successful checkpoint persistence. A receiver's commit effect
 * must succeed before completed-state persistence, verification success,
 * FILE_COMPLETE, and UI completion, in that order. See TransitionResult and
 * TransferSnapshotStore for the required persistence revision handling.
 */

/** Something an eventual executor must do as part of an ordered program. */
public sealed interface TransferEffect {
    /** The delivery this effect belongs to, where one delivery is involved. */
    public val transferId: TransferId?

    /** --- wire ------------------------------------------------------------ */

    /** Opens negotiation: version, capabilities and the chunk size we want. */
    public data class SendHandshake(
        override val transferId: TransferId,
        public val chunkSize: ChunkSize,
        public val capabilities: String,
    ) : TransferEffect

    /** Answers an inbound handshake positively. */
    public data class SendHandshakeAccept(
        override val transferId: TransferId,
        public val chunkSize: ChunkSize,
        public val resumeFromOffset: Long,
    ) : TransferEffect

    /** Answers an inbound handshake negatively. */
    public data class SendHandshakeReject(
        override val transferId: TransferId,
        public val reason: String,
    ) : TransferEffect

    /** Sends the file descriptor the peer must agree on before any bytes move. */
    public data class SendMetadata(
        override val transferId: TransferId,
        public val descriptor: TransferFileDescriptor,
    ) : TransferEffect

    /** Writes one chunk to the wire. */
    public data class SendChunk(
        override val transferId: TransferId,
        public val offset: Long,
        public val length: Int,
        public val sequence: Long,
    ) : TransferEffect

    /** Acknowledges one verified chunk and reports the confirmed offset. */
    public data class SendAcknowledgement(
        override val transferId: TransferId,
        public val offset: Long,
        public val length: Int,
        public val confirmedOffset: Long,
    ) : TransferEffect

    /** Receiver-to-sender verification result; success is emitted only after commit. */
    public data class SendVerificationResult(
        override val transferId: TransferId,
        public val success: Boolean,
        public val totalBytes: Long,
        public val observedDigest: Sha256Digest?,
    ) : TransferEffect

    /** Receiver-to-sender acknowledgement of a committed, verified file. */
    public data class SendFileComplete(
        override val transferId: TransferId,
        public val fileId: FileId,
        public val totalBytes: Long,
        public val digest: Sha256Digest?,
    ) : TransferEffect

    /** Tells the peer we are pausing. */
    public data class SendPause(override val transferId: TransferId) : TransferEffect

    /** Tells the peer we are ready again, naming the offset we will resume from. */
    public data class SendResume(
        override val transferId: TransferId,
        public val fromOffset: Long,
    ) : TransferEffect

    /** Tells the peer this delivery is cancelled. */
    public data class SendCancel(
        override val transferId: TransferId,
        public val reason: String,
    ) : TransferEffect

    /** --- storage --------------------------------------------------------- */

    /** Asks the source adapter for a stream positioned at [fromOffset]. */
    public data class RequestSourceStream(
        override val transferId: TransferId,
        public val fromOffset: Long,
    ) : TransferEffect

    /**
     * Asks the destination adapter for the partial file, expected to contain
     * exactly [expectedOffset] valid bytes.
     */
    public data class RequestDestinationPartial(
        override val transferId: TransferId,
        public val expectedOffset: Long,
    ) : TransferEffect

    /** --- persistence ----------------------------------------------------- */

    /**
     * Atomically writes the snapshot identified by [snapshotVersion]. It may
     * name either the previous or current snapshot in an accepted transition;
     * provisional revisions must not be skipped by a versioned store.
     */
    public data class PersistSnapshot(
        override val transferId: TransferId,
        public val snapshotVersion: Long,
    ) : TransferEffect

    /**
     * Persists a checkpoint. For a receiver, [confirmedOffset] is the pending
     * durable candidate, not yet the live confirmed frontier. Dispatch
     * ConfirmedOffsetPersisted only after this effect succeeds; on failure
     * dispatch ConfirmedOffsetPersistenceFailed and do not ACK.
     */
    public data class PersistConfirmedOffset(
        override val transferId: TransferId,
        public val confirmedOffset: Long,
    ) : TransferEffect

    /**
     * Report local implicit EOF for this exact snapshot revision by dispatching
     * AllBytesConfirmed after every preceding effect has succeeded. This is not
     * a wire frame. The executor must revalidate the current revision and must
     * not run this effect after a preceding persistence or protocol failure.
     */
    public data class DispatchAllBytesConfirmed(
        override val transferId: TransferId,
        public val snapshotVersion: Long,
    ) : TransferEffect

    /** Starts the full-file SHA-256 pass over the confirmed partial file. */
    public data class BeginVerification(
        override val transferId: TransferId,
        public val expectedDigest: Sha256Digest?,
    ) : TransferEffect

    /**
     * Commit the verified receiver destination. Before any physical publication,
     * the executor MUST durably save the accepted provisional verified snapshot
     * at [verifiedSnapshotVersion], using the last durable revision for CAS. If
     * that prerequisite fails, do not publish; report the typed persistence
     * failure. After it succeeds, attempt publication and report its actual
     * outcome. This precondition is part of this single commit effect, not an
     * extra wire message or permission to execute effects out of order.
     *
     * The durable provisional snapshot means "publication may have happened":
     * after a crash it requires reconciliation and must not be deleted or
     * committed again on a guess. See TransferSnapshotStore.
     */
    public data class CommitVerifiedDestination(
        override val transferId: TransferId,
        public val verifiedSnapshotVersion: Long,
    ) : TransferEffect

    /** Deletes the partial file; used on cancellation and on restart-from-zero. */
    public data class DeletePartialDestination(override val transferId: TransferId) : TransferEffect

    /** --- queue and UI ---------------------------------------------------- */

    /** The UI should re-read this delivery. */
    public data class NotifyUi(
        override val transferId: TransferId,
        public val state: TransferState,
    ) : TransferEffect

    /** The queue engine may have a free slot now. */
    public data class ScheduleNextEligibleItem(public val sessionId: SessionId) : TransferEffect {
        override val transferId: TransferId? = null
    }

    public companion object {
        /** True when this effect produces bytes on the wire. */
        public fun isWireEffect(effect: TransferEffect): Boolean = when (effect) {
            is SendHandshake, is SendHandshakeAccept, is SendHandshakeReject, is SendMetadata,
            is SendChunk, is SendAcknowledgement, is SendVerificationResult, is SendFileComplete,
            is SendPause, is SendResume, is SendCancel,
            -> true

            else -> false
        }

        /** True when this effect must survive a process restart. */
        public fun isPersistenceEffect(effect: TransferEffect): Boolean = when (effect) {
            is PersistSnapshot, is PersistConfirmedOffset -> true
            else -> false
        }
    }
}
