package app.morsecode.ui.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.ChipStyle
import app.morsecode.core.design.component.MorseChip
import app.morsecode.core.design.component.MorseEmptyState
import app.morsecode.core.design.component.MorseWash
import app.morsecode.core.design.component.PeerAvatar
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.component.StatTile
import app.morsecode.core.design.component.TransferProgressBar
import app.morsecode.core.design.component.ProgressFlavour
import app.morsecode.core.design.icon.MorseIcons
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.FeatureArea
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.rememberFeatureGate
import app.morsecode.ui.transfer.ScreenPadding
import app.morsecode.ui.transfer.TransferPeerCard
import app.morsecode.ui.transfer.TransferRowView
import app.morsecode.ui.transfer.TransferScreenHeader

/**
 * The two completion screens: what a finished broadcast adds up to.
 *
 * They are the screens where a broadcast is most tempted to lie, so both of them take their
 * words from the deliveries rather than from the batch:
 *
 * - The sender's screen says "all phones verified" only when every expected delivery arrived
 *   and every checksum agreed. One failed, skipped, cancelled or mismatched delivery turns
 *   that line into a count of what actually verified, and the failed and skipped counts are
 *   printed rather than the reference's zeros.
 * - The receiver's screen reports one phone's batch and nothing else, because that is all it
 *   knows.
 *
 * Neither screen prints an average speed: after a broadcast ends nothing is moving, and a
 * stored figure of a transfer that is over would be a number no delivery in this app is
 * producing. The reference prints its sample's figure; this app says what the deliveries say.
 */
@Composable
public fun BroadcastSentScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onEnded: () -> Unit,
    viewModel: BroadcastSentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberFeatureGate()

    BroadcastSentContent(
        state = state,
        onBack = onBack,
        onNavigate = onNavigate,
        onClear = viewModel::clear,
        onAddFiles = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
        onBackground = { gate.run(FeatureArea.BACKGROUND_SERVICE) { } },
        onEnded = onEnded,
    )
}

