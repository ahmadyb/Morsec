package app.morsecode.ui.broadcast

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.ChipStyle
import app.morsecode.core.design.component.FileKindIcon
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseChip
import app.morsecode.core.design.component.MorseIconButton
import app.morsecode.core.design.component.MorseWash
import app.morsecode.core.design.component.StatTile
import app.morsecode.core.design.component.TransferProgressBar
import app.morsecode.core.design.component.peerColour
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.Peer
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.rememberFeatureGate
import app.morsecode.ui.transfer.EndSessionDialog
import app.morsecode.ui.transfer.GlyphGap
import app.morsecode.ui.transfer.ScreenPadding
import app.morsecode.ui.transfer.TransferKindTagPrefix
import app.morsecode.ui.transfer.TransferScreenHeader
import app.morsecode.ui.transfer.TransferState
import app.morsecode.ui.transfer.progressFlavourFor
import app.morsecode.ui.transfer.transferChipStyle
import app.morsecode.ui.transfer.transferChipText

/**
 * The broadcast sender: one batch, fanned out to every chosen phone (master prompt §5
 * "Broadcast").
 *
 * The reference's `SC.bsender`, and the whole reason this screen exists is the nesting: a file
 * row says what the batch is doing with that file, and beneath it one row per phone says what
 * *that* phone is doing with it. Two rules follow from the master prompt and are visible here
 * rather than only in tests:
 *
 * - Every phone has its own delivery. Nothing is averaged: a phone that is held shows where it
 *   stopped, a phone that failed shows FAILED and offers its own retry, a phone that finished
 *   says so — and none of them changes what the row beside it says.
 * - The batch's totals are the batch's: the phones, the files each, and the bytes the fan-out
 *   has to carry (the batch once per phone), with the combined throughput counting only the
 *   deliveries that are actually moving.
 *
 * The bottom bar is the transfer bar, unchanged, because a broadcast is a transfer with more
 * than one receiver: Add files and Background are engine and service work and say so through
 * the app's own gate, the single whole-batch control pauses or resumes every delivery the
 * engine says may be held, and End asks before it stops anything.
 */
@Composable
public fun BroadcastSenderScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onSeeCompletion: () -> Unit,
    onOpenRecipient: (String) -> Unit,
    onEnded: () -> Unit,
    viewModel: BroadcastSenderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberFeatureGate()

    BroadcastSenderContent(
        state = state,
        onBack = onBack,
        onNavigate = onNavigate,
        onRetry = { row -> viewModel.retry(row.key.recipientId, row.key.fileId) },
        onToggleAll = viewModel::toggleAll,
        onAddFiles = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
        onBackground = { gate.run(FeatureArea.BACKGROUND_SERVICE) { } },
        onRequestEnd = viewModel::requestEnd,
        onConfirmEnd = {
            viewModel.confirmEnd()
            onEnded()
        },
        onDismissEnd = viewModel::dismissEnd,
        onSeeCompletion = onSeeCompletion,
        onOpenRecipient = onOpenRecipient,
    )
}

/** The sender as a function of the state it is handed. */
@Composable
internal fun BroadcastSenderContent(
    state: BroadcastSenderUiState,
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onRetry: (BroadcastDeliveryRow) -> Unit,
    onToggleAll: () -> Unit,
    onAddFiles: () -> Unit,
    onBackground: () -> Unit,
    onRequestEnd: () -> Unit,
    onConfirmEnd: () -> Unit,
    onDismissEnd: () -> Unit,
    onSeeCompletion: () -> Unit,
    onOpenRecipient: (String) -> Unit,
) {
    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        TransferScreenHeader(
            title = stringResource(R.string.broadcast_sender_title),
            backDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(BroadcastListTag),
            contentPadding = PaddingValues(bottom = 10.dp),
        ) {
            items(state.files, key = { it.file.id }) { file ->
                BroadcastFileBlock(
                    row = file,
                    onRetry = onRetry,
                    onOpenRecipient = onOpenRecipient,
                )
            }

            item(key = "summary") {
                BroadcastSummaryCard(state = state, onSeeCompletion = onSeeCompletion)
            }
        }

        BroadcastActionBar(
            allAction = state.allAction,
            onAddFiles = onAddFiles,
            onToggleAll = onToggleAll,
            onBackground = onBackground,
            onRequestEnd = onRequestEnd,
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
 * One file of the batch, and beneath it one progress row per phone.
 *
 * The file row speaks for the batch — what the file is, how big it is, how many phones have it
 * and what that adds up to — and each nested row speaks for one delivery, so a phone held at
 * 83 MB says so without any percentage being averaged across phones.
 */
@Composable
private fun BroadcastFileBlock(
    row: BroadcastFileRow,
    onRetry: (BroadcastDeliveryRow) -> Unit,
    onOpenRecipient: (String) -> Unit,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GlyphGap),
        ) {
            FileKindIcon(
                kind = row.file.kind,
                size = metrics.fileIconSize,
                modifier = Modifier.testTag(TransferKindTagPrefix + row.file.kind.id),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = row.file.fileName,
                    style = MorseTextStyles.listTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.broadcast_file_meta,
                        row.size,
                        pluralStringResource(
                            R.plurals.broadcast_file_deliveries,
                            row.deliveredPhones,
                            row.deliveredPhones,
                            row.totalPhones,
                        ),
                    ),
                    style = MorseTextStyles.meta,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            MorseChip(
                text = broadcastFileChipText(row.state),
                style = broadcastFileChipStyle(row.state),
            )
        }

        row.recipients.forEach { delivery ->
            BroadcastRecipientRow(
                row = delivery,
                onRetry = { onRetry(delivery) },
                onOpenRecipient = { onOpenRecipient(delivery.recipient.peerId) },
            )
        }
    }
}

