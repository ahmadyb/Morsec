package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.PartialIdentity

/** Test-only deterministic journal; production receives only the interface. */
internal class InMemorySafCommitJournal(
    private val eventLog: MutableList<String>? = null,
) : SafCommitJournal {
    val checkpoints: MutableMap<PartialIdentity, SafCommitCheckpoint> = linkedMapOf()
    val writes: MutableList<SafCommitCheckpoint> = mutableListOf()
    var rejectNextSave: Boolean = false
    var saveAttempts: Int = 0
        private set
    var rejectOnSaveAttempt: Int? = null
    var rejectNextPhase: SafCommitCheckpointPhase? = null

    override fun load(commitId: PartialIdentity): SafCommitCheckpoint? = checkpoints[commitId]

    override fun save(checkpoint: SafCommitCheckpoint): Boolean {
        eventLog?.add("journal-save:${checkpoint.phase.id}")
        saveAttempts++
        val rejectPhase = rejectNextPhase == checkpoint.phase
        if (rejectPhase) rejectNextPhase = null
        if (rejectNextSave || rejectPhase || saveAttempts == rejectOnSaveAttempt) {
            rejectNextSave = false
            return false
        }
        checkpoints[checkpoint.commitId] = checkpoint
        writes += checkpoint
        return true
    }
}
