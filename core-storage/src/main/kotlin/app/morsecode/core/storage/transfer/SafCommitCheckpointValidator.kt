package app.morsecode.core.storage.transfer

import androidx.core.net.toUri
import app.morsecode.core.transfer.ProtocolLimits

/** Strict, provider-free validation for rows loaded from the SAF journal. */
public object SafCommitCheckpointValidator {

    private const val MAX_IDENTITY_TEXT_BYTES = 4_096
    private val COMMIT_ID_PATTERN = Regex("[A-Za-z0-9._:@-]{1,128}")

    /** Rejects an unbounded/ill-formed lookup key before it is bound into a Room query. */
    public fun validateCommitId(commitId: PartialIdentity): TransferStorageError? =
        if (COMMIT_ID_PATTERN.matches(commitId.value) && commitId.value.any { it != '.' }) null else malformed()

    /** Returns a typed, redacted refusal; null means the full checkpoint is internally valid. */
    public fun validate(checkpoint: SafCommitCheckpoint): TransferStorageError? {
        if (checkpoint.version != SafCommitCheckpoint.CURRENT_VERSION) {
            return TransferStorageError.Unsupported("saf_checkpoint_version")
        }
        return try {
            if (!hasBoundedText(checkpoint.approvedTree.grantId) ||
                !hasBoundedText(checkpoint.approvedTree.treeUri) ||
                !hasBoundedText(checkpoint.approvedTree.authority) ||
                !hasBoundedText(checkpoint.approvedTree.rootDocumentId) ||
                !hasBoundedText(checkpoint.parentDocumentId) ||
                !hasBoundedText(checkpoint.expectedFinalName)
            ) return malformed()
            if (validateCommitId(checkpoint.commitId) != null ||
                checkpoint.expectedSizeBytes > ProtocolLimits.MAX_FILE_SIZE_BYTES ||
                checkpoint.copiedBytes > checkpoint.expectedSizeBytes ||
                checkpoint.renameHistory.size > SafRenameHistoryPolicy.MAX_ENTRIES ||
                checkpoint.commitId != checkpoint.stagingIdentity
            ) return malformed()

            val treeUri = checkpoint.approvedTree.treeUri.toUri()
            val strictTreeUri = runCatching { java.net.URI(checkpoint.approvedTree.treeUri) }.getOrNull()
                ?: return malformed()
            if (strictTreeUri.scheme != "content" ||
                strictTreeUri.rawAuthority != checkpoint.approvedTree.authority ||
                strictTreeUri.rawQuery != null || strictTreeUri.rawFragment != null ||
                treeUri.scheme != "content" || treeUri.authority != checkpoint.approvedTree.authority ||
                SafContainment.treeDocumentIdOf(treeUri) != checkpoint.approvedTree.rootDocumentId ||
                SafDocumentIdRules.validate(checkpoint.approvedTree.rootDocumentId) !is SafDocumentIdCheck.Valid ||
                SafDocumentIdRules.validate(checkpoint.parentDocumentId) !is SafDocumentIdCheck.Valid
            ) return malformed()

            val expectedScope = SafRenameScope(
                grantId = checkpoint.approvedTree.grantId,
                treeUri = checkpoint.approvedTree.treeUri,
                authority = checkpoint.approvedTree.authority,
                rootDocumentId = checkpoint.approvedTree.rootDocumentId,
                parentDocumentId = checkpoint.parentDocumentId,
                sessionId = checkpoint.sessionId,
                transferId = checkpoint.transferId,
                commitId = checkpoint.commitId,
            )
            if (!SafRenameHistoryPolicy.isWellFormed(checkpoint.renameHistory, expectedScope) ||
                checkpoint.renameHistory.any { evidence ->
                    evidence.phase == SafRenamePhase.BACKUP_RENAME &&
                        (checkpoint.duplicatePolicy != app.morsecode.core.model.DuplicatePolicy.OVERWRITE ||
                            checkpoint.existingIdentity == null)
                } ||
                checkpoint.renameHistory.any { evidence ->
                    evidence.phase == SafRenamePhase.FINAL_PROMOTION &&
                        checkpoint.strategy != SafCommitStrategy.TEMP_THEN_RENAME
                }
            ) return malformed()

            val identities = listOfNotNull(
                checkpoint.temporaryIdentity,
                checkpoint.existingIdentity,
                checkpoint.backupIdentity,
                checkpoint.returnedRenameIdentity,
                checkpoint.finalIdentity,
            ) + checkpoint.renameHistory.flatMap { evidence ->
                listOfNotNull(evidence.before, evidence.returned)
            }
            if (identities.any { !validDocumentIdentity(it, checkpoint) }) return malformed()

            checkpoint.returnedRenameUri?.let { returnedUri ->
                if (!validDocumentUri(returnedUri, checkpoint)) return malformed()
                val idFromUri = SafContainment.documentIdOf(returnedUri.toUri())
                    ?: return malformed()
                if (checkpoint.returnedRenameIdentity?.let { identity ->
                        identity.documentUri != returnedUri || identity.documentId != idFromUri
                    } == true
                ) return malformed()
            }
            val pending = checkpoint.pendingCleanup
            if (pending.size > 3 || pending.map { it.type }.distinct().size != pending.size) return malformed()
            val cleanupIdentities = mutableSetOf<SafStoredDocumentIdentity>()
            for (item in pending) {
                when (item.type) {
                    SafCleanupPending.STAGING -> if (
                        item.documentIdentity != null || item.stagingIdentity != checkpoint.stagingIdentity
                    ) return malformed()
                    SafCleanupPending.PROVIDER_TEMPORARY -> {
                        if (item.stagingIdentity != null || item.documentIdentity != checkpoint.temporaryIdentity ||
                            item.documentIdentity?.let(cleanupIdentities::add) == false
                        ) return malformed()
                    }
                    SafCleanupPending.BACKUP -> {
                        if (item.stagingIdentity != null || item.documentIdentity != checkpoint.backupIdentity ||
                            item.documentIdentity?.let(cleanupIdentities::add) == false
                        ) return malformed()
                    }
                }
            }
            if (checkpoint.unresolvedRenamePhase != null &&
                (checkpoint.phase != SafCommitCheckpointPhase.RECONCILIATION_REQUIRED ||
                    checkpoint.renameHistory.any { it.phase == checkpoint.unresolvedRenamePhase })
            ) return malformed()
            if (checkpoint.stagingReleased && pending.any { it.type == SafCleanupPending.STAGING }) return malformed()
            if (checkpoint.phase == SafCommitCheckpointPhase.COMMITTED &&
                (checkpoint.finalIdentity == null || pending.isNotEmpty() || !checkpoint.stagingReleased)
            ) return malformed()
            if (checkpoint.expectedDigest?.hex?.matches(Regex("[0-9a-f]{64}")) == false ||
                checkpoint.verifiedDigest?.hex?.matches(Regex("[0-9a-f]{64}")) == false
            ) return malformed()
            null
        } catch (_: IllegalArgumentException) {
            malformed()
        } catch (_: SecurityException) {
            malformed()
        }
    }

