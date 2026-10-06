package app.morsecode.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Durable state. Everything the UI shows about transfers, history, logs, crash
 * reports, recent devices and browser sessions is stored here, which is what
 * lets a session survive process recreation and lets progress be reported from
 * the engine instead of from a timer.
 */

/** A duplex session with one peer (or one broadcast delivery). */
@Entity(
    tableName = "transfer_sessions",
    indices = [Index("peer_id"), Index("broadcast_id"), Index("phase")],
)
public data class TransferSessionEntity(
    @PrimaryKey public val sessionId: String,
    @ColumnInfo(name = "peer_id") public val peerId: String,
    @ColumnInfo(name = "peer_name") public val peerName: String,
    @ColumnInfo(name = "peer_endpoint") public val peerEndpoint: String,
    @ColumnInfo(name = "peer_transport") public val peerTransport: String,
    @ColumnInfo(name = "peer_device_kind") public val peerDeviceKind: String,
    @ColumnInfo(name = "peer_detail") public val peerDetail: String,
    @ColumnInfo(name = "peer_app_version") public val peerAppVersion: String? = null,
    @ColumnInfo(name = "peer_supports_resume") public val peerSupportsResume: Boolean = false,
    @ColumnInfo(name = "peer_supports_encryption") public val peerSupportsEncryption: Boolean = false,
    @ColumnInfo(name = "broadcast_id") public val broadcastId: String?,
    public val phase: String,
    @ColumnInfo(name = "pause_all") public val pauseAll: Boolean = false,
    @ColumnInfo(name = "started_at") public val startedEpochMillis: Long = 0L,
    @ColumnInfo(name = "ended_at") public val endedEpochMillis: Long = 0L,
    @ColumnInfo(name = "failure_reason") public val failureReason: String? = null,
)

/**
 * One file inside a session.
 *
 * `confirmed_bytes` only ever advances on a receiver acknowledgement, so a
 * resume restarts from data the peer actually has.
 */
@Entity(
    tableName = "transfer_items",
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id"), Index("batch_id"), Index("state"), Index("direction")],
)
public data class TransferItemEntity(
    @PrimaryKey @ColumnInfo(name = "transfer_id") public val transferId: String,
    @ColumnInfo(name = "session_id") public val sessionId: String,
    @ColumnInfo(name = "batch_id") public val batchId: String,
    public val direction: String,
    @ColumnInfo(name = "display_name") public val displayName: String,
    @ColumnInfo(name = "relative_path") public val relativePath: String = "",
    @ColumnInfo(name = "mime_type") public val mimeType: String? = null,
    public val kind: String = "other",
    @ColumnInfo(name = "total_bytes") public val totalBytes: Long = 0L,
    @ColumnInfo(name = "last_modified") public val lastModifiedEpochMillis: Long = 0L,
    @ColumnInfo(name = "is_folder_archive") public val isFolderArchive: Boolean = false,
    public val state: String,
    @ColumnInfo(name = "confirmed_bytes") public val confirmedBytes: Long = 0L,
    @ColumnInfo(name = "bytes_per_second") public val bytesPerSecond: Long = 0L,
    @ColumnInfo(name = "sha256_hex") public val sha256Hex: String? = null,
    @ColumnInfo(name = "failure_reason") public val failureReason: String? = null,
    @ColumnInfo(name = "retry_count") public val retryCount: Int = 0,
    @ColumnInfo(name = "queued_at") public val queuedEpochMillis: Long = 0L,
    @ColumnInfo(name = "finished_at") public val finishedEpochMillis: Long = 0L,
    @ColumnInfo(name = "result_uri") public val resultUriString: String? = null,
    @ColumnInfo(name = "recipient_peer_id") public val recipientPeerId: String? = null,
    @ColumnInfo(name = "source_uri") public val sourceUriString: String? = null,
    @ColumnInfo(name = "target_dir") public val targetDirectory: String? = null,
    /** Ordering inside the queue; lower runs first. */
    @ColumnInfo(name = "queue_position") public val queuePosition: Int = 0,
)

/**
 * Completed (or permanently failed) transfers, kept after their session ends.
 * Received and sent rows live in one table and are split by `direction`.
 */
@Entity(
    tableName = "history_entries",
    indices = [Index("direction"), Index("finished_at"), Index("state")],
)
public data class HistoryEntryEntity(
    @PrimaryKey @ColumnInfo(name = "history_id") public val historyId: String,
    @ColumnInfo(name = "transfer_id") public val transferId: String,
    @ColumnInfo(name = "session_id") public val sessionId: String?,
    public val direction: String,
    @ColumnInfo(name = "display_name") public val displayName: String,
    @ColumnInfo(name = "mime_type") public val mimeType: String? = null,
    public val kind: String = "other",
    @ColumnInfo(name = "total_bytes") public val totalBytes: Long = 0L,
    @ColumnInfo(name = "peer_id") public val peerId: String?,
    @ColumnInfo(name = "peer_name") public val peerName: String?,
    public val state: String,
    @ColumnInfo(name = "failure_reason") public val failureReason: String? = null,
    @ColumnInfo(name = "sha256_hex") public val sha256Hex: String? = null,
    @ColumnInfo(name = "finished_at") public val finishedEpochMillis: Long = 0L,
    @ColumnInfo(name = "result_uri") public val resultUriString: String? = null,
    @ColumnInfo(name = "broadcast_id") public val broadcastId: String? = null,
)

