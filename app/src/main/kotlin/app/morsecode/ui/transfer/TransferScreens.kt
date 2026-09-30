package app.morsecode.ui.transfer

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.ChipStyle
import app.morsecode.core.design.component.FileKindIcon
import app.morsecode.core.design.component.MorseBodyText
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseChip
import app.morsecode.core.design.component.MorseDialog
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseWash
import app.morsecode.core.design.component.PeerAvatar
import app.morsecode.core.design.component.ProgressFlavour
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.component.TransferProgressBar
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.design.tokens.MorseMetrics
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
        TransferHeader(title = title, backDescription = backDescription, onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 10.dp),
        ) {
            item(key = "peer") { PeerCard(state.peer) }

            spec.sections.forEachIndexed { index, section ->
                val rows = state.items(section.direction)
                val sectionLabel = stringResource(section.labelRes)

                item(key = "section-${section.direction.id}") {
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

/** The reference's `.hd` row: the way back, and which of the two views this is. */
@Composable
private fun TransferHeader(
    title: String,
    backDescription: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MorseIconButton(
            iconRes = MorseIcons.back,
            contentDescription = backDescription,
            onClick = onBack,
        )
        Text(
            text = title,
            style = MorseTextStyles.screenTitle,
            color = MorseTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The connected peer: avatar, name, what the connection is, and its transport label.
 *
 * The subtitle is composed from the transport rather than stored, so this card, the Connect
 * screen and the gate copy all say "LAN" or "Nearby" the same way.
 */
@Composable
private fun PeerCard(peer: TransferPeer) {
    val colors = MorseTheme.colors
    MorseWash(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphGap),
        ) {
            PeerAvatar(letter = peer.letter.first(), colorSeed = peer.id, large = true)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.transfer_peer_connected, peer.transport),
                    style = MorseTextStyles.meta,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MorseChip(text = peer.transport, style = ChipStyle.OUTLINE)
        }
    }
}

/**
 * One file, with the controls its state permits and no others.
 *
 * The direction arrow is drawn when the row has room for it. The reference draws one on every
 * transfer row, and this app reports a 48 dp touch box for each icon button, so at the narrow
 * end of the phone range two leading glyphs, a status chip and up to two controls cannot all
 * fit beside a file name worth reading. The file-kind icon is the one that stays — it says
 * what the file *is* — and the arrow is the one that can go, because which way a file is
 * going is already stated by the section it is in and by its status chip. Nothing is dropped
 * on a 411 dp phone, and the trade is asserted at 360 dp.
 */
@Composable
private fun TransferRowView(
    row: TransferRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    val item = row.item
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val actions = item.actions

    val meta = transferMetaText(row)
    val progressDescription = stringResource(
        R.string.transfer_progress_description,
        row.transferred,
        row.total,
        row.percent,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val controls = if (actions.pause && actions.cancel || actions.resume && actions.cancel) 2 else 1
        val roomForDirection = maxWidth >= rowWidthNeeded(
            metrics = metrics,
            controls = controls,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = RowPaddingVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphGap),
        ) {
            if (roomForDirection) DirectionGlyph(item)

            FileKindIcon(kind = item.kind, size = metrics.fileIconSize)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(RowInnerGap),
            ) {
                Text(
                    text = item.fileName,
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MorseTextStyles.meta,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TransferProgressBar(
                    fraction = item.fraction,
                    flavour = progressFlavourFor(item.state),
                    modifier = Modifier.semantics {
                        contentDescription = progressDescription
                        progressBarRangeInfo = ProgressBarRangeInfo(item.fraction, 0f..1f)
                    },
                )
            }

            MorseChip(text = transferChipText(item.state), style = transferChipStyle(item.state))

            if (actions.any) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ToolsGap),
                ) {
                    if (actions.pause) {
                        RowControl(
                            iconRes = MorseIcons.pause,
                            description = stringResource(R.string.transfer_pause_file, item.fileName),
                            onClick = onPause,
                        )
                    }
                    if (actions.resume) {
                        RowControl(
                            iconRes = MorseIcons.play,
                            description = stringResource(R.string.transfer_resume_file, item.fileName),
                            onClick = onResume,
                        )
                    }
                    if (actions.retry) {
                        RowControl(
                            iconRes = MorseIcons.refresh,
                            description = stringResource(R.string.transfer_retry_file, item.fileName),
                            onClick = onRetry,
                        )
                    }
                    if (actions.cancel) {
                        RowControl(
                            iconRes = MorseIcons.close,
                            description = stringResource(R.string.transfer_cancel_file, item.fileName),
                            onClick = onCancel,
                        )
                    }
                }
            }
        }
    }
}

