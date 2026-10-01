package app.morsecode.core.transfer.effect

import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferFileDescriptor

/*
 * Effects: work the reducer has decided must happen, described as values.
 *
 * Nothing in this file is executed. The reducer returns a list of these and
 * stops; an Android adapter (still gated behind TRANSFER_ENGINE) walks the list
 * and turns each one into a socket write, a stream open, a database write or a
 * UI emission. That is what keeps the reducer a pure function: it can be tested
 * on the JVM with no sockets, no files and no clock, and the same decision list
 * can be replayed after a process restart.
 */

/** Something an adapter must do as a result of an accepted transition. */
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

    /** Writes the whole snapshot; the adapter must make it atomic. */
    public data class PersistSnapshot(
        override val transferId: TransferId,
        public val snapshotVersion: Long,
    ) : TransferEffect

    /** Writes just the confirmed checkpoint; cheap enough to do per chunk. */
    public data class PersistConfirmedOffset(
        override val transferId: TransferId,
        public val confirmedOffset: Long,
    ) : TransferEffect

    /** Starts the full-file SHA-256 pass over the confirmed partial file. */
    public data class BeginVerification(
        override val transferId: TransferId,
        public val expectedDigestHex: String?,
    ) : TransferEffect

    /** Moves the verified partial file to its final destination. */
    public data class CommitVerifiedDestination(override val transferId: TransferId) : TransferEffect

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
            is SendChunk, is SendAcknowledgement, is SendPause, is SendResume, is SendCancel,
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
