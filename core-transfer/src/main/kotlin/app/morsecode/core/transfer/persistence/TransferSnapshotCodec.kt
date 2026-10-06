package app.morsecode.core.transfer.persistence

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorOrigin
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RecipientId
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.model.VerificationInfo

/*
 * Versioned, Room-independent snapshot serialisation.
 *
 * This is a hand-rolled line format rather than kotlinx.serialization or a
 * generated adapter, for three reasons that matter here:
 *
 *  * Persistence must be *bounded*. Every field is length-checked on the way in,
 *    so a corrupt row cannot make a decoder allocate something huge.
 *  * Persistence must be *rejecting*. An unknown key or a newer version is an
 *    error, not a default: a snapshot written by a future build describes state
 *    this build does not understand, and guessing is how a resumable offset
 *    becomes a corrupt file.
 *  * Persistence must not couple the core to Room. There is no annotation, no
 *    generated code and no Android class in this file; `TransferSnapshotStore`
 *    is the seam an adapter implements.
 *
 * The format is `key=value` lines in UTF-8, one snapshot per string. A null value
 * is an empty field. Text is escaped so a value can never forge a line or a key.
 */

public object TransferSnapshotCodec {

    /** Version stamp written into every snapshot this build produces. */
    public const val VERSION: Int = ProtocolLimits.SNAPSHOT_VERSION

    private const val KEY_VERSION = "v"
    private const val KEY_TRANSFER_ID = "transferId"
    private const val KEY_SESSION_ID = "sessionId"
    private const val KEY_BATCH_ID = "batchId"
    private const val KEY_RECIPIENT_ID = "recipientId"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_FILE_ID = "fileId"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_RELATIVE_PATH = "relativePath"
    private const val KEY_MIME_TYPE = "mimeType"
    private const val KEY_TOTAL_BYTES = "totalBytes"
    private const val KEY_LAST_MODIFIED = "lastModified"
    private const val KEY_KIND = "kind"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_CHUNK_SIZE = "chunkSize"
    private const val KEY_PROTOCOL_VERSION = "protocolVersion"
    private const val KEY_STATE = "state"
    private const val KEY_CONFIRMED_BYTES = "confirmedBytes"
    private const val KEY_OPTIMISTIC_BYTES = "optimisticBytes"
    private const val KEY_LAST_ACK_SEQUENCE = "lastAckSequence"
    private const val KEY_RETRY_COUNT = "retryCount"
    private const val KEY_REMOTE_PAUSED = "remotePaused"
    private const val KEY_SNAPSHOT_VERSION = "snapshotVersion"
    private const val KEY_QUEUE_ORDER = "queueOrder"
    private const val KEY_ERROR_CODE = "errorCode"
    private const val KEY_ERROR_DETAIL = "errorDetail"
    private const val KEY_ERROR_RETRYABLE = "errorRetryable"
    private const val KEY_ERROR_ORIGIN = "errorOrigin"
    private const val KEY_ERROR_CATEGORY = "errorCategory"
    private const val KEY_VERIFICATION_EXPECTED = "verificationExpected"
    private const val KEY_VERIFICATION_OBSERVED = "verificationObserved"
    private const val KEY_VERIFICATION_STARTED = "verificationStarted"

    private val KNOWN_KEYS: Set<String> = setOf(
        KEY_VERSION, KEY_TRANSFER_ID, KEY_SESSION_ID, KEY_BATCH_ID, KEY_RECIPIENT_ID,
        KEY_DIRECTION, KEY_FILE_ID, KEY_DISPLAY_NAME, KEY_RELATIVE_PATH, KEY_MIME_TYPE,
        KEY_TOTAL_BYTES, KEY_LAST_MODIFIED, KEY_KIND, KEY_SHA256, KEY_CHUNK_SIZE,
        KEY_PROTOCOL_VERSION, KEY_STATE, KEY_CONFIRMED_BYTES, KEY_OPTIMISTIC_BYTES,
        KEY_LAST_ACK_SEQUENCE, KEY_RETRY_COUNT, KEY_REMOTE_PAUSED, KEY_SNAPSHOT_VERSION,
        KEY_QUEUE_ORDER, KEY_ERROR_CODE, KEY_ERROR_DETAIL, KEY_ERROR_RETRYABLE,
        KEY_ERROR_ORIGIN, KEY_ERROR_CATEGORY, KEY_VERIFICATION_EXPECTED,
        KEY_VERIFICATION_OBSERVED, KEY_VERIFICATION_STARTED,
    )

