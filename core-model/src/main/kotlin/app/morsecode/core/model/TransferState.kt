package app.morsecode.core.model

/**
 * Explicit transfer states required by the master prompt (§6.3).
 *
 * The state of a single file inside a session. Every transition the engine may
 * perform is declared in [allowedTransitions]; anything else is a defect and is
 * rejected by [requireTransition] rather than silently applied. State changes
 * are persisted (Room) and surfaced to the UI as a Flow, so the interface never
 * shows progress that the engine did not report.
 */
public enum class TransferState(
    /**
     * Stable wire and storage value. Both peers and the database agree on this
     * spelling, so renaming a Kotlin constant never invalidates a resumable
     * offset that was already persisted.
     */
    public val id: String,
) {
    /** Accepted into the queue, waiting for a free transfer slot. */
    QUEUED("queued"),

    /** Handshake in flight: descriptors, offsets and resume point being agreed. */
    NEGOTIATING("negotiating"),

    /** Outbound bytes flowing; the confirmed offset advances as ACKs arrive. */
    SENDING("sending"),

    /** Inbound bytes flowing toward the temporary partial target. */
    RECEIVING("receiving"),

    /** Paused by this device. The confirmed offset is preserved. */
    PAUSED_LOCAL("paused_local"),

    /** Paused by the peer; retry is not offered until the peer resumes. */
    PAUSED_REMOTE("paused_remote"),

    /** All bytes received; SHA-256 of the whole file is being checked. */
    VERIFYING("verifying"),

    /** Verified and committed to its final target. Immutable in the session UI. */
    COMPLETED("completed"),

    /** Failed for a recoverable reason. Retry is available for this file only. */
    FAILED_RETRYABLE("failed_retryable"),

    /** Failed for a reason retrying cannot fix (revoked grant, no space, policy). */
    FAILED_FINAL("failed_final"),

    /** Explicitly cancelled by either peer and acknowledged on both sides. */
    CANCELLED("cancelled"),

    /** Not transferred because of the duplicate policy or a rejected descriptor. */
    SKIPPED("skipped"),
    ;

    /** True when no further bytes will move in this state without user action. */
    public val isTerminal: Boolean
        get() = this in TERMINAL

    /** True while the engine is actively moving bytes or verifying them. */
    public val isBusy: Boolean
        get() = this in BUSY

    /** True while the item is waiting for a slot and can still be removed. */
    public val isPending: Boolean
        get() = this == QUEUED || this == NEGOTIATING

    /** True when the user paused it locally and can resume it. */
    public val isPaused: Boolean
        get() = this == PAUSED_LOCAL || this == PAUSED_REMOTE

    public companion object {
        /**
         * Parses a persisted or on-the-wire state. Unknown values fall back to
         * [FAILED_RETRYABLE] so an old row written by a newer build is surfaced
         * as a retryable failure instead of disappearing from the list.
         */
        public fun fromId(id: String?): TransferState =
            entries.firstOrNull { it.id == id } ?: FAILED_RETRYABLE

        /**
         * The complete legal transition table.
         *
         * Reading it as a map (rather than scattering `if` checks through the
         * engine) is what makes "retrying a failed file does not restart
         * unrelated files" and "cancellation is explicit" enforceable and
         * unit testable.
         */
        public val allowedTransitions: Map<TransferState, Set<TransferState>> = mapOf(
            QUEUED to setOf(NEGOTIATING, CANCELLED, SKIPPED, PAUSED_LOCAL),
            NEGOTIATING to setOf(SENDING, RECEIVING, FAILED_RETRYABLE, FAILED_FINAL, CANCELLED, SKIPPED, PAUSED_LOCAL),
            SENDING to setOf(VERIFYING, PAUSED_LOCAL, PAUSED_REMOTE, FAILED_RETRYABLE, FAILED_FINAL, CANCELLED),
            RECEIVING to setOf(VERIFYING, PAUSED_LOCAL, PAUSED_REMOTE, FAILED_RETRYABLE, FAILED_FINAL, CANCELLED),
            // A paused item may resume in either direction (sessions are duplex)
            // or be cancelled. It never jumps straight to COMPLETED.
            PAUSED_LOCAL to setOf(NEGOTIATING, SENDING, RECEIVING, CANCELLED, FAILED_FINAL),
            PAUSED_REMOTE to setOf(SENDING, RECEIVING, CANCELLED, FAILED_RETRYABLE, FAILED_FINAL),
            VERIFYING to setOf(COMPLETED, FAILED_RETRYABLE, FAILED_FINAL, CANCELLED),
            // Retry re-enters negotiation so the confirmed offset is re-agreed
            // with the peer instead of trusting a local guess.
            FAILED_RETRYABLE to setOf(QUEUED, NEGOTIATING, CANCELLED, SKIPPED),
            COMPLETED to emptySet(),
            FAILED_FINAL to setOf(CANCELLED),
            CANCELLED to emptySet(),
            SKIPPED to emptySet(),
        )

        private val TERMINAL: Set<TransferState> =
            setOf(COMPLETED, FAILED_FINAL, CANCELLED, SKIPPED)

        private val BUSY: Set<TransferState> =
            setOf(SENDING, RECEIVING, VERIFYING, NEGOTIATING)
    }
}

/** True when moving from this state to [next] is legal. */
public fun TransferState.canTransitionTo(next: TransferState): Boolean =
    next in (TransferState.allowedTransitions[this] ?: emptySet())

/**
 * Applies a transition, throwing when the engine attempts an illegal one.
 * Illegal transitions indicate a protocol or persistence defect, and failing
 * loudly is safer than corrupting a resumable offset.
 */
public fun TransferState.requireTransition(next: TransferState): TransferState {
    check(canTransitionTo(next)) { "illegal transfer transition $this -> $next" }
    return next
}

/**
 * The per-file action matrix from the master prompt (§5 Transfers):
 *
 *  - Sending or receiving: Pause and Cancel
 *  - Paused: Resume and Cancel
 *  - Queued: Cancel
 *  - Failed: Retry only
 *  - Completed: no pause, resume, cancel or retry control at all
 *
 * The UI derives its buttons from this function, so the matrix cannot drift
 * between screens.
 */
public data class ItemActions(
    val pause: Boolean,
    val resume: Boolean,
    val cancel: Boolean,
    val retry: Boolean,
) {
    public val isEmpty: Boolean get() = !pause && !resume && !cancel && !retry

    public companion object {
        public val None: ItemActions = ItemActions(pause = false, resume = false, cancel = false, retry = false)
    }
}

/**
 * Both paused flavours offer Resume and Cancel. Resuming an item the peer paused
 * sends an explicit resume request over the control channel rather than starting
 * to push bytes into a stalled socket.
 */
public fun TransferState.itemActions(): ItemActions =
    when (this) {
        TransferState.SENDING, TransferState.RECEIVING ->
            ItemActions(pause = true, resume = false, cancel = true, retry = false)

        TransferState.PAUSED_LOCAL, TransferState.PAUSED_REMOTE ->
            ItemActions(pause = false, resume = true, cancel = true, retry = false)

        TransferState.QUEUED, TransferState.NEGOTIATING ->
            ItemActions(pause = false, resume = false, cancel = true, retry = false)

        TransferState.VERIFYING ->
            ItemActions(pause = false, resume = false, cancel = true, retry = false)

        TransferState.FAILED_RETRYABLE ->
            ItemActions(pause = false, resume = false, cancel = false, retry = true)

        TransferState.COMPLETED, TransferState.CANCELLED, TransferState.SKIPPED, TransferState.FAILED_FINAL ->
            ItemActions.None
    }