/** The sender's completion as a function of the state it is handed. */
@Composable
internal fun BroadcastSentContent(
    state: BroadcastSentUiState,
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onClear: () -> Unit,
    onAddFiles: () -> Unit,
    onBackground: () -> Unit,
    onEnded: () -> Unit,
) {
    val colors = MorseTheme.colors

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        TransferScreenHeader(
            title = stringResource(R.string.broadcast_complete_title),
            backDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 10.dp),
        ) {
            item(key = "banner") {
                MorseWash(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // The check is drawn only when the batch earned it; a less-than-clean
                        // broadcast gets the same card without the tick, and its sub-line says
                        // what went wrong instead.
                        if (state.result.fullySuccessful) {
                            Icon(
                                painter = painterResource(id = MorseIcons.check),
                                contentDescription = null,
                                tint = colors.ok,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.broadcast_sender_complete),
                                style = MorseTextStyles.listTitle,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = verifiedText(state),
                                style = MorseTextStyles.meta,
                                color = colors.textTertiary,
                            )
                        }
                        if (state.result.fullySuccessful) {
                            MorseChip(
                                text = stringResource(
                                    R.string.broadcast_peer_count_chip,
                                    state.recipientCount,
                                ),
                                style = ChipStyle.ACCENT,
                            )
                        }
                    }
                }
            }

            item(key = "section") {
                SectionHeader(
                    text = stringResource(R.string.broadcast_delivered_to),
                    actionLabel = if (state.recipients.isEmpty()) null else stringResource(R.string.action_clear),
                    onAction = if (state.recipients.isEmpty()) null else onClear,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }

            if (state.recipients.isEmpty()) {
                item(key = "empty") {
                    MorseEmptyState(
                        title = stringResource(R.string.broadcast_nothing_delivered),
                        message = stringResource(R.string.broadcast_nothing_delivered_body),
                        iconRes = MorseIcons.check,
                    )
                }
            } else {
                items(state.recipients, key = { it.peer.peerId }) { row ->
                    RecipientResultRow(row = row)
                }
            }

            item(key = "summary") {
                MorseWash(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.transfer_summary_complete),
                            style = MorseTextStyles.listTitle,
                            color = colors.ok,
                        )
                        Text(
                            text = sentCountsText(state),
                            style = MorseTextStyles.meta,
                            color = colors.textTertiary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile(
                                value = state.recipientCount.toString(),
                                label = stringResource(R.string.broadcast_tile_phones),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                value = state.filesEach.toString(),
                                label = stringResource(R.string.broadcast_tile_files_each),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                value = state.deliveredBytes,
                                label = stringResource(R.string.broadcast_tile_delivered),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        BroadcastActionBar(
            allAction = state.allAction,
            onAddFiles = onAddFiles,
            onToggleAll = {},
            onBackground = onBackground,
            onRequestEnd = onEnded,
        )
    }
}

/**
 * The verification line: the reference's "All N phones verified" when — and only when — that
 * is true, and a count of what verified when it is not.
 */
@Composable
private fun verifiedText(state: BroadcastSentUiState): String = if (state.result.fullySuccessful) {
    pluralStringResource(
        R.plurals.broadcast_all_verified,
        state.recipientCount,
        state.recipientCount,
    )
} else {
    pluralStringResource(
        R.plurals.broadcast_partial_verified,
        state.result.delivered,
        state.result.delivered,
        state.result.expected,
    )
}

/** One phone's row: what it got, and the checksum answer the batch recorded for it. */
@Composable
private fun RecipientResultRow(row: BroadcastRecipientResultRow) {
    val colors = MorseTheme.colors
    val deliveryText = pluralStringResource(
        R.plurals.broadcast_recipient_result,
        row.delivered,
        row.delivered,
        row.expected,
        row.bytes,
    )
    // "verified" is a claim about the checksums, so a phone whose batch did not come out clean
    // gets the counts and the problem instead of the word.
    val resultText = if (row.clean) {
        stringResource(R.string.broadcast_recipient_verified, deliveryText)
    } else {
        deliveryText + COMPLETE_COUNT_SEPARATOR + recipientProblemText(row)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PeerAvatar(letter = row.peer.letter, colorSeed = row.peer.colorSeed)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = row.peer.displayName,
                style = MorseTextStyles.listTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = resultText,
                style = MorseTextStyles.meta,
                color = colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TransferProgressBar(
                fraction = if (row.expected == 0) 0f else row.delivered.toFloat() / row.expected.toFloat(),
                flavour = if (row.clean) ProgressFlavour.DONE else ProgressFlavour.ERROR,
            )
        }
        if (row.clean) {
            Icon(
                painter = painterResource(id = MorseIcons.check),
                contentDescription = null,
                tint = colors.ok,
                modifier = Modifier.size(16.dp),
            )
        } else {
            MorseChip(text = stateChipText(row), style = ChipStyle.ERROR)
        }
    }
}

/** What went wrong for one phone, in this app's own words. */
@Composable
private fun recipientProblemText(row: BroadcastRecipientResultRow): String {
    val pieces = buildList {
        if (row.failed > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_failed, row.failed, row.failed))
        }
        if (row.mismatched > 0) {
            add(
                pluralStringResource(
                    R.plurals.transfer_summary_checksum_differs,
                    row.mismatched,
                    row.mismatched,
                ),
            )
        }
        if (row.skipped > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_skipped, row.skipped, row.skipped))
        }
        if (row.cancelled > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_cancelled, row.cancelled, row.cancelled))
        }
    }
    return pieces.joinToString(COMPLETE_COUNT_SEPARATOR)
}

/** The chip on a phone whose broadcast did not come out clean. */
@Composable
private fun stateChipText(row: BroadcastRecipientResultRow): String = when {
    row.failed > 0 || row.mismatched > 0 -> stringResource(R.string.transfer_chip_failed)
    row.cancelled > 0 -> stringResource(R.string.transfer_chip_cancelled)
    else -> stringResource(R.string.transfer_chip_skipped)
}