    /** Hard cap before splitting/decoding untrusted persisted text. */
    public const val MAX_SERIALIZED_BYTES: Int = 64 * 1024

    /** Serialises one snapshot; the result never contains a newline or a NUL. */
    public fun serialize(snapshot: TransferSnapshot): String = buildString {
        append(KEY_VERSION).append('=').append(VERSION).append('\n')
        append(KEY_TRANSFER_ID).append('=').append(escape(snapshot.transferId.value)).append('\n')
        append(KEY_SESSION_ID).append('=').append(escape(snapshot.sessionId.value)).append('\n')
        append(KEY_BATCH_ID).append('=').append(escape(snapshot.batchId.value)).append('\n')
        append(KEY_RECIPIENT_ID).append('=').append(escape(snapshot.recipientId?.value ?: "")).append('\n')
        append(KEY_DIRECTION).append('=').append(snapshot.direction.name).append('\n')
        append(KEY_FILE_ID).append('=').append(escape(snapshot.descriptor.fileId.value)).append('\n')
        append(KEY_DISPLAY_NAME).append('=').append(escape(snapshot.descriptor.displayName)).append('\n')
        append(KEY_RELATIVE_PATH).append('=')
            .append(escape(snapshot.descriptor.relativePath.value)).append('\n')
        append(KEY_MIME_TYPE).append('=').append(escape(snapshot.descriptor.mimeType)).append('\n')
        append(KEY_TOTAL_BYTES).append('=').append(snapshot.descriptor.totalBytes).append('\n')
        append(KEY_LAST_MODIFIED).append('=')
            .append(snapshot.descriptor.lastModifiedEpochMillis?.toString() ?: "").append('\n')
        append(KEY_KIND).append('=')
            .append(if (snapshot.descriptor.isFolderArchive) "folder" else "file").append('\n')
        append(KEY_SHA256).append('=').append(snapshot.descriptor.expectedSha256?.hex ?: "").append('\n')
        append(KEY_CHUNK_SIZE).append('=').append(snapshot.descriptor.chunkSize.value).append('\n')
        append(KEY_PROTOCOL_VERSION).append('=')
            .append(snapshot.descriptor.protocolVersion.value).append('\n')
        append(KEY_STATE).append('=').append(snapshot.state.id).append('\n')
        append(KEY_CONFIRMED_BYTES).append('=').append(snapshot.confirmedBytes).append('\n')
        append(KEY_OPTIMISTIC_BYTES).append('=').append(snapshot.optimisticBytes).append('\n')
        append(KEY_LAST_ACK_SEQUENCE).append('=')
            .append(snapshot.lastAcknowledgedSequence?.toString() ?: "").append('\n')
        append(KEY_RETRY_COUNT).append('=').append(snapshot.retryCount).append('\n')
        append(KEY_REMOTE_PAUSED).append('=').append(snapshot.remotePaused).append('\n')
        append(KEY_SNAPSHOT_VERSION).append('=').append(snapshot.snapshotVersion).append('\n')
        append(KEY_QUEUE_ORDER).append('=').append(snapshot.queueOrder).append('\n')
        val failure = snapshot.failure
        append(KEY_ERROR_CODE).append('=').append(escape(failure?.code ?: "")).append('\n')
        append(KEY_ERROR_DETAIL).append('=').append(escape(failure?.detail ?: "")).append('\n')
        append(KEY_ERROR_RETRYABLE).append('=').append(failure?.retryable ?: false).append('\n')
        append(KEY_ERROR_ORIGIN).append('=').append(failure?.origin?.id ?: "").append('\n')
        append(KEY_ERROR_CATEGORY).append('=').append(failure?.category?.id ?: "").append('\n')
        val verification = snapshot.verification
        append(KEY_VERIFICATION_EXPECTED).append('=')
            .append(verification?.expectedDigest?.hex ?: "").append('\n')
        append(KEY_VERIFICATION_OBSERVED).append('=')
            .append(verification?.observedDigest?.hex ?: "").append('\n')
        append(KEY_VERIFICATION_STARTED).append('=')
            .append(verification?.startedSnapshotVersion?.toString() ?: "").append('\n')
    }