/**
 * One phone's row under a file.
 *
 * The avatar, the name, the bar and the percentage are this delivery's own, and the chip says
 * which state that delivery is in — so two phones beside each other can read 33%, 100% and
 * "held" without either of them borrowing the other's numbers. Tapping the row opens that
 * phone's receiving screen, which is how a reviewer sees an individual receiver's view while
 * the batch is fanning out. The only control is the retry a failed delivery permits, per file
 * and per phone: the master prompt's "per-recipient retry where possible".
 */
@Composable
private fun BroadcastRecipientRow(
    row: BroadcastDeliveryRow,
    onRetry: () -> Unit,
    onOpenRecipient: () -> Unit,
) {
    val colors = MorseTheme.colors
    val metrics = MorseTheme.metrics
    val openLabel = stringResource(R.string.broadcast_open_recipient)
    val percentText = if (row.percent.isEmpty()) {
        stringResource(R.string.broadcast_percent_none)
    } else {
        row.percent
    }
    val progressDescription = stringResource(
        R.string.broadcast_recipient_progress,
        row.recipient.displayName,
        row.transferred,
        row.total,
        percentText,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = openLabel,
                onClick = onOpenRecipient,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RecipientAvatar(peer = row.recipient)

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = row.recipient.displayName,
                style = MorseTextStyles.meta,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TransferProgressBar(
                fraction = row.fraction,
                flavour = progressFlavourFor(row.state),
                modifier = Modifier.semantics {
                    contentDescription = progressDescription
                    progressBarRangeInfo = ProgressBarRangeInfo(row.fraction, 0f..1f)
                },
            )
        }

        Text(
            text = percentText,
            style = MorseTextStyles.monospacedMeta,
            color = colors.textTertiary,
            maxLines = 1,
            modifier = Modifier.width(PercentWidth),
        )

        MorseChip(
            text = transferChipText(row.state),
            style = transferChipStyle(row.state),
        )

        if (row.actions.retry) {
            MorseIconButton(
                iconRes = MorseIcons.refresh,
                contentDescription = stringResource(
                    R.string.broadcast_retry_delivery,
                    row.fileName,
                    row.recipient.displayName,
                ),
                onClick = onRetry,
                tint = colors.textSecondary,
                size = metrics.iconButtonSizeSmall,
                glyph = metrics.iconButtonGlyphSmall,
            )
        }
    }
}

/**
 * The phone's initial in its own colour, at the size the reference draws it inside a file.
 *
 * Smaller than the app's standard avatar on purpose: this row belongs to the file above it,
 * and an avatar the size of the file's own kind icon would read as a second file.
 */
@Composable
private fun RecipientAvatar(peer: Peer) {
    val metrics = MorseTheme.metrics
    Box(
        modifier = Modifier
            .size(metrics.broadcastRecipientAvatar)
            .clip(CircleShape)
            .background(peerColour(peer.colorSeed))
            .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = peer.letter.toString(),
            color = Color(0xFF0B0B0B),
            fontSize = metrics.broadcastRecipientAvatarText,
            fontWeight = FontWeight.W700,
            maxLines = 1,
        )
    }
}

/**
 * The batch summary: what the fan-out is doing, how its deliveries are split, the combined
 * throughput while anything is moving, and the three numbers the reference draws under it.
 *
 * The counts come from the deliveries rather than from a stored figure, so the card cannot say
 * "0 failed" while a phone beside it says FAILED, and the throughput is printed only when a
 * delivery is actually moving — the reference prints its own sample figure otherwise, which
 * would be this card stating a speed nothing is producing.
 */
