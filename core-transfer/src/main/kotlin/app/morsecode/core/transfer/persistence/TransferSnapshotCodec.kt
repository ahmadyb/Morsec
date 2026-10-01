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
        val fields = LinkedHashMap<String, String>()
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            val split = line.indexOf('=')
            if (split <= 0) {
                return invalid("a snapshot line has no key: ${line.take(32)}")
            }
            val key = line.substring(0, split)
            if (key !in KNOWN_KEYS) {
                return invalid("unknown snapshot key $key")
            }
            if (fields.put(key, unescape(line.substring(split + 1))) != null) {
                return invalid("duplicate snapshot key $key")
            }
        }
        return try {
            val version = require(fields, KEY_VERSION).toIntOrNull()
                ?: return invalid("snapshot version is not a number")
            if (version > VERSION || version < ProtocolLimits.SNAPSHOT_VERSION_MIN) {
                return SnapshotDecodeResult.Invalid(
                    TransferError.SnapshotVersionUnsupported(version, VERSION),
                )
            }

            val descriptor = TransferFileDescriptor(
                fileId = FileId(require(fields, KEY_FILE_ID)),
                displayName = require(fields, KEY_DISPLAY_NAME),
                relativePath = RelativeTransferPath(require(fields, KEY_RELATIVE_PATH)),
                mimeType = fields[KEY_MIME_TYPE] ?: "",
                totalBytes = requireLong(fields, KEY_TOTAL_BYTES),
                lastModifiedEpochMillis = requireLongOrNull(fields, KEY_LAST_MODIFIED),
                isFolderArchive = (fields[KEY_KIND] ?: "file") == "folder",
                expectedSha256 = requireDigestOrNull(fields, KEY_SHA256),
                chunkSize = ChunkSize(requireInt(fields, KEY_CHUNK_SIZE)),
                protocolVersion = ProtocolVersion(requireInt(fields, KEY_PROTOCOL_VERSION)),
            )
            val state = TransferState.fromId(require(fields, KEY_STATE))
            if (state == TransferState.FAILED_RETRYABLE && fields[KEY_STATE] != state.id) {
                return invalid("unknown transfer state ${fields[KEY_STATE]}")
            }
            val failureCode = fields[KEY_ERROR_CODE] ?: ""
            val failure = if (failureCode.isEmpty()) {
                null
            } else {
                TransferError.restore(
                    code = failureCode,
                    detail = fields[KEY_ERROR_DETAIL] ?: "",
                    retryable = (fields[KEY_ERROR_RETRYABLE] ?: "false").toBoolean(),
                    origin = ErrorOrigin.fromId(fields[KEY_ERROR_ORIGIN]),
                    category = ErrorCategory.fromId(fields[KEY_ERROR_CATEGORY]),
                )
            }
            val verification = if (
                (fields[KEY_VERIFICATION_EXPECTED] ?: "").isEmpty() &&
                (fields[KEY_VERIFICATION_OBSERVED] ?: "").isEmpty() &&
                (fields[KEY_VERIFICATION_STARTED] ?: "").isEmpty()
            ) {
                null
            } else {
                VerificationInfo(
                    expectedDigest = requireDigestOrNull(fields, KEY_VERIFICATION_EXPECTED),
                    observedDigest = requireDigestOrNull(fields, KEY_VERIFICATION_OBSERVED),
                    startedSnapshotVersion = requireLongOrNull(
                        fields,
                        KEY_VERIFICATION_STARTED,
                    ) ?: 0L,
                )
            }
            SnapshotDecodeResult.Success(
                TransferSnapshot(
                    transferId = TransferId(require(fields, KEY_TRANSFER_ID)),
                    sessionId = SessionId(require(fields, KEY_SESSION_ID)),
                    batchId = BatchId(require(fields, KEY_BATCH_ID)),
                    recipientId = fields[KEY_RECIPIENT_ID]?.takeIf { it.isNotEmpty() }
                        ?.let { RecipientId(it) },
                    direction = runCatching {
                        SessionDirection.valueOf(require(fields, KEY_DIRECTION))
                    }.getOrDefault(SessionDirection.OUTBOUND),
                    descriptor = descriptor,
                    state = state,
                    confirmedBytes = requireLong(fields, KEY_CONFIRMED_BYTES),
                    optimisticBytes = requireLong(fields, KEY_OPTIMISTIC_BYTES),
                    lastAcknowledgedSequence = requireLongOrNull(fields, KEY_LAST_ACK_SEQUENCE),
                    retryCount = requireInt(fields, KEY_RETRY_COUNT),
                    failure = failure,
                    verification = verification,
                    remotePaused = (fields[KEY_REMOTE_PAUSED] ?: "false").toBoolean(),
                    snapshotVersion = requireLong(fields, KEY_SNAPSHOT_VERSION),
                    queueOrder = requireLong(fields, KEY_QUEUE_ORDER),
                ),
            )
        } catch (e: IllegalArgumentException) {
            invalid("a snapshot field failed validation: ${e.message ?: "unknown reason"}")
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
        val value = fields[key]
        return if (value.isNullOrEmpty()) null else value.toLongOrNull()
    }

    private fun requireDigestOrNull(fields: Map<String, String>, key: String): Sha256Digest? {
        val value = fields[key]
        if (value.isNullOrEmpty()) return null
        return Sha256Digest.fromHex(value)
            ?: throw IllegalArgumentException("snapshot field $key is not a SHA-256 digest")
    }

    private fun invalid(reason: String): SnapshotDecodeResult.Invalid =
        SnapshotDecodeResult.Invalid(TransferError.MalformedFrame(reason))

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
