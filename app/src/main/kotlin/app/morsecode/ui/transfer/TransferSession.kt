package app.morsecode.ui.transfer

import app.morsecode.core.model.MediaKind

/**
 * Which way a file is going, and the state that means "moving" in that direction.
 *
 * A duplex session carries both at once — the master prompt's two symmetrical views are
 * the same session seen from each end — so a state that depends on direction is derived
 * from this rather than written out twice.
 */
public enum class TransferDirection(
    public val id: String,
    /** The state a paused item in this direction resumes into. */
    public val activeState: TransferState,
) {
    OUTGOING("out", TransferState.SENDING),
    INCOMING("in", TransferState.RECEIVING),
    ;

    public companion object {
        public fun fromId(id: String?): TransferDirection? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a single file is doing.
 *
 * The reference's own vocabulary (`chipFor` names exactly these), extended by three
 * states it leaves out: [VERIFYING], because the master prompt verifies every delivered
 * file and a checksum that takes a moment is a state rather than a lie; [CANCELLED],
 * because the reference turns a cancelled transfer into `failed` and a user who cancelled
 * something deserves to see that rather than a failure they did not cause; and [SKIPPED],
 * which the reference's own summary counts ("1 skipped") without ever drawing.
 *
 * The classification below is the single place that decides what a state permits. No
 * screen re-derives these answers from `when (state)`, which is how two screens come to
 * disagree about whether a paused row can be cancelled.
 */
public enum class TransferState(public val id: String) {
    /** Waiting for its turn behind whatever is moving. */
    QUEUED("queued"),

    /** Moving, outbound. */
    SENDING("sending"),

    /** Moving, inbound. */
    RECEIVING("receiving"),

    /** Held where it stopped, resumable from the byte it reached. */
    PAUSED("paused"),

    /** Every byte arrived; the checksum is being compared. */
    VERIFYING("verifying"),

    /** Arrived and verified. */
    DONE("done"),

    /** Stopped by something other than the user, and retryable. */
    FAILED("failed"),

    /** Stopped because the user said so. */
    CANCELLED("cancelled"),

    /** Deliberately not transferred — a duplicate the policy skipped, for instance. */
    SKIPPED("skipped"),
    ;

    /** Moving under its own power right now. */
    public val isActive: Boolean get() = this == SENDING || this == RECEIVING

    /** Can be held where it is. */
    public val isPausable: Boolean get() = isActive

    /** Can be set going again. */
    public val isResumable: Boolean get() = this == PAUSED

    /** Can be stopped by the user, whether or not it is moving. */
    public val isCancellable: Boolean get() = isActive || this == QUEUED || this == PAUSED

    /** Can be sent again from the beginning. */
    public val isRetryable: Boolean get() = this == FAILED

    /** Arrived. */
    public val isComplete: Boolean get() = this == DONE

    /** Nothing further will happen to it without the user asking. */
    public val isFinished: Boolean
        get() = this == DONE || this == FAILED || this == CANCELLED || this == SKIPPED

    /** Still the engine's business: moving, waiting, held or being verified. */
    public val isUnfinished: Boolean get() = !isFinished

    public companion object {
        public fun fromId(id: String?): TransferState? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What a checksum comparison said.
 *
 * [PENDING] is the honest default: nothing has been compared yet, which is not the same
 * answer as "compared and fine".
 */
public enum class VerificationOutcome {
    PENDING,
    VERIFIED,
    MISMATCH,
}

/**
 * The other end of the session: who these files are going to or coming from.
 *
 * [transport] is the label the app's copy uses everywhere else ("LAN" or "Nearby"), so
 * the peer card and the Connect screen cannot describe the same connection two ways.
 */
public data class TransferPeer(
    public val id: String,
    public val name: String,
    public val letter: String,
    public val transport: String,
    public val address: String,
)

/**
 * One file in one direction.
 *
 * Everything the row needs is here and nothing is inferred from the screen: the id the
 * engine will key on, the direction, the name the user recognises, the kind that decides
 * the icon, the two byte counts, the speed while it is moving, the state, the checksum
 * answer, and the session and peer it belongs to. The three eligibility flags are the
 * model's way of saying what the *engine* will allow — a server that does not do range
 * requests cannot resume, whatever the state says — and [TransferActions] is the single
 * place where eligibility and state are combined into what a row may offer.
 *
 * Byte counts are stored as given and clamped when they are read, so a report of 145 MB
 * transferred inside a 144 MB file cannot draw a progress bar past its end or print a
 * number the file cannot hold.
 */
public data class TransferItem(
    public val id: String,
    public val sessionId: String,
    public val direction: TransferDirection,
    public val fileName: String,
    public val kind: MediaKind,
    public val totalBytes: Long,
    public val transferredBytes: Long,
    public val speedBytesPerSecond: Long = 0L,
    public val state: TransferState = TransferState.QUEUED,
    public val verification: VerificationOutcome = VerificationOutcome.PENDING,
    public val pauseEligible: Boolean = true,
    public val cancelEligible: Boolean = true,
    public val retryEligible: Boolean = true,
) {
    /** Bytes safely inside the file: never negative, never more than it holds. */
    public val transferred: Long
        get() = transferredBytes.coerceIn(0L, totalBytes.coerceAtLeast(0L))

    /** Where the progress bar sits, with a zero-byte file handled rather than divided by. */
    public val fraction: Float
        get() = TransferProgress.fraction(transferredBytes, totalBytes, state)

    /** What this row may offer right now. */
    public val actions: TransferActions get() = TransferActions.of(this)

    /** The sentence this row's second line is built from, before it is written in words. */
    public val meta: TransferMeta get() = TransferMeta.of(this)
}

/**
 * What a row may offer, in one place.
 *
 * A pure function of the item rather than of the screen: the per-file matrix in the
 * master prompt is a statement about a file's state, and both duplex views — and
 * everything after them — have to reach the same answer for the same row.
 */
public data class TransferActions(
    public val pause: Boolean,
    public val resume: Boolean,
    public val cancel: Boolean,
    public val retry: Boolean,
) {
    /** True when the row carries any control at all. */
    public val any: Boolean get() = pause || resume || cancel || retry

    public companion object {
        /** No controls: completed, cancelled, skipped and verifying rows all offer none. */
        public val NONE: TransferActions = TransferActions(pause = false, resume = false, cancel = false, retry = false)

        public fun of(item: TransferItem): TransferActions =
            of(item.state, item.pauseEligible, item.cancelEligible, item.retryEligible)

        /**
         * The same matrix, for anything that is a transfer without being a [TransferItem].
         *
         * A broadcast delivery to one phone is exactly that: it moves, holds, fails and
         * verifies like any other file, and it has to reach the same answer about what it may
         * offer. Rather than a second matrix in the broadcast package — which is how two
         * screens come to disagree about whether a paused file can be cancelled — the delivery
         * asks this.
         */
        public fun of(
            state: TransferState,
            pauseEligible: Boolean = true,
            cancelEligible: Boolean = true,
            retryEligible: Boolean = true,
        ): TransferActions = TransferActions(
            pause = state.isPausable && pauseEligible,
            resume = state.isResumable,
            cancel = state.isCancellable && cancelEligible,
            retry = state.isRetryable && retryEligible,
        )
    }
}

/**
 * The row's second line, as facts rather than as a sentence.
 *
 * The words belong to resources — this app is built for locales it has not been translated
 * into yet (§11) — so the state decides *which facts* a row states and the screen decides
 * how to say them. That split is also what makes the matrix testable without a screen: a
 * paused row stating its resume offset is an assertion about [TransferMeta], not about a
 * string.
 */
public sealed interface TransferMeta {
    /** Moving: bytes so far, the whole file, and how fast. */
    public data class Transferring(
        public val transferredBytes: Long,
        public val totalBytes: Long,
        public val speedBytesPerSecond: Long,
    ) : TransferMeta

    /** Waiting its turn: only the size is known. */
    public data class Waiting(public val totalBytes: Long) : TransferMeta

    /** Held: bytes so far, the whole file, and the offset it would resume from. */
    public data class Held(
        public val transferredBytes: Long,
        public val totalBytes: Long,
    ) : TransferMeta

    /** Everything arrived; the checksum is being compared. */
    public data class Verifying(public val totalBytes: Long) : TransferMeta

    /** Arrived, with the checksum's answer. */
    public data class Delivered(
        public val totalBytes: Long,
        public val verification: VerificationOutcome,
    ) : TransferMeta

    /** Stopped by something other than the user. */
    public data class Broken(
        public val transferredBytes: Long,
        public val totalBytes: Long,
    ) : TransferMeta

    /** Stopped because the user said so. */
    public data class Abandoned(
        public val transferredBytes: Long,
        public val totalBytes: Long,
    ) : TransferMeta

    /** Deliberately not transferred. */
    public data class Passed(public val totalBytes: Long) : TransferMeta

    public companion object {
        /**
         * The facts a row states, by state.
         *
         * A failed row states where it got to and how big the file is. The reference would
         * print a speed there too, because its sample rows always carry one; here a stopped
         * row is stopped, and printing "0.0 MB/s" beside it would be the row saying it is
         * moving at no miles per hour rather than saying it stopped.
         */
        public fun of(item: TransferItem): TransferMeta = when (item.state) {
            TransferState.QUEUED -> Waiting(item.totalBytes)
            TransferState.SENDING,
            TransferState.RECEIVING,
            -> Transferring(item.transferred, item.totalBytes, item.speedBytesPerSecond)

            TransferState.PAUSED -> Held(item.transferred, item.totalBytes)
            TransferState.VERIFYING -> Verifying(item.totalBytes)
            TransferState.DONE -> Delivered(item.totalBytes, item.verification)
            TransferState.FAILED -> Broken(item.transferred, item.totalBytes)
            TransferState.CANCELLED -> Abandoned(item.transferred, item.totalBytes)
            TransferState.SKIPPED -> Passed(item.totalBytes)
        }
    }
}

/**
 * A duplex session: one peer, and the files moving each way with them.
 *
 * This is the whole session and the only place it lives. Neither duplex view owns a list
 * of its own — the two views are the same session read in two orders — so a row paused in
 * one is paused in the other, and clearing completed files in a section cannot reach the
 * section next to it because sections are addressed by direction and nothing else.
 *
 * The lists are immutable and every transition returns a new session, which is what lets a
 * screen be handed one and draw it without a lock, and lets a test ask what the session
 * looks like after an action rather than what the screen looked like mid-recomposition.
 */
public data class TransferSession(
    public val id: String,
    public val peer: TransferPeer,
    public val outbound: List<TransferItem> = emptyList(),
    public val inbound: List<TransferItem> = emptyList(),
) {
    /** The rows going this way, in order. */
    public fun items(direction: TransferDirection): List<TransferItem> =
        if (direction == TransferDirection.OUTGOING) outbound else inbound

    /** The same session with one direction's rows replaced. */
    public fun withItems(direction: TransferDirection, items: List<TransferItem>): TransferSession =
        if (direction == TransferDirection.OUTGOING) copy(outbound = items) else copy(inbound = items)

    /** Every row, both ways. */
    public val allItems: List<TransferItem> get() = outbound + inbound

    /** One row by id, whichever way it is going. */
    public fun item(id: String): TransferItem? = allItems.firstOrNull { it.id == id }

    /** The same session with [item] replacing the row of that id, if it is in the session. */
    public fun withItem(item: TransferItem): TransferSession {
        val direction = item.direction
        return withItems(direction, items(direction).map { if (it.id == item.id) item else it })
    }

    /** The summary for one direction, computed from that direction's rows alone. */
    public fun summary(direction: TransferDirection): TransferSummary =
        TransferSummary.of(direction, items(direction))
}

/**
 * What the progress bar draws.
 *
 * Pulled out of the row because it is the one piece of arithmetic here that a test can get
 * wrong in a way nobody notices: a file whose reported bytes exceed its size must draw a
 * full bar rather than one past the end, and a zero-byte file must not divide by zero. A
 * zero-byte file that is done is full — there was nothing to carry and it all arrived —
 * while one still running is empty.
 */
public object TransferProgress {
    public fun fraction(transferredBytes: Long, totalBytes: Long, state: TransferState): Float {
        if (totalBytes <= 0L) return if (state == TransferState.DONE) 1f else 0f
        return (transferredBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * The batch line under a section: how many files are in each state, and how fast they are
 * moving on average.
 *
 * [complete] is the reference's own idea of a finished batch — everything delivered — with
 * one deliberate correction: it also requires that nothing is still running, waiting, held
 * or being verified. The reference only asks whether every row is `done`, which leaves a
 * batch that finished with a failure claiming to be in progress for ever.
 *
 * [averageSpeedBytesPerSecond] is zero when nothing is moving, and the screen omits the
 * average rather than printing a number no row is producing. The reference prints its own
 * sample average in that case, which is a fact about its fixtures rather than about a
 * transfer.
 */
public data class TransferSummary(
    public val direction: TransferDirection,
    public val total: Int,
    public val done: Int,
    public val active: Int,
    public val queued: Int,
    public val paused: Int,
    public val verifying: Int,
    public val failed: Int,
    public val cancelled: Int,
    public val skipped: Int,
    public val averageSpeedBytesPerSecond: Long,
) {
    public val complete: Boolean
        get() = total > 0 &&
            done > 0 &&
            active == 0 &&
            queued == 0 &&
            paused == 0 &&
            verifying == 0

    /** True when there is a speed worth printing. */
    public val hasAverageSpeed: Boolean get() = averageSpeedBytesPerSecond > 0L

    public companion object {
        public fun of(direction: TransferDirection, items: List<TransferItem>): TransferSummary {
            fun count(state: TransferState): Int = items.count { it.state == state }
            val moving = items.filter { it.state.isActive && it.speedBytesPerSecond > 0L }
            return TransferSummary(
                direction = direction,
                total = items.size,
                done = count(TransferState.DONE),
                active = items.count { it.state.isActive },
                queued = count(TransferState.QUEUED),
                paused = count(TransferState.PAUSED),
                verifying = count(TransferState.VERIFYING),
                failed = count(TransferState.FAILED),
                cancelled = count(TransferState.CANCELLED),
                skipped = count(TransferState.SKIPPED),
                averageSpeedBytesPerSecond = if (moving.isEmpty()) {
                    0L
                } else {
                    moving.sumOf { it.speedBytesPerSecond } / moving.size
                },
            )
        }
    }
}

/** What a per-file control asks the session to do. */
public enum class TransferRowAction {
    PAUSE,
    RESUME,
    CANCEL,
    RETRY,
}

/**
 * Every transition the duplex views can perform, as pure functions.
 *
 * Nothing here is a timer, a coroutine or a service: each function answers what the session
 * looks like after a user does one thing, which is what makes the whole matrix testable
 * without a transfer engine and what the engine will call into when it exists. Anything a
 * state or an eligibility flag does not permit returns the session unchanged, so a screen
 * that offers a control the model forbids cannot quietly change state anyway.
 *
 * Two rules are worth stating outright, because they are choices rather than mechanics:
 *
 * - Resume returns a row to the state that matches its direction, so a paused incoming file
 *   resumes receiving rather than sending.
 * - Retry restarts from the beginning and clears the checksum answer, because that is what
 *   the reference does (`it.sent=0`) and because retrying a whole file is a different
 *   action from resuming a held one at its offset. Outbound files go back to the queue;
 *   inbound ones start arriving again, since nothing on this side has to ask.
 */
public object TransferRules {

    /** Hold a moving row where it is. */
    public fun pause(item: TransferItem): TransferItem =
        if (item.state.isPausable && item.pauseEligible) {
            item.copy(state = TransferState.PAUSED, speedBytesPerSecond = 0L)
        } else {
            item
        }

    /** Set a held row going again, in its own direction. */
    public fun resume(item: TransferItem): TransferItem =
        if (item.state.isResumable) {
            item.copy(state = item.direction.activeState)
        } else {
            item
        }

    /** Stop a row the user no longer wants, keeping where it got to. */
    public fun cancel(item: TransferItem): TransferItem =
        if (item.state.isCancellable && item.cancelEligible) {
            item.copy(state = TransferState.CANCELLED, speedBytesPerSecond = 0L)
        } else {
            item
        }

    /** Send a failed row again, from the beginning. */
    public fun retry(item: TransferItem): TransferItem =
        if (item.state.isRetryable && item.retryEligible) {
            item.copy(
                state = if (item.direction == TransferDirection.OUTGOING) {
                    TransferState.QUEUED
                } else {
                    TransferState.RECEIVING
                },
                transferredBytes = 0L,
                speedBytesPerSecond = 0L,
                verification = VerificationOutcome.PENDING,
            )
        } else {
            item
        }

    /** One row's answer to one control. */
    public fun apply(item: TransferItem, action: TransferRowAction): TransferItem = when (action) {
        TransferRowAction.PAUSE -> pause(item)
        TransferRowAction.RESUME -> resume(item)
        TransferRowAction.CANCEL -> cancel(item)
        TransferRowAction.RETRY -> retry(item)
    }

    /** The same session with one row's control applied. */
    public fun apply(session: TransferSession, id: String, action: TransferRowAction): TransferSession {
        val item = session.item(id) ?: return session
        return session.withItem(apply(item, action))
    }

    /** All of the session's rows, both directions, with [transition] applied. */
    private fun overSession(
        session: TransferSession,
        transition: (TransferItem) -> TransferItem,
    ): TransferSession = TransferSession(
        id = session.id,
        peer = session.peer,
        outbound = session.outbound.map(transition),
        inbound = session.inbound.map(transition),
    )

    /**
     * Pause everything that can be paused, in both directions of this session.
     *
     * Both directions, because a session is one session: the reference's own action bar
     * toggle walks its whole list, and a "Pause all" that left half the session running
     * would be the one control on the screen that does less than it says.
     */
    public fun pauseAll(session: TransferSession): TransferSession = overSession(session, ::pause)

    /** Resume everything that is held, in both directions. */
    public fun resumeAll(session: TransferSession): TransferSession = overSession(session, ::resume)

    /**
     * Remove the completed rows of one direction, and only that direction.
     *
     * Rows that are not [TransferState.DONE] stay: a cancelled or failed file is something
     * the user may still want to act on, and clearing completed work is not the same
     * instruction as clearing everything that has stopped.
     */
    public fun clearCompleted(session: TransferSession, direction: TransferDirection): TransferSession =
        session.withItems(direction, session.items(direction).filterNot { it.state.isComplete })

    /**
     * Close the session: whatever is still the engine's business becomes cancelled.
     *
     * Files already delivered, skipped by policy and failed on their own terms are left as
     * they are — the first two because nothing about them is unfinished, and a failure
     * because it happened before the user ended anything and telling them it was cancelled
     * would be blaming them for it.
     */
    public fun end(session: TransferSession): TransferSession = overSession(session, ::end)

    /**
     * What ending a session does to one row, on its own.
     *
     * Everything unfinished stops, including a file that is being verified: the question the
     * user answered was about the whole session, and a checksum comparison outliving it would
     * make that question's answer untrue. The per-row eligibility flags are deliberately not
     * consulted — they are the *engine's* terms for a row it is still working on, and once the
     * session is over there is no engine left to ask. A row that already arrived, was skipped,
     * or failed on its own terms is left exactly as it is.
     *
     * It is a function of one row as well as of a session because a broadcast ends a delivery
     * with the same rule, and a second copy of "unfinished becomes cancelled" is a second
     * answer waiting to disagree with this one.
     */
    public fun end(item: TransferItem): TransferItem =
        if (item.state.isUnfinished) {
            item.copy(state = TransferState.CANCELLED, speedBytesPerSecond = 0L)
        } else {
            item
        }
}
