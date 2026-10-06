package app.morsecode.core.storage.transfer

import kotlinx.coroutines.CancellationException

/**
 * Per-coordinator/per-thread revision cursor for the synchronous SAF state
 * machine. It keeps every replacement tied to the exact read/previous write
 * made by this operation, while the journal remains an explicit CAS API.
 */
internal class SafCommitJournalCursor(private val journal: SafCommitJournal) {
    private val revisions = ThreadLocal.withInitial { linkedMapOf<PartialIdentity, Long>() }
    private val lastWriteFailure = ThreadLocal<TransferStorageError?>()

    private fun rememberRevision(commitId: PartialIdentity, revision: Long) {
        val current = revisions.get()
        current.remove(commitId)
        current[commitId] = revision
        if (current.size > MAX_REMEMBERED_COMMITS) {
            current.keys.firstOrNull()?.let(current::remove)
        }
    }

    private companion object {
        const val MAX_REMEMBERED_COMMITS: Int = 16
    }

    fun load(commitId: PartialIdentity): SafCommitCheckpoint? {
        SafCommitCheckpointValidator.validateCommitId(commitId)?.let { error ->
            revisions.get().remove(commitId)
            throw SafCommitJournalReadException(error)
        }
        val result = try {
            journal.read(commitId)
        } catch (_: CancellationException) {
            revisions.get().remove(commitId)
            throw SafCommitJournalReadException(TransferStorageError.Cancelled)
        }
        return when (result) {
            SafCommitJournalRead.Missing -> {
                rememberRevision(commitId, 0L)
                null
            }
            is SafCommitJournalRead.Found -> {
                val entry = result.entry
                SafCommitCheckpointValidator.validate(entry.checkpoint)?.let { error ->
                    revisions.get().remove(commitId)
                    throw SafCommitJournalReadException(error)
                }
                rememberRevision(commitId, entry.revision)
                entry.checkpoint
            }
            is SafCommitJournalRead.Rejected -> {
                revisions.get().remove(commitId)
                throw SafCommitJournalReadException(result.error)
            }
        }
    }

    fun save(checkpoint: SafCommitCheckpoint): Boolean = when (val result = write(checkpoint)) {
        is SafCommitJournalWrite.Saved -> {
            lastWriteFailure.remove()
            true
        }
        is SafCommitJournalWrite.Conflict -> {
            lastWriteFailure.set(result.error)
            false
        }
        is SafCommitJournalWrite.Rejected -> {
            lastWriteFailure.set(result.error)
            false
        }
    }

    /** The most recent failed [save] on this thread, consumed once by the coordinator. */
    fun consumeWriteFailure(): TransferStorageError? = lastWriteFailure.get().also { lastWriteFailure.remove() }

    /** Used only when the checkpoint could not be constructed before a journal write. */
    fun rememberWriteFailure(error: TransferStorageError) {
        lastWriteFailure.set(error)
    }

    fun write(checkpoint: SafCommitCheckpoint): SafCommitJournalWrite {
        SafCommitCheckpointValidator.validate(checkpoint)?.let { error ->
            return SafCommitJournalWrite.Rejected(error)
        }
        val expected = revisions.get()[checkpoint.commitId] ?: 0L
        val result = try {
            journal.write(checkpoint, expected)
        } catch (_: CancellationException) {
            SafCommitJournalWrite.Rejected(TransferStorageError.Cancelled)
        } catch (_: Exception) {
            SafCommitJournalWrite.Rejected(TransferStorageError.Io("journal_write"))
        }
        if (result is SafCommitJournalWrite.Saved) {
            rememberRevision(checkpoint.commitId, result.revision)
        }
        return result
    }
}

/** Typed invalid-persisted-state escape for existing synchronous outcome paths. */
internal class SafCommitJournalReadException(
    val error: TransferStorageError,
) : IllegalStateException("SAF checkpoint was rejected")

/** Test-facing convenience; a fresh cursor prevents a process-lifetime journal cache. */
internal fun SafCommitJournal.load(commitId: PartialIdentity): SafCommitCheckpoint? =
    SafCommitJournalCursor(this).load(commitId)

internal fun SafCommitJournal.save(checkpoint: SafCommitCheckpoint): Boolean {
    val cursor = SafCommitJournalCursor(this)
    try {
        cursor.load(checkpoint.commitId)
    } catch (_: SafCommitJournalReadException) {
        return false
    } catch (_: Exception) {
        return false
    }
    return cursor.save(checkpoint)
}