/** The batch, counted: the deliveries, and the ones that did not come out clean. */
@Composable
private fun sentCountsText(state: BroadcastSentUiState): String {
    val result = state.result
    val pieces = buildList {
        add(
            pluralStringResource(
                R.plurals.broadcast_summary_delivered,
                result.delivered,
                result.delivered,
            ),
        )
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
    return pieces.joinToString(COMPLETE_COUNT_SEPARATOR)
}

/**
 * One phone's completion: what arrived, what the checksums said, and where it went.
 *
 * The rows are the shared transfer rows read as incoming, with no controls: a finished file
 * has nothing left to pause, resume, cancel or retry, which is the master prompt's rule stated
 * in one place instead of nine.
 */
@Composable
public fun BroadcastReceivedScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onOpenFolder: () -> Unit,
    onEnded: () -> Unit,
    viewModel: BroadcastReceivedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberFeatureGate()
    val openFolder = { gate.run(FeatureArea.TRANSFER_ENGINE) { onOpenFolder() } }

    BroadcastReceivedContent(
        state = state,
        onBack = onBack,
        onNavigate = onNavigate,
        onOpenFolder = openFolder,
        onAddFiles = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
        onBackground = { gate.run(FeatureArea.BACKGROUND_SERVICE) { } },
        onEnded = onEnded,
    )
}

/** The receiver's completion as a function of the state it is handed. */
@Composable
internal fun BroadcastReceivedContent(
    state: BroadcastReceivedUiState,
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onOpenFolder: () -> Unit,
    onAddFiles: () -> Unit,
    onBackground: () -> Unit,
    onEnded: () -> Unit,
) {
    val colors = MorseTheme.colors

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        TransferScreenHeader(
            title = stringResource(R.string.broadcast_complete_title),
            backDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 10.dp),
        ) {
            item(key = "source") {
                TransferPeerCard(
                    peer = state.sender.asTransferPeer(),
                    subtitle = stringResource(
                        R.string.broadcast_peer_source,
                        stringResource(deviceWordRes(state.sender)),
                        state.sender.transport.badge,
                    ),
                    badge = stringResource(R.string.broadcast_from_badge),
                )
            }

            item(key = "section") {
                SectionHeader(
                    text = stringResource(R.string.broadcast_section_received),
                    actionLabel = stringResource(R.string.broadcast_open_folder),
                    onAction = onOpenFolder,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }

            items(state.rows, key = { it.item.id }) { row ->
                TransferRowView(
                    row = row,
                    onPause = {},
                    onResume = {},
                    onCancel = {},
                    onRetry = {},
                    showControls = false,
                )
            }

            item(key = "summary") {
                MorseWash(modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.transfer_summary_complete),
                            style = MorseTextStyles.listTitle,
                            color = colors.ok,
                        )
                        Text(
                            text = receivedCountsText(state),
                            style = MorseTextStyles.meta,
                            color = colors.textTertiary,
                        )
                        Text(
                            text = stringResource(R.string.broadcast_destination),
                            style = MorseTextStyles.meta,
                            color = colors.textTertiary,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile(
                                value = state.fileCount.toString(),
                                label = stringResource(R.string.broadcast_tile_files),
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                value = state.deliveredBytes,
                                label = stringResource(R.string.broadcast_tile_received),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        BroadcastActionBar(
            allAction = state.allAction,
            onAddFiles = onAddFiles,
            onToggleAll = {},
            onBackground = onBackground,
            onRequestEnd = onEnded,
        )
    }
}

/** This phone's outcome, counted: how much arrived, and what did not. */
@Composable
private fun receivedCountsText(state: BroadcastReceivedUiState): String {
    val result = state.result
    val pieces = buildList {
        add(
            pluralStringResource(
                R.plurals.transfer_summary_received,
                result.delivered,
                result.delivered,
            ),
        )
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
    return pieces.joinToString(COMPLETE_COUNT_SEPARATOR)
}

/** The reference's middot between one count and the next. */
private const val COMPLETE_COUNT_SEPARATOR = " · "