/** One per-file control, drawn small and reported at the app's full touch size. */
@Composable
private fun RowScope.RowControl(
    iconRes: Int,
    description: String,
    onClick: () -> Unit,
) {
    val metrics = MorseTheme.metrics
    MorseIconButton(
        iconRes = iconRes,
        contentDescription = description,
        onClick = onClick,
        tint = MorseTheme.colors.textSecondary,
        size = metrics.iconButtonSizeSmall,
        glyph = metrics.iconButtonGlyphSmall,
    )
}

/** The arrow the reference draws before a file: up while sending, down while arriving. */
@Composable
private fun DirectionGlyph(item: TransferItem) {
    MorseIconButton(
        iconRes = when {
            item.state.isComplete -> MorseIcons.check
            item.direction == TransferDirection.INCOMING -> MorseIcons.down
            else -> MorseIcons.up
        },
        contentDescription = null,
        onClick = {},
        enabled = false,
        tint = MorseTheme.colors.textSecondary,
        size = MorseTheme.metrics.transferDirectionGlyph,
        glyph = MorseTheme.metrics.iconButtonGlyphSmall,
    )
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

/** The second line of a row, written from the facts its state states. */
@Composable
private fun transferMetaText(row: TransferRow): String = when (val meta = row.item.meta) {
    is TransferMeta.Transferring -> stringResource(
        R.string.transfer_meta_transferring,
        row.transferred,
        row.total,
        row.speed,
    )

    is TransferMeta.Waiting -> stringResource(R.string.transfer_meta_waiting, row.total)
    is TransferMeta.Held -> stringResource(
        R.string.transfer_meta_paused,
        row.transferred,
        row.total,
        row.transferred,
    )

    is TransferMeta.Verifying -> stringResource(R.string.transfer_meta_verifying, row.total)
    is TransferMeta.Delivered -> stringResource(
        if (meta.verification == VerificationOutcome.MISMATCH) {
            R.string.transfer_meta_mismatch
        } else {
            R.string.transfer_meta_delivered
        },
        row.total,
    )

    is TransferMeta.Broken -> stringResource(R.string.transfer_meta_failed, row.transferred, row.total)
    is TransferMeta.Abandoned -> stringResource(R.string.transfer_meta_cancelled, row.transferred, row.total)
    is TransferMeta.Passed -> stringResource(R.string.transfer_meta_skipped, row.total)
}

/** The reference's `chipFor`: the state, in one word, in its own colour. */
@Composable
private fun transferChipText(state: TransferState): String = stringResource(
    when (state) {
        TransferState.SENDING -> R.string.transfer_chip_sending
        TransferState.RECEIVING -> R.string.transfer_chip_receiving
        TransferState.QUEUED -> R.string.transfer_chip_queued
        TransferState.PAUSED -> R.string.transfer_chip_paused
        TransferState.VERIFYING -> R.string.transfer_chip_verifying
        TransferState.DONE -> R.string.transfer_chip_done
        TransferState.FAILED -> R.string.transfer_chip_failed
        TransferState.CANCELLED -> R.string.transfer_chip_cancelled
        TransferState.SKIPPED -> R.string.transfer_chip_skipped
    },
)

private fun transferChipStyle(state: TransferState): ChipStyle = when (state) {
    TransferState.SENDING, TransferState.VERIFYING -> ChipStyle.ACCENT
    TransferState.RECEIVING -> ChipStyle.RECEIVE
    TransferState.PAUSED -> ChipStyle.WARN
    TransferState.DONE -> ChipStyle.OK
    TransferState.FAILED -> ChipStyle.ERROR
    TransferState.QUEUED, TransferState.CANCELLED, TransferState.SKIPPED -> ChipStyle.NEUTRAL
}

/** `.bar` and its variants, by state. */
private fun progressFlavourFor(state: TransferState): ProgressFlavour = when (state) {
    TransferState.RECEIVING -> ProgressFlavour.RECEIVE
    TransferState.PAUSED, TransferState.CANCELLED, TransferState.SKIPPED -> ProgressFlavour.PAUSED
    TransferState.DONE -> ProgressFlavour.DONE
    TransferState.FAILED -> ProgressFlavour.ERROR
    TransferState.SENDING, TransferState.QUEUED, TransferState.VERIFYING -> ProgressFlavour.ACTIVE
}

/**
 * Ending a session asks before it acts.
 *
 * The body says the two things a user needs: how much is still unfinished and what happens to
 * it, and that nothing already delivered is taken back. Dismissing — by either button, by the
 * back gesture or by tapping outside — changes nothing at all.
 */
@Composable
private fun EndSessionDialog(
    unfinishedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MorseDialog(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.transfer_end_title),
                style = MorseTextStyles.listTitle,
                color = MorseTheme.colors.textPrimary,
            )
            MorseBodyText(
                text = if (unfinishedCount > 0) {
                    pluralStringResource(
                        R.plurals.transfer_end_unfinished,
                        unfinishedCount,
                        unfinishedCount,
                    )
                } else {
                    stringResource(R.string.transfer_end_nothing_unfinished)
                },
            )
            MorseBodyText(text = stringResource(R.string.transfer_end_kept))
            MorseButton(
                text = stringResource(R.string.transfer_end_confirm),
                onClick = onConfirm,
                variant = MorseButtonVariant.DANGER,
                fillWidth = true,
            )
            MorseButton(
                text = stringResource(R.string.transfer_end_keep),
                onClick = onDismiss,
                variant = MorseButtonVariant.GHOST,
                fillWidth = true,
            )
        }
    }
}

