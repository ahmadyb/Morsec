package app.morsecode.ui.transfer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import app.morsecode.core.model.MorseFormatters
import app.morsecode.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Which of the two whole-session controls the action bar is offering. */
public enum class TransferAllLabel {
    PAUSE_ALL,
    RESUME_ALL,
}

/**
 * One row as the views draw it: the item, and the numbers it prints.
 *
 * The numbers are formatted here rather than in the row because formatting is the view
 * model's business in this app — it holds the locale-aware [MorseFormatters] — while the
 * words around them belong to resources, which is why this carries "48.9 MB" and the row
 * decides that a queued file says "· waiting" after it. It also means the arithmetic a row
 * shows can be asserted without a screen: that the transferred figure never exceeds the
 * file, and that the percentage beside the bar agrees with the bar.
 */
public data class TransferSummaryLine(
    public val direction: TransferDirection,
    public val summary: TransferSummary,
    /** "6.2 MB/s", formatted the way every other speed on the screen is. */
    public val averageSpeed: String,
)

public data class TransferRow(
    public val item: TransferItem,
    /** "48.9 MB". */
    public val transferred: String,
    /** "144 MB". */
    public val total: String,
    /** "6.2 MB/s", or "0 B/s" for a row that is not moving. */
    public val speed: String,
    /** "34%" — the same fraction the progress bar draws. */
    public val percent: String,
)

/**
 * What the one whole-session control would do, and whether it can.
 *
 * [enabled] is false when there is nothing for the label to act on — a session of nothing
 * but delivered files has nothing to pause — and the button says so by being disabled
 * rather than by disappearing, which would move the other three actions around under the
 * user's thumb.
 */
public data class TransferAllAction(
    public val label: TransferAllLabel,
    public val enabled: Boolean,
)

/**
 * Everything both duplex views draw, derived from the session.
 *
 * The rows are handed over as they are, in their directions, with their summaries already
 * counted: a screen here formats and draws, it does not decide. That is what keeps the two
 * views the same session read in two orders, and what lets a stateless screen be handed a
 * hand-written session in a test.
 */
public data class TransferUiState(
    public val layout: TransferLayout,
    public val sessionId: String,
    public val peer: TransferPeer,
    public val outbound: List<TransferRow>,
    public val inbound: List<TransferRow>,
    public val summaryOutbound: TransferSummary,
    public val summaryInbound: TransferSummary,
    /** The batch lines this layout's card prints, in its order. */
    public val summaryLines: List<TransferSummaryLine>,
    public val allAction: TransferAllAction,
    /** True while the End confirmation is on screen. */
    public val endConfirmationVisible: Boolean = false,
    /** How many files are still the engine's business, for the confirmation's wording. */
    public val unfinishedCount: Int = 0,
) {
    /** The rows going this way. */
    public fun items(direction: TransferDirection): List<TransferRow> =
        if (direction == TransferDirection.OUTGOING) outbound else inbound

    /** The batch line for this way. */
    public fun summary(direction: TransferDirection): TransferSummary =
        if (direction == TransferDirection.OUTGOING) summaryOutbound else summaryInbound
}

/**
 * The duplex session, as an explicit UI-state controller (master prompt §5 "Transfers").
 *
 * One view model for both approved views, because they are one session: which section leads
 * comes from the route's layout token, and every transition below acts on the session rather
 * than on a screen. Nothing here talks to a socket, a service or a clock — the engine arrives
 * in milestones 5 to 9, and until then the session is the fixture and the transitions are
 * the ones the user can see.
 *
 * The End confirmation lives here rather than in the screen so that its rule is testable
 * without one: asking to end opens the question, dismissing it changes nothing at all, and
 * confirming is the only thing that touches the session.
 */
