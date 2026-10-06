package app.morsecode.core.storage.transfer

import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest

/**
 * Durable protocol phases for a SAF destination commit. Intent phases are saved
 * before the matching side effect; result phases are saved only after observing
 * the provider's answer.
 */
public enum class SafCommitCheckpointPhase(public val id: String) {
    READY("ready"),
    STAGING_VERIFICATION_INTENT("staging_verification_intent"),
    STAGING_VERIFIED("staging_verified"),
    DESTINATION_RESOLUTION_INTENT("destination_resolution_intent"),
    DESTINATION_RESOLVED("destination_resolved"),
    TEMPORARY_CREATE_INTENT("temporary_create_intent"),
    TEMPORARY_CREATED("temporary_created"),
    VISIBLE_CREATE_INTENT("visible_create_intent"),
    VISIBLE_CREATED("visible_created"),
    VISIBLE_DELETE_INTENT("visible_delete_intent"),
    VISIBLE_DELETE_OBSERVED("visible_delete_observed"),
    COPY_STARTED("copy_started"),
    COPY_COMPLETED("copy_completed"),
    FLUSH_INTENT("flush_intent"),
    FLUSH_COMPLETED("flush_completed"),
    PROVIDER_VERIFICATION_INTENT("provider_verification_intent"),
    PROVIDER_VERIFIED("provider_verified"),
    BACKUP_RENAME_INTENT("backup_rename_intent"),
    BACKUP_RENAMED("backup_renamed"),
    FINAL_RENAME_INTENT("final_rename_intent"),
    FINAL_RENAMED("final_renamed"),
    RENAME_RECONCILIATION_INTENT("rename_reconciliation_intent"),
    FINAL_VERIFICATION_INTENT("final_verification_intent"),
    FINAL_VERIFIED("final_verified"),
    INTERRUPTED_TEMPORARY_DELETE_INTENT("interrupted_temporary_delete_intent"),
    INTERRUPTED_TEMPORARY_DELETE_OBSERVED("interrupted_temporary_delete_observed"),
    PROVIDER_TEMPORARY_DELETE_INTENT("provider_temporary_delete_intent"),
    PROVIDER_TEMPORARY_DELETE_OBSERVED("provider_temporary_delete_observed"),
    BACKUP_DELETE_INTENT("backup_delete_intent"),
    BACKUP_DELETE_OBSERVED("backup_delete_observed"),
    STAGING_DELETE_INTENT("staging_delete_intent"),
    STAGING_DELETE_OBSERVED("staging_delete_observed"),
    /** User cancellation before commit publication; provider identities are retained for exact cleanup. */
    CANCELLED("cancelled"),
    CANCELLED_TEMPORARY_DELETE_INTENT("cancelled_temporary_delete_intent"),
    CANCELLED_TEMPORARY_DELETE_OBSERVED("cancelled_temporary_delete_observed"),
    PUBLICATION_INTENT("publication_intent"),
    PUBLISHED("published"),
    RECONCILIATION_REQUIRED("reconciliation_required"),
    COMMITTED("committed"),
    ;

    public companion object {
        public fun fromId(id: String?): SafCommitCheckpointPhase? = entries.firstOrNull { it.id == id }
    }
}

/** The approved SAF tree, kept separate from any caller-presented document URI. */
public data class SafApprovedTreeIdentity(
    public val grantId: String,
    public val treeUri: String,
    public val authority: String,
    public val rootDocumentId: String,
) {
    init {
        require(grantId.isNotBlank()) { "grant id must not be blank" }
        require(treeUri.isNotBlank()) { "tree identity must not be blank" }
        require(authority.isNotBlank()) { "tree authority must not be blank" }
        require(rootDocumentId.isNotBlank()) { "root document identity must not be blank" }
    }

    override fun toString(): String = "SafApprovedTreeIdentity([redacted])"
}

/** Exact work to retry; provider deletions always carry the URI/id pair. */
public data class SafPendingCleanupIdentity(
    public val type: SafCleanupPending,
    public val documentIdentity: SafStoredDocumentIdentity? = null,
    public val stagingIdentity: PartialIdentity? = null,
) {
    init {
        when (type) {
            SafCleanupPending.STAGING -> {
                require(stagingIdentity != null && documentIdentity == null) {
                    "staging cleanup requires only a staging identity"
                }
            }

            SafCleanupPending.PROVIDER_TEMPORARY,
            SafCleanupPending.BACKUP,
            -> require(documentIdentity != null && stagingIdentity == null) {
                "provider cleanup requires only an exact document identity"
            }
        }
    }

    override fun toString(): String = "SafPendingCleanupIdentity(type=${type.id}, [redacted])"
}

/** Redacted typed failure metadata; provider messages and diagnostics are excluded. */
public data class SafCommitCheckpointFailure(
    public val categoryId: String,
    public val code: String,
) {
    init {
        require(TransferStorageErrorCategory.entries.any { it.id == categoryId }) {
            "unknown failure category"
        }
        require(code.matches(Regex("[a-z0-9_]{1,64}"))) { "invalid failure code" }
    }

    override fun toString(): String = "SafCommitCheckpointFailure(category=$categoryId, code=$code)"
}

