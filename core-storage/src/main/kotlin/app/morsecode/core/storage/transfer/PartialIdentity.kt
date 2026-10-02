package app.morsecode.core.storage.transfer

import app.morsecode.core.transfer.identity.TransferId

/*
 * Identity and lifecycle of an incomplete file.
 *
 * A partial is identified by an opaque key derived from the transfer and the
 * strategy, never by its file name. That matters more than it sounds: a name is
 * the one thing on this platform that a user, a gallery app, a cloud-sync client
 * or the provider itself is free to change underneath us. If the recovery row
 * were keyed on "the file called foo.mp4.part", then a rename — or a provider
 * that silently appends " (1)" because something already exists — would orphan
 * the only record of an incomplete download while leaving the bytes on disk.
 *
 * So the row is keyed on the identity, the identity is derived deterministically
 * from the transfer id, and the on-disk name is a *consequence* of the identity
 * rather than its source.
 */

/**
 * Stable identity of one incomplete file.
 *
 * Opaque, and safe to persist: it contains no path and no user-visible name.
 */
public data class PartialIdentity(public val value: String) {
    init {
        require(value.isNotBlank()) { "PartialIdentity must not be blank" }
    }

    public companion object {
        /**
         * Derives the identity for a transfer under a strategy.
         *
         * Deterministic on purpose: the same transfer resumed in a new process
         * must reach the same identity without asking anyone, and must not
         * collide with a concurrent transfer of a file that happens to share a
         * display name.
         */
        public fun of(transferId: TransferId, strategy: DestinationStrategy): PartialIdentity =
            PartialIdentity("${strategy.id}:${transferId.value}")

        public fun fromRow(value: String?): PartialIdentity? =
            value?.takeIf { it.isNotBlank() }?.let(::PartialIdentity)
    }
}

/**
 * Where a partial is in its life, and the only place that life is described.
 *
 * These states exist because "the transfer finished" and "the file exists at its
 * final location" are different facts, and the gap between them is exactly where
 * a process death used to lose work. `COMMITTING` in particular is a state that
 * must survive a restart: a crash during the final copy has to be resumable, not
 * silently downgraded to "the file is missing".
 */
public enum class CommitState(public val id: String) {
    /** Bytes are still arriving; nothing has been verified. */
    NOT_READY("not_ready"),

    /** Verification passed; the final move has not started. */
    READY_TO_COMMIT("ready_to_commit"),

    /** The final move is under way. A crash here must be recoverable. */
    COMMITTING("committing"),

    /** The file is at its final location under its final name. Terminal. */
    COMMITTED("committed"),

    /** The final move was attempted and failed. Retryable, and stays visible. */
    COMMIT_FAILED("commit_failed"),

    /** Deliberately discarded rather than completed. Terminal. */
    ABANDONED("abandoned"),
    ;

    /** True when no further transition is possible. */
    public val isTerminal: Boolean get() = this == COMMITTED || this == ABANDONED

    /** True when a crash in this state requires work on the next start. */
    public val requiresRecovery: Boolean
        get() = this == COMMITTING || this == COMMIT_FAILED || this == READY_TO_COMMIT

    /** True when the partial file still has to exist on storage. */
    public val ownsPartialFile: Boolean
        get() = this == NOT_READY || this == READY_TO_COMMIT ||
            this == COMMITTING || this == COMMIT_FAILED

    public companion object {
        public fun fromId(id: String?): CommitState =
            entries.firstOrNull { it.id == id } ?: NOT_READY

        private val ALLOWED: Map<CommitState, Set<CommitState>> = mapOf(
            NOT_READY to setOf(READY_TO_COMMIT, ABANDONED),
            READY_TO_COMMIT to setOf(COMMITTING, ABANDONED),
            COMMITTING to setOf(COMMITTED, COMMIT_FAILED),
            COMMIT_FAILED to setOf(COMMITTING, ABANDONED),
            COMMITTED to emptySet(),
            ABANDONED to emptySet(),
        )

        /** The states reachable from [from], in one step. */
        public fun next(from: CommitState): Set<CommitState> = ALLOWED[from] ?: emptySet()

        public fun allows(from: CommitState, to: CommitState): Boolean = to in next(from)
    }
}
