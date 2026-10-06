package app.morsecode.core.data.db

import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.error.ErrorCategory
import app.morsecode.core.transfer.error.ErrorDetailRedactor
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
import app.morsecode.core.transfer.persistence.StoreReadResult

/** Explicit Room/domain mapping. No domain object is serialized into a database blob. */
internal object TransferSnapshotEntityMapper {

    fun toEntity(snapshot: TransferSnapshot, rowRevision: Long): TransferSnapshotEntity? {
        if (rowRevision < 1L || snapshot.violations().isNotEmpty()) return null
        val failure = snapshot.failure
        if (failure != null &&
            (!FAILURE_CODE.matches(failure.code) ||
                failure.detail.toByteArray(Charsets.UTF_8).size > ErrorDetailRedactor.MAX_LENGTH_BYTES ||
                ErrorDetailRedactor.redact(failure.detail) != failure.detail)
        ) return null

        return TransferSnapshotEntity(
            transferId = snapshot.transferId.value,
            sessionId = snapshot.sessionId.value,
            batchId = snapshot.batchId.value,
            recipientId = snapshot.recipientId?.value,
            direction = snapshot.direction.name,
            snapshotState = snapshot.state.id,
            snapshotVersion = snapshot.snapshotVersion,
            confirmedBytes = snapshot.confirmedBytes,
            optimisticBytes = snapshot.optimisticBytes,
            lastAcknowledgedSequence = snapshot.lastAcknowledgedSequence,
            retryCount = snapshot.retryCount,
            failureCode = failure?.code,
            failureDetail = failure?.detail,
            failureRetryable = failure?.retryable?.let { if (it) 1 else 0 },
            failureOrigin = failure?.origin?.id,
            failureCategory = failure?.category?.id,
            remotePaused = if (snapshot.remotePaused) 1 else 0,
            queueOrder = snapshot.queueOrder,
            fileId = snapshot.descriptor.fileId.value,
            displayName = snapshot.descriptor.displayName,
            relativePath = snapshot.descriptor.relativePath.value,
            mimeType = snapshot.descriptor.mimeType,
            totalBytes = snapshot.descriptor.totalBytes,
            lastModifiedEpochMillis = snapshot.descriptor.lastModifiedEpochMillis,
            isFolderArchive = if (snapshot.descriptor.isFolderArchive) 1 else 0,
            expectedSha256Hex = snapshot.descriptor.expectedSha256?.hex,
            chunkSize = snapshot.descriptor.chunkSize.value,
            protocolVersion = snapshot.descriptor.protocolVersion.value,
            verificationExpectedDigestHex = snapshot.verification?.expectedDigest?.hex,
            verificationObservedDigestHex = snapshot.verification?.observedDigest?.hex,
            verificationStartedSnapshotVersion = snapshot.verification?.startedSnapshotVersion,
            rowRevision = rowRevision,
        )
    }

