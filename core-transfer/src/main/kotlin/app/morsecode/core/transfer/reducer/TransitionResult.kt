package app.morsecode.core.transfer.reducer

import app.morsecode.core.transfer.effect.TransferEffect
import app.morsecode.core.transfer.model.TransferSnapshot

/*
 * The reducer's return type.
 *
 * There are exactly two outcomes, and both are values:
 *
 *   Accepted(previous, current, effects) — a legal transition, with the ordered
 *   list of work an adapter must now perform.
 *
 *   Rejected(snapshot, reason)           — an illegal one; the snapshot is
 *   returned unchanged so a caller can keep using it without re-reading state.
 *
 * `previous` is nullable because the very first accepted transition (Enqueue)
 * has no predecessor.
 */

public sealed class TransitionResult {

    /** A legal transition. [effects] is ordered and may repeat. */
    public data class Accepted(
        public val previous: TransferSnapshot?,
        public val current: TransferSnapshot,
        public val effects: List<TransferEffect>,
    ) : TransitionResult() {
        /** True when the snapshot actually moved; false for an idempotent no-op. */
        public val changed: Boolean get() = previous == null || previous != current

        /** True when this transition produced no work for any adapter. */
        public val isNoOp: Boolean get() = !changed && effects.isEmpty()
    }

    /** An illegal transition; [snapshot] is the untouched input. */
    public data class Rejected(
        public val snapshot: TransferSnapshot?,
        public val reason: Rejection,
    ) : TransitionResult()

    public companion object {
        // --- small helpers so call sites read clearly -----------------------
        public fun accepted(
            previous: TransferSnapshot?,
            current: TransferSnapshot,
            effects: List<TransferEffect>,
        ): Accepted = Accepted(previous, current, effects)

        public fun rejected(
            snapshot: TransferSnapshot?,
            reason: Rejection,
        ): Rejected = Rejected(snapshot, reason)
    }
}

/** Convenience: the accepted snapshot, or null when the transition was refused. */
public val TransitionResult.snapshotOrNull: TransferSnapshot?
    get() = when (this) {
        is TransitionResult.Accepted -> current
        is TransitionResult.Rejected -> snapshot
    }

/** Convenience: the rejection, or null when the transition was accepted. */
public val TransitionResult.rejectionOrNull: Rejection?
    get() = (this as? TransitionResult.Rejected)?.reason

/** Convenience: the effects of an accepted transition, empty otherwise. */
public val TransitionResult.effectsOrEmpty: List<TransferEffect>
    get() = (this as? TransitionResult.Accepted)?.effects ?: emptyList()
