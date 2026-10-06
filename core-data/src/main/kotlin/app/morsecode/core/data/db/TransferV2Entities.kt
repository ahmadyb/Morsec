package app.morsecode.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** One reducer snapshot stored as typed columns; no serialized object or opaque payload is persisted. */
@Entity(
    tableName = "transfer_snapshots",
    indices = [
        Index(value = ["session_id", "snapshot_state"]),
    ],
)
public data class TransferSnapshotEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "transfer_id")
    public val transferId: String,
    @ColumnInfo(name = "session_id")
    public val sessionId: String,
    @ColumnInfo(name = "batch_id")
    public val batchId: String,
    @ColumnInfo(name = "recipient_id")
    public val recipientId: String?,
    public val direction: String,
    @ColumnInfo(name = "snapshot_state")
    public val snapshotState: String,
    @ColumnInfo(name = "snapshot_version")
    public val snapshotVersion: Long,
    /** Latest durable receiver-confirmed offset; may advance between reducer snapshots. */
    @ColumnInfo(name = "confirmed_bytes")
    public val confirmedBytes: Long,
    @ColumnInfo(name = "optimistic_bytes")
    public val optimisticBytes: Long,
    @ColumnInfo(name = "last_acknowledged_sequence")
    public val lastAcknowledgedSequence: Long?,
    @ColumnInfo(name = "retry_count")
    public val retryCount: Int,
    @ColumnInfo(name = "failure_code")
    public val failureCode: String?,
    @ColumnInfo(name = "failure_detail")
    public val failureDetail: String?,
    @ColumnInfo(name = "failure_retryable")
    public val failureRetryable: Int?,
    @ColumnInfo(name = "failure_origin")
    public val failureOrigin: String?,
    @ColumnInfo(name = "failure_category")
    public val failureCategory: String?,
    @ColumnInfo(name = "remote_paused")
    public val remotePaused: Int,
    @ColumnInfo(name = "queue_order")
    public val queueOrder: Long,
    @ColumnInfo(name = "file_id")
    public val fileId: String,
    @ColumnInfo(name = "display_name")
    public val displayName: String,
    @ColumnInfo(name = "relative_path")
    public val relativePath: String,
    @ColumnInfo(name = "mime_type")
    public val mimeType: String,
    @ColumnInfo(name = "total_bytes")
    public val totalBytes: Long,
    @ColumnInfo(name = "last_modified_epoch_millis")
    public val lastModifiedEpochMillis: Long?,
    @ColumnInfo(name = "is_folder_archive")
    public val isFolderArchive: Int,
    @ColumnInfo(name = "expected_sha256_hex")
    public val expectedSha256Hex: String?,
    @ColumnInfo(name = "chunk_size")
    public val chunkSize: Int,
    @ColumnInfo(name = "protocol_version")
    public val protocolVersion: Int,
    @ColumnInfo(name = "verification_expected_digest_hex")
    public val verificationExpectedDigestHex: String?,
    @ColumnInfo(name = "verification_observed_digest_hex")
    public val verificationObservedDigestHex: String?,
    @ColumnInfo(name = "verification_started_snapshot_version")
    public val verificationStartedSnapshotVersion: Long?,
    /** Adapter-owned CAS token; distinct from the reducer's snapshot version. */
    @ColumnInfo(name = "row_revision")
    public val rowRevision: Long,
) {
    override fun toString(): String =
        "TransferSnapshotEntity(state=$snapshotState, version=$snapshotVersion, " +
            "confirmedBytes=$confirmedBytes, rowRevision=$rowRevision, [identity redacted])"
}

/**
 * Parent row for a staging partial and its complete SAF checkpoint. No filesystem
 * path or content is stored. SAF identities are exact provider URI/document-ID
 * pairs, and digests use canonical lowercase 64-character SHA-256 hex. Child
 * counts detect a missing row as well as malformed or extra child rows.
 */
