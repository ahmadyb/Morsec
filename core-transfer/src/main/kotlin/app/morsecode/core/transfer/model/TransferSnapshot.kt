package app.morsecode.core.transfer.model

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.SequenceNumber
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest

/*
 * The transfer snapshot: everything the reducer needs to make the next decision,
 * and nothing else.
 *
 * Two rules shape the field list.
 *
 * 1. Confirmed and optimistic progress are separate fields, never one number.
 *    `confirmedBytes` only ever moves on a receiver acknowledgement (or on a
 *    receiver's own verified write); `optimisticBytes` moves when this device
 *    writes bytes to the wire. Resume is only ever allowed to start from
 *    `confirmedBytes`, which is what makes "resume after an interruption"
 *    correct rather than optimistic.
 *
 * 2. There is no clock, no random id and no handle in here. The reducer is a
 *    pure function of (snapshot, command|event); anything time- or
 *    environment-dependent belongs to the adapter that feeds it.
 */

/** Result of a full-file integrity check, or of its absence. */
public enum class VerificationOutcome(public val id: String) {
    /** Verification has not produced a digest yet. */
    PENDING("pending"),

    /** An expected digest was present and the observed digest matched it. */
    MATCHED("matched"),

    /** An expected digest was present and the observed digest did not match. */
    MISMATCHED("mismatched"),

    /**
     * Verification ran but the sender supplied no expected digest, so the file
     * was accepted on byte-count only. Recorded honestly: this is not the same
     * guarantee as [MATCHED].
     */
    VERIFIED_WITHOUT_EXPECTED("verified_without_expected"),
    ;

    public companion object {
        public fun fromId(id: String?): VerificationOutcome =
            entries.firstOrNull { it.id == id } ?: PENDING
    }
}

/** What verification expected, what it observed, and where it was started from. */
public data class VerificationInfo(
    public val expectedDigest: Sha256Digest?,
    public val observedDigest: Sha256Digest?,
    /** Snapshot version at which verification began; a position, not a time. */
    public val startedSnapshotVersion: Long,
) {
    init {
        require(startedSnapshotVersion >= 0L) {
            "startedSnapshotVersion must not be negative, was $startedSnapshotVersion"
        }
    }

    public val outcome: VerificationOutcome
        get() = when {
            expectedDigest == null && observedDigest == null -> VerificationOutcome.PENDING
            expectedDigest == null -> VerificationOutcome.VERIFIED_WITHOUT_EXPECTED
            observedDigest == null -> VerificationOutcome.PENDING
            expectedDigest == observedDigest -> VerificationOutcome.MATCHED
            else -> VerificationOutcome.MISMATCHED
        }

    /** True only when the file may be committed. */
    public val allowsCommit: Boolean
        get() = outcome == VerificationOutcome.MATCHED ||
            outcome == VerificationOutcome.VERIFIED_WITHOUT_EXPECTED

    public companion object {
        public fun started(expected: Sha256Digest?, atSnapshotVersion: Long): VerificationInfo =
            VerificationInfo(
                expectedDigest = expected,
                observedDigest = null,
                startedSnapshotVersion = atSnapshotVersion,
            )
    }
}

/**
 * Immutable state of one file delivery.
 *
 * Instances are produced only by [app.morsecode.core.transfer.reducer.TransferReducer],
 * which is also the only thing that may bump [snapshotVersion]. Adapters
 * receive snapshots; they never build them.
 */