    private fun validDocumentIdentity(
        identity: SafStoredDocumentIdentity,
        checkpoint: SafCommitCheckpoint,
    ): Boolean =
        hasBoundedText(identity.documentUri) && hasBoundedText(identity.documentId) &&
            validDocumentUri(identity.documentUri, checkpoint) &&
            SafContainment.documentIdOf(identity.documentUri.toUri()) == identity.documentId &&
            SafDocumentIdRules.validate(identity.documentId) is SafDocumentIdCheck.Valid

    private fun validDocumentUri(uriText: String, checkpoint: SafCommitCheckpoint): Boolean {
        if (!hasBoundedText(uriText)) return false
        val strictUri = runCatching { java.net.URI(uriText) }.getOrNull() ?: return false
        val uri = uriText.toUri()
        val segments = uri.pathSegments
        return strictUri.scheme == "content" &&
            strictUri.rawAuthority == checkpoint.approvedTree.authority &&
            strictUri.rawQuery == null && strictUri.rawFragment == null &&
            segments.size == 4 && segments[0] == "tree" && segments[2] == "document" &&
            uri.scheme == "content" && uri.authority == checkpoint.approvedTree.authority &&
            SafContainment.treeDocumentIdOf(uri) == checkpoint.approvedTree.rootDocumentId &&
            SafContainment.documentIdOf(uri)?.let {
                SafDocumentIdRules.validate(it) is SafDocumentIdCheck.Valid
            } == true
    }

    private fun hasBoundedText(value: String): Boolean =
        value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= MAX_IDENTITY_TEXT_BYTES

    private fun malformed(): TransferStorageError.StateConflict =
        TransferStorageError.StateConflict("checkpoint_malformed")
}
