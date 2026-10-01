package app.morsecode.ui.broadcast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morsecode.R
import app.morsecode.core.design.component.MorseButton
import app.morsecode.core.design.component.MorseButtonVariant
import app.morsecode.core.design.component.MorseWash
import app.morsecode.core.design.component.SectionHeader
import app.morsecode.core.design.theme.MorseTextStyles
import app.morsecode.core.design.theme.MorseTheme
import app.morsecode.core.model.DeviceKind
import app.morsecode.core.model.FeatureArea
import app.morsecode.core.model.Peer
import app.morsecode.navigation.MorseDestination
import app.morsecode.ui.common.MorseTabScaffold
import app.morsecode.ui.common.rememberFeatureGate
import app.morsecode.ui.transfer.EndSessionDialog
import app.morsecode.ui.transfer.ScreenPadding
import app.morsecode.ui.transfer.TransferPeerCard
import app.morsecode.ui.transfer.TransferRowView
import app.morsecode.ui.transfer.TransferScreenHeader

/**
 * The broadcast receiver: the one screen that draws any phone's share of a batch (master
 * prompt §5 "Broadcast").
 *
 * The reference draws this screen three times, once inside each phone frame, because three
 * frames is how a mockup shows three different phones. A real app has one screen and a route
 * argument, so this is that screen: it is handed one phone's deliveries and draws them, and
 * which phone that is comes from the route. Nothing here knows how many other phones there
 * are, which is exactly what makes one phone's trouble unable to appear on another phone's
 * screen.
 *
 * The rows are the duplex views' own shared row, without controls: the batch's single Pause
 * all / Resume all is where a receiver acts on all of it, and per-file controls on a receiver
 * would be a second way to do the same thing the bar already does.
 */
@Composable
public fun BroadcastReceiverScreen(
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onSeeCompletion: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onEnded: (String) -> Unit,
    viewModel: BroadcastReceiverViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gate = rememberFeatureGate()

    BroadcastReceiverContent(
        state = state,
        onBack = onBack,
        onNavigate = onNavigate,
        onToggleAll = viewModel::toggleAll,
        onAddFiles = { gate.run(FeatureArea.TRANSFER_ENGINE) { } },
        onBackground = { gate.run(FeatureArea.BACKGROUND_SERVICE) { } },
        onRequestEnd = viewModel::requestEnd,
        onConfirmEnd = {
            val recipientId = viewModel.state.value.recipient.peerId
            viewModel.confirmEnd()
            onEnded(recipientId)
        },
        onDismissEnd = viewModel::dismissEnd,
        onSeeCompletion = { onSeeCompletion(state.recipient.peerId) },
        onOpenFolder = { gate.run(FeatureArea.TRANSFER_ENGINE) { onOpenFolder() } },
    )
}

/** The receiver as a function of the state it is handed. */
@Composable
internal fun BroadcastReceiverContent(
    state: BroadcastReceiverUiState,
    onBack: () -> Unit,
    onNavigate: (MorseDestination) -> Unit,
    onToggleAll: () -> Unit,
    onAddFiles: () -> Unit,
    onBackground: () -> Unit,
    onRequestEnd: () -> Unit,
    onConfirmEnd: () -> Unit,
    onDismissEnd: () -> Unit,
    onSeeCompletion: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    val colors = MorseTheme.colors

    MorseTabScaffold(selected = MorseDestination.FILES, onNavigate = onNavigate) {
        TransferScreenHeader(
            title = stringResource(R.string.broadcast_receiver_title),
            backDescription = stringResource(R.string.action_back),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(BroadcastListTag),
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
                // The reference's receiver heading carried one action — Pause — and this app's
                // one control lives in the bar instead. There is deliberately no clear here:
                // the reference offers no way to dismiss a receipt from this screen, so the
                // heading states the section and nothing else.
                SectionHeader(
                    text = pluralStringResource(
                        R.plurals.broadcast_queue_files,
                        state.rows.size,
                        state.rows.size,
                        state.batchBytes,
                    ),
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
                            text = stringResource(
                                R.string.broadcast_receiver_from,
                                state.sender.displayName,
                            ),
                            style = MorseTextStyles.listTitle,
                            color = colors.textPrimary,
                        )
                        Text(
                            text = receiverCountsText(state),
                            style = MorseTextStyles.meta,
                            color = colors.textTertiary,
                        )
                        if (state.hasAverageSpeed) {
                            Text(
                                text = stringResource(R.string.transfer_summary_average, state.averageSpeed),
                                style = MorseTextStyles.meta,
                                color = colors.textTertiary,
                            )
                        }
                        if (state.complete) {
                            Text(
                                text = stringResource(R.string.transfer_summary_complete),
                                style = MorseTextStyles.listTitle,
                                color = colors.ok,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            Text(
                                text = stringResource(R.string.broadcast_destination),
                                style = MorseTextStyles.meta,
                                color = colors.textTertiary,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MorseButton(
                                    text = stringResource(R.string.broadcast_open_folder),
                                    onClick = onOpenFolder,
                                    variant = MorseButtonVariant.WASH,
                                    fillWidth = true,
                                    modifier = Modifier.weight(1f),
                                )
                                MorseButton(
                                    text = stringResource(R.string.broadcast_see_completion),
                                    onClick = onSeeCompletion,
                                    fillWidth = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
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
 * This phone's deliveries in words: what is arriving, what is still waiting, and what stopped
 * badly — with the checksum answer for a file that arrived and did not match.
 */
@Composable
private fun receiverCountsText(state: BroadcastReceiverUiState): String {
    val result = state.result
    val pieces = buildList {
        if (result.active > 0) {
            add(pluralStringResource(R.plurals.transfer_summary_receiving, result.active, result.active))
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
        if (result.complete) {
            add(
                pluralStringResource(
                    R.plurals.transfer_summary_received,
                    result.delivered,
                    result.delivered,
                ),
            )
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
    return pieces.joinToString(RECEIVER_COUNT_SEPARATOR)
}

/** What a device is called on the source card, in one word. */
internal fun deviceWordRes(peer: Peer): Int = when (peer.deviceKind) {
    DeviceKind.TABLET -> R.string.broadcast_device_tablet
    DeviceKind.PHONE -> R.string.broadcast_device_phone
    else -> R.string.broadcast_device_other
}

/** The reference's middot between one count and the next. */
private const val RECEIVER_COUNT_SEPARATOR = " · "