/** One section of a view: which way it goes, what it is called, and what it says when empty. */
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
 * The width a transfer row needs before it can also draw the direction arrow.
 *
 * The screen's own padding, both leading glyphs, the four gaps between cells, a file name
 * worth reading, the longest status chip, and one or two 48 dp controls. Everything here is a
 * token except the name and the chip: the two are estimates of how much room text needs, and
 * they are the reason the arrow moves rather than the name or a control.
 */
private fun rowWidthNeeded(
    metrics: MorseMetrics,
    controls: Int,
): Dp = ScreenPadding * 2 +
    metrics.transferDirectionGlyph +
    metrics.fileIconSize +
    GlyphGap * 4 +
    NameBudget +
    ChipBudget +
    metrics.touchTarget * controls

/** The reference's `.li` gap of 12 px between everything in a row. */
private val GlyphGap = 12.dp

/** `.li{padding:12px 0}`. */
private val RowPaddingVertical = 12.dp

/** The gap between a row's name, its second line and its bar. */
private val RowInnerGap = 3.dp

/** `.tx-tools` sits its controls closer together than the row's own gap. */
private val ToolsGap = 2.dp

/** The screen's own horizontal padding (`.scr`), shared by the list and its headings. */
private val ScreenPadding = 16.dp

/** How much room a file name is worth before the arrow gives up its place. */
private val NameBudget = 96.dp

/** The longest status chip ("VERIFYING"), measured at the chip's own type. */
private val ChipBudget = 72.dp

/**
 * What separates one item from the next in a line.
 *
 * A middot, as the reference prints it, and a constant rather than a resource because it is
 * punctuation rather than a sentence.
 */
private const val CountSeparator = " · "
