package app.morsecode.core.storage.transfer

import app.morsecode.core.data.db.MorseDatabase
import app.morsecode.core.data.db.SafPendingCleanupEntity
import app.morsecode.core.data.db.SafRenameHistoryEntity
import app.morsecode.core.data.db.TransferPartialEntity
import app.morsecode.core.model.TransferState
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

/** Room implementation of the provider-independent, typed SAF journal contract. */
internal class RoomSafCommitJournal(
    private val database: MorseDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : SafCommitJournal, SafCommitCheckpointDiscovery {

    override fun read(commitId: PartialIdentity): SafCommitJournalRead {
        SafCommitCheckpointValidator.validateCommitId(commitId)?.let { error ->
            return SafCommitJournalRead.Rejected(error)
        }
        return callDatabase(
            onFailure = { error -> SafCommitJournalRead.Rejected(error) },
        ) {
            database.withTransaction {
                val parent = database.transferPartialDao().find(commitId.value)
                // Query one sentinel beyond each domain bound, so truncated reads
                // cannot make corrupted unbounded child sets appear valid.
                val history = database.safRenameHistoryDao().forCommit(commitId.value)
                val cleanup = database.safPendingCleanupDao().forCommit(commitId.value)
                if (parent == null) {
                    return@withTransaction if (history.isEmpty() && cleanup.isEmpty()) {
                        SafCommitJournalRead.Missing
                    } else {
                        rejected("orphan_checkpoint_children")
                    }
                }
                val entry = try {
                    SafCommitEntityMapper.decode(parent, history, cleanup)
                } catch (_: IllegalArgumentException) {
                    return@withTransaction rejected("checkpoint_malformed")
                }
                val error = SafCommitCheckpointValidator.validate(entry.checkpoint)
                if (error != null) SafCommitJournalRead.Rejected(error) else SafCommitJournalRead.Found(entry)
            }
        }
    }

    override fun restorationPage(
        after: SafCommitDiscoveryCursor?,
        limit: Int,
    ): SafCommitDiscoveryPageResult {
        if (limit !in 1..MAX_DISCOVERY_PAGE_SIZE) {
            return SafCommitDiscoveryPageResult.Failed(
                TransferStorageError.StateConflict("restoration_page_limit"),
            )
        }
        return callDatabase(
            onFailure = { error -> SafCommitDiscoveryPageResult.Failed(error) },
        ) {
            database.withTransaction {
                val rows = database.transferPartialDao().restorationPage(
                    afterCommitId = after?.afterCommitId,
                    limit = limit + 1,
                )
                val hasMore = rows.size > limit
                val selected = rows.take(limit)
                val candidates = selected.map { row -> candidateForRow(row) }
                SafCommitDiscoveryPageResult.Page(
                    SafCommitDiscoveryPage(
                        candidates = candidates,
                        hasMore = hasMore,
                        nextCursor = candidates.lastOrNull()?.cursorAfter,
                    ),
                )
            }
        }
    }

    override fun readForRestoration(commitId: PartialIdentity): SafCommitDiscoveryReadResult {
        SafCommitCheckpointValidator.validateCommitId(commitId)?.let {
            return SafCommitDiscoveryReadResult.Failed(it)
        }
        val entry = when (val result = read(commitId)) {
            SafCommitJournalRead.Missing -> return SafCommitDiscoveryReadResult.Missing
            is SafCommitJournalRead.Rejected -> return SafCommitDiscoveryReadResult.Failed(result.error)
            is SafCommitJournalRead.Found -> result.entry
        }
        val activity = callDatabase(
            onFailure = { SafCommitTransferActivity.UNAVAILABLE },
        ) {
            database.withTransaction { readTransferActivity(entry.checkpoint) }
        }
        return SafCommitDiscoveryReadResult.Found(
            SafCommitDiscoveryCandidate(
                commitId = commitId,
                checkpoint = entry.checkpoint,
                revision = entry.revision,
                error = null,
                transferActivity = activity,
                cursorAfter = SafCommitDiscoveryCursor(commitId.value),
            ),
        )
    }

    override fun write(
        checkpoint: SafCommitCheckpoint,
        expectedRevision: Long,
    ): SafCommitJournalWrite {
        SafCommitCheckpointValidator.validate(checkpoint)?.let { error ->
            return SafCommitJournalWrite.Rejected(error)
        }
        if (expectedRevision < 0L) {
            return SafCommitJournalWrite.Conflict(TransferStorageError.StateConflict("journal_revision_invalid"))
        }
        val rows = try {
            SafCommitEntityMapper.encode(checkpoint)
        } catch (_: IllegalArgumentException) {
            return SafCommitJournalWrite.Rejected(
                TransferStorageError.StateConflict("checkpoint_malformed"),
            )
        }
        return callDatabase(
            onFailure = { error ->
                if (error is TransferStorageError.StateConflict) {
                    SafCommitJournalWrite.Conflict(error)
                } else {
                    SafCommitJournalWrite.Rejected(error)
                }
            },
            operation = "journal_write",
        ) {
            database.withTransaction {
                val parentDao = database.transferPartialDao()
                val historyDao = database.safRenameHistoryDao()
                val cleanupDao = database.safPendingCleanupDao()
                val existing = parentDao.find(checkpoint.commitId.value)
                val oldHistory = historyDao.forCommit(checkpoint.commitId.value)
                val oldCleanup = cleanupDao.forCommit(checkpoint.commitId.value)

                if (existing != null) {
                    val decoded = try {
                        SafCommitEntityMapper.decode(existing, oldHistory, oldCleanup)
                    } catch (_: IllegalArgumentException) {
                        return@withTransaction SafCommitJournalWrite.Rejected(
                            TransferStorageError.StateConflict("checkpoint_malformed"),
                        )
                    }
                    val decodeError = SafCommitCheckpointValidator.validate(decoded.checkpoint)
                    if (decodeError != null) {
                        return@withTransaction SafCommitJournalWrite.Rejected(decodeError)
                    }
                    if (decoded.checkpoint == checkpoint) {
                        return@withTransaction SafCommitJournalWrite.Saved(decoded.revision)
                    }
                } else if (oldHistory.isNotEmpty() || oldCleanup.isNotEmpty()) {
                    return@withTransaction SafCommitJournalWrite.Rejected(
                        TransferStorageError.StateConflict("orphan_checkpoint_children"),
                    )
                }

                val currentRevision = existing?.journalRevision ?: 0L
                if (currentRevision != expectedRevision) {
                    return@withTransaction SafCommitJournalWrite.Conflict(
                        TransferStorageError.StateConflict("stale_journal_revision"),
                    )
                }
                if (currentRevision == Long.MAX_VALUE) {
                    return@withTransaction SafCommitJournalWrite.Conflict(
                        TransferStorageError.StateConflict("journal_revision_exhausted"),
                    )
                }
                val nextRevision = currentRevision + 1L
                historyDao.deleteForCommit(checkpoint.commitId.value)
                cleanupDao.deleteForCommit(checkpoint.commitId.value)
                parentDao.upsert(rows.parent.copy(journalRevision = nextRevision))
                if (rows.history.isNotEmpty()) historyDao.insertAll(rows.history)
                if (rows.cleanup.isNotEmpty()) cleanupDao.insertAll(rows.cleanup)
                SafCommitJournalWrite.Saved(nextRevision)
            }
        }
    }

    private suspend fun candidateForRow(parent: TransferPartialEntity): SafCommitDiscoveryCandidate {
        val cursor = SafCommitDiscoveryCursor(parent.commitId)
        val commitId = try {
            PartialIdentity(parent.commitId)
        } catch (_: IllegalArgumentException) {
            return rejectedCandidate(cursor, null, TransferStorageError.StateConflict("checkpoint_malformed"))
        }
        SafCommitCheckpointValidator.validateCommitId(commitId)?.let { error ->
            return rejectedCandidate(cursor, commitId, error)
        }
        val history: List<SafRenameHistoryEntity>
        val cleanup: List<SafPendingCleanupEntity>
        try {
            history = database.safRenameHistoryDao().forCommit(parent.commitId)
            cleanup = database.safPendingCleanupDao().forCommit(parent.commitId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return rejectedCandidate(cursor, commitId, TransferStorageError.Io("journal_read"))
        }
        val entry = try {
            SafCommitEntityMapper.decode(parent, history, cleanup)
        } catch (_: IllegalArgumentException) {
            return rejectedCandidate(cursor, commitId, TransferStorageError.StateConflict("checkpoint_malformed"))
        }
        SafCommitCheckpointValidator.validate(entry.checkpoint)?.let { error ->
            return rejectedCandidate(cursor, commitId, error)
        }
        return SafCommitDiscoveryCandidate(
            commitId = commitId,
            checkpoint = entry.checkpoint,
            revision = entry.revision,
            error = null,
            transferActivity = readTransferActivity(entry.checkpoint),
            cursorAfter = cursor,
        )
    }

    private suspend fun readTransferActivity(
        checkpoint: SafCommitCheckpoint,
    ): SafCommitTransferActivity {
        val row = try {
            database.transferSnapshotDao().restorationActivity(checkpoint.transferId.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SafCommitTransferActivity.UNAVAILABLE
        } ?: return SafCommitTransferActivity.NOT_RECORDED
        if (row.transferId != checkpoint.transferId.value || row.sessionId != checkpoint.sessionId.value) {
            return SafCommitTransferActivity.MALFORMED
        }
        val state = TransferState.entries.firstOrNull { it.id == row.snapshotState }
            ?: return SafCommitTransferActivity.MALFORMED
        return if (state.isBusy || state.isPending || state.isPaused ||
            state == TransferState.FAILED_RETRYABLE
        ) {
            SafCommitTransferActivity.ACTIVE_OR_RESUMABLE
        } else {
            SafCommitTransferActivity.QUIESCENT
        }
    }

    private fun rejectedCandidate(
        cursor: SafCommitDiscoveryCursor,
        commitId: PartialIdentity?,
        error: TransferStorageError,
    ): SafCommitDiscoveryCandidate = SafCommitDiscoveryCandidate(
        commitId = commitId,
        checkpoint = null,
        revision = null,
        error = error,
        transferActivity = if (error is TransferStorageError.Io) {
            SafCommitTransferActivity.UNAVAILABLE
        } else {
            SafCommitTransferActivity.MALFORMED
        },
        cursorAfter = cursor,
    )

    private fun rejected(reason: String): SafCommitJournalRead.Rejected =
        SafCommitJournalRead.Rejected(TransferStorageError.StateConflict(reason))

    private companion object {
        const val MAX_DISCOVERY_PAGE_SIZE: Int = 100
    }

    private fun <T> callDatabase(
        onFailure: (TransferStorageError) -> T,
        operation: String = "journal_read",
        block: suspend () -> T,
    ): T = try {
        runBlocking(ioDispatcher) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (full: SQLiteFullException) {
        onFailure(TransferStorageError.StorageFull(operation))
    } catch (constraint: SQLiteConstraintException) {
        onFailure(TransferStorageError.StateConflict("journal_identity_conflict"))
    } catch (failure: Exception) {
        onFailure(
            when {
                failure.causeChainContains<SQLiteFullException>() -> TransferStorageError.StorageFull(operation)
                failure.causeChainContains<SQLiteConstraintException>() ->
                    TransferStorageError.StateConflict("journal_identity_conflict")
                else -> TransferStorageError.Io(operation)
            },
        )
    }

    private inline fun <reified T : Throwable> Throwable.causeChainContains(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is T) return true
            cause = cause.cause
        }
        return false
    }
}

private data class SafCommitEntityRows(
    val parent: TransferPartialEntity,
    val history: List<SafRenameHistoryEntity>,
    val cleanup: List<SafPendingCleanupEntity>,
)

/** Explicit, strict mapper. Domain values never leak into Room/provider operations. */
private object SafCommitEntityMapper {
    fun encode(checkpoint: SafCommitCheckpoint): SafCommitEntityRows {
        val parent = TransferPartialEntity(
            commitId = checkpoint.commitId.value,
            stagingIdentity = checkpoint.stagingIdentity.value,
            sessionId = checkpoint.sessionId.value,
            transferId = checkpoint.transferId.value,
            checkpointVersion = checkpoint.version,
            journalRevision = 0L,
            renameHistoryCount = checkpoint.renameHistory.size,
            pendingCleanupCount = checkpoint.pendingCleanup.size,
            strategyId = checkpoint.strategy.id,
            duplicatePolicy = checkpoint.duplicatePolicy.id,
            grantId = checkpoint.approvedTree.grantId,
            treeUri = checkpoint.approvedTree.treeUri,
            authority = checkpoint.approvedTree.authority,
            rootDocumentId = checkpoint.approvedTree.rootDocumentId,
            parentDocumentId = checkpoint.parentDocumentId,
            expectedFinalName = checkpoint.expectedFinalName,
            expectedSizeBytes = checkpoint.expectedSizeBytes,
            expectedDigestHex = checkpoint.expectedDigest?.hex,
            checkpointPhase = checkpoint.phase.id,
            temporaryUri = checkpoint.temporaryIdentity?.documentUri,
            temporaryDocumentId = checkpoint.temporaryIdentity?.documentId,
            existingUri = checkpoint.existingIdentity?.documentUri,
            existingDocumentId = checkpoint.existingIdentity?.documentId,
            backupUri = checkpoint.backupIdentity?.documentUri,
            backupDocumentId = checkpoint.backupIdentity?.documentId,
            returnedRenameUri = checkpoint.returnedRenameUri,
            returnedRenameIdentityUri = checkpoint.returnedRenameIdentity?.documentUri,
            returnedRenameDocumentId = checkpoint.returnedRenameIdentity?.documentId,
            finalUri = checkpoint.finalIdentity?.documentUri,
            finalDocumentId = checkpoint.finalIdentity?.documentId,
            copiedBytes = checkpoint.copiedBytes,
            stagingReleased = checkpoint.stagingReleased,
            lastFailureCategory = checkpoint.lastFailure?.categoryId,
            lastFailureCode = checkpoint.lastFailure?.code,
            unresolvedRenamePhase = checkpoint.unresolvedRenamePhase?.id,
            verifiedDigestHex = checkpoint.verifiedDigest?.hex,
        )
        val history = checkpoint.renameHistory.map { evidence ->
            val phase = requireNotNull(evidence.phase)
            val before = evidence.before
            SafRenameHistoryEntity(
                commitId = checkpoint.commitId.value,
                sequence = requireNotNull(evidence.sequence),
                phase = phase.id,
                beforeUri = before.documentUri,
                beforeDocumentId = before.documentId,
                returnedUri = evidence.returned?.documentUri,
                returnedDocumentId = evidence.returned?.documentId,
                reconciliationId = evidence.reconciliation?.id,
            )
        }
        val cleanup = checkpoint.pendingCleanup.mapIndexed { sequence, pending ->
            SafPendingCleanupEntity(
                commitId = checkpoint.commitId.value,
                sequence = sequence,
                cleanupType = pending.type.id,
                documentUri = pending.documentIdentity?.documentUri,
                documentId = pending.documentIdentity?.documentId,
                stagingIdentity = pending.stagingIdentity?.value,
            )
        }
        return SafCommitEntityRows(parent, history, cleanup)
    }

    fun decode(
        parent: TransferPartialEntity,
        historyRows: List<SafRenameHistoryEntity>,
        cleanupRows: List<SafPendingCleanupEntity>,
    ): SafCommitJournalEntry {
        if (parent.journalRevision < 1L ||
            parent.renameHistoryCount !in 0..SafRenameHistoryPolicy.MAX_ENTRIES ||
            parent.pendingCleanupCount !in 0..3 ||
            parent.renameHistoryCount != historyRows.size ||
            parent.pendingCleanupCount != cleanupRows.size ||
            historyRows.size > SafRenameHistoryPolicy.MAX_ENTRIES || cleanupRows.size > 3
        ) {
            throw InvalidCheckpointRow()
        }
        fun requiredPair(uri: String?, id: String?): SafStoredDocumentIdentity? = when {
            uri == null && id == null -> null
            uri != null && id != null -> SafStoredDocumentIdentity(uri, id)
            else -> throw InvalidCheckpointRow()
        }
        fun digest(value: String?): Sha256Digest? {
            if (value == null) return null
            if (!value.matches(Regex("[0-9a-f]{64}"))) throw InvalidCheckpointRow()
            return Sha256Digest.fromHex(value) ?: throw InvalidCheckpointRow()
        }

        val strategy = SafCommitStrategy.entries.firstOrNull { it.id == parent.strategyId }
            ?: throw InvalidCheckpointRow()
        val duplicatePolicy = DuplicatePolicy.entries.firstOrNull { it.id == parent.duplicatePolicy }
            ?: throw InvalidCheckpointRow()
        val phase = SafCommitCheckpointPhase.fromId(parent.checkpointPhase)
            ?: throw InvalidCheckpointRow()
        val unresolvedPhase = parent.unresolvedRenamePhase?.let { raw ->
            SafRenamePhase.entries.firstOrNull { it.id == raw } ?: throw InvalidCheckpointRow()
        }
        val expectedDigest = digest(parent.expectedDigestHex)
        val verifiedDigest = digest(parent.verifiedDigestHex)
        val failureCategory = parent.lastFailureCategory
        val failureCode = parent.lastFailureCode
        val failure = when {
            failureCategory == null && failureCode == null -> null
            failureCategory != null && failureCode != null ->
                SafCommitCheckpointFailure(failureCategory, failureCode)
            else -> throw InvalidCheckpointRow()
        }
        if (historyRows.map { it.sequence } != historyRows.indices.toList() ||
            cleanupRows.map { it.sequence } != cleanupRows.indices.toList() ||
            historyRows.any { it.commitId != parent.commitId } ||
            cleanupRows.any { it.commitId != parent.commitId }
        ) throw InvalidCheckpointRow()

        val scope = SafRenameScope(
            grantId = parent.grantId,
            treeUri = parent.treeUri,
            authority = parent.authority,
            rootDocumentId = parent.rootDocumentId,
            parentDocumentId = parent.parentDocumentId,
            sessionId = SessionId(parent.sessionId),
            transferId = TransferId(parent.transferId),
            commitId = PartialIdentity(parent.commitId),
        )
        val history = historyRows.map { row ->
            val before = SafStoredDocumentIdentity(row.beforeUri, row.beforeDocumentId)
            val returned = requiredPair(row.returnedUri, row.returnedDocumentId)
            val reconciliation = row.reconciliationId?.let { raw ->
                SafRenameReconciliation.entries.firstOrNull { it.id == raw } ?: throw InvalidCheckpointRow()
            }
            val renamePhase = SafRenamePhase.entries.firstOrNull { it.id == row.phase }
                ?: throw InvalidCheckpointRow()
            SafRenameEvidence(
                before = before,
                returned = returned,
                reconciliation = reconciliation,
                scope = scope,
                phase = renamePhase,
                sequence = row.sequence,
            )
        }
        val cleanup = cleanupRows.map { row ->
            val type = SafCleanupPending.entries.firstOrNull { it.id == row.cleanupType }
                ?: throw InvalidCheckpointRow()
            val identity = requiredPair(row.documentUri, row.documentId)
            when (type) {
                SafCleanupPending.STAGING -> {
                    val stagingIdentity = row.stagingIdentity
                    if (identity != null || stagingIdentity == null) throw InvalidCheckpointRow()
                    SafPendingCleanupIdentity(type, stagingIdentity = PartialIdentity(stagingIdentity))
                }
                SafCleanupPending.PROVIDER_TEMPORARY,
                SafCleanupPending.BACKUP,
                -> {
                    if (identity == null || row.stagingIdentity != null) throw InvalidCheckpointRow()
                    SafPendingCleanupIdentity(type, documentIdentity = identity)
                }
            }
        }

        val checkpoint = SafCommitCheckpoint(
            version = parent.checkpointVersion,
            sessionId = SessionId(parent.sessionId),
            transferId = TransferId(parent.transferId),
            commitId = PartialIdentity(parent.commitId),
            strategy = strategy,
            duplicatePolicy = duplicatePolicy,
            approvedTree = SafApprovedTreeIdentity(
                grantId = parent.grantId,
                treeUri = parent.treeUri,
                authority = parent.authority,
                rootDocumentId = parent.rootDocumentId,
            ),
            parentDocumentId = parent.parentDocumentId,
            stagingIdentity = PartialIdentity(parent.stagingIdentity),
            expectedFinalName = parent.expectedFinalName,
            expectedSizeBytes = parent.expectedSizeBytes,
            expectedDigest = expectedDigest,
            phase = phase,
            temporaryIdentity = requiredPair(parent.temporaryUri, parent.temporaryDocumentId),
            existingIdentity = requiredPair(parent.existingUri, parent.existingDocumentId),
            backupIdentity = requiredPair(parent.backupUri, parent.backupDocumentId),
            renameHistory = history,
            returnedRenameUri = parent.returnedRenameUri,
            returnedRenameIdentity = requiredPair(
                parent.returnedRenameIdentityUri,
                parent.returnedRenameDocumentId,
            ),
            finalIdentity = requiredPair(parent.finalUri, parent.finalDocumentId),
            pendingCleanup = cleanup,
            copiedBytes = parent.copiedBytes,
            stagingReleased = parent.stagingReleased,
            lastFailure = failure,
            unresolvedRenamePhase = unresolvedPhase,
            verifiedDigest = verifiedDigest,
        )
        return SafCommitJournalEntry(checkpoint, parent.journalRevision)
    }

    private class InvalidCheckpointRow : IllegalArgumentException()
}