@Composable
private fun BroadcastSummaryCard(
    state: BroadcastSenderUiState,
    onSeeCompletion: () -> Unit,
) {
    val colors = MorseTheme.colors
    MorseWash(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (state.complete) {
                    stringResource(R.string.transfer_summary_complete)
                } else {
                    pluralStringResource(
                        R.plurals.broadcast_summary_to_phones,
                        state.recipientCount,
                        state.recipientCount,
                    )
                },
                style = MorseTextStyles.listTitle,
                color = if (state.complete) colors.ok else colors.textPrimary,
            )
            Text(
                text = BroadcastCountsText(state),
                style = MorseTextStyles.meta,
                color = colors.textTertiary,
            )
            if (state.hasThroughput) {
                Text(
                    text = stringResource(R.string.broadcast_summary_throughput, state.combinedThroughput),
                    style = MorseTextStyles.meta,
                    color = colors.textTertiary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile(
                    value = state.tiles.phones,
                    label = stringResource(R.string.broadcast_tile_phones),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.tiles.filesEach,
                    label = stringResource(R.string.broadcast_tile_files_each),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    value = state.tiles.toSend,
                    label = stringResource(R.string.broadcast_tile_to_send),
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.complete) {
                MorseButton(
                    text = stringResource(R.string.broadcast_see_completion),
                    onClick = onSeeCompletion,
                    fillWidth = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * The deliveries, counted in words: what is moving and what is waiting while the batch runs,
 * and what it added up to once nothing is pending.
 *
 * Nothing is printed for a count of zero while the batch is running — "0 failed" is not news —
 * but a finished batch says its failures, skips and cancellations out loud, because those are
 * exactly what a user reads the summary for.
 */
@Composable
private fun BroadcastCountsText(state: BroadcastSenderUiState): String {
    val result = state.result
    val pieces = buildList {
        if (state.complete) {
            add(
                pluralStringResource(
                    R.plurals.broadcast_summary_delivered,
                    result.delivered,
                    result.delivered,
                ),
            )
        } else {
            if (result.active > 0) {
                add(pluralStringResource(R.plurals.transfer_summary_sending, result.active, result.active))
            }
            if (result.queued > 0) {
                add(pluralStringResource(R.plurals.transfer_summary_queued, result.queued, result.queued))
            }
            if (result.paused > 0) {
                add(pluralStringResource(R.plurals.transfer_summary_paused, result.paused, result.paused))
            }
            if (result.verifying > 0) {
                add(pluralStringResource(R.plurals.transfer_summary_verifying, result.verifying, result.verifying))
            }
        }
        if (result.failed > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_failed, result.failed, result.failed))
        }
        if (result.mismatched > 0) {
            add(
                pluralStringResource(
                    R.plurals.transfer_summary_checksum_differs,
                    result.mismatched,
                    result.mismatched,
                ),
            )
        }
        if (result.skipped > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_skipped, result.skipped, result.skipped))
        }
        if (result.cancelled > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_cancelled, result.cancelled, result.cancelled))
        }
    }
    return pieces.joinToString(COUNT_SEPARATOR)
}

/**
 * The per-file chip, in the batch's vocabulary.
 *
 * A file every phone has verified says so; one every phone has received but not yet checked
 * says DONE; anything that stopped everywhere short of arriving is INCOMPLETE — a word the
 * reference never needs, because its sample never finishes a file badly, and the only honest
 * one for a file that is over and did not arrive.
 */
@Composable
private fun broadcastFileChipText(state: BroadcastGroupState): String = when (state) {
    BroadcastGroupState.QUEUED -> transferChipText(TransferState.QUEUED)
    BroadcastGroupState.IN_PROGRESS -> transferChipText(TransferState.SENDING)
    BroadcastGroupState.HELD -> transferChipText(TransferState.PAUSED)
    BroadcastGroupState.COMPLETE -> transferChipText(TransferState.DONE)
    BroadcastGroupState.VERIFIED -> stringResource(R.string.broadcast_chip_verified)
    BroadcastGroupState.INCOMPLETE -> stringResource(R.string.broadcast_chip_incomplete)
}

private fun broadcastFileChipStyle(state: BroadcastGroupState): ChipStyle = when (state) {
    BroadcastGroupState.QUEUED -> ChipStyle.NEUTRAL
    BroadcastGroupState.IN_PROGRESS -> ChipStyle.ACCENT
    BroadcastGroupState.HELD -> ChipStyle.WARN
    BroadcastGroupState.COMPLETE, BroadcastGroupState.VERIFIED -> ChipStyle.OK
    BroadcastGroupState.INCOMPLETE -> ChipStyle.NEUTRAL
}

/** The percentage column's width, as the reference reserves it. */
private val PercentWidth = 38.dp

/** What separates one count from the next in a line: the reference's middot. */
private const val COUNT_SEPARATOR = " · "