@Entity(
    tableName = "transfer_partials",
    indices = [
        Index(value = ["staging_identity"], unique = true),
        Index(value = ["session_id", "checkpoint_phase"]),
        Index(value = ["transfer_id"]),
    ],
)
public data class TransferPartialEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "commit_id")
    public val commitId: String,
    @ColumnInfo(name = "staging_identity")
    public val stagingIdentity: String,
    @ColumnInfo(name = "session_id")
    public val sessionId: String,
    @ColumnInfo(name = "transfer_id")
    public val transferId: String,
    @ColumnInfo(name = "checkpoint_version")
    public val checkpointVersion: Int,
    @ColumnInfo(name = "journal_revision")
    public val journalRevision: Long,
    @ColumnInfo(name = "rename_history_count")
    public val renameHistoryCount: Int,
    @ColumnInfo(name = "pending_cleanup_count")
    public val pendingCleanupCount: Int,
    @ColumnInfo(name = "strategy_id")
    public val strategyId: String,
    @ColumnInfo(name = "duplicate_policy")
    public val duplicatePolicy: String,
    @ColumnInfo(name = "grant_id")
    public val grantId: String,
    @ColumnInfo(name = "tree_uri")
    public val treeUri: String,
    public val authority: String,
    @ColumnInfo(name = "root_document_id")
    public val rootDocumentId: String,
    @ColumnInfo(name = "parent_document_id")
    public val parentDocumentId: String,
    @ColumnInfo(name = "expected_final_name")
    public val expectedFinalName: String,
    @ColumnInfo(name = "expected_size_bytes")
    public val expectedSizeBytes: Long,
    @ColumnInfo(name = "expected_digest_hex")
    public val expectedDigestHex: String?,
    @ColumnInfo(name = "checkpoint_phase")
    public val checkpointPhase: String,
    @ColumnInfo(name = "temporary_uri")
    public val temporaryUri: String?,
    @ColumnInfo(name = "temporary_document_id")
    public val temporaryDocumentId: String?,
    @ColumnInfo(name = "existing_uri")
    public val existingUri: String?,
    @ColumnInfo(name = "existing_document_id")
    public val existingDocumentId: String?,
    @ColumnInfo(name = "backup_uri")
    public val backupUri: String?,
    @ColumnInfo(name = "backup_document_id")
    public val backupDocumentId: String?,
    @ColumnInfo(name = "returned_rename_uri")
    public val returnedRenameUri: String?,
    @ColumnInfo(name = "returned_rename_identity_uri")
    public val returnedRenameIdentityUri: String?,
    @ColumnInfo(name = "returned_rename_document_id")
    public val returnedRenameDocumentId: String?,
    @ColumnInfo(name = "final_uri")
    public val finalUri: String?,
    @ColumnInfo(name = "final_document_id")
    public val finalDocumentId: String?,
    @ColumnInfo(name = "copied_bytes")
    public val copiedBytes: Long,
    @ColumnInfo(name = "staging_released")
    public val stagingReleased: Boolean,
    @ColumnInfo(name = "last_failure_category")
    public val lastFailureCategory: String?,
    @ColumnInfo(name = "last_failure_code")
    public val lastFailureCode: String?,
    @ColumnInfo(name = "unresolved_rename_phase")
    public val unresolvedRenamePhase: String?,
    @ColumnInfo(name = "verified_digest_hex")
    public val verifiedDigestHex: String?,
) {
    override fun toString(): String =
        "TransferPartialEntity(version=$checkpointVersion, phase=$checkpointPhase, " +
            "copiedBytes=$copiedBytes, expectedSizeBytes=$expectedSizeBytes, " +
            "journalRevision=$journalRevision, [identities redacted])"
}

/** Bounded rename evidence, ordered by its zero-based sequence. */
@Entity(
    tableName = "saf_rename_history",
    primaryKeys = ["commit_id", "sequence"],
    foreignKeys = [
        // Only this checkpoint's owned evidence rows may cascade on parent deletion.
        ForeignKey(
            entity = TransferPartialEntity::class,
            parentColumns = ["commit_id"],
            childColumns = ["commit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["commit_id", "phase"], unique = true),
    ],
)
public data class SafRenameHistoryEntity(
    @ColumnInfo(name = "commit_id")
    public val commitId: String,
    public val sequence: Int,
    public val phase: String,
    @ColumnInfo(name = "before_uri")
    public val beforeUri: String,
    @ColumnInfo(name = "before_document_id")
    public val beforeDocumentId: String,
    @ColumnInfo(name = "returned_uri")
    public val returnedUri: String?,
    @ColumnInfo(name = "returned_document_id")
    public val returnedDocumentId: String?,
    @ColumnInfo(name = "reconciliation_id")
    public val reconciliationId: String?,
) {
    override fun toString(): String = "SafRenameHistoryEntity(sequence=$sequence, phase=$phase, [identity redacted])"
}

/** Exact pending deletion work owned by one checkpoint; never keyed by filename. */
@Entity(
    tableName = "saf_pending_cleanup",
    primaryKeys = ["commit_id", "sequence"],
    foreignKeys = [
        // Cleanup rows are owned exclusively by one journal parent.
        ForeignKey(
            entity = TransferPartialEntity::class,
            parentColumns = ["commit_id"],
            childColumns = ["commit_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["commit_id", "cleanup_type"], unique = true),
        Index(value = ["commit_id", "document_uri", "document_id"], unique = true),
    ],
)
public data class SafPendingCleanupEntity(
    @ColumnInfo(name = "commit_id")
    public val commitId: String,
    public val sequence: Int,
    @ColumnInfo(name = "cleanup_type")
    public val cleanupType: String,
    @ColumnInfo(name = "document_uri")
    public val documentUri: String?,
    @ColumnInfo(name = "document_id")
    public val documentId: String?,
    @ColumnInfo(name = "staging_identity")
    public val stagingIdentity: String?,
) {
    override fun toString(): String = "SafPendingCleanupEntity(sequence=$sequence, type=$cleanupType, [identity redacted])"
}
