package app.morsecode.ui.transfer

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseWash
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseActionBar
import app.morsecode.ui.common.MorseActionBarItem
import app.morsecode.ui.common.MorseActionBarTone
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.rememberFeatureGate

/**
 * The duplex transfer screens (master prompt §5 "Transfers").
 *
 * Two approved views of one session — "Sending + receiving" and "Receiving + sending back" —
 * so there is one screen here and two layouts of it, which is what keeps them symmetrical on
 * the day one of them changes. Which view is drawn comes from the route's layout token, and
 * the whole of the difference is which section leads and where the batch card falls.
 *
 * What is here and what is not:
 *
 * - Every per-file control is the master prompt's matrix: pause and cancel while moving,
 *   resume and cancel while held, cancel alone while queued, retry alone after a failure, and
 *   nothing at all on a file that is done, cancelled, skipped or being verified. The row asks
 *   [TransferItem.actions] rather than reading the state itself, so this screen and any later
 *   one cannot disagree about what a paused file may offer.
 * - The one whole-session control is in the bottom bar and nowhere else. Each section heading
 *   carries a single Clear completed action, and it reaches its own direction only.
 * - Ending a session is a question first: the dialog says how many files are unfinished and
 *   what happens to them, and dismissing it changes nothing.
 * - Nothing advances on a clock. The rows are [TransferFixtures] until the engine lands in
 *   milestone 5, and the controls here move only state the user moved themselves. Add files
 *   and Background are engine and service work, so they say so through the app's own gate
 *   rather than pretending: there is no socket to open and no foreground service to start.
 */
@Composable
public fun TransferScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onEnded: () -> Unit,
    viewModel: TransferViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gate = rememberFeatureGate()

    // Composed here rather than inside the click lambdas, which must not read resources
    // through LocalContext.
    val clearedDone = stringResource(R.string.transfer_cleared_done)
    val clearedNone = stringResource(R.string.transfer_cleared_none)

    DuplexTransferScreen(
        state = state,
        onBack = onBack,
        onNavigate = onNavigate,
        onPause = { viewModel.pause(it.item.id) },
        onResume = { viewModel.resume(it.item.id) },
        onCancel = { viewModel.cancel(it.item.id) },
        onRetry = { viewModel.retry(it.item.id) },
        onToggleAll = viewModel::toggleAll,
        onClearCompleted = { direction ->
            // Said out loud, because a section with nothing completed to clear would
            // otherwise look like a control that does nothing at all.
            val before = state.items(direction).count { it.item.state.isComplete }
            viewModel.clearCompleted(direction)
            Toast.makeText(
                context,
                if (before > 0) clearedDone else clearedNone,
                Toast.LENGTH_SHORT,
            ).show()
        },
        onAddFiles = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
        onBackground = { gate.run(FeatureArea.BACKGROUND_SERVICE) { } },
        onRequestEnd = viewModel::requestEnd,
        onConfirmEnd = {
            viewModel.confirmEnd()
            onEnded()
        },
        onDismissEnd = viewModel::dismissEnd,
    )
}

/**
 * The screen itself, as a function of the state it is handed.
 *
 * Stateless on purpose: it draws whatever session it is given and reports what the user
 * asked for, which is what lets a test hand it one row in one state — the whole per-file
 * matrix, without a fixture or a scrolling list in the way.
 */