    /**
     * Restores one snapshot.
     *
     * Optimistic bytes are restored exactly as written and are never promoted to
     * confirmed bytes: the whole point of keeping the two apart is that a process
     * restart must not turn "we wrote this" into "they received this".
     */
    public fun deserialize(raw: String): SnapshotDecodeResult {
        if (raw.length > MAX_SERIALIZED_BYTES ||
            raw.toByteArray(Charsets.UTF_8).size > MAX_SERIALIZED_BYTES
        ) {
            return invalid("snapshot_too_large")
        }
        val fields = LinkedHashMap<String, String>()
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            val split = line.indexOf('=')
            if (split <= 0) return invalid("snapshot_line_missing_key")
            val key = line.substring(0, split)
            if (key !in KNOWN_KEYS) return invalid("snapshot_unknown_key")
            if (fields.put(key, unescape(line.substring(split + 1))) != null) {
                return invalid("snapshot_duplicate_key")
            }
        }
        if (fields.keys != KNOWN_KEYS) return invalid("snapshot_fields_incomplete")
        return try {
            val version = require(fields, KEY_VERSION).toIntOrNull()
                ?: return invalid("snapshot_version_invalid")
            if (version > VERSION || version < ProtocolLimits.SNAPSHOT_VERSION_MIN) {
                return SnapshotDecodeResult.Invalid(
                    TransferError.SnapshotVersionUnsupported(version, VERSION),
                )
            }

            // A row written by a build that spoke a newer protocol is not a
            // corrupt row: it is a version this build cannot decode, and it gets
            // the same typed refusal the wire gives it rather than being folded
            // into "malformed" by the IllegalArgumentException catch below.
            val protocolVersion = ProtocolVersion.orNull(requireInt(fields, KEY_PROTOCOL_VERSION))
                ?: return SnapshotDecodeResult.Invalid(
                    TransferError.ProtocolVersionMismatch(
                        expected = ProtocolLimits.PROTOCOL_VERSION_MAX,
                        actual = requireInt(fields, KEY_PROTOCOL_VERSION),
                    ),
                )
            val kind = when (require(fields, KEY_KIND)) {
                "file" -> false
                "folder" -> true
                else -> return invalid("unknown_descriptor_kind")
            }
            val descriptor = TransferFileDescriptor(
                fileId = FileId(require(fields, KEY_FILE_ID)),
                displayName = require(fields, KEY_DISPLAY_NAME),
                relativePath = RelativeTransferPath(require(fields, KEY_RELATIVE_PATH)),
                mimeType = require(fields, KEY_MIME_TYPE),
                totalBytes = requireLong(fields, KEY_TOTAL_BYTES),
                lastModifiedEpochMillis = requireLongOrNull(fields, KEY_LAST_MODIFIED),
                isFolderArchive = kind,
                expectedSha256 = requireDigestOrNull(fields, KEY_SHA256),
                chunkSize = ChunkSize(requireInt(fields, KEY_CHUNK_SIZE)),
                protocolVersion = protocolVersion,
            )
            val stateId = require(fields, KEY_STATE)
            val state = TransferState.entries.firstOrNull { it.id == stateId }
                ?: return invalid("unknown_transfer_state")
            val directionId = require(fields, KEY_DIRECTION)
            val direction = SessionDirection.entries.firstOrNull { it.name == directionId }
                ?: return invalid("unknown_session_direction")
            val failureCode = require(fields, KEY_ERROR_CODE)
            val failureDetail = require(fields, KEY_ERROR_DETAIL)
            val failureRetryable = requireBoolean(fields, KEY_ERROR_RETRYABLE)
            val failureOriginId = require(fields, KEY_ERROR_ORIGIN)
            val failureCategoryId = require(fields, KEY_ERROR_CATEGORY)
            val failure = if (failureCode.isEmpty()) {
                if (failureDetail.isNotEmpty() || failureRetryable ||
                    failureOriginId.isNotEmpty() || failureCategoryId.isNotEmpty()
                ) {
                    return invalid("inconsistent_failure_fields")
                }
                null
            } else {
                if (!failureCode.matches(Regex("[a-z0-9_]{1,64}"))) {
                    return invalid("invalid_failure_code")
                }
                val origin = ErrorOrigin.entries.firstOrNull { it.id == failureOriginId }
                    ?: return invalid("unknown_failure_origin")
                val category = ErrorCategory.entries.firstOrNull { it.id == failureCategoryId }
                    ?: return invalid("unknown_failure_category")
                TransferError.restore(
                    code = failureCode,
                    detail = failureDetail,
                    retryable = failureRetryable,
                    origin = origin,
                    category = category,
                )
            }
            val verificationExpected = require(fields, KEY_VERIFICATION_EXPECTED)
            val verificationObserved = require(fields, KEY_VERIFICATION_OBSERVED)
            val verificationStarted = require(fields, KEY_VERIFICATION_STARTED)
            val verification = if (
                verificationExpected.isEmpty() && verificationObserved.isEmpty() && verificationStarted.isEmpty()
            ) {
                null
            } else {
                VerificationInfo(
                    expectedDigest = requireDigestOrNull(fields, KEY_VERIFICATION_EXPECTED),
                    observedDigest = requireDigestOrNull(fields, KEY_VERIFICATION_OBSERVED),
                    startedSnapshotVersion = verificationStarted.toLongOrNull()
                        ?: throw IllegalArgumentException("verification start version is not a number"),
                )
            }
            val snapshot = TransferSnapshot(
                transferId = TransferId(require(fields, KEY_TRANSFER_ID)),
                sessionId = SessionId(require(fields, KEY_SESSION_ID)),
                batchId = BatchId(require(fields, KEY_BATCH_ID)),
                recipientId = require(fields, KEY_RECIPIENT_ID).takeIf { it.isNotEmpty() }
                    ?.let(::RecipientId),
                direction = direction,
                descriptor = descriptor,
                state = state,
                confirmedBytes = requireLong(fields, KEY_CONFIRMED_BYTES),
                optimisticBytes = requireLong(fields, KEY_OPTIMISTIC_BYTES),
                lastAcknowledgedSequence = requireLongOrNull(fields, KEY_LAST_ACK_SEQUENCE),
                retryCount = requireInt(fields, KEY_RETRY_COUNT),
                failure = failure,
                verification = verification,
                remotePaused = requireBoolean(fields, KEY_REMOTE_PAUSED),
                snapshotVersion = requireLong(fields, KEY_SNAPSHOT_VERSION),
                queueOrder = requireLong(fields, KEY_QUEUE_ORDER),
            )
            if (snapshot.violations().isNotEmpty()) return invalid("inconsistent_snapshot")
            if (serialize(snapshot) != raw) return invalid("noncanonical_snapshot")
            SnapshotDecodeResult.Success(snapshot)
        } catch (_: IllegalArgumentException) {
            invalid("snapshot_field_invalid")
        }
    }

    private fun require(fields: Map<String, String>, key: String): String =
        fields[key] ?: throw IllegalArgumentException("missing snapshot field $key")

    private fun requireLong(fields: Map<String, String>, key: String): Long =
        require(fields, key).toLongOrNull()
            ?: throw IllegalArgumentException("snapshot field $key is not a number")

    private fun requireInt(fields: Map<String, String>, key: String): Int =
        require(fields, key).toIntOrNull()
            ?: throw IllegalArgumentException("snapshot field $key is not a number")

    private fun requireLongOrNull(fields: Map<String, String>, key: String): Long? {
        val value = require(fields, key)
        return if (value.isEmpty()) null else value.toLongOrNull()
            ?: throw IllegalArgumentException("snapshot field $key is not a number")
    }

    private fun requireBoolean(fields: Map<String, String>, key: String): Boolean =
        when (require(fields, key)) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("snapshot field $key is not a boolean")
        }

    private fun requireDigestOrNull(fields: Map<String, String>, key: String): Sha256Digest? {
        val value = require(fields, key)
        if (value.isEmpty()) return null
        if (!value.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalArgumentException("snapshot field $key is not a canonical SHA-256 digest")
        }
        return Sha256Digest.fromHex(value)
            ?: throw IllegalArgumentException("snapshot field $key is not a SHA-256 digest")
    }

    private fun invalid(reason: String): SnapshotDecodeResult.Invalid =
        SnapshotDecodeResult.Invalid(TransferError.PersistedSnapshotInvalid(reason))

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private fun unescape(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '\\' && index + 1 < value.length) {
                when (val next = value[index + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    '\\' -> append('\\')
                    else -> append(next)
                }
                index += 2
            } else {
                append(char)
                index++
            }
        }
    }
}

/** Result of restoring a persisted snapshot. */
public sealed class SnapshotDecodeResult {
    public data class Success(public val snapshot: TransferSnapshot) : SnapshotDecodeResult()
    public data class Invalid(public val error: TransferError) : SnapshotDecodeResult()
}
