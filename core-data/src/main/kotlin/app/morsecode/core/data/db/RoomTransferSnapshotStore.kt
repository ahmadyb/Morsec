package app.morsecode.core.data.db

import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.error.TransferError
import app.morsecode.core.transfer.identity.ConfirmedOffset
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.model.VerificationInfo
import app.morsecode.core.transfer.persistence.RetentionPolicy
import app.morsecode.core.transfer.persistence.SessionSnapshot
import app.morsecode.core.transfer.persistence.StoreReadResult
import app.morsecode.core.transfer.persistence.StoreResult
import app.morsecode.core.transfer.persistence.TransferSnapshotStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

/** Synchronous transfer-core contract backed by Room transactions on the IO dispatcher. */
internal class RoomTransferSnapshotStore(
    private val database: MorseDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : TransferSnapshotStore {

    override fun loadSession(sessionId: SessionId): StoreReadResult<SessionSnapshot> =
        readResult {
            val rows = database.transferSnapshotDao().forSession(sessionId.value)
            if (rows.isEmpty()) return@readResult StoreReadResult.Missing
            val snapshots = ArrayList<TransferSnapshot>(rows.size)
            for (row in rows) {
                when (val decoded = decode(row)) {
                    is StoreReadResult.Failed -> return@readResult decoded
                    StoreReadResult.Missing -> return@readResult invalid("snapshot_row_missing")
                    is StoreReadResult.Found -> {
                        if (decoded.value.sessionId != sessionId) {
                            return@readResult invalid("session_scope_mismatch")
                        }
                        snapshots += decoded.value
                    }
                }
            }
            StoreReadResult.Found(SessionSnapshot(sessionId = sessionId, transfers = snapshots))
        }

    override fun loadTransfer(transferId: TransferId): StoreReadResult<TransferSnapshot> =
        readResult {
            val row = database.transferSnapshotDao().find(transferId.value)
                ?: return@readResult StoreReadResult.Missing
            when (val decoded = decode(row)) {
                is StoreReadResult.Found -> if (decoded.value.transferId == transferId) {
                    decoded
                } else {
                    invalid("transfer_identity_mismatch")
                }
                is StoreReadResult.Failed -> decoded
                StoreReadResult.Missing -> invalid("snapshot_row_missing")
            }
        }

    override fun saveTransition(
        previous: TransferSnapshot?,
        current: TransferSnapshot,
        effects: List<TransferEffect>,
    ): StoreResult = writeResult("write") {
        if (TransferSnapshotEntityMapper.toEntity(current, rowRevision = 1L) == null) {
            return@writeResult StoreResult.Failed(
                TransferError.PersistedSnapshotInvalid("inconsistent_snapshot"),
            )
        }
        if (previous != null && !sameTransferScope(previous, current)) {
            return@writeResult StoreResult.Failed(TransferError.PersistenceConflict("transfer_scope_changed"))
        }
        if (previous != null && (previous.snapshotVersion == Long.MAX_VALUE ||
                current.snapshotVersion != previous.snapshotVersion + 1L)
        ) {
            return@writeResult StoreResult.Failed(TransferError.PersistenceConflict("snapshot_revision_gap"))
        }

        for (effect in effects) {
            when (effect) {
                is TransferEffect.PersistSnapshot -> if (
                    effect.transferId != current.transferId || effect.snapshotVersion != current.snapshotVersion
                ) {
                    return@writeResult StoreResult.Failed(
                        TransferError.PersistenceConflict("persist_snapshot_effect_mismatch"),
                    )
                }
                is TransferEffect.PersistConfirmedOffset -> if (
                    effect.transferId != current.transferId || effect.confirmedOffset != current.confirmedBytes
                ) {
                    return@writeResult StoreResult.Failed(
                        TransferError.PersistenceConflict("confirmed_offset_effect_mismatch"),
                    )
                }
                else -> Unit
            }
        }

        database.withTransaction {
            val dao = database.transferSnapshotDao()
            val storedRow = dao.find(current.transferId.value)
            val storedSnapshot = storedRow?.let { row ->
                when (val decoded = decode(row)) {
                    is StoreReadResult.Found -> decoded.value
                    is StoreReadResult.Failed -> return@withTransaction StoreResult.Failed(decoded.error)
                    StoreReadResult.Missing -> return@withTransaction StoreResult.Failed(
                        TransferError.PersistedSnapshotInvalid("snapshot_row_missing"),
                    )
                }
            }

            if (storedRow != null && storedSnapshot == current) {
                return@withTransaction StoreResult.Ok
            }
            if (previous == null) {
                if (storedRow != null) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("snapshot_already_exists"),
                    )
                }
            } else {
                if (storedRow == null || storedSnapshot == null) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("snapshot_previous_missing"),
                    )
                }
                if (storedSnapshot != previous) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("stale_snapshot_revision"),
                    )
                }
                if (current.confirmedBytes < storedSnapshot.confirmedBytes) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("confirmed_offset_regression"),
                    )
                }
            }
            if (storedRow?.rowRevision == Long.MAX_VALUE) {
                return@withTransaction StoreResult.Failed(
                    TransferError.PersistenceConflict("row_revision_exhausted"),
                )
            }
            val nextRevision = (storedRow?.rowRevision ?: 0L) + 1L
            val replacement = TransferSnapshotEntityMapper.toEntity(current, nextRevision)
                ?: return@withTransaction StoreResult.Failed(
                    TransferError.PersistedSnapshotInvalid("inconsistent_snapshot"),
                )
            dao.upsert(replacement)
            StoreResult.Ok
        }
    }

    override fun saveCheckpoint(transferId: TransferId, confirmedOffset: ConfirmedOffset): StoreResult =
        writeResult("checkpoint") {
            database.withTransaction {
                val dao = database.transferSnapshotDao()
                val row = dao.find(transferId.value)
                    ?: return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("snapshot_missing"),
                    )
                val snapshot = when (val decoded = decode(row)) {
                    is StoreReadResult.Found -> decoded.value
                    is StoreReadResult.Failed -> return@withTransaction StoreResult.Failed(decoded.error)
                    StoreReadResult.Missing -> return@withTransaction StoreResult.Failed(
                        TransferError.PersistedSnapshotInvalid("snapshot_row_missing"),
                    )
                }
                if (confirmedOffset.value > snapshot.totalBytes) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("confirmed_offset_exceeds_total"),
                    )
                }
                if (confirmedOffset.value < snapshot.confirmedBytes) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("confirmed_offset_regression"),
                    )
                }
                if (confirmedOffset.value == snapshot.confirmedBytes) return@withTransaction StoreResult.Ok
                val updated = snapshot.copy(confirmedBytes = confirmedOffset.value)
                if (updated.violations().isNotEmpty()) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("checkpoint_inconsistent_with_state"),
                    )
                }
                if (row.rowRevision == Long.MAX_VALUE) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("row_revision_exhausted"),
                    )
                }
                // The confirmed offset is a dedicated column so acknowledgements
                // need not rewrite descriptor, error, and verification metadata.
                dao.upsert(row.copy(
                    confirmedBytes = confirmedOffset.value,
                    rowRevision = row.rowRevision + 1L,
                ))
                StoreResult.Ok
            }
        }

    override fun saveVerificationResult(
        transferId: TransferId,
        result: VerificationInfo,
    ): StoreResult = writeResult("verification") {
        database.withTransaction {
            val dao = database.transferSnapshotDao()
            val row = dao.find(transferId.value)
                ?: return@withTransaction StoreResult.Failed(TransferError.PersistenceConflict("snapshot_missing"))
            val snapshot = when (val decoded = decode(row)) {
                is StoreReadResult.Found -> decoded.value
                is StoreReadResult.Failed -> return@withTransaction StoreResult.Failed(decoded.error)
                StoreReadResult.Missing -> return@withTransaction StoreResult.Failed(
                    TransferError.PersistedSnapshotInvalid("snapshot_row_missing"),
                )
            }
            if (result.startedSnapshotVersion != snapshot.snapshotVersion) {
                return@withTransaction StoreResult.Failed(
                    TransferError.PersistenceConflict("verification_snapshot_revision_mismatch"),
                )
            }
            if (snapshot.verification == result) return@withTransaction StoreResult.Ok
            if (row.rowRevision == Long.MAX_VALUE) {
                return@withTransaction StoreResult.Failed(
                    TransferError.PersistenceConflict("row_revision_exhausted"),
                )
            }
            val updated = snapshot.copy(verification = result)
            if (updated.violations().isNotEmpty()) {
                return@withTransaction StoreResult.Failed(
                    TransferError.PersistenceConflict("verification_inconsistent_with_state"),
                )
            }
            val replacement = TransferSnapshotEntityMapper.toEntity(updated, row.rowRevision + 1L)
                ?: return@withTransaction StoreResult.Failed(
                    TransferError.PersistedSnapshotInvalid("inconsistent_snapshot"),
                )
            dao.upsert(replacement)
            StoreResult.Ok
        }
    }

    override fun listResumable(sessionId: SessionId): StoreReadResult<List<TransferSnapshot>> =
        readResult {
            val rows = database.transferSnapshotDao().forSession(sessionId.value)
            if (rows.isEmpty()) return@readResult StoreReadResult.Missing
            val resumable = ArrayList<TransferSnapshot>()
            for (row in rows) {
                when (val decoded = decode(row)) {
                    is StoreReadResult.Failed -> return@readResult decoded
                    StoreReadResult.Missing -> return@readResult invalid("snapshot_row_missing")
                    is StoreReadResult.Found -> {
                        if (decoded.value.sessionId != sessionId) return@readResult invalid("session_scope_mismatch")
                        if (!decoded.value.isTerminal) resumable += decoded.value
                    }
                }
            }
            StoreReadResult.Found(resumable)
        }

    override fun removeTerminal(transferId: TransferId, policy: RetentionPolicy): StoreResult =
        writeResult("delete") {
            database.withTransaction {
                val snapshotDao = database.transferSnapshotDao()
                val row = snapshotDao.find(transferId.value)
                    ?: return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("snapshot_missing"),
                    )
                val snapshot = when (val decoded = decode(row)) {
                    is StoreReadResult.Found -> decoded.value
                    is StoreReadResult.Failed -> return@withTransaction StoreResult.Failed(decoded.error)
                    StoreReadResult.Missing -> return@withTransaction StoreResult.Failed(
                        TransferError.PersistedSnapshotInvalid("snapshot_row_missing"),
                    )
                }
                if (!snapshot.isTerminal) {
                    return@withTransaction StoreResult.Failed(
                        TransferError.PersistenceConflict("terminal_snapshot_required"),
                    )
                }
                if (policy == RetentionPolicy.DISCARD_IMMEDIATELY) {
                    val hasSafCheckpoint = database.transferPartialDao()
                        .forTransfer(transferId.value)
                        .isNotEmpty()
                    if (hasSafCheckpoint) {
                        // The SAF journal is a separate recovery authority. Leave
                        // the transfer row intact until a validated cleanup policy
                        // explicitly owns both stores.
                        return@withTransaction StoreResult.Failed(
                            TransferError.PersistenceConflict("partial_recovery_pending"),
                        )
                    }
                    snapshotDao.delete(transferId.value)
                }
                StoreResult.Ok
            }
        }

    private fun decode(row: TransferSnapshotEntity): StoreReadResult<TransferSnapshot> =
        TransferSnapshotEntityMapper.decode(row)

    private fun sameTransferScope(previous: TransferSnapshot, current: TransferSnapshot): Boolean =
        previous.transferId == current.transferId &&
            previous.sessionId == current.sessionId &&
            previous.batchId == current.batchId &&
            previous.recipientId == current.recipientId &&
            previous.direction == current.direction &&
            previous.descriptor == current.descriptor

    private fun invalid(reason: String): StoreReadResult.Failed =
        StoreReadResult.Failed(TransferError.PersistedSnapshotInvalid(reason))

    private fun <T> readResult(block: suspend () -> StoreReadResult<T>): StoreReadResult<T> =
        callDatabase(
            onFailure = { error -> StoreReadResult.Failed(error) },
            block = block,
        )

    private fun writeResult(operation: String, block: suspend () -> StoreResult): StoreResult =
        callDatabase(
            onFailure = { error -> StoreResult.Failed(error) },
            block = block,
            operation = operation,
        )

    private fun <T> callDatabase(
        onFailure: (TransferError) -> T,
        block: suspend () -> T,
        operation: String = "read",
    ): T = try {
        runBlocking(ioDispatcher) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(
            TransferError.PersistenceFailure(
                operation = operation,
                storageFull = failure.causeChainContainsFullException(),
            ),
        )
    }

    private fun Throwable.causeChainContainsFullException(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is SQLiteFullException) return true
            cause = cause.cause
        }
        return false
    }
}