/**
 * Versioned, Room-independent state sufficient to resume or reconcile a SAF
 * commit. It contains exact identities, the caller's expected SHA-256 value, and
 * the verified staged-content digest needed to revalidate final authority after
 * recovery. It never contains handles, streams, paths, file bytes, or digest
 * accumulator internals.
 */
public data class SafCommitCheckpoint(
    public val version: Int,
    public val sessionId: SessionId,
    public val transferId: TransferId,
    /** Stable deterministic commit key; currently the SAF staging identity. */
    public val commitId: PartialIdentity,
    public val strategy: SafCommitStrategy,
    public val duplicatePolicy: DuplicatePolicy,
    public val approvedTree: SafApprovedTreeIdentity,
    public val parentDocumentId: String,
    /** App-private staging row identity, not its filesystem path. */
    public val stagingIdentity: PartialIdentity,
    public val expectedFinalName: String,
    public val expectedSizeBytes: Long,
    /** Trusted expected digest only; a locally observed digest is transient. */
    public val expectedDigest: Sha256Digest?,
    public val phase: SafCommitCheckpointPhase,
    public val temporaryIdentity: SafStoredDocumentIdentity? = null,
    public val existingIdentity: SafStoredDocumentIdentity? = null,
    public val backupIdentity: SafStoredDocumentIdentity? = null,
    /** Settled rename evidence retained across process death. */
    public val renameHistory: List<SafRenameEvidence> = emptyList(),
    /** URI returned by a rename before the provider's id can be queried. */
    public val returnedRenameUri: String? = null,
    public val returnedRenameIdentity: SafStoredDocumentIdentity? = null,
    public val finalIdentity: SafStoredDocumentIdentity? = null,
    public val pendingCleanup: List<SafPendingCleanupIdentity> = emptyList(),
    public val copiedBytes: Long = 0L,
    /** Whether app-private staging deletion was independently observed. */
    public val stagingReleased: Boolean = false,
    public val lastFailure: SafCommitCheckpointFailure? = null,
    /** Rename mutation whose returned state still needs provider reconciliation. */
    public val unresolvedRenamePhase: SafRenamePhase? = null,
    /** Digest of the verified staged bytes; required to authorize final cleanup after recovery. */
    public val verifiedDigest: Sha256Digest? = null,
) {
    init {
        require(version > 0) { "checkpoint version must be positive" }
        SafFilenamePolicy.requireOriginalName(expectedFinalName)
        require(parentDocumentId.isNotBlank()) { "parent identity must not be blank" }
        require(expectedSizeBytes >= 0L) { "expected size must not be negative" }
        require(copiedBytes >= 0L) { "copied byte count must not be negative" }
        require(copiedBytes <= expectedSizeBytes) { "copied byte count exceeds expected size" }
        require(verifiedDigest == null || expectedDigest == null || verifiedDigest == expectedDigest) {
            "verified digest must agree with a supplied expected digest"
        }
        require(pendingCleanup.map { it.type }.distinct().size == pendingCleanup.size) {
            "cleanup types must be unique"
        }
        require(renameHistory.size <= SafRenameHistoryPolicy.MAX_ENTRIES) {
            "rename history exceeds its bounded entry count"
        }
        require(unresolvedRenamePhase == null || phase == SafCommitCheckpointPhase.RECONCILIATION_REQUIRED) {
            "unresolved rename phase requires a reconciliation checkpoint"
        }
        require(unresolvedRenamePhase == null || renameHistory.none { it.phase == unresolvedRenamePhase }) {
            "an unresolved rename cannot already have settled history"
        }
        require(!stagingReleased || pendingCleanup.none { it.type == SafCleanupPending.STAGING }) {
            "released staging cannot remain pending"
        }
        if (phase == SafCommitCheckpointPhase.COMMITTED) {
            require(finalIdentity != null && pendingCleanup.isEmpty() && stagingReleased) {
                "committed checkpoints require a final identity and completed cleanup"
            }
        }
    }

    /** Never include tree/document identities, filename, or digest bytes in diagnostics. */
    override fun toString(): String =
        "SafCommitCheckpoint(version=$version, phase=${phase.id}, strategy=${strategy.id}, " +
            "expectedSizeBytes=$expectedSizeBytes, copiedBytes=$copiedBytes, " +
            "stagingReleased=$stagingReleased, unresolvedRenamePhase=${unresolvedRenamePhase?.id}, " +
            "pendingCleanup=${pendingCleanup.map { it.type.id }.sorted()})"

    public companion object {
        public const val CURRENT_VERSION: Int = 2

        public fun fromRecord(
            record: SafCommitRecord,
            grant: SafTreeGrant,
            phase: SafCommitCheckpointPhase = SafCommitCheckpointPhase.READY,
        ): SafCommitCheckpoint = SafCommitCheckpoint(
            version = CURRENT_VERSION,
            sessionId = record.sessionId,
            transferId = record.transferId,
            commitId = record.partialId,
            strategy = record.strategy,
            duplicatePolicy = record.duplicatePolicy,
            approvedTree = SafApprovedTreeIdentity(
                grantId = grant.grantId,
                treeUri = record.treeUri,
                authority = grant.authority,
                rootDocumentId = record.rootDocumentId,
            ),
            parentDocumentId = record.parentDocumentId,
            stagingIdentity = record.partialId,
            expectedFinalName = record.expectedFinalName,
            expectedSizeBytes = record.expectedSizeBytes,
            expectedDigest = record.expectedDigest,
            phase = phase,
            temporaryIdentity = record.temporaryIdentity,
            existingIdentity = record.existingIdentity,
            backupIdentity = record.backupIdentity,
            renameHistory = record.renameHistory,
            finalIdentity = record.finalIdentity,
            pendingCleanup = record.pendingCleanup.map { pending ->
                when (pending) {
                    SafCleanupPending.STAGING -> SafPendingCleanupIdentity(
                        type = pending,
                        stagingIdentity = record.partialId,
                    )

                    SafCleanupPending.PROVIDER_TEMPORARY -> SafPendingCleanupIdentity(
                        type = pending,
                        documentIdentity = requireNotNull(record.temporaryIdentity) {
                            "provider temporary cleanup requires its recorded identity"
                        },
                    )

                    SafCleanupPending.BACKUP -> SafPendingCleanupIdentity(
                        type = pending,
                        documentIdentity = requireNotNull(record.backupIdentity) {
                            "backup cleanup requires its recorded identity"
                        },
                    )
                }
            },
            copiedBytes = record.copiedBytes,
            stagingReleased = record.stagingReleased,
            verifiedDigest = record.verifiedDigest,
        )
    }
}

