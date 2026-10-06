package app.morsecode.core.transfer.error

import app.morsecode.core.transfer.ProtocolLimits

private val SAFE_PERSISTENCE_REASON = Regex("[a-z0-9_]{1,64}")

/*
 * The transfer core never throws at a caller to report a protocol, storage or
 * transport problem: every failure is a value that can be stored, shown and
 * classified. Adapters (streams, sockets, Room) translate their own exceptions
 * into one of these.
 *
 * Each subclass answers the six questions later milestones need:
 *   retryable / final        – may the queue engine offer Retry for this item?
 *   local / remote           – did this device or the peer cause it?
 *   protocol / storage /
 *   transport / integrity /
 *   permission / remote /
 *   local-action / unknown   – which subsystem owns the fix?
 */

/** Which side of the connection a failure came from. */
public enum class ErrorOrigin(public val id: String) {
    /** Produced by this device: storage, permissions, user action. */
    LOCAL("local"),

    /** Produced by, or attributed to, the peer or the wire. */
    REMOTE("remote"),

    /** The cause could not be attributed; treated as local for recovery. */
    UNKNOWN("unknown"),
    ;

    public companion object {
        public fun fromId(id: String?): ErrorOrigin =
            entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** Which subsystem owns the fix for a failure. */
public enum class ErrorCategory(public val id: String) {
    PROTOCOL("protocol"),
    STORAGE("storage"),
    TRANSPORT("transport"),
    INTEGRITY("integrity"),
    PERMISSION("permission"),
    REMOTE("remote"),
    LOCAL_ACTION("local_action"),
    UNKNOWN("unknown"),
    ;

    public companion object {
        public fun fromId(id: String?): ErrorCategory =
            entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/**
 * Removes anything a transfer error is not allowed to carry: control bytes,
 * absolute filesystem paths, environment-looking secrets and over-long blobs.
 *
 * SHA-256 digests are redacted along with other opaque values. They remain in
 * the persistence codec where integrity decisions require them, never in an
 * error detail, log line, or shared diagnostic.
 */
public object ErrorDetailRedactor {
    private const val REDACTED = "[redacted]"

    private val ABSOLUTE_PATH = Regex("""(^|[\s=(])(/|\\)[^\s,;)]*""")
    private val WINDOWS_DRIVE = Regex("""\b[A-Za-z]:[\\/][^\s,;)]*""")
    private val LONG_OPAQUE = Regex("""[A-Za-z0-9+/=._-]{40,}""")
    private val CONTROL = Regex("""\p{Cntrl}""")

    /** Upper bound applied to every detail string before it is stored. */
    public const val MAX_LENGTH_BYTES: Int = ProtocolLimits.MAX_ERROR_DETAIL_BYTES

    /**
     * Cleans [raw] and truncates it to at most [maxBytes] UTF-8 bytes.
     *
     * Truncation walks backwards so a multi-byte character is never cut in
     * half.
     */
    public fun redact(raw: String, maxBytes: Int = MAX_LENGTH_BYTES): String {
        // Only the first line survives: an exception's stack frames would be a
        // stack-trace leak, and a multi-line detail is useless in a UI row or a
        // log line anyway.
        var text = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        text = CONTROL.replace(text, " ")
        text = WINDOWS_DRIVE.replace(text, REDACTED)
        text = ABSOLUTE_PATH.replace(text) { match ->
            val prefix = match.groupValues[1]
            "$prefix$REDACTED"
        }
        text = LONG_OPAQUE.replace(text, REDACTED)
        text = text.trim()
        return truncateToUtf8Bytes(text, maxBytes)
    }

    private fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        if (encodedLength(text) <= maxBytes) return text
        var end = text.length
        while (end > 0 && encodedLength(text.substring(0, end)) > maxBytes) {
            end--
        }
        return text.substring(0, end)
    }

    private fun encodedLength(text: String): Int = text.toByteArray(Charsets.UTF_8).size
}

/**
 * Sealed hierarchy of every failure the transfer core can report.
 *
 * [detail] is always run through [ErrorDetailRedactor] by the subclass, so no
 * error can leak a raw private path, a session secret, file contents or a stack
 * trace to the UI, the log or the peer.
 */
public sealed class TransferError {
    /** Stable, machine-readable identifier; safe to persist and compare. */
    public abstract val code: String

    /** Redacted, bounded, human-readable explanation. */
    public abstract val detail: String

    /** True when the same transfer can be attempted again. */
    public abstract val retryable: Boolean

    /** Convenience inverse of [retryable]; kept explicit at call sites. */
    public val isFinal: Boolean get() = !retryable

    /** Which side of the connection produced this failure. */
    public abstract val origin: ErrorOrigin

    /** Which subsystem owns the fix. */
    public abstract val category: ErrorCategory

    /** True when the failure is a framing, versioning or sequencing problem. */
    public val isProtocolRelated: Boolean get() = category == ErrorCategory.PROTOCOL

    /** True when the failure concerns reading the source or writing the target. */
    public val isStorageRelated: Boolean get() = category == ErrorCategory.STORAGE ||
        category == ErrorCategory.PERMISSION

    /** True when the failure concerns the connection rather than the bytes. */
    public val isTransportRelated: Boolean get() = category == ErrorCategory.TRANSPORT

    /** `"code: detail"`, safe to show in the UI or write to the log. */
    public fun describe(): String = "$code: $detail"

    /** Hostile input produced a frame this build cannot interpret. */
    public data class MalformedFrame(public val reason: String) : TransferError() {
        override val code: String = "malformed_frame"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** The peer speaks a protocol version this build cannot decode. */
    public data class ProtocolVersionMismatch(
        public val expected: Int,
        public val actual: Int,
    ) : TransferError() {
        override val code: String = "protocol_version_mismatch"
        override val detail: String =
            "peer offered protocol version $actual, this build supports $expected"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** A frame declared a total length above [ProtocolLimits.MAX_FRAME_SIZE_BYTES]. */
    public data class FrameTooLarge(public val size: Long, public val max: Int) : TransferError() {
        override val code: String = "frame_too_large"
        override val detail: String = "frame length $size exceeds the limit $max"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** A frame declared a payload above the negotiated or absolute maximum. */
    public data class PayloadTooLarge(public val size: Long, public val max: Int) : TransferError() {
        override val code: String = "payload_too_large"
        override val detail: String = "payload length $size exceeds the limit $max"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** A variable-length field exceeded its documented maximum. */
    public data class InvalidFieldLength(
        public val field: String,
        public val size: Long,
        public val max: Int,
    ) : TransferError() {
        override val code: String = "invalid_field_length"
        override val detail: String = "field $field length $size exceeds the limit $max"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** An identifier was blank, over-long or contained illegal characters. */
    public data class InvalidIdentifier(public val field: String) : TransferError() {
        override val code: String = "invalid_identifier"
        override val detail: String = "field $field is not a valid transfer identifier"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** A relative path was absolute, traversal-capable or otherwise unsafe. */
    public data class InvalidPath(public val reason: String) : TransferError() {
        override val code: String = "invalid_path"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** A frame arrived that the current state cannot accept. */
    public data class UnexpectedFrameType(
        public val expected: String,
        public val actual: String,
    ) : TransferError() {
        override val code: String = "unexpected_frame_type"
        override val detail: String = "expected $expected but received $actual"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /**
     * A chunk arrived whose sequence number does not match its offset.
     *
     * Retryable: re-negotiating from the confirmed offset resynchronises the
     * peers without discarding the partial file.
     */
    public data class UnexpectedSequence(
        public val expected: Long,
        public val actual: Long,
    ) : TransferError() {
        override val code: String = "unexpected_sequence"
        override val detail: String = "expected sequence $expected but received $actual"
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** A chunk arrived at an offset the receiver was not expecting. */
    public data class UnexpectedOffset(
        public val expected: Long,
        public val actual: Long,
    ) : TransferError() {
        override val code: String = "unexpected_offset"
        override val detail: String = "expected offset $expected but received $actual"
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** The peer proposed a chunk size this build cannot honour. */
    public data class UnsupportedChunkSize(
        public val size: Long,
        public val min: Int,
        public val max: Int,
    ) : TransferError() {
        override val code: String = "unsupported_chunk_size"
        override val detail: String = "chunk size $size is outside $min..$max"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** The resume proposal disagreed with the stored descriptor. */
    public data class DescriptorMismatch(public val field: String) : TransferError() {
        override val code: String = "descriptor_mismatch"
        override val detail: String = "stored descriptor disagrees on $field"
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.PROTOCOL
    }

    /** A chunk's CRC32 did not match its payload; the chunk is discarded. */
    public data class ChunkChecksumMismatch(
        public val offset: Long,
        public val expectedCrc: Long,
        public val actualCrc: Long,
    ) : TransferError() {
        override val code: String = "chunk_checksum_mismatch"
        override val detail: String =
            "chunk at offset $offset failed its CRC32 check"
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.INTEGRITY
    }

    /** The full-file SHA-256 did not match the descriptor. */
    public data class FileChecksumMismatch(
        public val expectedHex: String,
        public val actualHex: String,
    ) : TransferError() {
        override val code: String = "file_checksum_mismatch"
        override val detail: String = "full-file SHA-256 differs from the descriptor"
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.INTEGRITY

        override fun toString(): String = "FileChecksumMismatch([digests redacted])"
    }

    /** The source stream could not be opened. */
    public data class SourceUnavailable(public val reason: String) : TransferError() {
        override val code: String = "source_unavailable"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.STORAGE
    }

    /** The partial destination file could not be created or written. */
    public data class DestinationUnavailable(public val reason: String) : TransferError() {
        override val code: String = "destination_unavailable"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.STORAGE
    }

    /** The destination filled up mid-transfer. */
    public data class StorageFull(public val requiredBytes: Long) : TransferError() {
        override val code: String = "storage_full"
        override val detail: String = "the destination ran out of space for $requiredBytes bytes"
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.STORAGE
    }

    /** A URI permission grant was revoked while the transfer was running. */
    public data class PermissionRevoked(public val reason: String) : TransferError() {
        override val code: String = "permission_revoked"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.PERMISSION
    }

    /** The transport dropped while the transfer was in flight. */
    public data class TransportDisconnected(public val reason: String) : TransferError() {
        override val code: String = "transport_disconnected"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.TRANSPORT
    }

    /** The peer declined the transfer. */
    public data class PeerRejected(public val reason: String) : TransferError() {
        override val code: String = "peer_rejected"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.REMOTE
    }

    /** This device cancelled the transfer. */
    public data object CancelledLocally : TransferError() {
        override val code: String = "cancelled_locally"
        override val detail: String = "cancelled on this device"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.LOCAL_ACTION
    }

    /** The peer cancelled the transfer. */
    public data object CancelledRemotely : TransferError() {
        override val code: String = "cancelled_remotely"
        override val detail: String = "cancelled by the peer"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.REMOTE
        override val category: ErrorCategory = ErrorCategory.REMOTE
    }

    /** The retry ceiling was reached; the failure can no longer be retried. */
    public data class RetryLimitReached(public val limit: Int) : TransferError() {
        override val code: String = "retry_limit_reached"
        override val detail: String = "the retry limit of $limit attempts was reached"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.LOCAL_ACTION
    }

    /** A persisted snapshot was written by an incompatible build. */
    public data class SnapshotVersionUnsupported(
        public val found: Int,
        public val maxSupported: Int,
    ) : TransferError() {
        override val code: String = "snapshot_version_unsupported"
        override val detail: String =
            "snapshot version $found is outside the supported range ending at $maxSupported"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.STORAGE
    }

    /** A stored row exists but failed strict validation. The retained reason is a safe token only. */
    public class PersistedSnapshotInvalid(reason: String) : TransferError() {
        public val reason: String = reason.takeIf { SAFE_PERSISTENCE_REASON.matches(it) } ?: "malformed_row"
        override val code: String = "persisted_snapshot_invalid"
        override val detail: String = "stored transfer state is invalid ($reason)"
        override val retryable: Boolean = false
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.STORAGE

        override fun equals(other: Any?): Boolean = other is PersistedSnapshotInvalid && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "PersistedSnapshotInvalid(reason=$reason)"
    }

    /** Compare-and-set rejected a stale or gapped persistence revision. */
    public class PersistenceConflict(reason: String) : TransferError() {
        public val reason: String = reason.takeIf { SAFE_PERSISTENCE_REASON.matches(it) } ?: "revision_conflict"
        override val code: String = "persistence_conflict"
        override val detail: String = "transfer state changed concurrently ($reason)"
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.STORAGE

        override fun equals(other: Any?): Boolean = other is PersistenceConflict && reason == other.reason
        override fun hashCode(): Int = reason.hashCode()
        override fun toString(): String = "PersistenceConflict(reason=$reason)"
    }

    /** Local database failure with storage-full kept distinct from ordinary I/O. */
    public class PersistenceFailure(
        operation: String,
        public val storageFull: Boolean,
    ) : TransferError() {
        public val operation: String = operation.takeIf { it.matches(Regex("[a-z_]{1,24}")) } ?: "saved"
        override val code: String = if (storageFull) "persistence_storage_full" else "persistence_io"
        override val detail: String = if (storageFull) {
            "the device ran out of space while saving transfer state"
        } else {
            "transfer state could not be $operation"
        }
        override val retryable: Boolean = !storageFull
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.STORAGE

        override fun equals(other: Any?): Boolean =
            other is PersistenceFailure && operation == other.operation && storageFull == other.storageFull
        override fun hashCode(): Int = 31 * operation.hashCode() + storageFull.hashCode()
        override fun toString(): String = "PersistenceFailure(code=$code)"
    }

    /**
     * A catch-all for a failure an adapter could not classify.
     *
     * [detail] is redacted, so an exception message never reaches the UI.
     */
    public data class UnexpectedInternal(public val reason: String) : TransferError() {
        override val code: String = "unexpected_internal"
        override val detail: String = ErrorDetailRedactor.redact(reason)
        override val retryable: Boolean = true
        override val origin: ErrorOrigin = ErrorOrigin.LOCAL
        override val category: ErrorCategory = ErrorCategory.UNKNOWN
    }

    /**
     * An error rehydrated from a persisted snapshot.
     *
     * Snapshots store the classification rather than the concrete subtype so a
     * new build can still restore an old row; every decision the reducer and
     * the scheduler make depends only on these five fields.
     */
    public data class RestoredError(
        override val code: String,
        override val detail: String,
        override val retryable: Boolean,
        override val origin: ErrorOrigin,
        override val category: ErrorCategory,
    ) : TransferError()

    public companion object {
        /**
         * Rebuilds an error from its persisted form, keeping the detail
         * redacted and bounded.
         */
        public fun restore(
            code: String,
            detail: String,
            retryable: Boolean,
            origin: ErrorOrigin,
            category: ErrorCategory,
        ): TransferError = RestoredError(
            code = code,
            detail = ErrorDetailRedactor.redact(detail),
            retryable = retryable,
            origin = origin,
            category = category,
        )
    }
}