@Composable
internal fun DuplexTransferScreen(
    state: TransferUiState,
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onPause: (TransferRow) -> Unit,
    onResume: (TransferRow) -> Unit,
    onCancel: (TransferRow) -> Unit,
    onRetry: (TransferRow) -> Unit,
    onToggleAll: () -> Unit,
    onClearCompleted: (TransferDirection) -> Unit,
    onAddFiles: () -> Unit,
    onBackground: () -> Unit,
    onRequestEnd: () -> Unit,
    onConfirmEnd: () -> Unit,
    onDismissEnd: () -> Unit,
) {
    val spec = transferSpecFor(state.layout)
    val title = stringResource(spec.titleRes)
    val backDescription = stringResource(R.string.action_back)

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        TransferScreenHeader(title = title, backDescription = backDescription, onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 10.dp),
        ) {
            item(key = "peer") {
                TransferPeerCard(
                    peer = state.peer,
                    subtitle = stringResource(R.string.transfer_peer_connected, state.peer.transport),
                    badge = state.peer.transport,
                )
            }

            spec.sections.forEachIndexed { index, section ->
                val rows = state.items(section.direction)

                item(key = "section-${section.direction.id}") {
                    // Read inside the item rather than above it: this lambda composes and the
                    // loop around it does not, so a resource read belongs in here.
                    val sectionLabel = stringResource(section.labelRes)
                    SectionHeader(
                        text = pluralStringResource(
                            R.plurals.transfer_section_files,
                            rows.size,
                            sectionLabel,
                            rows.size,
                        ),
                        actionIconRes = MorseIcons.trash,
                        actionIconDescription = stringResource(R.string.transfer_clear_completed),
                        onActionIcon = { onClearCompleted(section.direction) },
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )
                }

                if (rows.isEmpty()) {
                    item(key = "empty-${section.direction.id}") {
                        MorseEmptyState(
                            title = stringResource(section.emptyTitleRes),
                            message = stringResource(section.emptyBodyRes),
                            iconRes = if (section.direction == TransferDirection.OUTGOING) {
                                MorseIcons.upload
                            } else {
                                MorseIcons.down
                            },
                        )
                    }
                } else {
                    items(rows, key = { it.item.id }) { row ->
                        TransferRowView(
                            row = row,
                            onPause = { onPause(row) },
                            onResume = { onResume(row) },
                            onCancel = { onCancel(row) },
                            onRetry = { onRetry(row) },
                        )
                    }
                }

                if (index == spec.summaryAfter) {
                    item(key = "summary") { BatchSummaryCard(lines = state.summaryLines) }
                }
            }
        }

        MorseActionBar(
            items = listOf(
                MorseActionBarItem(
                    id = "add",
                    label = stringResource(R.string.transfer_add_files),
                    iconRes = MorseIcons.plus,
                    tone = MorseActionBarTone.PRIMARY,
                    onClick = onAddFiles,
                ),
                MorseActionBarItem(
                    id = "all",
                    label = when (state.allAction.label) {
                        TransferAllLabel.PAUSE_ALL -> stringResource(R.string.transfer_pause_all)
                        TransferAllLabel.RESUME_ALL -> stringResource(R.string.transfer_resume_all)
                    },
                    iconRes = when (state.allAction.label) {
                        TransferAllLabel.PAUSE_ALL -> MorseIcons.pause
                        TransferAllLabel.RESUME_ALL -> MorseIcons.play
                    },
                    enabled = state.allAction.enabled,
                    onClick = onToggleAll,
                ),
                MorseActionBarItem(
                    id = "background",
                    label = stringResource(R.string.transfer_background),
                    iconRes = MorseIcons.minus,
                    onClick = onBackground,
                ),
                MorseActionBarItem(
                    id = "end",
                    label = stringResource(R.string.transfer_end),
                    iconRes = MorseIcons.close,
                    tone = MorseActionBarTone.DANGER,
                    onClick = onRequestEnd,
                ),
            ),
        )
    }

    if (state.endConfirmationVisible) {
        EndSessionDialog(
            unfinishedCount = state.unfinishedCount,
            onConfirm = onConfirmEnd,
            onDismiss = onDismissEnd,
        )
    }
}

/**
 * The batch card: whether the batch is finished, how its files are split between states, and
 * how fast they are moving on average.
 *
 * Counts of nothing are not printed while a batch is running — "0 verifying" is not news —
 * but they are on a finished batch, because "0 failed · 0 skipped" is exactly what a user
 * wants to read there. The average is printed only when a row is actually moving: the
 * reference prints its own sample figure in that case, which would be this card stating a
 * speed nothing is producing.
 */
@Composable
private fun BatchSummaryCard(lines: List<TransferSummaryLine>) {
    val colors = MorseTheme.colors
    MorseWash(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lines.forEachIndexed { index, line ->
                val summary = line.summary
                Text(
                    text = stringResource(
                        if (summary.complete) {
                            R.string.transfer_summary_complete
                        } else {
                            R.string.transfer_summary_in_progress
                        },
                    ),
                    style = MorseTextStyles.listTitle,
                    color = if (summary.complete) colors.ok else colors.textPrimary,
                )
                Text(
                    text = summaryCounts(summary),
                    style = MorseTextStyles.meta,
                    color = colors.textTertiary,
                )
                if (summary.hasAverageSpeed) {
                    Text(
                        text = stringResource(R.string.transfer_summary_average, line.averageSpeed),
                        style = MorseTextStyles.meta,
                        color = colors.textTertiary,
                    )
                }
                if (index != lines.lastIndex) Spacer(modifier = Modifier.size(6.dp))
            }
        }
    }
}

