package app.morsecode.core.transfer.reducer

import app.morsecode.core.model.TransferState

/*
 * The legal transition table for Milestone 3.
 *
 * core-model's `TransferState.allowedTransitions` remains the looser, UI-facing
 * table (it also describes what a screen is allowed to offer). This table is the
 * stricter one the reducer enforces; [divergenceFromCoreModel] lists exactly
 * where the two differ, so the difference is reviewable instead of accidental.
 *
 * The three deliberate tightenings:
 *
 *  * A paused transfer resumes through NEGOTIATING, never straight back into
 *    SENDING or RECEIVING, so the confirmed offset is re-agreed with the peer
 *    before any byte moves.
 *  * FAILED_FINAL is terminal. core-model allows FAILED_FINAL → CANCELLED for
 *    the "dismiss this row" gesture; the reducer treats that as a UI-level
 *    removal, not a transfer transition.
 *  * PAUSED_LOCAL and PAUSED_REMOTE may move into each other so that a both-
 *    sides pause is representable, but a delivery can never leave a paused state
 *    for anything other than NEGOTIATING, the other paused state, or CANCELLED.
 */

public object TransferTransitionTable {

    public val allowed: Map<TransferState, Set<TransferState>> = mapOf(
        TransferState.QUEUED to setOf(
            TransferState.NEGOTIATING,
            TransferState.PAUSED_LOCAL,
            TransferState.SKIPPED,
            TransferState.CANCELLED,
            TransferState.FAILED_FINAL,
        ),
        TransferState.NEGOTIATING to setOf(
            TransferState.SENDING,
            TransferState.RECEIVING,
            TransferState.PAUSED_LOCAL,
            TransferState.SKIPPED,
            TransferState.CANCELLED,
            TransferState.FAILED_RETRYABLE,
            TransferState.FAILED_FINAL,
        ),
        TransferState.SENDING to setOf(
            TransferState.VERIFYING,
            TransferState.PAUSED_LOCAL,
            TransferState.PAUSED_REMOTE,
            TransferState.CANCELLED,
            TransferState.FAILED_RETRYABLE,
            TransferState.FAILED_FINAL,
        ),
        TransferState.RECEIVING to setOf(
            TransferState.VERIFYING,
            TransferState.PAUSED_LOCAL,
            TransferState.PAUSED_REMOTE,
            TransferState.CANCELLED,
            TransferState.FAILED_RETRYABLE,
            TransferState.FAILED_FINAL,
        ),
        TransferState.PAUSED_LOCAL to setOf(
            TransferState.NEGOTIATING,
            TransferState.PAUSED_REMOTE,
            TransferState.CANCELLED,
        ),
        TransferState.PAUSED_REMOTE to setOf(
            TransferState.NEGOTIATING,
            TransferState.PAUSED_LOCAL,
            TransferState.CANCELLED,
        ),
        TransferState.VERIFYING to setOf(
            TransferState.COMPLETED,
            TransferState.CANCELLED,
            TransferState.FAILED_RETRYABLE,
            TransferState.FAILED_FINAL,
        ),
        TransferState.FAILED_RETRYABLE to setOf(
            TransferState.QUEUED,
            TransferState.SKIPPED,
            TransferState.CANCELLED,
        ),
        TransferState.COMPLETED to emptySet(),
        TransferState.FAILED_FINAL to emptySet(),
        TransferState.CANCELLED to emptySet(),
        TransferState.SKIPPED to emptySet(),
    )

    /** True when the reducer will accept `from -> to`. */
    public fun canMove(from: TransferState, to: TransferState): Boolean =
        to in (allowed[from] ?: emptySet())

    /** Every legal edge, ordered by source state. */
    public fun legalEdges(): List<Pair<TransferState, TransferState>> =
        TransferState.entries.flatMap { from ->
            (allowed[from] ?: emptySet()).map { to -> from to to }
        }

    /** Every edge core-model allows that this table refuses. */
    public fun divergenceFromCoreModel(): List<Pair<TransferState, TransferState>> =
        TransferState.entries.flatMap { from ->
            (TransferState.allowedTransitions[from] ?: emptySet())
                .filter { to -> !canMove(from, to) }
                .map { to -> from to to }
        }

    /** Every edge this table allows that core-model does not. */
    public fun additionsBeyondCoreModel(): List<Pair<TransferState, TransferState>> =
        TransferState.entries.flatMap { from ->
            (allowed[from] ?: emptySet())
                .filter { to -> to !in (TransferState.allowedTransitions[from] ?: emptySet()) }
                .map { to -> from to to }
        }

    /** Markdown table used by doc/transfer-protocol.md and by the docs test. */
    public fun toMarkdown(): String = buildString {
        appendLine("| From | Allowed next states |")
        appendLine("| --- | --- |")
        for (from in TransferState.entries) {
            val targets = allowed[from] ?: emptySet()
            val rendered = if (targets.isEmpty()) {
                "*(terminal)*"
            } else {
                TransferState.entries.filter { it in targets }.joinToString(", ") { "`${it.name}`" }
            }
            appendLine("| `${from.name}` | $rendered |")
        }
    }

    /** The twelve states, in declaration order. */
    public val states: List<TransferState> = TransferState.entries.toList()
}