@HiltViewModel
public class TransferViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val formatters: MorseFormatters,
) : ViewModel() {

    private val layout: TransferLayout =
        TransferLayout.fromId(savedStateHandle.get<String>(Routes.TRANSFER_ARG))

    private val session = MutableStateFlow(TransferFixtures.session(layout))

    private val endConfirmationVisible = MutableStateFlow(false)

    private val _state = MutableStateFlow(publish(session.value))

    /** What both views draw. */
    public val state: StateFlow<TransferUiState> = _state.asStateFlow()

    /** Pause one row, if its state and its eligibility allow it. */
    public fun pause(id: String) = row(id, TransferRowAction.PAUSE)

    /** Set one held row going again. */
    public fun resume(id: String) = row(id, TransferRowAction.RESUME)

    /** Stop one row the user no longer wants. */
    public fun cancel(id: String) = row(id, TransferRowAction.CANCEL)

    /** Send one failed row again, from the beginning. */
    public fun retry(id: String) = row(id, TransferRowAction.RETRY)

    /**
     * The action bar's single whole-session control.
     *
     * It pauses when the session is running and resumes when it is held, which is the
     * reference's own toggle (`S.tx.paused=!S.tx.paused`). Which of the two it currently is
     * comes from [TransferUiState.allAction], so the label and the action cannot disagree.
     */
    public fun toggleAll() {
        mutate { current ->
            if (current.allAction.label == TransferAllLabel.RESUME_ALL) {
                TransferRules.resumeAll(current)
            } else {
                TransferRules.pauseAll(current)
            }
        }
    }

    /**
     * Clear the completed rows of one section.
     *
     * The direction is the section, so this cannot reach the other one: clearing what has
     * been sent leaves what has been received exactly where it was.
     */
    public fun clearCompleted(direction: TransferDirection) {
        mutate { current -> TransferRules.clearCompleted(current, direction) }
    }

    /** Ask to end the session. Nothing changes until the question is answered. */
    public fun requestEnd() {
        endConfirmationVisible.value = true
        publishCurrent()
    }

    /** Keep transferring: the question closes and the session is untouched. */
    public fun dismissEnd() {
        endConfirmationVisible.value = false
        publishCurrent()
    }

    /**
     * End the session.
     *
     * Whatever was still moving, waiting or being verified becomes cancelled and keeps the
     * bytes it reached; delivered, skipped and failed rows are left as they are, because
     * ending a session does not undo a delivery and does not turn somebody else's failure
     * into the user's cancellation.
     */
    public fun confirmEnd() {
        endConfirmationVisible.value = false
        mutate { current -> TransferRules.end(current) }
    }

    private fun row(id: String, action: TransferRowAction) {
        mutate { current -> TransferRules.apply(current, id, action) }
    }

    /** Applies one transition to the session and re-publishes what the views draw. */
    private fun mutate(transition: (TransferSession) -> TransferSession) {
        session.update(transition)
        publishCurrent()
    }

    private fun publishCurrent() {
        _state.value = publish(session.value)
    }

    /** The session as the views read it, decided by [transferUiStateTo] and nothing else. */
    private fun publish(session: TransferSession): TransferUiState =
        transferUiStateTo(session, layout, formatters, endConfirmationVisible.value)
}

/**
 * The session as the views read it.
 *
 * A function rather than a private method because it is the rule the screens depend on — which
 * batch line is offered, what the single whole-session control would do, how many files are
 * unfinished — and a rule that can only be reached through a view model can only be tested
 * through one. Everything decided here is decided once: neither duplex view counts a state for
 * itself.
 */
internal fun transferUiStateTo(
    session: TransferSession,
    layout: TransferLayout,
    formatters: MorseFormatters,
    endConfirmationVisible: Boolean = false,
): TransferUiState {
    val paused = session.allItems.count { it.state == TransferState.PAUSED }
    val pausable = session.allItems.count { it.state.isPausable && it.pauseEligible }
    // Resume is offered when something is held and nothing else is running, which is the same
    // condition the reference's single batch flag expresses.
    val resumeInstead = paused > 0 && session.allItems.none { it.state.isActive }
    return TransferUiState(
        layout = layout,
        sessionId = session.id,
        peer = session.peer,
        outbound = session.outbound.map { rowTo(it, formatters) },
        inbound = session.inbound.map { rowTo(it, formatters) },
        summaryOutbound = session.summary(TransferDirection.OUTGOING),
        summaryInbound = session.summary(TransferDirection.INCOMING),
        summaryLines = layout.summaryDirections.map { direction ->
            val summary = session.summary(direction)
            TransferSummaryLine(
                direction = direction,
                summary = summary,
                averageSpeed = formatters.speed(summary.averageSpeedBytesPerSecond),
            )
        },
        allAction = TransferAllAction(
            label = if (resumeInstead) TransferAllLabel.RESUME_ALL else TransferAllLabel.PAUSE_ALL,
            enabled = if (resumeInstead) paused > 0 else pausable > 0,
        ),
        endConfirmationVisible = endConfirmationVisible,
        unfinishedCount = session.allItems.count { it.state.isUnfinished },
    )
}

/** One item with the four numbers its row prints. */
internal fun rowTo(item: TransferItem, formatters: MorseFormatters): TransferRow = TransferRow(
    item = item,
    transferred = formatters.bytes(item.transferred),
    total = formatters.bytes(item.totalBytes),
    speed = formatters.speed(item.speedBytesPerSecond),
    percent = formatters.percent(item.fraction),
)