    fun decode(row: TransferSnapshotEntity): StoreReadResult<TransferSnapshot> {
        if (row.rowRevision < 1L || row.snapshotVersion < 1L || row.confirmedBytes < 0L ||
            row.confirmedBytes > ProtocolLimits.MAX_FILE_SIZE_BYTES ||
            row.optimisticBytes < 0L || row.optimisticBytes > ProtocolLimits.MAX_FILE_SIZE_BYTES ||
            row.totalBytes < 0L || row.totalBytes > ProtocolLimits.MAX_FILE_SIZE_BYTES ||
            row.retryCount < 0 || row.queueOrder < 0L ||
            row.remotePaused !in 0..1 || row.isFolderArchive !in 0..1 ||
            row.failureRetryable?.let { it !in 0..1 } == true
        ) return invalid("invalid_snapshot_row_metadata")

        if (row.failureDetail != null &&
            row.failureDetail.toByteArray(Charsets.UTF_8).size > ErrorDetailRedactor.MAX_LENGTH_BYTES
        ) return invalid("invalid_failure_detail")

        return try {
            val state = TransferState.entries.firstOrNull { it.id == row.snapshotState }
                ?: return invalid("unknown_transfer_state")
            val direction = SessionDirection.entries.firstOrNull { it.name == row.direction }
                ?: return invalid("unknown_session_direction")
            val protocolVersion = ProtocolVersion.orNull(row.protocolVersion)
                ?: return StoreReadResult.Failed(
                    TransferError.ProtocolVersionMismatch(
                        expected = ProtocolLimits.PROTOCOL_VERSION_MAX,
                        actual = row.protocolVersion,
                    ),
                )
            val failure = decodeFailure(row) ?: if (hasAnyFailureField(row)) {
                return invalid("inconsistent_failure_fields")
            } else {
                null
            }
            val expectedDigest = digest(row.expectedSha256Hex)
            val verificationExpected = digest(row.verificationExpectedDigestHex)
            val verificationObserved = digest(row.verificationObservedDigestHex)
            val verification = when {
                verificationExpected == null && verificationObserved == null &&
                    row.verificationStartedSnapshotVersion == null -> null
                row.verificationStartedSnapshotVersion == null -> return invalid("incomplete_verification_fields")
                else -> VerificationInfo(
                    expectedDigest = verificationExpected,
                    observedDigest = verificationObserved,
                    startedSnapshotVersion = row.verificationStartedSnapshotVersion,
                )
            }
            val descriptor = TransferFileDescriptor(
                fileId = FileId(row.fileId),
                displayName = row.displayName,
                relativePath = RelativeTransferPath(row.relativePath),
                mimeType = row.mimeType,
                totalBytes = row.totalBytes,
                lastModifiedEpochMillis = row.lastModifiedEpochMillis,
                isFolderArchive = row.isFolderArchive == 1,
                expectedSha256 = expectedDigest,
                chunkSize = ChunkSize(row.chunkSize),
                protocolVersion = protocolVersion,
            )
            val snapshot = TransferSnapshot(
                transferId = TransferId(row.transferId),
                sessionId = SessionId(row.sessionId),
                batchId = BatchId(row.batchId),
                recipientId = row.recipientId?.let(::RecipientId),
                direction = direction,
                descriptor = descriptor,
                state = state,
                confirmedBytes = row.confirmedBytes,
                optimisticBytes = row.optimisticBytes,
                lastAcknowledgedSequence = row.lastAcknowledgedSequence,
                retryCount = row.retryCount,
                failure = failure,
                verification = verification,
                remotePaused = row.remotePaused == 1,
                snapshotVersion = row.snapshotVersion,
                queueOrder = row.queueOrder,
            )
            if (snapshot.violations().isNotEmpty()) {
                invalid("inconsistent_snapshot")
            } else {
                StoreReadResult.Found(snapshot)
            }
        } catch (_: IllegalArgumentException) {
            invalid("invalid_snapshot_row")
        }
    }

    private fun decodeFailure(row: TransferSnapshotEntity): TransferError? {
        val code = row.failureCode ?: return null
        val detail = row.failureDetail ?: throw IllegalArgumentException("missing failure detail")
        val retryable = row.failureRetryable?.let { value ->
            require(value in 0..1) { "invalid failure retryability" }
            value == 1
        } ?: throw IllegalArgumentException("missing failure retryability")
        val origin = ErrorOrigin.entries.firstOrNull { it.id == row.failureOrigin }
            ?: throw IllegalArgumentException("unknown failure origin")
        val category = ErrorCategory.entries.firstOrNull { it.id == row.failureCategory }
            ?: throw IllegalArgumentException("unknown failure category")
        require(FAILURE_CODE.matches(code)) { "invalid failure code" }
        require(ErrorDetailRedactor.redact(detail) == detail) { "failure detail is not redacted" }
        return TransferError.restore(code, detail, retryable, origin, category)
    }

    private fun hasAnyFailureField(row: TransferSnapshotEntity): Boolean =
        row.failureCode != null || row.failureDetail != null || row.failureRetryable != null ||
            row.failureOrigin != null || row.failureCategory != null

    private fun digest(value: String?): Sha256Digest? {
        if (value == null) return null
        require(DIGEST.matches(value)) { "invalid SHA-256 representation" }
        return Sha256Digest.fromHex(value) ?: throw IllegalArgumentException("invalid SHA-256 digest")
    }

    private fun invalid(reason: String): StoreReadResult.Failed =
        StoreReadResult.Failed(TransferError.PersistedSnapshotInvalid(reason))

    private val FAILURE_CODE = Regex("[a-z0-9_]{1,64}")
    private val DIGEST = Regex("[0-9a-f]{64}")
}
