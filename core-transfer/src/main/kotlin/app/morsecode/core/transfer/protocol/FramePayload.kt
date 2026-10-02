package app.morsecode.core.transfer.protocol

import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.integrity.Sha256Digest

/*
 * Typed payloads: the meaning of the bytes inside an envelope.
 *
 * Decoding is two-stage on purpose. `FrameCodec` validates the envelope — magic,
 * version, header CRC32, declared lengths, payload CRC32 — and only then does
 * `FramePayloads.parse` interpret the payload. That ordering is what makes a
 * hostile length field harmless: it is bounds-checked against `ProtocolLimits`
 * before a single allocation of that size is attempted, and the payload CRC is
 * verified before any of its fields are believed.
 */

/** The interpreted body of one frame. */
public sealed interface FramePayload {

    /** Sender's opening offer. */
    public data class Handshake(
        public val capabilities: String,
        public val maxChunkSize: ChunkSize,
        public val resumeSupported: Boolean,
    ) : FramePayload

    /** Receiver's agreement, naming the chunk size to use. */
    public data class HandshakeAccept(
        public val chunkSize: ChunkSize,
        public val resumeSupported: Boolean,
        public val resumeFromOffset: Long,
    ) : FramePayload

    /** Receiver's refusal. */
    public data class HandshakeReject(public val reason: String) : FramePayload

    /** The descriptor both peers must agree on before any bytes move. */
    public data class FileMetadata(public val descriptor: TransferFileDescriptor) : FramePayload

    /**
     * What the sender intends to send.
     *
     * [senderAdvertisedBytes] is advisory only: it exists so a diagnostic can
     * compare the two sides, and it is explicitly *not* used to derive the
     * resume offset. Resume trusts the receiver's confirmed bytes alone.
     */
    public data class ResumeProposal(
        public val fileId: FileId,
        public val totalBytes: Long,
        public val chunkSize: ChunkSize,
        public val expectedSha256: Sha256Digest?,
        public val relativePath: RelativeTransferPath,
        public val lastModifiedEpochMillis: Long?,
        public val senderAdvertisedBytes: Long,
    ) : FramePayload

    /**
     * Where the receiver wants the sender to start.
     *
     * The wire carries one offset field; [offset] mirrors the one inside a
     * [ResumeDecision.ResumeAt] and is zero for every other decision, so the two
     * can never disagree.
     */
    public data class ResumeResponse(
        public val fileId: FileId,
        public val decision: ResumeDecision,
    ) : FramePayload {
        public val offset: Long
            get() = (decision as? ResumeDecision.ResumeAt)?.offset ?: 0L
    }

    /** One chunk of file bytes. */
    public data class DataChunk(
        public val offset: Long,
        public val sequence: Long,
        public val bytes: ByteArray,
        public val crc32: Int,
    ) : FramePayload

    /** Receiver's acknowledgement of one verified chunk. */
    public data class ChunkAck(
        public val offset: Long,
        public val length: Int,
        public val confirmedOffset: Long,
        public val accepted: Boolean,
    ) : FramePayload

    /** Stop sending. */
    public data class Pause(public val reason: String) : FramePayload

    /** Start again from [fromOffset]. */
    public data class Resume(
        public val fromOffset: Long,
        public val reason: String,
    ) : FramePayload

    /** This delivery is over. */
    public data class Cancel(public val reason: String) : FramePayload

    /** A typed failure crossing the wire. */
    public data class ErrorFrame(
        public val code: String,
        public val detail: String,
        public val retryable: Boolean,
        public val origin: ErrorOrigin,
        public val category: ErrorCategory,
    ) : FramePayload {
        /** Rebuilds the error value this frame describes. */
        public fun toError(): app.morsecode.core.transfer.error.TransferError =
            app.morsecode.core.transfer.error.TransferError.restore(
                code = code,
                detail = detail,
                retryable = retryable,
                origin = origin,
                category = category,
            )
    }

    /** Outcome of the full-file SHA-256 check. */
    public data class VerificationResult(
        public val success: Boolean,
        public val totalBytes: Long,
        public val observedDigest: Sha256Digest?,
    ) : FramePayload

    /** Sender's confirmation that one file is complete and verified. */
    public data class FileComplete(
        public val fileId: FileId,
        public val totalBytes: Long,
        public val digest: Sha256Digest?,
    ) : FramePayload

    /** Whole batch finished. */
    public data class SessionComplete(
        public val itemCount: Int,
        public val totalBytes: Long,
    ) : FramePayload

    public companion object {
        /** Wire value for `ResumeDecision.ResumeAt`. */
        public const val DECISION_RESUME_AT: Int = 1
        public const val DECISION_RESTART_AT_ZERO: Int = 2
        public const val DECISION_ALREADY_VERIFIED: Int = 3
        public const val DECISION_REJECT: Int = 4

        /** Wire value for a file descriptor that is a plain file. */
        public const val KIND_FILE: Int = 0
        public const val KIND_FOLDER_ARCHIVE: Int = 1
    }
}

/** Result of interpreting a frame's payload. */
public sealed class PayloadResult {
    public data class Success(public val payload: FramePayload) : PayloadResult()
    public data class Invalid(
        public val error: app.morsecode.core.transfer.error.TransferError,
    ) : PayloadResult()
}
