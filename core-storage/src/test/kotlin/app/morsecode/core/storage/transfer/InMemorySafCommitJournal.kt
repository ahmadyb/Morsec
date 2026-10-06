package app.morsecode.core.storage.transfer

/** Test-only deterministic journal; production receives only the interface. */
internal class InMemorySafCommitJournal(
    private val eventLog: MutableList<String>? = null,
) : SafCommitJournal {
    val checkpoints: MutableMap<PartialIdentity, SafCommitCheckpoint> = linkedMapOf()
    val writes: MutableList<SafCommitCheckpoint> = mutableListOf()
    private val revisions: MutableMap<PartialIdentity, Long> = linkedMapOf()
    var rejectNextSave: Boolean = false
    var rejectWriteError: TransferStorageError? = null
    var saveAttempts: Int = 0
        private set
    var rejectOnSaveAttempt: Int? = null
    var rejectNextPhase: SafCommitCheckpointPhase? = null
    var afterSave: ((SafCommitCheckpoint) -> Unit)? = null

    override fun read(commitId: PartialIdentity): SafCommitJournalRead =
        checkpoints[commitId]?.let { checkpoint ->
            SafCommitJournalRead.Found(
                SafCommitJournalEntry(checkpoint, revisions[commitId] ?: 1L),
            )
        } ?: SafCommitJournalRead.Missing

    override fun write(
        checkpoint: SafCommitCheckpoint,
        expectedRevision: Long,
    ): SafCommitJournalWrite {
        eventLog?.add("journal-save:${checkpoint.phase.id}")
        saveAttempts++
        val rejectPhase = rejectNextPhase == checkpoint.phase
        if (rejectPhase) rejectNextPhase = null
        val rejectByAttempt = saveAttempts == rejectOnSaveAttempt
        val rejectByInjectedError = rejectWriteError != null && rejectOnSaveAttempt == null
        if (rejectNextSave || rejectPhase || rejectByAttempt || rejectByInjectedError) {
            rejectNextSave = false
            val error = rejectWriteError ?: TransferStorageError.StateConflict("test_save_rejected")
            rejectWriteError = null
            return SafCommitJournalWrite.Rejected(error)
        }
        SafCommitCheckpointValidator.validate(checkpoint)?.let { error ->
            return SafCommitJournalWrite.Rejected(error)
        }
        val actualRevision = revisions[checkpoint.commitId] ?: 0L
        val existing = checkpoints[checkpoint.commitId]
        if (existing == checkpoint) return SafCommitJournalWrite.Saved(actualRevision)
        if (expectedRevision != actualRevision) {
            return SafCommitJournalWrite.Conflict(TransferStorageError.StateConflict("stale_journal_revision"))
        }
        if (actualRevision == Long.MAX_VALUE) {
            return SafCommitJournalWrite.Conflict(TransferStorageError.StateConflict("journal_revision_exhausted"))
        }
        val nextRevision = actualRevision + 1L
        checkpoints[checkpoint.commitId] = checkpoint
        revisions[checkpoint.commitId] = nextRevision
        writes += checkpoint
        afterSave?.invoke(checkpoint)
        return SafCommitJournalWrite.Saved(nextRevision)
    }
}