/** A device the user has transferred with, shown as "Recent devices". */
@Entity(tableName = "recent_devices", indices = [Index(value = ["peer_id"], unique = true)])
public data class RecentDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "peer_id") public val peerId: String,
    @ColumnInfo(name = "display_name") public val displayName: String,
    public val transport: String,
    @ColumnInfo(name = "device_kind") public val deviceKind: String,
    public val detail: String = "",
    @ColumnInfo(name = "last_seen") public val lastSeenEpochMillis: Long = 0L,
    @ColumnInfo(name = "last_summary") public val lastSummary: String? = null,
    @ColumnInfo(name = "interaction_count") public val interactionCount: Int = 0,
)

/** Application log rows. Secrets and absolute private paths are redacted first. */
@Entity(tableName = "log_entries", indices = [Index("timestamp"), Index("level")])
public data class LogEntryEntity(
    @PrimaryKey(autoGenerate = true) public val id: Long = 0L,
    public val timestamp: Long,
    public val level: String,
    public val tag: String,
    public val message: String,
    @ColumnInfo(name = "error_id") public val errorId: String? = null,
)

/** Locally stored crash reports. Never uploaded. */
@Entity(tableName = "crash_reports", indices = [Index("occurred_at")])
public data class CrashReportEntity(
    @PrimaryKey(autoGenerate = true) public val id: Long = 0L,
    @ColumnInfo(name = "occurred_at") public val occurredEpochMillis: Long,
    public val component: String,
    @ColumnInfo(name = "exception_type") public val exceptionType: String,
    public val message: String?,
    @ColumnInfo(name = "stack_trace") public val stackTrace: String,
    @ColumnInfo(name = "app_version") public val appVersion: String,
    @ColumnInfo(name = "version_code") public val versionCode: Int,
    @ColumnInfo(name = "sdk_int") public val androidSdkInt: Int,
    @ColumnInfo(name = "device_model") public val deviceModel: String,
    @ColumnInfo(name = "recovery_note") public val recoveryNote: String? = null,
)

/**
 * A WebShare browser session. Only the SHA-256 digest of the bearer token is
 * stored: the raw token exists in memory and in the browser, never on disk.
 */
@Entity(tableName = "browser_sessions", indices = [Index("state"), Index("token_digest")])
public data class BrowserSessionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") public val sessionId: String,
    @ColumnInfo(name = "token_digest") public val tokenDigest: String,
    @ColumnInfo(name = "user_agent") public val userAgent: String?,
    @ColumnInfo(name = "remote_address") public val remoteAddress: String,
    public val state: String,
    @ColumnInfo(name = "requested_at") public val requestedEpochMillis: Long = 0L,
    @ColumnInfo(name = "accepted_at") public val acceptedEpochMillis: Long = 0L,
    @ColumnInfo(name = "revoked_at") public val revokedEpochMillis: Long = 0L,
    @ColumnInfo(name = "last_activity") public val lastActivityEpochMillis: Long = 0L,
    @ColumnInfo(name = "bytes_downloaded") public val bytesDownloaded: Long = 0L,
    @ColumnInfo(name = "bytes_uploaded") public val bytesUploaded: Long = 0L,
    @ColumnInfo(name = "request_count") public val requestCount: Int = 0,
)

/** Persisted Storage Access Framework tree grants. */
@Entity(tableName = "saf_grants", indices = [Index(value = ["tree_uri"], unique = true)])
public data class SafGrantEntity(
    @PrimaryKey(autoGenerate = true) public val id: Long = 0L,
    @ColumnInfo(name = "tree_uri") public val treeUri: String,
    @ColumnInfo(name = "display_name") public val displayName: String,
    @ColumnInfo(name = "granted_at") public val grantedEpochMillis: Long = 0L,
    @ColumnInfo(name = "read_write") public val readWrite: Boolean = true,
) {
    override fun toString(): String =
        "SafGrantEntity(readWrite=$readWrite, [grant identity redacted])"
}

/** In-flight resumable upload/download offsets owned by the WebShare server. */
@Entity(tableName = "web_transfers", indices = [Index("upload_id")])
public data class WebTransferEntity(
    @PrimaryKey @ColumnInfo(name = "upload_id") public val uploadId: String,
    @ColumnInfo(name = "session_id") public val browserSessionId: String,
    @ColumnInfo(name = "file_name") public val fileName: String,
    @ColumnInfo(name = "target_dir") public val targetDirectory: String,
    @ColumnInfo(name = "total_bytes") public val totalBytes: Long,
    @ColumnInfo(name = "received_bytes") public val receivedBytes: Long = 0L,
    @ColumnInfo(name = "partial_path") public val partialPath: String,
    public val state: String,
    @ColumnInfo(name = "updated_at") public val updatedEpochMillis: Long = 0L,
)