/** One validated checkpoint and the adapter-owned compare-and-set revision. */
public data class SafCommitJournalEntry(
    public val checkpoint: SafCommitCheckpoint,
    public val revision: Long,
) {
    init {
        require(revision >= 1L) { "journal revision must be positive" }
    }
}

/** Typed journal read: corrupt/unknown rows are not reported as missing. */
public sealed interface SafCommitJournalRead {
    public data object Missing : SafCommitJournalRead

    public data class Found(public val entry: SafCommitJournalEntry) : SafCommitJournalRead

    public data class Rejected(public val error: TransferStorageError) : SafCommitJournalRead
}

/** Typed write result. Conflicts never overwrite a newer checkpoint. */
public sealed interface SafCommitJournalWrite {
    public data class Saved(public val revision: Long) : SafCommitJournalWrite

    public data class Conflict(public val error: TransferStorageError.StateConflict) : SafCommitJournalWrite

    public data class Rejected(public val error: TransferStorageError) : SafCommitJournalWrite
}

/** The coordinator's persistence boundary; implementations must not log checkpoints. */
public interface SafCommitJournal {
    /** Loads and validates the latest checkpoint for the deterministic commit key. */
    public fun read(commitId: PartialIdentity): SafCommitJournalRead

    /** Atomically replaces the whole checkpoint/child set at an expected revision (0 means absent). */
    public fun write(checkpoint: SafCommitCheckpoint, expectedRevision: Long): SafCommitJournalWrite
}

/** Possible outcomes from the executable recovery entry point. */
public sealed interface SafCommitRecoveryOutcome {
    public data class ReadyToResume(public val checkpoint: SafCommitCheckpoint) : SafCommitRecoveryOutcome
    public data class Skipped(public val checkpoint: SafCommitCheckpoint) : SafCommitRecoveryOutcome {
        override fun toString(): String = "SafCommitRecoveryOutcome.Skipped(phase=${checkpoint.phase.id})"
    }
    public data class Committed(public val checkpoint: SafCommitCheckpoint) : SafCommitRecoveryOutcome
    /** Cancellation is terminal for copy/publication; exact cleanup is an explicit separate retry. */
    public data class Cancelled(public val checkpoint: SafCommitCheckpoint) : SafCommitRecoveryOutcome {
        override fun toString(): String =
            "SafCommitRecoveryOutcome.Cancelled(phase=${checkpoint.phase.id}, copiedBytes=${checkpoint.copiedBytes})"
    }
    public data class ReconciliationRequired(
        public val checkpoint: SafCommitCheckpoint,
        public val error: TransferStorageError,
        public val knownUris: List<String> = emptyList(),
    ) : SafCommitRecoveryOutcome {
        override fun toString(): String =
            "SafCommitRecoveryOutcome.ReconciliationRequired(phase=${checkpoint.phase.id}, error=$error, " +
                "knownIdentityCount=${knownUris.size})"
    }
    public data class Failed(
        public val checkpoint: SafCommitCheckpoint,
        public val error: TransferStorageError,
    ) : SafCommitRecoveryOutcome
}
