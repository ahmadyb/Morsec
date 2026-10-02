package app.morsecode.core.transfer.resume

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.protocol.ResumeDecision

/*
 * Resume negotiation, as a pure function.
 *
 * Everything here is decided from two values: what the sender says it is about to
 * send, and what the receiver already has on disk. No socket, no clock, no file
 * handle. That is what makes the thirteen rules below testable one by one.
 *
 * The rule that governs all the others:
 *
 *     Resume trusts confirmed valid bytes, and nothing else.
 *
 * The sender's own write position is deliberately carried in
 * [ResumeProposal.senderAdvertisedBytes] and deliberately never read: after an
 * interruption the sender's optimistic position is a guess, and resuming from a
 * guess produces a file that is silently truncated or silently duplicated. The
 * only number that becomes a resume offset is the receiver's confirmed one.
 */

/** What the sender proposes to send. */
public data class ResumeProposal(
    public val protocolVersion: ProtocolVersion,
    public val fileId: FileId,
    public val totalBytes: Long,
    public val chunkSize: ChunkSize,
    public val expectedSha256: Sha256Digest?,
    public val relativePath: RelativeTransferPath,
    public val lastModifiedEpochMillis: Long?,
    /** Fingerprint of the descriptor the sender will use. */
    public val descriptorFingerprint: String,
    /**
     * The sender's own write position.
     *
     * Advisory only. It exists so a diagnostic can compare the two sides and so
     * a test can prove it is ignored; it never influences the decision.
     */
    public val senderAdvertisedBytes: Long = 0L,
) {
    init {
        require(totalBytes >= 0L) { "totalBytes must not be negative, was $totalBytes" }
        require(totalBytes <= ProtocolLimits.MAX_FILE_SIZE_BYTES) {
            "totalBytes $totalBytes exceeds the maximum ${ProtocolLimits.MAX_FILE_SIZE_BYTES}"
        }
        require(senderAdvertisedBytes >= 0L) {
            "senderAdvertisedBytes must not be negative, was $senderAdvertisedBytes"
        }
    }
}

/** Where verification of the stored partial file got to. */
public enum class ReceiverVerificationState(public val id: String) {
    /** Nothing has been verified. */
    NOT_STARTED("not_started"),

    /** Verification is running or was interrupted mid-run. */
    PENDING("pending"),

    /** The stored partial file passed its full-file SHA-256 check. */
    VERIFIED("verified"),

    /** The stored partial file failed its check and must not be trusted. */
    FAILED("failed"),
    ;

    public companion object {
        public fun fromId(id: String?): ReceiverVerificationState =
            entries.firstOrNull { it.id == id } ?: NOT_STARTED
    }
}

/** What the receiver already holds for this transfer. */
public data class ReceiverResumeState(
    /** False when the receiver has never seen this transfer before. */
    public val knownTransfer: Boolean,
    public val fileId: FileId?,
    public val totalBytes: Long?,
    /** Bytes this receiver has confirmed as valid. The only trusted number. */
    public val confirmedBytes: Long,
    /** Fingerprint stored alongside the partial file. */
    public val descriptorFingerprint: String?,
    public val chunkSize: ChunkSize?,
    public val verification: ReceiverVerificationState,
    public val expectedSha256: Sha256Digest?,
    public val lastModifiedEpochMillis: Long?,
) {
    init {
        require(confirmedBytes >= 0L) {
            "confirmedBytes must not be negative, was $confirmedBytes"
        }
        if (totalBytes != null) {
            require(totalBytes >= 0L) { "totalBytes must not be negative, was $totalBytes" }
            require(confirmedBytes <= totalBytes) {
                "confirmedBytes $confirmedBytes exceeds totalBytes $totalBytes"
            }
        }
    }

    public companion object {
        /** The state of a receiver that has nothing stored for this transfer. */
        public fun unknown(): ReceiverResumeState = ReceiverResumeState(
            knownTransfer = false,
            fileId = null,
            totalBytes = null,
            confirmedBytes = 0L,
            descriptorFingerprint = null,
            chunkSize = null,
            verification = ReceiverVerificationState.NOT_STARTED,
            expectedSha256 = null,
            lastModifiedEpochMillis = null,
        )
    }
}