/**
 * The counts a batch line prints, joined the way the reference joins them.
 *
 * The direction decides the words: a batch going out counts what it "sent" and one coming in
 * counts what it "received", including while it runs, so the same session read from either
 * end reads correctly.
 */
@Composable
private fun summaryCounts(summary: TransferSummary): String {
    val pieces = buildList {
        if (summary.complete) {
            add(
                pluralStringResource(
                    if (summary.direction == TransferDirection.OUTGOING) {
                        R.plurals.transfer_summary_sent
                    } else {
                        R.plurals.transfer_summary_received
                    },
                    summary.done,
                    summary.done,
                ),
            )
        } else {
            if (summary.active > 0) {
                add(
                    pluralStringResource(
                        if (summary.direction == TransferDirection.OUTGOING) {
                            R.plurals.transfer_summary_sending
                        } else {
                            R.plurals.transfer_summary_receiving
                        },
                        summary.active,
                        summary.active,
                    ),
                )
            }
            if (summary.queued > 0) {
                add(pluralStringResource(R.plurals.transfer_summary_queued, summary.queued, summary.queued))
            }
            if (summary.paused > 0) {
                add(pluralStringResource(R.plurals.transfer_summary_paused, summary.paused, summary.paused))
            }
            if (summary.verifying > 0) {
                add(
                    pluralStringResource(
                        R.plurals.transfer_summary_verifying,
                        summary.verifying,
                        summary.verifying,
                    ),
                )
            }
        }
        if (summary.failed > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_failed, summary.failed, summary.failed))
        }
        if (summary.cancelled > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_cancelled, summary.cancelled, summary.cancelled))
        }
        if (summary.skipped > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_skipped, summary.skipped, summary.skipped))
        }
    }
    return pieces.joinToString(CountSeparator)
}


internal data class TransferSectionSpec(
    val direction: TransferDirection,
    @StringRes val labelRes: Int,
    @StringRes val emptyTitleRes: Int,
    @StringRes val emptyBodyRes: Int,
)

/**
 * How a layout is drawn: its title, its sections in order, and where the batch card falls.
 *
 * This is the whole of the difference between the two approved views, which is why it is data
 * rather than two composables. The directions the card reports on come from the layout itself,
 * so the card and the summaries it prints are ordered from one source.
 */
internal data class TransferScreenSpec(
    @StringRes val titleRes: Int,
    val sections: List<TransferSectionSpec>,
    /** The index of the section the batch card follows. */
    val summaryAfter: Int,
)

internal fun transferSpecFor(layout: TransferLayout): TransferScreenSpec = when (layout) {
    TransferLayout.SENDING_FIRST -> TransferScreenSpec(
        titleRes = R.string.transfer_title_sending,
        sections = listOf(
            TransferSectionSpec(
                direction = TransferDirection.OUTGOING,
                labelRes = R.string.transfer_section_sending,
                emptyTitleRes = R.string.transfer_empty_sending_title,
                emptyBodyRes = R.string.transfer_empty_sending_body,
            ),
            TransferSectionSpec(
                direction = TransferDirection.INCOMING,
                labelRes = R.string.transfer_section_receiving,
                emptyTitleRes = R.string.transfer_empty_receiving_title,
                emptyBodyRes = R.string.transfer_empty_receiving_body,
            ),
        ),
        summaryAfter = 0,
    )

    TransferLayout.RECEIVING_FIRST -> TransferScreenSpec(
        titleRes = R.string.transfer_title_receiving,
        sections = listOf(
            TransferSectionSpec(
                direction = TransferDirection.INCOMING,
                labelRes = R.string.transfer_section_receiving,
                emptyTitleRes = R.string.transfer_empty_receiving_title,
                emptyBodyRes = R.string.transfer_empty_receiving_body,
            ),
            TransferSectionSpec(
                direction = TransferDirection.OUTGOING,
                labelRes = R.string.transfer_section_sending_back,
                emptyTitleRes = R.string.transfer_empty_sending_title,
                emptyBodyRes = R.string.transfer_empty_sending_body,
            ),
        ),
        summaryAfter = 1,
    )
}

/**
 * What separates one item from the next in a line.
 *
 * A middot, as the reference prints it, and a constant rather than a resource because it is
 * punctuation rather than a sentence.
 */
private const val CountSeparator = " · "