public data class TransferSnapshot(
    public val transferId: TransferId,
    public val sessionId: SessionId,
    public val batchId: BatchId,
    /** Non-null when this delivery is one leg of a broadcast. */
    public val recipientId: RecipientId?,
    public val direction: SessionDirection,
    public val descriptor: TransferFileDescriptor,
    public val state: TransferState,
    /** Bytes the receiving side has confirmed as valid. Never decreases. */
    public val confirmedBytes: Long,
    /** Bytes this device has written to the wire; may be ahead of [confirmedBytes]. */
    public val optimisticBytes: Long,
    /** Sequence number of the most recent acknowledgement, null before the first. */
    public val lastAcknowledgedSequence: Long?,
    public val retryCount: Int,
    public val failure: TransferError?,
    public val verification: VerificationInfo?,
    /** True while the peer has asked us to stop, even if we are also paused locally. */
    public val remotePaused: Boolean,
    /** Monotonic position in the state machine's history for this transfer. */
    public val snapshotVersion: Long,
    /** Stable ordering key: lower runs first when everything else is equal. */
    public val queueOrder: Long,
) {
    init {
        require(confirmedBytes >= 0L) { "confirmedBytes must not be negative, was $confirmedBytes" }
        require(confirmedBytes <= descriptor.totalBytes) {
            "confirmedBytes $confirmedBytes exceeds totalBytes ${descriptor.totalBytes}"
        }
        require(optimisticBytes >= confirmedBytes) {
            "optimisticBytes $optimisticBytes must not be below confirmedBytes $confirmedBytes"
        }
        require(optimisticBytes <= descriptor.totalBytes) {
            "optimisticBytes $optimisticBytes exceeds totalBytes ${descriptor.totalBytes}"
        }
        require(retryCount >= 0) { "retryCount must not be negative, was $retryCount" }
        require(snapshotVersion >= 1L) { "snapshotVersion must start at 1, was $snapshotVersion" }
        require(queueOrder >= 0L) { "queueOrder must not be negative, was $queueOrder" }
        require(lastAcknowledgedSequence == null || lastAcknowledgedSequence >= 0L) {
            "lastAcknowledgedSequence must not be negative, was $lastAcknowledgedSequence"
        }
    }

    public val fileId: FileId get() = descriptor.fileId
    public val totalBytes: Long get() = descriptor.totalBytes
    public val chunkSize: ChunkSize get() = descriptor.chunkSize

    /** Bytes written but not yet acknowledged. */
    public val inFlightBytes: Long get() = optimisticBytes - confirmedBytes

    /** The only progress value resume may trust. */
    public val confirmedOffset: ConfirmedOffset get() = ConfirmedOffset(confirmedBytes)

    /** Remaining bytes from the confirmed position. */
    public val remainingBytes: Long get() = totalBytes - confirmedBytes

    /** True when every byte has been confirmed by the receiving side. */
    public val allBytesConfirmed: Boolean get() = confirmedBytes == totalBytes

    /** Sequence number the next chunk must carry. */
    public val nextExpectedSequence: SequenceNumber
        get() = SequenceNumber.forOffset(confirmedBytes, descriptor.chunkSize)

    /** Fraction in 0..1; an empty file reports 1 once it is confirmed. */
    public val progressFraction: Float
        get() = if (totalBytes == 0L) {
            if (allBytesConfirmed) 1f else 0f
        } else {
            (confirmedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    /** True when the state can no longer produce a new transition. */
    public val isTerminal: Boolean get() = state.isTerminal

    /** True while this delivery is occupying a transfer slot. */
    public val isActive: Boolean get() = state.isBusy

    /**
     * Structural self-check used by tests and by the snapshot store.
     *
     * `init` catches the numeric bounds; this catches the cross-field rules
     * (a completed file must be fully confirmed and verified, a paused file must
     * not have optimistic bytes beyond what was confirmed, and so on) that only
     * make sense once a whole state is assembled.
     */
    public fun violations(): List<String> {
        val problems = mutableListOf<String>()
        if (confirmedBytes > totalBytes) problems += "confirmedBytes past totalBytes"
        if (optimisticBytes < confirmedBytes) problems += "optimisticBytes below confirmedBytes"
        if (optimisticBytes > totalBytes) problems += "optimisticBytes past totalBytes"
        if (state == TransferState.COMPLETED) {
            if (!allBytesConfirmed) problems += "COMPLETED without all bytes confirmed"
            if (verification?.allowsCommit != true) problems += "COMPLETED without a successful verification"
            if (inFlightBytes != 0L) problems += "COMPLETED with unacknowledged bytes"
        }
        if (state == TransferState.VERIFYING && !allBytesConfirmed) {
            problems += "VERIFYING before all bytes were confirmed"
        }
        if (state == TransferState.PAUSED_LOCAL || state == TransferState.PAUSED_REMOTE) {
            if (optimisticBytes != confirmedBytes) problems += "paused with optimistic bytes still outstanding"
        }
        if (state == TransferState.QUEUED && confirmedBytes != 0L) {
            problems += "QUEUED with a non-zero confirmed offset"
        }
        if (retryCount > ProtocolLimits.DEFAULT_MAX_RETRY_COUNT && failure?.retryable != false) {
            problems += "retryCount above the default ceiling without a final failure"
        }
        return problems
    }

    /** Convenience for tests and assertions. */
    public fun isInternallyConsistent(): Boolean = violations().isEmpty()

    public companion object {
        /** Snapshot version a brand new transfer starts at. */
        public const val INITIAL_SNAPSHOT_VERSION: Long = 1L
    }
}