public object ResumeNegotiator {

    /**
     * Decides where a delivery should start.
     *
     * The same two inputs always produce the same decision, which is what lets a
     * duplicate proposal be idempotent: re-running it returns an equal decision
     * rather than advancing anything twice.
     */
    public fun negotiate(
        proposal: ResumeProposal,
        receiver: ReceiverResumeState,
    ): ResumeDecision {
        // A version we cannot decode cannot reach this function: ProtocolVersion
        // refuses to be constructed outside the supported range, so what follows
        // can assume both ends already speak a version we understand.

        // 1. Nothing stored: this is a fresh transfer, start at zero.
        if (!receiver.knownTransfer) return ResumeDecision.ResumeAt(0L)

        // 2. A different file occupies this transfer id.
        if (receiver.fileId != null && receiver.fileId != proposal.fileId) {
            return ResumeDecision.RestartAtZero("a different file occupies this transfer")
        }

        // 3. A receiver cannot hold more bytes than the file contains.
        if (receiver.confirmedBytes > proposal.totalBytes) {
            return ResumeDecision.Reject(
                TransferError.UnexpectedOffset(
                    expected = proposal.totalBytes,
                    actual = receiver.confirmedBytes,
                ),
            )
        }

        // 4. Size disagreement: the stored partial belongs to a different file.
        if (receiver.totalBytes != null && receiver.totalBytes != proposal.totalBytes) {
            return ResumeDecision.RestartAtZero(
                "stored size ${receiver.totalBytes} differs from the proposed ${proposal.totalBytes}",
            )
        }

        // 5. Descriptor disagreement, which covers the path, the name, the MIME
        //    type, the modification time, the chunk size and the expected digest
        //    in a single comparison.
        if (receiver.descriptorFingerprint != null &&
            receiver.descriptorFingerprint != proposal.descriptorFingerprint
        ) {
            return ResumeDecision.RestartAtZero("stored descriptor does not match the proposal")
        }

        // 6. Chunk-size disagreement: partial chunks would be written at
        //    different boundaries, so the partial file is unusable.
        if (receiver.chunkSize != null && receiver.chunkSize != proposal.chunkSize) {
            return ResumeDecision.RestartAtZero(
                "stored chunk size ${receiver.chunkSize.value} differs from the proposed " +
                    proposal.chunkSize.value,
            )
        }

        // 7. Both sides know the expected digest and they disagree.
        if (receiver.expectedSha256 != null &&
            proposal.expectedSha256 != null &&
            receiver.expectedSha256 != proposal.expectedSha256
        ) {
            return ResumeDecision.RestartAtZero("stored expected digest differs from the proposal")
        }

        // 8. The stored partial already failed its own verification.
        if (receiver.verification == ReceiverVerificationState.FAILED) {
            return ResumeDecision.RestartAtZero("the stored partial failed verification")
        }

        val offset = receiver.confirmedBytes

        // 9. A complete partial file is not a completed file: it still has to be
        //     verified, and only a verified one may be skipped.
        if (offset == proposal.totalBytes) {
            return if (receiver.verification == ReceiverVerificationState.VERIFIED) {
                ResumeDecision.AlreadyVerified
            } else {
                ResumeDecision.RestartAtZero(
                    "a complete but unverified partial must be retransmitted",
                )
            }
        }

        // 10. Alignment: a stored offset that is not on a chunk boundary cannot
        //     be produced by this protocol, so the partial file is suspect.
        if (offset % proposal.chunkSize.value.toLong() != 0L) {
            return ResumeDecision.RestartAtZero(
                "stored offset $offset is not aligned to ${proposal.chunkSize.value} bytes",
            )
        }

        return ResumeDecision.ResumeAt(offset)
    }

    /**
     * The offset a receiver should resume from, or null when the decision is not
     * a plain "resume here".
     *
     * Convenience for adapters that only care about the common case.
     */
    public fun resumeOffset(
        proposal: ResumeProposal,
        receiver: ReceiverResumeState,
    ): ConfirmedOffset? = (negotiate(proposal, receiver) as? ResumeDecision.ResumeAt)
        ?.let { ConfirmedOffset(it.offset) }
}
